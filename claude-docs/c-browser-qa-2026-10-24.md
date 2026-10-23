# C 기존 웹 브라우저 QA — bounded harness 준비

> **실제 서비스 연결 후속 결과:** 별도의 `c-live-browser-smoke.mjs`로 신규 `ccpg-a8ee763b344b4bdb`를1회 실행해1366×768 PNG8을 확보했다. source907b3d33…/실행전RAM4037MiB, actualAPI·NativeWS·C계정 조회는 성공했으나 ADMIN의 수동이력조회 버튼을 누르지 않고 GET을 기다린 시험결함으로 마지막 POLL_TIMEOUT/전체BLOCKED다. 실제 Chrome 추가실행/새환경/금융거래 없이정상종료·보존했다. [발표자용 실제 화면·원인·한계](c-integrated-web-chrome-acceptance-2026-10-24.md)를 최신으로 읽는다. 아래 NOT_RUN은 최초 Mock/bounded 하네스 준비 당시 이력이며 실제성공을소급하지않는다.

> 수정 후 독립 재검토1/2: **발견된 필수 수정 없음**. 검토자 직접 FakeSocket/FakeChild/FakeServer14 PASS·소스SHA 일치를 확인했다. cleanup 미확정·trace/result 기록 실패 시 BLOCKED/exit1, 소유 handle 한정 bounded 정리와 최종 PASS 분리를 확인했다. 실제 Chrome·리스너는 아직 실행하지 않았으며 이 기록은 화면 캡처나 실제 cleanup 인수 PASS가 아니다.

## 현재 체크포인트

- 상태: **순수 transport/cleanup 회귀 14 PASS / 실제 Chrome 실행 NOT_RUN / 독립 최초 검토의 필수 1건 수정 후 재검토1/2 필수 지적 없음**.
- 대상: `deployment/runtime/c-product-web-integration-worktree` 안의 기존 웹 smoke와 작은 CDP helper만 변경.
- 이번 준비 중 Chrome, HTTP listener, Vite, Spring, PostgreSQL 및 Anvil을 실행하지 않았다. 실제 화면 성공이나 Windows 장애 해결을 주장하지 않는다.
- 이전 `dist/.smoke/c-existing-9FHtj7/` 및 `dist/.smoke/c-existing-browser.mjs`는 수정·삭제하지 않았다.

## 확인된 문제와 변경

기존 하네스에서 `/json/list`의 fetch와 JSON body, WebSocket open, CDP pending command, poll 내부 await, Browser.close가 각각 무제한 대기할 수 있었다. 이전 중단에 어느 경계가 실제 원인이었는지는 증거 부족으로 미확정이다. 제품/RPC/Windows 원인을 이 보완만으로 확정하지 않는다.

- `tools/websocket-test-client/scripts/cdp-boundary.mjs`: deadline과 AbortSignal, pending timer, close/error reject 및 timer 정리, late reply 무시, 안전한 protocol/send/error 코드, bounded Browser.close.
- `tools/websocket-test-client/scripts/browser-smoke.mjs`: 기존 흐름에 helper 연결. 전체 작업 deadline 180초, command 최대 8초, target-list/open 최대 5초, poll 최대 15초. poll 내부 await도 남은 시간에 제한한다. 자동 CDP 재전송은 없다.
- 매 실행은 새 `dist/.smoke/bounded-*` 디렉터리와 독립 Chrome profile을 사용한다. 안전 metadata JSONL은 stage/method/requestId/elapsedMs/outcome만 기록한다. params, 응답 원문, URL query, JWT, 키, 원문 예외는 로그에 기록하지 않는다.
- cleanup은 Browser.close 최대 1초 후 **이번에 spawn한 child handle만** 종료 확인/정리한다. 기존 사용자 Chrome이나 다른 PID를 조회하거나 종료하지 않는다. fixture 서버 종료도 제한시간을 둔다. 결과는 owned child exit와 fixture close의 실제 확인 여부를 분리한다.
- 이전 desktop/mobile 흐름은 유지한다. 승인된 P0의 OPEN 라벨 `진행 중`과 기존 assertion을 일치시켰다 (`src/futures-panel.js`). 1366×768과 390×844 C 탭은 기존 API의 404 미지원 경로를 그대로 이용해 **read-only unavailable** 화면만 캡처하도록 준비했다. 자산 성공이나 지갑 서명 fixture를 만들지 않았다.
- 모든 페이지에 `OFFLINE FIXTURE · Mock HTTP/STOMP/AI · no real RPC, MetaMask signing or asset transactions` 표시를 넣는다. 기존 mock 현물/선물 화면과 실제 C 자산 거래를 혼동하면 안 된다.

