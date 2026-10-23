# B-v2 실행 환경 및 가격 확정 경계 후속 조사

> 2026-10-22. [이전 RPC/가격 조사](dapp-b-v2-rpc-price-investigation-2026-10-22.md), [누적 지갑 인수](dapp-b-v2-browser-wallet-integration-2026-10-22.md)의 후속. 거래 정책 변경은 **설계 제안이며 구현하지 않았다**.

## 1. 결론과 작업 경계

- 같은 Node/IPv4/기존 실패 포트에서 최소 HTTP와 Vite가 모두 성공했다. 이전 `listen EFAULT`는 재현되지 않았고 근본 원인은 미확정이다. 포트 충돌이나 Docker port publishing 오류라고 단정하지 않는다.
- Vite를 생략한 **새 격리 실제 Spring Boot + PostgreSQL + Anvil**의 HTTP 소유증명/nonce/draft 경로가 성공했다. 기존 서버 RPC 진단도 실제 동작했다. 과거503은 재현되지 않아 원인 해결을 선언하지 않는다.
- 가격 확정 이후에도 calldata 의존 estimate가 필요하다. A(초기 확정 시점 지연)만으로 exact-price 거부를 없애거나 정상 tick 실행을 보장할 수 없다.
- 권장 최종 개선은 B의 **B-v2 사용자 action에만 적용하는 unsigned 단일 재가격 기회**다. 단, 불변 evidence revision과 DB 예약/서명 CAS를 설계해야 하므로 이번에 활성화하거나 구현하지 않았다. 반복 재가격/TTL 증가/시장 검사 삭제는 없다.
- 이번 제품 main/계약/DB schema/금융 계산 변경0. 추가 파일은 private 진단 도구, 테스트2개 및 문서다. 이전 작업의 진단 코드와 미커밋 B-v1/v2 전체를 이번 구현으로 재분류하지 않는다.

## 2. 실행 기준과 최소 listener 비교

