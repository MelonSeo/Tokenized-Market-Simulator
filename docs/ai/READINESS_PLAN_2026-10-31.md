# AI 무과금 통합·운영 특성 검증 사전 기준

## 후속 실사용 전환 작업 — 2026-10-31, 검증 진행 중

아래 기존 무과금 성능 기준은 당시 실행 이력으로 보존한다. 이번 후속 작업은 사용자가 승인한 제품 AI 수정과 별도 CI 브랜치 Push/실행까지 포함한다. 원격 main 변경·인터넷 배포·신규 유료 호출은 승인되지 않았다. 기존 결과를 새 소스의 PASS로 재사용하지 않는다.

### 변경과 명시적인 경계

- F1: 금융 계산은 변경하지 않는다. `BuyAmountRelation`은 BUY 견적의 입력액=총 차감액, 포함 수수료, 순 가격계산액, 정수 내림 출력의 관계를 Tool DTO로 전달한다. `StateExplanation`과 제한된 자연어 모순 검증을 추가한다. 임의 표현 전부의 의미 정확성을 보증하지 않으며 실제 모델 재인수가 필요하다.
- A2: 현재 공급자·거래량 출처 질문은 `RequiredReadRoute`가 실제 시장 Tool을 요구한다. 현재 Tool에는 차트 구간 거래량 숫자가 없음을 명시한다. 공급자 정책만으로 현재 값을 추측하지 않는다.
- C4: 구조화된 `abnormal` 인자를 기존 Tool 검증으로 검사하고 기존 웹에 ADMIN 명시 필터 선택/건수/초 입력을 연결한다. 일반 질문도 명확한 `5건`/`120초`는 같은 제한으로 파싱하며 충돌·범위 초과는 clarification 오류다. 필터·제한·대기 기준·페이지 범위를 답변에 표시한다. 자연어 임의 기간·복합 필터를 모두 해석하는 기능은 아니다. 숫자 조건이 없으면 기본값을 실제 적용값으로 표시한다.
- C 조회: 기존 JWT+EIP-191 지갑 소유 증명 및 고정 블록 RPC를 그대로 사용하는 `getCAccount/getCPosition` 두 읽기 Tool을 추가한다. 기존 10개 legacy Tool 및 Skill4와 구분한다. ADMIN도 임의 owner를 지정할 수 없다. 개인 Claims 전수 조회, 과거 청산 원인, 전체 감사, 거래 실행은 지원하지 않는다. 이를 사용자 화면과 응답에 표시한다.
- 기존 승인9문서/88 Chunk와 고정30문항 lock은 불변이다. 별도 `ingest-manifest-pilot.json`은 코드 근거 C 읽기 정책을 추가한 **10문서/93 Chunk**, fingerprint `9c38325949ae81df3380d178a4e10587f1754163f01c636a5273e93532190a61`이다. 과거 실제 Embedding 색인이 새 C 정책을 검증한 것으로 취급하지 않는다. 새 문서의 실제 Embedding은 비용 승인 후 별도 AI DB에서 수행한다. C MIXED는 custody 범위만 검색하고 legacy Agent는 custody를 SQL 단계에서 제외한다.
- 서버 비용 보호: `PgProviderBudget`의 별도 AI DB schema가 공급자 전송 전에 동시성 안전하게 보수 예약한다. 전체·UTC 일일 비용, LLM/Embedding 호출량, 계정별 분/일 요청 제한, 재시작 후 누적 보존을 검사한다. 미설정/DB 장애/정책 불일치는 fail-closed다. 실패·사용량 불명·취소 예약도 환불하거나 자동 재전송하지 않는다. 기존 평가 SQLite/STOP과 별개이며 기존 예산을 승계하지 않는다.

### 재현과 안전한 활성화

`ai-unpaid-readiness.yml`은 Java21/Node22, 해시 검사, 무과금 회귀, 새 tmpfs PostgreSQL+pgvector, 실제 HTTP/JWT/Tool/RAG, controlled provider, 별도 Chrome 프로필의 웹 조작을 실행한다. `AI_PILOT_BROWSER=true python deployment/ai-readiness.py run`이 실행 경로다(웹 빌드 선행, 기동 전 RAM1920MiB/디스크2GiB). 합성 벡터·통제된 생성 결과를 실제 의미 검색/모델 품질로 표현하지 않는다. UI는 실제 Agent HTTP를 호출하며 브라우저 응답 가로채기를 사용하지 않는다. 공개 artifact는 numeric-only metrics와 전체 시험 성공 후 고정 allowlist의 **시험 계정** AI 화면 PNG2개/해시만 포함한다. 프로필·로그·암호·JWT·실사용자 자료는 제외한다. PNG 해시/종류 검사가 화면 의미의 무유출 증명은 아니며 최종 시각 대조가 필요하다.

