# 개인 Fork Linux 통합 조회 CI 준비

## 현재 판정

**CI 구현·오프라인 검증·독립 검토 완료 / commit·push·최초CI 실행 승인 대기 / 실제 Actions·Linux 통합 Gate NOT_RUN.**

> 후속: 개인 저장소는 Fork network에서 분리된 Public 독립 저장소이며 URL은 같다. 설치경로 보완·최종 staged검사·개인저장소 commit/push와 최초CI1회에 조건부승인을 받았다. 아래 초기준비기록과 실행전승인대기 표기는 당시이력이며 최초CI 결과는 후속기록으로 구분한다.

- 기준: 개인 Fork `MelonSeo/Tokenized-Market-Simulator`의 `melon-init@207f60313e507fff6f098f4843db599d0a8b8101`.
- 새 브랜치 `ci/linux-product-read`, 새 worktree `deployment/runtime/linux-product-read-ci-worktree`.
- commit/push/원본병합/실제 CI 실행 없음. `main`을 개발 기준으로 사용하지 않았다.
- 기존20개 worktree와 사용자 DB/Anvil/checkpoint를 사용하거나 수정하지 않는다. 로컬 Windows 전체 Gate·금융 거래·유료 호출 없음.

## 선별 반영

기존 product-read-integration-worktree의 검토된22파일을 내용 대조 후 선별했다. 전체 worktree 복사나 private runtime 복사가 아니다.

- 제품2: `SecurityConfig.java`의 정적GET 공개 범위, `CCustodyController.java`의 readOnly 표기.
- 웹3: `c-api-adapter.js`, `c-direct-panel.js`, `c-direct-wallet.js`의 공통JWT/시장 조회·읽기전용·연결 소유자 표시.
- 회귀3: `StaticWebSecurityTest.java`, `CCustodyControllerTest.java`, `c-existing-web.test.js`.
- 조회 helper5: ProductReadGate/Driver/DriverRpcTest/Connection/ConnectionTest.
- 기존 Windows 실행·RAM·네트워크·순수시험8 및 product-read-browser.mjs.
- Windows 경로는 원본과 동일하게 보존했다. 이 CI worktree의 Driver에만 새 Fixture의 독립적으로 확인한 wallet/free/locked/status와 API결과를 추가 대조한다. 제품 API는 추가 변경하지 않는다.

## Linux 실행 구조

`.github/workflows/product-read-linux.yml`은 개인Fork·지정브랜치 push만 허용한다. Ubuntu24.04, contents:read, concurrency1/cancel-in-progress:false,25분 한도다. 외부Actions는40자리SHA, JDK/Node/Solc/이미지/라이브러리는 `deployment/product-read-ci-lock.json`에 고정했다. 실제 사용 버전과 산출물 hash는 실행 증거로 구분한다.

1. fresh checkout → 순수Python검사
2. npm lock 설치/웹213회귀/빌드 → Java21 Gradle compile 및 선택된 auth/C회귀
3. 고정OZ5.1.0/forge-std1.16.2 commit 및 digest이미지 설치
4. solc0.8.24/optimizer200 계약 build
5. `product-read-linux.py prepare`: 소스·JAR·class·웹·artifact fingerprint, compiler/source-map, helper self-test
6. 별도 run: 실제 Linux 메모리1920MiB/작업디스크2GiB guard → 충돌없는 신규internal network
7. fresh PG/Anvil → 새Fixture → PG schema/bootstrap → keyless 실제ExchangeApplication
8. HTTP/JWT/EIP191/owner 조회·격리/시장·캔들 → HeadlessChrome의 STOMP가격MESSAGE/로그인/C조회/로그아웃·계정전환
9. STOP·소유자/이미지/망 확인 후 정지 → 안전Artifact allowlist만1일 보존

빌드와 실환경 실행은 순차다. 기존 Windows RAM/route 구현은 수정하지 않고 Linux adapter를 분리했다. Linux MemAvailable과 유한한 cgroup headroom 중 작은 값을 사용해 컨테이너 한도를 무시하지 않는다. 기준을 낮추거나 플랫폼이 다르다는 이유로 Guard를 건너뛰지 않는다.

