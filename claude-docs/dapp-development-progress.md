# TMS DApp 총괄 개발 진행

> 최신 갱신 2026-10-24. 사용자 총괄 자율 개발 지침에 따른 현재 실행 상태. 과거 단계 문서·증거는 삭제하거나 이 문서로 소급 대체하지 않는다.

## 현재 목표

### 최상단 체크포인트 — 발표 소스 통합 2026-10-24

별도 후보에서 B-v2/C/P0/AI/통합 웹을 선별했고 fresh Java 컴파일·선택878 PASS/PG10 SKIP·Foundry190·웹211/build를 확인했다. 독립 검토 필수 수정 없음. 아래 Chrome PARTIAL/BLOCKED 및 사용자8d 정본은 그대로 보존하며 새 전체 실환경 성공으로 승격하지 않는다. 실제 C live 창은 아직 미기동이고 현물 기존 Playground는 source identity BLOCKED다. [통합 결과](presentation-integration-2026-10-24.md), [발표 운영 큐시트](../docs/presentation/DEMO_CUE_2026-10-24.md)를 현재 기준으로 사용한다. 기존 runtime·snapshot·ledger·claim은 공개 통합 대상에서 제외한다.

### 지금 이어갈 단일 체크포인트 — 2026-10-24

- **최신 실제 Chrome 단위 종료 — 전체 PARTIAL/BLOCKED, PNG8 확보.** `ccpg-a8ee763b344b4bdb` / prepare `20261023T214415Z-b0aff7ba` / source `907b3d33e0d309fa711bd8aa856957cbdf84fbae2aeb4279cd397156ed63c165`, 실행 직전 RAM4037MiB다. 기존 compiled281eab64·dist 재사용/웹172·제품310컴파일·서버 fullGate 반복0, 새 시험PG RO coldcopy·App-only stdio/loopback 연결로 실제 Chrome1366×768에서 login/market/chart/spot form/futures/C/AI PNG8을 확보했다. 실제 login/me/시장/캔들/C config/wallet/account/market200 및 WS4upgrades, C시험지갑0정확DOM 확인. finance/legacy futures/audit/AI OFF를 성공으로 바꾸지 않는다.
- 마지막 ADMIN 구간 `POLL_TIMEOUT`으로 전체 `BLOCKED/FULL_BROWSER_CHROME_FAILED`다. ADMIN로그인·me·메뉴/페이지DOM 조건·faucet200은 성공했으나 audit이력GET은 **버튼을 누를 때만 발생**한다. 하네스가 `이력 조회` 버튼 없이 자동응답을 기다린 결함으로 source/log가 일치한다. 제품/RPC/권한 실패로 단정하지 않으며 지침대로 **추가 환경/Chrome/코드 수정 없이 종료**했다. 감사PNG2는없음/NOT_RUN. 다음최소안은 기존 GET 버튼1회 클릭 후 오류표시·PNG 확인이며 POST감사 실행이 아니다.
- 시각확인에서 C상단 메뉴/카드는 기존디자인 안에 렌더링되나 자산수치 PNG는 sticky메뉴에 행이가려지고metadata가 좁게줄바꿈되어 **수치발표용 비권장**이다. 시장상단PNG는 로그인 재연결 중 순간이며 차트PNG/C·AI의 실제연결표시와 구분한다. UI/CSS수정·위조캡처는 하지 않았다. 정확한 접근순서·버튼·8PNG·URL경계는 새 UI `claude-docs/c-integrated-web-chrome-acceptance-2026-10-24.md`에 간결하게 정리했다.
- 99.485초 후 relay/App/Anvil/PG/copy/hash 종료확인·network/volume보존, source/compiled/dist/공식앵커·fork14tx 불변, 성공시험PG원본raw SHA 전후동일/공식8dclaim미사용이다. 사용자거래/MetaMask서명/자산이동/유료호출/merge·commit·push/전역재시작/삭제0. 현재사용자접속창없음(캡처중URL127.0.0.1:32171은종료). 이번 소비RUN_CLAIM 재사용금지. 이번요청은제한된1회인수였으므로이결과를보고하고추가개발로확대하지않는다.

- **사용자 메모리 확보 후 실제 Chrome 인수 단위 준비 중:** 첫 RAM3743MiB/disk24.85GiB 확인. 새 시험 하네스 `full-app-browser-gate.py` + `c-live-browser-smoke.mjs`는 성공 시험DB8949의 정지 volume을 RO cold copy하고 기존 App compiled281eab64·기존 dist를 그대로 사용한다. 웹172/서버 Gate 전체/310소스 컴파일은 반복하지 않는다. 원본 사용자PG/Oracle key/개인키·MetaMask서명·금융거래·AI유료0 유지. 목표는 실제same-origin HTTP/NativeWS와1366px 탭별렌더/API/비활성 구분 PNG다. 시험 USER/ADMIN(역할변경은 새 복제DB만), 지갑은 공개 시험주소의 조회전용 어댑터이며 실제 MetaMask/RPC지갑 인수로 표현하지 않는다. 새 실행 전 최종 source/dist/compiled/claim/자원·독립 검토를 확인하고 신규1회만 실행한다. 현재 실제Chrome/새환경은 아직0이며 아래 결과는 이전 단위 이력이다.

- **최신 사용자 요청: 기능 추가 없이 실제 통합 웹 인수 우선.** 새 `ccpg-8949e3a7a933490e` / prepare `20261023T211046Z-281eab64` / source `39bfb7912247a4fd8ff4277776478dc6385f667193fbddf24d0cb222b3ef724c`의 실제 fullApp HTTP/STOMP/C 읽기 Gate는 **PASS_NOT_UI_OR_TRADING**다. 실제 새 계정2/JWT/공통401·403/지갑 소유 증명·계정 격리/C config·account/시장·캔들·C 참고시세/native STOMP 시세 수신/DB 금융쓰기0을 확인했다. 시험 EOA proof와 실제 사용자 MetaMask 거래를 구분한다. 107.734초 후 소유5역할 정상 종료, source/fixture/공식 anchor·fork14tx 불변, 새network/volume 보존·공식claim미사용이다.
- 이전 75초는 측정 근거가 없는 하네스 고정값이었다. 테스트 전용 readiness120/work300/total390(cleanup90 유지) 및 stage+elapsed 두 필드 관측만 보완했다. 제품310/Driver/helper/금융·RPC timeout·TTL·권한은 변경0. 순수9/compiled 자체검사 PASS·독립 재검토1/2 필수없음 후 새 Gate1회를 실행했다. 이번 JVM_ENTER2ms→SPRING_RUN_START3693ms→DONE56973ms→POSTCHECK56983ms→READY57126ms→STOP77674ms→CTX_CLOSED77817ms다. Spring.run 약53.280초·postcheck10ms이며 이전 약79초의 세부 원인은 여전히 미확정이다.
- **브라우저/탭별 인수는 PARTIAL/NOT_RUN:** 실행 직전 자원 기준을 통과했으나 종료 후 RAM1508MiB로 최소1920 대비412MiB 부족해 추가 Chrome/실환경을 시작하지 않았다. 최신 검토된 browser-smoke는 Mock전용이며 실제 서버는 내부망·host port0·정상 종료되어 현재 사용자 접속 URL이 없다. 실제 Chrome에는 bounded 캡처 동안만 App 유지+기존 dist의 same-origin API/WS loopback 프록시가 필요한 최소 시험 경계다. 제품·디자인 재작성/인증완화는 필요하지 않다. 이번에 해당 새 브라우저 연결 코드를 구현하거나 Mock을 actual로 재사용하지 않았다.
- 위 PASS는 시장·인증·C 계정 조회 범위다. 기존 현물/legacy 선물/감사/AI 전체 API 및 C 포지션 조회·1366/390px 화면은 이번 Gate로 인수하지 않았다. off flag/조회 allowlist를 지우거나 관리자 역할을 올려 화면 성공을 만들지 않았다. 사용자 요청에 따라 지금은 추가 개발이나 반복 환경 생성 없이 자원 차단과 필요한 최소 연결 범위를 보고한다. 다음은 사용자 앱 정리 등으로 **가용 최소1920MiB(이번 관측 대비+412MiB, 여유 권장)** 확보 후 실제 브라우저 조회 Gate를 판단하는 것이다.

- **05:57 최신 실행:** 검토 완료된 fullApp 준비 `20261023T203643Z-b6461651`의 신규 실환경 `ccpg-49a47adf535948bd`는 **BLOCKED / ACTUAL_FULL_APP / FULL_READ_APP_READY_TIMEOUT**이다. 114.672초에 중단·보존했고 HTTP/STOMP/지갑 proof 시험은 미실행이다. bootstrap 성공, PG/Anvil/App/bootstrap 정상 종료, 새 network/volume 보존, source/fixture/원본 anchor·fork의 기존14거래 동일성 PASS다. 공식8d claim은 건드리지 않았다. 이 준비의 RUN_CLAIM은 소비됐으므로 재사용하지 않는다. timeout 원인을 읽기 진단하며 재실행이나 timeout 증가는 하지 않는다.
- 그 직전 자원 차단은 claim/환경 생성 전에 발생했다. 이후 실제 CIM RAM2075MiB·디스크24.534GiB를 확인하고 위 1회 Gate를 실행했다. 검토된 소스의 sourceIdentity는 `02730929247deafb6e9fc481d0974d038187ed5f031647b2dd4f68166e7d0078`이며 최초 독립 검토 필수 지적 없음이었다. 아래 준비 중/NOT_RUN 문구는 이 실행 이전 이력이다.
- bounded Chrome QA 하네스는 순수14 PASS·수정 후 독립 재검토1/2 필수 지적 없음으로 동결했다. 실제 Chrome 후속은 아직 NOT_RUN이며 기존 PNG0 실패 증거를 보존한다. 사용자 Chrome/공식 사용자창에는 변경이 없다.
- 후속 화면 Gate의 실행 전 자원 검사에서 RAM559MiB/필요1920MiB(부족1361MiB)를 확인해 Chrome/리스너 실행 전에 차단했다. 기존 앱·Docker/WSL 임의 종료나 기준 완화 없이 로그·소스 읽기 진단으로 전환했다. fullApp 컨테이너 정상 종료가 Windows 가용 RAM 즉시 회복을 뜻하지는 않으며 그 인과는 미확정이다.
- App 보존 증거 추가 확인: UTC start20:55:29.579 → STOP20:56:45.030 → ready20:56:48.442(시작+78.863초/STOP+3.412초) → normal-stop20:56:49.512 → exit20:56:49.580, exit0/OOMfalse다. 실제 ExchangeApplication·off 설정·deny Oracle·API DataSource/member0·대상 bean 검사를 지나 ready를 썼지만 75초 Gate를 초과했으므로 HTTP PASS가 아니다. 로그는 banner305bytes/예외 marker0이고 세부 기동 구간 시간이 없어 느린 bean·DB·CPU·메모리 중 원인은 확정 불가다. 실패 결과와 늦은 준비 증거를 함께 보존하며 제한시간 상향으로 숨기지 않는다.
- 안전한 읽기 조사로 C 제품 위험Worker 미구현을 재확인했다. 기존 legacy Worker는 자체 ledger/intent/sender·상태 enum에 결합되어 직접 배선하지 않는다. C Risk ABI·rules transition/transaction proof·projection reducer/store와 순수 금융/시장 검증을 재사용할 수 있으나 후보 scanner·keeper Report·durable 제출/복구 Worker는 새 검증 단위가 필요하다. 기존 시험 risk/Claims fixture 성공을 제품 자동청산으로 바꾸지 않는다. 이번 조사에서는 코드/실행/정책 변경0이다.
- 다음 재개: 가용 RAM1920MiB 이상을 확보한 뒤 **기존 검토된 bounded Chrome 화면 Gate부터 1회** 판단한다. fullApp은 소비 claim을 재사용하지 않고 늦은 준비의 구간 진단·자원 조건과 필요한 최소 보완을 확인한 뒤 별도 검토 범위에서 새 실행을 판단한다. 공식 사용자 재개는 기존8d 전용 launcher를 유지하며 현재 통합 실패로 새 배포/초기자금분배로 대체하지 않는다.

- 사용자 실제5거래·자산 정본의 최신 보존 자손은 **cdemo-8d5fd2ff832345bb**다. wallet1000003.066148919985598442/free·locked·unpaid·pending0/CLOSEDv2/EOA8/Cnonce2·계약/Receipt 실제 읽기 복원 PASS 후 정상 종료했다. 조상135/e0로 재개 금지.
- 발표 당일 재개 도구는 C worktree `deployment/c-checkpoint-live-launch.py`다. 순수10/기본계획CLI·독립 재검토 PASS, 명시 `--live-window`는 아직 NOT_RUN. 지금 활성 사용자 접속 창은 없다. 시작 때 자원·현재source/산출물·claim/최신상태 검증 후에만 URL을 안내한다.
- 별도 `c-product-web-integration-worktree`의 기존 웹+C 탭: 웹172/build 및 독립 재검토 PASS. 참고시세 제품 API7 PASS/독립 검토 PASS. Chrome 시각QA는 CDP 대기에서 중단·PNG0/NOT_RUN이다. 사용자 Chrome은 변경하지 않았다.
- 같은 새 UI 작업 공간의 최소권한 기동 분리: 신규13 PASS, unchanged default H2 금융24 PASS, 독립 재검토1/2 필수없음. 실제PG catalog/권한/owner bootstrap은 NOT_RUN이다. 원본 install·정책·SQL·금융식은 유지한다.
- **최신 실제 PG 단독 Gate PASS:** `ccpg-f767ff6a011147d0` / source `a39917fc3cf2e0dc06bb210a50378e52f8e65deccf2d3b6d582ecf467d10764a`는25.766초에 owner 원본install→최소권한API SELECT 검증/DDL·DML42501/별도 잘못된 grants owner/disabled trigger/틀린 함수body/다른configuredVersion의 **6 found=started=succeeded, fail·containerFail·skip·abort0**을 확인했다. 검증경로 API SELECT233, 원본 정책행·재현자료 보존, Java/PG normalStop true, 새volume/network·source/fixture 불변 확인이다. 실제 fullApp/Anvil/브라우저/C 거래 인수는 아니다.
- 해당 준비 `20261023T195604Z-28583e67`은9소스 compile/SQL3 compiled ClassLoader SHA/parameters self-test·순수guard8 PASS·최초 독립 검토 필수없음 후 새 실행1회에 사용했다. **그 RUN_CLAIM은 영구 소비·보존**하며 재실행하지 않는다. 기동 전 읽기 자원은 RAM1994MiB/disk25.666GiB/Server28.4.0/images2/running0이었고 실제run도1920MiB/2GiB 기준을 재확인했다. E1a/C4b의 과거6 PASS와 별개다.
- **현재 fullApp 읽기 Gate 준비 동결·최초 독립 검토 중:** 새 UI `deployment/full-app-read-gate.py`, `FullAppReadGate.java`, `FullAppReadDriver.java`, `test_full_app_read_gate.py` 4파일과 준비 `20261023T203643Z-b6461651` / source `02730929247deafb6e9fc481d0974d038187ed5f031647b2dd4f68166e7d0078`다. 새310소스+실제App wrapper/Driver compile·SQL4/yaml ClassLoader·actual class origin/parameters·unsafe config negative·순수8 PASS, RUN_CLAIM 미사용이다. 실제 PG/Anvil/App/HTTP/STOMP/proof는 아직0/NOT_RUN. 검토 후 실제 자원·격리 조건을 충족할 때1회만 실행한다. 새 DB/시험 계정·비공개 RO 체인 fork이며 공식8d/user window와 분리한다. 과거 PG6/308 compile 성공을 현재 fullApp 성공으로 대신하지 않는다.
- 병행 UI 증거 후속은 기존 browser-smoke의 무한 대기 가능(fetch/WS/CDP/poll/close)을 제한·관측하는 최소 보완이다. 구 실패의 정확한 단계/원인은 여전히 미확정. 새 Chrome/리스너 실행 없이 Fake transport 순수 검증만 진행 중이며, 실제 화면은 별도 검토·순차 자원 확인 후 격리 실행1회에서만 확보한다. 사용자 Chrome·9FHtj7 실패 자료·제품 API/디자인 변경0이다.
- 공식8d/발표재개 claim·원본/UI/AI/C 평가증거·기존 환경은 불변. 현재 단위는 PG+시험 JDK만 사용하며 Anvil/App/브라우저/사용자키/거래/유료호출0이다. 실제 fullApp 읽기 Gate와 C 위험Worker·감사·relayer·WS·AI·1초 체결 안정화 등은 이후 범위에 유지한다.

### 2026-10-24 최신 체크포인트 — 실제 거래 상태 복원 PASS / 기존 웹 통합 별도 검증 중

- 최신 보존 자손은 `cdemo-8d5fd2ff832345bb`다. source identity `4d7cee7e06a761f14d960e2ecb2ca729705b1437cce4d0487bf75c88ed12292d`. 실제 사용자 거래가 발생한 135…의 PG와 snapshot을 읽기 전용 복제했고, 재배포·초기 자금 배분·새 거래·금융 metadata 쓰기는 모두 0이다.
- 실제 PG/JDBC·기존 JWT/지갑 연결·원래 genesis·4계약 codeHash·8개 기존 Receipt·최종 wallet `1000003.066148919985598442` cMKRW·CLOSED v2·EOA nonce8/controller nonce2·free/locked/unpaid/pending0을 실제 복원 RPC/API로 대조했다. PG drafts3/claims3/executions2/consents0/users2/walletLinks1 및 각 행 집합 hash가 조회 전후 같다. 결과는 `PASS_READ_ONLY_CHECKPOINT_SERVICE_RECOVERY_NOT_NEW_USER_TRADE_OR_FULL_AUDIT`다.
- 122.782초 후 소유 역할 5개 모두 exit0/OOMfalse/normalStoptrue로 종료했다. 원본 불변, 새 volumes/network 보존. 최신 snapshot 14,800,699 bytes / SHA `71192dd8e8cdbb58e78d6463efea3cb0d793964c9c1ccb71fae6b7ea123f6b19` / head7438. 다음 재개는 반드시 이 8d… 자손을 사용하며 135…나 더 오래된 조상으로 되돌리지 않는다.
- 증거: C worktree `deployment/runtime/c-checkpoint-resume/cdemo-8d5fd2ff832345bb/`의 result, before/after-clock, metadata-before/after, http-read-gate, checkpoint-lineage. 사용자용 1시간 창은 열지 않았다. 최신 자손 기반 명시적 live launcher는 별도 준비·검토 중이며 아직 실제 실행 NOT_RUN이다.
- 기존 TMS 웹+C 기능은 별도 `c-product-web-integration-worktree`에서 선별 통합했다. 역순 응답 차단 및 P0-B 감사 표현 보존 보완 후 선택52/전체 웹172 PASS, Vite build PASS(107 modules). 최초 독립 지적2건 수정 후 재검토1/2 중이다. 실제 통합 서버/Chrome/MetaMask 인수는 NOT_RUN이며 기존 단독 C 성공으로 대체하지 않는다.
- C 제품 Worker/청산, 전체 자산 감사·projection·복구, relayer revision/SIGNED, 1초 tick, AI 및 기존 서비스 전체 통합은 최종 범위에 유지한다. 아래 기록은 당시 시점의 이력이다.

#### 발표 화면과 실제 서버 지원 범위 — 소스·이번 읽기 Gate 기준

**당일 재개 도구 최종 준비 완료:** C worktree에서 `python -B deployment/c-checkpoint-live-launch.py`는 Docker/claim 없이 계획만 확인한다. 명시적 `--live-window`만 최신8d RO복제·자원/identity/PG/JWT/8Receipt/자산 사전검사 후 최대1시간 창을 연다. 최초 helper SHA 누락1건을 수정하고 순수10 PASS·실제 기본CLI PASS·독립 재검토1/2 필수없음을 확인했다. 현재 사용자창은 열지 않았고 실제live NOT_RUN이다. 다음 요청 때 검증된 기존 산출물을 재사용하며 재배포/초기분배/자동서명/재전송0을 유지한다. 종료 후 새 거래가 있으면 새 자산·nonce·metadata를 관측해 최신 자손에 보존하며 기존 상수로 되돌리지 않는다. 정확한 실행·STOP·수명·32MiB 보존 경계는 C `c-post-trade-checkpoint-resume-2026-10-24.md` 최종 절을 따른다.

기존 웹+C 선별 통합의 수정 후 독립 재검토1/2는 **발견된 필수 수정 없음**으로 완료됐다. 검토자 직접 선택52 PASS, 전체172/build107은 구현자 실행 결과로 구분한다. 실제 브라우저 Mock 캡처와 실서버 인수는 별도 범위다.

새 UI 공간은 원본 HEAD 기반의 기존 시장·legacy 선물·P0/AI 화면과 C 탭의 선별 통합이다. B-v2 worktree의 모든 backend/서명 지갑 변경, 취소 observer 또는 전체 감사까지 병합한 결과가 아니다. 원본과 B-v2/UI/AI의 미커밋 제품은 그대로 보존한다. 1366/390px 화면 캡처 시도는 첫 PNG 전 CDP 대기로 1회 중단했고, 03:51 KST 소유 profile Chrome 잔존0을 확인했다. 시각 QA는 NOT_RUN이며 사용자 Chrome은 변경하지 않았다.

| 기능 | 실제 확인한 범위 | 아직 충족하지 않은 경계 |
| --- | --- | --- |
| C 자산·OPEN/CLOSE·인출 | 사용자 MetaMask 5거래 완료, 최신 PG/RPC/API 읽기 복원 PASS | 현재 서비스는 정상 종료 상태. 새 1시간 창은 명시적으로 시작해야 함 |
| Synthetic 시세 | C 선택 App에서 실제 제품 provider·REST 시세 조회 PASS, 로컬3초 설정 | 기존 시장 차트와 STOMP를 같은 C App에서 브라우저 인수한 것은 아님 |
| 기존 시장 차트·현물·legacy 선물 | 기존 구현과 과거 인수 보존, 새 통합 프런트 회귀 PASS | 현재 해당 full TMS 서버를 새로 기동하거나 실제 거래하지 않음. C 선택 App은 해당 전체 API를 제공하지 않음 |
| 기존 감사 UI | 최신 P0-B 판정/coverage/원본 분리 표시 선별 보존·Mock 회귀 | C Vault 전체 자산 감사는 미구현/미인수. legacy MATCH를 C MATCH로 대신하지 않음 |
| AI | 기존 읽기 전용 UI·실모델 Q12 근거 보존 | C bytes32 ID·Vault 자산 대응 Tool/RAG 미연동, 신규 유료 호출0. C 청산 설명을 완료로 표시하지 않음 |

단일 full TMS+C 서버의 다음 필수 의존성은 (1) C API의 기존 JWT 사용자 및 DB FK identity 정합성, (2) `CCustodyRuntimeConfig`/`CDirectRuntimeConfig`의 동일 DataSource에 대한 additive schema와 최소권한, (3) demo 전용 `CDirectDemoApplication.DemoController.market`의 `/api/c-futures/direct/market` 조회를 제품 경로로 안전하게 옮기는 검증, (4) Oracle의 고정 identity/keyless App 경계, (5) 실제 full REST/WS와 C 브라우저 회귀다. 기존 C 사용자1을 다른 DB 사용자1과 동일인으로 추정하지 않으며 보존 DB에 full 제품 schema를 무단 적용하지 않는다. 발표 당일 재개는 이미 검증한 기존 단독 C 산출물을 우선 유지한다.

위 (3)의 최소 제품 적응은 별도 새 UI worktree에 `CDirectMarketController`와 테스트만 추가해 완료했다. 기존 MarketPriceService·JWT principal·401 예외를 재사용하며 명시 direct opt-in=true 및 demo 중복배제, 동일6필드·decimal문자열·원래 observedAt를 유지한다. 직접JDK21 compile/JUnit7 PASS/FAIL·SKIP·ABORT0·source/dependency불변, 최초 독립 검토 필수없음이다. 이는 ApplicationContext+standalone MockMvc/직접 호출 범위이고 실제 JWT/filter/fullApp HTTP·DB/RPC는 NOT_RUN이다. 기존 C149/compiled/현재8d/보존 DB에는 변경0이다. 다음 실환경 단위는 새 fullApp DB·새 시험 계정·RO 체인 복제본에서 시장/WS·공통JWT·C 읽기만 검증하는 것이며 사용자 실거래 시연 환경과 분리한다.

#### 후속 읽기 Gate 준비 — 컴파일 PASS / 실환경 하네스 준비 BLOCKED / 최소 기동 분리 진행 중

