# B-v2 Spring Boot 및 additive DB 통합

작성 2026-10-22. `feat/dapp-user-authorization`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523` + 기존 B-v1/v2 미커밋 + 이번 Java 변경. [계약 Gate](dapp-b-v2-contract-implementation-2026-10-22.md), [B-v1 서버 기록](dapp-user-authorization-stage2-2026-10-22.md), [시간 조건 분석](dapp-user-authorization-stage3-preflight-2026-10-22.md)을 보존한다. **실제 PG/Anvil/브라우저 인수 완료가 아니다.**

## 구현 경계

이번에는 Java·additive SQL·Java 테스트·문서만 변경했다. 계약/Foundry 테스트/기존 runtime/평가 ledger/STOP/claim은 수정하지 않는다. DB·Anvil 기동/배포·새 거래 fixture·웹 UI·유료 호출·commit/merge/push 없음. 실제 PostgreSQL migration/잠금·canonical RPC는 NOT_RUN이다.

### 변경 묶음

- 신규: `futures/authorization/UserIntentV2Codec`, `AuthorizedV2Store`, `AuthorizedV2Service`, `AuthorizedV2IntentStore`, `AuthorizedV2Config`, `AuthorizedV2Controller`, `AuthorizedWalletConfig`.
- 공통 재사용 확장: `FuturesChainIdentity`, `UserIntentCodec.Authorization`, 공통 wallet draft admission, v1 Controller/Service/typedData의 명시 버전 거부, v1 Config의 wallet store bean 분리.
- worker/전송: `FuturesLedger` 위험 후보 선택, `FuturesWorker`, `FuturesRpcGateway`, `FuturesExecution`(최종 nonce RPC 이후 시세 재검사), `FuturesReceiptVerifier`. 기존 TransactionStore/Sender/Reconciler는 확장된 authorization의 코덱 분기로 재사용한다.
- schema: `futures-schema.sql`, `FuturesSchema` append-only 보호 목록. 기존 테이블의 positional INSERT를 깨는 column 추가 대신 v2 supplemental 테이블을 사용한다.
- 신규 테스트: `chain/UserIntentV2CodecTest`, `AuthorizedV2ReceiptTest`, `AuthorizedV2RpcTest`; `ledger/AuthorizedV2IntegrationTest`; `authorization/AuthorizedV2BoundaryTest`, `AuthorizedV2HttpTest`.

## 버전과 기본 OFF

`FuturesChainIdentity.authorizationVersion`은 LEGACY / 1 / 2만 허용한다. 기존 JSON의 누락값은 rules가 없으면 LEGACY, 있으면 1로 복원한다. Java Authorization은 기존4인자 생성/JSON을 유지하며 nullable `intentV2`를 추가한다. v1 Approval과 v2 Intent를 함께 수용하지 않고 각 version의 domain·ABI만 사용한다.

새 조건 `app.futures.enabled=true`, `app.futures.runtime-enabled=true`, **`app.futures.user-authorization-v2.enabled=true`** 세 가지가 모두 있어야 v2 API/runtime이 생성된다. 기본은 OFF. 기존 공통 futures runtime 설정은 계속 필요하며 기존 엔진 설정을 v2로 바꿔치기하지 않는다.

v2 전용 prefix `app.futures.user-authorization-v2.`: `chain-id`, `controller-address`, `rules-address`, `controller-code-hash`, `rules-code-hash`, `rpc-url`, `executor-private-key`, `signer-private-key`, `wallet-origin`, 선택 `confirmations`. 서버 executor/가격 signer는 legacy/B-v1/spot 키와 분리해 nonce 공간과 역할을 보존한다. 사용자 개인키를 받거나 생성하지 않는다. 이번에는 실제 값 주입/서비스 시작을 하지 않았다.

RPC 배포 검증은 chainId·두 코드 hash, controller.executor/rules, rules.executor=controller, rules signer/symbol/policy 및 v2 MAX_USER_TTL=120을 확인한다. codeHash 자체의 신뢰는 배포 manifest에서 확보해야 한다. 실제 계약 identity 검증은 후속 Gate다.

## API / 저장 모델

JWT의 기존 내부 users.id를 사용하며 body userId로 계정 귀속을 정하지 않는다. 새 base `/api/futures/authorized-v2`:

| API | 역할 |
|---|---|
| POST `/wallet/challenges`, `/wallet/link` | v1과 같은 Origin-bound EOA 소유 증명 및 unique user/wallet 모델 재사용 |
| GET `/wallet` | 본인 연결 주소 |
| GET `/nonce` | 현재 onchain nonce 읽기; `OBSERVED_LATEST_NOT_CANCELLATION_RECEIPT`, 취소 확정 판정 아님 |
| POST `/drafts` | 명시적 가격 E8/수량 wei 범위, side/leverage/margin wei, action/pid/version, ttlSeconds(1~120)를 받아 사용자 typed data만 생성 |
| POST `/drafts/{id}/submit` | Idempotency-Key와 EOA 서명; 그 뒤 실제 가격 선택/Report 생성/원자적 준비, 202 operation |
| GET `/positions/{id}` | 본인 + 현재 v2 배포 identity를 확인한 version/owner/controller/rules 및 기존 안전한 position view |

금액은 Java BigInteger, typed data에는 **decimal 문자열**을 사용한다. v1 API의 marginAmount decimal 계약을 v2의 integer wei 필드와 혼동하지 않는다. 가격·수량 min/max와 TTL은 필수이며 자동 무제한 기본값이 없다. 실제 UI 사용자가 확인한 범위를 전달하는 검증은 웹 미구현으로 NOT_RUN이다.

추가 테이블:

- `futures_authorized_v2_drafts`: 사용자 Intent, 서버가 정한 UUID/계약 identity/원 요청/만료/state; **시장 관측/가격 Report/서버 가격 서명 없음**.
- `futures_authorized_v2_positions`, `futures_authorized_v2_operations`: controller에 따른 version별 routing. PG에서는 append-only trigger 대상에 추가. 기존 공통 authorized position/op immutable metadata에는 v2 chain/owner/사용자 Intent·서명·실제 Report·가격 서명을 함께 저장한다.
- v1/v2 지갑은 같은 `futures_wallet_links.inflight_draft`와 user guard/wallet lock을 공유한다. 현재 draft가 어느 버전인지 확인하고, ACCEPTED 미확정/REVIEW_REQUIRED 또는 미만료 draft가 있으면 새 승인 nonce 사용을 차단한다.

서명 원문은 private DB evidence이며 DTO/toString에는 포함하지 않는다. 암호화-at-rest/DB ACL/backup 보안·실제 PG trigger를 검증 완료했다고 주장하지 않는다.

## 승인부터 정산까지

1. Draft는 user/pid/op/version/epoch/owner/nonce와 경제 조건을 고정한다. OPEN은 담보·방향·레버리지 exact + 명시 범위, TOP_UP/CLOSE는 기존 q/entry 및 추가 담보 exact. 가격을 capture하거나 담보 예약하지 않는다.
2. Submit은 owner/서명/domain/120초 window를 먼저 검증하고 배포/EOA/현재 nonce를 조회한다. 그 **후에** currentForTrading의 적격 시장 tick을 선택한다. draft 발급 시점의 낡은 mark는 없다.
3. OPEN은 실제 가격으로 기존 `FuturesMath.quantity`를 계산한다. 사용자 price/q 범위를 확인하고 실제 Report와 가격 서명을 생성한다. TOP_UP/CLOSE의 q/entry는 기존 포지션과 일치해야 한다.
4. Store의 짧은 단일 트랜잭션에서 user/wallet/draft를 잠그고 재검증 → 기존 ledger prepare로 담보/house 예약 → 실제 Report가 준비 operation으로부터 재구성된 값과 exact 일치하는지 검사 → 불변 metadata/routing/market evidence와 ACCEPTED를 commit한다. 모순이면 전부 rollback. RPC는 이 DB 트랜잭션 밖이다.
5. Worker는 v2 controller에 귀속된 PREPARED/위험 후보/미확정 raw만 선택한다. legacy/v1 selector는 v2를 제외한다. 사용자 action은 고정된 실제 Report/서버 서명을 재사용하고 자동 action만 기존 서버 Report 생성 경로를 쓴다. PREPARED 자동 재가격/재시도는 없다.
6. 실제 선택한 실행 mark에 기존5초/미래2초/가격동일/provider/generation gate를 적용한다. estimate 후 검사하고 최종 EOA/nonce RPC **후에도** 다시 검사한다. 사용자 deadline도 raw 서명 전 재확인한다. 실패 시 UNSIGNED 준비만 REJECTED/예약 해제한다.
7. Sender의 raw 서명→REQUIRES_NEW SIGNED durable commit→전송 순서, 동일 raw/hash/nonce 복구를 유지한다. SIGNED 이후의 nonce/시세/사용자 만료로 임의 재가격·자동재승인·잠금 해제를 하지 않는다.
8. Receipt는 full calldata/두 서명/owner/chain/controller/rules/version을 검증한다. rules Applied/Consumed 다음 controller `UserIntentExecutedV2`의 intentHash/reportDigest/owner/pid/op/action/nonce를 모두 대조한다. 채굴 블록 기준120초 window/30초 Report, explicit-block rules position/controller owner, canonical blockHash 재확인을 거쳐 기존 멱등 ledger 정산한다. 모순은 REVIEW_REQUIRED, RPC 부재는 미확정/예약 보존이다.

실제 선택한 mark는 사용자 서명 뒤 새로운 generation일 수 있다. 그러나 **그 실행 mark가 고정된 이후** generation/provider/가격이 바뀌면 전송을 차단한다. 5초를 사용자 승인 대기에 적용하지 않으며, 5초 시장 gate를 온체인 채굴의 검사라고 표현하지 않는다.

## 취소, 감사와 신뢰 한계

- cancelNonce는 사용자 EOA가 직접 보낸다. backend nonce 조회는 관측일 뿐 취소 Receipt 검증 API가 아니다. PREPARED에 다른 nonce가 관측되면 새 raw를 만들지 않고 거절한다. SIGNED이면 nonce 변경만으로 실행 성공/취소 성공/실패를 구분할 수 없으므로 canonical Receipt로만 정산한다.
- 성공 Receipt는 실행으로 nonce가 이미 증가했더라도 수락한다. canonical reverted Receipt는 기존 rollback 정산, 증거가 없으면 잠금 유지다. 테스트의 nonce 변경은 Mock이며 실제 취소 트랜잭션 실행 증거가 아니다. revert 원인을 TTL/취소라고 임의 확정하지 않는다.
- 자동 START_CALL/CLEAR_CALL/LIQUIDATE는 사용자 Intent·nonce 취소와 독립적이다. 기존 60초 block-time 유예/가격/위험 규칙과 장부를 재사용한다.
- **정산용 v2 Receipt 검증은 구현했지만 v2 전체 감사 coverage는 미구현**이다. 기존 snapshot은 공통 authorized position을 감지해 `AUTHORIZED_CONTRACT_COVERAGE_REQUIRED`/INCONCLUSIVE로 차단하므로 기존 MATCH로 대체하지 않는다. 혼합 환경에서 기존 자산 대사/전체 감사도 이 차단 영향을 받는다.
- 가격 signer/시장 원천을 신뢰하며, relayer는 범위 안의 불리한 실행가격 선택/지연/거부 가능하다. 실제 담보는 DB 모의장부이고 C안 ERC20/Vault/지급능력 보증은 아니다.

## 실제 검증과 남은 Gate

Solidity `testCompositionAndNewUserDomain`을 수정 없이 새 trace 실행하여 digest `0x28e15d377181266709dd6ad20214513b715945396ff4a99a656a4341bbbf26a2`를 확인했다. Java golden과 독립 Web3j StructuredDataEncoder가 이를 대조한다. 실제 배포/RPC가 아니다.

최초 테스트 compile의 괄호1개 구문 오류를 수정했다(제품 compile은 성공). 초기 v2 선택 **16 PASS / 0 SKIP / 0 FAIL**, 로그 `v2-20261022T000623184374.log`. 그 뒤 최종 nonce RPC 이후 시세 재검사, 명시 수량 범위·기본OFF/JWT귀속·v2 RPC identity 테스트를 추가해 선물 package **145 PASS / 11 SKIP / 0 FAIL**을 확인했다(`futures-20261022T003017349835.log`). 이후 HTTP/JWT와 독립 만료 경계5개를 추가했다. 최종 `testClasses` BUILD SUCCESSFUL(`classpath-20261022T010029633532.log`), 최종 실행/독립 검토는 아래에 누적한다. 기존 과거 PASS를 최신 결과로 대체하지 않는다.

검증 구분: 직접 JUnit은 프로젝트와 동일 JDK/classpath를 쓰지만 정상 Gradle `test` 전체 회귀 PASS가 아니다. 이번 정상 Gradle test/백엔드 전체/실PG/Anvil/웹은 **NOT_RUN**. 과거 단계의 native socket BLOCKED 이력을 보존하며 이번에 근본 원인이 해결됐다고 판단하지 않는다. 실제 JWT 필터/MockMvc도 실제 브라우저·외부 Google 인증 검증이 아니다.

### 최종 실행 및 독립 검토

- 제품 hash: `64d766d57bffa7938530d8aeaeef2a4f136c66f78773a383d7bcc0a19beedf1c` (HEAD 위 미커밋 Java/SQL 포함, 테스트·문서 제외). 기존 계약 Gate hash/manifest를 덮어쓰지 않는다.
- 최종 `testClasses` BUILD SUCCESSFUL 뒤 직접 JUnit 선물 패키지 **150 PASS / 11 SKIP / 0 FAIL** (`futures-20261022T010720790204.log`, 161 found/150 started/0 aborted). 이번 신규 6클래스의 26개 테스트를 포함한다. 11개 환경 opt-in SKIP을 실제 DB/RPC PASS로 바꾸지 않는다.
- 신규 테스트는 두 domain/digest golden, 3event/calldata 변조, owner/nonce/TTL/명시 범위, 10초 사용자 승인 지연 뒤 새 Report, atomic rollback/동시 submit, 최종 nonce RPC 중 시세 변경, SIGNED 보존/동일 raw, Mock canonical 성공·revert, JWT/Google-only principal 및 기본 OFF를 확인한다. 자동 청산의 60초는 제어 Clock/Mock이지 실제 시간 Anvil 검증이 아니다.
- 독립 `review_dapp_v2_backend`: **발견된 필수 수정 없음**. 소스·신규 테스트·SQL·문서를 직접 검토하고 별도 선택 실행 **20 PASS / 0 SKIP / 0 FAIL** (`v2-20261022T010918077428.log`: codec2/receipt3/H2 integration15). RPC/HTTP/boundary 테스트는 검토자가 재실행한 것으로 표현하지 않는다. 검토는 실제 PG 잠금/trigger·RPC/취소 경합·별도 JVM·동시 runtime 기동을 대체하지 않는다.
- 보호 기준 156파일 SHA-256 변경0 및 기존 계약/Foundry 테스트 6파일 hash 불변을 확인했다. 기존 원본 사용자 변경과 UI/AI worktree는 보존하며 이 작업의 로컬 commit도 만들지 않았다.

다음 **신규 격리 PG/Anvil Gate** 필요조건:

1. 신규 DB/체인/서버 키 및 v2 controller+전용rules/codeHash를 새 identity로 준비. 기존 v1/Google/KRX/AI baseline/manifest를 수정하거나 DB/볼륨을 재사용하지 않는다.
2. additive schema를 기존 v1 행이 있는 복제 테스트 DB에도 적용해 행 보존, UNIQUE/FK/append-only trigger, 실제 PG 동시 submit·wallet 경합·rollback 검증.
3. 독립 사용자 EOA 서명(제품에서 키 미보유), 10초 이상 지연 후 새 Report OPEN, 가격·수량 경계, TOP_UP/CLOSE, TTL/nonce/취소 경합, 실제 canonical3event/DB 정산.
4. 별도 JVM에서 SIGNED 원문 선저장/응답 유실/동일 raw/hash/nonce 복구, nonce 증가 후 성공·실패·미확정 상태 구분, REVIEW_REQUIRED 잠금 보존.
5. v1/v2/legacy 실제 동시 runtime 분리, worker 자동 마진콜/실제60초/양방향 청산, 기존 금융/자산·house journal 보존. version별 전체 감사는 구현 전 INCONCLUSIVE 유지.
6. 실제 JWT/REST/STOMP 권한과 v2 지갑 UI는 후속 승인 범위에서 검증. 이번 H2/Mock/직접 JUnit은 이를 대신하지 않는다.

후속 실제 PG/Anvil 실행은 [별도 Gate 기록](dapp-b-v2-postgres-anvil-gate-2026-10-22.md)에 누적했다. 위 NOT_RUN은 이 구현 단계 당시 이력이며, 후속 core 개별 PASS/보완2JVM PASS 및 전체PARTIAL을 구분한다. 기존 구현 단계 결과를 소급 변경하지 않는다.