브랜치 `feat/dapp-user-authorization`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523`. 이번 실제 서버 productHash `4fda0ffee9039249c3be19bed2219822e75e2fecf219f0e3555da24b41ddf19f`는 이전 진단 구현과 동일하다. 문서/테스트/private harness는 기존 product_hash 계산 범위 밖이다.

먼저 Docker/PG/Anvil을 만들지 않고 `probe-listeners.py`를 1회 실행했다. 같은 Node `v24.4.1`, 같은 `127.0.0.1:30585`에서 순차3건만 실행했다. 각 프로세스25초 상한, 포트 탐색·fallback·자동 재시도 없음. 성공 시 listener를 닫았다.

| 비교 | 결과 | listen 준비 경과 |
|---|---|---|
| 사용자 shell 환경의 최소 Node HTTP | PASS | 25ms |
| 기존 fixture clean child 환경의 최소 Node HTTP | PASS | 38ms |
| 같은 clean 환경 + 실제 Vite config + 명시적 IPv4/strictPort | PASS | 496ms |

세 프로세스 모두 NODE_OPTIONS 미설정. 환경 원문은 출력하지 않았다. Vite는 실제 제품 config를 읽되 root/host/port/strictPort만 명시했다. 실제 config 기본 port5173을 CLI와 같이 덮어쓰며, 기존 실패 launcher도 `--host 127.0.0.1 --strictPort`였다. 따라서 명시적 IPv4를 새로 켜서 고쳤다는 주장은 하지 않는다. 최소 HTTP는 Vite의 proxy/시장 API나 앱을 검증하지 않으며, 최소 Vite 성공도 실제 브라우저 거래 성공을 뜻하지 않는다.

이전 실패의 확인 가능한 경계는 Windows Node `Server.setupListenHandle`의 EFAULT다. EADDRINUSE가 아니었고, 이번 동일 포트 성공은 **현재 listen 가능**하다는 증거이지 과거 원인 증명이 아니다. Docker 게시 포트/RPC 장애와도 별개다. 전역 설정/방화벽/Windows/WSL/Docker 재시작 없음.

안전한 대안: 진단 목적에서는 Vite 없이 실제 backend loopback HTTP에 직접 연결한다. 웹 인수 시에는 먼저 동일 bounded listener preflight를 통과해야 하며, 재발 시 해당 새 run에서 중단한다. WSL/Linux 이전이나 보존 fixture 재시작은 이번에 하지 않았다.

private 최소 진단 증거: `deployment/runtime/dapp-stage2-offline/listener-20261022T095835534840-*.log` 및 summary JSON. stdout/stderr/exit code/Node 버전/선택주소/경과시간이 포함된다.

## 3. Vite와 분리한 실제 서버 RPC 관측

listener 비교 통과 후 **신규 session1개만** 생성했다.

- session `phase-a-20261022095928-8b249520`, fixture `futures-browser-d6fac52d`.
- 새 PG44898 / RPC46034 / backend48852, 실제 Spring Boot PID300. Java21.0.10, 기존과 같은 프로세스 범위 IPv4 옵션, loopback proxy 예외, 3초 RPC timeout. 새 domain/계약/DB identity 및 fixture 소유 검사 통과.
- private launcher에 `BV2_RPC_PROBE_ONLY=1` 경로 추가: Vite를 시작하지 않고 같은 실제 제품 Spring context에 HTTP로 접속한다. 이 모드만 Origin을 backend loopback으로 고정하며 일반 브라우저 모드는 기존 동작을 유지한다.
- controlled 테스트 계정1개와 공개 시험 scalar1로 EIP-191 소유증명만 수행했다. 사용자 MetaMask/독립 개인키 인수가 아니다. **EIP-712 거래 서명·submit·가스 지급·OPEN/TOP_UP/CLOSE는 수행하지 않았다.**
- signup201, `/api/me`200, wallet challenge200, link200, nonce200, draft200. nonce0, draft `2d85c118-9201-427b-8b0d-94617a5e7727`.

실제 서버 로그의 대표 단계:

| scope | trace | 측정 결과 |
|---|---|---|
| nonce API | `2bffa929-5ba5-4327-98df-4e3418ca199f` | deployment22ms, nonce2ms, 전체27ms |
| OPEN draft API | `6fc7375e-8b8c-4b23-9f3c-d42fc7f8e669` | chainId2ms, controller/rules code 각2ms, deployment24ms, EOA1ms, nonce1ms, 전체88ms |
| 그 전 소유증명 scope | `f6127c9f-18b3-473f-b8f3-3bf88f9ff1d3` | chainId131ms, 나머지 getter1~2ms |

deployment에는 controller executor/rules/TTL 및 rules executor/signer/symbol/policy 검사가 포함된다. 하위 `OK`는 전송 완료이고 외부 DEPLOYMENT `OK`가 의미 검증 완료를 나타낸다. trace는 동기 scope 단위로만 연결된다. 실제 서버에서 FAIL/timeout/503이 이번에는 없으므로 **과거 어느 RPC가 실패했는지는 여전히 확인 불가**다. 별도 signer JVM은 소유증명 생성만 담당하며 RPC 성능 측정의 주체가 아니다.

private 증거: fixture `rpc-server-observation.json`, `rpc-probe-process.json`, `backend.private.log`, `browser-ready.json`, session before/after container inventory. 기존 키/토큰/서명/raw/비밀 설정을 보고서에 복사하지 않았다. 운영 API·공개 오류 형식 변경 없음.

## 4. 실제 코드와 DB 트랜잭션 기준 확정 경계

### 현재 순서

1. `AuthorizedV2Service.submitInternal`: 기존 draft/계정/멱등성 확인, 사용자 EIP-712 consent 및120초 기한 검증.
2. DB 금융 잠금 밖에서 배포/EOA/사용자 nonce RPC 검증.
3. `market.capture`: 적격 가격/provider/generation/관측시각 확보. OPEN은 FuturesMath.quantity 계산, TOP_UP/CLOSE는 기존 entryPrice/quantity 유지.
4. 서버 Report 생성/서명 및 사용자 승인 가격·수량 범위 검증, exact-price 재검사.
5. `AuthorizedV2Store.accept @Transactional(timeout=5)`: 사용자 guard → draft → wallet row 잠금, owner/기한 재확인, 같은 Report 검증.
6. 같은 트랜잭션에서 ledger.prepareAuthorized(Open) 호출: 사용자/포지션/house/잔고 잠금, 담보/house 예약과 operation/position/journal 저장. 같은 트랜잭션에서 승인 metadata/Report/가격서명/시장 증거 저장, draft ACCEPTED. 실패 시 전부 rollback.
7. commit 후 Worker signal. Worker는 기존 pending 체인 거래 복구를 먼저 처리하고 PREPARED를 처리한다. 신호가 있어도 이전 작업이 실행 중이면 지연 가능.
8. `FuturesExecution`: 고정 mark 재검사 → DB operation/position으로 Report 재구성 → 불변 저장 Report와 동등성 확인.
9. Sender: deployment RPC → 미해결 sender 검사 → pending relayer nonce/gas → 전체 calldata/승인 검증 → 해당 calldata의 estimate.
10. raw 직전: 고정 mark 재검사 → 사용자 EOA/nonce/기한 확인 → 고정 mark 재검사 → raw 서명.
11. `FuturesTransactionStore.saveSigned REQUIRES_NEW(timeout=5)`: sender guard와 operation 잠금, PREPARED/pending/Report/서명/calldata/raw 동등성 검증, durable SIGNED commit.
12. commit 후 broadcast. 응답 유실이면 SIGNED/예약 보존, 복구는 **동일 raw/hash/relayer nonce만** 사용한다.

현재 RPC는 금융 예약 트랜잭션 안에서 실행하지 않는다. 반면 `saveSigned` 전 raw 계산은 JVM 메모리에서 일어나므로, 미래 revision 설계는 단순히 DB에 SIGNED 행이 아직 없다는 이유만으로 이전 raw와 경합해선 안 된다. revision CAS와 sender claim을 raw 생성/저장까지 연결해야 한다.

### 무엇이 가격에 의존하는가

| 작업 | 가격 고정 전 가능 여부 | 불변 조건 |
|---|---|---|
| 인증/사용자 서명/domain/배포/EOA/nonce 조회 | 가능, 현재 상당 부분 이미 선행 | 송신 전 변경 여부 재검사는 여전히 필요 |
| pending relayer nonce / gas price | 사전 조회 가능 | sender 간 경합 및 값 변경 때문에 DB 밖 조회값을 독점 예약으로 취급 금지 |
| OPEN 수량/진입가 | 반드시 선택 가격에 의존 | `quantity = FuturesMath.quantity(C,L,P)`와 서명 범위 동시 만족 |
| OPEN/TOP_UP 사용자 담보 예약 | 금액 자체는 가격 비의존 | 포지션 상태/Report/승인 metadata와 원자적으로 commit해야 함 |
| TOP_UP 위험 회복 여부 | 가격 의존 | 새 담보와 mark에 따른 실제 계약 결과와 일치 |
| CLOSE 실현손익/badDebt/house 지급 예약/잔고 범위 | 가격 의존 | `houseReservation=max(realized,0)`와 operation expected값 일치 |
| Report/서명/calldata | 가격과 관측시각에 의존 | 사용자 Intent와 정확한 실행 Report 연결 |
| 정확한 estimate | calldata 확정 후 필요 | 예전 calldata estimate를 새 거래의 검증으로 취급 금지 |
| SIGNED raw 저장/전송 | 모든 값 확정 후 | 이후 교체/재가격/nonce 재선택 금지 |

`futures_authorized_operations`는 PostgreSQL `FuturesSchema`의 UPDATE/DELETE/TRUNCATE 금지 trigger 대상이다. 현재 Report 변경은 SQL 몇 줄 교체로 구현할 수 없다. operation의 가격/기대손익/예약, OPEN_PENDING 포지션의 q/entry, market_evidence도 함께 일치해야 한다.

### exact-price 검사의 목적과 한계

5초 신선도는 오래된 증거를 차단하고 provider/generation은 공급자/연결 전환을 차단한다. exact-price 검사는 **이미 예약·서명·불변 저장한 실행가격**이 raw 직전 시장과 달라진 것을 거부한다. 사용자 범위 승인과는 별개의, 고정 실행 증거 일관성 정책이다. 따라서 범위 승인만을 이유로 검사를 제거하면 현재 정본의 의미가 바뀐다.

동일 가격 새 tick은 허용되지만 원래 fixed observedAt을 갱신하지 않는다. 5초 경계는 포함, 5초+1ns는 거부한다. 새로운 timestamp만 덮어써 TTL을 연장하는 일도 금지한다.

## 5. A/B 비교와 가장 작은 안전 변경 제안 — 승인 대기

### A. 최초 실행가격 확정 지연

가격 비의존 RPC를 더 먼저 수행하는 것은 가능하다. 하지만 현재 API는 이미 주요 검사를 capture 전에 수행하고 있으며, 최신 실제 서버 draft의 deployment는24ms였다. 과거 병목이 해결됐다는 뜻은 아니지만 증거 없이 검사를 캐시/삭제할 근거도 없다.

실질적으로 확정을 Worker까지 미루려면 사용자 서명 수락을 금융 PREPARED와 분리한 `CONSENT_ACCEPTED` 큐가 필요하다. 사용자/포지션/멱등 guard와 API 상태 계약, 재시작 복구를 추가해야 한다. Worker가 가격/수량/손익/예약/Report를 최초로 원자적 고정하고 그 뒤 정확한 estimate를 해야 한다. **estimate 이후 tick 경합은 여전히 남는다.** RPC를 오래 걸리는 DB row lock 안에 넣는 구조는 권장하지 않는다.

판정: 작은 호출 순서 조정만으로 일반적인1초 Synthetic tick 안정성을 보장할 수 없다. 이번에 금융 가격 확정 순서를 바꾸지 않았다.

### B. unsigned 한정 단일 revision (권장 검토안)

현재 계약의 bounded Intent는 새 적격 Report를 수용할 수 있다. 컨트랙트/금융 산식 변경보다는 backend evidence와 영속화 경계 변경이 중심이다. 다음 설계를 구현 전 승인받는 것을 권장한다.

1. **범위 제한:** B-v2의 OPEN/TOP_UP/CLOSE만 opt-in, 기본OFF. legacy/B-v1 및 자동 마진콜/청산 경로는 기존 규칙 유지. 사용자 Intent/서명/owner/operationId/positionId/nonce/정책/domain은 불변이다.
2. **횟수 제한:** PREPARED의 PRICE_CHANGED에서만 최대1회의 revision 기회. count를 영속화하여 재시작/새 Worker로 초기화하지 않는다. 시간 만료/provider 또는 generation 변경/권한 오류를 재가격으로 숨기지 않는다. 반복 재가격 루프 금지.
3. **새 증거:** 새 적격 mark와 새30초 Report/가격서명을 생성한다.5초/사용자120초 기한과 승인 가격·수량 범위를 모두 다시 검증한다. OPEN q는 같은 산식, TOP_UP/CLOSE의 기존 entry/q/version/epoch 고정.
4. **원자성:** user/position/operation/sender guard의 기존 잠금 순서를 분석·통일한 뒤, PREPARED·pending·revision·tx없음·사용자 nonce 조건을 검증한다. RPC는 DB 잠금 밖에서 하고 commit 시 저장 버전/기한/market 재검사를 결합한다. 사용자 nonce는 최종 raw 직전 재검사하며 nonce 조회만으로 취소 경합 확정을 보장하지 않는다.
5. **예약 조정:** OPEN의 잠긴 C와 TOP_UP의 추가 C를 이중 예약하지 않는다. OPEN_PENDING q/entry를 새 Report와 함께 반영한다. CLOSE는 새 realized/badDebt/wallet 범위와 house 예약을 계산하고 차액을 반영한다. 지급 부족이면 revision 전부 rollback하고 기존 예약/증거를 보존한 채 명시적 unsigned 거절 절차로 종료한다.
6. **불변 이력:** 기존 append-only 승인/Report를 수정하거나 trigger를 제거하지 않는다. 별도 append-only execution revision 테이블과 active revision CAS를 설계한다. journal은 삭제/덮어쓰기 없이 필요한 예약 차액을 새로운 유일 항목으로 기록한다. rollback은 pointer/operation/position/house/journal 전부 원자적이어야 한다.
7. **이전 Report 차단:** Sender는 active revision 번호/hash를 들고 새 calldata/서명을 검증하고 새 estimate를 수행한다. `saveSigned`가 동일 active revision 및 Report/예약 동등성을 CAS로 검증한다. 변경 전 Report로 생성한 raw는 저장·전송 불가. sender/operation fence 없이 두 Worker가 각각 서명해 전송하는 구조 금지.
8. **SIGNED 불변:** raw 생성/저장과 revision의 경합은 동일 sender/operation fence 및 CAS로 차단한다. SIGNED 행 존재 또는 commit 불확실 시 refresh/예약해제 금지. canonical 결과 또는 동일 raw 복구만 허용.
9. **재검사 유지:** revision 후에도 estimate와 raw 직전 exact-price/신선도/nonce 검사를 유지한다. 다시 tick이 바뀌면 거절한다. 이 설계도 무조건 체결을 보장하지 않는다.

예상 수정 대상: `AuthorizedV2Store`/새 revision store, `AuthorizedV2IntentStore`, `FuturesExecution`, Sender/TransactionStore, ledger의 제한적 unsigned revision API, additive schema, revision을 읽는 Receipt 검증/복구/관련 읽기·증거 계약, H2·PG 동시성 및 Anvil Gate. 기존 고정 Report 조회가 여러 경로에 있으므로 Sender만 바꾸는 패치는 안전하지 않다. 감사 미구현 coverage는 계속 INCONCLUSIVE다. 예상: 설계/코드/단위0.5~1일 + 실제 PG/Anvil·장애/독립 검토0.5~1일, 환경·실제 지갑 인수 시간 별도. 이번에 구현하지 않았다.

## 6. 실제 무과금 테스트

- 새 테스트2개: `FuturesMarketGateTest.newerSamePriceTickKeepsOriginalFixedEvidenceWithoutRepricing`, `originalFixedEvidenceIsAcceptedAtFiveSecondsButNotOneNanosecondLater`.
- `run-offline.py classpath`: compileJava UP-TO-DATE/compileTestJava PASS, `classpath-20261022T100034800494.log`.
- `run-offline.py futures`: **160 PASS / 12 SKIP / 0 FAIL**, `futures-20261022T100118459047.log`. 실제 H2/Mock/로컬 서명 및 직접 JUnit. 일반 Gradle test worker 전체 회귀/실제 거래 E2E가 아니다.
- 기존 승인 범위 초과/수량식/Report 충돌 rollback/동시 submit/estimate 및 최종 nonce 중 가격변경/nonce 취소/유실 후 동일 raw 복구 테스트도 같은 새 실행에 포함된다. 실제 금융 정책은 바꾸지 않았다.
- 신규 PG/Anvil HTTP 무과금 소유증명/nonce/draft 관측 PASS. 실제 거래/estimate/send/새 MetaMask 서명은 NOT_RUN. 과거 OPEN과 개별 체인 PASS를 이번 실행으로 대체하지 않는다.

향후 B를 승인받으면 필수 추가 Gate: 동일 Intent 단일 refresh 성공, 두 번째 변경 거절, 범위 초과/Intent 만료/provider 전환 차단, OPEN q/entry와 CLOSE 손익/house 예약 변경, 부족 pool rollback, revision/journal append-only, old Report 송신 차단, 다중 Worker와 saveSigned 경합, SIGNED 응답 유실·별도 JVM 동일 raw 복구, cancelNonce 경합, 실제1초 Synthetic tick과 MetaMask TOP_UP/CLOSE 및 canonical3이벤트/DB 정산.

## 7. 보존·검토·다음 인수

신규 backend-only 프로세스와 자기 PG/Anvil은 parent가 종료·정지했고 증거/데이터는 보존했다. 기존 STOP fixture/실제 성공 OPEN을 재시작하거나 수정하지 않았다. 원본/UI/AI worktree, ledger/STOP/claim/서명 증거 및 기존 컨테이너는 정리하지 않았다. commit/merge/push/외부 배포/유료 호출 없음. 신규 시험 Anvil에만 시험 계약을 배포했다.

독립 검토 결과와 보호 기준 최종 대조는 아래에 누적한다. 다음 MetaMask 인수는 A/B 정책 결정과 승인된 구현/회귀, 새 fixture listener/RPC preflight 통과 이후에만 안내한다. 지금 사용자에게 재서명을 요청하지 않는다. 새 체인에서는 새 OPEN부터 필요하며 이전 성공 포지션을 옮긴 것으로 표현하지 않는다.

- 최종 보호 검사: 기존 기준156개 SHA-256 변경0. 새 session 전후 기존361개 컨테이너 ID/이름/상태 분류 변경0, 이번 신규 PG/Anvil2개만 Exited(0), 데이터·로그 보존. `git diff --check` PASS.
- 독립 검토 `review_execution_boundary_followup`: 새 도구/분기/테스트 및 관련 금융 코드·보고서·실제 listener/HTTP/RPC/JUnit 로그를 직접 확인하여 **발견된 필수 수정 없음**. 검토자는 테스트를 재실행하거나 전체 보호 리소스를 재측정하지 않았다. 이 검토는 과거 장애 원인 확정이나 미구현 revision의 안전성 인증이 아니다.

## 후속 승인·구현

이후 사용자가 unsigned 최대1회 재가격을 승인했다. [새 구현·실제 targeted Gate 결과](dapp-b-v2-single-repricing-implementation-2026-10-22.md)를 따른다. 이 문서의 승인 대기 표시는 당시 기록으로 유지하며 과거 환경 실패를 소급 수정하지 않는다.
