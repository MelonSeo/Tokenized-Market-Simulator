# B-v2 실제 PostgreSQL / Anvil 통합 Gate

작성 2026-10-22. [백엔드 구현](dapp-b-v2-backend-integration-2026-10-22.md), [계약 Gate](dapp-b-v2-contract-implementation-2026-10-22.md) 이후 실행 기록. 기존 실패 이력은 보존한다. **정상 거래·실제 자동청산·정산·복구의 개별 검증과 보완 2개 JVM 실행은 PASS. 전체 판정은 PARTIAL, 마지막 단일 full-run 시도는 Docker 포트 게시 환경 BLOCKED다. 한 번의 전체 시나리오가 전부 PASS했다고 주장하지 않는다.**

## 기준과 격리

- worktree `deployment/runtime/dapp-user-authorization-worktree`, branch `feat/dapp-user-authorization`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523` + 기존 미커밋 구현.
- productHash `64d766d57bffa7938530d8aeaeef2a4f136c66f78773a383d7bcc0a19beedf1c`. 이번 제품 Java/SQL/Solidity 변경 없음. 새 Java 인수 테스트와 private 하네스만 추가했다. productHash에 테스트/하네스는 포함되지 않으므로 실행별 로그·테스트 버전을 함께 해석한다.
- Docker Server 28.4.0 정상 응답. 첫 sandbox 내 default context 조회 실패와 권한 확보 후 실제 `desktop-linux` 정상 응답을 구분한다. 전역 context 변경/daemon 재시작 없음. C 드라이브 최초 가용 약40.89GiB.
- 기존 phase-a의 신규 session/포트 할당/owner label/loopback bind/SQL marker/clock guard/소유 컨테이너만 종료하는 경계를 재사용. 새 PG bind 경로·persistent Anvil state·별도 relayer/priceSigner/user EOA. 기존 컨테이너/볼륨/DB/chain을 시작·재사용·초기화하지 않았다.
- 사용자 키는 새 private `secrets/user-*`를 독립 signer JVM만 읽는다. Spring properties에는 사용자 키를 주입하지 않는다. 이는 테스트 하네스의 독립 EOA 서명이며 브라우저 지갑 인수가 아니다.
- AI 및 외부 가격 공급자는 OFF, child 환경은 기존 clean_env whitelist와 loopback-only HTTP proxy 정책. 유료 호출/키움 호출/commit/merge/push/배포 없음.

## 변경 파일과 실행 방식

- 신규 추적 대상: `backend/src/test/java/com/pricetrack/exchange/futures/chain/AuthorizedV2PostgresAnvilE2ETest.java`.
- private ignored 하네스: `deployment/runtime/dapp-stage2-offline/{run-bv2-gate.py,Dockerfile.bv2-tools,DeployBv2Gate.s.sol,deploy-bv2.sh,Bv2ExternalSigner.java,Bv2SocketPreflight.java}`. 키/DB/state/원본 로그는 Git 대상이 아니다.
- 기존 오프라인 in-process Gradle 경로로 testClasses를 컴파일하고 동일 JDK21.0.10/classpath의 직접 JUnit launcher로 `@SpringBootTest(RANDOM_PORT)`를 실행한다. 실제 Tomcat HTTP·PG·Anvil·제품 Worker이며 정상 Gradle test worker 전체 PASS와는 다르다.
- 프로세스 범위 `preferIPv4Stack=true`, `preferIPv6Addresses=false`. 독립 ServerSocketChannel loopback bind/listen PASS. 이것만으로 과거 native socket 근본 원인이 해결됐다고 판단하지 않는다.
- 제품 Synthetic 엔진 전체 인수가 아니라, 기존 test-only persistent Scenario 공급자가 실제 MarketPriceService/가격 gate/이벤트/Worker로 전달하는 결정적 Synthetic 시나리오다. 공개 가격 조작 API는 추가하지 않았다.

## 시도별 증거와 결함 분류

1. `phase-a-20261022012433-d1383ccf`: tools build의 `COPY contracts/lib` 경로 없음으로 실패. 분리 worktree에 라이브러리가 없는 빌드 구성 문제이며 PG/Anvil 생성 전이다. 원본 라이브러리를 읽기 전용 named build context로 전달하도록 하네스만 보완했다.
2. `phase-a-20261022012538-1fcfeab0/futures-api-7e1495d7`: 실제 Spring/HTTP/PG/Anvil 정상 OPEN(10초 지연)/TOP_UP/CLOSE·양방향 자동청산을 포함한 최초 1테스트 PASS. 단 이 버전은 60초 증거·PG 실패 경계·복구 coverage가 부족하므로 전체 Gate PASS가 아니다. 당시 DB33719/RPC34773, 증거와 종료 컨테이너 보존.
3. `phase-a-20261022013733-f8020f87/futures-api-2212601e`: `expected2 but was4` assertion. 복제 schema에도 만들어진 trigger를 전역 집계한 테스트 결함이다. `tgrelid`를 public 대상 두 테이블로 한정했다. 제품 migration 실패로 해석하지 않는다.
4. `phase-a-20261022014600-84cad95b/futures-api-50385b63`: 실제 metadata INSERT에서 SQLSTATE P0001 주입 성공 후 HTTP5xx 기대 assertion 실패. unhandled servlet exception의 error dispatch 결과를 SQL rollback 증거로 사용하는 테스트 가정이 부적절했다. Spring 서비스 경계에서 정확한 P0001과 operation/journal/잠금 rollback을 검사하도록 변경했다. 정상 사용자 거래는 계속 HTTP로 검사한다. 원래 HTTP 오류 표현의 정확한 계약은 별도 문제이며 정책을 바꾸지 않았다.
5. `phase-a-20261022015334-7aa7d191/futures-api-f71aaef3`: PG migration/제약/경합/rollback, 실제 HTTP OPEN/TOP_UP/CLOSE, CLEAR_CALL/추가담보 회복, 사용자 nonce 취소 후 자동청산을 통과했다. Long 실제65.8609056초/Short67.2529505초, 두 canonical call deadline 차이60초 및 청산 Report/Receipt 경계 검증 PASS. 이후 wrong-contract 부정 사례의 테스트용 Java identity가 controller=rules로 구성되어 `AUTHORIZED_DEPLOYMENT_CONFLICT`로 **EVM 전** 실패했다. 제품 계약/가격 정책 문제로 해석하지 않는다. 정상 core의 개별 증거는 유효하지만 전체 JUnit 결과는 FAIL로 보존한다.
6. `phase-a-20261022050248-c1daf0a2/futures-api-832da6e1`: wrong-contract 서명 domain에는 이 새 체인의 별도 legacy 주소를 사용하도록 테스트를 수정했다. 신규 격리 보완 실행은 정상 OPEN/CLOSE로 독립 상태를 만든 뒤 체인 오류·재시작·취소·REVIEW_REQUIRED만 검사한다(`tail`). **첫 JVM1PASS/두 번째 JVM1PASS, FAIL0**. 위 실제60초 core를 새 결과처럼 재실행했다고 주장하지 않는다. `result.json`의 PARTIAL을 그대로 보존한다.
7. `phase-a-20261022051415-f668ec6e/futures-api-14492147`: 최종 테스트 버전의 단일 full-run을 새 identity에서1회 시도했으나 **Anvil docker run 포트 게시 단계 BLOCKED**. PG30156/RPC45419. Docker daemon은 `exposing port TCP 127.0.0.1:45419 ... listen tcp4 ... invalid pointer address`를 반환했다. 계약 배포·Java·금융검증 전이며 Gradle 오류/계약 결함으로 분류하지 않는다. 새 PG는 finally에서 정지(Exit137), Anvil은 부분 생성된 Created 상태로 실행되지 않음. 이 PG를 정상 초기화 완료로 간주하지 않는다. 기존 환경과 실패 자료를 변경하지 않고 중단했다.

## 최종 테스트의 검사 범위

| 범위 | 검사 방법 / 판정 경계 |
|---|---|
| PG migration | 새 별도 schema에 v1 테이블/행을 구성하고 additive migration 뒤 기존 metadata/행 보존. 실제 보존 DB 복사본이 아닌 구조화된 v1 fixture |
| PG 제약/원자성 | wallet UNIQUE, v2 FK, append-only mutation 거부; 동시 draft/submit; metadata INSERT 장애 후 예약/operation/journal/metadata rollback |
| 배포 identity | 새 controller/rules codeHash·chain31337·executor/signer/symbol/policy·120초 RPC 검증, legacy와 분리한 runtime |
| 정상 사용자 | 실제 HTTP/JWT, wallet challenge/personal signature, 10초 이상 승인 지연 후 새 Report, OPEN/TOP_UP/CLOSE, 3event·canonical blockHash·journal1회·동일 key replay |
| 권한/오류 | 타인 포지션 REST 거부. 새 실제 Anvil EVM eth_call의 valid baseline과 서명/owner/nonce/domain/가격/수량/두 만료/v1ABI/relayer/replay 거부. **미채굴 call이며 실패 tx Receipt라고 부르지 않음** |
| 위험 관리 | 실제 Worker START_CALL/CLEAR_CALL, 유예 중 가격 회복·추가담보 회복; Long/Short canonical call timestamp+60=deadline, LIQ Report/Receipt>=deadline, 위험 주입 이후 실제60초 이상 |
| nonce 취소 | 사용자 별도 서명 취소 tx canonical 확인 후 자동청산 독립성. SIGNED raw 보류 후 사용자 취소 선확정 → 동일 raw 전송 → canonical revert와 예약 해제 |
| 복구 | 실제 broadcast 뒤 응답만 유실 주입, SIGNED commit 검증; 별도 JVM의 CONFIRMED 복구에서 raw SHA256/hash/nonce 불변과 journal1회 |
| 증거 불일치 | 실제 PG/Anvil 거래의 실제 Receipt를 얻은 뒤 응답 owner topic만 test spy로 변조해 REVIEW_REQUIRED/잠금 보존. **실제 체인의 위조 이벤트가 아니라 RPC 응답 fault injection** |
| 정산 보존 | user wallet+house 보존, 전체 locked/house reserved 해제와 journal 대응. review fault fixture의 보존 잠금은 따로 판정 |

60초는 vm.warp/시간 가속이 아니라 자연 진행하는 2초 주기 Anvil 블록 및 monotonic wall time을 함께 검사한다. 복구/취소 장애 주입에서는 새 v2 worker만 정지하고 실제 Sender/Reconciler를 호출한다. 정상 자동청산의 실제 worker 흐름과 구분한다.

## 감사 및 미검증 경계

- canonical 정산용 v2 3event/서명/Report 검증과 v2 전체 감사는 별개다. 기존 `AUTHORIZED_CONTRACT_COVERAGE_REQUIRED`/INCONCLUSIVE를 MATCH로 변경하지 않는다.
- 담보는 PostgreSQL 모의장부이며 실제 ERC20 예치/비수탁/지급능력 증명이 아니다. C안 미구현.
- 브라우저 지갑/실제 사용자 수동 서명 UI, B-v1까지 포함한 세 runtime의 동시 실제 인수, 전체 기존 backend 정상 Gradle 회귀, fresh 실제 공급자 세대 변경은 이번 별도 NOT_RUN 경계다.
- 취소 선확정/실행 선확정과 양 tx가 동시에 mempool에 존재하는 경합은 다르다. 최종 증거 범위를 확인해 명시한다.

## 최종 실행 결과 / 독립 검토

### 새 실행 identity

- 보완 session sourceHead/productHash는 위 기준과 동일. tools image ID `sha256:7a5108d6f213f0112720114ff0038b088134c108261b0b9c0538ef28812f9416`.
- 보완 DB `exchange_futures_e2e`, loopback PG40737 / RPC40509, chain31337, 자연2초 mining. core 증거는 별도 DB31332/RPC32918의 `f71aaef3`에 귀속한다.
- 보완 controller `0x7c9c5CEb0cF52273B1220cA84E8Fc127f25026C4`, rules `0x830360CAb18383638BaA73A5D6CD146713dEA4aa`.
- controller codeHash `0x4e5daf79bb045b254c62a9e4a39ae5aa33f1e59a08562e91e1e504da137b342d`; rules codeHash `0xb2585d00b2ec971387b9b506182ccf1534843a2ca4fde8620f30babb5f81354b`.
- 최종 인수 테스트 SHA256 `e868f4933229a3bdaf48ad8fb37de4f5ca1364e9a87f69116832d7a348572582`. 제품 hash와 테스트 hash를 구분한다.

### 항목별 판정

| 항목 | 판정 | 실제 근거 / 제한 |
|---|---|---|
| 새 PG 및 v1 형식 행 보존 / UNIQUE / FK / append-only | PASS | core와 보완의 `postgres-migration.evidence.json`. 구조화된 v1 행 fixture이며 실제 기존 사용자 DB를 복사하지 않음 |
| 동시 draft / 동일 key submit / P0001 원자적 rollback | PASS | core의 해당 assertion 통과 후 정상 13operation 확정. HTTP 오류 매핑 자체는 별도 미확정/후속 |
| 실제 Spring HTTP/JWT/지갑 proof / 타인 접근 차단 | PASS | 실제 Tomcat 요청. 브라우저 지갑 아님 |
| 10초 서명 대기 뒤 새 Report / OPEN / TOP_UP / CLOSE | PASS | core 첫 OPEN의 Report observedAt−Intent issuedAt=12초. 사용자 3event canonical 검증·journal 중복 없음 |
| 실제 계약 권한/범위/두 만료/domain/nonce/ABI | PASS(eth_call) | 보완 13case: baseline1 허용 + 거부12. 사용자 서명 없는 정상 CLOSE를 별도 mined tx로 시도한 검증은 NOT_RUN |
| CLEAR_CALL / 추가담보로 위험 해소 | PASS | core `grace-recovery.evidence.json`, 실제 Worker/온체인 결과 |
| Long/Short 자동청산 / 취소와 자동위험 독립 | PASS | core auto-long/short 및 cancel-before-automatic. 실제65.8609056/67.2529505초. 두 call deadline=canonical call timestamp+60 |
| DB wallet/house/journal 보존 | PASS(한정) | core 정지 후 소유PG만 읽기 전용 SQL로 대조, 아래 정수값. ERC20 보관/전체 감사 MATCH 아님 |
| durable SIGNED → 실제 전송 응답 유실 → 두 JVM CONFIRMED | PASS | 보완 PID3220→25080, raw SHA256/hash/nonce 불변, canonical3event/journal1회 |
| SIGNED 이후 취소 먼저 확정 → 기존 raw canonical revert | PASS(순서 제어) | 보완 cancel-signed-race. 취소만으로 잠금 해제하지 않고 실패 Receipt 후 해제. 양 tx 동시 mempool 경합은 NOT_RUN |
| 증거 불일치 → REVIEW_REQUIRED / 잠금 유지 | PASS(fault injection) | 실제 chain 성공Receipt의 owner-topic RPC응답만 변조. DB 잠금100000 mKRW 유지, CONFIRM journal0. 실제 chain 변조가 아님 |
| legacy/B-v1/B-v2 Java 회귀 | PASS / SKIP | 새 실행 직접JUnit150PASS/12SKIP/FAIL0, 아래 로그. 세 runtime 동시 실환경 인수는 NOT_RUN |
| Windows native socket | 이번 listener/실제 Spring PASS | 정상 Gradle test worker 전체 및 과거 근본 원인 해결은 NOT_RUN |
| B-v2 전체 감사 / 브라우저 지갑 / C안 | NOT_RUN / 미구현 | 이번 인수 범위 제외/후속. 기존 INCONCLUSIVE guard 유지 |

기존 전체 테스트 결과를 새 실행처럼 재사용하지 않았다. 최종 `testClasses` BUILD SUCCESSFUL은 `classpath-20261022T050221971573.log`; 새 선물 패키지 직접 JUnit **150PASS/12SKIP/FAIL0**은 `futures-20261022T050950896009.log`다. 정상 Gradle 전체 backend/웹/Foundry test를 이번에 재실행한 것은 아니다. 이번 제품 소스는 변경하지 않았다.

### 대표 canonical 거래

| 실행 | txHash / 검증 |
|---|---|
| core 10초 지연 OPEN | `0x69ab0c7da152b7e36f1c769de301379e09788b2e3dcccc966c6d10cf8ef3240e` — Report가 Intent보다12초 최신, rules2+controller1 |
| core TOP_UP | `0x90b055148bb49ac7409707d8f7ed08bc881106624fd5ae5fe19ec2326954396a`, `0x85b61dca33c37f9a302365b8d3656669baf1e77b3e7c78811523062baa6ef9e4` |
| core CLOSE | `0xbc1ab01c4525aff3f5b6a5b685e476ea3db33d379d5f4060c2f317834758e949` |
| core Long LIQUIDATE | `0xc7c5be9b9de36cc6f00facfbc481204e1ae72116892e11108e84cc509f3860cd` — call1792634155/deadline1792634215/Report1792634216/Receipt1792634218 |
| core Short LIQUIDATE | `0x60a17b6c3aa09cf477066223e94ef62e780f2aa53b207ecc874ed38e8b4ddcc2` — call1792634227/deadline1792634287/Report1792634287/Receipt1792634290 |
| 두 JVM CONFIRMED 복구 | `0x47ce4ea48a828e024f5d8cbf95eb67c9629022018ef5d6dc9c1c0b3219c58b17`, relayer nonce3, 같은 raw SHA256 `e27c89f49d7010f138462138b0bf743ce3beb44763153e4c613292fe12cb41bd` |
| 사용자 취소 우선 확정 | cancel `0xfec62ea220380562fbb18fff50a00f9749177a39bec512eaadc1c1804e3891f3`; 기존 raw `0xd9d164e415541fa0ed7786e55041662aa10212e7e3c556219f0c21ef01a9aaa3` REVERTED. Receipt만으로 정확한 revert selector를 단정하지 않음 |
| REVIEW_REQUIRED 주입 | `0xb7aafa5e7ad4acbd61eed319ce74d5a3cacae781b8cd638f81de7e8cf0b4858e` — 실제 chain 성공, 응답증거 불일치로 DB 격리. 강제 정산하지 않음 |

### 정산 보존식 / 보존 확인

core 실행에서 13operation CONFIRMED, journal18행. 전체 mKRW wallet `2600000000000000000005000` wei + house `10399999999999999999995000` wei = **13000000000000000000000000 wei (13,000,000 mKRW)**. locked0 / house reserved0 / journal wallet_delta+house_delta 위반0. 원본 decimal을 반올림하지 않았다.

이 값은 core 실패 fixture를 초기화/인수 재개한 결과가 아니다. 소유 label·원 mount·정지 상태 확인 후 해당 새 PG만 잠시 시작하고 `BEGIN READ ONLY`로 조회한 뒤 다시 정지했다. 거래/DDL/정산을 추가하지 않았으며 `core-readonly-conservation.evidence.json`과 명령 로그를 별도 보존했다. 보완 fixture에는 의도된 REVIEW_REQUIRED 잠금과 정상 OPEN 담보가 남아 있으므로 이 core의 locked0를 보완 DB에 적용하지 않는다.

보호156파일 SHA256 변경0. 시작 전 기존 컨테이너345개의 ID/name/image/정지·실행 분류 변경0. 마지막 실패를 포함한 신규12개는 11개 Exited/1개 Created이며 신규 실행 중 컨테이너0. 모든 PG/Anvil state·실패 증거·이미지·network 보존, 기존/증거 컨테이너 삭제·prune 없음(배포용 일회성 `--rm`은 기존 하네스 절차). 디스크 최종 약38.99GiB. 기존 DB/chain 내용 전체를 다시 감사한 것은 아니며 기존 서비스를 기동하지 않았다.

### 마지막 환경 BLOCKED의 최소 진단

Docker Server28.4.0은 실패 후에도 정상 응답했다. Windows IPv4 excluded port 목록에는45419가 없고, `Get-NetTCPConnection`에 해당 listen 항목이 없었다. 같은 Windows Python 프로세스의 `bind(127.0.0.1:45419)`+`listen(1)`은 PASS 후 즉시 닫았다. 따라서 단순 기존 포트 점유/예약이나 전체 Docker Server 무응답으로 확정할 근거는 없다. 확인 경계는 **Docker Desktop의 Windows TCP port publishing/listen 실패**이며, 근본적인 Winsock/forwarder/자원 원인은 미확정이다.

관련 원본은 마지막 session의 `failure.json`, `logs/futures-api-14492147-anvil-20261022T051420396674.private.log`, 신규 `diagnose-bv2-publish.py`의 제한된 읽기 전용 출력이다. PG Exit137의 상세 원인을 OOM이라고 추정하지 않는다. 포트를 바꿔 반복 실행하거나 Docker/WSL/네트워크/방화벽 전역 설정을 변경하지 않았다. 단일 전체 Gate는 환경 정상화 확인 후 새로운 identity에서 재개해야 한다.

### 독립 검토와 남은 일

`review_bv2_live_gate` 최초 검토 및 두 차례 수정 재검토: trigger scope / 60초 증거 / sourceHash 귀속·누락 검증을 지적했고 보완했다. 마지막 코드검토 결론은 **발견된 필수 수정 없음(검토 범위)**, 검토자 별도 실환경 중복 실행 없음. 그 후 실행으로 확인된 wrong-contract 테스트 객체 수정과 `tail` 분할은 구현자 자체 점검 및 실제 두 JVM PASS로 검증했다. 이 마지막 소규모 테스트 변경을 추가 독립 재검토했다고 표현하지 않는다.

별도 `audit_bv2_gate_evidence`는 코드 수정 재검토와 구분한 읽기 전용 실행 증거 감사를 수행했다. core 시간·정산식, tail 두 JUnit 집계·raw/hash/nonce·eth_call/spy/cancel-first 경계와 마지막 Docker 포트 실패를 원본 정제 증거/로그로 대조해 수치와 분류가 일치함을 확인했다. 테스트·서비스·DB를 새로 실행하거나 수정하지 않았다. 최종 컨테이너/용량 계측은 구현자 실행으로 구분한다.

제품 권한·금융 코드 결함으로 확정된 미해결 실패는 이번 실행에서 없었다. 그러나 다음은 전체 인수 완료로 넘기지 않는다.

1. 한 번의 전체 시나리오가 최신 테스트 버전에서 끝까지 PASS한 증거는 없음. core 개별 증거와 새 보완 실행2PASS를 합쳐 판단했으며 실패 JUnit은 그대로 FAIL이다. 마지막 full-run은 Docker 포트 게시에서 BLOCKED로 종료했다.
2. 동시 mempool 양방향 cancel/execute 경합, 세 runtime 혼합 실제 인수, 악성 서명/CLOSE의 별도 채굴 실패 tx 확대는 NOT_RUN.
3. 구조화된 v1 migration fixture를 실제 기존 사용자 DB의 의미적 백업/복구 인수로 확장하지 않음.
4. 최종 작은 테스트 분기 변경의 추가 독립 재검토, 전체 감사 coverage 및 웹 지갑 인수는 후속. 새로운 제품 기능을 자동 시작하지 않는다.
