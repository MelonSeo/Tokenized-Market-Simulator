# AI 발표용 실제 웹 통합 — 1단계 무과금 검증

작성: 2026-10-21. **실제 Chrome→Spring REST/JWT/Agent→거래 PostgreSQL/Anvil Tool PASS. 유료 자연어 답변/RAG 검색은 NOT_RUN.**

## 1. 기준과 보존

- 브랜치 `feat/ai-presentation-integration`, 기준HEAD `f9353c33244565b30fb130dfb6159eab441a3523`, 기존 미커밋 발표 UI 위에 이번 harness/test 변경을 추가했다.
- 이전 [오프라인 UI 결과](ai-presentation-integration-2026-10-21.md)의143테스트/fixture 성공과 이번 실제 데이터 검증은 별도다.
- Q12의 실제 모델 결과는 AI worktree `claude-docs/ai-q12-postreboot-real-evaluation-2026-10-21.md`에 보존했다. 그때의 답변·거래·벡터를 이번 새 거래의 결과로 사용하지 않았다.
- 기존 root/UI/AI worktree, 부모/child/Q12 ledger·STOP·RESERVED·claim·approval 및 private evidence를 수정하지 않았다. 원본 merge/commit/push/배포 없음.
- Docker Server28.4.0 응답, C 여유48,119,123,968bytes 확인 후 시작했다. 기존 전체컨테이너315/실행4가 있었으며 이들을 시작/정지하지 않았다. 현재 기존Google이 중지돼 있다고 추정하지 않고 사용자환경 상태를 보존했다.

## 2. 재사용과 변경 범위

`deployment/phase-a.py`의 신규session/fixture·fresh키·소유권·loopback·subnet재시도·2초시각guard·불변baseline·동일raw정산 절차를 재사용했다. `futures-ai-web` case만 추가했으며 기존 `futures-browser` 테스트·driver를 단계옵션으로 사용한다. 본 case는 Long10 한 건에 집중하고 기존 전체 Long/Short/회복 데모를 반복하지 않는다.

거래정책/제품Java/Solidity/프롬프트/timeout은 변경하지 않았다. 새 worktree에 없었던 공개 `contracts/lib/forge-std`, `openzeppelin-contracts` 의존성만 로컬 공개원본에서 설치했고781파일 해시동일 확인. 기존lib/이미지/private/state 복사는 없다.

주요 신규/수정 경로:

- `deployment/phase-a.py`: 새case, 기존 network-none AI DB probe + bounded loopback Relay 재사용. 고유AI run/volume 생성. own-label/immutableID 기준 정지, 볼륨·증거 삭제 없음.
- `backend/src/test/.../support/PhaseAFixture.java`: 정확한 신규case+명시 env gate에서만 Tools/Agent/Skill 허용. app.ai.enabled=false, key빈값, guarded:stdio 전송만 허용. 다른case의 AI차단 유지.
- `backend/src/test/.../futures/chain/FuturesBrowserPostgresAnvilE2ETest.java`: 기존 실제제품Synthetic tick/worker/체인/정산/감사 경로, 새AI DB 식별·pgvector검증, 별도사용자, 새Chrome 옵션 및 실제Agent결과 검증.
- `FuturesWebStage1Configuration.java`, `FuturesWebStage1TransportTest.java`: **test classpath 전용** provider/Agent/retriever wiring. 실제 OpenAiProvider 요청직렬화를 사용하되 transport는 무조건 `AI_WEB_PAID_EXECUTION_NOT_APPROVED`로 거부한다. HTTP client·키·승인파일reader·live전환옵션이 없다.
- `deployment/futures-browser.mjs`, `futures-browser-assertions.mjs`, `test_futures_browser.mjs`: 실제P0 카드의 정확한 상세값과UUID로 검사. 종전배열순서/이전라벨 기반검사를 최신표시에 맞췄다. AI HTTP 요청/응답·Tool과권한경계·1366/390px캡처. 인증header/JWT/비밀번호는 결과에 저장하지 않는다.
- `deployment/ai_web_demo_scope.py`, `test_ai_web_demo_scope.py`: **후속 유료adapter용 scope 후보**, 기존 durableGuard 위에 단일웹제출·LLM/embedding각1개의영속claim·토큰상한·STOP을 추가한 무과금 fake-sender검증. 현재실제웹에 유료연결되거나 활성화된 코드가 아니다. 이구분을 숨기지 않는다.

## 3. 신규 환경 identity와 저장 역할

