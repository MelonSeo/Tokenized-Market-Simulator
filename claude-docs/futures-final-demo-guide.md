# Futures 통합 데모 및 발표 근거 — 2026-10-19

## 한 문장 설명

PostgreSQL 사용자 모의자산 원장과 별도의 온체인 포지션 검증을 연결하고, 서버 Worker의 위험 감시·실제60초 유예·강제청산을 canonical Receipt/Event와 DB journal로 정산하는 하이브리드 앱이다. AI는 확인된 근거를 조회하고 설명할 뿐 금융 규칙이나 거래를 실행하지 않는다.

## 검증된 데모 순서

1. 신규 Synthetic 사용자 로그인/Faucet. 선물 화면에서 Long2배를 preview→명시 확인→개설, 추가담보, 전체 종료한다. 이어 Short5배를 개설/종료한다. 실시간 표시와 본인 개인 WS를 확인한다.
2. Long10배를 개설하고 신규 격리 fixture의 private 시나리오가 제품 Synthetic checkpoint를 공급한다. 제품 engine의 실제 tick 생성·candle commit·가격 공개 이후 Worker가 위험을 감지한다. 일반 사용자 가격 조작 API는 없다.
3. Worker가 서명한 마진콜을 계약이 검증한다. canonical 블록 시각부터 실제60초를 기다린다. 새 적격 가격에서도 위험하면 Worker가 강제청산을 호출한다. 계약이 스스로 실행된다고 설명하지 않는다.
4. Short10배 급등 청산도 같은 경로로 확인한다. 유예 내 회복 및 추가담보의 위험 해소, 실제31초 preview 만료도 별도 단계다.
5. 화면의 포지션·확정 손익과 Receipt/Event·DB journal·잠금0을 확인한다. Futures audit MATCH/complete coverage와 기존 reserve MATCH를 구분한다. 담보의 ERC-20 예치·사용자 온체인 소유권·미래 지급 능력 증명이 아니다.
6. 포지션의 AI 진단 버튼은 target 선택만 한다. 선물 Tool은 본인 데이터와 선택 CALL/LIQ의 수학·canonical/journal 근거를 조회하고 Skill trace를 만든다. **현재 실제 모델은 NOT_RUN**이다. 실행된 Mock Agent 결과는 실제 유료 모델의 답변 데모로 발표하지 않는다.

실제 재현 증거: 최신 제품 `phase-a-20261019120028-4ac049a3/futures-browser-655a2024`, 별도 AI fixture `futures-ai-0841d476`, CONFIRMED 두 JVM `futures-confirmed-46c6222d`. private runtime의 정제 결과와 desktop/mobile 이미지는 Git에 넣지 않는다. 최신 Chrome은 headless 설치 Chrome/CDP의 실제 서버 연결이며 offline Mock Chrome과 다르다. Long/Short 모두 call 이후62초에 청산됐다. 2/5/10의 양방향 산식 전수는 별도 Java/Solidity 테스트이며 Chrome에서 여섯 조합을 모두 정상 종료했다고 확대하지 않는다.

## 재현 명령과 환경 경계

프로젝트 루트에서 `python deployment/phase-a.py init`을 실행하고 출력된 **새 PHASE_A_SESSION 경로**를 복사한다. 다음 명령의 경로는 그 새 값으로 교체한다.

```powershell
$futuresSession = 'deployment/runtime/phase-a-새로출력된실제세션'
python deployment/phase-a.py fixture --session $futuresSession --case futures-browser
python deployment/phase-a.py fixture --session $futuresSession --case futures-ai
python deployment/phase-a.py fixture --session $futuresSession --case futures-confirmed
```

각 명령은 새 child DB/Anvil/키를 만들고 완료 후 자신의 컨테이너만 정지한다. 같은 실패 child 재사용·기존 fixture 초기화·baseline 재생성은 하지 않는다. `futures-ai`는 Mock 모델/검색이며 외부 API를 호출하지 않는다. 위 명령은 재현 방법이지 이번 보고서에서 새로 실행했다는 주장과 별개다. 실제 호출 횟수·PASS/SKIP/실패 이력은 [구현 로그](futures-implementation-log.md), [AI 결과](futures-ai-integration.md), [제품 Chrome/재시작](futures-product-browser-and-restart.md)을 따른다.

기존 현물 데모는 시장→Quote→BUY→Receipt/DB 정산→정확 가용수량 전량SELL→개인WS/portfolio→두대사 순서다. 기존 Google 실제 인증·현물 브라우저 증거와 이번 Futures 실제 브라우저 결과를 혼합하지 않는다. KRX/NXT 인수·실제 LLM·Sepolia·AWS/Android는 이 Synthetic 성공으로 대체되지 않는다.

## 발표에서 밝힐 한계

- DB 담보 정본과 온체인 상태/서명 검증의 역할 분리, operator 및 전용 Futures executor/가격 signer 신뢰가 필요하다.
- 유한한 10,000,000 mKRW 모의 pool은 실제 원화가 아니며 지급 부족을 막연히 보증하지 않는다.
- preview와 실행 가격이 다르면 재확인이 필요하다. 1초 Synthetic 갱신 중 사람이 오래 읽으면 반복 확인이 필요할 수 있다. 이를 숨기려고 허용 편차/TTL을 바꾸지 않았다.
- 자동청산에는 서버 Worker의 가용성이 필요하다. 장애 시 같은 raw/hash/nonce로 복구하며 미확정 거래의 잠금을 시간만으로 해제하지 않는다.
- AI의 실제 모델 자유 문장 정확성 및 갱신 index 검색 성능은 별도 비용 승인 후 평가해야 한다. 코드 구현과 실제 공급자 인수는 다르다.