cgroup 관측은 현재루트`/sys/fs/cgroup/memory.max/current`의노출된한도에한정한다. 임의중첩cgroup전체경로를추적하는범용검사라고주장하지않는다. 이번workflow는job container가아닌지정된GitHub-hosted Ubuntu VM에서실행하도록제한했다.

App/PG/Anvil은 같은 소유network와 명시app/pg/anvil alias를 사용한다. App은 실제8080/all-interface/localTCP READY 뒤 Driver HTTP로 확인한다. Docker host port publish 없이 Linux host의 bounded opaque localhost bridge가 정확한 소유App IP:8080만 전달한다. 기존 Windows bridge는 그대로이며 이 Linux bridge에는 연결별 JVM이 없다. 실제 사용자 Chrome이 아니라 새 시험 프로필 Chrome이다.

## 비공개 자료 없는 새 시험 Fixture

`ProductReadFixture.java`는 CAnvilContractGate의 기존 ABI·서명·송신·canonical Receipt·전체calldata/이벤트·runtime template 검증을 재사용한다. 과거 run()은 호출하지 않으며 과거 snapshot/키/DB를 읽지 않는다.

- Runner 안에서 treasury/admin/relayer/oracle/userA/userB 서로 다른 임시 EOA 생성.
- 고정공급 ERC20·C controller·내부rules/Vault를 새 Anvil에 배포.
- 실제code를 compilerartifact 고정부·immutable반복slot과 비교하고 executor/Oracle/asset/controller/relayer/admin/symbol/policy getter를 검증.
- 각 사용자 지갑에 시험100만, 예치3000, 담보1000·Long10배·가격100 OPEN →105 CLOSE. 수량100, 수익500, 사용자wallet997000/free3500/locked0/CLOSED.
- 최종Vault free7000/locked0/house99000/unpaid0 및 token자산 보존 확인.
- 사용자·서버 EIP712를 각각 서명한 relayed 시험거래의 Receipt와 정확히4개 rules2+Vault1+controller1 이벤트를 검증한다.
- 이는 **시험 체인 생성 거래**다. 후속 조회Gate의 productFinancialRequests=0과 혼동하지 않는다. Fixture 실제거래 수는 meta에 별도로 기록한다.

상태 생성 절차·경제 기대값은 재현 가능하지만 임시EOA·주소·runtime codeHash·blockHash·txHash는 실행마다 다르다. 기존 C5a2 identity를 그대로 성공으로 승계하지 않는다. 새 Fixture의 codeHash는 관측값만 믿는 것이 아니라 compilerartifact/source metadata 대조와 getter/Receipt를 통과한 뒤 본인조회 baseline에 바인딩한다. 조회는 제품 RPCallowlist/EIP1898 blockHash·requireCanonical/최종헤더·genesis 재검증을 유지한다.

사용자 A/B 키는 Runner private dir에서 생성하여 별도Driver에만 주고 Spring에는 제공하지 않는다. 실사용자 지갑과 독립적이며 MetaMask 인수가 아니다. 비밀번호/JWT도 매번 새로 생성한다. 원본키·시드·DBdump·C5a2 snapshot 외부 업로드 없음.

## Artifact와 실패·종료 경계

