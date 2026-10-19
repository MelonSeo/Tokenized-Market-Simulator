# Futures 최종 통합 재검증 — 2026-10-20

## 현재 상태

**진행 중.** 이 문서는 이전 실행을 새 PASS로 옮기지 않는다. 최종 Phase D, 실제 KRX/NXT 제품 거래, 실제 LLM 평가를 완료로 선언하지 않는다.

- 원본 `feat/futures-engine` HEAD `216ede45729907c2cc17de4d5a66fd1b39565cd9`, `melon-init` `9a9197b290c57ce3351452fcf7f0794e6f24cdfc`에서 조사 시작.
- 별도 `integration/futures-final-20261020`에 정상 merge `f70c1eb5eb21e2bb848c132734402747a6182189`. 충돌0, merge tree는 Futures 부모와 동일. 원본 사용자 dirty5개는 보존한다.
- [76개 기존 ID와 신규 Futures 대조](../docs/PROJECT_COMPLETION_AUDIT_2026-10-20.md). 원본 미추적 마스터/설계는 임의 커밋하지 않는다.

## 새 환경 준비와 실패 보존

별도 worktree에 공개 Foundry 의존성이 없었다. Git에 `contracts/lib`가 추적되지 않고 `.gitmodules`도 없어 fresh checkout만으로 빌드되지 않았다. 원본의 공개 `forge-std/src`31파일과 `openzeppelin-contracts/contracts`274파일, 필요한 공개 foundry/remappings/package/license metadata만 오프라인 복사하고 원본/사본 SHA-256을 대조했다. 기존 .env·키·runtime·state는 복사하지 않았다. 의존성 pinning/repository bootstrap 개선은 이번 제품 변경에 포함하지 않는다.

- `phase-a-20261019153357-aee02981`: tools build **FAIL**, `/contracts/lib` 없음. fixture/DB/chain 생성 전.
- `phase-a-20261019153809-f01d5ecf`: tools build **FAIL**, 공개 의존성 metadata가 없어 자동 remapping 실패. fixture/DB/chain 생성 전.
- `phase-a-20261019153910-daf57837`: 새 tools 준비 **PASS**, 이후 새 회귀 진행. 이전 두 session은 덮어쓰지 않는다.

## 새 실행 결과

| 실행 | 판정 | 범위 |
|---|---|---|
| backend 전체 isolated workers(corpus 갱신 전) | PASS | 643중594 PASS/49 SKIP/0FAIL/0ERROR, 과거 실행과 별도 |
| 웹/Chrome offline/Foundry | PASS | 웹120/Node8/Foundry52, Chrome desktop-mobile offline fixture, SKIP0 |
| 실제 Synthetic/Chrome/PG/Anvil | PASS | futures-browser-25e4aba7, 1 PASS/0SKIP, Long63초·Short62초 유예 후청산, 개인이벤트40, 선물감사/기존reserve MATCH |
| 별도 두 JVM CONFIRMED 복구 | PASS | futures-confirmed-21ca9e37 prepare1/recover1 PASS, 각각SKIP0 |
| 실제 선물 AI 읽기+Mock모델 | PASS | futures-ai-4bc3517c 1 PASS/0SKIP, 실제 체인·원장 조회/인가/읽기 불변, 실제 LLM 아님 |
| 실제 현물 전량 매도 | PASS | sellall-aa32367b 1 PASS/0SKIP, BUY/부분SELL/전량SELL 및 두 현물대사 |
| 실제 조건부/관측 재시작 | PASS | conditional-60f67dca 본실행1/별도JVM1 PASS, 앞선준비실패와별도 |
| Google 오프라인 PG/Anvil | PASS | google-25d86b7a 1 PASS/0SKIP, 실제 Google 계정 재인증 아님 |
| Faucet PG/Anvil | PASS | faucet-54178193 1 PASS/0SKIP, 경합/원자성·한도 |
| 선물 API/개인 STOMP | PASS | Java21 futures-api-223d4ccd 1 PASS/0SKIP, HTTP권한/null-loginId/개설·추가담보·종료·개인전달 |
| 선물 요청 경합 | PASS | Java21 futures-requests-d9551f5c 1 PASS/0SKIP, 실제 PostgreSQL 경쟁/확정거절·중복요청, 체인 거래 검증 수와 별개 |
| 선물 감사 수정 후 새실행 | PASS | Java21 futures-audit-36aaddcd 1 PASS/0SKIP, 정상/미확정/의도차이·reserve호환, 이전1FAIL보존 |
| corpus 갱신 후 loader 회귀 | PASS | Java21 자식 프로세스 KnowledgeLoaderTest10 PASS/0SKIP/0FAIL, 내용/역할/hash 검사 |
| corpus/감사 guard 갱신 후 전체 회귀 | PASS | backend-final-java21: **648중599 PASS/49 SKIP/0FAIL/0ERROR**, 같은전체선택·forkEvery20 |
| ADMIN UI/harness 반영 후 전체 회귀 | PASS | backend-admin-ui-java21: **648중599 PASS/49 SKIP/0FAIL/0ERROR**, 직전 실행과 별도 |
| 실제 LLM/embedding/index | NOT_RUN/비용 승인 대기 | 무료 fixture와 구분 |
| 실제 Kiwoom/Toss/NXT | NOT_RUN | 이번 작업 외부 호출 금지 |

