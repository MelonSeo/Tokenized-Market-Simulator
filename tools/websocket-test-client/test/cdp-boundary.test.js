import test from 'node:test';
import assert from 'node:assert/strict';
import { BoundaryError, BoundedCdp, bounded, cleanupOwned, fetchJsonBounded, finalGate, pollBounded } from '../scripts/cdp-boundary.mjs';

class FakeSocket extends EventTarget {
  constructor() { super(); this.readyState = 1; this.sent = []; this.closeCount = 0; }
  send(data) { if (this.sendFails) throw new Error('secret transport detail'); this.sent.push(JSON.parse(data)); }
  reply(data) { const event = new Event('message'); event.data = typeof data === 'string' ? data : JSON.stringify(data); this.dispatchEvent(event); }
  close() { this.readyState = 3; this.closeCount++; this.dispatchEvent(new Event('close')); }
}
const rejectsCode = (promise, code) => assert.rejects(promise, error => error instanceof BoundaryError && error.code === code);

test('CDP resolves once; trace omits parameters and response bodies', async () => {
  const ws = new FakeSocket(), trace = [], cdp = new BoundedCdp(ws, { trace: value => trace.push(value) });
  const request = cdp.command('Runtime.evaluate', { expression: 'SECRET_JWT' });
  ws.reply({ id: 1, result: { value: 'SECRET_RESPONSE' } });
  assert.deepEqual(await request, { value: 'SECRET_RESPONSE' }); assert.equal(cdp.pending.size, 0);
  assert.doesNotMatch(JSON.stringify(trace), /SECRET|expression|value/);
  assert.deepEqual(Object.keys(trace[0]), ['stage', 'method', 'requestId', 'elapsedMs', 'outcome']); cdp.dispose();
});
test('silent command times out, clears pending and ignores late reply', async () => {
  const ws = new FakeSocket(), cdp = new BoundedCdp(ws, { timeoutMs: 20 });
  await rejectsCode(cdp.command('Runtime.enable'), 'CDP_COMMAND_TIMEOUT');
  assert.equal(cdp.pending.size, 0); ws.reply({ id: 1, result: {} }); cdp.dispose();
});
test('close and error reject all pending commands', async () => {
  for (const event of ['close', 'error']) {
    const ws = new FakeSocket(), cdp = new BoundedCdp(ws);
    const pending = [cdp.command('Runtime.enable'), cdp.command('Page.enable')];
    const checks = pending.map(p => rejectsCode(p, event === 'close' ? 'CDP_SOCKET_CLOSED' : 'CDP_SOCKET_ERROR'));
    ws.dispatchEvent(new Event(event)); await Promise.all(checks); assert.equal(cdp.pending.size, 0);
    await rejectsCode(cdp.command('Page.enable'), 'CDP_SESSION_CLOSED'); cdp.dispose();
  }
});
test('send error, protocol error and CDP error are safely classified', async () => {
  const ws = new FakeSocket(), cdp = new BoundedCdp(ws); ws.sendFails = true;
  await rejectsCode(cdp.command('Page.enable'), 'CDP_SEND_ERROR'); ws.sendFails = false;
  const p = cdp.command('Page.enable'); ws.reply({ id: 2, error: { message: 'SECRET' } });
  await rejectsCode(p, 'CDP_COMMAND_ERROR'); const malformed = cdp.command('Page.enable'); ws.reply('{');
  await rejectsCode(malformed, 'CDP_PROTOCOL_ERROR'); assert.equal(cdp.pending.size, 0);
  await rejectsCode(cdp.command('Page.enable'), 'CDP_SESSION_CLOSED'); cdp.dispose();
});
test('WebSocket opening deadline closes an otherwise silent socket', async () => {
  let socket;
  class ConnectingSocket extends FakeSocket { constructor() { super(); this.readyState = 0; socket = this; } }
  await rejectsCode(BoundedCdp.connect('ws://fixture', { WebSocketImpl: ConnectingSocket, timeoutMs: 20 }), 'BOUNDARY_TIMEOUT');
  assert.equal(socket.closeCount, 1);
});
test('WebSocket opening close/error fails before and after open-listener installation', async () => {
  for (const event of ['close', 'error']) for (const early of [true, false]) {
    let socket;
    class ConnectingSocket extends FakeSocket { constructor() { super(); this.readyState = 0; socket = this;
      const fail = () => this.dispatchEvent(new Event(event)); if (early) queueMicrotask(fail); else setTimeout(fail, 0);
    } }
    await rejectsCode(BoundedCdp.connect('ws://fixture', { WebSocketImpl: ConnectingSocket, timeoutMs: 200 }), early ? 'CDP_SOCKET_CLOSED' : 'CDP_SOCKET_OPEN_FAILED');
    assert.equal(socket.closeCount, 1);
  }
});
test('target-list headers and JSON-body await are both bounded', async () => {
  for (const bodyStalls of [false, true]) {
    let signal;
    await rejectsCode(fetchJsonBounded('http://fixture?SECRET', { timeoutMs: 20, fetchImpl: (_url, options) => {
      signal = options.signal;
      return bodyStalls ? { ok: true, json: () => new Promise(() => {}) } : new Promise(() => {});
    } }), 'BOUNDARY_TIMEOUT'); assert.equal(signal.aborted, true);
  }
});
test('poll bounds the inner await and does not retry a transport timeout', async () => {
  let calls = 0;
  await rejectsCode(pollBounded(() => { calls++; return new Promise(() => {}); }, { timeoutMs: 20 }), 'BOUNDARY_TIMEOUT'); assert.equal(calls, 1);
  calls = 0;
  await rejectsCode(pollBounded(() => { calls++; throw new BoundaryError('CDP_COMMAND_TIMEOUT'); }, { timeoutMs: 200 }), 'CDP_COMMAND_TIMEOUT'); assert.equal(calls, 1);
  calls = 0;
  assert.equal(await pollBounded(() => ++calls === 3, { timeoutMs: 200, intervalMs: 1 }), true);
});
test('Browser.close is bounded and disposes even without an acknowledgement', async () => {
  const ws = new FakeSocket(), cdp = new BoundedCdp(ws);
  assert.equal(await cdp.close(20), 'CDP_COMMAND_TIMEOUT'); assert.equal(cdp.pending.size, 0); assert.equal(ws.closeCount, 1);
});
test('invalid budgets send nothing; diagnostics failure cannot strand a command', async () => {
  const ws = new FakeSocket(), cdp = new BoundedCdp(ws, { trace: () => { throw new Error('disk full'); } });
  await rejectsCode(cdp.command('Page.enable', {}, 0), 'BOUNDARY_DEADLINE'); assert.equal(ws.sent.length, 0);
  const p = cdp.command('Page.enable'); ws.reply({ id: 1, result: {} }); await p; cdp.dispose();
  await rejectsCode(bounded('INVALID SECRET', () => { throw new Error('SECRET'); }, { timeoutMs: 100 }), 'BOUNDARY_ERROR');
});

