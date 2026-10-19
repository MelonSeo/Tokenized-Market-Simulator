# Futures 제품 Synthetic/Chrome 및 CONFIRMED 재시작 검증 — 완료

## 기준과 분리

감사 커밋 `ded9b79`, Scenario 빌드 수정 `f5c5a28` 이후의 테스트-only 마일스톤이다. 제품 Java/Solidity 정책은 변경하지 않는다. 기존 60초 유예/30초 견적 및 raw/hash/nonce 정책을 유지한다. 기존 Gate7 REVERTED 별도 JVM 증거와 정확한 revert 원인 미확정 기록도 보존한다.

## 신규 CONFIRMED 두 JVM 시나리오

`FuturesConfirmedRestartPostgresAnvilE2ETest`, `phase-a.py --case futures-confirmed`:

1. 신규 격리 fixture의 worker만 닫고 OPEN 의도를 저장한다.
2. 정상 RPC로 실제 broadcast한 뒤 응답 유실을 주입한다. signed raw는 기존 store에 선커밋되고 DB는 SIGNED를 유지한다.
3. JVM1에서 성공 Receipt가 실제 채굴됐음을 확인하되 정산하지 않는다. 채굴 시각≤원래 validUntil을 직접 확인한다. 체인 시간 이동/TTL 연장/재서명은 없다.
4. 별도 JVM2의 정상 worker가 원래 transaction을 CONFIRMED로 정산하는지 확인한다. PID 차이, 동일 raw digest/hash/nonce/blockHash, canonical Receipt, journal1회, 중복 reconcile 시 잔고·잠금 불변을 검증한다.

최초 신규 child `futures-confirmed-d3c42fa5`는 테스트의 long/BigInteger assertion 타입 오류로 compileTestJava에서 중단됐고 실제 prepare/recover는 미실행이다. 증거는 보존했다. 타입 비교만 고친 신규 child에서 재실행 중이다. 이를 제품이나 체인 결함으로 분류하지 않는다.

## 실제 제품 Synthetic/Chrome

`FuturesBrowserPostgresAnvilE2ETest`, `deployment/futures-browser.mjs`, case `futures-browser`:

- 실제 제품 `SimulatedPriceProvider`/`SyntheticMarketEngine`/candle 저장/commit 후 snapshot·공개 WS·Worker 경로를 사용한다. 기존 @Primary Scenario fake provider를 import하지 않는다.
- 테스트가 신규 fixture의 private checkpoint에 사전 정의한 BASE/급락/급등/회복 값을 공급한 뒤 제품 provider가 새 tick을 생성·커밋한다. 일반 사용자 가격 API, 직접 위험 이벤트 주입, chain time warp는 없다. 실제 tick은 seed·시간에 따른 engine 산출값이며 시나리오 target을 체결 가격으로 임의 고정하지 않는다. 스케줄러의 실제 시각 jitter까지 완전히 동일한 경로라고 주장하지 않는다.
- 실제 Chrome은 새 private 프로필과 loopback Vite proxy를 사용한다. Vite config import는 끄고 envDir를 새 빈 evidence 경로로 지정해 기존 .env를 import하지 않는다. API/STOMP는 실제 서버이며 Mock 응답을 주입하지 않는다.
- 로그인→Faucet→Long2/추가담보/종료→Short5/종료→실제31초 preview 만료→Long10/Short10 자동 마진콜·60초 유예·청산→가격회복/추가담보로 위험해소→최종 portfolio·개인 WS·모바일/desktop 화면을 검증한다.
- Java가 실제 Receipt/Event의 call/liq block timestamp 차이≥60, 신규 observedAt≥deadline, DB 잠금0/journal 보존 및 Futures audit 완전coverage/MATCH·기존 reserve MATCH를 확인한다.
- Chrome 단계 최대465초, Java 제어 최대480초. 새로운 fixture만 사용하며 실패 fixture를 초기화/재사용하지 않는다. 테스트 계정과 모든 원본 증거는 Git 제외 private 경로에만 둔다.

## 현재 결과

- 신규 두 테스트 클래스 `compileTestJava --offline` PASS. Python harness/clock18 PASS, Node syntax check PASS. 최초 Node syntax 확인 명령은 cwd가 deployment인데 경로를 deployment/로 중복 지정해 module-not-found였으며 올바른 경로로 새로 확인했다.
- CONFIRMED 실제 두 JVM: 실행 중. 실제 제품 Synthetic/Chrome: 아직 NOT_RUN.
- 독립 검토와 최신 전체 회귀, 로컬 커밋은 후속이다. 유료 AI/실제 공급자/기존 환경 변경은 없다.

## 신규 실행 및 최초 검토 보완

