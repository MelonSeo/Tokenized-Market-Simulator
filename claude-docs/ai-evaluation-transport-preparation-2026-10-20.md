# AI 실제 평가 전송 경계·격리 Index 준비

## 승인 경계와 기준

기준은 `melon-init@7e166a2`, [이전 guard 보고서](ai-real-evaluation-cost-guard-2026-10-20.md)다. 이번 승인은 전송 경계 연결·오프라인 검증·관련 파일 로컬 커밋까지다. 실제 LLM/embedding/재색인/기존 AI DB 접근은 승인되지 않았으며 실행하지 않는다. 기존 실제 Chrome/PG/Anvil 전체 E2E는 재실행하지 않는다.

## 실제 연결

```text
평가 Runner의 RAG / AuthorizedKnowledgeRetrieval / OpenAiAgentProvider / Skill
 → 동일 OpenAiProvider.forGuardedEvaluation
 → EvaluationTransport (기존 postInternal의 공통 전송 지점)
 → Java stdio → Python broker
 → SQLite 비용 Guard 선예약 COMMIT
 → OFFLINE: 가짜 응답 (네트워크 client 생성 없음)
 → LIVE: 별도 승인파일 검증 후 단1회 HTTPS POST (현재 미실행)
```

- 제품 `OpenAiProvider` 기존 생성자의 기본 HTTP/모델/권한/응답 검증은 유지한다. 새 평가 factory는 API key가 빈 문자열이고 `apiBase=guarded:stdio/`일 때만 만들며 HttpClient 자체를 생성하지 않는다. RAG answer/embedding/Agent structured 요청은 모두 기존 공통 post 지점에서 같은 전송을 사용한다.
- `backend/src/test/.../ai/evaluation/GuardedAiEvaluationRunner.java`는 test-only 평가 구성 지점이다. 실제 `KnowledgeLoader`, RAG, 검색, Agent adapter를 재사용한다. 기존 환경변수 opt-in live test/5433 고정 경로를 새 평가 runner로 사용하지 않는다.
- Java에는 실제 API key를 전달하지 않는다. 이 설정으로 실수로 일반 provider를 만들어도 `AI_API_KEY_MISSING`으로 전송 전에 거부한다. 평가 경로의 직접 URL, 잘못된 모델/요청·자동 재시도·중복 UUID를 제한한다. 임의 코드/별도 프로세스/다른 클라이언트나 계정 전체의 네트워크를 통제하는 OS sandbox는 아니다.
- broker의 key는 LIVE 명시 옵션과 runId/source/corpus/US$2/32LLM/80embedding/만료가 일치하는 별도 승인 파일이 있어야 읽는다. key는 명시 파일에서만 읽고 .env·환경변수·proxy·refresh를 import하지 않는다. Java가 띄우는 자식 환경도 허용된 OS 실행 변수만 전달한다.
- 표준 라이브러리 HTTPSConnection을 요청마다 새로 만들고 고정 `api.openai.com:443`의 `/v1/responses` 또는 `/v1/embeddings`에만 POST한다. redirect/429/5xx/불확실 응답에 retry/follow-up은 없다. 응답 최대2MB·전송 timeout·Java 측 deadline 및 해당 자식 프로세스 종료로 제한한다. 실제 upstream 통신은 이번에 미실행이다.
- 최종 정책v3는 평가 Responses 요청에만 `service_tier=default`를 강제하고 그 실제 직렬화 bytes를 예약한다. guard는 누락/auto/priority/fast/flex를 거부하며 live 응답 tier가 다르면 불확실로 종료한다. [공식 tier 계약](https://developers.openai.com/api/reference/java/resources/responses/methods/create)의 미지정=auto/프로젝트 설정을 표준요금으로 오인하지 않도록 한 최소 보완이다. 기존 제품 HTTP 요청 정책은 변경하지 않는다.

OpenAI Docs의 [Responses max_output_tokens](https://developers.openai.com/api/reference/resources/responses/methods/create)는 reasoning을 포함하는 출력 상한이며, [embedding 입력 계약](https://developers.openai.com/api/reference/resources/embeddings/methods/create)은 개별 입력 제한을 정의한다. 이번에는 해당 계약에 맞는 기존 모델/요청 형식을 그대로 사용한다. 문서 조회는 모델 호출/실제 embedding이 아니다.

## 비용·재개·중단

- 기존 nano-USD 선예약과 token/call 한도를 유지한다. 입력 US$2.50/M의 보수적 예약을 포함하면 token 한도상 최대 US$1.026, 최종 승인 요청 상한은 US$2다. 실제 과금·세금/환율 보증이 아니다.
- sourceHash는 backend main Java/resources/build.gradle·contracts/src Solidity·평가 runner/테스트·broker/guard·지식/manifest의 정규화된 파일 내용으로 계산한다. Java loader의 실제 corpus fingerprint와 Python 계산도 대조한다. Git HEAD만 바뀌고 파일 내용이 같아도 기존 증거 HEAD를 덮어쓰지 않는다.
- ledger identity에 contextHash를 추가해 run metadata 전체(db/port/user/mode/fixture/정책)도 묶는다. OFFLINE run을 LIVE로 전환하거나 대상 port/fixture를 바꿔 재사용할 수 없다. live 평가에는 별도로 새 live-mode run을 준비해야 하며 이것만으로 실행 승인이 되지는 않는다.
- 정상적으로 결과가 저장된 호출만 다음 호출을 허용한다. 같은 run을 새 Java/Python process에서 열어도 누적 비용/횟수·중복 UUID가 유지된다. RESERVED/BREACH 등 미확정 결과는 자동 해제하지 않는다.
- 명시 stop은 `STOP` marker와 DB control을 남긴다. Java timeout도 STOP을 남기고 **자신이 띄운 broker만** 종료한다. 정상 close는 clean detach여서 검증된 상태만 재개 가능하다. 불확실 예약/stop을 새 ledger 생성으로 우회하지 않는다.

## 신규 격리 Index

- 새 private run은 신규 디렉터리만 허용한다. DB/user 이름은 `exchange_ai_eval_<신규UUID>`, host=127.0.0.1, 명시49152~65535 port다. 기존5433 AI DB나 기존 거래/Google/KRX fixture를 기본값으로 사용하지 않는다. 이번에는 파일·임시 SQLite 외에 실제 PostgreSQL/pgvector DB나 Index를 생성하지 않았다.
- `EvaluationIndexLease`는 실제 current_database/current_user와 manifest를 먼저 대조한다. 최초 claim은 비시스템 테이블이 하나라도 있으면 채택하지 않는다. 신규 전용 DB에 불변 run/source/corpus marker를 만들며 기존 DB 자동 초기화·reset·캐시 복사는 없다.
- PREPARED → INDEXING을 COMMIT한 뒤에만 embedding한다. 동일 DB advisory lock/조건부 update로 경합을 차단한다. 성공 publication 후 fingerprint/model1536/active·문서/청크 개수를 검증하고 READY로 전환한다.
- READY 재개는 같은 source/corpus/DB의 한 활성 index와 정확한 개수를 확인하고 ingest를 다시 호출하지 않는다. INDEXING 중 crash/publication 후 marker 미확정은 완료로 추정하거나 재색인하지 않는다.
- corpus9개 구조/기존 설정1200/60과 CLEAN chunker를 유지한다. chunk<=500 및 embedding byte 보수 상한<=100000을 외부 호출 전에 검사한다. 예산에 맞추려고 지식/질문을 줄이지 않는다.
- Index 검증은 이번에 **JDBC 계약 시뮬레이션**이다. 실제 새 PostgreSQL/pgvector DDL/READY 재개는 비용 승인 후 새 전용 환경에서 선확인할 항목이며 이번 결과를 실제 PG 성공으로 표시하지 않는다.

## 검증 실행 및 실패 보존

- Python: `python -B -S -m unittest discover -s deployment -p 'test_ai_evaluation_*.py'` — 신규41PASS/0FAIL/0SKIP 후 metadata binding 테스트1개를 추가했다. 네트워크 생성 금지 assertion, 실제 stdio protocol,32/33회 경계·재개·stop·중복·오류·승인 만료, HTTPS redirect/429/500 mock 1회 제한을 검사했다. 실제 key는 읽지 않았다.
- Java: Gradle offline의 `*GuardedAiEvaluationRunnerTest`, `*EvaluationIndexLeaseTest`, `*OpenAiProviderTest`, `*OpenAiAgentProviderTest`만 선택한다. 새 실제 Java→별도 Python 프로세스와 가짜 upstream을 사용한다. 실제 모델 응답 평가가 아니다.
- 첫 Java 실행27개 중20PASS/7FAIL: 신규 Index 테스트의 중첩 Mockito stubbing 오류. 수정 후27개 중26PASS/1FAIL: 같은 mock ResultSet을 두 번째 조회에서 재사용해 target 검사에 실패한 테스트 설정 오류. 실제 PG/제품 전송 실패가 아니다. 둘 다 새 `deployment/runtime/ai-guard-offline-20261020-7e166a2` 하위에 결과를 보존했다.
- 이후 최종 실행·독립 검토·커밋 결과는 아래에 누적한다. 기존 전체 backend/웹/Foundry/Chrome/PG/Anvil PASS를 이번 실행으로 전기하지 않는다.

### 수정 후 관련 검증

- Java 새27PASS/0FAIL/0SKIP: Runner9(실제 Java/자식Python IPC), IndexLease7(JDBC mock), 기존 Provider7/AgentProvider4(로컬 HTTP fixture). 신규 PostgreSQL/Anvil 또는 실제 모델 검증이 아니다.
- 실제 loader 오프라인 preflight: 문서9개/청크88개, 색인 embedding HTTP6회, 색인 입력 token 보수 상한71678. 이는 실제 embedding 호출 수가 아니라 색인 준비량이다. 전체 embedding100000/80회 예산에는 이후 검색 평가/Agent 검색도 포함하며 이번 수치가 전체 평가 성공을 보장하지 않는다.

### 최종 검증·독립 검토

- 최종v3 코드 새 실행: Python **44PASS/0FAIL/0SKIP**, Java 관련 **27PASS/0FAIL/0SKIP**, `--offline --rerun-tasks` BUILD SUCCESSFUL. Java27개 구성과 검증 종류는 위와 동일하며 이전 결과를 최신 결과로 복사한 것이 아니다. 실제 Google/현물/선물/Chrome/전체 backend 회귀는 이번 좁은 전송 준비 작업에서 재실행하지 않았다.
- service tier 보완은 자체 점검 및 독립 검토가 필요성에 동의한 항목이다. 기본 제품 경로는 기존 Provider7/AgentProvider4 로컬 fixture로 확인했다. 실제외부 호출/키 사용/새PG 생성0이다.
- `review_ai_guard_transport`: **발견된 필수 수정 없음**. 실제 Git diff·신규 파일·주변 provider/store/서비스·최종 v3 요청/예약 및 Index 경계를 읽기 전용 검토했다. 직접 테스트·외부·DB/private 접근은 없었고 전달받은 최종44/27을 실제 모델/PG 검증으로 바꾸지 않았다. 검토 중 일시적인 에이전트 사용량 제한으로 최종 답신이 중단됐으나 같은 검토자가 재개하여 최종 결론을 전달했다.
- 실패 및 최종 XML은 이번 새 private 증거 디렉터리의 실행별 하위 경로에 보존한다. 이전 실패 XML을 성공으로 덮어쓰지 않는다. source/corpus 최종 값과 Git 보존 확인은 아래에 누적한다.

### 최종 식별자·커밋 범위

- 최종 평가 소스 해시 `b6de5697b4d624c4917ea69c68ef1bda013efcfe808982fe385b9bf407d200bc`, corpus fingerprint `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`. 기존 실제 E2E fixture의 sourceHead/productHash/manifest를 이 값으로 덮어쓰지 않았다.
- 이번 관련12경로(제품 Provider1, 평가 Java4, Python4, 보고서2, Futures 로그1)만 로컬 커밋 대상으로 한다. 보호 사용자5경로 SHA-256은 원래 값과 모두 동일하다. 기존 README/implementation-log/NXT 문서/두 master plan은 커밋하지 않는다.
- 실제 .env·credential·runtime·DB/state·캡처·로그·빌드 산출물은 제외한다. 원격 push/merge/배포나 기존 Docker/DB/Anvil 조작은 없다. 로컬 커밋 해시는 최종 응답 및 Git 이력에서 확인한다.

## 실제 실행 전 사용자 비용 승인

요청 범위는 기존16질문, `gpt-5.6-terra` LLM<=32회, `text-embedding-3-small`1536 embedding HTTP<=80회, 재시도0, **US$2 이하**다. 승인 전 실행하지 않는다. 실패/불확실 응답도 한도에 포함하며 성공 수를 채우려 추가 호출하지 않는다.

승인 후 새 live-mode run·격리 AI DB/index와 실제 근거 fixture를 구성하고,16질문/고정 retrieval golden·negative·identifier 평가를 구분한다. Tool 선택·권한·인용·정확한 수치·불확실성은 실제 모델 평가 때 판정한다. 새 실제 모델 평가의 PASS가 최신 KRX/NXT/Phase D의 완료 근거가 되지는 않는다. 실제 API 접근/모델 권한과 외부 요금은 아직 미검증이다.
