# Google 인증 최종 마무리·커밋·통합 — 진행 중

> 2026-10-17. 이번 승인: 남은 실제 인증 경계 확인 후 Google/전량매도를 구분해 로컬 커밋·melon-init 통합·병합 후 새 회귀. 원격push/외부배포/기존환경초기화 금지.

## 시작 상태와 보존

- 원본 루트 `feat/google-auth`, HEAD/원본 `melon-init` 모두 `a98ea4b47fdb66484efdc31d66ffddc4f2b75085`다. 현재 feature는 기준에서 아직 커밋하지 않은 Google 인증+전량매도 구현이며, 이전 독립 검토·실행 보고서와 대조한다.
- 별도 integration-worktree는 `feat/kiwoom-live-fixes`/`2fb8cd6`이며 기존 KRX 인수의 경로·source/image guard를 위해 보존한다. 어떤 worktree도 제거하지 않는다.
- 미추적 `kiwoom-nxt-aftermarket-observation-2026-10-16.md` 및 implementation-log의 NXT 후속 섹션은 별도 사용자 변경이다. Google/전량매도 커밋에 섞지 않는다. private runtime/실제.env/키/DB·chain/capture/로그는 Git 제외한다.
- Docker Server28.4.0 응답, 기존 Google5173/구playground25173/기존KRX PG·Anvil의 실행을 읽기 전용 확인했다. 기존 컨테이너는 정지/초기화/재배포하지 않는다.

## 새 격리 환경의 필요성과 준비

전량매도 제품 소스가 추가되어 기존 Google run의 source hash와 달라졌다. 기존 manifest/image guard를 갱신하거나 check를 끄지 않는다. 기존5173도 사용 중이므로 최신 소스로 별도 **http://localhost:5174** 환경을 준비한다. 동일 Google OAuth Web Client ID를 사용하되 Google Cloud 승인된 JavaScript 원본에5174를 사용자가 추가해야 한다. 기존5173 원본과 `.env`는 변경하지 않는다.

ignored private 준비 adapter의 변경은 명시적5173/5174 localhost origin 선택과 새 run 전용 Origin/port 반영, 시작 직후 ConnectionError의 bounded readiness 처리뿐이다. 기존 source/image/서비스 label/주요 mount 소유권 검사는 그대로다. 고유 이름·라벨을 가진 전용 network를 새로 생성하지만 `owned()`에 network membership/owner 자동 검사가 있다는 의미는 아니다. 새 unique image tag·DB·persistent Anvil·키·secret·포트·baseline을 사용하며 구environment pointer를 수정하지 않는다. 실제 Google SDK/ID Token 검증은 사용자 명시 조작 때만 수행하고 Toss/Kiwoom/AI는 비활성이다. 새 run ID와 실제 준비 결과는 완료 후 추기한다.

### 실제 신규 준비 결과

