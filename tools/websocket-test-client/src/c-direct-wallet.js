import {address,hash,uint,validateDraft,validateExecution,vaultTransaction,encodeDirect,INTENT_FIELDS} from './c-direct-intent.js';

/** Only the browser wallet signs/sends. Public pending metadata is persisted; keys/JWTs are never persisted here. */
export class CDirectWallet {
  constructor({api,provider,storage,locks,changed=()=>{},now=()=>Math.floor(Date.now()/1000),uuid=()=>crypto.randomUUID()}){
    Object.assign(this,{api,provider,storage,locks,changed,now,uuid});this.epoch=0;this.busy=false;this.user=null;
    this.invalidate=()=>{this.epoch++;this.owner=null;this.execution=null;this.draft=null;this.request=null;this.changed();};
    for(const event of ['accountsChanged','chainChanged','disconnect'])provider?.on?.(event,this.invalidate);
  }
  context(user){this.epoch++;this.user=user;this.cfg=null;this.owner=null;this.execution=null;this.draft=null;this.request=null;this.changed();}
  ticket(){const epoch=this.epoch;if(!this.user)throw new Error('C 시연 계정으로 로그인하세요.');return()=>{if(epoch!==this.epoch||!this.user)throw new Error('로그인·지갑·네트워크가 변경되어 요청을 무효화했습니다. 이전 거래는 조회로 확인하세요.');};}
  async run(task){if(this.busy)throw new Error('진행 중인 C 지갑 요청을 먼저 확인하세요.');this.busy=true;this.changed();try{return await task();}finally{this.busy=false;this.changed();}}
  async init(){const check=this.ticket();const out=await this.api.get('/api/c-futures/config');check();const c=out.value;if(!c||c.custody!=='C_ERC20_VAULT_CANONICAL_ASSETS')throw new Error('지원하는 C 계약 설정이 아닙니다.');uint(c.chainId);hash(c.genesisHash);hash('0x'+c.deploymentId);for(const key of ['token','vault','controller','relayer'])address(c[key]);this.cfg=c;return c;}
  storageKey(){if(!this.cfg||!this.user)throw new Error('C 배포 identity가 필요합니다.');return `tms-c-direct:${this.user.id??this.user.userId}:${this.cfg.chainId}:${this.cfg.genesisHash}:${this.cfg.controller}`;}
  pending(){
    const raw=this.storage.getItem(this.storageKey());if(raw===null)return null;
    try{
      const p=JSON.parse(raw),plain=v=>v!==null&&typeof v==='object'&&!Array.isArray(v)&&Object.getPrototypeOf(v)===Object.prototype;
      const need=ok=>{if(!ok)throw new Error('C_PENDING_STRUCTURE');};
      const draftPhases=['DRAFT_REQUESTED','DRAFT_READY','EXECUTION_REQUESTED','EXECUTION_READY'];
      const sentPhases=['WALLET_REQUESTED','USER_REJECTED','UNKNOWN','SUBMITTED','REVIEW_REQUIRED','REVERTED','RECEIPT_OBSERVED'];
      need(plain(p)&&[...draftPhases,...sentPhases].includes(p.phase));address(p.owner);
      const direct=draftPhases.includes(p.phase)||['OPEN','CLOSE'].includes(p.kind);
      if(direct){
        need(typeof p.key==='string'&&/^[A-Za-z0-9._:-]{8,128}$/.test(p.key)&&plain(p.request)&&['OPEN','CLOSE'].includes(p.request.action));
        need(['LONG','SHORT'].includes(p.request.side));
        for(const field of ['leverage','collateralAfterE18','priceMinE8','priceMaxE8','quantityMinE18','quantityMaxE18'])uint(p.request[field]);
        if(p.request.action==='CLOSE')hash(p.request.positionId);
        if(p.phase!=='DRAFT_REQUESTED'){
          hash(p.intentId);need(plain(p.draft)&&p.draft.intentId===p.intentId&&plain(p.draft.intent));
          const i=p.draft.intent;need(Object.keys(i).length===INTENT_FIELDS.length);
          for(const field of INTENT_FIELDS){
            if(['owner','relayer'].includes(field))address(i[field]);
            else if(['operationId','positionId','accountRef','symbolHash','policyHash'].includes(field))hash(i[field]);
            else uint(i[field]);
          }
          need(address(i.owner)===address(p.owner)&&i.operationId===p.intentId);
          const td=p.draft.typedData;
          need(plain(td)&&plain(td.domain)&&plain(td.message)&&plain(td.types)&&td.primaryType==='CFuturesIntent'
            &&td.domain.name==='TokenizedMarketCFuturesUserIntent'&&td.domain.version==='1'
            &&uint(td.domain.chainId)===uint(this.cfg.chainId)&&address(td.domain.verifyingContract)===address(this.cfg.controller)
            &&INTENT_FIELDS.every(f=>td.message[f]===i[f]));
        }
        if(p.phase==='EXECUTION_READY'||sentPhases.includes(p.phase)){
          need(plain(p.execution)&&plain(p.execution.transaction)&&plain(p.execution.intent));
          const e=p.execution;need(INTENT_FIELDS.every(f=>e.intent[f]===p.draft.intent[f]));
          need(e.transaction.data?.toLowerCase()===encodeDirect(e.report,e.priceSignature,p.draft.intent)
            &&address(e.transaction.from)===address(p.owner)&&address(e.transaction.to)===address(this.cfg.controller)
            &&uint(e.transaction.chainId)===uint(this.cfg.chainId)&&e.transaction.value==='0x0');
        }
      }
      if(sentPhases.includes(p.phase)){
        need(plain(p.transaction));const t=p.transaction;
        need(address(t.from)===address(p.owner)&&typeof t.chainId==='string'&&/^0x[0-9a-f]+$/i.test(t.chainId)
          &&BigInt(t.chainId)===uint(this.cfg.chainId)&&t.value==='0x0'&&typeof t.data==='string'&&/^0x(?:[0-9a-f]{2}){4,}$/i.test(t.data));
        address(t.to);
        if(direct)need(p.kind===p.request.action&&t.data===p.execution.transaction.data&&address(t.to)===address(this.cfg.controller));
        else{
          need(['APPROVE','DEPOSIT','WITHDRAW'].includes(p.kind));const expected=vaultTransaction(this.cfg,p.owner,p.kind,p.amount);
          need(address(t.to)===expected.to&&t.data===expected.data);
        }
        if(['SUBMITTED','REVIEW_REQUIRED','REVERTED','RECEIPT_OBSERVED'].includes(p.phase))hash(p.txHash);
      }
      if(['RECEIPT_OBSERVED','REVERTED'].includes(p.phase)){
        need(plain(p.result)&&p.result.status===(p.phase==='RECEIPT_OBSERVED'?'CONFIRMED':'REVERTED'));
        need(hash(p.result.txHash)===hash(p.txHash));hash(p.result.blockHash);
        if(p.phase==='RECEIPT_OBSERVED')need(p.result.completeCoverage===false);
      }
      // Historical records remain readable after expiry. TTL checks belong to the actual send boundary.
      return p;
    }catch{throw new Error('이전 거래 기록 손상: 신규 거래를 차단합니다.');}
  }
  save(p,key=this.storageKey()){this.storage.setItem(key,JSON.stringify(p));this.changed();}
  async lock(task){if(!this.locks?.request)throw new Error('Chrome localhost/HTTPS와 Web Locks가 필요합니다.');return this.locks.request(this.storageKey(),{ifAvailable:true},lock=>{if(!lock)throw new Error('다른 탭이 C 거래를 처리하고 있습니다.');return task();});}
  async wallet(interactive=false){if(!this.provider?.request)throw new Error('MetaMask 등 EIP-1193 지갑이 필요합니다. 개인키·시드는 입력하지 마세요.');const check=this.ticket();if(!this.cfg)throw new Error('C 배포 설정을 먼저 확인하세요.');const accounts=await this.provider.request({method:interactive?'eth_requestAccounts':'eth_accounts'});check();const owner=address(accounts?.[0]);const chain=await this.provider.request({method:'eth_chainId'});check();if(typeof chain!=='string'||!/^0x[0-9a-f]+$/i.test(chain)||BigInt(chain)!==uint(this.cfg.chainId))throw new Error('MetaMask chainId가 C 신규 체인과 다릅니다.');const genesis=await this.provider.request({method:'eth_getBlockByNumber',params:['0x0',false]});check();if(hash(genesis?.hash)!==hash(this.cfg.genesisHash))throw new Error('동일 chainId의 다른 체인입니다. C RPC/genesis를 확인하세요.');return owner;}
  async sameWallet(owner){if(await this.wallet()!==address(owner))throw new Error('지갑 계정이 변경되었습니다.');}
  async connect(){return this.run(async()=>{this.owner=await this.wallet(true);this.changed();return this.owner;});}
  async linked(){const check=this.ticket(),owner=await this.wallet();const link=await this.api.get('/api/c-futures/wallet');check();if(address(link.wallet)!==owner)throw new Error('JWT 계정의 연결 지갑과 현재 MetaMask가 다릅니다.');this.owner=owner;this.changed();return owner;}
  async link(){return this.run(()=>this.lock(async()=>{const check=this.ticket(),owner=await this.wallet();const c=await this.api.post('/api/c-futures/wallet/challenges',{wallet:owner});check();if(address(c.wallet)!==owner||c.deploymentId!==this.cfg.deploymentId||typeof c.message!=='string'||Date.parse(c.expiresAt)<=this.now()*1000)throw new Error('소유 증명 challenge를 검증할 수 없습니다.');const message='0x'+Array.from(new TextEncoder().encode(c.message),n=>n.toString(16).padStart(2,'0')).join('');const sig=await this.provider.request({method:'personal_sign',params:[message,owner]});check();await this.sameWallet(owner);check();if(Date.parse(c.expiresAt)<=this.now()*1000)throw new Error('소유 증명 기한이 만료됐습니다. 자동 재서명하지 않습니다.');const out=await this.api.post('/api/c-futures/wallet/link',{challengeId:c.id,signature:sig});check();if(address(out.wallet)!==owner)throw new Error('연결된 지갑이 다릅니다.');this.owner=owner;return out;}));}
  async prepare(request){return this.run(()=>this.lock(async()=>{const check=this.ticket(),owner=await this.linked();const old=this.pending();
    if(old&&!['DRAFT_REQUESTED','DRAFT_READY'].includes(old.phase))throw new Error('이전 거래를 먼저 조회하세요. 자동 재주문하지 않습니다.');
    if(old&&JSON.stringify(old.request)!==JSON.stringify(request))throw new Error('이전 draft 조건과 다릅니다. 서명 전 draft를 명시적으로 해제한 뒤 준비하세요.');
    const key=old?.key??this.uuid();this.save({phase:'DRAFT_REQUESTED',key,owner,request});
    const d=await this.api.post('/api/c-futures/direct/intents',request,key);check();await this.sameWallet(owner);check();validateDraft(d,this.cfg,owner,request,this.now());
    this.draft=d;this.request=structuredClone(request);this.execution=null;this.owner=owner;this.save({phase:'DRAFT_READY',key,owner,request,intentId:d.intentId,draft:d});return d;
  }));}
  async executionPrice(){return this.run(()=>this.lock(async()=>{const check=this.ticket(),owner=await this.linked(),p=this.pending();if(!p||!['DRAFT_READY','EXECUTION_REQUESTED','EXECUTION_READY'].includes(p.phase)||!p.draft)throw new Error('사용자가 확인할 C draft가 필요합니다.');validateDraft(p.draft,this.cfg,owner,p.request,this.now());this.save({...p,phase:'EXECUTION_REQUESTED'});
    const e=await this.api.post(`/api/c-futures/direct/intents/${encodeURIComponent(hash(p.intentId))}/execution`,{});check();await this.sameWallet(owner);check();validateExecution(e,p.draft,this.cfg,owner,p.request,this.now());this.execution=e;this.draft=p.draft;this.request=p.request;this.save({...p,phase:'EXECUTION_READY',execution:e});return e;
  }));}
  async sendDirect(){return this.run(()=>this.lock(async()=>{const check=this.ticket(),owner=await this.linked(),p=this.pending();if(p?.phase!=='EXECUTION_READY'||!p.execution)throw new Error('새 실행 가격을 명시적으로 확인하세요.');const tx=validateExecution(p.execution,p.draft,this.cfg,owner,p.request,this.now());check();return this.send(tx,{...p,kind:p.request.action},check);}));}
  async sendAsset(action,amount){return this.run(()=>this.lock(async()=>{const check=this.ticket(),owner=await this.linked();if(this.pending())throw new Error('이전 거래를 먼저 조회·확인하세요.');const a=await this.api.get('/api/c-futures/account');check();const n=uint(amount),v=a.value;if(n<=0n||n>20_000_000n*10n**18n)throw new Error('명시된 고정 공급 안의 양수 금액만 허용합니다.');if(action==='APPROVE'&&n>uint(v.walletBalance)||action==='DEPOSIT'&&(n>uint(v.walletBalance)||n>uint(v.allowanceToVault))||action==='WITHDRAW'&&n>uint(v.free))throw new Error('지갑 잔고·정확한 허용량 또는 실제 free가 부족합니다.');const tx=vaultTransaction(this.cfg,owner,action,amount);await this.sameWallet(owner);check();return this.send(tx,{kind:action,owner,amount},check);}));}
  async send(tx,metadata,check){const key=this.storageKey(),p={...metadata,phase:'WALLET_REQUESTED',transaction:tx};this.save(p,key);let result;
    try{result=await this.provider.request({method:'eth_sendTransaction',params:[tx]});}
    catch(error){if(error?.code===4001||error?.code==='ACTION_REJECTED'){this.save({...p,phase:'USER_REJECTED'},key);throw new Error('사용자가 거래 승인을 거절했습니다. 자동 재서명·전송하지 않습니다.');}this.save({...p,phase:'UNKNOWN'},key);throw new Error('지갑 전송 결과가 불확실합니다. 새 거래를 만들지 말고 MetaMask Activity에서 기존 txHash를 확인하세요.');}
    // Persist a late tx hash before checking epoch: a network/account change must not lose the old transaction.
    try{hash(result);}catch{this.save({...p,phase:'UNKNOWN'},key);throw new Error('지갑의 txHash 응답을 검증하지 못했습니다. 이전 거래를 수동 확인하세요.');}
    this.save({...p,phase:'SUBMITTED',txHash:result.toLowerCase()},key);check();return result;
  }
  async resolve(manualHash=null){return this.run(()=>this.lock(async()=>{const check=this.ticket(),p=this.pending();if(!p?.transaction)throw new Error('조회할 전송 기록이 없습니다.');await this.sameWallet(p.transaction.from);check();const txHash=hash(p.txHash??manualHash);if(!p.txHash)this.save({...p,phase:'SUBMITTED',txHash});
    const tx=await this.provider.request({method:'eth_getTransactionByHash',params:[txHash]});check();if(!tx){return {status:'PENDING_OR_UNKNOWN',txHash};}
    if(hash(tx.hash)!==txHash||address(tx.from)!==address(p.transaction.from)||address(tx.to)!==address(p.transaction.to)||typeof tx.value!=='string'||BigInt(tx.value)!==0n||tx.input?.toLowerCase()!==p.transaction.data.toLowerCase())throw new Error('실제 거래가 기존 승인 전송 기록과 다릅니다. 신규 거래를 차단합니다.');
    const receipt=await this.provider.request({method:'eth_getTransactionReceipt',params:[txHash]});check();if(!receipt)return {status:'PENDING',txHash};
    const block=await this.provider.request({method:'eth_getBlockByNumber',params:[receipt.blockNumber,false]});check();if(hash(receipt.transactionHash)!==txHash||hash(receipt.blockHash)!==hash(block?.hash)||hash(tx.blockHash)!==hash(receipt.blockHash))throw new Error('현재 RPC 기준 canonical Receipt를 확인할 수 없습니다.');
    let out={status:receipt.status==='0x1'?'WALLET_RECEIPT_OBSERVED':'REVERTED',txHash,blockHash:receipt.blockHash,scope:'WALLET_SINGLE_PROVIDER_RECEIPT_NOT_FULL_AUDIT'};
    if(!['0x0','0x1'].includes(receipt.status))throw new Error('Receipt 상태가 불명확합니다.');
    if(receipt.status==='0x1'){out=await this.api.get(`/api/c-futures/direct/transactions/${txHash}${p.intentId?'?intentId='+encodeURIComponent(p.intentId):''}`);check();if(hash(out.txHash)!==txHash||!['CONFIRMED','PENDING','REVIEW_REQUIRED'].includes(out.status)||out.status==='CONFIRMED'&&hash(out.blockHash)!==hash(receipt.blockHash))throw new Error('서버와 지갑의 현재 거래/기준 블록이 상충합니다. 확정 처리하지 않습니다.');}
    this.save({...p,phase:out.status==='CONFIRMED'?'RECEIPT_OBSERVED':out.status==='REVERTED'?'REVERTED':out.status==='PENDING'?'SUBMITTED':'REVIEW_REQUIRED',txHash,result:out});return out;
  }));}
  async acknowledge(){return this.run(()=>this.lock(async()=>{const p=this.pending();if(!p||!['USER_REJECTED','REVERTED','RECEIPT_OBSERVED','DRAFT_REQUESTED','DRAFT_READY','EXECUTION_REQUESTED','EXECUTION_READY'].includes(p.phase))throw new Error('미확정 전송은 해제할 수 없습니다. 기존 txHash로 조회하세요.');this.storage.removeItem(this.storageKey());this.draft=null;this.execution=null;this.request=null;this.changed();}));}
}
