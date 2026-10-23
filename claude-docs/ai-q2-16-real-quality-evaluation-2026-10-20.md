# Q2~16 실제 AI 품질 평가 — 부분 실행 후 안전 중단

## 1. 최종 판정

**전체 평가 BLOCKED.** Q2~4는 실제 모델의 STATE 분류/Tool 선택과 조회 결과 전달이 PASS, Q5는 RAG 실패를 안전하게 표시한 PARTIAL이다. Q6의 종합 LLM 요청1건이 불확실한 RESERVED 상태로 남아 자동 중단했다. Q7~16은 NOT_RUN이다. 재시도·대체 모델·추가 run/ledger 생성·STOP 해제는 하지 않았다.

특히 **Q11~16의 실제 모델 답변은 확보하지 못했다.** 청산 이유·금융 계산·canonical 확정·journal 설명을 실제 LLM으로 검증했다고 할 수 없다. JUnit도 이번에는 1실행/1실패이며 이를 품질 PASS로 바꾸지 않는다.

발표용 **AI 청산 설명 시연은 현재 제외 권장**이다. 검증된 현물/선물/스마트 컨트랙트 자동청산과 감사 데모는 별개로 유지한다. AI의 읽기 전용 구조 설명은 가능하나 검증하지 못한 실제 답변 영상이나 라이브 성공을 주장하지 않는다.

## 2. 실행 identity와 보존

- 실행 브랜치/HEAD: `melon-init@f9353c33244565b30fb130dfb6159eab441a3523`.
- sourceHash: `332499954d0041f87faa0deccded99965b2433efa98a129a1d2b14051bcdb9fd`.
- corpusHash: `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`.
- 신규 child run: **`eval_d55c916420ae4a28a37a0ef11b1b2e6f`**.
- 신규 private 폴더: `deployment/runtime/ai-real-evaluation-q2-16-20261020-01/`.
- 부모 run: `eval_7a8f25154337449e9f49d676b4d5fbb7` (`ai-real-evaluation-20261020-02/`)의 기존5LLM/35embedding/예약US$0.13845652를 그대로 carry했다.
- 평가 시작 marker: **2026-10-20 23:19:22 KST**. STOP23:19:53, 프로세스 종료 기록23:19:54. 준비/컴파일을 포함한 Gradle 실행은 약76초다. 질문별 latency와 혼동하지 않는다.
- sourceUnchanged=true를 종료 시 재확인했다. 이전 mapper/fixture 수정2개 및 사전 보고서는 기존 미커밋 변경으로 보존했다. 이번에는 제품 코드·프롬프트·정답 기준을 변경하지 않았다.

이전 [Q1/검색 및 Q2~16 실패](ai-real-model-evaluation-rerun-2026-10-20.md), [mapper 보완](ai-evaluation-harness-followup-2026-10-20.md), [v4](krx-priority-ai-input-v4-2026-10-20.md), [직전 사전 점검](ai-q2-16-presentation-preflight-2026-10-20.md), [실제 체인/Mock AI E2E](futures-postmerge-e2e-2026-10-20.md)는 덮어쓰지 않는다. 과거 Q1 PASS와 golden12/negative8/identifier8 검색은 재실행하지 않았다.

## 3. 유료 전 최종 무과금 gate

| 항목 | 이번 실제 결과 |
|---|---|
| Git/source/corpus 및 fixture | 이전 승인 source와 일치, Instant mapper 및 SIMULATED 상태 수정 유지 |
| Java 실행/ToolResult 직렬화 | 필요한 Serialization3 + PositiveSmoke19 = **22 PASS/0 FAIL/0 SKIP**, 새 Gradle 종료0 |
| 부모 승인/ledger | EXPIRED, 모든40예약 OBSERVED, ledger SHA 기존값 일치 |
| 단일 child | 생성 전 claim 없음 확인 후 기존 도구로 단 한 번 claim |
| 원본 DB 보호 | 원본 container의 기존 중지 상태(exit0)/network-none/소유 label 및 volume 확인. 원본 container는 조작하지 않았으며 원본 volume은 helper에 readonly로만 연결 |
| 새 DB/벡터 | **실제 PostgreSQL/pgvector copy test1 PASS**, 신규 DB/user/owner, 9문서88청크1536차원, vector·metadata checksum 일치 |
| JDBC/SCRAM/lease | 실제 신규 DB에서 잘못된 비밀번호28P01 거부, PREPARED→INDEXING→READY 및 같은 owner 재개 PASS. paid runner에서도 READY_VERIFIED/JDBC PASS/paidCalls0 선확인 |
| 유료 전송 | source/corpus/부모 ledger hash/질문2~16/READY proof에 묶인 새 승인 파일 검증. API 키는 새 ACL 제한 파일에만 준비하고 JVM/로그에 전달하지 않음 |