- 소스 저장 대상: 선별 제품/시험 소스, Linux launcher/support/Fixture, lock/init.gradle, Workflow, 순수시험과 이 문서.
- 제외 대상: deployment/runtime 전체, .env, 키·password/JWT 설정, raw/signature/Receipt 원문이 포함된 private evidence, Chrome profile, DB/Anvil snapshot, 빌드캐시/도구바이너리.
- `safe_artifacts`는 result, 공개Fixture meta, 검증된OPEN/CLOSE4건의정제txHash/blockHash/event요약, 정제API/browser결과, 지정PNG4개만 허용한다. 키·password 등 실제생성secret 값과민감필드/토큰패턴을 검사하고 성공 후에만manifest를 생성한다. manifest가 없으면 upload가 실행되지 않는다. private dir 전체를 recursive upload하지 않는다. 원문Receipt/서명은여전히제외하며정제요약과혼동하지않는다.
- 단계별 deadline과 전체job timeout, SIGTERM시 finally cleanup, always cleanup fallback을 둔다. fallback도 기록된 run/name/image/network/owner가 일치하는 컨테이너만 정지하며 prune/delete/restart나재전송은 없다.
- 실패한 거래/조회는 자동 재시도하지 않는다. PG/Anvil 기동대기는 제한된 읽기전용 readiness poll이며 signed거래 재전송이 아니다.
- GitHubRunner는job후폐기된다. private실패증거가영구보존된다고주장하지않는다. 업로드되는것은정제된결과만이며 로컬기존증거는그대로유지한다.

## 오프라인 실행 결과

| 검증 | 실제 결과 |
|---|---|
| Python 순수 RAM/네트워크/연결/Linux/Artifact검사 | 59 PASS |
| 웹 전체 | 213 PASS/0FAIL/0SKIP |
| 웹 build | PASS,107modules |
| 새제품 Java main361/helper13 직접javac | PASS,기존검증된JAR캐시hash대조,소스불변 |
| 최종변경 Fixture/Driver 재컴파일 | PASS |
| Java RPC/HTTP/경계/Fixture self-test | RPC16/HTTP9 및2개self-test PASS,무소켓·무EVM |
| Solidity | solc0.8.24,41files compile PASS,기존lint경고보존 |
| Workflow | actionlint1.7.7 checksum검증후PASS, YAML파서scope검사PASS. shellcheck/pyflakes미실행 |
| Linux Gradle/선택JUnit/컨테이너/JDBC/RPC/Chrome | NOT_RUN,최초CI에서검증필요 |
| 실제 MetaMask | NOT_RUN/이번범위밖 |

직접javac는Windows Gradle native socket 문제를피한오프라인빌드이며 Linux Gradle성공이나실환경인수로대체하지않는다. Solidity로컬빌드는기존root 라이브러리를읽기전용remapping으로사용했으며CI에서는공식고정commit을새로받는다. EOA생성과실거래는로컬에서실행하지않았다.

추가 의존성대조: 로컬OpenZeppelin Solidity261개를공식고정commit의Gitblob SHA와대조하여불일치0(줄끝CRLF→LF정규화)이다. source/candidate 검사에서private경로·credential패턴탐지0. 이검사는모든종류의비밀이절대없다는보증이아니며최종commit전선별검사가여전히필요하다. 기존20worktree의브랜치/HEAD/미커밋상태hash는불변이고등록수는새CI를포함해21개다.

## 독립 검토 최종 결과와 보존

검토자 `/root/review_product_read_integration`: **발견된 필수 수정 없음**. Workflow/Gradle연결·2EOAFixture·canonicalReceipt와4이벤트·codeHash/자산·고정RPC·JWT/권한·WS/Chrome경로·Guard·키분리·정지·최종정제4거래요약/Artifact범위를읽기검토했다. 검토자는직접diff--check만실행했으며테스트·Docker·EVM·CI를직접실행하지않았다.

비차단제안: 향후임의의중첩cgroup환경까지지원할때`/proc/self/cgroup`기준경로탐색보완. 현재지정Runner의필수수정으로판단되지않았고이번범위를추가확장하지않았다.

최종후보 source460개 fingerprint는 **e0a34080e056dda3cfec279f278d651625e30514a84226a915063bdd0c587d7e**다. 이는현재Windows체크아웃의실제byte기준이며최초Linux실행은그실행의commit/source/artifact hash를새로기록한다. 플랫폼별줄끝·배포identity차이를과거검증의소급성공으로취급하지않는다.

