# Windows 재부팅 후 Docker 복구 및 Q12 실제 모델 평가

> 2026-10-21. **Docker 관리 API 복구 PASS / 실제 Q12 전송·Tool·RAG 실행 PASS / 답변 종합 품질 PARTIAL / 실제 웹 AI 시연 NOT_RUN.**

## 1. 결론과 검증 범위

사용자 Windows 재부팅 후 Docker Client/Server와 info가 정상 응답했다. 기존 Google/KRX/playground 컨테이너는 중지 상태이며 자동 시작·복구하지 않았다. 이들의 이미지·mount·파일·network 보존을 확인했지만, 중지된 PostgreSQL/Anvil 내부 정합성·canonical baseline·Google 인증/WS는 새로 검증하지 못했다. **관리 API 복구와 모든 서비스 정상화를 구분한다.**

Q12 전용 신규 PostgreSQL/pgvector에 기존 READY 벡터를 RO cold copy하고 실제 JDBC/SCRAM/IndexLease/owner/checksum을 확인한 뒤, 승인된 embedding1회와 LLM1회만 실행했다. 한국어 설명을 확보했으며 과거 Q5 RAG_UNAVAILABLE/Q6 전송 불확실은 이번 단일 실행에서 재발하지 않았다. 이것이 과거 장애의 근본 원인을 확정하거나 일반적인 재발 방지를 보장하지는 않는다.

모델의 선택 사실14개와 손익/위험 산식은 원본과 일치했다. 다만 자연어 본문은 구체적인 진입가·노출수량·5% 산식의 숫자를 충분히 풀어 설명하지 않았고, 보존 snapshot의 LIQUIDATED/GRACE 병존 및 fresh RPC 부재를 유보했다. 따라서 사용자 요구 전체를 **완전 품질 PASS로 승격하지 않고 PARTIAL**로 기록한다. Q14/Q15 추가 호출, 실제 웹 AI 검증, 원본 병합은 하지 않았다.

## 2. 실행 identity와 보존

- worktree/branch: 기존 `deployment/runtime/ai-failure-worktree`, `feat/ai-failure-observability`.
- HEAD: `f9353c33244565b30fb130dfb6159eab441a3523`.
- sourceHash: `86dcb3cef1b41a98f761a7c53286b252aca4f8ba8cfab6738729ab330de07fa8`.
- corpusHash: `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`.
- 새 continuation: **`eval_d11433b5acf445a3b0466b8c5eaf2901`**, 질문 `[12]`만 포함.
- private 실행 폴더(본 worktree 기준): **`deployment/runtime/ai-q12-postreboot-20261021-01/`**.
- 새 DB/user: `exchange_ai_eval_d11433b5acf445a3b0466b8c5eaf2901`, loopback Relay60071, container network-none.
- 기존 부모 `eval_7a8f25154337449e9f49d676b4d5fbb7` 및 child `eval_d55c916420ae4a28a37a0ef11b1b2e6f`의 local 예약을 합산했다. Q6 불확실 RESERVED1개도 전액 포함한다.
- 단일 continuation claim을 새 registry에 생성했다. 이미 사용한 claim을 재발급하거나 원본 child claim을 덮어쓰지 않았다.

실행 전 현재 root/UI 사용자 변경과 원본 부모/child 증거를 포함한 **107파일**을 해시 기준으로 기록하고 실행 후 **107/107 동일**을 확인했다. 이는 이번 재부팅 후 측정한 기준과의 비교다. 이전 세션의53개 체크섬 목록을 재사용한 결과처럼 표현하지 않는다. 원본 root HEAD/status 및 UI worktree를 보존했다.

이전 [Docker 진단](docker-server-q12-diagnosis-2026-10-21.md), [정상종료 시도](docker-normal-restart-q12-attempt-2026-10-21.md), [Q12 BLOCKED](ai-q12-real-evaluation-2026-10-21.md), [continuation 준비](ai-q12-continuation-preparation-2026-10-21.md), [Q5/Q6 조사](ai-agent-recovery-investigation-2026-10-21.md)는 당시 기록으로 유지한다.