- `futures-confirmed-05ae4a77`: **prepare JVM1 PASS + recover 별도 JVM1 PASS**, FAIL/SKIP0. 기존 REVERTED 복구와 달리 실제 성공 Receipt를 JVM1에서 확인하고 DB SIGNED를 남긴 뒤 JVM2가 동일 raw/hash/nonce를 CONFIRMED로 단일 정산했다. 정확한 실패 원인을 TTL로 추정하는 시나리오가 아니다.
- 최초 제품 브라우저 fixture `futures-browser-02208017`는 **FUTURES_BASELINE_CUT_CHANGED로 FAIL**, Chrome 실행 전 단계다. 2초 주기 채굴 중 read-only cut 경계가 움직였고 제품이 fail-closed했다. 기존 baseline을 재생성/초기화하지 않았다. 아직 INSERT되지 않은 신규 baseline 후보의 CUT_CHANGED만 최대4회 재조회하도록 테스트를 보완했다. 다른 오류는 재시도하지 않는다.
- 최초 독립 검토 `review_futures_product_browser` 필수2건: screenshot/REST만으로 UI 및 해당 포지션 WS 반영을 보장하지 못함; Node 실패 시 Chrome 하위 프로세스 정리와 공통 deadline 미흡. 실제 DOM의 방향·배수·상태·진입가·담보·확정 손익을 REST와 비교하고 positionId/operationId별 확정 개인 이벤트를 확인하도록 보완했다. 동적 평가가격의 시각 차이를 정수 정합성 결함으로 오인하지 않는다.
- Node의 deadline/abort를 sleep/poll/fetch/CDP 대기에 공유한다. Java는 이번 Node의 descendant PID+startInstant만 수집해 bounded graceful→강제 종료하고 cleanup 증거를 남긴다. 기존 Chrome이나 공유 서비스는 종료하지 않는다. cleanup 실패는 원래 실패에 suppressed로 보존한다. 새 Node parent+child만 사용하는 실제 OS cleanup 단위 테스트도 추가했다.
- 수정 후 actual browser 재실행 및 재검토는 아직 진행 전/중이며 PASS로 주장하지 않는다.

### Chrome 후속 실패 증거 (전체 PASS 아님)

- `futures-browser-510d4763`: 브라우저 WAIT_TIMEOUT, 단계/오류 타입이 최초 runner에 없어 세부 원인 미확정. own-tree42개 추적/잔존0. 제품 Vite 설정과 비교해 runner가 SockJS의 `global: globalThis` 매핑을 누락한 사실을 확인하고 동일 매핑을 보완했다. 이 구성 차이를 해당 실패의 직접 runtime 증거로 단정하지 않는다. 이후에는 단계와 허용된 오류 분류만 기록한다.
- `futures-browser-1e61f54b`: 실제 Chrome 로그인/Faucet PASS, 최초 OPEN은 **REJECTED/PRICE_CHANGED/OPEN_FAILED**로 중단. 신규 fixture PG만 소유권 확인 후 일시 기동해 read-only 조사: Futures transaction0, mKRW1,000,000/locked0, mSEC0. PG를 다시 정지했다. 현재 제품의 가격 재확인 정책을 바꾸지 않고, runner에서 **확정 unsigned PRICE_CHANGED + txHash/nonce=null**인 경우만 새 preview/명시 확인을 최대3회 허용한다. SIGNED/SUBMITTED/REVIEW_REQUIRED/REVERTED는 재주문하지 않는다. 실패 OPEN_FAILED는 삭제하지 않고 감사 대상에 남긴다. own-tree19개 추적/잔존0.
- `futures-browser-189ed9f5`: 실제 Chrome 로그인/Faucet, Long2 개설·추가담보·정상 종료, Short5 개설·정상 종료, 실제31초 preview 만료, Long10 개설 및 자동 마진콜까지 실행했다. private18 events, 명시적 가격 재확인2건. 이후 자동청산 대기 중 generic BROWSER_STEP_FAILED로 중단했고 timeout 플래그는 없었다. 원래 오류 메시지/타입을 폐기한 runner 때문에 정확한 원인은 **미확정**, 청산 PASS로 주장하지 않는다. own-tree42개 추적/잔존0. 다음 신규 실행에는 고정 errorType/causeCode/자체 코드 위치 및 안전한 REST/DOM 실패 snapshot만 보강했다. 원문 오류·인증값은 저장하지 않는다.
- DOM 검증은 라벨별 exact 값 비교로 보완했으며 잘못된 0값/방향/배수/수량/진입가/증거금/유예 시각, unsigned 재확인 경계, 비밀 오류 폐기에 대한 Node 오프라인7 PASS다. 실제 OS owned Node parent/child cleanup 단위1 PASS다. 기존 실패와 현재 actual browser 재실행은 구분한다.

