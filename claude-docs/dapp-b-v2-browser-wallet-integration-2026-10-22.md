# B-v2 브라우저 지갑 연동 — 2026-10-22

## 판정 및 기준

구현/무과금 검증 작업. **실제 독립 사용자 지갑 및 새 PostgreSQL/Anvil 거래 인수는 NOT_RUN**이다. Mock 브라우저의 CONFIRMED 문자열은 실제 체인 확정 증거가 아니다. [이전 PG/Anvil Gate](dapp-b-v2-postgres-anvil-gate-2026-10-22.md)의 PARTIAL/마지막 Docker 포트 게시 BLOCKED를 승격하지 않는다.

- branch `feat/dapp-user-authorization`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523` + 기존 및 이번 미커밋 변경. 커밋/병합/push 없음.
- 최종 productHash `d63881bc57e0ea035d7364575e27833cd81789e431dda000d6535e8dacabea66`. 기존 `phase-a.py::product_hash` 기준이며 웹/테스트는 이 해시에 포함되지 않는다. 웹 sourceHash `b1188a3731211419e51daa6b3c26cdb93ddc91d62a45826141e72bc12ebc6d31`: 웹 src 전체 파일, index.html, package.json, package-lock.json을 경로 정렬 후 경로 UTF-8 + NUL + 원본 bytes로 SHA-256 누적. 기존 manifest를 이 값으로 덮어쓰지 않는다.
- UI/AI 별도 worktree의 프론트엔드 차이를 읽기 전용 비교했다. 해당 변경을 복사/병합하지 않고 이 worktree의 JWT/ApiClient/DOM helper를 재사용한다. 최신 P0/AI 발표 UI를 이 브랜치에 통합했다고 주장하지 않는다.
- backend의 금융/Worker/정산/SQL/Solidity는 이번에 변경하지 않는다. 기존 미커밋 파일들은 앞선 단계 작업이다.

## 이번 변경 파일

| 파일 | 변경 |
|---|---|
| `tools/websocket-test-client/src/dapp-intent.js` | 18/8자리 정수 스케일, 명시적 가격/수량 범위, 22필드 typed data/domain 비교, ID/accountRef Keccak 비교 |
| `src/dapp-wallet.js` (동일 웹 경로) | EIP-1193/EIP-191/EIP-712, 계정/chain/세션 무효화, Web Locks, 불명확 요청 보존 |
| `src/dapp-panel.js` | 별도 opt-in 화면, 조건 확인/원문/서명, 포지션 선택, 상태 조회 및 정산 확정 표시 |
| `src/api.js`, `src/main.js`, `index.html`, `src/style.css` | v2 전용 API, 인증/라우팅, 반응형 표시. legacy 쓰기 API로 v2 주문을 전송하지 않음 |
| `test/dapp.test.js`, `scripts/fixtures/dapp-fixture.mjs` | 정확도/변조/만료/경합/사용자 변경/응답 유실 Mock 회귀 |
| `scripts/browser-smoke.mjs` | 기존 화면 회귀 + Mock 지갑 시나리오, 독립 Chrome profile/실행별 ignored 증거 |
| `backend/.../authorization/AuthorizedV2Controller.java` | 인증된 GET config 추가, 기존 position 래퍼에 정확한 version/epoch 문자열 추가 |
| `backend/.../authorization/AuthorizedV2HttpTest.java` | JWT config/보관·감사 한계 및 2^53 초과 version/epoch 문자열 검증 |

`src` 이하 표기는 `tools/websocket-test-client/` 기준이다. backend 변경은 readonly API 응답 추가에 한정한다. B-v2 활성화 flag 기본 OFF 및 기존 경로를 유지한다.

## 사용자 승인과 단위

1. 로그인 후 `#dapp`에서 활성 배포 config를 읽는다. flag OFF/없는 경로는 오류로 표시하고 legacy 화면은 유지한다.
2. 사용자가 직접 지갑을 연결한다. chainId 불일치는 자동 네트워크 변경 없이 거절한다. 초기 계약 지원은 EOA이며 서버의 실제 RPC EOA 검증을 재사용한다.
3. 서버 challenge를 `personal_sign`으로 서명하고 wallet/link를 호출한다. challenge 만료, JWT 귀속, 중복 지갑, Origin은 기존 서버에서 최종 검증한다. 지갑 키/seed는 입력받거나 저장하지 않는다.
4. 사용자가 방향, 2/5/10배, 담보, 최소/최대 가격과 TTL을 입력한다. 가격 범위의 무제한 기본값이나 자동 확장은 없다.
5. OPEN 수량 하한은 최대 가격, 상한은 최소 가격에서 기존 floor 산식으로 계산한다. 사용자는 화면에서 두 값을 확인하고 checkbox로 승인한다.
6. draft의 22필드, domain, ID 해시, owner, relayer, nonce, policy, 각 범위를 확인한 요청과 대조한다. 검증된 원문과 **동일한 typed data**를 `eth_signTypedData_v4`에 전달한다.
7. 결과를 submit하고 operation을 REST 재조회한다. 시장 장애/새 tick/범위 위반은 기존 서버/Worker가 판단한다. UI가 5초/30초 조건을 늘리지 않는다.

