# 통합 웹 실제 Chrome 화면 인수 — 2026-10-24

## 발표자가 먼저 볼 결과

**실제 서비스 PNG 8개 확보 / 화면 인수 PARTIAL / 전체 Gate BLOCKED.** 모든 PNG는 1366×768이다. Mock API·Mock WebSocket 화면이 아니라 실제 Spring Boot·PostgreSQL·Anvil에 연결했다. 다만 이 환경은 **조회 전용**이며 현물 거래·기존 선물·감사·AI 실행은 OFF/차단이다. 표시된 입력 폼을 거래 성공 증거로 사용하면 안 된다.

감사 화면의 수동 조회 버튼을 누르지 않은 채 자동 API 응답을 기다린 **시험 하네스 결함**으로 마지막 단계가 timeout됐다. 실제 서비스 전체 실패나 RPC 장애로 판정하지 않는다. 사용자 지침에 따라 추가 환경·Chrome·거래를 실행하지 않고 종료했다. 현재 접속 창은 없다.

## PNG와 접근 순서

캡처 폴더: `deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/`.

| 접근 순서·정확한 메뉴/버튼 | 실제 표시 및 API 판정 | PNG / 발표 사용 범위 |
| --- | --- | --- |
| 상단 **로그인** → 아이디/비밀번호 → **로그인** | 실제 두 시험 계정 로그인·`/api/me` 200. 새로고침 뒤 재로그인이 필요한 기존 메모리 세션 방식 | [login-dialog.png](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/login-dialog.png): 로그인 UI 설명 |
| **시장** (`#market`) | 실제 Synthetic 가격·캔들 200, 차트 canvas 렌더링. 실제 Native WS 업그레이드4회와 연결 표시 관측 | [market-chart.png](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/market-chart.png): 실제 차트 설명용 권장. 이번 시험 이력은3개 봉이며 실제 증권 시세가 아님 |
| **시장** 상단 | 렌더링됨. 로그인에 따른 WS 재연결 중 순간의 안내가 함께 캡처됨 | [market.png](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/market.png): 연결 완료를 주장하는 대표 이미지로는 비권장 |
| **시장** 안의 **mSEC 거래** / **매수·매도** / **견적 확인** | 현물 입력 폼 렌더링. `/api/orders`, `/api/trades`, `/api/portfolio`는 시험 필터403. 견적·BUY/SELL은 요청하지 않음 | [spot-form.png](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/spot-form.png): 폼 구조 설명만, 거래 인수 불가 |
| **모의 선물** (`#futures`) / **포지션 새로 조회** | 렌더링됨. 실제 `/api/futures/positions` 403, 제품의 `선물 조회 실패 · 표시 상태가 최신이 아닐 수 있습니다.` 안내 확인. 기존 선물 실행 OFF | [futures.png](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/futures.png): 미활성 상태를 명시하는 경우만 사용 |
| **내 지갑 · C 자산** (`#custody`) → **MetaMask 연결** → **계정·시세 새로 조회** | 실제 C config/wallet/account/direct-market 모두200, 시험 지갑의 정확한 `0 cMKRW` DOM 확인. 연결은 공개 시험주소의 **조회 전용 EIP-1193 어댑터**이며 실제 MetaMask·신규 소유 증명·거래 인수가 아님 | [custody.png](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/custody.png): 기존 TMS header/navigation/card 안의 C 통합 구조 설명용 |
| 같은 C 탭의 자산 카드 | 실제 여섯 수치 DOM은 생성됐지만 이 PNG는 sticky 메뉴에 수치 행이 가려지고 metadata가 좁게 줄바꿈되어 있음 | [c-account-metrics.png](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/c-account-metrics.png): **자산 수치 발표용 비권장**, 가독성·캡처 보완 필요 |
| **AI 도움** (`#ai`) | `AI 도움·진단` 및 입력 폼 렌더링. 질문 버튼·Agent POST는 누르지 않음. 실제 LLM/RAG 답변은 NOT_RUN | [ai.png](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/ai.png): 읽기 전용 보조 기능 UI 소개만 가능 |
| 별도 시험 ADMIN 로그인 → **ADMIN 운영** (`#admin`) | 관리자 메뉴·페이지 DOM 표시 조건 통과, `/api/admin/faucet` 실제200. 감사 GET은 발생하지 않아 NOT_RUN, 감사 실행 OFF. 마지막 하네스 timeout으로 PNG 없음 | `admin.png`, `admin-audit-off.png` **미생성**. 관리자/감사 시각 인수 완료로 표시하지 않음 |

