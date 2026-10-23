import test from 'node:test';
import assert from 'node:assert/strict';
import { createFuturesAuditPanel, renderFuturesAudit } from '../src/futures-audit-panel.js';
import { ApiClient } from '../src/api.js';

class Element {
  children = []; text = ''; dataset = {}; disabled = false; hidden = false;
  set textContent(v) { this.text = String(v ?? ''); this.children = []; }
  get textContent() { return this.text + this.children.map(c => c.textContent).join(' '); }
  append(...v) { this.children.push(...v); } replaceChildren(...v) { this.text = ''; this.children = v; }
  addEventListener(name, fn) { this['on' + name] = fn; }
}
function setup(api = {}, unauthorized) {
  const ids = new Map(['panel', 'baseline', 'run', 'refresh', 'message', 'history', 'result'].map(id => [id, new Element()]));
  globalThis.document = { createElement: () => new Element(), getElementById: id => ids.get(id.replace('futures-audit-', '')) };
  const panel = createFuturesAuditPanel(api, unauthorized); return { ids, panel };
}
const result = { id: 'audit-fixture', status: 'MATCH', reason: 'EXACT_LEDGER_CHAIN_MATCH', coverage: { complete: true, operations: 1, transactions: 1, verifiedTransactions: 1, scannedEvents: 2, verifiedEvents: 2, fromBlock: 1, toBlock: 4 }, checks: [{ name: 'wallet', expected: '1000000000000000001', actual: '1000000000000000001', difference: '0', matches: true }], items: [{ action: 'LIQUIDATE', verdict: 'MATCH', txHash: 'confirmed-hash' }] };
const admin = { id: 1, role: 'ADMIN' };
const flush = () => new Promise(r => setImmediate(r));

test('verdict summary keeps incomplete MATCH amber and raw evidence collapsed without execution', () => {
  const { ids } = setup(); const target = ids.get('result');
  renderFuturesAudit(target, { ...result, coverage: { ...result.coverage, complete: false }, block: { number: 42, hash: 'hash-exact' }, baseline: { block: { number: 1 } } });
  const summary = target.children[0];
  assert.match(summary.textContent, /coverage.complete = false/);
  assert.equal(summary.children[0].className.includes('audit-match'), false);
  assert.match(summary.children[0].className, /audit-uncertain/);
  assert.match(summary.textContent, /42/); assert.doesNotMatch(summary.textContent, /hash-exact|1000000000000000001/);
  const details = target.children.find(c => c.className === 'futures-audit-details');
  assert.ok(details); assert.notEqual(details.open, true); assert.match(details.textContent, /hash-exact|1000000000000000001/);
});
test('missing DB counts stay unknown and mismatch reasons are in the primary summary', () => {
  const { ids } = setup();
  renderFuturesAudit(ids.get('result'), { status: 'MISMATCH', items: [{ verdict: 'MISMATCH', reason: 'JOURNAL_CONFLICT' }], checks: [{ name: 'pool', matches: false }] });
  const summary = ids.get('result').children[0];
  assert.match(summary.textContent, /\? \/ \?/); assert.match(summary.textContent, /JOURNAL_CONFLICT/); assert.match(summary.textContent, /pool/);
  assert.match(summary.children[0].className, /audit-mismatch/);
});

