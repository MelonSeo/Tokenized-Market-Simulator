# Futures read-only AI 통합 — 구현·무과금 검증 완료

## 범위와 근거

기준 HEAD266465e(제품 Synthetic/실Chrome 및 CONFIRMED 두 JVM 테스트), 이전 핵심 제품 hash6c5c6930…를 보존한다. 이번 마일스톤은 실제 거래 엔진을 변경하지 않고 기존 8 Tool/3 Skill에 선물 Tool2/Skill1을 추가한다. 기존 Tool 이름, 권한, WAITING_LONG 및 자동 REVIEW_REQUIRED 진단을 유지한다. 새로운 AI 자동 실행이나 mutation은 없다.

## 구현

- `ai/tool/read/FuturesToolReader`: 인증 컨텍스트로 본인 포지션 또는 ADMIN의 명시적 포지션만 선택. `getFuturesPosition(positionId)`는 기존 표시 DTO와 DB 수탁·지급 미보증 표시다. userId/role을 모델 인자로 받지 않는다.
- `getFuturesLiquidationEvidence(positionId)`: read-only REPEATABLE_READ cut 이후 DB transaction 밖에서 최대2건의 START_CALL/LIQUIDATE만 검증한다. 기존 raw/domain/signature/거래/정규 Receipt/Event/역사적 position 검증기를 재사용하고 현재 chain position·journal·DB 재조회 fingerprint를 대조한다. history INSERT, 원장 보정, reconcile, sign/broadcast 호출은 없다. 내부 snapshot/report/raw/signature는 직렬화하지 않는다.
- VERIFIED는 선택 operation의 canonical 근거와 journal의 범위다. 전체 감사 coverage/MATCH, 온체인 담보 보관, 미래 지급 보증과 다르다. RPC/역사 상태/확정수/DB cut 불확실성은 INCONCLUSIVE다. 다른 사용자의 자산·house 상세는 USER 출력에 포함하지 않는다.
- 기존 dispatcher4workers/5초·Agent40초/Tool4회/검색1회/모델2회 한도를 공유한다. 타임아웃 중 실제 작업이 끝날 때까지 worker 점유를 유지한다. 신규 Tool은 Futures 비활성 환경에서 TOOL_DISABLED이며 현물/AI DB startup을 요구하지 않는다.
- `AgentRequest.target.positionId` UUID, `Subject.FUTURES`; preflight 권한 검증 후 고정 Tool을 실행한다. 모델 입력에는 position/operation/journal/pending 식별자와 txHash/nonce/개인정보를 제거하며 응답의 인가된 toolEvidence와 근거 ID는 유지한다.
- `futures-liquidation-diagnosis`: LOAD_POSITION→LOAD_FUTURES_EVIDENCE→CHECK_EVIDENCE→RETRIEVE_POLICY→SUMMARIZE. 기존 USER/ADMIN, domain trading/settlement/support 경계를 유지한다. 미검증 선물 체인 증거는 모델 종합으로 확정하지 않고 서버의 PARTIAL 설명을 반환한다.
- 웹 포지션의 진단 버튼은 대상 선택 화면으로만 이동한다. 실제 AI 요청은 사용자의 명시 제출 때만 발생한다. 유료 모델 사용 허가를 의미하지 않는다.
- 승인 지식9문서의 구조·역할을 유지하며 onchain-settlement-policy v3와 manifest hash를 오프라인 갱신한다. 손익/유예/DB 수탁/유한 pool/실제 검증 경계를 추가한다. **기존 AI index가 갱신됐다는 의미가 아니다.** 기존 active index와 fingerprint가 다르면 fail-closed하며 재색인·유료 embedding/LLM은 승인 후 별도 실행한다.

## 검증 기록

- 최초 선택 단위/registry/knowledge 테스트 및 컴파일 PASS. 실제 모델·유료 API 호출0. 이후 전체 AI 회귀 및 새 격리 PG/Anvil에서 자동60초 청산 증거를 실제 Tool/Skill/Mock Agent가 조회하는 테스트를 진행한다.
- `FuturesAiPostgresAnvilE2ETest`: 신규 전용 fixture/전용 키/포트/체인만 사용한다. 가격은 기존 test-only Scenario 공급자이며 **제품 Synthetic/Chrome 결과와 별개**다. 실제 DB/Anvil/Worker/정규60초/Tool 조회는 actual, LLM 및 retrieval은 Mock/fixture다. 이를 실제 LLM 평가나 실제 pgvector 검색 평가 PASS로 기록하지 않는다.
- 초기 기록: 당시 전체 회귀, 독립 검토 및 해당 변경의 로컬 커밋은 미완료였다. 기존266465e의584/48·웹119·Foundry52 및 actual Chrome PASS를 이번 AI 소스의 새 PASS로 재사용하지 않는다. 후속 최종 결과는 아래 별도 실행을 따른다.

