/** Built-in Node CDP browser smoke: real Chromium/compiled UI, deterministic REST + STOMP fixtures.
 * No real users, trading DB writes or paid AI calls. Screenshots stay under ignored dist/.smoke.
 */
import { createServer } from 'node:http';
import { readFile, mkdir, mkdtemp } from 'node:fs/promises';
import { resolve, join, extname } from 'node:path';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { appendFileSync } from 'node:fs';
import { BoundaryError, BoundedCdp, bounded, cleanupOwned, fetchJsonBounded, finalGate, pollBounded } from './cdp-boundary.mjs';

const root = resolve('dist'); const evidenceRoot = join(root, '.smoke'); await mkdir(evidenceRoot, { recursive: true });
const artifacts = await mkdtemp(join(evidenceRoot, 'bounded-'));
const profile = join(artifacts, 'profile'); await mkdir(profile);
const tracePath = join(artifacts, 'boundaries.jsonl'); await writeFile(tracePath, '', { flag: 'wx' });
let traceFailed = false;
const trace = metadata => { try { appendFileSync(tracePath, JSON.stringify(metadata) + '\n'); } catch { traceFailed = true; } };
const deadline = Date.now() + 180000;
const budget = maximum => Math.min(maximum, deadline - Date.now());
let role = 'USER', orders = [], failWrite = false, failAi = false, delayedAi = false;
let googleUser = false, googleLinked = false;
const faucetClaims=new Map();let loseFaucetResponse=false;
const conditionalClaims=new Map();
const futuresPreviews=new Map();let futuresPositions=[];
const quoteId = '0x' + '1'.repeat(64); let expiry = 30;
const agent = { status: 'PARTIAL', answer: '<img src=x onerror=alert(1)> 관측 가능한 범위에서 설명합니다.', uncertainties: ['receipt를 확인할 수 없습니다.'],
  toolEvidence: [{ tool: 'getOrder', status: 'OK', data: { status: 'PENDING_ONCHAIN' } }],
  knowledgeSources: [{ id: 'k1', title: '거래 정책', version: '6' }],
  skill: { id: 'signed-quote-diagnosis', diagnosis: { classification: 'WAITING_OBSERVED' }, trace: [{ stepId: 'CHECK', status: 'DONE', resultCode: 'OK', evidenceRefs: ['t1'] }] } };
