import { node, button, fields } from './ui.js';
import { terminalPosition, futuresRisk, positionSummary, positionDetails } from './futures-display.js';

export function futuresAmount(value) {
  if (typeof value !== 'string' || !/^\d{1,12}(\.\d{1,18})?$/.test(value) || !/[1-9]/.test(value)) throw new Error('양수 증거금을 소수점18자리 이내로 입력하세요.');
  return value;
}
export async function futureIntent(uid, request, storage = localStorage, locks = navigator.locks) {
  const choose = () => {
    const name = `futures-pending:${uid}`, old = storage.getItem(name);
    if (old) { const value = JSON.parse(old); if (JSON.stringify(value.request) !== JSON.stringify(request)) throw new Error('이전 요청 결과부터 확인하세요.'); return value; }
    const value = { key: crypto.randomUUID(), request }; storage.setItem(name, JSON.stringify(value)); return value;
  };
  return locks ? locks.request(`futures-intent:${uid}`, choose) : choose();
}
const STATES = { OPEN_PENDING: '개설 확인 중', OPEN: '진행 중', CLOSING: '전체 종료 확인 중', LIQUIDATING: '자동청산 확인 중', CLOSED: '종료 확정', LIQUIDATED: '자동청산 확정', OPEN_FAILED: '개설 실패', REVIEW_REQUIRED: '수동 확인 필요 · 잠금 유지' };
export function createFuturesPanel(api, unauthorized = () => {}, diagnose = () => {}) {
  const $ = id => document.getElementById(id); let user = null, route = '', epoch = 0, revision = 0, previewRevision = 0, busy = false, preview = null, poll = null;
  const marginDrafts = new Map(), expanded = new Set();
  const notice = text => { $('futures-message').textContent = text; };
  const uid = () => user?.id ?? user?.userId;
  function clearPreview() { previewRevision++; preview = null; $('futures-preview').replaceChildren(); controls(); }
  function controls() { $('futures-confirm').disabled = busy || !user || !preview; $('futures-preview-open').disabled = busy || !user; $('futures-retry').disabled = busy || !user; }
  async function refresh() {
    if (!user || route !== 'futures') return;
    const ticket = epoch, serial = ++revision;
    try {
      const { body } = await api.futuresPositions(); if (ticket !== epoch || serial !== revision) return;
      const ordered = [...body.filter(p => !terminalPosition(p)), ...body.filter(terminalPosition)];
      let group = '';
      $('futures-positions').replaceChildren(...ordered.map(p => {
        const ended = terminalPosition(p), nextGroup = ended ? '종료 포지션' : '활성 · 처리 중 포지션';
        const card = node('article', '', `card futures-position ${ended ? 'futures-ended' : ''}`);
        if (group !== nextGroup) { card.append(node('p', nextGroup, 'eyebrow')); group = nextGroup; }
        const header = node('div', '', 'futures-position-heading');
        header.append(node('h2', `${p.side} · ${p.leverage}배`), node('span', STATES[p.status] || p.status, 'futures-state'));
        card.append(header, positionSummary(p));
        const risk = futuresRisk(p);
        if (risk) card.append(node('p', risk, p.status === 'OPEN' && p.riskStatus === 'GRACE' ? 'notice warning' : 'muted'));
        if (!ended && p.priceAvailable !== true) card.append(node('p', '가격 평가 불가 · 표시 값만으로 거래 가능 여부를 판단하지 마세요.', 'notice warning'));
        if (p.pendingOperation || p.blockReason) card.append(fields([['실행 중', p.pendingOperation || '없음'], ['차단 사유', p.blockReason || '없음']]));
        const details = positionDetails(p); details.open = expanded.has(p.id);
        details.addEventListener('toggle', () => { if (ticket === epoch && serial === revision) { if (details.open) expanded.add(p.id); else expanded.delete(p.id); } });
        card.append(details);
        if (p.status === 'OPEN' && !p.pendingOperation) {
          const close = button('전체 종료 견적 확인', () => void makePreview({ action: 'CLOSE', positionId: p.id, expectedVersion: p.version })); close.disabled = busy;
          const label = node('label', '추가 증거금 (mKRW)'), input = node('input'); input.inputMode = 'decimal'; input.placeholder = '추가할 금액'; label.append(input);
          input.value = marginDrafts.get(p.id) ?? ''; input.oninput = () => marginDrafts.set(p.id, input.value);
          const add = button('증거금 추가', () => { try { void write({ path: `/positions/${p.id}/margin`, body: { amount: futuresAmount(input.value.trim()), expectedVersion: p.version } }); } catch (e) { notice(e.message); } }); add.disabled = busy;
          card.append(close, label, add);
        }
        card.append(button('AI 읽기 전용 근거 진단', () => diagnose(p.id)));
        return card;
      }));
      if (!body.length) $('futures-positions').append(node('p', '아직 선물 포지션이 없습니다.'));
    } catch (e) { if (ticket === epoch && serial === revision) { notice(e.status === 404 ? '이 환경에서는 선물 기능이 비활성입니다.' : '선물 조회 실패 · 표시 상태가 최신이 아닐 수 있습니다.'); if (e.status === 401) unauthorized(); } }
  }
  async function makePreview(body) {
    if (!user || busy) return; const ticket = epoch; busy = true; clearPreview(); const previewTicket = previewRevision; controls();
    try {
      const { body: p } = await api.futuresPreview(body); if (ticket !== epoch || previewTicket !== previewRevision) return;
      preview = { ...p, positionId: body.positionId, expectedVersion: body.expectedVersion ?? 0 };
      $('futures-preview').replaceChildren(fields([['동작', p.action], ['방향 / 배수', `${p.side} / ${p.leverage}`], ['고정 가격', p.price], ['증거금', p.margin], ['만료', new Date(p.expiresAt).toLocaleTimeString('ko-KR')]]), node('p', p.notice));
      notice('가격과 위험 안내를 확인한 뒤 확정하세요. 가격이 바뀌면 새 확인이 필요합니다.');
    } catch (e) { if (ticket === epoch) { notice('새 견적을 확인할 수 없습니다. 시장 상태와 잔고를 확인하세요.'); if (e.status === 401) unauthorized(); } }
    finally { if (ticket === epoch) { busy = false; controls(); } }
  }
  async function write(request, retry = null) {
    if (!user || busy) return; const ticket = epoch, owner = uid(); let attemptedKey = null; busy = true; clearPreview(); controls();
    try {
      const pending = retry || await futureIntent(owner, request); if (ticket !== epoch) return;
      attemptedKey = pending.key;
      const { body } = await api.futuresWrite(pending.request.path, pending.request.body, pending.key);
      // Clear only a confirmed response for this exact key. A lost response never generates a replacement key.
      const name = `futures-pending:${owner}`, saved = localStorage.getItem(name);
      if (saved && JSON.parse(saved).key === pending.key) localStorage.removeItem(name);
      if (ticket !== epoch) return;
      notice(`요청 ${body.state} · ${body.id}. 체인 확정과 DB 정산을 기다립니다.`); void refresh();
    } catch (e) {
      if (ticket === epoch) {
        if (e.status === 409 && e.body?.code === 'FUTURES_REQUEST_REJECTED') {
          // This dedicated code is returned only AFTER a DB-serialized no-execution marker COMMIT.
          const name = `futures-pending:${owner}`, saved = localStorage.getItem(name);
          if (saved && JSON.parse(saved).key === attemptedKey) localStorage.removeItem(name);
          notice('미실행 거절이 확인됐습니다. 최신 상태를 조회하고 새 견적을 확인하세요.');void refresh();
        } else notice(e.message === '이전 요청 결과부터 확인하세요.' ? e.message : '요청 결과가 불확실하거나 거절됐습니다. 새 요청을 만들지 말고 같은 요청 재확인과 최신 상태 조회를 사용하세요.');
        if (e.status === 401) unauthorized();
      }
    }
    finally { if (ticket === epoch) { busy = false; controls(); } }
  }
  $('futures-preview-open').onclick = () => { try { void makePreview({ action: 'OPEN', side: $('futures-side').value, leverage: Number($('futures-leverage').value), marginAmount: futuresAmount($('futures-margin').value.trim()) }); } catch (e) { notice(e.message); } };
  $('futures-confirm').onclick = () => {
    if (!preview || busy) return; const p = preview;
    if (Date.parse(p.expiresAt) <= Date.now()) { clearPreview(); notice('견적 만료 · 새 견적을 확인하세요.'); return; }
    void write(p.action === 'OPEN' ? { path: '/positions', body: { previewId: p.previewId } } : { path: `/positions/${p.positionId}/close`, body: { previewId: p.previewId, expectedVersion: p.expectedVersion } });
  };
  $('futures-retry').onclick = () => { if (!user || busy) return; const saved = localStorage.getItem(`futures-pending:${uid()}`); if (!saved) { notice('재확인할 요청이 없습니다.'); return; } const p = JSON.parse(saved); void write(p.request, p); };
  $('futures-refresh').onclick = () => void refresh();
  for (const id of ['futures-side', 'futures-leverage', 'futures-margin']) $(id).oninput = clearPreview;
  return { refresh, setContext(next, nextRoute) {
    if ((next?.id ?? next?.userId) !== uid() || nextRoute !== route) { epoch++; revision++; busy = false; marginDrafts.clear(); expanded.clear(); clearPreview(); $('futures-positions').replaceChildren(); notice(''); }
    user = next; route = nextRoute; controls(); if (user && route === 'futures') void refresh();
    if (poll) clearInterval(poll); poll = user && route === 'futures' ? setInterval(() => { if (!busy) void refresh(); }, 3000) : null;
  } };
}
