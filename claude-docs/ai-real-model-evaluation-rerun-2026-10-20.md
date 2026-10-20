# 실제 AI 모델 평가 재승인 실행 결과 — 2026-10-20

## 1. 결론 및 실행 기준

**전체 AI 통합 인수는 미완료다.** 실제 신규 RAG 색인·고정 검색 평가는 완료했지만, 16문항 중 Q1만 정상 답변했고 Q2~16은 평가 harness의 Tool 직렬화 설정 결함에 막혔다. 실행 JUnit의 성공은 질문 품질 PASS가 아니다. 제품 Agent/Tool 자체의 결함 또는 모델 능력 부족으로 결론내리지 않는다.

- 재승인: 같은16문항, `gpt-5.6-terra`≤32회, `text-embedding-3-small`≤80회, 자동 재시도0, 이전 실행 US$0을 포함한 전체 US$2 상한.
- HEAD `melon-init@e63dd6158ee43b81215ce15ddb19644d8952e2a0` + 승인 범위의 Relay/사전검사 준비 변경. **실행 sourceHash `59073241bbf89c204ed132021f2ef7445360e895efd23cb86772458c62543dfa`**.
- corpusHash `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`; 기존 승인9문서 구조와 golden12/negative8/identifier8/16문항을 변경하지 않았다.
- 신규 run `eval_7a8f25154337449e9f49d676b4d5fbb7`, private 증거 `deployment/runtime/ai-real-evaluation-20261020-02/`. DB/user는 해당 run 전용 `exchange_ai_eval_7a8f25154337449e9f49d676b4d5fbb7`, loopback59745.
- [원래 실패 run](ai-real-model-evaluation-2026-10-20.md)과 [무과금 복구 기록](ai-evaluation-db-network-recovery-2026-10-20.md)은 별도 이력이다. 새 ledger는 이번 명시적 재승인으로 단 한 번 생성했고 추가 실행/대체 ledger/승인을 벗어난 재평가는 하지 않았다.

## 2. 유료 요청 전 안전·환경 검증

- Docker28.4.0 및 기존 pinned pgvector image 사용. 새 container/volume 소유권·immutable ID·`network_mode=none` 검증, 외부 bridge/게시 포트/privileged/capability/bind mount 없음.
- 새 secret 폴더 ACL은 사용자/SYSTEM으로 제한했다. 기존 backend `.env`의 명시한 API key만 전용 파일로 준비했고 값을 출력하지 않았다. 계정 key/모델 메타데이터를 추가 조회하지 않았다.
- Relay 전체 및 연결별 수명을 3600초로 일치시켰다. 기본600초 중간 종료 위험을 제거했고 loopback만 허용/고정 컨테이너 내부5432/최대64연결/상한 내 DB 재연결만 허용했다. 평가 orchestration 자체는2700초 timeout, 종료 시 relay 및 자신이 생성한 DB container만 정지한다. API 재시도와 DB 연결은 구분한다.
- `EvaluationDatabasePreflight`가 **provider/broker 생성 전** 실제 Java JDBC DB/user 확인, 새 빈 DB의 IndexLease PREPARED, pgvector extension/연산, 잘못된 비밀번호28P01 거부, 새 JDBC 연결 PREPARED 재개를 확인했다. 실패하면 안전 SQLSTATE와 STOP를 남기고 paid path로 진입하지 않는다.
- 실제 `database-preflight.json`: JDBC PASS / SCRAM28P01 / pgvector PASS / PREPARED_RESUME_VERIFIED / paidCalls0.
- 동일 PREPARED lease를 runner가 create=false로 검증하고 INDEXING→READY로 진행했다. 임의 초기화/재색인 반복/다른 DB의 index 채택은 없다.
- Python 오프라인 **60PASS**, Java 관련 **31PASS/0FAIL/0SKIP** 및 컴파일. `review_ai_live_reapproved`가 준비 소스 독립 검토 후 필수 수정 없음. 검토자의 직접 API/DB/private 접근은 없었다.

## 3. 실제 색인 및 고정 검색 품질

실제 `text-embedding-3-small`로9문서88청크·1536차원 신규 index를 생성했다(색인6요청). 기존 AI DB/index는 변경하지 않았다. 기본 K 경로(semantic threshold0.25, 충분한 후보 후 문서당2청크, Top5), 역할/metadata 필터를 유지했다. Hybrid/reranker를 새로 추가하지 않았다.

