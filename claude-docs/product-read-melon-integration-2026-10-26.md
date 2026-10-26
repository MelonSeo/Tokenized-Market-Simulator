# Linux 통합 조회 → melon-init 통합

## 사전 상태와 통합 방법

- 실제 원격 `personal/melon-init=207f60313e507fff6f098f4843db599d0a8b8101`, `personal/ci/linux-product-read=f8eb22bfd600b1ff153f6a5cf05e005247eecbe7`. 직계후손4커밋으로충돌없는fast-forward가능. 성공근거 run40223677802.
- 원본melon-init의미커밋문서/운영도구6개는통합40파일과경로중복없음. 기존21worktree의identity/status+tracked diff hash를작업전기록하고새통합worktree/브랜치만추가한다. 사용자DB/체인/체크포인트는조회·수정하지않는다.
- 후보는f8eb22b전체이력을유지하며workflow와해당scope회귀만melon-init실행으로전환한다. 원격상태재확인후개인melon-init에비강제push, 통합후CI1회확인. 원본로컬melon-init의fast-forward도미커밋보존가능할때만수행한다. stash/reset/clean/강제덮어쓰기없음.

## 전체 변경 범위와 제품 영향

검증된4커밋의전체목록은`git diff --name-only 207f60313e507fff6f098f4843db599d0a8b8101 f8eb22bfd600b1ff153f6a5cf05e005247eecbe7`로고정재현가능(40파일). 계약/금융계산/청산/DBschema변경없음.

| 구분 | 실제 변경과 영향 |
|---|---|
| 제품Java2개 | SecurityConfig: GET `/`, `/index.html`, `/assets/**`만익명허용. API/JWT규칙은그대로. CCustodyController: config관측envelope에readOnly표시추가 |
| 활성화 | C read/proof controller는기존app.c-futures.enabled=true일때만생성. direct.enabled기본false이면readOnly=true. 기존직접거래서버활성화조건은변경하지않음 |
| 보안경계 | readOnly는UI capability표시이지인증대체가아님. 서버JWT와지갑소유증명·owner검증은기존그대로, 계정숫자일치로동일인판정하지않음 |
| 제품웹3개 | c-api-adapter 공유시장REST연결; c-direct-panel 읽기전용일때금융버튼차단/계정교체즉시이전데이터삭제/일반시장조회; c-direct-wallet 공유시장연결. 기존거래모드동작보존 |
| Java시험/build | StaticWebSecurityTest신규,CCustodyControllerTest회귀. H2/JUnitLauncher를testImplementation으로변경(제품runtime에추가하지않음) |
| 웹시험/도구 | c-existing-web회귀,Chrome탐색/브라우저하네스및시험. Linux에서만font/CDP관측,Windows경로유지 |
| Linux/Windows하네스 | 신규공개Fixture/연결/RPC고정블록/네트워크/Guard/prepare/정지/안전Artifact및순수시험. 기존금융API활성화·실사용자체인접근없음 |
| 문서 | 구현로그및준비/JDK/의존성/폰트기록. 로컬취업용회고인덱스와private runtime는제외 |

### 검증된 4커밋의 전체 40파일

- `.github/workflows/product-read-linux.yml`
- `backend/build.gradle`
- `backend/src/main/java/com/pricetrack/exchange/common/config/SecurityConfig.java`
- `backend/src/main/java/com/pricetrack/exchange/custody/api/CCustodyController.java`
- `backend/src/test/java/com/pricetrack/exchange/auth/StaticWebSecurityTest.java`
- `backend/src/test/java/com/pricetrack/exchange/custody/api/CCustodyControllerTest.java`
- `claude-docs/implementation-log.md`
- `claude-docs/product-read-linux-ci-2026-10-25.md`
- `claude-docs/product-read-linux-ci-autonomous-2026-10-26.md`
- `claude-docs/product-read-linux-ci-jdk-resolution-2026-10-26.md`
- `claude-docs/product-read-linux-korean-fonts-2026-10-26.md`
- `deployment/ProductReadConnection.java`
- `deployment/ProductReadConnectionTest.java`
- `deployment/ProductReadDriver.java`
- `deployment/ProductReadDriverRpcTest.java`
- `deployment/ProductReadFixture.java`
- `deployment/ProductReadGate.java`
- `deployment/product-read-ci-lock.json`
- `deployment/product-read-ci.gradle`
- `deployment/product-read-integration.py`
- `deployment/product-read-linux.py`
- `deployment/product-read-offline-build.py`
- `deployment/product-read-resource-compare.py`
- `deployment/product-read-rpc-unit.py`
- `deployment/product_read_linux_support.py`
- `deployment/product_read_network.py`
- `deployment/product_read_resources.py`
- `deployment/product_read_tools.py`
- `deployment/test_product_read_connections.py`
- `deployment/test_product_read_linux.py`
- `deployment/test_product_read_network.py`
- `deployment/test_product_read_resources.py`
- `deployment/test_product_read_tools.py`
- `tools/websocket-test-client/scripts/browser-executable.mjs`
- `tools/websocket-test-client/scripts/product-read-browser.mjs`
- `tools/websocket-test-client/src/c-api-adapter.js`
- `tools/websocket-test-client/src/c-direct-panel.js`
- `tools/websocket-test-client/src/c-direct-wallet.js`
- `tools/websocket-test-client/test/browser-executable.test.mjs`
- `tools/websocket-test-client/test/c-existing-web.test.js`

