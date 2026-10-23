# C4 구현 계획 — canonical 자산·Claims projection과 읽기 전용 감사

작성: 2026-10-23. **읽기 전용 소스 조사와 계획만 완료; C4 Java/DB/RPC 구현·실행 NOT_RUN.** C2 계약 132 PASS와 독립 재검토 완료는 선행 증거이며 C4의 실제 PostgreSQL/RPC PASS가 아니다. C3 및 자원 상태 확인 후 독립 검증 단위 순서로 진행한다. 현재 메모리 여유가 낮아 조사 중 Gradle/Docker/전체 회귀를 실행하지 않았다.

## 1. 실제 기존 구현에서 재사용하면 안 되는 가정

현재 C worktree 백엔드는 기존 root 선물 코드다. `futures/ledger/FuturesLedger`와 `futures-schema.sql`은 PostgreSQL wallet을 정본으로 house 초기자본·담보/지급 예약·journal을 갱신한다. 이는 C 자산 정본이 아니다.

- `FuturesReceiptVerifier.success`는 stored server raw와 legacy 계약의 **정확히 2개 이벤트**를 기대한다. C 직접 거래는 서버 raw가 없고 rules/Vault/controller의 이벤트가 추가되므로 그대로 재사용 불가다.
- `FuturesAuditService`의 fresh DB baseline/고정 DB 초기자본/DB담보 관찰과 LIMITATION은 B용이다. B `MATCH`나 기존 `Coverage.complete`를 C로 재표시하지 않는다.
- `FuturesSchema`는 `app.futures.enabled` 조건으로 ResourceDatabasePopulator 및 PG append-only trigger를 생성한다. **C는 별도 opt-in schema/config**를 추가하고 기존 futures 행·trigger·baseline은 건드리지 않는다.
- `SecurityConfig`는 현재 `/api/admin/futures/**`에 ADMIN을 강제한다. 향후 C admin은 그 하위 신규 namespace로 두거나 새로운 명시 ADMIN matcher를 추가해야 한다. 단순 authenticated fallback을 ADMIN 경계로 쓰지 않는다.
- `reserve/ReserveChainReader`는 explicit block·canonical 재확인·읽기 전용 인터페이스 개념을 참고할 수 있다. spot 재원 대사 자체를 C 대사로 확장했다고 주장하지 않는다.

## 2. 새 코드 위치와 최소 의존성

새 패키지는 `com.pricetrack.exchange.custody` 하위에 두어 기존 B types/DB 계정을 C로 혼용하지 않는다. 이름은 구현 시 일관되게 고정한다.

| 단계 | 제안 파일 | 경계 |
|---|---|---|
| C4a | `model/CCustodyModels.java`, `projection/CCustodyReducer.java`, `projection/CCustodyReducerTest.java` | 순수 BigInteger 모델·정렬된 검증 이벤트 환원; Spring/DB/RPC 없음 |
| C4b | `resources/c-custody-schema.sql`, `config/CCustodySchema.java`, `store/CCustodyProjectionStore.java`, PG 통합 테스트 | 신규 C evidence/canonical revision/projection/checkpoint 원자 commit 및 append-only |
| C4c | `chain/CDeploymentIdentity.java`, `CReadOnlyChainReader.java`, `CCanonicalReceiptVerifier.java`, `CCustodyLogDecoder.java`, `audit/CCustodyAuditService.java` | read-only RPC, 새 C ABI/domain/codeHash 및 전체 coverage |
| C4d | `api/CCustodyAuditController.java`, 제한된 read DTO/API 및 보안 회귀 | 기본 OFF, ADMIN 감사/읽기; 거래·예치·서명 endpoint 없음 |

현재 root의 web3j/Jackson/JdbcTemplate/트랜잭션·보안 의존성으로 시작한다. B-v2 worktree 전체 복사는 필요하지 않다. 참고할 최소 소스는 `futures/chain/FuturesReportCodec.java`, `FuturesReceiptVerifier.java`, `FuturesChainIdentity.java`, `reserve/ReserveChainReader.java`, `futures/ledger/FuturesSchema.java`다. 감사 worktree의 `FuturesAuditNonceFold` 및 `FuturesAuditNonceCancellationVerifier`는 envelope 재구성과 누락을 INCONCLUSIVE로 처리하는 아이디어만 참고한다. 미완료 취소 observer/harness를 그대로 C verified dependency로 가져오지 않는다. 필요한 hunk·원본 SHA·C 테스트를 별도 기록한다.

## 3. 자산 정본과 C 단위

