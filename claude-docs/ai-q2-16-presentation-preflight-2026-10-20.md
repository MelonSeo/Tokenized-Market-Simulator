# Q2~16 실제 AI 평가 재개 전 점검과 발표 활용성

## 1. 현재 판정과 실행 식별자

- **무과금 준비 완료 / 유료 평가 승인 대기**. 이번 요청은 유료 실행 승인이 아니다. 신규 실제 모델 답변은 없으며 Q2~16 정답성은 모두 NOT_RUN이다. 과거 실패는 아래 원본 기록대로 보존한다.
- 브랜치 `melon-init`, 실행 HEAD `f9353c33244565b30fb130dfb6159eab441a3523`. 이번 변경은 평가 fixture와 회귀 테스트 및 이 보고서뿐이며 미커밋이다.
- 시작 sourceHash `89cb60c0bb3e0132ca1682a30cdb529dc982d74211391ea153d66422de86dcfe`.
- 최소 보완 후 sourceHash **`332499954d0041f87faa0deccded99965b2433efa98a129a1d2b14051bcdb9fd`**. 새 Java 직렬화 캡처와 root 재계산이 일치한다.
- corpusHash `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba` 유지. 기존 9문서·질문·검색 설정·금융 정책·프롬프트·정답 판정 기준 변경 없음.
- 신규 **무과금 증거 폴더** `deployment/runtime/ai-q2-16-preflight-20261020-01/`. live run/승인 파일/child claim/신규 AI DB는 만들지 않았다. 이 폴더를 유료 run으로 표시하지 않는다.

이전 근거: [실제 실패와 Q1/검색 결과](ai-real-model-evaluation-rerun-2026-10-20.md), [mapper 보완](ai-evaluation-harness-followup-2026-10-20.md), [v4 예산](krx-priority-ai-input-v4-2026-10-20.md), [병합 후 실제 거래/Mock AI](futures-postmerge-e2e-2026-10-20.md), [고정 평가 계획](futures-final-integration-2026-10-20.md).

## 2. 기존 실패와 이번 최소 보완

이전 plain `ObjectMapper`는 ToolResult의 Instant를 직렬화하지 못했다. Dispatcher의 IOException 처리가 정상 Tool 호출도 INVALID_TOOL_ARGUMENTS로 반환했고 Agent의 Tool/Skill 경로가 막혔다. Q1만 실제 답변 PASS였고 Q2~16 실패를 제품 기능 부재나 모델 오답으로 바꾸어 설명하지 않는다.

현재 `EvaluationJson.mapper()`는 Spring Jackson builder 및 ISO 날짜/Duration 설정을 사용한다. positive smoke에서 Boot Jackson auto-configuration 결과와 실제 ToolResult wire를 비교한다. Tool10/Skill4의 실제 Dispatcher/handler를 사용하되 reader·RAG·LLM은 fixture/Mock이다.

이번 발견: `EvaluationToolFixture`의 현재 시장은 provider=SIMULATED인데 priceStatus=LIVE였다. 제품 `SimulatedPriceProvider`의 정상 응답에 맞춰 **priceStatus만 SIMULATED**로 수정했다. 질문·모델 프롬프트·가용성 정책을 바꾸지 않았다. `EvaluationPositiveSmokeTest.syntheticMarketFixtureMatchesProductStatusAndSkillUsesObservedProvider`를 추가해 다음을 검증했다.

- 실제 Dispatcher의 getCurrentReferencePrice SUCCESS 및 provider/SIMULATED·priceStatus/SIMULATED·marketStatus/OPEN.
- 실제 시장 Skill이 SIMULATED_REFERENCE_OBSERVED를 기록하고 CLOSED/PRICE_UNAVAILABLE를 잘못 기록하지 않음.
- 명시 Skill은 모델 planning 없이 해당 Tool 경로를 사용함. 종합 답변은 Mock이므로 실제 모델의 합성 가격 인식 PASS가 아님.

