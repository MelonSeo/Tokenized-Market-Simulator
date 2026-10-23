# B-v2 실제 서버 RPC 장애 진단 및 복구 판단

> 2026-10-22. [MetaMask 인수 누적](dapp-b-v2-metamask-repricing-acceptance-2026-10-22.md)의 후속. 사용자는 실제 Spring Boot 클라이언트의 원인 조사와 필요한 최소 수정을 승인했다. 추가 사용자 서명·버튼 조작·기존 거래 재전송은 하지 않는다. 현재 문서는 진행 기록이며 최종 결과를 아래에 누적한다.

## 1. 실제 장애와 사용자 상태

- 사용자 캡처의 controller는 실제 새 browser fixture와 일치한다. 주소/네트워크 오선택이나 사용자 실수로 단정하지 않는다. 지갑 주소/chainId 표시는 서버 지갑 연결 완료 증거가 아니다.
- 실제 서버 PID1428에서20:28:51,20:29:05,20:44:57 KST에 CHAIN_ID/DEPLOYMENT TRANSPORT_TIMEOUT 약3018~3034ms 관측. 원래 서버의 첫 eth_chainId 단계 실패는 확인했지만 당시 HTTP connect/header/body 중 어느 단계인지는 기존 로그로 복원할 수 없다.
- 정지 전 SQL READ ONLY: users1, walletLinks0, positions0, operations0, transactions0, Faucet0, locked0, unresolved0, house10,000,000 mKRW. 사용자 계정 DB는 보존하며 자산 초기화/계정 삭제/거래 재전송은 하지 않았다.
- 기존 browser session `phase-a-20261022105446-923beac2/futures-browser-2217180b`는 소유 STOP으로 정상 정지(parent exit0). `browser-stop.json` STOP_FILE/preserveData=true. 원래60분 상한도 임박한 상태였다. 자기 web/backend/PG/Anvil만 정지하고 컨테이너/데이터/증거는 보존했다. Google/KRX/AI 등 공유 서비스는 재시작하지 않았다.
- 기존 계정이 새 진단 DB로 옮겨졌다고 주장하지 않는다. 이후 새 환경의 시험 계정은 구분한다. 기존 계정으로 즉시 재로그인 가능한 상태와 DB 자체 보존은 다른 판정이다.

## 2. 실제 프로세스와 비교 JVM

| 항목 | 기존 실제 서버 | 별도 비교 JVM |
|---|---|---|
| Java |21.0.10, jcmd에서 직접 확인|같은 JDK21.0.10|
| IPv4/IPv6 |preferIPv4Stack=true / preferIPv6Addresses=false|동일|
| HTTP(S) proxy |127.0.0.1:1, loopback nonProxyHosts 예외|동일 clean child 설정|
| call timeout |소스상3초, 실제 실패 약3초|동일3초|
| 클라이언트 수명 |Spring context에서 생성 후 오래 유지|새 JVM/새 client|
| 관측 |eth_chainId timeout|connect DIRECT→request body 전송→HTTP200|

20:45 KST 실제 서버 PID1428은 살아 있었고 working set 약290MB/handles955였다. 이 사실만으로 시스템 메모리 문제를 배제하거나 확정하지 않는다. 애플리케이션 소스에서 런타임 ProxySelector 변경 코드는 발견하지 못했다.

별도 JVM `rpc-comparison-20261022T114631742002.log`: chainId44ms, verifyDeployment369ms, EOA3ms, nonce7ms PASS. 실제 direct loopback 연결 및 HTTP 단계가 기록됐다. **별도 JVM 성공은 기존 서버 클라이언트 정상이나 원인 확정의 증거가 아니다.**

## 3. 최소 코드 보완 — 전송 정책 변경 아님

