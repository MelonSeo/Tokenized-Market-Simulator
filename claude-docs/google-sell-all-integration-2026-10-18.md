# Google 인증·전량 매도 최종 로컬 통합

> 2026-10-18 KST. 사용자가 실제 인증의 주요 흐름과 취소/중복 거부 관측을 수용하고 로컬 커밋·melon-init 병합 및 새 회귀를 승인했다. 실제 계정 인증과 무과금 fixture, 이전 결과와 이번 병합 후 실행을 구분한다.

## 시작과 변경 구분

- 원본 루트 `feat/google-auth` HEAD와 `melon-init`은 모두 `a98ea4b47fdb66484efdc31d66ffddc4f2b75085`였다. 시작 index는 비어 있었다.
- Google: GIS ID Token 검증, LOGIN/LINK challenge, 내부 userId JWT, 명시 연결, 서버 STOMP 만료, 웹 로그인/연결과 관련 테스트. [구현](google-auth-implementation.md), [실제 인증](google-auth-live-acceptance-2026-10-17.md), [최신 수동 인수](google-auth-finalization-and-integration.md).
- 전량 매도: portfolio의 정확한 가용량 문자열, SELL 전량 입력 및 경합 방어, 별도 PG/Anvil·웹 검증. [별도 구현](sell-all-precision-implementation.md).
- 미추적 NXT 애프터 보고서와 implementation-log의 NXT 후속 구간은 사용자 변경으로 이번 커밋에서 제외한다. 실제 `.env`/키/JWT/Google credential/runtime/DB·Anvil state/캡처·로그·빌드 산출물을 추가하지 않는다.
- 작업 파일 54개의 시작 byte hash와 무관한 NXT log 구간을 ignored private 경로에 보존했다. Google-only 분리는 작업 파일을 변경하지 않고 index blob만 구성했다.

## 커밋 및 검토 경계

Google 기능 → 전량 매도 → 실제 인수/통합 문서 순서로 분리한다. main.js/browser-smoke/phase-a/PhaseAFixture/README를 index에서 분리해 Google 커밋에 전량 기능의 미해결 참조를 남기지 않는다. 통합 문서 커밋에서도 NXT 구간은 제외한다.

독립 `review_google_sell_integration` 최초 소스 검토 및 Google-only 40파일 index 재검토: **발견된 필수 수정 없음**. 검토자는 테스트/Docker/DB/private 원문에 접근하지 않았으며 최종 병합 소스와 새 회귀 결과는 별도 재검토 대상이다. 기존 challenge 소비·409 상세 매핑 한계는 유지한다.

## 이번 새 실행 결과

- Google-only index export 새 실행: backend Google/WS 선택56개 중 **54PASS/2SKIP/FAIL0/ERROR0**, 웹 **93PASS/FAIL0/SKIP0**, Chrome desktop/mobile·offlineGIS PASS. 2개SKIP은 opt-in Google PG/Anvil·migration이며 실제 신규 PG 실행은 병합 후 따로 수행한다. 전체 백엔드 회귀가 아니다.
- Google commit `bc02330`, 전량 매도 commit `16b8988`. 전량매도15파일 index도 독립 재검토 **발견된 필수 수정 없음**. 두 커밋 전 staged diff/check 및 경로·민감 문자열 패턴 검사를 통과했고 snapshot 대상 작업 파일54개 byte hash는 변경되지 않았다.
- `c6a7b60` 문서 커밋 뒤 원본 `melon-init`에 `--no-ff` 일반 merge: **`426cb0b4865fd3cf9b5ac8ef2a1acd0e27e9b248`**, 부모는 `a98ea4b`/`c6a7b60`이며 feature와 merge의 Git tree 동일. 충돌 없음, push 없음.
- NXT log만 이름 있는 전용 stash로 잠시 보관하고 merge 후 apply했다. 원문을 LF 정규화해 완전 일치, NXT 미추적 보고서 byte hash 동일을 확인했다. 안전용 stash는 삭제하지 않았다. NXT 두 변경은 미커밋으로 유지한다.
- 병합 HEAD의 신규 session **`phase-a-20261017180337-f3d6764c`**에서 전체 회귀를 새 실행: backend **533중495PASS/38SKIP/FAIL0/ERROR0**, 웹 **107PASS/FAIL0/SKIP0**, Chrome desktop/mobile·전량입력·offlineGIS fixture PASS, Foundry **36PASS/FAIL0/SKIP0**. `deployment/`의 harness/clock 단위도 새 **15PASS**. Google RSA/loopback JWKS·서버 STOMP 만료·null loginId와 전량 REST/JS 경계는 backend/web 회귀에 포함된다. 38개 opt-in SKIP을 PASS에 포함하지 않으며 필요한 새 PG fixture 결과는 아래에 별도 추가한다.

