# B-v2 SIGNED 이전 단일 재가격 구현·검증

> 2026-10-22. [승인 전 조사](dapp-b-v2-execution-boundary-followup-2026-10-22.md)의 후속. 사용자가 B-v2 사용자 action의 unsigned 최대1회 재가격을 승인했다. 기존 성공 OPEN/실패 TOP_UP/STOP fixture는 보존했다. 실제 Gate 및 독립 검토 결과는 아래에 누적한다.

## 정책과 불변식

- 적용 대상은 **새 B-v2 사용자 OPEN/TOP_UP/CLOSE**이며 기존 B-v2 opt-in runtime 안에서만 연결한다. legacy/B-v1 및 자동 START_CALL/CLEAR_CALL/LIQUIDATE는 재가격하지 않는다.
- 최초 고정가격과 raw 직전 시장가격이 달라 `PRICE_CHANGED`인 경우만 기회가 있다. 사용자 Intent/서명/owner/nonce/operationId/positionId/action/담보/방향/레버리지/범위/domain/정책은 유지한다.
- revision은0 또는1만 존재한다. 첫 변경을 원자적으로 확정한 뒤 두 번째 변경은 PRICE_CHANGED 거절이다. durable head와 PK/CHECK 제약이 재시작/새 Worker에 의한 횟수 초기화를 막는다.
- 5초 신선도/같은 provider·generation,30초 Report TTL, 사용자 deadline/EOA/nonce 및 승인 가격·수량 검사를 유지한다. TTL 연장, 자동 사용자 서명, 범위 확대, 시장 검사 삭제는 없다.
- 배포 전 이미 존재하던 B-v2 operation에는 revision/head를 소급 생성하지 않는다. 기존 Report로 복구하되 재가격은 하지 않는다.
- SIGNED 행이 존재하면 refresh 금지. raw/hash/relayer nonce는 저장 이후 바꾸지 않는다. uncertain COMMIT/전송과 REVIEW_REQUIRED의 잠금 보존 정책을 유지한다.

## 변경 파일과 책임

| 파일 | 변경 |
|---|---|
| `futures-schema.sql`, `FuturesSchema` | additive revisions/heads 테이블, revision 이력 UPDATE/DELETE/TRUNCATE 방지 |
| `AuthorizedV2Store` | 신규 사용자 승인 accept 트랜잭션에 revision0/head0 추가 |
| `AuthorizedV2RevisionStore` (신규) | 승인·Report·owner 검증, ledger revision/이력 append/head CAS를 한 트랜잭션으로 commit |
| `AuthorizedV2Repricing` (신규) | DB 잠금 밖에서 현재 authority·시장 확인, 범위 내 수량/Report 재생성·서명, 원자적 저장 호출 |
| `AuthorizedV2Config` | B-v2 runtime에만 revision store/재가격 callback 연결 |
| `AuthorizedV2IntentStore` | 활성 revision의 시장 evidence 선택, 기존/자동 경로 fallback |
| `FuturesExecution` | PRICE_CHANGED에 한해 최대1회 callback 후 재실행. stale worker가 새 revision을 거절하지 않도록 조건부 rejection |
| `FuturesLedger` | user/position/op/head 잠금, unsigned 재계산/예약 차액/REPRICE journal, 예상 Report에 한정한 unsigned 거절 |
| `FuturesTransactionStore` | 활성 Report 조회·동등성 검증, SIGNED 잠금 획득 이후 최신 operation/position 재조회 |
| `FuturesTransactionSender` | raw 서명 전 활성 Report 검사, recovery에서도 활성 증거 대조 |
| `FuturesReconciler` | 실제 최종 revision과 SIGNED 저장 Report를 canonical 검증 전에 대조 |
| `AuthorizedV2RepricingTest` (신규) | H2/Mock·로컬 서명 테스트15개, SIGNED 양방향 fence/증거 손상/부족 pool 포함 |
| `AuthorizedV2PostgresAnvilE2ETest` | reprice 전용 opt-in Gate/두 번째 JVM 모드 |
| private `run-bv2-gate.py` | 신규 fixture reprice/reprice-recover 선택. 기존 정상 모드 보존 |

계약/ABI/EIP-712 typed data/금융 산식/웹 UI는 변경하지 않았다. 기존 큰 미커밋 B-v1/v2 파일 전체를 이번 작업으로 새로 만든 것으로 취급하지 않는다.

## DB와 금융 원자성

