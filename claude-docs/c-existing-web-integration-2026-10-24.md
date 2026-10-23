# 기존 TMS 웹 C 자산 안전 통합 — 2026-10-24

> **최신 실제 Chrome 인수 결과: PARTIAL / 전체 Gate BLOCKED / 실제 PNG8.** `ccpg-a8ee763b344b4bdb`(source907b3d33…, 준비b0aff7ba)를 RAM4037MiB 확인 후 제한된1회 실행했다. 실제 시장/C 계정200·로그인·NativeWS·1366px 주요화면은 관측했으나 ADMIN 수동 이력 조회 버튼 없이 자동 GET을 기다린 하네스 결함으로 마지막 POLL_TIMEOUT이 발생했다. 추가 실행 없이 종료·보존했다. 정확한8PNG/메뉴/버튼/API/OFF 구분·캡처가독성한계·현재URL종료는 [발표자용 화면 인수 결과](c-integrated-web-chrome-acceptance-2026-10-24.md)를 우선 읽는다. 아래 NOT_RUN/준비 중/재검토 대기는 이전 시점의 이력이다.

## 최신 조회 인수 결과 — 실제 서버 PASS / Chrome·전체 탭 PARTIAL

- 실행: `ccpg-8949e3a7a933490e`, prepare `20261023T211046Z-281eab64`, source `39bfb7912247a4fd8ff4277776478dc6385f667193fbddf24d0cb222b3ef724c`. 결과 `ACTUAL_FULL_APP_HTTP_STOMP_C_READ_PASS_NOT_UI_OR_TRADING`, safeError null,107.734초. 증거는 `deployment/runtime/full-read/ccpg-8949e3a7a933490e/{result.json,appEvidence/startup-timing.jsonl,verifyEvidence/verify.json}`다.
- 기존75초는 하네스 고정값이며 실제 기동 측정으로 산정했다는 근거는 없다. 테스트 전용 readiness120/work300/total390과 안전 stage/elapsed 관측만 보완했다. cleanup90초·제품310·Driver·금융·인증·Oracle/Report/Intent TTL·RPC timeout은 그대로다. 순수9/새 compiled 자체검사·독립 재검토1/2 필수없음 후 신규1회 실행했다. 기존 소비된 claim/실패 환경은 재사용하지 않았다.
- 이번 기동: JVM_ENTER2ms→SPRING_RUN_START3693ms→SPRING_RUN_DONE56973ms→POSTCHECK_DONE56983ms→READY57126ms. Spring.run53.280초·후속 내부검사10ms, 준비57.126초다. STOP77674ms→CTX_CLOSED77817ms. 이전78.863초의 어느 bean/DB/자원이 느렸는지는 기존 로그로 확정할 수 없으며 이번 통과를 근본 원인 해결로 표현하지 않는다.
- fresh 시험 계정2개를 만들고 JWT 로그인/me/권한·사용자 격리 및 독립 시험 EOA의 **소유 증명만** 검증했다. 개인키는 Driver만 보유했고 사용자 원래 C 지갑과 JWT 계정 동일성을 가정하지 않았다. DB users2/balances4·잔액0·links2, orders/trades/faucet_grants0을 실제 확인했다. MetaMask/자산 approve·deposit·OPEN·CLOSE·withdraw/유료 API는0이다.

| 이번 인수 대상 | 실제 결과 | 미검증 경계 |
| --- | --- | --- |
| 공통 REST/JWT | 실제 signup/login/me·공통401/403 PASS | 사용자 기존 계정 이관 아님 |
| 시장 | REST 시장/캔들·C 참고시세 PASS | 실제 Chrome 차트 렌더링 NOT_RUN |
| WebSocket | 실제 native STOMP CONNECTED 및 SIMULATED 가격 MESSAGE PASS | Chrome STOMP·SockJS NOT_RUN |
| C 계정 | 실제 config·두 시험 지갑 account 조회·다른 userId/wallet 주입 무시 PASS | C 탭 자산 렌더링·포지션 조회 NOT_RUN |
| 기존 현물 | 금융 POST 차단 및 orders/trades0 확인 | 기존 현물 화면/API·거래 인수 NOT_RUN |
| 기존 선물·감사 | 기존 소스/Mock 회귀 보존, 이번 서버에서 실행 OFF | 전체 API/ADMIN 감사·탭 인수 NOT_RUN |
| AI | 신규 유료 호출0, 기존 UI 보존 | 실제 웹 Agent API·C Tool/RAG 인수 NOT_RUN |

- 실행 전 RAM2308MiB/디스크24.44GiB 및 실제 runner 자원 검사를 통과했다. 종료 후 RAM1508MiB/디스크24.74GiB로, 최소1920MiB 대비 **412MiB 부족**해 후속 Chrome/추가 실환경은 시작하지 않았다. 기존 앱/Chrome/Docker/WSL을 종료하거나 반복 측정·재실행하지 않았다.
- 소유5역할 정상 종료, 새network/volume 보존, source/fixture/공식 anchor 및 fork 기존14거래 불변, 공식8d live claim 미접근이다. 현재 공개/호스트 접속 URL은 없다(host port0, 종료 상태). 내부 `http://app:8080`은 컨테이너 시험 주소이며 사용자 브라우저 URL이 아니다. 시험 로그인ID는 `read_8949e3a7a933490e_0/1`, 비밀번호는 신규 무작위 private 설정이므로 보고서에 기록하지 않고, 종료된 환경 로그인 안내로 재사용하지 않는다.
- 캡처: 이번 **실제 서버 Chrome PNG0/NOT_RUN**. 기존 Mock browser-smoke 또는 과거 사용자 C 단독 화면을 이번 통합 성공으로 제시하지 않는다. 실제 브라우저의 최소 후속은 충분한 RAM에서 bounded 캡처 시간 동안 소유 App 유지·기존 compiled dist 재사용·실제 `/api`와 `/ws`를 전달하는 same-origin loopback 프록시다. 기존 금융 POST/AI 유료 요청 차단·JWT·ADMIN 경계는 유지해야 하며 제품 기능/디자인 재작성은 필요 없다. 이번에는 이 시험 연결 코드를 추가하지 않았다.
- 발표 판정: 기존 실제 C5거래 증거는 보존·활용 가능하나 **단일 통합 웹 라이브 시연은 아직 인수 미완료**다. 이번 요청은 자원 차단 경계에서 종료하며 기존172 전체 회귀/새 금융 거래/배포/commit·merge·push는 실행하지 않았다.

> **최신 실제 실행:** fullApp 읽기 Gate `ccpg-49a47adf535948bd`는 `ACTUAL_FULL_APP` 단계의 `FULL_READ_APP_READY_TIMEOUT`으로 **BLOCKED**다. 준비 `20261023T203643Z-b6461651` / source `02730929247deafb6e9fc481d0974d038187ed5f031647b2dd4f68166e7d0078`의 최초 독립 검토는 필수 지적 없음이었다. RAM2075MiB/디스크24.534GiB 확인 후 실행했으며, 114.672초에 안전 종료했다. owner bootstrap 성공, PG/Anvil/App/bootstrap 정상 종료, network/volume 및 source/fixture/원본 anchor 보존, fork 기존14거래 동일성 확인. verify Driver는 시작하지 않아 HTTP/JWT/STOMP/지갑 연결은 NOT_RUN이다. 공식8d claim 미접근·host port0·거래0이며 이 준비의 RUN_CLAIM은 소비·보존한다. 원인 진단 전 재실행·timeout 증가는 하지 않는다.

> 후속 Chrome은 자원 검사 RAM559MiB로 기준1920MiB를 충족하지 못해 **실행 전 BLOCKED**됐다. 신규 Chrome/리스너/프로필은 생성하지 않았다. 독립 재검토를 통과한 bounded 하네스와 이전 실패 증거는 그대로 유지한다. 다른 앱이나 Docker/WSL을 임의 종료하지 않았다. 아래 NOT_RUN/검토대기 문구는 각 시점 이력이다.

### 위 fullApp 실패의 읽기 후속 진단

보존 파일에서 실제 ready가 늦게 생성된 것을 확인했다. UTC App start20:55:29.579, 감독 STOP20:56:45.030, ready20:56:48.442, normal-stop20:56:49.512, exit20:56:49.580다. ready는 시작78.863초 후/STOP3.412초 후여서75초 readiness 제한을 넘겼다. 해당 marker는 실제 ExchangeApplication bean·OFF 설정·deny Oracle·API DataSource/member0·Auth/WS/C bean 검사를 지난 위치에서만 생성된다. 따라서 기동 및 내부 검사 완료는 관측됐지만, HTTP/STOMP/사용자 지갑/거래 검증 완료는 아니다. 컨테이너 로그305bytes는 banner뿐이고 OOMfalse/exception marker0이다. WARN·startup info OFF 때문에 세부 기동 시간 증거가 없어 느린 bean/DB/CPU/메모리 중 원인을 단정하지 않는다. 새 실행·timeout 상향·금융 정책 변경 없이 BLOCKED를 유지한다.

> 기동 책임 분리 단위 최신 검토: 수정 후 독립 재검토1/2 **발견된 필수 수정 없음**. 최초 지적은 `faucet_grants`의 직접 소유권 검사 누락이며 원본4컬럼·PK 및 비소유 검증과 거부 회귀로 보완했다. 최종 신규13 PASS, 기존 default H2 금융24 PASS는 변경 없는 기본 경로의 선행 결과로 재사용했다. 검토자는 소스·변경3파일·보존 로그를 직접 확인했으며 테스트를 반복하지 않았다. 실제 PostgreSQL catalog·owner bootstrap·fullApp Gate는 NOT_RUN이고, Spring 기동 전 외부 설정 guard와 새 API 역할의 owner 멤버십0 확인은 후속 실환경 전제다.

> 후속 제품 참고시세 endpoint: 신규 controller/test의 최초 독립 검토는 **발견된 필수 수정 없음**. 캐시 기반 직접 JUnit7 PASS/FAIL·SKIP·ABORT0과 ApplicationContext/standalone MockMvc 범위다. 검토자는 소스와 보존 실행 결과를 확인했으며 직접 JUnit을 반복하지 않았다. 실제 JWT 필터·fullApp HTTP·DB/RPC는 NOT_RUN. 정확한6필드·정수 정밀도·기본off·demo 중복배제·공통 인증 경계 외 금융·서명·TTL 정책 변경은 없다.

