# 원본 병합 후 실제 E2E 재개 — 2026-10-20

## 기준·격리

- 실행 전 원본 `melon-init@25f434224b82286748eb03d14fbb82661856f599`. 사용자 기존 dirty5경로는 보존한다. 이전 분리 worktree PASS를 이번 결과로 사용하지 않는다.
- productHash `e186511df351864ee2709fe7d858ebeb75412d7f08dda8a389fb81888e3255af`. 이전 원본 제품과 동일하지만 최신HEAD로 새 session을 생성했다.
- Docker Server28.4.0/version·info 정상응답을 도구에서 직접 확인했다. linux `/var/lib/docker`, C여유5,616,181,248bytes. 기존Google4서비스는조회당시실행중이며조작하지않는다.
- 새 session `phase-a-20261019233131-dfbb357a`. 독립 PG/Anvil/loopback 포트/네트워크/키·source-built tools image. secret 자동import차단, 외부AI/시세OFF, 새fixture만소유권검사후정지하고state/log/DB보존. 전역Docker/WSL재시작·prune·기존환경초기화없음.
- Java21.0.10은해당자식프로세스의JAVA_HOME에만지정한다. 기존시각2초guard/실제60초유예/30초견적을변경하지않는다.

## 이번 새 실행

| 범위 | 현재 판정 | 실행과 증거 |
|---|---|---|
| 실제제품Synthetic/Chrome/PG/Anvil 최초 | FAIL1/0SKIP | futures-browser-16c4cf48, Faucet 전 정책조회/이력단계 WAIT_TIMEOUT |
| 별도 두JVM CONFIRMED | PASS1+1/0SKIP | futures-confirmed-acdaf32c, 독립새PG/Anvil·별도JVM prepare/recover |
| 실제Chrome 정제관측 재현 | FAIL1/0SKIP | futures-browser-2d798ee4. Faucet조회/지급200,Long2추가담보·종료/Short5종료후만료검증용preview준비WAIT_TIMEOUT |
| test-only 준비 보완 후 실제 Chrome 전체 | PASS1/0SKIP | futures-browser-f8780a85, 실제 제품 Synthetic/HTTP/WS/PG/Anvil·정상 종료/양방향 청산/회복/모바일/ADMIN |
| 현물 BUY/부분SELL/전량SELL·두대사 | PASS1/0SKIP | sellall-f3c9ddac, 독립새PG/Anvil·잔여수량0·현물두대사 |
| Futures감사·coverage·의도적차이 | PASS1/0SKIP | futures-audit-d54cefe5, 정상3tx/6events·pending·의도적1wei차이·불변baseline |
| 실제체인근거+Mock AI | PASS1/0SKIP | futures-ai-fce63982, 실제청산·Tool2/Skill·USER/ADMIN·타인404·조회원장불변. 실제LLM 아님 |
| 실제LLM/embedding/재색인 | NOT_RUN | 비용미승인·호출0 |

이번요청에서는제품코드수정없이기존도구를재사용한다. 테스트수정이필요하면최초실패증거를보존하고최소원인을분류한뒤별도검토한다. 이전593/6FAIL/49SKIP과후속599/49SKIP은이전원본회귀이력이며새실제E2E수에합산하지않는다.

### 최초 실패 조사와 진단-only 보완

브라우저failure.png는로그인된본인portfolio화면/잔고0/버튼disabled/`정책 조회 실패`를보인다. 새실패fixture PG만소유권/정지상태확인후일시기동·boundedSQLreadiness·`BEGIN READ ONLY`로조회한결과grant0/claim0/operation0,사용자1·2의잔고·잠금0,예산initialized=true/count0/spent0였다. 읽기후원래정지상태로돌렸다. Anvil/원장/기준점은재배포·초기화·보정하지않는다.

기존driver는Faucet조회응답코드를저장하지않아HTTP오류와UI처리오류를아직구분할수없다. DockerServer재조회는정상이므로이번실패를앞선Engine500이나시각문제로단정하지않는다. 시각preflight는midpoint-1.0703초/RTT0.2521초로기존2초범위통과다.

