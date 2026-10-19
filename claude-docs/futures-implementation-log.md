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

## Gate4a — 전용 EIP-712 codec·서명 선저장·동일 raw 복구 (진행 중)

- Gate3 장부 단위는 `d966c0b886114509db86967a7c0814b3a1a9c5d3`로 로컬 커밋. 기존 사용자 문서는 포함하지 않았다.
- `futures/chain/FuturesReport`, `FuturesReportCodec`: Solidity와 동일한 18필드 static tuple, 별도 Futures domain, chainId/engine/action/version/epoch/정책 결합, low-S/65byte 서명 검증. Public DTO/API에 report/signature를 추가하지 않았다.
- `FuturesTransactionStore`: 새 sender guard, PREPARED operation/position과 report의 완전 일치 및 raw sender/chain/nonce/to/data/hash 확인, REQUIRES_NEW로 raw/report/signature 선커밋. sender별 미확정 SIGNED/SUBMITTED/REVIEW_REQUIRED가 있으면 다른 신규 서명 진입을 거절한다. 이는 분산 nonce 할당 구현이 아니다.
- `FuturesTransactionSender`: 전용 executor만 사용하고 process-local synchronized. 전송 전 RPC 오류는 미서명 실패, 선저장 후 응답 유실/거부/hash 불일치는 SIGNED 유지. 복구는 저장 raw만 재전송하며 nonce 재조회/재서명하지 않는다. 확인/검토 상태를 지연된 SUBMITTED로 되돌리지 않는다.
- `Transport`는 다음 실제 RPC 경계의 계약이다. 현재 프로덕션 Bean/worker/REST와 아직 연결하지 않았으며, 배포 identity 검증은 Transport 구현의 필수 조건이다. 이 단계에서 외부 RPC·키·환경 설정을 사용하지 않았다.
- 선택 실행 `gradlew test --no-daemon --offline --tests '*FuturesTransactionSenderTest' --tests '*FuturesReportCodecTest' --tests '*FuturesLedgerTest' --tests '*FuturesMathTest' --rerun-tasks`: 25 PASS/0 FAIL/0 SKIP. 구성은 sender9(H2+fakeTransport), codec3(독립 StructuredDataEncoder 비교 포함), ledger8(H2), math5. 실제 Anvil/Receipt 성공으로 해석하지 않는다.
- 실제 Solidity digest/ABI 상호운용, canonical Receipt/Event, failed Receipt 잠금 해제, 새 PG/Anvil 서명·응답유실 복구는 다음 Gate4b/5에서 검증한다. 별도 검토는 요청 예정이며 실제 모델/AI/브라우저 선물은 NOT_RUN이다.
- 독립 `review_futures_signing`: 발견된 필수 수정 없음. 실제 코드/ABI/테스트를 읽었고 직접 테스트·private 열람은 하지 않았다. 다음 facade는 prepare 커밋 후 sender를 호출하고 응답의 status 대신 저장 상태를 정본으로 재조회해야 한다. 실제 Transport/canonical/PG 다중 sender 검증은 다음 단계의 필수 과제다.

## Gate4b/5 — 실제 RPC·canonical 정산 경계 (진행 중 / Anvil E2E BLOCKED)