> 최신 판정: 웹 소스·Mock 회귀·빌드 및 독립 재검토 PASS. 실제 1366/390px Chrome 캡처는 첫 PNG 전 CDP 응답 대기에서 1회 중단하여 NOT_RUN. 03:51 KST root의 읽기 전용 Windows 프로세스 검사에서 이번 전용 profile `c-existing-9FHtj7`을 사용하는 chrome.exe는 0개였다. 사용자 Chrome 종료나 전체 프로세스 정리는 하지 않았다. 실서버·MetaMask 통합 PASS가 아니다.

## 범위와 현재 상태
- 최신 추가 확인: 새 PG startup 단독 unit의 owner 원본 install→최소권한 API verify-existing 6case를 실제 Linux PG/JDK에서 1회 통과했다. 이는 fullApp/웹/Anvil/지갑 통합 Gate가 아니며 관련 상세·남은 범위는 아래 실제 실행 절에 분리한다.
- 별도 detached worktree HEAD `f9353c3`에서 선별 프런트엔드만 통합. 원본·기존 미커밋 worktree는 읽기 전용 출처이며 전체 복사하지 않았다.
- 기존 market/futures/history/portfolio/ai/admin hash routes와 최신 AI 증거 표시를 유지하고 `#custody` 탭을 추가했다.
- C는 별도 cMKRW ERC-20/Vault 자산이다. 기존 mKRW DB 장부와 합치거나 전환하지 않는다. C bytes32 positionId를 UUID 전용 기존 AI 선물 Skill에 연결하지 않았다. 최초 UI 단위에는 backend 변경이 없었고, 후속 승인된 참고시세 read controller 1개는 아래 별도 단위에 기록한다. 인증/금융/C 계약 변경은 없다.
- C 선택 App은 전체 TMS backend가 아니다. 이 정적 통합만으로 시장·현물·선물·AI·C가 단일 실서버에서 동작했다고 주장하지 않는다.

## 구현과 출처
- AI presentation의 최신 index/main/ui/style/futures-panel과 의존 futures-display/decimal-display/ai-futures-evidence를 선별 재사용했다. 관련 테스트 2개·고정 응답 fixture 1개도 재사용했다.
- CDirectWallet/c-direct-intent는 기존 방어 로직 그대로 재사용했다. BigInt, exact calldata, owner/nonce/genesis, pending, Web Locks, 동시 탭 방어를 유지한다.
- c-demo HTML의 C 자산 필드/안내와 흐름 helpers/이벤트만 `createCDirectPanel`로 재구성했다. 독립 demo 로그인·비밀번호·token 저장·복사는 가져오지 않았다.
- `ApiClient.cRequest`는 C namespace만 허용하고 기존 JWT transport를 사용한다. `createCApiAdapter`가 `{status,body}` transport만 벗기고 C `{value}` 응답은 보존한다.
- logout/route 변경은 wallet context epoch를 바꾸고 표시값을 제거한다. account/network 이벤트는 execution·consent를 무효화한다. 이미 제출된 늦은 txHash는 원래 storage key에 저장한 뒤 epoch를 검사하므로 유실하지 않는다. pending 삭제는 명시적 acknowledge 경로뿐이다.
- C config 미지원/미설정/실패면 C 버튼은 비활성이고 fake balance/live 표시가 없다. 지갑 연결은 사용자 버튼으로만 요청한다. 개인키/시드 요청 0.
- C CSS는 C 영역 scope이며 demo의 :root/body/.card 전역 스타일을 복사하지 않았다. 추가 탭의 모바일 내비게이션은 수평 스크롤을 허용한다.

## 검증
- `node --check src/c-direct-panel.js` PASS.
- 선택 순수 Node `node --test test/c-existing-web.test.js test/c-direct.test.js test/ai-futures-evidence.test.js test/futures-display.test.js`: 40 PASS / FAIL0 / SKIP0, 약0.2초.
- C 원본 회귀 25개는 exact amount/ABI/Report 만료·owner/chain/genesis·nonce·미확정 전송·late txHash·canonical receipt·Web Locks·교차 탭·손상 pending을 검증한다. 한 소스 구조 검사의 경로만 통합 panel/index로 바꿨다.
- 신규 통합 Mock 4개: shared JWT·transport adapter·Idempotency-Key, 미지원 disabled, 늦은 config/logout/route, account 변경 후 execution 무효화 및 public pending 보존.
- `git diff --check` PASS. 새 파일 포함 검토는 별도 요청한다.
- 초기 선택 검증 당시 전체 웹 회귀/build는 root의 실제 보존-state Gate와 겹치지 않도록 대기했다. Gate 종료 후 최종 실행 결과는 아래 재검증 절에 구분한다. 네트워크 설치 0.
- 실제 PG/Anvil/App/MetaMask·유료 AI·전체 backend/실브라우저 live 통합·commit/merge/push는 이 단위에서 NOT_RUN.

## 남은 검증
- 수정 후 독립 재검토1/2 완료: 발견된 필수 수정 없음. 검토자가 선택52개를 직접 PASS로 확인했고 전체172/build107은 구현자 실행 증거로 구분했다. 최초 두 지적의 수정 내용과 P0-B 출처 일치를 확인했다. 실제 서버·Docker·MetaMask·유료 호출은 검토 중 실행하지 않았다.
- 전체 TMS+C endpoint를 함께 제공하는 승인된 서버, 동일 사용자 연결 정책·C 배포 identity·provider·기존 JWT 경계의 실환경 통합은 별도 인수다. 현재 선택 C App만으로 이 의존성을 충족하지 않는다.
- 사용자의 MetaMask 직접 동작은 미실행. C direct UI의 OPEN/CLOSE 최소 경로는 기존 전체 감사/자동청산 인수와 다르다.

## 최종 동결 전 Report 카운트다운 보완
- root의 자체 검토 권고를 반영해 원본 시연의 Report 남은 30초 표시와 만료 시 전송 버튼 비활성을 복원했다. 금융 계약·만료 시각·owner wallet 검증은 변경하지 않았다.
- C 탭 active 동안에만 1초 timer를 유지하고 route/context 변경·로그아웃 때 해제한다. Node timer가 있으면 unref하며 테스트는 fake clock/scheduler로 실제 대기 없이 수행한다.
- pending 손상 시 전송/신규 거래 버튼을 fail-closed 처리한다. 만료 후 자동 재가격·재서명·재전송 및 pending 삭제는 없다.
- 추가 fake-clock 회귀 1개 포함 동일 선택 Node 명령 최종 41 PASS / FAIL0 / SKIP0, 약0.2초. 앞선 40 PASS는 보완 전 이력이다. 전체 build는 root 실환경 Gate 종료 신호까지 계속 보류했다.

## 최초 독립 검토 지적 2건과 최소 수정 후 최종 검증
- 최초 검토: 같은 session epoch의 연속 계정 새로고침 응답 역전이 과거 잔고를 최신 값 위에 표시할 수 있음. 서로 다른 positionId의 연속 조회도 같은 문제가 있으며 검토자가 Mock으로 재현했다.
- 수정: account refresh에 latest serial + epoch ticket을 두고 계정/참고시세 응답을 함께 받은 뒤 현재 ticket인 경우에만 한 번에 표시한다. position 조회는 호출 시 입력 ID를 고정하고 request serial + epoch + 현재 입력 일치 조건으로 selected/CLOSE 필드/DOM을 갱신한다. route/logout/owner 변경은 기존 epoch 경계로 무효화한다.
- 추가 deferred 회귀 2개: 같은 epoch에서 계정/시세 역순 응답과 두 응답 완성 전 표시 금지, owner 변경 후 이전 응답 무시; 서로 다른 positionId 역순·입력 편집·logout 후 응답 무시와 CLOSE 담보 유지.
- 최초 검토의 두 번째 지적: 최신 P0-B ADMIN 선물 감사 표시가 누락됨. AI presentation의 `src/futures-audit-panel.js`와 관련 `test/futures-audit.test.js` 두 파일만 선별 재사용했다. 새 금융/API/권한 규칙 추가는 없다.
- 수정 후 선택 Node 검증: `node --test test/c-existing-web.test.js test/c-direct.test.js test/ai-futures-evidence.test.js test/futures-display.test.js test/futures-audit.test.js` 52 PASS / FAIL0 / SKIP0.
- root의 실제 복원 Gate 종료 신호 후 순차 실행: `npm test` **172 PASS / FAIL0 / SKIP0** (1.65초), 이어 `npm run build` **PASS**, Vite7.3.6 / 107 modules (1.76초).
- Node 설치나 네트워크 접근 없이 새 worktree의 `tools/websocket-test-client/node_modules` junction만 기존 AI presentation 캐시에 연결했다. cache 삭제·설치·원본 제품 파일 수정은 없다. 생성 dist와 junction은 ignored 산출물이다.
- 최종 `git diff --check` PASS. 원본 P0-B 두 파일은 선별 읽기 전후 exact 원문 불변을 재확인했다. 독립 재검토는 아직 대기이며 구현자 PASS와 독립 검토 완료를 혼동하지 않는다.
- 실제 PG/Anvil/MetaMask·단일 full TMS+C live 서버 인수·유료 호출·commit/merge/push는 이 단위에서 계속 NOT_RUN이다.

## 최종 독립 재검토 결과

`review_c_direct_web`의 수정 후 재검토1/2: **발견된 필수 수정 없음**. account/market latest serial+epoch 및 두 응답 동시완성 렌더, position captured input+serial+epoch와 deferred 역전/owner/logout 회귀로 최초 지적1을 해결했다. 최신 P0-B panel/test는 AI presentation 원본과 LF 정규화 후 동일하며 최초 지적2를 해결했다. 검토자 직접 선택52 PASS/FAIL0/SKIP0, 제품·증거 파일 수정0이다. 이 결과는 웹 소스·Mock 표시 통합 범위이고 실제 full TMS+C 서버 완료를 뜻하지 않는다.