새 UI main277 + C에서 필요한31소스(전체53 중 미사용22 제외), 공통276 rawSHA 동일·FQCN중복0을 확인하여308소스를 새로 컴파일했다. source identity `7cf2f8d991b0847526cbe501e9fc888cafdd5ff23c31e566214391569058e571`, class607/tree `83ce1d83ce09631aaf34565b95268e7846cba4b3440bd0d8d58c981fb399bf30`. 비초기화8class 및 실제인수22개 name/isNamePresent 검사 PASS, 기존원문/compiled 덮어쓰기0다. 근거는 새 UI `deployment/runtime/c-full-read-compile-results/20261023T190509Z-a2f8c06a/`. 이 결과는 앱·DB·RPC·WS 기동 성공이 아니다.

실환경 하네스 재사용 분석에서 FaucetStore의 무조건 startup DDL/DML과 최소권한 API 역할 충돌을 확인했다. Faucet에는 enabled off flag가 없다. 기존 driver도 새배포·funding·축소schema/uid1을 전제해 이번 read-only fullApp에 그대로 쓸 수 없다. 따라서 실제 Gate는 PREPARATION_BLOCKED/NOT_RUN으로 유지하고 환경·claim을 생성하지 않았다.

안전한 독립 후속으로 새 UI worktree에만 `FaucetStore`/`ConditionalSchema` 기동 책임을 최소 분리하는 단위를 진행 중이다. 기존 default install와 정책·credit/status·SQL은 유지하며 local-read-gate profile의 명시 verify-existing만 owner가 사전 설치한 schema/function/trigger·정책을 읽기 검증한다. API role의 CREATE/owner/superuser 격상, 가짜 Store, 사용자 자산·C 정책 변경은 하지 않는다. 기존 생성자 호환·기본 금융 회귀와 별도 독립 검토 전 실환경 실행 금지다. 위308 컴파일 산출물은 보완 전 증거로 보존하며 변경 후 결과로 자동 승계하지 않는다. 발표용8d/동결149·재개 도구에는 영향이 없다.

**기동 분리 단위 후속 완료(코드/오프라인):** default install 본문·기존 생성자·Service/Policy/SQL을 유지한 local-read-gate 전용 `install`/`verify-existing` 분기를 구현했다. 후자는 sql.init=never/jpa.ddl=none 전제에서 고정 PG schema/function/trigger·정책을 SELECT로 확인하며 복구/repair/INSERT를 하지 않는다. 기존 H2 금융24 PASS, 최종 신규13 PASS·fresh main compile PASS, 독립 재검토1/2 필수없음이다. 최초 grants 테이블 owner 검사 누락1건은 원본4컬럼·PK·비소유 조건과 negative로 수정했다. 기존24는 unchanged default 경로의 선행 실행이며 최종 재실행으로 세지 않는다. 실제PG catalog/권한·owner bootstrap/fullApp Gate는 NOT_RUN. 다음은 검증된 PG 하네스의 안전 재사용 여부를 확인해 작은 새PG owner-install→API-verify 검증을 준비하는 것이다. 기존 C149/8d/공식claim/발표재개 도구는 그대로다.

### 2026-10-23 C 구현 승인 수신 — 현재 작업

#### 정확한 재개 체크포인트 — 시험 EOA 실제 OPEN→CLOSE→인출 PASS / 공개 EOA 확인 후 브라우저 새 환경 기동

**최신03시자동종료확인/발표당일재개Gate준비:** 135ae9a8는03:00:58에감독종료result생성,safeErrornull/originalUnchangedtrue,PG·Anvil·Oracle·copy exit0/App143·전부harness normalStoptrue/OOMfalse,3개volume/network보존true다. snapshot14,591,003bytes/SHA`eb9f99b9e305507d820f35b0e2c839b35877de91247c86ca98702b5e84fb50e6`/head7333/tx14를재확인했다. 종료snapshotstorage에서최종wallet/house/free/locked/unpaid/EOA8/Cnonce2/CLOSEDv2/terminalLock1/4codeHash와5성공Receipt(EOAnonce3~7)를읽기대조PASS다. mempool은snapshot미직렬화라pending0은마지막live관측と구분하며실제복원RPC에서재검증한다. snapshot에genesis후보header가2개여서원래genesis단일RPC선택도새Gate에서엄격확인하며스냅샷수정금지다. 신규profile/기존helpers재사용Gate순수7PASS·독립최초검토중,실Gate아직NOTRUN. 다음실행은최신135만RO복제/확인후즉시stop보존이며재배포/초기분배/거래재전송0이다. 기존웹+C통합은새`c-product-web-integration-worktree`에서프런트엔드선별개발병행,기존C/AI/UI원본은불변이다.

**최신실제사용자C 최소흐름완료:** 135ae9a8에서사용자독립MetaMask APPROVE3000→DEPOSIT3000→Long10/담보1000OPEN→CLOSE→free3003.066148919985598442전액인출의5개실제tx/서버ReceiptCONFIRMED를확인했다. 마지막withdraw fa5bb44e…/block4957/hashc63c3d97…이다. 동일block4989에서wallet1000003.066148919985598442/free0/locked0/미지급0/positionCLOSED/EOAnonce8/Cnonce2/pending0,house및실제Vault잔고9999996.933851080014401558와자산합계보존을확인했다. PGdraft3/claim3/실행2/지갑연결1이며첫Report미생성실패는보존한다. owner직접경로·로컬3초Synthetic/PGmetadata/Anvil범위PASS이며relayer/전체감사/청산/Claims/AI완료가아니다. 서비스03:00종료예정·상시환경아님. 다음재개는현재자손최신종료snapshot/DB·최종5Receipt·nonce8/2검증을사용해야하며이전미거래조상/하드코딩resume로되돌리면안된다. 전체tx/잔고근거는 C `dapp-c-direct-demo-2026-10-23.md`최상단에기록했다.

**최신사용자C CLOSE실제CONFIRMED/직접인출대기:** tx0675c567…/block4765/hashf60ec5b5…의저장Intent·실제canonicalReceipt4event검증PASS. 동일positionCLOSED/version2/locked0/userNonce2/pending0. Long기존수량식손익3.066148919985598442cMKRW=Receipt실현·지급이익일치,free3003.066148919985598442/house9999996.933851080014401558/unpaid·badDebt0/Vault자산합계일치. 사용자직접free전액인출을안내하며이완료전최소흐름최종PASS로확대하지않는다. 기존자료·현재환경유지,03:00종료예정.

**최신사용자C OPEN실제CONFIRMED:** 현재135ae9a8복원환경에서사용자MetaMask예치3000후Long10/담보1000 OPEN직접승인·실제서버Receipt4event검증PASS(tx c3bfb46a…/block4567/hashc8fb3c51…). 본인position a0413425… ACTIVE/version1/entry74825.61571115/qty0.133644072353551894/free2000/locked1000/userNonce1/pending0이다. 실제Report관측→채굴17초,3초내서명전제아님. 다음사용자직접CLOSE→실제잠금해제→free인출을안내한다. 현재포지션열려있으므로03:00종료전해당자산상태확인이필수다. C상세 `dapp-c-direct-demo-2026-10-23.md`최상단에전체tx/position/Receipt근거. 전체감사/relayer완료로확대하지않는다.

**최신10/24 02:01 같은환경 복원 웹READY:** RAM2558MiB확보후동결코드592d23aa…/순수11PASS/독립재검토1/2필수없음으로새자손 `cdemo-135ae9a88bb244c6`(recovery source16c3d610…)가READY다. 같은DB계정·지갑연결·genesis/4계약·기존3Receipt/잔고/nonce보존, 비공개현재시각보정정확1회+기존2초검사통과, 실제JWT/본인/지갑/account/Synthetic/ReceiptAPI통과. 웹32171/c-demo.html/RPC32172/chain31337,현재wallet1000000/free0/locked0/EOAnonce3/Cnonce0. 사용자MetaMaskOPEN/CLOSE대기다. **03:00KST이전세션종료**,감독session3052,원본/실패/claim보존·다음재개는이자손최신상태만사용. C `c-same-state-resume-preparation-2026-10-24.md`최상단참조. 아래RAM대기/BLOCKED는과거관측이다.

**최신 10/24 복원 수정·검토 완료 / RAM 대기:** 첫서비스복원은PG byteclone/systemidPASS후 DockerDesktop의file mount별칭 비교로 NEW_PG_ANVIL에서중단했다. 원본/실패claim보존·사용자노출/tx/clock0조건의한정자손재개와경로수정,보정전빈블록race회귀를완료했다. source592d23aa…/순수11PASS/독립재검토1/2필수없음이다. 후속기동은RESOURCES preflight에서 RAM1831<1920(89MiB부족),disk25.99GiB/running0으로새claim·환경생성전에차단됐다. 현재prepare/review작업은완료이고추가검토를기다리는것이아니다. 자원확보후같은동결코드로재개하며원본초기화/재배포/재컴파일/전체회귀반복없이사용자OPEN→CLOSE→인출접속환경확보에집중한다. 웹아직미기동,실제MetaMaskOPEN/CLOSENOT_RUN. C상세 `c-same-state-resume-preparation-2026-10-24.md` 최상단참조.

**현재 10/24 사용자 시각복구 승인 수신:** 원본/과거블록을 바꾸지 않고 새 복제 자손의 첫 새 블록을 실제벽시계에 1회 동기화하는 제한적 복구와 기존 DB·웹 재개를 승인받았다. `c-direct-same-state-resume.py`에서 원본PG RO coldcopy·새volume/동일systemid·DB계정/지갑연결 유지, 원본snapshot별 독점live자손claim, keylessApp·기존Oracle역할, 새STOP/evidence/1시간기동을 준비·검토중이다. 소스검토/실제JWT·API·자산·과거Receipt 재확인 전 READY가 아니며 현재사용자조작을 요청하지 않는다. 이전시각BLOCKED/원본자료는 보존한다.

**최신 10/24 같은환경 복원 probe BLOCKED:** RAM2544MiB/disk25.98GiB로 자원조건 충족. 새복원단위 최초독립검토 필수없음/무소켓8PASS 후 `cclone-0c6fd9a9ec3b458f` 1회(networknone·동일Anvil1.8.1·원본snapshotRO)에서 genesis/4codeHash/원래3Receipt·이벤트/잔고·nonce는 모두일치했다. 다만 복원체인시각이 실제보다1463.107초 뒤처져 NATURAL_WALL_CLOCK에서BLOCKED다. clone정상종료·원본hash불변·새서명/거래/배포/시간조작0, PG복제·서비스재개0/활성URL없음이다. 자원부족은현재원인이아니다. 시간검사완화나새체인대체없이 기존체인실제시각복구의 승인경계를 확인해야한다. 상세는 C `dapp-c-direct-demo-2026-10-23.md` 최상단. 실제MetaMask OPEN/CLOSE NOT_RUN유지.

**현재동일환경재개요청/자원BLOCKED:** 사용자가e0e5a246의같은계정·DB·체인으로진행을요청했다. 원래PG/Anvil컨테이너exit0·owner라벨/volumes/images보존을읽기확인했으나RAM1681MiB가최소1920보다239부족(disk25.92GiB/running0)이어서복원실행0이다. 원본초기화/재배분/임의재시작/앱종료없음. 메모리확보후동일Docker이미지의보존복제본에서genesis·계약codeHash·과거세Receipt·잔고/nonce연속성을검증해야하며이restore Gate는NOT_RUN이다. 호스트Anvil1.5.1/기존Docker표시1.8.1차이를혼용하지않는다. 같은환경으로이미복구됐다고말하지않는다.

**현재환경종료/사용자현금흐름부분인수확인:** e0e5a246에서실제MetaMask소유증명→approve→deposit→free3000직접인출까지확인했다. 인출tx ce1ea49c…/block3654/status1 및Transfer+Withdrawn3000은종료보존snapshot에서검증,token/VaultcodeHash·stateSHA일치·실제종료storage wallet1000000/free0/locked0확인이다. 01:02:49확인시이미자동정지여서freshRPC/서버중단UI인수는미실행이며원문launcher결과를고치지않는다. 이전approve/deposit은livecanonicalAPI확인,인출은snapshot확인으로구분한다. 새OPEN은만료임박에보류했으므로 **실제사용자OPEN/CLOSE NOT_RUN** 유지,과거시험EOA결과로대신하지않는다. 현재activeURL없음·기존자료보존·자동재시작0. 상세증거와다음새환경조건은 C보고서최상단이다.

**사용자실거래APPROVE확정:** e0e5a246에서MetaMask사용자approve를실제event→JWTReceiptAPI로APPROVE/CONFIRMED확인했다(tx7e81bf8d…/block2554/hash426a51a6…). allowance3000cMKRW/wallet1000000/free0/locked0/Cnonce0/EOApendingnonce1이다. 다음사용자는확정브라우저기록정리후3000deposit직접서명이며아직deposit/OPEN/CLOSE사용자인수PASS가아니다. 현환경01:01종료예정·만료후거래생성금지·원본data보존을유지한다.

**사용자인수후속:** e0e5a246활성환경의실제Chrome캡처로그인c-demo-user/MetaMaskEOA/chain·genesis·controller·Vault가실제identity와일치하며PG wallet_links user1/동일EOA등록을확인했다. 로그인폼이지속표시되는UI때문에혼동했지만로그인·지갑연결·소유증명등록완료다. 실제account token1000000/free0/locked0/allowance0/nonce0 확인,다음은사용자3000 exactapprove직접서명이다. 아직사용자OPEN/CLOSE등거래PASS가아니며자동거래/키대행0을유지한다.

**현재활성READY(10/24):** 사용자승인준비JSON보완추가검토1회PASS후새1회 `cdemo-e0e5a24638b0416f`/source`2ed9c86b…`가READY다. source/prepared/claim·RAM2803/disk26.2·기존실행0확인후기동,실제web/RPC/PG/bootstrap/JWT/Syntheticprobes및 **c-demo-user/<LOCAL_ONLY_PASSWORD_REDACTED> 명시입력HTTP로그인·본인조회PASS**를확인했다. 실제JWT3600초,종료예상 **10/24 01:01:57KST**(안내01:01),web32171/c-demo.html/RPC32172/chain31337,sameEOA. session3264실행중·개인키대행/자동사용자거래0·실제MetaMask소유증명대기다. 현재코드실행중수정금지,불확실거래확인전신규생성금지. 아래BLOCKED/이전종료시간은과거run기록이다.

**현재BLOCKED/서버정지:** 사용자시험비번통일을승인받아local계정만c-demo-user/<LOCAL_ONLY_PASSWORD_REDACTED>로고정하고4운영secret는무작위를유지했다. 기존7200351c는사용자기록0확인후소유STOP·자료보존(승인wrapper변경검출END_IDENTITY는그대로보존)했다. 최종브라우저재검토2/2PASS후새a7b0ccdf환경을열었으나START_ORACLE_APP JSONDecodeError로relayREADY전중단/모두정지·volume/state보존이다. 실제원본JSON은로그부족미확정,빈/부분readyJSON생성경합코드경계는무과금재현했다. 최소읽기보완후새prepare145120Z-58f159a7/source855bcc24…·순수19/compiled2/selftest2PASS·미사용claim,wrapper c06d1e8b…이다. 해당하네스검토cap사용완료라 **준비JSON읽기+안전진단보완추가검토1회/통과후신규기동1회** 예외승인전실환경재실행금지. 아래활성URL/종료시각은모두이전기록으로현재접속불가다. 보고서C dapp-c-direct-demo-2026-10-23.md최상단참조.

**현재활성1시간환경:** `cdemo-7200351c85f5491b`/source`ab2e8b6c…`는운영시간수정독립검토·새준비/자원통과후READY다. App/Oracle3600초·실제JWT exp-iat3600초/로컬HTTP·RPC·시세probes PASS, 웹32171/c-demo.html·RPC32172·chain31337이다. 가장이른종료예상 **10월24일00:16:38KST**(안내00:16). session70705실행중,같은공개EOA로사용자로그인/소유증명대기이며자동사용자거래0·MetaMask미인수다. 새비밀번호는신규run private로그인파일만,기존15분환경은정지보존. 아래기동전/15분URL·시간은과거기록이다.

**현재요청:** 사용자가15분환경종료로놓친뒤1시간수동시험환경기동을요청했다. 기존cdemo-0ab9122db70e487f는정상정지/자원보존/사용자인수NOT_RUN이다. 로컬App명시flag/Oracle/relay/launcher/JWT를최대3600초로일치시키되Report30/Intent120/시장5초/권한/금융/키분리는그대로유지했다. JUnit122PASS·새baseprepare140536/source9224bb17… PASS후브라우저새준비와독립검토중이며실제기동은아직없다. 준비/검토/자원통과후동일공개EOA로새환경을열고실제URL·종료시각을안내한다. 아래READY주소는이전종료환경이므로사용하지않는다.

**최신실행READY:** 사용자가같은EOA를확정해신규browser `cdemo-0ab9122db70e487f`/runtime source`44f0db9a…`를기동했다. 준비149source/claim/검토SHA와RAM2637MiB/disk27.36GiB/기존실행0확인후새리소스만생성했다. bootstrap·실제loopback정적웹/RPCchain31337/genesis/EOA·JWT/me/config/제품Synthetic읽기PASS,웹 `http://127.0.0.1:32171/c-demo.html`,RPC `http://127.0.0.1:32172`다. 사용자키생성/자동사용자거래0·MetaMaskNOT_RUN이며소유증명대기한다. 로그인private파일은신규runtime만,문서원문없음. session38626 bounded실행중·App종료예상22:50:21KST이므로만료후자동재기동/재전송하지않는다. 현재pending/userposition없음(신규fixture미거래),이후사용자서명발생시실제상태를재조회한다. 아래EOA답변대기/URL미발행은이전기록이다.

브라우저최소준비6파일 `132549Z-79940b60`/source`df496353860f3e52340852b9c301d3a5c4eabc2f04d7688be87bfa0091232661`+신규launcher(`f72f4756…`)는순수15/무소켓selftest·compiled2/selftest2PASS,독립재검토1/2필수없음이다. 149개현재source불일치0/새RUN_CLAIM미사용이다. APP/RPC각2slot·App768(no verify동시)로기아와자원상한을보완했다. 기존143소스/성공Gate/원본환경은불변이다. 실제MetaMask공개EOA확인답변대기이며주소추정/키요청/새환경기동은하지않았다. 답변후자원·identity검사→새browser-run기동·실제HTTP/RPCprobe→READY확인후URL안내→사용자수동서명순서다. 아직브라우저접속URL미발행/MetaMaskNOT_RUN이며추가유료승인필요없다. 상세실행후보명령·보존증거는 C `dapp-c-direct-demo-2026-10-23.md`최상단이다. 아래준비중/검토대기는과거기록이다.

**최신결과:** 이번한정추가검토필수지적없음후신규Gate `cdemo-a3b7bc88e4e8482e` /source`e3266434f4b4d55672b2f0be3d8e93536f0e79baed5d1163e051c02516caad86`는90.484초에실제로그인·EIP191·approve·deposit3000·Long10OPEN담보1000·CLOSE·free3000.326146301086616446전액인출PASS다. canonicalReceipt/API·기존금융식/PnL·Vaultfree/locked/unpaid0·DBmetadata검증,6역할exit0/OOMfalse/source불변·새volume/net/state보존이다. **local3초·시험EOA·owner직접호출만PASS**며MetaMask/relayer/전체감사/최종통합은아니다. 다음사용자브라우저최소환경준비를시작했고공개테스트EOA확인만요청했다. 실제URL/RPC는준비후안내. 아래실행중/승인대기문구는이전이력이다.

브라우저 후속 현재 범위: 신규browser bootstrap/고정목적지TCP pipe/loopback relay의 추가파일을 별도작업단위로 준비중이다. 기존Gate143소스는 모두hash불변이며 성공fixture를 재시작하지 않는다. 실제사용자 공개EOA답변·offline compile/회귀·독립검토·신규owned기동/HTTP/RPC확인은 아직미완료라 URL은미발행이다. owner직접거래의수동확인에는Report30초를적용하고3초이내MetaMask서명을조건으로삼지않는다. 최종계획의1초tick/C relayer revision·SIGNED복구/전체감사/통합/AI는유지한다.

브라우저최신준비 `132549Z-79940b60`/source`df496353…` compile/selftest2·순수15PASS/원본143불변,새boundedlauncher무소켓selftestPASS다. 최초독립검토의APP/RPC공유slot기아를역할2개씩분리하고App768(verifyDriver미동시)로RAM1920예산내보완했다. launcher포함재검토1/2요청중이며실제새환경기동/Chrome/MetaMask는미실행이다. 공개EOA답변후에도검토·자원·실제HTTP/RPC검사통과전URL을안내하지않는다.

**최신후속:** 사용자의 이번3초설정한정추가검토1회는필수지적없이완료했다. manifest(localOnly/tick3000)→Driver설정→실제Appready→Gate대조를연결한새준비 `130219Z-9b82332b`/source`e3266434f4b4d55672b2f0be3d8e93536f0e79baed5d1163e051c02516caad86`로 승인된신규Gate1회실행중이다. 기준125034준비는소스일치/RUN_CLAIM미사용확인후보존했다. 자원2535MiB/27.93GiB,Docker28.4,기존실행0이며실제결과확인전PASS아님. 아래검토승인대기문구는이전기록이다.

최종무과금준비 `c-direct-demo-prepare/20261023T125034Z-0ddc0050` / source`ee9d3e36bfb33b63966b40890b3c6818c3e3c2df6d12b1e315567a9d949e5ca3`는 각역할compile/selftest·실제App config정상2종/잘못된cadence거부·SQL자원·웹build·Python17PASS다. RUN_CLAIM미사용/실환경미실행이며 이 후보의좁은추가검토승인만대기한다. 실행컨테이너0, 원본dirty10/AI보호156불변을최종재확인했다. 사용자용URL은아직없고실제Chrome/MetaMask는NOT_RUN이다.

Direct backend122PASS·최종재검토2/2필수없음으로 claim의 중복FORUPDATE 권한오류를 최소 수정했다. 새 실환경 `cdemo-26694c18020248ac`/source`f1d98ce…`는 실제로그인/지갑연결/제품Synthetic/approve/deposit/서버Receipt/OPENdraft 성공 후 execution409PRICE_CHANGED로 안전중단했다. OPEN전송없음·기존환경변경없음이며 전체최소흐름은 BLOCKED다. 모든소유서비스정지·새DB/체인/evidence보존이다.

로컬발표Synthetic tick3000ms+draft전 bounded read-only tick관측을 보완하는 하네스 최종재검토2/2가 Appguard1000ms불일치를 발견했다. Appguard와 compiled정상/거부config self-test를 수정하고 무과금검증중이나, 이 단위검토한도를 소진했으므로 **해당보완 추가검토1회와 통과후 신규Gate1회**의 좁은예외 전 실제실행금지다. `124805Z-83e8576b` 준비/claim미사용으로보존. SQL/금융/5초/30초/120초/사용자조건은완화하지 않았다. 1초tick안정성·실제Chrome/MetaMask는미완료다. 상세 C `dapp-c-direct-demo-2026-10-23.md` 최상단이 최신이며 아래진행중문구는과거기록이다.

발표 최소 직접 경로의 첫 실제 신규 Gate `cdemo-8910bf8bacf54eaa` / source `034799dc…`는 로그인·지갑연결·제품Synthetic·실제 approve/deposit·서버canonical Receipt·OPEN draft까지 성공, OPEN execution POST의503 `C_DIRECT_STORAGE`에서 안전 중단했다. PG 로그는 append-only claim SELECT FOR UPDATE의 UPDATE권한 부족을 입증했다. 사용자 행 직렬화 잠금은 유지하면서 불변claim의 중복 FOR UPDATE만 제거하는 최소수정과 회귀·backend재검토2/2 진행 중이다. 기존 권한/SQL/금융/계약을 완화하지 않는다. 실패 fixture/claim/Oracle응답은 보존됐고 OPEN전송은 없었다. 모든 소유서비스 정지, 실MetaMask·OPEN/CLOSE/인출 전체는 아직 NOT_RUN. 아래 준비상태는 과거 체크포인트이며 최신 결과로 승계하지 않는다.

현재 화면은 C단독 login/지갑연결/approve/deposit/OPEN/CLOSE/freewithdraw 버튼과 exact ABI/금액·pending 보호까지 구현됐다. **실제 신규서비스 URL·MetaMask 인수는 아직 NOT_RUN**이다. Direct backend121PASS·readcore55PASS, 웹25/전체152PASS·각재검토1/2 필수없음이다. 검토가 실제연결누락2개(`/api/me`, client tx/Receipt params)와 손상pending fail-open을 잡아 수정했다. 최신 준비 `c-direct-demo-prepare/20261023T121444Z-7fd17ce1` / `87977d1704096b63ee1bdcc476160895bc0b83861f086cff2619d95ff5c716a6`는 server/oracle/driver compile·compiled selftest0, SQL4 exactclasspath·-parameters·key분리·새ownedwebbuild0/Python16PASS다. 새actual 하네스 검토가 다음이며 RAM1920MiB/disk2GiB/이미지/소유권 통과 후 내부PG/Anvil/제품Synthetic 시험EOA Gate1회를 수행한다. RUN_CLAIM 미사용, 기존환경변경0이다. 자세한 근거는 C `dapp-c-direct-demo-2026-10-23.md` 최상단이다.

