# Q2~16 실제 평가 마지막 무과금 준비 — 입력 예산 BLOCKED

## 범위와 결론

기준 `melon-init@15f267f`, [이전 보완](ai-evaluation-harness-followup-2026-10-20.md). 제품 코드·금융 데이터·기존 index/lease/STOP/ledger를 수정하지 않았다. **추가 LLM/embedding 호출0**. OpenAI Docs의 [응답 출력 제한](https://developers.openai.com/api/docs/guides/text)도 확인했지만 모델·요금·허용량을 변경하지 않았다.

이번에 새 index 데이터 복사, 부모 예약 선차감 Guard와 단일 child 제한, 실제 Java 직렬화 dry-run을 구현했다. **현 입력 bound 잔여216,563에서는15문항 전체 완주를 안전하게 승인할 수 없다.** 정상적인 예산 차단이며 실제 모델 품질 FAIL이 아니다. 유료 실행 및 실제 부모의 child claim 생성은 하지 않는다.

## 변경과 안전 경계

- `deployment/ai_evaluation_lineage.py`: 부모 manifest/ledger를 읽기 전용으로 검증. 부모 runId별 전용 registry의 O_EXCL claim은 최초 한 child만 허용하고 실패 claim도 자동 삭제하지 않는다. child 경로·context hash·부모 SHA·예약 합계를 결합한다. 매 guard 접근에서 부모/claim 변화를 재검증한다.
  - 부모가 아직 전송 가능한 경우 검증 직후의 경합을 방지하기 위해 **부모 승인 만료/영속 STOP 또는 stopped control**을 먼저 요구한다. 종료 근거와 승인 파일 SHA도 lineage에 결합한다. 완료 보고서만으로는 종료로 인정하지 않는다. 검토 중 실제 부모의 approvalExpired=false/STOP없음/control0을 읽기 전용으로 확인했으므로 현재 child 생성은 이 조건으로도 차단된다. 부모 파일을 변경해 강제 해제하지 않았다.
- `ai_evaluation_guard.py`: 부모의 호출·input/output/embedding 토큰·무환급 예약액을 자식 예약과 합산한다. carry가 ledger identity에 포함되므로 일반 Guard로 다시 열어 선차감을 누락하면 identity mismatch다. 원32/80/US$2·256K/32K/100K policy는 그대로다.
- `ai_evaluation_broker.py`: 실제 전송 경계가 누적 Guard를 사용한다. child live sender는 기존 승인 외에도 source가 일치하는15문항 dry-run의 두 profile·보존 선물 근거·budget fit 및 새 owner의 index-copy PASS 증거를 요구한다. 입력 초과나 증거 누락은 키 읽기/HTTP 연결 전에 거절한다.
- `ApprovedAiModelEvaluationTest`: lineage child는 Q1·고정28검색·재색인을 반복하지 않고 Q2~16만 실행한다. READY lease를 검증하고 원래16문항/판정 계약을 유지한다. 이 유료 test는 이번에 실행하지 않았다.
- `ai_reevaluation_plan.py`: 부모 예약의 음수 및 원가 산식 불일치도 차단한다. 기존 계획 출력은 역사적 계획이며 유료 실행 허가가 아니다.
- `ai_index_copy_fixture.py`와 `EvaluationIndexCopyTest`: 새 복사 DB만 생성/수정. 아래 복사·검증 절차 사용. 최신 실제 fixture 결과는 후속 표에 기록한다.
- `EvaluationRequestDryRunTest`와 `ai_evaluation_dry_run.py`: 실제 OpenAiProvider와 OpenAiAgentProvider가 직렬화한 body를 fake transport에서 포착해 같은 Guard `_bound`로 합산한다. HTTP client와 API key가 없다. source hash를 전후 대조하고 body는 ignored private 파일에만 보존한다.

## 실제 PostgreSQL/pgvector 복사 방식

부모 run은 `eval_7a8f25154337449e9f49d676b4d5fbb7`이다. 원 컨테이너가 정지/정상 종료·network-none인지, 원 volume 소유 label 및 실행중 참조가 없는지 먼저 확인했다. pinned local image만 사용하며 pull/외부 연결이 없다.

1. 원 volume을 helper의 `/source`에 **readonly**로 마운트하고 신규 volume `/target`에 cold copy한다. 원 DB를 시작하지 않는다.
2. 이 복제 volume의 별도 PostgreSQL을 default_transaction_read_only=on으로 시작한다. repeatable-read/read-only SQL로 부모 READY owner 및 ai indexes/documents/chunks만 export한다. 기존 비밀번호는 원 env 파일의 명시적 참조에만 쓰며 출력/최종 DB로 복사하지 않는다.
3. 최종 target은 새 DB/user/password/volume/network-none이다. 전용 loopback relay로 Java JDBC가 접속한다. 기존 public owner는 import하지 않고 **새 source/run/corpus owner**를 빈 DB에서 PREPARED→INDEXING으로 만든다.
4. 로컬 KnowledgeLoader의 실제 corpus와 모든 doc hash/version/title/role/domain/type/update 및 chunk ID/path/heading/content를 비교한다. 모든 벡터가 유한·nonzero·1536차원이어야 한다.
5. parameterized insert 후 원/target 데이터·vector text를 정확히 비교한다. created_at만 timezone 표기가 아닌 동일 Instant로 정규화한다. 비교가 성공한 후에만 READY로 commit한다.
6. 실제 pgvector cosine 검색/USER 필터, cached vector88개, READY 재개, 다른 source identity 거부, metadata/vector 변조 거부를 확인한다. 새 target의 잘못된 비밀번호28P01 검사도 최종 실제 실행에 포함한다.

복사 artifact SHA와 부모 ledger SHA, parent source/container/volume 식별자는 신규 private provenance에 보존한다. 초기 probe01은 표 비교 assertion에서 FAIL했고 당시 log와 DB를 보존했다. JDBC timezone 표기 비교의 테스트 문제를 보완한 새 probe02는 실제 Java1PASS였다. 최초 assertion의 전체 payload는 별도 보존하지 못했으므로 원래 실패 필드가 무엇이었는지 소급 확정하지 않는다. 비교 전 READY commit도 비교 후로 옮겨 검증 실패가 READY가 되는 경로를 막았다. 최종 최신 소스의 새 fixture 결과는 아래에 추가한다.

## 고정15문항 요청 dry-run과 예산

원 사용/예약: **LLM5 / embedding35 / US$0.13845652**. 부모 ledger SHA `db4e7a9995cd3603a64eeb7c41978e1f971c3a97ac0d2f6902c35c9a6cd4c292`을 유지한다.

| 항목 | 잔여 | 예상 STATE 경로 | 모두 MIXED로 검토한 경로 |
|---|---:|---:|---:|
| LLM 호출 | 27 | 20 | 24 |
| embedding 호출 | 45 | 11 | 15 |
| input 보수 bound | **216,563** | **242,960** | **308,941** |
| output 예약 토큰 | 28,800 | 20,000 | 26,400 |
| embedding 보수 bound | 26,799 | 1,270 | 1,588 |
| 추가 USD 예약 | 1.86154348 | 0.84742540 | 1.08918426 |

이 수치는 최초 정상 직렬화 capture02의 결과다. 마지막 동일 소스 dry-run을 재실행하면 마지막 결과를 아래에 별도로 기록한다. profile은 실제 provider body+4096 framing의 byte 보수값이며 BPE 실사용 토큰이 아니다. 실제 embedding query는 AgentService가 선택하는 고정 정책 질문이다. query vector는 로컬 모형으로 응답하고 실제 검색 품질 평가로 기록하지 않는다.

RAG 근거는 현재 corpus에서 USER/ADMIN 및 Skill domain·문서당 최대2개·Top5를 지키며 가장 큰 청크를 선택한다. 즉 실제 query similarity 결과를 아는 척하지 않는 보수적 context stress다. 실제 모델 route/Skill 선택에 따라 양은 달라지며 이 두 profile이 모든 가능한 모델 응답을 열거한다는 뜻은 아니다. 따라서 **실제 모든 요청이 반드시 실패한다고 단정하지 않지만 현 cap에서 전체15개를 완료한다고도 보장할 수 없다.** 전송마다 실측 body를 예약하는 Guard가 최종 상한을 강제한다.

Q16은 불확실 근거에서 PARTIAL/모델 종합0의 안전 경로다. Q1 및 고정 검색의 과거 PASS는 그대로 유지한다. 이번 mock/오프라인 검증을 신규 실제 모델 정답성으로 기록하지 않는다.

## 검사·실패 이력·보존

- 누적 Guard 관련 Python 초기57PASS → 보강71PASS. 별도 process 재개·broker 전송 경계·부모 output 한도 보강 후 최종 결과는 후속 추가한다.
- 첫 dry-run compile에서 lambda checked exception 처리가 누락돼3개 compiler error가 있었다. local transport catch를 보완했다.
- 첫 증거 경로가 evidence가 아닌 fixtures에 있다는 점을 바로잡았다. 그때 로컬 fixture의 신규 MIXED mock 응답에서도 scalar pointer 누락이 발견돼 portfolio/filter를 보완했다. Gradle의 부수 `Bad address: listen`도 함께 기록하되 이를 실제 모델/DB 장애 원인으로 단정하지 않는다.
- 후속 실제 보존 선물 snapshot 기반 dry-run과 positive gate는 Java23PASS였다. 실제 모델·새 금융 E2E가 아니다.
- 실패 fixture01, 성공 fixture02와 log·원래 run/STOP/ledger/index는 초기화하거나 삭제하지 않는다. 전용 신규 container만 소유권 확인 후 정지하고 volume/evidence는 보존한다.
- 기존 사용자5경로, 거래 DB/Anvil, KRX/Google/NXT는 보존한다. 전체 선물/Chrome E2E를 반복하지 않는다. remote push/배포도 없다.

## 승인 필요 사항과 다음 실행

1. 현 정책을 자동 완화하지 않는다. **입력 기술 예산 변경 여부**를 먼저 결정해야 한다. context stress 최대값과 부모39,437을 합치면 input 총348,378로 현재256,000을 넘는다. profile 밖의 모델 분기에 대한 추가 여유도 고려해야 하며, 이를 검토 없이 새 cap으로 채택하지 않는다.
2. 금액 총US$2는 변경할 필요가 있다는 근거가 없다. 최대 stress의 부모 포함 예약은US$1.22764078로 US$2 이내다. 기술 토큰 예산은 비용과 별도로 승인/버전 관리해야 하며 부모 원 policy/ledger를 고치거나 실사용량으로 환급하지 않는다.
3. 다른 선택은 근거를 임의로 삭제하지 않는 적법한 context 전달 방식 보완 또는 평가 분할이다. 분할만으로 합산 토큰 상한이 늘어나지는 않는다. 변경 시 정답성·근거 완전성 및 오프라인 재검토가 필요하다.
4. 입력 예산 결정 및 무과금 검증을 통과한 최종 source에서만 단일 child를 claim하고 새 immutable owner DB로 검증된 vector를 복사한다. 지금의 probe READY DB는 복사 기술 검증용이며 미래 live child라고 표시하지 않는다. 새 owner는 필요하지만 **유료 embedding 재생성은 필요하지 않다.**
   - 부모의 기존 승인 자연 만료 또는 별도 승인된 영속 종료도 선행 조건이다. 이번에 STOP을 추가하거나 approval/ledger를 수정하지 않는다. live child 생성 전에 다시 읽기 전용 확인해야 한다.
5. 그 이후에 Q2~16 실제 실행을 남은27/45/US$1.86154348 범위로 별도 승인받아야 한다. 현재 API key/paid approval/live child는 생성하지 않았고 실제 실행을 기다린다.

## 최종 검토 및 커밋

### 최종 동일 소스의 새 실행

sourceHash **`6287d2a8e78cbe45f82189b8ce6a87c961b21c681ac3442bce07f9b17b95c686`**, corpusHash `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`.

| 범위 | 새 결과·증거 |
|---|---|
| 최종 PG/pgvector 복사 | **1 PASS / 0 FAIL / 0 SKIP**, `ai-index-copy-probe-20261020-04`, owner `eval_a693f67305154fe8a5bd06b0e890d431` |
| 원/target canonical data SHA256 | `9888d43fb6a4056645efcf1c93fbd061246ef016ffbdd7244174e536b3a0869e`; 9docs/88chunks/1536dim, metadata/vector 일치·새 owner READY·다른source거부·USER SQL필터·wrong password28P01 |
| Java 관련 최종 회귀 | **53 PASS / 0 FAIL / 0 SKIP**. DryRun1 + PositiveSmoke18 + Verdict4 + Serialization3 + GuardedRunner9 + IndexLease7 + Provider7 + AgentProvider4 |
| Python 관련 회귀 | **76 PASS / 0 FAIL**. lineage13·dry-run2와 기존guard/broker/plan/relay 포함. 이후 copyfixture3건 추가와 해당 lineage/dry-run15건을 합친 subset **18 PASS**. 중복15건을 다시 더하지 않은 고유 검사79건 |
| 추가 LLM/embedding | **0 / 0**, 새 paid approval/key/live child claim0 |
| 새 환경 종료 | fixture01~04의 신규 소유 container12개 모두 exited; relay 종료, volume/실패log/DB/원자료 보존. 기존 환경 종료·초기화 없음 |

최종 private `ai-q2-16-dry-run-20261020-03.private.json`과 `ai-q2-16-budget-20261020-02.private.json`:

- EXPECTED_STATE: LLM20/embedding11/input **242,954**/output20,000/embed1,270/예약 **US$0.84741040**.
- MAXIMUM_MIXED: LLM24/embedding15/input **308,941**/output26,400/embed1,588/예약 **US$1.08918426**.
- capture02와 EXPECTED input은6byte 차이가 있었으며, 최종 값도 잔여216,563 초과다. 요청에는 조회 시각 등 가변 문자열이 포함되지만 그6byte의 원인을 별도로 확정한 것은 아니다. 실제 live 호출 상한이나 정책을 변경하지 않았다.
- 최종 PG의 원본 export artifact SHA는 `fedae1a0933a24f5362ab805e64e8baa346641fddd61f268878db25406338a80`. 모든 숫자는 새로운 관측으로 기록하며 과거검색/LLM정답성 지표와 합산하지 않는다.

probe03은 Gradle daemon 시작 전 `SocketException: Bad address: listen`으로 **DB 테스트 NOT_RUN**이다. 이 환경 장애를 제품/SQL 실패로 기록하지 않는다. Windows 자식 테스트 JVM에만 `-Djava.net.preferIPv4Stack=true`를 적용한 새 probe04에서 PASS했고 시스템 네트워크/전역서비스는 변경하지 않았다. 이 결과만으로 Windows listen 문제의 정확한 원인이나 영구 해결을 단정하지 않는다.

probe03의 예전 harness는 Gradle이 시작하지 못했는데 이전 build XML을 복사한 문제도 있었다. 원본 XML을 삭제하지 않고 `execution-provenance.json`에 stale/NOT_RUN임을 표시했다. 최종 harness는 --rerun-tasks와 report mtime 변경 확인으로 새 실행 XML만 복사한다. 최종04는 새 XML·전용log·verification을 모두 보존한다.

독립 `review_ai_q2_preparation`의 최초 소스·문서 검토와 부모 종료 경계 재검토, 마지막 IPv4·stale XML 방어와 실행 집계에 대한 최종 재검토 모두 **발견된 필수 수정 없음**이었다. 부모가 추가 전송할 수 없는 전제를 명확히 하라는 검토 의견을 반영해 active parent 차단과 expiry/permit 변경 테스트를 추가했다. 검토자는 실제 API/DB/Docker/private나 테스트를 직접 실행하지 않았다.

보호 사용자5개와 부모 STOP/ledger 해시는 기존 기록과 같다. 관련 명시 경로만 로컬 커밋하고 사용자5경로/private/runtime/DB/vector 원본 artifact는 제외한다. **준비 전체 PASS가 아니라, 복사·Guard·positive gate PASS / 전체15문항 유료 실행 BLOCKED**로 마감한다.