| 항목 | 이번 실제 결과 | 해석 |
|---|---|---|
| golden12 hit@5 | **12/12** | Phase2 baseline10/12 대비2개 개선. 과거 K의12/12 유지 |
| golden12 MRR@5 | **0.881944** | 과거 K0.8819와 같은 수준 |
| 고정 직접근거 문자열 검사 | **11/12** | ADMIN 문항1개가 과거 문자열과 정확 일치하지 않음 |
| 직접근거 의미상 수동 확인 | **12/12** | ADMIN 문항1위 실제 본문에 '이 권한은 기존 본인 주문 API를 전체 사용자 조회로 확대하지 않는다'가 있어 질문에 직접 답함. 고정 문자열 결과11을12로 덮어쓰지 않음 |
| negative8 검색 후보 반환 | **5/8** | 과거 K와 같은 후보 오탐. 최종 답변 오탐률이 아님 |
| identifier8 hit@5 | **6/8** | MRR0.604167, 과거 기본 K의 미해결 한계 유지 |

golden 문서 순위는 순서대로 `1,1,1,1,1,1,3,1,4,1,1,1`이다. 기존 성공10개 문서 hit 회귀0. ADMIN 문항 top1 similarity0.585205, 본문은 현재 권한 구분을 명시한다.

고정 질문은 같지만 corpus는 최신 선물/인증 정책을 반영한88청크이며 과거 K의50청크와 다르다. 따라서 baseline 대비 수치는 이번 최신 지식의 관측 비교이며 특정 변경만의 인과적 개선 실험으로 해석하지 않는다.

식별자 실패는 `QuoteAlreadyUsed`(후보0)와 `MinimumOutputNotMet`(signed-quote-policy 후보1이나 기대 오류 가이드 미포함)다. negative 후보 반환은 슈뢰딩거 방정식/VIP 할인/mSEC 담보대출/비트코인 반감기/친구초대5개다. 이8문항의 실제 LLM 거부 답변은 이번 승인16문항 밖이므로 추가 유료 평가하지 않았다. 과거0/8 최종답변 오탐을 이번 결과로 재사용하지 않는다.

## 4. 질문별 실제 판정

아래 FAIL은 요청 통합 흐름 실패다. 해당 모델 단계에 도달하지 못한 항목의 정답률을0점으로 계산하지 않는다. `Tool/Skill BLOCKED`는 구현 부재가 아니라 이번 평가 harness 결함 때문에 검증을 완료하지 못했다는 뜻이다.

| Q | 고정 질문 주제 | 실제 호출·선택 | 기능흐름 / 답변 정답성 |
|---|---|---|---|
| 1 | quoteId 일회 사용 이유 | KNOWLEDGE, planning+synthesis2 LLM, 검색1 | **PASS/정답**. DB CONSUMED·체인 usedQuoteIds·소유권/만료/방향/입력/잠금의 원자성·CONSUMED≠FILLED 설명. 실제 검색 내 인용2개 |
| 2 | 현재 기준가격·시장 | LLM STATE, getCurrentReferencePrice 시도 | **FAIL(harness)**. 분류/Tool 선택은 적합, 실제 수치 답변BLOCKED |
| 3 | 본인 가용잔고·포트폴리오 | LLM STATE, getPortfolio 시도 | **FAIL(harness)**. 분류/Tool 선택은 적합, 가용25000 등 수치 검증BLOCKED |
| 4 | 주문 상태 | getOrder 선조회에서 INVALID_TOOL_ARGUMENTS | **FAIL(harness)** / 실제 LLM NOT_RUN |
| 5 | 견적 상태+만료 정책 | getQuote 선조회에서 동일 오류 | **FAIL(harness)** / 실제 LLM NOT_RUN |
| 6 | 전송·SIGNED/SUBMITTED | getOrder 선조회에서 동일 오류 | **FAIL(harness)** / 실제 LLM NOT_RUN |
| 7 | 체인 확정·정책 | getOrder 선조회에서 동일 오류 | **FAIL(harness)** / 실제 LLM NOT_RUN |
| 8 | ADMIN 이상 주문 | LLM STATE, listAbnormalOrders 시도 | **FAIL(harness)**. 분류/Tool 선택은 적합, 실제 ADMIN 결과BLOCKED |
| 9 | 정산 지연 Skill | settlement-debugging 지정, getOrder 선조회 오류 | **FAIL(harness)** / Skill 실행·종합NOT_RUN |
| 10 | 견적 진단 Skill | signed-quote-diagnosis 지정, getQuote 선조회 오류 | **FAIL(harness)** / Skill 실행·종합NOT_RUN |
| 11 | 시장 진단 Skill | market-availability-diagnosis 지정, 시장 선조회 오류 | **FAIL(harness)** / Skill 실행·종합NOT_RUN |
| 12 | Long 청산 원인 Skill | futures-liquidation-diagnosis 지정, 포지션 선조회 오류 | **FAIL(harness)** / 실제 청산 해석NOT_RUN |
| 13 | 마진콜가격·유지증거금5% | 포지션 선조회 오류 | **FAIL(harness)** / 계산 설명NOT_RUN |
| 14 | 청산 Receipt/Event 확정 | 포지션 선조회 오류 | **FAIL(harness)** / 확정·과거 snapshot 한계 설명NOT_RUN |
| 15 | 손익·DB journal | 포지션 선조회 오류 | **FAIL(harness)** / 수치·정합성 설명NOT_RUN |
| 16 | 불확실 가격·Receipt | 진단 지정, 포지션 선조회 오류 | **FAIL(harness)** / INCONCLUSIVE 안전PARTIAL 경로도 NOT_RUN |