- Gate4a는 `93e544558066615ca7e87704168bc63cc2d1e7af`로 로컬 커밋했다.
- 신규 `FuturesRpcGateway`: 전용 Web3j를 직접 받아 현물 Bean과 분리, chainId/배포 codeHash/executor/signer/symbol/policy 검증. 원문 RPC error는 외부로 전달하지 않는다. 없는 tx/Receipt의 null은 정상 미확정 조회로 처리한다.
- 신규 `FuturesReceiptVerifier`/`FuturesReconciler`: explicit blockNumber/hash, tx의 sender/to/nonce/input/value, 정확한 두 이벤트 및 11개 position storage 필드 검증 후 canonical hash 재조회. 확인수 미달·canonical 변경·RPC 장애는 대기/조회불가로 유지한다. canonical status0만 실패 정산, 증거 충돌은 REVIEW_REQUIRED로 격리한다. chain 재조직과 DB commit 사이 완전한 원자성/최종성 보장은 아니며 저장한 확정 기준과 확인수를 별도 감사할 필요가 있다.
- Ledger의 canonical 실패 정산은 OPEN 예약 또는 TOP_UP 추가분만 해제한다. CLOSE/LIQUIDATE 실패는 기존 담보 유지, house 지급 예약만 해제한다. 중복 실패 Receipt guard·충돌 거부·REVIEW_REQUIRED 무해제를 추가했다. 새 준비/성공은 오래된 block_reason만 지운다.
- 기존 Phase A 도구를 확장한 `futures-chain`: 새 별도 4키·DB·Anvil, 전용 local deploy script, codeHash pin. 기존 init-chain/현물 계약은 변경하지 않았다. source hash 범위에 새 배포 script/entrypoint/Dockerfile을 추가해 변경된 소스에는 반드시 새 session이 필요하다. 기존 manifest를 새 값으로 덮어쓰지 않는다.
- 신규 실제PG/Anvil 테스트는 정상 Long/Short 세배수, 회복, 추가담보, 실제 wall-clock60초 유예 후 양방향 badDebt 청산, 실제 전송 후 응답유실·새 sender 동일 raw 복구/단일 정산을 준비했다. 이는 별도 JVM 재시작·실제 provider/브라우저 E2E를 대신하지 않는다. 아직 실행하지 못했다.
- 오프라인 최신 선택 실행: 35 PASS/0 FAIL/3 SKIP. codec3/sender9/H2ledger10/Receipt fixture5/local HTTP RPC3/math5. 3 SKIP은 실제PG ledger/DB-only/chain gates이며 PASS에 포함하지 않는다. Python guard/clock15 PASS. 새 `forge test -vv`52 PASS/0 FAIL/0 SKIP.
- `forge build --offline` 전체 script 컴파일은 기존 `script/Scenario.s.sol:61` stack-too-deep로 FAIL. 기존 script는 수정하지 않았고 `forge build script/DeployFuturesFixture.s.sol --offline` 신규 배포script 단독 컴파일은 PASS. 전체 build FAIL은 Foundry52 PASS와 별도로 남긴다.
- 신규 session 초기화 시도 `phase-a-20261018142550-cd91140d`: Docker Server version이 LinuxEngine HTTP500을 반환해 중단했다. 도구 init의 cache inspect 대기도 취소했다. 새 PG/Anvil fixture/배포/거래는 NOT_RUN, 실제 chain E2E는 BLOCKED다. 기존 Docker/WSL/서비스를 재시작하거나 기존 데이터를 정리하지 않았다. 해당 private 준비 로그를 보존한다.
- 제품 RPC Bean/feature config·시장 preview/facade·위험 worker·WS/UI·선물 감사/대사·AI 통합은 아직 후속이며 전체 완료가 아니다. 독립 검토 요청 예정. Docker 정상화 후 새 session/fixture에서 실제 test를 실행하기 전까지 이 Gate를 통과/통합하지 않는다.
- 독립 최초 `review_futures_receipts`: 필수2건 발견. (1) log의 canonical block/txIndex/logIndex 결합 누락, (2) Reconciler 전체 분기 오프라인 테스트 누락. 검토자는 소스만 읽고 테스트/private를 실행하지 않았다. 각 log의 블록·거래 인덱스/누락값·서로 다른 증가 logIndex를 검증하도록 수정하고 메타데이터 변조10종, H2+mockRPC의 직접 Reconciler7종을 추가했다.
- 첫 보완 선택실행은42 PASS/1 FAIL/3 SKIP. 실패는 IOException을 던지도록 설정한 Mockito 메서드를 다시 when()으로 설정하면서 테스트 자체가 IOException을 받은 문제다. doReturn 재설정으로 고친 뒤 새 실행43 PASS/0 FAIL/3 SKIP. 실제PG/Anvil 결과가 아니며 실패 기록은 보존한다.
- 추가 chain E2E 준비: 실제PG 동시 reconcile의 단일 journal, 전송전 장애로 보존한 raw를 실제30초 TTL 만료 후 그대로 재전송→status0→예약만 단일 해제를 포함했다. 아직 Docker 차단으로 NOT_RUN이며 별도 JVM restart를 대신하지 않는다.
- Docker 차단과 무관한 기존 `clean_env`/`java_test`만 직접 재사용해 새 오프라인 전체 회귀를 수행했다(환경 생성·source identity guard 우회 목적이 아님; DB/RPC gate 전부OFF). **검토 보완 전** 실행 `futures-offline-backend-full`: backend571중530 PASS/41 SKIP/0 FAIL/0 ERROR. 새 웹107 PASS 및 Chrome desktop/mobile offline fixture PASS. 이 결과는 이후 메타데이터/Reconciler 수정 후 전체 회귀와 구분한다.
- init에 Docker Server version15초 bounded preflight를 추가해 Engine 불응 시 이미지 build/fixture 생성 전 중단한다. Docker/WSL 자동 재시작은 하지 않는다. Python guard/clock 재실행15 PASS. 검토 보완 후 전체 backend 새 실행과 재검토는 진행 예정.
- 독립 첫 재검토 `review_futures_receipts`: 앞선 필수2건 소스상 해결, 발견된 추가 필수 수정 없음. 검토자는 직접 테스트/private/DB/Docker를 실행하지 않았다. 이 결론은 오프라인 보완 범위이며 실제 PG/Anvil NOT_RUN/BLOCKED를 해소하지 않는다. 보완 후 전체 회귀 결과는 별도 추가한다.
- 보완 후 최신 소스 새 전체 회귀 `futures-post-review-backend-full`: backend579중538 PASS/41 SKIP/0 FAIL/0 ERROR. 앞선530/41과 별개 실행이다. 새 테스트까지 컴파일됐으나 opt-in actualPG/Anvil gate는 OFF라 41 SKIP을 PASS에 포함하지 않는다. 웹107/Chrome fixture/Foundry52는 이번 작업의 별도 새 실행이며, 실제 Futures chain/UI/AI 결과가 아니다.
- 현재 검증 완료 local commit: core `9b07963`, 장부 `d966c0b`, 서명 전송 `93e5445`. Gate4b/5는 actual chain 검증 전이므로 미커밋으로 유지한다. `melon-init` ref `9a9197b` 유지, 기존 README1줄/implementation-log7줄 및 미추적 사용자 문서 보존을 git diff/status로 재확인했다. push/merge/기존 환경 초기화 없음.
- 2026-10-19 KST 후속: 제한된 Server 읽기 확인에서 Docker28.4.0이 정상화됐다(에이전트의 daemon/서비스 재시작 없음). 실패 준비 세션은 보존하고 새 `phase-a-20261018145558-84d44508`를 생성했다. 새 실제PG/Anvil `futures-chain-e7f17884`는 격리/시각/배포 통과 후 테스트1 FAIL/0 SKIP: `EmptyResultDataAccessException`, 테스트 user helper47줄에서 대문자 `LONG/SHORT`를 포함한 loginId로 조회했으나 기존 AuthService91~92가 lower-case로 저장한 fixture 오류다. 제품 인증/금융 정책은 변경하지 않고 테스트 ID를 Locale.ROOT lower-case로 맞췄다. 실패 fixture/로그를 초기화하지 않고 새 child로 재실행한다.