새 session productHash는 `b34a2d089988e74066111c1bb6b22ab65fb92bfaade28a574f7aebae51cba670`이다. 원본의172e90d1…과 같은 값이라고 가정하지 않는다. fresh Windows checkout의 byte 차이를 기존 manifest 재작성으로 우회하지 않고 새로운 session에서 검증했다. productHash는 웹/문서 전체 hash가 아니며 Git diff와 실행별 결과를 함께 사용한다.

실제 Chrome Long 청산 tx `0x79179004a1ca5855b616297a9909cfc3021cfc79e28b94fa38943a3f84a6af6e`, Short `0x07e6d6187bf2da865c23886b383cc399ae2a881bafa5ef684b99d4dad42daf64`. 두 receipt 성공·canonical/새 관측가격·60초 이상을 실제 테스트가 검증했다. 테스트 전용 checkpoint 시나리오 제어는 실제 제품 Synthetic commit/공개 경계를 통과하며 일반 사용자 가격 조작 API를 추가하지 않았다.

선물 감사 coverage는 operations20/transactions19/verifiedTransactions19/events38/verifiedEvents38, blocks73~180, complete=true다. 브라우저8단계/6포지션/preview 만료 거절1건, 소유 Node descendants25개 중 종료 후 생존0을 확인했다. 기존 사용자 브라우저 프로세스를 정리한 결과가 아니다.

`conditional-9e415d5e`는 **실행 준비 FAIL / 테스트 NOT_RUN(0건)**이다. SQL/RPC/clock/격리·계약 준비 후 Gradle compileTestJava가 `java.net.SocketException: Bad address: listen`으로 종료되어 테스트 클래스 실행 전 중단됐다. 조건부 거래 assertion 실패나 시세/정산 결함으로 분류하지 않는다. Windows native socket 문제의 영구 원인은 미확정이다. 당시TCP 상태 개수만으로 자원 고갈을 확정하지 않는다. 기존 실패 fixture/로그/summary0을 보존하고 제품/정책 변경 없이 새 JVM·새 fixture **1회** 재검증한다. 이후 미실행 단계는 성공으로 합산하지 않는다.

그 새 conditional-60f67dca는 본실행1/별도JVM1 PASS했다. 이후 futures-api-8824a783은 컴파일은 끝났지만 Gradle test task의 같은 `Bad address: listen`으로 결과XML0건/집계0건이다. 실행 완료 증거가 없으며 PASS로 해석하지 않는다. 원본 통합과 반복 E2E를 보류하고 읽기 전용 daemon 로그를 확인했다. 최근 daemon3개는Java23, 프로젝트 toolchain은Java21이다. 이 차이가 원인이라는 증거는 아직 없다. 설치된Java21.0.10을 **해당 자식 명령의 JAVA_HOME에만** 지정한 오프라인 corpus 비교 검증10PASS를 확인했다. 전역환경·Docker/WSL·기존서비스는 변경하지 않는다. 달라진 runtime의 새fixture로 API·경합·감사를 한 차례 실행하며 같은 오류가 반복되면 추가 우회 없이 BLOCKED로 유지한다.