상단의 노란 시험 안내는 하네스가 추가한 범위 표시다. 기존 제품 디자인·문구를 고쳐 성공을 만든 것이 아니다. 일부 원래 버튼은 화면에서 활성처럼 보이지만 시험 프록시/서버가 금융·서명·AI 요청을 거부하므로 **렌더링과 기능 활성은 다르다**.

## URL·로그인 및 실행 범위

- 실제 캡처 중 URL: `http://127.0.0.1:32171/`; 경로는 `/#market`, `/#futures`, `/#custody`, `/#ai`, `/#admin`이다. **시험 종료 후에는 접속할 수 없다.** 현재 이 URL이 READY라고 안내하지 않는다.
- 일반 시험 계정 `read_8949e3a7a933490e_0`, 관리자 시험 계정 `read_8949e3a7a933490e_1`을 사용했다. 비밀번호는 기존 성공 **시험** 환경의 무작위 비밀번호를 private config로만 전달했다. Git/보고서/PNG에 비밀번호·JWT·개인키를 기록하지 않았다.
- C 링크는 이전 성공 서버 시험의 시험 지갑 링크를 보존한 것이며 이번에 `소유 증명 및 계정 연결`을 다시 실행하지 않았다. 실제 사용자 계정이나 최종 잔고1,000,003.066148919985598442 cMKRW를 새 시험 사용자에 연결하지 않았다. 이번 C 화면의0잔고는 **새 시험 지갑의 실제 조회 결과**다.
- 수행한 절차는 자원/identity 확인 → 성공 시험PG 정지 volume의 RO cold copy → 새 전용 PG/Anvil/App → APP-only stdio relay → 기존 compiled dist의 same-origin 프록시 → 독립 Chrome 프로필의 로그인·탭 조회/캡처 → 소유 자원 종료/원본 대조다. 기존 웹172·서버 조회 Gate 전체·제품310소스 컴파일은 반복하지 않았다. 기존 고정 목적지 `CDirectBrowserPipe` transport1소스만 새 시험 경로에서 컴파일/자체 검사했다.
- 이번 사용한 준비물은 `20261023T214415Z-b0aff7ba`이며 `RUN_CLAIM`은 소비·보존됐다. **같은 run 명령을 다시 실행하지 않는다.** 다음 캡처가 필요하면 아래 최초 실패 보완과 검토 뒤 별도 새 제한 실행 단위를 사용해야 한다. 이 문서는 기존 실패 실행의 재사용이나 자동 반복 승인이 아니다.

## 최초 실패와 최소 후속 조치

1. `c-live-browser-smoke.mjs`의 ADMIN 구간은 페이지가 보인 뒤 `/api/admin/trade-audits` 또는 `/api/admin/futures/audits` 응답을 기다렸다. 그러나 `trade-audit-panel.js`와 `futures-audit-panel.js`의 `setContext()`는 화면·권한·버튼만 갱신하고 이력을 자동 조회하지 않는다. 실행 API 기록에도 해당 GET이 없다. 따라서 **발생하지 않을 이벤트를 기다린 POLL_TIMEOUT**이다.
2. 최소 보완은 ADMIN 확인 뒤 **거래 기록 전수 대사 → 이력 조회**(`#audit-refresh`)를 한 번 클릭한 다음 실제 거부/미지원 표시와 PNG를 확인하는 것이다. 또는 **선물 장부·체인 감사 → 감사 이력 조회**(`#futures-audit-refresh`)를 사용한다. **수동 대사 실행 / 선물 수동 감사** POST를 누르는 방안이 아니다. 이번 실패 뒤 코드는 고치거나 재실행하지 않고 원인만 기록했다.
3. C 자산 PNG는 수치의 정확성 문제가 아니라 캡처 위치·배치 문제다. `#c-account.scrollIntoView()`와 상단 sticky 메뉴 조합으로 수치 행이 가려질 수 있으며, `.metrics` 안의 metadata `dl`은 한 grid cell에 배치되어 긴 시각/검증범위가 좁게 줄바꿈된다. 후속 최소안은 수치 행이 viewport에 실제 보이는지 확인한 뒤 캡처하고 metadata를 전체 폭으로 배치하는 것이다. **이번에는 제품 CSS를 변경하지 않았다.**
4. `market.png`는 로그인 직후 기존 WS disconnect/reconnect가 진행되는 순간이다. 이후 C/AI 이미지에는 `실시간 연결됨`이 보이며 API 및 실제 WS 연결 관측이 있으므로 이 한 장을 영구 WS 장애의 증거로 해석하지 않는다. 다음에는 로그인 후 재연결 안정 조건을 확인한 뒤 상단 대표 이미지를 캡처하는 것이 최소안이다.