### Gate4b/5 실패 조사 및 새 actual E2E — 2026-10-19

- 실행별 최초 오류·분류·조치·보존 근거는 [실패 조사 보고서](futures-phase-a-failure-investigation.md)에 기록했다. 과거 NOT_RUN/BLOCKED는 당시 이력이며 아래 새 결과와 구분한다.
- Gateway는 자체 Web3jService를 소유하고 같은 RPC 경계로 identity/전송을 수행한다. 새 gas 추정은 명시적 pending, 알려진 revert만 고정 코드로 분류한다. 원문 오류는 노출하지 않으며 경제/청산 정책은 변경하지 않았다.
- 테스트 결함은 정규화된 ID 조회, receipt 비동기 준비, 호스트 경과와 실제 chain 시각을 혼동한 만료 전제였다. 신규 전용 fixture에만 실제2초 interval mining과 bounded clock readiness를 적용했다. 기존 실패 fixture/기존 환경의 시간·데이터를 수정하거나 강제 mine하지 않았다.
- 새 `phase-a-20261018164330-b375a5df / futures-chain-2b2c3e6a`: 실제 PostgreSQL/Anvil **1 PASS/0 FAIL/0 ERROR/0 SKIP**. Long/Short 2·5·10 개설/정상 종료, 회복/추가담보, 실제60초 뒤 양방향 청산, 실제 응답유실 후 같은 raw/hash/nonce 복구, concurrent 단일 정산, 실제30초 만료 후 status0 실패 정산을 확인했다.
- 청산 실제 경과64,421ms/68,354ms, 만료 raw34,158ms 및 canonical block의 TTL 초과를 정제 증거로 보존했다. 최종 미확정0·잠금0·house/journal 보존도 통과했다. 별도 JVM restart나 자동 worker/브라우저 검증으로 확대 해석하지 않는다.
- Java 선택44 PASS/3 SKIP, Python guard/clock16 PASS. 최종 두 번째 독립 재검토 `review_futures_receipts`는 추가 필수 수정 없음. 직접 실행은 구현자가 수행했고 검토자는 소스/테스트/문서만 읽었다.
- 같은 최종 소스 전체 회귀를 새 실행 중이며 완료 수치는 후속 기록한다. 전체 선물/Phase D PASS 또는 원본 브랜치 통합을 선언하지 않는다.
- 최종 새 전체 회귀 완료: backend580 중539 PASS/41 SKIP/0 FAIL/0 ERROR, 웹107 PASS, Chrome desktop/mobile offline fixture PASS, Foundry52 PASS/0 FAIL/0 SKIP. Python guard/clock도 새16 PASS. 기존 Scenario 전체 build FAIL 이력은 별도 유지한다.
- 최종 제품 sourceHash `fa949057e0f12034f99afc80a2bdac344a4dde1d888d92713f9c0647f7fb3ee8`의 독립 새 actual PG fixture `futures-ledger-299995bd` 및 `futures-db-only-4d54fc1c` 각각1 PASS/0 FAIL/0 SKIP. 위 futures-chain과 합쳐 새3개 독립 E2E 통과이며, 이전 실패 증거는 보존한다.
- 이로써 RPC/canonical 정산 core 단위의 실패는 해결됐지만 전체 선물 완료는 아니다. 자동 위험 worker·preview/REST·개인WS/UI·별도JVM restart·선물 감사/대사·AI 연결·실Chrome chain 데모는 후속 필수이다. 검증된 이번18개 경로만 로컬 커밋하며 기존 사용자 변경/원본 ref/모든 보존 환경과 manifest는 그대로 유지한다.

## Gate6 — 자동 위험 감시·preview/API·개인 알림 연결 (진행 중)