- 새 run `google-live-20261017083610-9b6c788e`, Docker project `exchange-google-live-20261017083610-9b6c788e`.
- 웹 `http://localhost:5174`, backend loopback62461→8082. 새 PG/Anvil은 host port를 공개하지 않는다. 독립 persistent mount/network/지갑과 고유 backend/web/tools 이미지, 신규 local 계약과 최초 baseline을 생성했다. 구 DB/키/state를 복제하지 않았다.
- `prepare --origin http://localhost:5174` 정상 종료/ready=true, `status --run ...`에서 현재 source/image/mount ownership guard 통과, PG/Anvil healthy·backend/web running, Google config enabled=true 확인. 로컬 health/config와 baseline API만 사용했으며 실제 Google SDK/JWKS/계정 로그인은 아직 실행하지 않았다.
- 공개 Google 설정3개만 기존 `.env`에서 필터링해 가져오고, allowed origin은 **새 run 전용**5174로 준비했다. Client ID/키/token/비밀번호 값은 출력하지 않았다. 기존 `.env`·5173 secret·manifest는 수정하지 않았다.
- Google Cloud의 추가5174 origin은 사용자 설정 대기다. 로컬 enabled=true/config 일치만으로 Cloud 등록 완료나 실제 로그인 PASS를 추정하지 않는다. 기존5173 origin을 삭제할 필요 없다.
- UTC08:47:19 새 PG의 USER 조회0건, 웹 Google 로그인/연결 관련 정제HTTP기록0건을 확인했다. baseline 생성에 사용한 테스트 ADMIN만 선행하며 신규 실제 인증·사용자 지급·거래를 아직 실행하지 않았다. query는 허용된 NULL/연결 boolean·수량·건수만 선택했고 원문 인증정보를 조회하지 않았다.
- 별도 읽기 전용 `docker network inspect`와4개 service inspect에서 network label owner 일치, 연결된컨테이너4개/예상서비스만존재, 각서비스가새전용network에만연결됨을 확인했다. 이는 이번 관측 결과이며 기존 adapter에 network guard가 구현돼 있다는 주장은 아니다.
- build/준비는 이번 실행의 새 bootJar·Vite build이며, 이전495/38·웹107·Foundry36 결과를 이번에 재실행한 것으로 기록하지 않는다. 병합 후 회귀는 여전히 NOT_RUN이다.
- 준비 단위 독립 검토 `review_google_final_preparation`: 최초 문서1건(network 자동guard 범위 과장)을 수정한 후 **발견된 필수 수정 없음**. 검토자는 소스·문서 읽기만 수행했고 직접 테스트/Docker/private/실제Cloud 확인은 하지 않았다. 실제 수동 인수와 최종 커밋 검토의 완료를 뜻하지 않는다.

### 사용자 5174 접속 실패 후 재확인 — BLOCKED

준비 완료 이력 이후 사용자가 접속 실패를 알렸다. 재진단에서 Docker Desktop Linux Engine의 `/version` 및 대상 container 목록 API가500 Internal Server Error를 반환했다. host5174 listener는 존재하지만 localhost/127.0.0.1의 HTTP 요청은 제한시간 안에 응답하지 않았다. 따라서 준비 당시 PASS를 현재 가용성 PASS로 사용하지 않는다. 실제 Google 로그인 전 단계의 로컬 실행환경 장애이며 Google Origin 설정이나 제품 코드가 원인이라고 단정하지 않는다.

bounded read-only probe만 추가했고 Docker/WSL 전체 재시작·컨테이너 조작·DB/chain 초기화·재배포는 하지 않았다. Engine 복구에는 기존5173/KRX/playground 연결까지 중단될 수 있는 전역 조치가 필요할 수 있어 사용자 확인 전에 실행하지 않는다. 남은 실제 인수·커밋·통합은 대기한다.

이후 사용자 Docker Desktop 재시작 승인을 받아 공식 `docker desktop restart --timeout 120`을 실행했다. Desktop status는stopping이었고, Desktop/backend/build/WSL 관련 프로세스가 종료되지 않아 `Failed to stop Docker Desktop`/`context deadline exceeded`로 실패했다. 정상 재시작 완료로 기록하지 않는다. 강제종료·WSL전체종료·데이터삭제는 하지 않았다. 이어 표준 `docker desktop start --timeout 30`은already running을 반환했으나 localhost5174는여전히HTTP시간초과였다. 표준기동메시지만으로Engine복구를주장하지않으며 남은인수는BLOCKED다.

사용자가 Docker 전용 프로세스 강제종료를 추가 승인했다. 설치된 Docker 경로 allowlist와 직전 PID/path를 확인해 Desktop4/backend2/build1 프로세스만 강제종료했다. WSL 프로세스 종료·배포판 reset/unregister·파일/볼륨 삭제는 하지 않았다. 표준start는already running을 반환했지만 backend가 없고 Docker 전용Windows서비스(com.docker.service)가Stopped였다. 일반서비스기동은권한거부됐고 사용자UAC를거친정확한Start-Service만실행해Running/exit0을확인했다. 서비스기동만을Engine정상화로보지않는다.

