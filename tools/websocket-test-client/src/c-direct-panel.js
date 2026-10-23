import './c-direct-panel.css';
import { CDirectWallet } from './c-direct-wallet.js';
import { units, decimal, quantityRange, hash, uint } from './c-direct-intent.js';
import { createCApiAdapter } from './c-api-adapter.js';

/** Existing TMS session only. Pending public transaction metadata survives route/logout changes. */
export function createCDirectPanel(client, unauthorized = () => {}, options = {}) {
  const document = options.document ?? globalThis.document;
  const $ = id => document.getElementById(id);
  const node = (tag, text) => { const el = document.createElement(tag); el.textContent = text; return el; };
  const api = createCApiAdapter(client);
  const now = options.now ?? (() => Math.floor(Date.now() / 1000));
  const schedule = options.setInterval ?? globalThis.setInterval;
  const unschedule = options.clearInterval ?? globalThis.clearInterval;
  let reportTimer = null;
  const readSerial = { account: 0, position: 0 };
  function beginRead(kind) {
    const serial = ++readSerial[kind], epoch = wallet.epoch;
    return () => active && epoch === wallet.epoch && serial === readSerial[kind];
  }
  let user = null, active = false, selected = null, lastResult = null, contextKey = null, initializing = false, renderedEpoch = -1;
  const wallet = new CDirectWallet({ api, provider: options.provider ?? globalThis.window?.ethereum,
    storage: options.storage ?? globalThis.localStorage, locks: options.locks ?? globalThis.navigator?.locks,
    changed: () => {
      if (!active) return;
      if (renderedEpoch !== wallet.epoch) {
        renderedEpoch = wallet.epoch; selected = null; lastResult = null;
        for (const id of ['c-account','c-position','c-draft','c-result','c-range']) $(id).replaceChildren();
        $('c-market').textContent = '지갑·네트워크가 변경되면 기존 자산과 시세를 새로 조회하세요.';
      }
      if (!wallet.execution) $('c-consent').checked = false;
      update();
    } });
function fields(rows){const dl=document.createElement('dl');for(const [a,b]of rows)dl.append(node('dt',a),node('dd',String(b??'—')));return dl;}
function raw(value){const d=document.createElement('details');d.append(node('summary','정확한 원본 값 / calldata'),node('pre',JSON.stringify(value,null,2)));return d;}
function say(text){if(active)$('c-message').textContent=text;}
function money(n){try{return decimal(n,18)+' cMKRW';}catch{return '미확인';}}
function update(){if(!active||!document.getElementById('c-connect'))return;for(const id of ['c-connect','c-link','c-refresh','c-approve','c-deposit','c-withdraw','c-prepare','c-price','c-send','c-position-read','c-resolve','c-ack'])$(id).disabled=wallet.busy||!user||!wallet.cfg;try{const p=user&&wallet.cfg?wallet.pending():null;$('c-pending').replaceChildren(p?fields([['기존 단계',p.phase],['동작',p.kind??p.request?.action],['intentId',p.intentId],['txHash',p.txHash]]):node('p','이 브라우저에 기록된 미확정 C 거래 없음'));}catch(e){say(e.message);}if(wallet.cfg)$('c-identity').replaceChildren(fields([['로그인',user?.loginId??user?.id??user?.userId],['MetaMask',wallet.owner??'연결 전'],['chainId',wallet.cfg.chainId],['genesis',wallet.cfg.genesisHash],['controller',wallet.cfg.controller],['Vault',wallet.cfg.vault]]));reportCountdown();}
function reportCountdown() {
  if (!active) return;
  $('c-send').disabled = true;
  try {
    const pending = user && wallet.cfg ? wallet.pending() : null;
    const report = pending?.execution?.report;
    if (!report) {
      $('c-deadline').textContent = 'Report는 관측부터 30초입니다. MetaMask 승인·전송·채굴 시간을 포함하며 자동 연장·재가격하지 않습니다.';
      return;
    }
    const remaining = uint(report.validUntil) - BigInt(now());
    $('c-deadline').textContent = 'Report 남은 ' + (remaining > 0n ? remaining.toString() : '0') + '초 · 관측부터 30초, MetaMask 승인·전송·채굴 포함. 만료되면 자동 연장·재가격하지 않습니다.';
    $('c-send').disabled = wallet.busy || !wallet.execution || !$('c-consent').checked || pending.phase !== 'EXECUTION_READY' || remaining <= 0n;
  } catch (error) {
    for (const id of ['c-approve','c-deposit','c-withdraw','c-prepare','c-price','c-send']) $(id).disabled = true;
    $('c-deadline').textContent = '이전 거래 기록을 확인할 수 없어 신규 거래를 차단합니다.';
    say(error.message);
  }
}
async function act(task){const epoch=wallet.epoch;try{await task();}catch(e){if(epoch!==wallet.epoch||!active)return;say(e.code===4001?'사용자가 지갑 승인을 거절했습니다. 자동 재서명하지 않습니다.':e.message||'요청 실패');if(e.status===401){unauthorized();}}finally{update();}}
async function refresh(current = beginRead('account')) {
  try {
    const [a, m] = await Promise.all([api.get('/api/c-futures/account'), api.get('/api/c-futures/direct/market')]);
    if (!current()) return false;
    const v = a.value;
    $('c-account').replaceChildren(...[['내 ERC-20',money(v.walletBalance)],['내 인출 가능 free',money(v.free)],['내 locked',money(v.locked)],['house',money(v.house)],['전체 미지급 Claims',money(v.totalUnpaidProfit)],['Vault 허용량',money(v.allowanceToVault)]].map(([label,value])=>{const d=node('div',label);d.append(node('strong',value));return d;}),fields([['조회 기준 블록',a.block?.number],['관측시각',a.observedAt],['범위',a.verificationScope]]));
    $('c-market').textContent = `현재 참고 시세 ${m.price??'—'} · ${m.provider??'미확인'} · ${m.observedAt??'—'}. 허용 가격은 사용자가 직접 입력하며 자동 변경하지 않습니다.`;
    return true;
  } catch (error) { if (current()) throw error; return false; }
}
function request(){const action=$('c-action').value,lo=units($('c-min-price').value,8).toString(),hi=units($('c-max-price').value,8).toString();if(uint(lo)<=0n||uint(lo)>uint(hi)||uint(hi)>=10n**20n)throw new Error('유효한 최소/최대 가격을 직접 입력하세요.');if(action==='CLOSE'){if(!selected||selected.positionId!==hash($('c-position-id').value)||!['ACTIVE','MARGIN_CALL'].includes(selected.status))throw new Error('본인 활성 C 포지션을 먼저 조회하세요.');return {action,side:selected.side==='0'?'LONG':'SHORT',leverage:selected.leverage,collateralAfterE18:selected.historicalCollateral,priceMinE8:lo,priceMaxE8:hi,quantityMinE18:selected.quantity,quantityMaxE18:selected.quantity,positionId:selected.positionId};}const c=units($('c-collateral').value,18).toString(),leverage=$('c-leverage').value;return {action,side:$('c-side').value,leverage,collateralAfterE18:c,priceMinE8:lo,priceMaxE8:hi,...quantityRange(c,leverage,lo,hi)};}
function range(){wallet.execution=null;$('c-consent').checked=false;try{const r=request();$('c-range').replaceChildren(fields([['승인 담보',money(r.collateralAfterE18)],['승인 가격',`${decimal(r.priceMinE8,8)} ~ ${decimal(r.priceMaxE8,8)}`],['승인 수량',`${decimal(r.quantityMinE18,18)} ~ ${decimal(r.quantityMaxE18,18)} mSEC`]]));}catch{$('c-range').replaceChildren(node('p','조건을 입력하면 승인 수량 범위를 표시합니다.'));}update();}

$('c-connect').onclick=()=>void act(async()=>{await wallet.connect();say('MetaMask 연결 완료. 소유 증명 또는 기존 연결 계정 조회를 진행하세요.');});
$('c-link').onclick=()=>void act(async()=>{await wallet.link();say('C 계정과 지갑 소유 증명 연결 완료.');await refresh();});
$('c-refresh').onclick=()=>{const current=beginRead('account');return act(async()=>{await wallet.linked();if(!current())return;if(await refresh(current))say('본인 계정과 합성 시세를 조회했습니다.');});};
for(const [id,action]of [['c-approve','APPROVE'],['c-deposit','DEPOSIT'],['c-withdraw','WITHDRAW']])$(id).onclick=()=>void act(async()=>{const amount=units($('c-asset-amount').value,18).toString();await wallet.sendAsset(action,amount);say(`${action} 제출. 기존 거래 조회로 canonical Receipt를 확인하세요.`);});
$('c-position-read').onclick=()=>{
  const ticket=beginRead('position'), input=$('c-position-id').value;
  const current=()=>ticket()&&$('c-position-id').value===input;
  selected=null;
  return act(async()=>{
    try {
      const id=hash(input);await wallet.linked();if(!current())return;
      const out=await api.get('/api/c-futures/positions/'+encodeURIComponent(id));if(!current())return;
      selected=out.value;
      $('c-position').replaceChildren(fields([['포지션',selected.positionId],['상태',selected.status],['진입가격',decimal(selected.entryPriceE8,8)],['수량',decimal(selected.quantity,18)],['담보(과거)',money(selected.historicalCollateral)],['현재 locked',money(selected.lockedCollateral)]]));
      if($('c-action').value==='CLOSE'){$('c-side').value=selected.side==='0'?'LONG':'SHORT';$('c-leverage').value=selected.leverage;$('c-collateral').value=decimal(selected.historicalCollateral,18);}range();
    } catch(error) { if(current())throw error; }
  });
};
$('c-prepare').onclick=()=>void act(async()=>{const r=request();const d=await wallet.prepare(r);$('c-position-id').value=d.intent.positionId;$('c-draft').replaceChildren(node('h3','고정 사용자 조건 · 아직 거래 승인 전'),fields([['intentId',d.intentId],['positionId',d.intent.positionId],['owner',d.intent.owner],['nonce',d.intent.nonce],['담보',money(d.intent.collateralAfter)],['사용자 유효기간(초)',d.intent.deadline]]),raw(d));$('c-consent').checked=false;say('draft 고정 완료. 새 실행 가격을 확인한 뒤 지갑 거래를 승인하세요.');});
$('c-price').onclick=()=>void act(async()=>{const e=await wallet.executionPrice();$('c-draft').append(node('h3','이번 실행 Report'),fields([['실행가격',decimal(e.report.priceE8,8)],['수량',decimal(e.report.quantity,18)],['담보',money(e.report.collateralAfter)],['Report 관측초',e.report.observedAt],['Report 만료초',e.report.validUntil],['시세 출처',e.market.provider]]),raw(e));$('c-consent').checked=false;say('실행 가격과 calldata를 확인하세요. Report 30초에 MetaMask 승인과 채굴 시간도 포함됩니다.');});
$('c-send').onclick=()=>void act(async()=>{if(!$('c-consent').checked)throw new Error('표시된 거래 조건을 먼저 확인하세요.');const r=request(),p=wallet.pending();if(JSON.stringify(r)!==JSON.stringify(p?.request))throw new Error('입력 조건이 고정 draft와 다릅니다. 새 서명 전 draft를 명시적으로 해제하세요.');await wallet.sendDirect();say('owner 직접 거래 제출. 자동 재전송 없이 기존 거래를 조회하세요.');});
$('c-resolve').onclick=()=>void act(async()=>{const result=await wallet.resolve($('c-manual-hash').value||null);lastResult=result;$('c-result').replaceChildren(fields([['판정',lastResult.status],['txHash',lastResult.txHash],['기준 blockHash',lastResult.blockHash],['검증 범위',lastResult.scope]]),raw(lastResult));say('기존 거래를 조회했습니다. 관측 결과는 아래 검증 범위를 확인하세요.');await refresh();});
$('c-ack').onclick=()=>void act(async()=>{await wallet.acknowledge();$('c-consent').checked=false;$('c-draft').replaceChildren();say('확인한 기록을 브라우저 대기 목록에서 해제했습니다. 체인·DB 이력은 변경하지 않았습니다.');});
for(const id of ['c-action','c-side','c-leverage','c-collateral','c-min-price','c-max-price','c-position-id'])$(id).addEventListener('input',range);
$('c-consent').addEventListener('change',update);

  return {
    async setContext(nextUser, route) {
      const nextActive = Boolean(nextUser) && route === 'custody';
      const key = nextActive ? String(nextUser.id ?? nextUser.userId) : null;
      if (contextKey === key && active === nextActive && (initializing || wallet.cfg)) return;
      if (reportTimer !== null) { unschedule(reportTimer); reportTimer = null; }
      active = false; user = nextActive ? nextUser : null; contextKey = key;
      selected = null; lastResult = null; wallet.context(user);
      for (const id of ['c-identity','c-account','c-position','c-draft','c-result','c-pending','c-range']) $(id).replaceChildren();
      for (const id of ['c-asset-amount','c-collateral','c-min-price','c-max-price','c-position-id','c-manual-hash']) $(id).value = '';
      $('c-consent').checked = false;
      active = nextActive; initializing = active; update();
      if (!active) return;
      reportTimer = schedule(reportCountdown, 1000);
      reportTimer?.unref?.();
      const epoch = wallet.epoch;
      say('C 서버 배포 identity를 확인하는 중입니다.');
      try {
        await wallet.init();
        if (epoch !== wallet.epoch || !active) return;
        say('C 설정 확인 완료. MetaMask 네트워크와 소유 지갑을 확인하세요. 기존 pending 기록은 보존합니다.');
        range();
      } catch (error) {
        if (epoch !== wallet.epoch || !active) return;
        say('C 기능이 미지원·미설정이거나 서버 확인에 실패했습니다. 거래는 비활성입니다. 기존 DB 장부 화면은 계속 이용할 수 있습니다.');
        if (error.status === 401) unauthorized();
      } finally { if (epoch === wallet.epoch) { initializing = false; update(); } }
    },
    wallet,
  };
}