최소test-only보완: 기존driver/assertions에Faucet3개정확path의HTTP상태·고정공개오류코드/사유·eligible boolean만기록한다. token/password/key/headers/원문응답/임의오류문자열은저장하지않으며50건상한이다. 실패시UI의route·로그인완료여부·handler준비·정책표시/조회실패boolean만추가한다. 추가HTTP요청·재지급·지연허용·재시도·제품수정은없다. Node10PASS/0SKIP(새보안2개포함). 독립검토후새child1회로정확원인을좁힌다.

초기read-only조사2개명령은PowerShell인자/Go-template전달오류와초기SQL소켓미준비stderr에서중단됐다. 성공조회로해석하지않고마지막정상소유권·readiness검사명령의결과만위에반영했다. 이들은제품테스트실패와구분하며기존failedfixture증거는보존한다.

진단-only 변경의 독립 검토 `review_postmerge_faucet_diagnostics`: 발견된 필수 수정 없음. 소스3개/테스트/새문서와 실제 Faucet 계약을 읽었고 직접테스트/DB/private접근은없었다. 공개코드 `FAUCET_CLOCK_REVERSED` whitelist 추가 제안은 현재원인미확정/이력0 상황에서 확장하지 않고, 알려지지 않은코드는 UNRECOGNIZED로남긴다. 정확한HTTP원인과새E2E결과를보기전에는검토통과를실제인수PASS로쓰지않는다.

### 두 번째 실패와 test-only 준비 경계

새2d798ee4는Faucet조회/지급모두200,지급후DAILY_LIMIT·eligible=false를실제관측했다. 최초정책조회실패는재현되지않았고원인미확정으로남긴다. Long2개설·추가담보·종료/Short5개설·종료와개인WS10건을확인했지만전체PASS는아니다. 다음31초만료시나리오의preview준비에서멈췄다. 기존step값이OPEN_SHORT_5에머물러있던것도진단한계다.

`FuturesService.preview`는가격capture→DB preview기록→동일가격재검증으로tick이바뀌면정상적으로PRICE_CHANGED를거부할수있다. 다른개설/종료시나리오는새제품tick직후preview를요청하지만만료시나리오에만그준비가없었다. 이것은확인된테스트비일관성이며두번째실패의정확한HTTP코드는미기록이므로PRICE_CHANGED가실제발생했다고단정하지않는다.

test-only최소보완: 만료시나리오에도기존 `nextProductTick` 준비를적용하고명시적step을기록한다. 실제31초대기와주문미전송assertion은그대로다. preview응답은상태/고정code만관측하고비200이면대기timeout대신PREVIEW_HTTP_nnn으로즉시실패한다. 자동preview/주문재시도나허용정책변경없다. LOGIN응답감지와실제제품UI인증완료는다르므로USER/ADMIN 모두dialog닫힘/로그인숨김/logout표시를확인한뒤화면을조작한다. 최초Faucet원인을이경합으로확정하지않는다. Node11PASS/0SKIP,제품Java/Solidity/웹코드변경없음. 독립재검토후새fixture에서재검증한다.

추가진단회귀는 Node12PASS/0SKIP/0FAIL이다. preview HTTP status는100~599 정수만허용하고,응답body를읽을수없거나JSON이아닌경우에도상태와bodyUnavailable boolean만기록해502등을대기timeout으로숨기지않는다. 원문오류/서명/preview payload를기록하지않는다.

최종 독립 재검토(최초 + 재검토2회) 결과는 `발견된 필수 수정 없음`이다. 실제31초·60초 기준과 실패 보존, 허용 목록·비밀정보 비기록을 소스로 확인했다. 검토자는 직접 테스트/DB/private/외부 접속을 수행하지 않았다. 최종 새 Node 실행12PASS/0SKIP/0FAIL, `node --check` 및 `git diff --check`도 통과했다.

### 두 JVM 새 실행 증거

두JVM정제근거: prepare/recover processId상이, txHash `0x500f8ac092b8b69105da0b933f27e9c9ddc955e0aed3a5e02b1646887ed87472` 동일, nonce동일/raw해시일치, CONFIRMED/canonicalReceipt/singleSettlementJournal=true. 실제새JVM결과이며이전분리브랜치PASS가아니다. preflight중앙값DB-host -0.9450초/RTT0.4725초,기존2초불확실성guard통과.

### 원본 실제 Chrome 전체 통합 새 PASS