## 병합 소스의 신규 PostgreSQL/Anvil 실행

| 독립 fixture / 검증 | 이번 결과 | 확인 범위 |
|---|---|---|
| `google-a051627d` | 1 PASS / 0 SKIP | 실제 PG lock·unique/rollback·계정 연결·기존 자산/이력·Google-only Synthetic BUY/SELL·두 대사 |
| `google-migration-d65b9cfc` | 1 PASS / 0 SKIP | legacy users 행·기존 비밀번호 해시 보존, 반복 additive SQL, 과거 DB Faucet 예산 미활성 유지, 신규 Google 0원 사용자 |
| `sellall-5cfd3b4b` | 1 PASS / 0 SKIP | 부분 SELL 잠금 제외 가용 문자열·전량 SELL 뒤 잔여/잠금0·Receipt/Trade·두 대사 MATCH/coverage |
| `conditional-e42df94f` | 1 PASS / 0 SKIP | 조건부 가격·실행/취소·receipt·복구·정체 관측과 자산 불변 |
| 같은 conditional fixture, 별도 JVM | 1 PASS / 0 SKIP | PostgreSQL snapshot 재시작 지속성 |
| `faucet-cf92a75a` | 1 PASS / 0 SKIP | 실제 PG 한도·예산·멱등성 경쟁/rollback·KST 날짜 경계·거래/대사 호환 |
| `reserve-7f13ada3` | 1 PASS / 0 SKIP | 장부-체인 대사 정상·불일치·판정불가 및 실제 DB/체인 cut 경합 |
| `audit-76283827` | 1 PASS / 0 SKIP | Order/Trade/Quote/Receipt/Event 양방향 감사·고아 quote·불일치·coverage·cut 변화 |

모두 새 fixture의 identity·소유권·loopback DB/RPC·기존2초 시각 preflight·현재 source/tools image 검사를 통과한 뒤 실행했다. **독립 fixture7개·별도 JVM 재시작1개, 총8PASS/FAIL0/ERROR0/SKIP0**이다. 위 실제 PG/RPC 결과와 H2/Mock·Chrome offline fixture·이전 Google 계정 실인증은 서로 다른 증거다. 전체 회귀의38SKIP 집계는 바꾸지 않는다.

실행 명령은 `python deployment/phase-a.py init`, 새 session의 `regression --session ...`, `fixture --session ... --case google|google-migration|sellall|conditional|faucet|reserve|audit`이다(각 case는 독립 호출). conditional은 성공 후 별도 JVM restart를 이어 실행한다. `python -S -m unittest test_phase_a test_phase_a_clock`는 deployment 디렉터리에서 실행했다. XML/summary·메타데이터·로그·시각 preflight와 DB/chain은 새 ignored session의 evidence/logs/fixtures에 보존한다. 제품 거래·Oracle·복구 정책이나 컨트랙트는 이번 Git 통합에서 추가 변경하지 않았다.

최종 수집 확인: 테스트 HEAD와 실행 당시 HEAD 모두426cb0b, session product hash 일치. 7개 fixture의 새 PG/Anvil **14개 모두 exact label 확인 후 정지 상태**이며 데이터는 보존했다. 시각 오차+왕복 불확실성의 최댓값은 **1.067438초**로 기존2초 guard 안이다. 통합54파일 경로·PEM/JWT/API key 패턴 검사 및 NXT log/보고서 제외 확인을 마쳤다. 일반 문서에는 인증 원문/비밀값을 기록하지 않았다. private `final-integration-check.json`은 새 session evidence에만 저장했다.

## 남은 한계와 최종 검토

- 실제 Google 취소의 개별 challenge 소비 상태, 409의 정확한 오류 코드/요청자 매핑은 사용자 합의대로 직접 미검증으로 유지한다. 사용자 실제 동작/서버 상태 확인을 이 세부 항목의 직접 검증으로 확대하지 않는다.
- Chrome desktop/mobile 회귀는 실제 Chrome에서 실행한 **오프라인 HTTP/WS/GIS fixture**다. 실제 PG/Anvil은 별도 JVM/Java HTTP+RPC이며 하나의 Chrome→실DB→체인 E2E로 합치지 않는다. 이전 실인증과 Synthetic 브라우저 실행도 이번 새 실행으로 집계하지 않는다.
- 실제 키움 KRX/NXT 제품 거래 인수, 유료 AI Phase C, Android 기기, 외부 배포는 NOT_RUN. refresh/revoke·이메일 인증·계정 복구·Google 해제는 기존 후속 범위다. 새로고침 시 메모리JWT 소실 동작을 이번에 바꾸지 않았다.
- 최종 독립 재검토 `review_google_sell_integration`: **발견된 필수 수정 없음**. 검토자가 Git의 melon-init HEAD/merge 부모·feature와 동일 tree, 이후 문서만 변경된 사실을 직접 확인했다. 소스·수치의 문서 정합성, 실제/fixture/미검증 구분 및 신규 Phase B 제약을 재검토했다. 테스트를 독립 재실행하거나 private/DB/서비스를 직접 확인한 것은 아니다.
- 마지막 커밋은 이 결과를 기록하는 문서4개만 포함한다. 제품 검증 실행 기준 HEAD는426cb0b이며 이후 문서 커밋을 제품 재실행으로 표현하지 않는다. 최종 HEAD는 `git log -1`로 추적하며 해당 커밋과426cb0b 사이에 제품 파일 변경이 없어야 한다. NXT 미추적 보고서와 log 구간은 계속 미커밋으로 보존하고 원격 push는 하지 않는다.