## 3. 재부팅 전후 Docker와 기존 서비스

| 항목 | 재부팅 전 | 이번 실제 결과 |
|---|---|---|
| Docker version/info | Server timeout | Client/Server **28.4.0**, Desktop4.47.0, Linux Engine 응답 |
| context | desktop-linux, named pipe | desktop-linux 유지. context 교체 없음 |
| C 드라이브 가용 | 직전복구시도29.25→28.85GiB | **48.06GiB**, 새 측정 |
| Google5174 | 정적루트200 | Google 컨테이너4개 Exited255. 자동시작 안 함 |
| Google image | 보존 images.json 존재 | web/backend/PG/Anvil4개 실제imageID **모두 일치** |
| Google PG/Anvil 저장 | persistent bind mount | 기존 mount경로·PG_VERSION·global/pg_control·chain/state.json 존재 |
| KRX PG/Anvil | 이전부터 중지 | Exited255 유지, 기존 mount·PG control/state.json·baseline.json 존재 |
| Google/KRX network | 보존 구성 | Google network 및 KRX isolated/host-access network inspect 성공 |
| 기존 DB/체인 내부 identity | 실행검사 미확보 | **NOT_RUN/INCONCLUSIVE**. 중지환경을 기동하거나 재구축하지 않음 |

설정 파일 이름은 실제로 `run.json`, Google `images.json`/compose.yml, KRX `run.json`/infra.json/bootstrap.json/baseline.json이다. 초기 존재 조회에서 `manifest.json`이라는 파일이 없었던 것을 manifest 소실로 오인하지 않았다.

첫 info는312컨테이너/Running4를 반환했지만 이어 ps/df/stats 시점에는312개 모두중지였다. 재부팅 직후의 서로 다른 시점으로 구분하며 임의로 둘을 같은 snapshot으로 맞추지 않는다. 이번에 기존 컨테이너를 stop/start한 것은 아니다. Google 인증·API·개인WS가 정상이라고 보고하지 않는다.

## 4. 자원 사용량과 반복 장애 원인

읽기 전용 `docker system df -v` 성공(708행), 요약 df 및 `docker stats --no-stream`을 실행했다. 모든 CLI에 제한시간을 사용했다.

| 구분 | 이번 관측 |
|---|---:|
| Images(df 시점) | 95개 /42.16GB, active13, reclaimable38.98GB(92%) |
| Containers | 312개 / writable layer4.719MB, active0 |
| Local Volumes | 35개 /1.376GB, active참조22, reclaimable443.3MB |
| Build Cache | 251개 /3.925GB |
| Engine 메모리 한도 | 7,194,066,944bytes(약6.70GiB), CPU12 |
| Windows 가용RAM | 2,181,420KiB(약2.08GiB), 총14,528,572KiB |
| WSL2 메모리(평가종료후) | MemTotal7,025,456KiB /MemAvailable5,794,356KiB, swap2,097,152KiB전체가용 |
| docker_data.vhdx Windows 파일크기 | 49,826,234,368bytes(약46.40GiB) |
| ext4.vhdx Windows 파일크기 | 146,800,640bytes(140MiB) |
| stats 현재컨테이너별메모리 | 실행컨테이너0이라 출력없음. 당시peak메모리 측정 아님 |
| Q12 READY 원본volume 사용량 | **49.29MB**, 소유라벨 일치·정상종료0·network-none |

이상메트릭의GB/GiB 단위를 구분한다. 공유image layer·cache·VHDX크기를 단순합산하지 않는다. 원본volume약49.29MB의cold/data복사와새PG작업은48.06GiB여유에서안전하게준비가능하다고판단했다. 기존volume은ROmount이며원본DB를기동하지않았다.

반복 장애 원인에 관해 **확정한 사실**은 과거 낮은C여유·낮은가용RAM·API/정상종료deadline과 이번재부팅후API복구다. 이것만으로 메모리누수·deadlock·ENOSPC가원인이었다고확정할수없다. 이전제한logtail의panic/ENOSPC/OOM직접근거는없었고, 이번Application오류이벤트최근24시간중최대150건을필터링한범위에서Docker관련provider/message의정제항목을확보하지못했다. 전체Windows/WSL이벤트를감사한결과는아니다.