`futures-browser-f8780a85`는 **1 PASS / 0 FAIL / 0 SKIP**이다. 제품 `SimulatedPriceProvider`의 실제 tick 저장/commit/공개 경계를 이용하고, 일반 사용자 가격조작 API나 가짜 PriceProvider는 추가하지 않았다. 테스트 제어는 새 fixture의 private checkpoint에만 한정한다.

- 로그인·Faucet → Long2 개설/추가담보/종료 → Short5 개설/종료 → 실제31초 견적 만료 및 미전송 → Long10·Short10 자동청산 → 유예 중 가격 회복 → 추가담보 회복 → ADMIN 감사 baseline 조회/수동실행/이력/상세, desktop·mobile를 완료했다.
- 개인 WS 38건, JavaScript exception 0건. 청산 Receipt 성공·canonical blockHash·각2개 Event와 DB 정산, 최종 잠금 합0·journal 보존을 테스트에서 확인했다.
- Long call block timestamp1792454161 → 청산1792454226: **65초**. txHash `0xa87a9517e7c7113dfba5500718aa49cbc3d98d84e25ea0874f671694bcff33f9`.
- Short call1792454228 → 청산1792454307: **79초**. txHash `0xe0d82a0ca3513cd3bb4e04e86b0de89ab8763a7077f90dbce02d9d7b86c5cbf7`.
- 최종 backend Futures 감사 `MATCH / EXACT_LEDGER_CHAIN_MATCH`, 19/19 transactions·38/38 events, coverage.complete=true. 기존 자산 대사 `MATCH / EXACT_QUANTITY_MATCH`이며 온체인 담보 예치나 지급능력 보증을 의미하지 않는다.
- 앞선 ADMIN 화면 수동 실행은 `INCONCLUSIVE / complete=false`였고 REST 상세와 동일하게 표시됐음을 확인했다. 이를 UI에서 MATCH였다고 바꾸지 않는다. 이후 별도 최종 관측의 MATCH/complete=true와 시점을 구분한다.
- 새 Node의 startInstant가 일치하는 자식43개만 추적해 aliveAfterCleanup=0/nodeAlive=false. 새 PG/Anvil은 도구가 종료하고 DB/state/스크린샷/로그/실패2개 fixture를 보존했다.
- 이전 두 실패의 정확한 HTTP 원인은 여전히 미확정이다. 준비 절차 보완 후 새 실행이 PASS했다는 사실만으로 최초 실패의 원인이나 영구 해결을 단정하지 않는다.

### 감사·현물·무료 AI 후속

- `futures-audit-d54cefe5`: 실제 PostgreSQL/Anvil1PASS. 미정산 operation에서 INCONCLUSIVE, 확정3거래/6이벤트 MATCH·complete=true, 마지막 새 fixture house를1wei줄인 의도적 MISMATCH·complete=true를 확인했다. 현물 자산 대사도 정상 MATCH/차이 MISMATCH, 사용자 wallet 및 불변 baseline은 감사로 변경하지 않았다. 의도적 차이를 고치거나 fixture를 재사용하지 않는다.
- `sellall-f3c9ddac`: 실제 PostgreSQL/Anvil1PASS. 기존 현물 BUY→부분SELL→정확 전량SELL, 성공 Receipt/Trade·토큰 가용/잠금 잔여0·자산 대사 및 현물 거래 감사 MATCH와 coverage를 확인했다. 이 case 자체는 Java/HTTP 거래이며 실제 Chrome 테스트라고 바꾸지 않는다.
- `futures-ai-fce63982`: 실제 PostgreSQL/Anvil/서버 worker의 마진콜·실제60초 유예·청산 이후 근거 조회1PASS. call/liquidation verification=VERIFIED, 본인 USER·ADMIN 허용/타인 USER404, readOnlyLedgerUnchanged=true. Skill은 Tool2개·RAG1회·Mock model1회로 ANSWERED/FUTURES_LIQUIDATION_VERIFIED를 반환했다. retrieval=fixture, LLM=Mock이며 토큰0은 모델 무료 사용이 아니라 실제 모델 미호출을 뜻한다.
- 이번 새 무과금 E2E 집계는 최종 성공5case/6JVM(PASS6/FAIL0/SKIP0)와 앞선 Chrome 실패2case/2JVM(FAIL2)을 **별도로** 보존한다. 처음부터 전체8PASS라고 보고하지 않는다. 실제 AI/재색인·KRX/NXT 거래·Phase D는 NOT_RUN이다.
- 추가 새 회귀: 웹127PASS/0SKIP, Foundry52PASS/0SKIP, 진단 Node12PASS/0SKIP. 전체 backend599PASS/49SKIP은 앞선 원본 제어 실행 이력이며 이번 여섯 실제 E2E 성공과 합산하지 않는다. 49SKIP은 통과 수가 아니다.