수량 `floor(marginWei × leverage × 10^8 / executionPriceE8)`, 담보/수량 18자리, 가격 8자리. JavaScript BigInt만 경제 수치에 사용한다. Number는 제한된 enum/TTL/초 단위 표시만 사용한다. TOP_UP/CLOSE는 선택 포지션의 owner/controller 및 정확한 entryPrice, quantity, version, callEpoch를 사용한다. TOP_UP/CLOSE 실제 체인 실행은 이번 웹 검증으로 입증되지 않았다.

## 비동기/복구 및 한계

- wallet accountsChanged/chainChanged/disconnect, 로그인 사용자 및 화면 변경 시 진행 중인 응답을 무효화한다. 지갑 반환 뒤 계정과 chain을 다시 조회하고, TTL도 재확인한다. 서명 거절/만료에 자동 재서명하지 않는다.
- 서명부터 제출까지 같은 사용자/controller에 Web Lock을 유지한다. busy/단일 제출 및 공유 localStorage marker로 다른 탭/새로고침 이후 중복 생성도 차단한다. Web Locks/저장소를 쓸 수 없으면 fail closed한다.
- JWT/사용자 서명은 영속 저장하지 않는다. 최소 요청 키/공개 조건/식별자/상태만 사용자·chain·controller별로 저장한다. draft POST 응답 유실은 **같은 조건·같은 키의 명시적 재조회성 POST**만 허용한다. 자동 재시도 없음.
- submit 직전 SUBMITTING marker를 선저장한다. 응답 유실/서버 오류 이후 새 키 재주문을 막고 operation GET만 허용한다. 404가 미전송 증거는 아니다. 현재 보수적인 한계: submit이 실제로 수락되지 않았지만 결과가 불명확한 경우 운영자 확인이 필요하며 UI에서 잠금을 강제 삭제하지 않는다.
- SIGNED/SUBMITTED/REVIEW_REQUIRED를 화면에서 실패 처리하지 않는다. `CONFIRMED && settlementConfirmed=true`일 때만 온체인+DB 확정이라고 표시한다. terminal 응답 이후 과거 상태가 돌아와도 후퇴시키지 않는다.
- nonce 취소 UI는 이번에 구현하지 않았다. 직접 취소 제출/확정을 구분해야 하며 기존 잠금을 임의 해제하지 않는다.
- 3초 REST 조회로 연결 단절 후 상태를 다시 확인한다. B-v2 전용 WS 확장이나 AI 모델 호출은 추가하지 않는다. 기존 WS/AI 기능은 보존한다.
- DB 담보는 실제 토큰 예치가 아니다. 전체 B-v2 감사는 INCONCLUSIVE로 표시한다. C안 MarginVault/사용자 ERC-20 보관은 미구현이다.

## 실제 실행 검증

