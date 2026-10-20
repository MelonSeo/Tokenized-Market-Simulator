# AI 평가 harness 무과금 보완 및 Q2~16 최소 재평가 계획

## 1. 범위와 현재 판정

기준은 `melon-init@e63dd6158ee43b81215ce15ddb19644d8952e2a0`와 [이전 실제 실행 보고서](ai-real-model-evaluation-rerun-2026-10-20.md)다. 이번에는 harness와 오프라인 검증만 보완했다. **추가 LLM/embedding 호출, 색인 복사·생성, Docker/DB 기동은 0회**다. 제품 거래·정산·권한 정책은 변경하지 않았다.

- **PASS:** 제품 mapper 대조, 실제 Dispatcher의 Tool10 정상 응답, 실제 handler의 Skill4 정상 경로, 고정16문항 Mock 요청 흐름, 권한/오류/불확실성 및 예산 계산 관련 오프라인 검증.
- **NOT_RUN:** 수정 소스의 실제 LLM 정답성, Q2~16 유료 재평가, 신규 index-copy 및 누적 예산 child 실행.
- **실행 금지:** 현 도구의 재평가 계획은 `executable=false`, `paidApproved=false`다. 새 ledger를 만들면 기존 승인이 다시 US$2로 초기화되는 현재 기본 흐름을 실행해서는 안 된다.
- 기존 실제 Q1/검색 결과는 과거 실행의 유효한 증거로 보존하지만, 새 sourceHash에서 다시 평가한 결과로 표현하지 않는다.

## 2. 원인과 변경 위치

`backend/src/test/java/com/pricetrack/exchange/ai/evaluation/`:

| 경로 | 보완 내용 |
|---|---|
| `EvaluationJson.java` | Spring Jackson builder와 Boot 기본 ISO 날짜 설정 사용. Instant/Duration의 timestamp 출력을 끄고 제품과 같은 Java time 처리를 적용 |
| `ApprovedAiModelEvaluationTest.java` | plain ObjectMapper 대신 공통 mapper, 고정16질문 추출, 공유 fixture, 결과 판정 축 추가. 기존 무과금 JDBC 사전검사도 포함 |
| `EvaluationToolFixture.java` | 실제 유료 harness와 smoke가 같은 read fixture를 사용. order/quote 입력·출력 연결 필드, transaction의 `databaseStatus`, abnormal 목록의 `items`를 실제 계약에 맞춤 |
| `EvaluationPositiveSmokeTest.java` | Tool10/Skill4 성공 경로, Boot mapper wire 대조, USER/ADMIN/타인 접근·잘못된 인자, 불확실 선물 근거, 고정16요청 검사 |
| `EvaluationVerdict.java`, `EvaluationVerdictTest.java` | 요청 실행·모델 호출·정답성 판정을 분리 |
| `EvaluationHarnessSerializationTest.java` | plain mapper 실패와 time module 보완 성공의 원인 재현3건(이전 실행 뒤 작성한 진단 포함) |
| `EvaluationDatabasePreflight.java` | 이전 run 준비 시 추가한 무과금 JDBC/SCRAM/pgvector/lease 검사. 민감정보 대신 안전한 SQLSTATE 진단 |

제품 mapper는 Spring 주입 mapper다. 최소 `JacksonAutoConfiguration`만 띄운 테스트에서 실제 `ToolResult`의 Instant와 18자리 Decimal 문자열 wire 출력을 대조했다. 서버나 시세 공급자를 시작하지 않았다. JSON 설정을 향후 제품에서 바꾸면 이 대조 gate도 유지해야 한다.

fixture 계약 보완은 제품 필드 변경이 아니다. 실제 Futures snapshot은 기존 VERIFIED 증거만 읽고 `PRESERVED_E2E_SNAPSHOT_NOT_FRESH_RPC`로 표시한다. 오프라인 smoke는 별도의 인공 수치를 사용한다. 어느 경우도 새 체인 조회나 금융 데이터 정합성 E2E를 실행한 것으로 표현하지 않는다.

이전 준비 변경인 `deployment/ai_db_relay.py`의 연결 alarm도 relay 전체 제한과 일치하도록 검사했다. 기본600초를 무한정 늘린 것이 아니라, 설정한 1~14400초 범위 내 전체/개별 연결 제한을 일치시킨다. 이번에는 relay를 기동하지 않았다.