- 재개 시 실제 HEAD는 `0d7c6272e2ac94ea670c021f6481e6aaf263272b`로 Gate4b/5의18개 경로가 이미 커밋돼 있었다. 중복 커밋하지 않았다. 원본 melon-init은9a9197b, 사용자 README1줄/log7줄·NXT/두 계획 문서는 보존 상태다. 사용자가 지칭한 `docs/futures-implementation-log.md`/`docs/futures-phase-a-failure-investigation.md`의 실제 기존 위치는 `claude-docs/`이며 복제 문서를 만들지 않는다.
- 새 opt-in runtime은 별도 flag·RPC client/키/identity 검증을 사용한다. core-only fixture나 기본 서버에서는 자동 worker/RPC를 시작하지 않는다. 초기 house 자본 자동 삽입·기존 장부 migration·기존 환경 설정 변경은 없다.
- `FuturesMarketGate`, `FuturesRiskEvaluator`, `FuturesWorker`: commit된 공개 가격 이벤트는 bounded 전용 worker를 깨우기만 하고,1초 보조 tick도 같은 evaluator를 사용한다. pending raw 복구/정산은 시장 장애에도 지속, 새 risk 서명은 allowed/fresh/generation·CLOSED/STale 경계와 zero-drift를 지킨다. chain-confirmed deadline60초/새 표본/실제 chain시각을 확인하며 Solidity도 재검증한다. 단일 프로세스 executor 모델이며 분산 nonce 구현은 아니다.
- `FuturesIntentStore`는 본인 previewId·action/version/가격/provider/generation·원래 관측시각과30초 만료를 저장한다. 일회 소비+기존 ledger prepare+내부 시장 근거는 동일 짧은 DB transaction이다. 새 서명 직전에는 RPC gas 추정 뒤 generation/고정가격/신선도를 재검증한다. 사용자는 보고서/서명이 아니라 ID만 제출한다.
- 신규 USER REST preview/positions/open/topup/close/operation 조회, ADMIN positions/operations/house/risk 읽기만 추가. 새 개인 `/user/queue/futures`는 after-commit ID invalidation이며 raw/키/서명/다른 user 자산을 전달하지 않는다. 기존 현물 API/Google/JWT 의미는 그대로다. 선물 UI/실네트워크 WS/실제 REST 권한 인수는 아직 후속이다.
- 첫 단위 실행57 PASS/3 SKIP, 보강 후 새 선택63 PASS/3 SKIP/실패0(실제PG gates OFF). H2 preview atomic consume/replay/owner/rollback, 순수 위험/market, worker mock, post-RPC pre-sign guard/개인 알림을 포함한다. 후속 변경이 있으므로 최종 전체 회귀로 승격하지 않는다.
- 신규 `phase-a-20261018173232-26b2a727`의 `futures-auto` 실행을 시작했다. 이 테스트는 테스트 전용 PG 합성 tick commit 후 실제 runtime worker가 CALL/LIQUIDATE를 자동 제출하게 한다. 기존 core처럼 테스트가 위험 action을 직접 호출하지 않는다. 실제 canonical60초와 real monotonic60초 경과를 모두 확인하며, 이전 fixture/state/manifest는 수정하지 않는다. 아직 결과 미확정이다.
- 별도 JVM restart·실제Chrome·선물 감사/대사·AI는 여전히 미완료이며 이번 단위 통과와 구분한다. 기존 Scenario 전체 build FAIL 별도 유지. 실시세/유료AI/배포/push 없음.
- Gate6 새 actual `futures-auto-3c9a8e81`:1 PASS/0 FAIL/0 SKIP. 실제 server worker가 Long/Short call과 liquidation을 전송했다. Long call block1792344979→deadline1792345039→청산 block1792345041, 위험 tick 이후63,851ms/GRACE 관측 이후61,958ms. Short call1792345045→deadline1792345105→청산1792345107,63,712ms/61,906ms. 양쪽 모두 새 observedAt>=deadline, canonical Receipt/Event 및 journal/잠금 검증을 통과했다. CLOSED/STale pause, 재개, recovery clear, topup도 같은 테스트에서 확인했다.
- 같은 고정 backend 소스의 새 전체 회귀는 backend600 중558 PASS/42 SKIP/0 FAIL/0 ERROR. 웹110 PASS, 기존 Chrome desktop/mobile offline smoke PASS, Foundry52 PASS. 새 선물 UI 전용 브라우저/실체인 검증은 포함하지 않았다. 42 SKIP은 opt-in gates이며 위 actual 결과와 중복 합산하지 않는다.
- 독립 `review_futures_runtime`: runtime/API/DTO/preview/worker/서명 재검증/알림/보안/schema/tests/fixture/로그를 직접 읽고 발견된 필수 수정 없음. 직접 테스트/DB/private는 실행하지 않았고 검토 환경 Git diff 조회는 실패하여 현재 파일을 직접 읽었다. 실제 broker commit/rollback·REST 권한·별도 JVM·제품 Synthetic engine 통합은 후속 필수로 유지한다.
- Gate6 backend 단위를 검증된 명시 경로만 로컬 커밋한다. 새 웹 패널은 별도 미커밋 후속 작업이며 이 backend 검토·actual 통과로 UI 완료를 주장하지 않는다.

## Gate7 후속 — UI·실제 네트워크/별도 JVM 검증 준비 (진행 중)