## 실제 AI 평가 준비와 승인 경계

기존모델 `gpt-5.6-terra`와 `text-embedding-3-small`1536차원을유지한다. OpenAI Docs로공식모델페이지를다시조회했다. [Terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra)는표준입력1M당US$2/출력US$12, [embedding](https://developers.openai.com/api/docs/models/text-embedding-3-small)은입력1M당US$0.02다.2026-10-20확인,캐시할인·세금/통화·계정별접근권한은별도다.

기존승인요청안은16질문·LLM최대32회/embedding HTTP최대80회·재시도0,누계입력256K/출력32K/embedding100K토큰예산으로US$0.898계산,예상US$0.3~0.9/지출상한US$2다. 신규격리index만사용하고기존AI DB를갱신하지않는다. [질문·검증기준·예산상세계획](futures-final-integration-2026-10-20.md#실제-모델-평가-승인안--아직-미실행)을따른다.

이금액은현재까지의실제발생비용이나이미구현된hard-cap보증이아니다. 실행전전용provider전송경계의요청/출력/토큰보수상한/비용선예약guard를오프라인검증하고,현재corpus/manifest/hash와새index버전을대조해야한다. 기존고정port/H2 live test를실제체인인수처럼호출하지않는다. 현재지식9문서+manifest는갱신됐지만실제검색품질과자유답변정확도는미검증이다. 무료Mock/권한·근거조회와실제모델답변을분리한다.

실제모델이포지션·잔고·청산을변경하지않으며USER본인/ADMIN경계를유지한다. 실제Kiwoom/Toss·Sepolia/AWS·유료API호출은이작업에서실행하지않는다.

## 종료·보존·Git 범위

- 최종 읽기 전용 대조에서 실행 HEAD와 새 session.sourceHead는 모두 `25f434224b82286748eb03d14fbb82661856f599`였다. productHash는 시작값과 동일하다. 이 후속 변경은 테스트 harness3개와 결과 문서4개이며 제품 Java/Solidity/웹 기능 변경은 없다. 커밋 후 HEAD가 바뀌더라도 이미 실행한 증거의 sourceHead를 다시 쓰지 않는다.
- 새 fixture7개에 속한 컨테이너14개 모두 `phase-a.fixture` 소유권 일치 및 exited를 확인했다. 이번 preflight7개는 왕복 불확실성을 포함한 최대 오차0.3338~1.1964초로 기존2초 한도를 통과했다. 기존 Google4서비스는 조회 시 그대로 실행 중이었다. 전체 Docker/WSL 재시작·prune·기존 컨테이너 종료는 없다.
- 사용자 보호5경로의 SHA-256이 시작값과 모두 동일하다. 새 fixture DB/Anvil/state/로그/Chrome 스크린샷·실패2개와 이전 증거는 보존한다. private runtime은 Git에 추가하지 않는다.
- 이번 명시7경로를 신규 비밀 값36개와 메모리에서 대조한 결과 포함0, 개인키 PEM/JWT 패턴0이다. 실제 값·JWT·서명 원문을 보고서에 기록하지 않는다. 최종 staged diff도 같은 명시 범위로 점검한 후 논리적 로컬 커밋만 허용한다. 사용자 dirty5경로는 제외하며 push·외부 배포는 없다.
- 남은 위험: 첫 Faucet 실패 및 두 번째 preview 실패의 정확한 과거 HTTP 원인은 미확정이다. 이번 최종 PASS로 그 이력을 삭제하거나 간헐 Windows native listen/Docker 장애가 영구 해결됐다고 주장하지 않는다. 실제 LLM/index·실제 KRX/NXT 및 최종 Phase D는 별도다.
