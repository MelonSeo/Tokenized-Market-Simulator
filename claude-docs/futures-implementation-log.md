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