## 3. 이번 무과금 실행과 실패 경계

### Java: 신규 XML 71 PASS / 0 FAIL / 0 ERROR / 0 SKIP

`backend/gradlew.bat test --offline --no-daemon --rerun-tasks`에 아래 8개 selector만 적용했다.

| 클래스 | 건수 |
|---|---:|
| EvaluationHarnessSerializationTest | 3 |
| EvaluationIndexLeaseTest | 7 |
| EvaluationPositiveSmokeTest | 19 |
| EvaluationRequestDryRunTest | 1 |
| EvaluationVerdictTest | 4 |
| GuardedAiEvaluationRunnerTest | 9 |
| SkillServiceTest | 22 |
| ToolDispatcherTest | 6 |

XML의 새 실행 timestamp는 UTC13:34:45~13:35:05이다. `java-first-results/`와 `dry-run.private.json`에 보존한다. live 환경 gate와 API 환경변수를 자식 프로세스에서 제거했으며 dry-run은 실제 Java provider 직렬화 + 로컬 transport만 사용했다. 실제 API/DB/RPC 호출이 아니다.

**명령 실패와 XML 통과를 분리한다.** 최초 PowerShell `ErrorActionPreference=Stop`이 Java 컴파일 경고 stderr를 NativeCommandError로 처리해 wrapper 종료는 실패했다. 그러나 테스트의 신규 XML 8개와 새 sourceHash의 CREATE_NEW dry-run 캡처가 실제 생성됐고 위 71개 결과를 확인했다. 명령 전체 BUILD SUCCESS라고 표현하지 않는다.

stderr 경계를 제거한 재확인 명령은 Gradle daemon 시작 중 `java.net.SocketException: Bad address: listen`으로 종료1이었다. 해당 실행은 테스트 미진입이며 `java-rerun-results/`에 복사된 기존 XML을 새 PASS로 계산하지 않는다. `java-rerun.private.log`를 보존했다. 제품·모델·DB 장애로 단정하지 않으며 무한 재시도하지 않았다. **유료 실행 직전 Gradle 프로세스 시작 가능성은 다시 확인해야 한다.**

후속으로 전체 테스트를 재실행하지 않고 `gradlew.bat help --offline --no-daemon`만 실행했다. API/live gate와 자식 프로세스의 JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS를 제거하고 JDK21을 사용했으며 **종료0**이었다(`gradle-start-check.private.log`). 최소 daemon 시작 가능성은 확인됐지만 특정 JVM 옵션이 앞선 오류의 확정 원인이라는 비교 실험은 아니다. 시스템 설정·공유 daemon/서비스 재시작은 없었다. 실패 이력과 유료 실행 직전 재확인 원칙은 유지한다.

### Python: 새 실행 73 PASS / 0 FAIL / 0 ERROR

`python -B -S -m unittest test_ai_input_budget_v4 test_ai_evaluation_guard test_ai_reevaluation_plan test_ai_evaluation_lineage test_ai_evaluation_broker test_ai_evaluation_dry_run test_ai_index_copy_fixture`

마지막 실행은 stderr를 subprocess 로그에 합쳐 종료0과 `Ran 73 tests / OK`를 확인했다. 첫 PowerShell wrapper는 unittest의 정상 stderr 진행 출력을 오류로 처리했으므로 별도 실패로 보존한다. 테스트의 임시 fake child/transport는 실제 평가 child claim이나 외부 호출이 아니다.

### 웹: 관련 단위 테스트 24 PASS / 0 FAIL / 0 SKIP

`node --test tools/websocket-test-client/test/futures.test.js tools/websocket-test-client/test/product.test.js`

선물 진단 버튼의 정확한 positionId 선택, 버튼만으로 AI/거래를 실행하지 않는 경계와 관련 UI/Agent API 계약을 검사했다. 실제 Chrome·서비스·유료 모델을 연결한 새 UI 인수가 아니다. 전체 backend/Foundry/Chrome/선물 E2E는 반복하지 않았다.