## CI 운영 기준

- 개인저장소`MelonSeo/Tokenized-Market-Simulator`의`melon-init` push만실행한다. CI개발브랜치및임시통합브랜치는트리거에서제외해동일통합의중복실행을피한다. Organization과main은변경하지않는다.
- 기존contents:read/pinnedActions/ubuntu24.04/concurrency1/25분/고정JDK·Node·Solidity/이미지/자원Guard/Artifact정제·1일보존/소유cleanup유지. workflow_dispatch나새권한을추가하지않는다.
- 트리거의branch와job ref조건을함께수정하며scope순수회귀도동일기준으로갱신했다. 기존API/JWT/WS/Chrome·한글검증은유지하며실제통합은새commit/sourceidentity로실행한다.

## 검증과 검토

격리후보Python72 PASS/JS문법/actionlint PASS. 제품소스는검증된f8eb22b와동일하며통합에서새금융기능을추가하지않는다. 독립검토및전체공개Diff/추가stagedDiff보안검사후에만원격melon-init을갱신한다. 실제CI결과와보존비교는실행후누적한다.

- 독립검토`review_melon_ci_integration`: **발견된필수수정없음**. 전체40파일과제품Java·웹·활성화/권한경계·하네스·workflow전환·기술기록검토. 직접Python72/핵심웹·Chrome탐색19/diff검사PASS, f8대비추가제품diff0확인. 실제원격갱신/통합후CI/로컬FF후보존비교는미실행으로구분했다.
- 전체4커밋Diff Gitleaks8.24.2 탐지0. 신규staged와최종통합Diff도push전확인하며0건이무유출절대보장은아니다.

## 개발 경험 연결과 다음 경계

| 세부 사건 | 실제 확인과 선택 | 실행 근거 |
|---|---|---|
| Windows RAM 차단 | 물리RAMGuard를낮추거나필수사용자세션을종료하지않고, 사용자승인후독립LinuxRunner로검증경계이동 | 기존CI준비보고서와사용자승인기록 |
| Docker 연결 | 과거Driver/App DNS별칭·HTTP연결진단,사용중subnet관측및전용network,host포트게시없는실행을사용 | 기존ProductReadConnection/Network시험및CI하네스 |
| 고정블록RPC | 테스트Driver를제품allowlist/EIP1898 blockHash/requireCanonical+끝재검증정책에맞춤. 제품RPC정책완화안함 | ProductReadDriverRpcTest/Driver,기존조회Gate기록 |
| JDK 배포 식별자 | setup-java21.0.10+7해석실패→실제목록21.0.10+7.0.LTS사용.실제runtime은동일Temurin21.0.10/build7 | run40163271068 FAIL→run40215304287 설치PASS |
| Gradle 시험 의존성 | 직접import하는H2/Launcher가runtimeOnly라compileTestJava19errors→testImplementation두줄수정.시험skip안함 | run40215304287 FAIL→run40218771542 전체조회PASS |
| 한글 글꼴 | DOM/API성공PNG는tofu.이후Runner한글font목록0확인,Noto CJK설치/cache/CDP실제glyph/PNG시각확인.깊은probe오판은독립검토후분리 | run40218771542 조회PASS/시각한계→run40223677802 조회·한글PASS |

경제정책·핵심권한·실행한도는사용자결정이며, 로그대조와범위안최소수정은Codex의개발판단으로구분한다. Windows네트워크/RPC의과거근본원인해결이나실사용자MetaMask인수를Linux조회성공으로주장하지않는다.

WindowsRAMGuard차단→Linux독립실행환경→공개시험Fixture→고정블록RPC·Docker/DNS/HTTP경계보완→JDK식별자·Gradle시험compile依存성scope→실제조회PASS→한글glyph/PNG확인까지의근거는[CI준비](product-read-linux-ci-2026-10-25.md),[JDK](product-read-linux-ci-jdk-resolution-2026-10-26.md),[의존성](product-read-linux-ci-autonomous-2026-10-26.md),[폰트](product-read-linux-korean-fonts-2026-10-26.md)에연결한다. 당시자료에없는대안·사용자생각·기여도는추정하지않는다. 취업용후보인덱스는기존ignored runtime로컬전용을보존한다.

이조회Gate는실제사용자MetaMask거래·제품자동청산·AI·전체감사성공이아니다. 후속실거래통합은별도승인된격리쓰기환경/동일JWT-EOA귀속/사용자직접승인/canonicalReceipt와Vault·DB정합성검증이필요하며이번에시작하지않는다.