| 범주 | 결과 / 범위 |
|---|---|
| 웹 Node 전체 | 최종 144 PASS / 0 FAIL (신규 17개 포함), 무과금 Mock |
| 웹 build | PASS, Vite 102 modules |
| 직접 JUnit futures | 152 PASS / 12 SKIP / 0 FAIL. `futures-20261022T072224315479.log` |
| 오프라인 Java testClasses compile | PASS. `classpath-20261022T054603463481.log` |
| 정상 Gradle test worker 전체 | NOT_RUN — 직접 JUnit과 구분 |
| Chrome 1366×768 / 390px | 최종 Mock 실행 PASS, `browser-wallet-smoke/run-16K29w` |
| 실제 독립 EOA 브라우저 서명 | NOT_RUN — 사용자 지갑 준비/수동 승인 필요 |
| 최신 웹 → Spring → 신규 PG/Anvil OPEN/TOP_UP/CLOSE | NOT_RUN — 과거 core Gate를 대체하지 않음 |
| 신규 유료 AI/키움 연결 | 0회 |

첫 Chrome 실행에서 Mock 정상 경로는 통과했다. 이후 캡처 동기화 보완 실행에서 `0 !== 1` assertion이 발생했다. 테스트가 실제 operation이 아니라 정적 안내문의 SUBMITTED를 기다려 POST 완료 전에 검사한 경합이다. 결과 영역을 `dapp-operation`으로 분리하여 동기화 대상을 고쳤다. 성공한 실행도 실제 사용자 지갑 인수로 해석하지 않는다.

Chrome 증거는 새 `deployment/runtime/browser-wallet-smoke/run-*`에 실행별 저장한다. 기존 profile·증거를 삭제하거나 실패한 거래 fixture를 초기화하지 않는다. screenshot에서는 Mock 주소·가격을 사용한다.

## 환경/보존 및 후속 인수

- 읽기 전용 확인: Docker Server 28.4.0 응답, 실행 중인 컨테이너 없음. C 가용 41,799,970,816 bytes (약38.93 GiB). 기존 보존 컨테이너를 시작/중지/삭제하지 않았다. Docker가 응답한다는 사실만으로 과거 포트 게시 장애가 해결됐다고 판단하지 않는다.
- 사용자에게 테스트 전용 독립 지갑 준비 여부를 물었다. 공개 Anvil 기본 키나 하네스 키를 사용자 독립 지갑 증거로 사용하지 않는다.
- 실제 수동 인수는 새 owner/ports/DB/chain/controller identity를 만든 뒤, 앱에 사용자 키를 전달하지 않은 상태에서 사용자가 계정 연결/EIP-191/EIP-712 승인해야 한다. 테스트 Gas는 해당 새 체인에서만 지급한다. 기존 Google/KRX/AI 환경을 재사용하지 않는다.
- 신규 fixture에서 Docker 포트 게시 오류가 재발하면 그 경계에서 중단한다. 전역 재시작/포트 돌려막기/기존 manifest 갱신으로 우회하지 않는다.
- 120초 Intent/30초 Report/5초 시장 조건, 실제 Worker 경합 및 canonical Receipt는 새 실제 Gate에서 다시 확인해야 한다. 실제 신규 transaction/Receipt 증거 없이는 완료 판정하지 않는다.

## 독립 검토 및 최종 누적 기록

### 최종 누적 기록