- session `phase-a-20261021051458-2ca805e4`.
- child `futures-ai-web-0ac55400`.
- 본 worktree private경로 `deployment/runtime/phase-a-20261021051458-2ca805e4/fixtures/futures-ai-web-0ac55400`.
- 거래DB `exchange_futures_e2e`, PostgreSQL loopback32690, Anvil loopback36894/chain31337/2초실제블록/독립키·계약. 이 DB는 phase_a_fixture_identity를 확인하고 새 schema만 초기화했다.
- AI DB `exchange_ai_eval_bc3f96ac6acb4926a307857096abd1d7`. 거래DB/접속자와별개, 신규namedvolume·network-none, loopbackRelay900초/최대96연결. pinned pgvector이미지. 실제JDBC/SCRAM 연결·current_database/current_user·vector(3) 연산 및 별도AI schema 초기화 확인.
- AI Index는 **EMPTY_NO_EMBEDDING**. 이전 READY벡터/index/owner/lease를 복사하거나 변경하지 않았다. 신규index를READY라고표현하지 않으며 IndexLease실인수·검색·인용도 NOT_RUN이다.
- startupDB-host차이0.250890970초/왕복0.2323856초, 오차+불확실성약0.3671초로 기존2초guard통과. 허용치나OracleTTL변경없음.

실행 후 새 backend/web/Chrome/Relay 종료, 신규PG/Anvil/AI DB 정지. 데이터/chain state/baseline/AI volume은보존한다. 기존환경을 재생성·초기화·정지하지 않았다.

## 4. 실제 거래·웹 결과

신규USER가 실제Chrome에서 로컬로그인/JWT획득→Faucet1회100만mKRW→100,000담보 Long10 preview/확정→Synthetic privatecheckpoint의실제제품tick→Worker마진콜→실제유예→청산→개인WS/DB정산→AI진단선택→AgentPOST를 실행했다. 일반사용자 가격조작API/시간warp는없다.

| 항목 | 이번 실제 결과 |
|---|---|
| position | `8f2ecf86-3ab2-4952-9dc3-e4071be4418c`, LONG10, LIQUIDATED/GRACE, pending=null |
| 진입가격/수량 | 75000.30276749 /13.333279508219064374 |
| DB담보/확정손익/badDebt | 100000 /−100000 /100666.801448482654854643 mKRW |
| OPEN | `0x9098d34f95c46c850e505508494d4d0a030a9d948282858d54a1f5e6252d4f3a`, block49 |
| START_CALL | `0x98e9d892379d3844088712ffc18bbc77892d3d9960d0f61b397b4387599a44c8`, block50, timestamp1792561800 |
| 유예deadline |1792561860, 실제60초 |
| LIQUIDATE | `0x1df97f938ed3f4d79956d7750d346db2fedf42db0b25056096a93544ac177993`, block87, timestamp1792561872 |
| 실제유예경과 |call→청산 **72초**, 새가격observedAt>=deadline 검증 |
| Receipt/Event/DB | statusOK/canonical blockHash/2logs/operationCONFIRMED/positionLIQUIDATED. 잔고lock합0·journal wallet+house합0 |
| 개인WS |실제FUTURES_UPDATED6건, OPEN/CALL/LIQUIDATE별확정operation연결 |
| 선물감사 |**MATCH**, transactions3/3, events6/6, coverage.complete=true |
| 기존자산대사 |**MATCH**, 새환경의초기불변baseline기준. 담보온체인예치/지급보증아님 |

## 5. 실제 웹→Agent→Tool과 무과금 경계

AI 버튼은 정확한UUID·고정`futures-liquidation-diagnosis`를입력에연결하고 자동요청하지않았다. 사용자의명시제출은실제`POST /api/ai/agent/answers`로전송됐다. 두번submit해도같은진행요청은1회다. 이것은offline HTTPfixture가아니라실제SpringController/Agent/Skill/ToolDispatcher다.

