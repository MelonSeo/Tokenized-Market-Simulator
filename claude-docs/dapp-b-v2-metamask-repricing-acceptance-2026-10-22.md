# B-v2 실제 MetaMask 재가격 인수 — 환경 준비 및 수동 인수 누적

> 2026-10-22. [단일 재가격 구현](dapp-b-v2-single-repricing-implementation-2026-10-22.md)의 후속. 기존 실제 MetaMask OPEN과 통제된 provider의 재가격 PASS 증거는 보존한다. 이번 제품 Synthetic/MetaMask 인수와 혼동하지 않는다.

## 기준과 보호

- worktree `feat/dapp-user-authorization`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523`. 제품 변경 없이 실제 PostgreSQL revision 경합 시험과 새 브라우저 환경 준비를 추가한다.
- 기존 원본/UI/AI worktree의 dirty 파일, 사용자 승인/평가 ledger·STOP·claim, 기존 fixture와 DB/Anvil은 변경하지 않는다. 새 시험 fixture와 새 사용자 브라우저 fixture는 서로도 분리한다.
- 기존 RPC503/Vite EFAULT 근본 원인은 미확정이다. 전역 네트워크/WSL/Docker 변경, 기존 서비스 중지/정리, commit/merge/push/유료 API/외부 배포 없음.

## 사전 검사

- 동일 Node24.4.1/IPv4 `127.0.0.1:30585`: shell HTTP24ms / clean HTTP18ms / clean Vite448ms, 각1회 PASS. 포트 fallback/반복 시도 없음.
- Docker Server28.4.0 응답. Windows CIM 실제 C 여유39,290,605,568 bytes(약36.6GiB). sandbox의 Get-PSDrive 값은 유효한 호스트 용량으로 취급하지 않았다.
- 새 컴파일 PASS `classpath-20261022T105106542475.log`. 이번 추가는 PG 시험/launcher web identity 기록이며 main source 변경 없음.
- 웹 신규 실행146 PASS/0 FAIL, build PASS. 선물 신규 회귀 및 실행별 결과는 아래에 누적한다.

## 실제 PostgreSQL 경합 Gate

새 session `phase-a-20261022105207-f8db6e49`에서 진행한다. 기존 STOP fixture를 재사용하지 않는다.

시험은 두 thread가 같은 PREPARED/old Report로 revision을 요청하여 정확히1개만 승리하는지 확인한다. revision2행/RESERVE1개/REPRICE1개와 기존 잠금을 대조하고, 패배한 worker의 old Report 조건부 rejection 및 old raw의 saveSigned를 거부하는지 검사한다. 별도 시험 계정에서 SIGNED를 먼저 영속화한 뒤 RevisionStore 직접 변경이 거부되고 raw/hash/nonce가 보존되는지도 확인한다.

이 시험은 실제 PG row lock/CAS와 신규 Anvil identity를 사용하되, 소유 시험 signer의 controlled 데이터다. 사용자 MetaMask 성공 또는 채굴 거래의 성공으로 표현하지 않는다. 시험 raw는 네트워크에 broadcast하지 않으며 해당 fixture를 종료할 때 데이터/서명 증거를 private 위치에 보존한다.

최종 결과·fixture identity·독립 검토는 아래에 누적한다.

- 실제 fixture `futures-api-028be39e`, PG36231/RPC41039. `run-bv2-gate.py race` 첫 JVM1 PASS/0 FAIL, parent exit0.
- 정제된 `gate.evidence.json`: concurrentWinners1, active revision1, reservationAndJournalSingle=true, staleRejectBlocked=true, oldSaveSignedBlocked=true, signedFirstRevisionBlocked=true, noBroadcast=true.
- 새 PG에서 실제 동시 row lock/CAS, 이전 raw의 최종 saveSigned 차단, 패배한 Worker의 오래된 Report rejection 차단, 다른 시험 계정의 SIGNED 선행 후 revision 거부를 확인했다. 사용자 개인키가 아닌 소유 시험 signer를 사용했다.
- 새 선물 직접 JUnit `futures-20261022T105302876438.log`:175 PASS /12 SKIP /0 FAIL. 최종 재가격15개 포함. 기존 전체 backend725 PASS는 이전 실행으로 구분하고 이번 전체 회귀 숫자로 재사용하지 않는다.
- 시험 fixture는 자기 프로세스 종료 후 PG/Anvil을 정지하고 데이터/증거를 보존했다. 다음 사용자 브라우저 환경과 DB/체인을 공유하지 않는다.

## 제품 Synthetic + 사용자 MetaMask 환경

PG 경합 Gate와 실행 사전조건이 통과하면 기존 launcher로 **별도 신규** 브라우저 환경을 만든다. 이 환경은 main classpath만 사용하고 시험 MarketPriceProvider를 주입하지 않는다. 제품 Synthetic의1초 commit tick, 실제 Worker/API/웹 proxy를 사용한다. Google/AI/실제 증권 공급자는 비활성이다.

webSourceHash는 `src/**`, index.html, vite.config.js, package.json, package-lock.json을 경로+NUL+내용으로 정렬 SHA-256 계산한다. productHash와 구분하여 새 ready manifest에 기록한다. 과거 manifest를 수정하거나 해시 guard를 우회하지 않는다.

신규 배포/실제 RPC identity, URL/chainId/controller/rules와 source hash를 확정한 뒤 사용자에게 네트워크 등록을 안내하고 멈춘다. 개인키/시드/지갑 비밀번호를 요구하지 않는다. EIP-191/EIP-712 서명은 사용자가 직접 하며 서버는 받지 않은 키를 생성한 것으로 주장하지 않는다. 사용자 가스 거래가 아닌 relayer 거래이므로 소유증명/주문 서명만을 위해 개인 지갑에 실제 자산을 보낼 필요가 없다.

브라우저 launcher의 STOP 또는60분 상한은 소유한 web/backend/fixture만 정지한다. parent loop 기반이며 독립 watchdog으로 과장하지 않는다. 기존 fixture를 자동 재기동하지 않는다.

### 실행된 신규 환경 — READY / 사용자 네트워크 등록 대기

- session `phase-a-20261022105446-923beac2`, fixture `futures-browser-2217180b`. Parent 실행 세션은 계속 살아 있고 최대60분 제한이다.
- 웹 `http://127.0.0.1:41274/#dapp`, RPC `http://127.0.0.1:31997`, chainId31337(hex0x7a69), 통화 ETH. backend45752, 새 PG33734. 실제 개인 자산/개인 네트워크가 아닌 이 신규 로컬 Anvil용이다.
- productHash `fb429106b70a1ea444985e7b00f7b70a034763b070f270aa9faad7637de5d8b3`, webSourceHash `a9f621ee38d622184768f61b9581766df0a90b55aaceff42318239f8073fb7b1`.
- controller `0xCc25F99dE91A0e0d7DDaa7294556fB7A3185865D`, codeHash `0x607cbbca22a78e124705ffd552ea71c0167fcf0f65965e150e69002e5b59ca4a`.
- rules `0x857f4C9442261e1eeA91075ec06848e1A56BE9Ea`, codeHash `0xcc0df04f0660bc1e740e0629fa2e692ea54530a7c44cfd66df2de91bed1c6888`.
- 실제 RPC getter: controller.rules=rules, rules.executor=controller, controller.MAX_USER_TTL=120. relayer `0x2abd35ec22c35a427e2d868860fd23385bea7bfa`, priceSigner `0x2fdb72a1f3f9eb2330a8442c57c1cc24ce62ae02`. 서버 startup에서도 새 배포 identity와 key 귀속을 검증했다. 사용자 EOA와 구분한다.
- `verify-browser-ready.py`의 새 read-only preflight PASS. chainId/codeHash/immutables/DB runId/웹proxy 시장 응답 확인. 준비 시점 users0/positions0. 실제 제품 provider SIMULATED/priceStatus SIMULATED/marketStatus OPEN, 관측2026-10-22T10:57:41.097Z, 당시가격74954.69483187. 과거 가격을 현재 유효 견적으로 재사용하지 않는다.
- 준비 시점 backend `futures_stage.*FAIL`0. 이는 과거503 해결 보장이나 사용자 거래 성공이 아니다. preflight는 로그인/서명/거래를 만들지 않았다. 새 `browser-preflight.json` 및 `browser-ready.json`에 정제 증거를 보존했다.

사용자에게 새 RPC 등록/기존31337 테스트 네트워크의 RPC 변경을 안내한 뒤 멈춘다. 웹은 일반 Chrome+MetaMask로 열어야 하며 로그인과 서명은 이후 단계에서 직접 수행한다. block explorer URL은 비워 둔다. 키/seed/비밀번호를 서버에 제공하지 않는다.

## 수동 인수 상태

| 단계 | 현재 판정 |
|---|---|
| 신규 MetaMask 네트워크 등록 | NOT_RUN / 사용자 대기 전 |
| 신규 JWT 로그인·EIP-191 지갑 연결 | NOT_RUN |
| 실제 사용자 EIP-712 OPEN | NOT_RUN |
| 실제 사용자 EIP-712 TOP_UP | NOT_RUN |
| 실제 사용자 EIP-712 CLOSE | NOT_RUN |
| canonical3events/최종 활성 revision/DB settle | 사용자 거래 이후 대조 |
| 전체 B-v2 감사 | INCONCLUSIVE / 범위 밖 |

각 거래는 사용자 완료 알림만으로 PASS하지 않고 operation/txHash, active revision, original Intent 범위, Receipt와 blockHash, DB 담보·house·journal을 읽기 전용으로 대조한다. revision1 이후 PRICE_CHANGED는 안전 거절로 구분하고 불확실 operation이 있으면 다음 거래를 안내하지 않는다. 이전 성공 거래/시험 Gate를 새 사용자 거래 성공으로 대체하지 않는다.

## 준비 단계 독립 검토와 보존 결과

- `review_bv2_metamask_reprice_readiness`: 신규 PG race 코드/실제 evidence 및175/12/0 로그, launcher web hash, read-only ready helper와 실제 ready/preflight JSON을 직접 대조해 **발견된 필수 수정 없음**. 검토자가 테스트를 재실행한 것은 아니다. thread 동시 시작과 CAS 결과를 확인했지만 모든 lock-wait/interleaving을 강제로 검증했다고 주장하지 않는다.
- 보호156개 hash 변경0. PG 시험 이전365개 및 브라우저 시작 이전367개 컨테이너 ID/이름/이미지/상태 분류·종료코드 변경0. 시험 새PG/Anvil은 정지·보존, 브라우저 새PG/Anvil2개는 사용자 인수 대기를 위해 실행 중이다.
- `git diff --check` PASS. 실제 사용자 네트워크 등록/로그인/서명/OPEN/TOP_UP/CLOSE는 아직 NOT_RUN이며 네트워크 등록 안내 후 대기한다.

## 사용자 연결 진행 후 확인 — 2026-10-22 20:30 KST 전후

- 사용자가 기존 MetaMask 네트워크 RPC31997/chain31337 등록을 완료했다고 보고했다. 호스트 read-only RPC는0x7a69, 웹 시장 SIMULATED/OPEN 응답을 확인했다.
- 사용자는 지갑 소유증명 연결 완료를 보고했으나, 이 신규 DB 읽기 전용 확인에서는 users1/links0/challenge1·consumed0·expired1, positions0/미확정operation0/Faucet0이었다. 사용자 보고와 DB 연결 완료 증거가 일치하지 않아 연결 PASS로 처리하지 않고 추가 거래 안내를 보류한다.
- 실제 backend PID1428 로그에서20:28:51 DEPLOYMENT TRANSPORT_TIMEOUT3030ms,20:29:05 CHAIN_ID TRANSPORT_TIMEOUT3018ms를 확인했다. 해당 요청의 chainId 단계 timeout 경계는 이번에 확보했지만 네트워크/Engine/JVM의 근본 원인은 미확정이다. 호스트 RPC 성공을 서버 성공으로 대체하지 않는다.
- 사용자의 실제 접속 URL과 현재 안내 문구를 확인할 필요가 있다. 다른 탭/환경 문제나 사용자 실수로 단정하지 않는다. 새 서명/새 주문/자동 재시도·timeout 변경·환경 재시작 없음. 현재 새 사용자 포지션/거래는 아직 생성되지 않았다.

## 실제 서버 RPC 조사 후 상태 — 2026-10-22

- [서버 RPC 복구 조사](dapp-b-v2-rpc-server-recovery-2026-10-22.md)에 실제 JVM 비교, 안전 관측 보완, 새 Spring 내부6.5초/366초 idle 시험과 회귀 결과를 누적했다. 새 실제 서버의 지갑 연결/nonce/draft HTTP PASS이나 과거503 근본 원인은 미확정이다.
- 위 READY URL은 더 이상 실행 중이 아니다. 원래60분 상한 직전 소유 STOP으로 정지했고 계정/DB/chain/evidence를 보존했다. 정지 전 users1, links0, positions/operations/transactions/locked/unresolved0. 사용자 보고만으로 연결/OPEN PASS를 기록하지 않았다.
- 새 진단 환경은 사용자 계정을 옮기지 않은 별도 통제 시험이다. 실제 MetaMask OPEN/TOP_UP/CLOSE는 이 인수에서 여전히 NOT_RUN, 사용자 추가 조작 안내 없이 재개 보류. 기존 별도 성공 OPEN 증거는 그대로 보존한다.