- 독립 검토 `review_bv2_browser_wallet`를 요청했으나 에이전트가 사용량 제한 오류로 실행에 실패했다. **독립 검토 BLOCKED/미완료**, 검토자가 필수 수정 없음을 확인한 것으로 기록하지 않는다. 같은 구현자의 아래 화면 확인은 자체 점검이다.
- 자체 화면 점검에서 서명 전 DRAFT를 존재하지 않는 operation으로 주기 조회하는 문제가 추가 발견됐다. SUBMITTING/ACCEPTED만 주기 조회하도록 수정하고, DRAFT resolve는 서버 호출 전에 명시적 안내로 차단한다. null 조회 응답도 확정으로 승격하지 않고 오류로 처리한다. 신규 회귀 1개 및 Chrome에서 서명 전 operation GET 0회를 확인했다.
- 최종 `npm test` **144/144 PASS**, `npm run test:browser` **PASS**, 포함된 새 build PASS. `run-16K29w`의 desktop consent/mobile confirmed 화면을 직접 열어 승인 수치/INCONCLUSIVE/모의 담보 안내와 줄바꿈을 점검했다. 앞선 `run-J8yeJF`는 위 DRAFT 조회 결함이 보이는 중간 캡처로 보존하며 최종 캡처와 구분한다.
- [1366px 승인 조건](../deployment/runtime/browser-wallet-smoke/run-16K29w/desktop-dapp-consent.png), [390px Mock 확정 표시](../deployment/runtime/browser-wallet-smoke/run-16K29w/mobile-dapp-confirmed.png). 실제 확정 거래 화면이 아니라 fixture다.
- 보호 기준표 156개 SHA-256 **변경 0**. 원본 dirty 10개(기존 tracked4/untracked6), UI/AI worktree 기존 변경 보존. 의존성 lockfile 변경 없음. `git diff --check` PASS. Docker/DB/Anvil 생성·기동·정지·삭제·재시작 및 신규 배포 없음.
- 실제 지갑 준비 여부/수동 승인 응답은 이번 작업에서 확보하지 못했다. 사용자 지갑 연결/서명 및 최신 웹→신규 PG/Anvil 거래는 **NOT_RUN**이다. 현재 Docker 정상 Server 응답은 새 port publishing PASS를 뜻하지 않는다. 이전 PARTIAL Gate/포트 장애 기록도 그대로 유지한다.
- 미완료: 독립 검토 재개, 실제 사용자 지갑 수동 인수, 새 격리 서비스와 OPEN/TOP_UP/CLOSE/자동청산 확인, 사용자 권한/중복 지갑/만료의 실제 HTTP·지갑 인수. UI의 nonce 취소 및 전체 감사/C안은 이번 미구현 경계 그대로다.

전체 판정 **PARTIAL — 웹 구현/Mock 회귀 PASS, 독립 검토 BLOCKED, 실제 사용자 지갑 및 신규 체인 인수 NOT_RUN**. 다음 기능 구현으로 자동 진행하지 않는다.

## 후속 실제 지갑 인수 준비 — 새 실행 (2026-10-22)

위 내용은 이전 실행 이력이다. 사용자가 MetaMask 및 독립 테스트 지갑을 준비한 뒤 새 격리 환경 생성을 승인했다.

