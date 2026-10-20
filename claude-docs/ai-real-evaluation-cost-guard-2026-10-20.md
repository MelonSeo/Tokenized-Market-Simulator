# 실제 AI 평가 비용 guard — 오프라인 준비

## 기준과 이번 범위

- 원본 `melon-init@7e166a252003c7da7de539101aabde0d15ce309b`. 기존 사용자 변경5개를 보존한다. 실제 원본 E2E의 실행 기준은 `25f4342`이며 후속 커밋과 혼동하지 않는다.
- [최신 실제 E2E](futures-postmerge-e2e-2026-10-20.md): 실제 Chrome/PG/Anvil, Long/Short 자동청산65/79초, 최종 선물감사19거래/38이벤트 MATCH, CONFIRMED 두JVM, 실제 체인+Mock AI PASS. 초기 Chrome2FAIL의 정확한 원인은 미확정이다.
- 실제 LLM·embedding·재색인 NOT_RUN. US$2는 **승인 요청 기준일 뿐 실행 승인 아님**. 이번에는 문서 조회 이외의 외부 API/비밀 조회·Docker·DB·Anvil·브라우저·기존 E2E 재실행이 없다.

## 구현

- `deployment/ai_evaluation_guard.py`: 제품 코드와 분리된 Python 표준 라이브러리 모듈. HTTP client, API key 로딩, 서버/CLI 또는 실전 자동 실행 기능이 없다.
- `EvaluationGuard.dispatch(path, raw, sender, request_id=...)`가 전송 직전 경계다. 기본 `execution_approved=False`이며 환경변수의 live flag/key 존재로 승인되지 않는다. 테스트에서만 가짜 sender와 명시적 true를 사용한다.
- 허용 경로는 `responses`, `embeddings`. 고정 모델·1536차원·text-only·JSON schema·store=false·reasoning none·출력<=1600만 허용한다. tools/image/history/background/priority/미확인 추가 필드·중복 JSON key·잘못된 UTF-8/비정상 숫자를 전송 전에 거부한다.
- LLM32회, embedding HTTP80회, 누적 입력256000/출력32000/embedding100000, nano-USD 정수 예약액2000000000(US$2)을 각각 검사한다. embedding16개 일괄 입력은 HTTP1회로 센다.
- 요청 전체 JSON UTF-8 byte 수 + framing4096을 LLM 입력 token의 보수적 대용으로 쓴다. 질문뿐 아니라 instructions/schema 전체를 포함한다. 단일 요청 상한32768. embedding은 각 문자열UTF-8 bytes+8, 개별8191 및 최대16개다. 이는 text-only BPE와 현재 요청 형식에 대한 공학적 보수 상한이며 공급자 과금에 대한 무조건적 수학적 보증이 아니다.
- 전송 전 SQLite `BEGIN IMMEDIATE`로 전체 한도/중복 확인→예약 INSERT→`synchronous=FULL` COMMIT. 원문/비밀/응답은 저장하지 않고 UUID·요청해시·누적 숫자·고정 outcome만 저장한다. 같은 ledger를 공유한 guard의 경합을 직렬화한다.
- runId/sourceHash/corpusHash/정책값을 불변 identity로 저장한다. create는 O_EXCL로 기존 ledger 덮어쓰기를 거부하고 resume은 mode=rw로 파일이 없으면 생성하지 않는다. 프로세스 재개 시 이전 예약을 유지한다. 새 ledger로 동일 평가를 다시 실행하여 예산을 초기화하는 것은 허용 절차가 아니다.
- 전송 실패는 고정 `TRANSPORT_UNCERTAIN`, 재시도0/환급0. 같은 request UUID는 다시 전송하지 않는다. crash 전후 RESERVED는 그대로 누적한다. 실제 usage 누락/음수/boolean/예약 초과는 BREACH로 기록하고 후속 실행을 차단한다. **한 ledger의 실행은 순차형**이며 진행중/불확실/결과미저장 RESERVED도 다른 인스턴스·프로세스의 새로운 전송을 차단한다. 성공 usage가 OBSERVED로 저장되어야 다음 호출이 가능하다. 저장 실패로 확인된 BREACH를 기록할 수 없어도 RESERVED를 근거로 영속 차단한다. 자동 해제/예약 삭제/새 ledger로 재시작하는 기능은 없다.

## 요금 가정과 한계