Git후보32파일(기존tracked수정8+새파일24),stage/commit/push0. 선별22파일중21개는기존검토본과LF정규화내용동일하며Driver1개만명시된새Fixture경제값대조를추가했다. 기존20worktree identity/status hash,새CI브랜치를제외한기존모든localhead/tag,branch추적설정은작업전과동일하다. root melon-init207f603/main194c38b·origin/personal remote를유지했다. 사용자DB/Anvil/checkpoint변경0.

## 최초 CI 성공 기준과 남은 위험

독립검토 필수문제가해소되고 사용자commit/push/실행승인 이후에만 최초CI를실행한다. 최초run은 lock의공개이미지접근,정확한Gradleinit/task,Soliditymetadata경로,새Fixture생성,실제HTTP/RPC/SCRAM/WS/Chrome/종료까지 모두통과해야PASS다. 부분성공·단위시험을전체PASS로올리지않는다.

현재신규Fixture는실EVM미실행이다. JVMvendor/Foundryimage의미세한환경차이,Runner의Chrome sandbox/의존성다운로드,GitHub조직·계정정책/자원변동은최초CI에서확인해야한다. Runner RAM도실측전성공을보장하지않는다.

기본브랜치main을수정하거나workflow_dispatch를추가하지않았다. 향후승인된개인Fork ci/linux-product-read push가최초실행을트리거한다. Organization origin에는push하지않는다. 전체감사·AI·위험Worker·실사용자금융거래는이번Gate에서OFF이며완료로취급하지않는다.

## 설치 경로 이식성 보완 및 최초 실행 준비

- Windows JDK/Chrome 설치 절대경로3개는 일반프로그램위치이며 비밀유출로 판정하지 않았다. 업로드전 이식성기준에 따라 제거했다.
- `product_read_tools.find_jdk`: JAVA_HOME 우선, 없으면 PATH의 java/javac. 명시한JAVA_HOME이잘못되면자동fallback하지않으며실행파일존재·동일설치·두버전일치·Java21을검사한다. CLI신규오류는경로나원문예외가아닌고정코드로출력한다.
- `browser-executable.mjs`: CHROME_PATH 우선, PATH와Windows설치루트환경변수로후보탐색. 누락/다른브라우저/지원범위밖버전은명확히거부한다. 필요시TMS_CHROME_EXPECTED_MAJOR로일치강제. 선택family/major만기록하며경로는기록하지않는다. Windows에서는파일버전메타데이터만읽고Chrome을실행하지않는다. 실제인수는기존새프로필·deadline·cleanup그대로다.
- 실제Windows PATH Java23은예상대로거부, 명시적임시JAVA_HOME의21.0.10은통과했다. 글로벌환경변수는변경하지않았다. WindowsChrome메타데이터155 확인, 기존브라우저/프로필변경0.
- Python69 PASS,웹223 PASS. Windows/Linux분기·명시경로·PATH·미설치·다른버전/혼합JDK·명시경로실패시fallback금지·오류redaction을시험했다. 실제Linux탐색/서비스인수는CI에서확인해야한다.
- 추가파일은공통Python탐색기/순수시험및JS탐색기/순수시험4개다. source identity에도반영했다. 제품Java/금융·인증·계약·Oracle·Fixture경제조건은추가수정없다.
- 이범위독립검토1회와최종보안검사결과를확인한경우에만개인remote personal의 ci/linux-product-read에지정메시지commit/push한다. CI는최초1회관측하며실패시수정·재push·재실행하지않는다.
- 추가독립검토1회 review_product_read_integration: **발견된필수수정없음**. 설치경로·버전·오류출력·새프로필/85초예산·fingerprint·제품/경제조건불변을읽기확인하고staged空白검사만직접실행했다. 실제Linux탐색·서비스·CI는검토자가실행하지않았다.
- 최종업로드후보36파일(기존32+탐색기/시험4)을명시stage했다. Gitleaks8.24.2 공식checksum검증후실제stageddiff와전체index파일을각각검사하여탐지0,허용목록일치·설치절대경로0·비공개파일경로0을확인했다. 이는무유출의절대보증이아니다. 최종문서사실기록후같은검사를다시확인한다.