- 신규 `FuturesRpcHttp.java`: B-v2에만 OkHttp EventListener.Factory를 연결한다. per-call UUID, 마지막 HTTP 단계, 같은 factory에서 관측된 연결 재사용 여부, 실제 proxy 종류, elapsed 및 고정 오류 분류만 기록한다.
- `AuthorizedV2Config.java`: 기존 `new OkHttpClient.Builder().callTimeout(3s).build()` 대신 같은 기본 설정+관측 factory를 사용한다. timeout/connection pool/proxy selector/금융 정책/Worker/raw/TTL은 변경하지 않는다.
- 연결 재사용은 Connection 객체 재관측을 뜻한다. 실제 원격 연결의 건전성을 보장하거나 특정 idle 오류의 원인이라고 해석하지 않는다.
- 단계는 CONNECT_START/CONNECTED/CONNECTION_ACQUIRED/REQUEST_HEADERS/REQUEST_SENT/WAIT_RESPONSE_HEADERS/RESPONSE_HEADERS/RESPONSE_BODY 등 고정값이다. URL/HTTP headers/body/JWT/개인키/서명/raw/예외 원문/stack은 기록하지 않는다.
- 실제 새 서버 설정 로그: call3000ms/connect10000ms/read10000ms/write10000ms, connectionRetry=true, proxyMode=SELECTOR, default pool. `connectionRetry=true`는 **기존 OkHttp 기본값**을 공개한 것이며 이번에 자동 재전송을 추가하거나 거래를 실행한 것이 아니다. 이 설정을 원인 근거 없이 바꾸지 않았다.
- HTTP call ID와 상위 `futures_stage` trace는 별개다. 시간/실행 thread와 RPC stage를 함께 대조해야 하며 요청 전 생애가 단일 ID로 연결됐다고 주장하지 않는다.
- 신규 `FuturesRpcHttpTest`3개: 기존 설정과 동등성, timeout의 마지막 단계/비밀 원문 차단, factory별 재사용 및 connect 실패 구분. EventListener callback의 분류 테스트이며 실제 Windows TCP 장애 재현 시험이 아니다.

## 4. 같은 실제 Spring Boot 내부 비교

private `BrowserBv2Server.java`에 **새 소유 fixture의 명시적 진단 모드만** 추가했다. 새 DB owner 확인 뒤 실제 V2Runtime의 Gateway를 사용한다. 동일 JVM에서 같은 client 호출→일정 유휴→같은 client 호출→새 client 호출을 고정1회씩 비교한다. 금융/API/DB mutation RPC는 호출하지 않는다. 운영 REST debug endpoint나 임의 코드 실행 API는 만들지 않았다.

새 `phase-a-20261022115857-4f02ccb6/futures-browser-46da9457` 실제 Spring PID23300:

- 같은 client 유휴 전 deployment46ms.
- 같은 client 6500ms 유휴 후 deployment583ms.
- 같은 JVM 새 client deployment46ms.
- 실제 HTTP 로그에서 기존 connection 재사용=true 및 응답 완료 확인. 모든 probe가 성공했고 뒤이어 controlled HTTP 소유증명/nonce/draft PASS. 이 fixture의 시험 scalar는 공개 시험용이며 사용자 지갑의 독립 통제 증거가 아니다. 거래 서명/submit은 하지 않았다.
- 이 실행 productHash `5fa26d0a2d088b5e30f628abfea49fc30040a8b78e3569834663eac77efc537a`. 이후 실제 proxy 종류 관측 보완은 다음 별도 실행으로 구분한다.

583ms와46ms의 차이는 관측값이지 연결 재사용이 원인이라는 인과 증명이 아니다. 오래 유지한 client가 항상 실패하지 않는다는 반례도 확보했다. 최초 문제가 더 긴 유휴 뒤 발생했으므로, default pool 유휴 정리 구간을 넘는366000ms 비교를 새 격리 fixture1개에서 추가 진행한다. 기존 fixture를 재사용하거나 실패 요청을 자동 반복하지 않는다.

유휴 시험은6500 또는366000ms만 허용하고 startup deadline도 해당 구간에 한정한다. 각 verify는 기존3초 call timeout을 유지한다. 실패하더라도 결과를 보존하고 같은 JVM fresh client의 고정 비교만 수행하며, 실패가 있으면 사용자/controlled HTTP 호출 전에 종료한다.

## 5. 원인 판정 기준