- Gate6 검증 단위는 `452c5db8e0ef9e92a81d79323af2e8b938b7c1dc`로31개 명시 경로만 로컬 커밋했다. UI/사용자 문서는 제외, melon-init/기존 환경 변경 없음.
- 웹 `futures-panel.js`와 새 선물 메뉴: Long/Short·2/5/10·정밀 문자열 증거금, preview→별도확정, 포지션/손익/마진콜 deadline/자동청산 결과, topup·종료 preview, 본인 WS invalidation 및3초 REST 재동기화. 손익 풀 지급불가/DB담보/서명가격 한계를 표시한다. 요청 전 user별 pending intent를 Web Lock+localStorage에 보존하고 불확실 응답은 같은키만 명시 재시도, 계정/route 전환 늦은응답 무시. raw/키/서명 없음.
- 새 웹 단위115 PASS/0 FAIL/0 SKIP: exact decimal·다중탭 동일키·늦은 read/preview/write·preview는 주문하지 않음·중복 확정/응답 유실·bounded API 경로를 포함한다. Chrome desktop/mobile smoke에 선물 preview/call/liquidation **HTTP/STOMP fixture** 화면을 추가해 실행 중이다. 실제 서버/체인 Chrome 완료가 아니다.
- `FuturesApiPostgresAnvilE2ETest`는 실제 HTTP USER/익명/다른user/ADMIN 경계, null loginId 내부JWT fixture, 개인 STOMP, 실제 개설/종료, rollback 알림 미전달을 준비했다. `FuturesRestartPostgresAnvilE2ETest`는 동일 owned fixture에서 두 별도 JVM의 raw 선저장→복구 및 위험 감시 재개를 준비했다. `phase-a.py`에 전용 case/두 JVM stage를 추가하고 Java ownership/mining guard도 확대했다. `compileTestJava --offline` PASS이며 **실제 테스트는 아직 NOT_RUN**.
- 2026-10-19 새session `phase-a-20261019001704-a759f4eb` 준비가 `docker version --format {{.Server.Version}}`의15초 timeout에서 **BLOCKED**. 이어 읽기 전용 `/version`·`/info`는 LinuxEngine API HTTP500을 반환했다. fixture 생성·DB/Anvil/자산 초기화·실제 API/restart E2E는 시작하지 않았다. 빈 timeout 원문 파일/metadata도 보존하며 원인을 제품 코드 실패로 단정하지 않는다. daemon/WSL 재시작·기존 환경 reset 없음.
- Docker 차단 중에는 새 실제 E2E를 반복하지 않고 오프라인 UI/감사 작업을 진행한다. 별도 JVM/실REST·WS·실Chrome·선물 감사·대사 결과를 기존 auto PASS로 대체하지 않는다.
- Chrome 확장 smoke 최초 실행 PASS: desktop/mobile 선물 preview→명시확정→GRACE/LIQUIDATED fixture·기존 현물/Google fixture. 실제 chain이 아니다.
- 독립 `review_futures_ui_restart` 최초 검토에서 UI 필수3건 발견: (1) 확정 미실행 거절도 pending을 영구 유지, (2) 입력 변경 뒤 늦은 preview가 복원, (3)3초/WS 갱신이 topup 입력을 지움. 서버/금융정책 변경이 아니라 요청·표시 정합성 결함으로 보완한다.
- 확정 미실행 처리는 모든4xx를 지우지 않는다. 새 `futures_request_rejections`에 user/key fingerprint/request hash만 저장하며, prepare와 같은 잠금 순서로 먼저 접수된 op를 반환하거나 거절 marker를 커밋한다. 늦게 도착한 같은키 실행은 marker로 차단한다. 서버가 이 근거를 커밋한 전용 `FUTURES_REQUEST_REJECTED`만 클라이언트 pending 해제를 허용하고, 네트워크/미확인409/5xx는 원래키를 보존한다. key원문·보고서·키재료는 저장하지 않는다. 기존 성공 op/원장/정산은 수정하지 않는다.
- preview 별도 generation과 방향/배수 확인 표시, position별 exact topup draft 보존을 추가했다. 새 웹119 PASS/0 FAIL/0 SKIP, H2 marker 선/후 순서 및 기존Futures 회귀·Chrome 재실행은 진행 중이다. 새 backend sourceHash가 달라졌으므로 Gate6 actual PASS를 이 변경의 실제PG PASS로 재사용하지 않는다. Docker 차단으로 최신 실제PG 경쟁/API/restart는 NOT_RUN이다.
- 수정 후 선택 Java71 중65 PASS/6 SKIP/0 FAIL/0 ERROR. 최신 productHash `060d393d36a1e4a55ae834d9ba0ea50eac14be8cc349cdaf1b85099b7fb4282e`. 실제PG opt-in6개는 SKIP이며 H2와 혼동하지 않는다. 새 전체 backend604 중560 PASS/44 SKIP/0 FAIL/0 ERROR(`futures-ui-rejection-backend-full`), 웹119 PASS, 확장Chrome desktop/mobile **offline fixture PASS**, Foundry52 PASS/0 FAIL/0 SKIP. 최초Gate6의558/42와 구분한다.
- Python harness 새18 PASS. 처음 추가한 두 stage 테스트는 shared mock dict 때문에17 PASS/1 FAIL이었고 실제 clean_env처럼 호출별 dict를 반환하도록 테스트만 수정한 뒤18 PASS다. 로그에 출력된 prepare/recover1PASS는 Mock 집계이며 실제 JVM 증거가 아니다.
- `review_futures_ui_restart` 첫/최종 두 번째 재검토 모두 추가 필수 수정 없음. 첫 재검토는 UI3건/새 DB 거절 marker·잠금/선후 방어를 확인, 두 번째는 신규harness2테스트/기존conditionalrestart 보존을 읽었다. 직접 테스트/DB/private 실행은 없다. 실제PG 경합·API·별도JVM·실Chromechain 미실행 한계를 유지한다.

### 현재 통합 판정과 재개 기준