- 모든 token/free/locked/house/Claim 숫자는 **18 decimals 최소단위 정수**이며 Java BigInteger, SQL `NUMERIC(78,0)` 또는 검증된 정수 문자열로 보존한다. double/JS Number를 원본으로 쓰지 않는다.
- 가격은 `priceE8` 정수, quantity는 기존 `q=floor(collateral*leverage*1e8/priceE8)` 정수다. quantity를 담보 최소단위나 UI mSEC 소수와 혼동하지 않는다.
- signed realized/unrealized는 int256 범위를 엄격히 decode한다. badDebt는 담보 초과 손실 기록일 뿐 별도 현금/사용자 추가 청구권이 아니다.
- owner ERC-20 wallet 잔고와 Vault free/locked는 별도다. Claim은 미지급 이익 부채이고 withdraw 가능한 현금에 더하지 않는다.
- `recognizedAssets=totalFree+totalLocked+house`, 실제 `token.balanceOf(Vault)>=recognizedAssets`. 초과분은 미귀속 직접 전송이며 정식 deposit/house 출자로 간주하지 않는다.
- `Claim.original=paid+remaining`, `totalUnpaid=Σremaining`, head/next와 FIFO 순서도 별도로 검증한다. `PositionSettled`의 이익 전체와 최초 claim amount(즉시 지급을 제외한 나머지)를 구분한다.

## 4. 실제 C 이벤트와 한 tx 단위 환원

현재 계약의 실제 이벤트를 직접 읽었다. token에는 표준 Transfer/Approval, Vault에는 Deposited/Withdrawn/HouseFunded/CollateralLocked/PositionSettled/ProfitClaimCreated/ProfitClaimPaid, controller에는 CUserIntentExecuted/CRiskExecuted/CNonceCancelled/NewPositionsPauseChanged, rules에는 OperationApplied/FuturesReportConsumed가 있다.

| 사건 | canonical tx에서 필요한 대응 | projection 효과 |
|---|---|---|
| token approve | 정확 token/owner/spender/value | allowance observation; 예치/거래 성공 아님 |
| deposit | token owner→Vault Transfer와 Deposited owner/amount, calldata/tx.from | owner wallet 감소·free 증가; house 변화 없음 |
| withdraw | Withdrawn owner/amount와 token Vault→같은 owner Transfer | free 감소·owner wallet 증가 |
| fundHouse | contributor→Vault Transfer와 HouseFunded | house 증가; LP/환급권 생성 없음 |
| OPEN | rules 2개 → CollateralLocked → CUserIntentExecuted | position/owner/nonce/version·free→locked |
| TOP_UP | rules 2개 → CollateralLocked(delta) → CUserIntentExecuted | exact entry/q 보존·담보 증액·위험회복 |
| CLOSE | rules 2개 → 필요 시 ProfitClaimCreated → PositionSettled → CUserIntentExecuted | terminal/locked0·원금 free·손실 또는 paidProfit/Claims |
| START/CLEAR | rules 2개 → CRiskExecuted, Vault 이동 없음 | risk/version/callEpoch/deadline만 변경 |
| LIQUIDATE | rules 2개 → PositionSettled → CRiskExecuted | 60초+새 Report 조건·terminal/자산 귀속 |
| processNextClaim | ProfitClaimPaid head 1개; token Transfer 없음 | house→고정 owner free, paid/remaining/head 갱신 |
| nonce cancel | CNonceCancelled, 직접 owner tx | nonce+1; 자산/position 변경 없음 |

로그 수를 모든 action에 하나로 고정하지 않는다. 실제 ABI topic/길이/address/순서를 action별 명세로 확인하고 외부 로그를 임의로 무시해 coverage를 늘리지 않는다. status0 canonical reverted tx는 상태 변화가 없고 로그가 없어야 하지만 해당 tx도 reverse coverage에 포함한다. processNextClaim의 무변경 성공은 지급 이벤트가 없으므로 calldata와 status로 구분하며 지급 성공 건수에 더하지 않는다.

하나의 tx에 대해 모든 필수 로그·서명·calldata 연결을 먼저 검증한 후 reducer가 원자적으로 적용한다. 부분 로그까지 free 증가를 먼저 commit하지 않는다. 같은 block의 여러 operation은 **transactionIndex/logIndex 실제 순서**로 환원한다. parent block state가 각 tx 직전 상태라는 가정을 하지 않는다.

## 5. 직접 owner와 relay의 검증

CUserIntentExecuted의 direct flag만 믿지 않는다. 실제 tx.to/controller 및 selector, 전체 calldata Intent/Report, Oracle 서명, intentDigest/reportDigest/operationId/positionId/owner/action/nonce를 대조한다.