- 독립 `review_bv2_wallet_acceptance`: **발견된 필수 수정 없음**, 직접 Node 지갑 테스트 17 PASS. typed data/owner/chain/JWT/지연 서명/BigInt/Web Locks/응답 유실 경계를 읽기 전용 검토했다. 아래 private 환경 준비 하네스도 별도 검토했으며 필수 수정 없음. 실제 사용자 지갑 인수 판정과는 별개다.
- 새 session `phase-a-20261022082024-283bc6fc`, fixture `futures-browser-9fe35e84`. 기존 성공/실패 fixture 재사용 없음. productHash `d63881bc57e0ea035d7364575e27833cd81789e431dda000d6535e8dacabea66`, HEAD f9353c3 유지.
- Docker28.4.0 정상, 실행 전 기존 running container0, C 가용43,972,800,512 bytes. 이번 새 PG/Anvil 포트 게시·loopback readiness **PASS**. 과거 Windows 게시 장애의 근본 원인 해결을 뜻하지 않는다.
- 새 PG marker `futures-browser-9fe35e84`, DB `exchange_futures_e2e`, port48577. 실제 READ ONLY 확인: 사용자0, futures_journal0, house초기자본10,000,000 mKRW(wei `10000000000000000000000000`), reserved0. 기존 DB/잔고를 초기화한 것이 아니라 새 DB만 초기화했다.
- 새 RPC `http://127.0.0.1:34462`, 실제 chainId31337(0x7a69), 2초 block time. controller `0x730456fB60e6f5417e7499FABDAC6eaC0Cab7AaE`, rules `0x79DE0e9c7A941170400d9734a6851618A34021e7`. 실제 RPC rules() 및 rules.executor() 교차 대응 PASS. policyHash `0xeacf99dcf1f6523cc734124b7f269daf8262245fe79f89f339630bf69e27becb`.
- controller codeHash `0xf56f4bd6435fcc97980e587306efcdb37c8d752fbd46c1c2621038869ff9d29b`, rules codeHash `0x636ccf11703709a25e413eeabadc35722b008dc7c8a3143a73aff909ce918f6a`. 새 runtime 시작 시 실제 RPC deployment identity 검증을 통과했다.
- 실제 Spring Boot localhost30560 + Vite `http://127.0.0.1:47001/#dapp`, 새 DB/Anvil 연결. 웹 proxy의 실제 시장 응답 provider/priceStatus SIMULATED 확인. 실제 제품 Synthetic engine의 1초 commit tick을 사용하며 키움/유료 AI는 OFF다.
- ignored 준비 하네스 `deployment/runtime/dapp-stage2-offline/{run-browser-bv2.py,BrowserBv2Server.java}`. 서버 main classpath에서 test class/resource를 제외하고, 새 DB run_id를 확인한 뒤 빈 fixture 정책만 초기화했다. 사용자 EOA 키를 생성하거나 받지 않는다. 새 relay/priceSigner만 전용 private config에 존재한다. 실제 사용자 지갑에는 아직 Gas 지급/서명/거래 요청 없음.
- 서비스는 소유 parent loop의 STOP 또는 준비 완료 후 최대60분 경계에서 새 web/backend/컨테이너만 종료한다. **독립 watchdog은 아니다.** 데이터/컨테이너/볼륨 삭제 없음. 정지 경로는 해당 새 fixture의 STOP 파일이며 기존 AI STOP과 무관하다.
- private 실행 증거: 새 session/fixture의 run.json, isolation.json, preflight-clock.json, browser-ready.json, backend.ready 및 logs. DB-host worst uncertainty는 기존2초 guard 안이며 정책을 완화하지 않았다.

현재 **READY_FOR_USER_NETWORK_REGISTRATION**에서 대기한다. 사용자가 MetaMask 네트워크를 등록한 뒤에만 새 로컬 사용자 로그인/지갑 연결/직접 서명 OPEN, TOP_UP, CLOSE 인수를 이어간다. 실제 사용자 서명·온체인 사용자 거래·Receipt/journal은 아직 **NOT_RUN**이다. 네트워크 이름은 `TMS B-v2 Local 9fe35e84`, 통화 ETH, explorer 없음이며 실제 자산을 보내면 안 된다.

### 실제 사용자 직접 승인 및 OPEN (동일 새 fixture의 후속)