무과금 증거: `deployment/runtime/ai-q2-16-live-precheck-20261020-01/` 및 신규 child의 `index-copy-execution.xml`, `index-copy-verification.json`, `database-preflight.json`.

새 DB는 loopback59981, `network_mode=none`, 외부 port publish 없음이다. 기존 relay가 Docker exec를 통해 해당 소유 DB의 localhost5432만 연결한다. relay 전체/개별 연결3600초, 최대128연결, 평가 프로세스1800초 제한을 사용했다. DB 연결과 API 재시도는 다르며 API 재시도는0이다.

기존 `ai_index_copy_fixture.py`와 고정 `ApprovedAiModelEvaluationTest`를 재사용했다. 일회성 runtime orchestration은 private 경로에만 있고 Git에서 제외된다. full reindex/색인 embedding은 **0회**다.

## 4. 호출 상한 및 Guard

사용자 승인 모델 `gpt-5.6-terra`, `text-embedding-3-small`을 그대로 사용했다. 공식 모델 문서를 실행 전 확인했고 다른 모델로 대체하지 않았다.

기존 Guard는 부모 carry를 포함한 LLM32/embedding80/input400,000/output32,000/embedding100,000/총US$2를 전송 전에 영속 예약한다. 이번 추가24/15 범위는 source-bound 고정15질문 runner가 보장한다: 명시 Skill5문항으로 planning 최대10, Q16의 합성 차단으로 synthesis 최대14, 문항당 retrieval 최대1이다. EVALUATION_STARTED는 CREATE_NEW이며 child claim은 단일 영속 기록이다. 재색인/고정검색 루프는 child에서 건너뛴다.

독립 실행 전 검토 `review_q2_live_execution`: **발견된 필수 수정 없음**. 위 고정 상한, parent carry, relay/timeout/STOP 및 신규 컨테이너 한정 종료를 코드로 대조했다. 검토자는 직접 API·DB·private 원자료·테스트를 실행하지 않았다. 기존 build XML을 과거 결과로 오인하지 말라는 주의도 반영했다. 이번에는 새 marker/질문 파일/시각/실패 XML이 모두 실제 새 실행과 일치한다.

## 5. 실제 호출·토큰·비용

| 항목 | 신규 child | 부모 포함 누적 |
|---|---:|---:|
| LLM 요청 예약/전송 경로 진입 | **6** (usage확인5 + 불확실1) | **11/32** |
| Embedding 요청 | **2** (모두 usage확인) | **37/80** |
| input 기술 bound | 48,138 | 87,575/400,000 |
| output 기술 bound | 3,600 | 6,800/32,000 |
| embedding 기술 bound | 194 | 73,395/100,000 |
| 무환급 보수 예약액 | **US$0.16354888** | **US$0.30200540/2** |

신규6번째 LLM 요청은 Guard가 RESERVED를 COMMIT했으나 응답/usage를 검증하지 못했다. upstream이 실제로 처리·청구했는지는 확인 불가다. 실패를0회/0원으로 환급하지 않는다. 7개 OBSERVED(LLM5+embedding2), RESERVED1, BREACH0이다.

확인된 신규 usage는 LLM input **2,791**, output **117**, embedding input **55**다. 이 확인된7응답만의 표준 단가 추정은 **US$0.00698710**, input cache-write 보수 단가 적용 추정은US$0.00838260이다. **불확실 요청1건을 제외한 부분 추정**이며 신규 전체 실제 청구액이나 확정 영수증이 아니다.