남은UI를종료하는도중자식프로세스가사라져identity안전검사가중단한1회를보존한다. 이후정식설치root의Docker Desktop.exe를기동해새backend PID2개및Desktop starting을확인했으나Server는계속제한시간안에응답하지않고5174는연결불가였다. WSL상태읽기(`wsl --list --verbose`)도12초timeout으로실패했다. WSL자체응답지연이관측됐으나정확한원인은미확정이다. C여유3861774336bytes(약3.6GiB), host가용메모리약2.1GiB도읽었으며이수치만으로직접원인을확정하지않는다.

현재Engine복구/컨테이너DB정상상태확인은BLOCKED다. 반복강제종료/전역WSL종료/Windows재부팅/정리·prune는자동실행하지않는다. 전역WSL또는호스트추가조치는사용자방향결정이필요하다. 복구후새환경과기존인수의이미지/소유권/baseline·체인상태를다시확인해야하며기존manifest를고쳐성공처리하지않는다. Google실인수/거래/커밋/병합/유료AI는진행하지않았다.

사용자가5173가용성을질문해별도재확인했다. 두웹은서로다른Compose/DB/이미지지만같은DockerEngine에의존한다. 관리API불능만으로5173불능을추정하지않고cache-busting query와no-cache header로5173/5174의루트및/api/health를동시조회했다. 에이전트실행환경의4개새HTTP요청모두URLError였고기존열린브라우저DOM을현재서버응답으로간주하지않았다. 사용자브라우저의실제새로고침성공여부는별도확인이필요하며실행환경차이가있다면추가조사한다. 이조회는인증/거래/Docker상태변경을하지않았다.

### 2026-10-18 재부팅 후 복구 — Engine/5174 PASS

사용자 Windows 재부팅 후 read-only probe에서 Docker Server28.4.0 정상 응답, 기존5174컨테이너4개 Exited255를 확인했다. 이번에는 Docker 전역 재시작/강제종료/관리자서비스 조작을 추가하지 않았다. DockerWindows서비스가Stopped인 상태에서도Server는정상응답했으므로 이전서비스Stopped관측을장애의확정원인으로간주하지않는다. 정확한이전장애원인은미확정이다.

ignored private `recover-google-5174.py`는 기존guard를재사용하고실제container imageID/서비스label/주요mount/전용network/state파일·ready확인을추가했다. 기존infra2개만`compose start`한뒤SQL/RPC readiness를bounded로확인했다. DBbaseline payload와보존file완전일치, chainId·baseline blockHash canonical일치, 계약4개codeHash일치·baseline파일미변경을확인한뒤에만기존backend/web2개를`start`했다. prepare/up/이미지빌드/재배포/DBreset/baseline재생성은없다.

복구증거: UTC2026-10-17T16:53:10, run google-live-20261017083610-9b6c788e, healthUP/providerSIMULATED/Googleenabledtrue. 사용자총1(준비ADMIN), Order0/Trade0/Faucet0 유지. 새USER인증·GoogleSDK/JWKS·거래·유료AI를실행하지않았다. 현재소스/image guard도통과했고복구evidence는같은run에timestamp추가저장했다. 이전실패이력은그대로보존한다.

### 5174 실제 가입·명시 연결 — PASS (2026-10-18 KST)

복구 후 웹 루트와 `/api/health`가 각각HTTP200/UP임을 새 요청으로 확인했다. 기존5173는해당조회에서연결불가였으며시작하지않았다.

사용자가일반Chrome에서새로컬가입및Google연결을직접수행했다. UTC16:59:56의읽기전용snapshot은USER id2/Google미연결/잔고·잠금·Faucet·Order·Trade모두0이었다. 연결후UTC17:00:47에같은id2/USER/Google연결true, 로컬loginId·password존재, 서비스email null/emailVerified false, 두자산잔고·잠금과모든이력건수0유지를확인했다. 제한된최근HTTP기록에서LINK challenge200/연결204를확인했다. Google sub·이메일·비밀번호·토큰원문은조회하지않았다. 신규run의timestamp별private증거로보존했다.