사용자가 발표용 `예치 → 사용자 승인 → OPEN → CLOSE → 직접 인출`을 우선하도록 조정했다. 기존 계약의 **executeDirect + MetaMask owner 온체인 tx 서명**을 최소 경로로 선택했다. 컨트랙트의 owner/EOA/nonce/Intent120초/Report30초/Oracle/가격·수량·금융 검증은 그대로이며 별도 EIP-712 사용자 서명을 검증한 relayer 시연으로 표현하지 않는다. relayer revision/SIGNED 저장·광범위 복구·전체감사는 후순위로 보존한다. 현재 E1d 구현은 시작하지 않았다.

SQL resource 보완은 신규 `ccpg-54a43006c5954d1e`에서 실제 PG6PASS/skip·abort0/양컨테이너Exit0로 끝났다. 이어 E1b55PASS·독립검토 필수없음과 E1c unsigned Report 후보 `c-report-candidate/20261023T110636Z-387b1aef` / `39b50dccf3631ee92dcc39a221d86d359cba4a20d1d251ede0367c46b82ad5d4`의69PASS·독립검토 필수없음을 확보했다. 기본nonce runner도 `110847Z-243c0cf9`에서55PASS다. 이 결과들은 실제 Report API·MetaMask·체결 성공이 아니다.

현재 신규 direct API/JWT 본인지갑/immutable 실행 Report·단일claim, keyless Spring↔전용 내부Oracle signer, C전용 opt-in 웹·owner직접승인, canonical tx/Receipt 읽기와 제품Synthetic selectedApp을 연결 중이다. 기존 계약·인증을 제거하지 않고 user/relayer 키는 API JVM에 넣지 않는다. Oracle키만 새 격리 signer 프로세스에 둔다. 사용자 서명이 필요한 시점 전 실제 시험EOA/PG/Anvil/HTTP Gate와 독립검토를 거친다. 기존자료·원본/UI/AI/B worktree 보존, commit/merge/push/유료/삭제/전역재시작 금지 유지.

독립 E1b 구현 `c-nonce-admission/20261023T105714Z-f76ee6ce` / source `9b8bce39e3d1a8351a62d235235005848831daf495f892954d3d6c732c4523a0`는 Mock pinnedRPC/H2 직접JUnit55PASS/FAIL·SKIP·ABORT0, compile/test0·stderr0·source/JAR/SQL불변이다. 기존 조회/지갑 회귀53개도 `c-service-read/20261023T105628Z-b0827ada` / `a46a32a7…` PASS다. `review_c_nonce_admission` 최초검토 필수없음(source23/class62대조)이며 E1a 기존입력11개 불변이다. 최초실행은 테스트수정과 겹친 sourcesUnchanged=false로 BLOCKED 이력을 보존했다.

새 pinned admission 조회와 exact consent 기반 durable user nonce 슬롯만 구현했다. RPC는DBlock밖, user/wallet/서명/identity/window 재검증, UNIQUE/append-only·commitunknown 관측우선, 자산장부변경/자동해제/서명·전송없음이다. **실제 nonce PG/RPC/HTTP는 NOT_RUN**이며 무해제 슬롯의 liveness/향후 SIGNED lock연결도 남았다. 다음은 실행가격 Report 후보·revision 영속화이며, 관련 nonce/revision PG제약 검증은 새 통합단위로 묶어 불필요한 fixture생성을 피한다. 과거 actual서비스PASS를 변경된 Reader소스로 승계하지 않는다. 상세 C `dapp-c-nonce-admission-2026-10-23.md`.

사용자가 이번 SQL resource 배치·compiled ClassLoader self-test에 한해 승인한 추가독립검토1회와 신규PG1회를 각각 사용했다. `review_e1a_resource_exception`은 필수 지적 없음이며 source13/snapshot13/fixture184 exact hash, 제품·SQL 불변, Python10 PASS와 compiled self-test를 직접 확인했다. 기존 C서비스/C4b 예외를 재사용하지 않았다.

새 `c-consent-pg/ccpg-54a43006c5954d1e/result.json`은 **C_CONSENT_ACTUAL_POSTGRES_6_PASS_NOT_SERVICE_OR_CHAIN**이다. source `2fbdfe8d3d28862fa615563d271252f31d8c83f21b6c6040f84970d97422b3a8`, found6/succeeded6/failed0/skipped0/aborted0, 실제19.219초다. 실행 직전 RAM2,502,713,344bytes/disk31,874,854,912bytes로 기준 충족, PG·Java 모두 Exit0/OOMfalse 정상종료, source/prepared/runtime fixture불변과 새 network/volume 소유존재를 확인했다. 원래 실패run/claim/DB는 보존했다. 과거 projection PG10개 및 조회/지갑 HTTP Gate와 별개다.

완료 범위는 실제 PG 동의 metadata·UNIQUE/FK·append-only·least privilege·동시성·rollback·commit unknown 관측이다. nonce admission/실행 Report/revision/SIGNED/전송·복구/웹은 아직 연결 전이며 체결 가능성 또는 확정 증거가 아니다. 다음 작은 단위는 저장된 exact consent에 대한 durable user nonce admission이며, 기존 자료/경제 정책/서버 keyless 경계를 유지한다. 아래 승인 대기·BLOCKED 문구는 각 당시 이력이다.

#### 이전 체크포인트 — 서비스 Gate PASS / E1a SQL resource 최소 보완 오프라인 완료

사용자가 승인한 capability 표기 추가검토1회·신규 C서비스Gate1회는 모두 사용했고 **조회/지갑 연결 실환경은 PASS**(`c-live-6f3298d68b9b4fa6`/`3ba5d075…`)다. 전체거래서비스/MetaMask 인수로 확대하지 않는다.

그 뒤 자율 진행한 독립 E1a 동의 저장소는 H214PASS/구현검토 필수없음이나, 별도 신규PG1회 `ccpg-bf266731af0e4514`는 SQL classpath resource 누락으로6개본시험전 BLOCKED였다. 아래 기록과 실패원본/RUN_CLAIM/DB/volume/network를 보존했다. PG정상Exit0·Java실패Exit1/OOMfalse를 구분한다.

최소보완은 **새 PG 하네스3파일만**이다. SQL2개를 fixture root와classes에 같은 bytes/hash로 배치하고, 실제compiled ClassLoader의 resource()에 접근하는 무환경/무JDBC/무socket self-test와 누락 negative를 추가했다. production/core/SQL/API/금융/키분리/실제6시험/timeout/동시성정책은 바꾸지 않았다. 새 `c-consent-pg-prepare/20261023T103437Z-199cd8bf` / source `2fbdfe8d3d28862fa615563d271252f31d8c83f21b6c6040f84970d97422b3a8`는 PREPARED_NOT_POSTGRES·compile0/stderr0·resource self-test0/validtrue/stderr0·Python10PASS/0.045s·source/deps/두SQLhash동등true다. Docker/유료0, **추가검토·actual PG는 미실행**이다.

**필요한 좁은 승인:** E1a resource배치/compiled self-test 보완에 한해 추가독립검토1회와 필수없음·자원충족 후 **새 격리PG Gate1회**. E1a의 최초설계1+재검토2/actual1회가 사용 완료됐기 때문이다. 이전 C서비스/C4b 예외를 전용하지 않는다. 승인 후 현재source/prepared hash·RUN_CLAIM미사용 확인→검토1회→RAM≥1920MiB/disk≥2GiB·image/owner확인→`python deployment/c-consent-postgres-check.py run 20261023T103437Z-199cd8bf` 신규1회다. 기존실패run/claim은 절대재사용하지 않는다.

보호156개 변경/누락0·rootdirty10/HEADf9353c3·실행컨테이너0을 최종대조했고 commit/merge/push/유료/삭제/전역재시작0이다. 다음 의존개발은 PG제약·권한·원자성 실제통과 후 C nonce admission/실행Report/revision/SIGNED/전송·복구/웹이다. 아직 C fullaudit/제품Synthetic Worker/실제MetaMask/C7은 미완료다. 현재 실행 중인 테스트는 없다. 장시간 다음 단계 전 `/status` 확인·필요시 `/compact`를 권장하며 정확한 재개 기준은 이 최상단과 C `dapp-c-consent-store-2026-10-23.md`다.

#### 최신 실제 상태 — 조회 Gate PASS 유지 / E1a PG는 classpath resource 누락으로 BLOCKED

같은 E1a 마지막재검토2/2 필수없음·PGprepare `20261023T102752Z-48c7e49a` / `02684157746a0a6ca40fc591923bb0cfa8fad5e2336f9e284d1785f7c5aa2cfa` compile0/Python9PASS 후 신규 PG1회 `ccpg-bf266731af0e4514`를 실행했다. 실제 RAM2,732,957,696bytes/disk32,079,818,752bytes·총14.797秒다. **BLOCKED**이며 JUnit container setup에서 `C_CONSENT_PG_RESOURCE_MISSING`, found6/success0/container failure1이다. 여섯 PG 본시험은 미실행이다. PostgreSQL 제품 결함이나 Windows RPC 장애로 분류하지 않는다.

확정 원인은 SQL두파일을 fixture최상단에만 복사하고 runtime classpath를 `/fixture/classes:/fixture/jars/*`로 제한한 하네스 resource배치 누락이다. Java의 ClassLoader resource조회에서 원문walletSQL을 찾지 못했다. 신규PG owner/SCRAM·공개sentinel/users8 초기생성 뒤, wallet/consent DDL과 본시험 전에 중단했다. PG는Exit0/OOMfalse 정상종료, Java는실패Exit1/OOMfalse이며 강제종료가 아니다. top safeError `C_CONSENT_NORMAL_STOP_FAILED`는 Java의실패종료를 반영한 후처리이고 첫원인 RESOURCE_MISSING을 가리지 않도록 원본JUnit증거를 함께 읽는다.

source/prepared/runtimefixture 모두불변, 새network/volume owner존재 확인·삭제/재사용없음, 실행컨테이너0·보호156개 변경/누락0·rootdirty10 유지다. 기존 C 실제read/wallet Gate `c-live-6f3298d68b9b4fa6` PASS와 E1a H214PASS는 그대로 보존하되 actualPG PASS로 승격하지 않는다.

현재 SQL의root/classpath 양쪽 exactcopy·해시와 실제compiled resource self-test/누락 negative를 **오프라인 최소 보완 중**이다. production/API/금융/계약/H2원본은 바꾸지 않는다. E1a 최초1+재검토2 상한·실제PG1회는 사용 완료했으므로 추가검토/새PG실행을 자동으로 하지 않는다. 준비후 이 resource 보완에 한한 추가검토1회·통과시 새PG1회 예외가 필요하다. 기존 C서비스 성공Gate 예외는 전용하지 않는다.

#### 현재 E1a — 오프라인14 PASS·구현 검토 완료 / 실제 PG 준비

C draft/동의 저장소7신규파일은 `c-consent-store/20261023T101729Z-24a8b6eb` / `d094ad7622ace598cd72e0430ffe4b8f167d636005449ad767f14126bc692d18` 직접 compile/test0·14PASS/무skip·stderr0/source·deps·schema불변이다. `review_c_consent_store` 구현 후 재검토1/2 필수없음, 소스11/class37·기존Gate34 변경0을 직접 확인했다. 최초설계1+재검토1 사용/최종재검토1 남음이다. C 실제 read/wallet Gate PASS는 유지한다.

이는 E1a의 순수/H2 저장 검증이며 PostgreSQL trigger·권한·잠금/동시성·실제DDL은 아직 NOT_RUN이다. 현재 작은 PG-only Gate(tester+prepare/run runner+순수Python회귀3파일) 준비를 이어간다. 기존하네스·helper·production은 변경하지 않고 frozen SQL/source/classes를 묶으며, 준비 후 마지막검토→자원/이미지/owner검사→새PG1회 순서다. user nonce admission/Report/revision/SIGNED/Sender/HTTP/자산정산/MetaMask로 확대하지 않는다. 사용자 추가조작·유료·기존환경변경은 없다.

#### 후속 E1a 착수 — C draft·동의 기록만 구현 / 실행 권한과 분리

실제 read/wallet Gate 통과 후 승인된 다음 C서비스 단위를 작게 나눴다. E1a는 fixed배포/JWT사용자↔기존Cwallet link에 귀속한 draft·EIP712 사용자 동의의 원자적/멱등/append-only 저장과 무과금 tests만이다. 신규 C 전용 schema를 명시설치하며 기존 wallet·금융 schema/서버API·Gate를 수정하지 않는다. 사용자Intent digest/65byte EOA서명/domain·고정scope를 검증하지만 현재RPC nonce/EOA code/position/fresh가격/잔고·실행 가능성은 후속 service가 다시 검증해야 한다. 결과를 PREPARED/체결승인으로 표시하지 않는다.

user nonce 입장예약·execution revision·raw·Sender·금융예약·자동전송은 이번 작은 단위에서 만들지 않는다. DB자산을 생성/잠그지 않고, commit-unknown은 같은key조회 우선이며 자동retry0다. 새 immutable draft/consent+모델/저장소/2tests/최소직접runner/문서만 추가한다. 순수/H2와 실제PGtrigger·경합 검증은 구분한다. 최초설계 독립검토 `review_c_consent_store` 진행 중(최초1+구현후재검토최대2), 구현 agent `inspect_c_service_connection`; 기존cap예외를 재사용하지 않는다.

#### 최상단 확정 — C 조회·지갑 연결 실제 HTTP/PG/Anvil Gate PASS / 거래 연결 후속 진행

이번 추가 예외 검토1회 `review_c_capability_exception`은 필수없음으로 완료했고 신규 실행1회 `c-live-6f3298d68b9b4fa6`은 **C_READ_WALLET_ACTUAL_HTTP_PG_ANVIL_PASS_NOT_FULL_TMS_OR_BROWSER**다. source `3ba5d0753f7b9c3a429689ccf36d5f9e74fb4dd96606a67bfae67c9293939ac0` 불변, failure null, 실제 실행 RAM2,989,068,288bytes/disk32,290,643,968bytes다. 이번 좁은 예외 검토·실행은 사용 완료이며 다른cap를 풀지 않는다. 이전 `c-live-c43c47b33c3c4081` BLOCKED/Exit137 증거는 그대로 보존한다.

실제 선택 Spring Boot HTTP에서 root JWT filter/JPA 사용자 조회, 잘못된JWT·타서명JWT·없는user·잘못된Origin 거부, EIP191 소유증명, 동일wallet 두 사용자 경합 정확히1성공/1충돌·재사용 거부·타사용자 challenge 거부, PG consume 실패주입의 link+proof rollback 및 sentinel/user행 보존을 확인했다. `/positions/{id}` 종료/자기활성 조회, `/claims/{id}` 자기claim900·타인404, ADMIN wallet우회 차단, 동일 pinned block free/실제Vault토큰 getter와header 재확인도 PASS다. DB APIrole DDL/사용자변경 거부·SCRAM 분리도 확인했다.

실제 Linux HTTP fault 서버의 503/302/401 요청은 각각 wire1·C_RPC_HTTP_STATUS, slow body는 wire1·C_RPC_TIMEOUT/202ms였으며 redirect-target 요청0이다. 키는 시험Driver만, 서버keyless 유지. 선택조회로 자금상태가 바뀌지 않았음을 최종 검사했다. 4개 소유 컨테이너 모두 정상정지·정지Anvil state463,670bytes/SHA `5b206b0ad0f3b537d335c56788720d47dacd8ade9effba1fd255dd41e723583b` 보존이다. 이는 재시작 증명이 아니다.

**범위:** 신규 시험EOA/작은house100·통제Oracle·선택C Spring context 인수다. 전체TMS/브라우저/MetaMask/제품Synthetic·C Sender·전체감사는 아니다. completeCoverage=false를 유지한다. 보호156개 변경/누락0·root dirty10·실행컨테이너0 재확인, 기존자원변경/유료/commit/merge/push/삭제0.

다음은 승인된 E1 C 전용 draft/동의/append-only revision/활성head/SIGNED metadata 연결이다. Vault 잔고는 온체인 정본이며 DB돈예약을 만들지 않고, pure/H2와 실제PG를 구분한다. 기존 실행계획을 기준으로 작은단위/불변식/검증범위를 확정한 뒤 구현·독립검토를 계속한다. 사용자 추가조작은 아직 필요 없다.

#### 현재 실행 승인 — capability 동등 표기 예외 검토1회·신규 Gate1회

사용자가 위 두 항목을 명시 승인했다. `review_c_capability_exception`이 frozen runner/test 두 파일과 이전/신규 prepare·보안 경계의 독립 읽기 전용 검토를 진행한다. 대상 prepare `20261023T095259Z-6e3ef966`/`3ba5d075…`의 현재 34개 소스 SHA 불일치0, compile/self-test 각0/Python14PASS다. 읽기 전용 자원확인은 RAM3,330,052,096bytes·disk32,367,280,128bytes·실행컨테이너0·고정3이미지Linux/amd64일치다. 필수 지적이 없고 실행 직전 기준을 충족할 때만 해당 prepare로 새 identity Gate1회를 실행한다. 과거 실패 자원은 재사용하지 않고 다른 검토 한도는 유지한다. 아래 승인 대기는 과거 상태다.

#### 최상단 체크포인트 — 승인된 Gate 1회 BLOCKED / 동등 capability 표기 보완 검증 완료

자원 확보 후 RAM 3,390,267,392bytes·디스크 32,585,322,496bytes로 기준을 충족해 metadata 예외 검토 PASS 소스 `e1d98f3c…`의 신규 Gate를 **1회 실행했다**. `c-service-live/c-live-c43c47b33c3c4081/result.private.json`은 `BLOCKED / C_SERVICE_PG_INITIALIZATION_SCOPE`다. Docker 실제 `CapDrop=['CAP_NET_RAW']`를 하네스가 `NET_RAW`와 다른 권한으로 오인한 것이 확인됐다. PG 로그에는 최종 서버 ready가 있으므로 PostgreSQL 초기화 자체 실패로 단정하지 않는다. Anvil/App/Driver·사용자 키·체인 거래·실제 HTTP 실행은0이다.

실패 정리의 소유 PG는 Exit137/OOMKilled=false로 종료돼 정상 정지 성공이 아니다. 기존 증거와 신규 정지 PG/네트워크/볼륨을 모두 보존하고 재사용하지 않는다. cleanup과 초기화 경합은 신호 시각 증거가 부족해 가설로 남긴다. Exit137 거부·자원·키분리·금융 정책은 그대로다.

runner/test만 정확히 단일 `NET_RAW` 또는 `CAP_NET_RAW`를 허용하고 추가·중복·혼합 capability를 거부하도록 최소 보완했다. 새 offline prepare `c-service-live-prepare/20261023T095259Z-6e3ef966` / source `3ba5d0753f7b9c3a429689ccf36d5f9e74fb4dd96606a67bfae67c9293939ac0`는 PREPARED_NOT_ACTUAL_HTTP_PG_ANVIL, 두 self-test0·Python14 PASS이며 core/API/계약/App/Driver는 불변이다. 실제 C 서비스 Gate는 여전히 BLOCKED다.

**다음 승인 필요:** metadata 예외 검토1회와 실제 Gate1회는 사용 완료다. capability 표기 보완에 한한 추가 독립 검토1회와 통과 후 신규 격리 Gate1회 예외를 요청했으며 아직 승인 미확인이다. 기존 예외를 전용하거나 무단 반복 실행하지 않는다. 승인 후 자원·현재 소스/산출물/이미지 재확인→좁은 검토→새 Gate1회 순서다. 아래 RAM 대기·실행 미사용 문구는 과거 기록이다.

최종 읽기 전용 확인: root HEAD `f9353c33244565b30fb130dfb6159eab441a3523`, 기존 dirty10 유지, 보호 파일156개 변경/누락0, 실행 컨테이너0. C5a1/C4b/C5a2 PASS와 순수 formatter8 PASS는 보존하되 C HTTP/거래서비스/MetaMask/전체감사 완료로 확대하지 않는다. commit/merge/push·삭제·유료·전역 재시작0.

#### 정확한 재개 — metadata 예외 검토·독립formatter 완료 / 실환경은 RAM만 대기

사용자 승인 추가 metadata검토1회는 `review_c_metadata_exception` 필수없음 완료했고 실행승인도 유효하다. 현재 frozen Gate `e1d98f3cc88bbcf6d5de1c093b6a6ffa0771c9296f06360dede77e0ff1aec4f1`/prepared `20261023T085722Z-ee075b27` 소스·산출물 일치, 이미지3개 일치다. 그러나 마지막 제한된읽기 RAM1,558,663,168bytes(약1486MiB)<1920MiB로 실제Gate명령/새fixture생성0이다. 디스크32,557,203,456bytes, 실행컨테이너0이며 기준인하·앱종료·전역재시작·무한반복검사 없음. 사용자에게 불필요앱 정상종료로0.5~0.8GiB추가확보를 요청했다. **추가 실행승인은 필요 없으며 RAM 확보 통보 후 자원/identity 재확인→신규 Gate1회**가 다음이다.

RAM대기동안 진짜독립 C Intent JSON formatter3파일을 구현·검증·검토까지 완료했다. `c-intent-typed-data/20261023T093549Z-839ee3e6`/`3fe389fd093f7fae4a02a1dad6bf71908a3e16d162fdb808517d5d7d848ec513` compile/test0·8PASS/무skip, `review_c_pure_typed_data` 첫독립검토 필수없음이다. source5개/class21개 및 frozen Cread24/Gate34개 해시 일치를 검토자가 직접 대조했다. 이는 C22필드/JSON/digest/정밀도만이며 금융·권한 재판정·서명생성·거래 전송·UI/API 연결이 아니다. 이 단위 최초검토1회사용/재검토최대2회이며 다른cap초기화는 없다.

이후 C metadata/Sender·복구/웹의 의존구현은 실제read/wallet Gate가 먼저다. 전체서비스/MetaMask/fullAudit/C7 완료로 확대하지 않는다. 다음실행명령 `python deployment/c-service-live-check.py run 20261023T085722Z-ee075b27`는 RAM≥1920MiB/디스크≥2GiB와 source/artifact/image/owner를 다시확인한 뒤 한 번만 실행한다. 모든기존성공/실패/보호파일·원본dirty10·OPEN/DB/체인/AI상태보존, commit/merge/push·유료·삭제0.

#### RAM 대기 중 독립 진행 — C Intent JSON8 PASS / 별도 최초 검토 중

metadata예외검토는 필수없음 완료했고 실제서비스Gate1회 실행승인은 유효하다. 다만 제한된 최종자원읽기 RAM1,558,663,168bytes(약1486MiB)<1920MiB/디스크32,557,203,456bytes라 실행명령/새fixture생성0을 유지했다. 추가권한 승인이 아니라 사용자 정상앱종료에 따른 RAM 여유가 필요하다. 무한검사·기준인하·전역재시작·기존앱종료는 하지 않는다.

독립적으로 C계약/type/기존CEip에만 의존하는 순수JSONformatter/8test/최소offline runner3파일을 구현했다. 첫 javac @argfile JVM인수오류compile2를 보존한 뒤 directargv분리만 수정, `c-intent-typed-data/20261023T093549Z-839ee3e6`/`3fe389fd…` compile/test0·8PASS/무skip이다. C22필드·Cdomain·정밀문자열·불변graph와 실제JSONencoder→golden/CEip digest를 대조했다. JWT/PG/RPC/실시간nonce·금융판정·키서명·거래/API연결은없다. `review_c_pure_typed_data` 최초검토중이며 Cread347321/Gatee1d/C5/API/schema/기존runner 불변이다. C작업metadata·Sender·복구·웹의 의존 구현은 아직 실제read/wallet Gate 이후다.

#### 최신 체크포인트 — C metadata 예외 검토 PASS / 실제 Gate는 RAM 부족 전 미시작

사용자가 **이번 컴파일metadata보완에 한해 추가독립검토1회 및 조건충족 후 신규실환경Gate1회**를 명시 승인했다. `review_c_metadata_exception`은 source/새server·driverfixture해시·실제javap를 대조해 **발견된 필수 수정 없음**으로 완료했다. 새 두 HTTP경로에서 `MethodParameters u,id`와 self-test의 `isNamePresent()/id` 검사를 확인했고 core24개·계약·Driver/키분리 불변을 확인했다. 예외 검토1회는 사용완료, 다른 한도는 유지한다.