| 항목 | 실제 상태 |
|---|---|
| Gate4b/5 core |0d7c627 로컬 커밋, 당시 새PG/Anvil3fixture/전체회귀 통과 |
| Gate6 자동 worker/API backend |452c5db 로컬 커밋, 당시 sourceHead0d7c627/productHash99bb1a89bc688c9cf8e95d43325235cdb72f8847e92645b1bceed8aa4d96ed68의 실제 자동 양방향 청산1PASS·전체회귀 통과 |
| 최신 UI/확정거절 보완 |오프라인/독립 검토 통과, **미커밋**. PostgreSQL 동시 경합 필수 검증 대기 |
| 실제 REST·개인 broker/별도 JVM |테스트 컴파일/guard mock 준비, Docker Server HTTP500으로 BLOCKED/NOT_RUN |
| 제품 Synthetic 경로/실Chrome→서버→체인 |후속 미구현·미실행. offline UI 및 테스트용 Scenario 증거로 대체하지 않음 |
| 선물 감사·자산 대사 확장/AI Tool·Skill·RAG |미구현/NOT_RUN, 기존 현물 감사/AI 통과와 구분 |
| 원본 통합/외부 인수 |아직 안 함. melon-init9a9197b·원래 사용자dirty5개·KRX/Google/playground/키/state/manifest 보존 |

- 현재 Docker 차단은 애플리케이션/경제 정책 오류로 해결할 수 없으며 전역 daemon/WSL 재시작은 실행하지 않았다. Engine의 `docker version`/`docker info` Server 정상 응답을 확보한 뒤 최신 productHash의 **새 session**을 만든다. 실패 session/이전 child는 초기화하거나 manifest를 바꿔 재사용하지 않는다.
- 다음 실행 순서: 최신 거절 marker의 실제PG 양방향 경합→`futures-api` 실제REST/STOMP→`futures-restart` 두 별도JVM→최신 source actual auto 재확인. 필수 검증 통과 뒤 UI/marker/harness 논리 커밋, 이후 선물 감사/자산 대사→fixture전용 제품Synthetic 경로/실Chrome→read-onlyAI를 이어간다.
- 발표 가능한 현재 증거는 core Receipt/DB 정산 및 이전source의 worker 양방향 자동청산 tx/실제60초 유예다. 최신전체 실제브라우저 데모/별도JVM/선물감사/AI 설명이 완성됐다고 발표하지 않는다. 최종통합 PASS 아님.

### Gate7 실제 PostgreSQL/REST/STOMP 재개 — 2026-10-19

- 읽기 전용 Docker Server version/info가 모두28.4.0으로 정상 응답했다. 에이전트가 Docker Desktop/WSL/공유 서비스를 재시작하지 않았다. 앞선 HTTP500/timeout 세션은 그대로 보존했다.
- 새 session `phase-a-20261019004856-29dfdfbb`, sourceHead `452c5db8e0ef9e92a81d79323af2e8b938b7c1dc`, productHash `060d393d36a1e4a55ae834d9ba0ea50eac14be8cc349cdaf1b85099b7fb4282e`. 새 child별 전용 PG/Anvil/키/포트/네트워크와 기존 ownership·clock·productHash guard를 유지했다. 기존 KRX/Google/playground 및 melon-init은 변경하지 않았다.
- `futures-requests-9d0f010c`: 실제 PostgreSQL **1 PASS/0 FAIL/0 SKIP**. 거절 선행/접수 선행과 barrier 기반6회 동시 요청에서 단일 operation 또는 동일 확정 거절, 정확한 잠금, 같은 키 replay를 확인했다. 이 case는 Anvil fixture를 준비하지만 체인 RPC 거래 검증은 하지 않는 DB 동시성 테스트다. 정리는 미서명 PREPARED만 기존 rejectUnsigned로 처리하며 서명 거래 강제 실패와 다르다.
- `futures-api-9f0b4a55`: **1 FAIL**, 테스트가 loginId만NULL로 바꾸어 실제 chk_users_login_method 제약을 위반했다. 제품 인증은 변경하지 않고 fixture만 loginId/passwordHash=NULL 및 offline google_sub로 수정했다. 실제 Google 인증 결과가 아니다.
- `futures-api-36c56ae1`: **1 FAIL**, 거래 전 개인 STOMP 준비 probe 미수신. 테스트 StringMessageConverter 대신 서버 JSON envelope에 맞는 MappingJackson2MessageConverter/JsonNode handler를 사용하도록 보완했다. 실패 child와 XML/로그는 보존했다.
- `futures-api-463ae9e2`: 실제 PostgreSQL/Anvil/HTTP/개인 STOMP **1 PASS/0 FAIL/0 SKIP**. 신규 개설·추가담보·전체 종료와 canonical 정산, 익명401/USER의ADMIN403/타사용자404, null-loginId 내부JWT fixture, owner 개인 알림 및 타사용자 미수신, rollback 미발행을 확인했다. 두 병렬 TOP_UP 요청은 같은 operation 및 한 번의 담보 증가를 반환했다. 모든 실행에서 물리적 lock 대기를 계측한 것은 아니다. stale 요청은 HTTP409와 FUTURES_REQUEST_REJECTED를 두 번 확인했다.
- Python 새 회귀는 harness15 PASS, clock3 PASS. 최초 저장소 루트에서 clock unittest를 실행한 명령은 import 경로 오류1 ERROR였고, 기존 권장 deployment 작업 디렉터리에서 재실행하여3 PASS했다. Mock stage 집계는 실제 JVM 재시작 결과로 합산하지 않는다.
- 독립 `review_futures_pg_validation` 최초/1차 재검토: 발견된 필수 수정 없음. 실제 소스 및 신규 PG 경합/HTTP/JSON converter/409 assertion/fixture guard를 읽었으며 직접 테스트나 private evidence 접근은 하지 않았다.
- 별도 두 JVM `futures-restart`는 신규 child에서 실행 중이다. 최신 actual worker 재검증·최종 전체 회귀·안전 커밋은 후속이며, 선물 감사/자산 대사/실Chrome/AI가 완료됐다는 뜻이 아니다. 위 과거 BLOCKED 표는 당시 기록으로 보존하며 이 새 실행과 구분한다.
- 후속 `futures-restart-0b5f8978`는 **BLOCKED**, JVM 테스트0건 실행. 신규 PostgreSQL initdb의 post-bootstrap 초기화에서 `FATAL: could not write to file …: No space left on device`가 발생해90초 SQL readiness가 실패했다. 계약 배포/장부 초기화/prepare/recover JVM 이전 단계이며 제품 거래·시각 정책 결함으로 분류하지 않는다. 이 child의 PG/Anvil은 harness의 기존 소유권 검사 후 종료되었고 디렉터리/네트워크/컨테이너/로그는 삭제하지 않았다.
- 장애 후 Docker Server version은28.4.0 정상 응답했다. 저장공간 부족의 정확한 호스트/Docker backing-store 경계는 추가 확인 대상이며, Docker HTTP500과 동일 원인이라고 단정하지 않는다. 무단 prune/volume/image/network 삭제·Docker/WSL 재시작·기존 환경 정리는 하지 않았다. 디스크 공간을 쓰는 새 E2E/빌드와 추가 제품 개발은 중단하며, 보존 데이터에 영향 없는 공간 확보 방법을 확인하거나 사용자 승인을 받은 뒤 새 child에서 재개해야 한다. UI/marker 전체 단위의 안전 커밋은 필수 restart/최신 worker 검증이 남아 있어 아직 수행하지 않는다.
- 읽기 전용 저장공간 집계: Windows C 여유14,863,183,872bytes, Docker images87개/42.1GB(표시상reclaimable38.92GB), containers214개/3.293MB, local volumes15개/640.5MB, build cache223개/3.943GB. reclaimable 표시는 삭제 허가가 아니며 보존 이미지/중단 컨테이너를 제거하지 않았다. 호스트에 여유가 있다는 사실만으로 Docker 내부 파일시스템 여유를 보장하지 않는다. build cache 정리 또는 Desktop disk limit 조정은 사용자 승인/영향 확인 대상이다.