- 사용자가 MetaMask 네트워크 등록, 새 USER1 가입, 지갑 연결 및 EIP-191 소유 증명을 직접 완료했다고 알렸다. 새 DB의 wallet link1 및 challenge 소비를 확인했다. 지갑 키/seed를 받지 않았다. 연결 완료 안내가 눈에 띄지 않는다는 사용자 보고가 있었으며, 실제 화면 원인을 확정하지 않는다. 현재 브라우저 제어 inventory가 비어 있어 일반 Chrome의 DOM을 직접 확인하지 못했다.
- Faucet 실제1회, 1,000,000 mKRW 지급. 사용자 안내 후 직접 EIP-712 OPEN 승인·제출을 완료했다고 알렸다. 단순 사용자 완료 알림만으로 PASS하지 않고 아래 실제 chain/DB를 재조회했다.
- OPEN operation `3c40d945-8ec6-4d60-8f34-9ccc6918cede`, position `88bde310-5015-4bca-9075-14ed26e51d8a`, txHash `0x3775bfa3b1818d14f0b6e80a9a7c59750210b9ce8f730454a6dc439c7d8cbcc2`.
- 실제 LONG10, entry `74951.79039853` mKRW, quantity `13.34190944182719092` mSEC, collateral100,000 mKRW. operation CONFIRMED / position OPEN HEALTHY / pending null. 잔고총액1,000,000 mKRW·잠금100,000 mKRW. journal RESERVE locked+100,000 및 CONFIRM; wallet/house delta0.
- block597, canonical blockHash `0xee41c46f1f800f226ec8e2725bb9e73dd7295795d53f67d6560d2d54e20fa78a`, fresh Receipt status1 및 해당 block의 hash 일치. rules의 OperationApplied→FuturesReportConsumed→controller UserIntentExecutedV2 3개 순서/주소, tx.from relayer 및 tx.to controller 대조 PASS. 제품 Reconciler의 full calldata/두 서명/digest 검증을 거쳐 DB CONFIRMED됐다. 별도 수집기는 signature/raw calldata/JWT를 출력하거나 저장하지 않는다.
- 사용자 Intent issuedAt1792658705, deadline1792658825. 실제 실행 시장 observedAt1792658731(사용자 Intent 이후26초), SIMULATED 적격 가격 Report로 분리 실행했다. 서버 가격 TTL/신선도 정책 변경 없음.
- private 증거 수집기 `inspect-browser-bv2.py`의 첫 시도는 psql 여러 줄 JSON 파싱 오류, 다음은 이벤트 순서 가정 오류였다. 계약 FuturesEngine.sol의 emit 순서(125/127행)로 수집기만 정정했으며 실패 증거를 삭제하지 않았다. 최종 `3c40d945-8ec6-4d60-8f34-9ccc6918cede-canonical-085029.json` PASS. 이는 실제 거래 실패나 재전송이 아니다.

### 후속 draft 재확인 UX 결함 및 최소 보완

- 사용자에게 TOP_UP을 안내했으나 다음 DB 관측은 새 OPEN DRAFT1개(미제출/만료)이며 TOP_UP operation은0이었다. 기존 성공 OPEN은 그대로 유지됐다. 사용자 화면에서 정확히 어떤 입력/클릭 순서였는지는 관측하지 못했으므로 사용자 과실로 단정하지 않는다.
- 실제 코드/무과금 재현에서 prepare를 두 번 누르면 유효한 미제출 DRAFT에도 일반적인 '이전 요청 조회' 오류가 발생하고, resolve는 '아직 서명 제출 전'을 반환한다. 미제출 draft와 제출 operation을 혼동시키는 UI 안내/재확인 결함이다.
- 같은 메모리 draft·같은 승인 조건·같은 owner·유효기한이면 typed data를 재검증하여 같은 draft를 다시 표시한다. 추가 POST/새 키/서명 요청0회. 조건이 다르거나 화면을 떠나 원문을 잃은 경우에는 미제출 상태와 만료 시각을 명시하며 임의 잠금을 삭제하지 않는다. 만료 후에만 명시적으로 새 draft를 만들 수 있다. SUBMITTING/SIGNED/SUBMITTED 원칙 변경 없음.
- 안내문은 표시될 때 해당 영역으로 스크롤해 사용자가 결과를 볼 수 있게 했다. 자동 서명/거래는 추가하지 않았다. Vite 변경으로 실제 웹이 재로딩될 수 있음을 먼저 안내했다.
- 보완 후 Node146 PASS, build/Chrome 최종 결과 및 재검토는 다음 누적 기록으로 확정한다. 백엔드/계약/DB 정책 변경 없음. 실제 TOP_UP/CLOSE 및 전체 B-v2 감사는 아직 미완료다.

- 보완 최종: Node146 PASS/build PASS/Chrome Mock PASS(`run-eZUcPh`). 독립 재검토 필수 수정 없음, 별도 재실행 지갑19 PASS. 새 웹 sourceHash `284141e90e4e7280580af297e95c966d44f71a85d13ab9674e71460f25639596`. 실제 OPEN 성공은 이 UX 보완 이전 웹 해시의 실행이며 보완 후 전체 사용자 거래 PASS로 소급하지 않는다. 08:58:29 UTC 실제 웹 proxy 시장 SIMULATED 응답 유지. productHash/백엔드/기존 manifest/DB상태 변경 없음. 다음 수동 TOP_UP 전에 현재 동작과 담보 전후를 다시 확인한다.