- 확인: 실제 서버의 첫 chainId 요청이 deadline에 도달했다. 비교 JVM/새 실제 Spring context는 정상 응답했다. 기존 사용자 환경의 계정/거래 상태와 서버 JVM flags를 확인했다.
- 아직 미확정: 기존 실패가 connect/read/응답 body/idle pool/Windows native network 중 어디서 시작됐는지. 연결 재사용, proxy 또는 JVM 장기 상태 중 무엇이 근본 원인인지.
- 풀을 끄거나 timeout을 늘리거나 프로세스를 바꾸면 일시적으로 성공한다는 이유만으로 원인 해결을 선언하지 않는다. 원인 불확실 상태에서 금융/시장/서명 검사를 완화하지 않는다.
- 새 실제 서버의 무과금 RPC 정상 응답과 기존 사용자 웹 거래 재개 가능 판정은 구분한다. 새 계정/새 네트워크가 필요한 환경을 기존 계정 복구라고 표현하지 않는다.

## 6. 회귀·검토·보존

- 첫 전송 관측 구현: compile PASS, 선물 직접 JUnit178 PASS/12 SKIP/0 FAIL (`futures-20261022T115840854696.log`). 이 실행은 실제 proxy 종류 보완 전이며 최종 회귀는 아래에 추가한다.
- 사용자 추가 서명/개인키 요청/새 사용자 주문/기존 거래 재전송/유료 API 없음. 원본/UI/AI worktree와 과거 ledger/STOP/claim, DB/chain/capture를 보존한다.
- 이번 작업은 contract/DB schema/금융 산식/재가격 정책을 바꾸지 않는다. commit/merge/push/외부 배포/전역 Windows·Docker·WSL 재시작 없음.
- 실제 서버 RPC의 재발 방지 완료와 MetaMask 거래 재개 여부는 최종 관측 및 독립 검토 후 판정한다.

## 7. 최종 실행 결과 및 재개 판단

새 `phase-a-20261022120302-0d69b589/futures-browser-03b0a59c`, 실제 Spring PID21880. HEAD `f9353c33244565b30fb130dfb6159eab441a3523`, productHash `fd29bef4fbc32cb2ab9f2ad4785556727361de2373609840e2e310ed0d9ec7f1`.

| 같은 Spring JVM의 deployment 검증 | 결과 | 전체 소요 |
|---|---|---|
| 실제 Gateway, 유휴 전 | PASS |32ms|
| 같은 Gateway,366초 유휴 후 | PASS |2805ms|
| 같은 JVM의 새 client | PASS |91ms|

위 시간은 **여러 RPC로 구성된 deployment 검증 전체**이며 단일 요청의3초 call deadline과 직접 같다고 해석하지 않는다. 전체 차이만으로 pool/Windows/proxy가 원인이라고 확정하지 않는다. 각 HTTP의 실제 proxy=DIRECT 및 RESPONSE_BODY 완료를 확인했다. 유휴 후 신규 연결/재사용 여부는 각 call 로그 단위로 읽어야 한다.

21:10:38 KST 유휴 직후 첫 HTTP는2595ms, `reused=false`, `proxy=DIRECT`, RESPONSE_BODY/OK였다. 이후 같은 연결 재사용 요청은 성공했다. 따라서 이번 긴 지연을 단순히 오래된 연결의 재사용 실패라고 단정할 수 없다. 성공 로그는 마지막 단계와 전체시간이므로2595ms 중 connect와 response 대기의 시간 비율까지 측정했다고 주장하지 않는다.

- 이어 실제 HTTP signup201, me200, wallet challenge200/link200, nonce200, OPEN draft200 PASS. `rpc-server-observation.json`은 공개 시험 signer의 통제 경로이며 사용자 MetaMask 인수가 아니다. 사용자 거래 서명/submit/전송은0회다.
- `in-process-rpc-comparison.json`, `backend.private.log`, `rpc-server-observation.json` 및 before/after inventory는 새 private fixture에 보존했다. 부모 실행 exit0, 자체 backend/PG/Anvil 정지·데이터 보존.
- 최종 직접 JUnit `futures-20261022T120454970934.log`: **178 PASS/12 SKIP/0 FAIL**, compile PASS (`classpath-20261022T120202780709.log`). 일반 Gradle test-worker 전체 회귀와 구분한다. 웹/Foundry는 이번 변경 대상이 아니며 이번에 재실행하지 않았다.
- 보호 파일156개 SHA-256 변경0. 짧은/긴 진단 전 기존 컨테이너369개/371개의 ID·이름·이미지·상태 분류·종료코드 변경0. 각 진단의 새2개 컨테이너만 Exited(0), 삭제 없음. 이는 컨테이너 보존 검사이며 모든 기존 DB 행/체인 상태를 새로 전수 검증했다는 뜻은 아니다.
- 독립 검토 `review_bv2_rpc_recovery`: 소스/테스트/private launcher/초안 직접 확인, 발견된 필수 수정 없음. 검토자가 직접 테스트를 실행한 것은 아니다. 별도 비교 JVM은 실패해도 exit0일 수 있으므로 exit code만이 아닌 명시적 단계 PASS를 근거로 판정했다.
- 최종 문서 재확인에서도 정제된 실제 comparison JSON과 §7/누적 기록을 대조해 발견된 필수 수정 없음. 직접 테스트 재실행은 없었다.

