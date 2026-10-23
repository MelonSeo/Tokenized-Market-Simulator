/** Bounded smoke-test transport. Trace metadata deliberately excludes params and response bodies. */
export class BoundaryError extends Error {
  constructor(code) { super(code); this.name = 'BoundaryError'; this.code = code; }
}
const stageName = value => /^[A-Z0-9_]{1,64}$/.test(value ?? '') ? value : 'UNSPECIFIED';
const methodName = value => /^[A-Za-z]{1,32}\.[A-Za-z]{1,64}$/.test(value ?? '') ? value : '';
const duration = value => {
  if (!Number.isFinite(value) || value <= 0) throw new BoundaryError('BOUNDARY_DEADLINE');
  return Math.max(1, Math.floor(value));
};
function emit(trace, stage, outcome, started, method = '', requestId = 0) {
  try { trace?.({ stage: stageName(stage), method: methodName(method), requestId,
    elapsedMs: Math.max(0, Date.now() - started), outcome }); } catch { /* Diagnostics must not strand a pending command. */ }
}

export async function bounded(stage, operation, { timeoutMs, trace } = {}) {
  const started = Date.now(), ms = duration(timeoutMs), controller = new AbortController();
  let timer;
  emit(trace, stage, 'STARTED', started);
  try {
    const result = await Promise.race([
      Promise.resolve().then(() => operation(controller.signal)),
      new Promise((_, reject) => { timer = setTimeout(() => {
        controller.abort(); reject(new BoundaryError('BOUNDARY_TIMEOUT'));
      }, ms); }),
    ]);
    emit(trace, stage, 'OK', started); return result;
  } catch (error) {
    emit(trace, stage, error instanceof BoundaryError ? error.code : 'BOUNDARY_ERROR', started);
    throw error instanceof BoundaryError ? error : new BoundaryError('BOUNDARY_ERROR');
  } finally { clearTimeout(timer); }
}

export async function fetchJsonBounded(url, { timeoutMs = 5000, trace, fetchImpl = globalThis.fetch } = {}) {
  return bounded('TARGET_LIST', async signal => {
    const response = await fetchImpl(url, { signal });
    if (!response.ok) throw new BoundaryError('TARGET_LIST_HTTP_ERROR');
    return response.json();
  }, { timeoutMs, trace });
}

export async function pollBounded(operation, { timeoutMs = 15000, intervalMs = 50, trace, stage = 'POLL' } = {}) {
  const deadline = Date.now() + duration(timeoutMs);
  for (;;) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new BoundaryError('POLL_TIMEOUT');
    try {
      const value = await bounded(stage, () => operation(remaining), { timeoutMs: remaining, trace });
      if (value) return value;
    } catch (error) {
      // Expected filesystem/DOM readiness errors may be retried, never a timed-out/closed transport.
      if (error instanceof BoundaryError && error.code !== 'BOUNDARY_ERROR') throw error;
    }
    const left = deadline - Date.now();
    if (left <= 0) throw new BoundaryError('POLL_TIMEOUT');
    await new Promise(resolve => setTimeout(resolve, Math.min(intervalMs, left)));
  }
}

