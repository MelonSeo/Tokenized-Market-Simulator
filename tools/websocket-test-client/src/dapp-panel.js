import { node,button,fields } from './ui.js';
import { conditions,decimal,address } from './dapp-intent.js';
import { DappWallet,walletError } from './dapp-wallet.js';

/** Separate opt-in view; never routes user-authorized positions through legacy write APIs. */
export function createDappPanel(api,unauthorized){
  const root=document.getElementById('dapp-root');
  const notice=node('p','','notice'),identity=node('div'),reference=node('p','','muted'),result=node('div'),consent=node('div'),positions=node('div'),controls=node('div','','dapp-controls');
  result.id='dapp-operation';consent.id='dapp-draft';
  let user=null,active=false,epoch=0,selected=null,timer,refreshing=false,lastOperation=null;
  const wallet=new DappWallet({api,provider:window.ethereum,storage:localStorage,locks:navigator.locks,changed:()=>update()});
  const inputs={};
  const input=(name,title,values=null)=>{const wrap=node('label',title),el=node(values?'select':'input');el.id='dapp-'+name;if(values)for(const [v,t]of values){const o=node('option',t);o.value=v;el.append(o);}else{el.type='text';el.inputMode='decimal';el.autocomplete='off';}inputs[name]=el;wrap.append(el);controls.append(wrap);return el;};
  input('action','동작',[['OPEN','새 포지션 개설'],['TOP_UP','선택 포지션 추가 담보'],['CLOSE','선택 포지션 전체 종료']]);
  input('side','방향',[['LONG','Long · 상승'],['SHORT','Short · 하락']]);input('leverage','레버리지',[['2','2배'],['5','5배'],['10','10배']]);
  input('margin','담보 / 추가 담보 (mKRW)');input('minPrice','허용 최소 체결가 (mKRW/mSEC)');input('maxPrice','허용 최대 체결가 (mKRW/mSEC)');input('ttl','사용자 서명 유효시간 (1~120초)').value='120';
  const range=node('div'),checked=node('input');checked.id='dapp-consent';checked.type='checkbox';const checkLabel=node('label','표시된 조건 및 전체 typed data를 확인했으며, 이 범위만 승인합니다.');checkLabel.prepend(checked);
  const connect=button('1. 지갑 연결',()=>act(()=>wallet.connect())),link=button('2. 소유 증명 및 계정 연결',()=>act(async()=>{await wallet.link();say('지갑 소유 증명 연결 완료');}));
  connect.id='dapp-connect';link.id='dapp-link';
  const prepare=button('3. 조건 고정 및 draft 확인',()=>act(async()=>{const approved=current();const d=await wallet.prepare(approved);showDraft(d,approved);say('조건을 확인한 뒤 별도로 지갑 서명을 승인하세요.');}));prepare.id='dapp-prepare';
  const sign=button('4. 확인한 Intent 지갑 서명 및 제출',()=>act(async()=>{if(!checked.checked)throw new Error('승인 조건 확인이 필요합니다.');const op=await wallet.sign();showOperation(op);consent.replaceChildren();checked.checked=false;await refresh();}));sign.id='dapp-sign';
  const resolve=button('이전 operation 조회',()=>act(async()=>showOperation(await wallet.resolve())));resolve.id='dapp-resolve';
  const acknowledge=button('확정 결과 확인 후 다음 거래 준비',()=>act(async()=>{await wallet.acknowledge();consent.replaceChildren();say('최종 상태를 확인했습니다. 새 조건을 입력하세요.');}));acknowledge.id='dapp-acknowledge';
  const reload=button('가격·포지션 새로 조회',()=>act(refresh));reload.id='dapp-refresh';
  const actions=node('div','','button-row');actions.append(connect,link,reload);
  root.append(node('p','B-v2 opt-in · 독립 EOA 승인 / PostgreSQL 모의 담보. 실제 ERC-20 예치·인출이나 비수탁 자산 보관이 아닙니다.','notice'),identity,actions,reference,controls,range,prepare,consent,checkLabel,sign,notice,result,resolve,acknowledge,
    node('p','수량 = floor(담보 wei × 레버리지 × 10⁸ / 실행가격 E8). 수량 18자리, 가격 8자리, 담보 18자리의 정수 단위입니다. 서버는 승인한 범위 안의 실행가를 선택합니다. 사용자 Intent 최대 120초와 별개로 실행 Report 30초 및 시장 신선도 5초를 검사합니다. 유지증거금 5%, 온체인 마진콜 확정 후 60초 유예 및 서버 worker 자동청산은 그대로 유지됩니다.','muted'),
    node('p','B-v2 전체 감사: INCONCLUSIVE · 정산 확인은 전체 감사 MATCH가 아닙니다. nonce 취소 UI는 미구현입니다. 직접 cancelNonce는 Gas와 canonical Receipt가 필요하며 SIGNED/SUBMITTED 잠금을 화면에서 임의 해제하지 않습니다.','notice'),node('h2','내 포지션 선택'),positions);
  function say(text){if(active){notice.textContent=text;notice.scrollIntoView?.({block:'nearest'});}}
  function current(){return conditions(Object.fromEntries(Object.entries(inputs).map(([k,v])=>[k,v.value])),selected);}
  function invalidateConsent(){wallet.draft=null;wallet.approved=null;consent.replaceChildren();checked.checked=false;update();try{const a=current();range.replaceChildren(fields([['최소 수량',decimal(a.request.quantityMin,18)+' mSEC'],['최대 수량',decimal(a.request.quantityMax,18)+' mSEC']]));}catch{range.replaceChildren(node('p','담보와 허용 가격을 직접 입력하면 정확한 수량 범위가 표시됩니다.','muted'));}}
  for(const el of Object.values(inputs)){el.addEventListener('input',invalidateConsent);el.addEventListener('change',()=>{inputs.margin.disabled=wallet.busy||inputs.action.value==='CLOSE';invalidateConsent();});}
  checked.addEventListener('change',update);
  function update(){
    if(!identity)return;
    identity.replaceChildren(fields([['지갑',wallet.owner||'연결 전'],['지갑 chainId',wallet.chain||'미확인'],['서버 chainId',wallet.cfg?.chainId||'비활성/미확인'],['controller',wallet.cfg?.controller||'—']]));
    for(const b of [connect,link,prepare,sign,acknowledge])b.disabled=wallet.busy||!wallet.cfg;
    sign.disabled=wallet.busy||!wallet.draft||!checked.checked;link.disabled=wallet.busy||!wallet.owner||!wallet.cfg;
    for(const el of Object.values(inputs))el.disabled=wallet.busy;
    inputs.side.disabled=wallet.busy||inputs.action.value!=='OPEN';inputs.leverage.disabled=wallet.busy||inputs.action.value!=='OPEN';inputs.margin.disabled=wallet.busy||inputs.action.value==='CLOSE';
    if(!wallet.draft){checked.checked=false;consent.replaceChildren();}
  }
  async function act(fn){const e=epoch;try{await fn();}catch(error){if(e!==epoch||!active)return;say(walletError(error));if(error.status===401)unauthorized();}finally{if(e===epoch&&active)update();}}
  function showDraft(d,a){consent.replaceChildren(node('h2','지갑에 전달할 승인 조건'),fields([['동작 / 방향 / 배수',`${a.request.action} / ${a.request.side} / ${a.request.leverage}x`],['담보 전 → 후',`${decimal(a.before,18)} → ${decimal(a.after,18)} mKRW`],['가격 범위',`${decimal(a.request.priceMinE8,8)} ~ ${decimal(a.request.priceMaxE8,8)} mKRW/mSEC`],['수량 범위',`${decimal(a.request.quantityMin,18)} ~ ${decimal(a.request.quantityMax,18)} mSEC`],['positionId',d.positionId],['operationId',d.operationId],['서명 만료',new Date(Number(d.typedData.message.deadline)*1000).toISOString()]]));const raw=node('details');raw.append(node('summary','정확한 EIP-712 원문 (지갑 요청과 동일)'),node('pre',JSON.stringify(d.typedData,null,2)));consent.append(raw);checked.checked=false;update();}
  function showOperation(op){
    if(lastOperation?.id===op.id&&['CONFIRMED','REVERTED','REJECTED','REVIEW_REQUIRED'].includes(lastOperation.state)&&op.state!==lastOperation.state)return;
    lastOperation=op;const settled=op.state==='CONFIRMED'&&op.settlementConfirmed===true;
    result.replaceChildren(node('h2',settled?'온체인 및 DB 정산 확정':op.state==='REVIEW_REQUIRED'?'검토 필요 · 잠금 보존':'거래 처리 상태 · 확정 전일 수 있음'),fields([['operationId',op.id],['positionId',op.positionId],['state',op.state],['DB 정산',settled?'확정':'미확정 / 확인 필요'],['txHash',op.txHash||'아직 없음'],['blockHash',op.blockHash||'아직 없음']]));
  }
  async function select(id){const e=epoch;const {body}=await api.dappPosition(id);if(e!==epoch||!active)return;if(body.version!=='USER_AUTHORIZED_V2'||address(body.controller)!==address(wallet.cfg.controller)||address(body.owner)!==wallet.owner)throw new Error('현재 지갑/계약에 귀속된 B-v2 포지션이 아닙니다.');selected=body;inputs.side.value=body.position.side;inputs.leverage.value=String(body.position.leverage);inputs.action.value='TOP_UP';invalidateConsent();say('선택한 B-v2 포지션: '+id);}
  async function refresh(){if(!active||refreshing)return;refreshing=true;const e=epoch;try{
    const [p,m]=await Promise.all([api.futuresPositions(),api.market()]);if(e!==epoch||!active)return;
    reference.textContent=`참고 시세 ${m.body.price??'—'} mKRW/mSEC · ${m.body.provider??'출처 미확인'} · ${m.body.observedAt??'시각 미확인'} (서명 가격 범위는 자동 변경하지 않음)`;
    positions.replaceChildren();for(const p0 of p.body){const card=node('article','','record');card.append(fields([['positionId',p0.id],['상태',p0.status],['방향 / 배수',`${p0.side} / ${p0.leverage}x`],['진입가',p0.entryPrice+' mKRW'],['수량',p0.quantity+' mSEC'],['담보',p0.collateral+' mKRW'],['미실현 손익',p0.unrealized??'—']]));if(p0.status==='OPEN')card.append(button('B-v2 포지션으로 검증·선택',()=>act(()=>select(p0.id))));positions.append(card);}
    if(wallet.cfg){const pending=wallet.pending();if(pending?.operationId&&['SUBMITTING','ACCEPTED'].includes(pending.phase))try{showOperation(await wallet.resolve());}catch(error){if(e===epoch)say(walletError(error));}}
  }finally{refreshing=false;}}
  return {setContext(next,route){const on=route==='dapp'&&Boolean(next);if(String(user?.id)===String(next?.id)&&active===on)return;const changedUser=String(user?.id)!==String(next?.id);epoch++;user=next;active=on;clearInterval(timer);selected=null;lastOperation=null;wallet.context(next,on);identity.replaceChildren();consent.replaceChildren();positions.replaceChildren();result.replaceChildren();notice.textContent='';reference.textContent='';checked.checked=false;if(changedUser){inputs.action.value='OPEN';inputs.side.value='LONG';inputs.leverage.value='2';inputs.ttl.value='120';for(const name of ['margin','minPrice','maxPrice'])inputs[name].value='';range.replaceChildren();}
    if(on){void act(async()=>{await wallet.init();await refresh();});timer=setInterval(()=>{if(!wallet.busy)void act(refresh);},3000);}},refresh:()=>act(refresh)};
}
