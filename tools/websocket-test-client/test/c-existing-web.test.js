import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { ApiClient } from '../src/api.js';
import { createCApiAdapter } from '../src/c-api-adapter.js';
import { CDirectWallet } from '../src/c-direct-wallet.js';
import { units, decimal, quantityRange, hash, uint } from '../src/c-direct-intent.js';

test('C adapter uses current shared JWT, unwraps transport only and preserves idempotency', async () => {
  const previous = globalThis.fetch, calls = [];
  globalThis.fetch = async (path, options) => { calls.push({path,...options}); return {ok:true,status:200,text:async()=>JSON.stringify({value:{free:'900719925474099312345678'}})}; };
  try {
    const client = new ApiClient(), api = createCApiAdapter(client);
    client.setToken('first'); assert.equal((await api.get('/api/c-futures/account')).value.free,'900719925474099312345678');
    client.setToken('second'); await api.post('/api/c-futures/direct/intents', {leverage:'2'}, 'same-key');
    assert.equal(calls[0].headers.Authorization,'Bearer first'); assert.equal(calls[1].headers.Authorization,'Bearer second');
    assert.equal(calls[1].headers['Idempotency-Key'],'same-key');
    assert.throws(()=>client.cRequest('/api/auth/login'));
    client.clearToken(); await api.get('/api/c-futures/config'); assert.equal(calls[2].headers.Authorization,undefined);
  } finally { globalThis.fetch = previous; }
});
function harness(client) {
  client.market ??= () => client.cRequest('/api/markets/mSEC');
  const elements = new Map(), events = {}, storage = new Map(), timers = new Map();
  let clock = 1800000000, timerId = 0;
  const element = () => ({value:'',checked:false,disabled:false,children:[],textContent:'',
    append(...c){this.children.push(...c);},replaceChildren(...c){this.children=c;},addEventListener(){}});
  const document = {getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id);},createElement:element};
  const source = readFileSync(new URL('../src/c-direct-panel.js',import.meta.url),'utf8').replace(/^import .*;\r?\n/gm,'').replace('export function','function');
  const context = vm.createContext({CDirectWallet,createCApiAdapter,units,decimal,quantityRange,hash,uint,document,globalThis:{}});
  vm.runInContext(source+';globalThis.create=createCDirectPanel;',context);
  const panel = context.globalThis.create(client,()=>{}, {document, provider:{on(e,f){events[e]=f;}},
    storage:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},locks:{},
    now:()=>clock,setInterval(fn,ms){assert.equal(ms,1000);timers.set(++timerId,fn);return timerId;},clearInterval(id){timers.delete(id);}});
  return {panel,elements,storage,events,timers,tick(seconds){clock+=seconds;for(const fn of timers.values())fn();}};
}
const cfg={custody:'C_ERC20_VAULT_CANONICAL_ASSETS',chainId:'31337',genesisHash:'0x'+'1'.repeat(64),deploymentId:'2'.repeat(64),
  token:'0x'+'1'.repeat(40),vault:'0x'+'2'.repeat(40),controller:'0x'+'3'.repeat(40),relayer:'0x'+'4'.repeat(40)};

function fillOpen(h) {
  for(const [id,value] of Object.entries({'c-action':'OPEN','c-side':'LONG','c-leverage':'2','c-collateral':'1000','c-min-price':'75000','c-max-price':'75500'}))h.elements.get(id).value=value;
}

test('draft input failure is visible beside the button as well as at the page top, without a POST',async()=>{
  const calls=[];const h=harness({cRequest:async path=>{calls.push(path);return {body:{value:cfg}};}});
  await h.panel.setContext({id:1},'custody');fillOpen(h);h.elements.get('c-min-price').value='';
  await h.elements.get('c-prepare').onclick();
  assert.match(h.elements.get('c-action-message').textContent,/소수점/);
  assert.equal(h.elements.get('c-action-message').textContent,h.elements.get('c-message').textContent);
  assert.equal(calls.length,1);assert.ok(!calls.some(p=>p.includes('/direct/')));
});

test('draft click shows progress immediately, then the unchanged blocking reason beside the button',async()=>{
  const h=harness({cRequest:async()=>({body:{value:cfg}})});await h.panel.setContext({id:1},'custody');fillOpen(h);
  let fail;h.panel.wallet.prepare=()=>new Promise((resolve,reject)=>{fail=reject;});
  const pending=h.elements.get('c-prepare').onclick();
  assert.match(h.elements.get('c-action-message').textContent,/draft 요청 준비 중/);
  fail(new Error('이전 거래를 먼저 조회하세요. 자동 재주문하지 않습니다.'));await pending;
  assert.match(h.elements.get('c-action-message').textContent,/이전 거래를 먼저 조회/);
  assert.equal(h.elements.get('c-action-message').textContent,h.elements.get('c-message').textContent);
});