실제 실행 전 읽기 전용 확인: RAM1,522,970,624bytes(약1452MiB)<필수1920MiB, 약468MiB 부족. 디스크32,564,174,848bytes·고정이미지3개 Linux/amd64/ID일치·실행컨테이너0이다. **새 C 서비스 run명령·fixture·network·volume·container·PG/Anvil/Spring/Driver 실제생성0**, Gate1회는 미사용이다. 기준인하/기존앱종료/전역재시작/기존환경변경 없이 사용자에게 사용하지않는IDE/Chrome탭을 직접정상종료해0.5~0.8GiB여유를 확보하도록 요청했다. 당시Chrome working set상위약761/460/408/403MiB지만 각앱정상종료후회수량을 보장하지 않는다.

정확한 다음 실행은 메모리확보통보→가용RAM≥1920MiB/디스크≥2GiB·소스/새prepared `20261023T085722Z-ee075b27`/`e1d98f3c…`·이미지/소유권 재확인→`python deployment/c-service-live-check.py run 20261023T085722Z-ee075b27` 신규1회다. 검토완료 뒤 별도실행승인을 다시 받을 필요는 없다. 실패시같은환경/불확실거래자동재전송/무한새fixture반복은금지한다. 기존RootHEAD f9353c3/dirty10 보존. RAM대기동안 C거래전송·복구·웹연결의 독립설계만 코드/정책과 대조하며 실제C서비스Gate/MetaMask/전체감사 완료로 표시하지 않는다.

#### 재개 체크포인트 — C 서비스 Gate 최소 보완13 PASS / 추가 검토1회 승인 대기

1. **실제 통과 유지:** C5a1 `canvil-468f07cdb57c46aa`, C4b PG10 `cpg-3dfbda7f33414a95`, C5a2 실제60초/Claims `canvil-9c60eadeb80647f8` PASS와 원본·정상정지/state를 보존했다. 성공 환경은 재기동하지 않았다.
2. **C서비스 후보:** core `34732172…` 53PASS·첫구현검토 필수없음은 그대로다. 새 selected Spring/JWT/JPA·키분리Driver/PG/Anvil Gate를 구현했으나 실제 서비스 Gate는 아직 NOT_RUN이다.
3. **마지막 검토 필수1건:** 직접컴파일에 `-parameters`가 없어 bare `@PathVariable String id` 두 HTTP경로의 이름해석을 막는 경계가 발견됐다. old prepared `d2ee65f4…` Controller.class SHA `6b1769623c2480cad27f5bfdfa2aa3c1d3e714f489bb1e91a0e45d66f04a439e`를 javap로 읽어 MethodParameters없음/PathVariable있음을 확인했다. 실제HTTP실패를 실행한 것은 아니다. 재검토2/2는 이1건으로 필수있음 종료, 다른 필수지적은 없었으나 cap소진이다.
4. **보완 검증 완료:** core/API/계약/금융·Driver는 유지하고 새하네스 javac `-parameters`와 Appself-test compiled id이름/isNamePresent 검사, Python compile-builder회귀만 추가했다. 새 `c-service-live-prepare/20261023T085722Z-ee075b27`/`e1d98f3cc88bbcf6d5de1c093b6a6ffa0771c9296f06360dede77e0ff1aec4f1` server/driver compile0·self-test0·관련stderr0·Python13PASS/source불변이다. old prepared·누락증거는 그대로다.
5. **승인 필요/정확 재개:** 이 보완에 한한 추가독립검토1회 예외를 비동기 요청했고 아직 승인 미확인이다. C4b의 사용완료 예외를 가져오거나 이름변경으로 상한을 초기화하지 않는다. 추가검토와 새 실제서비스Gate1회는 아직 실행하지 않았고 승인 전 금지다. 승인을 받으면 현재소스/새준비hash확인→추가검토1회→필수없음 후 RAM최소1920MiB/디스크2GiB·이미지/새owner확인→새Gate1회 순서다. 정책 변경·새유료 승인은 아니다.
6. **한계/보존:** 실제CwalletPG/pinnedRPC/HTTP retry0/선택SpringJWT·JPA는 위Gate에서 확인해야 한다. C Sender·재가격/복구·제품SyntheticWorker·C웹/MetaMask·fullScanner/Audit·C7은 미완료다. root/UI/AI/B·사용자OPEN·DB/Anvil·ledger/STOP/claim 보존, commit/merge/push·유료·전역재시작·삭제0. 상세 C worktree `dapp-c-service-read-wallet-2026-10-23.md`.

#### 현재 — C 실제 서비스 Gate 준비12 PASS / 마지막 독립 검토 중

C core53PASS/첫 구현독립검토 완료 상태를 유지하고, 새 selected Spring/JWT/JPA/C API와 별도 시험EOA Driver·신규 PG/Anvil을 연결할 최소Gate4파일을 구현했다. 실제 준비 `c-service-live-prepare/20261023T084817Z-3bde2cd5`/`d2ee65f444b39588409ec83f1d3d3af60e7be089e7cb76e2586331f5e8667731`은 server/driver 분리compile0·무socket self-test0·Python12PASS/source불변이다. 캐시Lombok으로rootUser원본을새out에컴파일했으며Appclasspath에Driver/C5/JUnit/EOA키생성코드는없다. 실제 서비스/PG/RPC/HTTP 실행은 아직0이다.

productioncore·기존C5원본은변경하지 않았다. 같은Cread/wallet단위 마지막재검토2/2 진행 중이며 새이름으로한도를초기화하지 않는다. 완료시 실제RAM최소1920MiB(새4컨테이너한도1408+host여유512)/disk2GiB·source/artifact/image/owner를재확인하고 새Gate1회다. PG는검증된새ownedrootRW/NET_RAWdrop구성을재사용하고App/Driver는readOnly·capdropALL·nonroot·마운트/키분리; 비밀Env는inspect로그에복제하지않는다. actual선택Ccontext성공도전체TMS/로그인UI/MetaMask·Sender·제품Synthetic·fullAudit완료로승격하지않는다.

#### 최신 — C read/wallet 후보53 PASS·독립 검토 완료 / 실제 서비스 Gate 준비

C opt-in 기본OFF·고정배포/EIP1898 single-provider pinned read·JWT본인 지갑EIP191·별도원자SQL/API 및 전용 keyless retry0/3초 client를 구현했다. 최초 `c-service-read/20261023T075601Z-8f60115f`/`5fb53cf6…` 실제50PASS2FAIL을 보존했다. H2의 timestamp 반올림으로 원래발급시각보다211ns미래가 되어 정상증명을 `C_WALLET_EXPIRED_OR_USED`로 거절했고, JSON에서 plain record method의 검증범위4개가 빠진 것을 확인했다. 과거실서비스장애가 아니라 새후보의 무과금재현이다.

발급전 millisecond 정규화/비ms store입력거부·원문보존, JSON `@JsonProperty` 최소수정 후 `20261023T075906Z-9a0a410e`/`34732172658d779342ccf5e8b8cfd78d135053d5238be6c65905a235f9dbb96e`는 **53PASS/FAIL·SKIP·ABORT0/compile·test0/stderr0/source·dependency불변**이다. `review_c_read_wallet_design` 구현후재검토1/2가 소스24개/class67개/제3자jar147개 실제SHA와원본을 대조해 필수수정없음. 최초설계1+재검토1 사용/재검토1 남음.

**한계:** no-socket Mock/H2·직접controller/선택Spring단위 검증이지 실제CwalletPG·HTTP retry0·pinnedRPC·SpringHTTP/JWTfilter·브라우저/MetaMask는 아니다. 다음 새소유Linux PG/Anvil/서버/Driver 최소Gate를 준비한다. user시험키는Driver에만, 서버는읽기전용/keyless로분리하고 실제RootJWT/사용자행·원자SQL·RPC/header/소유권·정상stop를검증한다. 기존C5a1/PG10/C5a2PASS는 그대로보존하며 반복실행안함. core/계약/금융정책·다른worktree 불변, commit/merge/push·유료·전역재시작·삭제0.

#### 현재 진행 — C 서비스 read/wallet 구현 중

C5a1·C4b·C5a2 실제 PASS를 보존한 뒤 첫 C서비스 연결을 시작했다. C worktree `dapp-c-service-read-wallet-2026-10-23.md`의 defaultOFF·고정배포·single-provider pinned read·JWT본인 EIP191/별도원자SQL 범위다. 초기독립검토 `review_c_read_wallet_design`은 capacity오류 전 잠정메시지를 완료로 쓰지 않고 같은검토의 최종응답을 받아 **필수 수정 없음**으로 완료했다. 최초1회사용/구현후재검토2회남음.

현재 C전용 runtime/auth/API/SQL·Mock/H2 테스트 작성 중이며 **컴파일·테스트·실제 PG/Anvil/Spring/웹은 아직 NOT_RUN**이다. 필요한 새 offline launcher/runner만 명시source·cached제3자jar·기존appclasses제외·source snapshot컴파일·고유증거·무skip으로 준비했다. 기존 C4b/C5a 하네스/계약 및 다른worktree는 변경하지 않는다. 생산용 keyless OkHttp는 retryfalse만으로503 follow-up까지 막힌다고 가정하지 않고, 비200응답의 안전종료·전송계수·async body/deadline/cancel을 단위검증한다. 무검증 상태로 실제자산/사용자인수를 진행하지 않는다.

#### 최신 체크포인트 — C5a1·C4b·C5a2 실제 Gate PASS / C 서비스·웹 연결 착수

1. **실제 자산 흐름:** 새 C5a1 `canvil-468f07cdb57c46aa`/`b1645a5e…` 토큰배분·approve/deposit/freewithdraw·OPEN/TOP_UP/CLOSE·승인거부 및 canonical Receipt/잔고 PASS. 별도 C4b `cpg-3dfbda7f33414a95`/`955e3cd8…` 실제PG10PASS. 추가승인 검토1회·PG1회는 사용완료다. 기존 실패는 보존했다.
2. **새 C5a2 실제 PASS:** `canvil-9c60eadeb80647f8`/`6ffb45c5…` 실제Long/Short START/CLEAR/재START·nonce취소독립·조기거부·유예후 새ReportLIQ PASS. Long wall62,418ms/mono62,334ms, Short wall62,208ms/mono62,125ms; LIQ timestamp는 deadline보다3/2초뒤. 양쪽 담보1000만house/badDebt1000비현금 확인. 작은house FIFO claim900/1000·부분/1wei/전액상환·미지급/관리자OPENpause경계·직접free인출 PASS. 고정소스/오프라인28PASS 후 독립재검토1/2 필수없음; 실제 실행 소스불변/소유컨테이너 정상정지·state보존이다.
3. **구현·검증 경계:** 모두 시험EOA·통제Oracle/가격이다. C 전용 Spring/JWT/제품Synthetic Worker/실제MetaMask/체인→PG전체scanner·reorg/최종감사·실행중서버종료·Oracle중단locked는 아직 NOT_RUN/미구현이다. 직접인출이 서버없음 환경에서 성공한 것과 실제서비스종료 인수는 구분한다. 기존 금융정책/서명/신선도/시간/재시도 조건을 완화하지 않았다.
4. **다음 독립 단위:** C 전용 기본OFF runtime identity(token/controller/rules/Vault/codeHash/genesis/domain)·JWT↔EOA일회challenge·동일기준블록 live자산/nonce/position 조회를 먼저 설계·구현·검토. 그다음 C작업metadata와 raw/Receipt/최대1revision·복구, 웹 approve→deposit→OPEN→TOP_UP→CLOSE→freewithdraw를 연결한다. 기존 B코드는 필요한hunk만 hash·의존성 대조 후 재사용하고 DB돈예약/ABI/domain을 그대로 가져오지 않는다. typedPGstore/sameblockOverlay를 전체canonical증명으로 승격하지 않는다.
5. **보존:** 모든 새Gate는 순차실행, 소유컨테이너 정상정지 후 network/volume/state/evidence보존. 기존 사용자 OPEN·환경·원본/UI/AI/Bworktree·ledger/STOP/claim 불변, commit/merge/push·유료·전역재시작·삭제0. 상세 C worktree `dapp-c5a1-anvil-harness-2026-10-23.md`, `dapp-c4b-storage-2026-10-23.md`, `dapp-c5a2-risk-claims-2026-10-23.md`.

#### 최신 — C5a2 구현·오프라인 PASS / 실환경 전 독립 검토

서버 중단 후 재확인했으며 새 실제 Gate나 실행 컨테이너는 없었다. 일부 저장된 C5a2 dispatch를 보존해 신규 scenario만 완성했다. 최초 설계 독립검토 필수없음, 최종 동결 `c-anvil-offline/20261023T065808Z-9108ce4b`/`6ffb45c5e07187ed647f8281e048893c7dba522363ff55fdfeaf126deccbf1cb` fresh build/metadata·Java compile·기존/신규 selftest·Python28PASS, exit0/source불변이다. 구현 후 독립 재검토1/2 진행 중이다. 실제60초·Claims는 아직 NOT_RUN이며 검토 통과 전 실행하지 않는다. 기존 C5a1 실제PASS/PG10PASS 원본은 재실행하거나 덮어쓰지 않았다.

다음은 필수 지적 해결/재검증 및 검토 완료→RAM/source/artifact/image 재확인→새 C5a2 실제1회다. 통과 후 C전용 opt-in identity/사용자 지갑 연결·고정블록 live read, 작업 metadata/raw·Receipt, 웹을 작은 의존단위로 연결한다. B의 DB담보 예약/ABI/domain을 그대로 C에 복사하지 않으며 typedPG 저장 및 same-block overlay를 전체 canonical scanner로 표시하지 않는다. 기존 사용자 데이터·환경·증거/삭제금지·commit/merge/push·유료금지 유지.

#### 최신 체크포인트 — C5a1 실제 Anvil·C4b 실제 PG 통과 / C5a2 준비

2026-10-23 사용자 메모리 확보 통보 후 RAM3,158,458,368bytes/Cfree28,287,913,984bytes·실행컨테이너0을 확인했다. 기존 기준과 최종 동결 소스/산출물 검사를 유지해 **새 C5a1 `canvil-468f07cdb57c46aa` PASS**: 실제 고정공급/분배·approve/deposit/free withdraw·사용자 OPEN/TOP_UP/CLOSE·11개 승인 거부 시험, canonical tx/Receipt/Event·실제 잔고/lock/house 검증을 완료했다. source `b1645a5e…` 불변, 소유 Java/Anvil 정상정지 및 state 보존 확인. 시험 EOA이며 MetaMask·제품 Synthetic/서버5초·실제60초/Claims·전체감사 완료가 아니다.

Anvil 정지 후 RAM2,020,921,344bytes·실행컨테이너0을 확인하고 순차 **새 C4b PG `cpg-3dfbda7f33414a95` PASS**: 실제 PG10개 PASS/FAIL·SKIP·ABORT0, source `955e3cd8…`, owner/SCRAM/신규 schema·동시성·멱등·충돌·원자 rollback/commit불확실·append-only/FK/손상 검증. Sentinel 보존, Java/PG 정상정지. 추가 승인된 PG1회는 여기서 사용했다. 이는 typed projection 저장 인수이며 체인→DB scanner/실제 서비스·웹 연결 성공은 아니다.

다음은 기존 하네스를 최대 재사용한 **C5a2 실제 Long/Short 60초·작은 house FIFO Claims·인출 경계** 최소 설계/구현·독립검토다. 아직 해당 실제 Gate는 NOT_RUN. 과거 실패/리소스/DB/chain·사용자 OPEN/평가 증거 보존, 삭제·전역재시작·commit/merge/push·유료0. 새 테스트 소유 자원은 모두 정지 후 보존했다. 단계 보고만 하고 종료하지 않고 다음 안전한 작업을 계속한다.

#### 최신 재개 체크포인트 — C4b 예외 검토 완료 / 신규 Gate는 RAM 확보 전 미실행

1. **승인 변경:** 사용자가 C4b 추가 독립 검토1회와 새 격리 PG Gate1회만 승인했다. 아래 과거의 ‘예외 승인 대기’는 당시 이력이다. 다른 단위의 검토 한도나 실패 판정은 바꾸지 않는다.
2. **검토 완료:** `review_c4b_exception`이 ENTRYPOINT/고정 컴파일 whitelist/신규 DB identity·SCRAM·schema/원자 저장 및 기존 실패 원본을 읽기 전용 검토했다. 결론은 **발견된 필수 수정 없음**. 예외 검토1회는 소진했고 새 PG Gate1회는 아직 사용하지 않았다. 신규 테스트 실행으로 표시하지 않는다.
3. **현재 identity 재검증:** C4b `955e3cd8…`의 현재 소스·class 해시 일치, C5a1 `b1645a5e…`의 현재 소스·계약·전체 fixture 산출물 해시 일치. Foundry/JRE/PG 고정 이미지3개의 실제 ID·Linux/amd64 확인. 기존 오프라인47/26PASS는 해당 기록으로 유지하며 재실행하지 않았다.
4. **자원 BLOCKED:** 현재 호스트 RAM658,649,088bytes(약628MiB), C 여유27,088,199,680bytes. C5a1 최소1,342,177,280bytes(1,280MiB)에 약652MiB 부족하다. 실행 컨테이너0. 실제 새 Anvil/PG Gate·새 네트워크/볼륨/컨테이너·자산 이동은 이번 재개에서 **0/NOT_RUN**이다. RAM 기준 인하·기존 앱 종료·서비스 재시작은 하지 않았다. PG도 자원 여유 전 시작하지 않는다.
5. **필요한 사용자 조치 및 순서:** 사용하지 않는 IntelliJ IDEA(당시 working set 약2,426MiB)나 Chrome 탭을 작업 저장 후 직접 정상 종료해 RAM 여유를 확보해야 한다. working set 전체가 곧 회수량이라는 보장은 없다. Docker/WSL/Codex는 종료하지 않는다. 준비 통보 후 실제 RAM/source/artifact/image를 다시 확인해 **새 C5a1 1회 → 자원 해제 확인 → 새 C4b PG 1회 → 통과 의존성에 맞춰 C5a2 실제60초/Claims → C 서비스·웹** 순차 진행한다. 기존 실패 fixture는 재사용하지 않는다. 실제 사용자 목표는 approve→deposit→OPEN→TOP_UP→CLOSE→free withdraw이며 시험 EOA와 MetaMask는 구분한다.
6. **보존:** root `melon-init@f9353c3` dirty10 유지, 보호156개 SHA 변경·누락0. 기존 OPEN·DB/Anvil·UI/AI worktree·ledger/STOP/claim 불변. 제품/하네스 수정·새 무거운 회귀·commit/merge/push·유료 호출0. 현재 가능한 검토/identity 확인은 끝났고 실제 자산 흐름은 자원 확보 대기다.

#### 재개 체크포인트 — C1~C3 구현 검증·C5a1 준비 완료 / 실제 Gate는 자원 BLOCKED

1. **완료:** 승인 정책 ADR, C 고정공급 token/Vault·사용자 승인/직접호출·원자적 잠금/정산·FIFO Claims 및 고갈/보존식 계약 검증. 기존 C Foundry141PASS의 소스28개는 현재 SHA 불일치0이다. 실제 배포 성공을 뜻하지 않는다. 후속 순수 거래 증거 연결/T2 43PASS 및 독립 검토도 완료했다.
2. **최신 구현·시험:** 신규 C5a1 내부 네트워크/Anvil-only 하네스3파일, 최종 offline `20261023T055323Z-d714d8bd`/identity `b1645a5e15efa09e420d33ceca6aa6842eaf3b25be8048d4b5f19c7f40095a84`는 새 build/artifact·Java compile/self-test·Python26PASS/exit0/소스불변. 첫 재검토4건을 보완했고 마지막 재검토2/2는 필수 지적 없음, 해당 주기는 소진했다.
3. **실제 실행 BLOCKED:** 새 Gate1회 `c-anvil/canvil-b33d2f73684240df`는 사전 RAM1,285,423,104bytes(약1.197GiB)<1.25GiB에서 중단. Cfree27,112,255,488bytes. network/volume/container/Anvil/JVM/RPC/배포/거래 생성0이다. 소유권 cleanup inspect not-found는 원인이 아니며 fixture/resources/result를 보존했다. 임계 인하·반복 새 fixture 실행·기존 앱 종료 없음.
4. **별도 미완료:** C4b PG10개는 NOT_RUN/BLOCKED이며 ENTRYPOINT 수정의 추가 독립 검토1회+새 PG Gate1회는 기존 상한 예외 승인 대기다. 이 예외를 C5a 이름으로 우회하지 않는다. 실제 C5a1 거래/거부·Linux/mount/state, C5a2 실제60초/Claims, canonical multi-block/scanner·PG 저장/복구/전체 감사·C 서비스/웹/MetaMask는 아직 완료하지 않았다.
5. **다음 재개:** 가용 RAM을 충분한 여유로 확보한 뒤 최종 동결 source/compiled/이미지 identity를 확인하고 새 Anvil-only Gate1회. 기존 실패 fixture 재사용이나 자동 거래 재전송은 하지 않는다. C4b 예외는 승인 전 보류한다. 실제 C5a1 인수가 막힌 상태에서 의존하는 새 금융/서비스 구현을 검증 완료로 앞당기지 않는다.
6. **보존:** 끝의 원본 `melon-init@f9353c3` dirty10 유지, 보호156개 SHA 변경/누락0. 기존 성공 OPEN·DB/Anvil/UI/AI/ledger/STOP/claim·다른 환경 변경·삭제·commit/merge/push·유료 호출0. 상세는 C worktree `dapp-c5a1-anvil-harness-2026-10-23.md`, 정책은 `dapp-c-policy-adr-2026-10-23.md`다.

#### 최신 — C5a1 첫 독립 재검토 필수4건 보완 중

오프라인 PASS 후 첫 독립 재검토(1/2)가 HTTP 내부 connect 재시도0 강제, 검증 전 실패 Receipt 원문 보존, 신규 mount 실제 쓰기·정상종료 state flush, 계획의 owner/domain/nonce/범위·직접 rules 거부 시험 보완을 요구했다. 신규3파일만 수정하며 경제/계약·기존권한·보존자료는 변경하지 않는다. 수정 후 진단·최종오프라인검증·마지막재검토1회까지 완료하기 전 실제 Gate를 실행하지 않는다. 실제 재전송/쓰기실패가 발생했다는 판정이 아니라 코드·검증 경계의 필수 보완이다. Docker/RPC0, C4b PG 예외 대기와 RAM 기준 미달은 유지한다.

#### 최신 — C5a1 최종 오프라인 Gate PASS / 구현 후 독립 재검토 중

`c-anvil-offline/20261023T053905Z-16539109`, sourceIdentity `484fe5933469194b07d16c24c05386d3d83b6707a3bf753e87450336c34ea3e1`: 새 offline forge build·artifact 검증·Java compile/self-test·Python19PASS, exit 모두0·소스불변이다. 앞선 `c-anvil-diagnostic/20261023T053844Z-b50751eb`의 compile/test0는 별도 무전송 진단이며 live 전제조건으로 재사용하지 않는다. 신규3파일을 동결해 C5a1 구현 후 첫 독립 재검토(1/2)에 넘겼다.

Foundry의 parsed metadata 표현과 CRLF compiler representation을 원본 바이트와 구분했고, raw SHA 보존 및 compiler Keccak를 모두 검사한다. 후진단에서 확인한 CRLF 경계를 원래 공통오류만 있던 실패 원인으로 소급 확정하지 않는다. 상세 C worktree `dapp-c5a1-anvil-harness-2026-10-23.md`. 실제 Docker/RPC·C 배포·자산 이동·사용자 거래/60초·MetaMask는0/NOT_RUN, RAM은 실제 시작 전 다시 확인해야 하며 기존 기준 미달을 무시하지 않는다. C4b PG10개 NOT_RUN/BLOCKED·검토 예외 대기, 기존 환경 및 사용자 OPEN·평가자료 보존을 유지한다.

#### 최신 진행 — C5a1 신규 하네스 오프라인 검증 중 / 실환경 미실행

신규 C worktree에 `deployment/c-anvil-check.py`, `CAnvilContractGate.java`, `test_c_anvil_check.py`를 작성했다. 최초 독립 설계 검토는 필수 지적 없음이고 구현 후 검토는 아직 미실행이다. 기존 계약/금융/PG 하네스는 변경하지 않는다. 새 run 전용 offline forge build(out/cache 별도), 배포 artifact/source 대응, 단일 전송·불확실 STOP, 내부 네트워크/호스트 포트 미게시 및 소유 자원 보존 경계를 검증 중이다.

