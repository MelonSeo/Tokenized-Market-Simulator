// Sanitized presentation fixture, NOT a live Agent/DB/RPC response.
// Prose and decimals preserved from ai-q12-postreboot-real-evaluation-2026-10-21.md.
// This is a selected-field reconstruction, not the private original evaluation artifact.
export const positionId = '39a7acf4-9f40-4718-9d28-aa5cf3503669';
export const q12Response = {
  status: 'PARTIAL', route: 'MIXED',
  answer: '기록상 이 Long 포지션은 가격 하락으로 위험 상태가 된 뒤 마진콜 유예가 지난 시점에도 위험으로 판정되어 청산되었습니다. 정책상 E가 MM 이하이면 마진콜 위험이며, 온체인 마진콜 확정 뒤 실제 60초 유예 후 새 관측가격에서도 위험할 때 강제청산합니다. 선택된 마진콜과 청산 이벤트 및 journal 범위는 VERIFIED이므로, 해당 범위에서는 청산 기록이 정합합니다. 다만 VERIFIED는 선택된 최대 두 건의 마진콜·청산 canonical Receipt/Event 및 journal 검증 범위일 뿐 전체 감사나 지급능력 보증은 아닙니다. 선물 담보는 온체인 예치가 아닌 DB 모의자산 장부의 담보입니다.',
  uncertainties: ['제공된 증거는 보존된 E2E 스냅샷이며 freshChainVerification은 false이므로, 조회 시점의 신선한 RPC 재검증 여부는 확인할 수 없습니다.', '포지션 스냅샷의 riskStatus는 GRACE로 남아 있습니다.'],
  toolEvidence: [
    { evidenceId: 'tool-1', tool: 'getFuturesPosition', status: 'SUCCESS', data: {
      captureScope: 'PRESERVED_E2E_SNAPSHOT', freshChainVerification: false,
      position: { id: positionId, side: 'LONG', leverage: 10, status: 'LIQUIDATED', riskStatus: 'GRACE',
        entryPrice: '75000.00000000', quantity: '13.333333333333333333', collateral: '100000.000000000000000000',
        markPrice: '99000.00000000', // Deliberately different current reference: never use as liquidation price.
        realized: '-100000.000000000000000000', badDebt: '99999.999999999999995000' } } },
    { evidenceId: 'tool-2', tool: 'getFuturesLiquidationEvidence', status: 'SUCCESS', data: {
      positionId, captureScope: 'PRESERVED_E2E_SNAPSHOT', freshChainVerification: false,
      verification: 'VERIFIED', scope: 'SELECTED_CALL_AND_LIQUIDATION_ONLY', onchainEscrow: false, payoutGuaranteed: false,
      operations: [
        { action: 'START_CALL', databaseState: 'CONFIRMED', verification: 'VERIFIED', chainTime: 1792454685, graceDeadline: 1792454745 },
        { action: 'LIQUIDATE', databaseState: 'CONFIRMED', verification: 'VERIFIED', price: '60000.00000000', collateral: '100000.000000000000000000',
          equity: '-99999.999999999999995000', maintenance: '39999.999999999999999000', chainTime: 1792454753,
          txHash: '0xb3459a30188845569a59096231dbeaa0c76ff32e3d660816767eb9e579c4ce8b', blockNumber: 88,
          journalId: '3bf96a8b-74fc-4cd4-b390-82bdf24dce94' },
      ] } },
  ],
  knowledgeSources: [{ id: 'adbb95c2b9cf50e2e625705e20bfcafff67f315c1b7a6fedbf2b8ea0d10028e2', title: 'onchain-settlement-policy.md', heading: '선물 FuturesEngine의 별도 확정 경계', version: '3' }],
  skill: { id: 'futures-liquidation-diagnosis', trace: [{ stepId: 'SUMMARIZE', status: 'VALIDATED', resultCode: 'FIXTURE_REPLAY' }] },
};