test('disabled draft controls explain config, busy and read-only reasons without unlocking them',async()=>{
  const h=harness({cRequest:async()=>({body:{value:cfg}})});await h.panel.setContext({id:1},'custody');
  assert.match(h.elements.get('c-control-state').textContent,/버튼 사용 가능/);
  h.panel.wallet.busy=true;h.panel.wallet.changed();
  assert.equal(h.elements.get('c-prepare').disabled,true);assert.match(h.elements.get('c-control-state').textContent,/처리 중/);
  h.panel.wallet.busy=false;h.panel.wallet.cfg=null;h.panel.wallet.changed();
  assert.equal(h.elements.get('c-prepare').disabled,true);assert.match(h.elements.get('c-control-state').textContent,/설정 확인 전/);
  h.panel.wallet.cfg={...cfg,readOnly:'true'};h.panel.wallet.changed();
  assert.equal(h.elements.get('c-prepare').disabled,true);assert.match(h.elements.get('c-control-state').textContent,/조회 전용/);
});

test('late draft failure after account switch or logout cannot expose the previous feedback',async()=>{
  const h=harness({cRequest:async()=>({body:{value:cfg}})});await h.panel.setContext({id:1},'custody');fillOpen(h);
  let fail;h.panel.wallet.prepare=()=>new Promise((resolve,reject)=>{fail=reject;});
  const pending=h.elements.get('c-prepare').onclick();await h.panel.setContext(null,'market');
  fail(new Error('previous account message'));await pending;
  assert.equal(h.elements.get('c-action-message').textContent,'');assert.equal(h.elements.get('c-control-state').textContent,'');
});

test('inline action feedback is placed between draft controls and draft output with a live status role',()=>{
  const html=readFileSync(new URL('../index.html',import.meta.url),'utf8');
  assert.ok(html.indexOf('id="c-prepare"')<html.indexOf('id="c-action-message"'));
  assert.ok(html.indexOf('id="c-action-message"')<html.indexOf('id="c-draft"'));
  assert.match(html,/<p id="c-action-message"[^>]*role="status"[^>]*aria-live="polite"/);
});
test('unsupported C server disables controls without signing or fake balances',async()=>{
  const h=harness({cRequest:async()=>{throw {status:404};}});
  await h.panel.setContext({id:1},'custody');
  assert.match(h.elements.get('c-message').textContent,/미지원/);
  for(const id of ['c-connect','c-send','c-deposit'])assert.equal(h.elements.get(id).disabled,true);
  assert.equal(h.elements.get('c-account').children.length,0);
});
test('late config after route/logout cannot restore C config and does not erase stored pending',async()=>{
  let finish; const h=harness({cRequest:()=>new Promise(r=>finish=r)});
  h.storage.set('old-submitted','preserved');
  const pending=h.panel.setContext({id:1},'custody');
  await h.panel.setContext(null,'market'); finish({body:{value:cfg}}); await pending;
  assert.equal(h.panel.wallet.cfg,null);assert.equal(h.storage.get('old-submitted'),'preserved');
});
test('account/network changes invalidate execution without erasing pending metadata',async()=>{
  const h=harness({cRequest:async()=>({body:{value:cfg}})});
  await h.panel.setContext({id:1},'custody');
  h.storage.set('old-submitted','preserved'); h.panel.wallet.execution={};h.elements.get('c-consent').checked=true;
  h.elements.get('c-account').children=['previous account'];h.elements.get('c-draft').children=['previous calldata'];
  h.events.accountsChanged();
  assert.equal(h.panel.wallet.execution,null);assert.equal(h.elements.get('c-consent').checked,false);
  assert.deepEqual(h.elements.get('c-account').children,[]);assert.deepEqual(h.elements.get('c-draft').children,[]);
  assert.equal(h.storage.get('old-submitted'),'preserved');
});
test('Report countdown expires send without repricing, fails closed on corrupt pending and cleans route/logout timer',async()=>{
  const h=harness({cRequest:async()=>({body:{value:cfg}})});
  await h.panel.setContext({id:1},'custody');
  assert.equal(h.timers.size,1);
  await h.panel.setContext({id:1},'custody');assert.equal(h.timers.size,1);
  const report={validUntil:'1800000030'}, pending={phase:'EXECUTION_READY',execution:{report}};
  h.panel.wallet.pending=()=>pending;h.panel.wallet.execution={report};h.elements.get('c-consent').checked=true;
  h.tick(0);assert.match(h.elements.get('c-deadline').textContent,/남은 30초/);assert.equal(h.elements.get('c-send').disabled,false);
  h.tick(29);assert.match(h.elements.get('c-deadline').textContent,/남은 1초/);
  h.tick(1);assert.match(h.elements.get('c-deadline').textContent,/남은 0초/);assert.equal(h.elements.get('c-send').disabled,true);
  assert.equal(report.validUntil,'1800000030');assert.equal(pending.phase,'EXECUTION_READY');
  h.panel.wallet.pending=()=>{throw new Error('corrupt pending');};h.tick(1);
  assert.equal(h.elements.get('c-deposit').disabled,true);assert.match(h.elements.get('c-deadline').textContent,/차단/);
  await h.panel.setContext({id:1},'market');assert.equal(h.timers.size,0);
  await h.panel.setContext({id:1},'custody');assert.equal(h.timers.size,1);
  await h.panel.setContext(null,'market');assert.equal(h.timers.size,0);
});
function deferred() { let resolve; const promise=new Promise(r=>resolve=r); return {promise,resolve}; }
const flush=async()=>{for(let i=0;i<8;i++)await Promise.resolve();};
const textTree=e=>[e.textContent,...e.children.map(textTree)].join(' ');