첫 sandbox 두 실행은 Foundry 홈 조회 panic으로 BLOCKED였다. 정상 권한의 `c-anvil-offline/20261023T051039Z-a91a37f4`는 새 build 성공 후 metadata 형식 대조에서 중단, 후속 `20261023T053010Z-3239f38a`는 ABI 대조에서 중단됐다. 원실패/새 산출물은 보존했으며 두 경우 Java 시나리오는 미실행이다. 실제 Forge parsed metadata의 빈 context remapping·문서/ABI 표현 차이를 읽기 전용으로 분석해 파서 회귀부터 보완한다. Docker/RPC/실제 C 배포·거래/유료 호출0이다. RAM 기준 미달과 C4b PG 예외 대기는 유지한다. 실제 통과 전 C5a1 준비 완료로 표시하지 않는다.

#### 최신 — T2 순수43PASS·마지막 독립 재검토 완료 / C5a 계약 실환경 계획

T2 교차 거래 block-global logIndex 결함을 테스트 전용으로 먼저 재현해37PASS6FAIL을 보존했고(잘못 수락5건·기존 하위 거부 reason 차이1건), 신규 Overlay만 최소 수정했다. 실제 후속 `c-proof/20261023T045618Z-70cb5a96`, source `42612c6e6fa7fe375b065c6358c1b478e882268c6e431715f785eee17918f5d2`는43PASS/실패·SKIP·ABORT0/compile·test0/stderr없음/소스불변이다. 마지막 독립 재검토2/2는 필수 지적 없음이며 해당 검토 주기는 소진됐다. opaque checkpoint와 전체 원본 재검증을 유지한다. 순수 same-block 내부 연결 완료일 뿐 canonical/PG/multi-block/전체 coverage 성공이 아니다.

다음 독립 C5a는 신규 내부 네트워크 Anvil 계약 단독 인수 계획을 검토한다. C4b PG10개 NOT_RUN/BLOCKED 및 검토 예외 대기는 유지하고 다른 이름으로 재시도하지 않는다. 2026-10-23 13:57KST 읽기 전용 확인: Docker Server28.4.0 응답, 실행 컨테이너0, C 여유27,599,446,016bytes(약25.70GiB). 메모리는 새로 확인해야 하며, 과거 값으로 자원 충분 판정을 하지 않는다. 기존 환경·사용자 OPEN·평가 기록 변경/유료/commit/merge/push0이다.

#### 최신 — T2 첫 재검토 필수1건 보완 중 / 독립 C5a 계약-only 계획

T2 엄격 UTF-8 수정 후36PASS (`c-proof/20261023T044037Z-6843f7d5`, source `d89afa66…`), 공유5단위161PASS/Python5PASS를 실제 실행했다. 그러나 구현 후 첫 독립 재검토가 **거래 간 block-global logIndex 연결 누락**을 지적해 현재 미완료다. token/금융 서로의 full logs를 하위 reducer가 모두 보지 않으므로, Root의 동일블록 관측 최대값부터 다음 full logs 첫 index가 증가하는 private guard와 회귀를 보완한다. T2 설계최초1회+첫재검토1회 사용, 마지막재검토1회만 남는다. 실제 수정/재검증 전 완료 판정하지 않는다. 상세 새 C worktree `dapp-c-global-proof-overlay-2026-10-23.md`.

C4b PG10개 NOT_RUN·검토 예외 대기는 유지한다. 그 절차를 다른 이름의 PG 하네스로 우회하지 않는다. 독립적으로 가능한 C5a 신규 Anvil 계약 단독 인수(기본 실제 자산 배분/예치/사용자 거래/위험·Claims)의 작은 계획도 검토 중이다. 현재 C5a는 계획이며 코드/컨테이너 실행0, 실제 Spring/PG/MetaMask/제품시장/Worker 인수로 표시하지 않는다. 기존 환경·사용자 OPEN·평가 ledger/STOP/claim 불변, 유료 호출/commit/merge/push0이다.

#### 최신 — T1 token-only31PASS·독립 검토 완료 / T2 메모리 overlay 구현

T1 원본 token transfer/approve/transferFrom 영향0 검증31PASS (`c-proof/20261023T041722Z-1011c24c`, source `44114b9da0c60ba318648d5e6eff065010152190d21680601fe2b5fb60626ae0`), 공유 runner 확장 뒤 user37/risk32/Vault32/control29/Python5PASS, 최초 독립 검토 필수 지적 없음이다. ZERO_DELTA는 선언된 Vault 한 주소만 뜻하며 실제 배포/전체 chain 보증이 아니다. 양수 Vault 전송·T2연결은 이 단위로 완료하지 않는다. 상세 새 C worktree `dapp-c-token-transaction-proof-2026-10-23.md`.

다음 T2는 메모리 전용 global overlay다. 최초 독립 설계 검토 필수 지적 없음, 현재 구현 중/실행 NOT_RUN. root+전체 raw gap/금융 원본을 내부 재검증하고 private 임시 cut을 통해 기존 금융 verifier를 호출한다. 외부에는 불투명 checkpoint+요약만 반환하고 임시 State/내부 Verified를 v1 저장소에 넘기지 않는다. mutable log/topics는 cap 확인 후 deep snapshot한다. source/deployment/point/순서 및 pending64/전체128/원문8MiB 등 상한을 고정한다. 기존 codec/store/wrapper를 변경하지 않으며 T2 검토는 최초설계1회+후속재검토 최대2회다. 설계 `dapp-c-token-global-cursor-design-2026-10-23.md`.

C4b 실제 PG10개는 여전히 NOT_RUN/BLOCKED·예외 승인 대기다. 기존 보존 환경·사용자 OPEN·평가 STOP/ledger/claim은 변경하지 않았고 commit/merge/push/유료 호출도 없다. 실제 C Anvil 자산 이동·벽시계60초·MetaMask·canonical/전체 감사는 아직 NOT_RUN이다.

T2 첫 구현의 구체 범위는 같은 block/hash/parent/timestamp·연속txIndex(+1), pending64/전체128/root+원문8MiB다. 블록 전환/빈블록/index gap은 미지원이며 실제 C 전체 Gate의 기준은 유지한다. Root/source/header는 prevalidated assertion의 결합이고 실행artifact/canonical 진실을 새로 증명하지 않는다. 금융 witness는 State/Verified를 받지 않으며, 불투명 checkpoint도 append 때 전체 transcript를 재검증한다. T2 구현 완료·실행·후속 재검토는 아직 NOT_RUN이다.

T2 사전 분석의 추가 검증: 이미 확인한 full rules after(수량/진입가 등11필드)를 다음 position prestate와 연결하고, transcript에서 알려진 allowance도 approve/set·finite소비/MAX유지로 이어간다. 마지막 Approval을 현재 allowance로 추정하지 않는다. Root의 첫 미확인 상태는 여전히 prevalidated assertion이지만, 알려진 앞 거래 상태를 뒤 입력이 조용히 바꿀 수 없도록 한다. 이 발견은 과거 실제 운영 장애로 소급 기록하지 않는다.

보존 재확인: T2 구현 중 실제 SHA-256 읽기 전용 대조156개 중 변경/누락0, 원본 `melon-init@f9353c3` dirty10 그대로다. 이번 후속은 새 C 파일과 C 문서·이 진행 문서만 수정하며 기존 환경/거래/평가 실행은 하지 않았다.

#### 최신 — C4c-3d control29PASS·독립 검토 완료 / token-only coverage 연결 설계

controller cancelNonce 및 OPEN pause 원본 거래 연결29PASS (`c-proof/20261023T040103Z-73ebf312`, source `7e7f013c9cebf23cc05ac6ac26cc53031ea39d2f3a6a33366fdd48d0caafee31`), 공유 runner 확장 후 user37/risk32/Vault32/Python5PASS, 최초 독립 검토 필수 지적 없음이다. 상세 새 C worktree `dapp-c4c3d-controller-control-proof-2026-10-23.md`. 첫 same-pause28/1FAIL은 동일 raw/hash를 새 txIndex에 사용한 테스트 fixture 오류였고 Ethereum nonce만 분리했으며 제품의 증거 충돌 방어는 유지했다. 취소·pause는 자산·Claims·포지션을 임의로 해제하지 않는다.

다음은 독립 token-only Transfer/Approval/transferFrom과 전역 cursor의 정직한 연결 설계다. 가짜 ProcessClaim/Donation으로 Vault cursor를 진행시키지 않고, C4b v1 codec/store를 조용히 변경하지 않는다. 실제 C4b PG10개 NOT_RUN·추가 검토 예외 승인 대기는 그대로다. 실제 canonical/RPC·C 자산 이동·벽시계60초·MetaMask/전체 감사는 아직 NOT_RUN이다. 기존 C 계약141PASS는 소스가 그대로인 앞선 실행 증거이며 이번 Java 실행으로 다시 실행한 것이 아니다.

#### 최신 — C4c-3c Vault32PASS·독립 검토 완료 / 취소·OPEN pause 연결 진행

Vault deposit/withdraw/fundHouse/processNextClaim 원본→이벤트→token/Vault 장부 연결32PASS (`c-proof/20261023T035255Z-5b8f2080`, source `88070388…`), 공통 runner 변경 후 사용자37PASS/keeper32PASS·Python5PASS, 최초 독립 검토 필수 지적 없음이다. 최초 테스트 괄호 compile 실패는 문법만 고쳐 snapshot을 보존했다. 현재 allowance는 Approval 이력에서 추정하지 않고 정확 prestate assertion만 검사하며, currentAllowanceVerified=false/completeCoverage=false다. 실제 C4b PG10개 NOT_RUN·예외 승인 대기는 유지한다. 상세 새 C worktree `dapp-c4c3c-vault-proof-2026-10-23.md`.

다음 controller cancelNonce/pauseNewPositions는 별도 verifier로 개발 중이다. 취소는 본인 nonce만 증가하고 locked/free·미확정 거래를 임의 해제하지 않으며, 관리자 pause는 신규 OPEN flag만 바꾼다. 기존 user/risk/cash/계약·실환경 자료를 변경하지 않는다. 이 후속의 테스트·독립 검토는 아직 NOT_RUN이다.

#### 최신 — C4c-3b keeper32PASS·사용자37PASS 회귀·독립 검토 완료 / Vault 현금·Claims 연결 진행

자동 START/CLEAR/LIQ의 실제 raw sender·Oracle 서명·full rules/event·Vault/Claims·token 연결을 신규32PASS (`c-proof/20261023T034457Z-e92934f1`, source `0fae56e4…`)로 검증했다. 공통 wrapper 변경 후 사용자37PASS (`034517Z-8cb86a45`, `9dae78df…`)·Python5PASS, 최초 독립 검토 필수 지적 없음이다. user nonce 취소/ownerCode와 위험 실행의 독립성, badDebt 비현금, 과거 FIFO 보존 및59/60초 조건을 확인했다. 상세 새 C worktree `dapp-c4c3b-risk-proof-2026-10-23.md`. 실제 RPC/PG·canonical·벽시계60초·전체 감사 PASS는 아니다.

다음은 기존 wrapper를 동결한 별도 Vault deposit/withdraw/fundHouse/processNextClaim 연결 단위다. deposit/fund의 현재 allowance는 과거 Approval 이벤트로 추정하지 않고 정확한 사전 point의 keyed assertion으로 제한하며, 향후 실제 RPC 진실 검증은 별도다. token-only/cancel/pause 및 actual canonical scanner는 아직 미지원이다. C4b BLOCKED·예외 승인 대기/실제 PG10개 NOT_RUN은 유지하면서 독립 개발을 계속한다.

#### 최신 — C4c-3a 사용자 full 연결37PASS·독립 검토 완료 / keeper 위험 거래 연결 진행

1. 사용자 Direct/Relayed OPEN·TOP_UP·CLOSE의 원본 raw→Receipt→사용자/Oracle 서명→전체 rules 산식·이벤트→Vault/Claims·token 장부를 연결했다. 사용자37PASS (`c-proof/20261023T033140Z-8dcb564c`, source `3ef2666bbf0d369bda3a41cee8f85502567f483a08f06ede3656792f5efa1658`), 관련6범위159PASS·Python5PASS, 최초 독립 검토 필수 지적 없음이다. 상세 새 C worktree `claude-docs/dapp-c4c3-transaction-proof-2026-10-23.md`.
2. 이196개는 각각 실행한 순수 JUnit 합계이지 정상 Gradle 전체·실제PG/Anvil·전체 감사 MATCH가 아니다. context/state의 실제 canonical 진실, fresh RPC/서버5초/provider 및 DB 저장은 아직 증명하지 않는다. completeCoverage=false다.
3. 다음 keeper START_CALL/CLEAR_CALL/LIQUIDATE는 고정 relayer/Oracle·60초/새 관측·full risk event·실제 settlement/FIFO를 연결한다. 사용자 nonce 취소와 owner code 검사에 의존하지 않는 실제 계약 규칙을 유지하고, 기존 사용자37개도 회귀한다. 현재 구현 중/실행·검토 NOT_RUN이다.
4. C4b 실제 PG10개 NOT_RUN/BLOCKED와 추가 검토·새 Gate 예외 승인 대기는 그대로이며, 독립 C 개발을 중지하는 사유로 사용하지 않는다. 기존 원본 HEAD `f9353c3`/dirty10과 worktree들을 읽기 전용 확인했다. 기존 환경·ledger/STOP/claim·MetaMask OPEN 변경, commit/merge/push 및 유료 호출 없음.

#### 최신 요약 — C4c 승인 연결 검증 완료 / Receipt 연결 구현 중

**후속 완료:** Receipt envelope **25PASS** (`c-proof/20261023T031324Z-ef952cf1`, `fdb26cbf…`), 최초검토+재검토2회 후 필수 지적 없음. 정상 reverted value 거래/미지원 payable 주소의 분류 오류를 수정했고 중간24PASS1FAIL 재현을 보존했다. ERC-20 고정공급·지갑 잔고 projection **25PASS** (`20261023T030353Z-88d1708a`, `797bd4cf…`), 최초 독립 검토 필수 지적 없음. 후자는 supply합계만 맞춘 위조 wallet seed를 전체 Transfer 재생으로 차단하고 bounded header ancestry를 검증한다. Approval은 마지막 관측치이지 현재 allowance가 아니며 Vault 직접 송금을 free/house로 승격하지 않는다. 두 단위 모두 실제 DB/RPC/전체 감사 MATCH가 아니고 C4b BLOCKED는 그대로다. 상세 새 C worktree `dapp-c4c2c2b-envelope-linkage-2026-10-23.md`, `dapp-c-token-ledger-2026-10-23.md`.

1. **문제:** 부분 ABI·서명·회계 일치만으로 C 전체 거래를 승인된 것으로 오인하지 않도록 검증 책임을 연결한다.
2. **정책:** 승인된 C 경제/권한·120초 Intent/30초 Report/60초 유예를 유지한다. caller가 준 상태와 실제 canonical RPC 증거를 구분한다.
3. **구현:** C 사용자·Oracle 승인 검증기33개와 rules 전체 전이34개를 완료했다. 실제 서명을 내부에서 검증하고 direct/relay, owner/code 관측, nonce, accountRef, 전체 Intent/Report 연결·유한 가격/수량 범위를 확인한다. 다음 순수 Receipt envelope 단위가 원본 raw·Receipt·전체 로그의 identity/순서를 검사하도록 구현 중이다.
4. **실행·검토:** authorization33PASS (`c-proof/20261023T024320Z-1bbf1900`, `e4ecdb1b…`), 공유 runner rules34/EIP22/raw20PASS·Python5PASS, 최초 독립 검토 필수 지적 없음. 전체 계약141PASS는 앞선 C3 소스 검증으로 별도 보존한다. 상세 새 C worktree `claude-docs/dapp-c4c2c2a-authorization-2026-10-23.md`.
5. **한계·대기:** 실제 C PG10개는 NOT_RUN/C4b BLOCKED, 하네스 검토 한도 예외 승인 대기. Receipt wrapper·canonical/full coverage·reorg·C 실제 자산 이동/벽시계60초/MetaMask는 아직 NOT_RUN이다. 기존 사용자 OPEN/DB·체인·AI 보호156개 해시 변경0/원본 dirty10 보존. 추가 비용·commit/merge/push 없음.
6. **발표 흐름:** 승인→실제 예치/잠금→종료·Claims/FIFO→free 직접 인출을 목표로 하되, 현재 오프라인 검증을 실제 지갑 시연 완료로 표시하지 않는다. 승인 필요 없는 독립 검증은 계속한다.

**최신 C4c-2c-1 완료 / 다음 승인-연결 단위 진행:** 전체 rules 상태·기존 금융식·30초 Report/60초 유예·full OperationApplied11/ReportConsumed3필드 순수 검증34PASS, 공용 runner 회귀 EIP22/raw20PASS, 최초 독립 검토 필수 지적 없음. clean 증거 `c-proof/20261023T022600Z-ca5f509d`/`e0dbfdb9…`, `20261023T022603Z-98562bdb`/`bcfa6d90…`, `20261023T022607Z-c5fd84bb`/`2205442d…`. 최초56 compile오류는 테스트55개 지수 double literal+BigInteger helper1개를 정확한 정수 표현으로 수정했으며 실패 snapshot 보존. sandbox cache cleanup AccessDenied와 clean 사용자환경 성공도 구분했다. 금융 정책·기대값·기존 Math/계약 변경 없음. 원본 dirty10·보호156개 해시 변경0 확인. 다음은 C accountRef golden 및 direct/relay owner·nonce·Intent 범위와 실제 Oracle/user 서명을 연결하는 순수 승인 단위이며, Receipt/canonical/full coverage는 아직 미구현이다. C4b 추가 검토 예외 승인 대기/실제 PG10개 NOT_RUN은 유지하고 독립 작업을 계속한다. C 실제 Anvil 배포·자산 이동·벽시계60초·MetaMask는 NOT_RUN이다.

**최신 C4c-2b 완료:** signed legacy EIP-155/type2의 strict RLP·raw hash·chainId·발신자 복구20PASS, shared runner 변경 후 기존 EIP-71222PASS 재검증, 각각 FAIL/SKIP/ABORT0·최초 독립 검토 필수 지적 없음이다. 실행 `c-proof/20261023T015921Z-5e947ebb`/`adeb2e1f…`, EIP 회귀 `20261023T015918Z-96db84e6`/`27de9030…`. 실제 거래 전송이나 Receipt/canonical 증명이 아닌 순수 검증이다. 다음 C4c-2c는 full Receipt·기존 rules 산식/기한·owner/nonce 상태와 Vault 회계의 연결 계획을 실제 Solidity와 대조 중이다. C4b 추가 검토/새 PG Gate 예외 승인은 아직 사용자 응답 대기이며 실제 PG10개는 NOT_RUN 상태를 유지한다.

**사용자 판단 대기(전체 개발 중지는 아님):** C4b 검토 한도 예외의 추가 독립 검토1회+새 PG Gate1회를 비동기 요청했다. 후속 ENTRYPOINT/명시적 컴파일 whitelist의 최신 오프라인47PASS·Python8PASS는 확인(`c-projection/20261023T015724Z-73178da2`, source `955e3cd…`)했지만 검토·실환경 완료로 승격하지 않는다. 답변 전 C4b 실제PG 재실행은 하지 않는다. C4c-2b의 독립 순수 signed-transaction 검증은 계속 진행한다.

**최신 C4c-1b/2a 완료 / C4c-2b 진행:** 이벤트16종 full ABI **25PASS**와 순수 C EIP-712/EOA 복구 **22PASS**를 각각 별도 source/runner로 실행해 최초 독립 검토 필수 지적 없음이다. 이벤트 원본 `c-abi/20261023T014752Z-39dde769`/`63906ab…`; crypto `c-proof/20261023T014717Z-cb0c60f5`/`944dcf…`. 최초 이벤트24/1FAIL은 cast golden 전사 때 price word의 초과1byte를 바로잡은 **테스트 fixture 수정**이며 허용 조건·제품은 그대로다. C4c-2b는 서명된 legacy EIP-155/type2 raw에서 실제 sender/hash/chain을 복구하는 순수 단위를 구현한다. 아직 Receipt·금융 조건·fresh RPC·전체 감사 검증은 아니며 C4b 실환경 BLOCKED도 그대로다. C4c 상세 보고서는 새 C worktree `claude-docs/dapp-c4c1b-event-abi-2026-10-23.md`, `dapp-c4c2a-eip712-2026-10-23.md`에 둔다. DB·Anvil·사용자 서명·원본/UI/AI 환경은 이번 단위에서 사용하지 않았다.

**최신 독립 C4c-1a 완료 / C4c-1b 이벤트 진행:** C 실제18필드 Report/22필드 Intent·14개 호출 selector의 strict ABI 단위를 신규 구현했다. Java19PASS/0FAIL/0SKIP/0ABORTED, 최초 독립 검토 필수 지적 없음. 실행 `c-abi/20261023T013821Z-8b59f9c1`, source `034a59a01e710d8bee6ec5d8549fcea7697b6a27b528b344cbcc948e339ca27a`, 상세 C worktree `dapp-c4c1a-call-abi-2026-10-23.md`. offset/padding/범위·전체 re-encode와 별도 local cast golden을 검증했다. 순수 ABI 문법이며 ECDSA·금융 정답·canonical RPC·감사 MATCH가 아니다. 미지원 형식은 후속 감사 INCONCLUSIVE로 남긴다. C4b PG BLOCKED/검토 한도 소진은 아래 기록 그대로이며, 독립 C4c-1b에서 full 손익/Claims/토큰 발행 이벤트를 해석하는 단위를 작성·검증한다.

**최신 C4b PARTIAL/BLOCKED / 독립 C4c-1a 진행:** C 전용 binary codec/JDBC/append-only SQL 구현·순수 Java47PASS와 실행 전 최초/재검토2회를 수행했다. 실제 PG Gate는3개 신규 identity로 각각 (1) Docker 기본 주소 풀 고갈 (2) init 임시 Unix 서버 readiness 오인 (3) 캐시 이미지의 Spring ENTRYPOINT 선택에서 중단됐다. 세 실패 DB/로그·자원은 보존, 생성된 소유 컨테이너는 정지했으며 제품 PG 테스트10개는 **NOT_RUN**이다. 최종 후보 source `cbbaf04ecbc6f50a20ff200c94ad7a143a4efada2f5f3d0189a5d90100a868a9`, 47PASS 실행 `20261023T012823Z-6cc1d50d`. ENTRYPOINT 명시 보완+Python8PASS는 확보했지만 추가 독립 검토가 없어 **실환경 재실행 금지/완료 승격 안 함**이다. 본 단위 검토 한도를 초기화하지 않는다. 상세 C worktree `claude-docs/dapp-c4b-storage-2026-10-23.md`와 `deployment/runtime/c-postgres/cpg-{7764daff1e894cd1,899cb42f6f974248,f8b5877a3a024035}`. C4b에 의존하지 않는 순수 C calldata ABI 해석(C4c-1a)을 이어 구현한다. 기존 C 계약·자산 정책/기존 사용자 OPEN·DB·평가 자료는 변경하지 않았다.

**최신 C4a 완료 / C4b 구현 중:** 별도 C Java 모델·BigInteger reducer 직접 JUnit **28PASS/0FAIL/0SKIP/0ABORTED**, 독립 최초 검토 필수 지적 없음. 실행 `c-projection/20261022T204008Z-2fc1cf7d`, Java aggregate `cb29e4ba980265c332b313063adfc89c5bdae9c4d4cf766ed937b04389e19de3`. 자산/Claims 회계, 중복·순서·잘못된 seed·fork 거부를 검증했으며 실제 RPC/서명/DB/전체 coverage 증명은 아니다(`completeCoverage=false`). C4b는 전용 schema·versioned whitelist codec·JDBC 원자 evidence/snapshot/head 저장을 구현한다. 기존 DB/API/금융 경로에는 연결하지 않으며 신규 Linux 내부망 PG-only Gate 전 코드·테스트·독립 검토를 거친다. 상세 C worktree `claude-docs/dapp-c4a-projection-2026-10-23.md`, `dapp-c4b-storage-plan.md`. 아래 “C4a 구현 예정”은 이전 기록이다.

**최신 단계 C3 완료 / C4 진행:** 실제 C controller가 사전 개설한 두 시험 EOA의 고갈·FIFO 시퀀스9개와 전체계약141 PASS/0 FAIL/0 SKIP, 최초 독립 검토 필수 지적 없음. aggregate `c42e04888a601d23cab83f9f885723e2bf779d19d8b92c3ce748df8cf1797dac`, 증거 `20261022T202307Z-0cb29292`, 상세 `dapp-c3-economic-scenarios-2026-10-23.md`. house50wei의 실제 소규모 출자·원금 인출·기존 순위·1wei/FIFO 부분 상환·pause/미지급 차단 독립성·위험 처리/badDebt 비현금을 Foundry에서 검증했다. 제품 계약은 C2 이후 불변이며 실제 PG/Anvil·MetaMask·벽시계60초는 NOT_RUN이다. 다음은 별도 C 패키지의 순수 BigInteger projection/replay 단위(C4a) 구현·직접 JUnit 검증이다. 기존 DB 모의 장부나 B 전체감사MATCH를 C로 재사용하지 않는다.