- 1차 재검토는 field 전체 includes 검색의 0값 혼동만 남았다고 지적했다. `futures-browser-assertions.mjs`로 dt/dd 라벨별 exact 비교 및 h2 exact 비교를 분리했고 현재 오프라인 **8 PASS**다. 단위 테스트는 실제 Chrome/체인 성공을 의미하지 않는다. 마지막 2차 재검토는 최신 실제 실행/최종 source가 정리된 뒤 수행한다.
- `futures-browser-6f5fbdd6`: START 이전 PREPARE에서 FAIL, generic 분류만 있어 원래 runtime 원인은 미확정이다. 설치된 Vite 소스의 `startServer`를 읽어 **port=0은 임의 포트가 아니라 기본5173으로 귀결될 수 있음**을 확인했다. OS loopback probe에서 free port를 선택하고 명시 포트/strictPort를 사용하며 경쟁 시 fail-closed하도록 수정했다. 기존 listener를 종료하지 않는다. envDir/cacheDir 모두 새 private 경로로 분리했다.
- `futures-browser-43b1cb2e`: 격리 webPort63441로 로그인/Faucet 이후 명시적 preview3회 모두 가격 변경으로 확정 거절되어 **PREVIEW_RECONFIRM_EXHAUSTED FAIL**. OPEN_FAILED2개는 보존되며 거래 성공으로 세지 않는다. 재확인 루프가1초 가격 갱신 주기의 같은 경계에 반복 진입할 수 있어 새 실제 commit tick을 관찰한 직후 UI preview를 요청하도록 테스트 timing을 보완했다. 가격 동결/미래 허용·5초 fresh·30초TTL·0 drift·재연결 정책 변경은 없다. 이 시나리오는 짧은 시간의 명시 확인 성공을 검증하며, 사람이 가격을 오래 검토하는 동안 반복 재확인이 필요한 UX 한계는 남는다.
- Windows C 가용9,629,487,104bytes/Docker Server28.4.0 정상 응답을 새로 확인했다. 저장공간 문제를 해결했다고 재선언하지 않는다. 최신 runner는 fixed OS/network cause code·자체 코드 위치만 기록하며 원문 오류/개인키/인증값을 저장하지 않는다. 이전 private JSON 조회의 PowerShell 기본 인코딩으로 Unicode parse 오류가 있었고 이후 조회는 UTF-8을 명시한다.

## 최종 신규 실제 제품 Chrome 실행 PASS

`phase-a-20261019094753-bffb06d2/futures-browser-c1767e1e`: **실제 PostgreSQL/Anvil/Chrome1 PASS, FAIL/SKIP0**. 해당 소스의 productHash는 앞선 감사와 동일한 `6c5c6930acb3d6dc4d17b98e20dd1acfea4a4d0592274fa4eb96dbcc97ba3246`이다(이번 변경은 테스트/harness만). 새 run은 기존 실패 fixture를 초기화/재사용하지 않았다.

- 실제 제품 `SimulatedPriceProvider`, Chrome loopback web51523, private events42, 사용자 포지션6. 로그인/Faucet, Long2 추가담보·종료, Short5 종료, 실제31초 preview 만료, 양방향 자동청산, 유예 중 가격 회복·추가담보, desktop/mobile DOM·숫자/화면 확인8단계를 실행했다. 명시적 unsigned 가격 재확인1회는 실패 이력으로 보존되며 signed 불확실 거래를 재주문한 것이 아니다.
- Long: call block timestamp1792408815 → liquidation1792408879, **64초**, tx `0x0bdd5c6a506056b3427b6983c9c478447d73b3ed4b23ef2ea408f2326f306ee6`.
- Short: call1792408884 → liquidation1792408961, **77초**, tx `0xb3804b8b087a0093fe0476399c1574a36ba66d625662a217b1bd03de7c05c9c5`.
- 둘 다 실제 canonical 성공 Receipt/Event2, observedAt≥deadline, deadline 이후 새 가격으로 검증했다. 시간 warp/유예 단축/견적TTL 완화는 없다.
- Futures audit **MATCH**, operations21/transactions19/verified19, block61~177, events38/verified38/**complete=true**. 기존 reserve **MATCH**. 최종 lockedAmount0 및 wallet_delta+house_delta합0, immutable baseline 불변. baseline 후보 재시도0.
- Node와 자신이 만든 하위44개만 PID/startInstant로 추적한 cleanup, 잔존0. 새 PG/Anvil은 harness의 기존 ownership 검증 후 정지, DB/state/logs/screenshots 보존. 보호 환경·원격 변경/외부 API·유료 AI 없음.
- desktop/mobile 스크린샷은 실제 포지션 카드를 scrollIntoView한 뒤 저장한다. 기존189ed9f5의 최초 screenshot은 viewport 상단 위주였으므로 실제 마진콜 수치를 시각 확인한 증거로 확대하지 않는다.

최종2차 재검토 `review_futures_product_browser`: 발견된 필수 수정 없음. 실제 소스·테스트·문서를 읽은 별도 검토이며 직접 테스트/DB/private 증거를 실행·조회한 검토는 아니다. 새 session `phase-a-20261019112424-f30234f2`(sourceHead ded9b79, 동일 productHash)의 전체 회귀는 backend632중 **584 PASS/48 SKIP/0 FAIL/0 ERROR**다. AI 연결/실제 LLM 평가는 아직 미완료이며 위 PASS에 포함하지 않는다.