test('a late receipt cannot repopulate result or history after chain, account or logout invalidation',async()=>{
  for(const change of ['chain','account','logout']){
    const h=harness({cRequest:async()=>({body:{value:cfg}})});await h.panel.setContext({id:1},'custody');
    const reply=deferred();h.panel.wallet.resolve=()=>reply.promise;
    h.elements.get('c-resolve').onclick();await flush();
    if(change==='chain')h.events.chainChanged();else await h.panel.setContext(change==='account'?{id:2}:null,change==='account'?'custody':'market');
    reply.resolve({status:'CONFIRMED',txHash:'0x'+'c'.repeat(64),blockHash:'0x'+'d'.repeat(64),kind:'OPEN'});await flush();
    assert.equal(h.elements.get('c-result').children.length,0,change);assert.equal(h.elements.get('c-history').children.length,0,change);
  }
});

test('session receipt history is confirmed-only, deduplicated and cleared on wallet or JWT changes',async()=>{
  const h=harness({cRequest:async path=>({body:path.endsWith('/config')?{value:cfg}:{value:{walletBalance:'0',free:'0',locked:'0',house:'0',totalUnpaidProfit:'0',allowanceToVault:'0'}}}),market:async()=>({body:{price:'75000'}})});
  await h.panel.setContext({id:1},'custody');
  let result={status:'PENDING',txHash:'0x'+'a'.repeat(64)};
  h.panel.wallet.resolve=async()=>result;
  h.elements.get('c-resolve').onclick();await flush();assert.equal(h.elements.get('c-history').children.length,0);
  result={...result,status:'CONFIRMED',blockHash:'0x'+'b'.repeat(64),kind:'APPROVE'};
  h.elements.get('c-resolve').onclick();await flush();assert.match(textTree(h.elements.get('c-history')),/APPROVE/);
  const count=h.elements.get('c-history').children.length;
  h.elements.get('c-resolve').onclick();await flush();assert.equal(h.elements.get('c-history').children.length,count);
  h.events.chainChanged();assert.equal(h.elements.get('c-history').children.length,0);
  h.elements.get('c-resolve').onclick();await flush();
  await h.panel.setContext({id:2},'custody');assert.equal(h.elements.get('c-history').children.length,0);
  await h.panel.setContext(null,'market');assert.equal(h.elements.get('c-history').children.length,0);
});

test('read-only C config keeps wallet/account reads but disables every financial control',async()=>{
  const calls=[];
  const h=harness({cRequest:async path=>{calls.push(path);return {body:path.endsWith('/config')?{value:{...cfg,readOnly:'true'}}:{value:{walletBalance:'1000000000000000000',free:'0',locked:'0',house:'0',totalUnpaidProfit:'0',allowanceToVault:'0'}}};},
    market:async()=>{calls.push('/api/markets/mSEC');return {body:{price:'75000',provider:'SIMULATED'}};}});
  await h.panel.setContext({id:1},'custody');h.panel.wallet.linked=async()=>{};
  await h.elements.get('c-refresh').onclick();
  assert.match(textTree(h.elements.get('c-account')),/1 cMKRW/);
  assert.ok(calls.includes('/api/markets/mSEC'));assert.ok(!calls.includes('/api/c-futures/direct/market'));
  for(const id of ['c-approve','c-deposit','c-withdraw','c-prepare','c-price','c-send','c-resolve'])assert.equal(h.elements.get(id).disabled,true,id);
  for(const id of ['c-connect','c-link','c-refresh','c-position-read'])assert.equal(h.elements.get(id).disabled,false,id);
  h.tick(2);assert.equal(h.elements.get('c-send').disabled,true);
});