### 저장공간 장애 읽기 전용 정밀 조사 — 2026-10-19

- [원인 조사·대안·승인 경계 보고서](futures-storage-incident-investigation.md)를 추가했다. 코드/테스트/컨테이너/volume/image/환경 설정은 수정하지 않았고 신규 E2E·빌드·prune·Docker/WSL 재시작도 하지 않았다.
- 실패 파일은 PGDATA의 `base/4/2675`이며 PGDATA는 Windows runtime의 postgres 폴더 bind다. WSL에서 해당 경로는 C: NTFS 공유9p, 현재 약13.8GiB 가용이다. Docker data ext4는 현재 약1006.9GiB 중44.5GiB 사용/911.1GiB 가용, inode 약1% 사용이다. **Docker 가상 디스크 한도 초과 또는 캐시 정리가 확정 해결책이라는 판단은 근거가 없다.** 이전 캐시 제안은 후보이며 해결 보장이 아님을 명확히 한다.
- PG exit1/OOMKilled=false, initdb 자체 실패 정리 메시지와 현재 파일0개를 확인했다. 에이전트의 디렉터리 삭제가 아니다. 실패 당시 host 사용량 이력은 없어 순간 공간 소진·NTFS/할당량·공유 계층 원인은 아직 구분하지 못했다. fsutil 읽기 명령3개는 접근 거부, 접근 가능한 storage 이벤트/선택한 Docker backend log/WSL kernel 패턴에서 추가 원인 증거는 발견하지 못했다.
- 214개 컨테이너의 실제 참조 이미지13개와 보존 그룹을 대조했다. PostgreSQL/Foundry 각96개 컨테이너 공유, 컨테이너 writable layer 합계3.293MB다. 컨테이너 참조0인74개 이미지 중 최소13개 Phase A 도구는 session manifest가 필요하며 KRX/Google/playground 도구도 별도 보존이다. reclaimable38.92GB를 삭제 가능 용량으로 보고하지 않는다.
- 선택적 A안 후보는 비공유 immutable cache `lafz6m9snsfubgau95ekq0z81`/`58xgmj46y4254ts0nevv5rv9w` 명목512.9MB이나 미실행이다. host 공간 즉시 반환/현재 PG bind ENOSPC 해결은 보장하지 않는다. 우선 추천은 관리자 읽기 전용 NTFS 확인 및 별도 승인된 최대32MiB 신규 경계 probe이며, 필요할 때 새 fixture PG만 Linux volume으로 분리하는 대안이다. 기존 환경 이동·가상 디스크 증설·다른 프로젝트 이미지 삭제는 추천 실행하지 않는다.
- 현재는 조사/문서화 완료, 개발 재개는 **BLOCKED/승인 대기**다. 사용자 승인 없는 저장공간 변경은 하지 않는다. 이후 정상화되면 새fixture restart→최신auto→전체회귀/검토→로컬커밋→선물감사/대사→실Chrome→AI 순서를 유지한다.

### 승인된 선택 이미지 정리와 신규 restart 재개 — 2026-10-19