## 3. 평가 상태의 구분

`EvaluationVerdict`는 다음 세 축을 별도로 저장한다.

| 상황 | request | model | correctness |
|---|---|---|---|
| 문항 미실행 | NOT_RUN | NOT_RUN | NOT_RUN |
| 응답 자체를 얻지 못함 | BLOCKED | NOT_RUN | NOT_RUN |
| Tool/harness 등 요청 오류 | FAIL | CALLED 또는 NOT_RUN | BLOCKED |
| 응답 완료, 정답 검토 전 | COMPLETED | CALLED 또는 NOT_RUN | REVIEW_REQUIRED |
| 검토자가 정답/안전 경계 충족 확인 | COMPLETED | CALLED 또는 NOT_RUN | PASS |

`model=CALLED`는 모델의 정답 PASS가 아니다. Q16은 불확실한 증거에서 모델 종합을 호출하지 않고 PARTIAL로 멈추는 것이 올바른 안전 동작일 수 있다. 이때도 실제 확인한 안전성만 PASS로 판단한다. 현재 유료 runner의 기본 출력은 정답성을 자동 PASS로 만들지 않는다.

16문항 Mock flow는 원래 질문 문자열을 그대로 재사용하고 모든 요청이 HTTP200/ANSWERED 또는 PARTIAL에 도달함을 확인했다. Mock 모델은 검증용 출력만 제공하므로 이16건은 **실제 모델 정답16PASS가 아니다**. 별도 verdict 테스트는 Q1만 검토 PASS이고 나머지15건이 BLOCKED인 집계도 확인한다. 과거 실제 실패15건을 소급 성공으로 바꾸지 않는다.

## 4. 이번에 실제 실행한 오프라인 검증

### Java: 112 PASS / 0 FAIL / 0 ERROR / 0 SKIP

`backend`에서 JDK21, `gradlew.bat test --offline --no-daemon`에 다음 class selector를 적용했다. 빌드 결과 SUCCESS, test XML 합계112다.

| 클래스 | 테스트 수 |
|---|---:|
| AgentServiceTest / FuturesAgentTest / OpenAiAgentProviderTest | 22 / 6 / 4 |
| EvaluationHarnessSerializationTest / EvaluationIndexLeaseTest | 3 / 7 |
| EvaluationPositiveSmokeTest / EvaluationVerdictTest | 18 / 4 |
| GuardedAiEvaluationRunnerTest / OpenAiProviderTest | 9 / 7 |
| SkillRegistryTest / SkillServiceTest / ToolDispatcherTest | 4 / 22 / 6 |

Tool10은 정상 `SUCCESS` 및 Instant 직렬화를 확인했다. Skill4는 실제 Dispatcher/handler/Agent의 SUMMARIZE VALIDATED까지 확인했다. 검색·LLM·DB/RPC reader는 Mock이며 실제 provider 또는 PostgreSQL/Anvil 검증이 아니다. 기존 USER/ADMIN/타인 접근 거부와 비정상 인자 테스트도 실행했다.

### Python: 65 PASS / 0 FAIL / 0 ERROR

`deployment`에서 `python -B -S -m unittest test_ai_reevaluation_plan test_ai_db_relay test_ai_evaluation_guard test_ai_evaluation_broker test_ai_model_access_preflight` 실행.

첫 실행65건 중 신규 계획 테스트5건이 Windows 임시 SQLite 파일 정리 중 ERROR였다. 연결 수명 문제였으며 예산 우회 성공이 아니다. `contextlib.closing`으로 읽기 전용 연결과 테스트 seed 연결을 명시적으로 닫고 같은 범위를 새로 실행해65PASS를 확인했다. 실패 사실은 이 문서에 유지한다.

`ai_reevaluation_plan.py`는 실제 부모 ledger를 **읽기 전용**으로 분석했다. 모드·policy·run/source/corpus/context identity 및 예약상태를 검증하고, 읽기 전후 ledger SHA256을 대조한다. 불확실 예약이 있으면 계획도 차단한다. 자식 승인/ledger/실행파일을 생성하지 않는다.

## 5. Q1 및 기존 검색 결과의 재사용 경계

부모 run: `eval_7a8f25154337449e9f49d676b4d5fbb7`.