test('switching JWT accounts clears prior assets, position and identity before config resolves',async()=>{
  let finish;let first=true;
  const h=harness({cRequest:async()=>first?{body:{value:cfg}}:new Promise(r=>finish=r)});
  await h.panel.setContext({id:1,loginId:'alice'},'custody');
  h.elements.get('c-account').children=['alice balance'];h.elements.get('c-position').children=['alice position'];
  first=false;const pending=h.panel.setContext({id:2,loginId:'bob'},'custody');
  assert.equal(h.elements.get('c-account').children.length,0);assert.equal(h.elements.get('c-position').children.length,0);
  assert.doesNotMatch(textTree(h.elements.get('c-identity')),/alice/);
  finish({body:{value:{...cfg,readOnly:'true'}}});await pending;
  assert.match(textTree(h.elements.get('c-identity')),/bob/);
  await h.panel.setContext(null,'market');assert.equal(h.elements.get('c-identity').children.length,0);
});
test('same-epoch refresh renders latest account and market pair, never older reversed responses',async()=>{
  const accounts=[],markets=[];
  const h=harness({cRequest(path){
    if(path.endsWith('/config'))return Promise.resolve({body:{value:cfg}});
    const d=deferred();(path.endsWith('/account')?accounts:markets).push(d);return d.promise;
  }});
  await h.panel.setContext({id:1},'custody');h.panel.wallet.linked=async()=>{};
  const first=h.elements.get('c-refresh').onclick();await flush();
  const second=h.elements.get('c-refresh').onclick();await flush();
  const account=n=>({body:{value:{walletBalance:String(BigInt(n)*10n**18n),free:'0',locked:'0',house:'0',totalUnpaidProfit:'0',allowanceToVault:'0'}}});
  accounts[1].resolve(account(2));await flush();assert.equal(h.elements.get('c-account').children.length,0);
  markets[1].resolve({body:{price:'new-price'}});await second;
  assert.match(textTree(h.elements.get('c-account')),/2 cMKRW/);assert.match(h.elements.get('c-market').textContent,/new-price/);
  accounts[0].resolve(account(1));markets[0].resolve({body:{price:'old-price'}});await first;
  assert.doesNotMatch(textTree(h.elements.get('c-account')),/1 cMKRW/);assert.match(h.elements.get('c-market').textContent,/new-price/);
  const third=h.elements.get('c-refresh').onclick();await flush();h.events.accountsChanged();
  accounts[2].resolve(account(3));markets[2].resolve({body:{price:'after-account-change'}});await third;
  assert.equal(h.elements.get('c-account').children.length,0);assert.doesNotMatch(h.elements.get('c-market').textContent,/after-account-change/);
});
test('position reads capture input and latest serial, so reversed IDs cannot restore old selected CLOSE fields',async()=>{
  const reads=[], id1='0x'+'1'.repeat(64),id2='0x'+'2'.repeat(64),id3='0x'+'3'.repeat(64);
  const h=harness({cRequest(path){
    if(path.endsWith('/config'))return Promise.resolve({body:{value:cfg}});
    const d=deferred();reads.push({path,...d});return d.promise;
  }});
  await h.panel.setContext({id:1},'custody');h.panel.wallet.linked=async()=>{};
  h.elements.get('c-action').value='CLOSE';h.elements.get('c-position-id').value=id1;
  const first=h.elements.get('c-position-read').onclick();await flush();
  h.elements.get('c-position-id').value=id2;const second=h.elements.get('c-position-read').onclick();await flush();
  assert.ok(reads[0].path.endsWith(id1));assert.ok(reads[1].path.endsWith(id2));
  const position=(id,n)=>({body:{value:{positionId:id,status:'ACTIVE',side:'0',leverage:'2',entryPriceE8:'100000000',quantity:'1000000000000000000',historicalCollateral:String(BigInt(n)*10n**18n),lockedCollateral:'0'}}});
  reads[1].resolve(position(id2,2));await second;assert.equal(h.elements.get('c-collateral').value,'2');
  reads[0].resolve(position(id1,1));await first;
  assert.equal(h.elements.get('c-collateral').value,'2');assert.match(textTree(h.elements.get('c-position')),new RegExp(id2));assert.doesNotMatch(textTree(h.elements.get('c-position')),new RegExp(id1));
  const third=h.elements.get('c-position-read').onclick();await flush();h.elements.get('c-position-id').value=id3;
  reads[2].resolve(position(id2,3));await third;assert.equal(h.elements.get('c-collateral').value,'2');
  const fourth=h.elements.get('c-position-read').onclick();await flush();await h.panel.setContext(null,'market');
  reads[3].resolve(position(id3,4));await fourth;assert.equal(h.elements.get('c-position').children.length,0);
});