test('inactive ADMIN and USER cannot trigger reads or execution; activation never auto-runs', () => {
  let count = 0; const { ids, panel } = setup({ futuresAuditRun: () => { count++; } });
  for (const [user, route] of [[{ id: 2, role: 'USER' }, 'admin'], [admin, 'market']]) {
    panel.setContext(user, route); ids.get('run').onclick(); assert.equal(ids.get('panel').hidden, true);
  }
  panel.setContext(admin, 'admin'); assert.equal(count, 0); assert.equal(ids.get('panel').hidden, false);
});
test('one explicit run, exact integers and independent coverage; no automatic retry', async () => {
  let count = 0, finish; const { ids, panel } = setup({ futuresAuditRun: () => { count++; return new Promise(r => { finish = r; }); } });
  panel.setContext(admin, 'admin'); ids.get('run').onclick(); ids.get('run').onclick(); assert.equal(count, 1);
  finish({ body: { ...result, coverage: { ...result.coverage, complete: false } } }); await flush();
  assert.match(ids.get('result').textContent, /1000000000000000001/); assert.match(ids.get('result').textContent, /불완전/);
  assert.equal(ids.get('result').dataset.status, 'MATCH'); assert.equal(ids.get('result').dataset.complete, 'false');
});
test('logout, account switch and leaving route discard late audit evidence', async () => {
  for (const [user, route] of [[null, 'market'], [{ id: 2, role: 'ADMIN' }, 'admin'], [admin, 'market']]) {
    let finish; const { ids, panel } = setup({ futuresAuditRun: () => new Promise(r => { finish = r; }) });
    panel.setContext(admin, 'admin'); ids.get('run').onclick(); panel.setContext(user, route); finish({ body: result }); await flush();
    assert.equal(ids.get('result').textContent, ''); assert.equal(ids.get('result').dataset.auditId, undefined);
  }
});
test('lost response is not retried, original error is hidden and 401 expires auth', async () => {
  let count = 0, expired = 0; const { ids, panel } = setup({ futuresAuditRun: async () => { count++; throw new Error('private-rpc-secret'); }, futuresAuditHistory: async () => { throw { status: 401 }; } }, () => { expired++; });
  panel.setContext(admin, 'admin'); await ids.get('run').onclick(); assert.equal(count, 1);
  assert.match(ids.get('message').textContent, /이력 조회/); assert.doesNotMatch(ids.get('message').textContent, /private-rpc-secret/);
  await ids.get('refresh').onclick(); assert.equal(expired, 1);
});
test('history detail is explicit and baseline has no create/reset action', async () => {
  let detail; const { ids, panel } = setup({ futuresAuditHistory: async () => ({ body: [result] }), futuresAuditDetail: async id => { detail = id; return { body: result }; }, futuresAuditBaseline: async () => ({ body: { id: 'baseline' } }) });
  panel.setContext(admin, 'admin'); await ids.get('refresh').onclick(); await ids.get('history').children[1].onclick();
  assert.equal(detail, result.id); assert.equal(ids.get('result').dataset.auditId, result.id);
  await ids.get('baseline').onclick(); assert.equal(ids.get('result').dataset.auditId, undefined);
});
test('all verdicts remain distinct; values are text, unknown status is inconclusive', () => {
  setup(); for (const status of ['MATCH', 'MISMATCH', 'INCONCLUSIVE', '<img>']) {
    const e = new Element(); renderFuturesAudit(e, { ...result, status, reason: '<img onerror=secret>' });
    assert.equal(e.dataset.status, status === '<img>' ? 'INCONCLUSIVE' : status); assert.match(e.textContent, /온체인 예치/);
    assert.equal(e.children.some(x => x.innerHTML), false);
  }
});
test('API uses only existing ADMIN routes and never sends baseline POST', async () => {
  const before = globalThis.fetch, calls = []; globalThis.fetch = async (path, options) => { calls.push([path, options.method || 'GET']); return { ok: true, text: async () => '{}' }; };
  try { const api = new ApiClient(); await api.futuresAuditBaseline(); await api.futuresAuditRun(); await api.futuresAuditHistory(); await api.futuresAuditDetail('a/b');
    assert.deepEqual(calls, [['/api/admin/futures/audits/baseline', 'GET'], ['/api/admin/futures/audits', 'POST'], ['/api/admin/futures/audits', 'GET'], ['/api/admin/futures/audits/a%2Fb', 'GET']]);
  } finally { globalThis.fetch = before; }
});
