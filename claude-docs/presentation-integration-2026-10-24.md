# 발표 전 소스 통합 및 운영 확인 — 2026-10-24

## 통합 범위

- 원본 `melon-init@f9353c3`의 미커밋10파일은 독립 읽기 검토/credential scan 후 `d595375`로 원문을 보존했다. runtime/data는 stage하지 않았다.
- 별도 `integration/presentation-20261024` 작업 공간에서 AUD의 최신 B-v2/RPC/revision/감사·취소 observer, C 전용 계약·Vault·백엔드/SQL, NEWUI의 검토된 startup/시장 API·P0/AI/C 웹을 선별했다. AUD85+C-only101+NEWUI10 backend/contract 파일은 원문 복사 지문을 기록했다. C↔B 차이17개는 C=base/B만 변경이므로 충돌을 새 금융 규칙으로 해결하지 않았다.
- 웹은 P0/AI/C 기존 파일과 B-v2 지갑3모듈·관련 시험을 보존하고 기존 ApiClient의 B-v2 독립 메소드9개만 추가했다. **B-v2 전용 메뉴는 연결하지 않았다.** C owner-direct 화면을 B-v2 EIP-712 relayer UI 성공으로 바꾸지 않는다.
- AI의 진짜 중복은 Java 평가 Runner, Python broker/source identity, index-copy wrapper였다. failure branch의 continuation·단계별 진단·STOP과 presentation branch의 웹 claim/deadline·ACL·부분 생성 cleanup을 함께 유지했다. 질문/모델/예산/TTL·재시도 정책을 바꾸거나 유료 호출하지 않았다.
- 공개본에 남아 있던 로컬 편의 로그인 비밀번호 리터럴은 제거했다. 공개 운영 도구는 `TMS_LOCAL_DEMO_PASSWORD`를 명시적으로 받아 자원/claim 이전에 검사한다. 원래 로컬 C worktree·고정 산출물·private 설정·체크포인트는 변경하지 않았다. 공개 문서 사본의 비밀번호만 마스킹했다.

## 실제 검증

| 검증 | 이번 결과 |
| --- | --- |
| 새 통합 backend main361/test252 | cached JDK21 직접 fresh 컴파일 PASS; 예전 compiled 프로젝트 클래스를 섞지 않음 |
| 선택 JUnit | 888 found / 878 PASS / 실제PG opt-in10 SKIP / FAIL·ABORT0 |
| Foundry offline | 190 PASS / FAIL·SKIP0. sandbox user-home 캐시 오류는 제품 시험 시작 전 실패로 별도 보존 후 정상 사용자 권한에서 수행 |
| 새 통합 웹 | 211 PASS, build PASS(Vite7.3.6/107modules), 각각1회 |
| Python AI 병합 경계 | 최초98 중 continuation ACL mock1오류. 해당 fixture mock 보완 후 관련3모듈28 PASS; 나머지70은 최초 실행 PASS. 새 전체98 동시 PASS로 표현하지 않음 |
| 로컬 비밀번호 외부화 | 순수2 PASS; 없는 값/잘못된 값은 안전한 고정 오류. 실제 비밀번호 출력0 |
| 이번 PG/Anvil 새 대형 Gate·배포·거래 | NOT_RUN/0. 기존 성공 결과와 이번 소스 통합 회귀를 구분 |

증거는 각 원본이 아니라 새 통합 worktree의 ignored `deployment/runtime/presentation-backend-import/20261023T223212Z/`, `frontend-import-20261024.json`, `presentation-root-import/`에 있다. private runtime·DB dump·snapshot·ledger·approval·STOP·claim은 GitHub에 업로드하지 않는다.

## 오늘 서비스 READY 판정

독립 검토(`review_c_checkpoint_live`): 원문 선별, AI 경계 접합, 비밀번호 외부화, 공개 PNG와 staged 비공개 경계에서 **발견된 필수 수정 없음**. 검토자는 실환경을 재실행하지 않았다. 마지막 공백 정리는 동작 변경 없이 후보 사본에만 적용했다.

| 대상 | 이번 확인 | 현재 판정 |
| --- | --- | --- |
| 통합 소스 | compile/계약/웹 위 회귀 | 코드 통합 검증. 전체서비스 LIVE READY가 아님 |
| 기존 현물 Playground | 공식status정상/실행컨테이너0; 준비물 소유권 체크 뒤 source identity검사 실패 | **BLOCKED**. sourceHash/manifest를 조작해 켜지 않음 |
| C 사용자 최신8d | 원래 C worktree의 기본 launcher 실제실행 `OFFLINE_PLAN_ONLY_NOT_STARTED`, parent8d/snapshot71192…/Docker0/claim0 PASS | 재개 계획 PASS. 1시간 실제 창은 별도 명시 실행 및 READY 출력 필요 |
| 통합 웹 조회 Gate | 이전 실제 HTTP/NativeWS 성공 및 실제Chrome PNG8/ADMIN 하네스 대기실패 | PARTIAL/BLOCKED 당시 상태 유지. 자동 종료 Gate를 상시 서버로 사용하지 않음 |
| AI | 기존 Q12 실제 답변·근거 보존 | 새 호출0/OFF. C Tool/RAG 미완료 |

발표 시각/리허설 시각을 질문했다. 기존 C 창을 너무 일찍 열어 본 발표 전에 만료시키거나 영구 claim을 소모하지 않도록 현재까지 `--live-window`를 실행하지 않았다. 현재 세션을 실제 READY로 보고하지 않는다.

## 발표 동선·한계

[한 장 큐시트](../docs/presentation/DEMO_CUE_2026-10-24.md)에 PPT1~13 대응, 실제 메뉴/버튼, URL·시작/정상 종료·만료·반복 방법·즉시 대체 자료를 정리했다. 검토한 공개 시험 PNG5개를 `docs/presentation/screenshots/`에 복사했고 원본은 보존했다. C 수치 가림 이미지/감사 미생성 이미지는 발표자료로 선택하지 않았다.

현재 free0이므로 **Approve/Deposit이 OPEN의 선행 조건**이다. 실제 새 거래를 사용자가 선택하면 값과 nonce가 바뀌므로 연습 후 과거 정본으로 되돌릴 수 없다. 권장안은 같은 C창에서 조회·검증된 과거 거래·문서만 반복하는 것이다.

남은 범위: C 제품 위험Worker/자동청산, C Vault 정본 전체감사·projection/복구, C EIP-712 relayer/revision/SIGNED, C AI, 1초 tick 안정화, 기존 현물 사용자 자산 소유권, 일체형 실환경 인수. B-v2 취소 observer 실제 Gate도 BLOCKED이며 과거12거래32이벤트 감사 PASS로 승격하지 않는다. C 최소5거래를 최종 프로젝트 완료로 취급하지 않는다.