보조 조회에서 최초 인라인 Python 구문 오류와 스냅샷 읽기의 기본 cp949 디코딩 오류가 있었다. 각각 단일행 명령과 UTF-8 명시로 읽기 전용 재확인했다. 원본 데이터·승인·가격·경제 정책 수정으로 우회하지 않았다.

## 4. 부모 실행과 벡터 재사용

- 부모 run `eval_7a8f25154337449e9f49d676b4d5fbb7`, 자료 `deployment/runtime/ai-real-evaluation-20261020-02/`.
- read-only `derive()`는 모든 기존 예약이 OBSERVED이며 identity/회계가 일치함을 확인했다. ledger SHA256은 기존 `db4e7a9995cd3603a64eeb7c41978e1f971c3a97ac0d2f6902c35c9a6cd4c292`와 같다.
- 이번 `_snapshot()` 결과는 **EXPIRED**. 이전 보고서의 PARENT_AUTHORIZATION_STILL_ACTIVE는 당시 결과이며 지금은 자연 만료됐다. STOP/control/approval를 고치지 않았다.
- 영속 registry에서 해당 부모의 **childClaimExists=false**. 확인 이후 새 claim을 만들지 않았다. 유료 준비 직전 다시 검사하며 경쟁/변경이면 차단한다.
- 기존 copy probe04의 export 원문 SHA가 provenance와 검증 결과의 artifact SHA 모두와 일치한다. READY 부모, 9문서/88청크, 모든 벡터1536차원·유한·비영벡터, corpusHash 일치를 읽기 전용 확인했다.
- probe04의 실제 PostgreSQL/pgvector copy PASS는 **이전 실행 근거**다. 이번에는 Docker나 DB를 기동하지 않았으며 현재 DB 연결 검증을 새 PASS로 표시하지 않는다.
- 향후 새 live child는 현재 sourceHash/새 DB owner로 복사해야 한다. 이전 owner/manifest를 덮어쓰거나 probe READY DB를 live child로 전환하지 않는다. 승인 후 기존 cold RO 복사/신규 network-none DB/JDBC·SCRAM·lease 검증을 수행하고 통과해야 유료 전송으로 진입한다.
- 기존 READY 벡터를 재사용할 수 있으므로 전체 embedding 색인을 다시 만들 필요가 없다. corpus/model/dimension/metadata 검증이 달라지면 차단한다.

## 5. 누적 예산과 새 직렬화 dry-run

| 항목 | 기존 사용/무환급 예약 | 승인 정책 잔여 | 이번 최대 추가 예상 |
|---|---:|---:|---:|
| LLM | 5 | 27 | 24 |
| embedding | 35 | 45 | 15 |
| input 보수 bound | 39,437 | 360,563 | 308,948 |
| output 보수 bound | 3,200 | 28,800 | 26,400 |
| embedding 보수 bound | 73,201 | 26,799 | 1,588 |
| USD 무환급 예약 | 0.13845652 | 1.86154348 | 1.08920176 |

새 `budget.private.json`은 질문2~16과 현재 sourceHash의 직렬화 캡처를 사용한다. EXPECTED_STATE는 LLM20/embedding11/input242,971/output20,000/embedding1,270/예약US$0.84745290이다. MAXIMUM_MIXED도 모든 잔여 한도 안에 있다.

최대 경로의 부모 포함 합계는 LLM29/32, embedding50/80, input348,385/400,000, output29,600/32,000, embedding74,789/100,000, 예약 **US$1.22765828/2**다. 실제 청구액이나 확정 실사용 토큰이 아니라 Guard의 byte 기반 보수 예약 산식이다. 할인·요금 가정은 이번에 변경하지 않았다.