**가설:** 장기실행Desktop/backend의관리경로stuck,호스트저장공간/메모리압박,다수과거fixture·cache누적이영향을줬을수있다. 재부팅후정상화는이들중특정원인의증명이아니다.

삭제는없다. build cache와미참조과거이미지는향후정리조사후보이나`reclaimable`만으로안전판정을하지않는다. 중지컨테이너·manifest의imageID참조와KRX/Google/Futures최신이미지,private증거재현성을확인하기전개별삭제후보ID를확정하지않았다. namedvolume은크기가작고보존위험이커서정리대상으로추천하지않는다.

## 5. 무과금 gate와 실제 실행

| 단계 | 판정 | 실제근거 |
|---|---|---|
| oldledger/STOP/claim/만료승인/hash | PASS | RO snapshot/legacyverify. carry11LLM/37embedding/US$0.30200540, RESERVED포함 |
| source/corpus/Long10증거 | PASS | 기존v3capture와현재hash일치,증거hash일치 |
| 단일claim | PASS | 사용전없음확인→이번run의단일claim생성 |
| ROcoldcopy | PASS | 정상종료0의기존READYvolume을readonly로복사. 원본start/reset없음 |
| 실제JDBC/SCRAM/pgvector/IndexLease | PASS | `EvaluationIndexCopyTest` **1PASS/0FAIL/0SKIP**, Javaexit0 |
| 신규index | PASS | 9문서/88청크/1536dim, 원본metadata/vector일치·신규ownerREADY |
| checksum | PASS | dataSha256 `9888d43fb6a4056645efcf1c93fbd061246ef016ffbdd7244174e536b3a0869e` |
| Q12dry-run/Guard/approval | PASS | source동일capture재사용후assess. 새identity/context/questions/localLimits에binding |
| 실제Q12 | PASS(실행) | `ApprovedAiModelEvaluationTest` **1PASS/0FAIL/0SKIP**, Gradle50초. 품질PASS아님 |

승인파일의capUsd2/llmCalls32/embeddingCalls80은원본전체정책호환필드다. 새예산2달러가아니며, 별도immutablelocalLimits의1/1/input32768/output1600/embedding1032/newCap0.10114064와carry를함께강제했다. 승인에cumulativeCap0.40314604·모델·retry0을명시했다. key는기존private파일을참조했고값을복사/출력하거나JVM환경에주입하지않았다.

실제모델평가시각은 **2026-10-21T03:54:18~03:54:31Z(12:54KST)**다. 별도Relay는900초/최대96연결/loopback/network-none으로제한했다. 평가후새runSTOP과control.stopped를설정하고소유권확인한**새Relay/새AI DB만정지**했다. 새DB·coldcopy·ledger·승인·원문답변·진단·volume은보존했다. Q14/Q15로넘어가는USER품질PASS파일은생성하지않았다.

## 6. Tool/Skill/RAG 실행 의미

- Skill `futures-liquidation-diagnosis` version1. LOAD_POSITION/getFuturesPosition **SUCCESS**, LOAD_FUTURES_EVIDENCE/getFuturesLiquidationEvidence **SUCCESS**, CHECK_EVIDENCE **OBSERVED**, RETRIEVE_POLICY **SUCCESS**, SUMMARIZE **VALIDATED**.
- 모델계획호출은없다. 명시Skill의고정Tool2개→scopedRAG1회→종합LLM1회다. 실제Agent메트릭 Tool2/RAG1/LLM1,latency12,757ms.
- ToolDispatcher/Skill/소유권handler는실제로실행됐으나**거래조회어댑터는보존된실제E2Esnapshot을주입한fixture**다. 이번에거래DB/RPC를신규live조회한것이아니다. envelope의source이름TRADING_DB/TRADING_DB+RPC를현재live접속증거로해석하면안된다. fixture가captureScope와freshChainVerification=false를명시했고답변도이를유보했다.
- 실제USER본인/타인404·ADMIN Tool금지검사는동일harness의controlledownershipfixture로확인했다. 실제웹JWT/REST/STOMP인수결과로대체하지않는다.
- RAG는실제Embedding+새pgvector검색으로5chunk를반환했다. 최종인용은선물정책직접관련2chunk와Tool2개,총4개다. 나머지3검색결과는현물견적/주문정책으로이번본문에인용하지않았다. 이번1질문성공을고정golden검색전체PASS라고표현하지않는다.