## 선택 브라우저 Mock 캡처 시도 — 캡처 NOT_RUN / 하네스 중단
- root 요청으로 기존 browser-smoke 하네스 재사용 가능성을 읽기 확인했다. AI presentation 하네스는 ephemeral HTTP fixture, 전용 mkdtemp profile, headless Chrome, 동적 DevTools port 및 소유 Browser.close 방식이고 기존 audit 1366×768 / 390×844 캡처가 있다. 사용자 Chrome 탭·프로필을 제어하지 않는다.
- 제품·기존 도구 파일을 수정하지 않고 ignored `tools/websocket-test-client/dist/.smoke/c-existing-browser.mjs` runtime 사본에 C 미지원 서버 안내/비활성 탭의 두 viewport 캡처만 최소 삽입했다. 실제 C 설정·잔고·지갑을 위조하지 않았다. 로컬 fixture 외 실제 PG/Anvil/MetaMask/AI 호출은 없다.
- `node dist/.smoke/c-existing-browser.mjs --polish-after` 실행은 전용 profile과 DevToolsActivePort를 만든 뒤 첫 PNG 전 CDP 초기 단계에서 진척 없이 대기했다. 기존 command 응답에 시간 제한이 없어 root 지시에 따라 한 번만 시도하고 소유 helper를 인터럽트 종료(exit1)했다. 반복 브라우저 기동은 하지 않았다.
- 증거: `tools/websocket-test-client/dist/.smoke/c-existing-9FHtj7/` 및 하위 `profile-QGoB71/DevToolsActivePort`. PNG 0개이므로 C 탭·audit의 시각 QA는 **NOT_RUN**이다. 하네스 실행이 성공했다거나 실사용 MetaMask/live 인수가 통과했다고 주장하지 않는다.
- 종료 후 해당 소유 profile의 DevTools 주소에 한정해 Browser.close를 시도했으나 socket 연결 불가였고, 같은 port의 HTTP endpoint도 연결 불가였다. 사용자 Chrome에 대한 kill/close는 하지 않았다. 별도 Win32_Process 확인은 sandbox 접근 거부여서 프로세스 전수 종료 여부까지 단정하지 않는다.
- 제품 코드 동결·172 Node PASS / Vite build PASS 결과는 그대로이며 이번 시도는 브라우저 시각 검증 성공 증거에 포함하지 않는다.

## 다음 최소 연결 단위 — 읽기 분석만 / 구현·환경 생성 없음
- 근거 출처는 보존된 C worktree의 `backend/src/main/java/com/pricetrack/exchange/` 및 `deployment/CDirectDemoApplication.java`이다. `ExchangeApplication`의 package scan은 C config/controller를 발견할 수 있고 `CCustodyRuntimeConfig`·`CDirectRuntimeConfig`는 각각 opt-in이다. 두 config가 받는 `DataSource ds`는 fullApp의 공통 datasource이며 별도 C DB 분리나 `sharedDataSource`라는 신규 bean은 없다. config 자체는 schema 설치/worker를 시작하지 않는다.
- 공통 인증은 그대로 재사용 가능하다. `JwtAuthenticationFilter`가 JWT subject의 userId로 **현재 DB** `UserRepository`를 조회하고 C controller는 같은 `AuthenticatedUser.userId`를 사용한다. `c-wallet-schema.sql`은 `public.users(id)` FK, `c-consent-schema.sql`과 `c-direct-execution-schema.sql`은 deployment/user/owner linkage FK를 둔다. 다른 DB의 uid1·loginId가 같다는 사실만으로 동일인으로 합치거나 기존 지갑 연결을 재귀속하면 안 된다. 새 full fixture의 정식 signup/로그인·본인 지갑 proof를 사용하며 legacy mKRW↔cMKRW 변환은 없다.
- 명확한 실제 연결 차이: `/api/c-futures/direct/market`은 demo 조건부 `CDirectDemoApplication.DemoController`에만 있고 제품 `CDirectController`에는 없다. DemoController 전체를 fullApp에 import하면 기존 `AuthController`의 login/me mapping과 충돌하고 demo의 최소 사용자 응답은 제품 nickname 등도 생략하므로 재사용 대상이 아니다. 최소 적응 후보는 **NEWUI c-api-adapter의 참고시세 조회만 기존 `ApiClient.market()` / `/api/markets/mSEC`로 연결하는 1개 파일 + Mock 회귀**다. 이는 화면의 참고시세에 한정하며 실제 draft/execution 가격·E8/E18·서명·calldata는 기존 `CReportCandidateBuilder`→`MarketPriceService` 경로를 그대로 쓴다. 전용 C market URL 보존이 필수라면 C 전용 소형 read controller + MVC 테스트가 대안이며 인증 controller를 옮기지 않는다. 이번에는 어느 쪽도 구현하지 않았다.
- 시작 안전성: application.yml의 AI/자동진단/legacy blockchain/price-report/futures runtime/conditional 기본값은 false이고 `AiConfiguration`은 off일 때 provider/pool도 만들지 않는다. legacy recovery는 enabled 확인 후 반환하고 conditional worker도 service.enabled로 막히며 FuturesWorker는 두 futures flag가 켜져야 생성된다. 그러나 **fullApp 전체가 무부작용은 아니다**: spring.sql.init.mode 기본 always, 무조건 `ConditionalSchema`·`FaucetStore`의 PostConstruct schema 설치, Synthetic tick 영속 갱신이 있다. `AdminAccountInitializer`는 password가 설정되면 사용자/장부를 만들 수 있다. 따라서 demoDB에 full schema를 적용하거나 기존 8d App 설정을 바꿔 fullApp을 기동하면 안 된다.
- 실제 지원 범위: fullApp는 기존 시장/candles·auth/me·portfolio/orders·기존 STOMP `/ws`·SockJS를 제공한다(`MarketController`, `AuthController`, `websocket/config/WebSocketConfig`). C REST config/wallet/account/position/direct/receipt는 opt-in으로 재사용 가능하지만 **C 전용 WS destination/event는 없다**(`WebSocketDestinations`·subscribe allowlist는 기존 price/trades/orders/portfolio/futures/conditional만). 현 C UI는 수동 REST 조회 방식이며 C를 legacy futures WS/AI UUID Skill에 연결하지 않는다. legacy 기능이 off인 첫 단위에서 선물·감사·AI까지 live PASS라고 주장하지 않는다.
- 다음 실행 가능한 가장 작은 단위: 별도 소스/환경 fixture에서 위 참고시세 adapter 선택을 먼저 확정·독립 검토하고, **새 격리 fullApp DB·포트·JWT·사용자·전용 C chain snapshot clone**으로 fullApp 기동→정식 로그인/me→기존 market/캔들/WS read→같은 JWT의 C config 및 wallet/account read 경계만 검증한다. 승인된 clone 원본은 읽기만 하고 C 149/compiled/현재8d·기존DB/chain·NEWUI 동결 산출물은 변경하지 않는다. clone의 genesis/codeHash/deployment identity와 users↔wallet mapping은 별도 증명하며 복사 uid 일치로 대체하지 않는다. 최초 단위는 지갑 거래/새 funding/주문/청산/legacy recovery/유료AI 없이 두 사용자 교차 접근 401/403/404와 fail-closed를 확인한다.
- direct 실행은 그 다음 별도 단위다. `CDirectOracleConfig`는 내부 token file을 요구하고 `CDirectOracleClient`는 고정 `http://oracle:8091/sign` bridge를 호출한다. 단순 direct flag=true만으로 완결되지 않으며 새 clone 전용 bridge/권한·origin·RPC·SQL role/schema 적용 순서 검증이 필요하다. 공통 auth/security/금융규칙 변경은 최소 연결에 필요하지 않다. 이번 절은 read-only 코드 분석이며 제품 수정/새 DB·Anvil·App 생성/실제 REST·WS 호출/추가 테스트는 전부 미실행이다.

## 후속 최소 구현 — full 제품용 C 참고시세 read controller
- root의 후속 승인으로 NEWUI에만 `backend/src/main/java/com/pricetrack/exchange/custody/direct/CDirectMarketController.java`와 관련 `CDirectMarketControllerTest.java`를 추가했다. 앞선 분석의 frontend 참고시세 adapter 대안 대신 **동일 C URL 보존**을 선택했고 frontend는 다시 수정하지 않았다.
- `GET /api/c-futures/direct/market`은 제품 `MarketPriceService.current()`를 한 번 읽고 demo 원문과 동일한 **6개 문자열 필드** symbol/price/provider/observedAt/marketStatus/priceStatus만 반환한다. price는 toPlainString이며 새로운 스케일·TTL·Report·정책·nonce·서명·실행 권한은 없다.
- direct.enabled=true의 명시 opt-in에서만 등록된다. demo.enabled가 missing 또는 정확한 false일 때만 허용하는 별도 condition으로 demo=true/unknown/blank를 fail-closed 처리해 demo mapping과 충돌하지 않는다. 기존 CWORK의 DemoController/149/compiled/현재8d는 수정하지 않았다. SQL/C DB/다른 C controller source를 복사하지 않았다.
- 기존 `AuthenticatedUser` principal을 받고 null/nonpositive uid는 기존 `InvalidTokenException`을 던진다. 기존 GlobalExceptionHandler가 이를 401로 매핑하며 SecurityConfig/JWT 필터는 변경하지 않았다. 테스트에서 null uid 경계는 직접 JUnit으로 검증했고 **실제 JWT filter-chain 또는 servlet HTTP 401 인수는 하지 않았다**.
- 검증 7개: default/false/unknown direct off, explicit on+demo missing/false, demo true/unknown/blank 배제, null/nonpositive principal의 provider 조회 전 차단, exact 6필드/큰 decimal 문자열, standalone MockMvc AuthenticationPrincipal resolver의 GET200·POST405, provider 실패를 fake 데이터로 대체하지 않음.
- 기존 cached direct-JUnit 하네스를 ignored `deployment/runtime/c-market-read-offline/`에 명시 source-list/단일 test selector로 최소 재사용했다. Gradle·네트워크·PG·RPC·Docker·실계정·유료 호출은 0이다. 기존 app class를 classpath에서 제외하고 제3자 cache JAR만 읽었다.
- 최초 run `20261023T185708Z-11bc9fa4`: source-list의 MarketDataEligibility 누락 및 sandbox cache JAR AccessDenied로 compile1/test미실행. 실패 증거를 보존하고 source-list만 보완한 뒤 sandbox 권한 승인을 받아 재실행했다.
- 최종 run `20261023T185813Z-f075d2f6`: compile0/test0, **JUnit7 PASS / FAIL0 / SKIP0 / ABORT0**, 약5.64초. sourceHash `1f5ba30b2a8aa9a5a132382e76aa853521e527fff4a020a2eb1df30ea5552bbd`, sourcesUnchanged/dependenciesUnchanged=true. 증거는 `deployment/runtime/c-market-read-results/<run>/`에 보존했다.
- 이 결과는 조건부 Spring context + standalone MockMvc/직접 JUnit에 한정하며 fullApp 기동/실HTTP/DB 연결/기존 보존 C App 인수가 아니다. 기존 웹172/build는 변경 없는 웹의 선행 결과로 유지하고 재실행하지 않았다. 새 endpoint 단위의 독립 최초 검토는 요청 대기다.

