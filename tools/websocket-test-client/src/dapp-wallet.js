import { address, integer, validateDraft } from './dapp-intent.js';

/** Wallet owns the key. This controller never creates keys, signs locally or stores signatures/JWTs. */
export class DappWallet {
  constructor({ api, provider, storage, locks, changed = () => {}, now = () => Math.floor(Date.now()/1000), uuid = () => crypto.randomUUID() }) {
    Object.assign(this,{api,provider,storage,locks,changed,now,uuid});
    this.epoch=0; this.user=null; this.active=false; this.busy=false;
    this.invalidate=()=>{this.epoch++;this.owner=null;this.chain=null;this.draft=null;this.approved=null;this.changed();};
    for(const event of ['accountsChanged','chainChanged','disconnect'])provider?.on?.(event,this.invalidate);
  }
  context(user,active) {
    if(String(this.user?.id)!==String(user?.id)||this.active!==active){this.epoch++;this.user=user;this.active=active;this.cfg=null;this.owner=null;this.chain=null;this.draft=null;this.approved=null;this.changed();}
  }
  ticket(){const e=this.epoch;if(!this.user||!this.active)throw new Error('로그인 후 B-v2 화면에서 진행하세요.');return()=>{if(e!==this.epoch||!this.active||!this.user)throw new Error('사용자·지갑·네트워크 또는 화면이 변경되어 요청을 무효화했습니다.');};}
  async run(task){if(this.busy)throw new Error('진행 중인 지갑 요청을 먼저 완료하세요.');this.busy=true;this.changed();try{return await task();}finally{this.busy=false;this.changed();}}
  async init(){const check=this.ticket();const {body}=await this.api.dappConfig();check();if(body?.enabled!==true||body.maxUserTtlSeconds!=='120')throw new Error('B-v2가 비활성 상태이거나 지원하지 않는 설정입니다.');integer(body.chainId);address(body.controller);address(body.relayer);this.cfg=body;this.changed();return body;}
  async wallet(interactive=false){if(!this.provider?.request)throw new Error('EIP-1193 브라우저 지갑이 필요합니다. 개인키·시드 문구를 입력하지 마세요.');const check=this.ticket();const accounts=await this.provider.request({method:interactive?'eth_requestAccounts':'eth_accounts'});check();const chain=await this.provider.request({method:'eth_chainId'});check();const owner=address(accounts?.[0]);if(typeof chain!=='string'||!/^0x[0-9a-f]+$/i.test(chain)||BigInt(chain)!==integer(this.cfg?.chainId))throw new Error('지갑 네트워크가 B-v2 chainId와 다릅니다. 직접 올바른 네트워크를 선택하세요.');return {owner,chain};}
  async connect(){return this.run(async()=>{const w=await this.wallet(true);this.owner=w.owner;this.chain=w.chain;this.changed();return w;});}
  async checkWallet(owner){const w=await this.wallet();if(w.owner!==owner)throw new Error('지갑 계정이 변경되었습니다.');}
  async link(){return this.run(async()=>{const check=this.ticket(),{owner}=await this.wallet();const {body:c}=await this.api.dappChallenge(owner);check();if(address(c.wallet)!==owner||!c.message||Date.parse(c.expiresAt)<=this.now()*1000)throw new Error('소유 증명 challenge가 유효하지 않습니다.');const message='0x'+Array.from(new TextEncoder().encode(c.message),b=>b.toString(16).padStart(2,'0')).join('');const signature=await this.provider.request({method:'personal_sign',params:[message,owner]});check();await this.checkWallet(owner);check();if(Date.parse(c.expiresAt)<=this.now()*1000)throw new Error('소유 증명 기한이 만료됐습니다. 다시 확인하세요.');const result=await this.api.dappLink(c.id,signature);check();if(address(result.body.wallet)!==owner)throw new Error('연결된 지갑이 다릅니다.');this.owner=owner;return result;});}
  storageKey(){if(!this.cfg||!this.user)throw new Error('B-v2 설정이 필요합니다.');return `tms-bv2:${this.user.id}:${this.cfg.chainId}:${address(this.cfg.controller)}`;}
  pending(){const raw=this.storage.getItem(this.storageKey());if(!raw)return null;try{return JSON.parse(raw);}catch{throw new Error('이전 요청 기록을 읽을 수 없어 새 전송을 차단했습니다.');}}
  save(value){this.storage.setItem(this.storageKey(),JSON.stringify(value));}
  async lock(task){if(!this.locks?.request)throw new Error('중복 서명 방지를 위한 Web Locks 지원 브라우저(HTTPS/localhost)가 필요합니다.');return this.locks.request(this.storageKey(),{ifAvailable:true},lock=>{if(!lock)throw new Error('다른 탭에서 승인 중입니다.');return task();});}
  async prepare(approved){return this.run(()=>this.lock(async()=>{const check=this.ticket(),{owner}=await this.wallet();const prior=this.pending();
    if(prior?.phase==='DRAFT'&&prior.deadline>this.now()){
      if(this.draft?.id===prior.draftId&&JSON.stringify(this.approved?.request)===JSON.stringify(approved.request)){
        validateDraft(this.draft,this.cfg,owner,this.draft.typedData.message.nonce,approved,this.now());return this.draft;
      }
      throw new Error('서명 전 draft가 아직 유효합니다. operation은 생성되지 않았습니다. 원래 조건을 확인하거나 '+new Date(prior.deadline*1000).toISOString()+' 만료 후 새 조건을 준비하세요.');
    }
    const replay=prior?.phase==='DRAFT_REQUESTED'&&prior.owner===owner&&JSON.stringify(prior.request)===JSON.stringify(approved.request);if(prior&&!replay&&!(prior.phase==='DRAFT'&&prior.deadline<=this.now()))throw new Error('이전 제출 요청을 먼저 조회하세요. 미확정 전송은 새 주문으로 대체할 수 없습니다.');const linked=await this.api.dappWallet();check();if(address(linked.body.wallet)!==owner)throw new Error('현재 사용자에게 연결된 지갑과 다릅니다.');const n=await this.api.dappNonce();check();if(address(n.body.owner)!==owner)throw new Error('nonce 소유자가 다릅니다.');const key=replay?prior.key:this.uuid();
    // Record BEFORE draft POST: a lost response must not silently create another operation/key.
    this.save({phase:'DRAFT_REQUESTED',key,owner,request:approved.request});
    const response=await this.api.dappDraft(approved.request,key);check();await this.checkWallet(owner);check();const td=validateDraft(response.body,this.cfg,owner,n.body.nonce,approved,this.now());
    this.draft={...response.body,typedData:td};this.approved=structuredClone(approved);this.owner=owner;
    this.save({phase:'DRAFT',key,draftId:this.draft.id,operationId:this.draft.operationId,positionId:this.draft.positionId,deadline:Number(td.message.deadline)});this.changed();return this.draft;
  }));}
  async sign(){return this.run(()=>this.lock(async()=>{const check=this.ticket(),p=this.pending();if(!this.draft||!this.approved||p?.phase!=='DRAFT'||p.draftId!==this.draft.id)throw new Error('승인할 draft가 없거나 이전 요청의 결과 확인이 필요합니다.');const {owner}=await this.wallet();check();validateDraft(this.draft,this.cfg,owner,this.draft.typedData.message.nonce,this.approved,this.now());const signature=await this.provider.request({method:'eth_signTypedData_v4',params:[owner,JSON.stringify(this.draft.typedData)]});check();await this.checkWallet(owner);check();validateDraft(this.draft,this.cfg,owner,this.draft.typedData.message.nonce,this.approved,this.now());
    this.save({...p,phase:'SUBMITTING'});const result=await this.api.dappSubmit(p.draftId,signature,p.key);check();if(result.body.id!==p.operationId||result.body.positionId!==p.positionId)throw new Error('접수 응답 식별자가 다릅니다. 이전 요청 조회가 필요합니다.');this.save({...p,phase:'ACCEPTED'});this.draft=null;this.approved=null;return result.body;
  }));}
  async resolve(){const check=this.ticket(),p=this.pending();if(!p?.operationId)throw new Error('응답이 유실된 draft는 동일 조건으로 draft 확인 버튼을 직접 눌러 같은 키로 확인하세요.');if(p.phase==='DRAFT')throw new Error('아직 지갑 서명 제출 전입니다. 화면을 떠난 draft는 기한 만료 후 새 조건을 확인하세요.');const result=await this.api.futuresOperation(p.operationId);check();if(result.body?.id!==p.operationId||result.body?.positionId!==p.positionId)throw new Error('조회한 거래 식별자가 다릅니다.');return result.body;}
  async acknowledge(){return this.run(()=>this.lock(async()=>{const check=this.ticket();const op=await this.resolve();check();if(!((op.state==='CONFIRMED'&&op.settlementConfirmed===true)||op.state==='REVERTED'||op.state==='REJECTED'))throw new Error('미확정·검토 필요 상태는 해제할 수 없습니다.');this.storage.removeItem(this.storageKey());this.draft=null;this.approved=null;return op;}));}
}

export function walletError(error){if(error?.code===4001||error?.code==='ACTION_REJECTED')return '사용자가 지갑 서명을 거절했습니다. 자동 재서명하지 않습니다.';if(error?.status)return `서버 요청 거절 (${error.status} · ${error.body?.code||'REQUEST_FAILED'}). 기존 요청은 조회로 확인하세요.`;return error?.message||'지갑 요청 실패. 자동 재시도하지 않습니다.';}