이 예상은 모든 가능한 모델 분기를 보장하지 않는다. 실제 body별 Guard가 최종 제한하며 불확실 전송·한도 초과·정책/identity 위반이면 즉시 중단한다. 자동 재시도0, 부모 선차감, 단일 child, 불확실 결과 무환급은 유지한다. 이번 추가 유료 호출/비용은0이다.

## 6. 질문별 현재 상태와 검토 기준

모든 고정 질문 문자열/기대 결과는 유지한다. 아래 **신규 실제 답변 판정은 전부 NOT_RUN**이며 표의 내용은 승인 후 확인할 기준이다. 과거 Q1은 해당 과거 source의 PASS로만 채택하고 재호출하지 않는다. 고정 검색/full reindex도 반복하지 않는다.

| Q | 대상과 승인 후 검토할 사항 | 신규 실제 판정 |
|---|---|---|
| 2 | 현재 기준가격/시장: 실제 선택 route와 Tool, SIMULATED 공급자·관측시각·가격 단위. 시장 reader는 controlled fixture | NOT_RUN |
| 3 | 본인 portfolio: total/locked/available 및 단위. controlled fixture와 대조 | NOT_RUN |
| 4 | 주문 상태: 본인 target, PENDING_ONCHAIN을 체결로 단정하지 않음 | NOT_RUN |
| 5 | CONSUMED/expired와 FILLED를 구분하고 인용·만료 정책 정확성 | NOT_RUN |
| 6 | SIGNED/SUBMITTED 및 불확실 전송 의미, 재전송 실행 주장 금지 | NOT_RUN |
| 7 | receipt NOT_FOUND/INCONCLUSIVE를 미확정으로 설명 | NOT_RUN |
| 8 | ADMIN 이상 목록 허용, USER 접근 차단 | NOT_RUN |
| 9 | settlement-debugging 실제 trace·Tool 순서·정산 원인 유보 | NOT_RUN |
| 10 | signed-quote-diagnosis의 일회 소비/만료/정산 상태 구분 | NOT_RUN |
| 11 | market-availability-diagnosis가 Tool의 SIMULATED를 근거로 합성 시세와 실제 주가를 구분. 원 질문은 '시장 거래 가용성을 진단해주세요.'이며 '주말'을 새로 삽입하지 않음 | NOT_RUN |
| 12 | Long10의 실제 보존 담보/가격/START_CALL/LIQUIDATE 근거로 청산 이유를 설명. 사후 riskStatus=GRACE를 현재 진행 중인 유예로 오해하지 않음 | NOT_RUN |
| 13 | E=담보+손익, 현재 명목가치의5% 유지증거금, E<=MM 및 기록된 위험가격. 손실 cap/악성부채와 경제적 손익을 구분 | NOT_RUN |
| 14 | VERIFIED selected canonical Receipt/Event·블록·확인 수와 보존 snapshot임을 구분. fresh RPC 확정 주장 금지 | NOT_RUN |
| 15 | 실현손실/잠금 해제/journal wallet·house 관계와 범위 한계. 현재 house 총잔고나 전수 감사 완료를 없는 DTO로 추정하지 않음 | NOT_RUN |
| 16 | INCONCLUSIVE 근거에서 PARTIAL·불확실성 유지. 종합 LLM 미호출이 정상일 수 있으며 확정 성공/실패·새 주문/강제 정산 주장 금지 | NOT_RUN |

JUnit 성공·응답 생성·키워드 포함만으로 correctness를 PASS로 만들지 않는다. `EvaluationVerdict`는 request/model/correctness를 분리하며 완료된 답변도 수동 검토 전 REVIEW_REQUIRED다. 실제 질문별 route, Tool/Skill/trace, 수치/단위/시간, 인용과 전체 서술을 검토한다. 원본 자유 서술 전체가 구조화 fact 검증만으로 보장되지는 않는다.

Q2~11의 현재 거래/시장 reader는 Mock controlled fixture다. Q12~16만 실제 PostgreSQL/Anvil E2E에서 보존된 사실을 사용한다. 승인 후 평가 역시 새 거래 DB·새 체인 조회 검증으로 과장하지 않는다. Q11의 주말 실제 화면 인수는 고정 질문 평가와 별개다.