`futures_v2_execution_revisions`는 `(operation_id,revision)` PK, revision IN(0,1), full Report/가격서명/가격/관측시각/provider/generation을 보존한다. `heads`는 operation PK 및 composite FK로 존재하는 revision만 선택한다. 기존 `futures_authorized_operations`와 최초 market evidence를 덮어쓰지 않는다. PostgreSQL append-only trigger는 새 revisions에도 적용한다.

새로운 Report 서명과 RPC는 DB 잠금 밖에서 수행한다. `replaceUnsigned`의 단일 transaction 안에서 ledger가 user → position → operation → head 잠금을 잡고 PREPARED/pending/기대version·epoch/head0/tx행없음을 확인한다. 이전 Report가 현재 operation/position과 정확히 맞아야 한다.

- OPEN: 같은 C/L로 새 가격의 quantity를 기존 FuturesMath로 계산하고 OPEN_PENDING q/entry를 갱신한다. 기존 C 잠금은 유지하며 이중 예약하지 않는다.
- TOP_UP: 기존 q/entry와 승인 collateralBefore/After 유지. 추가 담보를 이중 잠그지 않는다. 최종 위험 회복은 기존 Receipt/settle 규칙으로 판단한다.
- CLOSE: 같은 q/entry에서 realized/badDebt/wallet 범위와 `reservedHouse=max(realized,0)` 재계산. 기존 예약을 돌려 사용할 수 있는 house 가용액을 검증한 뒤 차액만 조정한다. 부족하면 전체 rollback한다.
- 원래 Report/Intent와 새 Report의 조건 일치는 codec과 `FuturesReport.from(updated ledger)`가 이중 검증한다. REPRICE journal은 소유 잔고/house 자본을 바꾸지 않는0 delta 증거이며 예약 전후는 각 full Report로 계산 가능하다. 기존 journal 삭제/수정 없음.
- 같은 transaction에서 새 revision append와 head0→1 CAS를 수행한다. 시간/현재 mark를 재검사하고 실패 시 operation/position/house/journal/revision/head 전부 rollback한다.

## 동시성 및 SIGNED fence

재가격 전송은 이전 raw를 재사용하는 것이 아니라 새 활성 Report/calldata에 대해 새 estimate를 한 번 수행한다. 재가격 뒤에도 exact-price 검사를 유지하므로 다시 바뀌면 거절한다.

두 Worker가 동시에 후보를 계산할 수는 있지만, DB operation/head 잠금과 head0 조건 때문에 하나만 revision1을 확정한다. 패배자는 REVISION_CONFLICT로 종료하고 다른 Worker의 revision 예약을 해제하지 않는다. rejection도 예상 Report를 operation 잠금 안에서 대조하므로 오래된 worker가 최신 revision을 거절하지 못한다.

raw 생성 직전 활성 Report/서명을 비교하고, 더 중요한 최종 fence는 `saveSigned`가 **operation row lock 획득 후** 최신 operation/position/활성 Report/calldata를 다시 검증하는 것이다. 다른 JVM이 revision을 먼저 commit했다면 이전 메모리 계산/raw는 저장·전송될 수 없다. 반대로 SIGNED commit이 먼저 이기면 revision이 tx 존재를 보고 실패한다. 이 구조는 모든 JVM의 메모리 서명 계산 자체를 전역 직렬화하는 것은 아니지만, durable SIGNED와 broadcast의 충돌을 차단한다. broadcast는 durable commit 이후에만 가능하다.

기존 executor sender guard/미확정 거래 차단은 유지하며 분산 nonce allocator를 새로 구현했다고 주장하지 않는다. Reconciler/Recovery는 활성 revision과 저장 Report를 대조하고 불일치 시 확정 성공으로 정산하지 않는다.

## 오프라인 검증 (현재 기록)

- 초기 직접 JUnit 선물: `futures-20261022T102946404903.log`, **171 PASS /12 SKIP /0 FAIL**. H2/Mock/로컬 서명 결과이며 PG/Anvil 체결 증거가 아니다.
- 신규11개: 무변경rev0, 최초 tick OPEN 재가격·canonical fixture 정산, 두 번째 tick 거절·한 번 반환, 가격/수량 범위 거절, stale 거절, nonce 취소, commit실패 전체rollback, 두 worker 단일 append·새 객체 횟수 유지, 이전 Report 송신 거부, SIGNED 유실 동일 raw 복구, TOP_UP/CLOSE house/담보 정산.
- 기존 legacy/B-v1, 사용자 권한, 시장5초/동일가격tick, Report/Intent기한, 자동마진콜/회복/60초 청산, 원래 금융/복구 테스트도 같은 새 회귀 실행에 포함된다. SKIP12개는 실제 DB/RPC opt-in suite이며 PASS로 세지 않는다.
- 초기 회귀 이후 old mark를 authority RPC 뒤에도 다시 확인하도록 보강했다. 최종 회귀는 아래에 별도로 기록한다.