## 순수 검증

실행 명령: 웹 디렉터리에서 `node --test test/cdp-boundary.test.js`.

초기 결과: **10 PASS, 0 FAIL, 0 SKIP**, 약 0.32초. listener/network/Chrome 사용 없음.

범위: 정상 응답·민감값 없는 trace, command timeout/late reply, close/error의 전체 pending reject, send/protocol/CDP error, WS open timeout, listener 설치 전/후 오류, fetch 및 body hang, poll 내부 hang, bounded close, deadline 0의 send 차단, trace callback 오류 시 pending 해소.

첫 시험 9 PASS/1 FAIL은 opening listener 설치 전 오류가 이미 `CDP_SOCKET_CLOSED`로 fail-closed된 분류를 fixture가 `OPEN_FAILED`로 예상했기 때문이다. 검증을 삭제하지 않고 이벤트 전/후를 각각 명시하여 재실행했다.

`node --check scripts/browser-smoke.mjs` 및 helper syntax 검사 PASS. 이 runtime copy는 Git worktree로 인식되지 않아 해당 디렉터리의 `git diff --check`는 NOT_RUN(명령 오류)이며 Git 검증 PASS로 처리하지 않는다.

## 다음 Gate의 한계

main agent의 동일 source freeze 및 독립 최초 검토 뒤, 자원과 소유권 조건을 충족한 경우에만 격리 Chrome 1회를 판단한다. 현재 전체 smoke의 제품 UI 호환성, screenshots, 실제 서비스/JWT/DB/RPC 및 MetaMask는 모두 이 변경으로 새로 검증되지 않았다. C 지원 화면과 실제 자산 거래는 별도 실환경 Gate가 필요하다. 금융 계산, 제품 UI/API, 승인 범위, 실제 사용자 상태, 과거 sourceHash/manifest/ledger에는 변경이 없다.

## 최초 독립 검토 후 필수 보완

확정된 지적: 이전 후보는 UI assertion 통과 직후 PASS를 출력하고, cleanup에서 child exit/server close를 확인하지 못하거나 trace 쓰기가 실패해도 최종 PASS/exit 0을 유지할 수 있었다. 이는 실제 종료·증거 조건을 검증한 PASS가 아니다.

- UI 단계는 `UI_ASSERTIONS_PASS`만 출력한다. 최종 `PASS`는 cleanup 확인과 안전 trace 저장 조건이 충족된 뒤에만 출력한다.
- child exit 미확인, fixture server close 미확인, safe trace 쓰기 실패는 각각 안전한 고정 코드의 **BLOCKED / exit 1**이다. 결과 파일 쓰기 실패도 **BLOCKED / exit 1**이다.
- 제한시간 내 이번 child가 종료되지 않으면 해당 handle만 unref하고 종료 미확인 사실을 남긴다. fixture close 미확인 시 이번 server 및 추적된 소유 socket만 destroy/unref하여 실패한 handle이 Node를 계속 붙들지 않게 한다. 다른 PID/Chrome/네트워크에 대한 정리는 없다.
- `cleanupOwned`와 `finalGate` 순수 FakeChild/FakeServer 회귀 4개를 추가했다. 종료 후 PASS, child exit 미확인, server close 미확인, UI 이후 trace 실패를 각각 검증한다.
- 수정 후 실제 실행: `node --test test/cdp-boundary.test.js` **14 PASS / 0 FAIL / 0 SKIP**, 약 0.40초. 두 스크립트 `node --check` PASS. 신규 Chrome/HTTP/PG/Vite 실행은 계속 **0회**다.
- 동일 소스 범위의 재검토 전 실제 브라우저 Gate를 실행하지 않는다. 최초 필수 지적을 해결했다는 구현자 설명과 독립 재검토 완료는 구분한다.

## 실제 서비스 읽기 전용 Chrome 인수 후보

새 `scripts/c-live-browser-smoke.mjs`는 이전 Mock smoke와 분리되어 있다. 기존 compiled dist를 수정하거나 재빌드하지 않으며, 정해진 `127.0.0.1:32171`에서 단 한 번 bind한다. 신규 격리 App의 게시 loopback 주소로 API/Native WebSocket을 전달한다. 준비된 Linux Gate의 source identity에 연결된 private config와 새 evidence 디렉터리를 인자로 받는다.

