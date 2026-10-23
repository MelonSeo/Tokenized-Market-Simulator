// Presentation only. Never compute money, infer a missing price, or promote a verdict.
import { futuresDecimal } from './decimal-display.js';
const money = value => `${futuresDecimal(value)} mKRW`;
const epoch = value => Number.isSafeInteger(value) && value > 0 ? `${value} (UTC epoch 초)` : '확인 불가';
export function futuresEvidenceSummary(response) {
  const tools = response?.toolEvidence || [];
  const position = tools.find(t => t.tool === 'getFuturesPosition' && t.status === 'SUCCESS')?.data;
  const evidence = tools.find(t => t.tool === 'getFuturesLiquidationEvidence' && t.status === 'SUCCESS')?.data;
  if (!position && !evidence) return null;
  const p = position?.position;
  const mismatch = Boolean(p?.id && evidence?.positionId && p.id !== evidence.positionId);
  const snapshot = [position, evidence].some(d => d?.captureScope || d?.freshChainVerification === false);
  const operations = Array.isArray(evidence?.operations) ? evidence.operations : [];
  const liquidation = operations.find(o => o.action === 'LIQUIDATE');
  const call = operations.find(o => o.action === 'START_CALL');
  const rows = mismatch ? [] : [
    ['포지션 ID', p?.id ?? evidence?.positionId ?? '확인 불가'],
    ['포지션 상태 / 마지막 위험 기록', `${p?.status ?? evidence?.positionSnapshot?.status ?? '확인 불가'} / ${p?.riskStatus ?? evidence?.positionSnapshot?.riskStatus ?? '확인 불가'}`],
    ['진입가격 · 포지션 원본', money(p?.entryPrice)],
    ['가격 노출 수량 · 포지션 원본', `${futuresDecimal(p?.quantity, 6)} mSEC`],
    ['청산 판단가격 · operation 원본 (현재 참고가 아님)', money(liquidation?.price)],
    ['청산 시 DB 담보 기록 (현재 잠금액 아님)', money(liquidation?.collateral)],
    ['청산 시 Equity · operation 원본', money(liquidation?.equity)],
    ['청산 시 유지증거금 · operation 원본', money(liquidation?.maintenance)],
    ['확정 실현손익 · 포지션 원본', money(p?.realized)],
    ['악성 부채 기록 · 포지션 원본', money(p?.badDebt)],
    ['마진콜 기록 체인 시각', epoch(call?.chainTime)],
    ['기록된 유예 종료 시각', epoch(call?.graceDeadline)],
    ['청산 기록 체인 시각', epoch(liquidation?.chainTime)],
    ['청산 operation DB 상태 / 선택 증거 판정', `${liquidation?.databaseState ?? '확인 불가'} / ${liquidation?.verification ?? '확인 불가'}`],
    ['응답 전체 선택 증거 판정', evidence?.verification ?? '확인 불가'],
    ['청산 트랜잭션 / 블록', `${liquidation?.txHash ?? '확인 불가'} / ${liquidation?.blockNumber ?? '확인 불가'}`],
    ['선택 journal ID', liquidation?.journalId ?? '확인 불가'],
    ['Tool 조회 시각 · 보존 시점과 다를 수 있음', evidence?.observedAt ?? position?.observedAt ?? '확인 불가'],
  ];
  return { rows, snapshot, mismatch,
    scope: mismatch ? 'Tool 대상 불일치 · 수치 요약을 표시하지 않습니다.' : snapshot
      ? '보존된 스냅샷입니다. 조회 시점의 fresh RPC 검증이 아닙니다.'
      : '선택된 마진콜·청산 Receipt/Event와 journal만의 조회 응답입니다. 전체 감사 또는 지속적인 실시간 검증이 아닙니다.' };
}