- Q1은 지식 전용 답변과 인용이 실제로 확인됐다. mapper/Tool fixture 보완은 Q1 경로 변경이 아니므로 **과거 source의 Q1 인수 증거로 채택하고 재호출은 제외**한다. 새 소스의16문항 실모델 전체 PASS라고 표기하지 않는다.
- 동일 corpus/model/dimension/chunking 및 retrieval 구현을 유지한다면 기존 golden12 hit@5 12/12, MRR .881944, negative 후보5/8, identifier6/8은 해당 index의 품질 근거로 재사용 가능하다. negative5/8은 답변 오탐률이 아니다.
- corpus·필터·chunk·검색 코드나 vector가 바뀌면 기존 검색 수치를 새 경로의 결과로 사용할 수 없다. 이번에는 검색 알고리즘이나 지식 문서를 바꾸지 않았다.
- 새 DB로 vector를 복사한다면 chunk/document ID·역할 metadata·model/dim·fingerprint·vector checksum과 개수를 대조해야 한다. 대조 전에는 새 DB 검색 PASS를 선언할 수 없다. 필요시 기존 query vector도 provenance를 유지하여 무과금 비교할 수 있으나 이번에는 복사나 비교를 실행하지 않았다.

## 6. 누적 예산과 15문항 계획

기존 예약은 실제 usage로 환급하지 않는다. 이전0달러 실패 run을 포함한 원 승인 한도는 여전히 **LLM32 / embedding80 / US$2 총합**이다.

| 항목 | 기존 무환급 사용/예약 | 신규 실행에 허용 가능한 잔여 |
|---|---:|---:|
| LLM 호출 | 5 | 27 |
| embedding 호출 | 35 | 45 |
| input 보수 토큰 bound | 39,437 | 216,563 |
| output 예약 토큰 | 3,200 | 28,800 |
| embedding 보수 토큰 bound | 73,201 | 26,799 |
| USD 보수 예약 | 0.13845652 | **1.86154348** |

부모 ledger SHA256: `db4e7a9995cd3603a64eeb7c41978e1f971c3a97ac0d2f6902c35c9a6cd4c292`. 이전 sourceHash는 `59073241bbf89c204ed132021f2ef7445360e895efd23cb86772458c62543dfa`다.

Q2~16만 선택하고 Q1·고정검색28질문 반복·full reindex를 제외한다. 기존 모델 `gpt-5.6-terra`/`text-embedding-3-small`과 자동재시도0을 유지한다.

- 계획 호출: 일반 문항10 planning + 양성14문항 synthesis, **최대24 LLM**. 지정 Skill4+Q16은 planning을 생략하며 Q16은 불확실 근거에서 종합도 생략한다. 기존 요청 형태의 예상은20 LLM이나 경로 차이를 고려해24로 산정한다.
- 검색: 질문별 최대1회, **embedding 최대15회**. 질문 byte bound1024+8 적용 시15,480으로 잔여26,799 이내다.
- 출력: planning10×400 + synthesis14×1600 = **26,400 tokens**, 잔여28,800 이내다.
- **입력 총216,563 bound를 충족하는지 실제 직렬화 요청의 오프라인 dry-run이 아직 필요**하다. 모델이 생성하는 출력/route 및 근거 크기도 확인해야 한다. 요청 전 예약에서 초과하면 중단해야 하며 답변 근거를 임의로 잘라 성공 처리하면 안 된다.
- 현 cap에서 잔여 input/output/embedding bound를 모두 쓰는 보수 비용은 US$0.88754348이며 잔여 US$1.86154348 미만이다. 이 값은 실행 예상 청구액이 아니라 잔여 기술 토큰 한도를 이용한 비용 상계다.

따라서 **호출 수·출력·query embedding·금액상으로는15문항을 수용할 수 있지만, 입력 dry-run과 누적 Guard·index-copy의 무과금 검증 전에는 실행 준비 완료/완주 보장이라고 말할 수 없다.**

## 7. IndexLease와 안전한 신규 실행 방법 — 설계만

현재 평가 sourceHash는 `dded53b88e6714751d8212573954bdd8026b12c0925ee3fd7972c8c0a7c4cddd`다. Java 평가 harness가 sourceHash 범위에 포함되므로 mapper 변경만으로도 기존 실행 identity와 다르다. 기존 manifest의 해시를 새 값으로 덮어써 resume guard를 우회하지 않는다.

