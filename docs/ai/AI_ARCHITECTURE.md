# AI 아키텍처

> AI Phase 8 기준. 검증/완료 상태는 [평가 보고서](EVALUATION_REPORT.md)를 따른다.

2026-10-30 Agent 조회는 query embedding만 model+corpus+question 키/TTL5분/LRU64로 캐시한다(실측 후 기본ON, `app.ai.query-vector-cache=false` 가능). 권한/domain/활성 index/승인 검증과 최신 Tool 조회는 매 요청 유지하며 답변·거래 상태·검색 결과는 캐시하지 않는다. Basic RAG/ingest 경로는 그대로다. 모델이 근거 부족을 선택해도 성공 Tool 사실이 있으면 검증된 scalar만 PARTIAL로 보존하고, 미검증 모델 초안은 노출하지 않는다. 실제 부분 인수와 소표본/cold/warm 한계는 최신 평가 절에서 구분한다.

2026-10-19 Futures 확장: 기존 구조와 권한/예산을 재사용해 선물 Tool2개·Skill1개·positionId target 및 기존9문서의 선물 정산 정책을 연결했다. 거래 Worker/원장/계약을 호출·변경하는 Agent는 아니다. [후속 구현·실행별 증거](../../claude-docs/futures-ai-integration.md)의 실제 DB/Anvil과 Mock 모델을 구분한다. 실제 LLM 및 새 corpus의 유료 embedding/index 평가는 별도 승인 전 미실행이다.

AI는 거래 실행자가 아니라 승인된 지식과 조회 사실을 결합하는 보조 진단 모듈이다. Spring Boot 내부 모듈과 얇은 provider adapter를 사용하며 Spring AI/외부 Agent 프레임워크는 도입하지 않았다.

```text
JWT principal ── RAG ADMIN API ── 승인 corpus/manifest ── AI pgvector DB
             └─ Agent USER/ADMIN ── 고정 route / Skill handler
                                  ├─ role+domain RAG 검색 ── AI DB
                                  ├─ read-only Tool ── 거래 DB / 시장 snapshot / RPC
                                  └─ 제한 model 해석 ── 서버 근거 검증
거래 DB committed REVIEW_REQUIRED ── 독립 polling
    ── AI DB fingerprint unique / claim / quota / lease
    ── 기존 ADMIN settlement-debugging ── AI 이력 ── ADMIN 웹 패널
```

## 책임과 코드

- `ai/RagService`, `retrieval/AuthorizedKnowledgeRetrieval`: 지식 설명. 현재 잔고·가격·주문은 지식으로 추측하지 않는다.
- `ai/provider/OpenAiProvider`, `agent/OpenAiAgentProvider`: 기존 gpt-5.6-terra / text-embedding-3-small1536 연결, timeout/응답 크기/구조화 출력. Tool 실행 권한을 provider에 등록하지 않는다.
- `ai/tool`: 정해진8개 조회와 권한/연결/안전 DTO. [보안 모델](TOOL_SECURITY_MODEL.md).
- `ai/agent`: KNOWLEDGE/STATE/MIXED/CLARIFY/UNSUPPORTED, 서버 주체·대상·예산·출처/사실 검증. USER 요청의 userId/role을 모델이 만들지 않는다.
- `ai/skill`: 버전/hash registry3개와 고정 절차. [Skill 안내](SKILL_GUIDE.md).
- `ai/diagnosis`: 별도 scheduler/private pool과 durable marker/안전한 이력. 자동 거래·재전송·잠금 해제는 없다.
- `ai/observability`: 고정 label count/sum/max와 ADMIN 조회. 영속 로그 저장소나 거래 DB 쓰기는 없다.

## 격리와 한계

거래 PostgreSQL/JPA와 AI 전용 PostgreSQL+pgvector/별도 Docker volume/JDBC pool을 분리한다. 두 DB의 분산 transaction은 없다. DB connection을 모델/RPC 호출 동안 보유하지 않는다. 독립 Tool은 외부 AI와 AI DB 없이 동작한다. 자동 분석 장애는 거래 정산 호출에 동기 전파되지 않는다.

Agent worker2·Tool4·검색1·모델2를 공유한다. 수동 요청 기본 전체90초/계획15초/생성30초/검색15초이며, 자동 진단은 기존 claim lease90초의 여유를 보존하기 위해 min(전체 설정,40초)다. 자동 run은 실제 worker 종료까지 프로세스당1개다. polling5초/50건·대기100·UTC일20·결과7일은 조정 가능한 초기 안전값이며 다중 서버는 같은 namespace/안전 설정을 사용한다. lease 만료는 재실행하지 않는다. 물리적 외부 호출의 exactly-once, 무손실 이벤트 전달, 자유 문장 완전 진실성은 보장하지 않는다.

[master guide](../../claude-docs/AI_AGENT_RAG_IMPLEMENTATION_MASTER_GUIDE.md), [Phase7 상세](phase-7-event-driven-diagnosis.md), [운영 절차](RUNBOOK.md).