export class BoundedCdp {
  constructor(socket, { timeoutMs = 8000, trace, onEvent = () => {} } = {}) {
    this.socket = socket; this.timeoutMs = timeoutMs; this.trace = trace; this.onEvent = onEvent;
    this.serial = 0; this.pending = new Map(); this.closed = false;
    this.message = event => {
      let response;
      try { response = JSON.parse(event.data); }
      catch { this.closed = true; this.failAll('CDP_PROTOCOL_ERROR'); return; }
      if (!response || typeof response !== 'object' || Array.isArray(response)) {
        this.closed = true; this.failAll('CDP_PROTOCOL_ERROR'); return;
      }
      if (Number.isSafeInteger(response.id) && response.id > 0) {
        const task = this.pending.get(response.id);
        if (!task) { emit(this.trace, 'CDP_COMMAND', 'IGNORED_REPLY', Date.now(), '', response.id); return; }
        this.finish(response.id, response.error ? 'CDP_COMMAND_ERROR' : null, response.result);
      } else if (typeof response.method === 'string') this.onEvent(methodName(response.method));
    };
    this.closeEvent = () => { this.closed = true; this.failAll('CDP_SOCKET_CLOSED'); };
    this.errorEvent = () => { this.closed = true; this.failAll('CDP_SOCKET_ERROR'); };
    socket.addEventListener('message', this.message);
    socket.addEventListener('close', this.closeEvent);
    socket.addEventListener('error', this.errorEvent);
  }
  static async connect(url, { WebSocketImpl = globalThis.WebSocket, timeoutMs = 5000, trace, ...options } = {}) {
    let socket;
    try { socket = new WebSocketImpl(url); } catch { throw new BoundaryError('CDP_CONNECT_FAILED'); }
    const session = new BoundedCdp(socket, { ...options, trace });
    try {
      await bounded('WS_OPEN', () => new Promise((resolve, reject) => {
        if (socket.readyState === 1) { resolve(); return; }
        if (session.closed || socket.readyState > 1) { reject(new BoundaryError('CDP_SOCKET_CLOSED')); return; }
        const opened = () => { remove(); resolve(); };
        const failed = () => { remove(); reject(new BoundaryError('CDP_SOCKET_OPEN_FAILED')); };
        const remove = () => {
          socket.removeEventListener('open', opened); socket.removeEventListener('close', failed);
          socket.removeEventListener('error', failed); session.removeOpening = null;
        };
        session.removeOpening = remove;
        socket.addEventListener('open', opened); socket.addEventListener('close', failed); socket.addEventListener('error', failed);
      }), { timeoutMs, trace });
      return session;
    } catch (error) { session.dispose(); throw error; }
  }
  command(method, params = {}, timeoutMs = this.timeoutMs) {
    if (this.closed || this.socket.readyState !== 1) return Promise.reject(new BoundaryError('CDP_SESSION_CLOSED'));
    let ms;
    try { ms = duration(timeoutMs); } catch (error) { return Promise.reject(error); }
    const id = ++this.serial, started = Date.now();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.finish(id, 'CDP_COMMAND_TIMEOUT'), ms);
      this.pending.set(id, { resolve, reject, timer, method, started });
      emit(this.trace, 'CDP_COMMAND', 'STARTED', started, method, id);
      try { this.socket.send(JSON.stringify({ id, method, params })); }
      catch { this.finish(id, 'CDP_SEND_ERROR'); }
    });
  }
  finish(id, error, result) {
    const task = this.pending.get(id); if (!task) return;
    this.pending.delete(id); clearTimeout(task.timer);
    emit(this.trace, 'CDP_COMMAND', error ?? 'OK', task.started, task.method, id);
    if (error) task.reject(new BoundaryError(error)); else task.resolve(result);
  }
  failAll(code) { for (const id of [...this.pending.keys()]) this.finish(id, code); }
  dispose() {
    this.closed = true; this.removeOpening?.(); this.failAll('CDP_SESSION_CLOSED');
    this.socket.removeEventListener('message', this.message);
    this.socket.removeEventListener('close', this.closeEvent); this.socket.removeEventListener('error', this.errorEvent);
    try { this.socket.close(); } catch {}
  }
  async close(timeoutMs = 1000) {
    let outcome = 'NOT_OPEN';
    try {
      if (!this.closed && this.socket.readyState === 1) {
        await this.command('Browser.close', {}, timeoutMs); outcome = 'ACKNOWLEDGED';
      }
    } catch (error) { outcome = error instanceof BoundaryError ? error.code : 'CLOSE_ERROR'; }
    finally { this.dispose(); }
    return outcome;
  }
}

/** Only exact handles created by this harness; an unconfirmed exit remains a failed Gate. */
export async function cleanupOwned({ child, hasExited, spawnFailed = false, server, connections = new Set(), trace,
  exitWaitMs = 1500, killWaitMs = 2000, closeWaitMs = 1000 }) {
  let ownSpawnExited = hasExited(), serverClosed = false;
  if (child && !ownSpawnExited && !spawnFailed) {
    try { await pollBounded(hasExited, { timeoutMs: exitWaitMs, stage: 'OWNED_BROWSER_EXIT', trace }); }
    catch {
      try { child.kill(); } catch { /* Never expand to other processes. */ }
      try { await pollBounded(hasExited, { timeoutMs: killWaitMs, stage: 'OWNED_BROWSER_EXIT', trace }); } catch {}
    }
    ownSpawnExited = hasExited();
    if (!ownSpawnExited) child.unref?.();
  }
  try {
    await bounded('FIXTURE_CLOSE', () => new Promise(done => server.close(error => { serverClosed = !error; done(); })), { timeoutMs: closeWaitMs, trace });
  } catch {
    try { server.closeAllConnections(); } catch {}
    try { await pollBounded(() => serverClosed, { timeoutMs: closeWaitMs, stage: 'FIXTURE_CLOSE', trace }); } catch {}
  }
  // No new wait is introduced by cleanup failure. Only this fixture's handles are detached.
  if (!serverClosed) {
    for (const socket of connections) { try { socket.destroy(); socket.unref?.(); } catch {} }
    server.unref?.();
  }
  return { ownSpawnExited, childCleanupComplete: !child || spawnFailed || ownSpawnExited, serverClosed };
}

export function finalGate({ uiAssertionsPassed, childCleanupComplete, serverClosed, traceFailed, failure }) {
  const blockers = [];
  if (!childCleanupComplete) blockers.push('OWNED_BROWSER_EXIT_UNCONFIRMED');
  if (!serverClosed) blockers.push('FIXTURE_CLOSE_UNCONFIRMED');
  if (traceFailed) blockers.push('SAFE_TRACE_WRITE_FAILED');
  return { verdict: blockers.length ? 'BLOCKED' : uiAssertionsPassed ? 'PASS' : 'FAIL',
    failure: blockers[0] ?? failure ?? null, blockers };
}
