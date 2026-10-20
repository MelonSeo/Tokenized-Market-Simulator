# 승인된 실제 AI 평가 — 2026-10-20

## 승인·기준 및 현재 단계

사용자가 기존16질문·gpt-5.6-terra 최대32회·text-embedding-3-small 최대80회·US$2·자동재시도0·신규격리AI DB/index를 승인했다. 시작HEAD는 `melon-init@e0c085c`. 보호 사용자5개/기존 KRX·Google·NXT·실패증거는 유지한다. [전송 준비](ai-evaluation-transport-preparation-2026-10-20.md)와 [16문항 원계획](futures-final-integration-2026-10-20.md#실제-모델-평가-승인안--아직-미실행)을 따른다.

- 실제 모델 메타데이터GET2회: 두 모델 모두HTTP200/정확한id로 PASS. LLM0/embedding0/재시도0이며 추론 성공을 뜻하지 않는다. 키값·헤더·응답원문은 출력/저장하지 않았다. 새메타데이터 도구오프라인4PASS, 실제확인결과는새private경로에보존했다.
- Docker Server28.4.0응답과기존pgvector이미지digest를읽기전용확인했다. 기존컨테이너/volume/network를변경하지않았다. 이후신규DB/index준비와실제평가결과는아래에누적한다.

## 평가 데이터와 검증 경계

- 모델·embedding·pgvector검색은 실제 공급자로 평가한다. ToolRegistry/Dispatcher, AgentService, SkillRegistry/고정handler, 권한 및 model-evidence 검증은 실제 클래스다.
- 현물·시장·포트폴리오 조회의 저장소는 명시적 통제 fixture다. Futures 두 Tool의 데이터는 보존된 실제 PG/Anvil/worker E2E의 정제된 Tool 결과/Receipt·journal 스냅샷이다. **이번에 기존 거래DB나체인을다시조회하지않으며 새로운 canonical검증/새거래인수로표현하지않는다.** 모델에도 captureScope 및 freshChainVerification=false를 전달한다.
- USER/ADMIN·타인404 점검은 실제Dispatcher/Agent와 통제 fixture의 소유권검사를 결합한다. 새 JPA/REST 보안E2E로표현하지않는다. 미확정 사례는 명시적으로 verification을INCONCLUSIVE로반환하는음성fixture이며기존원장·체인에는변경없다.
- 질문별 실패가 의미/선택/근거 품질 문제인지, Guard/전송/환경 문제인지 구분한다. 성공을 얻기 위한 같은 질문 재시도는 하지 않는다. 전송결과가 불확실하거나 Guard가 중단하면 나머지는NOT_RUN으로 남긴다.

## 호출·비용 기록과 보존

- 기존 Guard/Java stdio 전송을 그대로 사용하고 예산ledger는 한 run만 만든다. 신규test harness는 별도명시환경gate와 EVALUATION_STARTED create-new로 실제평가재실행을거부한다. 기존live테스트의고정5433/H2자동실행flag는사용하지않는다.
- 실제 Provider 관측 훅에서 요청별 LLM/embedding input/output token과 예약누적을private JSON으로기록한다. 기록실패/usage미확인에서는runner를중단한다. 원문요청·키·JWT·서명은기록하지않는다. 모델의정제된답변·인용·수치근거는질문별평가private파일에보존한다.
- 실제 token 기반 표준단가 계산은 캐시할인 미반영 추정/보수상한과 구분하며 계정 청구서 확정금액으로 오인하지 않는다. 기존 Guard의누적token·요청수·US$2상한은변경하지않는다.
- 새fixture의DB·ledger·실패/성공파일을보존한다. 소유권확인후이번신규컨테이너만정지하며 기존실행환경과증거를초기화하지않는다. 원격push/배포없음.

## 실행 결과

**최종 판정: BLOCKED — 신규 DB의 Windows 호스트 접근 경계. 유료 평가 재실행 없음.**

### 실행 identity와 환경

- 시작/실행 HEAD: `e0c085c454e0bc0b310cd5c3ad0a71668c99a024` (`melon-init`). 신규 평가 harness를 포함한 sourceHash: `2dad0e23fdbb1ab2c0b0df3b0e7f54badc14bd6b127ca0be3d6365920b8994e7`.
- corpusHash: `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`.
- 단 하나의 run: `eval_29a98ebca694441dbe319530adae2edf`; private 경로: `deployment/runtime/ai-real-evaluation-20261020-01/`. ledger 재생성/초기화 없음.
- 신규 DB/user: run suffix 전용 `exchange_ai_eval_29a98ebca694441dbe319530adae2edf`, Windows loopback port `59741`. 기존 DB에는 연결하지 않았다.
- 신규 container/volume/network의 `ai-evaluation.run` label 일치, image digest 고정, secret ACL은 소유 사용자와 SYSTEM만 허용함을 확인했다. 비밀 값은 출력하지 않았다.
- 최초 Compose up은 Docker 기본 주소 풀 소진으로 실패했다. 기존 네트워크를 삭제/수정하지 않고 충돌 검사에 통과한 `10.249.231.0/24`를 이번 신규 internal network에만 지정했다. 이후 container 생성과 PostgreSQL 초기화 성공. 기존 fixture에는 변경 없음.

### 새 실행 결과

| 항목 | 판정 | 실제 증거와 한계 |
|---|---|---|
| 정확한 모델 메타데이터 접근 | PASS | GET 2회, 각각 HTTP200. 추론 권한/요청 성공을 보증하지 않음 |
| preflight 도구 오프라인 테스트 | PASS | 4개. 외부 모델 평가가 아님 |
| 최종 `compileTestJava --offline --no-daemon` | PASS | 신규 평가 harness 컴파일 |
| 신규 container/volume/network 소유권 및 secret ACL | PASS | 전용 label/image/loopback 설정 확인 |
| 신규 PostgreSQL 내부 SQL | PASS | `current_database()=current_user`, public table 0개 |
| 실제 평가 JUnit 실행 | FAIL | 1 test / 1 failed / skip 0. 색인 이전 중단; 자동 재실행 없음 |
| Java→신규 DB 호스트 연결 | BLOCKED | loopback TCP connect code 10061(연결 거절); Docker 실제 게시 포트 없음 |
| 9문서/88청크 색인 | NOT_RUN | corpus preflight만 완료; 예상 embedding 6회, bound 71,678. 실제 embedding 0회 |
| 고정 golden12 / negative8 / identifier8 검색 | NOT_RUN | 색인 전 중단, hit@5·MRR@5·오탐률 산출 불가 |
| 실제 모델 Q1~Q16 | NOT_RUN | 정답성/Tool·Skill 선택/인용/수치/불확실 상태 판정 불가 |
| 신규 USER/ADMIN fixture 경계 실행 | NOT_RUN | 해당 점검보다 앞서 중단; 기존 권한 테스트를 새 결과로 재사용하지 않음 |

실행 명령은 backend에서 `AI_APPROVED_MODEL_EVALUATION=true`, 전용 run/snapshot 경로를 명시하고 `gradlew.bat test --offline --no-daemon --rerun-tasks --tests '*ApprovedAiModelEvaluationTest'`였다. 전체 Chrome/선물 E2E는 재실행하지 않았다.

### 확인된 장애와 원인 확정 범위

1. container는 running/healthy이며 PostgreSQL 서버 로그에 5432 listen/ready가 있고 `docker exec psql`의 실제 SQL은 성공했다.
2. `HostConfig.PortBindings`에는 `127.0.0.1:59741→5432`가 있지만 **`NetworkSettings.Ports`는 `{"5432/tcp":[]}`**였다. 실제 Windows TCP 접속은 거절됐다.
3. 구성은 internal-only 네트워크 하나였다. **신규 환경의 호스트 포트 게시/네트워크 경계 실패**를 확인했다. PostgreSQL 데이터 장애나 모델 거부로 판단하지 않는다. Docker 구현상의 정확한 게시 실패 내부 원인까지 확정한 것은 아니다.
4. JUnit catch는 원래 예외를 고정 안전 오류 `APPROVED_AI_EVALUATION_STOPPED_NO_RETRY`로 대체해 구체 SQLException/SQLSTATE가 보존되지 않았다. 따라서 최초 내부 예외의 세부 원문/SQLSTATE를 확인했다고 주장하지 않는다. 후속 읽기 전용 TCP·inspect·SQL로 접근 경계를 재현했다.

### 호출, 토큰 및 비용

- SQLite `reservations`: **0행**, control stopped=1. LLM **0/32**, embedding **0/80**, 실제 usage token **0**, 예약 **US$0**, 이 평가의 토큰 기반 API 비용 **US$0**.
- 메타데이터 GET 2회는 별도로 기록했다. 생성/embedding POST, 자동 재시도, 대체 모델 호출은 없었다.
- `STOP`, `EVALUATION_STARTED`, `stopped.json`, `preflight.json`, ledger, DB 및 volume을 보존한다. STOP 제거/새 ledger로 한도 초기화/성공을 위한 재실행을 하지 않았다.
- 실제 모델 응답이 없으므로 질문별 정답률·실제 토큰 효율·실제 생성 비용 및 청구서 검증은 산출할 수 없다. US$2는 승인 상한이며 지출액이 아니다.

### 독립 검토 및 다음 승인

별도 `review_approved_ai_evaluation`가 신규 harness/준비 도구/기존 guard·broker·runner를 코드로 검토했고 필수 수정 없음이었다. 실제 외부 호출/DB/private 접근은 검토자가 수행하지 않았다. Q16의 사전 안전 PARTIAL, fixture 권한과 실환경 보안 검증의 차이, metadata200과 추론 성공의 차이를 유지한다.

다음 최소 조치는 **신규 AI DB의 호스트 접근 경계 수정과 비유료 SQL 확인**, 최초 예외의 안전한 code/SQLSTATE 보존 보완이다. 기존 DB/네트워크를 변경할 이유는 없다. 이후의 재개는 STOP/source identity를 우회하지 않는 승인된 절차가 필요하다. 현재 run을 임의 해제하거나 대체 ledger로 재시작하지 않고 사용자에게 재개 방식을 보고한다. 제품 기능 확장·추가 유료 평가·원격 push·배포는 진행하지 않는다.

종료 시 이번 신규 container만 label 재확인 후 정상 정지했다. volume·DB·ledger·manifest·실패 증거는 보존했다. 기존 사용자 변경 5개의 SHA256은 작업 시작 값과 모두 일치했고 `git diff --check` 통과, HEAD 변경·commit·merge·push 없음. 이번 신규 테스트/도구/보고서 5개는 미추적 상태로 남긴다.

최종 보고서 및 FileChannel 전체 쓰기 보완에 대한 별도 후속검토도 필수 수정 없음이었다. 검토자는 실행 실패와 미실행 평가의 분리, 원인 확정 한계 및 STOP 보존/승인 대기 경계를 확인했다. 실제 유료 평가와 새 DB 접근 성공을 검토가 대체하지 않는다.

### 후속 무과금 네트워크 복구

사용자 별도 승인으로 [DB 네트워크 복구 보고서](ai-evaluation-db-network-recovery-2026-10-20.md)를 추가했다. 신규 network-none fixture/loopback exec relay에서 실제 Java·pgvector·IndexLease 검증을 수행했으며 SCRAM localhost 인증 보완과08001/28P01 안전 진단을 확인했다. **이 원래 평가 run의 STOP/ledger/실패 결과는 변경하지 않고 실제16문항·유료색인은 여전히NOT_RUN**이다. 신규 live run의 재개 승인은 별도로 받는다.