인용2개는 `docs/ai-knowledge/onchain-settlement-policy.md` version3의 “선물 FuturesEngine의 별도 확정 경계”다. ID는각각`adbb95c2…10028e2`, `4227e55c…a76d7e`이며private결과에전체ID가보존돼있다. 역할USER,similarity약0.5991/0.5812다. 5%산식·60초·raw복구/Receipt/Event·선택범위검증·DB담보·house pool한계를지원한다.

## 7. 실제 한국어 답변 — 원문(인증정보 없음)

> 기록상 이 Long 포지션은 가격 하락으로 위험 상태가 된 뒤 마진콜 유예가 지난 시점에도 위험으로 판정되어 청산되었습니다. 정책상 E가 MM 이하이면 마진콜 위험이며, 온체인 마진콜 확정 뒤 실제 60초 유예 후 새 관측가격에서도 위험할 때 강제청산합니다. 선택된 마진콜과 청산 이벤트 및 journal 범위는 VERIFIED이므로, 해당 범위에서는 청산 기록이 정합합니다. 다만 VERIFIED는 선택된 최대 두 건의 마진콜·청산 canonical Receipt/Event 및 journal 검증 범위일 뿐 전체 감사나 지급능력 보증은 아닙니다. 선물 담보는 온체인 예치가 아닌 DB 모의자산 장부의 담보입니다.

실제함께반환한불확실성:

1. “제공된 증거는 보존된 E2E 스냅샷이며 freshChainVerification은 false이므로, 조회 시점의 신선한 RPC 재검증 여부는 확인할 수 없습니다.”
2. “포지션 스냅샷의 riskStatus는 \"GRACE\"로 남아 있으나, 별도 청산 operation은 databaseState \"CONFIRMED\" 및 verification \"VERIFIED\"입니다. 이 상태 불일치의 정확한 갱신 원인은 제공된 기록만으로 확인할 수 없습니다.”
3. “기록은 청산 당시 위험 판정과 유예 기한 경과를 보여주지만, 유예 기간 중 가격 회복 또는 추가담보가 있었는지 여부는 제공되지 않았습니다.”

모델structuredstatus는ANSWERED이나,Agent최종status는**PARTIAL**/HTTP200/error=null이다. `AgentService.java`의 종합후분기(현재약197행)가modelUncertainties가남으면PARTIAL로내린다. 이는모델전송/Tool/RAG오류가아니고불확실성표시정책의결과다. JUnit의COMPLETED/REVIEW_REQUIRED판정도자동정답PASS가아니다.

`riskStatus=GRACE`는이번모델이만든값이아니다. `EvaluationToolFixture.preserved()`는보존capture의position을deepCopy하고범위표시만추가한다. 원본capture에도LIQUIDATED/GRACE가병존했다. 이위험상태표현이원래DTO의종료후표현인지결함인지는이번에수정하거나확정하지않았다.

## 8. 실제 근거·산식·설명 대조