## 실제 PG/Anvil Gate와 검토

새 격리 Gate를 준비했다. 실제 PostgreSQL/Spring HTTP/독립 시험 EOA signer/Anvil에서 OPEN/TOP_UP/CLOSE revision1, PostgreSQL commit fault rollback/append-only, canonical3events/DB settle, revision1 SIGNED 응답 유실 후 두 번째 JVM 동일 raw/hash/nonce CONFIRMED를 대상으로 한다. 통제된 시험 provider는1초마다 committed tick을 제공하고 첫 estimate 이후 가격을 변경한다. 이것을 제품 Synthetic 경로나 실제 MetaMask Chrome 성공으로 표현하지 않는다.

실제 실행 identity/sourceHash와 검증 결과는 다음과 같다. 최초 초안의 계획이 아니라 아래 실행 증거를 근거로 판정한다.

### 실제 실행 결과

- HEAD `f9353c33244565b30fb130dfb6159eab441a3523`, productHash `fb429106b70a1ea444985e7b00f7b70a034763b070f270aa9faad7637de5d8b3`.
- 새 session `phase-a-20261022103307-2a2c35f9`, fixture `futures-api-468dd2bd`, PG48395/RPC42313. 기존 정지 fixture를 재사용하지 않았다.
- `run-bv2-gate.py reprice`: 첫 JVM1 PASS/0 FAIL, 별도 두 번째 JVM1 PASS/0 FAIL, parent exit0. 기존 단일 전체 B-v2 Gate나 전체감사 PASS가 아니라 이번 **재가격 대상 Gate PASS**다.

| action | operation | 실제 txHash | 결과 |
|---|---|---|---|
| OPEN | `e5507df5-0efc-4d71-8a4e-313ef735095a` | `0x87a106de8bdd457f857a623a6afda107f5c35f2e9c85589a072fef80120acce9` | revision1 / canonical3events / DB CONFIRMED |
| TOP_UP | `16932b12-d5e3-4344-839a-b183d4adf5d4` | `0x221e59142394404265cb1202ad8c2857fb66986867c4fe45b9c49089e093932d` | revision1 / canonical3events / DB CONFIRMED |
| CLOSE | `0d3054ab-c3e3-4e34-85de-0867b514366f` | `0xda19e57ae1ccc6c9fcbd9566779ff8b7ba42aeb8d54807d56af54ac32d723f14` | revision1 / canonical3events / DB CONFIRMED |

각 거래의 최초 실행가격75000을75001로 변경했다. OPEN quantity/entry는 새 가격과 일치하며 TOP_UP/CLOSE는 기존 quantity/entry를 유지했다. 원래 Report 불변, revision2행, estimate2회, 새 가격서명/calldata, PostgreSQL revision UPDATE 거부를 확인했다. CLOSE 후 user locked와 house reserved 모두0이다.

PostgreSQL head UPDATE 직전에 이 신규 fixture만의 시험 trigger로 예외를 발생시켜 예약/journal/revision/head가 원래 상태로 rollback하는 것을 확인했다. 소유 시험 trigger만 해제했으며 기존 append-only trigger는 해제하지 않았다.

- revision1 OPEN operation `d83e2cee-dcd8-40a8-bcb3-620ebf80eb93`: 실제 전송 후 응답 유실을 주입해 SIGNED를 유지했다. 제1 JVM PID20552→제2 JVM PID2364, nonce4, txHash `0x9a48073c2db9934852f4710f0c2f42e5357a6f688908a63fa5ef68b39038a15a`, raw SHA-256 `b59b990ea334502fccacb8217809b975d08419a93da563a47a458638a9c229b0`가 일치하며 CONFIRMED됐다. journal CONFIRM1회, canonical3events 확인.
- private 증거는 fixture의 `gate.evidence.json`, `postgres-migration.evidence.json`, `restart-prepare.evidence.json`, `restart-recover.evidence.json` 및 session 로그다. 최초 gate의 `signedRevision1SecondJvmPending=true`는 첫 JVM 시점의 스냅샷이므로 덮어쓰지 않고 후속 recover 증거로 완료를 판정했다.
- 이 Gate는 실제 PG/HTTP/Anvil과 **1초마다 생성하는 시험 provider tick**이며 제품 Synthetic의 자율 난수 가격 경로/실제 Chrome/MetaMask가 아니다. 이번 신규60초 자동청산이나 전체 감사의 성공을 주장하지 않는다.