## full TMS+C 읽기 연결 Gate 준비 — 혼합 소스 컴파일 가능성만 확인
- 제품 추가 변경 없이 NEWUI fullApp main 소스277개와 CWORK read/direct opt-in entry 6개에서 시작한 의존 closure31개를 **원본 절대경로 읽기 전용 참조**로 선택했다. C 전체53개 중22개는 선택하지 않았고 source 복사/기존 compiled·149 class 재사용/덮어쓰기/launcher 변경은 0이다. seed는 CCustodyRuntimeConfig, CCustodyController, CDirectRuntimeConfig, CDirectOracleConfig, CDirectController, CDirectReceiptController이며 NEWUI의 CDirectMarketController는 fullApp277개 안에 포함된다.
- manifest의 `cClosure`·`closureEdges`가 필요한 C import/같은-package type token 참조의 근거 목록이다. ABI codec/models, wallet proof/store, consent/report-builder/typed-data, direct service/oracle/receipt/store, EIP712/rules-transition proof, C runtime RPC/identity 경계만 선택했다. compiler에는 빈 sourcepath와 explicit source list·implicit:none를 사용해 선택 밖 원본을 자동 수집하지 않았다.
- 공통 상대경로276개는 NEWUI와 CWORK **raw SHA256가 모두 동일**했다. 같은 FQCN은 NEWUI 한 벌만 선택하고 sourcepath/compile classpath에 기존 app classes/JAR를 넣지 않아 shadowing을 막았다. classpath는 검증된 제3자 cached runtime JAR123개와 compile 전용 Lombok1.18.34이며 기존 c-direct-demo-check의 javac_args(server) flag builder만 재사용했다. test JAR/H2·demo entrypoint/driver는 제외했다.
- 준비물: `deployment/runtime/c-full-read-compile-results/20261023T190509Z-a2f8c06a/`의 manifest.json·compile.args·classes·result.json. manifest SHA256 `2273497a3e6d88cf0b3778995576880082bc00eaaf8679402f0015503a202934`. **혼합 제품308소스 + metadata probe 1개의 bounded javac 1회 compile0**, 13.094초. source/dependency hashes 불변=true, 컴파일 경고·오류 없음. main 소스를 수정하거나 full C folder를 복사하지 않았다.
- identity.json의 sourceIdentity `7cf2f8d991b0847526cbe501e9fc888cafdd5ff23c31e566214391569058e571`는 정렬된 (origin, absolutePath, sourceSHA256) tuple의 canonical JSON SHA256다. compiled tree SHA256 `83ce1d83ce09631aaf34565b95268e7846cba4b3440bd0d8d58c981fb399bf30`는 초기 metadata helper를 포함한 607개 class의 relativePath→SHA256 canonical JSON 해시다. 후속 parameter probe는 별도 디렉터리이므로 이 compiled tree를 덮어쓰지 않았다.
- 비기동 metadata probe는 Class.forName(name, **false**, loader) 및 선언 method 정보만 읽어 fullApp/C endpoint/config 8개가 새 classes에서 로드되는지 확인했다(exit0). SpringApplication.run·bean 생성·application static 초기화는 하지 않았다. 이어 full compile을 재실행하지 않고 **별도 parameter-probe-classes**에서 작은 probe를 컴파일하여 기존 C PathVariable/RequestHeader와 새 market(user)의 bound parameter22개 isNamePresent/name을 확인했다(exit0). `-parameters` 보존, marketUser/pathVariable/requestHeader 모두 true, 기존 full compiled/dependency 불변=true다.
- 이번 준비의 DB·Anvil·App·RPC·소켓·Docker·사용자 claim 소비·테스트·유료 호출은 모두 0이다. 결과는 **컴파일 및 classloader/parameter 메타데이터 진단**이며 fullApp 기동·권한·실제 읽기 Gate PASS가 아니다. SQL/resources/runtime origin/DB role·users↔wallet mapping·C state clone/런타임 bean 조건 충돌·JWT/WS 통합은 검증하지 않았다. 새 fixture/entrypoint 실행 설계와 실제 연결 인수 및 준비물 독립 검토는 별도 단위로 남긴다. 기존 발표용 same-state launcher와 이 준비 산출물을 섞지 않았다.

## fullApp+C 읽기 실환경 Gate 하네스 준비 — 당시 필수 사전조건 BLOCKED
- 후속 승인 범위에서 기존 launch/DB/schema/snapshot/relay helper를 **읽기만** 대조했다. 아직 실행 가능한 fullApp read Gate 하네스는 만들지 않았고 live 실행을 허용할 준비 상태도 아니다. 이번 단위의 제품/기존 helper 수정, 새 DB·Anvil·App·Docker·proof key 생성·snapshot/claim 소비는 0이며, 새 guard/compiled selftest도 미실행이다. 앞선 308소스 컴파일·metadata PASS는 그 범위로만 유지한다.
- **차단1 — fullApp runtime 최소권한과 Faucet startup DDL의 충돌.** `wallet/faucet/FaucetStore.java:27-37`의 PostConstruct는 classpath faucet-schema.sql 실행 후 PostgreSQL에서 `CREATE OR REPLACE FUNCTION reject_faucet_mutation`과 trigger 확인/생성, policy_versions/budget_usage INSERT를 수행한다. `spring.sql.init.mode=never`로 이 코드를 끌 수 없다. `FaucetService.credit/status`에도 enabled guard가 없고 설정은 policy-version뿐이다. 따라서 앞선 준비 문구의 “faucet 명시 off”는 **존재하지 않는 flag를 가정한 목표였으며 실제 가능한 설정이 아니다**. gate-only HTTP deny는 faucet 금융 POST를 차단할 수 있지만 bean 시작의 DDL/DML 권한 문제를 해결하지 않는다.
- 기존 `CDirectDemoDriver.databaseBootstrap`은 API role에 NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOINHERIT 및 제한 table DML만 부여하고 public CREATE를 회수하며 API DDL이 SQLSTATE42501로 거부되는지 확인한다. 이 역할을 그대로 fullApp에 쓰면 위 function CREATE OR REPLACE를 수행할 소유권/DDL 경계와 맞지 않는다. preinstall만으로 해결됐다고 주장하거나 API를 function/table owner로 격상·CREATE 권한 부여·임의 bean 교체로 통과시키지 않았다. 기존 demoDB에 full schema를 설치하는 우회도 하지 않았다.
- **차단2 — 기존 driver/relay는 이번 read-only fork Gate와 그대로 호환되지 않음.** `CDirectDemoDriver.chainBootstrap`은 anvil_setBalance·새 계약 deploy·token transfer·house funding을 수행하고 databaseBootstrap은 축소 users schema와 고정 uid1/2를 만든다. 이번 no-deploy/no-funding/no-tx·새 full users·uid 동일인 추정 금지 조건에 맞지 않으므로 bootstrap을 실행하거나 재사용 가능한 read driver라고 포장하지 않았다. 새 시험 계정/독립 proof wallet은 기존 사용자 주소를 재귀속하지 않아야 하며, 필요한 HTTP auth/me·market/candle·STOMP read + C ownership 교차 접근 driver는 아직 별도 구현/검증이 필요하다.
- 기존 `c-anvil-snapshot-clone-check.py`는 network=none·127.0.0.1·RO snapshot mount·정해진 과거 출처의 clone 검증이고, 기존 `c_direct_browser_relay.py`/`CDirectBrowserPipe`는 host loopback listener와 opaque APP/RPC byte pipe다. 전자는 fullApp 내부망 App 연결을 제공하지 않고 후자는 RPC method/HTTP write deny 또는 이번 no-user-URL 경계를 구현하지 않는다. 이름/owner/hash/STOP 보호는 참고 가능하지만 이를 fullApp read-only 보안 경계가 이미 구현됐다는 증거로 확대하지 않는다. 현재8d 공식 checkpoint·resume claim·실사용자 MetaMask URL에는 접근/변경하지 않았다.
- **안전한 다음 단위:** 먼저 Faucet startup의 DDL bootstrap/runtime 책임 분리를 최소권한 관점에서 별도 승인·설계·검토한다(정책/지급/장부 규칙 변경 없이 필요한 lifecycle 경계만). 그 전까지 fullApp read Gate는 실행 금지다. 해결 후에만 새 freshDB owner 설치→제한 API 권한, gate-only HTTP allowlist/denyOracle, RO RPC fork 경계, 새 시험 사용자·proof/WS driver, bounded watchdog/STOP·소유 자원 정지와 증거 보존을 기존 helper의 작은 적응 단위로 각각 검토할 수 있다. 이 문서는 그 설계나 권한 확대를 승인하거나 구현한 기록이 아니다.