### draft 503 / RPC 경계 조사

- 사용자가 '조건 고정 및 draft 확인'에서 `503 FUTURES_RPC_UNAVAILABLE`을 보고했다. DB에는 TOP_UP draft/operation0, 기존 OPEN 정상·pending null이며 예약 추가 없음. 요청을 재전송하거나 원래 거래를 변경하지 않았다.
- AuthorizedV2Config.ChainAccess의 verify/eoa/nonce는 IOException을 원인 없이 해당 Failure로 변환한다. GlobalExceptionHandler는 503 응답을 만들지만 이 처리된 오류를 로그에 기록하지 않는다. 실제 backend.private.log에 exception/error 흔적0. 따라서 과거 사용자 요청이 verify/eoa/nonce 중 어디서 어떤 IOException을 냈는지는 **기존 로그만으로 확인 불가**다. 로그가 없다는 사실이 성공이나 사용자 잘못을 뜻하지 않는다.
- 새 readonly `ProbeBrowserV2Rpc.java`로 제품 Gateway와 3초 callTimeout을 재사용했다. 별도 기본 네트워크 JVM의 초기2회 verifyDeployment는 약3.2초 InterruptedIOException, 단계 관측에서 첫 eth_chainId의 headers 응답 전 timeout을 관측했다. 추가 단계 관측1회는 첫응답2891ms로 간헐 PASS, 나머지 호출1~3ms였다. 이 별도 JVM에는 처음에 실제 서버의 IPv4 옵션이 없었으므로 원래 사용자 요청과 동일한 실행 환경/원인으로 단정하지 않는다.
- Windows Python host eth_chainId31ms, 새 Anvil 내부 chainId 성공. 실제 서버와 같은 프로세스 한정 IPv4 옵션을 준 마지막 비교1회: first eth_chainId49ms, verifyDeployment298ms, eoa2ms/true, nonce5ms/1 PASS. 실제 서버에는 시작 때부터 그 옵션이 적용돼 있었으므로 이번 비교를 서버 설정 수정/근본 원인 해결로 주장하지 않는다.
- 확인 범위: RPC transport의 간헐 지연은 별도 JVM에서 관측됐으나 원래503의 정확한 단계/근본 원인은 미확정이다. 체인 identity·EOA·nonce의 현재 readonly 검증은 PASS. timeout/정책 완화, 전역 네트워크 변경, Docker/WSL 재시작, raw 재전송 없음.
- 후속 안전 관측 필요: 제품의 RPC 오류 변환에 고정 stage·예외 분류·경과시간·상관ID를 기록하되 요청본문/서명/JWT/비밀설정은 제외해야 한다. 이번 조사에서 제품 로그 코드를 변경하거나 원본 실패를 성공으로 덮어쓰지 않았다. 다음 수동 draft 확인은 같은 조건·같은 키의 명시적 재확인만 허용하고, 서명/submit은 별도로 확인한다.

### TOP_UP 직접 서명 결과 및 반복 RPC 오류로 중단

- 이후 사용자가 명시적으로 같은 요청을 재확인해 실제 TOP_UP DRAFT 생성에 성공했다. 대상은 기존 position, before100,000/after110,000 mKRW, qmin=qmax `13341909441827190920` wei, expires `2026-10-22T09:17:04Z`로 서버에서 확인했다. 사용자가 MetaMask 서명·제출을 직접 완료했다고 알렸다.
- operation `fc3d4a18-348a-4688-b832-d09c24428a58`: **REJECTED / PRICE_CHANGED**. 실행가격 고정 후 Worker 전송 전 가격 변화 검사가 거절했다. 이를 정상 TOP_UP 성공으로 기록하지 않는다. 해당 operation의 futures_transactions0, txHash/Receipt 없음. 기존 OPEN position OPEN/HEALTHY, version1, 담보100,000 mKRW/수량 불변. TOP_UP journal RESERVE locked+10,000 → RELEASE locked-10,000으로 반환됐으며 최종 locked100,000, 총잔고1,000,000 mKRW다.
- 재승인이 필요한 새 거래라는 점을 알리고 사용자가 수동 진행했으나, draft 단계에서 다시 `503 FUTURES_RPC_UNAVAILABLE`을 보고했다. 더 이상 반복 재시도·서명이나 CLOSE를 안내하지 않고 **BLOCKED_RPC_UNAVAILABLE**로 중단했다. timeout이나 시장 가격 gate를 완화하지 않았다.
- 중단 직전 READ ONLY 확인: SIGNED/SUBMITTED/REVIEW_REQUIRED0, TOP_UP tx0. 새 `browser-final-state.json`에 서명/원문 없이 operation/position/잔고/house/journal을 보존했다. 해당 새 fixture에만 별도의 STOP 파일을 생성했다(기존 AI STOP과 무관). parent가 STOP_FILE을 인지하여 소유 web/backend를 종료하고 새 PG/Anvil만 정지한다. 컨테이너·볼륨·DB·chain·증거는 삭제하지 않는다. 보호 기준156개 SHA-256 변경0.