이는새환경가입·명시연결및0원상태보존의실제증거다. 연결후Google재로그인에서브라우저`/api/me` id확인, 신규개인WS이벤트, 취소·중복연결거부는아직NOT_RUN이며이전환경의결과로대체하지않는다.

### 5174 재로그인·본인 ID·연결後 개인WS — PASS

사용자Google재로그인완료후UTC2026-10-17T17:05:46의정제조회에서Google LOGIN200/소비된LOGIN challenge1건/USER총1명·id2·Google연결유지·잔고와이력0유지를확인했다. 이후사용자가일반Chrome Network의`/api/me` Preview에서id2를직접확인했다고보고했다. 브라우저응답원문이나JWT는수집하지않았다. 새로고침은메모리JWT를초기화하므로Network를먼저열고로그인직후확인하도록안내를정정했다. 로그인지속/Refresh Token은기존후속범위로유지한다.

연결된계정의로그인상태에서사용자가Faucet1회를수행하고일반Chrome WS Messages의신규`PORTFOLIO_UPDATED`수신을직접확인했다. 이는사용자관측증거이며에이전트가프레임원문을직접캡처했다는의미는아니다. UTC17:29:44 읽기전용DB확인은id2/mKRW1000000/mSEC0/두자산잠금0/Faucet1/Order0/Trade0으로지급1회와정합했다. 최근HTTP로그window의횟수는전체누적호출수로해석하지않는다. private timestamp별snapshot을보존했으며실제취소·다른계정중복연결거부는여전히NOT_RUN이다.

### 실패 경계 중간 관측 (2026-10-18)

사용자가두번째계정에서실수로Google연결을완료한후취소절차를수행했다고보고했다. UTC2026-10-17T17:33:49 allowlist SELECT에서id3은Google연결true, 추가id4는미연결이며두계정의자산·잠금·Faucet/Order/Trade건수는0이다. id2의연결및100만mKRW/Faucet1건도유지됐다. 최근HTTP window에는link204총2건/link409총1건이있지만이로그만으로409의공개오류코드와대상userId·조작순서를확정할수없다. id4에서실제취소했는지도사용자확인대기이며취소·중복연결거부를이시점에PASS로변경하지않는다. 기존연결해제나DB수정은하지않았다.

### 사용자 조작 확인 후 실패 경계 판정

사용자가id3에서이미연결된Google계정으로연결을시도해거부과정을확인했고, id4에서는취소절차를수행했다고명시했다. 따라서중복연결거부와취소의실제브라우저동작은사용자확인기준PASS로기록한다. 서버측독립증거는앞선link409 1건, id4 미연결, id2/3/4의관측자산·이력보존이다. 409응답의정확한공개오류코드·요청자매핑및취소challenge의개별소비상태를직접수집한것은아니며, 이를검증완료로확대하지않는다. id3의실수로완료된연결은유지하고unlink/데이터보정을하지않는다. 실제UI관측과fixture의세부오류코드·동시성검증은별개증거다.

## 실제 시나리오 진행 및 남은 검증

1. 새 환경 로컬 가입·Google 명시 연결은 위실제관측으로PASS다. 연결대상은새DB의id2이며과거환경의id3과혼동하지않는다.
2. 브라우저`/api/me` id2는사용자직접확인으로PASS. 서버의동일사용자·로그인성공증거와구분해위에기록했다.
3. 연결후신규개인WS `PORTFOLIO_UPDATED`는사용자직접관측으로PASS. Faucet1회및잠금0은별도DB조회로확인했다. 프레임원문은수집하지않았다.
4. id4 취소는사용자직접확인및미연결·자산불변관측으로PASS. 개별challenge미소비는직접조회하지않았으므로미검증으로구분한다.
5. id3의이미연결된Google계정연결거부는사용자확인과link409관측으로PASS. 정확한공개오류코드·요청자매핑직접수집은미검증이며강제해제·교체·이메일병합은하지않았다.