## 후속 승인 단위 — bootstrap/runtime startup 책임 최소 분리
- NEWUI에만 공통 `SchemaStartupMode`/`PreinstalledSchemaVerifier`와 실제 `FaucetStore`·`ConditionalSchema`의 verify 분기를 추가했다. 공통 설정은 `app.schema.startup-mode=install`(기본) 또는 `verify-existing`이며 후자는 `local-read-gate` active profile + 명시적 `spring.sql.init.mode=never` + `spring.jpa.hibernate.ddl-auto=none` 조건에서만 허용한다. unknown/blank/wrong-case mode와 잘못된 local DDL 전제는 예외로 차단한다. 기본 YAML/default 속성은 바꾸지 않았다.
- 기존 FaucetStore 2인수·ConditionalSchema 1인수 생성자를 유지하고 install로 위임한다. Spring 생성자만 추가 mode를 받는다. Faucet install 원문 구간의 정규화 텍스트 동일=true, FaucetService/FaucetPolicy/faucet-schema.sql/conditional-schema.sql rawSHA가 frozen CWORK와 동일함을 대조했다. credit/status 계산·정책/CONTENT/hash·grant·기존 SQL 정의·package-private initialize 가시성은 바꾸지 않았다. owner bootstrap fixture bridge 구현/실행은 아직 하지 않았다.
- verify-existing은 PostgreSQL 전용이며 실제 Store/initializer가 **SELECT만** 실행한다. 고정 catalog 검증 범위는 직접 superuser/createdb/createrole·public/database CREATE 권한 부재, 대상 table의 owner!=current_user 및 필수 column type/nullability·PK/unique/FK, Faucet 함수의 plpgsql/trigger signature·security-definer/config/volatile 제약과 **strip 후 정확한 원본 body**, 3개 테이블의 BEFORE UPDATE/DELETE/TRUNCATE statement trigger(tgtype58)·enabled O·원본 함수/무조건/인수 없음, Conditional의 두 valid/ready btree index다.
- Faucet는 budget row가 정확히1개인지 확인하고 **기존 actual Store.configured()/consistent()의 읽기 검사**를 재사용한다. initialized=false/activeVersion·policy CONTENT/hash 상충·journal 상충은 준비 실패이며 UPDATE/INSERT로 활성화·수리하지 않는다. 이 mode는 faucet을 비활성화하는 새 경제정책 flag가 아니라 초기화 책임 설정이다. API role의 owner/CREATE/superuser 격상은 없다.
- 범위 한계: 이 고정 verifier는 CHECK 전체 정의·전체 DB DDL 등가성·모든 role inheritance를 감사하는 범용 도구가 아니다. 미래 fresh Gate API role은 owner-role membership 없는 명시 생성·검증이 추가 전제다. mode는 **두 initializer만** 제어하며 constructor 검사보다 먼저 수행될 수 있는 Spring auto schema/다른 bootstrap까지 전역 차단하지 않는다. Gate는 Spring 기동 전에도 sql.never/jpa.none 및 다른 finance/external flag off를 검증해야 한다. 실제 PostgreSQL catalog/query 정합성과 owner bootstrap→runtime least-privilege 경계는 아직 NOT_RUN이므로 live Gate 실행 허가는 아니다.
- 신규 테스트는 실제 FaucetStore/ConditionalSchema에 JDBC **transport** fixture를 연결한 SELECT-only/정상 catalog/missing column·key·index·unsafe role·잘못된 함수 body·disabled/row-level/wrong-function/missing trigger·policy/budget/journal 상충·비PG·mode/profile/DDL 전제 negative 검증이다. fake Store/서비스 대체는 없다. Mock catalog는 PostgreSQL 실증거가 아니다.
- 최초 run `20261023T192628Z-64ea8c76`: fresh compile0, 신규10 PASS/2 FAIL, 기존 default 금융 H2 **24 PASS**. 두 실패는 JDBC 테스트 fixture가 BigDecimal bind를 String으로 잘못 cast한 결함이며 제품 분기를 수정하지 않고 fixture만 보완했다. 실패 원본 로그와 산출물을 보존했다.
- 최종 run `20261023T192743Z-bffa1e01`: compile0, 신규 **12 PASS**, 기존 FaucetPolicy/FaucetIntegration/ConditionalOrderIntegration **24 PASS**, FAIL0/SKIP0/ABORT0. 약26.97초, sourceIdentity `22cfb7b4643a0d0e6fd4fc5521df4a7097287c9e34183683a68a46a7953b1ac2`, sources/dependencies unchanged=true. 증거 `deployment/runtime/schema-startup-results/<run>/`, cached 명시 compile/JUnit 준비 `deployment/runtime/schema-startup-offline/`. 실제 PG·RPC·Docker·외부 provider·owner bootstrap·실환경 Gate 호출은0이다. H2 Spring test context를 실제 fullApp live HTTP/권한 인수로 합산하지 않는다.
- 이번 승인으로 **NEWUI 공통 원문2개(Store/Conditional)가 CWORK와 의도적으로 달라졌다**. 이전 308소스 manifest/compiled는 이전 단위 증거로 보존하며 현재 source identity로 덮어쓰거나 발표용149/8d launcher에 섞지 않았다. NEWUI 외 frozen CWORK·기존149/compiled·8d·공식 checkpoint/claim은 변경·소비하지 않았다. 기존 웹172/build는 웹 추가 변경 없는 선행 결과이며 재실행하지 않았다. 현재 소스 동결 후 이 startup 단위의 별도 최초 독립 검토를 요청한다.

## startup 분리 최초 독립 검토 1건 보완·재검토 요청
- 최초 검토의 필수1건 수용: 보호 trigger가 있는 `faucet_grants` 자체가 기존 column/직접 owner 검사 대상에서 빠져 있었다. API가 이 표의 직접 owner이면 trigger disable/drop 권한을 가질 수 있으므로 단순 trigger 존재 검사로 충분하지 않다.
- 원본 `schema.sql`을 재대조하여 `faucet_grants`의 id VARCHAR(36) PK, user_id BIGINT NOT NULL, amount NUMERIC(30,18) NOT NULL, created_at TIMESTAMP NOT NULL을 **기존 columns(owner!=current_user 포함) 및 PK 검사**에만 추가했다. 범용 role inheritance 감사로 확대하지 않았다. 기존 두 initializer·생성자·install 본문·서비스·정책·SQL은 이 보완에서 변경하지 않았다.
- 실제 verifier/Store를 쓰는 JDBC transport fixture에 API 직접 소유 표 모델을 추가하고 grants owner/필수 column 누락/PK 누락을 차단하는 negative 1개를 추가했다. 수정 main은 새 classes로 재컴파일했으며 원래 compiled를 덮어쓰지 않았다.
- 새 run `20261023T193346Z-a2f3c6fd`: compile0, 신규 **13 PASS / FAIL0 / SKIP0 / ABORT0**, 15.375초. sourceIdentity `b0055b7cce8cbda8baa40560cf00a667f2e39be8813c72334f29fda9fd4ec226`, sources/dependencies unchanged=true. 증거 `deployment/runtime/schema-startup-results/<run>/`, 재사용 준비 사본 `deployment/runtime/schema-startup-offline/verifyfix.py`.
- default H2 금융 **24 PASS는 bffa1e01의 선행 결과를 재사용**한 것이며 이번에 반복 실행하지 않았다. 두 initializer/default 경로·서비스/정책/SQL의 source SHA가 그 실행과 같음을 확인했다. 수정 전후 공통 입력에서 달라진 것은 verifier와 transport fixture/negative test 3개뿐이다. 이 사실을 최신 전체 금융 재실행 PASS로 표현하지 않는다.
- 실제 PostgreSQL catalog·권한/owner bootstrap·Gate/Anvil/Docker는 계속0/NOT_RUN이다. source 동결 후 같은 검토자에게 재검토1/2를 요청하며, 아직 이번 보완의 독립 재검토 완료를 주장하지 않는다.

## 다음 작은 actual PG 단위 — 재사용 조사와 계획만
- 조사 결과 `c-consent-postgres-check.py`의 **PG+Java 두 컨테이너** 경계가 가장 가깝다. pinned Linux PG/JDK image, min RAM1920MiB/disk2GiB, 내부 전용 subnet/owner label·충돌 검사, RO fixture+RW evidence, bounded150/180초, 소유 컨테이너만 정지·volume/network/증거 보존 패턴을 재사용할 수 있다. `resources/tree_hashes/quote/observation/clean_env` 및 `c_gate_network.select_subnet`은 직접 재사용 후보이며 raw inspect/SQL 오류를 기록하지 않는 observation 정책을 유지한다.
- 기존 `run_gate/prepare` 전체 호출은 부적합하다. CORE_HASH·C schema/resources·기존 consent test6개·entrypoint가 고정되어 있고 CConsentPostgresTests의 wallet/consent INSERT/UPDATE 예외 grant는 이번 **SELECT-only** API role과 다르다. 기존 파일/런/claim을 변조하거나 C4b/E1a의 예외권한을 가져오지 않는다. 새 unit의 source manifest/entrypoint·정확한 검사 결과에 맞춘 작은 별도 adapter가 필요하다. deploy/funding가 있는 demo 하네스는 사용하지 않는다.
- 최소 production compile closure는 `SchemaStartupMode/PreinstalledSchemaVerifier/FaucetStore/FaucetPolicy/FaucetFailure/ConditionalSchema` **6소스**다. package-private initialize는 제품 가시성을 바꾸지 않고 **fixture 동일-package bridge 2개**로 actual install/verify 경로를 호출할 수 있다. 여기에 단일 direct JDBC/JUnit runner만 추가하면 fullApp/Anvil/C custody source 전체를 컴파일하거나 기동할 필요가 없다. 원본 core schema.sql·faucet-schema.sql·conditional-schema.sql의 classpath/원문 SHA selftest와 -parameters/소스·JAR·class 해시 검증을 먼저 준비하는 안이다.
- 새 empty DB의 owner가 원본 schema.sql을 적용하고, 실제 FaucetStore의 install과 실제 ConditionalSchema install을 호출한다. 원본 정책/SQL을 재작성하지 않는다. core schema.sql의 assets 두 종 metadata INSERT와 empty users 대상 0개 balance 보완 SELECT는 원본 동작으로 구분하며 사용자·장부 funding/주문·키/체인 동작은 없다. API는 NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOINHERIT, owner membership0·public/database CREATE0·대상 owner0을 확인하고 필요한 fixed table SELECT만 받는다.
- 최소 검사 제안은 **6개**: (1) 두 actual initializer의 API verify-existing positive + 관측 SQL 전부 SELECT + 원본 row/metadata 보존, (2) API CREATE/INSERT/trigger 변경 등 통제된 시도의 SQLSTATE42501, (3) grants 직접 owner를 갖는 잘못된 principal 거부, (4) disabled trigger 거부, (5) 잘못된 함수 body 거부, (6) actual Store의 configuredVersion만 non-active-policy로 바꾼 거부. 정책 append-only row는 수정하지 않는다. 3번은 baseline API를 영구 격상하지 않도록 **별도 negative principal/owner 메타 주입 후 복원** 범위를 최초 검토에서 확정한다. 4/5도 새 fixture owner가 메타만 주입하고 원본으로 복원한 뒤 API positive와 fingerprint를 재확인한다. 사용자 데이터 삭제·정책 행 강제 수정·전면 예외시나리오 재구현은 하지 않는다.
- DB PASSWORD는 기존 private env 파일/JDBC 경로로만 주고 SQL role 생성 시 statement/error parameter logging 억제 패턴을 재사용한다. stdout/stderr 원문 대신 byte count·safe code/SQLSTATE만 남긴다. offline prepare는 compile/resource selftest/pure guards까지만, 이후 별도 최초 검토와 자원 판단 후 새 PG1+Java1의 1회 실행을 결정한다. **현재는 읽기 조사·계획뿐이며 코드 작성/새 fixture 생성/컴파일/PG·Docker·원본8d·claim 소비 모두0**다.