| 항목 | 보존원본decimal/기록 | 판정 |
|---|---|---|
| 방향·배수 | LONG /10 | Tool일치. 모델사실목록LONG확인 |
| 진입/평가가격 | 75000.00000000 /60000.00000000 | Tool/RAG에근거있음. 본문은하락만설명,진입가숫자는생략 |
| 격리담보 | 100000.000000000000000000 | Tool원본. 본문은정확한담보금액생략 |
| 수량 | 13.333333333333333333 | E18수량. 본문과선택facts에서노출수량설명생략 |
| 미실현손익 | -199999.999999999999995000 | Q×(60000−75000),원본과정수계산일치 |
| Equity | -99999.999999999999995000 | 담보+미실현손익,모델facts정확일치 |
| 유지증거금 | 39999.999999999999999000 | 현재명목가치의5%,E18올림,모델facts일치. 본문은5%계산을숫자로풀지않음 |
| 위험판정 | E<=MM /atRisk=true | 원본·정책·본문일치 |
| 유예 | START_CALL체인1792454685→deadline1792454745 | 정확히60초 |
| 청산확정시각 | 1792454753 | START_CALL부터**68초**,deadline+8초. 모델본문은60초정책만설명 |
| 신규가격관측 | 1792454751 /60000.00000000 | 유예deadline후재관측·atRisk확인. 임의회복/추가담보없었다고단정하지않음 |
| 실현손실 | -100000.000000000000000000 | 격리담보로손실제한,미실현전체손실과구분 |
| badDebt | 99999.999999999999995000 | 음수Equity의절댓값,별도기록. 모델facts일치 |
| 선택journal delta | wallet/locked각-100000.000000000000000000 | 청산operation의기록. 전체journal/house잔고감사PASS아님 |
| 온체인담보/지급보증 | onchainEscrow=false /payoutGuaranteed=false | 본문에정확히한계명시 |

무과금원본대조계산은`backend/src/main/java/com/pricetrack/exchange/futures/math/FuturesMath.java`와동일하게E18/E8정수나눗셈·ceil규칙을사용했다. unrealized/equity/maintenance/realized/badDebt **5/5일치**,모델선택JSONpointer사실 **14/14정확문자열일치**. 단지관련단어가나왔다는이유로채점한것이아니다.

보존체인확정근거(이번freshRPC아님):

- START_CALL tx `0x866789a25632921b2cc8d5db7c366f25aa61876306e3800df528d60697f82728`, block52.
- LIQUIDATE tx `0xb3459a30188845569a59096231dbeaa0c76ff32e3d660816767eb9e579c4ce8b`, block88/hash `0xe2f59c7c8ca44e0a727570cc583bab5fd0c6a7e5be4fcacd365665637df461f2`.
- 두operation모두CONFIRMED/VERIFIED,requiredConfirmations1,선택canonicalReceipt/Event및journal검증범위다. FuturesEngine operation/report 이벤트조건과DB연결을검증했던보존E2E결과를읽은것이며지금새로계약을실행한것이아니다.

**품질평가:** 핵심원인·선택확정근거·한계는정확하고AI가청산을실행했다는허위주장은관측하지못했다. 그러나발표용으로초심자가숫자계산을바로이해할만한완전한자연어설명은아니다. 추가담보/가격회복이력을확정하지않는태도는적절하다. PARTIAL을해제하려고질문·프롬프트·기준을바꾸거나추가유료호출하지않았다.

## 9. 단계별 실제 관측

| RAG단계 | elapsed(ms) | 단계종료remaining(ms) |
|---|---:|---:|
| CORPUS_LOAD |55|4942|
| INDEX_ACTIVE |307|4627|
| EMBEDDING_BUDGET |0|4624|
| EMBEDDING |3843|775|
| VECTOR_VALIDATION |2|771|
| CANDIDATE_BUDGET |0|770|
| CANDIDATE_SEARCH |64|704|
| FILTER_RESULTS |2|700|
| APPROVAL_VERIFY |22|675|
| DEADLINE_CHECK |0|673|

전단계OK,Skill의RAG전체4330ms. shared5초안에서통과했지만여유673ms로크지않다. 과거Q5의정확한하위실패단계를이번성공으로소급확정하지않는다.

Pythonembedding전송: request()반환156ms,HTTP200헤더/JSONdecode3750ms(timeout4618ms). Javaframeflush3ms→brokerreply3812ms→decode3822ms. PythonLLM전송: request()반환62ms,HTTP200헤더8140ms→decode8265ms(timeout10000ms). Javabrokerreply8283ms→decode8285ms,Skill종합8350ms. API재시도0,Guard두예약모두OBSERVED다. 요청반환·헤더·decode·usage검증의경계를구분했다.

