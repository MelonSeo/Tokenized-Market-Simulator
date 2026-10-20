# KRX 우선 점검 및 AI 입력 한도 v4 — 2026-10-20

## 1. 우선순위와 KRX 점검 결과

기준 HEAD `melon-init@45a4554`, 기존 보호 사용자5경로는 보존한다. 사용자는 KRX 시세 읽기 조회/구독과 AI 누적 입력 기술 한도400,000을 승인했다. 실제 주문·거래DB/Anvil 변경·유료AI·환경초기화·push/배포는 승인하지 않았다.

**최우선 점검 시각: 2026-10-20 19:33:28 KST**, 별도 UTC clock은10:33:29였다. 정규장 관측 시간 조건을 충족하지 않아 실제 키움 연결을 시작하지 않았다. KRX 정규시장 통상 시간09:00~15:30은 [KRX 유가증권 거래제도](https://regulation.krx.co.kr/contents/RGL/03/03010100/RGL03010100T1.jsp)와 [생활법령 안내](https://easylaw.go.kr/CSP/CnpClsMain.laf?csmSeq=1701&ccfNo=2&cciNo=1&cnpClsNo=2&menuType=ov)의 검색 결과로 대조했으나 이번 직접 페이지 열기는403/도구 오류였다. 당일 연장이나 실제 시장 CLOSED 코드가 API로 확인됐다는 뜻은 아니다. 시간표만으로 제품 OPEN/CLOSED 상태를 생성하지 않았다.

확인한 저장소 근거:

- `docs/PROJECT_COMPLETION_MASTER_PLAN_2026-10-18.md`: M02~04 및 Phase B는 실제 KRX 읽기 관측과 제품 BUY/SELL 인수를 분리한다. M05 NXT 관측 성공은 KRX 인수 성공이 아니다.
- `KiwoomProtocol.subscribe/trade/state`: `005930`의0B 및 빈 item0s, item 정확히005930만 수용. 290=2는 정규 체결, 215=3은 명시 OPEN이고 지원 CLOSED 코드와 UNKNOWN은 별개다. NXT item/미확인9081 의미를 KRX로 해석하지 않는다.
- `KiwoomPriceProvider.currentForTrading`: 현재 generation 체결·연결·LIVE·fresh(과거5초/미래2초)·발급전후 generation 확인, 명시CLOSED/지원불가 상태 우선. 0s 미수신은 그 자체로 차단하지 않고 조건 충족 시 UNKNOWN을 유지할 수 있다.
- 기존 `deployment/kiwoom-diagnostic.py` mixed profile의 token1+1분봉최대3, WS1/180초/5000 정제event 경로를 재사용할 수 있다. 이번에는 init/capture를 실행하거나 기존 prepared run/capture/credential을 수정하지 않았다.

| 항목 | 이번 판정 |
|---|---|
| 정규장 시간 전제 | BLOCKED — 점검시각19:33, 유효 정규 세션 근거 없음 |
| OAuth/REST/WS | NOT_RUN, 실제 호출 각각0 |
| item/290/9081/215/체결시각/가격 정규화 실측 | NOT_RUN — 새 event 없음 |
| current-generation LIVE/fresh 가용성 | NOT_RUN — 제품 서버 미기동, 상태 추정 없음 |
| KRX Quote/BUY/SELL/receipt/DB/WS/두 대사 | 미완료 유지. 이번 읽기 전용 승인으로 거래 실행하지 않음 |
| NXT/애프터 또는 Synthetic 대체 | 실행하지 않음. KRX 성공으로 대체하지 않음 |

다음 유효 정규 세션에는 기존 진단으로 새 private run/최신source와 소유권·credential 참조·중복WS 안전성을 확인하고005930만 제한 관측한다. 0s가 없다고 OPEN을 만들어내거나 같은 실험을 무한 재실행하지 않는다. 실제 주문 연계 인수는 별도 범위로 남긴다.

## 2. 장 종료 후 승인된 AI 보완

`deployment/ai_evaluation_guard.py`의 원 v3 policy를 불변 `LEGACY_POLICY_V3`로 보존하고 새 `futures-ai-eval-v4`에서는 **input_tokens만256,000→400,000**으로 바꿨다. 버전 변경 외 나머지 모든 필드는 같다: 총US$2/LLM32/embedding80, output32K/embedding100K, 모델·단가·요청별bound·framing·재시도0/선예약·무환급 유지.

`ai_reevaluation_plan.derive()`는 부모의 정확한 v3 또는 v4 policy 및 그 policy로 저장된 identity/context를 검증한다. 부모를 현재 v4로 덮어쓰지 않는다. 부모가 v3 당시 한도를 넘었다면 새400K로 합법화하지 않고 거부한다. 원 policy를 적용한 비용 산식과 사용량을 확인한 뒤, 현재 승인 v4 총한도에서 선차감한다. 기존 v3 ledger를 v4 Guard로 직접 여는 것도 identity mismatch로 거부한다.

| 누적 항목 | 부모 v3 무환급 사용/예약 | v4 잔여 |
|---|---:|---:|
| LLM / embedding 호출 | 5 / 35 | 27 / 45 |
| input 기술 bound | 39,437 | **360,563** |
| output / embedding bound | 3,200 / 73,201 | 28,800 / 26,799 |
| USD 예약 | 0.13845652 | **1.86154348** |

원 부모 ledger SHA는 `db4e7a9995cd3603a64eeb7c41978e1f971c3a97ac0d2f6902c35c9a6cd4c292`다. 정확한 토큰 청구액이 아니라 기존 보수 예약을 그대로 차감한다. 새 ledger로 호출/예산을 초기화하지 않는다.

부모 종료 확인·영속 단일child claim·불확실 전송 중단은 변경하지 않았다. 읽기 전용 종료 검사에서 부모 승인이 아직 활성인 경우 `PARENT_AUTHORIZATION_STILL_ACTIVE`가 실제 발생했으며 이를 우회하지 않았다. 자연 만료/기존STOP/control 종료를 다시 검증해야 한다. 실제 child claim/새 승인/키 파일은 생성하지 않았다.

## 3. 추가로 발견한 sourceHash 경로 정규화 결함

첫 v4 Java capture04와 root에서 계산한 sourceHash가 달랐다. 같은 파일인데 Java helper의 `../deployment` 상대 import가 `__file__` 유래 path에 `backend/..`를 남길 수 있고, broker는 그 path를 정규화하지 않은 채 정렬·파일명 해싱했다.

최소 수정: `ai_evaluation_broker.source_hash`에서 모든 Path를 resolve 후 중복 제거·정렬한다. 해시 범위를 축소하거나 기존 manifest를 덮어쓰지 않는다. root와 backend 상대 import의 해시 동등성 회귀 테스트를 추가했다. 기존 capture04는 산술 진단 자료로만 보존하고 최종 실행 identity에 채택하지 않는다. 새 capture05에서 source 전후 및 root 일치를 확인한다.

## 4. 이번 새 검증과 예산 판정

이전 PG복사/전체선물/Chrome PASS를 새 실행으로 합산하지 않는다. 이번에는 Docker·DB·Anvil을 기동하거나 재색인하지 않았다. OpenAI Docs의 [token counting](https://developers.openai.com/api/docs/guides/token-counting)을 확인했으며 provider request의 실제usage와 보수 input bound를 구분했다. 유료 API는0회다.

- **최종 sourceHash:** `89cb60c0bb3e0132ca1682a30cdb529dc982d74211391ea153d66422de86dcfe`. 새 capture05의 전후 해시와 root 재계산 해시가 모두 같다. corpus는 기존 `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`로 유지한다.
- **Python70 PASS / FAIL0 / ERROR0:** `test_ai_input_budget_v4`, `test_ai_evaluation_guard`, `test_ai_reevaluation_plan`, `test_ai_evaluation_lineage`, `test_ai_evaluation_broker`, `test_ai_evaluation_dry_run`. 신규5개 v3→v4 경계와 root/backend sourceHash 회귀1개 포함. 처음74건 집계에는 import된 기존 test class5건 중복이 있어 이를 제거했고, 이후69건+신규경로회귀1건으로 최종70개를 실행했다. 중복을 추가 성과로 더하지 않는다.
- **Java32 PASS / FAIL0 / SKIP0:** DryRun1+PositiveSmoke18+Verdict4+GuardedRunner9. `test --offline --no-daemon --rerun-tasks` 및 이4개 class selector로 새 실행했다. 첫32PASS는 source 경로 차이가 있는 capture04에 귀속한다. 정규화 직후 한 명령은 UP-TO-DATE로 새 capture05가 없었으므로 성공으로 채택하지 않고 강제 재실행했다. 기존 XML을 새 실행 결과로 재사용하지 않았다.
- Tool10/Skill4 정상 handler 및 권한·실패/불확실 경계는 local fixture다. 현재 모델의 실제 라우팅·응답 정답성 또는 새로운 체인·거래 E2E PASS가 아니다.

최종 `ai-q2-16-dry-run-20261020-05.private.json`과 `ai-q2-16-budget-20261020-04.private.json`(ignored runtime):

| 항목 | v4 잔여 | EXPECTED_STATE | MAXIMUM_MIXED |
|---|---:|---:|---:|
| LLM 호출 | 27 | 20 | 24 |
| embedding 호출 | 45 | 11 | 15 |
| input bound | 360,563 | 242,966 | 308,944 |
| output bound | 28,800 | 20,000 | 26,400 |
| embedding bound | 26,799 | 1,270 | 1,588 |
| 추가USD예약 | 1.86154348 | 0.84744040 | 1.08919176 |

**두 profile 모두 fit**이다. 최대 profile의 부모 포함 input은348,381/400,000(여유51,619), 부모 포함 예약은US$1.22764828/2다. 이는 가장 큰 적격RAG chunk와 고정 요청의 오프라인 stress 값이며, 실제 모델이 선택할 모든 분기나 정답을 보장하지 않는다. 최종 live 요청은 실제 body마다 누적 Guard가 제한한다. 입력400K 승인으로 이전 입력 예산 차단은 이 검증 범위에서 해소됐지만, 부모 승인 종료 확인과 별도 유료 실행 승인은 여전히 필요하다.

독립 검토는 `review_ai_budget_v4`가 실제 코드·테스트·문서에서 최초 및 최종 집계 문서 재검토 모두 **발견된 필수 수정 없음**으로 보고했다. 검토자는 private/API/DB/Docker/테스트를 직접 실행하지 않았다. 제품(Java/Solidity/웹) 변경0, 키움 OAuth/REST/WS0, 실제 LLM/embedding0, child claim/paid approval 생성0, 기존 환경 변경0이다.

## 5. 남은 승인·인수

- KRX 실제 item/코드/시각/신선도/정규화 관측 및 최신 제품 거래 인수는 다음 유효 정규 세션에 남는다. 오늘19:33 점검을 정규장 성공으로 표시하지 않는다.
- AI는 v4 dry-run fit과 오프라인 검사 후에도 **실제 LLM/embedding 재평가 NOT_RUN/유료 승인 대기**다. 입력400K 승인은 유료 호출 승인이 아니다.
- 부모 승인 종료와 단일child 검증은 여전히 필수다. 미래 child의새source/owner index는 검증된 vector-copy로 준비해야 한다. 원 DB/lease/STOP/manifest는 수정하지 않는다.
- 기존 사용자5경로·KRX/Google/NXT/거래DB/Anvil·이전평가증거를 보존하고 remote push/배포는 하지 않는다.