요금 근거: [Terra 공식 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.6-terra)의 input US$2/M·output US$12/M·cache write1.25배, [embedding 공식 문서](https://developers.openai.com/api/docs/models/text-embedding-3-small)의 US$0.02/M (2026-10-20 확인). Guard는 기존 input US$2.50/M·output US$12/M·embedding US$0.02/M 산식을 유지했다. 각 요청의 최대 input bound32K는 장문 할증 경계보다 작다. 실제 할인·세금·계정 청구서는 확인하지 않았다.

자동 재시도0, 추가 metadata API 조회0, Q1재호출0, 새 전체색인0이다. API 전송 경로8회 외 다른 평가 호출을 추가하지 않았다. 사용량·비용 원본은 `usage-001.json`~`usage-007.json`과 SQLite에 보존한다.

## 6. 문항별 수동 품질 판정

**PASS는 해당 문항의 제한된 route/조회 계약 기준이다. 자연어 종합 답변 전체 PASS와 다르다.** raw runner verdict는 기존 설계대로 correctness=REVIEW_REQUIRED이며 원본을 수정하지 않고 아래 별도 검토 결과를 기록한다.

| Q | 실제 모델/Tool/Skill 및 근거 | latency | 품질 판정 |
|---|---|---:|---|
| 2 | 실제 LLM STATE, getCurrentReferencePrice SUCCESS. SIMULATED/OPEN/SIMULATED, 기준가75,000, observedAt/priceKind 전달. 자연어는 서버의 고정 안내이며 LLM 종합 없음 | 3,076ms | **PASS** — 분류·조회 사실 전달. 실제 주가/매매 가능 보증을 생성하지 않음 |
| 3 | 실제 LLM STATE, getPortfolio SUCCESS. mKRW total100,000/locked75,000/available25,000, mSEC2/locked0/available2, totalValue250,000mKRW. 합계·단위 및 잠금/가용 구분 일치. LLM 종합 없음 | 2,028ms | **PASS** — controlled fixture의 조회 계약 기준 |
| 4 | 실제 LLM STATE, getOrder SUCCESS. PENDING_ONCHAIN/trade=null, BUY입력75,000mKRW. FILLED 또는 확정 체결로 바꾸지 않음. LLM 종합 없음 | 1,929ms | **PASS** — 본인 target/상태 조회 기준 |
| 5 | 실제 LLM MIXED, getQuote SUCCESS. CONSUMED/expiredByTime=true/연결된Order 전달. embedding1응답은 성공했지만 RAG_UNAVAILABLE 및 KNOWLEDGE_EVIDENCE_MISSING. 종합 LLM 미호출, 인용 없음 | 6,481ms | **PARTIAL** — 사실은 전달하고 규칙 근거 부족을 명시. 기대한 만료 정책 설명/인용은 미완료 |
| 6 | planning LLM/embedding의 usage는 확인됨. 제어흐름/예약 순서상 다음 종합 LLM에서 불확실1건 발생. 질문 row/자유 답변은 저장하지 못함. 정확한 route JSON·Tool 결과를 보존 파일에서 확인할 수 없음 | 문항 완료시간 미보존 | **FAIL/BLOCKED** — 전송 결과 불확실, 정답성 판정 불가 |
| 7 | 체인 확정 설명 | — | **NOT_RUN** |
| 8 | ADMIN 이상 주문 조회 | — | **NOT_RUN** |
| 9 | settlement-debugging | — | **NOT_RUN** |
| 10 | signed-quote-diagnosis | — | **NOT_RUN** |
| 11 | Synthetic 시장 가용성 진단 | — | **NOT_RUN** |
| 12 | Long10 청산 원인 Skill | — | **NOT_RUN** |
| 13 | 마진콜/유지증거금5% 계산 설명 | — | **NOT_RUN** |
| 14 | canonical Receipt/Event 확정 설명 | — | **NOT_RUN** |
| 15 | 실현손익·journal·house 관계 설명 | — | **NOT_RUN** |
| 16 | controlled uncertainty에서 유보 | — | **NOT_RUN** |

Q2~4의 답변 본문은 '조회 시점의 사실은 toolEvidence에 표시했습니다.'이며 숫자를 모델이 새로 계산해 서술한 답변은 아니다. 실제 LLM이 맞는 조회 경로를 선택했고 서버가 원본 Tool 사실을 반환한 점을 확인했다. STATE에는 RAG가 없으므로 citationIds가 비어 있는 것을 인용 오류로 판정하지 않는다.

Q5는 '현재 사실은 조회했지만 관련 규칙의 근거를 확보하지 못했습니다.'라고 유보했다. RAG 실패를 감추거나 가짜 출처·확정 만료 원인을 생성한 관측은 없다. 다만 설명 목표를 달성하지 못했으므로 전체 정답 PASS가 아니다.

집계: **제한적 조회 PASS3 / PARTIAL1 / 실행 FAIL·품질 BLOCKED1 / NOT_RUN10**. 신규 자연어 종합 답변은0개다. Q1의 과거 실제 종합 PASS를 합쳐 이번15문항 전체 품질을 과장하지 않는다.

## 7. 실패 원인: 확인된 사실과 가설

### Q5 RAG

확인: Tool 성공, 실제 embedding 응답 usage확인, 최종 RAG_UNAVAILABLE, knowledge근거/인용 없음이다. 과거 Instant 직렬화 문제는 재발하지 않았다. 현재 공유5초 retrieval 예산은 corpus/active 확인·embedding·SQL 후보검색·최종 승인 확인을 모두 포함한다.

가설: embedding 지연 및 relay/DB 접근이 누적되어 retrieval 예산을 소진했을 수 있다. usage파일 시각은 planning23:19:35→embedding23:19:40, 질문 전체6.481초다. **하위 예외가 고정 RAG_UNAVAILABLE로만 보존되어 timeout·DB·승인검사 중 정확한 실패 지점은 확정할 수 없다.** READY/JDBC 사전검사 PASS만으로 실행 중 DB 장애를 완전히 배제하지 않는다.

### Q6 불확실 종합 전송

확인: 종합 요청으로 판단되는 LLM 슬롯이 input bound15,490/output1,600/예약US$0.057925로 RESERVED다. 완료 응답/usage 파일은 없다. STOP 및 stopped.json의 EVALUATION_STOPPED_NO_RETRY, summaryUnavailable=true가 남았다. 원문 HTTP상태/응답/원격 request ID는 기존 전송 경계가 저장하지 않아 확인 불가다.

planning23:19:42·embedding23:19:43→STOP23:19:53은 기존 종합 단계10초 경계와 부합한다. 그러나 **클라이언트 deadline, HTTP상태 오류, 응답 유실 또는 다른 전송 실패 중 무엇인지 확정하지 않는다.** 서버가 처리하지 않았다고도 단정하지 않는다. 모델의 답변 내용이 틀렸다는 증거는 없지만 답변 자체가 없으므로 품질 PASS로 평가할 수 없다.

이번에는 timeout/질문/프롬프트/Guard/모델을 수정하거나 같은 요청을 재시도하지 않았다. 기존 전체 선물/Chrome E2E도 반복하지 않았다.

## 8. 권한·근거 범위·발표 활용성

- 유료 실행 전 controlled fixture 권한 gate: 타인 USER 포지션404/모델호출0, USER의 ADMIN Tool 거부 PASS. 이것은 새 JPA/REST/STOMP 보안E2E가 아니다.
- Q2~11 reader는 controlled fixture다. 보존된 실제 Long10/체인/DB 사실은 Q12~15에서 사용하도록 준비했지만 이번에는 도달하지 못했다. Q16 역시 정상 보존 증거에 controlled uncertainty를 주입하는 문항이며 미실행이다.
- 현재 확보한 응답에서 쓰기/청산 실행·타인 데이터 공개·담보 온체인 예치/지급능력 보증을 주장한 관측은 없다. 하지만 미실행10문항 때문에 전체 보안/환각 안전성을 PASS로 선언하지 않는다.
- 웹 진단 버튼→positionId→futures-liquidation-diagnosis 연결은 기존 구현/무과금 근거가 있다. **이번에는 실제 Chrome+현재 서버+거래DB/RPC+실제LLM을 함께 연결하지 않았다.** Harness의 모델 분류 성공을 발표 화면 통합 성공으로 바꾸지 않는다.

7~8분 발표에서는 검증된 Long/Short 자동청산·실제60초 유예·Solidity 조건검증·Receipt/Event·DB journal/감사를 중심으로 한다. AI는 현재 '보조 조회 구조를 구현했고 실모델 통합 평가 중 지연/불확실 전송 경계를 확인했다'는 구현·한계 설명으로 제한한다. **'이 포지션은 왜 청산됐고 체인에 확정됐나요?'의 라이브/녹화 답변 시연은 이번 결과만으로 권장할 수 없다.** AI가 청산을 실행하는 것처럼 설명하지 않는다.

## 9. 종료·증거 및 최소 후속 작업

- 새 DB container만 정확한 immutable ID/소유 label을 확인해 종료했다. 최종 network-none/exited0 확인. 새 DB/volume/index와 cold copy·export·manifest·ledger·approval·STOP를 보존했다.
- Relay를 종료했고 자동 재연결·새 승인·다른 child·새 ledger·비용 환급을 하지 않았다. SQLite control은0이지만 STOP 파일과 RESERVED가 남아 이후 전송은 차단된다. 이를 성공 종료 또는 자동 재개 가능 상태로 바꾸지 않는다.
- 부모 ledger SHA는 기존 `db4e7a9995cd3603a64eeb7c41978e1f971c3a97ac0d2f6902c35c9a6cd4c292`와 일치한다. 기존 사용자 변경/거래DB/Anvil/KRX/Google/NXT/시세증거는 건드리지 않았다.
- private 증거: questions.json(4문항), usage001~007, budget.sqlite(8예약), STOP, stopped.json, process-result.json, 실패 XML/로그, READY 복사 검증. completion.json은 없다.
- 실행 중 sourceHash는 유지됐고 제품 기능/평가 질문·기준·금융 정책은 변경하지 않았다. 보고서 외 이번 실행 도구/secret/DB 자료는 ignored runtime에만 있다. 커밋/merge/push/배포는 하지 않았다.

필요한 다음 작업은 **유료 재시도보다 무과금 실패 관측 보완안 검토**다. 최소 후보는 retrieval의 고정 단계/실패 코드/소요시간과 guarded transport의 안전한 HTTP상태·deadline 구분을 비밀 원문 없이 보존하는 것이다. 이번에는 구현하지 않았다. 원인 없이 시간 제한을 늘리거나 정책을 완화하지 않는다.

누적 예약의 산술 잔여는US$1.69799460이지만 **새 실행 허가가 아니다**. 기존 child claim은 이미 소비됐고 미확정 RESERVED가 있어 자동 재개/추가 child는 차단 상태다. 불확실 요청과 향후 실행 방식을 증거를 보존한 채 별도로 결정해야 하며, ledger/STOP 삭제나 부모 예산 재부여로 우회할 수 없다.

## 10. 결과 독립 검토와 최종 보존 확인

`review_q2_live_execution`의 결과 문서 재검토도 **발견된 필수 수정 없음**이다. 제한적 조회PASS3/자연어종합0/전체BLOCKED/Q11~16NOT_RUN 구분, Q6 원인 미확정, 비용 합산, STOP+RESERVED 차단과 발표 제외 권고를 코드·문서로 대조했다. 직접 API·DB·private 원본·테스트를 확인한 것은 아니다. 선택 제안인 '원본 기존 중지 상태 확인' 표현을 반영했다.

마지막 조회에서 무과금 gate XML22PASS, 실제 평가 XML1FAIL/0SKIP 및 timestamp14:19:22Z, sourceHash 불변을 확인했다. 사용자 보호5파일 SHA256은 시작 기준과 모두 일치한다. 신규 private orchestration/approval/ledger는 `git check-ignore`로 제외됨을 확인했고 `git diff --check`도 통과했다. 기존 미커밋 변경은 그대로이며 이번에는 새 결과 문서만 공개 변경으로 추가했다.
