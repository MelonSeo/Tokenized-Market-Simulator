import test from 'node:test';
import assert from 'node:assert/strict';
import { exactAvailableAmount, createSellAllAction } from '../src/sell-all.js';

for (const value of ['1.328161833310517989', '0.000161833310517989', '0.000000000000000001', '9007199254740993.123456789012345678']) {
  test(`keeps exact decimal ${value}`, () => assert.equal(exactAvailableAmount(value), value));
}
test('rejects missing, numeric, zero, negative, exponential and excess precision', () => {
  for (const value of [undefined, null, 1.3, '', '0', '0.000', '-1', '1e-18', 'NaN', ' 1', '0.0000000000000000001']) {
    assert.throws(() => exactAvailableAmount(value));
  }
});
function fixture() {
  const state = { userId: 3, epoch: 0, side: 'SELL', blocked: false };
  const values = [], notices = []; let resolve, reject, reads = 0, invalidations = 0, expired = 0;
  const action = createSellAllAction({ context: () => state,
    portfolio: () => { reads++; return new Promise((yes, no) => { resolve = yes; reject = no; }); },
    invalidateQuote: () => { invalidations++; action.cancel(); }, fill: value => values.push(value),
    notify: text => notices.push(text), changed: () => {}, unauthorized: () => expired++ });
  return { state, action, values, notices, get resolver() { return resolve; }, resolve: value => resolve({ body: value }), reject: error => reject(error),
    stats: () => ({ reads, invalidations, expired }) };
}
test('fresh REST fills available, not total, without quote/order calls; duplicate click ignored', async () => {
  const f = fixture(); const first = f.action.run(); await f.action.run();
  assert.equal(f.action.busy, true);
  f.resolve({ tokenBalance: '2', tokenAvailableAmount: '1.000000000000000001' }); await first;
  assert.deepEqual(f.values, ['1.000000000000000001']);
  assert.deepEqual(f.stats(), { reads: 1, invalidations: 1, expired: 0 }); assert.equal(f.action.busy, false);
});
for (const change of [s => s.userId = 4, s => { s.userId = null; s.epoch++; }, s => s.side = 'BUY', s => s.blocked = true]) {
  test('late response cannot fill a changed context', async () => {
    const f = fixture(); const pending = f.action.run(); change(f.state);
    f.resolve({ tokenAvailableAmount: '1' }); await pending; assert.deepEqual(f.values, []);
  });
}
test('cancel and a newer request cannot be overwritten by the old response', async () => {
  const f = fixture(); const old = f.action.run(); const oldResolve = f.resolver;
  f.action.cancel();
  const next = f.action.run(); f.resolve({ tokenAvailableAmount: '2' }); await next;
  oldResolve({ body: { tokenAvailableAmount: '1' } }); await old;
  assert.deepEqual(f.values, ['2']); assert.equal(f.stats().reads, 2);
});
test('blocked, anonymous and BUY contexts do not read', async () => {
  for (const change of [s => s.blocked = true, s => s.userId = null, s => s.side = 'BUY']) {
    const f = fixture(); change(f.state); await f.action.run(); assert.equal(f.stats().reads, 0);
  }
});
test('old API or malformed available amount never falls back to rounded total', async () => {
  const f = fixture(); const pending = f.action.run(); f.resolve({ tokenBalance: 1.3282 }); await pending;
  assert.deepEqual(f.values, []); assert.equal(f.action.busy, false);
});
test('401 expires current session without automatic retry', async () => {
  const f = fixture(); const pending = f.action.run(); f.reject({ status: 401 }); await pending;
  assert.equal(f.stats().expired, 1); assert.equal(f.stats().reads, 1); assert.deepEqual(f.values, []);
});