- [개별 삭제·용량·재개 결과](futures-image-cleanup-2026-10-19.md)에 전체 판단을 기록했다. 사용자 승인 조건에 맞는 초기 웹 build-only `tokenized-market-web:local`의 전체 ID `sha256:e281ca41aacac1260417597bd34b019b688857f3527c4f5d7cb8fa3a2516d010` **1개만** 비강제 개별 삭제했다. 컨테이너 참조0·runtime identity 참조0·프로젝트 소유/재생성 경로를 확인했다. KRX/Google/playground/Futures·과거 fixture 의존 도구·현재 release 기반·소유 불명/타 프로젝트 이미지는 모두 보존했다.
- images87→86, df42.1GB→42.1GB(표시 정밀도), Windows C15,708,020,736→15,707,893,760bytes. UniqueSize26.4MB 표시는 실제 host 회수량이 아니다. 컨테이너214/volume15/cache223은 정리 직후 그대로이며 cache/prune/서비스 재시작은 하지 않았다. 현재 공간 측정이나 이미지 삭제만으로 ENOSPC 해결을 선언하지 않는다.
- 같은 최신 productHash060d393d…의 **신규** `futures-restart-4f7696a5`: PG 초기화/readiness/clock/ownership 통과 후 prepare JVM1 PASS + recover 별도 JVM1 PASS, 각각FAIL/SKIP0. 최초 실패0b5f8978은 초기화/재사용하지 않았다.
- 동일 raw/hash/nonce 복구는 REVERTED canonical Receipt와 DB 상태 일치를 확인한 결과다. 채굴 시각과 report.validUntil을 직접 비교하지 않은 이 테스트에서는 정확한 revert 원인(TTL 포함)은 미확정이다. TOP_UP 성공 인수와 구분한다. nonce3/txHash9f058e0d… 유지, 이후 원래 마진콜60초 유예 조건으로 worker 자동청산 재개·LIQUIDATED·단일 journal·잠금0 검증 통과다. 현재 actual auto 재검증/새 전체 회귀는 진행 중이며 최종통합 PASS·실Chrome·선물 감사/대사·AI 완료는 아니다.
- 최신 actual `futures-auto-f71b7b7b`1 PASS/0 FAIL/0 SKIP. Long은call→deadline60초/청산63초, Short은call→deadline60초/청산74초로 새가격/정규Receipt/Event를 확인했다. 위험 tick 이후64,642ms/75,739ms, GRACE 관측 이후62,843ms/73,671ms. 세부 정제 시각은 위 정리 보고서와 private evidence에 보존했다.
- 새 웹119/Chrome desktop-mobile offline fixture/Foundry52/Python18 PASS. 전체 forge build는 Scenario.s.sol61 stack-too-deep FAIL을 새로 확인했고 별도 추적한다. 백엔드 전체 회귀는 새로 실행 중이다. 실제Chrome-to-chain/선물 감사·대사/AI 완료를 의미하지 않는다.
- `review_futures_pg_validation` 최종2차 검토에서 코드 추가 필수 결함은 없었고, restart의 status0을 TTL 원인으로 확정한 보고문구1건을 지적했다. 해당2개 문서 문구를 'canonical REVERTED 복구·정확한revert 원인 미확정'으로 바로 좁혔다. 제품/테스트 정책 변경은 없으며 검토자는 private/DB/테스트를 직접 실행하지 않았다. 문서의 원인 단정이 실제 assertion보다 강했던 점을 기록한다.
- 첫 새 전체 backend605중548 PASS/45 SKIP/12 FAIL. 세 loopback HTTP fixture 클래스의 native Bad address bind/listen 실패이며 금융/외부 provider 실행 전이다. 제품/테스트 소스 무수정의 새 JVM 분리 실행17 PASS, 최초 실패 증거를 보존하고 전체 recheck를 새 이름으로 실행 중이다. 원인을 영구 해결했다고 주장하지 않는다.
- staged22개는 이 기능의 명시 경로만이다. 최초 보안 검사에서 Java runtime 패키지를 데이터폴더로 오인한 후 검사 범위만 바로잡아 신규fixture secret33개 대조0/금지경로0/cached diff PASS를 확인했다. 기존 NXT/계획/사용자 변경 제외, 전체 검증 전 커밋 보류다.
- 최종 새 JVM 전체 recheck `futures-gate7-backend-full-recheck`: **605중560 PASS/45 SKIP/0 FAIL/0 ERROR**. 첫548/45/12 FAIL과 선택17 PASS는 각각 별도 실행으로 보존한다. 제품/테스트 소스 변경·실패 제외·환경 정책 완화 없이 새 JVM에서 전체 재실행한 결과이며 native socket 실패 원인을 영구 해결했다고 단정하지 않는다.
- Gate7의 UI/확정거절 marker/PG 경합/실HTTP·개인STOMP/실제두JVM/최신 auto 및 영향 회귀를 마쳐 위22개 명시 경로만 로컬 커밋한다. 코드에 대한 이전 UI/marker 독립검토와 새 실제 검증 단위 독립검토를 구분하며, 보고서 원인 과장은 지적대로 수정했다. melon-init9a9197b·기존사용자dirty5개/보존환경/모든이전증거 유지. 다음 필수는 선물 감사·자산 대사→제품Synthetic/실Chrome→read-only AI다. 전체 Futures/Phase D 완료 또는 원본 통합은 선언하지 않는다.