**최종 판정: 새 실제 서버 RPC 및 무과금 지갑 연결/draft 회귀 PASS. 과거503 근본 원인과 재발 방지 완료는 미확정, 실제 MetaMask 거래 재개는 보류(BLOCKED).** 추가한 것은 최소 관측 보완이지 입증된 네트워크 원인 수정이 아니다. 새 client 성공만으로 pool을 비활성화하거나 timeout을 늘리지 않았다. 기존 사용자 계정 DB는 정지 보존 상태이며 새 시험 계정으로 대체하지 않았다. 기존 미확정 거래0이므로 재전송 대상도 없다.

다음 인수에는 관측 코드가 포함된 사용자용 bounded 실행 환경과 실제 서버의 idle 후 RPC/지갑 귀속 사전검사가 필요하다. 기존 정지 계정 환경을 재개한다면 현재 제품 identity와 기존 manifest guard를 먼저 검토하고, 구버전 fixture를 최신 소스로 조용히 재시작하지 않는다. 기존 계정/체인 유지와 신규 환경 선택을 혼동하지 않는다. 현재 사용자에게 버튼·재서명·네트워크 변경을 요청하지 않으며, Q12/AI/기존 성공 OPEN 증거에도 변경이 없다.

## 8. 관측하 제한 인수 후 재발 — 과거 결과와 구분

사용자가 제한 재개를 승인해 새 `phase-a-20261022122803-8f9ec7af/futures-browser-2557294e`를 생성했다. 같은 productHash이며 PID30684, actual Spring366초 idle25/153/43ms와 실제 웹 proof/nonce/draft, DB pending/locks0 Gate를 통과했다. 사용자 MetaMask OPEN은 revision1로 실제 canonical/DB CONFIRMED가 됐다. 상세 수치와 증거는 [총괄 진행 문서](dapp-development-progress.md)의 실제 OPEN 항목에 누적한다.

그러나22:09~14 KST 실제 Worker에서 DIRECT/new·reused 양쪽 HTTP의 REQUEST_SENT timeout3초가 재발했다. jcmd의 해당 Worker stack은 `FuturesRpcGateway.height`/eth_blockNumber에서 `Http1ExchangeCodec.readResponseHeaders`→`NioSocketImpl.timedRead`였다. 이전과 달리 **응답 header 읽기 대기 경계**를 확보했지만 왜 응답이 도착하지 않는지는 미확정이다. 성공1~3ms도 번갈아 관측돼 일시 정상 응답을 복구 완료로 사용하지 않는다.

별도 Windows Receipt reader도4초 timeout, 내부 Anvil의 read-only Receipt/tx/block 대조는 성공했다. 별도시점 Windows native curl/Python직접TCP/내부chainId 각1회 비교도 성공했다. 서로 다른 시점·RPC method라 Spring/Windows게시포트/Anvil 중 하나를 단독 원인으로 확정할 수 없다. Vite backend연결 EFAULT도 별도 관측돼 관련성을 추가 조사할 근거일 뿐이다.

TOP_UP/CLOSE는 보류. 현재 사용자 OPEN은 정상 담보100000/미확정operation0으로 보존한다. 무단 CLOSE·재전송·금융정책완화·timeout증가·공유서비스재시작 없음. 사용자 추가 서명은 RPC재개 Gate 이후에만 안내한다. 별도 private read-only 도구와 실제 canonical 증거의 독립 검토에서 필수 수정 없음이며 전체 금융 회귀를 이번에 재실행한 것은 아니다.