실제 모델 planning을 실행한 질문은 Q1/2/3/8이고 synthesis는Q1이다. **16개 실제 모델 답변을 받았다고 표현하지 않는다.** 지정 Skill4개와 Tool10개의 registry는 사용했으나 성공 Tool evidence는0개였다. 따라서 선물 Tool2개·Skill4개 연동, 수치 정확성 및 불확실 상태의 최종 통합 인수를 완료했다고 주장하지 않는다.

USER 타인 포지션404·모델호출0, USER의 ADMIN Tool 거부는 이번 실행의 별도 사전검사에서 PASS했다. 단 통제 fixture의 소유권 검사이며 새 JPA/REST 보안E2E가 아니다. Q1의 USER 검색 인용은 USER 허용 문서만 사용했다. 타인 데이터가 모델에게 전달되거나 근거 없이 청산이 확정됐다는 관측은 없지만, 실패한15문항 때문에 일반적인 환각/보안 안전성 전체 PASS를 선언하지 않는다.

## 5. 실패 원인과 무과금 재현

`ApprovedAiModelEvaluationTest`는 `new ObjectMapper()`만 사용했다. 실제 `ToolResult`에는 `Instant retrievedAt`이 있고, `ToolDispatcher`는 성공 결과의 크기 제한 확인을 위해 이를 JSON으로 직렬화한다. Java time module이 없어 `InvalidDefinitionException`이 발생하고 Dispatcher의 IOException catch가 이를 `INVALID_TOOL_ARGUMENTS`로 매핑했다. Q2/3/8의 최종 Agent 오류는 TOOL_UNAVAILABLE이지만 audit의 원 Tool 코드는 같은 직렬화 오류다.

기존 `ToolDispatcherTest`와 `AgentServiceTest`는 `findAndRegisterModules()`를 사용한다. 제품은 Spring에서 주입한 mapper를 쓰며 이번 실행의 plain mapper와 구성이 다르다. 이 결과로 제품 Tool 계약을 바꿀 근거는 없다.

유료 실행 뒤 **추가 호출 없이** `EvaluationHarnessSerializationTest`로 다음을 재현했다.

1. plain mapper가 실제 ToolResult의 Instant를 직렬화하지 못함.
2. 유효한 getOrder 인자와 정상 mock 결과도 Dispatcher에서 INVALID_TOOL_ARGUMENTS가 됨.
3. time module을 등록하고 ISO 시각 직렬화를 설정한 mapper로 같은 Tool 호출이 SUCCESS가 됨.

새 오프라인 진단3PASS. 이는 수정 후보의 무과금 검증이지16문항 유료 재평가가 아니다. **실제 평가 harness의 mapper는 이번 결과를 얻은 뒤 몰래 고쳐 재실행하지 않았다.** 사후 진단 테스트 추가로 현재 sourceHash는 실행 sourceHash와 달라졌으며 이번 결과는 위 실행 해시에만 귀속한다.

사전 독립 검토와 기존 오프라인 회귀에서 이 harness 설정 차이를 발견하지 못했다. 향후 유료 실행 전에는 권한 거부뿐 아니라 정상 Tool 결과 직렬화·각 Skill의 positive fixture도 점검해야 한다. 최소 수정 후보는 test-only mapper 등록과 이 positive smoke gate이며 제품 정책/거래/Oracle/모델 변경은 필요하지 않다.

## 6. 실제 토큰·호출·비용

모든 POST는 기존 Guard를 통과했다. SQLite40예약 모두 OBSERVED, RESERVED/BREACH0이며 자동재시도0·대체모델0·추가ledger0이다. broker key는 Java에 전달하지 않았다.