## 실행·보존 증거

- HEAD `f9353c33244565b30fb130dfb6159eab441a3523`; 제품 compiled 출처 `39bfb7912247a4fd8ff4277776478dc6385f667193fbddf24d0cb222b3ef724c`, 새 시험 sourceIdentity `907b3d33e0d309fa711bd8aa856957cbdf84fbae2aeb4279cd397156ed63c165`.
- run `ccpg-a8ee763b344b4bdb`; 실제 판정 `BLOCKED / ACTUAL_CHROME / FULL_BROWSER_CHROME_FAILED`, Node `FAIL / POLL_TIMEOUT`. 전체99.485초. 실행 직전 RAM **4037MiB**(최소1920 충족).
- App `SPRING_RUN_START=2919ms`, `SPRING_RUN_DONE=49435ms`, `READY=49823ms`, `CTX_CLOSED=75769ms`. 기동 제한 초과가 이번 실패 원인은 아니다. 원래 RPC/Vite 장애의 근본 원인 해결로 확대하지 않는다.
- 원본 성공 시험DB `ccpg-8949e3a7a933490e-data`는 RO mount만 사용했다. 새 복제본에서 시험 계정1의 역할만 ADMIN으로 바꿨다. 원본 raw tar SHA는 복사/종료 후 동일한 `9b4db4de9252bc518f5ee1e3cb17bb6cddc7462cfff16b9d0badcfd165c1bbe3`이다.
- 공식 사용자8d checkpoint/claim·기존 UI/AI/C 환경·소스/compiled/dist/앵커 불변, fork의 기존14거래 동일. relay/App/Anvil/PG/copy/hash 모두 종료 확인, 새 network/volume 보존. 원본 사용자 서비스 기동·거래 재전송·서명·자산 이동·유료 API·commit/merge/push·전역 재시작·삭제0.
- 독립 최초 검토에서 정적 서버의 검증 제외 `.smoke` 파일 접근 가능성1건을 수정했다. 정규화·실경로 확인 후 pinned manifest의 own-key 파일만 서빙한다. Python8/Node6·syntax·transport 자체 검사 PASS, 수정 후 재검토1/2 **발견된 필수 수정 없음** 뒤 실제1회 실행했다. 이는 위 실제 하네스 실패를 PASS로 바꾸지 않는다.
- [실행 result](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/result.json) SHA `e80846d400db5161e74a4c6472c8913d79254c4b1d8bead3aab2529de920c4e4`; [브라우저 result](../deployment/runtime/full-browser/ccpg-a8ee763b344b4bdb/browserEvidence/result.json)에 실제 API 상태·PNG SHA·범위별 판정이 있다. Node runtimeError false, accountRendered true, distUnchanged true, ownSpawnExited/childCleanupComplete/serverClosed true, traceFailed false다.

**발표 권고:** 실제 시장 차트·로그인·C 메뉴 통합·AI 폼은 위 제한을 붙여 정적 자료로 사용할 수 있다. C 수치 PNG와 감사 PNG, 통합 웹 전체 라이브 거래/AI 시연은 준비 완료가 아니다. 기존 사용자 MetaMask5거래 성공 증거는 별도 성과로 유지한다.