### 첫 검토와 보완

- 초기 actual `phase-a-20261019115455-416ae627/futures-ai-7e12ff30`1 PASS/0FAIL/0SKIP: 실제60초 유예 후 Long 자동청산 및 canonical Tool/Mock Skill·Agent 조회, 타USER404/ADMIN 명시조회, 원장fingerprint 불변. 이 결과는 아래 보완 전 소스의 증거이며 보완 후 새 fixture를 다시 사용한다.
- 오프라인 AI196중170 PASS/26SKIP/0FAIL, 웹119 PASS, Chrome desktop/mobile offline fixture PASS, Python18 PASS.
- 최초 독립 검토 필수2건: terminal position R/D projection과 canonical operation의 누락 대조; 두 Tool 사이 같은 OPEN 상태의 version/담보/risk 변경 혼합. 기존 감사의 순수 settlementMatches를 공개 재사용하고, 안전한 positionSnapshot의 version/담보/risk/epoch/deadline/잠금중operation/R/D를 정확히 비교하도록 보완한다. 불일치는 INCONCLUSIVE/PARTIAL이며 모델 종합하지 않는다. 실제 거래/정산 정책은 변경하지 않는다. 각 R/D 변조와 같은OPEN의 교차Tool 경합을 별도 단위 테스트한다.

### 보완 후 신규 실제 검증

- 기준 session `phase-a-20261019120028-4ac049a3`, sourceHead266465e + 명시된 미커밋 AI 변경, productHash `172e90d1892a6038591eaf2b9a949ea122cf9b796e6c6e8da333aa1905fde4b3`.
- 관련 순수/Mock 테스트18 PASS/0SKIP/0FAIL, 웹120 PASS. `review_futures_ai`1차 재검토에서 필수2건 해결·추가 필수 수정 없음. 실제 소스/diff/문서 검토이며 private/DB/테스트 실행은 검토자가 하지 않았다.
- **futures-ai-0841d476 실제PG/Anvil1 PASS/0SKIP/0FAIL**: Worker의 실제60초 청산과 canonical Receipt/Event·journal 조회, 본인/ADMIN 허용·타USER404, read-only cut fingerprint 불변, 실제 dispatcher/Skill/Mock Agent ANSWERED 및2 Tool 호출. LLM/검색은 Mock/fixture이며 실제 모델/pgvector 검색 평가가 아니다.
- 동일 제품 hash의 **futures-browser-655a2024 실제 제품Synthetic/Chrome1 PASS**: 8단계,6포지션, 개인event38, 가격 재확인 거절0. Long/Short 청산 각각 chain call 이후62초, tx fa47fc2a…/2672d2a6…, 두 성공 Receipt/Event·DB 정산. Futures audit MATCH/coverage complete(19tx/38events, block44~147), 기존 reserve MATCH, 소유 하위42개 잔존0. 이전 c1767e1e/실패fixture는 별도 증거로 보존한다.
- 동일 제품 hash의 **futures-confirmed-46c6222d** prepare1 PASS + 별도 JVM recover1 PASS. 동일raw/hash/nonce의 실제 canonical 성공 거래를 단일 CONFIRMED 정산. 이전 REVERTED/CONFIRMED 증거를 덮어쓰지 않는다.
- 최종 전체 회귀는 새 실행 중이다. 제품 지식 파일은 오프라인 승인 manifest만 갱신했으며 기존 AI index·보존 환경은 변경하지 않았다.

### 전체 회귀 native socket 실패 보존