원본trace는private폴더의`retrieval-diagnostics.jsonl`, `java-transport-diagnostics.jsonl`, `python-transport-*.jsonl`에보존했다. 이파일들은고정phase/code/시간만포함하며prompt/Authorization/원문오류를일반로그에복사하지않았다.

## 10. 실제 사용량과 무환급 예약

| 구분 | LLM | embedding | input예약 | output예약 | embedding예약 | 무환급예약USD |
|---|---:|---:|---:|---:|---:|---:|
| 과거root+child |11|37|87575|6800|73395|0.30200540|
| 이번신규 |1|1|18054|1600|140|**0.06433780**|
| 전체누적 |12|38|105629|8400|73535|**0.36634320**|

이번실제usage: LLMinput **3598**, output **798**, embedding **60** tokens. 예약은actualusage로환급하지않았다. 기존Q6불확실예약도계속차감한다. 승인누적상한US$0.40314604이내이며전체US$2를새로부여하지않았다.

기존standarduncached단가(input$2/M,output$12/M,embedding$0.02/M)로산정한이번예상청구액은 **US$0.01677320**. 실제청구서확인값은아니다. cached-input할인이나cache-write배분을확보하지못했다. 프로젝트의더보수적인input$2.5/M을actualtokens에적용하면US$0.01857220이며이역시청구서값아니다. 예약US$0.06433780과구분한다. 모델공식단가/구성은[OpenAI지정모델문서](https://developers.openai.com/api/docs/models/gpt-5.6-terra)를확인했고모델을교체하지않았다.

## 11. 발표 활용과 남은 문제

1. **현재실제UI라이브AI시연은보류.** harness실행성공이지Chrome실제질문/응답·로그인/WS까지검증한것은아니다. 이번승인밖이라실행하지않았다.
2. 확보된답변/Tool근거를기술설명자료로사용할수있다. 녹화시연을만들려면별도승인된UI연동검증을먼저하고,PARTIAL·보존snapshot·freshRPC아님을표시해야한다. 이미녹화영상이있다고주장하지않는다.
3. 발표의주요데모는검증된서버Worker→FuturesEngine→Receipt/Event→DB정산이며,AI는읽기전용보조설명이다. AI가청산을결정하거나담보/지급능력을보증한다고설명하지않는다.
4. 현재자연어본문의수치설명누락과LIQUIDATED/GRACE병존의원래표현의미는후속검토대상이다. 이번에는금융/프롬프트/Tool/UI코드를수정하지않았다.
5. RAG/LLMdeadline여유와N=1평가한계를고려한다. 이번성공으로안정적인라이브성능·모든질문정답을보증하지않는다.
6. Google/KRX/playground서비스는중지상태그대로이며향후기동·DB/chaincanonical검증은별도작업이다. 중지자체는독립Q12coldcopy의실행을막지않았지만전체시스템인수완료는아니다.

## 12. 산출물과 종료

private폴더에원본답변`question-result.private.json`/questions.json,usage2개,completion,authorization,indexcopyproof/SQL XML,실제평가XML,source-index/provenance,ancestor/approval/ledger/STOP/trace를보존했다. 새DB정지exit0,volume삭제없음. 원본parent/child가변경되지않은것은107파일해시대조로확인했다.

실행중조회보조명령이cp949로한국어결과를읽다실패한1회는UTF-8로다시읽었다. 이는유료평가재실행이아니며추가POST0이다. 실제평가harness는처음부터제품동일JavaTimeObjectMapper를사용했고Instant직렬화오류는재발하지않았다.

WSL메모리조회초안은PowerShell quoting으로실행되지않아파이프없는`wsl --exec cat /proc/meminfo`로읽었다. 이는자원진단보조명령의수정이며제품/평가harness변경이나모델재실행이아니다. 실제XML확정수치는indexcopy1/1·evaluation1/1,각FAIL/ERROR/SKIP0이다.

제품/harness코드변경0,원본병합·commit/push/배포0,새기능확장0. 이번변경은결과문서와누적이력링크뿐이며새독립코드검토는호출하지않았다. Q14/Q15·웹실인수는NOT_RUN유지하고추가사용자판단을기다린다.