- Agent HTTP200/**PARTIAL**, Tool2, retrieval1, model0, latency205ms.
- `getFuturesPosition`/`getFuturesLiquidationEvidence`는이번새거래DB·localAnvil RPC를실제로조회했다. Q12보존snapshot을주입하지않았다.
- 선택증거**VERIFIED**: currentdeployment/역사블록position/canonicalReceipt와Event/confirmation/journal/동일DBcut을ToolReader가검증했다. freshRPC는**이요청시점의선택범위**이며계속유효한보증/전수감사가아니다.
- 빈AI Index는`AI_INDEX_NOT_READY`를일으켜retrieval을중단한다. Agent외부표현은`RAG_UNAVAILABLE`, `KNOWLEDGE_EVIDENCE_MISSING`. Embedding0/LLM0이다.
- 화면의한국어안내 “현재 사실은 조회했지만 관련 규칙의 근거를 확보하지 못했습니다.”는**서버고정안내**이며새모델답변이아니다. 원본Tool수치/판정/부분응답을실제화면에표시했다.
- 인증없음401, 타인Tool404, 타인Agent404, USER의ADMIN Tool403, 없는UUID404을 확인. 타인토큰/인증응답은일반증거에저장하지않았다.

### 일반 웹의 실제 모델 연결 경로

일반제품은 AgentController→AgentService→명시Skill→Tool2→AuthorizedKnowledgeRetrieval→PgKnowledgeStore.active→OpenAiProvider.embed→검색→OpenAiAgentProvider.synthesize→OpenAiProvider.postInternal→HttpClient다. 이constructor의evaluationTransport는null이다. 기존500회프로세스상한은지속예산/선예약/원격불확실성STOP이아니다. **일반웹은평가Guard를자동으로통과하지않는다.**

이번test전용구성은 app.ai.enabled=false로일반provider생성을막고, 기존`forGuardedEvaluation` hook만사용한다. startup별도embedprobe가전송직전거부됨을확인했고, 웹Agent는빈Index에서모델호출전에중단했다. 새유료요청/기존ledger예약변경/claim재사용은0이다.

## 6. 후속 유료 웹 실행 안전구조와 남은 작업

`WebDemoScope`는이미검증된EvaluationGuard에**호출자제공lineage-boundcarry**가없으면거부한다. 하나의서버run/정확한source·corpus·user/position/questioncontext에1회제출claim을 영속저장하고, LLM·embedding별O_EXCL1슬롯만허용. 재시작/중복worker/중복요청도소비슬롯을다시만들지않는다. Token상한은input32768/output1600/embedding1032, 전역Guard의BEGIN IMMEDIATE선예약·모델검증·불확실RESERVED를유지한다. 하위예외면새runSTOP+guardstop, 환급/자동재시도없음.

**아직일반웹live에배선하지않았다.** 현재실제웹서버의transport는무조건deny다. 따라서비용상한준비를실제유료웹전송강제PASS로보고하지않는다. 다음liveadapter는서버수명전체에단일Guardedtransport만노출하고, 키없는JVM/단일Pythonsender, native직접HTTPprovider없음, 새run/허용user·position·고정Skill/context검증, 누적3ancestor읽기전용검증, 승인기간·watchdog·단일claim을 충족하는필요가있다. 이최종배선/새READYindex/read-only복사·IndexLeasegate・실제payloaddryrun는 남은**무과금gate**다.

과거Q12는PARTIAL이고기존continuation는 Q12→Q14→Q15방향의수동품질gate가있다. 새웹시연을Q14나Q12재평가로가장하거나기존claim/STOP을 풀어서는안된다. 별도웹시연branch승인을받아전체lineage를승계해야한다.

## 7. 조건부 유료 승인 요청안 — 아직 실행 불가

위무과금gate완료및새owner/index/source/context확정후, 현재Long10하나·사용자한명·브라우저한탭·명시Skill질문1회만을 추천한다.

| 제한 | 제안 |
|---|---|
| 모델/새요청 |gpt-5.6-terra최대1, text-embedding-3-small최대1 |
| 입력/출력/embedding예약 |32768 /1600 /1032 |
| 신규보수예약 |**US$0.10114064 이하** |
| 기존누적예약 |US$0.36634320, Q6 RESERVED포함, 무환급 |
| 제안최대누적 |**US$0.46748384**, 전체US$2이하 |
| 누적요청/토큰상한 |LLM13/32, embedding39/80, input138397/400000, output10000/32000, embedding74567/100000이내 |
| 재시도/추가문항 |0, Q14/Q15/다른질문/전체재색인금지 |
| 시간/중단 |웹service최대10분독립watchdog, Agent40초/검색5초/종합10초유지. 불확실전송/한도/권한/identity실패즉시STOP, 추가요청없음 |

이것은새예산부여가아니며이번에유료승인파일/ledger/claim을생성하지않았다. 현재빈Index라실제요청크기를아직확정하지않았고, dryrun이 상한을넘으면승인전차단. 이숫자를바로실행가능한최종계산으로오해하지않는다.

공식단가참고: [Terra모델문서](https://developers.openai.com/api/docs/models/gpt-5.6-terra)의 input$2/M/output$12/M, cachewrite1.25배를고려해기존Guard는input$2.5/M예약.  [Embedding문서](https://developers.openai.com/api/docs/models/text-embedding-3-small) $0.02/M. 표의값은정확한상한산술이지청구서/실제예상usage가아니다. 기존Q12의약US$0.0168usage사례는참고일뿐새웹실행예상치를보증하지않는다.  [공식retry문서](https://developers.openai.com/api/docs/guides/rate-limits)와별개로이프로젝트는승인된자동재시도0을유지한다.

## 8. 테스트·독립 검토·증거

이번 단계에서 새로 실행한 결과는 다음과 같다. 이전 보고서의 PASS를 재사용하지 않았다.

| 검증 | 결과 | 범위 |
|---|---|---|
| 신규 PostgreSQL/Anvil/Chrome E2E | 1 PASS, FAIL/ERROR/SKIP 0 | 실제 Long10, 72초 유예, 자동청산, REST/WS/Tool/감사 |
| Java 선택 회귀 | 58 PASS, FAIL/SKIP 0 | AgentService, SkillService, FuturesToolReader, Guarded runner, deny transport |
| Python 회귀 | 84 PASS, FAIL 0 | Guard/lineage/relay/harness/웹 scope; fake sender·임시 ledger |
| 브라우저 assertion | 13 PASS, FAIL/SKIP 0 | 정확한 원본 수치, P0 표시, 안전한 진단 |
| 웹 전체 단위 테스트 | 143 PASS, FAIL/SKIP 0 | 이번 worktree의 웹 전체 |
| 웹 production build | PASS | 배포하지 않음 |
| 실제 신규 LLM/Embedding | NOT_RUN, 각각 0회 | 새 자연어 생성·RAG 검색·인용은 미검증 |

독립 검토에서 두 필수 문제를 발견해 수정했다. (1) 모델/로컬 토큰상한/endpoint claim 검증이 STOP 처리 밖에 있던 것을 안으로 이동했다. 실패 후 재시작해도 다른 요청을 차단한다. (2) AI compose 부분 생성 후 실패한 경우에도 신규 identity를 정리 대상으로 유지한다. 정확한 이름 조회 → 소유 label 검증 → immutable ID 정지만 허용하며, 명시적인 컨테이너 부재 이외의 timeout/500/타인 소유는 무시하지 않는다. 관련 경계 테스트를 추가했다.

`review_ai_web_stage1`의 수정 후 독립 재검토: **발견된 필수 수정 없음**. 검토자는 Python 관련 41개와 diff-check를 직접 확인했다. 실제 E2E나 유료 호출을 재실행하지 않았다.

실제 E2E는 마지막 실패 정리 보완 및 `cardAction`의 P0 상태 라벨 호환 보완 **이전 실행**이다. 최종 보완의 실패 경계는 위 오프라인 테스트로 검증했으며, 전체 실제 E2E를 다시 실행했다고 주장하지 않는다. 금융/제품 코드 변경은 없다.

기준 productHash: `d757f64f74fd089f4facc83318a25293d1bb8b4d7ef16efe462603f2e773b415`. 도구 image digest: `sha256:67f71d4f8d1d385f7dad8b87a0ed0d1d6d36f9dc979e988f39a80e982a5d65dd`. 신규 AI loopback Relay 포트는 65167이다. 실행 identity는 당시 manifest에 귀속되며 이를 최종 harness 파일 해시로 덮어쓰지 않았다.

최종 보존 검사: 기존 사용자 변경 43개와 이전 평가 증거 113개, 총 **156개 SHA-256 동일**. 기존 원본/UI/AI worktree를 수정하지 않았고 commit/merge/push는 하지 않았다. 신규 backend/web/Chrome/Relay는 종료했고 신규 DB/Anvil 데이터와 baseline은 보존했다.

실제증거: 새child의`product-browser.evidence.json`, `ai-database.evidence.json`, `ai-isolation.json`, `browser/result.json`, `owned-process-cleanup.evidence.json`, session/evidence의XML. 프로세스정리기록는 새Node하위32개중alive0, Node종료. 실제일반Chrome로그인/Tool표시캡처는`browser/ai-real-tools-1366.png`와 `ai-real-tools-390.png`. private, secret, DBstate, 원문JWT을 Git에 추가하지않는다.