Google 인증 UI 조작은 사용자만 수행한다. 기존 원격디버깅 Chrome에서 차단 경험이 있으므로 일반 Chrome을 사용했다. 응답ID/신규WS/취소/중복거부는위사용자관측과서버증거의범위에서확인됐으며이전주요실인증, 무과금fixture, 새실제사용자증거를구분한다. 최종diff검토·회귀·독립검토·커밋/통합은아직미완료다.

## 커밋 및 통합 순서

실제 인수 증거가 닫히기 전에는 커밋/병합하지 않는다. 최종 보안·diff·독립 검토 후 다음 단위로 나눈다.

- Google 서버/웹/JWT·STOMP/도구·관련 테스트와 문서.
- 전량매도 REST문자열/버튼/별도 테스트·E2E와 문서.
- 필요하면 최종 실제 인수·통합 결과 기록을 별도 문서 커밋으로 분리.

공통 main.js/browser-smoke/phase-a/README/implementation-log는 Git index의 부분 staging으로 구분하고 작업 트리 소스를 되돌리지 않는다. Google-only 중간 tree에 sell-all import/버튼 참조나 새 Portfolio 생성자가 섞이지 않도록 확인한다. NXT 섹션/미추적 문서는 남겨두며, 브랜치 전환을 막는 경우 데이터를 버리지 않고 별도 보존 방식부터 결정한다. force/reset--hard/checkout--로 사용자 변경을 지우지 않는다.

melon-init의 실제 ref/공통 조상을 재확인하고 일반 merge로 반영한다. 충돌 시 중단하고 보고한다. 병합 뒤 그 HEAD에서 backend/web/Chrome/Foundry 및 필요한 새 격리 E2E를 실행하고 이전 결과와 분리한다. 현재는 아직 커밋·통합·병합후 회귀를 실행하지 않았다.

## 2026-10-18 사용자 승인 후 Git 통합 진행

사용자가최신수동인수결과와미검증한계를수용하고로컬커밋/정상merge/병합후새회귀를명시승인했다. 앞절의커밋금지는승인이전시점기록이며현재실행결과는[최종통합기록](google-sell-all-integration-2026-10-18.md)을따른다. Google `bc02330`, 전량매도 `16b8988`을분리커밋했다. 공통파일은index에서분리하고제품작업파일은변경하지않았다. Google-only별도export는backend54PASS/2SKIP·web93PASS·Chrome desktop/mobile offline fixture PASS였고독립index검토필수수정없음이다. 최종melon-init병합과새전체회귀는아직진행전이며이결과로대체하지않는다.

## 최종 Phase B와 Phase C 경계

- 기존 KRX immutable 이미지/DB/Anvil/baseline에는 Google/전량매도 기능이 없다. 그 환경의 장중 성공을 최신 소스 인수로 쓰지 않는다.
- Google/전량매도 통합 및 병합회귀가 끝나면 최신 melon-init HEAD/tree/source hash로 **새** Phase B KRX run을 준비한다. 신규 DB·persistent Anvil·키/주소·baseline·이미지·전용secret과 소유권/시각/독립 watchdog/egress 중단 검증을 갖춘다. 키움 secret은 승인된 읽기 전용 참조 또는 사용자 전용 주입으로만 준비하며 기존 파일·manifest를 재작성하지 않는다.
- 그 후 실제 키움 KRX 장중 호출과 모의 거래는 실행 범위·시간·호출 및 재연결 제한을 확인받아 진행한다. 정상 current-generation0B/290=2·LIVE·fresh·가용성, CLOSED 우선/Quote 전후 generation 검증을 유지한다. Synthetic 성공으로 Phase B PASS를 선언하지 않는다.
- Phase C 실제 AI는 별도 신규 AI DB/지식artifact/index version을 최신 소스와 대조하고 유료 색인/호출 수·비용 상한 승인 후 실행한다. 이번에는 유료AI/AI색인을 실행하지 않는다.