| 실제 인수 항목 | 최종 판정 |
|---|---|
| 독립 소스 검토 및 UX 보완 재검토 | PASS, 지갑19개 별도 실행 PASS |
| 사용자 MetaMask 네트워크 등록/지갑 연결/EIP-191 소유 증명 | PASS — 사용자 직접 동작 보고 + 실제 DB/서명 검증 결과 |
| Faucet1회 | PASS — 실제1,000,000 mKRW |
| 사용자 직접 EIP-712 OPEN | PASS — 실제 canonical3events/DB CONFIRMED |
| 사용자 직접 TOP_UP 정상 정산 | 미완료 — 실제 요청은 PRICE_CHANGED로 REJECTED, 예약 반환 PASS |
| TOP_UP 후속 draft | BLOCKED — 반복503 RPC_UNAVAILABLE |
| 사용자 직접 CLOSE | NOT_RUN — 실패 경계에서 중단 |
| 이번 웹에서 자동 마진콜/60초 청산 | NOT_RUN — 기존 개별 PG/Anvil PASS와 구분 |
| 실제 지갑의 거절/계정변경/잘못된chain/만료서명 및 권한·무서명 EVM 거부 | NOT_RUN — 기존 Mock/과거EVM 결과를 승계하지 않음 |
| 전체 B-v2 감사 coverage | INCONCLUSIVE / 미구현 경계 유지 |

현재 실제 브라우저 지갑 인수는 **PARTIAL / RPC 안정성 BLOCKED**이며 완료 선언하지 않는다. 다음 필수 작업은 (1) 원인·stage를 삼키지 않는 비밀정보 없는 서버 RPC 관측, (2) 실제 서버 프로세스의 RPC 지연 원인 확인, (3) 검증된 새 source identity/새 격리 run에서 사용자 TOP_UP/CLOSE 및 남은 실패 경계 재인수다. 임의 TTL 증가·보안/시장 검사 삭제·자동 사용자 재서명·기존 fixture 초기화는 대안으로 사용하지 않는다.

- 종료 검증 완료: parent exit0, 소유 PG/Anvil 각각 Exited(0), 새 web/backend 종료. 기존357컨테이너의 ID/이름/이미지/상태 분류 변경0, 새 컨테이너2개 보존. 기존 보호156파일 hash 변경0. `git diff --check` PASS. 원격·로컬 commit/merge/push/외부배포 및 유료 호출 없음.
# RPC / PRICE_CHANGED 후속 조사 (2026-10-22)

[단계별 원인 조사·진단 구현·새 fixture 결과](dapp-b-v2-rpc-price-investigation-2026-10-22.md)를 참조한다. 직접 JUnit 선물158 PASS/12 SKIP/0 FAIL. 시장/TTL/raw 정책은 유지했다. 새 실제 서버 관측은 Vite `listen EFAULT`로 BLOCKED이며, 기존 RPC503의 원인 해결 또는 실제 TOP_UP/CLOSE 성공으로 해석하지 않는다. 아래 기존 이력은 그대로 보존한다.