const requests = [];
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://fixture');
  if (!url.pathname.startsWith('/api/')) {
    const file = resolve(root, '.' + (url.pathname === '/' ? '/index.html' : url.pathname));
    if (!file.startsWith(root + '\\') && !file.startsWith(root + '/')) { res.writeHead(403).end(); return; }
    try { res.setHeader('Content-Type', ({ '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' })[extname(file)] || 'application/octet-stream'); res.end(await readFile(file)); } catch { res.writeHead(404).end(); } return;
  }
  let text = ''; for await (const chunk of req) text += chunk; const input = text ? JSON.parse(text) : {};
  requests.push({ path: url.pathname, method: req.method, input });
  const now = new Date().toISOString(); let body = {}, status = 200;
  if (url.pathname === '/api/auth/google/config') body = { enabled: true, clientId: 'offline-browser-fixture' };
  else if (url.pathname === '/api/auth/google/challenges' || url.pathname === '/api/me/google/challenges') body = {
    challengeId: 'browser-only-challenge', nonce: 'n'.repeat(43), purpose: url.pathname.startsWith('/api/me/') ? 'LINK' : 'LOGIN', expiresAt: new Date(Date.now()+300000).toISOString() };
  else if (url.pathname === '/api/auth/google/login') { googleUser=true; role='USER'; body={accessToken:'google-fixture-token',expiresIn:3600}; }
  else if (url.pathname === '/api/me/auth-methods') body = { local: !googleUser, google: googleUser || googleLinked };
  else if (url.pathname === '/api/me/google/link') {
    assert.equal(input.password,'fixture-local-password'); googleLinked=true; body={};
  }
  else if (url.pathname.startsWith('/api/auth/')) { googleUser=false; role = input.loginId === 'admin' ? 'ADMIN' : 'USER'; body = { accessToken: 'fixture-token', expiresIn: 3600 }; }
  else if (url.pathname === '/api/me') body = { id:googleUser?3:role==='ADMIN'?2:1,loginId:googleUser?null:'fixture-local',nickname: googleUser?'Google 사용자':role === 'ADMIN' ? '관리자' : '모의 사용자', role };
  else if(url.pathname==='/api/futures/previews') {body={previewId:'future-preview-'+(futuresPreviews.size+1),action:input.action,side:input.side||'LONG',leverage:input.leverage||10,margin:input.marginAmount||'0',price:'75000',expiresAt:new Date(Date.now()+30000).toISOString(),notice:'Offline fixture · 온체인 예치 아님 · 유한 손익 풀'};futuresPreviews.set(body.previewId,body);}
  else if(url.pathname==='/api/futures/positions'&&req.method==='POST') {
    const p=futuresPreviews.get(input.previewId);assert.ok(p);assert.ok(req.headers['idempotency-key']);
    futuresPositions=[{id:'fixture-future',side:p.side,leverage:p.leverage,quantity:'13.333333333333333333',entryPrice:p.price,collateral:p.margin,status:'OPEN',riskStatus:'HEALTHY',version:1,callEpoch:0,deadline:0,pendingOperation:null,blockReason:null,markPrice:'75000',unrealized:'0',equity:p.margin,maintenance:'50000',realized:null,badDebt:null,priceAvailable:true,observedAt:now,createdAt:now}];
    body={id:'fixture-future-open',positionId:'fixture-future',action:'OPEN',state:'PREPARED'};status=202;
  }
  else if(url.pathname==='/api/futures/positions')body=futuresPositions;
  else if(url.pathname==='/api/conditional-orders/capabilities')body={registrationEnabled:true};
  else if(url.pathname==='/api/conditional-orders/requests'){body=conditionalClaims.get(req.headers['idempotency-key']);if(!body){status=404;body={code:'CONDITIONAL_NOT_FOUND'};}}
  else if(url.pathname==='/api/conditional-orders'&&req.method==='POST'){const key=req.headers['idempotency-key'];body=conditionalClaims.get(key)||{...input,id:'condition-'+(conditionalClaims.size+1),state:'WAITING',createdAt:now,updatedAt:now};conditionalClaims.set(key,body);}
  else if(url.pathname==='/api/conditional-orders'||url.pathname==='/api/admin/conditional-orders')body={items:[...conditionalClaims.values()],nextBefore:null};
  else if(url.pathname.startsWith('/api/conditional-orders/')&&url.pathname.endsWith('/cancel')){const id=url.pathname.split('/')[3];body=[...conditionalClaims.values()].find(c=>c.id===id);if(body)body.state='CANCELLED';else{status=404;body={code:'CONDITIONAL_NOT_FOUND'};}}
  else if(url.pathname==='/api/wallet/faucet/status')body={eligible:true,remainingCount:5,decisionDate:'2026-10-11',policyVersion:'kst-v1'};
  else if(url.pathname==='/api/wallet/faucet/grants'||url.pathname==='/api/admin/faucet/grants')body={items:[...faucetClaims.values()],nextBefore:null};
  else if(url.pathname==='/api/admin/faucet')body={activeVersion:'kst-v1',configured:true,spent:'1000000',remainingBudget:'49000000',grantCount:1,journalConsistent:true,rejections:{FAUCET_DAILY_LIMIT:1},rejectionMetricsStartedAt:now};
  else if(url.pathname==='/api/wallet/faucet'||url.pathname==='/api/wallet/faucet/claims'){
    const key=req.headers['idempotency-key'];if(!key){status=400;body={code:'FAUCET_INVALID_KEY'};}else if(req.method==='POST'){
      body=faucetClaims.get(key)||{grantId:'fixture-grant',userId:1,amount:'1000000',receivedAmount:'1000000',balance:'1000000',grantedAt:now,policyVersion:'kst-v1'};faucetClaims.set(key,body);
      if(loseFaucetResponse){res.destroy();return;}
    }else{body=faucetClaims.get(key);if(!body){status=404;body={code:'FAUCET_CLAIM_NOT_FOUND'};}}
  }
  else if (url.pathname.includes('/candles')) {
    const interval = url.searchParams.get('interval'); const max = { '1m': 100, '5m': 100, '15m': 50, '30m': 30, '1h': 20, '1d': 100 }[interval];
    if (+url.searchParams.get('count') > max) { status = 400; body = { code: 'INVALID_CANDLE_QUERY' }; }
    else body = { provider: 'SIMULATED', candles: [{ startedAt: now, open: '75000', high: '76000', low: '74000', close: '75500', volume: '10' }], nextBefore: null };
  }
  else if (url.pathname === '/api/markets/mSEC') body = { price: '75000', change: '1000', changeRate: '1.35', provider: 'SIMULATED', marketStatus: 'UNKNOWN', priceStatus: 'SIMULATED', observedAt: now };
  else if (url.pathname === '/api/portfolio') body = { krwBalance: '1000000', tokenBalance: '2', tokenAvailableAmount: '1.328161833310517989', currentPrice: '75000', averageBuyPrice: '70000', tokenValue: '150000', unrealizedProfit: '10000', totalValue: '1150000' };
  else if (url.pathname.startsWith('/api/quotes/')) body = { side: url.pathname.endsWith('buy') ? 'BUY' : 'SELL', price: '75000', fee: '100', expectedTokenAmount: '1.332', expectedKrwAmount: '74925', minimumOutputAmount: '1.332', quoteId, observedAt: now, validUntil: new Date(Date.now() + expiry * 1000).toISOString(), status: 'ISSUED' };
  else if (url.pathname === '/api/orders') body = orders;
  else if (url.pathname.endsWith('/buy') || url.pathname.endsWith('/sell')) {
    if (failWrite) { status = 503; body = { code: 'BLOCKCHAIN_UNAVAILABLE' }; }
    else { const order = { orderId: orders.length + 1, side: input.krwAmount ? 'BUY' : 'SELL', status: 'PENDING_ONCHAIN', inputAmount: input.krwAmount || input.tokenAmount, outputAmount: '1.3', createdAt: now }; orders.push(order); body = order; status = 202; }
  }
  else if (url.pathname.startsWith('/api/orders/')) body = orders.find((order) => order.orderId === Number(url.pathname.split('/').at(-1)));
  else if (url.pathname === '/api/trades') body = [];
  else if (url.pathname === '/api/admin/transaction-observations') body = {observedAt:now,warningSeconds:300,nextCursor:null,recorder:{storageFailures:0,dropped:0},items:[{id:1,type:'BUY',status:'SIGNED',kind:'USER_TRANSACTION',linkStatus:'ORDER_MISSING',attention:'LONG_WAIT',totalAgeSeconds:301,waitingAgeSeconds:301,warningSeconds:300,observedAt:now,lastAttempt:null}]};
  else if (url.pathname === '/api/admin/transaction-observations/1') body = {id:1,type:'BUY',status:'SIGNED',kind:'USER_TRANSACTION',linkStatus:'ORDER_MISSING',attention:'LONG_WAIT',totalAgeSeconds:301,waitingAgeSeconds:301,warningSeconds:300,observedAt:now,lastAttempt:null,txHash:'0x'+'a'.repeat(64),nonce:'7'};
  else if (url.pathname === '/api/admin/trade-audits') body = req.method === 'POST'
    ? { id: 'audit-fixture', lifecycle: 'RUNNING', verdict: 'INCONCLUSIVE' }
    : { items: [{ id: 'audit-fixture', lifecycle: 'COMPLETED', verdict: 'INCONCLUSIVE', startedAt: now }], nextBefore: null };
  else if (url.pathname === '/api/admin/trade-audits/audit-fixture') body = {
    id: 'audit-fixture', lifecycle: 'COMPLETED', verdict: 'INCONCLUSIVE', reason: 'ITEM_EVIDENCE_INCOMPLETE',
    startedAt: now, coverage: { dbComplete: true, chainComplete: false, cutStable: true, environmentVerified: true }, match: 1, inconclusive: 1 };
  else if (url.pathname === '/api/admin/trade-audits/audit-fixture/items') body = {
    items: [{ key: 'order:1', orderId: 1, mode: 'ONCHAIN', side: 'BUY', verdict: 'INCONCLUSIVE', reason: 'HISTORICAL_CONFIG_UNAVAILABLE', checks: [] }], nextBefore: null };
  else if (url.pathname === '/api/admin/reserve-reconciliations') body = req.method === 'POST'
    ? { id: 'reserve-fixture', status: 'MATCH', reason: 'EXACT_QUANTITY_MATCH', createdAt: now, dbAt: now,
        chain: { block: { number: 123, hash: '0x' + 'a'.repeat(64) } }, checks: [],
        liquidity: { operatorBuyFunds: '1000000000000000000000000', buyAllowance: '1000000000000000000000000',
          operatorSellTokens: '0', vaultSellFunds: '5000000000000000000000000', userKrw: '1000000000000000000000000', userSec: '0', operatorAllocationReference: '1.0000', operatorEthWei: '1000000000000000000', gasAssessment: 'READ_ONLY_NOT_ESTIMATED' } }
    : [{ id: 'reserve-fixture', status: 'MATCH', createdAt: now }];
  else if (url.pathname === '/api/admin/reserve-reconciliations/reserve-fixture') body = {
    id: 'reserve-fixture', status: 'INCONCLUSIVE', reason: 'CUT_CHANGED', createdAt: now, checks: [], liquidity: null };
  else if (url.pathname === '/api/ai/agent/answers') { if (delayedAi) await new Promise((done) => setTimeout(done, 400)); if (failAi) { status = 503; body = { error: 'AGENT_DISABLED' }; } else body = agent; }
  else if (url.pathname === '/api/ai/diagnoses') body = { items: [{ id: 1, orderId: 1, jobStatus: 'COMPLETED', detectedAt: now }], nextBefore: null };
  else if (url.pathname === '/api/ai/diagnoses/1') body = { completedAt: now, targetStale: true, result: { ...agent, responseStatus: 'PARTIAL' } };
  else if (url.pathname === '/api/ai/observability') body = { startedAt: now, diagnosis: { status: 'AVAILABLE' }, series: { 'LLM:call': { calls: 2, failures: 0, averageLatencyMs: 123, reportedInputTokens: 20, reportedOutputTokens: 5, usageUnknownCalls: 0 } } };
  res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body));
});
const chrome = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const connections = new Set();
server.on('connection', socket => { connections.add(socket); socket.once('close', () => connections.delete(socket)); });
let cdp, child, port, childExited = false, spawnFailed = false;
let uiAssertionsPassed = false, failure = 'NOT_STARTED'; const errors = [];
async function poll(operation, timeout = 15000) { return pollBounded(operation, { timeoutMs: budget(timeout), trace }); }
function command(method, params = {}) { return cdp.command(method, params, budget(8000)); }
async function evaluate(expression) { const result = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (result.exceptionDetails) throw new BoundaryError('PAGE_EVALUATION_ERROR'); return result.result.value; }
const click = (id) => evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
const fill = (id, value) => evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));}`);
const submit = (id) => evaluate(`document.getElementById(${JSON.stringify(id)}).requestSubmit()`);
const waitText = (id, text) => poll(() => evaluate(`document.getElementById(${JSON.stringify(id)}).textContent.includes(${JSON.stringify(text)})`));
async function screenshot(name) { const data = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false }); await writeFile(join(artifacts, name + '.png'), Buffer.from(data.data, 'base64')); }
try {
  await bounded('FIXTURE_LISTEN', () => new Promise((done, reject) => {
    server.once('error', reject); server.listen(0, '127.0.0.1', () => { server.removeListener('error', reject); done(); });
  }), { timeoutMs: budget(5000), trace }); port = server.address().port;
  child = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { windowsHide: true, stdio: 'ignore' });
  child.once('exit', () => { childExited = true; }); child.once('error', () => { spawnFailed = true; });
  const debugPort = await poll(async () => {
    if (spawnFailed || childExited) throw new BoundaryError('OWNED_BROWSER_START_FAILED');
    return (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0];
  });
  if (!/^\d{1,5}$/.test(debugPort)) throw new BoundaryError('TARGET_PORT_INVALID');
  const targets = await fetchJsonBounded(`http://127.0.0.1:${debugPort}/json/list`, { timeoutMs: budget(5000), trace });
  const target = targets.find(target => target.type === 'page' && target.url === 'about:blank');
  if (!target) throw new BoundaryError('OWNED_PAGE_MISSING');
  const address = new URL(target.webSocketDebuggerUrl);
  if (address.protocol !== 'ws:' || !['127.0.0.1', 'localhost'].includes(address.hostname) || address.port !== debugPort) throw new BoundaryError('TARGET_SCOPE_INVALID');
  cdp = await BoundedCdp.connect(address.href, { timeoutMs: budget(5000), trace, onEvent: method => { if (method === 'Runtime.exceptionThrown') errors.push('RUNTIME_EXCEPTION'); } });
  await command('Runtime.enable'); await command('Page.enable');
  await command('Page.addScriptToEvaluateOnNewDocument', { source: `document.addEventListener('DOMContentLoaded',()=>{const b=document.createElement('div');b.id='offline-smoke-scope';b.textContent='OFFLINE FIXTURE · Mock HTTP/STOMP/AI · no real RPC, MetaMask signing or asset transactions';b.style.cssText='background:#fff2bb;padding:8px;position:relative;z-index:9999;font:14px sans-serif';document.body.prepend(b);});` });
  // Use compiled STOMP client with a deterministic transport; not a real backend STOMP claim.
  await command('Page.addScriptToEvaluateOnNewDocument', { source: `window.__sockets=[];window.WebSocket=class{static OPEN=1;constructor(){this.readyState=0;this.subs={};window.__sockets.push(this);setTimeout(()=>{this.readyState=1;this.onopen?.({});},20);}send(frame){if(typeof frame!=='string')frame=new TextDecoder().decode(frame);if(frame.startsWith('CONNECT'))setTimeout(()=>this.onmessage?.({data:'CONNECTED\\nversion:1.2\\nheart-beat:0,0\\n\\n\\0'}),5);if(frame.startsWith('SUBSCRIBE')){const id=frame.match(/id:([^\\n]+)/)?.[1],dest=frame.match(/destination:([^\\n]+)/)?.[1];this.subs[dest]=id;}if(frame.startsWith('DISCONNECT')){const r=frame.match(/receipt:([^\\n]+)/)?.[1];if(r)this.onmessage?.({data:'RECEIPT\\nreceipt-id:'+r+'\\n\\n\\0'});}}close(){this.readyState=3;this.onclose?.({});}emit(dest,event){const sub=this.subs[dest];if(sub)this.onmessage?.({data:'MESSAGE\\nsubscription:'+sub+'\\nmessage-id:fixture\\ndestination:'+dest+'\\n\\n'+JSON.stringify(event)+'\\0'});}};` });
  await command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await command('Page.navigate', { url: `http://127.0.0.1:${port}/` });
  await waitText('price', '75,000'); await waitText('connection', '연결됨');
  await waitText('custody-notice', 'PostgreSQL 사용자별 모의자산 장부');
  assert.equal(await evaluate("document.getElementById('custody-notice').getBoundingClientRect().height > 0"), true);
  await waitText('market-mode', 'SIMULATED'); await waitText('market-description', '실제 삼성전자 시세가 아닙니다');
  await waitText('volume-description', '합성 거래량'); await screenshot('desktop-market');
  assert.equal(await evaluate(`document.getElementById('admin-nav').hidden`), true);
  assert.equal(requests.some((request) => request.path === '/api/portfolio'), false);
  await click('auth-open'); await click('auth-switch'); await fill('login-id', 'fixtureuser'); await fill('password', 'password123'); await fill('nickname', '모의 사용자'); await submit('auth-form'); await waitText('identity', '모의 사용자');
  assert.ok(requests.some((request) => request.path === '/api/auth/signup'));
  await waitText('conditional-availability','등록 가능');await fill('conditional-input','100000');await fill('conditional-target','74000');await fill('conditional-expiry','2099-01-01T00:00');await submit('conditional-form');await waitText('conditional-history','가격 조건 대기');
  assert.equal(conditionalClaims.size,1);await evaluate(`document.getElementById('conditional-history').querySelector('button').click()`);
  await poll(async()=>[...conditionalClaims.values()][0].state==='CANCELLED');await waitText('conditional-history','취소');
  await fill('conditional-side','SELL');await fill('conditional-input','1.5');await fill('conditional-target','80000');await submit('conditional-form');await waitText('conditional-history','조건부 매도');assert.equal(conditionalClaims.size,2);
  await command('Emulation.setDeviceMetricsOverride',{width:360,height:800,deviceScaleFactor:1,mobile:true});
  assert.equal(await evaluate(`document.documentElement.scrollWidth<=window.innerWidth`),true,'conditional mobile overflow');
  await command('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await fill('trade-amount', '100000'); await submit('trade-form'); await waitText('quote-summary', '75,000');
  assert.equal(await evaluate(`document.getElementById('sell-all').hidden`), true);
  await click('side-sell');
  const beforeAll = requests.filter(r => r.path.startsWith('/api/quotes/') || /^\/api\/orders\/(buy|sell)$/.test(r.path)).length;
  await click('sell-all'); await waitText('trade-message', '현재 매도 가능 수량을 입력');
  assert.equal(await evaluate(`document.getElementById('trade-amount').value`), '1.328161833310517989');
  assert.equal(await evaluate(`document.getElementById('quote-confirm').disabled`), true);
  assert.equal(requests.filter(r => r.path.startsWith('/api/quotes/') || /^\/api\/orders\/(buy|sell)$/.test(r.path)).length, beforeAll);
  await click('side-buy'); await fill('trade-amount', '100000'); await submit('trade-form'); await waitText('quote-summary', '75,000');
  await fill('trade-amount', '110000'); assert.equal(await evaluate(`document.getElementById('quote-confirm').disabled`), true);
  await submit('trade-form'); await waitText('quote-summary', '75,000'); await click('quote-confirm'); await click('quote-submit'); await waitText('latest-order', '블록체인 확인 중');
  assert.equal(requests.filter((request) => request.path === '/api/orders/buy').length, 1);
  assert.doesNotMatch(await evaluate(`document.getElementById('latest-order').textContent`), /실제 체결가/);
  await evaluate(`window.__sockets.at(-1).emit('/user/queue/orders',{eventId:'fill1',type:'ORDER_FILLED',data:{orderId:1,side:'BUY',status:'FILLED',inputAmount:'110000',outputAmount:'1.3'}})`);
  await waitText('latest-order', '체결 완료');
  // An older REST snapshot must not roll back a terminal event.
  await poll(() => evaluate(`document.getElementById('latest-order').textContent.includes('체결 완료')`));
    await evaluate(`location.hash='portfolio'`); await waitText('portfolio-summary', '평균 매수가');await waitText('faucet-policy','KST');
    loseFaucetResponse=true;await click('faucet');await waitText('faucet-message','응답이 유실');loseFaucetResponse=false;assert.equal(faucetClaims.size,1);
    await click('faucet-resolve');await waitText('faucet-message','이전 성공');assert.equal(faucetClaims.size,1);await screenshot('desktop-portfolio');
  // Product UI + deterministic HTTP/STOMP fixtures only; NOT an actual Engine/liquidation E2E.
  await evaluate(`location.hash='futures'`);await fill('futures-margin','100000.000000000000000001');await fill('futures-leverage','10');
  const futuresBefore=requests.filter(r=>r.path==='/api/futures/positions'&&r.method==='POST').length;
  await click('futures-preview-open');await waitText('futures-preview','75000');
  assert.equal(requests.filter(r=>r.path==='/api/futures/positions'&&r.method==='POST').length,futuresBefore);
  await click('futures-confirm');await waitText('futures-positions','진행 중');
  assert.equal(futuresPositions[0].collateral,'100000.000000000000000001');
  futuresPositions[0].riskStatus='GRACE';futuresPositions[0].deadline=Math.floor(Date.now()/1000)+60;
  await evaluate(`window.__sockets.at(-1).emit('/user/queue/futures',{eventId:'future-call',type:'FUTURES_UPDATED',data:{positionId:'fixture-future',operationId:'fixture-call'}})`);
  await waitText('futures-positions','마진콜');await screenshot('desktop-futures-grace');
  futuresPositions[0].status='LIQUIDATED';futuresPositions[0].realized='-100000';futuresPositions[0].badDebt='100000';
  await evaluate(`window.__sockets.at(-1).emit('/user/queue/futures',{eventId:'future-liquidated',type:'FUTURES_UPDATED',data:{positionId:'fixture-future',operationId:'fixture-liquidation'}})`);
  await waitText('futures-positions','자동청산 확정');
  await evaluate(`location.hash='ai'`); await click('ask-policy'); await submit('ai-form'); await waitText('ai-result', '정책 출처');
  assert.equal(await evaluate(`document.getElementById('ai-result').querySelector('img')===null`), true); await screenshot('desktop-ai');
  failAi = true; await submit('ai-form'); await waitText('ai-message', '준비되지'); failAi = false;
  assert.equal(await evaluate(`document.getElementById('logout').hidden`), false);
  // Late private AI response after logout cannot restore the old user's analysis.
  delayedAi = true; await submit('ai-form'); await click('logout'); await new Promise((done) => setTimeout(done, 650));
  assert.equal(await evaluate(`document.getElementById('ai-result').textContent`), ''); delayedAi = false;
  await click('auth-open'); await click('auth-switch'); await fill('login-id', 'admin'); await fill('password', 'password123'); await submit('auth-form'); await waitText('identity', '관리자');
  await evaluate(`location.hash='admin'`); await waitText('admin-history', '진단 #1'); await waitText('observation', 'LLM:call');
  await waitText('conditional-admin-history','조건부 매도');
  await waitText('tx-observation-list','LONG_WAIT');await waitText('tx-observation-list','UNRECORDED');
  assert.equal(await evaluate("document.getElementById('tx-observation-list').textContent.includes('txHash')"),false);
  await evaluate("document.getElementById('tx-observation-list').querySelector('button').click()");await waitText('tx-observation-detail','txHash');
  await fill('tx-observation-status','SUBMITTED');await evaluate("document.getElementById('tx-observation-status').dispatchEvent(new Event('change'))");await waitText('tx-observation-list','LONG_WAIT');
  await evaluate(`document.getElementById('admin-history').querySelector('button').click()`); await waitText('admin-result', '과거 관측'); await screenshot('desktop-admin');
  await click('reserve-run'); await waitText('reserve-result', '장부·체인 수량 일치');
  await waitText('reserve-result', '1 ETH'); await waitText('reserve-result', '비용 미추정');
  await waitText('reserve-result', '준비금 보증 비율이나 Proof of Reserves가 아니며');
  await click('reserve-refresh'); await waitText('reserve-history', '최근 대사 20건');
  await evaluate(`document.getElementById('reserve-history').querySelector('button').click()`);
  await waitText('reserve-result', '동일 시점·근거 확인 불가');
  await evaluate(`window.confirm=()=>true`); await click('audit-run');
  await waitText('audit-result', '전수 MATCH를 뜻하지 않습니다');
  await waitText('audit-items', 'HISTORICAL_CONFIG_UNAVAILABLE');
  await click('audit-refresh'); await waitText('audit-history', 'INCONCLUSIVE');
  await command('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 1, mobile: true });
  for (const route of ['market', 'futures', 'history', 'portfolio', 'ai', 'admin']) {
    await evaluate(`location.hash=${JSON.stringify(route)}`); await new Promise((done) => setTimeout(done, 120));
    assert.equal(await evaluate(`document.documentElement.scrollWidth<=window.innerWidth`), true, 'horizontal overflow: ' + route);
    assert.equal(await evaluate("document.getElementById('custody-notice').getBoundingClientRect().height > 0"), true, 'custody notice hidden: ' + route);
    await screenshot('mobile-' + route);
  }
  for (const interval of ['1m', '5m', '15m', '30m', '1h', '1d']) await evaluate(`document.querySelector('[data-interval="${interval}"]').click()`);
  await evaluate(`location.hash='market'`); await click('side-sell'); await click('sell-all'); await waitText('trade-message', '현재 매도 가능 수량을 입력');
  assert.equal(await evaluate(`document.getElementById('trade-amount').value`), '1.328161833310517989');
  assert.equal(await evaluate(`document.documentElement.scrollWidth<=window.innerWidth`), true, 'sell-all mobile overflow');
  await fill('trade-amount', '1'); expiry = 1; await submit('trade-form'); await waitText('quote-summary', '75,000');
  // Wait for the real 1s UI timer rather than assuming its phase relative to a fixed 1300ms sleep.
  await waitText('quote-timer', '견적이 만료됐습니다');
  assert.equal(await evaluate(`document.getElementById('quote-confirm').disabled`), true); expiry = 30;
  await submit('trade-form'); await waitText('quote-summary', '75,000'); await click('quote-confirm'); await screenshot('mobile-quote-sheet');
  failWrite = true; await click('quote-submit'); await waitText('trade-message', '자동 재주문하지');
  assert.equal(await evaluate(`document.getElementById('quote-request').disabled`), true);
  assert.equal(requests.filter((request) => request.path === '/api/orders/sell').length, 1);
  await click('resolve-order'); await waitText('history-list', '블록체인 확인 중');
  // Offline GIS adapter: no Google script/network. Server cryptography is tested separately with real RSA.
  assert.equal(await evaluate(`document.querySelectorAll('script[src^="https://accounts.google.com"]').length`),0);
  await evaluate(`window.google={accounts:{id:{initialize(options){this.options=options;},renderButton(container){const callback=this.options.callback;const b=document.createElement('button');b.type='button';b.id='fixture-google-button';b.textContent='Offline Google';b.onclick=()=>callback({credential:'offline-fixture-proof'});container.replaceChildren(b);}}}}`);
  await click('logout'); await click('auth-open'); await fill('login-id','local_fixture'); await fill('password','fixture-local-password'); await submit('auth-form');
  await poll(()=>evaluate(`!document.getElementById('logout').hidden`)); await evaluate(`location.hash='portfolio'`);
  await poll(()=>evaluate(`!document.getElementById('google-start').hidden`));
  await fill('google-link-password','fixture-local-password'); await click('google-start');
  await poll(()=>evaluate(`Boolean(document.getElementById('fixture-google-button'))`)); await click('fixture-google-button');
  await waitText('google-status','Google을 연결했습니다');
  assert.equal(requests.filter(r=>r.path==='/api/me/google/link').length,1);
  await click('logout'); await click('auth-open'); await click('google-start');
  await poll(()=>evaluate(`Boolean(document.getElementById('fixture-google-button'))`)); await click('fixture-google-button');
  await waitText('identity','Google 사용자'); await evaluate(`location.hash='portfolio'`); await waitText('google-status','Google 연결됨');
  assert.equal(await evaluate(`document.getElementById('google-start').hidden`),true);
  await click('logout'); await click('auth-open');
  assert.equal(await evaluate(`document.getElementById('auth-form').contains(document.getElementById('google-start')) && document.getElementById('google-start').getClientRects().length>0`),true);
  await click('google-start'); await poll(()=>evaluate(`Boolean(document.getElementById('fixture-google-button'))`)); await click('fixture-google-button');
  await waitText('identity','Google 사용자');
  for (const [width, height, label] of [[1366, 768, 'presentation'], [390, 844, 'mobile390']]) {
    await command('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width === 390 });
    await evaluate(`location.hash='custody'`);
    await waitText('c-message', '미지원');
    await screenshot(label + '-c-offline-unavailable');
  }
  assert.deepEqual(errors, []); assert.equal(traceFailed, false, 'boundary trace write failed');
  uiAssertionsPassed = true; failure = null;
  console.log('UI_ASSERTIONS_PASS: final Gate still requires confirmed owned-resource cleanup and safe trace.');
} catch (error) {
  failure = error instanceof BoundaryError ? error.code : 'SMOKE_ASSERTION_OR_IO_ERROR';
  // Do not print error text: CDP/page assertions can contain private page content.
  console.error('FAIL browser smoke:', failure); process.exitCode = 1;
} finally {
  const closeOutcome = cdp ? await cdp.close(1000) : 'NOT_CONNECTED';
  const cleanup = await cleanupOwned({ child, hasExited: () => childExited, spawnFailed, server, connections, trace });
  const result = { ...finalGate({ uiAssertionsPassed, ...cleanup, traceFailed, failure }), uiAssertionsPassed,
    scope: 'OFFLINE_HTTP_STOMP_AI_FIXTURE_C_UNAVAILABLE', closeOutcome, ...cleanup, spawnFailed, traceFailed };
  try { await writeFile(join(artifacts, 'result.json'), JSON.stringify(result, null, 2), { flag: 'wx' }); }
  catch { result.verdict = 'BLOCKED'; result.failure = 'RESULT_WRITE_FAILED'; }
  process.exitCode = result.verdict === 'PASS' ? 0 : 1;
  console.log(result.verdict + ' browser smoke:', result.failure ?? 'OFFLINE_FIXTURE_ONLY');
  console.log('Evidence:', artifacts);
}