**현재 최상위 상태:** C0/C1/C2 구현·오프라인 검증·독립 검토를 완료하고 C3 실제 C controller 기반 다중 사용자 경제 시퀀스 검증을 시작했다. C2 보완 aggregate `1d4365a0bc1a27c83cf564fe6a85baec4b77f7b9baf35f78df8602b43bddf64f`, 전체132 PASS/0 FAIL/0 SKIP (`20261022T201803Z-b9ea67c7`), build PASS (`20261022T201914Z-704cd3de`). 독립 첫 재검토 필수 지적 없음: golden/원본 서명22필드 변조/타인 포지션/취소 후 자동 위험 회귀 보완 완료. 과거128PASS와 검토 지적도 아래에 보존했다. C3는 구현 중이며 C4는 기존 소스 대조·단위 계획 준비 중이다. C 실제 PG/Anvil·벽시계60초·MetaMask·전체 감사는 아직 NOT_RUN이다.

**C2 후속:** 새 C controller/22필드 Intent, 실제 lock/settle 및 FIFO Claims를 구현했다. 최초 후보 계약 aggregate `fa398990fe955281e16043c82965b72d1c86d1f0ca0cbd612f3ff1fd59557406`에서 C76PASS/전체128PASS/0FAIL/0SKIP, 전체buildPASS. C2 최초 독립 검토는 계약 결함 발견이 아니라 독립 golden/22필드 원본 서명 변조·타인 포지션·취소 후 자동 위험 회귀 누락을 보통 필수 보완으로 지적했다. 해당 테스트 보완·재실행·재검토 중이며 C2를 완료로 승격하지 않는다. `dapp-c2-atomic-claims-2026-10-23.md`에 실행별 증거와 한계를 기록했다. C3는 실제 C controller에서 사전 개설한 두 포지션을 이용한 고갈/기존 우선권/부분 상환 계획을 준비했으며 C2 통과 전 의존 구현을 시작하지 않는다.

**후속 진행:** C1 구현 최초 독립 검토에서 필수 지적 없음(실소스·테스트·83PASS/build·해시 대조, 검토자의 재실행 없음). 승인된 C2 controller/사용자 Intent 및 Vault lock/settle/FIFO Claims 구현을 시작했다. C2는 구현 중이며 아직 테스트/리뷰 PASS가 아니다. C1 원본 소스는 실행별 snapshot에 보존하고 C2 최종 소스에서 C1 회귀도 다시 수행한다. 단계별 추가 사용자 승인을 기다리지 않는다.

**현재 실행 결과:** C0 Claims/권한 ADR 최초 독립 검토 필수 지적 없음. C1 토큰/Vault 신규31 PASS + 이 C worktree 기존 계약52 PASS = 전체83 PASS/0 FAIL/0 SKIP, 전체 build(Scenario 포함) PASS. aggregate 계약 sourceHash `60a35d6ff5a20d64ff7389bc6b659680e6a566caff0b21d273c7ceaf491286cf`. C1 구현 독립 검토 중이며 통과 후 C2로 이어간다. 실제 PG/Anvil/지갑/잠금·정산·Claims는 아직 NOT_RUN.

새 C 문서: `dapp-c-development-worktree/claude-docs/dapp-c-policy-adr-2026-10-23.md`, `dapp-c1-token-vault-2026-10-23.md`, `dapp-c2-implementation-plan.md`. 실제 증거는 C worktree `deployment/runtime/c-foundry/20261022T195802Z-8e6fef66`(83PASS), `20261022T195735Z-3434ceef`(build). 최초 sandbox compiler 조회 실패 및 stdout cp949 wrapper 실패도 각각 보존했고 제품 소스/검증 조건을 바꾸지 않았다. 최종 출력 UTF-8 수정 후 wrapper exit0/source 불변을 확인했다.

사용자 첨부 `TMS_C_IMPLEMENTATION_APPROVAL_2026-10-23.md`에 따라 C-D01~05의 로컬 모의 초기 정책과 C0~C7 신규 격리 개발이 승인됐다. 아래의 과거 “C 정책 미승인”은 당시 기록이다. 종료/미지급 수익 분리, FIFO 부분 상환, 미지급 중 신규 OPEN 차단, 고정 공급20,000,000 cMKRW(18dec), H10m/U2m/R8m, free 직접 인출, 고정 Oracle 및 OPEN 한정 pause를 적용한다. 기존 B/현물 자산의 자동 이전은 없다.

- 해결할 문제: DB 모의 담보만 검증하는 B와 별개로 실제 ERC-20 소유권·Vault 자산 및 Claims 부채를 계약에서 강제한다.
- 작업 위치: 새 `deployment/runtime/dapp-c-development-worktree`, detached `f9353c33244565b30fb130dfb6159eab441a3523`. 원본 dirty10, UI/AI/B/감사 worktree 보존.
- 순서/완료 조건: C0 ADR·Claims/권한 독립 검토 → C1 토큰/예치/free 인출·실수령/rollback/권한 Foundry·독립 검토 → C2 원자적 금융/Claims → C3 고갈 → C4 projection → C5 새 Linux PG/Anvil → C6 지갑 → C7 중단 경계. PASS는 해당 신규 소스에만 귀속한다.
- 현재 구현: C 전용 worktree 생성, 승인·기존 소스 대조, ADR 작성/독립 검토 준비 중. C 계약 검증·실환경 자산 이동 NOT_RUN.
- 한계: B-v2 취소 하네스 부모 manifest 누락과 Windows RPC/Vite는 별도 미해결이며 독립 C 계약의 선행 조건이 아니다. C가 재사용하는 nonce/취소/복구는 새 검증한다.
- 발표 목표: approve → deposit → 승인 거래/잠금 → 종료/청산 → free 직접 인출. 고갈은 별도 소규모 자본의 종료/미지급/FIFO 상환으로 구분.
- 승인 경계: 일반 구현·검증은 계속 진행. MetaMask 승인, 실질적 경제정책 변경, 추가 비용/파괴적 조작만 사용자 확인. commit/merge/push/외부 배포/유료 호출·기존 데이터 변경 없음.

C안의5개 정책 결정 자료·자금 흐름·단계별 Gate 설계와 독립 검토를 완료했다. 추천과 수치는 사용자 미승인이며 자산 이동 코드는 구현하지 않는다. B-v2 실제 사용자 OPEN과 정지 환경은 보존했고 TOP_UP/CLOSE는 RPC 재발로 보류 중이다. 감사 후보 `dac4ee…`는 canonical 취소/nonce observer 연결과186선택PASS·독립 소스 검토를 마쳤으나 신규 실제 취소 Gate는 하네스 준비 결함으로 BLOCKED다(첫 실행 초기guard, 두 번째 시험OPEN 확정 후 signer 부모manifest 마운트 누락). 아래 후속 기록에 검증 범위와 재개 지점을 남긴다. 기존0029의 전체 감사 Gate PASS를 새 후보의 성공으로 재사용하지 않는다. 추가 서명·버튼 조작을 요청하지 않는다.

> 완료된 전체 감사 기준 identity: HEAD `f9353c33244565b30fb130dfb6159eab441a3523`의 별도 미커밋 감사 worktree, productHash **`0029d2a80ae1b2bab5ceef74479fd3318f946afd246cb455a3f413f879972064`**. 직접JUnit 전체876PASS/53SKIP/1ABORTED/0FAIL, 선택177PASS, 새Linux PG/Anvil1PASS. 일반Gradle전체/실제Chrome·MetaMask로 확대하지 않는다. a0a78d.../b1a737... 및 과거 source별 검증은 아래 이력으로 보존한다. 이후 후보는 아래 `dac4ee…` 별도 결과를 따른다.

### 현재 작업 계획 — C안 결정 준비 / B-v2 취소 coverage 병행

2026-10-23 사용자 후속 승인 범위다. C 자산 이동 구현 승인이 아니며 기존0029 Gate는 frozen 검증 기준으로 유지한다.

1. **C 설계 문서 단위:** 기존 `dapp-c-custody-policy-design.md`를 실제 계약/장부와 대조해5개 정책의 대안·추천·실패·의존성과 구체적 결정 질문을 보완한다. 별도 짧은 결정 요약을 작성한다. 계약별 자금 흐름/원자성/정본/불변식/단계별 성공·회귀·복구 기준과 독립 설계 검토가 완료 조건이다. 추천/미정 수치는 승인 가정이며 C 계약·DB 자산 이동 코드를 만들지 않는다.
2. **B-v2 취소 감사 단위:** 별도 감사 worktree에서 explicit-block strict32byte nonce와 canonical cancellation verifier/동일block nonce-fold를 observer에 연결한다. DB없는 외부EOA·실패무이벤트도 역방향 union에 포함하고50tx/512RPC/250block/30초한도 및 금융 읽기 전용·stablecut을 유지한다. 기존 DB기반 Coverage와 외부취소 Coverage의 숫자를 섞지 않는다. Mock회귀→독립검토→새 격리실환경 순서이며 아직 테스트/실환경 PASS를 주장하지 않는다.
3. **환경/통합 준비:** 중지된사용자환경을바꾸지않고 WindowsRPC/Vite 및 Linux공통timeout의 기존증거를 비교해 최소재현 단위를 선택한다. 실제MetaMask TOP_UP/CLOSE와 UI/AI/DApp 통합은 각환경·권한 조건을 충족해야하며 무단merge/원본덮어쓰기는 없다.
4. **의존성·위험:** 취소이벤트만 수집하면 실패/내부호출을 누락할 수 있다. historical nonce를LATEST/임의0으로 대체하지 않는다. 미지원type/ABI/철회로그/과거상태부재는INCONCLUSIVE, 완전한증거의nonce상충만MISMATCH다. 정책서의 자산 소유권/지급불능/비상탈출/관리자권한 선택은 사용자 승인이 필요하다.

무과금 C 경제 설계 실험4PASS: `c-custody-math-invariants.py`, 새증거 `c-design-math-runs/20261022T175602-6cdbecff`. smallgrid207360·seeded범위표본20000 positioncases(Long/Short각각), 양의PnL부등식9801쌍을검사했다. 현재quantity/2·5·10배/담보증가만/수수료0 가정에서 양의PnL은위험조건E<=MM과양립하지않음을확인했다. 유한pool부족의 이익CLOSE차단과 실제손실청산의 현금방향을구분하기위한 설계실험이며 실제Solidity/C Vault PASS·경제정책승인이 아니다. 독립 검토 전이다.

### C 결정 자료 준비 완료 / B-v2 후속 개발 진행 중

- [C 상세설계 §12~21](../../dapp-audit-development-worktree/claude-docs/dapp-c-custody-policy-design.md) 및 [사용자 결정 요약 5질문](../../dapp-audit-development-worktree/claude-docs/dapp-c-policy-decision-summary-2026-10-23.md)을 작성했다. 정본·범위, 공급권한, Vault/pool·지급불능, free직접인출/Oracle중단, 운영권한을 대안/추천/실패/의존성/질문과 연결하고 계약별 flow·원자성·projection·Gate별 회귀/복구를 구체화했다. 추천과 S/U/H/R·pause/Oracle세부는 아직 미승인이다. 기존B 잔고/OPEN의 자동이전과 C 자산이동코드는 없다.
- 첫독립설계검토가 approve의 호출대상을Token과Vault로분리하도록 지적해 수정했고 최종재검토에서 필수지적없음이다. 실제소스와수학진단4PASS도읽기대조했다. 테스트재실행·DB/RPC·환경조작없음. C 구현/배포/자산인수NOT_RUN을유지한다.
- Windows 최소진단: 미점유127.0.0.1:30585확인후 기존probe를각case1회재사용했다. `listener-20261022T180342079147-summary.json`: Node24.4.1 shellHTTP listenEFAULT24ms, cleanchildHTTP LISTEN_OK19ms, cleanchildVite LISTEN_OK1538ms. 모두종료, 기존서비스/포트/전역환경변경없음. shell/clean차이를다시재현했지만 RPCtimeout근본원인·WindowsEFAULT의정확원인은미확정이다.
- 기존Windows실제Spring response-header timeout과Linux4 REQUEST_BODY_COMPLETED/DIRECT/reused timeout은구분한다. Linux내부실패로Windowsportpublishing이모든RPC실패의필수원인이라는가설은배제되지만 Windows별도native오류를배제하지는않는다. 새pool/idle둘다실패이력과DIRECT관측상 특정원인만단정해timeout/pool/retry를바꾸지않았다. 실제MetaMask추가서명요청없음.
- B-v2취소observer 통합은compile PASS 후186선택중185PASS/1FAIL. 실패는Mock가DefaultBlockParameter를직렬화전string과비교한경계이며전송형식회귀보완중이다. 미실행canonical취소/실환경을PASS로기록하지않고 안전범위에서수정·리뷰를이어간다. 기존0029Gate는변경전frozen증거로유지한다.

### canonical 취소 후보 테스트·환경 경계

- `dac4ee8879f7c32dcde136a059fcc78659af8846bbae2473efbaf8f42438cb9d`: Web3j실제wire JSON 검증으로테스트만보완후 compile `classpath-20261022T180635612733.log` PASS, 감사선택 **186PASS/0FAIL/0SKIP/0ABORTED** `audit-20261022T180809174525.log`. 최초185/1FAIL은보존했다. 독립검토자는4SHA·실제소스·최종186로그를확인했고 필수지적없음이다. 같은owner Intent→Cancel→Intent를새focused실환경으로검증할하네스구현중이며아직실제취소Gate는NOT_RUN이다.
- 전체Windows직접JUnit `all-20261022T180930975559.log`: **880PASS/53SKIP/1ABORTED/5FAIL**(939found/126667ms). 5FAIL은 `OpenAiProviderTest`의임베딩순서/oversizedbody/structuredanswer/slowbody/poison검사로, 모두@BeforeEach `HttpServer.create`→native`Net.listen`의 `Bad address:listen`에서본문전실패했다. cleanup의null server NPE는부수오류다. 제품API유료실패가아니고실제외부호출0인Mock서버준비실패다. 실패를SKIP/PASS로바꾸거나AItest를임의수정하지않았다.
- 이Windows전체회귀는환경BLOCKED/5FAIL기록으로유지한다. Linux network-none은 원본 `OpenAiProviderTest`5개만 동일 assertion으로 비교하는 범위로 좁혔다. 전체 suite에는 source/docs/Python 상대경로 의존성이 있어 portable-only 환경의 전체 회귀는 NOT_RUN이며 저장소 전체를 추가 마운트하지 않는다. 독립 검토와 안전22PASS를 마쳤지만 실제5개 실행은 준비 중이다. globalWindows/Docker/WSL재시작·포트교체·timeout정책변경없음.
- C설계최종대표보장도일반요청별승인/directowner와사전정책의무서명자동청산을구분했다. Oracle/keeper공모로계약검증을통과하는악의가격청산의경제손실위험과signer키보유/역할분리/교체권결정필요를추가하고최종재검토필수지적없음이다. 이는새C정책확정이아니다.

### focused 취소 실환경 Gate 첫 실행 — 준비 실패 / 수정 중

- 신규 `phase-a-20261022182441-f7533443/futures-api-a994e779`, product `dac4ee…`: Linux JVM **0PASS/1FAIL**(15855ms). cancel 전용 manifest miningBlockTimeSeconds=0이 기존 `PhaseAFixture`의2초 주기 identity guard와 충돌하여 Spring 초기화 전 실패했다. 사용자 거래/취소/정산/감사 본문은 NOT_RUN이다. 기존 guard를 완화하지 않고 신규 하네스 metadata와 실제 periodic2를 일치시키는 최소 보완을 진행한다.
- 새 환경3개만 정지·보존, cleanup errors0. 실패 fixture의 manifest/DB/Anvil/로그를 수정하거나 재사용하지 않는다. 별도 signer20PASS·Java compile PASS·하네스 안전14PASS·초기 독립 검토를 실제 Gate PASS로 오인하지 않는다. 기존0029 Gate는 과거 정상 이력으로 보존한다.

### 이번 작업 최종 상태 — C 결정 자료 READY / 취소 실제 Gate BLOCKED

- C 상세설계와 짧은5질문 요약의 독립 검토를 완료했다. Token approve 대상과 Vault deposit/transferFrom/withdraw의 자금 흐름, 사용자 요청별 거래 승인과 정책 기반 자동청산, Oracle 공모 손실의 한계를 보완했다. 최종 필수 지적 없음. C-D01~05 및 S/U/H/R·pause·Oracle 세부는 **승인 대기**이며 C 코드/토큰/자산 이동/배포/실제 인수는 NOT_RUN이다. 순수 수학4PASS는 설계 보조 결과일 뿐이다.
- `dac4ee8879f7c32dcde136a059fcc78659af8846bbae2473efbaf8f42438cb9d`의 새 취소 Gate2는 `phase-a-20261022183524-2f9de7c0/futures-api-4149c9bd`. 보호guard와실제periodic2 유지, 금융 완료 뒤 frozen audit cut 보완, compile `classpath-20261022T183318778445.log` PASS/하네스안전16PASS 후 실행했다. 실제 PG migration·첫periodic block·baseline 및 **시험OPEN CONFIRMED** tx `0x0efb651d014d4918b24c68651d923441dea7b01c43debd421509849e586f6e9d`까지 수행했다.
- 이후 외부 시험 signer에서 `CANCEL_TRIAL_SIGNER_FAILED`로 **JUnit0PASS/1FAIL,167376ms**. signer가 읽는 부모 `session/session.json`은 fixture-only mount 밖에 있다. 정지 컨테이너의 해당 파일 조회 exit1/파일없음·호스트파일존재·mount경계로 원인을 확인했다. 취소·TOP_UP/CLOSE·실제 nonce-fold MATCH는 NOT_RUN이다. fixture 소유3개만 정지·cleanup0, portable1017SHA 불변, 기존401개 ID/이름/이미지/상태분류 불변. 기존 사용자 포지션은 사용하지 않았다.
- focused 하네스는 공통 지침의 최초+재검토2회 상한에 도달했다. 추가 결함을 검토 없이 수정/성공 처리하지 않고 **후속 수정·검토 미완료**로 남긴다. 다음 최소안은 새 fixture 내부에 부모의 비밀 없는 identity snapshot을 원본SHA와 대조해 CREATE_NEW로 전달하고 fixture-only mount 상당 재현을 추가하는 것이다. 기존 부모 전체를 마운트하거나 검사 삭제/기존 실패 환경 수정/세 번째 새 PG 자동 실행은 하지 않았다.
- Windows native listen 실패 비교를 위한 `tms-offline-20261022183344-0b3e592154ed0e21`의 고정 `OpenAiProviderTest` 실제 **JUnit7PASS/0FAIL/0SKIP/0ABORTED,10494ms**. 클래스가 실제7개인데5개라고 가정한 wrapper는 **FAIL/EXACT_FIVE_TESTS_REQUIRED**로 보존했다. 독립 증거 검토가 소스7개와artifact일치를 확인했다. 향후 exact7 하네스만 수정·Mock/AST22PASS·최종 독립 재검토 필수지적없음, Docker 재실행NOT_RUN. 전체 Linux 회귀NOT_RUN/Windows근본원인미확정을 유지하며 유료 API·DB/RPC사용0이다.
- 원본 HEAD f9353c3/dirty10 유지, 보호156 SHA를 대소문자 정규화해 **변경0/누락0** 확인. UI/AI/DApp 웹8경로 해시는 기존 통합 계획과 동일한 충돌 관계다. 자동 파일복사·staging·commit/merge/push·원본/기존DB/체인변경·유료호출·전역재시작을 하지 않았다. 상세 실행 근거는 [감사 후속 문서](../../dapp-audit-development-worktree/claude-docs/dapp-b-v2-authorized-audit-2026-10-22.md), [통합 준비](../../dapp-audit-development-worktree/claude-docs/dapp-worktree-integration-plan.md)에 있다.

### 후속 작업 단위 — 순수 취소 증거 검증 완료 / observer 연결은 별도

- 목적: 외부 EOA의 `cancelNonce` signed envelope·transaction/Receipt/header·취소 event를 검증하는 별도 순수 class와 회귀만 추가한다. 실제 취소 실행, historical nonce RPC 및 observer 연결은 이번 단위에서 하지 않는다.
- 의존성: 앞선 type0/type2 envelope spike와 nonce-fold를 재사용하되 boolean `envelopeVerified`만 신뢰하지 않고 재구성 hash/복구sender/chain을 직접 검사한다. caller가 선택한 trusted 배포 identity 및 실제 canonical cut/codeHash 검증 책임은 기존 observer에 남는다.
- 완료 조건: 정규 직접호출 type0/type2 성공/실패 및 변조·누락·미지원/기술한도·민감정보 비노출의 단위 회귀, compile/관련 선택 테스트, 독립 검토. 실제 PG/Anvil/MetaMask와 완전한 취소 coverage는 NOT_RUN으로 남긴다.
- 위험: 서명 복구·type2 parity·header/log 좌표·status0 해석을 혼동하면 잘못된 확정이 된다. 미지원 내부호출/trailing ABI는 INCONCLUSIVE, 실패 취소는 nonce 불소비와 revert 원인 미확정을 유지한다. 기존 금융/권한/API/DB 쓰기 경로를 바꾸지 않는다.
- 2026-10-23 후속 보존 재확인: 보호156개 변경0/누락0, 원본HEAD f9353c3/dirty10 경로 유지. commit/merge/push/유료 호출 없음.

## 구현 및 검증된 범위

- B-v2 사용자 bounded Intent/실행 Report 분리, 최대1회 unsigned 재가격, canonical 정산 및 동일 raw 복구는 구현됨. 개별 Gate의 환경/시험 signer와 실제 사용자 MetaMask 증거는 구분한다.
- [RPC 조사](dapp-b-v2-rpc-server-recovery-2026-10-22.md): 과거 실제 Spring chainId timeout 확인, 근본 원인 미확정. 새 Spring 동일 client366초 유휴 후 PASS이나 첫 HTTP2595ms 지연. 관측 추가 외 timeout/pool/retry 정책 변화 없음. 직전 직접 JUnit178 PASS/12 SKIP, 독립 검토 필수 지적 없음.
- 기존 실제 MetaMask OPEN 성공 이력은 보존. 최신 제품/사용자 OPEN→TOP_UP→CLOSE 인수 완료를 의미하지 않는다.

## 이전 사용자 인수 작업 / 실행 Gate — 2026-10-22 기록

- 신규 환경 전 Docker28.4.0 Server 응답. C 여유 약33.5GiB.
- 제한 Node24.4.1 listener: 사용자 shell EFAULT 재발, clean child HTTP 및 Vite PASS(동일127.0.0.1:30585). 환경 의존성 확인이며 EFAULT 근본 원인 미확정. 실제 launcher는 기존 clean child를 사용한다. 전역 네트워크/재시작/임의 포트 반복 없음.
- private launcher에 acceptance 전용366초 동일 Spring client 유휴 Gate 추가. 통과 후 실제 웹 proxy 경유 controlled JWT/지갑 proof/nonce/draft 및 read-only codeHash/DB/미확정·잠금 검사. 시험 signer는 공개 scalar이며 사용자 키가 아니고 거래 서명/submit 없음. 실패 시 소유 fixture 정지·보존, 사용자 조작 안내 금지.
- 실행 identity·결과는 확인 후 아래 누적. 기존 fixture는 재사용하지 않는다.

### 신규 제한 인수 READY — 2026-10-22

