import { node, fields } from './ui.js';
import { futuresDecimal } from './decimal-display.js';
export { futuresDecimal } from './decimal-display.js';

// Display only: never convert financial strings to Number or feed these summaries into requests.
export const terminalPosition = p => ['CLOSED', 'LIQUIDATED', 'OPEN_FAILED'].includes(p.status);
export function futuresRisk(p, now = Date.now()) {
  if (terminalPosition(p)) return '종료된 포지션 · 진행 중인 유예 없음';
  if (p.riskStatus === 'HEALTHY') return ''; // The primary status is sufficient; keep raw risk history in details.
  if (p.status !== 'OPEN' || p.riskStatus !== 'GRACE') return p.riskStatus === 'GRACE' ? '마진콜 기록 있음 · 처리 상태 확인 중' : '위험 상태 확인 불가';
  if (!Number.isFinite(p.deadline) || p.deadline <= 0) return '마진콜 · 유예 종료 시각 확인 불가';
  const seconds = p.deadline - Math.floor(now / 1000);
  return seconds > 0 ? `마진콜 · 남은 약 ${seconds}초 (화면 추정)` : '마진콜 · 유예 종료 시각 경과 · 서버의 체인 확인 대기';
}
export function positionSummary(p) {
  const ended = terminalPosition(p), summary = node('div', '', 'futures-metrics');
  const metric = (label, value, unit = 'mKRW', places = 2) => {
    const cell = node('div', '', 'futures-metric');
    cell.append(node('span', label, 'muted'), node('strong', `${futuresDecimal(value, places)} ${unit}`));
    return cell;
  };
  if (ended) summary.append(metric('확정 실현손익', p.realized), metric('악성 부채 기록', p.badDebt));
  else summary.append(metric('DB 격리증거금', p.collateral), metric('미실현 손익', p.unrealized), metric('Equity · 담보 + 손익', p.equity), metric('유지증거금 · 현재 명목가치 5%', p.maintenance));
  summary.append(metric('진입가격', p.entryPrice), metric('현재 참고가격 · 청산 체결가 아님', p.markPrice), metric('가격 노출 수량', p.quantity, 'mSEC', 6));
  return summary;
}
export function positionDetails(p) {
  const details = node('details', '', 'futures-exact');
  details.append(node('summary', '정확한 원본 수치 · 이력 상세'), node('p', '약 표시는 표시 자릿수 아래의 값이 생략된 근삿값입니다. 정확히 일치하면 붙이지 않습니다. 주문 입력 및 서버 원본 값은 변경하지 않습니다.', 'muted'), fields([
    ['포지션 ID', p.id], ['수량 (mSEC)', p.quantity], ['진입가격 / 현재 참고가격', `${p.entryPrice} / ${p.markPrice ?? '조회 불가'}`],
    ['DB 담보 원본 · 종료 후 현재 잠금액 아님', p.collateral], ['미실현 손익', p.unrealized ?? '산출 불가 / 종료'],
    ['Equity / 유지증거금', `${p.equity ?? '산출 불가'} / ${p.maintenance ?? '산출 불가'}`], ['실현손익 / 악성 부채', `${p.realized ?? '미확정'} / ${p.badDebt ?? '미확정'}`],
    ['기록된 위험 상태 (과거 이력 포함)', p.riskStatus], ['기록된 유예 종료 (체인 초)', p.deadline ?? '없음'], ['가격 관측 시각', p.observedAt ?? '확인 불가'],
  ]));
  return details;
}