`EvaluationIndexLease`의 owner는 run/source/corpus에 결합된다. 기존 READY index를 새 source로 직접 재사용하거나 public owner를 재작성하는 것은 허용되지 않는다. PREPARED/READY 재개는 **동일 identity**에서만 가능하며 중단된 INDEXING을 임의로 READY로 승격하지 않는다.

선호하는 최소 실행 경로는 다음과 같다. **이번에는 구현/실행하지 않았다.**

1. 별도 사용자 승인 후 새 실행 identity와 신규 network-none AI DB를 만든다. 부모 STOP/ledger/index DB는 보존하며 원 DB를 변경하지 않는다.
2. 부모 READY index의 model1536차원, corpus/fingerprint, 9문서88chunk 및 vector/metadata 해시를 읽기 전용으로 검증한다.
3. 새 DB가 빈 상태에서 새 run/source/corpus owner를 PREPARED로 claim한다. INDEXING으로 진입한 뒤 검증된 **ai schema 데이터만** 신규 target에 복사한다. 부모 `public.ai_evaluation_owner`, 승인/credential/ledger는 복사하지 않는다.
4. 새 DB 데이터·vector checksum과 parent provenance를 검증한 뒤 기존 verifyReady 조건을 만족해야만 READY로 종료한다. 실패한 신규 DB를 초기화해 성공처럼 재사용하지 않는다.
5. Q2~16 selector, **단일 child만 허용하는 누적 Guard**를 연결하고 무과금 dry-run을 통과시킨 후 별도 승인으로 유료 평가를 시작한다.

Full reembedding은6요청/보수 bound71,678로 **잔여 embedding token26,799를 초과**한다. 호출 수와 금액만 보고 재색인하면 안 된다. index-copy가 안전하게 검증되지 않으면 BLOCKED를 유지하고, 기술 토큰 한도 변경이 필요한지 사용자에게 별도로 요청해야 한다.

누적 Guard 구현 시 부모 ledger를 읽기 전용으로 고정하고 SHA/identity/예약 합계를 child lineage에 포함해야 한다. 부모 예약을 선차감하고 동일 parent에서 복수 child가 각기 남은 예산을 받지 못하게 해야 한다. 재시작·중복·불확실 전송·STOP도 기존과 같이 차단한다. 대안은 부모에 결합된 잔여 policy이나 현재 broker는 원 policy와의 일치를 요구하므로 별도 변경/검토가 필요하다. **지금의 계획 계산기는 전송 enforcement를 대신하지 않는다.**

## 8. 독립 검토·보존·다음 승인

`review_ai_harness_followup`이 실제 mapper/ToolReadFacade/Dispatcher/SkillRunner/Agent/Provider/IndexLease 및 신규 fixture·판정·예산 계획을 검토했다. 최초 코드 검토와 최종 문서 재검토 모두 **발견된 필수 수정 없음**이었다. 검토자는 직접 테스트/API/DB/Docker/private 자료에 접근하지 않았으며 실행 수치는 구현자의 이번 테스트 결과다.

보호 사용자5경로는 SHA256이 작업 전과 동일하다: README, 일반 implementation-log, NXT 사용자 문서, Futures48h 계획, completion master plan. 모두 커밋에서 제외한다. 이전 STOP/ledger/source/index/평가 결과는 보존한다. 기존 KRX/Google/NXT/거래DB/Anvil을 조작하지 않았다. 전체 선물 E2E, 원격 push/배포도 하지 않았다.

커밋 직전 원래 실패 run01의 STOP/ledger/run/EVALUATION_STARTED/stopped 해시는 기존 기록과 동일했고, run02 부모 ledger도 위 SHA256과 동일했다. 이전 실제 준비·실행 기록 및 이번 보완을 합쳐 명시17경로만 로컬 커밋 대상으로 선택한다. 보호 사용자5개와 모든 runtime/private/생성물은 제외한다.

다음 실행에는 **원래 US$2에서 잔여 US$1.86154348/LLM27/embedding45만 사용하는 Q2~16 평가의 명시적 재승인**이 필요하다. 승인 전 추가 호출은 금지한다. 그 전에 누적 Guard+단일 child, Q2~16 선택, 입력 dry-run, 검증된 vector 복사의 오프라인 보완이 남는다. 새 index의 schema/owner는 새로 필요하지만 유료 embedding 재생성은 우선하지 않는다. 사용자 결정 없이 전체 예산/토큰 한도를 늘리거나 새 full-budget ledger를 실행하지 않는다.