function ownedFixtures({ exit = false, close = true, exitOnKill = false } = {}) {
  const state = { exit, kills: 0, childUnrefs: 0, serverUnrefs: 0, forcedCloses: 0, destroys: 0, socketUnrefs: 0 };
  return { state, hasExited: () => state.exit,
    child: { kill() { state.kills++; if (exitOnKill) state.exit = true; }, unref() { state.childUnrefs++; } },
    server: { close(callback) { if (close) callback(); }, closeAllConnections() { state.forcedCloses++; }, unref() { state.serverUnrefs++; } },
    connections: new Set([{ destroy() { state.destroys++; }, unref() { state.socketUnrefs++; } }]),
    exitWaitMs: 5, killWaitMs: 5, closeWaitMs: 5,
  };
}
test('cleanup success is necessary before final PASS; exact owned child may exit after kill', async () => {
  const f = ownedFixtures({ exitOnKill: true }), cleanup = await cleanupOwned(f);
  assert.equal(cleanup.childCleanupComplete, true); assert.equal(cleanup.serverClosed, true);
  assert.equal(f.state.kills, 1); assert.equal(f.state.childUnrefs, 0);
  assert.equal(finalGate({ uiAssertionsPassed: true, ...cleanup }).verdict, 'PASS');
});
test('unconfirmed owned child exit blocks PASS and detaches only owned handle', async () => {
  const f = ownedFixtures(), cleanup = await cleanupOwned(f);
  assert.equal(f.state.kills, 1); assert.equal(f.state.childUnrefs, 1);
  assert.equal(cleanup.ownSpawnExited, false);
  const result = finalGate({ uiAssertionsPassed: true, ...cleanup });
  assert.equal(result.verdict, 'BLOCKED'); assert.equal(result.failure, 'OWNED_BROWSER_EXIT_UNCONFIRMED');
});
test('unconfirmed fixture close blocks PASS, detaches fixture sockets and records failure', async () => {
  const f = ownedFixtures({ exit: true, close: false }), cleanup = await cleanupOwned(f);
  assert.equal(f.state.forcedCloses, 1); assert.equal(f.state.serverUnrefs, 1);
  assert.equal(f.state.destroys, 1); assert.equal(f.state.socketUnrefs, 1);
  assert.equal(cleanup.serverClosed, false);
  assert.equal(finalGate({ uiAssertionsPassed: true, ...cleanup }).failure, 'FIXTURE_CLOSE_UNCONFIRMED');
});
test('trace failure after UI assertions blocks final PASS; prior UI failure remains FAIL', () => {
  const cleanup = { childCleanupComplete: true, serverClosed: true };
  assert.equal(finalGate({ uiAssertionsPassed: true, ...cleanup, traceFailed: true }).failure, 'SAFE_TRACE_WRITE_FAILED');
  assert.equal(finalGate({ uiAssertionsPassed: true, ...cleanup, traceFailed: true }).verdict, 'BLOCKED');
  assert.equal(finalGate({ uiAssertionsPassed: false, ...cleanup, failure: 'PAGE_EVALUATION_ERROR' }).verdict, 'FAIL');
});
