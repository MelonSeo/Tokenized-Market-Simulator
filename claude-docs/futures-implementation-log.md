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

## Gate3 — 증거금·손익 풀 원장과 조회 DTO (진행 중)

- 앞선 core는 `9b079636dcfdf79ac93641f1c85cc3a052c4e2ff`로 로컬 기능 commit 완료. 기존 사용자 문서/마스터/NXT는 포함하지 않았다.
- 신규 opt-in `futures-schema.sql`/`FuturesSchema`/`FuturesRecords`/`FuturesLedger`: 기존 잔고 행과 같은 PostgreSQL row lock 사용, 사용자 guard→position→operation→house→balance 순서. FIFO/체인 요청은 아직 이 서비스에 없다.
- 증거금은 amount에 포함된 locked만 예약, 실제 PnL은 canonical 검증 경계가 전달한 event와 저장 op/tx가 맞을 때만 확정. 양수 R은 house 선예약, 음수는 C한도. journal+counter 불일치 차단, PostgreSQL policy/journal append-only, nullable unique active_position_id로 동시 op 제한.
- `FuturesEvidenceView`는 AI/REST 공용 allowlist DTO를 먼저 정의했다. raw/signature 필드가 없으며 미확정 사실을 확정으로 승격하지 않는다. 아직 Agent/Tool에 연결하지 않았다.
- 초기자본은 명시적 `initializeEmptyFixture()`만 수행하며 기존 지급/거래/유잔고가 있으면 초기화 차단. production schema/구DB에 실행하지 않았다. 초기화 호출은 향후 owned fixture harness에서만 하고 사용자 API로 노출하지 않는다.
- 현재 H2 신규 테스트8 PASS/0 FAIL/0 SKIP + math5 PASS. H2의 ConfirmedEvent/서명 row는 의도적인 가짜 데이터이며 실제 Receipt/Anvil 검증 결과가 아니다.
- 기존 Phase A 도구에 `futures-ledger` case만 추가. 새 DB명/child regex를 명시적으로 제한하고 기존 소유권/시각/소스hash/외부credential차단/cleanupguard를 유지. Python harness/clock15 PASS.
- 새 실제 fixture 세션 `phase-a-20261018062722-b0e9b7ab`를 준비했고 실제PG 경쟁·rollback 검증은 실행 중이다. 현재 기존 보존 환경에는 어떤 변경/재시작/삭제도 하지 않았다. Docker server28.4.0 정상 읽기 확인.
- 전체 backend/web 회귀·FuturesEngine 배포/가격서명·실제 futures receipt/복구·위험worker·UI/브라우저·Agent는 아직 미완료다.
- 첫 실제PG `futures-ledger-a5a75869` 1 PASS. 독립 최초 검토에서 DB_ONLY 현물의 총잔고 검사가 새 담보를 소비할 수 있음을 발견했다. `OrderService.requireBalanceOrFail`을 available 검사로 최소 수정했다(수수료/체결 정책 불변). 실제PG 현물/Faucet 경쟁 누락도 지적되어 별도 `futures-db-only` fixture/테스트 추가.
- 수정 후 새 session `phase-a-20261018063236-0238b988`: `futures-ledger-5d98263c` 1 PASS/0 FAIL/0 SKIP. `futures-db-only-ebd5ef98`는 context 기동1 FAIL: 테스트가 blockchain=false이면서 price-report startup=true를 유지한 설정 오류였다. 기존 제품 정책을 바꾸지 않고 테스트의 price-report도 명시false로 맞췄다. 실패자료/DB/Anvil은 보존하고 새 child에서 재실행한다.
- 새 child `futures-db-only-3f20cf15`: 1 PASS/0 FAIL/0 SKIP. 실제 PG에서 담보100k/DB_ONLY950k거부, 현물700k vs 담보700k 경쟁의정확1성공, Faucet vs 선물 예약경합·잠금/금액보존을 확인했다. 이 테스트는 DB_ONLY 현물이며 onchain 현물/선물 실제Receipt 검증으로 확장하지 않는다. 신규 fixture는 run label 확인 후 정지, 데이터/실패자료 보존.
- 동일 최신 제품 소스의 새 전체 회귀: backend548중508 PASS/40 SKIP/FAIL0/ERROR0, web107 PASS/0 FAIL/0 SKIP, 실제Chrome desktop/mobile **offline fixture** PASS(GIS실외부0), Foundry52 PASS/0 FAIL/0 SKIP. 이 run은 위 실제PG2종과 별개다. 실제 선물 signed 정산/house경쟁·Receipt·복구·브라우저chain은 아직 NOT_RUN이다.
- 독립 첫 재검토 `review_futures_ledger`: 앞선 available 검증/실제PG 공유잔고경합 필수2건 해결, 발견된 추가 필수 수정 없음. 검토자 직접 실행/private열람은 없으며 위 새 전체 회귀는 구현자가 별도 수집했다. 이 단위는 준비/장부·DTO/보호 회귀까지만 검증됐고, 실제선물 청산 완료가 아니다.
