import test from 'node:test';
import assert from 'node:assert/strict';
import { futuresAmount, futureIntent, createFuturesPanel } from '../src/futures-panel.js';
import { ApiClient } from '../src/api.js';

test('futures money remains an exact string, including one wei', () => {
  for (const value of ['100000', '0.000000000000000001', '999999999999.123456789012345678']) assert.equal(futuresAmount(value), value);
  for (const value of ['0', '-1', '1e5', '1.0000000000000000001', ' 1', 1, '', '1000000000000']) assert.throws(() => futuresAmount(value));
});
const storage = () => { const values = new Map(); return { getItem: k => values.get(k) ?? null, setItem: (k, v) => values.set(k, v) }; };
test('lost response reuses original key and rejects a different intent', async () => {
  const store = storage(), request = { path: '/positions', body: { previewId: 'p' } };
  const first = await futureIntent(1, request, store, null), replay = await futureIntent(1, request, store, null);
  assert.deepEqual(first, replay); await assert.rejects(futureIntent(1, { path: '/positions/p/margin', body: { amount: '1' } }, store, null));
  assert.notEqual((await futureIntent(2, request, store, null)).key, first.key);
});
test('multiple tabs use the same per-user Web Lock and pending key', async () => {
  const store = storage(), locks = { request: async (name, fn) => { assert.equal(name, 'futures-intent:7'); return fn(); } }, request = { path: '/positions', body: { previewId: 'p' } };
  const [a, b] = await Promise.all([futureIntent(7, request, store, locks), futureIntent(7, request, store, locks)]); assert.equal(a.key, b.key);
});

