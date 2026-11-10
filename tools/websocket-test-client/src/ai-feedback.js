export function aiFailureMessage(code, status) {
  if (code === 'ABNORMAL_FILTER_CLARIFICATION_REQUIRED') return '이상 주문 목록의 조회 건수·대기 기준이 모호하거나 지원 범위를 벗어났습니다. ADMIN 명시 필터 화면에서 1~20건, 60~3600초로 지정하세요.';
  if (code === 'AI_ACCOUNT_RATE_LIMIT') return '이 계정의 AI 요청 한도에 도달했습니다. 자동 재시도하지 않습니다.';
  if (code?.startsWith('AI_BUDGET_')) return 'AI 비용 승인·잔여 한도 또는 예산 저장소를 확인할 수 없어 공급자 요청을 차단했습니다. 거래 기능과 별개입니다.';
  if (code === 'AGENT_BUSY' || code === 'TOOL_BUSY') return 'AI 처리 용량이 찼습니다. 자동 재시도하지 않으며 잠시 후 직접 다시 요청할 수 있습니다.';
  if (code === 'AGENT_TIMEOUT' || code === 'AI_REQUEST_TIMEOUT') return 'AI 응답 기한이 지났습니다. 외부 호출 종료 여부는 별도이며 자동 재전송하지 않습니다. 거래 상태는 거래 화면에서 확인하세요.';
  return null;
}