| 항목 | 실측 |
|---|---|
| LLM 요청 | **5/32** — Q1 계획+종합2, Q2/3/8 계획 각1 |
| Embedding 요청 | **35/80** — 색인6 + 고정검색28 + Q1검색1 |
| LLM 실제 input/output | **4449 / 330 tokens** |
| Embedding 실제 input | **24671 tokens** |
| Guard 선예약 총액 | **US$0.13845652** (무환급 예약; 실제 청구액 아님) |
| usage×표준단가 추정 | **US$0.01335142** |
| cache-write 보수 input단가 적용 추정 | **US$0.01557592** |
| 전체 승인 상한 | 이전실행0 포함 **US$2 이내** |

표준단가는 Terra input US$2/M·output US$12/M, embedding US$0.02/M이다. cache 할인/계정 청구서는 확인하지 않았으므로 **확정 청구액이 아니라 실제 usage 기반 추정**이다. Guard는 input US$2.50/M 보수단가와 UTF-8/출력 상한으로 선예약했다. 공식 근거: [Terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra), [embedding](https://developers.openai.com/api/docs/models/text-embedding-3-small).

요청별 토큰·success·누적예약은 `usage-001.json`~`usage-040.json`, 단가별 파생값은 `derived-evaluation.json`에 저장했다. 원문 키/토큰/Authorization/모델 원응답 전체는 보고서와 Git에 포함하지 않는다. 실제 답변·정제된 근거·citation은 private `questions.json`, 고정 검색 결과는 `retrieval.json`에 있다.

## 7. 보존·남은 작업·판정

신규 DB container는 실행 종료 즉시 소유권 재확인 후 정지했고 relay도 종료했다. 새 DB/volume/index/approval/ledger·원래 실패 증거를 보존한다. 기존 KRX/Google/NXT/거래DB/Anvil에는 접근·변경하지 않았고 선물전체E2E를 반복하지 않았다. 원격 push/배포 없음.

종료 후 신규 container의 소유 label·network-none·exited를 재확인했다. 보호 사용자5개는 작업 시작SHA256과 전부 같고, 원래 실패 run의 STOP·SQLite ledger·run manifest도 바이트 해시가 같았다. `git diff --check` 통과. 이번 준비/오프라인진단/보고서 변경은 미커밋이며 기존5개와 구분한다. HEAD는e63dd61 그대로다. 사후진단 포함 sourceHash는 `3327a7992b0bff97e7216d6743108d5a7c39aef4291c0bdeb8bcefde8331f95a`로 실행 해시와 구분한다.

`review_ai_live_reapproved` 후속 결과 검토도 필수 수정 없음이었다. 검토자는 source·분석 산식·보고서·무과금 재현을 검토했으며, private 원자료/실제 API/DB/테스트를 직접 실행하지 않았다. 실측 건수 자체를 독립 재실행한 것으로 표현하지 않는다.

- **PASS:** 무과금 DB 사전검사, 실제 신규색인, 고정 검색 실행, 실제 Q1 답변·인용, 제한된 권한 음성검사, 비용/호출 제한 준수.
- **FAIL:** Tool 직렬화 때문에 Q2~16 통합 요청15개 실패. JUnit 실행1PASS를 품질 PASS로 바꾸지 않는다.
- **NOT_RUN/BLOCKED:** 나머지 실제 LLM 답변·Skill4/Tool10 성공 연동·선물 수치·청산 원인/불확실성 설명의 인수.

다음은 평가 harness의 mapper/정상 Tool smoke만 무과금 보완한 뒤 실패 문항을 재평가하는 것이다. **이번 승인에서 추가 유료 평가를 실행하지 않으며 사용자 검토를 기다린다.** 향후 재평가는 이미 쓴 호출·비용을 누적해서 승인해야 하고, 새 ledger로 총예산을 다시US$2 부여하거나 기존 identity를 덮어쓰면 안 된다. 이번 Guard 무환급 예약까지 보수적으로 제외하면 남는 총예산은US$1.86154348이며 호출 잔여는LLM27/embedding45다. 이 수치는 새 실행 허가가 아니다.

후속 무과금 보완은 [harness 수정·Q2~16 재평가 계획](ai-evaluation-harness-followup-2026-10-20.md)에 별도로 기록한다. 위 실패와 과거 실행 sourceHash는 당시 결과로 유지하며, 수정 후 실제 모델 평가로 덮어쓰지 않는다.