특히 **Q16의 INCONCLUSIVE/pending은 보존된 정상 청산 증거에 `EvaluationToolFixture.unverified`로 주입하는 controlled uncertainty**다. 실제 미확정 체인 상태를 이번에 새로 관찰한 것이 아니라, 불충분한 증거에서 안전하게 유보하는 경로를 검증한다(`ApprovedAiModelEvaluationTest`의 i==15).

## 7. 선물 실제 근거와 발표 UI 사용성

스냅샷: `phase-a-20261019233131-dfbb357a/fixtures/futures-ai-fce63982/ai.evidence.json`(private). 원본을 읽기만 했다.

- LONG10, entry75,000, collateral100,000mKRW, LIQUIDATED.
- 위험/청산 가격60,000. 기록된 equity 약-100,000, maintenance 약40,000이며 정확한 18자리 값은 원본으로 대조한다.
- 실현손실은 담보 한도100,000, badDebt는 약100,000으로 별도 기록돼 있다. 음수 equity 전체를 사용자 잔고에서 추가 차감했다고 설명하면 틀리다.
- START_CALL 체인시각1792454685, deadline1792454745, LIQUIDATE1792454753: 실제68초, 유예60초 충족.
- 두 selected operation VERIFIED, 청산 walletDelta=-100,000/lockedDelta=-100,000, 마진콜의 두 delta는0. 전체 house solvency나 실제 온체인 담보 예치를 증명하지 않는다.

제품 `FuturesToolReader`는 본인/ADMIN 권한 확인, canonical receipt/block/event·서명/거래·DB journal을 검증한다. evidence 범위는 SELECTED_CALL_AND_LIQUIDATION_ONLY이고 onchainEscrow=false/payoutGuaranteed=false다. Agent/Skill은 본인 포지션 Tool과 청산 증거 Tool 두 개를 결합한다.

웹 `futures-panel.js`의 'AI 읽기 전용 근거 진단' → `main.js`의 `selectAi('futures', positionId, ...)` → 사용자 제출 시 positionId target + futures-liquidation-diagnosis Skill로 이어진다. 따라서 '이 포지션은 왜 청산됐고, 블록체인에서 실제로 확정됐나요?'라는 한 질문을 전달할 **계약은 있다**. 버튼 클릭 자체는 거래/AI 호출을 실행하지 않는다. 실제 답변의 두 요소 충족은 유료 평가 전이므로 미확인이다.

이번 웹 검증은 실제 함수/DOM 모형 단위 테스트다. 로그인된 실제 Chrome + 최신 서버 + 현재 DB/RPC + 유료 모델 + index를 한 번에 연결한 신규 인수는 NOT_RUN이다. 기존 실제 Chrome/체인 및 Mock AI 성공을 이 결과로 대체하지 않는다.

## 8. 7~8분 발표 활용성: 현재 잠정 판정

**녹화 영상만 권장(실제 AI 답변 품질 수용 후 녹화한다는 조건부 추천)**. 현재 제출 가능한 실제 모델 청산 답변 영상이 완성됐다는 뜻은 아니다. Q12~16이 실패하면 AI 답변 시연은 발표에서 제외하고 이미 검증된 거래/자동청산/감사를 중심으로 한다.