## PG startup 단독 하네스 — 실행 전 오프라인 구현·준비 이력
- 후속 승인으로 신규 파일5개만 추가했다: `deployment/schema-startup-postgres-check.py`, pure guard test, `SchemaStartupPostgresTests.java`, Faucet/Conditional fixture package-peer bridge2개. production6소스는 기존 검토된 actual initializer/helper/policy를 그대로 읽으며, 제품 source/정책/SQL을 다시 수정하지 않았다. 기존 C 하네스 원문·149/8d·공식 checkpoint/claim에는 쓰지 않았다.
- adapter는 기존 consent PG helper의 resource/subnet/argv·container isolation·safe observation·소유 cleanup 정책을 읽기 전용 재사용한다. 새 Java entrypoint를 먼저 exact 비교하고 그 한 항목만 기존 isolation validator 입력으로 대응시켜 검증한다. 신규 sourceIdentity는 **own files와 legacyHelper/networkHelper 두 파일 hash까지** 포함하며 prepare 종료·실행 진입·preflight 후 claim 직전·실행 종료에 동등성을 검사한다. 기존 consent SQL/예외 grant·기존 실제 성공6카운트를 가져오지 않는다.
- fixture owner는 원본 core schema.sql과 **actual Faucet/Conditional install 경로**를 실행하도록 구현했다. baseline API는 fixed table SELECT만 받으며 CREATE/owner/superuser/member/inherit/replication/bypassRLS 권한이 없는지 확인한다. bad-owner negative에는 별도 fixture principal만 사용하고 runtime API를 owner로 격상하지 않는다. 메타 주입은 새 fixture의 table owner/trigger enabled/function body에만 제한하고, finally에서 원본 owner/trigger/actual install로 복원한다. non-active-policy는 runtime configuredVersion만 다르게 주어 원본 정책 행을 건드리지 않는다.
- 신규 runner의 실제 목표6개는 API SELECT-only positive, DDL/INSERT/ALTER42501, 별도 wrong-owner principal, disabled trigger, wrong function body, wrong configuredVersion이다. 각 case 후 data/owner fingerprint와 actual API positive를 재확인한다. runner는 found/**started**/succeeded/failed/skipped/aborted와 containersFailed/failureCount를 구분하고 첫 실패 stage·안전 code/SQLSTATE만 남긴다. bootstrap 실패면 found6/started0을 PASS로 처리하지 않는다. **현재 이6개는 구현만 되었고 JDBC/JUnit 실제 실행은 하지 않았다.**
- 오프라인 최종 준비: `deployment/runtime/schema-pg-prepare/20261023T195604Z-28583e67/`, sourceIdentity `a39917fc3cf2e0dc06bb210a50378e52f8e65deccf2d3b6d582ecf467d10764a`. JDK21.0.10·명시9소스(제품6+fixture3) compile0, fresh class14개, cached JAR20개·artifact hashes 기록. 원문/fixture/classloader SQL3개 exact SHA, production 클래스 원본 경로 및 bridge -parameters metadata selftest PASS(JDBC·env·socket 미사용), pure guards **8 PASS**. prepared identity/helper hash 재확인 PASS, **RUN_CLAIM 없음**.
- 원본 SQL SHA: schema.sql `583848fc85aab0f66d3a4d871de83713562350417d8a9bf2a3f5964fb3a20018`, faucet-schema.sql `b09bc06a4f636932fc463d6675d25f7a8f4f1489d6e1e2e24a61051ecf315361`, conditional-schema.sql `01a6669cffef19a3783960eee7d22a6fa24ab3ffcaf5bd981a9ccae5ad42544b`.
- guards는 min1920MiB/2GiB·pinned image·host publish0·전용 내부망·실행 JVM/owner/mount/namespace·claim once·helper/fixture 변조·로그 secret 억제·소유2actor 정지를 검증한다. postclaim filecopy/env/hash staging 실패는 영구 claim과 새 evidence를 보존하고 resourceCreates0의 BLOCKED 결과를 best-effort 기록한다. 이 실패 경계 테스트는 subprocess 전체 Mock으로 수행했다. 예전 준비5aa752f6/f67d3722도 실제 실행하지 않고 보존했으며 최신 source는28583e67로 동결한다.
- 실제 run 경로는 아직 호출0이다. root 최초 독립 검토 PASS와 자원/동시 heavy test 판단 후에만 **새 PG1+Linux JDK actor1, 1회** 실행할 수 있다. actual PG/catalog·API 실제 권한 결과·신규6case started는 전부 NOT_RUN이다. App/Anvil/Chrome/웹/지갑·키/chain/paid·기존8d/claim 소비는0이며 이 준비를 full TMS+C live Gate PASS로 확대하지 않는다.

## 새 PG startup 실제 1회 검증 — 6 PASS / fullApp 읽기 Gate와 별개
- root가 별도 **최초 독립 검토의 필수 수정 없음**과 실행 전 자원/이미지 상태를 확인한 뒤 최신 prepare `20261023T195604Z-28583e67`로 1회 실행했다. 이번 정리는 새 실행이나 코드 변경 없이 아래 실제 산출물을 직접 읽어 대조한 기록이다.
- 실행 `ccpg-f767ff6a011147d0`, 결과 `ACTUAL_SCHEMA_STARTUP_PG_6_PASS_NOT_APP_OR_CHAIN`, safeError=null, 25.766초. sourceIdentity `a39917fc3cf2e0dc06bb210a50378e52f8e65deccf2d3b6d582ecf467d10764a`는 최신 준비물과 같다. 근거는 `deployment/runtime/schema-pg/ccpg-f767ff6a011147d0/result.json` 및 `java-evidence/result.json`이다.
- 신규 실제 JUnit **found6 / started6 / succeeded6**, testsFailed0 / containersFailed0 / failureCount0 / skipped0 / aborted0, firstFailureBoundary 비어 있음, failures=[]를 확인했다. 관측된 API SELECT 문233개다. actual owner install→API SELECT-only positive, 권한42501, 별도 wrong-owner principal·disabled trigger·wrong function body·non-active configuredVersion 거부가 이번 6case이며, 기존 E1a/C4b의 과거 성공6개를 재사용하거나 합산한 것이 아니다.
- Java·PG 각각 normalStop=true, 신규 network/volume preserved=true, sourcesAndFixturesUnchanged=true를 실제 result에서 확인했다. 새 fixture의 원본 정책/data·owner 복원 및 최소 API 권한은 runner의 각 case 후 검증에 포함되며, 이 결과를 모든 PostgreSQL schema/role inheritance의 일반 감사로 확대하지 않는다.
- 최신 prepare의 **RUN_CLAIM이 1회 소비되어 영구 보존**되었고 내용은 `ONE_NEW_SCHEMA_PG_UNIT_ATTEMPT_NO_RETRY`다. 같은 preparation의 재실행/자동 retry는 하지 않는다. 기존 C8d 공식 resume/checkpoint claim은 이 신규 단위의 대상이 아니며 소비하지 않았다. root가 기존 C8d 보존 hash의 중앙 checkpoint를 별도로 관리한다.
- 이번 실제 범위는 새 PG1+Linux JDK actor1의 startup/catalog/권한 단독 검증뿐이다. Anvil·ExchangeApplication 기동·실제 HTTP 로그인/me/시장·캔들·STOMP·C wallet/account·웹/Chrome·사용자 지갑/private key·chain transaction·유료 호출은0/NOT_RUN이다. **full TMS+C 읽기 Gate는 여전히 미완료**이며 별도 driver·read-only chain fork·gate-only deny 경계 및 기동 전 설정/자원 guard의 검토·준비가 필요하다. 이 문서 추기로 새 하네스/실환경을 추가 생성하지 않았다.

## fullApp+C 읽기 Gate — runner 포함 오프라인 준비 동결 / 실제 실행 NOT_RUN

독립 최초 검토 결과: **발견된 필수 수정 없음**. 검토자는 순수8 PASS, checked_prepare/source·artifact·공개anchor·미사용claim, compiled wrapper/Driver 자체 검사를 직접 확인했다. 실제 Docker/run은 검토 중 실행하지 않았다. 아래 검증 목표는 아직 실제 인수 결과가 아니다.

