# Google 인증·전량 매도 최종 로컬 통합

> 2026-10-18 KST. 사용자가 실제 인증의 주요 흐름과 취소/중복 거부 관측을 수용하고 로컬 커밋·melon-init 병합 및 새 회귀를 승인했다. 진행 중 기록이며 아래 실행 결과가 확정되기 전 통합 완료를 뜻하지 않는다.

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
- 최종 melon-init 실행은 아직 진행 전. 이전 backend495PASS/38SKIP·web107·Foundry36·기존 PG 결과를 병합 후 이번 실행으로 재사용하지 않는다.

## 환경 보존과 후속 Phase B

기존 Google5173/5174, KRX, playground의 컨테이너·image·DB·chain·immutable baseline·manifest·private evidence는 유지한다. 이번 검증은 별도 새 Synthetic fixture만 사용하고 실제 키움/Toss/Google 인증/유료 AI/배포는 실행하지 않는다. Google의 실인증 PASS는 사용자가 수행한 이전 실행의 증거이며 이번 fixture 회귀와 합치지 않는다.

월요일 Phase B는 통합 후 최신 `melon-init`의 HEAD/tree/source hash를 고정한 **신규** 환경에서 준비한다. 신규 거래 DB·persistent Anvil·키·계약·immutable baseline·고유 backend/web/tools image와 전용 secret을 사용한다. 기존 KRX manifest/sourceHead/sourceHash/image ID를 덮어쓰지 않고, 기존 KRX 성공을 최신 기능 인수로 간주하지 않는다.

준비 순서: Docker/여유 포트·자원 읽기 확인 → 신규 run·고유 project/network/volume·키 생성 → 전용 Anvil 계약·operator/Vault 유동성 준비 → 빈 사용자 장부·기준점 → 소스/image/owner/clock/canonical blockHash 검증 → 전용 키움 secret 사용자 입력 → 독립 10분 watchdog/egress 차단 사전 검증 → 승인된 KRX 장중 실행. 실제 공급자 연결 전까지 OAuth/REST/WS 자동 요청을 금지하고, 호출/재연결 범위·IP/동일계정WS 전제 및 실행 시간을 별도 확인한다.

실행 시 current generation 정상0B/290=2·LIVE·fresh/mockQuoteAllowed, UNKNOWN 허용·CLOSED 우선·견적 전후 generation 검증은 유지한다. Faucet→BUY→SELL→receipt/event·DB·개인WS·portfolio·두 대사와 coverage를 확인한다. Phase C는 최신 승인 지식/manifest/index·별도 AI DB와 호출 수/비용 상한 승인 후 실행하며 이번 통합에서 유료 호출을 수행하지 않는다. 이 작업 완료가 Phase B/C 또는 최종 전체 인수 PASS는 아니다.