`ai-c-read-linux.yml`은 새 Anvil/격리 PostgreSQL과 기존 결정적 C fixture를 재사용한다. 별도 `ProductAiCReadGate/Driver`로 C STATE Tool/Agent, owner 차단과 실제 웹을 검증한다. 기존 read-only Gate의 기본 기능 OFF와 판정은 변경하지 않는다. 이 프로필은 실제 사용자 MetaMask 승인도 C 정책 생성 품질 인수도 아니다.

실사용 외부 호출은 기본 OFF다. 관리자만 운영자가 구성한 `app.ai.budget.approval`, `total-nusd`, `daily-nusd`, `llm-calls`, `embedding-calls`, `actor-daily-requests`, `actor-minute-requests`를 `POST /api/ai/provider-budget/initialize`로 최초 바인딩할 수 있다(1 nanoUSD=$0.000000001). 요청 본문으로 상한을 바꾸지 못한다. 같은 DB의 다른 approval/정책으로 누적 초기화할 수 없다. 이 단계에서는 외부 키를 구성하거나 실제 비용 예산을 초기화하지 않는다. 무과금 시험은 독립 DB의 명시적인 `UNPAID_TEST_NOT_APPROVAL` 정책만 사용한다.

### 실제 모델 최종 인수 제안 — 아직 승인 아님

`backend/src/test/resources/ai/pilot-acceptance-v1.json`에 사용자2명/동시 Agent2/대기열0, 저부하 수용률95%, STATE p95 1.5초, controlled 복합 p95 2.5초, 실제 공급자 반복 표본 경로별6개/p95 목표45초(운영 SLA 아님), 90초 deadline 및 안전 위반0을 실행 전 고정한다. 기존30문항을 그대로 유지하고 F1/A2/C4 각각2표현 및 C3문항을 추가한다. 기존 PARTIAL을 단순 ANSWERED로 바꾸는 기준이 아니다.

한 번의 별도 승인안은 **US$15.00**, LLM80/Embedding60, 입력5,324,800/출력128,000/Embedding400,000토큰이다. wire64KiB+1,024 overhead의 보수 입력 상한, 출력1,600/호출, 기존30 최대51 LLM+표적9 최대11+반복 지연12요청 최대18=80으로 산정했다. Embedding은 새 색인6batch+51질문=57, 여유 포함60이다. 모든 파일럿 추가 질문도 같은 잔여 상한을 소비한다. 평가 계정의 일일 요청 상한은 사전100/분당5로 고정하고 요청을 분산하며, 일반 파일럿 기본값20/분당5와 구분한다.

2026-10-31 공식 모델 문서 확인: 입력 $2/M, 출력 $12/M, cache write 1.25배를 반영해 입력 $2.50/M로 전량 보수 예약한다. 요청당 입력 상한이272K보다 작아 장문 할증 구간에 들어가지 않는다. Embedding $0.02/M 기준 최대 예약액은 **$14.856**이다. 표준 tier/no hosted tool/no auto retry만 허용한다. 가격 및 모델은 승인 직전 다시 확인해야 하며 rate-card 변경 시 호출하지 않는다. 과거 $5/$5.70 원장·불명 예약·STOP은 그대로 보존한다. 실제 사용량과 예약액은 구분하며 0건 탐지를 무유출 보증으로 표현하지 않는다.