- 후보: Long10 자동청산 확정 화면 → AI 근거 진단 → 청산 이유·60초 유예·canonical 확정·보존/현재 조회 한계를 짧게 설명.
- 소비 시간: 기존 Agent 한 run의 서버 예산40초(외부 계획/검색/종합 및 Tool 단계 제한 포함). 정확한 실모델 latency는 이번에 측정하지 않았다. 발표 영상은 근거 화면과 핵심 답변을30~45초로 편집하는 제안이며 실측 응답시간이 아니다.
- 의존성: 로그인/JWT, 실제 서버·거래DB/RPC, 활성 AI 기능, 일치하는 RAG index·embedding·LLM과 외부 네트워크. Synthetic는 증권 API를 제거하지만 실제 AI 외부 의존성을 없애지는 않는다.
- 위험: API/네트워크 지연, 모델 실패, index 승인/버전 불일치, 로그아웃·JWT 만료, 현재 상태와 과거 스냅샷 혼동, Tool 범위를 벗어난 house/전수 정합성 보증.
- AI가 청산을 판단·실행한다고 설명하지 않는다. 서버 worker가 실행하고 Solidity가 조건을 검증하며 AI는 읽기 전용으로 근거를 설명한다.

## 9. 사용자 승인 이후의 정확한 경계

승인 요청은 **추가US$2가 아니라 부모 포함 총US$2**, gpt-5.6-terra / text-embedding-3-small, Q2~16만, 추가 최대LLM24/embedding15(정책 잔여27/45 내), 자동재시도0이다. 부모/child·source/corpus/index·Guard·JDBC 사전검사에 실패하면 유료 호출하지 않는다.

1. 최종 소스와 Gradle 자식 프로세스 시작 가능성 무과금 확인. native 오류 지속 시 BLOCKED 유지.
2. 부모 EXPIRED/ledger/uncertain 상태 및 미사용 단일 child 재확인.
3. 승인된 새 live identity에 기존 도구로 RO vector-copy, 새 DB owner/JDBC/SCRAM/pgvector/READY 검증. probe/부모를 재설정하지 않음.
4. 비용 승인 파일의 정확한 identity/모델/상한 검증 뒤 Q2~16만 실행. Guard 실패/불확실 전송 시 즉시 중단, 새 ledger로 우회 금지.
5. 실제 자유 답변·구조화 facts·인용을 보존 스냅샷/정책과 대조해 질문별 수동 판정. Q1/검색의 과거 근거는 출처를 유지.
6. 질문 결과를 검토한 후 발표용 별도 실제 UI 인수·녹화 여부를 사용자와 결정. 추가 유료 질문/시연 호출을 이번 무과금 준비로 승인한 것으로 해석하지 않음.

기존 사용자5경로와 KRX/Google/NXT/거래DB/Anvil·이미지·manifest·private 증거는 보존한다. 커밋/merge/push/배포는 이번 작업에서 수행하지 않았다. 독립 검토 결과 및 최종 보존 확인은 아래에 누적한다.

최종 보존 검사에서 사용자5경로의 SHA256은 시작 전 기준과 모두 동일했다. 부모 ledger도 기존 SHA256과 일치하며 새 child claim/유료 승인 파일/실제 평가 run은 생성하지 않았다. 이번 미커밋 변경은 명시된 test2개와 이 보고서1개이며 사용자5개와 분리한다. `git diff --check`는 통과했고 Git의 LF→CRLF 경고는 남는다.

## 10. 독립 검토

별도 에이전트 `review_q2_presentation_preflight`의 결론은 **발견된 필수 수정 없음**이다. 실제 diff와 EvaluationJson/평가 runner/SkillRunner/FuturesToolReader/UI 연결, 비용 합산 및 보고서의 검증 범위를 읽기 전용 대조했다. 직접 테스트·Docker·DB·API·private 증거 접근은 하지 않았고 실행 수치·ledger·SHA를 독립 재실행했다고 표현하지 않았다.

선택 제안은 Q16의 불확실성이 controlled injection이라는 설명이었다. §6에 해당 문장을 반영했고 제품/테스트 코드는 추가 변경하지 않았다. 후속 Gradle help 성공은 앞선 native 오류의 원인 확정이나 전체 테스트 성공으로 확대하지 않는다. 최종 source/corpus 및 read-only ledger 대조에서 sourceHash는 위 값과 같고 부모 EXPIRED/child claim 없음/부모 ledger SHA 일치를 재확인했다.