- direct: tx.from=owner, userSignature 없음이 정상. C domain과 명시 조건/nonce/version/range/deadline은 검증한다. 서버 stored raw가 없다는 이유로 실패/미상 처리하지 않는다.
- relay: tx.from=고정 relayer, userSignature로 owner 복구, Intent.relayer=고정 relayer. 실행 시 Report는 실제 최종 calldata로 검증한다.
- risk: tx.from=지정 keeper(relayer), action/rules 조건 확인. user Intent가 없다는 것이 무단 정상 CLOSE 허용을 뜻하지 않는다.
- cancel: owner 직접 호출·expected nonce·이벤트·canonical 성공과 실패/경합을 C 기준으로 다시 검증한다. B 취소 후보의 PASS를 승계하지 않는다.

서버 실행 revision은 C4의 chain projection 필수 FK가 아니다. 외부 direct tx는 revision이 존재하지 않을 수 있다. relay 실행 기록이 있다면 최종 활성 revision/reportDigest/raw/hash/nonce와 별도 연결해 검증한다. chain이 성공했지만 DB 실행 기록이 누락된 경우 자산 사실을 지우지 않고 projection/실행 provenance의 검증 범위를 분리한다.

## 6. additive 저장 모델과 재시작/reorg

제안 테이블은 `c_deployments`, `c_chain_observations`, `c_canonical_epochs`, `c_projection_checkpoints`, `c_owner_balances`, `c_positions`, `c_claims`, `c_vault_totals`, `c_audit_runs`이다. 기존 users/선물 position에 자산 FK를 강제하지 않는다. owner는 주소 소유자이며 JWT userId 연결은 후속 C6 조회 권한 문제다.

- deployment identity: chainId/genesis hash/token/Vault/controller/rules/각 codeHash·deployment blockHash·policy/domain/role 주소를 고정한다. 체인 재생성을 같은 chainId만 보고 같은 환경으로 취급하지 않는다.
- immutable observation key: `(deploymentId,blockHash,txHash,logIndex)`와 event address/topic/data. tx-level evidence는 별도 logIndex sentinel/유형을 명시한다. 같은 txHash라도 reorg 후 다른 blockHash는 관측 이력으로 추가할 수 있다.
- canonical epoch/selection은 append-only 결정 이력으로 유지한다. 과거 removed/orphan 관측을 삭제·재작성하지 않고 새 epoch가 선택한 canonical block 연결을 기록한다.
- owner/position/Claim/totals는 파생 projection이므로 epoch에 종속된 새 snapshot 또는 transactionally replace 가능한 materialization이다. **불변 observation과 가변 projection을 구분**한다. append-only trigger로 재구성까지 막지 않는다.
- batch 단위 tx: deployment/커서 lock → 마지막 canonical parent 확인 → 정렬된 새 observations insert(충돌 시 payload 동일 확인) → reducer 결과/다음 checkpoint commit. DB 실패는 전체 rollback. chain transaction을 재전송하지 않는다.
- 재시작은 checkpoint hash를 RPC로 재확인하고 같은 batch를 idempotent 재적용한다. commit 응답 유실에도 observation key/checkpoint로 이중 귀속을 막는다.
- reorg는 공통 조상까지 bounded 탐색 후 새 canonical epoch로 보존 snapshot에서 replay한다. 한도를 넘거나 historical RPC가 부족하면 INCONCLUSIVE/PAUSED이며 임의 baseline 재설정·현재 잔고 덮어쓰기로 MATCH를 만들지 않는다.

## 7. 새 C 감사와 coverage

감사는 C deployment 시작부터 명시적 cut block까지의 관측이 완전해야 전체 MATCH 후보가 된다. deployment tx의 고정 공급 mint와 treasury 분배/house 출자도 해당 자산 범위에 들어간다. 과거 genesis까지 무제한 탐색하지 말고 실제 deployment receipt/block identity로 시작한다.

필수 cut reads는 chainId·codeHash/getter 연결, token totalSupply/wallet balances/Vault 실제 token, free/locked/house/totals, position lock 및 rules position, claims original/paid/remaining/head/next/totalUnpaid, nonces/OPEN pause다. 모두 같은 explicit cut block에서 수행하고 전후 canonical hash를 재확인한다. `LATEST` fallback으로 과거 값을 0으로 바꾸지 않는다.