- 최초4ac049a3 backend643중591PASS/49SKIP/3FAIL: OpenAiProviderTest, GoogleJwksVerifierTest, FuturesRpcGatewayTest의 fixture 서버 시작 `SocketException: Bad address bind/listen`이다. 그 세 테스트는 다음 전체 실행에서는 통과했다. 제품 거래 또는 외부 공급자 요청 전 단계다.
- 새e6f7cc06 backend643중593PASS/49SKIP/1FAIL: 이번에는 TossPriceConfigurationTest의 loopback bind에서 같은 native 오류다. 첫 실패와 새 실패를 모두 보존하며 원인이 영구 해결됐다고 단정하지 않는다. assertions·소스·기존 서비스/시스템 설정을 바꾸거나 실패를 제외하지 않았다.
- 반복 무수정 재실행 대신 **선택적** `regression --isolate-test-workers`를 추가한다. 실행 전용 Gradle init은 maxParallelForks1/forkEvery20으로 같은 전체 테스트를20클래스마다 새 worker JVM에서 실행한다. 제품 build.gradle/runtime/정산·price 정책은 변경하지 않는다. 기본 회귀는 그대로이며 모든643개/49SKIP 및 실패0을 실제 결과로 확인해야 한다. JVM 수명 격리는 원인 확정이나 Windows 환경 영구 해결의 증거가 아니다.

### 최종 전수 결과 및 보안

- 새 실행 `e6f7cc06/evidence/backend-full-isolated-workers`: **643중594 PASS/49 SKIP/0FAIL/0ERROR**, 6분20초의 Gradle 실행. 기존 단일 worker3FAIL/1FAIL을 덮어쓰거나 합쳐 PASS로 만들지 않았다. 같은643개 전수와49SKIP이며 필터·assertion·기존 gate 변경이 없다.
- 이어서 같은 최종 소스의 웹120 PASS/0SKIP, 실제 Chrome desktop/mobile **offline fixture** PASS, Node 브라우저 검증기8 PASS/0SKIP, Foundry52 PASS/0SKIP. 전체 `forge build --offline` exit0(캐시 유효 확인 포함), 원래 Scenario 스택 실패는 f5c5a28의 별도 실제 컴파일/로컬EVM dryrun으로 해결했다. lint 경고0 또는 Windows socket 영구 해결로 확대하지 않는다.
- Python harness/clock20 PASS(신규 선택 worker 모드2건 포함). 최종2차 독립 재검토에서도 추가 필수 수정 없음. 실행 전용 격리·전체 선택·evidence 구분·남은 한계 문서를 읽었고 직접 테스트/DB/private 접근은 하지 않았다.
- 명시된 신규38경로만 staging했다. 최초 비밀 검사 집계는 PowerShell 자동 변수와 이름 충돌로 종료됐고 값 노출 없이 변수명만 수정했다. 새fixture 생성 비밀값15개와 실제 staged diff 대조 **0일치**, 금지 데이터 경로0, diff check PASS. 기존 사용자5경로 및 모든 private/runtime/DB/chain/log/빌드 산출물은 제외한다.
- 실제 LLM·새 corpus 유료 embedding/index·KIWOOM/KRX/NXT·AWS/Sepolia/Android 인수는 **NOT_RUN**이다. 이 결과를 전체 PhaseD 또는 실제 공급자 인수 PASS로 선언하지 않는다. 단일 JVM Windows native bind/listen 오류는 알려진 실행환경 한계로 남고 선택 격리 실행의 안정성도 한 번의 전수 통과로 영구 보장하지 않는다.
- productHash는 기존 harness 정의상 backend/src/main·계약 source/지정 배포 도구를 식별한다. 웹·테스트·문서는 동일 작업 트리의 실제 실행 및 명시 Git 변경 범위로 연결하며, 이 hash가 저장소 모든 파일을 해시한다고 설명하지 않는다.
- 추가 최종 현물 `e6f7cc06/sellall-8fbaf77a` **실제 PG/Anvil1 PASS/0SKIP/0FAIL**: Synthetic Quote BUY→부분 SELL 잠금→REST 정확 가용수량→전량 SELL/mSEC잔여0·잠금0, 세 거래FILLED/성공Receipt, 기존 자산대사MATCH와 거래전수감사MATCH/DB·chain complete/cutStable/environmentVerified. 실제 Google/KRX 또는 Chrome 현물 재인수로 확대하지 않는다.

## 유료 실제 AI 후속 승인

최신9문서의 실제 embedding/index 갱신1회와 선물 질문5개(왜 청산, CALL 계산, canonical 확정, journal, 불확실 증거) 평가가 후보이다. 질문당 최대2모델 호출/1검색/4Tool; 5개면 모델 최대10회·검색 최대5회다. 현재 모델의 요금과 실제 token 측정이 확정되지 않아 비용은 미확정이며 호출하지 않는다. 기존 index/capture/설정에 secret을 복사하거나 임의 교체하지 않는다.