### 독립 검토의 필수 보완

최초 독립 검토에서 코드의 fence는 타당하지만 테스트가 Sender 사전 검사에서 멈춰 최종 saveSigned CAS를 직접 검증하지 못한다는 지적을 받았다. 다음을 추가했다.

1. old Report/raw를 먼저 계산→revision1 commit→`saveSigned(old raw)` 직접 거부, tx0·PREPARED/예약 유지.
2. SIGNED 선행 commit→RevisionStore 직접 호출 거부, raw/hash/nonce/revision 불변.
3. old Report를 SIGNED ledger 증거에 의도적으로 섞으면 Reconciler는 REVIEW_REQUIRED, 잠금 유지.
4. CLOSE 재가격 시 house 부족은 전체 rollback 후 unsigned 거절, 기존 포지션/담보/Report 보존.

최종 실행 결과와 재검토는 아래에 누적한다.

### 최종 회귀 결과

| 실행 | 결과 | 범위/주의 |
|---|---|---|
| 최종 main 기준 선물 직접 JUnit |171 PASS /12 SKIP /0 FAIL| `futures-20261022T103509194544.log`, 최초11개 재가격 테스트 포함 |
| 전체 backend 직접 JUnit |725 PASS /53 SKIP /0 FAIL| `all-20261022T103834714572.log`, SIGNED 양방향 fence 추가2개 포함, 마지막 증거손상/house부족2개는 아직 미포함 |
| 최종 재가격15개 선택 실행 |15 PASS /0 SKIP /0 FAIL| `reprice-20261022T104304911482.log`, 마지막 추가2개 포함. 앞 전체 회귀와 중복하므로 합산해 전체PASS 숫자로 쓰지 않음 |
| 실제 PostgreSQL/Anvil |첫 JVM1 PASS + 두 번째 JVM1 PASS| 위 신규 targeted Gate. 단위테스트/Chrome/전체 B-v2 Gate와 구분 |
| 제품 Synthetic + MetaMask 최신 인수 |NOT_RUN| 실제 사용자 TOP_UP/CLOSE는 후속 |
| 새 실제60초 자동청산 |NOT_RUN| 금융 산식/자동청산 오프라인 회귀는 포함, 기존 실제 PASS를 이번 것으로 재사용하지 않음 |
| 웹/Foundry 재실행 |NOT_RUN| 이번 웹/계약 변경 없음 |

컴파일은 `classpath-20261022T103737021902.log`, 마지막2개 보완 후 `classpath-20261022T104152450233.log` PASS. 이 실행들은 정상 Gradle 전체 test worker 성공 주장과 구분한다. 어떤 유료 LLM/embedding/증권 API도 호출하지 않았다.

## 보존·미완료·다음 인수

기존 root/UI/AI worktree, 성공 MetaMask OPEN/Receipt/journal 및 실패 fixture를 보존한다. 신규 fixture만 소유권 확인 후 생성·정지하고 데이터/로그 보존. 기존 RPC503/ViteEFAULT의 근본 원인은 계속 미확정이다. 유료 AI/commit/merge/push/외부 배포/C안/전체감사 확장 없음.

실제 제품1초 Synthetic + MetaMask TOP_UP/CLOSE는 별도 인수가 필요하다. 새 체인이면 사용자 직접 새 OPEN부터 필요하며 이전 포지션을 복사하거나 원래 체인을 재사용하지 않는다. 전체 B-v2 감사 coverage는 계속 INCONCLUSIVE다.

- 최종 보존 검사: 보호 기준156개 SHA-256 변경0. 기존363개 컨테이너 ID/이름/이미지/상태 분류·종료코드 변경0, 신규2개만 Exited(0), 데이터/증거 보존. Gate productHash와 현재 main 소스 일치, `git diff --check` PASS.
- 잔여 위험: 첫 revision 후에도 새 tick/nonce/기한이 바뀌면 거절될 수 있고 체결을 보장하지 않는다. 다중 Worker revision 경합과 최종 SIGNED fence는 H2/direct 저장 테스트이며 PostgreSQL 동시 revision 경합 전용 시험은 별도 NOT_RUN이다. 실제 시장가격의 진실성·온체인 담보 보관·지급능력 보증은 제공하지 않는다.
- 최종 독립 재검토 `review_bv2_single_repricing`: 선택15 PASS 로그까지 직접 확인하여 **발견된 필수 수정 없음**. 최초 SIGNED fence 검증 누락은 해소됐다. 검토자는 코드를 읽고 실제 targeted Gate/복구 증거를 대조했으며 테스트를 직접 재실행한 것은 아니다.