오프라인 manifest는9/9 정규화 SHA-256/문서 버전 일치(전체정규화UTF8 65,686bytes), Python guard/clock20 PASS. 이20개 출력에 등장하는 prepare/recover 집계는 Mock harness assertion이며 실제 두 JVM 결과와 합산하지 않는다. KnowledgeLoader 문구/역할 회귀와 실제 embedding 검색·golden 평가는 별개다.

새 독립 fixture10개 case 그룹의 성공 실행은12 JVM 결과(confirmed/conditional 각2)이며 전수49SKIP에 더해 통과율을 계산하지 않는다. 이 중 requests는 실제PG 경합 검증이고 모든12실행에서 체인 transaction이 실행됐다는 뜻은 아니다. 실제 제품 SyntheticEngine/Chrome은 browser case이며 다른 가격 fixture·Mock모델과 구분한다.

### 실제 감사 assertion 실패와 최소 수정

`futures-audit-7952317a`는 **1 FAIL/0SKIP**이다. 실제 실패는 선물 감사가 아니라 기존 reserve 후속 assertion이었다. 저장 이력의 선물 감사는 정상MATCH/의도적house1wei차이MISMATCH, reserve 마지막은 INCONCLUSIVE/CUT_CHANGED였다. 테스트가2초 자동 채굴의 단일 관측에서 stable cut을 가정했다. [원인·테스트-only 보완·검토](futures-audit-fixture-cut-2026-10-20.md)에 read-only 조사와 실패 보존을 기록했다.

제품 대사 코드는 변경하지 않고 fixture에서 CUT_CHANGED만 최대4회 새 관측한다. 4회 모두 이동하면 INCONCLUSIVE가 유지되어 assertion이 실패하며 다른 사유·예외는 재시도하지 않는다. baseline/원장/거래를 보정하지 않는다. 전용guard4+corpus10=14PASS, 새 실제감사1PASS, 후속전체599PASS를 각각 구분한다. `review_audit_fixture_cut` 독립 최초검토 필수수정 없음(직접실행/DB/private접근없음). Java21 제어 실행에서 새 native socket 실패는 관측하지 않았지만 Java23 원인을 확정·영구 해결했다고 주장하지 않는다.

## 독립 검토와 지식 정합화

`review_final_integration`은 실제 merge tree·AI9문서·시장/인가/감사 경계를 정적으로 확인했다. 직접 테스트/DB/private 접근은 하지 않았다. 필수 수정2묶음: (1) market-data-policy/signed-quote-policy의 옛0s필수·UNKNOWN차단 정책, (2) system-overview의 현재8Tool/3Skill만 존재하는 설명. 현재 코드는10Tool/4Skill이며 키움은 승인된 현 generation 정상 정규0B와 연결·LIVE·과거5초/미래2초·veto를 검사한다. UNKNOWN 자체는 OPEN 확정도 거래 허용 근거도 아니다. 문서 최신화 후 version/정규화 hash/manifest와 오프라인 회귀·재검토가 필요하다.

위 필수 지적은8문서/manifest/loader assertion에 반영했고1차 재검토에서 필수 수정 없음이었다. 원본9문서 구조와 USER8/ADMIN1 역할을 유지했다. 실제 신규 색인·검색 품질 평가는 미실행으로 남긴다.

`review_final_integration` 최종2차 재검토도 필수 수정 없음. 실제 API·보안·새 ADMIN UI·fixture cut 보완·76ID 대장·지식 문서·비용 승인 경계를 읽기 전용 확인했다. 직접 테스트/DB/private/외부 실행은 없으며 진행 중 최신 회귀와 실제 LLM/인수 미완료를 대체하지 않는다.

## 실제 모델 평가 승인안 — 아직 미실행

