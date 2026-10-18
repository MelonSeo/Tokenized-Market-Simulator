# Futures + read-only AI — 구현 진행 기록

## 착수·보존

- T0: 2026-10-18 14:54 KST, 원본 HEAD `9a9197b290c57ce3351452fcf7f0794e6f24cdfc`, 새 기능 브랜치 `feat/futures-engine`.
- 사용자 최종 D01~09와 Futures+AI 지침 승인. A DB 담보, single mSEC/isolated/Long·Short/2·5·10, MMR5%, chain grace60초, 손실C한도, 신규 DB house10,000,000, fee/funding0, preview 가격변화재확인, 전용 executor/가격 signer.
- 유료 AI·실시세·Sepolia·배포·push는 미승인/실행금지. AI는 read-only Tool/청산 Skill/지식 보완을 핵심 E2E 뒤 연결; 실모델 PASS는 별도 비용 승인 전 주장하지 않음.
- 기존 README·implementation-log NXT 구간, 미추적 NXT 보고서, PROJECT_COMPLETION_MASTER_PLAN 및 FUTURES_48H_IMPLEMENTATION_PLAN은 작업 시작 상태로 유지. 기존 KRX/Google/playground·DB/Anvil·private 자료에 접근/변경하지 않음.
- 논리적 commit은 검증/독립 검토 후 명시적 경로만 stage. 기존 사용자 변경을 자동 stage하지 않음.

## Gate0~2 — 순수 산식과 온체인 상태 엔진 (진행 중)

### 구현

- 신규 Java `futures/math/FuturesMath`와 Solidity `FuturesMath`: E18/E8 정수, 음수 floor, 현재 notional5% ceil, 격리 손실/악성부채, 입력범위.
- 신규 `FuturesReportTypes`, `FuturesEngine`: 별도 domain·immutable signer/executor/policy·현재 한 포지션·open/topup/call/clear/close/liquidate·버전/epoch/operation일회소비. DB담보 수치의 온체인 검증이며 토큰 예치·전송 없음.
- 공유 JSON golden8개를 Java와 Foundry가 각각 읽고 검증. Foundry filesystem 권한은 그 한 fixture 파일 read-only만 추가.
- 아직 DB 서비스/REST/UI/실PG·Anvil/AI 연결은 미구현. Gate2 Foundry는 실제 Solidity EVM 테스트이며 배포된 Anvil E2E와 다름.

### 실행 기록

- 최초 `forge test --match-contract FuturesEngineTest -vv`: 9 PASS/1 FAIL/0 SKIP. 실패는 테스트에서 memory struct를 대입해 원본까지 변경한 stale report fixture 때문. 독립 report 생성으로 수정하고 새 실행 예정. 실패 결과를 최종 PASS로 덮어쓰지 않음.
- 수정 후 `forge test -vv`: 전체50 PASS/0 FAIL/0 SKIP(기존36+신규14), 신규 fuzz256 samples. 공유 golden8, 양방향·세배수·정상종료·유예전표본/미래가격/회복·담보인출거부·도메인/chain/signer/replay/version/epoch·음수equity 경계 포함.
- Java `gradlew test --no-daemon --tests '*FuturesMathTest' --rerun-tasks`: 4 PASS/0 FAIL/0 SKIP, 공유8벡터와 결정적2000개 계산 property 포함. 이 숫자는 DB/통합 테스트 수가 아님.
- 선택 경계 보강 후 새 `forge test -vv`: 52 PASS/0 FAIL/0 SKIP(기존36+신규16). 새 Java 선택실행은5 PASS/0 FAIL/0 SKIP. 최대값직전·0/상한·TTL·실패후미소비·회복후epoch/deadline 보강을 포함하며 앞선50/4와 구분한다.

### 검토/다음

- 독립 최초 `review_futures_core`: 발견된 필수 수정 없음. 선택 권고인 최대지원범위/0/TTL/실패operation미소비/회복후다음epoch·deadline을 추가 검증하고 재검토한다. 검토자는 직접 테스트/DB/Anvil/private를 실행하지 않음.
- ABI 구체화: 동작별 별도 이벤트 대신 `OperationApplied(action,...)`+`FuturesReportConsumed` 사용. call 시작 시각은 deadline−고정60초, 마지막 승인 가격은 report/event에서 추적하며 storage에는 lastObservedAt을 유지한다. 정산 parser는 이 실제 ABI와 explicit receipt block state를 검증해야 한다. 경제 정책/온체인 책임은 변경 없음.
- 독립 최종 재검토 `review_futures_core`: 발견된 필수 수정 없음. 선택 경계 테스트/ABI 기록을 확인했으며 검토자 직접 재실행은 없음. 이 마일스톤은 순수/Foundry까지만 완료이고 DB/Anvil/UI/AI·전체 backend 인수는 미완료다.
- 다음: 금융·서명 부정 경계 보강→단위 전체 확인→독립 검토→안전 commit→DB/operation/AI evidence DTO.