class Element {
  constructor() { this.children = []; this.text = ''; this.disabled = false; this.value = ''; }
  set textContent(v) { this.text = String(v ?? ''); this.children = []; }
  get textContent() { return this.text + this.children.map(x => x.textContent).join(' '); }
  append(...x) { this.children.push(...x); } replaceChildren(...x) { this.text = ''; this.children = x; }
  addEventListener(name, fn) { this['on' + name] = fn; }
}
function setup(t, api) {
  const ids = new Map(['message','confirm','preview','preview-open','retry','positions','side','leverage','margin','refresh'].map(x => ['futures-' + x, new Element()]));
  globalThis.document = { getElementById: x => ids.get(x), createElement: () => new Element() };
  const store = storage(); store.removeItem = k => store.setItem(k, null); globalThis.localStorage = store;
  ids.get('futures-side').value = 'LONG'; ids.get('futures-leverage').value = '10'; ids.get('futures-margin').value = '100000.000000000000000001';
  const panel = createFuturesPanel({ futuresPositions: async () => ({ body: [] }), ...api });
  t.after(() => panel.setContext(null, 'market')); panel.setContext({ id: 1 }, 'futures'); return { panel, ids };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
const preview = id => ({ body: { previewId: id, action: 'OPEN', price: '75000', margin: '100000.000000000000000001', expiresAt: '2099-01-01T00:00:00Z', notice: 'fixture' } });
test('preview never sends an order and explicit confirm retains exact decimal intent', async t => {
  let writes = 0, received; const { ids } = setup(t, { futuresPreview: async r => { received = r; return preview('p'); }, futuresWrite: async () => { writes++; return { body: { id: 'operation', state: 'PREPARED' } }; } });
  ids.get('futures-preview-open').onclick(); await flush(); assert.equal(writes, 0); assert.equal(received.marginAmount, '100000.000000000000000001');
  ids.get('futures-confirm').onclick(); ids.get('futures-confirm').onclick(); await flush(); assert.equal(writes, 1);
});
test('lost response is retained and manual retry uses the same key', async t => {
  const calls = []; const { ids } = setup(t, { futuresPreview: async () => preview('p'), futuresWrite: async (...args) => { calls.push(args); throw new TypeError('offline'); } });
  ids.get('futures-preview-open').onclick(); await flush(); ids.get('futures-confirm').onclick(); await flush();
  const pending = localStorage.getItem('futures-pending:1'); assert.ok(pending); ids.get('futures-retry').onclick(); await flush();
  assert.equal(calls.length, 2); assert.equal(calls[0][2], calls[1][2]); assert.equal(localStorage.getItem('futures-pending:1'), pending);
});
test('logout drops late private reads and late preview callbacks', async t => {
  let read, response; const { panel, ids } = setup(t, { futuresPositions: () => new Promise(resolve => { read = resolve; }), futuresPreview: () => new Promise(resolve => { response = resolve; }) });
  ids.get('futures-preview-open').onclick(); panel.setContext(null, 'market');
  read({ body: [{ id: 'private' }] }); response(preview('private')); await flush();
  assert.equal(ids.get('futures-positions').textContent, ''); assert.equal(ids.get('futures-preview').textContent, ''); assert.equal(ids.get('futures-confirm').disabled, true);
});
test('logout drops a late accepted write without displaying another user operation', async t => {
  let finish; const { panel, ids } = setup(t, { futuresPreview: async () => preview('p'), futuresWrite: () => new Promise(resolve => { finish = resolve; }) });
  ids.get('futures-preview-open').onclick(); await flush(); ids.get('futures-confirm').onclick(); await flush(); panel.setContext({ id: 2 }, 'futures');
  finish({ body: { id: 'user-one-private', state: 'CONFIRMED' } }); await flush(); assert.doesNotMatch(ids.get('futures-message').textContent, /user-one-private/);
});
test('futures writes allow only the bounded futures routes and put idempotency in headers', async () => {
  const calls = []; globalThis.fetch = async (path, options) => { calls.push({ path, options }); return { ok: true, status: 202, text: async () => '{}' }; };
  const api = new ApiClient(); await api.futuresWrite('/positions', { previewId: 'p' }, 'opaque-futures-key');
  assert.equal(calls[0].path, '/api/futures/positions'); assert.equal(calls[0].options.headers['Idempotency-Key'], 'opaque-futures-key'); assert.deepEqual(JSON.parse(calls[0].options.body), { previewId: 'p' });
  for (const path of ['/../orders/buy', '/positions/a/b/close', '/positions/p/unlock']) assert.throws(() => api.futuresWrite(path, {}, 'k'));
});
test('input changes invalidate an in-flight preview and it cannot enable confirmation', async t => {
  let finish;const { ids }=setup(t,{futuresPreview:()=>new Promise(resolve=>{finish=resolve;})});
  ids.get('futures-preview-open').onclick();ids.get('futures-side').value='SHORT';ids.get('futures-side').oninput();finish(preview('old-long'));await flush();
  assert.equal(ids.get('futures-preview').textContent,'');assert.equal(ids.get('futures-confirm').disabled,true);
});
test('only serialized definitive rejection clears pending and permits a fresh preview',async t=>{
  let calls=0;const {ids}=setup(t,{futuresPreview:async()=>preview('p'+calls),futuresWrite:async()=>{calls++;throw {status:409,body:{code:'FUTURES_REQUEST_REJECTED'}};}});
  ids.get('futures-preview-open').onclick();await flush();ids.get('futures-confirm').onclick();await flush();
  assert.equal(localStorage.getItem('futures-pending:1'),null);assert.match(ids.get('futures-message').textContent,/미실행 거절/);
  ids.get('futures-preview-open').onclick();await flush();ids.get('futures-confirm').onclick();await flush();assert.equal(calls,2);
});
test('unrecognized 409 is still uncertain and never discards the key',async t=>{
  const {ids}=setup(t,{futuresPreview:async()=>preview('p'),futuresWrite:async()=>{throw {status:409,body:{code:'PROXY_CONFLICT'}};}});
  ids.get('futures-preview-open').onclick();await flush();ids.get('futures-confirm').onclick();await flush();assert.ok(localStorage.getItem('futures-pending:1'));
});
test('REST or WS refresh preserves the exact topup draft',async t=>{
  const position={id:'p',side:'LONG',leverage:10,quantity:'1',entryPrice:'75000',collateral:'100000',status:'OPEN',riskStatus:'HEALTHY',version:1};
  const {ids,panel}=setup(t,{futuresPositions:async()=>({body:[position]})});await flush();
  const input=()=>ids.get('futures-positions').children[0].children.find(x=>x.children.some?.(y=>y.placeholder==='추가할 금액')).children[0];
  input().value='0.000000000000000001';input().oninput();await panel.refresh();assert.equal(input().value,'0.000000000000000001');
});
