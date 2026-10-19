import { node, fields, button } from './ui.js';
import { time } from './state.js';

const verdict = value => ['MATCH', 'MISMATCH', 'INCONCLUSIVE'].includes(value) ? value : 'INCONCLUSIVE';
export function renderFuturesAudit(container, result) {
  container.replaceChildren();
  Object.assign(container.dataset, { auditId: result.id, status: verdict(result.status), complete: String(result.coverage?.complete === true) });
  container.append(node('h3', `선물 감사 · ${verdict(result.status)}`), fields([
    ['실행 번호', result.id], ['판정 근거', result.reason], ['관측 시각', time(result.createdAt)],
    ['DB 기준 시각', time(result.dbAt)], ['확정 블록', result.block?.number ?? '확인 불가'],
    ['블록 해시', result.block?.hash ?? '확인 불가'], ['DB 검사 / 불일치', `${result.dbCheckCount ?? 0} / ${result.dbMismatchCount ?? 0}`],
  ]));
  const c = result.coverage;
  container.append(node('h3', '검증 범위 · coverage'), fields([
    ['전체 operation', c?.operations ?? '확인 불가'],
    ['검증 / 전체 transaction', `${c?.verifiedTransactions ?? '?'} / ${c?.transactions ?? '?'}`],
    ['검증 / 스캔 이벤트', `${c?.verifiedEvents ?? '?'} / ${c?.scannedEvents ?? '?'}`],
    ['스캔 블록 구간', c ? `${c.fromBlock} ~ ${c.toBlock}` : '확인 불가'],
    ['범위 완전성', c?.complete === true ? '완전' : '불완전 · 전수 일치로 해석하지 마세요'],
  ]));
  if (c?.complete !== true) container.append(node('p', '일부 조회·스캔이 불완전합니다. 개별 MATCH를 전수 MATCH로 해석하지 마세요.', 'notice warning'));
  for (const check of result.checks || []) container.append(fields([
    ['장부 검사', check.name], ['예상 / 실제 (원시 정수)', `${check.expected} / ${check.actual}`],
    ['차이', check.difference], ['일치', check.matches === true ? '예' : '아니오'],
  ]));
  for (const item of result.items || []) {
    const card = node('article', '', 'record');
    card.append(node('h3', `${item.action} · ${verdict(item.verdict)}`), fields([
      ['operation / position', `${item.operationId ?? '없음'} / ${item.positionId ?? '없음'}`],
      ['사용자 / DB 상태', `${item.userId} / ${item.dbState}`], ['판정 근거', item.reason],
      ['txHash', item.txHash ?? '미확정 또는 없음'], ['블록 번호', item.blockNumber ?? '없음'],
    ])); container.append(card);
  }
  container.append(node('p', '범위: FuturesEngine 상태·정규 Receipt/Event와 DB 증거금·손익 풀·journal. DB 담보의 온체인 예치, 사용자별 토큰 소유권 또는 미래 지급 보증이 아닙니다.', 'notice'));
}

/** No implicit audit, retries, repairs or baseline creation. Context epochs discard late ADMIN responses. */
export function createFuturesAuditPanel(api, unauthorized = () => {}) {
  const $ = id => document.getElementById('futures-audit-' + id);
  let identity = null, active = false, epoch = 0, busy = false;
  const controls = () => { for (const id of ['baseline', 'run', 'refresh']) $(id).disabled = !active || busy; };
  async function request(operation, render, write = false) {
    if (!active || busy) return;
    const ticket = epoch; busy = true; controls(); $('message').textContent = '선물 감사 근거를 조회하고 있습니다.';
    try {
      const { body } = await operation(); if (ticket !== epoch) return;
      render(body); $('message').textContent = '';
    } catch (error) {
      if (ticket !== epoch) return;
      $('message').textContent = error.status === 404 ? '기준점 또는 기능이 없습니다. 운영 설정을 확인하세요. 이 화면에서는 기준점을 생성·재설정하지 않습니다.'
        : error.status === 403 ? 'ADMIN 권한이 필요합니다.'
        : write ? '실행 결과를 받지 못했습니다. 자동 재실행하지 않습니다. 이력 조회로 결과를 확인하세요.' : '조회하지 못했습니다. 연결과 권한을 확인하세요.';
      if (error.status === 401) unauthorized();
    } finally { if (ticket === epoch) { busy = false; controls(); } }
  }
  $('baseline').onclick = () => request(() => api.futuresAuditBaseline(), b => {
    $('result').replaceChildren(node('h3', '불변 선물 기준점'), fields([
      ['기준점 번호', b.id], ['체인 ID', b.chain?.chainId ?? '확인 불가'],
      ['기준 블록', b.block?.number], ['기준 블록 해시', b.block?.hash], ['DB 기준 시각', time(b.dbAt)],
      ['초기 house (원시 정수)', b.initialHouse],
    ]));
    for (const name of ['auditId', 'status', 'complete']) delete $('result').dataset[name];
  });
  $('run').onclick = () => request(() => api.futuresAuditRun(), r => renderFuturesAudit($('result'), r), true);
  $('refresh').onclick = () => request(() => api.futuresAuditHistory(), rows => {
    $('history').replaceChildren(node('p', '최근 실행 최대 20건 · 각 실행은 당시 DB/블록 기준입니다.'));
    for (const row of rows) $('history').append(button(`${time(row.createdAt)} · ${verdict(row.status)} · ${row.id}`,
      () => request(() => api.futuresAuditDetail(row.id), r => renderFuturesAudit($('result'), r))));
  });
  return { setContext(user, route) {
    const next = user?.role === 'ADMIN' && route === 'admin';
    const key = next ? String(user.id) : null;
    if (key !== identity || next !== active) {
      epoch++; busy = false; identity = key; active = next;
      for (const id of ['history', 'result', 'message']) $(id).replaceChildren();
      for (const name of ['auditId', 'status', 'complete']) delete $('result').dataset[name];
    }
    document.getElementById('futures-audit-panel').hidden = !active; controls();
  } };
}