- session `phase-a-20261022122803-8f9ec7af`, fixture `futures-browser-2557294e`. HEAD `f9353c33244565b30fb130dfb6159eab441a3523`, productHash `fd29bef4fbc32cb2ab9f2ad4785556727361de2373609840e2e310ed0d9ec7f1`, webHash `a9f621ee38d622184768f61b9581766df0a90b55aaceff42318239f8073fb7b1`.
- `acceptance-ready.json` READY/idleAndHttpGate PASS. 실제 Spring PID30684: 동일 Gateway25ms→366초 유휴 후153ms→동일 JVM fresh43ms(여러 RPC 전체). 실행 중 JVM21.0.10/IPv4/loopback proxy 예외를 jcmd로 확인. risk-worker 스레드 존재, 제품 Synthetic1초 tick/OPEN. 과거 RPC 원인 해결을 뜻하지 않는다.
- Web `http://127.0.0.1:31432/#dapp`, RPC `http://127.0.0.1:36609`, chain31337/ETH. controller `0x8182C3e9C67D571d679636d58D38Ad9E7bfe7332`, rules `0x8CD3148580ba8c83102A212e17C72eCC7817Aafe`. 실제 codeHash/relayer/rules.executor 검증 PASS.
- 실제 웹 proxy 경유 signup/me/wallet challenge/link/nonce/draft PASS. 통제 시험계정1개가 있으며 사용자 계정은 아직 생성하지 않았다. 공개 시험 scalar의 EIP-191만 사용, 거래 서명·submit0. 새 사용자 개인키를 서버에 제공하지 않는다.
- read-only PG owner PASS, positions/operations/transactions/unresolved0, locked0, reservedHouse0, house10,000,000 mKRW. 과거 계정/잔고/포지션을 이 DB로 이전하지 않았다.
- 소스 변경 없이 기존178/12 회귀를 참고하되 이번 새 실행 결과로 부르지 않는다. 이번은 private harness syntax PASS, 실제366초/API/identity/DB Gate PASS. 독립 검토 review_bv2_rpc_recovery 준비 코드 및 실제 정제 JSON4개 대조에서 필수 지적 없음. 직접 테스트 재실행은 없었으며 과거 원인 해결이나 실제 MetaMask 거래 PASS를 뜻하지 않는다.
- 보호156개 hash 변경0, 기존373개 컨테이너 ID/이름/이미지/상태 분류 변경0, 이번 신규2개만 실행. 웹/backend/PG/Anvil 유지, 소유 STOP 또는 준비 후60분 상한(대략22:36 KST)에 정지·보존. 기존 fixture/공유서비스는 시작하지 않았다.
- **사용자 개입 대기**: 새 RPC 네트워크 등록 및 새 격리 계정 로그인/지갑 소유증명. 이후 OPEN/TOP_UP/CLOSE 각각 실제 MetaMask 승인 후 operation을 먼저 검증. 아직 이번 실사용자 거래는 NOT_RUN. readiness는 21:35경 Gate 시점 관측이며 계속 정상임을 무조건 보장하지 않는다.

## 이전 인수 시점의 남은 작업과 실행 순서 — 이후 누적 결과와 함께 읽음

1. 위 새 사전 Gate 통과 시 MetaMask 사용자 네트워크/로그인 안내. OPEN→TOP_UP→CLOSE를 각 사용자 서명 후 canonical3events/활성revision/DB 예약·journal과 대조. 오류 시 새 주문 반복 대신 기존 operation 먼저 조사.
2. B-v2 전체 감사: 현재 INCONCLUSIVE 유지. 별도 읽기 전용 gap 조사 병행. 기존 Receipt 정산 검증과 deployment 전체 reverse coverage는 다른 보장이다.
3. UI/AI/DApp worktree의 미커밋 변경 안전 통합 계획 및 회귀. 사용자 파일을 stash/reset/clean하지 않고 무단 merge/push하지 않는다.
4. C안 설계: 실제 사용자 ERC-20 소유권, minter 권한, MarginVault 예치/잠금/손익 풀/인출/서버 독립 경로 및 Oracle 한계를 정의. 핵심 자산·경제 정책의 사용자 승인 대기 후 구현.

## 승인·보존 경계

- 중간 일반 구현/진단/리뷰는 자율 진행. 실제 MetaMask 서명, 금융·수탁 정책 변경, 추가 권한/비용에는 사용자 개입 필요.
- 원본/UI/AI/DApp 미커밋 변경, 기존 DB/Anvil·성공/실패 fixture·ledger/STOP/claim/승인 및 private evidence 보존.
- commit/merge/push/외부 배포, 유료 AI, 전역 Windows/WSL/Docker 재시작, 데이터 삭제·초기화는 하지 않는다.
- 전체 감사 미완료, 최신 실사용자 TOP_UP/CLOSE NOT_RUN. 기존 PASS를 새 실행 PASS로 재사용하지 않는다.

### 사용자 지갑 연결 확인 — 2026-10-22 22시 이후

- 현재 신규 fixture owner를 확인한 뒤 실제 PG READ ONLY 조회: 사용자 계정 userId2의 wallet link와 consumed=true challenge 확인. linked_at `2026-10-22T13:00:43.675738Z`(22:00:43 KST). 통제 시험계정 userId1과 구분했다.
- challenge는 확인 시점에 기한이 지났지만 이미 성공적으로 소비돼 link가 저장된 상태이므로 연결 실패를 뜻하지 않는다. 원문 서명/JWT/개인키 조회·출력 없음.
- positions/operations/unresolved0, locked0, reservedHouse0. 사용자 실제 OPEN은 아직 NOT_RUN. 동일 소유증명 반복은 필요 없다. 다음은 잔고/현재 시장 및 사용자 승인 조건 확인 후 실제 OPEN 서명.

### 실제 MetaMask OPEN PASS / RPC 재발로 TOP_UP 보류

- 사용자가 OPEN 직접 서명 완료를 보고했다. userId2, operation `22685aba-969b-41ac-9a13-9902ec5593f1`, position `88ac7fc8-57a3-45f1-a414-51deb8c0ab88`, DB CONFIRMED/OPEN/HEALTHY/version1.
- tx `0xf552243e6d546c01760c02ae0b29a310ad5708b29d1fbbfafe104096147fdf66`, relayer nonce1, block804, canonical hash `0xad579c982f66ceaf3e98f314570154818a1de45a19a353a21d6b7bff341ed4f0`.
- **실제 제품 Synthetic revision1 성공**: rev0 가격75039.23313866→rev1 가격75040.39454801, active1. Long10x 담보100000 mKRW, 수량13.326155946051313110 mSEC. 사용자 승인 가격74000~76000 및 수량13.157894736842105263~13.513513513513513513 안에 있다. 사용자 nonce0 불변.
- fresh canonical 검증: Windows 게시 RPC Receipt 조회가4초 timeout이어서 소유 Anvil 내부의 명시적 read-only cast 경로로 대조했다. block/tx/receipt hash 및 rules OperationApplied/FuturesReportConsumed + controller UserIntentExecutedV2 주소·순서3개 일치 PASS. 내부 검사는 calldata·서명 전체를 독립 재검증하지 않으며 제품 ReceiptVerifier의 CONFIRMED 검증과 구분한다. 전체 감사 MATCH를 뜻하지 않는다.
- private 증거 `22685aba-969b-41ac-9a13-9902ec5593f1-canonical-131134.json`. journal RESERVE locked+100000, REPRICE/CONFIRM 추가잠금0, wallet/house delta0. user mKRW 총1000000/locked100000(가용900000), house10000000/reserved0. pending operation0, position.pending_operation=null. Faucet는 사용자가 웹에서 실행했으며 하네스가 무단 지급/서명하지 않았다.
- **RPC 장애 재발**: 실제 PID30684 위험 Worker에서22:09~14 KST 성공1~3ms와 REQUEST_SENT/TRANSPORT_TIMEOUT 약3초가 번갈아 발생. DIRECT, reused=true/false 모두 관측. jcmd thread에서 `FuturesRpcGateway.height`(eth_blockNumber)→OkHttp response header 읽기→NioSocket timedRead 확인. 요청 전송 후 응답 header 대기 경계가 확인됐으나 Windows/전달경로/Anvil의 근본 원인은 미확정.
- Vite도22:03/22:11경 backend42300 연결 EFAULT 관측. 공통 Windows 경로 가능성은 가설이며 원인 확정 아님. 별도22:12:48 bounded 비교에서는 Windows curl DIRECT31ms/Python TCP0ms/Anvil 내부188ms chainId PASS. 시점·메서드가 달라 실제 Spring 정상화 증거가 아니다. private `rpc-boundary-*.json`에 보존.
- `inspect-browser-bv2.py`에 소유 fixture 선택 및 read-only 내부RPC3method allowlist 추가. `probe-owned-rpc-boundary.py`는 소유 Anvil에 transport별 chainId1회, timeout/크기 제한, 자동 fallback/retry·거래변경 없음. 독립 검토 review_bv2_rpc_recovery 두 도구와 실제 canonical JSON을 직접 대조해 발견된 필수 수정 없음. 직접 금융 테스트 재실행 없음.
- **현재 단계: OPEN PASS, TOP_UP/CLOSE BLOCKED/NOT_RUN**. 사용자에게 추가 서명/버튼/주문 반복을 요청하지 않는다. 현재 OPEN 담보 잠금은 정상 포지션 담보이며 미확정 거래의 잠금이 아니다. 기존60분 소유 runtime cap과 데이터 보존 유지. 다음은 실제 서버 응답 header 대기 재발 경계를 좁히는 비변경 진단이며, RPC가 안정화된 뒤 같은 position의 TOP_UP부터 재개한다. 이번 사용자의 OPEN을 자동 종료·재전송하지 않는다.

## 다음 작업의 세부 Gate — B-v2 전체 감사

읽기 전용 조사 `inspect_bv2_full_audit_gap`과 구현자의 실제 소스 확인 결과다. 아직 구현/테스트 결과가 아니다.

- 현재 `FuturesAuditSnapshot.read`는 authorized position이 있으면 coverage unavailable을 반환한다. 이 guard를 제거하는 것만으로는 구현이 아니다. snapshot의 authorization=null, 기존 report_json만 읽는 구조, legacy 단일 baseline/주소/2event 역방향 scan을 모두 보완해야 한다.
- A1: 동일 REPEATABLE_READ cut에 owner/Intent/서명/원본 및 active revision/head를 포함하는 내부 증거 모델과 순수 verifier. 외부 API·AI·toString에는 원문 서명/raw를 노출하지 않는다. head/report/raw/범위·금융 예약 위변조 회귀 통과가 완료 조건.
- A2: 신뢰된 deployment registry와 additive baseline provenance. legacy/v1/v2 controller/rules/codeHash/domain 및 owner를 구분한다. 기존 baseline id=1을 덮어쓰지 않는다. 미등록 배포는 INCONCLUSIVE.
- A3: 같은 canonical cut의 rules2event/controller사용자1event 및 양주소 거래 역방향 scan. reverted/no-event/direct rules/orphan, pending/REVIEW_REQUIRED, 역사 state/RPC 부족 및 움직이는 cut 처리. 일부 deployment 검증만으로 전체 coverage.complete/MATCH 금지.
- A4: 신규 PG/Anvil에서 revision0/1 OPEN/TOP_UP/CLOSE·자동 action과 journal/house/잠금을 감사. 정상 MATCH, 증거 상충 MISMATCH, 부족 INCONCLUSIVE를 직접 확인하고 legacy 혼합 회귀·독립 검토 완료 후 연결. 기존 ReceiptVerifier는 재사용하되 Reconciler를 감사에서 호출하지 않는다(정산 mutation 방지).
- 예상: 코드·단위/PG·검토1~2일 이상, 혼합 실환경 실패 주입 추가 여유. 현재 인수 중인 소스 identity를 변경하지 않도록 실행 중 제품 수정은 분리한다.

## C안 조사 — 구현 승인 전 결정할 경계

읽기 전용 조사 `design_c_custody_decisions` 결과. B-v2 인수/감사 이후 상세 설계와 사용자 승인을 진행한다. 지금 C 자산 이동 코드를 작성하지 않는다.

- 현 MockKRW 공개 faucet과 mSEC minter의 임의 burn(from)/owner의 minter 교체가 남으면 비수탁 보장 불가. C 전용 신규 토큰 공급·분배·권한 제한 정책이 필요하다.
- 추천 검토안: 신규 격리 C 도메인에서 온체인 토큰/Vault 정본, DB projection. 기존 DB 모의잔고/포지션을 실제 자산으로 자동 전환하지 않는다.
- 실제 자본이 있는 유한 house/pool의 지급불능 정책, free/locked 자산 분리, 활성 포지션의 가격 증거 없는 비상 탈출, 운영자 pause/upgrade/Oracle 변경 권한은 사용자 결정 사항이다. 특히 현 수익 무상한 산식으로 유한 풀의 모든 지급을 보장할 수 없다.
- B-v2 Intent·정수 금융식·60초 규칙과 canonical 복구 원칙은 재사용 후보. immutable relayer-only controller를 adapter만 붙여 서버 독립 인출 계약으로 바꿀 수 있다고 가정하지 않는다. 새 계약/원자적 Vault 정산/직접 owner 호출/새 domain·감사 provenance가 필요하다.
- 실제 ERC-20 소유권도 원화 상환권, Oracle 진실성, 무조건 지급능력, Anvil 운영자로부터의 네트워크 독립성과는 다른 보장이다.

## 총괄 자율 진행 — 인수 보류와 개발 계속을 구분

> 2026-10-22. TOP_UP 보류는 전체 작업 종료가 아니다. 일반 개발·진단·검토는 승인 범위에서 계속하고, 실제 서명·중대한 자산 정책 결정만 사용자에게 요청한다.

- 사용자 인수 session은 예정된60분 RUNTIME_CAP으로 정상 정지, parent exit0/preserveData=true. OPEN 포지션을 자동 종료하거나 거래를 재전송하지 않았다. 정지 뒤 DB를 임의 재시작하지 않았으므로 정지 이후 SQL 조회를 새 보존 검증이라고 주장하지 않는다.
- 원래 B-v2 worktree productHash는 `fd29bef4fbc32cb2ab9f2ad4785556727361de2373609840e2e310ed0d9ec7f1` 그대로다. 개발을 `deployment/runtime/dapp-audit-development-worktree`(detached HEAD f9353c3)로 분리했다. 변경 소스57개만 SHA-256 대조 복사했고 DB·키·ledger/STOP/claim·private evidence·사용자 문서를 옮기지 않았다. 기존 worktree 병합/commit 없음.
- A1/A2 구현: 내부 사용자 승인 증거/순수 검증기, 같은 RR cut의 승인·head·revision·raw 수집, REPRICE zero/count 보존, deployment registry 및 additive append-only baseline. legacy 감사 guard를 유지한다.
- A3 핵심 구현: trusted deployment별 canonical Receipt/3event/owner/활성revision, 양주소 이벤트·거래 역방향 coverage, confirmed nonce/intent 중복 및 stable cut. 취소 coverage는 아직 미지원이므로 취소 이벤트가 있으면 INCONCLUSIVE. 기존 full-audit MATCH를 승계하지 않는다.
- 실제 새 회귀: compile PASS `classpath-20261022T135249951241.log`; 선택 감사/RPC **88 PASS/0 SKIP/0 FAIL** `audit-20261022T135814595003.log`; 전체 선물 **233 PASS/12 SKIP/3 FAIL** `futures-20261022T135414441017.log`. 실패3개는 기존 Gateway HttpServer fixture의 Windows Bad address:listen이며 전체 회귀 PASS가 아니다.
- 수정 이력: 최초 새H2시험 mapper의 JavaTime 누락3건/보존CHECK에 먼저 걸린 실패주입1건을 수정했다. 이후 실제 감사에서 DB admission guard의 랜덤 accountRef와 지갑 기반 position accountRef를 같다고 검사하는 오판정을 수정하고, guard/owner 변조 음성 회귀를 추가했다. 금융 장부·거래 산식·사용자 승인 조건은 변경하지 않았다.
- 독립 검토: A1/A2·guard 책임 분리 및 C 설계에서 필수 지적 없음. A3 최초 중복 RPC 증거를 HashSet이 접어 MATCH할 수 있는 필수 지적1건을 수정, 중복log/tx/cancel 회귀 추가 후 재검토 필수 지적 없음. 검토자는 소스·보존 로그를 확인했고 테스트를 직접 재실행한 것은 아니다.
- RPC 보완은 새 개발 소스에만 적용: 실제 이벤트 순서에 맞는 단계명, BLOCK_HEIGHT, 상위 trace/stage 및 단계시간 연결. 기존3초 timeout/pool/proxy/retry 유지. 로컬 헤더 지연 테스트는 기존 deadline 실패를 검증했으며 과거 실제 장애의 원인 해결은 아니다.
- C 상세 설계는 [별도 설계 문서](../../dapp-audit-development-worktree/claude-docs/dapp-c-custody-policy-design.md)에 작성하고 독립 검토했다. 5개 경제·자산 정책은 승인 대기, 실제 C 자산 이동 코드 NOT_RUN.

### 현재 실행 중 / 다음 Gate

1. ADMIN 전용 opt-in 감사 API·단일 bounded worker·동시성/timeout/사용자 차단을 연결하고 회귀/독립 검토.
2. 신규 PG/Anvil audit 전용 helper 준비. 실제 revision1 OPEN/TOP_UP/CLOSE, Long/Short 실제60초 유예, canonical/DB·append-only/concurrency·MATCH/MISMATCH Gate. 통제 시험 signer/provider이며 MetaMask·실제 제품 Synthetic 인수를 대신하지 않는다. 감사512RPC/250block 한도는 완화하지 않고 기존2초 mining 조건을 명시한다.
3. 실패 시 최초 경계 보존·최소 수정·검증을 계속하며, 불가피한 환경 승인 전까지 안전한 독립 작업으로 전환. 현재 사용자 추가 조작 없음.

### 감사 API 연결 및 신규 Gate 실행 — 개발 계속

- 새 개발 worktree에 기본OFF ADMIN 감사 API, trusted runtime identity, 단일worker/대기열0/40초 deadline/중복429/안전503 구현. 기존 legacy history와 혼합하지 않고 관측 DTO임을 명시한다.
- 독립 검토에서 timeout 뒤 baseline INSERT가 이어질 수 있는 경계를 발견했다. request-local cancellation token, stable 후 fence, 실제 DB 트랜잭션/INSERT 전·읽기 후·beforeCommit 검사 및 rollback 회귀로 수정했다. JDBC commit 진입 뒤 불확실성은 조회로 확인하며 취소 보장으로 과장하지 않는다. 재검토 필수 지적 없음.
- 최종 컴파일 PASS `classpath-20261022T141404161818.log`; 선택 회귀 **105 PASS/0 SKIP/0 FAIL** `audit-20261022T141521146521.log`; 전체 선물 **253 PASS/12 SKIP/0 FAIL** `futures-20261022T141649594327.log`. 직접 JUnit 방식이며 일반 Gradle test-worker/PG/Anvil PASS가 아니다. 이번 Gateway listener 통과가 과거 Windows 원인 해결을 뜻하지 않는다.
- 새 실환경 Gate 1회: `dapp-audit-development-worktree/deployment/runtime/phase-a-20261022141718-d918b7d7`, `futures-api-542761d1`, productHash `b1a737a2625e5565e2a0454a417fd075f49d50ecb92df88457caf3974e237c3d`. source-built tools 성공 후 최초PG 포트127.0.0.1:35029 게시에서 Windows invalid pointer address/listen TCP4 오류로 **BLOCKED**. PG schema/Anvil 배포/실제 거래·유예/감사 MATCH는 **NOT_RUN**. 기존 fixture를 재사용하거나 포트 변경·새 fixture 반복·전역 재시작하지 않았다.
- Docker Server28.4.0 정상 응답/C 여유32.6GiB/실행컨테이너0은 사전 관측일 뿐 포트 게시 정상 보장이 아니었다. 실패 증거·새 network/container를 보존하고 소유권/상태를 읽기전용 점검 중이다.
- 보호156개 SHA-256 대소문자 정규화 대조에서 실제byte변경0/누락0. 원본 dirty10과 모든worktree 보존. 추가 MetaMask 요청·기존 OPEN 전송/종료 없음.
- 세부 [신규 감사 기록](../../dapp-audit-development-worktree/claude-docs/dapp-b-v2-authorized-audit-2026-10-22.md), [worktree 안전 통합 계획](../../dapp-audit-development-worktree/claude-docs/dapp-worktree-integration-plan.md). 통합은 계획만이며 실제merge/파일결합 미실행.
- 다음: 전체백엔드 오프라인 영향 회귀, Windows listener/새 실패container 읽기전용 조사, 통합 계획 검토. 실환경 장애를 기록한 채 독립적으로 가능한 작업을 계속한다. 사용자 추가 조작 없음.

- 후속 전체백엔드 직접 JUnit **805 PASS/53 SKIP/0 FAIL**(858개, aborted0), `all-20261022T141901959927.log`. 선택105/선물253/전체805는 포함 관계이며 합산하지 않는다. 외부 DB/RPC/유료 opt-in은 차단했다.
- 새 실패container 읽기전용 확인: PostgreSQL1개만Created/PID0/시작이력없음, 소유label·새run하위mount일치, network연결0, postgres/chain/release빈상태. 기존375개 ID/이름/이미지참조/상태분류 전후변경0. 기존DB내부baseline재인수라고확대하지않는다. 기존373개에이전인수2개가추가된375개라는현재기준을사용한다.

### 환경 실패 후 독립 후속 작업

- 동일포트35029의 단일 Node24.4.1 HTTP listener를 기존 clean 환경에서 확인: LISTEN_OK22ms/정상close. 현재 IPv4 동적49152~65535/제외포트목록에35029없음, 후속netstat점유없음. Docker backend와 다른프로세스/시점이므로 원인해결증거가 아니며 전역설정·서비스·새fixture/포트재시도를 하지 않았다.
- B-v2 감사 취소coverage 후속설계를 읽기전용으로 정리했다. owner 직접cancel은DB미등록owner도합법, 성공/실패canonical취소를orphan으로오판하면안된다. 같은block복수nonce는이벤트순서fold와baseline/cut historicalnonce로검증해야한다. 현재구현은취소발견시INCONCLUSIVE유지, 금융정책/제품hash변경없음. 실환경A4Gate가검증되기전완료로승격하지않는다.
- UI/AI/DApp 통합 계획: main/index/style/browser-smoke는hunk별결합필요, 동일P0패널은중복개발불필요, audit웹에는DApp/P0/AI웹미반영. 실제merge·전체파일copy·원본staging은하지않았다.
- C안5개정책은상세설계와독립검토까지만완료. 신규token/Vault/풀/출금코드미구현. 특히유한pool부족시전체revert(잠금위험)와미지급채권분리중선택은사용자승인필수.
- 정확한재개지점: `dapp-audit-development-worktree` 제품hash `b1a737a2625e5565e2a0454a417fd075f49d50ecb92df88457caf3974e237c3d`, compile/선택105/선물253/전체805증거보존. 새A4fixture는PGentrypoint전실패이며재사용하지않는다. Windows/Docker listen원인을안전하게해결하거나별도Linux실행경계승인을받기전실환경Gate재시도금지. 현재사용자OPEN을가진정지fixture는그대로보존한다.
- Dockerhost로그동시대검색에서14:17:27Z apiproxy/expose/vpnkit-forward35029활동4건확인,15초window2522줄에invalidpointer/WSAEFAULT내부오류추가증거없음. 근본원인미확정유지. 추가무한진단·전역조작없음.
- 다음실환경권고는새전용network의LinuxJVM/내부PG·Anvil주소를이용한분리Gate이며실행경계변경승인전미실행. WindowsRPC해결이나Chrome/MetaMask성공으로대체할수없다. 소스bytehash와환경identity를분리해기록하고기존DB·키·evidence는이전하지않는다. C안정책승인, 실제MetaMask서명, 원본병합은각각별도권한경계로남긴다.

### Linux 내부 네트워크 Gate — 사용자 승인 후 실행 중

- 사용자가 신규LinuxJVM·전용Docker내부network·무publish 실행경계변경을 승인했다. 금융/C정책/유료/merge승인은추가되지않았다.
- 새session `dapp-audit-development-worktree/deployment/runtime/phase-a-20261022145701-4e49ea36`, fixture `futures-api-11b4ab68`. 제품hash는기존 `b1a737a2625e5565e2a0454a417fd075f49d50ecb92df88457caf3974e237c3d` 유지. 테스트helper/환경은새identity로기록한다.
- 캐시프로젝트JRE Temurin21.0.12.1 Linux이미지에entrypoint를대체해시험launcher만실행. WindowsJDK21.0.10으로컴파일한portable class와기존resolved JAR를새fixture에source/dest SHA대조복사한다. Linux Gradle빌드PASS로부르지않는다. 기존cache/소스는읽기만사용하며새산출물·tmp는격리한다.
- 신규Anvilanchor의--internalnetworknamespace를PG/Java가공유한다. 컨테이너내부35000/35001로listen하되호스트`-p`는없다. 기존PhaseAFixture loopback/run/DBmarker/AIoff검사를유지하고owner/Internal/subnet/namespace/mount/digest/no-publish검사를추가했다. PostgreSQL은TCP SCRAM을명시요구한다.
- helper확장: 실제ADMIN API/401/403, baseline부족·registry부족·실제PREPARED의INCONCLUSIVE, revision0/1각OPEN/TOP_UP/CLOSE, Long/Short실제유예, MATCH/MISMATCH/원복을검사한다. 목표12tx/32event이며실행결과확인전PASS아님.
- Java선택회귀 **108PASS/0SKIP/0FAIL** `audit-20261022T145536010450.log`, 컴파일 `classpath-20261022T145446865182.log` PASS. 최초추가signer테스트의Windowswildcard생성오류1건을보완하고재검증했다(앞선255/12/1실행보존). privateLinux안전5PASS/네트워크3PASS/ASTadapterPASS. 독립검토의환경변수allowlist/로그실패후전체정지/SHAcopy지적을수정하고재검토필수지적없음.
- 현재PG초기화·DBmarker·TCPauth사전검사후신규계약배포진행중. 최종실제감사/유예/정산판정은아직진행중이다. 원본사용자에게추가서명·조작요청없음.