허용 경계는 정해진 GET 목록과 POST `/api/auth/login`뿐이다. 그 밖의 거래·서명 challenge·AI·감사 실행 POST는 proxy에서 차단한다. API 원문·JWT·비밀번호·서명은 기록하지 않으며 method/path/status만 기록한다. 원래 WebSocket을 실제 App에 전달하고 Origin을 바꾸지 않는다. SockJS 성공을 주장하지 않는다. CSP는 외부 접속을 허용하지 않는다.

1366×768 화면에서 로그인 dialog, 시장 차트, 현물 form, 기존 선물, C 상단/정확 잔고, AI 화면, ADMIN/감사 OFF 영역을 캡처하도록 준비했다. 시장/candle REST 200, 실제 Native WS upgrade와 UI의 `실시간 연결됨`, C config/account 200, 정확 `0 cMKRW` 시험 지갑 렌더링은 필수다. 이 시험 계정은 과거 실제 사용자의 1,000,003 잔고와 다르다. 미지원 legacy/감사/AI는 조회 상태와 OFF 범위만 기록하며 거래·분석 성공으로 처리하지 않는다.

C 지갑 adapter는 공개 시험 주소/chainId/config genesis의 읽기만 제공하고 모든 signing/send를 거절한다. 실제 MetaMask 연결이나 fresh 지갑 RPC 검증이 아니다. 기존 제품 wallet constructor 전에 주입하며 기존 UI 코드는 수정하지 않는다. C position은 데이터 없음으로 NOT_RUN이다. 화면에 `실제 격리 서비스·읽기 전용 / 시험 계정 / 지갑 서명·금융·AI 실행 OFF`를 표시한다.

본 작업 budget 85초 및 기존 bounded cleanup을 사용한다. 결과 `result.json`은 sourceIdentity, 전후 dist SHA 일치, safe API metadata, PNG SHA, UI 경로, zero account 렌더링, 소유 child/server 종료, signing/paid/financial 실행 없음의 경계를 기록한다. 필수 오류나 cleanup 실패는 비-PASS다.

명령 계약: 웹 디렉터리에서 `node scripts/c-live-browser-smoke.mjs <private-config.json> <새 evidence-dir>`.

현재 검증: `node --check` PASS, `node --test test/c-live-browser-smoke.test.js` **5 PASS / 0 FAIL**. config loopback 제한, API write 차단, adapter sign/send 거절과 재주입 후 누적 카운터 보존, 순서 독립 dist manifest 비교, 18자리 정밀 수치 변환의 순수 검증만 실행했다. 실제 HTTP listener/Chrome/Docker/서비스 실행은 **0회**, 독립 최초 검토 및 실제 인수는 아직 NOT_RUN이다.

최종 후보 보완: `config.distHashes`를 필수로 대조하며 Python/Node 정렬 차이에 영향받지 않는다. 시장 상단과 별도로 chart 중심 스크롤 캡처를 추가하여 총 **10 PNG**를 준비한다: `login-dialog`, `market`, `market-chart`, `spot-form`, `futures`, `custody`, `c-account-metrics`, `ai`, `admin`, `admin-audit-off`. OFF 선물/감사는 실제 GET 403/404와 제품 실패/비활성 문구가 도착한 후 캡처한다. 관리자도 route별 API metadata/controls를 기록한다. App-only fixed stdio Relay의 제한된 slot을 HTTP keepalive가 점유하지 않도록 test proxy의 HTTP upstream에만 `Connection: close`를 고정한다. 실제 Native WS upgrade 연결은 유지한다. 금융·제품 RPC timeout이나 제품 소스는 변경하지 않았다.

독립 검토 필수 1건 보완: 기존 후보의 realpath containment만으로는 dist 안의 SHA 검증 제외 영역 `.smoke`까지 static 서빙할 수 있었다. 실제 유출을 관측한 것은 아니며 접근 경계의 잠재 위험이다. containment 뒤 normalized relative key가 검증된 dist manifest의 own key인 경우에만 서빙하도록 제한했다. `.smoke`, unknown 파일, traversal 및 inherited key는 403이다. 원래 실패 증거를 삭제하거나 수정하지 않았다. 순수 negative 회귀 추가 후 **6 PASS / 0 FAIL**, syntax PASS. 실제 환경 실행은 여전히 0회이며 재검토 대기다.