## 환경 보존과 후속 Phase B

기존 Google5173/5174, KRX, playground의 컨테이너·image·DB·chain·immutable baseline·manifest·private evidence는 유지한다. 이번 검증은 별도 새 Synthetic fixture만 사용하고 실제 키움/Toss/Google 인증/유료 AI/배포는 실행하지 않는다. Google의 실인증 PASS는 사용자가 수행한 이전 실행의 증거이며 이번 fixture 회귀와 합치지 않는다.

병합 checkout으로 기존 LF 파일들이 Windows CRLF로 바뀌었다. 시작 snapshot과 최종 index 사본을 대조해 제품/테스트 변경파일44개는 줄바꿈 정규화 시 동일함을 확인했다. 준비된5174의byte source hash guard는이를감지해중단했다. 별도읽기검사에서는기존image ID·mount소유권일치를확인했다. guard를끄거나manifest를갱신하지않았으며이제5174를현재checkout과동일byte환경으로자동재사용하지않는다. 기존KRX는별도보존worktree와구image기준이며신규Google/전량코드를포함하지않는다. 새PhaseB환경이필요하다는원칙은유지한다.

월요일 Phase B는 통합 후 최신 `melon-init`의 HEAD/tree/source hash를 고정한 **신규** 환경에서 준비한다. 신규 거래 DB·persistent Anvil·키·계약·immutable baseline·고유 backend/web/tools image와 전용 secret을 사용한다. 기존 KRX manifest/sourceHead/sourceHash/image ID를 덮어쓰지 않고, 기존 KRX 성공을 최신 기능 인수로 간주하지 않는다.

준비 순서: Docker/여유 포트·자원 읽기 확인 → 신규 run·고유 project/network/volume·키 생성 → 전용 Anvil 계약·operator/Vault 유동성 준비 → 빈 사용자 장부·기준점 → 소스/image/owner/clock/canonical blockHash 검증 → 전용 키움 secret 사용자 입력 → 독립 10분 watchdog/egress 차단 사전 검증 → 승인된 KRX 장중 실행. 실제 공급자 연결 전까지 OAuth/REST/WS 자동 요청을 금지하고, 호출/재연결 범위·IP/동일계정WS 전제 및 실행 시간을 별도 확인한다.

구체적인 선행 점검: `deployment/kiwoom-e2e-prepare.py`는 실행 때 새 run을 생성하고 initializer를 내부 network의 Synthetic로만 띄우며 live 설정을 별도 생성하는 기존 도구다. 반면 `kiwoom-live-run.py:OWNER`와 `kiwoom-e2e-safety.py:OWNER`는 **구 KRX run에 고정**돼 있다. 따라서 새 run 생성만으로 기존 live 명령을 곧바로 실행할 수 없다. 다음 준비 작업에서 신규 run 명시 인자/소유권·manifest·image·source 검사를 보존하는 최소 harness 보완과 로컬 안전장치 재검증이 선행돼야 한다. 기존 OWNER를 새 값으로 무작정 치환하거나 기존 manifest를 덮어써서는 안 된다. 이번 통합에서는 이 도구를 수정·실행하지 않았다. `kiwoom-live-browser.mjs`는 새 환경의 정확한 문자열/전량 버튼과 handler 준비 대기를 다시 확인한 뒤 사용한다. 포트·runId는 실제 충돌 조사 후 할당하며 기존 환경의 주소나 키를 복사하지 않는다.

실행 시 current generation 정상0B/290=2·LIVE·fresh/mockQuoteAllowed, UNKNOWN 허용·CLOSED 우선·견적 전후 generation 검증은 유지한다. Faucet→BUY→SELL→receipt/event·DB·개인WS·portfolio·두 대사와 coverage를 확인한다. Phase C는 최신 승인 지식/manifest/index·별도 AI DB와 호출 수/비용 상한 승인 후 실행하며 이번 통합에서 유료 호출을 수행하지 않는다. 이 작업 완료가 Phase B/C 또는 최종 전체 인수 PASS는 아니다.