출처: [모델 가격/할증](https://developers.openai.com/api/docs/models/gpt-5.6-terra), [비용 예약 원칙](https://developers.openai.com/cookbook/articles/per_run_spending_controller_responses_api). 기능 인수와 실제 사용자 만족도·장기 SLA는 별개다. 이 후속 작업의 실행 결과/CI SHA/독립 검토는 EVALUATION_REPORT에 별도 누적한다.

---

## 이전 무과금 성능 검증 계획 원문

기준 main9abf3db. 제품 코드는 변경하지 않는다. 이 문서/`deployment/ai-readiness-plan.json`은 **첫 실환경 실행 전에 고정**하며 결과에 맞춰 임계값을 낮추지 않는다. 측정은 운영 SLA/최종30문항의 실제 모델 재인수가 아니다.

## 환경·범위

- 새 소유 pgvector 컨테이너 한 개(320MiB/CPU1, PGDATA tmpfs), 별도 H2 메모리 거래 fixture, Spring Boot loopback HTTP·실제 로그인/JWT·JPA Tool·승인9문서88chunk/실제 PostgreSQL 검색·근거 검증을 연결한다. 기존 DB/Anvil/볼륨/원장 접근·금융 거래는 없다.
- OpenAiProvider는 시험 Mock으로 대체한다. 계획50ms/생성150ms/Embedding40ms, 1536차원 합성 벡터를 사용한다. 실제 모델·의미 검색 품질/공급자 응답 시간·Anvil/RPC·C Vault AI는 NOT_RUN이다. Mock 모델 호출수를 실제 유료 호출수로 세지 않는다.
- 실제 기동 전 Windows GlobalMemoryStatusEx 또는 Linux MemAvailable >=1920MiB, 디스크>=2GiB. 기준 미달이면 NOT_RUN. 다른 프로그램 종료/시스템 설정 변경/기존 Docker 자원 정리 금지. 회귀와 실환경 부하는 순차 실행한다.

## 사전 합격 기준

1. JWT 없는401, USER의 ADMIN Tool403, 타인/미존재 동일404와 모델 미호출, 잘못된 입력400, USER 모델 입력의ADMIN chunk/Tool 민감 필드 노출0. 금융 fixture 전후 동일. 위반은 FAIL이다.
2. STATE/KNOWLEDGE/명시적 signed-quote Skill의 MIXED를 각 동시성1/2/4/8, 단계·작업별32회 **배치형 시도**한다(단계 전 warmup2회/재시도0). 성공/503거절/다른 오류를 나눠 성공 표본 p50/p95와 거절 지연·시도/성공 처리량을 계산한다. C1/2 성공률>=95%, 성공 p95 STATE<=1500ms/그 외<=2500ms. 고부하 C4/8은 가용성 약속이 아니라 포화·거절 특성 관측이다.
3. 실제 Agent worker<=2/대기열0, Tool worker<=4, AI DB active<=2/거래 DB active<=4. 포화 거절은 AGENT_BUSY/503이어야 한다. 통제 latch로 두 Agent를 점유했을 때 추가8요청의 차단을 별도 검증한다. 거절 포함 전체 p95로 성공 지연을 낮춰 보고하지 않는다.
4. 캐시는 동일 기존 정책 질문을 OFF/ON 각12회 비교한다. warmup 이후ON embedding 호출0, OFF12회, 권한·인용·활성색인 검증을 유지해야 한다. warm중앙값이cold보다100ms 넘게 느리면 성능 조건 미충족이다. 실제 공급자 지연/사용자 시간 절약 성과로 확대하지 않는다.
5. 기본90초 전체 deadline에서 timeout은88.5~95초에504로 반환한다. timeout 이후 비협조 작업이끝나기 전 worker를 재사용하지 않고,96초 통제작업 종료 후15초 이내 active0/정상조회 회복. 모델 실패·클라이언트 취소·색인 변경·새 소유 AI DB pause도 기록하며 finally에서unpause/종료한다. DB 장애 응답은20초 이내, 미승인 정책·검증 안 된 초안/인용을 노출하지 않는다.
6. 자원 샘플은 JVM heap/nonheap·process CPU·Hikari active·Agent/Tool active를 관측한다. CPU는 프로세스 CPU/논리코어 정규화이며 JVM heap을RSS로 부르지 않는다. 신규 sampler·client executor와 이번 소유 컨테이너만 정상 종료한다.

## 판정

- PASS: 실제 수행한 위 통제 범위의 보안·금융 불변·bounded 실행·저부하 기준 충족.
- PARTIAL: 기본 동작은 있으나 중요한 품질/성능 조건 미충족, 또는 실제 공급자/운영환경까지 일반화할 수 없음. 서비스 전체 준비도는 기존 F1/A2/C4/답변 충분성/C Vault/실공급자·장기SLA가 남아 있어 별도 PARTIAL이다.
- FAIL: 위 안전·권한·읽기 전용·deadline/자원 경계의 실제 위반. 하네스/환경 실패와 제품 실패를 구분한다.
- NOT_RUN: 자원 guard/도구·연결 제약 등으로 실행하지 못한 경로.

p95는 nearest-rank, 표본32 및 고부하에서 더 적은 성공 표본이다. 단일 장비·작은 corpus·고정 Mock 지연의 배치 시험이며 실제 운영 부하나 통계적 SLA 증명이 아니다. 실제 결과와 최초 실패 증거는 별도 이력에 누적한다. 새 CI는 준비만 하며 Push/Actions 실행/main 반영은 별도 승인 전 금지한다.

검토 보완(실행 전): 정상 부하는 HTTP 200 외에도 예상 route, ANSWERED, 불확실성 없음, 필수 Tool/knowledge/인용과 검증된 Skill 생성 trace를 요구한다. 생성 입력 Tool data의 비공개 필드도 재귀 검사한다. `INDEX_CHANGE` 장애는 현재 활성 색인의 비활성화 시험이며 다른 활성 버전으로 교체한 시험이라고 보고하지 않는다. 합격 수치와 JSON 계획 해시는 변경하지 않는다.