2026-10-20 [Terra 공식 페이지](https://developers.openai.com/api/docs/models/gpt-5.6-terra), [embedding 공식 페이지](https://developers.openai.com/api/docs/models/text-embedding-3-small)를 확인했다. 기존 계획은 표준 입력US$2/M·출력US$12/M·embeddingUS$0.02/M로 US$0.898이다. guard는 입력에 cache-write 1.25배의 보수 여유를 포함하여 US$2.50/M을 예약한다. 같은 누적 token 한도의 계산상 최대는 **US$1.026**이다. 캐시 할인/실사용 환급은 적용하지 않는다. US$2는 별도 최종 상한이다.

계정 접근 가능성·실제 과금·세금/환율은 미검증이다. 상한은 이 guard를 거치는 지원 요청에만 적용하며 계정 전체/다른 클라이언트/수동 API 사용을 제한하지 않는다. 미래 sender의 재시도/리다이렉트·숨은 요청은 이 모듈만으로 제어할 수 없다. sender는 정확히1회 bounded POST여야 한다.

**아직 실제 Java provider/평가 runner에 연결하지 않았다.** 기존 `OpenAiProvider`의 수명당500회 제한은 이 guard와 별개이며 US$2 hard cap이 아니다. 실제 평가 전에 기존 apiBase loopback 경계 등을 이용해 모든 Responses/embedding 전송을 단일 guard로 강제하고, 직접 외부 전송·자동 retry·승인 없는 runner 시작이 불가능한지 오프라인 검증해야 한다. 그때까지 유료 실행 준비 전체 완료/비용 절대 보장으로 표시하지 않는다.

## 이번 검증

실행: `python -B -S -m unittest discover -s deployment -p test_ai_evaluation_guard.py -v`

- 첫 실행: 23개 중22PASS/1ERROR. 비밀 비저장 테스트 자체의 SQLite 연결이 Windows에서 열린 채 남아 임시파일 정리에 실패했다. 테스트 연결을 명시적으로 close하도록 보완했다. 제품/기존 데이터 영향 없음.
- 수정 후 새 실행23PASS/0FAIL/0SKIP. 가짜 전송만 사용하며 실제 AI/embedding 호출0이다. 한도 직전/초과, 원자적 마지막 호출 경쟁, 동일UUID 경쟁, 전체 요청byte, 재시도 없음·무환급·오류 비노출, 새 Python 프로세스의 영속 예약, identity/정책 불일치 및 비밀 비저장을 확인했다.
- 추가 정책 snapshot 불변화/저장 실패/비정상 Unicode 검증 후27PASS/0FAIL/0SKIP. 기존 backend/웹/Foundry/E2E는 불필요하게 재실행하지 않는다.

### 독립 검토와 수정 후 결과

- `review_ai_cost_guard` 최초: 필수 수정1건. usage 위반 이후 결과UPDATE가 실패하면 현재 인스턴스만 disabled되고 다른 인스턴스는 RESERVED를 통해 후속 호출을 할 수 있었다. 결과미확정 RESERVED도 후속 호출을 차단하도록 수정했다. 실제 request가 아니라 문서에 '응답 byte'라고 적은 오기도 정정했다.
- 수정 후 새 오프라인 실행 **29PASS/0FAIL/0SKIP**. 기존27개에 위반usage 저장실패→새guard 차단, 진행중→다른guard 차단/성공저장 후 다음호출 허용2개를 추가했다. 기존 저장실패 테스트에도 재개 차단 assertion을 보강했다.
- 1차 재검토 `review_ai_cost_guard`: **발견된 필수 수정 없음**. 최종 guard/29개 테스트/문서/로그를 읽기 전용 검토했다. 직접 테스트·외부·Docker·private·기존 DB 접근은 없었으며 실제 전송 우회 차단/공급자 과금 검증을 대신하지 않는다. 실제 비용 발생0, LLM/embedding/재색인은 NOT_RUN이다.

## Git 및 보존 확인

- HEAD는7e166a2 그대로, staged없음, commit/merge/push없음. 이번 추가는 guard/테스트/이 문서3개와 `futures-implementation-log.md` 끝 기록뿐이다. `git diff --check` PASS.
- 기존 보호5개(`claude-docs/README.md`, `claude-docs/implementation-log.md`, `claude-docs/kiwoom-nxt-aftermarket-observation-2026-10-16.md`, `docs/FUTURES_48H_IMPLEMENTATION_PLAN.md`, `docs/PROJECT_COMPLETION_MASTER_PLAN_2026-10-18.md`)는 작업 전후SHA-256이 모두 동일하다. 기존 dirty를 stage하거나 정리하지 않았다.
- 새3파일만 JWT/PEM/OpenAI-key 패턴 정적검사0건. 실제 비밀파일을 읽거나 대조한 검사는 아니다. 신규 실행 ledger는 테스트 임시 폴더에서만 생성했으며 Git에 포함되지 않는다. 기존 환경·자료는 접근/변경하지 않았다.

## 다음 승인과 실행 전 조건

1. **현재는 외부 비용 미승인**. 승인 요청안: 기존 모델 유지,16질문/LLM<=32/embedding HTTP<=80/재시도0, US$2 이하. 질문/기대 근거는 [기존 계획](futures-final-integration-2026-10-20.md#실제-모델-평가-승인안--아직-미실행)을 유지한다.
2. 실제 실행 전 별도 격리AI DB/index identity와 현재 source/corpus/manifest hash를 동결하고 chunk/byte 상한을 선검사한다. 한도 부족이면 질문/corpus를 임의 축소하지 않는다. 기존 AI DB/Google/KRX/NXT/runtime을 재사용하거나 변경하지 않는다.
3. 단일 전송 경계 연결·재개/중단·직접 호출 우회 차단·숨은 retry0의 오프라인 통합 검증이 남았다. 이번 모듈의 단위 통과를 실제 Java transport 통합 PASS로 바꾸지 않는다.
4. 실제 모델 평가와 검색 품질, 최신 KRX/NXT 실환경 인수, 최종 Phase D는 미완료로 유지한다. 승인 후에도 제품과 다른 사용자 환경을 변경하지 않고 read-only Tool/Skill 근거만 평가한다.

## 후속: 전송 연결과 격리 Index 준비

위의 '미연결'은 최초 guard-only 작업 시점 기록이다. 이후 사용자 승인에 따라 실제 Java Provider→stdio broker→동일 Guard를 연결했다. [최신 연결·검증·승인 경계](ai-evaluation-transport-preparation-2026-10-20.md)를 따른다. 최종 정책v3는 contextHash/영속 stop과 표준 `service_tier=default` 고정을 포함한다. 기존 DB/Index의 migration이나 예산 초기화를 하지 않으며 실제 LLM·embedding·재색인은 여전히 NOT_RUN이다.