coverage는 `identityVerified`, `from/to block hash`, `scanned/verified transactions`, `scanned/verified logs`, `owner/position/claim counts`, `supported selectors`, `historical available`, `canonical stable`, `complete`를 C 전용 DTO에 명시한다. block/RPC/tx/claim/owner 수·전체 deadline을 제한하고 cap에 걸리면 complete=false다. 큰 queue를 전부 무제한 읽지 않으며 충분한 범위가 없으면 INCONCLUSIVE다. 정상 FIFO 지급 호출은 계약상 O(1)이어도 감사 전체 claim 열거는 별도 한도가 필요하다.

- MATCH: 완전한 검증 coverage와 현금/부채/position/approval/projection 대응 모두 일치.
- MISMATCH: canonical/identity가 확인된 범위에서 구체적인 amount/owner/event/digest/projection 모순. 어떤 범위가 완전한지 별도 표시하고 부족 evidence를 확정 오류로 꾸미지 않는다.
- INCONCLUSIVE: identity 미확정, RPC/과거자료 누락, moving/reorg cut, unsupported tx, 불완전 coverage, 아직 미확정 실행. 부분 Receipt 일치로 전체 MATCH/complete를 올리지 않는다.

미귀속 direct token transfer는 실제 자산 사실이며 그 금액이 정확히 분리되면 “미귀속 차이 존재”를 보고할 수 있다. 금융 귀속과 provenance를 설명할 근거가 부족하면 INCONCLUSIVE로 남긴다. claim unpaid>0 자체는 정책상 정상일 수 있으나 지급능력 부족을 숨기지 않는다. 기록 MATCH와 지급 완료/미래 상환 보증은 별개다.

## 8. 기본 OFF API와 첫 안전 구현 단위

`app.c-custody.enabled=false`를 독립 기본값으로 두며 C schema/reader/scheduler/API가 조건 충족 전 생성되지 않게 한다. C 서비스에 Wallet/FuturesLedger 쓰기·transaction sender·private key를 주입하지 않는다. reader 인터페이스에는 send/sign/mine/fund/repair 메서드가 없다.

후보 admin route는 `/api/admin/futures/c-custody/audits`로 기존 ADMIN matcher를 재사용하되 MockMvc로 USER403/anonymous401 및 enabled=false route 부재를 검증한다. POST 감사는 관측 기록만 저장하며 금융 상태를 변경하지 않는다. 일반 USER의 임의 owner read API와 wallet linking은 C6 별도 범위다. DTO는 decimal 정수 문자열/공개 주소와 안전한 reason만 노출하고 raw tx/서명/JWT/개인키를 반환하지 않는다.

**다음 첫 구현은 C4a 순수 canonical reducer다.** C3 테스트·독립 검토 완료 이후 신규 model/reducer/test 3개 중심으로 시작한다. DB/RPC/새 환경을 필요로 하지 않아 메모리 부담이 작고 canonical 복구 의미를 먼저 고정할 수 있다. 입력은 identity가 붙은 정렬된 tx bundle 및 typed 검증 event이며, reducer의 한계는 명시한다: 자체 RPC canonicality/서명 검증 완료로 주장하지 않는다.

최소 테스트:

1. 두 owner deposit/lock/top-up/close·손실/paidProfit/Claims·부분 지급/fundHouse/withdraw; 자산·부채·head invariants.
2. 동일 observation 재적용 no-op, 같은 key 다른 payload 거부, tx 전체 실패 시 원본 projection 불변, event 누락/잘못된 순서/owner mismatch 거부.
3. direct/relay/risk/nonce cancellation typed bundle 구분, 무서명 relay·지원하지 않는 action은 verified input으로 수락하지 않음.
4. 동일 block 복수 tx 순서와 FIFO, 신규 미지급 중 OPEN 금지, head 건너뛰기·중복 지급·owner 변경 거부.
5. 재시작 checkpoint replay, orphan epoch와 새 canonical replay 모델, 기존 관측 이력 보존.
6. 18 decimals 큰 정수·signed rounding·1wei, 미귀속 송금/출자 분리, Claim을 free로 합산하지 않음.

C4b에서 실제 PG UNIQUE/FK/append-only/동시 cursor·rollback·commit 응답 유실·replay를 검증하고 H2와 구분한다. C4c는 fake RPC 실패 주입 뒤 새 Linux 내부망 C5에서 실제 ABI/canonical receipts/cut snapshot·두 owner·60초 및 DB 재시작을 인수한다. C4d는 권한·기본 OFF·민감정보·read-only 금융 경계를 독립 검토한다. 기존 서비스 또는 보호 DB/Anvil을 사용하지 않는다.