### Linux 환경 결함 보완과 세 번째 실행 — 2026-10-23

- 첫Linux `145701-4e49ea36`은새PG/SCRAM/namespace검사후배포셸CRLF(`set: Illegal option`)로계약배포전실패. byteCRLF18확인, 원인확정. private_lf exclusive생성/UTF8/LF정규화로고쳤고 실제byte/덮어쓰기거부등private안전7PASS/독립검토완료. 실패run수정·재사용없음.
- 두번째 `150500-9be79a64/futures-api-dc6f453c`은실제새계약배포·Spring/JDBC/RPCstartup후Mockito MockMaker 초기화에서 `JRE / No compatible attachment provider`로시험메서드전실패. 거래evidence0, cleanup errors0/전부정지보존. 금융제품결함으로단정하지않는다.
- 새network-none `tms-mockito-agent-20261022t151248-9792e3`에서현재resolvedbyte-buddy-agent1.14.19 premain + JRE21.0.12.1로Mockito5.11 mock/stub/verify **실제PASS**, container자연종료/보존/cleanup0. 금융/DB실행없음. root시험JVM에만명시-javaagent를적용하고childsigner/JAVA_TOOL_OPTIONS에는추가하지않는다. 해당변경독립검토필수지적없음.
- 세번째새session `phase-a-20261022151405-e6b20653/futures-api-e1dacade` 실행중. 제품hash b1a737...불변, 새helper/agent/portable파일SHAmanifest. 목표12tx32event/실제60초/3판정·권한Gate의최종결과아직대기. 600초bounded JVM/no-publish/internal 정책유지.
- 세부누적 [Linux 감사 Gate 기록](../../dapp-audit-development-worktree/claude-docs/dapp-b-v2-linux-audit-gate-2026-10-23.md). 현재추가MetaMask조작요청없음/기존OPEN자동전송·종료없음.

### 세 번째 Linux 실제 결과 및 네 번째 신규 Gate — 2026-10-23

- 세 번째 실행은 실제 **12 CONFIRMED 거래/32 canonical 이벤트**, revision0/1 OPEN·TOP_UP·CLOSE 및 Long/Short 자동청산에 도달했다. 위험 주입→최종 정산은 Long73.939초/Short75.838초, 각각 온체인 마진콜 deadline60초 및 청산블록65초 후를 확인했다.
- 그러나 감사는 **INCONCLUSIVE / BOUNDED_READ_LIMIT / complete=false**, JUnit은 첫 MATCH assertion에서1FAIL이다. 이후 registry부족·pending·house변조·복원 시나리오는 NOT_RUN. 부분 Receipt 일치를 전체 MATCH로 승격하지 않는다. 독립 검토자가 portable995개 SHA, 기존383개 컨테이너 변경/삭제0, 소유자원 정지·cleanup오류0도 확인했다.
- 원인은 두 deployment의 긴 역방향 이력 조회와 stable-cut 검사를 합쳐 기존512RPC 한도를 넘는 시험 배치다. 514는 소스에서 계산한 수치이며 실행 counter 로그로 관측한 값은 아니다. 제품 한도·검증을 늘리거나 삭제하지 않는다.
- 최소 시험 보완: v2 baseline은 모든 거래 전에 등록하고 불변 유지한다. 거래가 전혀 없는 별도 legacy target은 최종 cut에서 기존 baseline 기능으로 genesis 이후 거래·이벤트가 없음을 증명한 뒤 최초 등록한다. 기존 legacy id1/v2 baseline 교체 없음. 알려진 v2 포지션 허용/legacy이력 거부/미등록v2 거부 회귀3개 추가, **감사111PASS/0SKIP/0FAIL**(`audit-20261022T152740635222.log`), compile PASS. helper 설계·최종diff 독립검토 필수지적 없음.
- 네 번째 신규 session **`phase-a-20261022153056-c8496e93`** 실행 중. productHash `b1a737a2625e5565e2a0454a417fd075f49d50ecb92df88457caf3974e237c3d` 유지, 새 test/helper/portable identity 별도 기록. 기존 성공·실패 fixture 재사용·변경 없음. 최종 전체 Gate 판정 대기이며 Windows RPC/Vite·실제 MetaMask 인수 완료와 별개다.
- 최신 전체백엔드 직접JUnit 결과: **810 PASS/53 SKIP/1 ABORTED/0 FAIL**(864개발견, `all-20261022T153232324154.log`). ABORTED는 `FuturesRpcHttpTest.delayedLocalHeadersRespectProductionCallDeadline`의 로컬 Windows listener 생성 불가 assumption 경계다(소스상 이 suite의 유일한 abort 경로). exit0을 전부PASS로 해석하지 않는다. 선택 감사111개에서는 해당 테스트도 통과했지만 전체실행의 ABORTED를 소급PASS로 바꾸지 않는다.
- 추가3개 observer 경계 회귀도 별도검토 필수지적 없음. 보호156개 SHA 재대조 변경0/누락0, 원본HEAD f9353c3/dirty10 유지.
- 감사 `complete`는 등록된 배포·지정canonical cut 범위다. 동일RPC 관측 안정성이 다중RPC 합의/미래reorg불가/별도confirmationdepth를 뜻하지 않는다. nonce연속이력fold·취소coverage/장기동적chain/현물Receipt·토큰준비금은 별도범위로 명시했다.

### 네 번째 Linux Gate 실제 중단 — RPC 경계 분석 계속

- 신규 `phase-a-20261022153056-c8496e93/futures-api-2c2227fd`는 **전체 Gate FAIL/STOPPED**. PG migration/ADMIN 권한·baseline부족 INCONCLUSIVE/v2초기baseline 이후 거래 loop의 서명 제출 HTTP에서 `503 FUTURES_RPC_UNAVAILABLE`로 중단했다. 독립 검토로 run line76은 revision1 loop임을 확인했다. revision0 OPEN/TOP_UP/CLOSE 검증 반환 및 revision1 OPEN 이후 다음 TOP_UP 제출 실패를 로그/소스에서 확인 중이다. 새 실행의 열린 포지션·잠금이 남았을 수 있으며 전체 미확정0/잠금0을 주장하지 않는다. 이후 전체 감사·양방향실제유예·변조/복원은 이번 실행에서 NOT_RUN이다.
- 실제 Spring 프로세스 trace `60cb6f29-5745-44b3-a002-3aaa1b5ac198`: `CHAIN_ID` → `DEPLOYMENT` → `SUBMIT` timeout. HTTP 마지막 phase `REQUEST_BODY_COMPLETED`, reused=true/proxy=DIRECT, callFailed3033ms/submit3037ms. 이 로그는 peer가 요청을 수신·처리했음을 증명하지 않는다. 이전 Windows-only 원인 가정을 지지하지 않으며 공통 HTTP/서버/자원 경계를 조사해야 한다.
- 소유 컨테이너 정지/cleanup오류0/dataPreserved=true. 기존성공OPEN·실패fixture·ledger/STOP보존. 기존 실패run을 재시작하거나 거래를 재전송하지 않는다. 실제거래 도달 범위·portable/컨테이너 보존은 독립 증거 검토 중이다.
- 새로운 전체 거래fixture를 즉시 반복하지 않는다. 금융상태 없는 소규모 신규 Linux RPC-only 진단 설계로 client연결/유휴/peer도달 경계를 조사한다. 현재사용자추가서명·조작없음, 금융조건·timeout·pool/retry정책변경없음.
- Linux4 독립 증거 검토 완료: 기존386컨테이너 변경·삭제0/새소유3개만추가, portable995개 SHA일치/누락0, internal/no-publish/sharednamespace 일치. 성공4회 코드흐름 검증과 canonical tx별 독립아카이브 부재를 구분한다. 후속하네스에는 각거래assert완료직후 CREATE_NEW 증거를남기는 최소 관측보완만 적용했다. compile PASS `classpath-20261022T154946549855.log`, 독립검토 필수지적없음/실제새파일생성 NOT_RUN. helper현재SHA `12f81f8cde00f274d26f099901acdce371f4288d2737e280e53166e788fa1b8b`.
- 현재 격리 계약의 Foundry 오프라인 회귀 **101 PASS/0FAIL/0SKIP**, Solc0.8.24 컴파일 PASS(`foundry-20261022T155222996414.log`). 최초audit worktree에는ignored lib가없어missingimport로중단했으며, 기존 검증된 원본lib를읽기전용remapping으로사용해신규audit산출물만생성했다. 계약수정/라이브러리설치/배포없음. Foundry vm.warp를 실제Linux 60초 검증으로 대신하지 않는다.
- 무금융 Linux RPC-only 진단 하네스: direct3/relay3 eth_chainId·5초유휴·고정requestID, 동일ID HTTP재전송차단(연결시도는기존library기본정책별도), 전체50초/JRE/키·DB·계약배포없음. 새internal network/no-publish/정제env/전체launcher및Relay SHA검사·owner정지, 안전5PASS/필수SHA누락수정후독립재검토필수없음.
- 최초probe `rpc-only-20261022155744-9eb342`는 **RPC0회/컨테이너0**: Docker `all predefined address pools have been fully subnetted`에서중단. cleanup오류0. 기존network삭제없음. 이미검증된명시10.240.x/24 allocator를재사용하도록최소하네스보완중이며다른오류/불확실실패에서반복하지않는다.
- 취소/nonce fullaudit 후속 기술설계도 독립적으로 조사완료. explicit-block nonce, 지원signed-envelope hash/sender검증, 같은block순서fold, DB미연결외부EOA합법취소, constructor내부호출/code.length0한계, 실패cause미확정·budget산입을확인했다. 아직제품구현/실환경PASS아님, C자산정책승인은계속대기다.
- 신규 RPC-only `rpc-only-20261022160240-9e9146`: **direct3/relay3 고정eth_chainId HTTP6회 PASS**, 제품3초/defaultpool·connectionRetry유지·동일ID신규전송차단/앱재시도0. 직접335/1/1ms, 경유20/89/54ms. 독립검토 artifact573개 SHA일치/owner·internal·ROnewmount·미게시·cleanup오류0 확인. 5초sleep설정이며단조idle실측없음/벽시계차이해석유의. 과거실제Springtimeout 원인·해결은미확정.
- 현재C여유32.03GiB/host가용메모리1.86GiB(실패당시수치아님). 다음제한Gate에는무거운동시회귀를겹치지않는다. 신규startup빈block누적완화(TEST automine→모든거래전실제2초interval)·첫새block관측하네스를검토중이다. 금융/권한/TTL/실제60초/감사한도변경없음. 전체Gate를자동반복하는정책은도입하지않는다.

### 다섯 번째 제한 Linux Gate — 최종 하네스 검증 후 실행 중

- 최종compile PASS `classpath-20261022T160734832881.log`, Linux하네스안전10PASS, RPC-only안전8PASS. lifecycle 최초/최종 독립검토 필수지적 없음. startupAUTOMINE→모든거래전autominefalse/interval2→10초내첫새블록관측, 모든실제유예후freeze. 각제어RPC3초/실패즉시전파·timewarp없음. 가격TTL/5초신선도/금융/최대1재가격/512RPC/250block은그대로다.
- 신규session **`phase-a-20261022160814-5c27892a`**, 최종helperSHA `8b7e01cd6274c32e79c4aefaf55f7ed325c4a9f8088c59ba4b1c442fb987c328`. 제품hash b1a737... 불변, 이전 성공/실패의PASS승계없음. 새모든거래증거는확인직후CREATE_NEW저장한다.
- 이 실행에는무거운전체회귀를겹치지않는다. RPC-only 미재현은원인해결보장이아니다. RPC/identity/금융검증이실패하면해당run중단·정지보존하며사용자거래재서명/재전송이나무한fixture생성을하지않는다.
- 현시점별도범위: legacy+v2 실제등록2target Gate이며B-v1실제등록배포/취소nonce연속fold/장기동적cut은미인수다. complete는등록target·관측cut일치범위로만표현한다. 실제제품Synthetic/Chrome/MetaMask TOP_UP·CLOSE 및C자산구현완료로확대하지않는다.

### Linux5 실제 실행 PASS — 최종 증거 검토 중

- 신규 actualPG/Anvil helper **1PASS/0FAIL/0SKIP/0ABORTED**(393738ms), productHash b1a737... / LinuxJRE21.0.12.1 / nohostpublish. revision0·1 각 OPEN/TOP_UP/CLOSE6개 CONFIRMED·각3canonical event, 양방향 OPEN/START_CALL/LIQUIDATE6개를 합쳐 **12tx/32events**를 실제감사했다.
- 정상 **MATCH/complete=true/266checks·0mismatch**, 미등록v2 target 및 PREPARED pending은 **INCONCLUSIVE/complete=false**. 시험unsigned주문은기존명시거절경로로RELEASE1회·잠금반환 후13ops/12tx/32event MATCH292/0. house projection1wei 오차는 **MISMATCH/complete=true/292checks·1mismatch**, 정확복원 후 MATCH292/0. journal/승인/기준점덮어쓰기없음.
- Long단조wall **71.546988009초**, Short **71.667954411초**. 각각chain call→deadline60초·call→liquidation64초·새report observedAt=deadline+1. 60초생략/timewarp없음. 모든유예/정산후에만새소유Anvil cut102를freeze했다.
- v2baseline4→cut102불변, 별도무거래legacy는genesis빈이력검증후block102최초등록/기존legacyid1불변. 거래전firstperiodicblock5를2.061918636초에확인. 단일새block관측을장기cadence전수실측으로확대하지않는다.
- 새PG복제schema의v1행보존·walletunique·FK·appendonly, baselinezero-rowUPDATE/DELETE/TRUNCATE차단·동일Envelope동시재등록, 실제ADMIN/USER403/익명401검증을통과했다. 최초INSERT경합을검증했다고주장하지않는다. 사용자wallet+house총13,000,000mKRW와최종잠금/house예약해제는DB모의장부보존이며ERC20실예치/지급능력증명이아니다.
- cleanup오류0/dataPreserved=true. 최종독립증거검토대기. 보호156개최종SHA변경0/누락0/rootdirty10·HEADf935보존 재확인. commit/merge/push/유료0. 실제최종결과·공개계약identity·6개tx표는 [Linux누적보고 §13](../../dapp-audit-development-worktree/claude-docs/dapp-b-v2-linux-audit-gate-2026-10-23.md)에기록했다.

### Linux5 최종 독립 인수 확정 — 현재 판정

- 독립검토 `inspect_bv2_full_audit_gap`: **발견된 필수 수정 없음**. 보존995artifact SHA직접대조누락0/불일치0, 현재제품hashb1동일/소스helper8b7e01·compiled7d12b41일치. 6개거래즉시증거↔gate↔감사 item 및12tx/32event/3판정/원복·유예를실제JSON으로대조했다.
- 기존391컨테이너 ID/이름/이미지/상태분류 변경·누락0, 신규3개모두Exited0/cleanup오류0/internal·owner·namespace·publish0확인. DB/RPC재호출이나fixture변경없는읽기검토다.
- **등록legacy+B-v2·고정cut·controlled가격·시험EOA 범위 Linux PG/Anvil 감사 Gate PASS**. 실제MetaMask/제품Synthetic·WindowsRPC근본해결/B-v1실배포·취소noncefold·C자산보관은별도미완료. 프로젝트전체완료로과장하지않는다. 과거실패/RESERVED/STOP/OPEN성공증거는보존했다.
- 다음안전작업: 기존취소coverage후속설계를근거로 signed-envelope hash/sender/chainId 재구성의무과금로컬기술spike. 제품·금융·취소정책을바꾸지않고실RPC/DB없이진행한다. 원본통합/유료/자산정책결정은계속권한경계를유지한다.

### Gate 이후 독립 무과금 감사 준비 — 실행 기능 변경 없음

- 임의메모리 시험키로 type0 EIP155/type2(nonemptyaccessList)의 RPC field→signed raw byte동일/hash·복구sender·chain/to/value/input 재구성 기술spike **6그룹PASS**: `dapp-stage2-offline/cancel-envelope-spike/runs/local-20261022163807-98a634`. wrongchain·서명/입력변조의 실제음성검증, 미지원type1/3/4·trailing ABI의 INCONCLUSIVE 경계를구분했다. RPC/DB/Docker/사용자증거/유료0, 키저장·raw/서명출력0.
- Web3j4.12.2 type2 RPC parity0/1과 내부SignatureData27/28의 경계를확인했다. 최초로컬실패2개는보존하고 보정후byte동일대조를통과했다. 독립소스검토에서필수지적없음. source/class SHA는기록했지만dependency별SHA·실제JDK버전로그와to/value변조각음성은없으므로제품·완전재현성·canonical Receipt·실제MetaMask검증으로확대하지않는다.
- 다음순수 `FuturesAuditNonceFold`와단위시험은작성중이다. nonce연속성만다루며 ABI/서명/canonical/historical/coverage의상위검증을대신하지않는다. 새class는현재observer에연결하지않고, 전체coverage의취소미지원 INCONCLUSIVE 정책도유지한다. C자산이동/거래서명/잠금해제 로직변경없음.

### 후속 nonce-fold 후보 로컬 검증

- 순수class/test27개만추가·동결. 그룹정규화/explicitbaseline-cut/성공IntentCancel만+1·자동실패0/같은block정렬/중복·gap·reuse·최종값·uint256·범위/한도를검증한다. 서명/ABI/canonical검증자체와금융감사MATCH가아님을API계약에명시했다.
- 신규후보hash **`3f4e5d22b303b15b7feaed65c499e2fce191a7a65c34f700ea62b0b32d58862e`**. compilePASS `classpath-20261022T164402637625.log`, 선택감사 **138PASS/0FAIL/0SKIP/0ABORTED** `audit-20261022T164612214544.log`(신규27포함). 독립검토중이며새후보전체backend/PG·Anvil은NOT_RUN이다. Linux5 b1a737... 실제Gate와범위를구분한다.
- 기존observer/Gateway/ledger/schema/계약/HTTPAPI수정없음, cancelcoverage INCONCLUSIVE유지. 다음순서는순수컴포넌트독립검토→historicalnonce/서명envelope/canonical취소관측의안전연결설계다. 실제취소·사용자거래·금융자산이동·유료호출은수행하지않는다.

### nonce-fold 최종 수정·검토 완료

- 최초138 PASS 이후 독립 검토가 발견한 block-global logIndex 충돌과 transaction/log 순서 역전 누락을 보완했다. 정상 same-block/50전이 fixture도 EVM 전역 log 순서에 맞췄다. 원래 실패 사실을 소급 성공으로 바꾸지 않는다.
- 수정본productHash `a0a78d086bbcf3de459016a6db249e6b4de20c35fd084527f8e4e4c8b9755926`, compile PASS `classpath-20261022T165135475141.log`, 감사선택 **142PASS/0FAIL/0SKIP/0ABORTED** `audit-20261022T165354559585.log`(nonce31개 포함). Windows 직접JUnit이며 일반 Gradle worker 전체PASS가 아니다.
- 최종 별도 재검토 `inspect_bv2_full_audit_gap`은 동결2SHA/실제소스/4추가회귀와 최종142결과/컴파일로그를 직접 읽고 필수지적없음을 보고했다. 검토자 테스트재실행·상태수정없음.
- 이 후보의 실제PG/Anvil 및 전체backend 재실행은NOT_RUN. b1a737... Linux5 인수결과는 frozen소스에만귀속한다. 현재 다음단위는 순수 취소 envelope/Receipt/header 검증이며 observer/금융경로 연결은 하지 않는다.

### 최종 감사 보완 소스 / 신규 Linux 회귀 진행

- 순수 취소 verifier29개 회귀 및 전역50거래 Budget 보완을 구현했다. 최초별도검토가 removed 증거를 MISMATCH가 아닌 INCONCLUSIVE로 처리할 것과 승인검증 실패·미등록 route의 DB 거래도 union에 선등록할 것을 발견해 수정했다. 각각 최종 재검토에서 추가 필수 지적 없음이며 소스SHA·실제선택결과를 직접 읽었다.
- 수정전 외부거래cap의3개 Mock 실패를 실제 재현한 로그 `audit-20261022T171100412284.log`는 보존했다. 수정후 compile `classpath-20261022T171452664727.log` PASS, 감사선택 **177PASS/0FAIL/0SKIP/0ABORTED** `audit-20261022T171520874965.log`.
- 최종 productHash **`0029d2a80ae1b2bab5ceef74479fd3318f946afd246cb455a3f413f879972064`**. 직접JUnit 전체backend **876PASS/53SKIP/1ABORTED/0FAIL**(930found/117309ms), `all-20261022T171608046137.log`. 일반 Gradle test-worker 전체PASS가 아니며 abort는 별도로 유지한다. 순수취소/noncefold는 observer미연결, current취소coverage INCONCLUSIVE 유지.
- Docker Server28.4.0 제한12초응답PASS, C여유34,179,268,608bytes(약31.83GiB), host FreePhysicalMemory1,957,696KiB. 제한권한 Get-PSDrive의 Free누락은 실제공간0으로 해석하지 않고 CIM실측으로 확인했다. 자원값은 실패당시 원인을 증명하지 않는다.
- 무거운 전체회귀가 종료된 뒤에만 새 **`phase-a-20261022171906-0b891df0`** Linux 내부network/no-host-publish Gate를1회 시작했다. 현재실행중이며 PASS예정으로 기록하지 않는다. 새Source에 과거Linux5 PASS를 승계하지 않고, 실패시새run정지·보존/자동반복없음. 기존사용자환경·DB·체인·평가상태변경없음.

### 최종 소스0029 신규 Linux Gate 인수 완료

- `phase-a-20261022171906-0b891df0/futures-api-e9e07584` 실제 **1PASS/0FAIL/0SKIP/0ABORTED**(398251ms). revision0/1 OPEN·TOP_UP·CLOSE6거래의 각3event 및 양방향자동청산을 합쳐12canonical tx/32events MATCH complete266checks/0mismatch. 새source에 이전PASS를재사용하지않았다.
- 실제Long wall87.111734047초/call→청산81초, Short67.705291526초/63초. 각온체인deadline=call+60초, 새Report관측은각deadline+19/+0이고청산시2/3초신선도. 이전run의71초/64초/+1값을현재로복사하지않았다. timewarp/유예생략없음.
- 미등록·미확정INCONCLUSIVE, unsigned명시거절RELEASE/잠금반환→MATCH292/0, 새house1wei오류MISMATCH292/1→정확원복MATCH292/0을확인했다. v2baseline4→cut106불변, legacy는마지막106에서genesis빈이력확인후fresh등록/oldid1보존. DBwallet+house13,000,000mKRW·최종잠금/예약0, 새migration·append-only/동일Envelope동시재등록·ADMIN/USER403/익명401PASS.
- 최종별도증거검토 `inspect_bv2_full_audit_gap`: 발견된필수수정없음. portable1014SHA누락0/불일치0, 현재product0029/helper8b7e01·compiled7d12b41일치. 6즉시증거↔gate↔감사항목일치, 실제각유예/3판정/원복/coverage범위를대조했다. DB/RPC재호출·상태변경없음.
- 기존394컨테이너ID/이름/이미지/상태분류변경·누락0, 새3개Exited0/cleanup오류0/dataPreservedtrue/internal/sharednamespace/PortBindings0. 기존사용자OPEN/실패fixture/ledger·STOP·RESERVED는보존한다. 실제결과·공개identity·tx6개는 [Linux누적보고 §14](../../dapp-audit-development-worktree/claude-docs/dapp-b-v2-linux-audit-gate-2026-10-23.md)에기록했다.
- 한계: 등록legacy+v2/controlledprovider/시험EOA/고정cut범위다. type0/2순수취소verifier·noncefold는observer미연결,51거래음성은Mock6회귀이며실제51거래fault아님. B-v1실배포/장기cut/실제MetaMask TOP_UP·CLOSE/WindowsRPC·Vite원인해결/C자산보관은미인수유지.
- 다음의존성: 취소coverage통합은explicit-block historicalnonce와전역reverse union/동일block전이접합을검증해야한다. UI/AI/DApp의실제hunk통합은계획만수립/무단merge금지유지. C안은 [수탁정책설계](../../dapp-audit-development-worktree/claude-docs/dapp-c-custody-policy-design.md)의5핵심결정승인대기이며자산이동코드는작성하지않았다.
- 최종보존재확인: 보호156개SHA변경0/누락0, 원본dirty10경로·HEADf9353c3유지, 격리worktree `git diff --check` PASS. 실행Gate/오프라인테스트는종료됐고새소유서비스도정지했다. commit/merge/push/외부배포/유료호출/기존fixture재시작없음. 다음재개시이0029소스와§14증거를기준으로삼되이후source변경의자동PASS승계는금지한다.