서명/시각 범위 정정: **개인키는 Driver 내부에서만 생성·보관**한다. 공개 지갑 주소와 소유 증명 서명은 실제 기존 challenge/link API에 전달되어 서버가 검증·저장한다. 새 Synthetic 시세는 실제 새 App의 공급자 값이고, C 체인은 과거8d checkpoint의 숨은 읽기 fork다. 체인 wall-clock 신선도·거래 만료 조건·새 거래 실행 가능성까지 검증한 것으로 표현하지 않는다.
- 후속 승인 범위의 새 파일은 `deployment/full-app-read-gate.py`, `FullAppReadGate.java`, `FullAppReadDriver.java`, `test_full_app_read_gate.py` 4개다. 기존 PG fixture bridge2개·PG resource/subnet/safe-observation helper·C javac flag builder만 재사용하며 이전 PG6 actual run은 반복하지 않았다. 이 단위는 제품 source/기존 C helper/발표 launcher를 변경하지 않았다.
- 새 full compile은 **NEWUI279 + CWORK RO closure31 = 제품310소스**와 실제 App wrapper를 대상으로 수행한다. historical308 compiled를 현재 산출물로 승계하지 않는다. 공통 원문 중 허용 차이는 검토된 FaucetStore/ConditionalSchema 2개뿐이며 두 helper와 CDirectMarketController까지 총5개 reviewed SHA를 literal pin했다. 나머지 공통 원문은 frozen CWORK와 raw SHA 동등성을 검사한다. 빈 sourcepath/implicit:none, runtime cached JAR123+Lombok, -parameters, source/helper/anchor/fixture hash를 기록한다.
- wrapper는 **실제 ExchangeApplication을 SpringApplication.run**으로 시작하도록 구현했다. 원본 application.yml을 유지하며 새 local properties를 겹친다. 원본 security auto-config exclude를 명시 유지하고 /tmp clean working directory·App env-file 없음으로 host .env/secret 유입을 막는다. Spring 시작 전 local verify-existing/sql.never/jpa.none·모든 legacy finance/AI/Google off·admin password blank를 확인한다. Faucet off flag는 만들지 않았다. gate-only HTTP allowlist는 auth signup/login·wallet proof 및 지정 GET/WS handshake만 통과시키고 금융 POST를 거부하며, 기존 SecurityConfig/JWT는 수정하지 않는다. 무권한 dummy Oracle token과 @Primary deny-Oracle port만 사용하고 Oracle actor/서명 키는 없다.
- owner Driver는 **새 empty DB에만 원본 core schema.sql + actual Faucet/Conditional install + c-wallet-schema.sql**을 설치한다. 미사용 C projection/nonce/consent/direct SQL은 설치하지 않는다. API는 auth signup용 users/balances INSERT와 시장/candle DML·wallet metadata에 필요한 권한만 받는다. C userLock SELECT FOR UPDATE에 필요한 `users UPDATE(nickname)` 한 컬럼을 명시 허용하고 id/role UPDATE는 거부 확인한다. challenges UPDATE는 실제 존재하는 consumed/proof_signature 2컬럼뿐이다. 보호 Faucet 표는 SELECT-only이고 owner/member/create/superuser 권한을 부여하지 않는다.
- 실제 목표는 fresh signup2→공통 login JWT/me→실HTTP 시장/candle/C config·wallet challenge/link/account·query 타인ID 무시 및 본인 principal→공통 401 AUTHENTICATION_REQUIRED/403 ACCESS_DENIED→native STOMP price MESSAGE다. 추가 deny가 위 공통 권한 응답을 대신 만들지 않도록 해당 GET은 기존 security chain으로 넘긴다. 시험 EOA 개인키는 Driver 안에서만 임시 생성·보관하고, 공개 주소와 소유 증명 서명은 기존 API로 App에 전달한다. 실제 App bean/dataSource/API role·deny port를 확인하며 새 users2/zero balances4·wallet links2와 주문/trade/grant0을 검사하도록 구현했다. 이 목표는 아직 실행 결과가 아니다.
- runner까지 같은 단위에 구현했다: pinned PG256/Anvil192/App768/Driver384MiB, 최대 동시1600MiB(bootstrap/verify Driver 순차), min RAM1920MiB/disk2GiB·내부망·host port0·owner scope NON_USER_READ_INTEGRATION·claim once·bounded240초 작업/330초 cleanup 포함이다. PG/Anvil/App/Driver 4역할이며 순차 Driver phase 컨테이너2개를 포함해 총5개 컨테이너를 보존한다. 원본8d snapshot은 RO mount만 하고 dump는 신규 evidence로만 출력한다. accounts0, deploy/fund/tx/시간강제변경 primitive는 없다. 사용자 URL/MetaMask·기존 PG volume·원본 oracle-key/token/pg.env는 사용하지 않는다. 공식 resume/claim 경로에 접근하지 않는다.
- claim 직전과 종료에 source/helper/public anchor·prepared/runtime fixture 동등성을 다시 확인한다. postclaim staging 실패는 claim을 영구 보존하고 resourceCreates0 BLOCKED를 best-effort 남긴다. 종료는 owned STOP→실제 ctx.close의 normal-stop marker→소유 actor만 bounded stop, 원본 anchor 및 fork transaction hash14개 동일성·volume/network 보존을 확인해야 PASS다. 제한 시간/중간 실패/불변성·종료 증거 부족은 BLOCKED이며 자동 재실행은 없다. 이는 임의 RPC를 방화벽으로 일반 차단하는 프록시가 아니라 키 없는 App/고정 읽기 Driver/비공개 내부망이라는 제한된 시험 경계다.
- 최초 오프라인 `20261023T202544Z-b8c4d209`는 app/driver compile0 뒤 wrapper 자체 검사에서 synthetic lambda의 parameter metadata까지 요구하여 FULL_READ_PARAMETERS로 실패했다. 로그를 보존하고 synthetic method를 제외했다. 후속 e02f77b3는 compile/selftest/당시 순수7개 PASS였으나 마지막 guard 추가 이전 증거이므로 실행 대상으로 사용하지 않는다.
- 최종 준비 `deployment/runtime/full-read-prepare/20261023T203643Z-b6461651/`: app/driver **compile0**, JDK21.0.10, classloader 원문/fixture/classes SQL4개와 application.yml exact bytes·실제 ExchangeApplication/controller code origin·bound parameter metadata·unsafe config negative 자체 검사 PASS, 순수 **8 PASS / FAIL0**. App fixture744파일(클래스/리소스/JAR 포함), Driver fixture7파일의 SHA 목록을 result에 기록했다. sourceIdentity `02730929247deafb6e9fc481d0974d038187ed5f031647b2dd4f68166e7d0078`, result SHA `3ed0a850d3133922d7e3ecde63efe443d82936e061f73ab10c3a7453db9a8b0b`. 최종 checked_prepare 재확인 PASS, RUN_CLAIM 없음. git diff --check 통과(기존 웹 LF/CRLF 경고만).
- **이번 fullApp Gate의 Docker/PG/Anvil/App 실제 기동·HTTP/STOMP·proof 서명·official claim·유료 호출은 전부0/NOT_RUN**이다. 앞서 별도 완료된 PG6 PASS와 합산하지 않으며 기존 웹172/build도 재실행하지 않았다. 현재 4파일/준비물/문서를 동결하고 root 최초 독립 검토 1회를 대기한다. 실제 1회 실행은 검토 및 root 자원·동시 시험 판단 이후에만 가능하며, 성공하더라도 full frontend 인수나 거래/사용자 지갑/공식8d 재개 PASS가 아니다.

## 후속 승인 — bounded readiness와 안전한 startup timing만 보완
- 기존 실패 run/claim/증거는 그대로 보존하고 하네스3파일만 수정했다: `full-app-read-gate.py`, `FullAppReadGate.java`, `test_full_app_read_gate.py`. ready 대기를75→120초, 전체 work240→300초/total330→390초로 제한하고 cleanup 여유90초는 유지한다. 무한 대기나 자동 retry를 추가하지 않았다.
- wrapper는 기존 evidence의 새 `startup-timing.jsonl`에 allowlist stage와 monotonic elapsedMillis **두 필드만** 기록한다. JVM_ENTER(main 진입 이후), SPRING_RUN_START/DONE, POSTCHECK_DONE, READY, STOP 또는 WATCHDOG, CTX_CLOSED를 구분한다. WARN/setLogStartupInfo(false), 제품 설정·금융·권한·TTL은 유지했다. raw exception/config/사용자·비밀값은 기록하지 않으며 unknown stage/음수 elapsed 거부와 정확한2필드 JSON을 소켓 없는 Java selftest에서 검사했다.
- 새 준비물 `deployment/runtime/full-read-prepare/20261023T211046Z-281eab64/`: 제품310+wrapper/Driver compile0, 리소스·classloader·parameter·unsafe-config/timing 자체 검사 PASS, 순수 **9 PASS / FAIL0**. sourceIdentity `39bfb7912247a4fd8ff4277776478dc6385f667193fbddf24d0cb222b3ef724c`, result SHA `cf26d7b4e274d1394f0faddd119c04ef1a3632c206e2a93930e16a6e93331ba9`. checked_prepare 재확인 PASS/RUN_CLAIM 없음. 이전 준비물과 source 입력 비교 시 달라진 것은 위3개뿐이며 Driver·제품310·helper·원본 anchor는 동일하다. diff --check 통과(기존 웹 줄끝 경고만).
- 이 단위의 Docker/live/Chrome 실행은0이다. 실제 HTTP 필수 오류가 나면 해당 단계에서 중단할 범위이며, 현재 준비물을 실행 성공이나 통합웹 인수로 표현하지 않는다. legacy chain/futures/audit/reserve/AI 등은 명시OFF이므로 전체 제품 지원 인수도 아니다. host port0인 현재 runner에 Chrome 연결 기능을 추가하지 않았고, 향후 HTTP 결과 이후 별도 판단한다.