모델은 기존 `gpt-5.6-terra`, 임베딩은 `text-embedding-3-small`1536차원을 유지한다. [Terra 공식 요금](https://developers.openai.com/api/docs/models/gpt-5.6-terra): 입력1M당US$2/출력US$12, [embedding 공식 요금](https://developers.openai.com/api/docs/models/text-embedding-3-small): 입력1M당US$0.02. 2026-10-20 조회, 표준 단문맥·캐시 할인 미적용 기준. 계정 접근/과금 통화·세금은 확인되지 않았다.

- 제안: 기존/선물16질문, 모델 최대32회(분류400/종합1600 출력 상한), 신규 격리 corpus 색인1회 및 검색 평가. 기존 AI DB를 재색인하지 않는다.
- 색인1회는 HTTP1회라는 뜻이 아니다. 기존 코드는 최대16청크씩 embedding 요청한다. 색인 최대500청크/32요청, 고정golden12+음성8+식별자8 검색28요청, Agent16검색까지 포함해 embedding HTTP 상한80회/누계입력100,000토큰으로 제안한다. 실제 청크 수와 byte/token 보수 상한을 선검사하며 상한에 맞추려고 corpus·질문·정책을 임의 축소하지 않는다. 재시도0, 승인 한도 내 실패도 비용/호출에 남긴다.
- 계획 비용: 입력 누계256,000/출력32,000/embedding100,000토큰을 보수적 배치 한도로 두면 US$0.512+0.384+0.002=**US$0.898**. 통상 추정US$0.3~0.9, 승인 요청 상한US$2.
- 이는 아직 실행한 비용이나 구현된 hard-cap 보증이 아니다. 실행 전에 test-only 전송 경계에서 **전체 요청의 UTF-8 byte 상한을 token 상한의 보수적 대용으로 예약**, 모델/endpoint/출력·호출 수·재시도0 및 누계 비용을 검사하고 선예약한다. 불확실 응답 비용은 환급하지 않는다. 상한을 강제하는 오프라인 테스트 후에만 실행한다. 서버 사용량과 대조할 수 없는 응답은 비용 불확실로 남긴다.
- 평가: 기존 RAG/시장/portfolio/order/quote/receipt/ADMIN 이상조회·3Skills를 대표 질문으로, 선물 손익/마진콜/청산canonical/journal/미확정 설명을5질문으로 검증한다. 타인404/USER ADMIN403·읽기 불변·Tool/RAG 장애는 무료 경계 테스트로 별도 확인한다.
- 기존 live tests의 H2/고정5433 경로를 새 실제 체인 인수처럼 실행하지 않는다. 새 전용 AI DB와 실제 신규 선물 fixture의 근거를 연결한 평가가 필요하다.
- paid 승인 전 외부 API/색인0. 최신 KRX 거래 연계 AI는 Phase B 이후 별도이며 이 배치의 PASS로 대체하지 않는다.

평가 질문 고정안(입력 식별자는 새 fixture 내부에서만 대입):

| 번호 | 질문/대상 | 필수 사실·금지되는 확정 |
|---|---|---|
| 1 | quoteId는 왜 한 번만 쓸 수 있는가 | DB 소유/소비와 Oracle 재사용 방어, 정책 인용 |
| 2 | 현재 기준가격과 거래 가능 상태 | 시장 Tool2개·provider/observedAt 대조, UNKNOWN만으로 허용/차단 단정 금지 |
| 3 | 내 가용 잔고와 포트폴리오 | 본인 available/locked와 단위 정확, 타인 접근 금지 |
| 4 | 내 주문 상태 | 지정 Order 사실, PENDING을 FILLED로 설명 금지 |
| 5 | 내 견적 상태/만료 | 지정 Quote 상태와 시간 별개, CONSUMED≠체결 |
| 6 | 주문의 체인 전송 상황 | getBlockchainTransaction, SIGNED/SUBMITTED/확정 구분 |
| 7 | 이 주문이 체인에서 확정됐는가 | Receipt/Event/DB 상태 구분, timeout≠실패 |
| 8 | ADMIN 이상 주문 목록 | listAbnormalOrders와 운영 필터, USER403은 무료 사전 경계 |
| 9 | settlement-debugging | ADMIN 고정절차·RAG와 Tool 근거·조치 실행 금지 |
| 10 | signed-quote-diagnosis | 본인 quote/연결 Order·일회/만료·근거 인용 |
| 11 | market-availability-diagnosis | 시세 관측과 정책 구분, DTO에 없는 generation/allowed 추측 금지 |
| 12 | 내 Long은 왜 청산됐는가 | 담보/수량/가격·E와MM·마진콜/유예/청산 근거, 단순 문구 일치 대신 사실 검사 |
| 13 | 마진콜 가격과 유지증거금 | 실제 Q/C/진입·판정가격·ceil5% 및 E<=MM 계산 대조 |
| 14 | 청산이 실제 체인에 확정됐는가 | 선택 operation의 canonical Receipt/Event/블록·검증 범위 인용 |
| 15 | 청산 후 손익과 DB journal이 맞는가 | 실제 realized/badDebt·지갑/house/journal, 온체인 담보 예치/지급 보증 주장 금지 |
| 16 | 가격·Receipt가 불확실하면 무엇을 알 수 있는가 | PARTIAL/미확정 근거·확인 불가 명시, 강제 정산/새 주문 금지 |

고정 golden12 retrieval은 질문/기대문서를 변경하지 않는다. hit@5·MRR@5·직접 근거·기존 성공 회귀와 음성 질문 오탐은 실제 신규 index에서 별도 평가하며 위16질문의 자연어 답변 점수와 합산하지 않는다. 생성 답변은 문자열 완전 일치가 아니라 Tool 선택·필수 사실·단위·인용·권한·불확실성을 검사하고 정제 답변을 수동 대조한다. 모든10Tool/4Skill을 실제 모델이 항상 선택하리라고 보장하지 않으며 선택 실패도 기록한다.

## 보존·종료

### 원 설계 F14 ADMIN 화면 누락 보완

마스터 최종 대조에서 API만 있던 Futures ADMIN 감사 최소 화면 누락을 발견해 기존 API에만 연결했다. 제품 서버·경제·정산은 변경하지 않았다. [구현·검증·독립 검토](futures-admin-audit-ui-2026-10-20.md). 웹127PASS/0SKIP, 빌드와 desktop/mobile offline Chrome PASS. 새 `futures-browser-bf973f2e` 실제PG/Anvil/Chrome1PASS는 기존 일반사용자 거래·60초 이상 양방향청산·30초 preview 만료에 ADMIN 기준점/실행/이력/상세·모바일 검증을 더한 새 실행이다. 직전25e4aba7 결과를 새 화면 PASS로 재사용하지 않았다. 검토자 직접 테스트/private 접근은 없다. 실제 모델/시세 인수와는 별도다.

이 최신 실행의 Long 청산 `0x3cae047da5884c81ab59f1db7b18bd4ceebb666879a9563d1bb2d40821092c82`는 마진콜 블록부터65초, Short `0x6e59322c9e6297f18afa1a3ec39f4b18ec0f0e3893421c840f45a303144c164b`는62초다. 후속 선물감사 operations20/transaction19/verified19/events38/verified38/blocks64~172/complete true, reserve MATCH. ADMIN 화면 감사도 MATCH/complete이며 조회id를 실제 감사 이력과 대조했다. 전체9단계/6포지션/개인WS40건, 소유descendants28개 중 정리 후 생존0을 확인했다.

UI 반영 후 전체 backend648중599PASS/49SKIP, 웹127/Node8/Foundry52PASS, 빌드와 desktop/mobile offlineChromePASS를 새로 확인했다. 전체 `forge build --offline` 성공이며 lint 경고/노트는 남는다. staged UI8경로에 신규 fixture private값60개 대조0/금지경로0·diffcheckPASS. 실제 LLM/index·실시장 거래는 여전히 NOT_RUN이다.

기존 KRX/Google/playground/NXT/실패 session의 image·DB·Anvil·manifest·baseline·private evidence는 변경/초기화/삭제하지 않는다. 신규 fixture만 소유권 검사 후 정지하고 상태를 남긴다. 원격 push·배포·실제 금융거래는 하지 않는다. 새 통합 branch에서 검증된 명시 경로만 로컬 커밋하고, 원본 merge는 사용자 dirty와 충돌 여부를 재확인한 뒤 판단한다.
