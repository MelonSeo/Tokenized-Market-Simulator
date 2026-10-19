---
id: futures-liquidation-diagnosis
name: 선물 청산 근거 진단
purpose: 인가된 포지션의 위험 계산과 청산 정산 근거 관측
version: 1
allowedRoles: [ADMIN, USER]
preconditions: [FUTURES_TARGET_REQUIRED, OWNED_OR_ADMIN_AUTHORIZED]
allowedTools: [getFuturesPosition, getFuturesLiquidationEvidence]
retrievalDomains: [trading, settlement, support]
steps: [LOAD_POSITION, LOAD_FUTURES_EVIDENCE, CHECK_EVIDENCE, RETRIEVE_POLICY, SUMMARIZE]
stopConditions: [AUTHORIZATION_DENIED, INVALID_LINK, DEADLINE_EXCEEDED]
forbiddenActions: [WRITE_DB, SIGN, BROADCAST, FORCE_SETTLEMENT, CHANGE_BALANCE]
outputSchema: diagnostic-v1
---
# 선물 청산 근거 진단

서버가 인가한 단일 포지션과 최대 두 건의 마진콜 및 청산 근거만 조회한다.
조회된 가격, 증거금, 손익, 유지증거금과 정규 Receipt 및 journal을 구분한다.
VERIFIED는 선택한 operation의 근거 범위이며 전체 자산 감사나 지급 보증이 아니다.
미확정 상태와 오래된 가격은 청산 확정이나 현재 위험 판단으로 과장하지 않는다.
DB 수탁 담보를 온체인 예치로 설명하지 않는다. AI는 거래 또는 복구를 실행하지 않는다.