## 통합웹 actual Chrome 읽기 Gate — 실행 전 준비 동결
- 새 `deployment/full-app-browser-gate.py`·pure test만 추가했다. 제품310·기존 full-read 하네스·기존 C relay/transport 원문은 수정하지 않았다. 성공 시험 run `ccpg-8949e3a7a933490e`(result SHA `49ddb45697f2fb7652dda400d691c2bd982011a55413dc5052db0da6260f3880`)의 **시험 PG volume만** RO cold copy하고, 동일 시험 DB/owner/API·trial 계정·password·두 public wallet identity를 재사용하도록 구현했다. 공식8d PG·실사용자 키/장부는 대상이 아니다. target 복제 DB에서만 user1 role=ADMIN으로 바꾸며 user0 USER·trial password·잔고0·wallet link는 유지한다. 권한 GRANT/경제정책 변경은 없다.
- internal-only Docker28.4의 publish 미지원 근거를 확인하여 최초 `-p` 초안은 **prepare/실행 없이 폐기**했다. 최종은 모든 컨테이너 publish0/internal network 유지다. APP-only IPv4 loopback listener(127.0.0.1:0 단1 bind/exclusive, RPC listener0)→immutable 소유 App ID의 docker exec stdio→App 127.0.0.1:8080을 사용한다. 기존 pipe-command/accept/close 및 bounded bridge 패턴을 재사용하되 upload thread도 추적·join한다. APP slots4/총64연결/120초, 자신의 child만 terminate/wait 확인하며 payload는 기록하지 않는다. App 추가 mount는 정확한 RO `/server-fixture` 한 곳뿐이다.
- 원본 `CDirectBrowserPipe.java` **1소스만** 새 helper-fixture에 -parameters/빈 classpath/sourcepath로 별도 컴파일하고 소켓 없는 selftest를 실행했다. 제품310 재컴파일·기존 HTTP/STOMP 검증·웹172/build 반복은0이다. 기존 frozen Node/script/helper/test·Node/Chrome 실행파일·현재 raw frontend·기존 dist(index/assets 3파일, .smoke 제외)·compiled App/JAR·SQL·8d public anchor는 실행 identity에 포함한다. Node는 고정 origin32171 한 번 bind하며 점유 시 다른 포트를 시도하지 않는다.
- runner는 새 claim once/min1920MiB·2GiB/pinned images/owner metadata/원본 PG stopped·source mount 확인 후에만 clone을 만들도록 구현했다. 기존 원문 COPY의 source before/after/copied tar SHA 일치와 종료 후 별도 RO source hash actor, source PG/volume metadata hash, source/config/compiled/dist/anchor 동등성까지 검사한다. work310/total400/cleanup90초, ready120/Node100초 제한이다. Node 실행 전에도 원문·helper classes·runtime fixture를 재검증한다. 금융·Oracle·사용자 서명/송금·배포/자금 공급은0이다.
- PASS에는 실제 수집 login/me/market/Cconfig/Caccount 200, route5의 render/API/controls 분리 증거, 고정 PNG10개 header/SHA, Node source/dist identity·own Chrome exit/server close/trace 성공 및 소유 자원 cleanup·원본 불변성이 모두 필요하다. futures/audit/AI 등 OFF 화면은 기능 지원 PASS가 아니며 test adapter의 **실제 account API 잔고0 cMKRW**를 원본 사용자1000003 잔고로 표현하지 않는다. 실제 MetaMask·거래/AI 호출은 대상이 아니다.
- 최종 준비 `deployment/runtime/full-browser-prepare/20261023T214150Z-eb41153c/`, sourceIdentity `0f99d185dafde8577a4ded49cba400c8360a9e27a86c3c11e45fc4042567b9e7`, result SHA `833802f37a45a5f7d3bfd4f8c750df514e3b53056770fc85adb1022ab7d696b4`. transport compile0/selftest PASS, 순수 **8 PASS**, Node syntax0. Node 별도 agent의 선행 pure5/syntax PASS는 반복하지 않고 최종 script SHA `be5ba9c94c7622e942fd7d14401abb4355812a0d9bb3765b37c8e1ed7835c20b` 및 helper/test 원문을 pin했다. helper main class SHA `81784d82956a7acf87e6bed724956517f85880119cb98bf9320ad65701fd3704`, TimerTask class SHA `90f2ac1fa5a442fa9bb25378345d369b7510ee807a6c50f4849c688db2598260`.
- checked identity 재확인 PASS/RUN_CLAIM 없음, diff --check 통과(기존 웹 줄끝 경고만). **이 browser 단위의 Docker·cold copy·target role 수정·Node/Chrome actual 실행은 전부0/NOT_RUN**이다. 기존 성공 HTTP run과 새 Chrome 실행을 합산하지 않는다. 준비물/코드/문서를 동결하고 root 최초 독립 검토 및 자원 판단 이후에만 신규1회 실행할 수 있다.

### 최초 독립 검토 필수 보안 보완 — 정적 own-key whitelist / 새 pin 준비
- 최초 검토에서 `.smoke/profile/evidence`가 정적 경로로 접근될 수 있는 경계1건을 수용했다. 별도 Node 구현자가 realpath containment와 **pinned distHashes의 own-key 파일만** 제공하도록 수정하고 unknown/.smoke/inherited/traversal을403으로 거부했다. 기존 산출물 삭제나 제품 수정은 없으며 argv/result/PNG10 계약도 그대로다. Node script SHA `682eb1398d24078900d4273987f539de9c392a97c0dddda73a5791b45b3eaaad`, Node test SHA `47ea17359125ac594045b8bb517ecf32e7eecd64081f3d1ce6a89e4496848f4c`, 별도 pure6/syntax PASS다.
- 보안 필수 수정의 hash pin 반영을 위해서만 새 prepare1회를 수행했다. 기존 `eb41153c`는 RUN_CLAIM 없는 미사용 증거로 보존하고 **실행 대상으로 사용하지 않는다**. 새 준비물 `20261023T214415Z-b0aff7ba`, sourceIdentity `907b3d33e0d309fa711bd8aa856957cbdf84fbae2aeb4279cd397156ed63c165`, result SHA `74fd55fdaf9486ecbdd67bb7f8bc5507ce0926d9021a1b8bf7bfe00ee799352d`다. 원본 transport1소스 compile0/selftest PASS·순수8 PASS·Node syntax0, checked identity 재확인 PASS/RUN_CLAIM 없음이다.
- 이전 준비물과 달라진 source 입력은 위 Node script/test2개뿐이다. helper classes·compiled 제품/JAR·dist hash는 동일하고 제품310/기존 HTTP Gate/웹172 회귀는 반복하지 않았다. 새 actual Docker/Node/Chrome 실행은0/NOT_RUN이며 보완 재검토1/2를 대기한다.

## 선별 출처 SHA256 원본 목록

아래는 이번 작업에서 읽기 전용으로 사용한 원본 파일의 SHA256이다. 원본 파일을 수정하지 않았으며 통합 대상은 필요에 따라 factory/route 추가 또는 줄끝 정규화가 있다.

| 원본 worktree | tools/websocket-test-client 하위 파일 | SHA256 |
| --- | --- | --- |
| ai-presentation-worktree | `src/futures-audit-panel.js` | `7a43b57ea01d92607ccd09d12d3d159780fd5f9bf9a7c9286ce0b8a1d91bd1e2` |
| ai-presentation-worktree | `test/futures-audit.test.js` | `1ac7790d3ec0c32ef64c2ef857696525490e359336739437fe1d23e323b4d4ba` |
| ai-presentation-worktree | `index.html` | `cc8c5b58edecb12595e13e82be0979a4fb0e75be93f0b429ba505bd94b28f2ee` |
| ai-presentation-worktree | `src/main.js` | `5bee0b1fc506f119e9d95047ebfd14df2bb610a667d424a527ee345d06bec750` |
| ai-presentation-worktree | `src/style.css` | `70039abee1aebda7fedb068f73bf5118a7041845046e2e9a968a24c41068049c` |
| ai-presentation-worktree | `src/ui.js` | `846edf6f4d459606d2eee4cdcee8fa3e8507c1bea0bd29b4f7839d528713c25a` |
| ai-presentation-worktree | `src/futures-panel.js` | `90b25822102d80730cae9d9dad63f0e62cd7a3adb182c3400a95d51da96adc86` |
| ai-presentation-worktree | `src/futures-display.js` | `30431d1ecd9c561dfeb6a06e8a841a142ce4c1ad1b58998756c37d8e5b36c33b` |
| ai-presentation-worktree | `src/decimal-display.js` | `869144b892d0ca75d04fb9347855d775caecded6de092ecc7ff3e1a67ebcafe9` |
| ai-presentation-worktree | `src/ai-futures-evidence.js` | `3f817786d269ed70be6a36edcad41fd021bce0b24fe2b0affcbdd9d6df6a716b` |
| ai-presentation-worktree | `test/ai-futures-evidence.test.js` | `d99514dd15b5f8fbf70e80cd2400e3f572fbca5c337b18cc410281848022b7ca` |
| ai-presentation-worktree | `test/futures-display.test.js` | `b5bf6eafdbaf83f266edb7e2566d31e76a16cdc3fb2fe84890935969fd3d412c` |
| ai-presentation-worktree | `fixtures/futures-ai-response.js` | `b9b8674ff8c3d09c43fdfbdeaaf2e062ffe5f222582c73d21ca015ba6580a695` |
| dapp-c-development-worktree | `c-demo.html` | `09b00e2296d192259df7ae6a3f533f1cfda8d49c79fb8d43f2c9c906f8e55424` |
| dapp-c-development-worktree | `src/c-demo.js` | `31b69138891199e25c31873983fc2da89bcbcabfb0c575fe9e6d6586aae7222b` |
| dapp-c-development-worktree | `src/c-direct-wallet.js` | `d87e9c11b85748341aca89fb907a4fae1d96d297ee0eae3c34f783ac33ab1dd9` |
| dapp-c-development-worktree | `src/c-direct-intent.js` | `3b812c8d9ced29b589498f24d258873697d13e6e5b0a4798463c70a5c2acab32` |
| dapp-c-development-worktree | `test/c-direct.test.js` | `4e3de0582da70d7d2bb5d41560f79a8fbc17db1d6f25a7331c3e423797a3c5fb` |
