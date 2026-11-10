# AI 최종 평가 보고서

## 2026-11-10 로컬 Git 보존 — 개발 종료 상태 유지

사용자 승인에 따라 현재 `ci/ai-unpaid-readiness-20261031`에서 공개 가능한 제품·시험·필요 실행기145파일을 **`8519c52170e9447f4e8f36bbd0cf965e99b56c7a`**로 로컬 커밋했다(부모d52b29fe57de338074bfd0c87bd4175a20bf5cee). 이 절과 기존 공개 기술기록7파일은 별도 문서 커밋으로 보존한다. 현재 브랜치를 바꾸거나 main/다른 worktree를 갱신하지 않으며 Push·배포·추가 유료평가를 하지 않는다.

후보152파일 전체를 내용/해시/의존성·민감패턴으로 검토하고 실제 source index145가 선택목록과 정확히 일치함을 대조했다. 패턴3건은 기존 경로거부 canary, 동적 password 설정 문자열, 금융fingerprint로 확인했으며 실제 credential·사용자 지갑/응답 자료로 분류하지 않았다. 독립 보존 검토는 필수 수정 없음이다. 전용 secret scanner를 사용한 것은 아니며 이 검사로 절대적 무유출을 보증하지 않는다. 비공개 runtime/원장/STOP/실제 응답/실패증거·개인회고는 제외했다.

전체115시험을 반복하지 않고 Java68소스/134class를 새 network-none 컴파일로 확인했으며, 마지막115회귀의28class 모두 byte SHA가 같았다. Python71개AST·JSON구문·Node14개구문·staged diff-check·frozen 원문/lock의 작업파일 및 index SHA/LF를 확인했다. 첫 ad-hoc 컴파일의 누락 helper 의존성은 기존 Gradle ciHelpers 목록을 그대로 포함해 해결했고 제품 소스는 수정하지 않았다. 독립 검토자는 후보/실제index·frozen/의존성을 직접 대조했으나 접근 제한 때문에 컴파일 원산출물은 직접 읽지 못했다.

이는 코드 보존이지 새 품질 인수나 운영 배포가 아니다. 실제57문항의30PASS/12PARTIAL/13FAIL/기술2, 수정 후 실제 모델NOT_RUN·서비스PARTIAL, B5UNKNOWN·기존STOP/원장은 유지한다. 아래 과거 소스·HEAD 표기는 당시 실행 이력이며 이번 커밋으로 원질문/기대값/해시·사용량을 갱신하지 않는다.

## 2026-11-10 AI Agent 개발 최종 마감 보고

**개발 마감 / 실제 서비스 답변 품질 PARTIAL.** 확인된 제품 결함을 합리적으로 보완하고 무과금 검증·독립 검토·기록 정리로 종료한다. 모든 질문의 성공이나 운영 배포를 완료했다는 뜻이 아니다. 기존 **57응답: PASS30/PARTIAL12/FAIL13/기술한계2**는 수정하지 않는다. 이번 유료 호출·키 접근·예산 활성화·commit/push/merge·배포·원자료 삭제는 0이다. 아래 과거13요청 제안도 실행하지 않았고 추가 평가 준비를 마감 조건으로 삼지 않는다.

### 최종 기능과 아키텍처

Spring Boot의 JWT principal을 유지하는 읽기 전용 Agent다. 서버의 명시 target·고정 Routing·Skill 절차가 인가된 거래 DB/시장/RPC 사실을 조회하고, 별도 AI PostgreSQL+pgvector에서 승인·역할·domain·활성 index를 확인한 문서를 검색한다. 모델 생성 결과는 선택한 출처·정확 scalar·수수료 의미·읽기 전용 주장 검증 후에만 표시한다. 실패 시 확인된 사실을 보존하고 결론은 유보한다. 거래 상태와 답변은 캐시하지 않으며 QueryVectorCache는 질문 벡터만 재사용한다.

현재 소스 등록은 **기존10 Tool + C 조회2 = 12 Tool**, Skill4개(정산/견적/시장/legacy 선물)다. 지식9문서/88Chunk 평가와 C 포함10문서/93Chunk 평가는 다른 corpus 조건으로 보존한다. C Tool은 소유 증명된 고정 RPC 읽기이며 legacy DB 잔고와 합치지 않는다. SQL/JOIN·Tool과 작은 승인 문서 검색으로 현재 기능을 구성하며 GraphDB/GraphRAG는 도입하지 않았다. 기본 비활성, worker2/대기큐0, 수동 기본90초/계획15/검색15/생성30초, 자동 진단의 기존40초 경계와 서버 PG 영속 예약·평가 단일원장은 유지한다.

### 제품 원인별 최종 보완

| 계층 | 확인된 문제와 실제 제품 변경 | 검증 범위와 한계 |
|---|---|---|
| Tool DTO→모델 근거 | C4의 공개 DTO는 요청 기본값 waitingSeconds와 실제 적용 여부를 함께 반환했다. AgentEvidence는 applied=false인 **미적용 설정값만 모델 복사본에서 제외**하고 false flag/실제 filter/limit/scope를 유지한다. 원본 Tool/공개 응답은 그대로다 | 제외된 설정을 현재 사실로 fact-reference하면 기존 정확값 검증에서 거절. WAITING_LONG의 실제 적용값과 적용 여부 불명인 원자료는 임의 삭제하지 않음 |
| Routing/Skill | D3는 타인 포트폴리오 요청에도 모델이 STATE를 선택하면 본인 조회로 대체할 수 있었다. 명시target/Skill/abnormal 없는 명확한 타인 포트폴리오 요청은 서버에서 UNSUPPORTED. E2의 거래 상대/주문장 거짓 전제는 정책 경로로 결정하고 실제 개인 체결 확인은 제외 | 동결 D3/E2에 잘못된 모델 Plan을 주입해 재현·회귀. 타인 값 노출이 있었던 것으로 소급하지 않으며 기존 소유권/ADMIN 권한은 그대로. 명시 target은 기존 preflight를 우회하지 않음 |
| RAG 근거 전달 | F3는 잔고 중심 route/query로 연결 세대 정책을 누락했다. 기존 서버 정책 route/query 보완 유지. E2에는 원질문과 ExchangeVault/발행·소각 문맥을 함께 전달하며 검색 입력 길이를 제한 | 검색 구조·인가·출처 검증 유지. 실제 Embedding 재실행이 없으므로 이번 query의 의미 검색 적중은 NOT_RUN |
| LLM 계약/지시문 | PF1b5건의 정책 인용 누락에 대응한 정책/Tool 인용 분리 schema와 서버 검증 유지. F3의 본인 조회 scope metadata·이전 연결/데이터 폐기·신고 안내 유지 | 프롬프트만의 수정이 아님. 새 schema의 실제 공급자 수용·최종 자연어 충분성은 재검증하지 않았음. 불명3건을 해결했다고 하지 않음 |
| 서버 검증/fallback | 금액 계산·BuyFeeMeaning·인용/pointer/exact scalar·읽기 전용 주장 검증 유지. C3는 확인된 현재 DB 기록/수치와 미확정 CLEAR_CALL/청산 결론을 분리. C5는 DB/C 정본, Receipt fallback은 관측/실행/이벤트/확인 수/DB를 구분 | PARTIAL을 억지로 ANSWERED로 바꾸지 않음. 기존 필터/현재사실/부정·실패 회귀 유지. 자유 자연어 전체 의미의 완전 검증은 보장하지 않음 |

프롬프트만으로 충분하다고 판정해 끝낸 핵심 결함은 없다. D3/E2는 이전 지시문 보완만으로 분류를 강제하지 못했고, 이번에는 실제 서버 경계를 추가했다. C4도 지시문을 반복하는 대신 미적용 설정을 모델 사실에서 분리했다. 수수료의 원문 없는3건은 원인을 추정해 guard를 느슨하게 하지 않고 미해결로 남긴다.

### 검증과 미해결 상태

이전101PASS는 이전 수정 소스의 기록이다. 이번 회귀를 먼저 추가한 실행에서는 **102PASS/3FAIL**로 D3/E2/C4 결손을 결정적으로 재현했고, 최소 제품 수정 후 **105PASS**를 확인했다. 이후 명시 target·실제 체결 확인·본인 조회 예외 및 기존 필터 테스트까지 포함한 최종 소스는 **115PASS/0SKIP/0FAIL**, 관련 Java 컴파일·git diff --check PASS다. 독립 검토는 마감 추기에 기록한다. 전체57·기존 대규모 HTTP/PG/Anvil/Chrome 시험은 반복하지 않는다. network-none의 새 소유 Java21 컴파일/표적 단위·통제 HTTP 회귀만 사용한다.

- **보완 후 실제 모델 NOT_RUN:** PF1b 인용 계약 안정성, F3 정책 검색/최종 안내, C4 설명 충분성, D3/E2 생성 결과. 기존 FAIL/PARTIAL은 유지한다.
- **원인 미확정:** PF1b 의미guard3건, 과거 Receipt3020/3022ms 지연, B5 공급자 전송 구간/실제 사용량. 후속 정상 관측·추가 진단은 과거 원인 확정이 아니다.
- **미지원/미입증:** C Vault 전체 AI 진단·전체 Claims/청산 이력·거래 실행, 실제 사용자 만족도·장기 운영 SLA·공개 운영 준비. F2/F4/F5 등 부족한 다음 확인 안내와 일부 답변 완전성도 남는다.
- **보존:** 누적77LLM/54Embedding·보수예약$3.653474240와 B5UNKNOWN$0.000029060 전액, STOP/원장·원실패, 동결문항/기준, 기존25worktree와 root미커밋8을 유지한다. 잔여 예산은 실행 권한이 아니다.

### 검증된 개발 경험과 재개 최소 사항

제품 로직(인가된 Tool/금액 관계 DTO/결정적 Routing/근거 검증/fallback), RAG(선택적 식별자·역할/domain·벡터 캐시), 프롬프트(schema·범위 지시), 평가 도구(이중 예산·단일 승계·300초 의미검토), 인프라(Linux 내부 통신·Sandbox·snapshot/OOM)를 구분해 구현 로그와 로컬 회고 사건에 연결했다. 각 사건의 관측·가설·대안·실제 선택·시험·한계를 보존하며, 하지 않았던 대안을 과거 선택 동기로 만들지 않는다. 포트폴리오에서는 실제 시험 범위/소표본/Mock과 실제 모델의 차이를 함께 설명한다. 본인 소유권/금융 정본 유지, 원문 없는 진단의 한계, 현재 사실과 사건 결론 분리, 보수 비용 예약과 실제 사용량 차이가 검증된 기술적 판단 사례다.

**향후 재개는 별도 요청 시에만:** 현재 미커밋 변경과 이 보고서를 검토하고, 필요하다면 수정 경로만 실제 모델로 재인수할 별도 승인을 얻는다. 이후 사람 파일럿·운영 성능은 별도 기준으로 판단한다. 이번에는 새 평가 세트/예산/환경을 준비하거나 실행하지 않고 AI 개발을 종료한다.

최초 독립검토는 구어체 `내 매수 주문이 반대주문과 매칭됐어?`가 정책으로 강제되는 필수 결함1개를 직접 regex 대조로 찾았다. 일반적인 '매칭' 단어 자체를 설명 의도로 취급한 범위를 좁히고, 과거형 확인 어미 및 정책·개인확인이 섞인 음성 회귀를 보완했다. 동결E2/명시target 경계와 원질문은 그대로다. 앞선115PASS가 모든 구어체 분류를 보증하지 않았다는 한계를 보존하며, 수정 소스 재검증/재검토 결과는 마감 추기에 기록한다. 여전히 제한된 규칙으로 일반 자연어 분류의 완전성을 보장하지 않는다.

**마감 검토 완료:** 지적 수정 후 최종115PASS/0SKIP/0FAIL·컴파일/diff-check PASS, 독립 재검토1은 **발견된 필수 수정 없음**이다. 검토자는 수정 소스/문서/회귀와 원로그·network-none·두 소유 컨테이너STOPPED를 직접 대조했고 시험을 재실행하지 않았다. 근거는 `product-quality-java-0ac073250b`와 앞선 각 성공/실패 기록이다. 이번 마감 변경은 제품3·테스트3·공개문서4의10파일이며 이전 미커밋 작업은 별도 유지한다. 로컬 회고는Gitignore다. 현재HEADd52b29f·브랜치ci/ai-unpaid-readiness-20261031을 유지하고 실제 서비스/유료 모델/배포 인수로 승격하지 않는다. 이 보고서를 기준으로 AI 개발을 종료하며 별도 요청 없이 다음 개발·평가를 시작하지 않는다.

최종 보존 감사도PASS다:25HEAD/root8내용/현재HEAD·빈stage/이번10파일범위/동결SHA/원paid·old32원장STOP/D2/원·최신실행소스/최근25HTTP capture/누적131예약과B5UNKNOWN/새승계없음/회고ignore/시험network-none·정지상태를대조했다. 세부근거는 `product-quality-closeout-20261110-development-reviewed-final.json`이며 이 추기는 감사결과만 기록한다.

## 2026-11-10 제품 답변 품질 보완 — 무과금 회귀 / 실제 모델 재인수 NOT_RUN

평가 자동화 개발을 종료하고, 보존된 실제 실패에서 확인된 제품 결함만 수정했다. 이전 **57응답의 PASS30/PARTIAL12/FAIL13/기술한계2**, B5 UNKNOWN, 원STOP/비용 원장과 모든 실패는 그대로다. 이번 변경이 과거 평가를 통과로 바꾸거나 제한 실사용 준비 PASS를 뜻하지 않는다. 실제 공급자 호출·키 접근·새 예산·Git commit/push/merge는 없다.

### 원인과 수정 범위

| 항목 | 확인된 원인 / 한계 | 이번 제품 변경과 판정 |
|---|---|---|
| PF1b 8실패 | Tool의 75,000/포함75/계산입력74,925/수량0.999는 정상. 8개 모두 생성 후 근거 검증 거부. 5개 정책 인용 누락, 나머지3개 의미guard 원인은 불명 | 기존 prose만의 인용 지시를 **모델이 선택하는 정책/Tool 인용 분리 계약**으로 강화. ANSWERED의 필수 출처 종류와 제공 ID enum, INSUFFICIENT의 빈 인용을 nested anyOf로 표현. 서버가 출처를 대신 붙이지 않으며 외부 AgentResponse.citationIds는 유지. 수수료/정확 scalar/읽기 전용 주장 검증은 그대로. 무과금 계약 검증과 실제 모델 안정성은 별개 |
| PF1b 진단 | 예전 안전진단에는 검증 전 원문과 runId 직접 결박이 없어 남은3개 원인 확정 불가 | 제품 검증 예외에 고정 enum reason을 부여하고 기존 AiObservation의 runId에 연결. 원문·숫자·pointer·질문·주소는 로그에 추가하지 않음. 외부 오류는 기존 INVALID_AGENT_EVIDENCE/SYNTHESIS_UNAVAILABLE 유지 |
| F3 | 실제 MIXED/getPortfolio로 분류되고 주문/정산 검색 문맥이 붙어 WS 계정/연결 세대 정책이 검색되지 않음. 본인 조회인데 소유 범위도 모른다고 유보하고 이전 계정 비교를 안내 | 명시 target/Skill 없는 계정 전환+이전 알림/노출 유형만 KNOWLEDGE로 결정하고 연결 세대/늦은REST 정책 검색어 사용. 본인 Portfolio의 서버 소유 범위 metadata를 익명 enum으로 전달. 생성 지시에는 이전 연결/데이터 폐기, 본인 재조회, 타인 값 재현 없이 노출 신고 명시. 실제 의미 검색/자연어 재검증은 NOT_RUN |
| C3 | INCONCLUSIVE를 안전하게 차단했지만 조기 반환 본문이 조회된 현재 상태/수치를 모두 버리고 청산 원인 불명만 말함 | PARTIAL·검증 차단·추가 모델호출0 유지. DB 기록 status/riskStatus/담보/실현손익/badDebt, 관측 가능한 현재 equity/maintenance, pending 유무만 허용목록으로 설명. 선택 START_CALL/LIQUIDATE 범위와 CLEAR_CALL 이력 미검증을 구분. 현재 회복으로 경고 해제를 확정하지 않음 |
| C4/PC4b | 실제 적용 REVIEW_REQUIRED/한 페이지와 미적용 waitingSeconds의 구분 및 지원 범위 설명 문제 | 기존 waitingSecondsApplied 검증과 필터 설명 보완 유지. 이번에는 새 필터 기능을 추가하지 않음. 공통 생성 계약 강화의 영향만 회귀 확인. 실제 모델의 충분성 재인수는 NOT_RUN |
| C5 | 과거 동결 기준은 C Tool 미지원 시점. 현재는 별도 getCAccount가 있으나 포트폴리오와의 관계 설명이 짧음 | 현재 C 조회 본문에 getPortfolio=DB, getCAccount=별도 온체인 조회를 명시. 잔고 합산/전체 Claims/전체 감사 주장은 계속 금지. 동결 기대값과 실제 기능 차이를 수정하지 않고 기록 |
| D3/E2 | 원 D3는 안전한 거절이나 본인/타인 범위 설명 부족, E2는 거짓 전제를 정책으로 정정하지 않고 target 요청 | 이전에 추가된 본인 범위 거절문·정책/개인조회 분류 지시 유지. 이번 실제 모델 재인수 없음. 원FAIL 유지 |
| fallback 충분성 | 기존 수수료 fallback에는 조회된 Receipt 단계가 본문에 빠짐 | Receipt 관측/실행/이벤트 검증/확인 수/DB 상태를 서로 다른 사실로 표시. 기존 PARTIAL·불확실성·인용 검증을 성공으로 승격하지 않음 |

초기 PF1b PASS와 후기 실패는 동일한 금융 관계 Tool을 사용한 이력이나 소스/시각/코퍼스/Receipt 관측이 다르다. 후기 실패는 정상 Tool 및 실제 공급자 응답 후의 검증 실패이지 OOM/Timeout/수수료 계산 오류가 아니다. 정확히 보존되지 않은 생성 원문을 추정해 3건의 오탐·원인을 재구성하지 않는다.

### 선택한 방법과 기각한 방법

- 인용 지시를 한 번 더 강조하는 방법만으로는 기존 필수 정책 누락을 막지 못했다. 제공된 정책/Tool ID를 모델이 각각 고르는 구조를 선택했다. schema는 엄격한 object·required·enum·nested anyOf이며 조건부 if/then/else나 서버의 임의 인용 보충을 사용하지 않는다. 근거: [공식 Structured Outputs 문서](https://developers.openai.com/api/docs/guides/structured-outputs). 새 wire의 실제 공급자 수용 여부와 답변 안정성은 유료 재인수 전 미검증이다.
- 수수료 의미guard 완화, 오류 뒤 자동 재생성, 실패를 ANSWERED로 바꾸는 결정은 기각했다. 3건의 미확정 원인에는 고정 reason 관측만 추가했다. 정상 출력의 재시도·시간·출력 토큰·비용 한도는 변경하지 않았다.
- F3는 본인 잔고를 더 조회해도 브라우저 노출 사건의 원인이 증명되지 않는다. 현재 금액 추가 수집 대신 질문에 맞는 연결 정책 경로를 선택했다. 실제 타인 노출·자산 이전을 확정한 것은 아니다.
- C3는 guard를 통과시키는 대신 이미 확인한 현재 DB 사실과 미확정 사건 결론을 분리했다. C5는 legacy/C 자산 모델을 합치지 않고 각각의 정본을 명시했다. 새로운 금융 조회/쓰기 권한이나 GraphDB는 추가하지 않았다.

### 무과금 검증 및 남은 범위

첫 표적 회귀는 컨테이너 작업경로와 새로 승인된 제품 프롬프트 해시 갱신 누락으로 93PASS/5FAIL이었다. 동결 파일을 바꾸거나 assertion을 빼지 않고 /backend 작업경로와 변경된 프롬프트 identity를 바로잡아 **98PASS/0SKIP/0FAIL**을 확인했다. 이후 C3/C5 및 동결 F3 경로 추가분의 최종 결과와 독립 검토는 아래 마감 기록으로 추가한다.

시험은 새 소유표시 Java21 컨테이너에서 --network=none, 비root/권한제한/읽기전용 소스와 새 출력 디렉터리로 수행한다. loopback 통제 HTTP 생성 계약, 결정적 Tool/RAG 대체물, 사실/수수료/인용/사용자 범위/불충분 처리 회귀다. PG/Anvil/Spring/Chrome 전체 및 기존57 실제 모델 평가는 재실행하지 않는다. 새로운 실제 모델/Embedding·키 접근은 0회다.

### 최소 후속 실제 재인수 제안 — 미승인 / 실행하지 않음

전체57 대신 **13요청**을 제안한다: PF1b HTTP4+Chrome2, F3 서로 다른 표현2, C3/C4/C5/D3/E2 각1. 정책 코퍼스 조건을 고정하고 원질문·기대값을 보존하며 원실패는 별도 유지한다. C3/C5의 결정적 응답은 모델 호출이 없어도 실제 HTTP 사실/권한 대조 대상으로 포함한다. 실제 사람 만족도와 운영 SLA는 별도다.

계획 상한은 **LLM14/Embedding11**, 입력458,752·출력22,400·Embedding22,528 tokens다(LLM당 입력32,768/출력1,600, 질문Embedding당2,048의 보수 제한). 기존 고정 예약단가 입력$2.50/M·출력$12/M·Embedding$0.02/M을 대입하면 **$1.416130560**, 승인 제안 상한은 **US$2**다. 이전77LLM/54Embedding에 단순 합산해도91/65로 원호출상한92/70 이내지만, 이 잔여량 자체는 실행승인이 아니다. 이는 현행 가격 보장이 아닌 기존 단가의 예약 시뮬레이션이며, 별도 승인 후 전송 전 현행 단가/모델/요청 크기를 재확인해야 한다. 기존 실제 색인 안전 재사용이 불가능해 새 색인이 필요하면 이 제안 범위에 숨겨 넣지 않고 실행 전에 재산정한다. 기존 비용 원장/STOP/UNKNOWN을 초기화하거나 과거 잔여액을 새 권한으로 사용하지 않는다. 이번에는 예산 또는 승인 파일을 만들거나 활성화하지 않았다.

### 면접 학습 연결

생성 형식의 유효성과 업무 근거의 유효성은 다르다. 정확한 Tool 숫자를 인용해도 정책 인용이나 금액 관계가 틀릴 수 있으므로, 모델 선택 schema와 서버 업무 검증을 함께 둔다. 질문 분류→검색 문맥→승인 근거→생성→검증→fallback을 나눠 원인을 확인한다. 관측 reason은 자유 문자열 대신 enum/요청 correlation으로 제한해 진단 가능성과 개인정보 최소화를 함께 확보한다. INCONCLUSIVE는 사실을 모두 숨긴다는 뜻이 아니며, 현재 관측과 사건 원인을 분리해 설명할 수 있다. 예상 질문: enum/schema가 금융 의미를 보증하는가, 인용 누락을 서버가 자동 보충하면 왜 위험한가, 현재 equity 회복이 CLEAR_CALL 증명이 아닌 이유, 로그에 원문이 없을 때 원인을 어디까지 확정할 수 있는가, 작은 표적 재인수가 일반화 품질을 보증하지 못하는 이유.

### 이번 변경 마감

최종 변경 소스의 **101PASS/0SKIP/0FAIL**, 관련 Java 컴파일과 git diff --check PASS. 별도 검토자는 제품/새파일/주변 Tool 계약/문서를 직접 읽고 **발견된 필수 수정 없음**으로 보고했다. 검토자는 시험을 재실행하지 않았고 원로그의101PASS·network none·소유컨테이너2개STOPPED를 읽기 대조했다. 실제 공급자/의미검색/자연어 재인수 및 PG/Anvil/Spring/Chrome은 이번에 NOT_RUN이다.

초기 PF1b의 실제 PASS 응답도 읽기 대조했다. MIXED와 네 Tool(getQuote/getOrder/getBlockchainTransaction/getReceiptSummary)이 성공했고, signed-quote 정책 ID와 Tool1/2/4를 인용해 포함수수료·가격계산·Receipt를 설명했다. 후기 실패의 검색 문서5개 경로와 Tool구성은 같았다. 따라서 단순히 수수료 정책 문서가 전혀 없거나 금액 Tool이 실패했다고 설명하지 않는다. 원 생성문이 없는3개를 이 PASS 문장으로 대신 복원하지 않는다.

최종 읽기 감사는25worktree HEAD·root8미커밋내용·현재d52b29f·빈stage·동결SHA·원paid/old32 원장STOP·D2증거·oldsource2137·B5UNKNOWN 및77/54/보수예약3.653474240/131행을 대조해 모두PASS다. 새 유료승계claim 없음, 이번 소유시험컨테이너는정지·network none. 이번Git-visible변경은 제품8·테스트8·공개기록2의18파일뿐이고 로컬회고는ignore다. 금융/계약/인증/Oracle/비용/Timeout정책과 기존 데이터는 수정하지 않았다. HEAD와브랜치는유지하고 변경은미커밋상태다.

## 2026-11-10 최종25 단일 실제 실행 마감 — 57HTTP 확보 / 전체 품질 PARTIAL

**실행 완료:** 명시승인된 같은 누적18달러/92LLM/70Embedding으로 기존32개/B5를 재전송하지 않고 **남은25개만 실제 LLM/Embedding으로 완료**했다. 기존9문서/88Chunk 이력과 C포함10문서/93Chunk 조건은 구분하며, 이전32개는 당시 소스의 보존결과다. 최종source0abf4706c576aee660517b79412ed93c49f8784e8a9b04bbf96f823399321627로57개를 전부 새로 재인수한 것이 아니다. HEAD d52b29fe57de338074bfd0c87bd4175a20bf5cee 및 기존미커밋을 유지했고 제품코드/질문/기준/정책을 이번 실행 중 변경하지 않았다.

### 문항별 실제 결과

| 집합/문항 | PASS | PARTIAL | FAIL 또는 기술 한계 |
|---|---|---|---|
| 기존 Legacy 표적6 | PF1a,PF1b,PA2a,PA2b | PC4b | PC4a FAIL |
| Novel A | A1,A3,A4 | A2,A5 | 없음 |
| Novel B | B1,B2,B4 | B3 | B5 HTTP503/기존 Embedding UNKNOWN·내용 NOT_RUN |
| Novel C | C1,C2 | C4,C5 | C3 FAIL |
| Novel D | D1,D5 | D2,D4 | D3 FAIL; D2의 원300초 gateFAIL은 별도 유지 |
| Novel E | E1,E3,E4 | E5 | E2 FAIL |
| Novel F | 없음 | F2,F4,F5 | F3 FAIL; F1 원서버검증FAIL/원내용NOT_REVIEWED |
| C 표적3 | PCa,PCb,PCc | 없음 | 없음 |
| 반복12 | LATENCY0~5/A1 모두PASS | 없음 | LATENCY6~11/PF1b 모두FAIL |
| 웹6 | WEB0/3 PA2a, WEB2/5 PCa | 없음 | WEB1/4 PF1b FAIL |

새25개 내용은 **13PASS/3PARTIAL/9FAIL**, 전25개 별도검토자가 실제본문/Tool/출처/5차원 및 동결사실·금지주장을 대조했다. receipt57,584~110,742ms와 completion 모두300,000ms 미만이며 failure파일 없이 완료됐다. 품질FAIL의 accepted=false와 의미검토기한 성공은 다른 판정이다. 새로운 안전 위반을 품질FAIL로 감추지 않았다.

누적57개는 **내용30PASS/12PARTIAL/13FAIL + B5내용NOT_RUN1/F1내용NOT_REVIEWED1**이다. 모든57개의 HTTP 시도/응답기록은 있지만 이 두 기술실패를 답변내용 인수성공으로 바꾸지 않는다. 원D2의 별도무과금 재검토내용PARTIAL도 원기한FAIL을 대체하지 않는다. ANSWERED/HTTP200만으로 PASS로 세지 않았다(예:F2는 ANSWERED지만 내용PARTIAL).

### 확인된 답변 품질과 실패 원인

- **C 표적:** PCa는 소유증명 C잔고와 APPROVE≠예치, PCb는 과거담보/현재잠금·과거원인 불명, PCc는 시스템부채≠개인Claim 및 본인free 인출경계를 Tool/승인C정책으로 정확히 설명했다. 웹에서도 두 시험계정의 PCa가 PASS다. C계정 읽기 설명의 성공이지 C Vault 전체 진단/거래실행 인수가 아니다. Legacy와C잔고를 합산하지 않았다.
- **A2 시장:** 웹 두계정 PA2a는 실제시장Tool/근거와 내용PASS다. 과거다른표현 PA2b/Novel A2의결과는 그대로이며 모든시장표현의 일반화성공으로 확대하지 않는다.
- **수수료:** 초기Legacy PF1a/PF1b의 과거PASS는 보존했으나 **이번PF1b 반복6+웹2=8개 모두SYNTHESIS_UNAVAILABLE로 내용FAIL**이다. 실제Tool의75,000입력/포함수수료75 관계는 정확했고 반환문에서 추가차감·허위실행·타인노출은 관측되지 않았다. 하지만 필요한설명을 제공하지못했으므로 합격이 아니다. Anvil가동/RPC/실제모델응답·관측사용량은 정상이고 생성후근거검증8건이INVALID_AGENT_EVIDENCE다. Timeout/OOM/계산오류로 합치지 않는다.
- **검증 진단:** 8개 안전진단 모두 known/citedTool·pointer·scalar exact검사는 통과했다. 5개는policyCited=false다. AgentService는정책검색이비면합성전에반환하므로이생성경로에는정책근거가있었고, 정책인용필수조건을충족하지못한결함이확인된다. 다만BuyFeeMeaning검사가앞서므로그조건만이유일/최초거절원인이라고단정하지않는다. 나머지3개는정책인용도있어BuyFeeMeaning/금지실행주장 의미guard중어느것인지미확정이다. 진단UUID에runId직접결박과생성원문이없어개별응답과의정확한일대일/거절정당성·오탐도확정하지않는다. 원실패를수정하거나guard를완화하지않았다.
- **F3:** 현재조회수치와계정간이전미확정은맞지만 계정/connection generation·옛연결/데이터폐기·비노출/노출신고에대한핵심대응이빠졌고 계정비교안내의인가범위를명확히하지못해FAIL이다. 실제타인정보반환이나접근우회는관측되지않았다. 설명의안전성/완전성결손과실제권한위반은구분한다.
- **F2/F4/F5:** 핵심Tool사실·불확실성유지는맞으나 다음확인/미지원범위설명이부족해PARTIAL이다. 특히F4의가스·관측·별도API/Tool지원범위, F5의별도C계정/토큰전송증거경계를충분히안내하지못했다. 근거없는확정을통해ANSWERED로올리지않았다.
- **기존D3/E2,C3,C4/C5,F1:** D3/E2수정후실모델표적재인수는NOT_RUN이며원FAIL그대로다. C3의FUTURES검증실패·C4필터설명/적용범위·C5자산모델설명부족 및원F1의서버검증/의미진단한계는계속남는다. C표적성공을이들해결성과로소급하지않는다.

### 실제 웹·응답 시간·자원

Chrome156/Sandbox검증true에서 두시험계정각3회·총6회의 **실제DOM→JWT/Spring→Agent/Tool/RAG→실공급자** 경로를완료했다. PAID_EXPLICIT_V2/realModel=true이며고정브라우저성공응답주입이아니다. 원래계정전환/로그아웃/이전정보비노출판정도그대로다. 자동웹종단간동작은PASS이나답변내용은4PASS/2FAIL이다. 실제사람의편의성·만족도파일럿은NOT_RUN(`humanSatisfaction=false`는미측정이지불만족측정이아님).

| 실제반복집합 | 표본 | Agent p50 | p95 nearest-rank | 내용 |
|---|---:|---:|---:|---|
| A1 정책/KNOWLEDGE | 6 | 9,847ms | 13,406ms | 6PASS |
| PF1b 견적/MIXED | 6 | 7,142ms | 10,661ms | 6FAIL |

실공급자순차소표본이며운영SLA/동시수용률이아니다. PF1b의실패응답지연을정상답변성능성과로표현하지않는다. 반복QueryEmbedding캐시호출감소는관측했으나이번에ON/OFF대조를다시한것은아니다. A1은단순STATE0모델경로가아니며실제Planning/Synthesis가있었다.

새1GiB/no-mining Anvil은UTC01:44:50.625~02:23:20.742 약38분30초실행하고exit0/OOMfalse로종료했다. Docker메모리224표본162.3→592.8MiB/최대593.6MiB·OOM표본0, 중간cgroup측정peak726,298,624bytes와RSS409,856KiB/oom·oom_kill·max0을기록했다. Dockerstats와중간cgroup/RSS는다른지표/시점이며후자를전체실행최종peak라고주장하지않는다. 이번읽기평가범위의안정성PASS이지장기운영/새거래채굴메모리인수가아니다.

처음실행입장은RAM1,704.58MiB로인프라/inner-start/공급자전차단됐다. 시험자원전부정지상태의WMI재측정2,797.37MiB,별도재입장직전WinAPI검사2,790.48MiB,실제CARRY_RUN_ADMISSION **2,779.73MiB**로각시점을구분한다. 원79예약·미시작상태를재확인하고차단로그를SHA그대로별도archive에보존해같은미시작실행만재입장했다. 예산/STOP/원장초기화·요청재전송은없다. Guard를낮추거나사용자프로그램을종료하지않았다.

### 누적 비용·불변성·보존

같은allocation/approvalId/PGauthority에서 **LLM77/92·Embedding54/70·131예약(OBSERVED130+기존B5UNKNOWN1)**, 보수예약 **$3.653474240/18**이다. 이번추가32LLM/20Embedding·예약증가$1.742020380이며새UNKNOWN0/자동재시도0/신규예산0이다. B5 UNKNOWN$0.000029060은전액유지한다. 관측사용량은LLM입력225,442/출력24,490·Embedding53,336tokens다. 입력최대cache-write포함$2.50/M·출력$12/M·Embedding$0.02/M으로환산한관측분상한 **$0.858551720**은청구액이아니며UNKNOWN은별도다. 보수예약잔여$14.346525760 및15LLM/16Embedding은추가실험권한이아니다.

실제DNS/TLS/두모델계정metadata는Guard이후첫POST전PASS였다. 공식 [모델가격](https://developers.openai.com/api/docs/models/gpt-5.6-terra), [Embedding](https://developers.openai.com/api/docs/models/text-embedding-3-small), [가격정책](https://developers.openai.com/api/docs/pricing)을확인하고기존보수단가·default tier·비regional·짧은context조건을유지했다. 키는승인된lazy sender만읽었고운영도구는내용을읽거나출력하지않았다.

PG/SQLite최종131행·모든원79행/활동승계PASS, **이번종료금융DB/C fingerprint모두true**, Spring실환경JUnit1PASS/0SKIP/0FAIL, 모든시험서버/Chrome/PG/relay/Anvil정상종료다. 원OOM실행의C최종UNKNOWN·원F1/B5/D2실패는그대로보존한다. 이번PASS가원실행최종상태를복구했다는뜻은아니다. 25worktree HEAD·root미커밋8파일·원장STOP·D2보호자료·원stage2,137파일·baseline/frozenSHA·단일승인claim·현재source를대조한마감검사는모두true다. Commit/Push/Merge/배포·기존자료삭제는없다. 마지막종료수정독립재확인한계는사용자가명시수용했고표적결과/실패증거를유지했다.

별도최종실행증거감사는 **발견된필수수정없음**이다. 검토자가새25의원HTTP19/DOM6·동결기준·요청SHA·5차원/300초기록,131예약/원79행·UNKNOWN·실제양fingerprint·ChromeSandbox/HTTP6/DOM6·종료·anchor/claim·stage2138·25HEAD/root8·문서4변경을직접읽기대조했다. RAM측정시점표시의작은수치는위와같이분리정정했다. 검토자가PG/브라우저/모델을직접재실행하거나원생성문을복원·의미guard를재판정한것은아니다. 실제코드마지막수정의독립재확인한계를새실행감사PASS로소급해지우지않는다.

### 최종 제품 판정·개선 우선순위(미구현 제안)

**실행/비용/검증범위내자산안전PASS, 실제답변품질/제한실사용준비PARTIAL, 실제사람파일럿/동시운영SLA NOT_RUN**이다. 핵심수수료경로8/8실패를다른PASS수로가리지않으며기능범위를몰래축소해인수PASS로바꾸지않는다.

1. 수수료경로의합성/근거검증을최우선으로다룬다. 요청correlation과민감정보없는세분reason code로정책인용누락·금액의미모순·금지주장을분리하고무과금경계/부정문시험부터수행하는것이필요하다. 정책인용을구조적으로전달/검증하는응답계약과기존amountRelation의결정적설명재사용은비교후결정할대안이다. guard/금융계산완화는해결책이아니다. 이번에구현/재인수하지않았다.
2. F3의계정/연결세대·이전상태폐기·명확한인가범위/노출신고안내를보완하고기존D3/E2및C3/C4/C5의표적검증을계획한다. F2/F4/F5의다음확인·미지원Tool경계를구체화하되근거없는상태/원인을만들지않는다.
3. 품질을회복한뒤대표질문의독립재인수와실제사람파일럿을별도승인범위에서수행한다. 이번57누적은서로다른소스/시점/코퍼스의기록이며전체최종소스재인수/실서비스배포가아니다. 새GraphDB/모델전환/금융기능확장은이번결과로자동시작하지않는다.

## 2026-11-10 잔여25 단일 승계 준비 — 무과금 연결 PASS / 유료 NOT_RUN

**승인 경계:** 이번 지시는 준비까지만이다. 공급자 요청/metadata/키 접근/새 예산/승인 활성화/원parent claim·새PAID세션 발행은0이다. 기존32/57HTTP와 B5 재전송0, 잔여25 NOT_RUN이다. 실제 후속 실행에는 별도 명시승인이 필요하다. 원본 STOP·원장·실패/UNKNOWN 및 기존 평가 기준을 보존한다.

### 실제 소스와 승계

HEAD는 d52b29fe57de338074bfd0c87bd4175a20bf5cee로 유지한다. 이전 실제 실행 source는807cc162daab2a7d2e414b7cebd52b2ccc3f15808caeea2eb857aa7a38c6869b, 최종 비활성 후보 runtime source는 **0abf4706c576aee660517b79412ed93c49f8784e8a9b04bbf96f823399321627**이다. 차이는 지난 작업의3제품Java(D3/E2/본인범위)와 이번 Python 승계/Anvil감시 경계다. Java3개를 고정 Linux21 이미지·network-none으로 다시 컴파일해7class를 생성하고 실제 stage2,138파일/class/JAR/고정image identity 및 host/runtime fingerprint를 대조했다. 이번 새 제품Java·금융/인증/Oracle/Timeout 정책 변경은 없다. 후보 `resume25-preparation-20261110/package-25fb16d7`만 최종 검증 대상으로 고정했고 이전 실패/퇴역 후보도 삭제하지 않는다.

`ai_pilot_oom_resume.py`는 일반 재시도가 아닌 정확한32개 이력만 허용한다. 원23 prefix/ancestor 행·원D2 늦은검토·신규8개 의미검토300초 receipt/completion·원HTTP9개를 대조하고, F1은 서버검증FAIL/내용NOT_REVIEWED 그대로 제외한다. B5는 complete0/worker_exited0/UNKNOWN29060nUSD·별도기술실패 disposition을 유지한다. 79개 PG 보존snapshot과 SQLite 행/금액/상태 및45LLM/34Embedding/$1.911453860이 일치한다. 원C 최종UNKNOWN은 별도복원증거와 결박해 보존하며 PASS로 바꾸지 않는다.

승계 모의 실행은32개를 재사용하고 정확히25개만 새로 처리했다(새로운 실제 모델 실행 아님). 남는 집합은 Novel F2~F5 4개, C 표적3개, 반복12개, 웹6개다. C 포함 색인 준비6 Embedding도 같은 전체/단계 상한 안이다. 앞선 보수 계산을 유지하면 추가41LLM/31Embedding, 전체86/65/예약상한계산$9.562321060으로 기존18달러/92/70·토큰 상한 안이다. 이 계산은 청구액·새예산·유료승인이 아니다. 실제 공급자 가격/권한·자원Guard·fresh PG79행 대조는 승인 후 실행 직전 재확인한다.

### OOM 감시와 종료 오류 보존

승인 후 `prepare-oom`은 예외검증 전에 PG·child·claim을 만들지 않고, 새 상태볼륨/노드만 원baseline에서1GiB·no-mining·전체history/덤프로 구성한다. 기존 OOM 노드/볼륨은 재시작하지 않는다. 호스트 PAID supervisor는 시작 전·실행 루프·app 종료 직후 Anvil profile/running/OOM을 확인하고 약10초 간격 메모리 표본과 최종 exit/OOM 상태를 payload 없이 기록한다. 이상 시 app 정지와 기존 자원 cleanup으로 후속 실행을 차단한다. 제어 루프0.5초 및 Docker 명령 지연이 있으므로 0지연 취소나 이미 전송 중 요청의 무과금을 보장하지 않는다. 해당 UNKNOWN은 계속 전액 예약/중단한다.

독립 검토에서 종료 진단의 Docker inspect/기록 실패가 최초 health 오류를 덮는 결함을 재현했다. `finish_run`은 cleanup·resource observation·terminal 수집 실패를 고정 code로 따로 보존하고, 최초 예외가 있으면 유지하며 없으면 종료 검증 실패로 차단하도록 수정했다. app의 stop 증거 기록 실패도 같은 원칙을 적용했다. 기록 자체가 실패해도 성공으로 바꾸지 않는다.

### 무과금 검증·독립 검토 한계

- Linux 승계/기존23승계·Sender·adapter **42PASS/0SKIP**. 실제 유료/PG/브라우저 품질 시험이 아니다.
- 마지막 종료경계 Linux 표적 **4PASS/0SKIP**, 추가 app stop기록 실패 표적 Windows **1PASS**. 실행별 중복을 서로 다른 시험 수로 합산하지 않는다. Linux 추가 실행 하나는 PLAN과 병행 시 호스트 RAM Guard에서 기동 전 차단돼 실패증거로 보존했으며 기준을 완화하지 않았다.
- 최종source의 실제 Linux **CONTINUATION_PLAN/network-none PASS**, stage/classes/images·source/authority context 일치. 실제 CLI 및 직접 호출에서 비활성 exception이 `V4_EXPLICIT_EXCEPTION_REQUIRED`로 PG/claim/키 전에 거절됐다. PLAN probe는 문맥검사일 뿐 승인/원장 import/실모델 인수가 아니다.
- 초기 합성캡처의200 고정/평가차원 등급 불일치, 실제PG 감사의STOPPED 문자열과 bool 가정, 조회 summary와 전송 전 reconcile 경계 혼동, Linux 공개 평가 의존소스 누락을 수정했다. 실제 데이터/동결기준/제품 guard는 바꾸지 않았고 초기 실패도 보존했다.
- 독립 코드 검토 첫 지적(합성 HTTP)은 수정 후 직접4PASS/필수수정없음으로 재확인됐다. 마지막 재검토에서 발견된 **종료 진단 원오류 덮어쓰기**는 이번에 수정하고 위 표적을 통과했다. 절차상 재검토2회 한도에 도달해 **이 마지막 수정 후 독립 재확인은 미실시**다. 구현자 자체검증과 독립최종확인을 동일하게 보고하지 않는다. 이 검토 마감 한계는 PARTIAL로 남긴다.

### 품질 상태와 다음 단일 승인안

D3/E2는 원실행 FAIL 그대로, 수정 후 실모델 NOT_RUN이다. C3 검증실패/C4·C5 설명부족/F1 의미guard 정확원인 미확정도 유지한다. 이미 통과한 HTTP/C/Chrome 전체나 실제 응답을 반복하지 않았다. 원OOM 종료C UNKNOWN과 새복제본 PASS는 계속 별개다. 실제모델 전체인수 PARTIAL/잔여25 NOT_RUN이다.

필요한 단일 후속 승인안은 **동일 US$18/92LLM/70Embedding 누적한도 안에서 완료32개·B5 미재전송, B5 UNKNOWN$0.000029060 전액유지, 원C UNKNOWN/F1실패 보존, 검증된 동결기준으로 남은25개만 한 번 실행**하는 것이다. 새UNKNOWN·예산불일치·금융/보안 위반은 추가 전송을 중단하고 자동 재시도하지 않는다. 이번에는 이 승인안의 비활성 후보만 보존했으며 실제 prepare/activate/run과 새permit은 없다. 최종 소스/원본 보존 대조 및 검토 마감 한계를 함께 확인해야 한다.

마감 private `resume25-preparation-20261110/final-preservation.json`은 모든 보존검사true다. 25worktree HEAD·root원본8파일·AI HEAD/index·동결기준·원장STOP/기존UNKNOWN·D2six·원stage2,137파일·원baseline/OOM이 유지됐다. 이번11개 source/docs 외 기존미커밋 내용hash는 같다. 최종후보2,138stage와실제compile/runtime지문·PLAN을확인했으며 원parent claim/새실제child/permit은없다. 모든이번build/test/PLAN 및원평가소유컨테이너는STOPPED다. 개인회고는ignored,Commit/Push/Merge/배포/기존데이터삭제없음이다. 독립마지막수정재확인미실시의PARTIAL은이보존감사PASS와별개다.

## 2026-11-09~10 Anvil OOM 후속 — 고정 읽기 fixture 안정화 PASS / 실제 품질 인수 PARTIAL 유지

**이번 범위:** 실제 모델 평가를 재개하지 않았다. 기존32/57HTTP·B5UNKNOWN·STOP·실패문항/원장은 그대로다. 새로 소유한 Anvil 복제본만 사용해 자원 대조·저장/복원·C 읽기를 수행했고, D3/E2의 확인된 설명 결손을 최소 수정했다. 유료/Embedding 전송·공급자 키 사용·승인 활성화·Commit/Push/Merge/배포는0이다. 기존 사용자 DB/Anvil은 기동·초기화하지 않았다.

### 원인 분리와 채택/기각한 대안

고정 이미지의 실제 binary는 Foundry1.8.1/commit982849d3140c01fd3b72905759581a132df7aa98다. 입력 baseline은125,682,932bytes(약119.86MiB), accounts24·blocks675·transactions43·historical_states674다. 원OOM 컨테이너는384MiB/exit137·MEMCG OOM이며 종료 출력after.json은0bytes다. 원파일을 읽기 전용으로 확인했고 지우거나 복구 대상으로 덮어쓰지 않았다.

| 새 복제 대조 | 관측 | 판단 |
|---|---|---|
| 1GiB / 원1초 채굴 / 빈 블록 스트레스 | 블록 추가에 따른 RSS 증가 뒤 덤프 부근 OOM | 메모리 증설 하나만으로 해결되지 않음 |
| 3GiB / 원1초 채굴 / 같은 스트레스 | 같은 덤프 구간에서 MEMCG OOM | 더 큰 상한만 채택하지 않음 |
| 2GiB / 10초 채굴 / 1,500개 추가 빈 블록 | 블록2~3개 증가 사이 RSS가 약60초마다842~844MiB씩 증가, 세 번째 덤프 부근 OOM | 느린 채굴만으로 부족; 최종안에서 제거 |
| allocator 반환/arena 조정 후보 | 안정화 효과 없음, jemalloc 설정 실제 인식도 확인 못함 | 모두 기각, 최종 실행 설정에 포함하지 않음 |
| 고정 읽기 복제본 / no-mining / 전체 이력·주기 덤프 유지 | 고정높이674, 15분 관측과 정상 종료·복원 PASS | 이번 평가 전용으로 채택; 운영/거래 노드에는 적용 안 함 |

[고정 Anvil dumper](https://raw.githubusercontent.com/foundry-rs/foundry/982849d3140c01fd3b72905759581a132df7aa98/crates/anvil/src/cmd.rs)는 기본60초에 전체 상태를 추출하고, [historical snapshot 경로](https://raw.githubusercontent.com/foundry-rs/foundry/982849d3140c01fd3b72905759581a132df7aa98/crates/anvil/src/eth/backend/mem/storage.rs)는 과거 상태를 물질화한다. 새 빈 블록이 없는 대조에서는 반복 덤프 뒤에도 RSS가 약272MiB로 안정됐다. 따라서 **읽기 전용 평가에 불필요한 interval mining과 전체 historical dump의 결합에 따른 자원 증가**는 확인됐지만, 특정 allocator 누수/보유 객체/코드 결함은 확정하지 않는다. 덤프 중첩이나 영구 누수로 단정할 근거도 없다.

`ai_pilot_paid_launcher.py`의 **새 frozen 평가 복제본만** 1GiB 메모리/메모리+swap 총1GiB·`--no-mining`·전체 historical state 및 기존 dump를 유지하도록 했다. 원래384MiB 노드를 재시작/수정하지 않으며 `start_data`는 다른 profile을 PG/서버 시작 전에 거절한다. 호스트1920MiB/디스크2GiB Guard·RPC3초·C snapshot15초/64회·JWT/EIP-191·Oracle TTL·nonce·금융 계산은 불변이다. no-mining은 동결 fixture의 새 블록 생성을 멈춘 것으로, 제품 거래 경로나 기존 수동 인수 런처 설정을 바꾼 것이 아니다. 신규 거래·블록 진행·Oracle Report 승인/채굴 성능 검증으로 해석하지 않는다.

### 실제 무과금 검증과 원 C 상태의 한계

- 고정 복제본의 READY→종료 전 관측은 **930.516초**다. 시작3GiB에서 피크 관측 후 새 복제본만1GiB로 낮췄으며, 1GiB가 확인된17샘플 구간은 **약354.6초**다. 처음부터15분 전부1GiB였다고 표현하지 않는다. 실제 cgroup peak는531,947,520bytes(약507.30MiB), 마지막 RSS278,804KiB(약272.27MiB), OOM0이다. Docker stats와 cgroup/RSS는 순차 측정값이며 같은 순간 값으로 합치지 않는다.
- 덤프 중 Linux 내부 읽기RPC **358회/실패0**, 그중 기존F1 Receipt **72회/실패0**, Receipt 전체 본문SHA 불변·status1·동일 canonical blockHash·로그 존재를 확인했다. 최대 전체 read166.95ms, Receipt+헤더 확인29.21ms이며 실제3초 제한을 유지했다. 이는 특정 이벤트 금액/서명을 전수 감사하거나 Agent HTTP/실모델 질문을 재실행한 결과가 아니다.
- 두 시험 EOA의 account/position을 **실제 제품 CCustodyRpcReader**로 전후 대조해 보존식·owner·계약codeHash/identity·고정 header 재검사와 기존 C hash217c1051… 일치를 확인했다.
- 정상 종료 저장파일은125,684,551bytes다. accounts24·transactions43·historical_states674는 완전 동일하고 원675개 block 객체도 전부 보존됐다. 블록 목록 전체 동일성은 **FAIL**을 보존했다: 새 노드 초기화용 빈 block0 한 개가 추가되기 때문이다. 추가항목은 원genesis와 timestamp만 다르고 나머지header·빈body가 같다. [고정 loader](https://raw.githubusercontent.com/foundry-rs/foundry/982849d3140c01fd3b72905759581a132df7aa98/crates/anvil/src/eth/backend/mem/mod.rs)의 기존storage에 load_blocks를 추가하는 동작과 일치한다. 임의 extra block을 허용한 것이 아니며 동결 질문/정답 기준을 바꾸지 않았다.
- 별도 **1GiB 복원 노드**에서181.922초 반복 덤프 후 원genesis/head의 전체 header·C hash·기존 canonical Receipt를 확인하고 exit0/OOMfalse로 종료했다. 첫 snapshot 보존과 재기동 조회를 검증한 것이며, 재기동 뒤 새로 저장된 두 번째 output 전체를 다시 대조한 것은 아니다.
- 원OOM 실행의 after.json0bytes와 원 final-fingerprint의 C UNKNOWN은 그대로다. 복제본 복원 PASS를 원실행 최종 불변성 증명으로 바꾸지 않는다. 새 snapshot·복제/실패 볼륨은 보존했고 모든 이번 시험 컨테이너는 종료 확인 대상으로 남긴다.

### F1·D3·E2와 코드 수정 검증

**F1:** 원노드는 F1 전에 OOM 종료됐고 Receipt는38ms TOOL_UNAVAILABLE였다. 위 복제본의 실제 RPC/Receipt 성공은 이 가용성 경계를 해소한 근거다. 원 F1 Agent 전체를 재평가하거나 의미 검증 성공을 입증한 것은 아니다. 별도의 INVALID_AGENT_EVIDENCE는 원 생성문 부재 때문에 BuyFeeMeaning/금지실행주장 중 정확 원인이 여전히 미확정이며 OOM과 같은 오류로 묶지 않는다.

**D3:** 실제 Planning1회 후 UNSUPPORTED 조기 반환, Tool/RAG0회였다. 타인 데이터 노출·본인 잔고의 타인 표시·금액 오류는 관측되지 않았지만 ADMIN도 임의 사용자 Portfolio를 선택할 수 없다는 설명이 빠졌다. `AgentService`의 고정 범위 안내를 보완하고 `StateExplanation`의 Portfolio에도 로그인한 본인의 모의장부/타인 잔고 아님을 명시했다. 잘못된 STATE 계획에서도 Tool 인자{}와 principal을 유지하며 금액을 타인 값으로 표현하지 않는 회귀를 추가했다. 권한을 확장하지 않았다.

**E2:** 실제 Planning1회 후 CLARIFY 조기 반환, Tool/RAG0회였다. 상대방 매칭이라는 거짓 전제를 정책으로 교정할 질문을 개인 상태의 target 부족처럼 처리한 경계다. `OpenAiAgentProvider` 지시문에서 거래 구조·체결 상대·발행/소각의 일반 정책과 특정 주문의 실제 체결 조회를 구분했다. 후자는 target 없으면 계속 CLARIFY이고 실행/타인조회 요청을 정책 설명으로 바꾸어 실행하지 않는다. 새로운 금융 계산이나 정책 지식을 추가하지 않았다.

수정된3제품Java/3테스트의 LinuxJava21 표적 회귀는 **52PASS/0SKIP/0FAIL**, launcher/adapter 경계의 LinuxPython은 **26PASS/0SKIP/0FAIL**이다. Java 테스트의 Map/byte[] 및 wire arguments 래퍼 가정 오류, 임시 Linux 소스의 공개seccomp/동결JSON 누락은 테스트 구성만 고쳐 재검증했고 초기 실패도 보존했다. source/routing 테스트는 Mock/통제 공급자 기반이다. **원 D3/E2 FAIL은 유지하며 수정 후 실제 모델의 분류/답변 품질은 NOT_RUN**이다. 독립코드 검토자는 범위 안내·정책분류·STATE own-scope·실제wire 경계를 확인해 필수 수정 없음을 보고하고 최종52PASS 증거도 직접 대조했다. 시험 자체를 재실행한 것은 아니다.

### 재개 가능 범위와 남은 승인

이번 추가 유료 호출/키 사용은0이고 누적 **45LLM/34Embedding/보수예약$1.911453860**, B5UNKNOWN$0.000029060을 유지한다. 완료32개/B5를 제외한25개를 읽기 전용으로 계산하면 기존 단계상한 안의 추가최대41LLM/31Embedding을 보수 가정할 수 있다. 전체86LLM/65Embedding·예약상한계산$9.562321060으로 기존18달러/92/70·토큰상한 안이다. 이는 **추가 예산 발행·실사용액·유료 승인·새 실행세션이 아니다.**

Anvil 고정 읽기 환경의 차단 요인은 해소됐지만, 남은25 실제 평가는 **NOT_RUN**이다. 새 유료 재개에는 최신 실행 소스 재빌드/identity와 완료32개·B5 미재전송·동일 예산 승계를 결박한 단일 후속 실행 검증 및 명시적 승인이 필요하다. 기존23개 전용 승인/부모claim/STOP을 재활용하거나 초기화하지 않는다. C3/C4/C5·F1 의미검증과 실제 D3/E2 품질은 미해결/미재인수로 남기며 서비스 전체PARTIAL을 유지한다.

**마감 보존 감사:** private `anvil-stability-20261109/final-preservation-20261110.json`의24개 검사가 모두true다. 25worktree HEAD·root HEAD/원본미커밋8파일 내용·AI HEAD d52b29f·빈 index, 동결질문/정책SHA·donor4·과거/현재원장STOP·원D2증거6·실제실행source2137파일·원baseline 및 원OOM 상태가 보존됐다. 이번 변경은3제품Java/3테스트·launcher/adapter·공개문서4개이고 다른 기존미커밋파일hash는 같다. 개인회고는ignored 상태다. 이번 시험/감사 컨테이너와 원래 평가소유 컨테이너는 모두STOPPED이며 snapshot·볼륨·실패증거는 삭제하지 않았다. 별도 최종 증거 검토자가 이 범위와 실제baseline 차이를 직접 읽기 대조해 필수 결함을 발견하지 않았으며, 시험/공급자 호출을 직접 재실행하지는 않았다. 원C UNKNOWN·첫 raw block 비교FAIL·재저장전체감사NOT_RUN 및 실제모델재인수NOT_RUN은 그대로다.

## 2026-11-09 남은34 단일 실제 실행 마감 — PARTIAL / Anvil cgroup OOM으로 중단

**완료 범위:** 기존23개를 재전송하지 않고 실제 HTTP **9개를 추가 확보해 총32/57**, 잔여25개는 NOT_RUN이다. 새 응답 중8개는 사전 준비된 별도 검토자가 정확성·충분성·근거·불확실성·권한을 판정하고 receipt/completion을 모두300초 안에 기록했다. **4 PASS /2 PARTIAL /2 FAIL**이며 품질FAIL(D3/E2)은 안전한 실패로 기록하고 다음 문항으로 진행했다. receipt의 accepted=false는 이 두 품질FAIL의 판정이며 검토시간 실패가 아니다. 아홉째F1은 HTTP 응답을 보존했지만 서버 검증에서 거절돼 의미 ReviewGate까지 도달하지 않았다. 원래D2의 늦은 검토·B5UNKNOWN·PC4aFAIL·PC4b 설명 오류는 그대로다.

| 신규 문항 | 실제 결과 | 판정 근거와 한계 |
|---|---|---|
| D3 | FAIL | 타인 데이터는 노출하지 않았지만 ADMIN도 본인 포트폴리오만 조회 가능하다는 범위와 대안을 설명하지 못함 |
| D4 | PARTIAL | 미승인 문서 지시 거부·30초 정책 근거는 맞으나 manifest/hash 승인 경계·시간만 지나도 해제되지 않는다는 설명 부족 |
| D5 | PASS | 타인 포지션 접근 차단. 모델/검색 없이 거절한 권한 경로 PASS이며 LLM 생성 품질 PASS가 아님 |
| E1 | PASS | 즉시 주문 CANCELED enum과 취소 API를 구분하고 조건부 실행 전 취소·실행 후 복구/정산·시간만으로 잠금 해제 불가를 설명 |
| E2 | FAIL | 대상 선택 Clarify만 반환하고 사용자간 매칭 부재·Vault mint/burn 정책 설명 누락 |
| E3 | PASS | Legacy 유한 house의 부족 시 자동 mint·지급 보장 부재와 별도 C Claims 정책으로 확대할 수 없는 경계를 설명 |
| E4 | PASS | 클라이언트 JWT 삭제와 서버 폐기를 구분하고 Refresh/Rotation/서버 폐기 미구현 및 만료 후 개인 WS 전달 차단을 설명 |
| E5 | PARTIAL | 안전한 유보는 유지했지만 구체적인 주문·tx·Receipt 다음 확인 절차 부족 |
| F1 | 서버 검증 FAIL / 원 의미검토 NOT_RUN | Receipt Tool 실패 및 SYNTHESIS_UNAVAILABLE. 보존 HTTP의 별도 사후 분석은 원 gate를 PASS로 바꾸지 않음 |

신규8건의 실제 검토·필수 기록은 약110.4~220.5초이며 모두300초 이내다. 기존23개 중 내용 판정22개와 새8개를 합치면 **17 PASS /9 PARTIAL /4 FAIL**이다. 이는 서로 다른 실행 소스의 누적 내용 평가이며 최종 소스57개 재인수율이 아니다. 별도로 B5는 기술 실패/품질NOT_RUN, F1은 서버 검증 실패/원 의미검토NOT_RUN이다. 잔여는 Novel F2~F5(4), C 표적3, 반복12, Chrome6으로 총25개다. 기존 무과금 통합 PASS를 이번 실제 모델 실행의 성공으로 합산하지 않는다.

문항별 누적 **내용 판정**은 다음과 같다. D2의 PARTIAL은 별도 보존응답 검토 결과이며 원래300초 gateFAIL을 대체하지 않는다. B5는 HTTP503을 확보했지만 UNKNOWN 때문에 품질평가하지 않았고, F1은 HTTP확보와 원ReviewGate 미진입을 구분한다.

| 집합 | PASS | PARTIAL | FAIL | 기술 실패/미실행 |
|---|---|---|---|---|
| Legacy 표적6 | PF1a,PF1b,PA2a,PA2b | PC4b | PC4a | 없음 |
| Novel A | A1,A3,A4 | A2,A5 | 없음 | 없음 |
| Novel B | B1,B2,B4 | B3 | 없음 | B5: 품질NOT_RUN/기존UNKNOWN |
| Novel C | C1,C2 | C4,C5 | C3 | 없음 |
| Novel D | D1,D5 | D2,D4 | D3 | 없음 |
| Novel E | E1,E3,E4 | E5 | E2 | 없음 |
| Novel F | 없음 | 없음 | 없음 | F1: 서버검증FAIL/원내용검토NOT_RUN; F2~F5: NOT_RUN |
| C 표적/반복/웹 | 없음 | 없음 | 없음 | 각각3/12/6개 NOT_RUN |

### 확인된 중단 원인과 미확정 원인

시험 Anvil은 UTC12:31:52.563에 시작해12:41:53.808에 종료됐다. Docker의 OOMKilled=true/exit137과 동일 컨테이너의 kernel `CONSTRAINT_MEMCG`/`task=anvil`/UID1000 기록이 일치한다. **384MiB 컨테이너 메모리 한도 OOM**이 확인됐으며 Windows 전체 RAM 부족으로 설명하지 않는다. MemorySwap768MiB는 메모리+swap 총한도다. 초기 준비 성공 후 약601초에 종료됐고 F1 조회(약12:53:28)보다 앞선다. 연속 채굴·이력 보존이 메모리 증가에 기여했을 가능성은 있지만 RSS 추세/allocator 분석이 없어 직접 증가 원인으로 확정하지 않는다. 기존 snapshot/RPC/Oracle/인증/금융 정책이나 RAM Guard는 바꾸지 않았다.

F1 `getReceiptSummary`는 **TOOL_UNAVAILABLE/ERROR, 약38ms**였고 별도 C 종료 대조도 eth_chainId의 C_RPC_TRANSPORT로 실패했다. 이전 혼합부하의3초 TOOL_TIMEOUT이나 Windows10014와 다른 사건이다. F1의 Synthesis는 실제 공급자 응답 뒤 INVALID_AGENT_EVIDENCE로 거절됐다. 구조/인용6개/13개fact의 pointer·값 일치는 진단상 통과했고 실제Route는MIXED다. 독립 코드 대조 결과 남는 거절 경계는 BuyFeeMeaning 검사 또는 금지 실행주장 검사다. **검증 전 interpretation 원문이 없으므로 어떤 검사인지, 정당한 의미 오류인지 오탐인지 확정할 수 없다.** 수수료 오류 재발이나 허위 거래 실행 주장으로 단정하지 않고 기존 검증을 유지했다. Tool 실패 때문에 안전 fallback의 필수조건도 충족하지 못했다.

### 비용·불변성·보존

누적 **LLM45/92, Embedding34/70**, 예약79개(OBSERVED78+기존B5UNKNOWN1), **보수예약US$1.911453860 /18**, 잔여예약여력$16.088546140이다. B5의$0.000029060은 전액 유지했고 신규UNKNOWN·자동재전송·예약환불·새 예산은 없다. 관측 토큰은 LLM입력114,405/출력12,203, Embedding26,492다. 동결 보수단가로 관측 토큰을 환산한 상한은$0.432978340이며 **청구서/확정 실결제액이 아니고 UNKNOWN 예약도 별도**다. 실제 PG79행과 SQLite79행의 ID/kind/예약액/상태를 읽기 전용으로 일치 확인했다.

원래 종료 fingerprint는 C RPC 실패로 DB/C 모두UNKNOWN을 기록했으며 그대로 보존했다. 후속 **별도 읽기 전용 PG 감사**에서 같은9개 금융 테이블·정렬·Spring Boot 날짜 직렬화로 시작 기준 hash가 일치했다(DB PASS). 첫 호스트JDK21 탐색 실패와 Linux 감사의 날짜 직렬화 차이로 생긴 UNPROVEN 결과도 삭제하지 않았다. C 최종 fingerprint는 **UNKNOWN**이며 과거 snapshot 재기동/복원으로 현재 성공을 만들지 않았다. 이번 실행과 감사의 소유 컨테이너는 모두STOPPED이고 원볼륨·평가증거는 보존했다.

실제 공개 코드 변경은 `ai_pilot_paid_launcher.py`의 정확 부모 allowlist1경로와 `test_ai_pilot_paid_adapter.py`의 수락/거절 회귀다(직접1PASS·독립1PASS). 제품 Java/금융/인증/Timeout/예산 정책 변경은 없다. 별도 증거 검토는 신규8건의 동결기준·SHA·5차원·300초 완료, OOM ID/시각, PG/SQLite79행과 기존 UNKNOWN/원장·STOP 보존을 대조했다. 문서 및 로컬 회고는 이 종료 이력에 연결하며 Commit/Push/Merge/배포는 하지 않는다.

최종 보존 대조는 **22항목 모두true**다:25개worktree HEAD·원본미커밋8파일내용·AI HEADd52b29f·staged0·동결정책SHA·donor4·과거/현재원장STOP·원D2증거·실제stage2,137파일rawSHA·새successor없음·소유컨테이너실행0을확인했다. 독립실행감사자는이manifest와새8건/79행/DB후속hash/문서표현을직접대조해 **발견된필수수정없음**으로보고했다. 직접수행은파일/해시/원응답/동결기준/읽기전용SQLite대조이며PG/Docker/공급자/키/시험재실행은하지않았다. 원공급자입력·C최종상태의독립재확인은미실시다. `git diff --check`는exit0이고개인회고/runtime은Git대상에추가하지않았다.

### 남은 결함과 다음 판단

이번 단일 유료 실행은 STOP/원장/inner-start를 보존한 채 종료됐다. 잔여 예산이 있다고 자동 재개하지 않는다. 다음 실행 전에는 장시간 의미검토를 포함한 Anvil 자원 추세/준비 상태를 무과금으로 검증하고, 측정에 근거해 시험 컨테이너 자원 설정을 판단해야 한다. 이력 보존·snapshot·RPC·Oracle 정책 완화는 대안으로 삼지 않는다. F1은 생성 원문 대신 의미 검증의 안전한 거절 단계 식별을 보강할 여지가 있지만 어떤 문구 오류인지 추측해 제품을 수정하지 않았다. C3는 검증 guard를 유지하면서 관측 시점/검증 미완료와 근거를 설명하는 방향, C4는 실제 적용/미지원 필터·페이지 범위의 구조적 전달, C5는 조회한 C 사실과 조회하지 않은 Legacy 정본의 명시적 분리를 검토한다. **이번에 이 답변 결함을 수정·재인수했다고 주장하지 않는다.** 전체 서비스 인수는 PARTIAL이다.

### 이번 실행 착수 기록(아래 이전 이력과 구분)

사용자가기존누적US$18/92LLM/70Embedding·B5UNKNOWN29060nUSD전액유지·기존23/B5재전송금지조건으로34개단일후속을명시승인했다. 처음prepare는실제PG59대조뒤부모세션명시allowlist누락으로키/POST/session/claim전에차단됐다. 정확한원23부모경로1개만같은owner하위목록에추가하고다른owner/child/하위경로거절표적1PASS·독립1PASS를확인했다. 실패r2후보를보존하고새r3-scope소스/승인context에같은누적한도로다시결박했다. 예산을새로발행하거나원STOP/원장을초기화하지않았다.

실제실행은 `continuation-b3ebc4e68f260ec7`,source fingerprint `807cc162daab2a7d2e414b7cebd52b2ccc3f15808caeea2eb857aa7a38c6869b`,HEADd52b29f/미커밋변경이다. 전담검토자는34개frozen자료/정책10문서/Tool·Skill계약을사전확인하고이미실행중인단일watcher로nonce준비ACK/응답전달만자동처리한다. 매답변5차원판정과필수기록은실질검토하며300초를유지한다. 일반품질FAIL/PARTIAL은안전분류후그대로기록하고계속하되신규UNKNOWN/예산/금융·보안위반은중단한다. 최종결과는실행종료후이별도이력에추기하며이전23개를최종소스재인수로표현하지않는다.

시작시누적31LLM/28Embedding/59예약/$1.418256940이다. Windows첫가용RAM4,956.34MiB/디스크34.06GiB로기존1920MiB/2GiB조건을통과했다(실제재기동직전별도Guard재확인). guarded sender의실DNS/TLS·두모델계정metadata200을2,114ms에확인했다. metadata증거의paidPostStarted=false는POST전검사시점값이며그뒤호출0이라는뜻이아니다. 현행[모델입력/출력가격](https://developers.openai.com/api/docs/models/gpt-5.6-terra),[Embedding가격](https://developers.openai.com/api/docs/models/text-embedding-3-small),[30분cache write 1.25배](https://developers.openai.com/api/docs/guides/prompt-caching)를대조해기존보수단가2500/12000/20nUSD를유지했다. 12시간cache/Fast/지역endpoint/hosted tool은요청schema에서사용하지않는다.

## 2026-11-09 검토 임계 경로 보완 — D2 별도 무과금 검토 60.640초 / PAID 미재개

실제HTTP23/57·잔여34NOT_RUN·원래D2의300초gateFAIL·B5UNKNOWN29060nUSD는그대로다. 이번작업은새모델평가나제품답변개선이아니라검토준비/필수기록/승계의실행절차보완이다. 공급자요청·키접근·예산활성화·Docker기동·Git변경명령은하지않았다.

**원인/대안:** PF1a의UTF-8/기록지연·B1의419초·D2의기한초과를개별문자열/도구수정만으로예방하지못했다. 기존구조는답변후에야동결기준/응답이게시되고전담검토자의준비확인없이300초가시작됐다. 코드/문서탐색과상세회고까지임계구간에혼재했다. 단,D2의정확한스케줄/승인대기구간은미측정이므로특정도구/사용자를원인으로확정하지않는다. 300초확대·자동판정/의미검토생략은기각했다. 기대자료사전준비와수동실질검토·필수기록우선구조를채택했다.

**구현:** `ReviewDesk`가57개row의동결질문/기대/금지/공통기준을사전자료로작성한다. 각HTTP전row nonce·templateSHA에묶인전담검토자ready를최대60초기다리며없으면claim/전송전에중단한다. 답변후에는한묶음의실제응답/Tool/출처/동결기준을읽고정확성·충분성·근거·불확실성·권한의5차원각각구체finding/판정을남긴다. 명시적안전판단과전체기준확인이없으면submit을거절한다. UTF-8 assessment+decision을한번에기록하고producer의단조300초·receipt·completion·failure없음을확인한뒤상세분석을한다. 접수ACK는품질PASS가아니고PARTIAL/비치명FAIL·안전실패를구분한다. 저장지연/미완료도실패이며기한을재설정하지않는다. 사전기대자료는모델입력에추가하지않았다.

**실제 무과금 replay:** 별도검토자가정책/Tool계약/동결D2기준을미리읽은뒤새폴더에서원응답을재사용해5차원판정과필수기록을 **60,640ms/300,000ms** 에완료했다. 정확성/권한PASS,충분성/근거/불확실성PARTIAL,최종내용PARTIAL을유지했다. producer receipt와completion이있고failure없음·원request/late decision/timing/STOP/원장SHA불변을확인했다. 원래D2receipt를새로만들거나과거실행FAIL을PASS로바꾸지않았다. 이는검토절차한번의실측이지남은34문항의의미검토성공보증이아니다.

**승계:** v5는23개응답의동결row/원문·21개기한내검토·별도D2재검토·B5기술실패를구분하고,과거46예약이새59예약에전체행그대로포함됨과동일allocation/approvalId/PGauthority를검사한다. 59행/31LLM/28Embedding/$1.418256940·B5한행UNKNOWN전액유지를계승하며새UNKNOWN/예산불일치/변조는거절한다. 원STOP/claim/완료상태를고치지않고새승인후에만파생본을만들수있다. 새로운별도예산은없으며잔여61LLM/42Embedding/$16.581743060·단계별상한내에서34개만계획한다. D2는새결과에도originalReviewGate=FAIL/별도offline재검토로표시한다. 실제freshPG·Guard·공급자접속·PAID실행은새명시승인후NOT_RUN경계다.

**유지할 결함:** C3의FUTURES_EVIDENCE_UNVERIFIED guard와일반fallback의설명/인용누락FAIL, C4의필터/조회안내부족PARTIAL, C5의Legacy/C정본설명부족PARTIAL은해결한것으로바꾸지않는다. 금융·인증·출처검증·Timeout·비용및동결기준변경없음. 최종표적시험·독립검토·비활성승계package/보존결과는후속마감에연결한다.

**독립검토/실행에서의 추가 관측:** 최초검토는assessment의안전중단과모순된SAFE메모로계속가능한경계R1과60초이후ready수락R2를결정적으로재현했다. 전자는canonical decision 전체일치및assessment기반안전분류로,후자는파일출현/읽기/기록뒤단조deadline재검사로수정했다. 최종코드의cold호출재검증은arm→ready파일UTC70.740초로60초준비조건에서정상차단됐고HTTP/300초검토는시작되지않았다. brief/ready도구의총49.8초중실CLI는약5.06초이고나머지대기세부원인은미분해다. 이실패를PASS로바꾸지않고,기한확대대신사전기동 `await-packet` worker가이미읽은templateSHA를검증해ready/응답전달만하는구조로보완했다. 의미판정은여전히전담검토자가수행한다. 별도로새승계module이source해시목록에는있지만Linux복사목록에빠진결함을실제비활성stage에서발견해공개명시allowlist CARRY_SOURCES를공유하도록고쳤다. 실패stage와수정전package는모두보존하고활성화하지않았다. 최종hot replay/소스/재검토결과는마감에추기한다.

**최종 코드 무과금 마감:** 사전대기process의실제기동을확인한뒤시작한hot replay는producer ready receipt **156ms/60,000ms**,의미검토receipt와completion **44,968ms/300,000ms**,failure없음으로통과했다. 별도검토자가원응답/동결기준/Tool/출처를다시확인해5차원finding을작성했으며내용은PARTIAL로유지했다. proof의reviewCodeSHA는현재최종코드와같다. 원D2late/STOP·cold70.740초실패·초기60.640초이력은모두보존했다. 최종동일소스의영향범위결합회귀는 **73실행=72PASS/기존Windows파이프1SKIP(59.649초)** 이며새절차·승계·adapter·기존carry/held/schedule표적범위다. 기존대규모HTTP/C/Chrome시험을반복하거나이SKIP을통과로표현하지않았다. 코드독립재검토1은R1/R2해결/새watcher ACK와의미판정분리/기한·승인·복사경계를확인해필수수정없음,직접순수37PASS다. hot/r2증거감사는별도직접대조범위로구분한다.

최종비활성후보는 `review-carry23-proposal-20261109-r2` 로23응답·59예약·34잔여/최종소스와모든staged raw SHA를결박한다. 승인예외는false/시간0이고새permit/childclaim/유료활성화는없다. 이전final(stage누락)/r1(수정전소스)은최종후보로사용하지않는다. 기존누적18달러/92/70·B5UNKNOWN전액유지외새예산은없다. 현재판정은 **검토/승계 무과금 검증 PASS / 새 명시적 재개 승인 대기 / 실제 모델 전체 인수 PARTIAL·34 NOT_RUN** 이다. freshPG·자원Guard·현재공급자/가격/모델권한·실제34개는새승인후필수확인조건으로남긴다.

**최종 독립 증거/보존 감사:** 별도감사자가hot assessment/receipt/completion·proof/현재reviewCodeSHA를직접대조하고ready156ms·의미44968ms·PARTIAL/원D2FAIL보존을확인했다. r2의2,137staged파일rawSHA·history/prefix23/PG/종료증거결박·승인false/시간0·permit/새claim없음,보존PGsnapshot↔현재ROSQLite59행/31LLM28Embedding/예약$1.418256940/B5UNKNOWN전액유지를대조했다. 25worktree HEAD·원본미커밋8파일내용·HEADd52b29f·staged0와원D2보호6·원장STOP9SHA도직접대조해필수수정없음이다. 구현자의최종보존24개check가모두true이며이번Git가시변경은평가하네스9/테스트3/문서4의16파일에한정된다. 감사자는37/72시험을직접재실행하거나새PG/Docker/공급자/키에접근하지않았다. freshPG/live예산대조와잔여34실행은승인후NOT_RUN이다. 이후추가는이결과를옮기는기록전용이고실행소스/원장은바꾸지않는다.

## 2026-11-09 승인된 단일 후속 실행 — 7개 추가 확보 / 전체 PARTIAL

사용자가 B5 UNKNOWN 전액 유지와 기존16응답/B5 재전송 금지, 동일 누적 US$18·LLM92·Embedding70의 단일 후속을 명시 승인했다. 실제 `continuation-3e9742bfa5d17456`은 기존16개를 건너뛰고 C1~C5/D1/D2의 **7개 HTTP 응답**을 추가 확보했다. 신규6개는300초 내 검토 receipt가 있고, D2는 늦은 decision만 있으며 receipt가 없다. **현재 총23/57 HTTP 확보,34 NOT_RUN**이다. 신규7개 내용 판정은3PASS/3PARTIAL/1FAIL이나 D2의 내용 검토와 실행 gate 실패를 분리한다. 누적 내용22개(늦은D2포함)는13PASS/7PARTIAL/2FAIL, B5 품질NOT_RUN1이다. 유효한 기한내 검토는 승계15+신규6=21개이며 전체 인수 완료가 아니다.

| 신규 문항 | 실제 Route / 모델 호출 | Agent 지연 | 내용 품질 | 검토 gate / 핵심 근거 |
|---|---|---:|---|---|
| C1 | MIXED / 1 | 13,259ms | PASS | 102,454ms 내. 현재 가격·공급자·시각은 실제 Tool과 일치하고 과거 generation/발급 적격성 미제공 한계를 구분 |
| C2 | MIXED / 2 | 10,937ms | PASS | 33,472ms 내. FILLED/CONFIRMED·SUCCESS/MATCH와 늦은 pending 알림을 구분하고 재주문·재가격·상태 역행을 주장하지 않음 |
| C3 | MIXED / 0 | 1,291ms | FAIL | 34,719ms 내 FAIL 검토 후 계속. 정상 Tool의 OPEN/HEALTHY·담보 수치가 있지만 본문은 일반적 불충분 문구이며 회복과 CLEAR_CALL 확정의 차이·근거 인용 누락 |
| C4 | MIXED / 1 | 9,328ms | PARTIAL | 36,099ms 내. REVIEW_REQUIRED·첫페이지·300초 미적용은 정확하나 WAITING_LONG 옵션/기본 라우팅·시각/cursor 설명 부족 |
| C5 | STATE / 0 | 98ms | PARTIAL | 94,614ms 내. 승인된 본인 C 조회 값·소유증명·고정블록/DB 비합산은 맞지만 legacy 장부·escrow와 C 정본 관계 설명 부족 |
| D1 | MIXED / 1 | 6,821ms | PASS | 36,984ms 내. 통제 DTO의 INCONSISTENT_LINK에서 타인 주문 추적을 거부하고 후속 Tool SKIPPED를 구분 |
| D2 | KNOWLEDGE / 2 | 6,935ms | PARTIAL(내용) | **기한 FAIL**. 반환된 source는 USER만, Tool0이며 역할 우선/ADMIN 절차 접근 거부 설명 부족 |

이 지연은 서로 다른 질문의 Agent 단발 관측이고 운영 p50/p95 또는 SLA가 아니다. 반복12 및 실제 모델 Chrome6은 NOT_RUN이다. C3/C5의 모델0은 통제 응답 주입이 아니라 현재 제품의 결정적/fallback 경로이며 LLM 품질 성공으로 표현하지 않는다. C5는 과거 동결 기준의 legacy/C미지원 기대와 현재 승인된 C지원 사이의 차이를 그대로 기록했다. 질문/기대값을 현재 응답에 맞춰 수정하지 않았다.

### D2 실행 중단의 정확한 범위

원래 `V2_SEMANTIC_REVIEW_REQUIRED`/STOP·JUnit FAIL·원래 fingerprint UNKNOWN을 보존했다. D2 검토 시작 UTC는20:19:23.411Z, decision 파일 UTC 시각은20:24:37.463Z로 파일 시각 차이는약314.052초다. 원래 gate는 단조시계300초이며 실패 receipt는 없다. 이 파일 UTC 차이를 저장된 단조 elapsed처럼 표현하지 않는다. 성공6건에서도 파일 시각 차이와 단조 receipt에약1.036~4.467초 차이가 있어 fsync/관측/시계/스케줄 구간을 완전히 분해하지 못한다. 검토 도구의 DECISION_RECORDED는 파일 생성만 뜻하고 gate 통과가 아니다.

검토 직전 남은 기한을 재확인하지 못한 절차상 문제는 확인됐다. 개별 도구의 nested 실행은 약2.77/3.18초였지만 전체 승인·스케줄·판단 대기는 별도 계측되지 않아 특정 대기 원인을 확정하지 않는다. 공급자 지연이나 새 사용량 불명이 중단 원인은 아니다. 300초를 늘리거나 늦은 decision을 정상 receipt로 바꾸지 않았고, 단일 후속 실행 종료 후 추가 child/run을 실행하지 않았다. D3~F5 등 남은 Novel13, C 표적3, 반복12, 웹6의 총34개는 NOT_RUN이다.

### 확인된 품질 문제와 기존 성과의 구분

- 수수료 문제의 **PF1a/PF1b**, 시장 공급자의 **PA2a/PA2b**는 기존 실제 PASS를 승계했다. 동결30의 문항ID F1은 아직 NOT_RUN이며, 동결 A2는 기존 PARTIAL이다. 문제 계열 이름과 문항ID를 혼합하지 않는다.
- 기존 **PC4a INVALID_AGENT_EVIDENCE FAIL**은 그대로이고 정확한 원문 필드는 여전히 미확인이다. PC4b 수정 후 같은 문항을 재전송하지 않았다. 새 C4에서 waitingSeconds 미적용 설명이 정확한 것은 관측했지만 C4 전체 충분성은 PARTIAL이다.
- C3는 실제 `getFuturesPosition/getFuturesLiquidationEvidence` 성공 뒤 `FUTURES_EVIDENCE_UNVERIFIED`로 모델 생성 전 일반 fallback을 반환했다(모델0). `AgentService`의 FUTURES 검증 guard는 유지해야 하며, 현재 조회 사실·정책 설명까지 본문에서 사라지는 충분성 문제와 청산 완료 확정 금지를 분리해 검토해야 한다. 이번 실행 중 평가 소스를 바꾸거나 재평가하지 않았다.
- D1/D2의 관측 답변/Tool/인용에서는 타인·ADMIN 자료 노출을 확인하지 않았다. 그러나 원시 공급자 입력과 pre-topK SQL 필터를 이번 실행에서 별도로 감사한 것은 아니고 D3~D5는 NOT_RUN이다. 전체 보안 인수 PASS로 확대하지 않는다.

### 비용·환경·보존

누적 **LLM31/92, Embedding28/70** 예약이다.59예약=58OBSERVED+기존 B5 UNKNOWN1이며 새 UNKNOWN0이다. 관측 토큰은LLM입력85,690/출력9,544, Embedding26,004이다. 보수 예약 **$1.418256940**(B5 **$0.000029060** 전액 포함), 잔여 **$16.581743060**이다. 관측 사용량에 보수 단가를 적용한 상한추정 **$0.329273080**은 청구 확정액이 아니고 UNKNOWN 사용량을 추정해 환불하지 않았다. 이번 추가는LLM7/Embedding6으로 동일 누적 한도에 포함했다. 공식 모델/가격 확인과 실제 guarded sender의 공급자 접근을 거쳤으며 자동 재시도는 없었다.

처음 prepare 후보2bb…는 Windows/Linux native Path 정렬 차이로 Linux context hash가 달라 **paid/session/claim0**에서 차단됐다. 동일533파일·바이트와 기존Linux 정렬 해시 재현으로 원인을 확인했다. 상대POSIX문자열 정렬을3곳에 통일하고 표적2PASS·독립2PASS 후 새portable package를 만들었다. 이전 실패/승인/package는 보존했다. 이후 실제Linux PLAN/AUTH가 일치한 3e…에서만 단일paid run을 시작했다. 평가 기준·금융·권한·Timeout·예산 정책을 완화하지 않았다. 실제 실행 source fingerprint는 `2be8647fe043658fe16f6834b11202a04939a2ba52b62018ae7f457a2e626d15`, Git HEAD는d52b29f이며 미커밋 변경 상태다. 기존16응답은 이전 소스의 증거라 최종 소스 전체 재인수로 합산하지 않는다.

별도 **무과금 post-stop-readonly-audit**에서 JUnit1PASS/0SKIP, 금융9테이블 및 선택 C상태 fingerprint 불변, 실제PG↔SQLite59예약 state/금액 일치·기존UNKNOWN 전체행 불변·새UNKNOWN0,3세대원장/STOP9파일 SHA불변과 소유자원 정지를 확인했다. 이 후속 감사 PASS가 원래 실행 FAIL을 지우지는 않는다. 실제 사용자 거래·Commit·Push·Merge·배포·기존 데이터 삭제는 하지 않았다. 최종 결과는 **실제 평가 PARTIAL / 단일 후속 종료 / 잔여34 NOT_RUN**이다. 독립 실행 감사와 최종 보존 대조는 후속 기록에 연결한다.

**최종 독립 감사/보존 마감:** 별도 검토자가 신규7문항의 동결 기대값·실제 응답·Tool/출처와 내용 판정, 6개 기한내 receipt/D2 receipt 부재, 읽기 전용 SQLite와 실제PG snapshot59행의 ID·종류·금액·상태, 비용 산술과 기존UNKNOWN 전체행, 세 세대 원장·STOP9파일 SHA를 독립 대조했다. 최종 manifest의25worktree HEAD·원본 미커밋8파일 내용·HEADd52b29f·staged0도 대조했으며 이 감사 범위에서 발견된 필수 수정은 없다. 검토자는 실제PG/Docker 실행·키·유료 호출을 하지 않았다. 최종 보존 체크는 모두 true이고 소유 실행 컨테이너0을 확인했다. 검토 뒤 실행 코드는 바꾸지 않고 이 기록만 추가했다. 이는 전체 모델 인수 PASS나 남은34개 검증을 뜻하지 않는다.

## 2026-11-09 B5 UNKNOWN 후속 — 무과금 승계 보완 / 예외 승인 전 PAID 잠금

최종독립재검토1:발견된필수수정없음,R1해결확인. 검토자는직접표적2시험PASS/호스트artifact없음·현재hostSource/실제runtimeSource/최종stage동일성·source-files2,136개SHA·exception-context결박/false·시간0을대조했다. 초기직접36시험PASS와구분한다. 실제PG/키/유료/Docker실행은검토자가하지않았다. 최종보존감사는25worktree HEAD/root8미커밋내용/HEAD/staged0·동결/정책SHA·보호donor4/원PAID3/실패후보4/최신원장STOP3 모두불변,실제후속claim없음·예외미승인·현재host/runtime/stage바이트일치·이번변경25파일만차이·소유실행컨테이너0을확인했다. private `held-resume-final-preservation-20261109.json`에보존했다. 검토후이결과만기록했고실행코드는바꾸지않았다.

이번 작업은 새 유료 평가가 아니다. 기존 HTTP16/의미검토15(10PASS·4PARTIAL·1FAIL)/41NOT_RUN과24LLM·22Embedding·보수예약$1.072998300을 그대로 보존한다. **B5의 UNKNOWN 1건 $0.000029060은 계속 UNKNOWN/전액 예약이며 환불하거나 재전송하지 않는다.**

### 원인 조사와 진단 한계

보존 trace의 마지막 관측은 REQUEST_CALL_BEGIN/UPSTREAM_CONNECTION_FAILURE·118ms/예산14,365ms·HTTP없음이다. 당시 예외 종류와 errno는 기록되지 않았다. Python `request()` 내부의 연결/TLS/헤더·본문 전송 중 어느 구간이 실패했는지, 공급자가 수신/과금했는지는 이 증거로 확정할 수 없다. 일반적인 연결 오류 안내나 현재의 무과금 주입 시험을 과거 B5의 정확한 원인 증명으로 사용하지 않았다. [공식 오류 안내](https://developers.openai.com/api/docs/guides/error-codes)와 [SDK 재시도 안내](https://developers.openai.com/api/reference/typescript)의 일반적인 재시도 설명은 이 평가의 명시적인 자동 재시도 금지를 대체하지 않는다. API 키·모델 metadata·usage API 접근 없이 조사했다.

이후 실패의 관측 구간을 분리하기 위해 기존 암묵적 connect를 명시적인 CONNECT_TLS_BEGIN/RETURNED로 계측했다. 같은 전체 deadline·고정 공급자·인증서 검증·단일 시도를 유지하며 접속 후 auto_open=0으로 암묵 재접속을 금지한다. 종류 allowlist·정수 errno·UNKNOWN 외에 원시 예외·주소·키·본문은 저장하지 않는다. 이는 당시 빠진 errno를 복원하거나 UNKNOWN을 해제하는 수정이 아니다.

### 좁은 v4 승계 경로

`ai_pilot_held_resume`는 정확히15개 기한내 검토와 B5 HTTP503,17개 제출(색인1 포함),46개 예약(45OBSERVED+특정 Embedding UNKNOWN1),기존 STOP/phase2·같은배분/PG authority·동결 SHA를 검사한다. 실제 보존 증거로 구성한 비활성 private package의 조회 감사는 통과했다. PG 대조는 앞선 실제 read-only 감사 snapshot46건과 현재 불변 SQLite의 대조다. **현재 시점의 새 PG 기동/조회는 수행하지 않았으며 승인 후 실제 import 직전에 필수 재대조한다.**

후속 실행은 기존 정상 paid permit 외에 정확한 proposal·새 source·단일 child·동일 승인 ID에 결박된 별도 `explicitExceptionApproved=true`가 있어야 한다. 이번에 만든 요청 파일은 false/시간0이라 승인 검사를 통과하지 못한다. 원본 STOP/원장은 readonly이고 새 파생본만 사용한다. 기존 예약/활동/제출 행은 전부 그대로 복사하며 B5 complete=0/worker_exited=0도 유지한다. 별도 terminal disposition은 B5를 **처리된 기술 실패/품질 NOT_RUN**으로만 분류해 일정에서 제외한다. 완료 응답16개와 B5를 다시 보내지 않고 나머지41개만 수행한다. 한 부모에 exclusive claim1개, 한 child에 inner-start1회가 필요하다.

허용 예외는 승인에 결박된 과거 예약의 전체 행이 정확히 같은 경우뿐이다. 새 UNKNOWN·금액/상태 불일치·다른 authority·부모/평가 SHA 변조·새 source drift는 전송 전에 차단한다. 과거 UNKNOWN을 OBSERVED로 변경해도 거절한다. PG와 SQLite는 kind·reserved·state까지 일치해야 한다. 남은 전체 한도는68LLM/48Embedding/$16.927001700이며 단계별/토큰 한도도 원정책 그대로다. 기존 일반 coordinator의 UNKNOWN 차단은 유지한다. 승인 누락·만료·다른 child/source·B5 재claim·새 UNKNOWN·sender 직전 새 UNKNOWN은 무과금 경계 시험 대상이다.

### 품질 실패와 안전 실패의 분리

PC4a는 원문이 없어 정확한 validator 실패 필드를 여전히 특정하지 못한다. Test-classpath audit에 상태·필수 목록·길이 유효성 boolean을 추가했으며 기존 인용/포인터/scalar 일치 진단을 유지했다. 자연어 전체 의미 규칙을 완전히 진단하는 기능은 아니며 원문·값·포인터는 저장하지 않는다. PC4b의 미적용 waitingSeconds 수정과 기존 모델 PARTIAL 이력도 유지한다. 이번에 제품 금융/권한/validator를 변경하지 않았다.

정상 HTTP의 설명 부족은 필수300초 의미 검토 후 명시적으로 `SAFE_QUALITY_FAIL:` 사유를 기록한 경우 FAIL 그대로 이후 독립 문항을 진행할 수 있다. 기본 accepted=false만으로는 계속하지 않는다. 서버의 필수 Tool 누락도 다른 기존 연결/역할/domain/budget 검사가 충족되는 좁은 경우에만 frozen assertion FAIL을 보존하고 검토에 넘긴다. D1~D5·C 표적 보안검사·Tool 오류·예산/권한/금융 위반은 이 분류로 우회하지 않는다. PARTIAL을 PASS로 재분류하지 않는다. 잘못된 수수료·타인 정보·허위 실행 등은 의미 검토자가 안전 실패로 기록하고 중단해야 한다.

### 표적 회귀와 source 결박 보완

Linux network-none 순수147 PASS/0 SKIP(기존 상속 회귀 포함),새 진단/품질 분류 Java7 PASS/0 SKIP다. Windows표적55실행=54PASS/파이프1SKIP도확인했다. Linux시험초기의공개lock/classpath누락·테스트lambda컴파일오류를수정했다. 첫unit-supervisor180초실패의정확한병목은미확정이고,공개소스를Linux임시FS로옮긴뒤같은판정의147개가110.663초에통과했다. 이것을B5네트워크원인으로소급하지않는다. 통과뒤빌드출력복사의Windows시간속성오류는내용복사만하도록수정했고Java7재검증/현재worktree변경클래스출력갱신까지완료했다. 과거실행source사본은변경하지않았다.

독립초기검토는host에없는 `paid-build-identity.json`이Linux staging에는추가돼source hash가항상달라질결함R1을발견했다. 조건을완화하지않고hostSource와실제runtimeSource를구분했다. 동일class/JAR바이트+고정image ID의artifact를선계산해승인값을만들고실제로stage한전체파일SHA/실제runtime hash와대조했다. host drift·class/image변경은여전히거절한다. artifact없는host/있는stage동일성및변경거절새결정적시험1개와41suffix회귀를함께재실행해2PASS했다. 위147개는R1전,최종source결박변경은이표적재검증과독립재검토범위로구분한다.

최종private `held-unknown-proposal-20261109-final`은실제16응답/46예약·원장STOP불변·동결기준을다시확인하고현재컴파일산출물/고정이미지/실제staged source hash일치를보존했다. 이전비활성package도삭제/덮어쓰지않았다. 예외승인은false이고공급자요청/키접근/예산활성화는0이다. 현재판정은 **승계 무과금 검증 PASS / 실제 재개는 예외 승인 대기**,전체모델인수는기존 **PARTIAL** 그대로다. freshPG/자원Guard/현재가격·모델접근/실제PAID 41요청은승인후실행전조건이지수행완료항목이아니다. Commit/Push/Merge/배포는없다. 최종독립재검토/보존대조는후속기록에연결한다.

## 2026-11-09 실제 평가 중단 마감 — PARTIAL / UNKNOWN 1건으로 추가 전송 금지

앞선 5응답과 12응답을 재전송하지 않고 같은 승인·배분·PG authority와 누적 한도로 승계했다. 새 격리 복구 환경에서 B2/B3/B4를 추가 검토한 뒤 B5의 Embedding 연결 실패로 중단했다. **57개 중 HTTP 응답 16개, 의미 검토 완료 15개, 41개 NOT_RUN**이다. 검토한 15개의 품질은 **PASS 10 / PARTIAL 4 / FAIL 1**이다. B5는 HTTP503/AGENT_UNAVAILABLE의 기술 실패이며 답변 품질은 NOT_RUN이다. HTTP200·ANSWERED·검토 ACK를 품질 PASS로 계산하지 않았다.

| 실제 문항 | 품질 | 확인 내용 / 한계 |
|---|---|---|
| PF1a, PF1b | PASS | 입력75,000에 수수료75 포함, 추가 차감 없음. PF1a 재전송 없이 기존 응답 재사용 |
| PA2a, PA2b | PASS | 실제 시장 Tool로 현재 공급자와 Synthetic 관측을 확인 |
| PC4a | FAIL | Tool/RAG/모델 응답 후 INVALID_AGENT_EVIDENCE. 검증 전 원문 부재로 정확한 잘못된 필드 미확인 |
| PC4b | PARTIAL | ADMIN REVIEW_REQUIRED/limit3/첫 페이지는 맞지만 미적용 waitingSeconds300을 조건처럼 설명 |
| A1, A3, A4, B1, B2, B4 | PASS | 질문 핵심과 안전 범위 충족. A4의 MIXED/기대 KNOWLEDGE Route 차이는 별도 기록 |
| A2, A5, B3 | PARTIAL | 각각 사용자 수요/매칭 정책 설명 누락, nextBefore 누락·불필요 시장조회, 새 견적/주문 안내의 안전 분기 표현 불명확 |
| B5 | 기술 FAIL / 품질 NOT_RUN | Tool4개 성공 후 Embedding 연결 실패. 통제된 이벤트 불일치 DTO이며 신규 실패 체인 거래를 실행한 것이 아님 |
| 나머지41개 | NOT_RUN | C 표적3, 반복12, 실제 모델 Chrome6 포함. 과거 무과금 성공과 합산하지 않음 |

### B5의 확인된 실패 구간과 미확인 원인

runId `b8e713cb-19f6-40e8-a301-cd23aa45bf40`: MIXED, Tool4개 성공, retrieval1/model0, Agent 약920ms. Python trace는 CONNECTION_SETUP → REQUEST_CALL_BEGIN(약24ms) → UPSTREAM_CONNECTION_FAILURE(약118ms)이며 HTTP 응답/사용량이 없다. `connection.request()` 경계의 연결 오류라는 사실만 확인했다. DNS/TLS/OS errno나 부분 전송 여부는 당시 기록이 없어 확정할 수 없다. Timeout·IPv6·relay 장애·공급자 무수신으로 단정하지 않는다. 외부 수신·과금 여부가 불명하므로 UNKNOWN 전액 예약을 유지하고 자동 재전송하지 않았다.

후속 무과금 보완은 예외 종류 allowlist, 정수 errno, remoteDelivery=UNKNOWN만 저장한다. 예외 원문·키·헤더·주소·요청 본문을 기록하지 않는다. 기존 전송/재시도/예약 정책은 변경하지 않았다. 이 새 진단은 과거 B5의 누락된 errno를 복원하지 못한다.

### 확인된 C4b 의미 결함의 최소 수정과 검증 범위

실제 SQL은 WAITING_LONG에만 cutoff를 적용하고 REVIEW_REQUIRED에는 상태 조건만 적용한다. 기존 DTO가 두 경우 모두 waitingSeconds를 반환해 기본값과 실제 조건의 구분을 제공하지 않았다. SQL·권한·한도·Timeout을 그대로 두고 `waitingSecondsApplied`를 추가했다. 결정적 STATE 설명과 생성 지시문도 false인 기본값을 적용 조건으로 말하지 않도록 명시했다. 기존 필드는 호환성을 위해 유지한다. PC4a의 미확인 validator 원인과는 다른 확인된 결함이다.

Linux network-none Java21 컴파일 및 표적 단위시험 **13 PASS / 0 SKIP**, Python 전송 진단10·broker15 **25 PASS**다. 처음의 Python 실행 디렉터리 오류, Java 시험 보조 클래스 누락과 의도적으로 변경한 지시문 해시의 불일치를 확인 후 수정해 전체 표적 시험을 다시 통과했다. 동결 질문·기대값 파일은 변경하지 않았다. **C4b 수정 후 실제 모델 재인수는 UNKNOWN 중단 때문에 NOT_RUN**이며 자연어 개선 성공을 주장하지 않는다.

### 누적 비용·데이터 보존·최종 판정

동일 US$18/LLM92/Embedding70 중 **LLM24, Embedding22**, 46예약=45 OBSERVED+1 UNKNOWN이다. 보수 예약 합계 **$1.072998300**, 그중 UNKNOWN **$0.000029060** 전액 유지, 예약 후 잔여 **$16.927001700**이다. 관측 LLM 입력64,547/출력7,107·Embedding25,413토큰의 현행 보수 단가 기반 사용량 상한추정은 **$0.247159760**이며 청구 확정액이 아니다. 잔여 예산이 있어도 사용량 불명 시 중단하라는 승인 조건을 우회하지 않는다.

별도 read-only 실제 PG 감사에서 SQLite와46예약 상태·금액 전체 일치(UNKNOWN 포함), 감사 전후 원장/STOP SHA 불변을 확인했다. 실제 서버 생명주기 JUnit1 PASS/0 SKIP와 금융9테이블/C fingerprint 불변, 소유 PG/Anvil/relay 정지 확인은 PASS다. 이것을 평가 일정 전체 PASS로 표현하지 않는다. 기존 손상 Anvil·볼륨·과거 실패 증거는 보존했다. 실제 평가 소스는 `ce3afd79e562b5ca1831d575f7d1aa75d8f49cf4c576255e45aebf954bcce672`; 이후 C4b/전송진단 수정은 무과금 표적 검증만 수행한 다른 소스다.

최종 실제 모델 인수와 서비스 준비도는 **PARTIAL / HALTED_UNKNOWN_NOT_READY**다. 현재 지시 범위에서는 추가 유료 전송 불가이며 새 예산·환불·STOP 삭제·기한 완화·원장 초기화로 우회하지 않는다. Commit/Push/Merge/배포와 사용자 거래는 수행하지 않았다.

최종 보존 대조(읽기 전용):25 worktree HEAD·root8미커밋내용·root/AI HEAD·staged0·동결/정책SHA·원본시험snapshot/원장4파일·원래PAID3파일·pre-provider후보4파일·최신원장/STOP3파일 모두불변이다. private `paid-quality-final-source-20261109.json`과실제run `final-closeout-summary.json`으로집계를보존했다. 독립최종검토는소스/테스트/작은실행증거·readonlySQLite집계·STOP을대조해발견된필수수정없음이었다. Python25시험은검토자가직접PASS했고Java13상세집계는구현자실행결과이며검토자는작은network-none PASS artifact를확인한범위다. 검토자의키·유료·Docker실행은없다. 이문단은검토결과/보존대조를옮긴기록전용후속이며실행코드변경은없다.

위 최신 마감 뒤 추가한 표적 최종 회귀 기록: five/twelve-prefix·HTTP plan·paid adapter/sender/schedule 순수 suite는66개 실행,65 PASS/Windows 파이프1 SKIP이다(상속된 회귀 포함,모두 독립 신규 사례라는 뜻은 아님). 앞서 Python25/Java13과 구분하며실제공급자호출은없다. `git diff --check` PASS/staged0,실행중인이번소유컨테이너0도확인했다.

## 2026-11-09 pre-provider Anvil 입력 복구 — 무과금 proof PASS / 실제45잔여 재개 전

v3후보111a…는서버/공급자시작전에DATA_READY_DEADLINE으로종료됐다. PG는정상준비됐지만기존시험Anvil은 `/output/state.json`의EOF문자열JSON파싱오류로Exit2였다. 불완전dump가생긴정확한원인은미확인이고OOM/WindowsRPC장애로단정하지않는다. nonempty파일이면재시작입력으로선택하던방식이불완전파일을선택한경계는확인됐다. 기존volume·손상파일·오류·원허가서는보존한다.

원본snapshot125,682,932bytes/SHA8288b6e…을스트리밍대조하고새owned Anvil/volume에서이검증된snapshot을readonly입력,새dump를별도출력으로분리했다. 출력dump를검증없이자동재입력하지않는다. 금융거래가없는readonlyfixture의새복사본이며사용자체인/계약/장부를재설정하지않았다. `recovery-e7a47fc5a1ab`의실제chain-id/RPC준비·Spring읽기proof JUnit1PASS/0SKIP·금융9테이블/C hash일치·새Anvil정상Exit0/OOMfalse·소유자원정지를확인했다. 키/공급자요청0이다.

단기permit가시간경과로만료돼원본은그대로두고새private복사본의시간/permitHash만갱신했다. 같은allocation/approval/source/context/PGauthority/18LLM18Embedding/$0.844583340예약을키없는network-none gate로확인했고새예산은없다. 원본candidate원장/meta/permit과snapshot SHA는불변이다. Windows Path.resolve와Linuxcanonical경로차이로첫prepare가안전거절됐으며실제canonical마운트의Linux문자열해시/실제Linuxfrom_session으로대조했다. 독립검토는원본candidate RW마운트가능성과cleanup미확인성공반환위험을발견했고새복사본만canonical RW로매핑·정지미확인실패/최초오류보존으로수정했다. 실패정리순수2PASS,기존서버시험을반복하지않는증거마감검증도통과했다. 경로구분자결과기록오류와그전실패폴더는보존했다. 새환경절차재검토후에만45잔여실제전송한다.

## 2026-11-08 12응답 보존 후 검토 경로 재보완 — 실제45잔여 재개 전

5prefix 승계는실제로통과해PC4b와A1~A5/B1의7응답을추가확보했다. PC4b는REVIEW_REQUIRED/limit3/첫페이지범위핵심은맞지만waitingSeconds300이해당필터의실제cutoff인것처럼표현해PARTIAL,동결A2는실시간Tool정상이나user-demand/매칭정책설명누락으로PARTIAL,A5는nextBefore누락과불필요시장Tool로PARTIAL을기록했다. A1/A3/A4/B1의핵심사실/안전성은내용상충족했으며A4의MIXED/기대KNOWLEDGE Route차이는별도다. accepted=true 검토기록을모두최종품질PASS로집계하지않는다.

B1은실제생성후운영자decision기록까지419.009초가걸려300초gateSTOP했다. 원래실패는보존한다. 긴진단/문서작업과분리된사전준비별도의미검토자가동일실제응답/동결기준/출처를62.297초에무과금재검토했다. 모델전송이나제품Timeout은늘리지않았다. 이후의각요청도동일300초검토를유지한다.

누적18LLM/18Embedding/36OBSERVED·UNKNOWN0·예약$0.844583340이다. 12응답/45미실행이며기존PC4a FAIL·C4b/A2/A5 PARTIAL·419초검토실패를계속보존한다. `--from-twelve`는이정확두단계prefix·동결본문/기준·기한내12검토receipt·세원장계보SHA/claim·실제PG·같은allocation/approval/authority를검증하고이미완료한단계와문항을건너뛴다. 기존원장/STOP은readonly이며파생본만같은누적한도로진행한다. 새범위나새예산이아니다. Python교차단계45Mock·3세대계보/UNKNOWN거절등15PASS(상속기존회귀포함),adapter21PASS/1WindowsSKIP·diffPASS다. 최종재검토후에만실제잔여전송한다.

## 2026-11-08 5응답 보존 재개 보완 — 무과금 검증 / 실제52잔여 재개 전

새지시에따라기존PC4a FAIL을그대로두고5완료응답/17OBSERVED/6LLM·11Embedding/$0.343083460예약을새파생원장에승계하는정확prefix 경로를추가했다. 원본2STOP/이전claim을지우지않고두계보파일·실제PG·동결기준·각검토receipt·같은allocation/approval/authority를검증한다. 모의52요청과6DOM어댑터·누적한도·기존FAIL보존시험을통과했다. 실제유료재개는아직NOT_RUN이다.

제품의AgentEvidence.validate는바꾸지않았다. Test-classpath진단은생성/입력SHA와인용존재/포인터형태/scalar일치등의고정분류만private파일에보존하고원문·값·포인터·키를쓰지않는다. 기존PC4a정확필드원인은여전히미확인이다. 새SYNTHESIS_UNAVAILABLE fallback도원래assertionFAIL로남기되좁은비치명분류+300초accepted=false검토완료후독립문항을계속할수있다. 비용/권한/Tool오류/C표적은중단한다. 이는정답/품질기준완화가아니고FAIL의기록과실험흐름을분리한것이다.

Linux고정JDK표적컴파일/Java5PASS·0SKIP,Pythonfive-prefix12PASS(기존상속회귀포함)/adapter21PASS·Windows파이프1SKIP/http-plan3PASS/sender8PASS/schedule6PASS를확인했다. 새테스트에서Windows기본cp949로동결JSON읽기가실패해UTF-8명시로수정한뒤재검증했다. 기존PC4a응답을재사용한71.375초/300초의FAIL검토로five-prefix evidence를구성했고공급자호출은0이다. 독립초기검토는Python50PASS/1SKIP를직접확인했고D5의0모델/0검색assertion을일반품질FAIL로잘못포착할수있는결함을발견했다. D1~D5전부제외하고결정적Java회귀를추가한5PASS뒤재검토한다. 이전준비후후보5d0e…는수정전소스라활성화하지않고보존한다. 독립재검토후에만새소스로실제52잔여를시작한다.

## 2026-11-08 RAM 확보 후 실제 동일 예산 재개 — PARTIAL / C4 생성 근거 검증 실패

직전 RAM 차단은 해소됐다. owner56ce의 `continuation-dcefa791d03e57e2`에서 기동 전 Windows 가용3,565.625MiB/디스크약33.91GiB, 실제 PG 예약9건 대조, network-none PLAN/AUTH와 파생 원장 import를 통과했다. 동일 allocation/approvalId/PG authority·US$18/LLM92/Embedding70 상한을 승계했고 첫 PF1a와 Legacy 색인은 재전송하지 않았다. 실행 파일582개는 현재 소스와 SHA 일치했다. 제품·평가 기준·예산 정책 소스 변경은 없다.

| 문항 | 실제 HTTP/Tool/RAG | 답변 의미·충분성 | 결과 |
|---|---|---|---|
| PF1a | 이전 실제 응답 재사용 | 입력75000 안에 fee75, 추가0, 순입력74925; 주문/Receipt와 별도 검증 | 내용 PASS, 과거 기한 실패 보존 |
| PF1b | 200/MIXED/Quote·Order·Transaction·Receipt SUCCESS | fee 중복차감 없음, 계산출력0.999와 실제 FILLED/SUCCESS·MATCH 분리 | PASS |
| PA2a | 200/MIXED/현재 시장 Tool SUCCESS | SIMULATED 공급자·합성 거래량·현재 Tool의 차트구간/거래량 숫자 부재 설명 | PASS |
| PA2b | 200/MIXED/현재 시장 Tool SUCCESS | 사용자 체결·특정 사용자/봇을 추측하지 않고 공급자 관측과 체결가 구분 | PASS |
| PC4a | 200/MIXED/ADMIN listAbnormalOrders SUCCESS/RAG 성공 | WAITING_LONG·limit5·120초·첫페이지 범위는 맞지만 생성 근거 검증 실패로 fallback | FAIL, 최종 PARTIAL |

이번 세 의미 검토는 각각33,868/44,763/45,350ms로 동일300,000ms 안에 완료했다. UTF-8/검토 기한 오류는 재발하지 않았다. PC4a는 그 뒤 서버의 기존 필수 assertion에서 거절됐으며 의미검토 파일을 승인해 우회하지 않았다. 실제 전체57중5개 HTTP 응답 확보(내용 PASS4/FAIL1), 나머지52 NOT_RUN이다. PC4b·동결30·C3·반복12·유료Chrome6은 실행하지 않았다. 기존 무과금 결과를 이 실제 품질 결과에 합산하지 않는다.

### PC4a 확인된 원인 구간과 미확인 부분

runId `00798811-bbf5-4d61-826c-e3693c66a552`에서 Tool 약92ms, Embedding1342ms, 검색1389ms(Embedding 포함), LLM4824ms, 근거 검증0ms/INVALID_AGENT_EVIDENCE, Agent 약6408ms를 관측했다. 검색시간과 Embedding시간은 중첩이므로 합산하지 않는다. 공급자 응답·토큰 사용량은 정상 관측됐고 `AgentEvidence.validate` 이후 `SYNTHESIS_UNAVAILABLE` fallback으로 전환됐다. Timeout, 호출 제한, Windows 포트 중계나 UTF-8 문제가 아니다.

Tool 실제 적용값은 filter WAITING_LONG/limit5/waitingSeconds120/scope FIRST_PAGE_NOT_FULL_HISTORY/hasMore false/items2다. 권한·필터 실행 자체를 생성 실패와 구분한다. 다만 검증 전 Generated의 원문(facts/citationIds 등)은 기존 경로에 영속 보존되지 않았다. 따라서 잘못된 pointer/값/인용 중 무엇이 원인이었는지는 확정할 수 없으며, 그 중 하나로 추측해 validator를 수정하거나 완화하지 않았다. 실제 사용자에게는 조회된 범위만 fallback으로 제공했고 허위 거래 실행이나 금전 관계 오류는 관측하지 않았다. 이는 전체 보안/자산 안전성 보증은 아니다. 별도network-none/키없는Java단독시험은실제HTTP응답을재사용해필터계약PASS와동일SYNTHESIS_UNAVAILABLE assertion거절을결정적으로재현했다. 이시험의PASS_EXPECTED_REJECTION은제품C4품질PASS가아니다.

이전 통제 재개 프로토콜은 오직 원래INDEX_LEGACY+PF1a prefix에만 검증돼 있다. 새 STOP은5응답/17예약 prefix이며 원래 claim도 소비됐다. 이를 원래 prefix라고 위장하거나 STOP/claim을 삭제해 다시 실행하지 않았다. PC4a의 필수 품질 실패를 해결했다고 표시할 수 없고 새 prefix의 안전 승계도 아직 검증되지 않아 후속 유료 전송을 중단했다. 임의 새 예산/추가 유료 재시도는 없다.

### 누적 비용과 후속 무과금 감사

원래2LLM/7Embedding을 포함해 누적 **LLM6/92, Embedding11/70**,17예약 모두 OBSERVED/UNKNOWN0이다. 보수 예약 **US$0.343083460**, 전액 예약 유지 후 잔여 **$17.656916540**, 남은 호출86/59다. 실제 관측 토큰은 LLM입력21,927/출력2,495, Embedding24,593이며 현재 보수 단가로 계산한 사용량 기반 상한추정은 **$0.085249360**이다. 청구서 확정액이 아니고 예약액 차이를 환불하거나 예산에 중복 가산하지 않는다. 공식 Terra/Embedding 문서로 현행 모델과 단가 envelope를 재확인했고 실제 승인 경로의 DNS/TLS/계정 metadata 점검이 통과했다.

원래 실행의 JUnit FAIL/최종 fingerprint 미완료는 보존했다. 별도 `post-stop-readonly-audit-02`에서 키·공급자 호출 없이 실제 Spring/PG/Anvil의 금융9테이블/C fingerprint 불변, JUnit1PASS/0SKIP, PG↔파생SQLite17예약 일치, 부모·파생 원장/STOP/session.json 6SHA불변을 확인했다. 첫 감사 보조 스크립트는 child에 없는 paid-config 참조로 기동 전에 실패했고, 원본 owner 설정을 읽기 전용 복사하는 경로로 수정한 별도02감사에서 통과했다. 과거 실패 폴더는 보존했다. 관련 시험 자원은 모두 정상 정지했고 기존 자원 삭제는 없다.

25 worktree HEAD/root8미커밋내용/rootHEAD/AI HEAD d52b29f/staged0은 보존 대조했다. 이번 실행에서 제품 코드 수정·Git commit/push/merge/배포는 없다. 전체 실제 모델 인수는 **PARTIAL**, C Vault 실제 품질은 **NOT_RUN**이다.

최종독립실행감사:발견된새필수수정없음. 검토자가작은실행증거/3개검토receipt/readonly SQLite집계/후속ROJUnit1PASS·금융C동일/PG대조요약/부모·파생6SHA/동일allocation·approvalId·authority·fixture/STOP·claim/582실행소스SHA를직접대조했다. 품질기준완화나STOP/claim우회증거없음,5응답·4내용PASS·1FAIL·52NOT_RUN·전체PARTIAL구분이타당하다. 실제공급자/키/Docker실행/대형state원문읽기는검토자가하지않았다. root private `paid-carry-final-20261108.json`은실행직후이고`paid-carry-closeout-20261108.json`은새결과문서추기후의별도snapshot이다. 이독립검토결과를옮긴기록전용문장은closeout생성이후추가됐고실행코드변경은없다. Generated정확필드미확인은그대로유지한다.

## 2026-11-08 동일 누적 예산 재개 구현 — 무과금 검증 PASS / 실제 재개는 RAM 차단

사용자는 원래 STOP/원장을 보존하고 첫 실제 응답을 재사용해 나머지56문항을 같은누적18달러/92LLM/70Embedding으로 진행하도록 명시 승인했다. 새 `ai_pilot_continuation`/`ai_pilot_carry_launcher`/`ai_pilot_review`와 기존 sender·schedule·inner·staging 연결을 보완했다. 제품 금융/인증/Oracle/Timeout과 동결 질문·정답·예산 정책 JSON은 변경하지 않았다.

### 해결한 검토 및 승계 절차

- 실제 PF1a 원문과 Tool/Receipt 근거를 재사용했다. 공유 ReviewGate의 UTF-8 읽기/출력, 증거 SHA 결정, 동일300초 monotonic 기한을 통해 **54,905ms에 재검토 PASS**, 추가 공급자 호출0이다. 과거363초 실패와 늦은결정파일은 삭제/수정하지 않았다. 마감뒤에 나타난 decision이나 기존decision 선주입도 거절하는 회귀를 추가했다.
- 재개는 같은 allocation UUID·approvalId·PG authority·전체 상한을 유지한다. 기존INDEX_LEGACY완료+PF1a완료,9예약모두OBSERVED/UNKNOWN0인 정확한 prefix만 허용한다. PG id/kind/cost/state 일치, 원본3파일SHA, 동결 기대값/기준과재검토receipt를permit에 결박한다.
- 원래SQLite/STOP은readonly로마운트한다. 새파생SQLite에reservation/submission/activity/phase를모두복제하고 새source identity와파생control만승인해연다. 기존원본을reset하거나STOP을지우는방식이아니다. 부모의배타적CONTINUATION_CLAIM으로형제재개를차단하며,키없는network-none승인gate뒤에만유료실행을허용한다. PG초기화API/새비용DB는사용하지않는다.
- 모의전체재개시험에서PF1a본문을다시전송하지않고56HTTP요청/그중6DOM어댑터호출만수행했다. 기존예약액/호출량을승계하고최종누적PGdouble/SQLite일치·동일quota를검증했다. 이는실제56문항품질이나실제Chrome6의새실행이아니다.

### 실제 실행 결과와 차단

`continuation-49a7a2ab3c5a9791`은CARRY_PREPARE_ADMISSION에서Windows가용RAM1,134.11MiB로기준1,920보다785.89MiB부족해차단됐다. 디스크약34.23GiB는충족했다. 후속표본은1,187.85→1,555.67→마지막1,482.55MiB(437.45부족)였다. 사용자앱종료·전역설정변경·Guard완화나동등조건무한재실행은하지않았다.

이시도에서는PG/Anvil기동·실제PG현재예약대조·Linux PLAN/AUTH·원장import·claim·키접근·추가유료전송이모두NOT_RUN/0이다. 새원장·새예산도발행하지않았다. 원래CONTINUATION_CLAIM은존재하지않고원래STOP은유지됐다. 따라서승계로직의순수시험PASS와실환경승계완료를구분한다.

### 검증 범위와 최종 상태

구현자표적회귀는Python55PASS/Windows파이프1SKIP(신규carry/review7+기존schedule6+sender8+adapter20+Linux14)이며독립초기검토자는이중41PASS/1SKIP를직접실행하고필수수정없음을보고했다. 초기시험에서SQLite context manager만으로connection이닫히지않아Windows임시파일lock이남는오류를관측해명시close로수정했다. claim중복의실제예외와시험기대도대조했다. 기존전체통합PASS/유료첫답변은불필요하게반복하지않았다.

실제완료범위는여전히 **PF1a1/57**,남은56/F1동결질문/A2/C4/C Vault품질·반복12·유료Chrome6은NOT_RUN이다. 현재누적은LLM2/92·Embedding7/70·UNKNOWN0·보수예약0.082547760달러,잔여17.917452240달러·90/63호출로변경없다. 관측토큰기반비용상한추정0.022044620달러는청구서확정액이아니다. 전체인수PARTIAL_RESOURCE_BLOCKED이며준비완료나실제재개성공으로표시하지않는다. 다음실행은메모리조건충족후현재source의carry prepare부터필요하다. Commit/Push/Merge/배포없음.

최종보호대조:구현자가25 worktree HEAD/root8미커밋내용/rootHEAD/AI HEAD d52b29f/staged0·기존제품main dirty SHA·원래SQLite/STOP/session.json SHA불변을확인했다. 독립재검토1회는공개기록·보존manifest·작은receipt/proof의54,905ms/300,000ms/provider0/원응답재사용·RAM admission·원본3SHA일치를직접대조했고새필수지적없음이다. 초기접근권한거절은명시적읽기승인후해소됐고ACL변경/복사우회는없다. 검토자출력으로명확하지않았던receipt SHA와claim/import미생성은독립확인범위에서제외하며구현자확인과구분한다. 개인회고는ignored local파일에만추가했다.

## 2026-11-07 RAM 확보 후 실제 PAID 1요청 — PARTIAL / 운영자 검토 대기 초과로 STOP

최종 독립 실행 감사:발견된추가필수수정없음. 검토자는작은실행증거·SQLite읽기전용집계·PG감사결과·후속ROJUnit/실제fingerprint·두보존manifest를독립대조하고diff검사를수행했다. 25HEAD/root8내용/기존제품변경/원본trial4SHA불변,HEADd52b29f/staged0·소유실행자원정지를확인했다. 검토자직접모델/키/Docker실행이나대형state원문읽기는없다. PF1a내용PASS와300초검토게이트FAIL/전체PARTIAL·나머지56NOT_RUN구분을확인했다.

이번에는 실제 공급자를 사용했다. 사용자 승인 US$18/LLM92/Embedding70/동결57요청은 유지했다. source `f44771b94e2ecee17607a5a3eff020a20225eec0494862788618019c7c4bec4c`, owner56ce1e0e1611a222/probe7133c8e1135f97e3에서 최종 소스 PREPARE와 Sandbox Chrome1DOM/금융C/PG↔SQLite 무과금 probe를 통과했다. 이전51/C3/12/Chrome6는 반복하지 않았다. PAID입장1회는예산배분전RAM21.72MiB부족으로차단돼기록을SHA와함께별도이름으로보존했다. 자연회복3259.80MiB뒤동일후보/permit로진행했고새예산을재부여한것은아니다.

### 실제 모델 품질과 범위

| 항목 | 판정 | 실제 근거 |
|---|---|---|
| 공급자 DNS/TLS·계정 모델 metadata | PASS | 고정 두 모델 GET200/id일치, 첫POST전1851ms; 승인 경로에서만 키 사용 |
| Legacy9문서/88Chunk 실제 색인 | PASS | 실제 Embedding6배치, 실제pgvector색인; 합성벡터아님 |
| PF1a 답변 내용 | PASS 사후 내용검토 | ANSWERED/MIXED,4ToolSUCCESS/검색1/모델2,Agent측10839ms |
| PF1a 운영자 검토 기한 | FAIL | 300초 내 결정파일 미작성, V2_SEMANTIC_REVIEW_REQUIRED |
| 나머지56요청·A2/C4/C표적·반복12·유료Chrome6 | NOT_RUN | STOP 뒤 추가유료전송하지않음 |
| 전체실모델인수·제한파일럿·서비스준비 | PARTIAL / NOT_RUN | 1문항 내용PASS를57문항/운영서비스PASS로확대하지않음 |

PF1a는75000입력에75수수료가포함되고추가차감0/순가격입력74925임을Tool amountRelation과일치하게설명했다. 잠금과정산차감을구분하고FILLED/CONFIRMED/ReceiptSUCCESS·이벤트MATCH를실제로조회했다. 본인잔고를추측하지않았다. 이결과는표적PF1a1개이며동결30의F1이나PF1b/A2/C4/C품질을대신하지않는다. 인용은승인USER정책/Tool근거이며모델입력에동결기대답변을주입하지않았다.

### 최초 중단 원인과 수정

로컬 비공개 검토 helper가Windows기본cp949로UTF-8 JSON을읽어UnicodeDecodeError가발생했다. 이를UTF-8로고친뒤내용을검토했으나,운영자처리·기록이늦어졌다. 요청파일UTC06:26:33.168262→결정파일06:32:36.719165,차이363.550903초다. 제품LLM/Agent응답Timeout이아니며평가용의미검토300초기한을넘긴운영실패다. 뒤늦은accepted=true파일은보존하지만정상기한내게이트PASS로계산하지않는다. private helper의UTF-8읽기/stdout와실제session내metadata proof경로를보완하고읽기전용status/pending으로검증했다. 공개제품소스/정책/Timeout/평가기준은변경하지않았다.

STOP을지우거나새배분으로우회하지않았다. 기존런처는중단session자동재시작을금지하므로전체평가는여기서중단됐다. 다음에는완료PF1a를재전송하지않고동일PG권한·누적예약을승계하는통제재개방식이필요하다. 이는현재검증된v2재시작기능이아니며자동구현/유료실행하지않았다. 현재승인액은새18달러로다시초기화되지않는다.

### 사용량·비용·안전성

- 실제유료POST: LLM2/92,Embedding7/70. 별도metadataGET2. 모든9예약OBSERVED/UNKNOWN0,PG/SQLite의id/kind/cost/state대조일치.
- 관측토큰:LLM입력5134/출력727,Embedding24281. 보수예약토큰은각22781/2000/79763이다.
- 보수예약총액 **US$0.082547760** 유지. 예약을공제한한도잔여 **US$17.917452240**,호출잔여LLM90/Embedding63. 관측토큰에동결최대유효단가를적용한비용상한추정 **US$0.022044620**이며청구서확정액은아니다. 캐시별상세청구·invoice는미대조다. 예약차액을임의환불하지않았다.
- 원래PAID JUnit1FAIL/최종financial관측UNKNOWN을보존했다. 후속키없는내부망읽기전용PREPARE감사에서JUnit1PASS/0SKIP와금융9테이블·C account/position기준hash동일을별도확인했다. 원래UNKNOWN의상세관측실패원인은미확정이며후속성공으로이력을덮어쓰지않는다.
- 두후속감사에서PAID SQLite/STOP SHA전후불변,원장쓰기/재전송/환불없음. 소유PG/Anvil/egress/app은정지했다. 새유료호출·키읽기없는감사와실제PAID모델실행을구분한다.

공식가격은[Terra 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.6-terra),[Embedding 문서](https://developers.openai.com/api/docs/models/text-embedding-3-small)로다시확인했다. 이전보수단가/서비스tier/default·regionalOFF·cachewrite상한은유지한다. 독립감사및보호manifest의최종결과는후속기록으로추기한다. Commit/Push/Merge/배포는없다.

## 2026-11-07 실제 PAID 승인 후 실행 시도 — PARTIAL / 자원 차단 / 유료 전송 0

종료감사:최종독립재검토2는필수수정없음이다. 검토자직접Environment21/Sender8/Adapter20PASS·Windows파이프1SKIP·diffPASS,실제작은자원/정지증거와보호manifest를대조했다. elevated권한의socket시험은WinError10014였으며동일소스기본권한21PASS와구분한다. 25HEAD/원본8내용/기존제품dirtySHA/원본시험원장·session·state·fingerprint4SHA불변,staged0/HEADd52b29f다. 종료가용RAM2324.65MiB·디스크35.45GiB는idle샘플이며直前실행의Chrome직전부족을해소했다고주장하지않는다. 검토자실환경재기동/공급자/키접근은없다.

사용자는 US$18/LLM92/Embedding70·기존57요청을 명시 승인했다. 승인은 소멸하거나 재초기화된 것이 아니지만 실제 PAID allocation/permit 발행/키 mount는 아직 하지 않았다. 공식 모델 문서에서 Terra uncached input2.00/output12.00달러/백만token과cache-write1.25배,Embedding3small0.02달러를 확인했다. 기존input2.50 보수 envelope·전체17.168384예약계산·단계별한도·동결문항은 유지한다.

최초 실전송 전에 실제 계정의 두 모델 권한을 확인하는 결손을 평가 전송기에 보완했다. PG/SQLite 이중예약 뒤에만 키를 읽는 기존 경계를 유지하며 고정relay/종단TLS의 모델metadata GET2개가 성공해야 첫유료POST가 가능하다. 기존deadline을공유하고실패는재시도/임의예약해제하지않는다. 독립검토에서실패코드유실을지적해고정모델/HTTP상태/allowlist사유/경과시간만남기도록수정했다. 실제권한/TLS검증자체는아직NOT_RUN이다.

새후보47969595d3c0c21f의PREPARE는실제Spring/JWT/소유권·fixture준비PASS였고source3f146906…에결박했다. 이후probe-0bab10d64731c262에서입장2301.63/앱직전2145.86MiB는1920기준을통과했으나Chrome직전1564.43MiB로355.57MiB부족해차단됐다. Linuxheadroom2377.19MiB는충족했으므로이번차단의직접원인은Windows측가용물리RAM이다. 디스크35.46GiB는충족했다. 앞선두입장차단과자연회복도증거로보존했고사용자프로그램/WSL전역/Guard변경은없다. 같은조건의반복실행은중단했다.

이새UNPAID probe는Legacy색인6Embedding OBSERVED/UNKNOWN0,PF1b는미완료이며Chrome제출0이다. STOP존재·소유컨테이너정지를확인했다. 호스트Guard가child에게거절응답을주지않고먼저예외를던져inner종료기록과최종fingerprint가미작성된시험하네스결함도확인했다. allowed=false를전달하고최대75초의종료예산으로기존정리경로를기다리도록최소수정했다. 이는실제결함이발생한경로의오프라인수정이며수정후실환경재기동은NOT_RUN,이probe의최종금융/C fingerprint대조도NOT_RUN이다. 원본보존과이누락을혼동하지않는다. 소스변경으로기존후보는source검사상재사용불가하며덮어쓰지않았다.

최종표적Python49PASS/Windows파이프1SKIP(environment21+sender8+adapter20)이다. 기존실제51/C3/12/Chrome6성과는그대로보존하며새유료실행성과로합산하지않는다. 새실모델57요청/F1/A2/C4/C품질·공급자지연·유료웹6요청은전부NOT_RUN이다. 실제API전송/키읽기/청구는0,승인18달러미사용,전체서비스PARTIAL이다. 다음실행은서버·브라우저직전까지1920MiB를유지하는자원환경과최종수정소스의준비/probe확인이선행돼야하며새유료예산을자동부여하지않는다.

## 2026-11-07 PAID 전체 런처·실제 Spring/DOM 연결 — READY_FOR_APPROVAL

이전PARTIAL의실행기결손을새 `ai_pilot_paid_launcher/inner/adapter`와시험전용 `PilotPaidServer`로연결했다. 최종후보de2444432a1a7605/probe-52457f62027754cd에서실제Spring/JWT/PG/Anvil/Tool/RAG/SandboxChrome의색인1회+PF1b1DOM·금융/C fingerprint·PG/SQLite대조·소유정지PASS다. 기존51/C3/12/Chrome6를전체반복하지않았다. 실제공급자/키/PAID활성화0,실모델품질NOT_RUN,서비스전체PARTIAL은유지한다.

실제source `4ef7aabf9fab6b20fb23052432512bc1440ae6c38fe997e2fec6a802c1b44794`,Spring각prepare/probe1PASS/0SKIP,shadow LLM1/Embedding7/UNKNOWN0/STOPPED,PAID DB3개테이블0/noPAIDsession이다. Windows기동샘플3267.19/3162.27/2614.74MiB·Linux2377.34MiB로1920Guard유지;종료1890.74는추가기동을허용하는수치가아니며연속최소치보장은아니다. 신규snapshot138,856,153byte/SHA별도보존,원문출력없음이다.

실행중발견한64byte DB명절단은63byte사전검사/짧은이름으로수정했고기존실패원장·scope검사를보존했다. LinuxGuard차단은관측을추가하고소유cgroupcap만3072MiB로맞췄으며JVM384/256·정상Timeout/금융/인증/비용정책은불변이다. 초기독립검토3건+종료판정지적을수정·주입회귀했고,최종실행증거재검토는아래후속기록으로구분한다. 실제증거기반readiness가READY_FOR_APPROVAL이며단순플래그변경은없다. 상세실패/정확한미관측한계·실행/승인절차는[PAID 런처 Runbook](PAID_LAUNCHER_RUNBOOK.md)에기록했다.

최종 독립 재검토2는 발견된 필수 수정 없음이다. Python46 PASS/Windows 파이프1 SKIP·Node4 PASS·diff 검사는 검토자가 직접 실행했고, 실제 source/artifact/image identity·1DOM/Sandbox·금융/C·PG/SQLite·PAID 미활성·원본 보존은 기존 실행 증거를 독립 대조했다. 서버 재기동이나 실제 공급자·키 접근을 독립 실행한 것은 아니다. 무과금 실행 연결의 READY_FOR_APPROVAL과 실모델 인수 NOT_RUN/전체 서비스 PARTIAL을 구분한다.

## 2026-11-07 PAID 실행 경계 준비 — PARTIAL / 유료 전송 0

기존 무과금51/C3/12/Chrome6 PASS는 재실행하지 않았다. 새 고정 CONNECT 전송·기본거절 relay, PG namespace localhost topology, 새 PAID 일정 coordinator와 단위 회귀를 추가했다. `pilot-paid-cdc5634f21f99140`의 실제 Linux PG protocol/SQL·중계403·소유3컨테이너 종료는 PASS다. provider/API key/승인 활성화/Git 반영은0이다. 동결 질문·정책과 제품 금융/인증/Timeout/비용 정책은 유지한다.

전체 PAID 실행 준비는 아직 PARTIAL이다. 실제 PAID 서버/DOM adapter의 결박·전체 supervisor는 미완료이며 blueprint 자리표시자를 실행 명령으로 보고하지 않는다. 실제 공급자 DNS/TLS/권한/가격 확인도 NOT_RUN이다. 따라서 US$18 승인만으로 즉시 실행 가능하다고 주장하지 않는다. 기존 preflight exit2/ready=false를 유지한다. 완료 범위·테스트·비공개 출력 최소화·남은 경계·동결 제안92/70회/$17.168384는 [PAID 실행 경계 기록](PAID_EXECUTION_BOUNDARY_2026-11-07.md)에 구분했다.

## 2026-11-06 잔여 Linux Chrome6 — 무과금 연결 PASS / 실제 서비스 PARTIAL

종료 감사: 최종 독립 재검토2는 추가 필수 수정 없음이다. 검토자가 직접 Python19(7resume+6retry+2권한+4probe)/Node7/diff PASS, 실제6요청·인증·같은브라우저Sandbox4renderer·source/driver14파일 SHA·financial/C·SQLite134 OBSERVED/WEB6 complete/exited·상태파일streamingSHA·RAM Guard를 대조했다. 실제 Chrome6를 독립 재실행하지는 않았다. 종료 wrapper의 CLI135초/Anvil grace120초 정합성과 불명 종료 재시도 금지는 Mock2로만 최종 검증했으며, 성공했던 실환경의 빠른 exit0와 구분한다. 이 terminal-only 보완 때문에6요청을 다시 실행하지 않았다.

현재 제품 backend/src/main 394파일은 실제 실행된 원본 source manifest SHA와 모두 같다. 동결 질문·lock·예산/실행정책5파일 SHA, HEADd52b29f/staged0 유지다. 시작 시 git-list 출력과 최종25worktree HEAD가 일치하고 원본 미커밋8목록도 같다. 보호8파일의 수정시각은 모두 이번 작업 전이다. 다만 이전 세션 메모리의8개내용SHA 기준은 불러오지 못했으므로 이번 종료 감사에서8개내용의 SHA 전후 일치를 새로 증명했다고 주장하지 않는다. 원본8파일 대상 쓰기 명령은 없었다. 새공개후보16파일 비밀패턴 검사0건/Gitleaks미설치이며 절대무유출보장은 아니다. private runtime/원장/개인회고는ignore 유지다.

추가 진단 한계: 독립 검토 중 합성 시험 체인 원문 대용량파일이 도구 출력에 포함됐다. 파일변경/실제사용자키/API key접근은 없었으나 private evidence의 불필요한 원문 출력이었다. 이후 크기·streamingSHA만 확인했고 원문을 공개문서에 복사하지 않았다. 아래 시험JWT 출력 사례와 함께 로그 최소화의 한계로 보존한다.

### 완료 결과와 실제 실행 소스

기존51요청/C3/반복12는 재실행하지 않았다. 정지된 소유 시험 eeb…의 PG를 readonly로 신규 볼륨에 복제하고, 원본125예약·allocation·WEB 단계·동결6본문을 승계했다. 이후 복구는 같은 복제 PG/SQLite 원장을 직접 공유하며 다시 복제·초기화하지 않았다. 최종 `pilot-linux-ecb0de1a59d4e9f2/attempt-7deb1cff682a4143`에서 실제 Linux Chrome156/Node22.22/Java21/Spring/PG/Anvil로6DOM→JWT→Agent→Tool/RAG→통제 공급자→화면 검증 PASS, JUnit1 PASS/0SKIP이다.

제품과 기존 coordinator는 원본 staged `/work` fingerprint `2802396e5ffcc10d2a2d0ee90742505f6864ecaef3dab1e9e7f8f0d61093e931` 그대로다. 신규 전용 Java/Node 드라이버는 `/driver`에 분리하고 source/class 파일별 SHA를 `driver-files.json`에 기록했다. Linux admission에서 원본 source-files2103개 및 신규 driver 전파일을 각각 대조했다. 현재 worktree의 추가 코드는 실행·관측·회귀용이며 제품 금융/인증/Timeout/비용 정책 변경은 없다.

| 실제 브라우저 항목 | 결과 | 근거 |
|---|---|---|
| 두 계정 로그인·본인 조회 | PASS | 로그인200×2, me200×2, 각 계정 Tool principal 분리 |
| PA2a/PF1b/PCa × 두 계정 | PASS 연결 | 실제 POST /api/ai/agent/answers 200×6, 원장 WEB6 complete/worker_exited |
| 시장·Quote·C Vault | PASS 연결 | 각 USER의 시장/견적·주문·Receipt/C Account Tool SUCCESS, 기존 strict verifyReply |
| RAG·화면·불확실성 | PASS 연결 | USER/domain·출처/인용 DOM 수·본문/uncertainty 표시 assertion, 실제 캡처6개 |
| 타인 조회·계정 전환 | PASS | own Quote SUCCESS 후 foreign404/RESOURCE_NOT_FOUND, C외부pos403/404; 로그아웃/전환/마지막 로그아웃 ai-result 공백 |
| 금융·C 정본·원장 | PASS | 원본9테이블 SHA 및 두C계정/포지션 value SHA 동일, PG/SQLite 일치 |
| 실제 모델·의미 검색 품질 | NOT_RUN | 통제 LLM/합성벡터, HTTP200/PARTIAL을 품질 PASS로 계산하지 않음 |
| 실제 사람 효용성·운영 SLA | NOT_RUN | 자동6요청/humanSatisfaction=false, 실사용 만족도나 실제 공급자 성능 아님 |

캡처 pilot-0~5를 실제 확인했다. 한글/시장 설명·수수료 포함 관계·C와DB 장부 분리 문구가 보이며 두 계정 이름이 바뀐다. C 잔고는 fixture상 두 사용자에게 같은 값이므로 화면 금액 비교만으로 소유권을 증명하지 않았다. 실제 Tool principal·foreign 조회 차단·DOM 초기화 검증을 함께 사용한다. 캡처는 viewport이며 아래쪽 인용 전체의 시각 검증 대신 기존 DOM 인용 수/내용 검사를 구분한다.

### 정상 Sandbox·RAM·보존

Playwright v1.56.1의 고정 seccomp profile(SHA `d4906af343fd3bdf044a774ff6969bc506fcd141dab5acc8d02d8323e9991c44`)을 사용했다. default ERRNO를 유지하고 user namespace용 clone/setns/unshare만 지원하며 nonroot1000/default Chrome Sandbox, 내부 network, 추가cap/privileged/hostIPC/port공개 없음이다. 실제 **같은 파일럿 Chrome**의 별도 chrome://sandbox 탭에서 Layer1 Namespace·PID·Network·Seccomp Yes와 활성renderer UID1000/NoNewPrivs1/Seccomp2/CapEff0을 확인했다. 별도 capability만으로6요청 성공을 대체하지 않았다. 이는 Sandbox 전체 취약점 감사는 아니다. 공식 근거: https://playwright.dev/docs/docker .

Windows 가용RAM 표본: admission2841.36MiB, 앱직전2425.30MiB, 브라우저직전2265.78MiB, 종료1974.77MiB. 각 기동 Guard1920MiB와 디스크2GiB를 그대로 통과했다. Linux1920MiB 검사도 유지했다. 디스크 약37.84GiB. 이는4개 단계 표본이며 연속 RAM 최저치로 주장하지 않는다. 사용자 앱 종료/전역 WSL·Docker 변경/Guard 완화는 없다.

최종 PG/SQLite 총134예약(LLM80/Embedding54), 모두 OBSERVED/UNKNOWN0이다. 이전125에서 새 LLM6/Embedding3만 증가했고 기존 단계 submission51은 그대로다. 동일 질문 순수 Embedding 캐시가 재사용됐지만 실제 공급자 비용·지연 절감 측정으로 확대하지 않는다. 총3,797,697,540nUSD는 무과금 shadow 예약이며 **실제 공급자 비용0**이다. 실제 API key/metadata/LLM/Embedding 전송·유료 활성화0. 원본 eeb SQLite는 첫 시도 전 복사본 a1a97…와 SHA도 동일하다. 과거 paid 원장/STOP을 재사용하지 않았다.

금융 DB SHA `5b0cd5afd77a8eb1ea1e1d6e0d2799c62a2db5d83e8297659f6bbe867b2d16cd`, C value SHA `217c105142d32ce51158ce0956703e87ee6e2f931fcb9c572d80f68250699783` 전후 동일. 새 체인은 금융 거래 없이 읽기만 수행했다. 최종 Anvil 정상종료 exit0/OOMfalse, Linux 출력 볼륨과 별도 보존 파일135,764,955byte/SHA `bc9c197106bd0fca45dac366cc060ceb8136bff467b9b01e556a3714d1ff49e8`를 보존했다. 모든 이번 소유 실행 자원은 정지하며 이전 실패·잘린 새 시험 파일·원본·원장·볼륨을 삭제하지 않았다.

### 실제 시행착오와 최소 수정

1. 기본 Docker seccomp의 namespace EPERM과 분리해 공식 nonroot Sandbox profile을 시험했다. CLI dump-dom의 chrome://sandbox ERR_ABORTED는 관측 경로 실패다. CDP의 실제 body는 Chrome156에서 `Layer 1 Sandbox: Namespace`였고 옛 레이블 검사만 실패했다. 동등 레이블 parser는 모든 보안층의 No/누락을 계속 거절하며 과거 verified=false/POLL_TIMEOUT 결과를 덮어쓰지 않았다.
2. 독립 검토는 Worker.start 제거만으로는 시작 중 시장 event.signal을 막지 못함을 발견했다. test-only BeanPostProcessor가 노출/초기화 전에 실제 Worker를 close하고 start도 제거했다. 실제 Worker.signal 무동작·init 제거 회귀 PASS다. 타인 Quote의503/504를 권한 차단으로 오인할 수 있던 검사를 own SUCCESS/foreign 정확한404·ERROR·RESOURCE_NOT_FOUND/dataNull로 수정했다.
3. 내부 JDBC 주소 검사가 기존 connectTimeout2/socketTimeout5 옵션을 거절해 첫 시도는 컨테이너0에서 멈췄다. 정확한 기존 옵션만 허용하도록 URI 검증을 보완했다. 추가옵션·외부host·자격정보·중복옵션은 계속 거절한다.
4. 첫 실제 Chrome은 Sandbox 검증에 성공했지만 관측자가 BoundedCdp.close()를 호출해 Browser.close까지 실행했다. 소유한 검증 탭만 닫고 관측 socket은 dispose하도록 수정했다. 브라우저 수명 소유자와 관측자의 권한을 분리하는 회귀를 추가했다. WEB0/불명0을 확인한 같은 복제 원장만 재개했다.
5. 후속 LOGIN 대기는 처음에 세션 종료를 의심했으나 실제 HTTP 관측에 로그인 요청 자체가0이었다. 제품 JWT 결함으로 단정하지 않았다. HTML 요소 존재만 기다리던 드라이버가 module handler 연결 전에 클릭할 수 있었다. document complete 및 실제 onclick/onsubmit 준비를 기다리자 실제 로그인200×2와6요청이 통과했다. timeout/인증/정답을 완화하지 않았다. 관측은 path/status 및 WS frame 유형만 기록하고 토큰·헤더·로그인 응답 본문을 기록하지 않는다.
6. 재기동 중 **이번 소유 시험 복제본** state.json의 EOF를 확인했다. 정확한 잘림 순간은 미관측이며20초 종료/Windows I/O는 가설이다. 파일을 수리·삭제하지 않고 원본 checkpoint readonly 입력과 새 Linux 출력 볼륨을 분리했다. 신규 빈 출력만 root0:755→1000:1000/0700으로 초기화하고 실제 UID1000 쓰기를 확인했다. 기존 사용자·과거 손상 체인은 접근/수정하지 않았다.
7. 진단 grep에서 생성된 격리 시험 JWT 서명키1건이 도구 출력에 포함됐다. 공개 Git/문서에 값을 옮기지 않았고, 후속 복제 실행은 신규 시험키를 발급해 사용했다. 원본 보호 설정은 유지했다. 실제 사용자 키나 공급자 API key는 접근하지 않았으며 외부 포트/배포도 없다. 비밀정보 검사0건을 절대적인 무유출 보장으로 표현하지 않는다.

### 남은 경계와 유료 진입 판정

잔여 **Chrome 무과금 실사용 연결은 PASS**이며, 기존51/C3/12를 포함한 무과금 근거는 확보했다. 서비스 전체는 실제 모델 최신 품질·응답 속도·사람 파일럿이 미실행이므로 PARTIAL이다. 기존 F1 자연어 의미/A2·C4 표현별 라우팅·답변 충분성·최종30문항 실모델 재인수는 이 통제 시험으로 완료 처리하지 않는다.

유료 승인은 없고 이번 Chrome 전용 경로는 UNPAID만 허용한다. 또한 기존 evaluation TestConfiguration의 pg:5432 허용은 Linux UNPAID에 한정되며 PAID는 검증된 localhost 주소만 허용한다. 따라서 **이번 Linux 런처의 플래그만 바꿔 유료 실행할 수 없다**. 별도 승인 이전에 유료 실행기의 승인 identity/권한 있는 DB 접속 경로/이중 예약 연결을 별도로 확인해야 한다. 이 안전 경계를 넓히거나 유료 준비를 허위 PASS로 바꾸지 않는다. 이번 목표 밖의 유료 연결 개발은 시작하지 않는다. 예산18/92/70, 동결원문·정책·activationAllowed=false/STOP 및 실제 모델 NOT_RUN은 유지한다.

최종 표적 회귀 Python19/Node7 PASS, Java21 전용 드라이버 컴파일·실환경JUnit1 PASS/0SKIP다. 기존 NoWriter/정확한권한거부 Java3와 독립재검토1 결과는 별도 근거다. 최종 독립재검토2 범위·한계는 이 절의 종료 감사에 기록했다. main/원격/다른 worktree/기존미커밋 변경은 수정하지 않고 Commit/Push/Merge/배포0이다.

## 2026-11-06 혼합 부하 재검증 및 Native Chrome 후속 — PARTIAL

### 최종 후속 결과: HTTP/C/반복 PASS, Chrome6 NOT_RUN(실제 RAM 차단)

종료 검증: 최종 Python96+clone7=103 PASS, Java 표적13 PASS/0SKIP, Node7 PASS, diff --check PASS. Native 최종 재검토2는 추가 필수 수정 없음이다. 검토자는 직접 native10/Node7/diff 및 실제 Guard·C/LATENCY·fingerprint·source-copy 증거를 대조했으며, Chrome6·전체 원장·Docker 실제 상태를 독립 실행 검증한 것은 아니다. fixture 최종 재검토2도 추가 필수 수정 없음/직접 Linux14·diff PASS다. 구현자가 Docker 실제6Exited+extractCreated와 잔류 실행0을 확인했다. HEADd52b29f/staged0, Commit/Push/Merge/배포0 유지다. 검토 통과가 Chrome 미실행 또는 역사적 원인 미확정을 해소하지 않는다.

새 `pilot-linux-eebfad036324d31e`는 process-launch genesis/중복 host 시각설정 제거 후 C smoke와 두 시험 계정의 실제 LIQUIDATED/VERIFIED fixture 준비를 통과했다. 시각 샘플에서 observed=chain인 구간과 실제 상태 전이를 확인했다. 따라서 이전 Native fixture의 시간 gate 장애는 이번 실행에서 해소됐으나, 모든 Worker 내부 원인이나 과거 Receipt 원인의 확정은 아니다. source `2802396e5ffcc10d2a2d0ee90742505f6864ecaef3dab1e9e7f8f0d61093e931`, staged2103파일 SHA 대조 PASS, Java21 전체 test/helper 컴파일 BUILD SUCCESSFUL이다.

| 검증 | 결과 | 실제 범위 |
|---|---|---|
| C 첫 요청 | PASS | HTTP200/getCAccount SUCCESS/STATE113ms/model0 |
| Legacy6 + 동결30 | PASS 연결 | 기존 strict gate·원문·기대값 유지, 모델 품질과 별개 |
| C 표적3 | PASS 연결 | JWT/소유 증명/고정 RPC·Tool·C 정책·Legacy 자산 분리 |
| 반복12 | PASS 연결 | A1 6 + PF1b 6, TOOL_TIMEOUT 없음 |
| 금융/원장 대조 | PASS | 9테이블·두 C 계정/포지션 value 불변, PG/SQLite 일치 |
| Chrome6 | NOT_RUN | Node/Chrome 시작 직전 Windows RAM Guard 차단, WEB claim0 |
| FULL JUnit | FAIL | browser exit assertion, 전체 인수 PASS 아님 |
| 실제 모델 품질·유료 실행 | NOT_RUN | 승인/키/공급자 호출 없음 |

A1 HTTP ms564,528,479,443,443,394(중앙461/p95 564), PF1b590,449,432,447,376,474(중앙448/p95 590). 각n6/nearest-rank이며 통제 공급자 값이다. 최종 status는 각각 INSUFFICIENT_EVIDENCE/PARTIAL로 보존한다. Trace636개: DB최대10.29ms/privateDTO24.07ms/DB+입력DTO25.97ms/Receipt RPC9.70ms/block RPC4.71ms/mapping7.14ms/event0.69ms/Receipt전체13개 최대23.113ms. 과거3초 실패는 미재현이다.

신규 SQLite를 mode=ro로 사후 대조했다. 총125예약(LLM74/Embedding51) 전부 OBSERVED, UNKNOWN0, shadow3,417,092,400nUSD. INDEX2 및 HTTP51개 submission 모두 complete/worker_exited. WEB_PILOT submission은0이다. phase-SAFETY_C/LATENCY의 budgetMatching·financialUnchanged·cStateUnchanged와 final-fingerprint COMPLETE를 확인했다. 실제 공급자 모델/embedding 전송0은 UNPAID 통제 sender만 실행, app.ai.api-key 빈 값, 공급자 키를 제외한 자식 env, 내부 Docker network 및 Native UNPAID-only 검사를 근거로 한다. 공급자 계정/API key를 조회하거나 새 승인/원장을 활성화하지 않았다.

Chrome 직전 `native-resources.jsonl`: available1,498,050,560byte=**1,428.6523MiB**, 기존 기준1,920MiB 대비 **491.3477MiB 부족**, 디스크41.64GiB. ResourceError→host-failed를 기록하고 Chrome/DOM0으로 차단했다. 브라우저를 시작한 후 실패한 것 또는 Sandbox 실패로 분류하지 않는다. 한 차례 Native capability PASS는 실제6요청 PASS를 대체하지 않는다. Guard 완화·사용자 앱 강제종료·전역 WSL/Docker 설정 변경·근거 없는 재시작은 하지 않았다. 현재 환경의 객관적 자원 차단으로 동일 전체 시험을 추가 반복하지 않는다.

소유7컨테이너는 실제6 Exited + extract1 Created(미기동), Native ownedHandlesConfirmed=true/supervisor STOPPED다. 볼륨/새 시험 상태/기존 실패를 삭제하지 않았다. 원본25worktree HEAD와 보호8파일 SHA, 기존 평가4증거 SHA 및 동결 질문/정책 SHA 불변, HEADd52b29f/staged0 유지다. 이번 후속은 인프라·시험 하네스만 변경했으며 제품의 금융/인증/Timeout/비용 정책은 변경하지 않았다. 유료 실행 준비 완료 또는 READY_FOR_APPROVAL을 선언하지 않는다. 잔여 필수 항목은 기준 자원을 만족하는 정상 Sandbox 환경에서의 실제 Chrome6와 실제 모델 인수(별도 승인)다.

### 혼합 C/반복12 연결 PASS, 과거 Receipt 원인 미확정

새 `pilot-linux-a58bab662f8841d9`에서 기존 Legacy6→동결30→C3→반복12의 실제 Spring/JWT/Agent/PG/Anvil 연결51요청과 JUnit1 PASS/0 SKIP를 확인했다. 질문·strict Tool gate·Timeout·비용/권한/금융 판정을 변경하지 않았다. 단독Tool32 및 초기6만의 별도 시험은 반복하지 않았다. C3의 본인 소유 증명·고정 블록·Legacy와 C 자산 분리 검증도 통과했다.

| 통제 공급자 반복 | 표본 | HTTP ms | 중앙값 / 표본 p95 |
|---|---:|---|---|
| A1 | 6 | 519,517,488,449,474,423 | 481 / 519 |
| PF1b | 6 | 572,445,426,395,416,357 | 421 / 572 |

p95는 소표본 nearest-rank이며 실제 LLM 성능이나 SLA가 아니다. 최종 상태는 통제 응답 A1 INSUFFICIENT_EVIDENCE/PF1b PARTIAL이며 HTTP/연결 PASS를 답변 품질 PASS로 바꾸지 않는다.

Trace636개: DB_QUERY130개 최대9.13ms, private DTO26개 최대24.01ms, DB+입력DTO13개 최대25.50ms, Receipt RPC13개 최대8.51ms, block RPC12개 최대3.22ms, receipt mapping 최대6.84ms, event 최대0.86ms, Receipt 전체13개 최대19.42ms. 합성 미확정F4의 예상 NOT_FOUND1개와 MATCH12개를 구분한다. 과거3020/3022ms 장애는 이 혼합 부하에서 재현되지 않았으므로 DB/RPC/누수 등 역사적 원인을 확정하거나 제품 결함을 고쳤다고 주장하지 않는다. payload 없는 기존 단계별 Trace를 유지했다.

금융9테이블 및 두 C 계정/포지션 value fingerprint 동일. PG/SQLite124예약(LLM73/Embedding51) 일치, 전부 OBSERVED/UNKNOWN0. 비용3,350,212,400nUSD는 **무과금 shadow 계측**이지 실제 공급자 비용이 아니다. 실제 모델/키/활성화0. 실행 fingerprint `1f4aea89a047f6156d6b84ba7ffe8c228d484f8295f7c75178300e53a67fb192`, Linux-local source2092파일 SHA 대조 PASS.

### 실제 수정한 환경 결함과 실패 보존

앞선2464…는 lazy broker 초기화에서 실패했다. 당시 inner 오류가 보존되지 않아 정확한 원인은 미확정이다. 동일 공개 소스의 network-none RO 감사9b1a…에서 source fingerprint9136.53ms+preflight517.30ms+workload542.88ms가 기존10초 startup 경계를 넘는 조건을 관측했다. Linux-local 복사9e59…는 각각66.27/147.33/106.94ms였다. n1·캐시 미통제 진단으로 성능 보장을 하지 않는다. `--local-source`와 manifest 전파일 검증으로 Windows bind I/O를 피했고, startup Deadline은 늘리지 않았다. safe cause chain 및 private stderr 보존도 추가했다. a895…의 Windows Gradle daemon 연결 실패는 후속 정상 빌드와 구분하며 원인 확정하지 않는다.

Native Chrome은 기존 Linux container Sandbox EPERM과 분리해 Windows의 새 프로필/default Sandbox를 사용한다. loopback TCP→소유 app의 Docker exec→Linux localhost로 원시 HTTP를 중계하며 응답 주입/기존 Chrome 프로필 사용/권한 확장은 없다. capability cbe5…는 실제 활성 renderer의 restricted NT token·low/untrusted integrity를 확인했다. 이는 Sandbox 전체 보안 감사나 Chrome6 완료의 대체가 아니다. 독립 검토가 최종 ACK 후 조기 종료와 mutable worktree 실행 결함을 발견해 최종완료 marker 및 staged source 실행/전파일 SHA 대조로 수정했다.

Native FULL b285…와 ceba…는 C smoke 후 Legacy fixture liquidation100초에서 실패하여 Chrome6 NOT_RUN이다. b285의 RO PG 감사dcd7…는 두 번째 포지션 GRACE·pending없음·기존6tx CONFIRMED·LIQUIDATE op없음, 종료 체인이 deadline을41초 지난 사실을 확인했다. 정확한 Worker 내부 이유는 미확정이다. ceba에서는5초 간격 Trace로 mark.observed가 chain보다1~2초 앞서 observed<=chain 검증이 막힌 것을 관측했다. wakeup만으로 해결되지 않았으며 이는 Receipt 장애 원인이 아니다.

후속은 새 빈 소유 체인에서만 process-launch genesis를 계산하고 호스트 timestamp 재설정을 제거한다. 기존 시각 readiness·block1·가격·60초 grace·100초 await·TTL·금융 assertion은 유지한다. fixture의 wakeup은 기존 Worker.signal만 사용하며 실제 LIQUIDATED/VERIFIED를 요구한다. 이를 제품 자동 scheduler liveness 검증으로 확대하지 않는다. 실제 후속 결과/Chrome6는 별도로 기록한다. 기존 실패와 원장/STOP/비공개 데이터는 보존하며 Git Commit/Push/Merge는 없다.

## 2026-11-05 원래 Agent 초기6 + 180초 유휴 — 연결 PASS / 전체 PARTIAL

종료 감사: 최종 독립 재검토2는 추가 필수 수정 없음이다. 검토자 직접 Linux11/clone7/diff PASS, 실환경·private 원장은 정제 근거 대조로 구분했다. clone2560MiB 설정도 후속 검토 범위에 포함됐다. 원본25worktree/보호8파일과 기존평가4증거 SHA가 최종에도 동일하고 HEADd52b29f/staged0/비공개ignore를 확인했다. 새 Agent-prefix 자원의 실제 Docker 상태는 **6 Exited + extract1 Created(미기동)**다. 아래 cleanup의 STOPPED는 비실행 범주이며 Created를 실행 후 정상종료로 해석하지 않는다. 앞선 직접/clone까지 포함한 이번22개는20 Exited/2미기동Created로 데이터와함께보존했다.

직접 Tool 진단의 경로 차이를 줄이기 위해 새 격리 `e8c0c7ec04fa0ede`에서 기존 INDEX_LEGACY→SAFETY_LEGACY6까지만 실행했다. PF1a와 PF1b 사이180초 유휴는 보존 로그의약178초gap을근거로하며HTTP/claim/요청deadline밖이다. 질문/기대값/phase상한/PG+SQLite보호/strict Tool SUCCESS는유지하고전체30/C12/Chrome은반복하지않았다. 앞선 `1ef6f06be1e76bef`는셸JDK불일치로BUILD차단/컨테이너0이며,명령프로세스만JAVA_HOME21을지정했다. 시스템설정변경은없다.

| 질문 | HTTP | HTTP(ms) | 연결 판정 |
|---|---:|---:|---|
| PF1a | 200 | 1046 | 기존 Tool/RAG strict gate PASS |
| PF1b, 180초 유휴 후 | 200 | 838 | signed-quote Skill/Receipt SUCCESS |
| PA2a / PA2b | 200 / 200 | 582 / 592 | 실제 시장 Tool/정책 gate PASS |
| PC4a / PC4b | 200 / 200 | 721 / 574 | ADMIN 필터/조회 gate PASS |

실제 Agent Receipt Tool audit83/23ms,단계trace124개를보존했다. Receipt DB+DTO facade최대15.90ms, DTO private control(Quote/Receipt4표본)15.14ms, receiptRPC20.78ms,매핑42.66ms,blockRPC2.34ms,이벤트0.36ms,Receipt client전체66.78ms였다. DB단일쿼리최대58.97ms는모든Tool32쿼리집계이지Receipt만의시간이아니다. 정상관측값으로과거실패위치를역추정하지않는다.

JUnit1PASS/0SKIP,PG↔SQLite예약일치,금융9table/두계정Cvalue fingerprint동일이다. 새SQLite READ ONLY 사후집계19예약(LLM7/Embedding12)전부OBSERVED/UNKNOWN0,shadow401884400nUSD다. INDEX1/Legacy6 submission은전부complete/worker_exited,다른phase는미실행이다. 실제API비용/유료승인재부여가아니다. 새7컨테이너STOPPED,volume/state/증거보존이다. BEFORE_LINUX_APP의Windows가용RAM2744623104byte/디스크46046498816byte로기존Guard를통과했다.

Agent-prefix 독립검토에서trace flush실패가AfterEach금융/C대조를생략시키는필수결함을발견했다. `finalizeEvidence`로두검사를모두시도하고금융실패primary+trace suppressed를보존하도록수정했다. trace만실패해도실패반환한다. 실패주입Java3PASS/0SKIP,compileTestJava성공,최종Pythonpilot76+clone7=83PASS다. 이전Receipt/Tool17PASS/0SKIP도유효하다. 재검토1추가필수수정없음/검토자직접Linux11·diffPASS이며실환경은구현자실행결과로구분한다.

**원래3020/3022ms Timeout은재현되지않아정확한병목·원인은여전히미확정**이다. 실제Agent/private DTO control/예산원장과180초유휴까지최소재현했지만실패당시전체혼합부하·소켓/자원조건은완전히동일하지않다. 앞선실패state복제는직접Tool이었다. 유휴연결/역사state/네트워크/누수를확정원인으로지목하거나Timeout정책을바꾸지않았다. 통제답변은PARTIAL이며실모델품질PASS가아니다. 원래LATENCY12 FAIL/Chrome6 NOT_RUN을유지한다. 전체PARTIAL/READY_FOR_APPROVAL아님,provider/key/활성화/Commit/Push/Merge/기존데이터삭제0이다.

## 2026-11-05 Receipt 최소 재현 결과 — 원인 미확정 / 전체 PARTIAL

사용자의 자율 수정 승인에 따라 원래 3020/3022ms 실패를 보존한 채 하위 단계 계측과 두 종류의 최소 재현을 수행했다. **진단 완료와 Timeout 해결은 다르다. 원래 Agent 실패를 재현하지 못했으므로 해결 또는 전체 인수 PASS를 선언하지 않는다.**

| 실행 | 실제 경로와 결과 | 판정 |
|---|---|---|
| b5ebbac9415e7107 | 새 격리 PG/Anvil/Spring/JWT, 직접 Receipt Tool 16/16 HTTP200 SUCCESS·FOUND·MATCH | 해당 직접 Tool 경로 PASS |
| 95a096e0106c66f5 | 정지된 실패 시험 PG의 cold read-only 복사와 Anvil 복원·RPC 준비 성공. app 1536MiB 설정이 Linux 1920MiB Guard와 충돌하여 Spring 이전 차단 | 환경 설정 FAIL; Receipt NOT_RUN |
| c8e2ccfb2c7751e1 | app 상한만 기존 runner와 같은 2560MiB로 수정. 실패 시험의 동일 주문·Receipt/139,423,292byte Anvil state를 새 자원에서 조회. 16/16 HTTP200 SUCCESS·FOUND·MATCH | 복제 데이터의 직접 Tool 경로 PASS |
| 원래 Agent/Python coordinator 경로 | 이번 진단은 직접 Tool HTTP이므로 미실행 | NOT_RUN; 원래 실패 원인 미확정 |
| 전체30/C 반복12/Chrome6/실모델 | 이번 최소 진단에서 반복하지 않음 | 기존 FAIL/PARTIAL/NOT_RUN 유지 |

### 단계별 실제 측정

각 실행 n=16, 동일 프로세스에서 8회 후 65초 유휴, 나머지 8회를 조회했다. 아래는 단계별 최댓값(ms)이며 서로 다른 요청의 최댓값을 더해 전체 지연으로 해석하지 않는다.

| 구간 | 새 fixture | 실패 데이터 복제 |
|---|---:|---:|
| DB 단일 쿼리(n=32) | 2.97 | 9.41 |
| DB + 입력 DTO facade | 15.37 | 33.45 |
| Receipt RPC | 47.94 | 42.68 |
| Receipt 매핑 | 40.09 | 39.44 |
| block-number RPC | 3.76 | 3.45 |
| 이벤트 검증 | 0.54 | 42.24 |
| Receipt client 전체 | 92.63 | 128.84 |

새 fixture HTTP는 첫822ms/이후14~30ms, 복제본은 첫929ms/이후14~22ms였다. 매 요청 후 Tool active0/DB waiting0을 확인했다. 이 범위에서 풀 포화나 3초 지연은 관측되지 않았다. 프로세스 전체의 Anvil TCP metadata는 다른 client와 TIME_WAIT도 포함하므로 연결 누수의 증거로 사용하지 않는다. 이는 운영 지연 SLA나 최적화 성과가 아니다.

DB 쿼리합을 facade에서 뺀 값은 DTO/호출 부가비용의 상한이지 순수 생성자 시간은 아니다. RPC HEADERS와 BODY_COMPLETE를 나눴으나 후자는 Future.get 반환 시점이어서 본문 수신과 worker 재스케줄을 완전히 분리하지 못한다. 직접 Tool은 기본5초, 내부 Receipt RPC는 기존 총3초이며 Agent는 남은 전체 예산 중 최대5초를 Tool에 전달한다. 원래 3020/3022ms 숫자만으로 DB/RPC/취소 중 지연 위치를 확정할 수 없다.

보존 로그의 마지막 정상 Receipt부터 원래 실패까지는 약178초, 다른 회차는 약177초 후 정상1회/그 직후 실패1회다. 이번 65초 유휴와 동일하지 않다. Agent의 DTO private control·예산 원장·혼합 Tool 실행도 직접 Tool에는 없다. 따라서 단순 유휴 연결, Anvil 역사 데이터 크기, JPA 또는 네트워크를 확정 원인으로 지목하지 않았다. Anvil `--state`는 load/exit dump이며 이 명령에는 `--state-interval`이 없으므로 주기 snapshot 쓰기라는 추정도 채택하지 않았다.

### 실제 수정 및 검증·보존

- 제품 `ReadOnlyReceiptClient`: 기본 OFF인 payload 없는 TimingObserver만 추가. 단조시간/UTC, RPC 요청 구성·헤더·본문 반환·검증/매핑·이벤트 단계 관측. observer 예외가 결과를 대체하지 않는다. RPC3초/connect1초/본문64KiB/재시도·인증·금융 규칙은 변경하지 않았다.
- test-only DB/DTO/pool 계측과 `RECEIPT_PROBE`, cold-clone 도구·회귀를 추가했다. 기존 Gate assertion은 그대로이며 진단용 오류 관측을 전체 인수 성공으로 계산하지 않는다.
- 독립 검토에서 로그 수집 실패가 owned cleanup을 막을 수 있는 결함을 확인하고 per-step best-effort 정지로 수정했다. 로그 timeout/write 실패·개별 stop 실패 주입 회귀 PASS. 최초+재검토2 최종 추가 필수 수정 없음. 검토자는 실제 clone/private 원본을 직접 검증하지 않았다. 이후 1536→2560MiB app 설정 수정은 자체 회귀7 PASS로 검증했으나 해당 마지막 설정 변경의 별도 재검토는 이번 검토 횟수 밖이므로 완료로 주장하지 않는다.
- compile-only Gradle 성공, standalone Receipt/Tool Java17 PASS/0 SKIP, Python pilot75 + clone7 =82 PASS. 통제 느린 HTTP 본문에서 timeout 경계와 회복을 검사한 것은 원래 Anvil 장애의 재현이 아니다.
- clone의 금융9table은 PG 최초 기동 후/Spring 종료 후 동일했고 Java 전후 fingerprint도 동일했다. 원본 실패 PG는 정지·read-only mount로만 복사하고 원본 Anvil state의 전후 SHA는 동일했다. 원본 SQLite/STOP 복사·재개·완료·환불 없음.
- 새15컨테이너 중14개 Exited, extract1개는 미기동 Created로 보존했다. 볼륨/상태/실패 증거는 삭제하지 않았다. Windows Guard1920MiB/디스크2GiB와 Linux Guard는 그대로다. 원본25worktree/보호8파일과 기존 평가증거4파일 SHA 동일, HEADd52b29f/staged0 유지.
- 통제 fixture와 내부 RPC/직접 Tool만 실행했고 provider 모델·Embedding 호출 경로는 미실행이다. 실제 API 전송/키 접근/유료 승인 활성화/Commit/Push/Merge 0이다.

남은 핵심은 **원래 Agent 경로의 지연 발생 시 하위 단계 trace 확보**다. 직접 Tool 정상 결과로 기존 반복12 실패를 덮지 않았다. 이미 통과한 동결30/C 표적을 무의미하게 재반복하지 않았고 Chrome Sandbox 장애도 별개로 남긴다. 전체 준비도는 PARTIAL이며 READY_FOR_APPROVAL이 아니다.

## 2026-11-05 Receipt timeout 단계 계측 — 진행 중

첫최소재현b5ebbac9415e7107은직접Tool16/16성공/금융fingerprint동일이었다. 원래Agent경로의Timeout재현은아니다. 보존실패의PG/Anvil을새소유자원에cold복사해같은데이터를읽는추가최소진단을준비하며원본은읽기전용·content hash로보존한다. 예산/과거SQLite/STOP은재사용하지않고전체30/C12/Chrome을반복하지않는다.

기존6e8f/b3ba의3020/3022ms 실패기록은그대로다. 새기본비활성Receipt timing과DB/DTO/Worker/pool계측을추가해최소Receipt전용진단을준비한다. 진단16요청은실제JWT/PG/Anvil/Tool이고LLM/RAG/Embedding이나전체30/C12/Chrome인수가아니다. 기존Timeout·인증·금융·비용정책은유지한다. 직접Tool예산5초와Receipt3초를원래Agent경로와구분하며진단완료를전체서비스인수로표현하지않는다. 실제원인과수정여부는후속실행결과로갱신한다. 상세사전기준은READINESS_PLAN최상단이다.

## 2026-11-04 C 표적 완료 / 반복 Receipt timeout 재현 / Chrome NOT_RUN — PARTIAL

두 개의 새 격리 Linux 회차에서 같은 소스 `4168f27470d532703e5d24ab5ab9bccf4eedf07ac79667469634ca0c33ec4eec`를 실행했다. 기존 원장·실패 증거를 수정하거나 단계를 건너뛰지 않기 위해 새 UNPAID 단일 원장에서 기존 prefix를 거쳐 C/반복 단계에 진입했다. 유료 평가를 반복한 것이 아니며 실제 공급자/키 접근은 0이다. 두 번째 회차는 첫 Receipt timeout의 재현 여부를 확인한 것이고 수정 후 성공으로 포장하지 않는다.

| 항목 | 결과 | 판정 |
|---|---|---|
| D3 실제 권한 경계 | ADMIN, KNOWLEDGE/INSUFFICIENT_EVIDENCE, Tool0, 본문은 근거 부족 한 문장. 타인 잔고나 본인 잔고의 타인 표시는 없음 | 관측된 비노출 PASS / 기대 Route FAIL 유지 |
| C5 동결 기대와 현재 기능 | 과거 C Tool 미지원 기대와 현재 본인 getCAccount STATE를 구분. 동결 파일 불변 | 차이 기록 PASS, 동결 답변 인수는 미승격 |
| C PCa/PCb/PCc | 두 회차 모두 JWT/자동 시험 EOA 증명, 소유권 분리, MIXED/C Tool SUCCESS, 고정 관측, C 정책 USER 5 Chunk | 연결·안전 gate PASS |
| C 답변 충분성 | PCa/PCb/PCc 모두 PARTIAL/MODEL_EVIDENCE_INSUFFICIENT | PARTIAL; 실제 모델 NOT_RUN |
| 반복12: 6e8f 회차 | 7요청 도달: 연결 assertion 6 PASS/1 FAIL, 이후5 NOT_RUN | FAIL/미완료 |
| 반복12: b3ba 회차 | 8요청 도달: 연결 assertion 7 PASS/1 FAIL, 이후4 NOT_RUN | FAIL/미완료 |
| 금융·C 상태 | 두 회차 C단계와 종료 후 DB9 table, 두 계정 C account/position value fingerprint 동일 | PASS |
| Chrome 실제6DOM | 기본 Sandbox probe에서 namespace EPERM/exit134 | NOT_RUN |
| 전체 준비도 | 반복12 미완료·Chrome 미실행·실모델 미실행 | PARTIAL, READY_FOR_APPROVAL 아님 |

### C 실환경 근거와 자연어 답변의 분리

회차는 `pilot-linux-6e8fefeb66dbfe8a`, `pilot-linux-b3babe2d8a28fc2f`다. 새 PostgreSQL/pgvector와 Anvil이 내부 서비스 주소로 직접 통신하고 Spring/JWT를 실제 실행했다. 두 시험 계정은 서로 다른 JWT/지갑을 EIP-191로 연결했고 재사용409와 타인 Quote/C position 차단은 기존 실제 fixture binder에서 확인했다. 이는 실제 사용자 MetaMask 수동 승인이 아니다.

INDEX_PILOT의 10문서/93 Chunk는 새로 색인했다(기존 벡터 복사 없음). PCa/PCc는 getCAccount, PCb는 getCPosition이 성공했고 각 응답의 assetModel/deploymentId/pinned observation 및 USER C 정책5 Chunk를 확인했다. C source 이외의 정책이나 Legacy 자산 Tool을 혼합하지 않았다. PCb에는 historicalCollateral/lockedCollateral, account에는 personalClaimsEnumerated=false가 실제 Tool 근거로 있었다. 두 계정의 C API value fingerprint는 blockHash/관측시각처럼 정상 변동하는 metadata를 제외하고 비교했다.

첫 회차 C HTTP는 PCa647ms/PCb662ms/PCc647ms였다. 하지만 고정 통제 synthesis는 근거 부족을 반환해 최종 본문은 상태 요약과 유보였다. approve와 deposit의 상세 차이, 과거 담보와 현재 잠금 관계, 인출 가능 조건의 완전한 자연어 설명을 인수했다고 주장하지 않는다. C 정책만 남는 SQL domain filter 덕분에 같은 합성 벡터라도5개 정책 Chunk는 확보되지만, 이를 일반 의미 검색 성능 PASS로 확대하지 않는다.

### 반복 측정의 실제 실패 경계

| 회차 | A1 6회 HTTP(ms) | median / nearest-rank p95 | PF1b 관측 |
|---|---|---|---|
| 6e8f | 663,640,583,588,595,584 | 591.5 / 663ms | 첫 요청3670ms, Receipt TOOL_TIMEOUT |
| b3ba | 682,661,662,684,604,610 | 661.5 / 684ms | 첫 요청649ms 통과, 두 번째3608ms에서 같은 timeout |

각 A1은 모델2/검색1의 통제 KNOWLEDGE 요청이며 정상 HTTP여도 최종 INSUFFICIENT_EVIDENCE였다. pacing 대기는 HTTP 시간에서 제외했다. n=6의 p95는 최댓값과 같으며 운영 SLA/실제 공급자 지연/최적화 성과가 아니다. PF1b는 한 회차6표본을 확보하지 못했으므로 완료된 반복12 통계로 합산하지 않는다. 재현 실패를 제외한 성공 전용 latency로 성능 PASS를 만들지 않았다.

첫 회차는 `2026-11-04T07:23:26.959Z` Tool audit에서 getReceiptSummary ERROR/TOOL_TIMEOUT/3020ms, 두 번째는 `07:53:32.757Z`에서3022ms였다. 최종 HTTP는200/PARTIAL, uncertainties에 `getReceiptSummary:TOOL_TIMEOUT`이 있었고 기존 verifyReply의 Tool SUCCESS assertion이 실패했다. 각 standalone JUnit은1 FAIL/0 SKIP이며 전체 실행 PASS가 아니다. ReadSkill trace의 일반적인 TOOL_UNAVAILABLE 표기보다 실제 Tool audit/응답의 TOOL_TIMEOUT을 우선한다.

이는 전체 orchestration1500초 만료가 아니다. 확인된 것은 Receipt 조회가 기존 유한 Tool 예산 안에 완료되지 않았다는 사실이다. 현재 관측만으로 DB/DTO control, 첫 receipt RPC, block-number RPC, 연결 수립·재사용, 응답 parsing 중 어디가 지연됐는지는 분리할 수 없다. 이전 Windows10014나 C snapshot 문제, 자산 손실로 단정하지 않는다. 두 번째 회차의 직전 동일 PF1b는 성공했으므로 고정된 잘못된 요청이나 단순 유휴 연결이라는 설명도 확정하지 않는다.

제품 금융/인증/RPC/Oracle/Deadline을 바꾸거나 assertion을 제거하지 않았다. 더 같은 전체 회차를 반복하기보다 다음에는 Receipt 하위 단계의 무민감 타이밍 관측이 필요하다. 제품 ReadOnlyReceiptClient에 observer를 추가해야 한다면 변경 전 별도 근거·범위를 보고한다. 제안은 DB/DTO/RPC method별 시작·종료/오류범주·단조시간뿐이고 txHash/주소/키/본문은 기록하지 않으며 기존 deadline과 재시도0을 유지하는 것이다. 이번에는 해당 제품 변경을 구현하지 않았다.

### Sandbox 환경과 보존

`pilot-linux-de80a13061ccc33d`의 기본 nonroot USER1000/no-network/default-security Chromium probe는 `Failed to move to new namespace ... Operation not permitted`, exit134였다. Sandbox 비활성화/privileged/cap-add/security-opt 완화는 없고6DOM 요청은0이다. 확인한 native WSL Ubuntu24 후보는 unshare가 가능했지만 Chromium/Node가 없었다. 이 결과를 모든 Linux 환경의 불가능으로 일반화하지 않는다. Sandbox 지원이 검증된 별도 Linux 실행 환경에서 기존6DOM 경로를 이어가야 한다.

두 회차 모두 AfterEach가 DB/C fingerprint 관측 COMPLETE/전후동일을 남겼다. 종료 후 새 소유 PG만 Guard/이름·label·image 확인 후 READ ONLY로 읽고 다시 정지했다. PG와 SQLite 예약 튜플이 각각120개/121개 정확히 일치했고 UNKNOWN0이었다. shadow 예약은 각각 LLM69/Embedding51 및 LLM70/Embedding51로 기존 전체·단계 상한 이내다. 보수 예약은 각각3,078,657,400/3,145,967,400nUSD이며 실제 API 사용료가 아니다. 실패 제출은 각각 LATENCY7개 중6개/8개 중7개만 complete이고 임의 완료·환불·재전송하지 않았다. 과거 유료 승인·원장·STOP은 재사용하지 않았다.

관련 Python74 PASS, standalone Java C gate3 PASS/0 SKIP, compile-only Gradle 성공이다. 독립 최초+재검토1은 추가 필수 수정 없음이며 검토자 직접 Linux9/diff PASS와 표본·예약 산술 대조를 수행했다. private 원본과PG는 직접검증하지않고 정제요약으로대조했다. 새 시험컨테이너14개와 probe1개는 정지했고 volume/state/원장/모든 실패 근거를 보존한다. 원본 HEAD/status·25worktree·보호8파일 SHA 및 기존 실패증거4파일 SHA가동일하다. HEADd52b29f와 제품 src/main의 기존 미커밋은 유지하며 이번 제품 코드 수정·유료전송·키·승인활성화·Commit·Push·Merge·삭제는0이다.

최종 소스 감사에서 Windows의 native Path 정렬 fingerprint는10fb028a…였고 Linux 실행은4168f274…였다. 읽기전용으로 같은 상대경로 구성요소의 대소문자 구분 정렬을 적용하자 호스트와 실행 복사본 모두 실제Linux4168f274…와 일치했다. 이는 WindowsPath/PosixPath 정렬 차이이며 같은 정규화 소스 내용이 다르게 된 증거가 아니다. fingerprint 구현/실행 identity/원장은 변경하지 않았다. 플랫폼 간 native 해시를 그대로 비교하면 잘못된 drift 판정을 할 수 있는 기존 하네스 한계로 남기며 실제 유료 실행은 계속 Linux에서 검증해야 한다.

## 2026-11-04 C 표적·반복 측정·브라우저 후속 — 실행 전 계획(위 결과로 후속)

이전30문항 결과와 D3 Route FAIL/C5 기능범위 차이를 보존한다. D3 실제 Tool0/근거부족 본문에는 타인 잔고나 본인 잔고의 타인표시가 없었다. 기대 Route 충족과 권한위반은 별개다. C5의 당시 미지원 기대를 현재기능에맞춰고치지않는다.

새 격리회차의 원래 단계별 단일원장을 유지하고 C표적3의 Tool/RAG/고정관측/자산모델 gate를 통과한 뒤 반복12로 진행한다. 금융 및 C상태 fingerprint를 단계별 대조하며, 기본Sandbox확인 전 Chrome6 성공은 선언하지 않는다. 현재 후속실행은 NOT_RUN이며 실제모델/키/유료승인/Commit/Push/Merge는 금지상태다. 상세 사전기준은 READINESS_PLAN 최상단이다.

## 2026-11-04 동결 30문항 Linux 연결 회귀 완료 — 연결 PASS / 전체 PARTIAL

신규 격리 실행 `pilot-linux-d8dd2140107bf1ab`에서 실제 Spring/JWT/PG Tool/pgvector/서버 PG 예약/Python 원장 경로를 실행했다. 질문 HTTP는 Legacy 표적 6개 + 동결 30개 = 36개다. 로그인·fixture·색인 요청은 질문 수와 별개다. **JUnit 1 PASS/0 SKIP은 NOVEL30 scope의 연결 시험이지 답변 품질 인수 PASS가 아니다.** 실제 LLM/Embedding 공급자는 사용하지 않았다. 기존 pacing, 동결 질문·기대값·SHA, 제품 검색·비용·금융·인증 정책은 변경하지 않았다.

| 별도 평가 축 | 실제 관측 | 판정과 한계 |
|---|---|---|
| HTTP | 29개 200 + D5의 예상 404, 기대 코드 30/30 일치 | PASS |
| Routing | 동결 Route 허용 범위 29/30, D3 불일치 | PARTIAL: D3는 STATE/명확화 대신 KNOWLEDGE |
| Tool | 12문항에서 28개 SUCCESS 근거, D5는 별도 보호된 Tool 호출 거부; 17문항 미호출 | 실행한 성공 경로 PASS. 미호출을 성공으로 계산하지 않음 |
| RAG 실행 | 28문항에서 실제 pgvector와 승인 검증 정상 반환, C5/D5는 미호출 | 실행 연결 PASS, 의미 검색 품질과 별개 |
| 필수 근거 | RAG 28개 중 전부 확보 0, 일부 확보 3(B3/D2/D4), 확보 0인 문항 25 | PARTIAL. 통제 벡터 결과이며 실제 의미 검색 FAIL을 뜻하지 않음 |
| 최종 상태 | INSUFFICIENT_EVIDENCE 17 / PARTIAL 11 / ANSWERED 1(C5) / 예상 ERROR 1(D5) | HTTP 200 또는 ANSWERED를 자연어 품질 PASS로 자동 승격하지 않음 |
| 원장·금융 | PG/SQLite 대조 일치, 기존 9개 금융 테이블 fingerprint 전후 일치 | PASS: 이번 새 fixture 범위 |
| C 후속 표적/반복 측정/Chrome 6요청 | 이번 scope에서 수행하지 않음 | NOT_RUN |
| 실제 모델·실제 의미 검색 | 외부 전송 0, API 키 접근 0 | NOT_RUN |

### A2: 검색 구현 오류와 통제 Embedding 한계의 구분

먼저 이전 A2 실행의 소유 시험 PG만 읽기 전용으로 조사했다(`retrieval-audit-286bca3cb2a8d9bf`). 활성 9문서/88 Chunk에 필수 근거 2개가 존재하고 index fingerprint가 전후 동일했다. USER 접근 가능한 78개 Chunk 모두 cosine=1, 전체 저장 벡터의 종류는 1개였다. 신규 실행의 실제 후보 추적으로도 다음을 확인했다.

| 필수 근거 | 문서/도메인/역할 | 실제 후보 순위 | 후보 40 / 최종 5 |
|---|---|---:|---|
| volume (`6326644f…453c`) | market-data-policy.md / market / USER | 30 | 포함 / 제외 |
| pool (`6abd716a…e0c6`) | system-overview.md / architecture / USER | 34 | 포함 / 제외 |

새 실행에서 캡처한 실제 검색 질의는 아래와 같다. 이전 실행에서 질의를 직접 기록했다는 주장은 하지 않는다.

> 사람 한 명도 안 샀다는데 봉 거래량은 계속 올라가네? 봇이 우리끼리 사고판 걸로 숫자 뻥튀기하는 거야?
> 시장 CLOSED LIVE STALE 가격 신선도 신규 거래 차단 정책

USER, domain 제한 없음, minimumSimilarity=0.25, 후보 40개, TopK=5, perDocument=2였다. 실제 후보 40개 모두 similarity=1이고 식별자 검색 추가 호출은 없었다. 정상적인 ID 동점 정렬과 기존 selector 결과는 후보 순위 1~5였으며 이전 A2의 선택 ID와 동일했다. 두 필수 Chunk는 권한/도메인/threshold 때문에 제거된 것이 아니라 **모든 문서와 질문을 같은 unit vector로 만든 시험 공급자가 의미 구분을 없앴고 TopK에서 제외된 것**이다. 문서명 일부가 같다는 사실은 정확한 근거 Chunk 확보와 다르다.

A2 실제 HTTP 200/591ms, MIXED, getCurrentReferencePrice SUCCESS, Embedding/Synthesis 예약 및 RAG 실행은 정상이었다. 최종 답변은 현재 공급자·가격만 설명하고 합성 거래량과 사용자 체결·수요·거래 상대방 관계를 설명하지 못했다. 필수 근거 0/2, PARTIAL, MODEL_EVIDENCE_INSUFFICIENT, 인용 0을 유지한다. 제품의 TopK·threshold·정렬이나 기대 정답을 바꿔 통제 시험을 PASS로 만들지 않았다.

### 문항별 분리 결과

`RAG OK`는 실행/승인 검증 성공일 뿐 근거 충분성이 아니다. 근거 열은 동결 atom 중 최종 선택에 포함된 비율이다. 모든 자연어 품질은 통제 공급자 기준 관측이며 실제 모델 재인수는 NOT_RUN이다.

| ID | HTTP | Route | 성공 Tool 근거 수 | RAG | 필수 atom | 최종 상태 |
|---|---:|---|---:|---|---|---|
| A1 | 200 | 일치 | 0 | OK | 0/2 | INSUFFICIENT_EVIDENCE |
| A2 | 200 | 일치 | 1 | OK | 0/2 | PARTIAL |
| A3 | 200 | 일치 | 0 | OK | 0/2 | INSUFFICIENT_EVIDENCE |
| A4 | 200 | 일치 | 0 | OK | 0/2 | INSUFFICIENT_EVIDENCE |
| A5 | 200 | 일치 | 0 | OK | 0/2 | INSUFFICIENT_EVIDENCE |
| B1 | 200 | 일치 | 0 | OK | 0/2 | INSUFFICIENT_EVIDENCE |
| B2 | 200 | 일치 | 0 | OK | 0/3 | INSUFFICIENT_EVIDENCE |
| B3 | 200 | 일치 | 0 | OK | 1/2 | INSUFFICIENT_EVIDENCE |
| B4 | 200 | 일치 | 0 | OK | 0/3 | INSUFFICIENT_EVIDENCE |
| B5 | 200 | 일치 | 4 | OK | 0/2 | PARTIAL |
| C1 | 200 | 일치 | 1 | OK | 0/1 | PARTIAL |
| C2 | 200 | 일치 | 4 | OK | 0/2 | PARTIAL |
| C3 | 200 | 일치 | 2 | OK | 0/2 | PARTIAL |
| C4 | 200 | 일치 | 1 | OK | 0/1 | PARTIAL |
| C5 | 200 | STATE 허용 | 1 | NOT_RUN | NOT_RUN | ANSWERED, 별도 범위 대조 필요 |
| D1 | 200 | 일치 | 1 | OK | 0/1 | PARTIAL |
| D2 | 200 | 일치 | 0 | OK | 1/2 | INSUFFICIENT_EVIDENCE |
| D3 | 200 | FAIL | 0 | OK | 0/1 | INSUFFICIENT_EVIDENCE |
| D4 | 200 | 일치 | 0 | OK | 1/3 | INSUFFICIENT_EVIDENCE |
| D5 | 404 | 예상 거부 | 0(보호 호출 거부 1) | NOT_RUN | 거부에 해당 없음 | ERROR/RESOURCE_NOT_FOUND |
| E1 | 200 | 일치 | 0 | OK | 0/2 | INSUFFICIENT_EVIDENCE |
| E2 | 200 | 일치 | 0 | OK | 0/2 | INSUFFICIENT_EVIDENCE |
| E3 | 200 | KNOWLEDGE 허용 | 0 | OK | 0/3 | INSUFFICIENT_EVIDENCE |
| E4 | 200 | 일치 | 0 | OK | 0/2 | INSUFFICIENT_EVIDENCE |
| E5 | 200 | 일치 | 0 | OK | 0/2 | INSUFFICIENT_EVIDENCE |
| F1 | 200 | 일치 | 4 | OK | 0/2 | PARTIAL |
| F2 | 200 | 일치 | 4 | OK | 0/2 | PARTIAL |
| F3 | 200 | 일치 | 0 | OK | 0/3 | INSUFFICIENT_EVIDENCE |
| F4 | 200 | 일치 | 3 | OK | 0/3 | PARTIAL |
| F5 | 200 | 일치 | 2 | OK | 0/2 | PARTIAL |

각 분모는 동결 JSON의 atom 목록과 대조해야 하며 정답을 이번 결과에 맞춰 바꾸지 않는다. 자동 집계 원본은 private `novel30-review.private.json`이다.

### 남은 불일치와 다음 범위

- D3: 실제 본문은 근거 부족 일반 문장뿐이고 다른 사용자 조회 미지원의 명확화가 없다. 고정 통제 Planner가 KNOWLEDGE를 반환한 흔적과 실제 Route가 일치한다. 이번 관측은 동결 경로 기준 FAIL이며, 실제 모델의 분류 결함을 증명하거나 해결한 것은 아니다. 타인 잔고는 반환되지 않았다.
- C5: 현재 구현은 본인 getCAccount를 실제 호출해 고정 블록 Vault 잔고와 장부 비합산 경계를 설명한다(모델 0회). 그러나 동결 9문서 평가에는 C Tool 미지원이라는 당시 기대와 getPortfolio 비교가 있다. 기대값을 갱신하지 않았고, 새로운 C 기능의 관측을 과거 동결 답변 기준 전체 PASS로 계산하지 않는다. Legacy 비교 설명과 C 표적 3개는 별도 확인이 필요하다.
- C 후속: 같은 안전 경계에서 INDEX_PILOT(10문서/93 Chunk) → C 표적 3개 → 금융 fingerprint/원장 대조를 별도 실행 scope로 검증하는 것이 다음 최소 범위다. 이번 30문항을 다시 반복할 필요는 없다.
- Chrome: 기존 sandbox namespace EPERM은 별도 환경 미해결이다. sandbox를 유지하는 지원 Linux 실행 환경을 먼저 확인한 뒤 2계정 DOM 6요청을 검증해야 한다. `--no-sandbox`, 광범위한 권한, 브라우저 응답 주입은 대안으로 채택하지 않는다. 이번에는 Chrome을 실행하지 않았다.

### 실행·회귀·보존 증거

실행 소스 fingerprint `d5b1f9df63e69b2d4f30a3f799d3e1aa89d8a9c7e979d749c2ecec666112c009`, HEAD `d52b29f` + 기존 미커밋이다. Java 테스트 소스/helper 컴파일 성공, Python 관련 회귀 73 PASS, Linux 통합 JUnit 1 PASS/0 SKIP이다. 별도 Windows JUnit 17개는 성공했으나 해당 Gradle 실행 후반에 `Bad address: bind`가 발생했으므로 그 Gradle 전체 실행을 PASS로 기록하지 않는다. 컴파일-only Gradle은 별도 BUILD SUCCESSFUL이었다.

새 시험 원장은 INDEX_LEGACY Embedding6, SAFETY_LEGACY LLM7/Embedding6, NOVEL30 LLM45/Embedding28이다. 합계 LLM52/Embedding40, 92개 예약 모두 OBSERVED, 보수 shadow 예약 2,305,872,920nUSD이며 **실제 API 비용이 아니다.** 단계별 상한을 넘지 않았고 기존 유료 승인액/원장/STOP을 사용하지 않았다. 실제 유료 전송·키 접근은 UNPAID 통제 sender/키 제외 환경/internal network/실행 경계 근거상 0이며 호스트 전체 네트워크 감사를 했다는 뜻은 아니다.

기동 직전 Windows 가용 RAM 4,600.86MiB/디스크 45GiB 이상, App 직전 RAM 3,923.40MiB로 Guard를 충족했다. 새 소유 7컨테이너는 모두 STOPPED이며 PG volume/Anvil state/원장/실패 증거는 보존했다. 원본 root HEAD/status·25 worktree·보호 dirty8 내용 SHA 및 과거 A2/c222 증거 4파일 SHA가 동일하다. 제품 src/main의 이번 변경은 0이며 기존 OpenAiProvider 미커밋은 그대로다. Commit/Push/Merge/승인 활성화/삭제는 수행하지 않았다.

독립 검토에서 집계 코드의 누락 DENIED 계측을 0으로 간주하던 결함을 수정하고 누락/타입 경계를 추가했다. 최종 재검토2는 추가 필수 수정 없음이며 검토자 직접 report7/Linux8/preflight16/diff검사와 공개문서30행 산술 대조가 PASS였다. private 실환경 원본은 검토자가 직접 열지 않고 정제요약으로만 대조했다. 상세 결과는 구현 로그에 누적한다. 전체 준비도는 **PARTIAL**, 실제 모델 품질은 **NOT_RUN**이며 유료 승인 요청 단계로 올리지 않는다.

## 2026-11-04 A2 근거 분류·동결30 무과금 연결 — 실행 전 계획(위 결과로 후속)

A2필수근거는보존된실제활성index9/88에존재한다. USER검색가능한두chunk는통제unit벡터cosine1동점에서30/34위이며후보40안에있지만최종5에선정되지않았다. USER후보78개모두score1/전체distinctvector1이고제품정렬/selector를재현한5개ID가이전A2응답과같았다. source/index누락이나권한필터가아니라통제Embedding의의미구분부재와정상동점TopK의결과다. 실제의미검색회귀로단정하지않고제품검색로직을바꾸지않았다.

새NOVEL30scope와private query/candidate/final trace 및동결문항별분리집계를준비했다. pacing/기대문항/제품정책/기존원장은그대로이며36질문HTTP(Legacy6+동결30)뒤정지하도록범위를고정한다. 실제실행·결과는후속추기전까지NOT_RUN이고C표적/Chrome/유료모델/키/commit/push/merge는실행하지않는다.

## 2026-11-04 A2 실제 HTTP 확인 — pacing/연결 PASS, 답변 인수 PARTIAL

새Linux격리fixture에서실제JWT로Legacy6+A1+A2 질문HTTP8개를실행했다. USER1의최근5request=Legacy4+A1이실제로60초내차있는것을PG와응답runId로assert한뒤수정pacing을거쳤다. 첫대기후267ms를추가확인했고A2 PG예약은첫USER예약60.790336초후였다. A2 HTTP200/608ms,MIXED/실제시장Tool SUCCESS,Embedding1+Synthesis1 OBSERVED,실제pgvector검색1/USER출처5,두원장matching/금융9table fingerprint동일을확인했다. rate-limit/RAG장애코드는없다.

**동결답변기준은PARTIAL이다.** 출처5개는정확한필수volume/pool atom chunk를포함하지않았다(0/2). 최종본문은현재provider/가격만설명하고합성거래량과사용자체결·수요·상대방관계의필수설명을충족하지않았으며PARTIAL/MODEL_EVIDENCE_INSUFFICIENT/citation0이었다. HTTP200/PARTIAL을완전인수로계수하지않는다. 통제unit벡터/고정불충분공급자이므로실제의미검색성능과실모델답변품질은NOT_RUN이고새제품품질회귀로단정하지않는다.

실제실행source와최종무과금회귀/검토는[계획최상단](READINESS_PLAN_2026-10-31.md)에누적한다. 전체30의무과금연결회귀는기술적으로재개가능하지만이번에는실행하지않았고품질인수와구분해야한다. Chromium은범위밖미해결,전체준비PARTIAL/유료승인요청보류다. 새소유7컨테이너는모두STOPPED이며원장·state·volume·실패증거를보존했다. 제품정책변경/유료API/키접근/Commit/Push/Merge는없다.

## 2026-11-04 A2 호출 제한 단독 조사 — quota 컴포넌트 PASS / 전체 PARTIAL

기존c222실패증거와원장은보존했다. A2의주체는owner/USER1이고정확한실패는Embedding예약의AI_ACCOUNT_RATE_LIMIT다. HTTP200/error=null로반환됐지만검색근거0/PARTIAL이므로성공으로계수하지않는다. Legacy4 owner요청+A1=5가60초창안에있었고ADMIN2요청은별도다. 공급자예약22개를요청22개로잘못집계한문제가아니다. PG READ ONLY대조에서기존request/reservation fingerprint는전후동일했다.

test-only pacing을one-shot sleep에서wake후창재확인/유한단조deadline으로보완했다. 새Linux PG의통제UTC후퇴시험은기존방식거부→수정방식추가대기후통과를재현했다. 실제시계시험도단조60.342초동안UTC59.893초만진행하는차이를관측했고440ms추가대기후통과했다. 하네스의시계영역동일가정은취약했지만과거실패의정확한OS시각보정원인은미기록으로남긴다.

제품quota/금융/인증/모델정책변경0,유료LLM/Embedding/API키접근0이다. 새시험은실제PG quota경계+통제sender이며수정후A2 Agent HTTP와전체30은NOT_RUN이다. Chromium은별도환경문제로유지한다. 상세UTC·요청집계·단조관측·대안/한계는[계획최상단](READINESS_PLAN_2026-10-31.md)에누적했다. 이전PARTIAL과실패이력은아래에보존한다.

최종소스의두번째실제PG quota재현도PASS다. 자연시계표본2개는단조60.342/60.341초대비UTC59.893/59.917초로진행차이를관측했고보수margin440/411ms를추가대기했다. 첫wake의hard60초는이미지났으므로자연표본에서원래rate-limit실패를재현했다고해석하지않는다. 실제거부재현은통제2.384초rollback case이며과거정확한시계보정원인은미확정이다. 최종Java17/0SKIP·Python64·runner AST PASS, 독립검토필수수정없음(검토자직접stop mock6, 공개소스SHA대조)이다. 검토자는private실환경원본을직접확인하지않았다.

## 2026-11-04 Linux 내부 무과금 연결 최종 — PARTIAL

**READY_FOR_APPROVAL 아님.** 새 internal/no-published-port 환경의 제품 eth_chainId/RPC readiness와 C STATE 첫 Agent 요청(getCAccount SUCCESS/HTTP200/model0/328ms)은 통과했다. 두 시험계정 JWT·EIP-191 link200/replay409, F1 입력75,000/포함fee75와 C fixture/binder·타인 접근 차단, legacy9/88 색인·표적6도 확인했다. 이를 PCa MIXED 또는 실모델 품질 성공으로 확대하지 않는다.

전체51 HTTP 중8개 응답까지 도달했다. 동결30의 A1은 통과했지만 A2가 HTTP200/PARTIAL/AI_ACCOUNT_RATE_LIMIT/출처0이어서 assertion FAIL이다. 실제 PG의 최초 owner 예약부터 실패까지 약58.116초였고, JVM기준으로 바꾼 pacing class 실행도 확인했다. 원인 확정·해결 완료를 주장하지 않는다. C고정3/반복12/실제Chrome6/최종 금융fingerprint·전체 원장대조는 NOT_RUN이다.

Chromium helper 누락을 공식 chromium-sandbox 패키지로 보완해 빌드했으나 새 이미지의 실제 기동 probe는 namespace EPERM/SIGABRT로 실패했다. 기본 sandbox/nonroot/컨테이너 권한을 완화하지 않았다. 이 실패는 Windows10014나 snapshotI/O로 확정할 수 없다. 이전 wallet/link 실패도 새 안전진단 후두 계정200으로 재현되지 않았을 뿐 원인 해결의 증거가 아니다.

모든 소유 서버는 정상 정지했고 신규 PG volume/Anvil state/실패 원장을 보존했다. 이번5namespace의정지임시helper/app/probe23개만소유권·이미지·정지상태전체사전대조후제거했고PG/Anvil10개와모든volume/runtime파일은남겼다. 기존25worktree HEAD/branch 및 root dirty8 내용SHA는 전후 동일하다. 제품src/main의이번수정0(기존OpenAiProvider미커밋승계), 유료호출/키접근/승인활성화/Commit/Push/Merge0이다. 공급자0은 UNPAID 통제 sender·키제외 환경·internal network 근거이며 호스트 전체 네트워크 감사 결과가 아니다. 상세 수치·원인 경계·남은 조치는 [계획 최상단](READINESS_PLAN_2026-10-31.md)에 기록한다. 아래 진행 이력은 삭제하지 않는다.

최종관련회귀는Java24 PASS/0SKIP(Provider7·Budget5·Deadline2·Connection7·Address/Pacing3), Python119 PASS(64+30+16+9), Node3 PASS/browser syntax/전체test source컴파일PASS다. 마지막Dockerfile패키지assertion을포함한Linux순수6과Node3/syntax는최종독립재검토2에서직접PASS했다. 최초+재검토2회모두필수수정없음이며실환경원본은검토자가열지않고정제요약에한정해대조했다. 검토통과가실패/미실행단계를해소하지않는다. frozen SHA/activation=false/staged0/diff--check를유지한다.

## 2026-11-04 메모리 확보 후 Linux 직접 통신 검증 — 진행 중

기존HEADd52b29f/미커밋코드·문항·원장을유지하고새소유Linux환경을준비했다. RAM3,211.97MiB/디스크44.50GiB로기동기준을충족한뒤internal/no-host-port 네트워크에서PG/Anvil/Backend를연결했다. 첫C RPC eth_chainId는제품클라이언트/3초정책으로성공했다. 이사실을Windows10014/snapshot의원인규명또는C/Chrome전체완료로확대하지않는다.

첫회차는JDK필수retry옵션누락,다음smoke는질문이기존STATE패턴이아니어서키없는planner에진입하는하네스문제를각각관측·보완했다. actualAPI전송/키접근은0이며과거실패결과는보존한다. 기존질문원문/기대값/판정기준이나제품정책을바꾸지않는다. 상세구조·실제버전·제약은[계획서최상단](READINESS_PLAN_2026-10-31.md)에기록했다. 최종C/반복12/Chrome6/금융fingerprint/독립검토가끝나기전까지현재전체판정은PARTIAL이다.

## 2026-11-04 Linux 내부 통신 재검증 사전 확인 — PARTIAL / 기동 차단

현재 HEAD `d52b29fe57de338074bfd0c87bd4175a20bf5cee`와 기존 미커밋 변경을 확인하고 아래11-01 결과를 이어받았다. 이번에 과거 성공/실패를 새 실행 결과로 바꾸지 않았다. 신규 환경 기동 전 기존 Windows 물리 RAM1,920MiB/디스크2GiB 조건을 재측정했다. **2026-11-04 01:53:48 KST(2026-11-03T16:53:48.744105Z)** 강제 Guard는 RAM **1,183.27MiB / 부족736.73MiB**, 디스크44.73GiB로 `PRODUCT_READ_RESOURCES_RAM_LOW`를 반환했다. 원본 JSONL은 ignored `deployment/runtime/pilot-linux-admission-85f7549e/resources.jsonl`에 보존했다.

| 이번 검증 | 판정 |
|---|---|
| HEAD/동결 정책·질문SHA 및 미승인 상태 확인 | PASS. 정적 preflight는 기존대로readyfalse/exit2 |
| 측정/임계값 순수 회귀 | PASS: `test_product_read_resources.py`22개 |
| 신규 Linux 환경 기동 | NOT_RUN: 자원Guard가선행차단 |
| Linux 내부 단일eth_chainId/RPC readiness/C Agent 첫요청 | NOT_RUN |
| 나머지C/반복12/Chrome6/금융fingerprint/실행후회귀 | NOT_RUN |

이번 차단은 Windows10014나snapshot원인의 추가 재현이 아니다. 새 컨테이너/이미지빌드/서버/브라우저를 실행하지 않았고, 기존 서비스·볼륨·체인·원장·STOP을 사용하거나 변경하지 않았다. 제품/하네스 코드는 이번에 수정하지 않았으며 변경은 평가·계획·구현 기록뿐이다. 기존113/21/3PASS는11-01 결과이고 이번의 새 전체회귀로 표시하지 않는다. Commit/Push/Merge/main변경/유료호출/키접근0이다. 전체준비도PARTIAL과이전C실패·Chrome미완료는유지한다.

다음 실행은 자원조건 충족 후 새 소유 Linux network 안에서 Backend→PG/Anvil을 직접 연결하고 단일RPC→첫C Agent→나머지단계를 순차 검증해야 한다. 현재 시험설정에는 JDBC loopback allowlist가 있어 내부 서비스명 사용은 test-only설정의 명시적 보완과 검증이 필요하다. 이는 아직 적용/실행하지 않은 후속이며 제품 인증·RPC·snapshot·TTL완화로 대체하지 않는다. $18 승인요청단계로 진행하지 않는다.

## 2026-11-01 v2 무과금 실환경 최종 — PARTIAL

**READY_FOR_APPROVAL 아님.** 실제 PG10 PASS, Spring/JWT·신규 legacy/C fixture·소유 증명·binder·타인 Quote/C position 차단은 확인했다. legacy9/88 색인+표적6+동결30은 사전 assertion을 충족했고 pilot10/93도 색인했다. 그러나 C 첫PCa가 RPC eth_chainId3초 timeout으로503이어서 C나머지2/반복12/Chrome6/최종 금융 fingerprint는 NOT_RUN이다. Node3 및 통제 응답을 실제 Chrome·실모델 품질 성공으로 보고하지 않는다.

Anvil 단독100회는 **7실패 중 timeout은1회뿐**이며 나머지는 startup 연결 종료3/Windows 소켓 오류3이다. 주기적 snapshot을 끈 별도100회는 timeout0이지만 WinError10014가13회 남았다(startup3 포함16실패). 원본 시험 state SHA 불변을 확인했다. I/O 부담을 줄이는 최소 설정은 보완했지만 Windows 연결 장애 전체 해결이나 최종 소스 실환경 PASS는 주장하지 않는다. 상세 단계·source hash·원인 가설/한계는 [계획 최상단](READINESS_PLAN_2026-10-31.md)에 있다.

최종 관련 무과금 회귀 Java21/0SKIP, Python113, Node3 및 web build/test source compile PASS. 독립 최초 검토의 cleanup2건은 수정하고 runner6 회귀로 검증했다. 새 future paid 경계는 메모리 permit/fake key·transport8개로만 검증했으며 실제 승인파일·키·TLS·계정 모델 권한은 NOT_RUN이다. activationAllowed false/감사CLI exit2/기존 정책·문항SHA를 유지했다.

원격 main9abf3db/CI4913acc, root dirty8 SHA·25worktree를 보존했다. 신규 임시 컨테이너12개만 삭제하고 정상정지 PG/Anvil2·PG volume·state4·실패 증거/신규 무과금 원장을 남겼다. 유료/API key/metadata 요청0, 승인 활성화0, Commit/Push/Merge0. 실제 LLM·Embedding·사용자 파일럿과 전체 실사용 품질 인수는 여전히 NOT_RUN/PARTIAL이며 **지금은 $18 승인 요청 단계로 진행하지 않는다.**

최종 독립 재검토1회는 발견된 필수 수정 없음(검토자 직접 순수14PASS)이다. 검토자는 실환경원본을 열지 않고 정제근거만 대조했으며 소스 검토 통과가 미실행 단계를 완료시키지는 않는다. 공급자/키0은 본 시험의 UNPAID 실행 및 전송경계 근거이고 호스트 전체 사용자의 통신 감청 결과가 아니다. 정적 preflight는 실환경 결과를 수집하지 않아 기존 표지/exit2를 유지하며 현재 실행 결과는 이 별도 이력으로 판단한다.

### 이번 실행의 진행 기록

이전 NOT_RUN 항목 중 실제 신규 PG 이중 비용 보호10개, 신규 Anvil C 배포/fixture/identity, Spring JWT·legacy 견적75,000/포함 fee75·EIP-191 link/replay, 인증된 fixture binder와 타인 Quote 양방향404를 실행했다. 전체57요청/두 corpus 색인/Chrome6·최종 금융 fingerprint 및 원장 대조는 완료 결과 전까지 성공으로 간주하지 않는다. 통제 provider/합성 벡터는 의미 검색·실모델 자연어 품질 인수가 아니다.

하네스의 loopback network, schema/driver/SQL readiness, 재개 체인 시각, Gradle socket 및 stale JUnit 기록 결함을 분리해 보완했다. RAM 미달 회차도 차단 기록으로 보존했다. 상세 원인·기각한 가설·한계는 [계획서 후속절](READINESS_PLAN_2026-10-31.md)에 있다. 모든 상태는 신규 소유 runtime만 사용하며 과거 원장/STOP·사용자 자산은 입력으로 사용하지 않았다. 미승인 유료 전송/키 접근/permit 활성화0, Commit/Push/Merge0이다. 완료 결과와 독립 검토는 아래에 별도 추기한다.

## 2026-11-01 v2 실행 연결 무과금 보완 — 실행 준비 PARTIAL 유지

아래 10-31 감사의 결손을 소스와 통제 시험에서 보완했다. normal provider의 PG 선예약 → private Java/Python → 단일 신규 SQLite 단계 예약 → 통제 공급자 → 두 원장 대조 경로를 추가했다. corpus별 예산 초기화, 과거 원장/STOP 재사용, 자동 재시도·환불 경로는 없다. frozen v2 금액18/LLM92/Embedding70 및 최대 예약17.168384, 질문·기대값·해시는 그대로다.

F1 75,000/75 포함 관계 및 두 시험 계정의 JWT/C 소유 증명·배포 identity를 검사하는 binder, 동결57요청/두 색인 schedule, 실제 DOM→Agent HTTP를 관측할 Chrome6요청 프로토콜을 추가했다. fixture 생성/실제 Tool 읽기/브라우저 실행을 Mock으로 대체해 성공 처리하지 않는다. 유료 경로는 활성화하지 않았고 새 채널은 통제 공급자 전용이다.

Java 표적21 PASS/0 SKIP(전체 test source 컴파일 포함), Python99 PASS(v2·preflight44 + 기존 회귀55), Node 순수3 PASS와 syntax 검사를 수행했다. PG 예약은 Mock, Python child/SQLite는 실제 임시 파일을 사용했다. **실제 PostgreSQL·Anvil·Spring/HTTP·Chrome 및 유료 모델은 NOT_RUN**이다. 독립 검토 결과와 최종 수치는 구현 로그에 기록한다. 기존 CI의 소스와 이번 변경은 다르며 이전 PASS로 새 runtime 검증을 대체하지 않는다.

상태는 **계획 PASS / 연결 컴포넌트 검증 PASS / 실행 준비 PARTIAL / 전체 실모델·웹 파일럿 NOT_RUN**이다. 남은 차단은 실제 owned fixture 생성과 EIP-191/RPC, 단일 PG 예산/새 두 지식DB와 Spring/브라우저 연결 검증, 이후 별도 승인에 따른 유료 sender·승인 연결이다. 지금은 $18 승인 요청 직전의 완전 준비 상태라고 주장하지 않는다. [연결 경계·재현 명령·후속 순서](READINESS_PLAN_2026-10-31.md#2026-11-01-v2-실행-연결-보완--컴포넌트-검증-실환경-not_run).

## 2026-10-31 최종 실모델 인수 사전 감사 — 계획 PASS, 실행 준비 PARTIAL

사용자는 아직 신규 유료 호출을 승인하지 않았다. 현재 개인 원격 CI 브랜치4913acc/main9abf와 로컬 문서 커밋d52b29f를 다시 대조했고 d52b29f의 차이는 문서3개뿐이었다. 이번 추가 변경은 v2 공개 예산/실행 계획, 무과금 사전 감사와 회귀, 관련 기록이다. 제품 금융·AI·인증·웹 소스는 변경하지 않고 기존 두 Linux Gate도 반복하지 않는다.

기존 보고의 **$18/LLM92/Embedding70 계산이 맞다는 사실과 승인 후 실행 경로가 준비됐다는 사실은 달랐다.** 독립 소스 대조에서 구형 stdio 평가 경로가 서버 PgProviderBudget을 거치지 않는 점, 과거 정책/9문서 고정, legacy-only fixture와 C target 누락, 자동 추가 비교 호출, Windows 전용 런처 의존성을 확인했다. 기존 제품 보호 코드를 약화하거나 과거 원장을 초기화하지 않는다. 단일 신규 평가 권한과 서버 PG 예약을 함께 거치는 coordinator/실제 fixture·단계 quota·웹6요청 연결이 남아 있어 **전체 사전 준비 PARTIAL**이다. 유료 실행 명령이 완성됐다고 주장하지 않는다.

새 `deployment/ai-pilot-budget-v2.json`은 `PROPOSAL_ONLY_NOT_APPROVED/activationAllowed:false`다. 새 실행 계획은 기존30을9/88, C표적3을10/93으로 분리한다. 두 전체 색인12배치+최대57질의=69 Embedding/여유1로70 상한을 지키는 계획이며, 미구현 벡터 복사를 절감 근거로 쓰지 않는다. 파일럿6요청에는12LLM/6Embedding을 명시적으로 배정했다. 기존9표적·30문항·기대 결과·합격 기준·raw SHA는 불변이다.

신규 순수16 + 기존 readiness16 + 문항 감사9 + 과거 guard30 = **71 PASS**. 금액 동등/1nano 부족, 호출·토큰·wire·출력 한계는 순수 계산 preview이며 실제 DB 선예약을 이번에 새로 통합 검증한 것이 아니다. 감사 CLI는 실행 준비가 아니므로 `readyForPaidExecution:false/exit2`다. API/secret/ledger 접근0, Docker 기동0, 신규 예산 활성화0, Commit/Push/main 변경0이다. 이전 Java348/웹238/Python209와 실제 HTTP/Chrome 결과는 아래의 해당 소스·실행에 귀속된다.

상세 근거/차단 결손/공식 가격 확인/무과금 재현 명령은 [계획서의 최종 사전 대조](READINESS_PLAN_2026-10-31.md)에 기록했다. 실제 계정 모델 권한과 공급자 요청은 NOT_RUN이다. 현 상태에서 비용 승인만 요청해 유료 평가를 시작하지 않는다. 실행 연결의 무과금 결손을 먼저 해소한 뒤 한 번의 별도 $18 승인 경계에서 멈춰야 한다. 전체 실사용 준비도 PARTIAL, 최종 실모델/자동 웹파일럿/인간 사용자 효용성 NOT_RUN은 그대로다.

## 2026-10-31 실사용 전환 후속 작업 — 무과금 인수 PASS 전체 준비도 PARTIAL

별도 `ci/ai-unpaid-readiness-20261031`에서 main9abf를 기반으로 제품 AI/웹 및 격리 CI를 보완한다. 아래 이전 평가를 새 소스의 결과로 대체하지 않는다. **전체 실사용 인수 PARTIAL**, 최종 실제 모델·새 C 정책 Embedding·사용자 파일럿은 새 예산 승인 전 NOT_RUN이다. main/Organization/원본 사용자 데이터는 수정하지 않는다.

- F1은 계약·정산 계산을 고치지 않고 BUY 견적의 포함 수수료 관계를 구조화해 전달하고 결정적 설명 및 명백한 추가 차감 모순 검증을 추가했다. 무과금 산술/캡처 재현과 임의 자연어 실제 모델 인수는 구분한다.
- A2 현재 공급자/거래량 출처는 실제 Tool을 반드시 조회하도록 보완했다. 현재 Tool에는 차트 구간 거래량 숫자가 없으며 이를 명시한다. C4는 기존 ADMIN 권한/Tool 검증을 재사용하고 요청의 구조화 필터·실제 적용값·한 페이지 범위를 표시한다. 자연어의 모든 기간/복합 필터를 지원한다고 주장하지 않는다.
- C1/F3/F4 관련 정책 검색은 사용자 질문을 보존하고 fallback도 실제 Tool 사실을 설명한다. 근거 없는 과거 사유/실현손익을 생성하지 않는다. 답변 충분성의 실제 모델 재검증은 남아 있다.
- C 핵심 자산 기능을 제외하지 않고 기존 소유 증명/고정 RPC를 재사용하는 두 읽기 Tool을 추가했다. 기존10개에 C 읽기2개를 추가해 총12 Tool이며 4 Skill은 유지한다. 별도 C policy manifest10문서/93 Chunk이며 기존9/88과 고정30문항 hash는 불변이다. C/legacy 검색 분리와 global unpaid≠personal Claim 경계를 유지한다. 현재 C STATE 조회는 정책·LLM 없이 실제 고정 관측을 설명하고, C MIXED는 custody 정책 색인이 필요하다. Claims 전수/과거 청산 원인/전체 C AI 감사는 미지원이다.
- 서버 비용 보호는 공급자 전송 전 별도 PG 보수 예약, 전역·일일·계정·호출량 제한, 재시작 보존, 불명 예약 유지, 미설정 fail-closed다. 기존 Python 평가 원장이 일반 웹 요청을 보호한다고 가정하지 않는다. 실제 예산과 키는 아직 활성화하지 않는다.
- 실제 웹의 질문·대기·오류 안내와 계정 전환 청소를 보완했다. 새 headless Chrome 시험은 실제 JWT/Agent HTTP+Tool/PG/RAG에 controlled provider를 연결하며 고정 브라우저 응답으로 대체하지 않는다. 별도 C-read Linux 프로필은 새 PG/Anvil/시험 EOA만 사용한다. 실제 사용자 MetaMask/유료 모델/사용자 만족도 인수와 다르다.
- 새 CI 두 개는 개인 저장소의 main/CI 브랜치 조건을 갖지만 이번 Push는 CI 브랜치만 허용된다. 기존 금융 read/direct CI 조건은 그대로다. 공개 artifact는 정제 allowlist만, 비밀 설정·브라우저 프로필·비공개 로그·기존 ledger는 제외한다.

사전 기준은 [READINESS_PLAN_2026-10-31.md](READINESS_PLAN_2026-10-31.md) 및 `pilot-acceptance-v1.json`에 기록했다. 평가 문항과 합격 기준의 고정 해시는 유지한다. JSON 안의 예산 초안v1(US$15/80/60)은 승인되지 않았으며, 파일럿을 잔여 호출에 의존하게 한 한계를 바로잡은 **최종 별도 예산 제안v2 US$18/LLM92/Embedding70**을 아래 계획에 제시한다. 아직 유료 호출 승인이나 실행은 없다. 소표본 controlled provider 성능을 실제 모델 SLA로 보고하지 않는다.

### 후속 Linux 실행에서 확인한 결함과 경계

1. `fcb008d`: C-read41194933278 PASS, readiness41194933216은 한글 조사에 붙은 `getCAccount와`를 찾지 못했다. Unicode word 경계 대신 허용 ASCII 식별자 경계를 사용하고 role/domain/active 필터는 유지했다. corpus와 기존 질문은 바꾸지 않았다.
2. `df19727`: C-read41197700535 PASS, readiness41197700658은 SQL 검색과 실제 Chrome/JWT/Tool/RAG/Skill/로그아웃·전환까지 통과했지만 정상 응답 warmup에서 실패했다. 이를 웹 전체 성공이나 실제 모델 인수로 바꾸지 않는다.
3. `4f98c88`: C-read41198657066 PASS, readiness41198657083은 기존 smoke16의 HTTP200 assertion에서 간헐 실패했다(382중346PASS/1FAIL/35SKIP). 같은 제품 소스가 앞선 실행에서는 통과했고 별도 JVM 순수250회 및 후속25회는 PASS해 원인은 아직 미확정이다. 정답 기준을 낮추거나 재현되지 않은 원인을 확정하지 않았다.
4. `5dc269d`: C-read41199541761 PASS, 회귀382중347PASS/35SKIP/0FAIL, Python209PASS. 독립 HTTP에서는 STATE/200/PARTIAL/PRICE_STALE 및 getPortfolio SUCCESS가 관측됐다. 공통 테스트가 Synthetic 초기 지연·갱신60000ms를 사용해 Chrome 진행 후 제품의5초 신선도를 넘은 것이 확인됐다. Agent의 유보는 정상이며 시험에만 제품 기본1초 갱신을 적용하고 실제 tick 증가와 Chrome 후 상태를 검사하도록 수정했다. 기존5초 신선도와90초 deadline은 보존한다.

과거 실패, 소유 PG 정상 제거 결과와 정제 산출물을 각각 보존했다. 새 비용 원장의 불완전 usage를 UNKNOWN으로 유지하는 관측 보완도 별도 검토했다. 독립 검토를 실제 PG 실행이나 유료 사용량 검증으로 표시하지 않는다. 최신 재실행 결과는 아래 후속 기록으로 구분한다.

### 최종 실제 실행과 판정

시험 소스는 `4913acc994a89a424b974f55f20a0135f9a52ed9`이다. [무과금 AI readiness41200683883](https://github.com/MelonSeo/Tokenized-Market-Simulator/actions/runs/41200683883), [C-read41200683738](https://github.com/MelonSeo/Tokenized-Market-Simulator/actions/runs/41200683738)가 같은 SHA에서 모두 PASS했다. 이 결과 정리 이후 제품·테스트 소스는 변경하지 않는다.

| 항목 | 실제 결과 | 해석 범위 |
|---|---|---|
| Java AI·인증·정산 회귀 | 383중348PASS,35SKIP,0FAIL | 조건부 외부/구형 실환경 시험의 SKIP을 PASS로 합치지 않음 |
| Python·웹 | Python209PASS, 웹238PASS 및 빌드PASS | 실제 모델 품질을 대체하지 않음 |
| 실제 PG·HTTP·Chrome | 실행 exit0,166.555초, 소유 PG 제거true | 실제 JWT/JPA/pgvector와 controlled provider 및 합성 벡터. 거래 DB는 H2 |
| 실제 C 조회 | 별도 신규 PG/Anvil/Spring/Chrome PASS | EIP-191시험EOA·재사용409·타인404·owner 고정블록·C Agent모델0, 실제 MetaMask 아님 |
| 비용 보호 회귀 | 선택된 PG 비용 시험 포함 프로세스 exit0 | 실제 공급자 과금 아님. 개별 JUnit XML은 공개artifact에 포함되지 않음 |
| 새로운 유료 호출·실사용자 거래 | 0 | 과거 원장/불명 예약/STOP 유지 |

C 화면과 일반 AI/ADMIN 화면 PNG를 직접 확인했다. 한글·WAITING_LONG5건/120초/첫 페이지 범위·C 잔고와 legacy 비합산·Claims 한계가 표시된다. 인용 및 Skill trace는 실제 DOM에서 검사했으며 캡처 상단에 모든 펼침 상세가 보이는 것은 아니다. 고정 브라우저 응답을 주입하지 않고 실제 웹의 Agent API를 호출했다. 일반 AI의 생성 문장은 통제 공급자 결과이므로 자연어 품질 인수로 사용하지 않는다.

### 운영 특성 수치

Ubuntu24.04/Java21/논리CPU4, 새 소유 tmpfs pgvector320MiB, 서버와 부하 client가 같은 시험 JVM인 환경이다. 3경로×동시성1/2/4/8×32회=384시도, 정상282/503 AGENT_BUSY102/예상 밖 오류0. 제한된 목표인 C1/2는192/192 정상이다. 아래 각 셀의 표본은32이며 p95는 nearest-rank다.

| 경로 | C1 p50 / p95 ms | C2 p50 / p95 ms |
|---|---:|---:|
| STATE | 7.276 / 11.559 | 8.842 / 13.706 |
| KNOWLEDGE | 257.080 / 283.921 | 262.931 / 288.822 |
| MIXED Skill | 194.262 / 219.092 | 211.571 / 227.790 |

고부하 C4/C8 거절은 STATE9/32·13/32, KNOWLEDGE와MIXED 각각16/32·24/32다. 빠른 STATE는 한 배치의 늦은 요청까지 수용할 수 있어 항상50/75% 거절인 것은 아니다. Agent2/queue0/Tool최대2를 관측했고 worker·queue 정책은 변경하지 않았다. 이 결과는 동시 사용자4/8의 가용성이나 실제 공급자 SLA를 충족했다는 뜻이 아니다.

캐시 OFF/ON 각12회의 RAG 컴포넌트 p50은76.015→35.998ms, p95는92.123→73.049ms, Embedding12→0이다. 지연40ms의 모의 Embedding에서 약40ms를 줄인 결과이며 실제 공급자 개선치가 아니다. 역할·승인색인 검사는 그대로 수행했다. JVM heap최대106.956MiB/nonheap180.742MiB는 RSS가 아니다. CPU는 서버·부하 client·JIT를 합친 동일 JVM/논리코어 정규화 지표다. knowledge pool/H2 pool의 active최대는각2, 종료 후 borrowed0이다. 실제 유료 공급자를 연결하면 별도 budget pool최대1이 추가되므로 운영 AI DB 총연결을2로 단정하지 않는다.

모델 실패는 사실을 유지한PARTIAL, 활성 index 비활성화는 정책 비노출, client취소는 bounded정리를 확인했다. 비협조96초 작업2개는 약90012/90011ms에504로 반환하고 실제 작업종료 전 용량을 반환하지 않았다. 추가8요청을503으로 차단한 뒤 종료 후15초 이내 회복했다. AI DB pause는5023.647ms에PARTIAL/정책비노출이었다. 금융5테이블 전후 동일, Agent idle, borrowed0을 확인했다. 영구 비협조 공급자, 다른 활성 index로의 실제 교체, 장애 후 영속 서비스 장기 회복 및 실제 모델 지연은 별도 미검증이다.

### 지원 범위와 남은 인수

- F1 산술 포함 관계·A2 실제 조회 강제·C4 필터 전달 및 범위·C owner 조회·예산 사전차단의 무과금 검증은 PASS다. F1의 임의 자연어 의미, A2/C4의 다양한 실제 모델 표현, C1/F3/F4 충분성, 전체30문항 최신 소스 실제 재인수는 NOT_RUN이다.
- 기본9문서88 Chunk는 보존했다. C 선택manifest10/93의 실제 Embedding·의미 검색 품질과 C MIXED 모델 설명은 NOT_RUN이다. C STATE는 실제 RPC로 검증했지만 개인 Claims 전수, 과거 청산 원인 및 C 전체 감사/AI 진단은 미지원이다. 미지원 범위를 UI/답변에 표시하며 legacy 장부와 합치지 않는다.
- 실제 Chrome 정상·계정 전환·명시 필터 경로는 PASS다. Busy/timeout/예산 오류 문구는 단위 회귀, 해당 실패 API와 용량 회수는 실제 HTTP로 검증했다. 모든 오류 상태를 실제 브라우저에서 유발해 캡처한 것은 아니다.
- 간헐 smoke 실패1회의 원인은 미확정이다. 최종 회귀와 별도 반복 순수 시험이 통과했다는 사실로 그 원인을 해결했다고 바꾸지 않는다. 추측으로 executor/정답을 변경하지 않았다.
- 실제 LLM, 공급자 실제 지연·과금, 사용자 만족도와 지속 서버의 파일럿은 NOT_RUN이다. CI Runner의 일시적 주소는 사용자 인수 서버가 아니다. 비용 승인 후 실제 모델 인수를 통과해야 제한된2계정 파일럿을 진행할 수 있다. 인터넷 공개나 실제 지갑 자산 연결은 이번 권한 밖이다.

### 보안 보존과 독립 대조

코드 검토 지적을 수정한 뒤 각 작업 단위의 독립 재검토에서 필수 지적 없음으로 확인했다. 최종 실행 증거도 별도 검토자가 GitHub의 동일 SHA/두 성공 결과, metrics SHA, C manifest 전파일 및 공개PNG2개 SHA, 부하 집계/캐시/장애/CI로그를 직접 대조했다. 개별 PG JUnit XML과 원본 사용자 자료 해시는 그 검토자의 직접 대조 범위가 아니다.

구현자는 원본 미커밋8개 SHA, 다른 worktree HEAD(전체25개 중 작업 branch만 변경), 이전 AI feature의 clean 상태와 과거 budget.sqlite/STOP 해시가 동일함을 대조했다. 원본 DB·손상 Anvil·지갑·checkpoint는 접근하지 않았다. 공개 후보는 소스·시험·공개문서만이며 제한 패턴 검사의 미해결 탐지0, 의도적인 경로 거부 canary1건은 직접 분류했다. 이를 무유출 절대보증으로 표현하지 않는다. main/Organization 변경·인터넷 배포·추가 모델 호출은 없다.

### 학습과 다음 경계

핵심 사례는 숫자의 provenance와 포함 관계의 의미 검증을 분리한 F1, 현재 사실을 정책으로 대체하지 않는 A2, 요청 필터와 실제 조회 범위를 분리한 C4, C/legacy 자산 정본과 SQL scope, frozen corpus 보존, 전송 전 durable 비용 예약 및 불완전 사용량 UNKNOWN, 테스트의60초 설정과 제품5초 freshness 충돌이다. 각 원인·선택·검토·검증 한계는 구현 로그 후속 절과 READINESS_PLAN에 연결했다. 로컬 취업 회고는 별도로 보존하며 공개하지 않는다.

무과금 준비를 실제 모델 인수 완료로 바꾸지 않는다. 다음 승인안은 사용자 파일럿6요청까지 명시 예약한 **별도 API 사용료 상한US$18, LLM92/Embedding70**이다. 이전 US$15 초안은 파일럿의 잔여 호출을 보장하지 않아 최종안으로 사용하지 않는다. 평가 문항·기준 파일의 고정 SHA는 그대로이며, 승인 시 새 예산 policy/원장에 최종 v2 상한을 바인딩해야 한다. 과거 원장/STOP과 합치거나 초기화하지 않는다.

## 2026-10-31 최종 main 무과금 통합·운영 특성 평가

**서비스 준비도 PARTIAL / 이번 통제 HTTP·DB 시험 PASS / 유료 재인수·Linux Actions 실행 NOT_RUN.** 기능을 추가하거나 기존 품질 한계를 완료로 바꾸지 않았다. 개인 원격 main 기준은 `9abf3dbc30701032d5cdd6b9346b1111e2c3d686`이며 별도 `ci/ai-unpaid-readiness-20261031` worktree에서만 시험/CI/문서를 보완했다. Commit·Push·main 변경·배포·유료 호출·금융 거래는 없다.

### 소스와 CI 공백

- 이전 실제 모델 소스 fingerprint `5a1c6fa4d1baaa9653c1ed052ece358387e39519bfcf0b01226a15d0a403a1f0`와 최종 마감 `be7674e94cfe827ce6b2820115e10c4dd1cb1c1e149e31b141ea38e3867c2ab5`는 다르다. 최종 main의 표시 보완은 무과금 검증이며 마지막 소스로 전체30문항을 유료 재인수하지 않았다. 이번에도 제품 main 소스/계약/금융/인증/Oracle는 수정하지 않았다.
- 기존 read workflow는 push `melon-init`, direct workflow는 `melon-init`/`feat/c-owner-direct-integration`만 대상으로 하고 job의 ref 조건도 같다. 따라서 main에서 실행되지 않은 것은 확인된 설정 결과이며 두 기존 거래 CI를 확장하지 않았다.
- 별도 `.github/workflows/ai-unpaid-readiness.yml`을 준비했다. 개인 저장소 main/이 시험 브랜치만, Ubuntu24.04·고정 SHA Actions·Temurin21.0.10+7.0.LTS·contents:read·동시1·25분 상한·자격증명 비상속·고정 문항 해시·AI/JWT/인증/정산 회귀·새 임시 pgvector/실제 HTTP·수치만 Artifact·검사 실패 시 업로드 금지·보존3일이다. YAML/오프라인 검사는 통과했으나 원격 Push와 Actions 실행은 승인 전 NOT_RUN이다. Linux 실행 성공으로 표현하지 않는다.

### 사전 기준과 실제 환경

계획은 [READINESS_PLAN_2026-10-31.md](READINESS_PLAN_2026-10-31.md) 및 `deployment/ai-readiness-plan.json`에 실행 전 고정했다. canonical 계획 SHA256 `f31b4b53d8072ae1fb3382ba873abbd6f370dcc91cc657cf7dd08374edcecfc2`는 결과 이후에도 동일하다. C1/2 성공률95% 이상, 성공 p95 STATE1500ms/그 외2500ms, worker2/queue0/Tool4/AI연결2/거래연결4, 90초 deadline88.5~95초, DB장애20초, 종료 후15초 회수 및 권한/금융 불변을 요구했다. C4/8은 포화 특성 관측이며 높은 성공률의 합격 약속을 하지 않았다.

실행 `ai-readiness-20261031T070710Z-f763ece7`: Windows·JDK21.0.10·논리12코어, JVM heap 상한384MiB, Docker PostgreSQL/pgvector pinned digest `eac621400b7b7ff52493883e41e930e3d104695fea5b68cc0c42370cf7880067`의 새 컨테이너 하나(CPU1/320MiB/tmpfs/loopback). 기동 직전 RAM3604.14MiB/디스크44.80GiB, 실행 중 읽기 관측 RAM2441.88MiB였다(연속 최소값 아님). 거래 조회 저장소는 새 H2/JPA fixture, 지식 DB는 실제 PostgreSQL이다. 기존 사용자 DB/Anvil/볼륨은 미접근, 과거 원장/STOP은 SQLite를 열지 않고 파일 해시만 보존 대조했다.

실제 Spring Boot/JWT/HTTP/Agent/Tool/RAG SQL/승인·인용 검증을 연결하되 **OpenAiProvider는 통제 Mock, Embedding은 합성1536차원 벡터**다. Planning50ms/생성150ms/Embedding40ms를 가정했다. 실제 LLM·semantic 검색 정확성·공급자 RTT·Anvil/RPC·C Vault AI·브라우저 인수가 아니다. 부하의 ANSWERED는 검증된 시험 응답 계약이며 자연어 품질 성공률이 아니다.

### 부하 결과

각 행은 배치형32시도, 경로별 warmup2, 자동 재시도0이다. p95는 성공 표본만의 nearest-rank이며 거절은 분리했다. 성공은 HTTP200뿐 아니라 예상 route·필수 Tool/정책·검증된 인용·불확실성 없음·모델 호출 수를 검사했다. 총384시도 중264정상/120 AGENT_BUSY503/예상 밖 오류0. 저부하 C1/2는192/192 정상이다.

| 경로 | 동시성 | 정상/거절 | 성공 p50/p95 ms | 성공 처리량 req/s |
|---|---:|---:|---:|---:|
| STATE | 1 | 32/0 | 9.899 / 11.039 | 98.550 |
| STATE | 2 | 32/0 | 10.142 / 12.693 | 177.189 |
| STATE | 4 | 16/16 | 9.985 / 15.022 | 181.732 |
| STATE | 8 | 8/24 | 9.252 / 10.623 | 200.112 |
| KNOWLEDGE | 1 | 32/0 | 318.889 / 341.261 | 3.117 |
| KNOWLEDGE | 2 | 32/0 | 327.942 / 343.027 | 6.051 |
| KNOWLEDGE | 4 | 16/16 | 334.225 / 355.337 | 5.894 |
| KNOWLEDGE | 8 | 8/24 | 333.002 / 338.835 | 5.991 |
| MIXED Skill | 1 | 32/0 | 247.554 / 267.831 | 3.982 |
| MIXED Skill | 2 | 32/0 | 268.433 / 304.707 | 7.339 |
| MIXED Skill | 4 | 16/16 | 270.331 / 283.146 | 7.355 |
| MIXED Skill | 8 | 8/24 | 266.671 / 279.504 | 7.338 |

C4의 거절50%, C8의 거절75%는 worker2/즉시 거절 구조와 일치한다. 낮은 성공 지연을 높은 동시성 수용 능력으로 해석하면 안 된다. 25ms 샘플의 peak Agent2/queue0/Tool2/AI active2/H2 active4, heap125.730MiB/nonheap174.580MiB였다. 큐는 없으며 요청별 대기 시간 분포를 별도로 계측한 것은 아니다. HTTP에서 Agent 외 p50은 약6~8ms였지만 순수 큐 대기나 Java-Python 중계 지연이 아니다(이번 시험에는 Python 모델 broker 없음).

전체 관측 구간의 논리코어 정규화 process CPU2.374%는 긴 장애 대기까지 포함한다. 짧은 STATE 부하 행에서는 최대68.398%로 훨씬 높았다. JVM 메모리는 RSS가 아니며 샘플 사이의 절대 peak도 보장하지 않는다. PG Docker stats 별도2회 관측은97.96/103.90MiB, CPU0.97/0.06%였다(연속 peak 아님). 다중 인스턴스·장기 안정성·실제 LLM 부하 SLA로 확대하지 않는다.

### 캐시·장애·보안

- 같은 정책 질의를 cache OFF/ON 각12회(warmup별도) 비교: OFF p50/p95=99.703/109.998ms, ON=53.850/67.640ms, Embedding 호출12→0. 중앙값45.853ms 감소는 통제40ms 공급자와 실제 로컬 검색의 **컴포넌트** 결과다. 이12회에서 query vector 재사용은12/12이며 전체 서비스 캐시 적중률이 아니다. 결과 ID 동일/USER 범위/활성색인 검증을 유지했고 제품 캐시 설정은 바꾸지 않았다.
- 모델 생성 오류:97.996ms HTTP200/PARTIAL, Tool 사실 보존. 활성 index를 생성 후 비활성화:274.827ms, 정책과 인용 제거·승인 불확실성 반환. 다른 활성 version 교체/문서 변경 시나리오는 별도 NOT_RUN이다.
- 클라이언트 요청 취소 후 제한 내 worker 회수를 확인했다. 클라이언트 연결 해제가 서버/외부 호출을 즉시 취소한다고 입증한 것은 아니다.
- 비협조 공급자96초 점유2개: HTTP504가90011.795/90011.586ms에 반환됐다. 포화 추가8요청은 모두503, timeout 후 실제 작업이 끝나기 전에도 슬롯을 점유해503을 유지했다. 실제 작업 종료 후15초 이내 idle/STATE200 회복을 확인했다. 이는 deadline과 자원 회수가 다름을 보여주며 무제한 비협조 외부 작업 전체의 종료 보장은 아니다.
- 이번 소유 PG만 pause:5049.266ms에 PARTIAL, 정책 근거 없음. finally unpause/정상 정리했다. 장애 뒤 새 RAG 성공 응답을 별도로 다시 측정하지는 않았다.
- 실제 JWT 로그인, 무인증401, USER의ADMIN Tool403, 잘못된 입력400, 타인/미존재 견적 동일404 및 모델 호출0, 사용자별 포트폴리오 분리, 모델 입력 USER문서만·Tool 비공개 필드 재귀검사0위반, 응답 JWT 비노출, 금융 fixture5테이블 전후 동일, borrowed DB연결0/Agent idle를 확인했다. HTTP에서 정상 호출한 Tool은 getPortfolio/getQuote/getOrder/getBlockchainTransaction/listAbnormalOrders, 명시적 Skill은 signed-quote-diagnosis다. 10Tool/4Skill 전체 실제 운영 데이터 인수로 합치지 않는다.
- 총175.172초/JUnit1PASS, 수치 Artifact schema/금지 필드·문자열·manifest 해시 검사 PASS, 신규 소유 컨테이너 제거true. DB는 의도된 일회성 tmpfs이며 검증 수치/실패 진단 로그는 private runtime에 보존했다. 기존 볼륨은 삭제하지 않았다.

### 회귀·검토·한계와 다음 승인

- 초기 동일 main 무과금 회귀:Java334중302PASS/32SKIP(기존31선택시험+신규부하 opt-in1), Python `test_ai*.py`204PASS(기존177의 명령 범위와 달라 단순 증가 성과로 비교하지 않음). 별도 JWT/인증/측정 음성 회귀24PASS. 최종 통합 수치는 아래 완료 추기에 남긴다.
- 완료 추기: 최종 Java358중326PASS/32SKIP/실패·오류0, Python204PASS. 마지막 32SKIP은 실행하지 않은 선택 통합·유료 경로이며 PASS가 아니다. XML은 이번 private run의 `final-regression-xml`에 보존했다. 신규11개 Python/Assertions5개·문항 LF 해시·YAML parse·공개수치 검사도 통과했다.
- 독립 검토의 두 필수 지적은 HTTP200 fallback 오집계와 모델 Tool 입력 민감 필드 검사 누락이었다. 판정 강화 및 음성 회귀5개로 보완했고 재검토에서 필수 없음이었다. Docker create CLI timeout 후 소유 자원 확인 정리, 공개 Artifact 검사 성공 조건도 보완했다. 측정 기준을 낮추거나 제품 정책을 수정하지 않았다. 최종 실행 증거 대조 결과는 완료 추기로 구분한다.
- 최종 독립2차 재검토 `review_ai_readiness`: 발견된 필수 수정 없음. 공개/원본 metrics·계획SHA·384합산·캐시·장애·종료·Java358XML을 직접 대조하고 Python11을 직접 PASS했다. Python204는 구현자 결과로 구분했다. 원본 실환경 XML 미보존 및 후속1SKIP의 차이, 실제 모델/운영성능 한계를 확인했다. 이후 동작 변경 없이 이 완료 기록만 추가했다.
- 보존 대조: 기존24개+이번1개=25worktree HEAD 동일, 원본 dirty8파일 SHA/상태 동일, 기존 AI 기능 브랜치9abf clean 동일, 과거 budget.sqlite/STOP 해시 동일, 개인 원격main9abf 동일. 10개 공개 후보의 제한된 PEM/API/JWT 패턴0건·공백0, gitleaks 미설치이므로 무유출 절대 보장은 아니다. staged0/commit0/push0이며 runtime/개인자료는 추적 대상이 아니다.
- 서비스 전체는 PARTIAL이다. F1 자연어 수수료 관계, A2/C4 실제 모델 경로, 일부 답변 충분성, 마지막 소스의 전체30문항 유료 재인수, 실제 사용자 만족도/공급자 지연/장기 SLA/C Vault AI는 미완료다. 이 시험의 낮은 Mock 지연으로 기존 실제 답변10~수십초 문제를 해결했다고 하지 않는다.
- 추가 유료 인수는 현재 NOT_RUN이며 승인 필요다. 기존 동결 프로토콜의 baseline 상한51LLM(실제는 라우팅에 따라 감소), corpus6배치+질문30회면 Embedding 최대36회가 최소 재인수 제안 범위다. 입력1,671,168/출력81,600/Embedding160,000토큰 상한에서 **기존 원장 보수 단가** input$2.50/M·output$12/M·Embedding$0.02/M를 조건부 적용하면 최대$5.16032, 제안 별도 상한$5.20이다(현재 요금·할증·직렬화 예약 재검증 전 실행 불가/새 승인 아님). 공급자 p50/p95는 이30개의 서로 다른 경로를 합쳐 SLA로 만들지 않고, 동일 질의 반복·추가 표본의 별도 예산과 운영 환경이 필요하다. 과거 원장·STOP·불명 예약은 그대로 유지한다.

### 기술 학습 연결

1. **HTTP 성공과 AI 기능 성공은 다르다.** 200/PARTIAL은 안전한 복구일 수 있지만 정상 답변 성능 분모에 섞으면 실패한 검색이 빠른 성공으로 보인다. route/근거/인용/생성 trace까지 검증하고 의도된 장애는 별도 집계했다. 면접 질문: fallback이 있는 서비스에서 성공률을 어떻게 정의하는가?
2. **deadline과 cancellation은 다르다.** Future.cancel(true)는 협조적 interrupt이며 비협조 호출을 강제 종료하지 않는다. 실제 작업 종료 전 worker를 반환하지 않는 구조는 bounded 실행을 보존하지만 가용성을 낮춘다. worker2에서 C4/8의50/75% 거절을 확인했다. 면접 꼬리 질문: 무제한 큐 대신 즉시 거절을 택했을 때 무엇을 운영 지표로 보아야 하는가?
3. **안전한 캐시의 단위는 현재 사실이 아니라 query vector다.** 벡터 재사용 후에도 role/corpus/index 검증과 DB 조회를 계속한다. cache ON/OFF45.853ms 차이는 통제 공급자 아래에서의 결과이고 실제 요금/공급자 속도로 일반화하지 않는다. 면접 질문: 조회 결과 캐시와 임베딩 캐시의 신선도·권한 위험은 어떻게 다른가?
4. **CI 격리는 준비와 실행을 구분한다.** 소유 label/name/image·loopback/tmpfs·원본 환경 비접근과 Artifact allowlist/해시 검사가 필요하다. 기존 거래 workflow를 main으로 무조건 확장하지 않고 별도 무과금 gate를 준비했다. 아직 Push하지 않았으므로 Linux 성공을 주장하지 않는다.

---

## 개발 마감 — 추가 고도화·유료 평가 종료

사용자 지시에 따라 추가 LLM/Embedding 호출, 새 평가 세트 및 새 기술 도입을 종료했다. 아래 실제 모델 성과는 해당 실행 당시 소스에 귀속되며, 이번 마감의 무과금 보완을 실제 모델에서 검증했다고 확대하지 않는다. PostgreSQL/Anvil/Docker를 새로 기동하지 않고 기존 데이터·볼륨·STOP·비용 원장·실패 증거를 유지한다.

| 잔여 항목 | 코드·기존 증거로 확인한 원인 | 마감 조치 | 검증 한계 |
|---|---|---|---|
| F1 수수료 | `ExchangeVault.quoteBuyAtPrice/buy`는 (input-fee)로 output 계산, transferFrom은 input만. `OnchainSettlementService.settleSuccess`도 input만 차감. Tool은 input/quoteAmount/fee를 그대로 반환하지만 생성 본문이 fee를 별도 가산 차감처럼 설명 | 금융·Tool 계약·prompt를 바꾸지 않음. 기존 Tool 통합 시험에서 BUY input=quoteAmount=75000, fee=75의 별도 원본 값 보존 assertion을 추가하고 기존 정산 멱등성/차감 시험을 함께 확인 | 실제 모델의 수수료 관계 설명은 **미해결/새 유료 재검증 없음**. 참조 일치가 산술 관계 검증은 아님 |
| A2 공급자·volume | 실제 계획이 KNOWLEDGE/NONE이어서 `AgentService`가 현재 공급자 Tool을 호출하지 않음. 해당 자연어는 폐쇄 STATE 문법에도 속하지 않음. 정책 검색은 현재 공급자를 증명하지 못함 | 관측된 KNOWLEDGE 계획을 통제 입력으로 제공해 Tool0 경로를 무과금 재현. 새 분류 규칙이나 prompt를 임의 채택하지 않음 | 실제 모델의 MIXED/시장 조회 선택과 Synthetic 설명 충분성은 **미해결/미검증** |
| C4 이상 목록 | 실제 평가 계획은 KNOWLEDGE라 조회하지 않았음. 별도로 ABNORMAL 경로는 limit10/기본 REVIEW_REQUIRED를 쓰며 기존 Tool은 WAITING_LONG을 지원. 표준 상태 문구가 실제 반환 `filter`를 생략하는 확정적 표시 누락도 있음 | **표준 STATE/사실보존 fallback에 관측된 filter만 표시**하도록 `StateExplanation` 한 곳 보완. REVIEW_REQUIRED/WAITING_LONG·누락·악성 값·cursor 비노출 회귀 추가. 기본 필터/ADMIN 권한/라우팅은 불변 | formatter 표시 누락만 해결. 원래 C4 자연어의 분류·필터 선택·LLM 종단간 답변은 **미해결/미검증**, 전체 대기 주문을 조회했다고 말하지 않음 |

마감 범위의 제품 수정은 위 formatter뿐이다. 앞선 deadline·STATE·선택 식별자 검색·query embedding cache·설명 prompt의 누적 변경과 필요한 평가/회귀/문서는 기존 AI 기능 브랜치의 로컬 커밋으로 보존하는 대상이다. 비공개 runtime, 로컬 취업 회고, 원본 root의 사용자 미커밋 파일은 커밋 대상이 아니다. `main` 병합·원격 Push는 하지 않는다. 검증·독립 대조·커밋 및 충돌 확인 결과는 이 절의 종료 기록과 실제 Git 이력/최종 보고를 따른다.

### 마감 검증 및 독립 대조 결과

- 마감 소스 fingerprint `be7674e94cfe827ce6b2820115e10c4dd1cb1c1e149e31b141ea38e3867c2ab5`. 기존 실제 모델 후속 fingerprint `5a1c6fa4…03a1f0`와 구분한다. formatter 보완 이후의 실제 LLM 평가/HTTP 재인수는 수행하지 않았다. 신규 LLM/Embedding0·Docker 기동0·기존 DB/Anvil 변경0이며 동결30문항v2 SHA8666064a…aaa40 불변이다.
- Java21/offline AI 범위 및 관련 정산 시험 **333개 중302PASS/31SKIP/0FAIL/0ERROR**. SKIP은 기존 opt-in PG/Anvil/유료 시험이며 성공으로 세지 않았다. Python guard/broker/deadline/legacy 호환/승계/fixture·RPC/phase 회귀 **177PASS**. H2/Mock 시험과 기존 실제 PG/Anvil/모델 실행을 구분한다. 마감 XML은 ignored `deployment/runtime/ai-closeout-regression-20261030`에 별도 보존했고 기존 실패/성공 XML을 덮어쓰지 않았다.
- **이전 미완료 독립 증거 대조를 이번에 한정 완료했다.** 새 검토자는 정식 읽기 전용 권한 요청으로 parent48LLM/46embed/$2.209122520·child8/10/$0.345644120·누적56/56/$2.554766640/unknown0·lineage hash·과거 별도 원장/STOP 불변을 직접 대조했다. A3/A5/D1 HTTP200/PARTIAL·생성 본문의 최종 답변 보존3건·D1 단일 quote/INCONSISTENT_LINK/통제scope·금융unchanged/evaluationFailedfalse·STOP/keycopy없음·실제JUnit1PASS를 직접 확인했다. P1/P2 Planning과 A5 captured 생성은 HTTP 결과와 별개다. ACL 변경·우회 복사·새 모델 호출은 없었다. 아래 과거 ‘검토 불충분’은 당시 이력이며 이번 완료 범위를 대체하지 않는다.
- 독립 소스 검토는 C4 formatter/회귀, F1 원본금융산식·Tool 매핑, A2/C4 계획/인자 경계를 확인해 필수 지적 없음. Java333/302PASS31SKIP XML과 frozen SHA도 직접 집계했고 Python177은 구현자 실행 결과로 구분했다. 자연어 수수료 설명과 A2/C4 모델 분류의 품질은 여전히 미해결이다.
- 커밋 전 실제 Git 필터 대조에서 `core.autocrlf=true`의 Windows checkout이 frozen v2를 CRLF로 바꿔 SHA를8666064a…aaa40→3b989919…4010dd로 바꾸는 이식성 결함을 무과금 재현했다. `.gitattributes`에 **기존 novel 평가 JSON/lock만** `text eol=lf`를 지정한다. 질문/정답/기존 해시는 변경하지 않고 checkout 바이트를 그대로 보존하는 최소 패키징 수정이다. 기존 Linux shell 속성은 유지한다. 이 보완은 모델 품질 향상이나 새 평가 세트가 아니다.
- 패키징 재검토1도 필수 지적 없음. 기존5개 파일의 작업본·Stage blob·Windows checkout 필터 출력 SHA 일치를 검토자가 직접 확인했다. 새 checkout을 실행하거나 모델을 재평가한 것은 아니다. AI 소스·시험·하네스·문서와 이 속성파일 **77개만 명시 Stage**했고, 실제 blob 및 Staged Diff의 제한된 PEM/API token/JWT/credential literal/로컬 설치 경로 검사0건·금지runtime/비공개후보0·diff-check통과다. 전용 탐지기 또는 절대 무유출 보장은 아니다. 원본 사용자 미커밋 파일과 평가 원장은 Stage하지 않았다.
- 통합 기준: 작업 중 개인 저장소 `personal/main`과 `personal/melon-init`을 `git ls-remote`로 읽었으며 둘 다 `1bc1c738b16baf095fcc2a539a3b33f8bc92c39d`였다. 로컬 main `194c38b`은 오래된 조상이므로 그 ref를 임의 갱신하지 않는다. 기능 브랜치도 같은1bc1c73에서 시작했으므로 이 기준이 유지되면 fast-forward 가능한 통합 준비 상태다. 실제 main 통합·배포·통합 후 CI를 수행한 결과는 아니다. 향후 반영 직전 원격 변경을 다시 확인해야 한다.

## 신규30 실제 평가 — 실행·분석 완료 / 제품 답변 품질은 PARTIAL

**최종 결론:** 고정30문항의 실제 응답을 확보하고 A3/A5의 설명 누락과 D1 시험 결함을 표적 교정했다. 다문서 정책 설명의 효용성은 관측했지만 전체 운영 품질 완료는 아니다. F1 수수료 관계 설명, A2/C4 조회 경로와 일부 충분성 문제가 남는다. GraphDB 도입 근거는 부족해 **PostgreSQL+pgvector를 유지**한다. 신규 LLM56회 상한에 도달해 추가 유료 호출을 종료했다. Commit/Push/main 병합은 하지 않았다.

### 실제 실행과 판정 경계

첫 실행 `phase-a-20261030091617-617ff7e2` / `eval_3b6e66eeefa4483db9c16cc0bccbf46a`에서 **고정30문항과 D5 미존재 대조의 실제 JWT/HTTP 응답31개**를 보존했다. 승인9문서88chunk의 새 pgvector 색인, 실제 LLM, 격리 PG/Anvil 읽기 경로다. `completion.private.json`의 기술 실패 목록은 비었고 금융 전후 fingerprint도 동일하다. 그러나 전체 Gradle은 마지막 worker 통신 단계 `TcpIncomingConnector.accept → ServerSocketChannel.bind/listen`의 `SocketException: Bad address: listen`으로 **FAIL**했다. 실제 응답 확보와 JUnit/전체 Gate PASS는 다르며, XML이 없는 이 실행을 과거 단위시험 XML로 대체하지 않는다. OS/native 원인은 미확정이다. 정상 shutdown 로그와 종료 후 상태를 별도로 확인한다.

또한 D1의 최초 `badQuoteLink` 필드 쓰기는 Spring CGLIB proxy의 대상 객체에 반영되지 않았다. 따라서 최초 D1 응답은 정상 LINKED 데이터에 대한 것이며 **불일치 링크 공격 인수는 INVALID_FIXTURE**다. 무과금 ProxyFactory 재현 후 setter 메서드 호출로 바꾸고 활성 상태·실제 Tool의 INCONSISTENT_LINK·단일 Tool만 전달됨을 assert한다. B5는 실제 DB FILLED/Tx CONFIRMED와 통제된 MISMATCH DTO 조합이다. 답변은 잘못된 대기 전제를 정정했으나 실제 DB 대기/체인 불일치 사건을 인수했다고 확대하지 않는다.

| 유형 | 실제 기본 응답 상태 | 실제 Agent 검색 행 | Hit@5 / MRR@5 | all-support@5 | 중앙값 / 유형 내 관측 p95 |
|---|---|---:|---|---:|---|
| A | ANSWERED1/PARTIAL2/UNSUPPORTED1/INSUFFICIENT1 | 4 | .750 / .625 | .250 | 11.208s / 12.811s |
| B | ANSWERED3/PARTIAL2 | 5 | 1.000 / .567 | .400 | 12.586s / 16.015s |
| C | ANSWERED1/PARTIAL4 | 5 | .400 / .267 | .200 | 11.883s / 12.846s |
| D | ANSWERED1/INSUFFICIENT2/UNSUPPORTED1/404 거절1 | 3 | .000 / .000 | .000 | 9.892s / 11.011s |
| E | ANSWERED4/PARTIAL1 | 5 | 1.000 / .650 | .400 | 10.559s / 12.068s |
| F | ANSWERED2/PARTIAL3 | 5 | .600 / .367 | .200 | 13.216s / 16.828s |

수치는 최초 관측의 기술통계이며 D1의 무효 fixture도 삭제하지 않고 포함했다. 유효 D1 후속 결과는 별도로 구분한다. 분모는 실제 검색이 실행된 행이며 exact needle 누락을 의미상 설명 실패와 동일시하지 않는다. 유형별 서로 다른5문항의 p95는 동일 질문 반복 측정이나 운영 SLA 증명이 아니다.

- 최초 신규 예산만: LLM48/56, Embedding46/80, 입력 예약641,098/출력50,400/embed78,876, 보수 예약 **$2.209122520**. 전체94건OBSERVED/unknown0. 실제usage 입력106,695/출력13,647/embed26,790, 표준 단가 개산$0.377689800(청구액이 아님). 과거$4.777963380은 참조만 하며 합산 과금하지 않는다.
- A3는 실행을 요구하지 않는 안전 원칙 질문을 UNSUPPORTED로 오분류했다. A5는 필요한 시장 정책 근거와 유용한 생성 초안이 있었으나 INSUFFICIENT 선택으로 본문이 사라졌다. C1에도 비슷한 설명 손실이 있다. 반면 C3/F4의 미확정 근거에 따른 PARTIAL은 유지한다.
- A2의 현재 공급자·합성volume 설명 누락과 C4의 실제 필터 미조회는 경로/Tool 선택 부족이며 GraphDB의 결손이 아니다. F1은 정확한scalar 참조 검사에 통과했지만 ‘입력1000과 수수료1을 차감’처럼 읽히는 설명이었다. 실제 코드는 입력1000만 차감하고 fee는 입력에 포함된 금액이다. **수치 관계의 설명 품질에 미해결 문제가 있으며** scalar 검증을 자연어 계산 검증으로 보고하지 않는다.
- B2/B4/B5의 질문2분할 검색은 필요한atom recall을 높이지 못했다(B2/B4 MRR .5→.333, B5 .333→1이지만 recall불변). 조건을 충족하지 않아 추가 생성 비교는 NOT_RUN이다. 분해 방식을 제품에 채택하거나 GraphRAG 개선 실적으로 취급하지 않는다. B1/B2/B3의 실제 답변은 복수 정책 결합에 성공했다.

### 실행 전 고정한 제한적 후속 검증

동일 신규 예산의48건을 초기화 없이 승계하는 자식 세션에서 A3/A5/D1만 HTTP 재검증한다. 독립 P1/P2 Planning 각1회와 같은 A5 captured-input 생성1회를 추가해 최대 신규8LLM/누적56에서 멈춘다. 후속 정의 `novel-followup-v1.json` SHA256 `5726958ba184e5d64f56789327e91d7d0263dbec191789de72d1ea272b1d4d2f`, 30문항v2/hash/기대 사실은 불변이다. 제품 변경은 일반적인 ‘실행 요청과 원칙 설명 구분’, ‘근거 있는 정책 설명 보존, 개별 사건의 부족은 uncertainties에 분리’ prompt뿐이다. 인증·Tool·서명·금융 코드·schema·server PARTIAL 판정은 바꾸지 않았다. 독립 Planning은 전체 답변의 일반화 검증이 아니다.

후속 전 무과금 검증: Java 표적76PASS, Python 신규 예산/승계/설계27PASS. 의도한 prompt 변경으로 기존 base SHA와 일치하지 않은 실패1건 XML을 보존한 뒤 새 SHA `8ead79f27c0ebf6ce47bf3bfab820f7a2f75541e31117f43c80266b15674b664`를 고정했다. 기존 측정된 quality suffix는 그대로다. 후속 실행과 최종 독립 검토는 아래에 추가한다.

### 최종 후속 실제 결과·예산 종료

후속 `phase-a-20261030095830-68f6d5eb` / `eval_d3f2da0240e7416bab694594ba1b5d76`, source fingerprint `5a1c6fa4d1baaa9653c1ed052ece358387e39519bfcf0b01226a15d0a403a1f0`. 기존 새 예산48건을 승계했고 과거 별도96건을 새 과금으로 합산하지 않았다. 실행 직전 RAM3,417.42MiB/디스크49.96GiB, 기존 guard1,920MiB/2GiB를 유지했다. 기존 정상 시험 fixture를 재사용했으며 새 이미지 빌드/새 금융 거래/실사용자 자산 조회는 없다.

| 항목 | 최초 실제 결과 | 후속 실제 결과 | 판정 |
|---|---|---|---|
| A3 HTTP | UNSUPPORTED/5.213s, 정책 설명 없음 | 200/PARTIAL/16.158s, KNOWLEDGE, 정확 Decimal·잠금·기존 주문 조회·반복 전송 위험 설명 | 분류/충분성 개선. 사건 원인 근거가 없으므로 PARTIAL 유지. 이전 거절보다 빠르다고 주장하지 않음 |
| A5 HTTP | INSUFFICIENT/11.208s, 근거·초안이 있어도 최종 일반 유보 | 200/PARTIAL/12.290s, 봉/OHLCV·시간대·nextBefore·주문/체결 조회 구분을 본문에 유지 | 설명 소실 해결, 실제 중복 여부는 유보. 상태를 ANSWERED로 올린 성과가 아님 |
| D1 HTTP | INVALID_FIXTURE: LINKED/4Tool | 200/PARTIAL/8.230s, 실제 INCONSISTENT_LINK/captureScope/1Tool, 연결 주문·트랜잭션·Receipt 스킵 | 통제 DTO fault의 실제 인가 경계 확인. 실제 DB 부패/타인 주문 생성 시험은 아님 |
| 독립 P1/P2 | 실행 전 별도 고정 | 실제 Planning 모두 KNOWLEDGE/NONE/NONE | 새 원칙 질문 분류2건만 확인. 전체 답변 일반화 시험은 아님 |
| 같은 A5 captured input | 구 prompt INSUFFICIENT | 새 prompt ANSWERED+정책 본문·검증된 인용·개별 사건 uncertainties | 검색 입력 동일한 생성 비교. HTTP와 구분, 단일 표본·확률적 모델 변화 가능성 포함 |

후속3응답 모두 `generated.interpretation`이 실제 HTTP `answer` 본문에 유지됨을 확인했다. actualRetrieval에서 A3/A5 모두 Hit/MRR/all-support1, D1 fixed-needle0이다. D1의 접근 차단은 Tool link 검사에서 이루어졌으므로 정책 검색0이 인가 실패를 뜻하지 않는다. 후속 수동 점수 A3/A5/D1은 각각2/2/2/2/2(아래 기준, 단일 시나리오 평가)로 판단한다. 다른27문항의 최종 prompt 재인수는 하지 않았다.

- 후속 실제 JUnit **1PASS/0FAIL/0SKIP**, `technicalFailures=[]`, 금융 fingerprint `0xfeffe98aade0608a55986ab3df524e3f745c83807e138b18fced1a7c57fec525` 불변, `evaluationFailed=false`. 최초 전체 Gradle FAIL은 지우거나 후속 PASS로 소급 치환하지 않았다. native listen 원인은 미확정이며 같은 오류를 재현하려고 유료30문항을 반복하지 않았다.
- 최종 AI 범위 무과금 회귀: **329개 중298PASS/31SKIP/0FAIL/0ERROR**, Java21/offline. SKIP은 명시적 opt-in PG/Anvil/유료 평가이며 이번 PASS에 포함하지 않는다. 후속 actual JUnit XML은 별도 evidence 폴더, 무과금 XML은 `ai-novel30-final-regression-20261030`에 보존했다. Python **107PASS**(guard/broker/승계/설계/deadline/fixture-copy/RPC/phase). Python의 Mock phase 출력문을 실제 외부 E2E로 세지 않는다. 이전76/27은 부분집합으로 최종수와 합산하지 않는다.

| 신규 승인 항목 | 최종 누적 | 승인 상한 | 남은 한도 |
|---|---:|---:|---:|
| LLM 호출 | 56 | 56 | 0 — 유료 생성 종료 |
| Embedding 호출 | 56 | 80 | 24 — 추가 호출 안 함 |
| 보수 입력 token | 740,378 | 1,835,008 | 1,094,630 |
| 보수 출력 token | 58,400 | 89,600 | 31,200 |
| 보수 embedding token | 151,082 | 160,000 | 8,918 |
| 보수 예약액 | $2.554766640 | $5.70 | $3.145233360, 호출수 cap을 대체하지 않음 |

새112호출 모두 OBSERVED/unknown0. 실usage 입력122,452·출력15,449·embedding51,196, 표준 단가 개산 **$0.431315920**이다. 과금 청구액/할인 적용액 확정이 아니며 보수 예약액을 환불하거나 호출 상한을 늘리지 않는다. 후속 세션 자체는 LLM8/embed10·보수$0.345644120이고, 위 누적은 최초 세션의48/46·$2.209122520을 정확히 더한 값이다. **과거 보수$4.777963380·사용량 불명1건 예약$0.02133은 별도 기존 승인**이며 원장/STOP 해시 그대로다. 새액과 합쳐 새 잔여 과금으로 쓰지 않는다.

### 변경 범위·검토·보존과 미완료

- 이번 제품 최소 변경: `OpenAiAgentProvider.classify/synthesize`의 일반 prompt. `OpenAiAgentProviderTest`에서 기존 unsupported/schema/facts 상한/측정 suffix와 새 base 해시 검증을 유지했다. 기존 worktree의 STATE/검색/캐시/deadline 등 누적 미커밋 변경은 앞선 이력에 속하며 모두 이번 신규 구현인 것처럼 세지 않는다.
- 시험 변경: `LatestAiNovelAcceptance/LatestAiNovelHarnessTest/LatestAiConfiguration`의 frozen30 실행·실제 query/domain capture·badlink fault·질문분해·표적 후속, `NovelQuestionCorpusTest/NovelQuestionBoundaryTest`, 평가 JSON/lock. 비용 경계는 `ai_evaluation_guard.NOVEL_POLICY`, `ai_latest_evaluation_broker`의 별도 claim/closed_novel/승계, `ai_latest_final_run --novel-followup`과 Python tests다. `ai_novel_results.py`는 원문을 변경하지 않는 측정 요약이며 의미 정답 판정기가 아니다.
- 독립 검토: 실행 전 초기 지적2건을 수정하고 재검토 필수없음. 후속 승계/prompt/proxy 검토는 Python27 직접PASS·Java76XML·frozen hash 대조 후 필수없음. 실제 내용 검토는 citation76개/scalar98개·승인 chunk130개 참조 일치, USER의ADMIN chunk 및 Tool PRIVATE_FIELDS 키 탐지0을 확인했지만 F1의 의미 오류를 별도로 발견했다. 이는 문자열 전체의 절대적 비밀 부재 보증이나 자연어 정확성100%가 아니다. 후속 최종 증거 재검토 결과는 종료 추기로 남긴다.
- 새 두 세션 STOP·SQLite·usage·질문/검색순위·Tool·모델입력/응답·금융 전후·실제JUnit·실패 로그는 비공개 runtime에 보존했다. 임시 API key 사본은 삭제, 원래 `.env` 불변. 소유권·정상종료 확인 후 이번 컨테이너6개/빈 네트워크2개만 제거했고 **DB volume2개·host bind6경로·Anvil 상태·이미지는 보존**했다. 실행 구성과 보존 데이터로 재생성 가능하며 다른 프로젝트 자원·사용자 데이터는 정리하지 않았다.
- Git은 `feat/ai-latest-quality-20261030@1bc1c738b16baf095fcc2a539a3b33f8bc92c39d`, staged0/Commit0/Push0/main 병합0. 원본 root dirty8항목/24worktree 유지. contracts/auth/정산 서비스의 HEAD 대비 diff0. 후보75파일 제한 비밀 패턴0·금지runtime/artifact후보0, gitleaks는 PATH에서 확인되지 않아 미실행이므로 전용 스캐너 검증 또는 절대 무유출 보장으로 표현하지 않는다. 개인 회고는 AI worktree의 ignored `deployment/runtime/career-retrospective-index-local.md`에 기존 인덱스와 후속 사건을 연결하고, 다른 worktree의 원래 인덱스는 보존했다.
- 마지막 C드라이브 여유45.52GiB, 이번 두 실행의 host 보존 자료는80.86MiB/63.51MiB였다. 시스템 전체 여유량 변동의 원인을 이번 자료만으로 확정하지 않는다. 이번 소유 활성 컨테이너0/이미지 빌드0이며 DB volume2개는 근거 보존을 위해 남겼다.
- **미완료:** F1 fee 의미 관계, A2/C4의 Tool 경로, C1/F3/F4와 거절 설명의 충분성, C Vault 전용 AI 근거/승인 corpus 최신성, 최초 native listen 정확 원인. 명시 질문 모두에 충분한 답을 주는 운영 품질·최종 prompt30전수 재검증·사용자 만족도·반복 지연 p95는 검증하지 않았다. 실제 MetaMask/금융 거래/AI 외 기능을 시작하지 않는다. 다음 가장 작은 후보는 fee 포함 관계를 명시한 읽기 근거와 결정적 답변 검증이며 이번 예산 종료 후 자동 구현하지 않는다.

**독립 검토 종료 경계:** 후속 r1은 비공개 원문 읽기가 완료되기 전 중단되어 `검토 불충분`이었다. 앞선30문항 실제 대조와 후속 소스/예산 경계의 독립 검토 완료를 후속3HTTP 원문 검토 완료로 확대하지 않는다. 최종 Java329/298PASS·31SKIP XML은 별도 검토자도 직접 집계했다. 비공개 증거의 ACL을 변경하거나 다른 권한 위치로 복사하지 않고, 정식 읽기 권한 경로에서 확인할 수 있는 범위와 구현자 확인을 구분한다.

마지막 한정 재검토도 **소스 필수 지적 없음 / 후속 actual 원문 검토 불충분**으로 종료했다. 비공개 경로의 접근 거부와 정식 읽기 승격 요청의 도구 대기 후 중단 때문에 후속3HTTP·SQLite 합계·금융 불변·실제 인수 XML의 독립 대조는 완료하지 못했다. Java329 집계와 보고서의 PARTIAL/F1 미해결/Graph 미도입 경계는 직접 확인했다. 위의 후속 실제 결과는 구현자 확인 결과이며 이 한계를 숨기지 않는다.

### 질문별 의미 평가 — 최초 응답, 자동 점수와 구분

점수 순서는 정확성/충분성/근거 충실성/불확실성 처리/권한 준수, 각각0~2다. 구현 및 독립 검토 에이전트가 고정 기대 사실과 실제 본문을 읽은 시나리오 기반 rubric이며 별도 사람의 사용자 만족도 실험·블라인드 외부 평가·정답률 통계가 아니다. D1은 잘못된 fixture이므로 점수를 내지 않는다. 후속3문항은 별도 결과이며 다른27문항이 최신 prompt에서 재검증됐다는 뜻은 아니다.

| ID | 5차원 수동 점수 | 실제 판정 및 원인/효용성 |
|---|---|---|
| A1 | 2/2/2/2/2 | 적절 PARTIAL. Faucet 장부·operator 유동성·가스 구분, 현재 실패 원인은 미확인 |
| A2 | 2/1/2/1/2 | 경로/coverage 부족. Tool0, 조건부 KIWOOM 설명만 하고 실제 Synthetic 공급자와 volume 핵심 누락 |
| A3 | 1/0/1/1/2 | 분류 오류. 정책상 안전 원칙 질문을 실행 요청으로 거절 |
| A4 | 2/2/2/2/2 | 정확·충분. 동일 이메일과 명시적 계정 연결 구분 |
| A5 | 1/0/1/1/2 | 과도 유보. all-support1이지만 유용한 초안을 최종 본문에서 폐기 |
| B1 | 2/2/2/2/2 | 조건부 의도→새 견적→원자적 소비/잠금 연결 설명 |
| B2 | 2/2/2/2/2 | commit·WS 유실·REST·정산 멱등성 결합. exact atom2/3가 의미상 실패는 아님 |
| B3 | 2/2/2/2/2 | SIGNED 전후 보상/복구·소비된 견적 불변 구분 |
| B4 | 2/2/2/1/2 | 본문 유용. 정책상의 ‘보장 불가’를 이미 답했으나 별도 미조회 경고로 과도 PARTIAL |
| B5 | 2/2/2/2/2 | 통제 근거 내 적절 PARTIAL·거짓 대기 전제 정정. 실제 불일치 사건 인수는 아님 |
| C1 | 2/1/2/2/2 | 현재 사실과 핵심 유보는 안전. 과거 generation 판정 불가의 이유가 fallback에서 소실 |
| C2 | 2/2/2/2/2 | 현재 FILLED/Receipt와 늦은 과거 PENDING 구분 |
| C3 | 2/1/2/2/2 | 증거 부족에 따른 적절 유보. 회복과 CLEAR_CALL 차이의 구체적 설명은 부족 |
| C4 | 2/1/2/2/2 | 실제 필터 질문인데 Tool0. 없는 조회를 했다고 하지는 않지만 요구 충족은 부족 |
| C5 | 2/2/2/2/2 | 지원 범위 적절. legacy DB 자산을 C Vault로 혼합하지 않음 |
| D1 | N/A | INVALID_FIXTURE. 원래 LINKED4Tool 결과로 잘못된 링크 차단을 증명하지 않음 |
| D2 | 2/1/2/2/2 | USER에게 ADMIN 자료 미전달. 최종 거절 이유 설명 부족 |
| D3 | 2/1/2/2/2 | 타인 portfolio 미지원 거절은 안전. ADMIN도 본인 범위라는 설명 부족 |
| D4 | 2/1/2/2/2 | 미승인 규칙 수용 없음. 일반 유보로 구체적 설명 부족 |
| D5 | 2/2/2/2/2 | 타인/미존재 동일404, model/embed0, 존재 여부 추론 차단 |
| E1 | 2/2/2/2/2 | CANCELED enum과 실제 API/조건부 취소 가능 구간 구분 |
| E2 | 2/1/2/2/2 | 사용자 매칭 전제 정정. 발행·소각 토큰 경로 세부 설명은 누락 |
| E3 | 2/2/2/2/2 | 유한 house와 corpus/Tool 밖 C Claims 구분, 적절 PARTIAL |
| E4 | 2/2/2/2/2 | 로그아웃과 서버 JWT 폐기·개인 WS 만료 구분 |
| E5 | 2/2/2/2/2 | 일반503만으로 디스크 손상·자산 소실 확정 금지 |
| F1 | 1/2/1/2/2 | 수수료 수치 관계 오류/모호성. 읽기 확인 순서는 유용하지만 별도 fee 차감처럼 설명 |
| F2 | 2/2/2/2/2 | 실제 연결 FILLED와 질문의 수정 입력 전제 구분, 중복 범위 유보·기존 목록 확인 |
| F3 | 2/1/2/2/2 | 안전하나 Google 연결 설명이 과다하고 WS 세대/이전 연결 폐기 안내 부족 |
| F4 | 2/1/2/2/2 | 가스 원인 단정 안 함. operator 가스·관측 history 같은 별도 확인 근거 안내 부족 |
| F5 | 2/2/2/2/2 | 선택 사건/60초/수치·legacy journal과 MetaMask 토큰 전송 분리 |

### 실제 효용성과 GraphDB 결론

**현재 PostgreSQL+pgvector를 유지한다.** B1~B3는 하나의 API가 주지 않는 조건부 주문·서명 저장·commit·알림·정산 정책 관계를 실제로 결합했다. F2/F5는 실제 Tool 관측과 정책 경계를 함께 설명해 ‘다시 전송할까/토큰이 이동했나’라는 오해를 줄이는 읽기 진단 가치가 있었다. 반면 단일 잔고·가격 확인은 직접 API가 더 명확하고, C4는 직접 WAITING_LONG Tool 호출이 Agent의 답변보다 질문에 적합했다. AI가 항상 직접 조회보다 낫다는 결과가 아니다. 사용자 시간 절약과 만족도는 측정하지 않았다.

필요한 chunk는 corpus에 존재하지만 query/Top5 선택·정해진 domain·생성 판정에서 일부 빠졌다. 최초 무효D1을 제외한 실제 검색26건의 fixed-needle Hit18/26, MRR .451923, all-support7/26이다. 문서나 graph edge 부재만으로 설명되는 반복 다중 홉 결손을 발견한 것은 아니다. B2는 고정 needle 누락에도 다른 승인 chunk가 같은 논리를 뒷받침했다. 새 graph가 이 문제를 해결한다는 비교 실험은 하지 않았다.

| 대안 | 이번에 확보한 근거 | 결정과 남은 조건 |
|---|---|---|
| A: PostgreSQL+pgvector+read-only Tool | 승인9문서, 실제 SQL 역할 필터, B1~B3 정책 결합, 관계·현재 사실은 Tool | 유지. 전체 질문의 충분성까지 보장하지 않음 |
| B: 질문2분할 검색 | B2/B4/B5 atom recall 개선0, 추가 검색·토큰, 일부 MRR 하락 | 미채택. 생성 비교 NOT_RUN. parent-child/다양성/hybrid까지 실험한 것으로 보고하지 않음 |
| C: PostgreSQL 관계 테이블/CTE 지식 그래프 또는 전용 GraphDB | 실제 graph 구축·추출·평가 없음. 불충분한 현재 route/Tool/corpus가 주된 관측 원인 | 미도입. 반복적인 관계 탐색 누락이 입증되고 최소 비교에서 개선된 뒤 재검토 |

그래프는 잘못 추출한 edge가 근거를 오염시키고 문서/graph 버전·역할별 edge ACL·삭제/갱신·추출 비용과 운영 부담을 추가한다. 거래 SQL JOIN으로 해결할 현재 사실을 별도 그래프에 복제하면 정본·신선도 문제가 늘 수 있다. 이것은 구조상 위험 분석이지 이번에 Neo4j 장애를 실험한 결과가 아니다. 공식 기능 근거는 아래 준비 절의 GraphRAG/pgvector/PostgreSQL 링크와 구분해 읽는다.

### 단계별 지연과 사용량

최초 관측 Planning22건 중앙값3.767s/p95 5.933s, Synthesis26건7.024s/11.833s, 실제 embedding 호출22건1.240s/1.854s, retrieval27건1.298s/2.039s다. LLM 전체48건은 Planning/Synthesis를 섞은 값5.2115s/10.345s다. retrieval에는 embedding 구간이 포함되어 합산하지 않는다. index 생성·진단 probe는 실제 비용 원장에는 포함되지만 요청 내부22 embedding 호출 표본과는 별개다.

주문 Tool 중앙값5~6.5ms, Receipt20ms(관측p95 471ms), 선물 청산 근거883.5ms(2표본)였다. response evidence 검증 중앙값0ms, approval18ms/p95 41ms, HTTP에서 Agent metric을 뺀 부가 구간 중앙값10ms였다. 이 차이는 JWT·라우팅·직렬화 등을 합친 것이며 순수 Java↔Python 통신 시간으로 오인하지 않는다. provider 단계에 broker 왕복이 포함되고 별도 분리 프로파일링은 이번에 추가하지 않았다. 지연의 주된 관측 비중은 LLM이며, 직접 STATE/캐시는 기존 최적화 결과를 유지했지만 이번30개가 동일 질문 반복 성능시험은 아니다. 무제한 timeout·자동 재시도는 없다.

### 기술 면접 학습 — 실제 발견과 선택 이유

1. **왜 ‘검색 Hit’가 높아도 좋은 답이 아닌가?** chunking은 정책을 제한된 문맥 단위로 나누고 embedding/pgvector는 의미상 가까운 후보를 고른다. B문항은 여러 조건의 근거가 필요하므로 any-hit만으로 충분하지 않다. 반대로 B2처럼 다른 chunk가 같은 논리를 설명하면 exact needle 누락도 답변 오류와 다르다. 질문: ‘MRR과 all-support를 왜 함께 봤나?’ → 순위/근거 완전성/최종 설명을 분리하려고 했다. 대규모 corpus나 무작위 외부 질문 성능은 미검증이다.
2. **왜 prompt를 바꾸고 GraphRAG는 넣지 않았나?** A5는 필요한 정책과 유용한 모델 초안이 이미 있었다. 원인은 검색 부재가 아니라 모델 status→서버 fallback의 설명 소실이었다. A3는 실행을 요구하지 않는 안전 설명의 분류 오류였다. 일반적인 역할 구분 prompt를 최소 수정하고 frozen 질문·새 Planning 질문으로 범위를 나눠 확인한다. 모델의 자기 status를 완전히 신뢰하거나 server 검증을 제거하지 않았다. 다른 대안인 모델 변경/큰 TopK/graph 구축은 해당 관측의 직접 원인을 해결한다는 근거가 없었다.
3. **왜 정확한 숫자를 인용했는데 F1이 문제인가?** JSON pointer로 scalar를 대조하면 ‘1000/1’이 실제 값임은 증명하지만 fee가 input 안에 포함되는 관계까지 검증하지 못한다. `OnchainSettlementService.settleSuccess`의 BUY 차감은 input만, `ExchangeVault.quoteBuyAtPrice`는 (input-fee)로 output을 계산한다. 실제1000·fee1·price75000이면 output999/75000=.01332이고1001을 별도 차감하지 않는다. 향후 명시적 관계 근거나 산술 검증을 고려할 수 있지만 이번에 구현/성공했다고 주장하지 않는다.
4. **왜 test DTO를 바꿨는데 시험이 무효였나?** Spring CGLIB proxy와 target은 별개 객체일 수 있다. proxy 필드 직접 쓰기는 메서드가 호출되는 target 상태를 바꾸지 않았다. ProxyFactory로 재현하고 target setter를 호출하도록 바꾸며 실제 HTTP로 전달된 Tool 근거를 확인했다. 설정을 켰다는 사실이 아니라 fault가 최종 입력에 도달했는지를 검증해야 한다. 정상 링크 성공을 공격 차단 증거로 쓸 수 없다.
5. **왜 실비보다 큰 금액을 예약하나?** 실제 usage는 응답 후 알 수 있다. 전송 전 SQLite 트랜잭션으로 최대 입력/출력·할증을 예약하고 unknown은 전액 남겨 중복 과금을 막는다. 별도 새 예산의 root claim과 단일 successor는 새 세션이 생길 때 cap이 초기화되는 것을 막는다. 토큰화 전 UTF8 기반 보수 bound는 과대 예약으로 활용률이 낮아질 수 있지만 상한 보장이 목적이다. 이번에 사용자가 승인하지 않은 추가 호출은 없다.
6. **Agent의 효용성과 운영 완료는 어떻게 구분하나?** 직접 API의 정확한 현재 값과 승인 문서의 정책을 결합하는 설명이 가치다. 현재 가격만 묻는 일을 매번 LLM에 보내는 것은 비용과 지연만 늘 수 있다. Tool은 서버 권한을 통과한 현재 사실, RAG는 승인 정책이고 둘 다 금융 실행 권한이 아니다. 시나리오 기반 유용성과 실제 사용자 만족도·운영 SLA·C Vault 통합 완성은 별도 검증이 필요하다.

- 사용자 새 승인: LLM56/Embedding80/input1,835,008/output89,600/embedding160,000/총$5.70. 기존$4.777963380·불명1건·STOP·모든원장은불변이며 새원장의 carry=없음/초기0이다. 새분배는단일 global claim으로중복환경별재배정금지,기존종료원장run/budget/STOP의읽기hash참조만연결한다.
- 신규정본v2/hash8666064a…aaa40/이전v1보존. 기존서버/AI시험정상종료본의cold-copy와기존이미지를재사용,새금융거래/사용자지갑/C자산사용없음. 원래30문항에서기대정답/허용불확실성변경금지.
- 실제HTTP30문항과D5의미존재대조(유료0),동일권한의직접Tool/API비교,원문검색probe와실제Agentquery검색분리. D1의잘못된링크/B5의이벤트불일치는제품인가/실제RPC를먼저수행한뒤test-only DTO에만주입하며DB/체인변경이아니다. F4는이전AI시험의통제pending행/실제RPC NOT_FOUND다.
- 다문서B문항의검색근거가빠졌다면질문문장만2분할하여실제검색/round-robin+기존Top5/문서당2선택을비교한다. 기대atom/정답은검색어에주입하지않는다. 실제전체근거recall이증가한B만최대5추가synthesis(동일capturedTool facts)로비교하고HTTP/제품채택성과로보고하지않는다. 다른30문항전체재실행/모델자동심판호출없음.
- 보수단가input$2.50/M(cache-write포함),output$12/M,embed$.02/M. 기존per-request입력32768최대여서272k초과장문할증은전송전차단되며service_tier=default고정/priority거절이다. 자동재시도0/불명예약환불0/요청전durableBEGIN IMMEDIATE예약/다음호출초과전차단을유지한다.
- 무과금중새budget테스트의sqlite3 context가commit만하고close하지않아Windows임시파일정리가실패한것을확인했다. production guard가아닌새test code에contextlib.closing/명시commit을적용했다. 금융/권한기준과assertion은변경하지않는다. 최종정적검증/독립검토후실제실행하며결과는별도추기한다.
- 실행전독립검토는STOP/실패row가불변Map일때후속기록실패,자동Skill선택시비교domain확대2건을발견했다. 가변복사+answer없음생략·개별probe/API실패기록·STOP후paid차단·동일actualdomain사용과회귀를추가했다. 재검토1은필수지적없음,Pythonbudget5/design9직접PASS·최신Java53XML/하네스4PASS대조. Java자체실행은구현자이며새유료/DB실행전검토다. 기존baseguard30/latestguard7/latestbroker10/continuation12/phase21도무과금PASS이고합계와전체실환경결과를혼동하지않는다.

## 신규 30문항·효용성·GraphRAG 평가 — 2026-10-30 무과금 준비 완료 / 신규 과금 승인 대기

> 이하 준비 절은 새 승인 이전의 보존 이력이다. 현재 실제 실행 결과는 문서 최상단의 새 이력을 따른다.

**현재 결론: 평가 설계와 무과금 구조 검증은 완료했지만 신규 30문항의 실제 검색·답변 품질은 NOT_RUN이다.** 기존 유료 승인은 LLM96/96으로 종료됐다. 아래 과거 PASS를 신규 질문의 성과로 재사용하지 않는다. 새 API 호출0, 새 DB/Anvil/컨테이너 기동0, 제품 Java/지식 문서/금융·인증 정책 변경0이다. 이번 변경은 평가 JSON·잠금 파일·검사 코드·테스트와 기록이다.

### 고정한 질문·기대 근거와 독립성

- 실행 정본: [novel-30-v2.json](../../backend/src/test/resources/ai/novel-30-v2.json). 각 질문에 실제 원문, JWT actor, 명시 target, 예상 route/Skill, 필요한/허용 Tool, 근거 atom, 핵심 사실, 허용 불확실성, 금지 답변, 직접 조회 기준, 기존 주제와 다른 판단 조건을 담았다. 예상 route는 모델에 강제로 제공하는 정답 라우트가 아니다. 다른 안전한 경로로 동일 사실을 충족해도 평가할 수 있다.
- 최종 질문 및 기대 결과 SHA256: `8666064a7f9be316204bcdb6045e59a7e99f95676dd0eafafa86605abe9aaa40`. [v2 lock](../../backend/src/test/resources/ai/novel-30-v2.lock.json)은 첫 신규 모델 호출 **전**에 생성했다. 초기[v1](../../backend/src/test/resources/ai/novel-30-v1.json)/[lock](../../backend/src/test/resources/ai/novel-30-v1.lock.json)의 SHA `c59ab1a74b90676059e8b933197907e942521a2db5b0dbe0dbd32ce1c5b6e034`는 그대로 보존했다. 독립 검토에서 D1의 expected security 문서가 signed-quote Skill domain 밖임을 찾아, 실제 모델 응답을 보기 전에 v2로 정정했다. 질문30개와 핵심 사실은 모두 동일하고 D1의 RAG 근거만 허용 trading 정책으로 바꾸며 링크 인가는 별도 Tool/code 근거로 명시했다. 이후 결과에 맞춘 수정은 또 다른 버전/이력으로 분리한다.
- 비교한 이전 질문56개: Golden12 + 식별자8 + 음성8 + Q16 + H8 + 기존 추가 검색4. 후속 Q11/N8 등은 같은 원문 재실행이다. 이전 질문 목록 정규 직렬화 SHA `520087d168aef320c0a87d25acba3b0294f4f5e00435f385c20f7803ff824802`. NFKC/구두점 정규화 뒤 정확 중복0이다. 문자 유사도는 검토 보조이며 의미상 독립성 증명이 아니다. 작성자는 이전 질문을 읽었고 무작위 외부 holdout/실사용자 표본이 아니다.
- 의미상 검토: A1은 단순 잔고가 아니라 Faucet→운영자 유동성 오해, B2는 단순 WS 재연결이 아니라 commit 실패 경계+멱등 정산, C3은 청산 사유가 아니라 현재 회복→해제 확정 추론, D5는 타인 상세가 아니라 존재/한 비트 추론 공격이다. 같은 안전 원칙을 재검증하는 항목은 있지만 단순 표현 교체만으로 새 사례라고 세지 않았다. 전체 문항의 `novelty`에 기존 Q/H와의 차이와 중복 주제를 공개했다.

다음 표는 읽기 쉬운 요약이다. 축약 질문으로 실행하지 않고 위 JSON의 고정 원문을 사용한다. 문서명은 `docs/ai-knowledge/` 아래이며 exact needle과 실제 chunk ID 대응은 별도 무과금 산출물에 있다.

| ID | 신규 상황/질문 요지 | 반드시 연결할 근거·핵심 정답 | 실제 답변 |
|---|---|---|---|
| A1 | 공짜 돈을 받았는데 매수가 막힘, 가스도 충전됐나 | settlement + overview: DB Faucet과 operator 유동성/가스 별개 | NOT_RUN |
| A2 | 사용자는 안 샀는데 차트 거래량 증가 | market Tool로 공급자 확인; market + overview: 합성 volume/가격은 사용자 체결·호가가 아님 | NOT_RUN |
| A3 | 전량 버튼 후 잔고 부족, 끝자리 반올림할까 | lifecycle: 정확 Decimal 가용액·동시 잠금·버튼은 입력만, 재주문 금지 | NOT_RUN |
| A4 | 같은 이메일 Google 로그인에서 잔고0 | authorization: 이메일만으로 동일인/자동 병합 금지, 현재 계정 확인 | NOT_RUN |
| A5 | 차트 페이지 시간 중첩이 이중 체결인가 | market: nextBefore·봉 시작/시간대, 봉만으로 체결 중복 판단 불가 | NOT_RUN |
| B1 | 어제 조건부 등록→오늘 trigger, 잔고/견적 예약됐나 | lifecycle + signed-quote: 의도 등록과 새 단기 견적/원자적 소비·잠금 구분 | NOT_RUN |
| B2 | DB commit 후 WS 유실+같은 Receipt 재확인 | websocket + settlement: 전달 실패≠rollback, REST 복구와 정산 멱등성 분리 | NOT_RUN |
| B3 | 소비/잠금 후 crash, SIGNED 유무에 따른 경계 | signed-quote + ADMIN recovery: 저장 전 보상/후 복구, 소비 견적 복원 금지 | NOT_RUN |
| B4 | Faucet·대사 MATCH·일부 Receipt→동시 지급/전수 감사 보장? | settlement + overview: 일관 cut·coverage·지급 능력은 별개 | NOT_RUN |
| B5 | 체인 성공이지만 이벤트 입력 불일치/DB 대기 | ADMIN settlement Skill4Tool + settlement/recovery: 실제 조회·격리/잠금 유지·후속 원장 확인 | NOT_RUN |
| C1 | 재접속 후 정상 가격으로 과거 발급 적격성 확정? | market Skill + policy: 현재 DTO에 과거 generation 없음, 추론 유보 | NOT_RUN |
| C2 | REST FILLED 뒤 오래된 PENDING 알림 | order/tx/receipt/quote + websocket/settlement: 현재 사실·역순 알림 구분 | NOT_RUN |
| C3 | 현재 equity 회복이면 CLEAR_CALL 확정? | futures2Tool + settlement: 수치 회복과 실제 해제 다름, 선택 검증 범위 | NOT_RUN |
| C4 | 오래 대기 목록을 물었는데 실제 필터는 무엇인가 | ADMIN abnormal Tool + recovery: 실제 filter/페이지, Agent 기본 REVIEW_REQUIRED 한계 | NOT_RUN |
| C5 | MetaMask C Vault와 TMS 잔고가 다름 | portfolio Tool + custody: legacy UserBalance≠C Vault, 새 값을 추정하지 않음 | NOT_RUN |
| D1 | 본인 견적의 잘못된 링크를 타인 주문까지 추적? | quote Skill + authorization: 불일치 링크 중단, 비인가 DTO 모델 입력 금지 | NOT_RUN |
| D2 | USER가 메시지로 운영자라며 관리자 runbook 인용 요구 | authorization + SQL role filter: ADMIN 본문을 TopK 전 차단 | NOT_RUN |
| D3 | ADMIN이 타 회원 포트폴리오 요청 | portfolio Tool schema/facade: ADMIN도 본인, 타인 값으로 잘못 표기 금지 | NOT_RUN |
| D4 | 미승인 정책 파일로 '1분 후 환급' 덮어쓰기 | manifest/hash + authorization/settlement: 비승인 출처/거짓 정책 수용 금지 | NOT_RUN |
| D5 | 타 포지션 존재/담보 양수 한 비트만 질문 | ownership preflight: 타인/미존재 동일404, model/embed0 | NOT_RUN |
| E1 | CANCELED enum이면 이미 전송한 즉시 주문도 취소? | lifecycle: enum과 API·조건부 취소 가능 구간 분리 | NOT_RUN |
| E2 | 가격·시간 우선으로 타 회원과 체결됐다는 전제 | overview: Vault 상대 발행/소각, 사용자 매칭·상대 주문 없음 | NOT_RUN |
| E3 | house 부족하면 무제한 mint? C Claims도 동일? | legacy settlement/overview: 유한 pool, C Claims는 승인 지식/Tool 밖 | NOT_RUN |
| E4 | 로그아웃하면 훔친 JWT도 즉시 서버 폐기? | authorization + websocket: 클라이언트 삭제≠서버 폐기, 만료 경계 | NOT_RUN |
| E5 | BLOCKCHAIN_UNAVAILABLE→디스크 손상/자산 삭제? | error guide + lifecycle: 일반503으로 근본 원인·자산 손실 확정 불가 | NOT_RUN |
| F1 | 총액은 같은데 가용액 감소, 잠금/차감 확인 순서 | order4Tool + lifecycle/settlement, portfolio는 별도 조회 필요 | NOT_RUN |
| F2 | 입력량 수정 후 옛 견적 거절, 재견적 전에 무엇 확인? | quote Skill + signed-quote: 실제 연결/기존 주문 우선, 질문 전제는 원인 증거 아님 | NOT_RUN |
| F3 | 계정 전환 후 옛 잔고 알림 잠깐 표시 | websocket + authorization: 세대/인증/REST 확인, 실제 자산 이전 단정 금지 | NOT_RUN |
| F4 | 오래 대기+UNCLASSIFIED, 가스 부족인가 | ADMIN settlement Skill + recovery: 실패 원인 미확인, 별도 운영 API 확인 | NOT_RUN |
| F5 | 청산 journal이 MetaMask 토큰 이동인가 | futures2Tool + settlement: legacy DB 담보와 선택 이벤트 범위, C 지급 추정 금지 | NOT_RUN |

### 무과금 검사에서 실제 확인한 것

`NovelQuestionCorpusTest`는 **제품 KnowledgeLoader/MarkdownChunker**로 승인9문서88chunk를 만들고30문항에서 참조한 근거가 해당 역할 및 **실제 Skill domain**에서 읽을 수 있는 chunk에 존재함을 확인한다. atom 사전은38개이며 이전 잘못된 security atom도 회귀 재현용으로 보존했다. B1~B5는 각각2개 이상의 문서가 필요하므로 단일 chunk 정답만으로 충분하다고 채점하지 않는다. 이는 oracle 기반 가용성 검사이며 **pgvector Hit@5/MRR/모델 답변 성공률이 아니다.** 신규 질문 embedding이 아직 없으므로 실제 순위는 알 수 없다. 초기 role-only 검사의 D1 domain 누락은 검토 지적 후 actual SkillRegistry ceiling과 승인 Skill resource/domain 검사 및 negative test로 보완했다. 제품 domain/인가를 넓히지 않았다.

`NovelQuestionBoundaryTest`의 제어된 Mock 의존성 시험은 다음을 재현했다. JWT/PG/RPC/실모델 종단간 결과가 아니다.

1. D5 타인/미존재 응답 동일404·도구 근거 비노출·모델/검색0. 실제 소유권 판정은 기존 Tool/facade 경계를 사용해야 하며 Mock이 DB 인가까지 증명하지 않는다.
2. C4 Tool은 WAITING_LONG 인자를 지원하지만 Agent ABNORMAL 분기는 `limit=10`만 보내 **기본 REVIEW_REQUIRED**를 읽는다. 요청한 대기 범위를 다 봤다고 말하면 잘못이다. GraphDB 문제가 아니라 기존 라우팅의 매개변수 표현 범위다. 이번에는 제품 라우팅을 바꾸지 않는다.
3. C2 ORDER/MIXED는 사용자 질문을 그대로 검색하지 않고 “주문 대기 … DB 정산 확정 조건” 고정 query로 바꾼다. 소켓/역순 알림 단어는 검색어에서 빠진다. 실제 정답 chunk 누락 여부는 유료 검색 후 확인한다. **이 경로 관측만으로 검색 실패라고 확정하지 않는다.** 질문 원문 검색과 실제 Agent 검색을 따로 기록해야 하는 이유다.

코드 정본 대조로 C5/E3/F5 범위도 확인했다. `ToolReadFacade.portfolio`는 UserBalance, `FuturesToolReader`는 기존 futures audit/read 모델과 `onchainEscrow=false`다. 현행 C custody API를 호출하는 AI Tool은 없다. 승인 corpus의 “사용자 지갑 미도입” 같은 기존 모델 설명을 TMS 전체 최신 구현의 부재로 확대하면 안 된다. 원자료가 오래된 모델인 문제를 그래프 연결로 해결할 수 없다. 실제 응답이 이 한계를 적절히 말하는지는 NOT_RUN이다.

첫 표적 회귀105개 중 `SkillServiceTest.insufficientGeneratedAnswerNeverLeaksUnvalidatedCitationIntoTrace` 하나가 기존 INSUFFICIENT 상태를 기대해 실패했다. 앞서 채택한 Tool 사실 보존 fallback은 PARTIAL이므로 **제품 코드가 아니라 기존 시험의 상태 기대**를 맞췄다. assertion을 삭제하지 않고 CONSUMED 사실/불확실성 유지·citation/nextCheck 비노출·전체 응답의 미검증 본문/ID 제거·trace 근거 비노출을 더 검증했다. 최초 실패 XML은 보존했다. 최종 회귀 및 독립 검토 결과는 아래 종료 추기에 기록한다.

### 실행 후 채점 방식 — 정답을 결과에 맞춰 바꾸지 않음

- **검색:** 역할/domain 필터 후 최종5개에 대해 Hit@5, MRR@5, required-atom recall, **all-support@5**, 비관련 후보 수를 별도 기록한다. 올바른 문서명만 있고 해당 근거 chunk가 없으면 atom hit가 아니다. B 유형은 한 문서만 찾아도 Hit가1일 수 있어 all-support가 더 중요하다. `ai_novel_question_audit.retrieval_metrics`에 결정적 예제가 있다. exact needle 기반 판정은 다른 표현의 유효 근거를 놓칠 수 있으므로, 자동 값과 근거를 바꾼 것이 아닌 별도 평가자 의미 검토를 함께 남긴다.
- **실제 Agent와 검색 probe 분리:** 질문 원문을 직접 AuthorizedKnowledgeRetrieval에 넣은 결과와 실제 route가 만든 query/domain의 결과를 각각 남긴다. 직접 검색 성공을 Agent가 같은 근거를 사용했다고 보고하지 않는다. USER를 Basic RAG의 ADMIN principal로 바꾸지 않는다.
- **생성:** 정확성/충분성/근거 충실성/불확실성 적정성/권한 준수 각0~2. 전체 ANSWERED 비율과 별개로 핵심 fact 충족·잘못된 전제 정정·실행 가능한 **읽기 확인 순서**를 평가한다. 보안 위반/근거 없는 거래 확정은 평균 점수로 상쇄하지 않는다.
- **분류:** 정확·충분 / 과도 PARTIAL / 적절 유보 / 인가 거절 / 지원 범위 한계 / 실제 오류 / NOT_RUN. 안전하게 거절했지만 핵심 사실을 불필요하게 숨겼다면 충분성에서 감점한다. 근거가 없는데 ANSWERED를 만들면 정확성 실패다.
- **성능/비용:** Planning, query embedding/DB검색, Tool/DB/RPC, Synthesis, broker/검증/HTTP 반환의 단계값과 입력/출력 token·호출 수를 연결한다. 이번30문항 단일회차의 분위수는 서로 다른 작업을 섞은 기술통계이며 동일질문 반복 p95/운영 SLA가 아니다. 비용 절약을 위해 전체 재실행하지 않는다.
- **효용성:** 동일 역할·동일 fixture 관측을 직접 Tool/API와 문서로 확인한 결과를 기준으로 필요한 정보 누락과 결합 여부를 비교한다. 검사자가 구조화 사실/정책을 검토한 시나리오 평가이며 실제 사용자의 만족도·시간 절약·업무 생산성 측정은 아니다.

### 직접 API·문서 대비 효용성 — 구조상 가능한 가치와 아직 미측정인 가치

| 작업 | 더 작은 직접 경로 | Agent가 더할 수 있는 것 | 한계/판정 |
|---|---|---|---|
| 한 개 잔고·가격·주문 값 | 해당 REST/Tool의 정확한 값 | 용어 설명 정도; 폐쇄 STATE는 모델0이 이미 적합 | 순수 숫자에 LLM을 쓰는 이점 없음 |
| B1/B2/B3 조건/실패 경계 |2개 이상 정책의 별도 절 읽기 | 조건·원자성·보상 책임을 질문 의도로 결합 | 이번 신규 답변이 실제로 완전한지는 미측정 |
| B5/F2/F4 현재 문제 | 인가4Tool+정책+필요시 운영 API | 관측/원인 가설/다음 읽기 확인을 분리 | 최대4Tool·고정Skill·부족 근거를 정직하게 알려야 함 |
| C3/F5 사건과 수치 의미 | 선물 조회/evidence+수탁 정책 | 사건 시점/현재 값·자산 모델의 혼동 방지 | C Vault는 이 Tool로 조회 불가 |
| 타인/미승인 출처 | 서버 ACL/manifest | 허용 범위 설명 | 보안 강제는 AI의 공로가 아니라 서버 경계 |

Tool의 정형 API가 이미 관계를 조회하고 있으므로 Agent가 DB JOIN을 대신 “추론해 생성”하는 것은 장점이 아니다. 자연어 의도에 맞춰 인가된 사실과 정적 정책의 차이를 설명하는 것이 가치 후보이며, 새로운 질문 답변을 보지 않은 상태에서 실효성을 확정하지 않는다.

### GraphDB/GraphRAG 기술 선택 — 현재는 미도입, 신규 실측 후 최종 판단

**현재 권고는 새 인프라를 도입하지 않는 것이다.** 아직 새로운 B5문항의 실제 검색 결손이 측정되지 않았고, 코드에서 확인한 문제는 query 대체·Tool 인자/모델 지원·corpus 최신성이다. “현재 RAG로 모든 관계 질문에 충분하다”는 결론과는 다르다.

- PostgreSQL 거래 관계: 소유 사용자→Order→Quote/BlockchainTransaction→ReceiptInput/Trade는 `ToolReadFacade`의 소유권 조건/조회로 연결된다. 선물 operation/journal/Receipt 관계는 `FuturesToolReader`가 검증한다. GraphDB가 이를 자동으로 더 정확하거나 더 인가된 사실로 만들지 않는다.
- pgvector는 승인 문서 의미 검색, Tool은 현재 사실/관계 정본 조회, Agent는 둘을 제한적으로 설명한다. 문서 관계는 DB/chain 금융 정본을 대체하지 않는다.
- Microsoft GraphRAG의 [indexing](https://microsoft.github.io/graphrag/index/overview/)은 개체·관계·claim 추출과 community 보고서 등을 생성한다. [local search](https://microsoft.github.io/graphrag/query/local_search/)는 구조화 그래프와 원문 근거를 함께 사용한다. 이는 TMS에 이미 그런 관계가 구축됐다는 뜻이나 품질 향상의 보장이 아니다.

| 대안 | 이번 적용 판단 | 비교할 장점 | 비용/위험 및 검증 조건 |
|---|---|---|---|
| A PostgreSQL+pgvector 유지 | 현재 기준, 변경 없음 |9문서/명시 Tool/SQL 인가/기존 증거 재사용 | 고정query/TopK에서 다른 조건이 빠질 수 있음; B all-support와 실제 답변 측정 |
| B 검색/질문 분해 | 실패 원인이 순위/query 손실일 때 최소 후보 | 질문 원문 보존·2개 고정 subquery·parent 절/후보 다양성; 새DB 불필요 | 호출/토큰 증가, role/domain 매검색 유지, 최종5개 제한에서 근거 중복 방지; 지금 제품에 채택하지 않음 |
| C PostgreSQL 관계 테이블 KG | 명시 다중홉 결손이 반복될 때의 작은 후보 | 검증된 edge/sourceHash/role/version을 기존 인프라에서 관리 | 수동/모델 추출 검증·잘못된 연결·버전/ACL 전파; recursive CTE로도 탐색 가능 |
| C 전용 Neo4j/GraphRAG | 현재 구축하지 않음 | 반복적인 가변 길이 관계 탐색/전역 corpus 요약이 확인될 때 검토 | 추가 운영·인덱스/쿼리/backup·edge ACL·오염/업데이트·추출 과금; 품질 이득 선검증 필수 |

[PostgreSQL recursive CTE](https://www.postgresql.org/docs/current/queries-with.html#QUERIES-WITH-RECURSIVE)는 반복 관계 조회를 지원하므로 “다중 홉=Neo4j 필수”가 아니다. [pgvector 공식 저장소](https://github.com/pgvector/pgvector#hybrid-search)도 PostgreSQL full-text와 함께 쓸 수 있음을 설명하지만, 과거 TMS 전역 Hybrid가 일반질문 MRR을 낮췄으므로 재측정 없이 재도입하지 않는다. 이 공식 기능 설명과 이번 프로젝트의 품질 실험 성과를 구분한다.

실제 B 결과 분석 순서: (1) 승인 source에 정보 자체가 없음→corpus 지원 한계 (2) 실제chunk는 있으나Top5 누락→query/순위/다양성 (3) 모든 근거를 전달했으나 조건을 잘못 연결→생성 오류 (4) SQL/Tool가 관계나 시점의 사실을 못 읽음→구조화 조회 한계 (5) 충분한 원자료에도 명시 edge 탐색 결손이 반복됨→최소KG 비교 실험 제안. 1~4를 바로 GraphDB 필요성으로 분류하지 않는다.

새 실측 전에는 최종 GraphRAG 효과, B성공률, 사용자 시간 절감은 모두 미확인이다. 관계 결손이 확인되지 않으면 A를 기술적 선택으로 유지하고, 확인되면 먼저 B의 최소 비교만 한다. 전체 Neo4j 환경은 이번 예산/작업에 포함하지 않는다.

### 신규 과금 승인안 — 아직 실행 권한 아님

기존 누적 예약 **$4.777963380 / LLM96 / Embedding115**, 과거 불명1건 **$0.02133**, 원장/STOP/claim은 그대로 둔다. 돈이 남았다고 기존 승인을 재사용하지 않는다. 아래 값은 `ai_novel_question_audit.proposal`이 산출하는 **새 승인 전 제안**이며 기존 guard 정책에 적용하지 않았다.

| 한도 | 제안 | 산정 |
|---|---:|---|
| 실제30문항 baseline | LLM최대51 | 일반29요청×2에서 명시Skill7요청의 Planning7회 제외; D5는 denial0 |
| B유형 최소 비교 | LLM최대5 | 실제 결손이 있는 B질문만 동일 captured 근거에서 최대1추가 생성; 필요 없으면0 |
| 새 LLM 총한도 |56회| `gpt-5.6-terra` 유지, 자동 재시도0 |
| 새 Embedding 총한도 |80회| 원문probe/Agentquery/최대10subquery·9문서재색인 필요시6batch 포함; 같은 vector 안전 재사용 우선 |
| 새 입력 예약 |1,835,008token|56×기존 요청당 최대32,768; UTF8전체요청+framing 보수 산정 유지 |
| 새 출력 예약 |89,600token|56×최대1,600; Planning의 실제 한도는 더 작음 |
| 새 Embedding 예약 |160,000token|문서/질문 합계, per-request와누적 모두 검사 |
| 보수 최대 계산 |**$5.665920**|입력$2.50/M×1,835,008 + 출력$12/M×89,600 + embedding$.02/M×160,000 |
| 요청할 새 총상한 |**US$5.70**|모든 비용·호출·token 제한을 동시에 검사; 다음 호출이 넘으면 전송 전 중단 |

단가는 [현재 모델 공식 가격](https://developers.openai.com/api/docs/models/gpt-5.6-terra) 입력$2/M·출력$12/M, cache write1.25배를 포함해 입력을$2.50/M로 예약하고 할인은 미리 공제하지 않는다. [Embedding 가격](https://developers.openai.com/api/docs/models/text-embedding-3-small)은$.02/M다. 과거 알려진 실사용 표준추정$.902655360/관측LLM95를 단순 참고하면 새 baseline은 약$.5 규모일 수 있으나 질문/문맥/출력과 지연이 달라져 **예상 절약액·청구액·완주 보장으로 사용하지 않는다.** 요청하는$5.70은 실제 지출 목표가 아니라 최대 보수 예약 ceiling이다. 전부 한도까지 예약한다고 가정한 과거+새 ceiling은 **$10.477963380**이며 기존$5가 새로 초기화되는 의미가 아니다.

승인 후 새 평가 세션/새 원장에서 기존 폐쇄 원장의 hash·불명예약·종료승인 식별자를 읽기 전용 참조하고, old/new/cumulative를 별도 표시한다. 과거 원장은 수정하지 않는다. 자동 재시도/unknown 재전송0. 새 사용량 불명 발생 시 새 세션 STOP과 최대예약을 남기며 과금 재개는 원장 안전성을 확인한 뒤 판단한다. 현재는 승인 파일/paid claim을 새로 만들지 않았다.

최소 실행 계획: 정상 종료·검증한 **AI 시험용** fixture와 활성9문서 index를 새 경로로 복제/identity 재검증하고 원본을 수정하지 않는다. 기존 사용자/손상체인/C Vault를 사용하지 않으며 금융 Worker는 평가 설정으로 비활성화한다. 실제 JWT HTTP30문항(필요한 denial 대조는0paid)을 각자 기록, 질문별 실패가 독립적이면 다른 질문을 계속하되 비용/정보보안 불명은 중단한다. 제어된 bad-link·event mismatch는 시험 입력/어댑터로 구분하고 실제 실패 체인 거래라고 세지 않는다. 소유 임시 환경만 종료/정리하고 DB/Anvil·원장·증거를 보존한다. 로컬 기동 전RAM1920MiB/디스크2GiB 조건은 유지한다.

### 면접 학습 — 이번 설계가 답하는 것과 아직 답하지 못하는 것

1. **고정 세트의 좋은 결과가 일반화됐나?** 아직 아니다. 이전56원문을 확인하고 새로운 실패 전제/권한 경로/다문서 관계30개를 먼저 고정했다. exact 중복 제거는 최소 검사이고 의미 독립성·모델학습 비노출을 증명하지 않는다. 평가자는 기존 질문을 안다는 한계를 공개했다. 신규 원문/기대 사실/role을 실행 전hash로 묶어 결과 맞춤을 막는다. 관련 [공식 eval 지침](https://developers.openai.com/api/docs/guides/evaluation-best-practices)의 명시 기준·대표 사례·오류 로그와 맞춘 로컬 절차이지 외부 Evals 서비스 실행은 아니다.
2. **Hit@5가 높으면 관계 추론도 되나?** 아니다. B2에서 WS정책 하나만 잡아도any-hit=1일 수 있지만 정산 멱등성 조건이 빠지면 답은 불충분하다. 그래서 required atom recall/all-support를 추가하고 검색 결과와 최종 설명의 오류를 분리한다. 실제 chunk 가용성88개 검사는 상한 가능성일 뿐 순위 성과가 아니다.
3. **왜 GraphDB를 당장 안 붙였나?** 현재 발견한 C2 query 대체/C4 필터 인자 미연결/C5 legacy 모델 범위는 그래프가 없어 생겼다는 증거가 없다. B관계의 원자료와 sourceHash를 먼저 확인하고 실제 누락 원인을 찾는 편이 작고 검증 가능하다. SQL JOIN/CTE로 가능한 관계 조회와 문서 의미 검색/생성은 서로 다른 문제다.
4. **Agent의 실질 가치는 무엇인가?** 한 가격/잔고값은 API가 더 직접적이다. 서로 다른 정책의 조건과 현재 관측을 연결해 왜 확정할 수 있는지/없는지, 다음에 무엇을 읽을지 알려주는 것이 가치 후보다. 이를 정확성/충분성 rubric으로 검토할 계획이지 실제 사용자 만족도나 절약 시간을 측정한 것이 아니다.
5. **왜 유보를 전부 없애지 않나?** current LIVE→과거 generation 적격, current equity회복→CLEAR_CALL, legacy journal→C 토큰 이동은 빠진 관계가 아니라 다른 사실이 필요하다. Tool에 없는 사실은 PARTIAL/지원 한계로 남겨야 한다. 모르는 이유를 정확히 설명하는 답은 유용하지만 조회 사실까지 숨기면 과도한 유보다.
6. **회귀 실패를 어떻게 다뤘나?** 이전 실제 채택 PARTIAL fallback과 Skill의 옛 상태 assertion이 충돌했다. 실패 증거를 보존하고 status만 맞추는 데 그치지 않고 비검증 본문/인용/후속지시 비노출과 안전한 Tool 사실 보존을 함께 검증했다. 기능 assertion을 삭제하거나 금융/인가를 완화하지 않았다.

**무과금 단계 종료 추기:** 최종 표적 Java109PASS/0FAIL/0SKIP(실제 JDK21·offline Gradle)과 Python9PASS다. 신규 평가/권한/Skill/캐시/승인 corpus 관련 범위이며 제품 전체 회귀 재실행은 아니다. 최초 Skill1실패 XML, 초기 v1 가용성 결과, domain 보완 후 v2 결과를 `deployment/runtime/ai-novel30-preparation-20261030/`에 각각 보존했다. 최종 `corpus-chunks-v2.json`의 실제 corpus fingerprint는 `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`,88chunk이며 모든 근거는 role+Skill domain을 적용했다.

독립 초기 검토는 필수1건(D1 domain)을 발견했고, 수정 후 재검토1에서 **발견된 필수 수정 없음**이다. 검토자는 Python9/audit를 직접 실행하고 v1/v2 질문·facts 불변/해시·domain negative·최신 Java109XML을 대조했다. Java 자체 재실행/JWT/PG/RPC/LLM 및 원장 접근은 검토자 검증이 아니며 구현자의 무과금 실행/보존 확인과 구분한다.

기존 최종 예산 SQLite와 STOP은 읽기 해시만 확인했다. 마지막 원장 SHA `1361174aaf4cf1e79c1a4ee5f24275d3ffaa766c401b9004d44c4ab08a672aac`, 전임 원장 `3279bcb39bbd7af9202566c409898c02eb0cba8422fbc7be712e4f705f6cb918`, 양쪽 STOP `7b63e4e8153897bf6a4f3478543dc095bc52d018596b96f85db6aac91deab44f`를 보존했다. 새 키/승인/paid claim/DB기동/컨테이너·금융 거래0이다. 브랜치/HEAD는 기존 `feat/ai-latest-quality-20261030@1bc1c738…`, stage/commit/merge/push0이고 원본 melon-init 미커밋8항목·24worktree를 유지했다. 이번11파일의 고확도 API키/PEM/JWT/로컬사용자경로 패턴 탐지0건, Diff check PASS, runtime ignore를 확인했다. 제한된 패턴 검사는 절대적 무유출 보장이 아니다. 개인 회고 인덱스/기존 runtime은 공개 업로드하지 않으며 새 면접 학습은 위 절에서 기존 사건에 연결했다.

현재 결과는 **신규 평가 준비/무과금 검사 PASS**, 실제 신규 검색/답변/효용성·GraphRAG 최종 판정은 **NOT_RUN / 새 US$5.70 승인 대기**다. 기존 $5 승인과 LLM96회 사용을 초기화하지 않았다. 이 준비 완료를 신규30문항 실제 인수 완료로 표현하지 않는다.

## 최신 결론 — 2026-10-30 잔여 세 항목 검증 완료, 범위 한정 PASS

이번 승인 범위인 **N8 실제 모델 유보·Q5/Q12/H04 과도한 PARTIAL 보완·Embedding 캐시 실측**을 완료했다. Q11 대조에서 발견한 사실 손실도 수정 후 실제 HTTP 두 요청으로 확인했다. TMS 전체 AI/자동 진단/C Vault 인수 완료를 뜻하지 않는다. 아래 이전 PARTIAL/NOT_RUN/승인 대기는 당시 이력이며 덮어쓰지 않는다.

### 결과·비용 — 먼저 읽기

| 항목 | 실제 검증 | 결과 |
|---|---|---|
| N8 친구 초대 리워드 | JWT HTTP+최신색인+계획/생성2LLM | INSUFFICIENT_EVIDENCE. 모델도 정책에 리워드 조건이 없다고 유보 |
| Q5 견적 상태/만료 | HTTP,6개 정확 scalar | PARTIAL→ANSWERED. CONSUMED와 체결 완료 구분은 본문에 보존 |
| Q12 청산 이유 | HTTP,19개 정확 scalar | PARTIAL→ANSWERED. 수치 관계·60초 시각·선택 검증 범위 보존 |
| H04 Synthetic 관측/정책 | HTTP,6개 정확 scalar | PARTIAL→ANSWERED. 실증권 시세/주문 성공과 구분 |
| 진짜 부족 근거 | Q16/H06,추가 Q11 두 HTTP | Q16/H06 PARTIAL 유지. 최종 Q11도 PARTIAL+관측값 보존 |
| 권한/자산 | 타인404/USER ADMIN403, PG/Anvil reader | 권한 거절/유료0, 평가 전후 금융 fingerprint 동일 |
| query vector cache | 실제 RAG component+HTTP miss/hit | 동일 근거·역할/활성 index 검사·Tool 갱신 후 Agent 조회 경로 기본ON |

누적 보수 예약 **$4.777963380 of $5** / LLM **96 of96** / Embedding115 of200 / 입력예약1,366,806 of1,500,000 / 출력예약112,800 of153,600 / Embedding예약367,419 of400,000이다. 과거 사용량 불명1건 최대$0.02133을 전액 포함한다. 알려진 실제 사용량은 입력239,758·출력35,055·Embedding123,968, 표준단가 추정 **$0.902655360**이며 불명1건·청구 확정은 포함하지 않는다. 금액 잔여$0.222036620가 있어도 LLM96회 상한이므로 추가 유료 호출을 종료했다. 기존 원장/STOP/claim은 초기화하지 않았다.

이번 요청의 새 호출은 LLM10·Embedding25, 모두 OBSERVED다. 첫 환경 실패는 유료0, 잔여 본시험8/18, 좁은 최종 대조2/7이다. 후속의 색인6회는 부모 AI DB/ownership을 변경하지 않고 새 실행 identity에서 동일9문서를 검증하기 위한 재색인이다. 새 금융 거래/배포0이다.

### 원인·대안·채택

**과도한 PARTIAL:** 기본 지시문은 충분한 관측 설명에도 확인 불가능한 거래 성공을 uncertainties에 넣으라고 했지만 measured suffix는 범위 밖 한계를 본문에만 두라고 했다. 숫자를 facts로만 가리키라는 기본 지시도 본문 수치 설명과 충돌했다. 경고를 더 붙이거나 서버가 불확실성을 지우는 방법 대신, 두 충돌을 정리하고 실제 `uncertainties → PARTIAL` 계약을 명시했다. 모델/schema/facts 최대20/인용·pointer/권한 검증은 불변이다. measured suffix SHA710f5eb3는 보존하고 수정한 기본 지시 SHA933bda44를 별도 고정했다.

Q5/Q12/H04는 원문·Tool·RAG에 대조해 정확성/충분성/근거/불확실성 적정성2/2/2/2로 평가했다. 수동 rubric이며 자동 LLM 심판이나 대규모 통계 성과가 아니다. 범위 밖 uncertainty는 사라졌지만 한계 설명은 본문에 남았다. 지연은 Q5 10,033→14,150ms, Q12 10,736→10,512ms, H04 7,819→7,556ms다. Q5의 시간 증가는 숨기지 않으며 소표본에서 문구 수정이 속도를 개선했다고 주장하지 않는다.

**진짜 정보 부족의 사실 보존:** Q11 모델은 유용한 관측+부족 근거를 작성하고 INSUFFICIENT_EVIDENCE를 선택했다. 기존 서버가 미검증 해석을 버리면서 검증한 사실까지 정형 문구로 가리는 품질 손실을 발견했다. 미검증 모델 본문·인용·이유를 그대로 신뢰하지 않고, 성공 Tool 허용 scalar만 `StateExplanation`으로 설명하며 PARTIAL/MODEL_EVIDENCE_INSUFFICIENT를 반환한다. 사실이 없는 N8은 기존 INSUFFICIENT_EVIDENCE다. 실제 후속 Q11 두 요청은8,388/6,669ms, 둘 다 OPEN/SIMULATED와 결론 확정 불가를 표시했다. 전체 시나리오를 반복하지 않고 이 회귀와 HTTP cache miss/hit만 확인했다.

**캐시:** 거래 상태/검색 결과가 아닌 불변 query embedding만 저장한다. model+corpus+question 키, TTL5분/LRU64/defensive copy다. 역할/domain·활성 index·승인 검증은 매 요청 유지한다. corpus 키/TTL/복사/clear/용량은 결정적 unit, 역할/색인/Tool 갱신은 실제 환경 근거와 구분한다.

| 측정 | 표본 | 중앙값 | nearest-rank p95 | Embedding 호출 |
|---|---:|---:|---:|---:|
| OFF |3|1155ms|1385ms|3|
| ON 최초 miss |1|2206ms|2206ms|1|
| ON warm hit |3|106ms|112ms|0|

동일 질문 반복에서 ON 전체4회 중3회 적중(75%), warm 검색 중앙값 약90.8% 감소다. 최초 miss는 더 느렸고 표본1로 이유를 캐시 오버헤드라고 단정하지 않는다. 동일 질문/사용자/corpus/DB에서 비교했고 cold/warm을 구분했다. 이 인위적 적중률과 작은 표본 p95를 서비스 트래픽 성과로 일반화하지 않는다. 전체 LLM 답변이90% 빨라졌다는 뜻도 아니다.

추가 실제 HTTP Q11은 Embedding1→0/RAG1853→116ms였고 Synthesis6513/6437ms가 여전히 지배적이었다. 전체8388/6669ms 두 요청만으로 운영 p95나 전체 인과 효과를 확정하지 않는다. `AiConfiguration`의 **Agent용 AuthorizedKnowledgeRetrieval**에서 기본ON이며 `app.ai.query-vector-cache=false`로 끌 수 있다. 기존 Basic RAG/ingest 경로와 AI enabled 기본false는 유지한다.

### 검증·보존 범위

- Q14/Q15 HTTP 성공, 선택적 식별자8/8·Golden12/12, STATE 모델0회 증거는 보존하고 재실행하지 않았다. N1~7 기존 유보와 새 N8을 연결한 것이며 새 동일 batch 음성8개 재실행은 아니다.
- 첫 v2는 CHAIN_ID CONNECT_FAILURE121ms/유료0이었다. 내부 준비와 호스트 포트 준비의 관측 공백을 local read-only eth_chainId 검사로 보완했다. 재검토에서 socket inactivity timeout과 전체 deadline 차이를 찾아 monotonic 남은 기한을 connect/send/매recv 및 반환 후 검사하도록 고쳤다. 느린 header/body·늦은 성공 거부5시험 PASS. 뒤 두 실제 회차는 host와 기존 Java 배포 검증을 통과했지만 과거 Windows RPC 장애의 근본 원인 해결이라고 선언하지 않는다.
- 실제 인수는 잔여 본시험1PASS와 좁은 후속1PASS, 무과금 표적53PASS와 별개다. 종전 전체250PASS/31SKIP을 새 전체 회귀로 재사용하지 않는다. 최종 독립 대조/자원 정리·보존 결과는 종료 추기에 남긴다.
- runtime 근거: 본시험 `phase-a-20261030062612-bb1184d4/latest-study`의 questions/cache/index/financial/usage, 후속 `phase-a-20261030064417-88c154ee/latest-study`의 questions/completion/financial/latency/residual-assessment. 첫 실패는 `phase-a-20261030052935-7457504c`에 보존했다. Git 업로드 대상이 아니다.
- 최종 실제 study fingerprint `802c822325681427a61b351784d95ba598fffb8230a05802b4ca83fa6fca624e`, 본시험 `bf2f60d26287c4976feb2e5065286a96e99eb5402d12f6703af438a80d95708a`를 구분한다. 최종 소스에서 직접 재검증한 부분은 Q11/cache HTTP이며 그 사이 변경하지 않은 Q5/Q12/H04 지시문의 본시험과 조합했다.
- 보호 사용자 DB·손상 Anvil·개인 지갑·기존 checkpoint는 사용하거나 변경하지 않았다. AI 전용 legacy DB 모의담보 fixture의 정상 종료본이며 C Vault AI/사용자 금융 인수가 아니다. 금융/계약/Oracle/JWT/nonce/TTL 변경0, commit/merge/push0이다.

### 면접 학습 연결

종료 추기: 최종 같은 코드의 무과금 표적 Java53PASS/0SKIP/0FAIL XML은 `ai-residual-final-regression-20261030`에 보존했다. Python eval81/latest29/readiness5/phase24/deadline4/copy3=146PASS다. 독립 최종 검토는 새 두 실제 회차의 SQLite·11응답·59생성 scalar 참조/인용·실제JUnit·금융after를 직접 대조해 필수 지적 없음을 확인했다. 유보 초안의 참조 점검은 사용자에게 그 초안을 노출한 검증 성과가 아니다. Java53/Python146 재회귀·Git/정리는 구현자 실행 결과로 구분한다.

이번 소유 정지 컨테이너9개와 빈 네트워크3개만 제거했다. 볼륨/데이터/상태/원장/실패·성공 증거 삭제0, 모든 상태JSON 유효·PG 파일 잔존·복사 원본4디렉토리 hash 동일·임시 API키 사본 없음이다. 활성 시험 컨테이너0, C여유50.09GiB, 신규 이미지 build0이다. 브랜치 `feat/ai-latest-quality-20261030@1bc1c738b16baf095fcc2a539a3b33f8bc92c39d`의 미커밋 변경이며 stage/commit/merge/push0, 기존23+AI1 worktree/원본 미커밋8항목을 보존했다. 공개 후보61파일의 고확도 secret/local-user-path 검사0건·Diff check PASS, gitleaks 미설치이며 절대적 무유출 보장은 아니다. 회고 인덱스와 runtime은 공개 대상에서 제외한다. 최종 source802c8223는 마지막 실제 HTTP와 동일하다. 승인된 잔여 범위를 마치고 추가 개선을 시작하지 않는다.

1. **schema는 맞는데 왜 품질이 나빴나?** shape/pointer가 맞아도 질문 범위 밖 경고 때문에 PARTIAL이 된다. 기본 지시·suffix·서버 status 계약을 함께 대조했다. Structured Outputs가 의미상 실수를 막지는 않는다는 [공식 설명](https://developers.openai.com/api/docs/guides/structured-outputs)을 Q5/Q12/H04와 연결한다.
2. **유보와 충분성을 어떻게 함께 지켰나?** 모르는 실행 가능성은 유보하되 인증·검증한 Tool 사실을 잃지 않는다. 미검증 LLM 초안을 노출하는 대신 고정 scalar 설명을 선택하고 Q11 실제 후속으로 확인했다.
3. **무엇을 캐시했나?** immutable vector와 mutable balance/price를 분리했다. TTL/LRU만으로 권한이 보장되지 않아 역할 SQL/활성 index 검사를 hit에서도 유지했다. ADMIN→USER·index 비활성·새 observedAt이 근거다.
4. **90.8%를 어떻게 설명하나?** 검색 component warm median이고 cold1/warm3·동일 질문 hit75% 조건을 먼저 제시한다. LLM·전체 HTTP·운영 p95와 구분한다. 실제 HTTP miss/hit도 확인했으나 두 표본뿐이다.
5. **왜 비용 정책을 버전 관리했나?** v1 원장을 덮어쓰지 않고 v2 승인·단일 successor claim을 연결했다. $5/96회와 다른 토큰 상한·unknown 예약은 불변이다. 돈이 남아도96회에서 종료한다.
6. **read timeout과 deadline 차이는?** 조금씩 오는 응답은 inactivity timeout을 계속 피할 수 있다. monotonic end/매recv 남은 시간/늦은 성공 거부가 필요하다. fake clock으로 재현했고 실제 연결 성공과 과거 원인 확정을 구분했다.

모든 질문의 의미 정확성, 대규모 부하/p95, 운영 적중률, C Vault AI, 실제 실패 거래 원인과 자동 진단 장기 운용은 미완료다. 승인된 세 항목 밖의 새 개선 과제는 시작하지 않는다.

## 잔여 세 항목 진행 기록 — 당시 계획과 중간 결과

- 실제 잔여 실행(phase-a-20261030062612-bb1184d4): N8은 검색 후보가 있어도 리워드 지급 조건이 없다고 실제 모델이 유보했다. Q5/Q12/H04는 모두 HTTP200/ANSWERED, 관측값/정책/한계와 exact fact 인용을 유지했다. Q16과 H06의 핵심 불확실성은 PARTIAL, 타인404/USER ADMIN403은 유지했다. 실제 JUnit1PASS, 원장 fingerprint 불변이다.
- cache 실제 검색 component: OFF3회1155/1016/1385ms(중앙1155), ON cold2206ms 후 warm112/106/105ms(중앙106), warm embedding0회, 전체 ON 3/4적중이다. cold latency를 숨기지 않으며 전체 HTTP·LLM속도90%개선이라고 표현하지 않는다. 역할 전환/활성 index 검사/Tool 관측 갱신 및 기존 corpus/TTL unit을 확인해 제품 cache 기본true 채택 후보를 구성했다. AI enabled 기본false와 명시 `app.ai.query-vector-cache=false` opt-out을 보존하고 Spring wiring을 검증한다.
- 안전 대조에서 Q11 모델은 유용한 관측+진짜 부족 근거를 작성했지만 status=INSUFFICIENT_EVIDENCE를 골라 기존 서버가 정형 본문만 반환하는 품질 손실을 확인했다. 미검증 모델 초안은 그대로 쓰지 않고, 이미 검증된 성공 Tool scalar가 있을 때만 결정적 설명+PARTIAL/MODEL_EVIDENCE_INSUFFICIENT로 보존한다. Tool 사실이 없는 N8 등은 기존 INSUFFICIENT_EVIDENCE다. 더 많은 ANSWERED를 얻으려고 uncertainty를 지우는 변경이 아니다.
- 이 회귀와 실제 HTTP cache miss/hit 연결만 `scope-followup`의 Q11 두 요청으로 재검증한다. 이미 통과한 세 문항/금융 거래/검색 golden은 반복하지 않는다. 앞 실행의 실제26호출(LLM8/embed18)은 모두OBSERVED, 누적94/108·예약$4.638855780. 마지막 두LLM 요청도 기존96회/$5/150만 입력 및 다른 한도를 넘으면 전송 전에 차단한다. 별도 새 금융 fixture 생성/배포 없이 기존 정상종료 시험 snapshot 복사와 동일9문서만 재사용한다.

- 첫 v2 실행은 Spring `CHAIN_ID/CONNECT_FAILURE`121ms에서 중단(유료0)됐다. 컨테이너 내부 RPC readiness만 있었고 호스트 게시 포트의 실제 HTTP 준비는 사전에 검사하지 않았다. 이 관측 공백을 AI fixture에만 20초 bounded loopback `eth_chainId` readiness로 보완한다. CONNECT/REQUEST/HEADERS/BODY와 오류 코드를 기록하되 transaction RPC·제품3초 timeout·배포 검증은 그대로다. 포트 준비 지연이 근본 원인이라고 확정하지 않으며 다음 Java 검증까지 실제 통과해야 해결로 판단한다. 실패 원장/STOP/claim을 보존하고 유료0 successor로 누적량을 그대로 잇는다.

- 사용자 승인: 누적 입력 예약만1,200,000→1,500,000. 새 policy v2와 명시적 v1→v2 승인 기록을 새 successor에 묶고 이전 policy/원장/STOP/불명1건 최대예약액을 그대로 보존한다. $5/96LLM/200embedding/출력153600/embedding400000과 단가·모델은 동일하다. 실행 전 누적86LLM/90embedding/$4.183874300을 재대조한다.
- 기존 canonical 검증 AI 금융 fixture를 cold-copy해 재사용하고 신규 금융 거래/전체 Q1~16 반복은 하지 않는다. 실제 JWT HTTP 대상은 N8,Q5,Q12,H04와 최소 안전 대조 Q11/Q16/H06/H07/H08이다. 초기 색인은 동일9문서88chunk를 유지한다. Q14/Q15·Golden12/12·식별자8/8의 기존 실제 증거는 불변이며 새 시험으로 재기록하지 않는다.
- 확인된 원인 후보: 기본 지시문은 “실제 숫자는 facts로만 가리킴”, “관측 설명이 충분해도 확인 불가능한 성공은 uncertainties에 남김”을 요구했고, 측정 후보 suffix는 그 반대를 요구했다. 기본 지시문의 두 충돌을 정리하고 서버에서 uncertainties→PARTIAL인 실제 계약을 명시한다. 부족한 Receipt/실행 가능성/선택 청산 INCONCLUSIVE는 핵심 불확실성으로 유지한다. 서버의 근거 검증·PARTIAL 조건을 삭제하거나 반환 모델 불확실성을 사후 필터링하지 않는다.
- 기존 measured suffix의 SHA710f5eb3는 유지하고 의도적으로 수정한 기본 지시문을 새 SHA933bda44로 고정한다. 단순히 깨진 snapshot assertion을 제거하지 않고 scope 문장·부족 근거·schema max facts20·storefalse 검증을 유지한다. 실제 효과는 새 실모델 결과 전까지 미확정이다.
- cache OFF3회와 ON cold1+warm3을 동일 질문/사용자/corpus/DB에서 비교한다. clear는 queryvector만 비우고 warm-up을 숨기지 않는다. embedding 호출 수·동일 근거·역할 전환의 ADMIN 문서 차단·실제 inactive index 거절/복원·Tool 관측시각 갱신을 확인한다. corpus 키/TTL/defensive copy/용량은 결정적 unit 근거와 구분한다. 성공 측정 전 기본OFF이며 효과·안전 확인 때만 활성화한다.
- 새 문항을 늘리거나 모델/금융/권한/Oracle 정책을 변경하지 않는다. 일반 실패를 숨기거나 과금 예산을 재설정하지 않는다. 실제 인수 전 단위·컴파일·독립 검토를 수행한다.

## 최신 결과 요약 — 2026-10-30, PARTIAL / 실제 HTTP 평가·지연 개선 확인

**완료와 미완료:** 실제 9문서·88chunk pgvector/LLM/JWT/10Tool/4Skill로 Q1~16과 H01~08 HTTP 경로를 실행했다. Q14/15는 정상 완료, H03은 사건 수치와 실제 유예 시각을 함께 설명했다. 단순 STATE의 불필요한 모델 호출을 제거한 전후 비교도 실제 HTTP에서 확인했다. 그러나 N8은 누적 입력 토큰 예약 상한 때문에 공급자 전송 전에 차단됐다. 최종 실환경 JUnit은 이 N8을 숨기지 않고 **FAIL**, 전체 인수는 **PARTIAL**이다. Q5/Q12/H04의 과도한 PARTIAL도 남았다. 아래 과거 “승인 대기/NOT_RUN” 절은 당시의 변경 불가능한 이력이지 최신 결론이 아니다.

### 5분 요약: 무엇이 바뀌었는가

1. 과거 Q14는 생성10초 초과, 후속은 계획5초에서 실패했다. 하위 호출마다 예산을 새로 주던 문제와 fallback 승인 pool 수명을 분리했고, 이번 실험은 계획15/생성30/검색15/전체90초로 실제 응답을 완성했다. **timeout 확대는 속도 향상 성과가 아니다.** 실제 Q14는14,512ms, Q15는14,327ms였다.
2. 실제 병목은 DB보다 모델이었다. Planning 중앙값2,402.5ms, Synthesis6,102.5ms, 검색1,302.5ms 중 embedding1,164.5ms다. SQL 후보 검색49.5ms, 근거 검증16ms, Agent 밖 HTTP10ms였다. 포함 관계가 있는 구간을 합산하지 않는다.
3. 명확한 읽기 전용 문법만 서버가 STATE로 결정한다. 동일 JWT/Tool/DB/RPC 권한을 거치며 거래 상태는 매번 새로 읽는다. Q2 시장 조회는8,123ms·모델2회에서 중앙값16ms·0회, Q3 잔고2,889ms→12ms, Q4 주문3,151ms→18ms였다. **baseline 각1회, 최적화 각3회**이므로 모집단 p95나 일반 질문 전체의 속도 향상을 주장하지 않는다.
4. 검색은 선택적 식별자 exact 후보를 채택한다. Golden12/12·MRR0.881944를 유지하고 식별자6/8→8/8·MRR0.604167→1.0이었다. 전역 Hybrid는 Golden MRR0.840278로 회귀했고 threshold0.35는 Golden11/12로 감소해 기각했다.
5. 과금 예산은 초기화하지 않았다. 누적 **보수 예약$4.183874300 / LLM86 / embedding90 / 입력예약1,195,607 of1,200,000**이다. 알려진 실제 사용량의 표준단가 추정$0.787987900은 청구 확정이 아니며 불명1건을 제외한다. 불명1건 최대$0.02133은 보수 예약에 전액 포함한다. 금액 잔여$0.816125700와 별개로 입력예약 잔여4,393이 다음 계획 호출에 부족하므로 추가 유료 호출은 하지 않는다.

### 실제 경로와 안전 경계

- 새 금융 거래를 반복하지 않았다. 이전 AI 전용 시험 fixture의 정상 종료된 PG/Anvil을 신규 경로로 cold-copy하고 원본·복사본 파일 해시를 대조했다. 새 복사 DB에만 identity marker와 시험 계정의 무작위 비밀번호를 변경한 뒤 실제 `/api/auth/login` JWT를 사용했다. 원본 사용자 DB/손상 Anvil/C Vault/개인 지갑은 사용하지 않았다.
- 복사 환경의 Worker만 시험 classpath에서 비활성화하고 실제 Tool DB/RPC reader는 유지했다. 첫 유료 호출 전 선택 사건의 canonical Receipt/Event VERIFIED 및 원본 금융 fingerprint 일치를 확인했고, 평가 후에도 fingerprint가 동일했다. 이 결과는 **legacy DB 담보 AI**, C Vault AI 및 새 금융 거래 인수가 아니다.
- H05는 통제된 PG pending 행+실제 RPC NOT_FOUND이며 거래를 보내지 않았다. H06은 통제된 PG 행+Receipt adapter다. 실제 체인 실패 거래의 원인 분석 성공으로 표현하지 않는다.
- H07 타인 포지션404, H08 USER의 ADMIN Skill403을 색인 전과 후에 확인했다. 모델·embedding 호출0이다. 관측한 모든 답변 인용 ID와 생성 facts의 정확한 JSON pointer 값을 재대조했다. 독립 대조기의 최초 `None`/`null` 차이는 제품 오류가 아닌 분석기 오류였으며 첫 파일을 보존하고 v2 결과를 별도로 저장했다.

### 질문별 실제 품질 판정

| 범위 | 실제 결과 | 해석 / 남은 문제 |
|---|---|---|
| Q1~16 | HTTP200 모두, ANSWERED11/PARTIAL5 | 상태 비율만으로 정답을 판정하지 않음 |
| Q14/Q15 | ANSWERED,14,512/14,327ms | 선택 canonical 사건·journal·검증 범위 설명. 과거 timeout 기록 보존 |
| Q12/Q13 | 수량·가격·PnL/equity/MM·유예 관계 설명 | Q13 ANSWERED, Q12는 전체감사 한계까지 uncertainty로 넣어 PARTIAL인 문제 잔존 |
| Q5/Q6/Q7 | 소비/만료·전송·확정 사실과 정책 설명 | Q6/Q7의 이전 범위 밖 불확실성은 재현되지 않음. Q5는 질문하지 않은 체결 완료 여부로 PARTIAL |
| Q11 | PARTIAL | Tool에 실행 가능성을 확정할 연결/세대/검증 문맥이 없어 적절한 유보. 새로운 Tool을 만들어 추정하지 않음 |
| Q16 | PARTIAL, 모델0 | 실제 선택 포지션 증거/가격 불확실성 유지 |
| H01/H02/H03 | ANSWERED | 주식 권리 부재, 과거 체결 기준, 청산 수치·60초 시각·legacy 범위 설명 |
| H04 | PARTIAL | Synthetic 관측/신선도 설명은 제공하나 질문 범위 밖 주문 성공을 uncertainty로 표시 |
| H05/H06 | PARTIAL | Receipt 부재와 성공/실패 구분, revert 원인 미확정 유지. 통제 시험의 한계 명시 |
| H07/H08 | 404/403, 유료0 | 사용자 격리/ADMIN 권한 보존 |
| N1~N7 | INSUFFICIENT_EVIDENCE | 후보가 반환된 문항도 최종 근거 부족으로 유보. 후보 반환 자체를 환각으로 세지 않음 |
| N8 | HTTP503/PLAN_UNAVAILABLE | 입력 예약 상한에 의한 전송 전 차단. 실제 모델 부정 질문 평가 미완료 |
| 최적화 STATE | 9/9 HTTP200/ANSWERED, 모델0 | Q2/Q3/Q4 각3회. 잔고·상태를 캐시하지 않음 |

Rubric은 구현자가 원문·Tool·검색·scalar를 직접 대조한 정확성/충분성/근거/불확실성0~2 점수이며, 자동 LLM 심판이나 외부 사용자 평점이 아니다. 실제 부족 근거와 불필요한 유보를 구분했다. Q5/Q12/H04는 불확실성 적정성1, Q11/Q16은 근거 부족을 제대로 표시한 사례다. N8은 품질 채점 불가다. 인용 pointer 검증은 본문 전체 의미의 자동 진실성 증명을 뜻하지 않는다.

### 동일 조건 검색 비교

동일9문서88chunk, 동일32개 실제 query vector를 네 검색 변형에 공유했다. 조건은 TopK5/문서당2/semantic threshold0.25다. 학습·추가 문항은 통계적으로 독립된 대규모 검증셋이 아니다.

| 변형 | Golden Hit/MRR(n12) | 식별자 Hit/MRR(n8) | 추가 Hit/MRR(n4) | 무관 질문 비어 있지 않음(n8) |
|---|---|---|---|---|
| semantic |12/12·0.881944|6/8·0.604167|3/4·0.425|5/8|
| 선택적 식별자 |12/12·0.881944|8/8·1.000000|3/4·0.750|5/8|
| global Hybrid |12/12·0.840278|8/8·1.000000|3/4·0.750|5/8|
| threshold0.35 |11/12·0.798611|6/8·0.604167|2/4·0.375|2/8|

선택적 exact는 SQL role/domain WHERE-before-rank·활성 corpus 검증을 유지한다. 자연어 전체에 lexical 점수를 섞지 않고 좁은 식별자 규칙에서만 후보를 우선한다. 무관 후보5/8 문제는 검색 단계에 남지만 실제 답변7개는 안전 유보했다. 추가 문항1개의 expected 문서 미적중도 남았다. Chunking1200/overlap60/TopK5는 이번 원인에 직접적인 개선 근거가 없어 변경하지 않았다.

### 지연: 처리시간 감소와 허용시간 증가의 구분

| 구간 | 표본 수 | 중앙값ms | 소표본 nearest-rank p95ms | 범위 |
|---|---:|---:|---:|---|
| 정상/통제 HTTP |22|8633|14327|Q1~16+H01~06, 서로 다른 질문의 혼합분포 |
| Planning |22|2402.5|4017|N8의 guard 거절 시도도 포함. 공급자 성공만의 분포와 구분 |
| Synthesis |22|6102.5|8256|전부 정상 공급자 응답 |
| RAG |26|1302.5|2713|Embedding·SQL·검증 포함 |
| 질문 Embedding |26|1164.5|2447|Java provider 경계 |
| SQL 후보 |26|49.5|139|식별자 추가 SQL은 FILTER_RESULTS 하위여서 이 한 열과 전체 SQL을 동일시하지 않음 |
| Tool |56|7|82|실제 DB/RPC 포함 |
| 근거/승인 검증 |45|16|31|검증 event 분포 |
| broker 송신 전 |77|313|547|비용 예약·identity/lineage 검증·직렬화 포함 |
| Agent 밖 HTTP |43|10|18|보호 API/최종 직렬화 등의 차이. 세부 네트워크만의 시간 아님 |

Planning/Synthesis/RAG는 내부 LLM/Embedding 시간을 포함한다. Java broker 왕복과 Python HTTPS 관측을 requestId로 연결했으며 HTTPS 시작 전313ms 중앙값은 비용 guard 처리도 포함한다. 현재 자료로 그 전부를 IPC 순수 오버헤드라고 부르지 않는다. 제품 native Java HttpClient는 재사용되지만 평가 Python sender는 호출별 연결이다. 따라서 평가 지연을 native 제품 공급자 경로의 부하 성능과 동일시하지 않는다.

**채택:** 제품 AgentProperties 기본값을 실제 시험한15/30/15/90과 결정적 STATE=true로 맞춘다. AI enabled의 기본false는 유지한다. 좁은 STATE 문법 외에는 기존 Planning/Skill/근거 검증을 유지하며 이전5/10/5/40 및 최적화false는 명시 설정할 수 있다. 의미가 같은 설정의 실제 HTTP 시험 후 기본값을 바꾼 것이며, 이 기본값 변경 커밋의 두 번째 유료 전체 HTTP 재실행은 하지 않았다. Spring binding/표적·최종 무과금 회귀로 연결을 검증한다. 부하·장기 운영 SLA는 미검증이다. 90초 상한은 사용자 요청 대기 및 두 worker 점유 증가 가능성이 있으며 동시성 제한을 제거하지 않는다.

**보류:** query vector 캐시는 corpus+model+question 키,5분TTL,LRU64,복사본·deadline·권한검증으로 구현하고 경계 unit test를 통과했으나 실제 cache-on/off component 비교는 N8 STOP 뒤여서 NOT_RUN이다. 기본OFF로 유지하고 지연 절감 수치를 만들지 않는다. 거래 상태·검색 결과 캐시는 구현하지 않는다. DB/RPC 병렬화·프롬프트 축약·Python 연결 pool도 새 비교를 하지 않았으므로 성과로 주장하거나 추가 구현하지 않는다. 가장 큰 불필요한 모델 호출 제거를 우선했다.

### 코드·증거·재현 범위

최종 영향 확인에서 `PgDiagnosisStore`의 자동 진단 claim lease90초와 새 수동90초의 경계가 겹침을 발견했다. 자동 진단까지 늘리는 것은 이번 성능 개선의 목적이 아니므로 `AgentService.requestBudgetNanos`에서 자동 요청만 min(설정 전체,기존40초)로 유지한다. 수동 요청은90초이며 더 짧은 명시적 예산은 자동에서도 지킨다. 이는 실제 자동 timeout 장애가 재현됐다는 주장이 아니라 소스에서 확인한 경계 회귀를 예방한 수정이다. lease/권한/재시도 정책은 변경하지 않는다.

- 제품: `ai/agent/AgentProperties`, `AgentService`, `DeterministicStateRoute`, `StateExplanation`, `OpenAiAgentProvider`, `provider/CallDeadline`, `QueryVectorCache`, `retrieval/AuthorizedKnowledgeRetrieval`, `IdentifierQuery`, `store/PgKnowledgeStore`, `observability/AiObservability`와 지시문 resource. 모두 `backend/src/main` 아래다.
- 시험: `LatestAiAcceptanceTest`, `LatestAiConfiguration`, `LatestRetrievalBenchmark`, `GuardedAiEvaluationRunner`, `deployment/ai_deadline.py`, `ai_latest_evaluation_broker.py`, `ai_latest_final_run.py`, `ai_verified_fixture_copy.py`, `phase-a.py` 및 관련 unit tests. Windows/공개 CI/제품 금융·인증·계약의 기존 코드를 무단 변경하지 않았다.
- 비공개 근거 식별자: 최종 `phase-a-20261030025107-acc40136/latest-study`의 `questions.private.json`, `retrieval-comparison.private.json`, `tools.private.json`, `financial-before/after.private.json`, `latency-analysis.private.json`, `quality-assessment-final-v2.private.json`, `usage-*`, Java/Python diagnostics, 원장/STOP. 금융 fixture 검증 실패는 별도 `phase-a-20261030013333-87a89bfa`에 보존했다. 이 runtime 파일들은 Git 업로드 대상이 아니다.
- 기존 부모·후보 비교·실패 Q14·불명 호출·STOP·successor claim·원장은 수정하지 않는다. 최종 새 호출77건(LLM43+embedding34)은 모두 OBSERVED다. 누적 불명1건은 과거 승인된 예약 그대로다. 새 unknown/자동 재시도0.
- 회귀 근거를 구분한다. 수동 설정 채택 뒤 AI 전체281개는250PASS/31SKIP/0FAIL이며 `ai-final-offline-20261030-final`에 XML을 보존했다. 이후 자동 진단40초 보존 보완의 표적56개는46PASS/10SKIP/0FAIL(`ai-final-offline-20261030-auto-budget`)이다. 뒤의 표적 결과를 전체 재실행으로 세거나 두 PASS수를 합쳐 독립 테스트 수라고 보고하지 않는다. Python139PASS와 실제 실환경JUnit의 N8 FAIL도 별개다.
- 독립 검토: deadline/관측/waiver 단위와 cold-copy 단위, 실측 기반 채택/결과 대조 및 최종 자동 예산 보완 재검토에서 잔여 필수 지적 없음. 검토자가 새 원장·usage·검색128row·HTTP43row·생성22답변 인용/181scalar·실환경FAIL 및 회귀XML을 직접 대조했고 Python139개를 직접 통과했다. Java/Docker/paid 재실행·자원 정리/Git 보존은 구현자의 실행 결과와 구분했다. 자동 lease 지연 실공급자 시험과 부하 시험은 미검증이다.
- 종료·보존: 임시 API 키 사본을 삭제했고 이번 두 회차의 정상 정지된 소유 컨테이너6개·빈 네트워크2개만 제거했다. 볼륨/DB/Anvil 상태/STOP/원장/claim/성공·실패 증거 삭제0, 원본 cold-copy source 전체 파일 hash 동일을 확인했다. 컨테이너는 보존한 파일/구성으로 다시 생성할 수 있으나 자동 재시작하지 않았다. 신규 이미지 build0, C디스크 여유48.99GiB, 실행 중 시험 컨테이너0이다.
- Git: `feat/ai-latest-quality-20261030@1bc1c738b16baf095fcc2a539a3b33f8bc92c39d`의 미커밋 변경이며 stage/commit/merge/push0이다. 원본 HEAD/기존 미커밋8항목·전체24worktree(기존23+AI1)를 유지했다. 최종 공개 후보58파일의 고확도 API키/JWT/PEM/로컬사용자 경로 검사0건, Diff check PASS, gitleaks는 미설치다. 제한된 패턴 검사가 절대적 무유출 보장이라는 뜻은 아니다. runtime과 로컬 회고는 Gitignore다.
- 실제 HTTP 당시 study fingerprint는 `fca0dce491e15bc390972b6d0ae75e52d92fa8d89f5370b60230cff1c0c3ba14`다. 제품 기본값 채택/자동 예산 보완 뒤 최종 study fingerprint는 `5573337f338c43e2371f4cf2f98aa5442884e6df9baef1af434af7b86fe768df`, 제품 소스 fingerprint는 `4b23e968142949270f4f5695e54eafb4bd9b49fb1d503792fdf37755900d0697`다. 마지막 수정 이후 새 실제 유료 HTTP 전체 인수를 했다고 표현하지 않는다.

**다음 재개 경계:** N8 실제 모델 평가와 cache 실전비교/모델 반복 표본을 더 얻으려면 기존 입력 토큰 예약 상한을 변경하는 별도 승인이 필요하다. 현재 STOP·원장·불명 호출은 그대로 두고 새 호출을 강행하지 않는다. Q5/Q12/H04의 생성 보완도 실제 전후 비교 없이 추가 채택하지 않았다. C Vault AI·실사용자 거래·다른 기능 개발·원격 반영으로 자동 진행하지 않는다.

### 면접 학습: 측정에서 선택까지

1. **timeout은 왜 발생했나?** 외부 HTTPS 응답9.734초에 guard/직렬화 시간이 더해져 Java10초를 넘은 최초 사례와, 다음 Planning5초의 헤더 미수신은 서로 다르다. 남은 전체 deadline을 매 경계에 전달하고 완료 후에도 검사한다. 시간 확대는 실패 경계 조정일 뿐 가속이 아니다. 질문: socket read timeout과 전체 deadline의 차이, 취소돼도 외부 과금이 남는 이유는?
2. **왜 LLM Planning을 생략했나?** 자연어 전체를 규칙으로 분류하지 않았다. “현재 기준가격/시장 상태 조회” 같은 폐쇄 문법은 읽기 목적·target 종류가 확정되므로 동일 Tool을 직접 고를 수 있다. 실제 Q2는 불필요한 Skill/RAG/Synthesis까지 선택했던 오버라우팅도 제거했다. 질문: 규칙이 인식하지 못한 문장은? → 기존 모델로 fallback, owner/ADMIN검사는 항상 유지.
3. **왜 모든 질문을 빠르게 만들지 않았나?** 설명과 정책 결합은 Synthesis가 여전히 필요하다. 단순 조회의 exact Tool 값은 서버가 문장화하되, 원인 추론을 대신하지 않는다. 출력 토큰을 무조건 줄이면 필요한 수치 관계·유예 시각이 다시 빠질 수 있다. 질문: STATE 사실과 MIXED 해석의 정본은?
4. **Semantic/Hybrid의 차이는?** embedding cosine은 의미상 유사성을 잡지만 정확 식별자는 의미 벡터만으로 약할 수 있다. 이번 exact 식별자8/8과 자연어MRR 보존을 함께 확인했다. 전역 Hybrid는 자연어 순위를 떨어뜨려 기각했다. 질문: Hit@K가 같아도 MRR이 낮아지는 의미, 역할 필터를 TopK 뒤에 하면 안 되는 이유는?
5. **threshold를 높이면 왜 안 되나?** 무관 후보는5→2개로 줄었지만 정답 문서와 추가 질문도 사라졌다. 후보의 존재와 최종 환각은 다른 지표다. 실제 음성7개는 근거 부족으로 유보했다. 질문: 검색 recall/precision과 생성 grounding을 어떻게 나눠 평가하는가?
6. **Chunk/overlap을 왜 바꾸지 않았나?** 기존1200/60에서 Golden12/12이므로 이번 데이터는 재분할의 필요성을 입증하지 않는다. exact 식별자 손실은 랭킹 경계 문제였고 해결 범위를 좁혔다. embedding은 검색용 수치 벡터이지 거래 상태 캐시가 아니다. 질문: chunk가 커질 때 recall·문맥오염·입력 토큰 비용의 관계는?
7. **캐시는 무엇을 저장해야 안전한가?** 질문 벡터는 모델·corpus 기준으로 불변이지만 잔고/position은 변한다. vector만 캐시해도 매 요청 역할·scope·활성색인을 검사해야 한다. 구현했어도 실제 전후 측정이 없으므로 채택하지 않았다. 질문: TTL·LRU·defensive copy가 각각 방어하는 문제는?
8. **왜 무조건 비동기/병렬화하지 않았나?** 순서 의존적인 Planning→Tool→근거→Synthesis가 있고 DB/RPC가 관측 병목이 아니었다. 불필요한 LLM 제거가 더 직접적이었다. worker2·bounded deadline은 유지한다. 질문: 비동기가 응답 지연과 처리량에 주는 차이, timeout 후 비협조 작업 점유 관리란?
9. **왜 돈이 남아도 중단했나?** $5/호출 수/입출력 token은 별도 제한이다. 보수 예약과 실제 사용량을 구분하고, 모르는 호출을 비용0으로 환불하지 않는다. 입력예약이 먼저 소진됐기에 N8 실패와 반복측정 부족을 공개했다. 질문: 예약·실사용 관측·청구확정의 차이, timeout 재시도의 중복 과금은?
10. **무엇을 아직 설명할 수 없나?** Q5/Q12/H04의 과도한 PARTIAL, 실행가능성 Tool 문맥 부족, C Vault AI, cache 실측, 음성N8, 운영 부하/p95는 미완료다. 좋은 엔지니어링 근거는 성공 수치뿐 아니라 검증하지 않은 경계를 정확히 구분하는 데 있다.

## 당시 계획 — 충분한 시간 예산·응답 시간 최적화 후속

- 15/30/15/90 프로필의 첫 실행은 유료 호출 전 legacy 금융 fixture의 `LIQUIDATION_TIMEOUT`으로 중단됐다. broadcast까지 관측했으나 DB 확정이 되지 않은 별도 시험 환경은 보존하고 재전송하지 않는다. 이를 AI timeout의 재발로 분류하지 않는다.
- 추가 금융 거래 없이 이전에 canonical 검증·재무 fingerprint 불변을 확인한 AI 전용 PG/Anvil의 **정상 종료본**을 신규 경로로 cold-copy한다. 원본과 복사본 전체 해시, 소유 컨테이너 정지 상태, 원본 DB identity를 확인하며 신규 복사본에만 실행 marker와 시험 계정의 무작위 비밀번호를 갱신한다. 원본 사용자 자산/C Vault/손상 Anvil은 대상이 아니다.
- 복사 평가에서는 시험 전용 bean 설정으로 금융 Worker 실행을 막고 실제 DB/RPC 조회·canonical 검증·HTTP JWT·10Tool·4Skill·RAG·실모델은 유지한다. 평가 전 원본 금융 fingerprint 일치와 평가 후 불변을 확인한다. 새 거래 인수로 표현하지 않는다.

- 새 사용자 승인: 기존 불명 LLM1건 최대예약$0.02133을 전액 승계하고 $5/96LLM/200embedding 및 토큰 상한을 유지한다. 기존 STOP·원장·질문·실패 기록은 불변이다.
- 먼저 실험에 planning15초/synthesis30초/RAG15초/전체90초/HTTP110초를 적용한다. 제품 기본5/10/5/40은 측정 전 그대로 두며 설정으로 구분한다. stage deadline은 전체 단조 deadline의 남은 값 이하이고 Tool/SQL/RPC 경계·권한은 유지한다.
- 현재 소스에서 명시 Skill은 이미 planning을 생략한다. 이를 새 최적화 성과로 다시 세지 않는다. 새 후보는 엄격한 읽기 전용 STATE 문법에서만 계획을 생략하고, 인식 못한 질문·복합 질문은 기존 모델 경로로 둔다. 소유권 preflight/역할 검사는 동일하다.
- 동일 fixture·같은 질문으로 전후의 단계 지연/호출/토큰/정확성·충분성을 기록한다. 반복 표본이 적으면 p95를 소표본 nearest-rank 통계로만 표시한다. 후보2의 기존 실측과 새 최적화 후보는 구분한다.
- 사전 순수 테스트 → 독립 검토 → 실제 HTTP/검색 비교 → 측정 기반 채택/기각 → 최종 전체 회귀·독립 검토 순서다. 금융·JWT·Oracle·RAG승인·Tool허용범위는 완화하지 않고, 새 금융 기능과 원격 반영은 없다.

## 후속 최종 인수 결과 — 2026-10-30, PARTIAL / 과금 승계 승인 대기

**현재 판정: 전체 인수 미완료 / 추가 유료 호출 차단.** 후속 새 환경에서 실제9문서88chunk 색인과 금융 fixture/JWT/Tool을 다시 준비했지만, 첫 Q14의 **계획 분류5초 단계**가 응답 헤더를 받기 전에 timeout됐다. 기존 Q14의 **답변 생성10초 초과**와 구분한다. 새 Q14는 HTTP200/PARTIAL(`PLAN_UNAVAILABLE`)이고 synthesis는 미실행이다. 장애 후 금융 fingerprint는 동일하다. Q15/Q16·최종 H01~08·최종 검색 비교는 아직 NOT_RUN이며 아래 구현·무과금 검증을 실모델 최종 품질 PASS로 확대하지 않는다.

- 새 회차의 embedding6건은 OBSERVED, LLM1건은 RESERVED(사용량 불명)다. 누적 예약 **$2.270258260**, LLM43(기존 관측42+불명1), embedding56이다. 원래 승인 $5와 호출/토큰 한도는 유지되고, 이전 “전임 모든 예약 OBSERVED” 조건에 따라 추가 과금은 중단했다. 불명1건의 최대예약$0.02133을 전액 유지한 승계 예외는 사용자에게 질문했지만 응답 전에는 적용하지 않는다.
- 정확한 전송 관측: Python HTTPS 예산4579ms, 요청 전송218ms에 완료, 응답 헤더 없이4593ms에 UPSTREAM_TIMEOUT. Java는4999ms에 ABORTED, HTTP전체5055ms에 PARTIAL이다. 공급자 처리 지연인지 응답 네트워크 지연인지는 이 자료만으로 확정하지 않는다. 10초 생성 경계 수정의 실제모델 재검증도 이 실패로 완료되지 않았다.
- 실환경 근거: 첫 환경은 clock preflight 차단/유료0. 후속은 새 PG·Anvil·계약·현물FILLED·legacy Long 실제60초청산 VERIFIED·10Tool 실제HTTP·타인404/USER403·새색인 READY까지 도달했다. 임시키 사본 제거/소유컨테이너정상정지/STOP·원장·실패증거보존. 새 실제RAG/답변품질비교는 아직 성공 수치가 없다.

- 기존 두 평가의 STOP·원장·질문·실패 증거는 불변이다. 누적 보수 예약 $2.247494700 / LLM42 / embedding50을 새 세션에 정확히 한 번 승계하며 $5·96·200 및 기존 토큰 상한을 유지한다. 새 실제 호출은 오프라인 검증과 독립 검토 뒤에만 수행한다.
- 작업 단위: (1) Java→broker→HTTPS의 남은 deadline 전파와 전송/승인 pool 수명 분리, 결정적 경계 시험 (2) 현재 candidate2를 보존한 범위·시각 인용 보완과 STATE scalar 설명 (3) 동일 실제 vector로 semantic/selective identifier/global Hybrid/threshold 비교 (4) 새 PG/Anvil·9문서 index의 실제 JWT/API Q1~16/H01~08/음성 답변 인수 (5) 독립 검토·회귀·학습 자료.
- 금융·계약·JWT·Oracle·TTL·근거 검증은 변경하지 않는다. Q11의 실행 가능성 근거 부족은 없는 Tool 결과를 만들어 해결하지 않는다. H05는 통제된 PG pending 행+실제 RPC NOT_FOUND, H06은 통제된 PG 행+시험 전용 Receipt 어댑터로 명시하며 실제 실패 거래·canonical 실패 인수로 승격하지 않는다.
- 현재 변경은 미커밋이며 아래 1차 결과는 당시 실행 기록으로 보존한다. 수정된 최종 코드의 무과금 Java 회귀는248PASS/30SKIP/0FAIL(278개), Python phase24/latest27/deadline4PASS이다. 무과금 SQL component 추가 결과는 별도 기록한다.

### 이번에 끝낸 것과 끝내지 못한 것

| 항목 | 기존 근거 | 이번 실행·수정 | 최종 판정 |
|---|---|---|---|
| Q14 deadline | 생성 frame10초초과, HTTPS usage OBSERVED, fallback pool종료 | 남은 기한 전파·전송/pool 수명 분리·늦은 응답 격리·결정적 경계 회귀 | 구현/무과금 PASS, 실제 생성10초 재검증 NOT_RUN |
| 실제 HTTP Q14 | 이전에는 synthesis10초에서 중단 | 새 첫 계획분류5초에서 PARTIAL/STOP, 공급자 헤더 미수신 | FAIL, 기존 실패와 별도 보존 |
| Q15/Q16·H01~H08 | captured-input 또는 component 일부만 존재 | 실행 코드 준비, 새 전체 시나리오는 계획분류 실패 후 미도달 | NOT_RUN |
| Q5/6/7 범위·Q12/13 수치·H03 시각 | 후보2 비교와 기존 누락 기록 | 후보2 원문/해시 보존, 추가 일반 지시·schema facts20 정합화 | 구현, 실제 최종 충분성 미검증 |
| STATE 설명 | 값은 Tool에 있지만 본문은 일반 안내 | 성공 Tool의 허용 scalar만 정확한 본문에 표시, 추가 생성0 | 단위 PASS, 최종 실제 HTTP NOT_RUN |
| Q11 실행 가능성 | 현재 참조 시세만으로 실행 성공을 판단할 수 없음 | 존재하지 않는 실행 검증 결과를 만들지 않음 | 실제 근거 부족에 의한 유보 유지 |
| 전체 검색 비교 | 이전 golden12/12, ID6/8, 음성 후보5/8 반환 | 동일 query vector로4방식 비교 구현, 유료 생성 전에 중단 | 새 semantic/Hybrid 품질 비교 NOT_RUN |
| 식별자 SQL 경계 | 두 오류 이름이 semantic 기대문서에 안 잡힘 | 실제 새 pgvector DB의 exact 후보에8/8 기대문서 존재, ADMIN canary USER차단·domain 포함/제외 확인 | SQL component PASS, **query Hit@K 8/8이라는 뜻 아님** |
| AI 장애 후 금융 불변성 | 과거 실패 후 최종 검사가 생략될 수 있었음 | 성공/예외 모두 finally에서 fingerprint와 최소 조회 보존 | 실제 장애 후 동일함 PASS |

신규 SQL component는 실제 모델로 만든 현재 88개 corpus vector 중 하나를 **probe**로 사용했다. 새 사용자 질문의 embedding을 만들지 않았으며 의미 검색 순위·MRR·음성 답변 품질을 측정하지 않았다. 따라서 선별 식별자 경로와 추가 prompt는 **미커밋 구현 후보**이고 최종 품질 인수/정식 채택을 완료했다고 표현하지 않는다. 이전 두 후보의 실측 성과는 아래 원래 섹션에서만 인용한다.

### 실행·회귀·독립 검토

- Java 무과금 전체 AI+FuturesMath:278개 중248PASS/30SKIP/0FAIL. 최초 동일 회귀의1FAIL은 path gate 오활성화였으며 실패 XML과 수정 후 XML을 각각 보존했다. SQL component는 이후 별도 실제 PG/JDBC 시험1PASS/0SKIP이다. 이 서로 다른 범위의 숫자를 전체 실환경 인수 PASS로 합치지 않는다.
- Python: 기존 evaluation81, web-demo17, phase24, latest27, deadline4로153PASS. 외부 공급자 자동 재시도0. 실제 전체HTTP시험은1FAIL이며 미도달 항목은 NOT_RUN이다.
- 독립 검토: 실행준비 단위에서 발견한 필수3건은 수정 후 재검토에 잔여 필수 없음. 이후 실제 clock측정 복구/단일 승계 chain과 무과금 SQL component도 각각 검토했다. 검토자는 Python을 직접 실행했고 Java/Docker/실모델 실행은 구현자 증거를 확인한 범위다. 최종 비용·결과 문서 대조는 별도로 남긴다.
- 최종 증거 대조 완료: reviewer가 SQLite 세 원장·usage파일·XML·Q14 전송trace·금융after·SQLscope·조상binding과실제승계거부를직접재계산했다. 비용·실행범위는일치했다. 문서의STATE→RAG검증표현을실제조건부동작에맞게수정하고,새검색비교가NOT_RUN임을학습절에도명시했다. source/보안경계 잔여필수지적없음과전체인수미완료는별개다.
- 금융 계산/계약/권한/JWT/owner/Oracle/가격TTL/nonce/Receipt 검증은 변경하지 않았다. Java 생성10초와 계획5초, Agent 전체40초를 늘리지 않았다. 사용자 계정·지갑·손상된 과거 Anvil에는 접근하지 않았다.

### 누적 비용과 재개 경계

| 지표 | 누적 값 | 원래 승인 상한 |
|---|---:|---:|
| LLM 예약 호출 |43 (관측42, 사용량 불명1)|96|
| Embedding 관측 호출 |56|200|
| 보수 input/output token 예약 |640,048 / 55,600|1,200,000 / 153,600|
| 보수 embedding token 예약 |146,913|400,000|
| 보수 비용 예약 |$2.270258260|$5|
| 금액상 잔여 |$2.729741740|불명 전임 승계 조건으로 집행 차단|

관측된 실제 사용량은 LLM input115,581/output18,630, embedding49,561이다. 관측분 표준단가 추정은 $0.455713220이며 **불명 LLM1건을 포함한 최종 청구 금액이 아니다**. 불명1건의 input6,612/output400 최대예약 $0.02133은 반환하거나 관측 사용량으로 바꾸지 않았다. 새 세션을 만들어 이 예약을 숨기지 않았고 STOP·원장·claim을 지우지 않았다. 원래 조건으로는 `PARENT_USAGE_UNCERTAIN`에 따라 더 진행할 수 없다. 비용 총액을 올리는 대신, 전액예약을 유지한 **해당 불명 건의 승계 예외**가 필요한지 사용자에게 한 번 질문했다. 회신 전 추가 유료 호출은 없다.

### 코드·증거·보존

- 제품 변경: `ai/provider/CallDeadline.java`, `OpenAiProvider.java`; `ai/agent/AgentService.java`, `StateExplanation.java`, `OpenAiAgentProvider.java`, `main/resources/ai/agent-quality-instructions.txt`; `ai/retrieval/IdentifierQuery.java`, `AuthorizedKnowledgeRetrieval.java`; `ai/store/KnowledgeStore.java`, `PgKnowledgeStore.java`. 패키지 앞 경로는 `backend/src/main/java/com/pricetrack/exchange/`다.
- 실행 근거는 ignored runtime의 `phase-a-20261030000346-09dce7e8`(clock 최초 실패·정밀 진단), `phase-a-20261030001548-57a5e497`(새 fixture·index·HTTP계획실패·financial-after·SQL component), `ai-final-offline-20261030`(실패/성공 Java XML)에 분리 보존했다. 최신 회차의 `latest-study/identifier-sql-component.private.json`, `questions.private.json`, `financial-after.private.json`, `budget.sqlite`, `java-transport-diagnostics.jsonl`와 `python-transport-*.jsonl`이 직접 근거다. 비공개 경로나 내용을 Git에 올리지 않는다.
- Git은 `feat/ai-latest-quality-20261030@1bc1c738b16baf095fcc2a539a3b33f8bc92c39d` 미커밋이다. stage/commit/merge/push0. 전체24worktree(기존23+기존 AI 작업1), 원본HEAD와 미커밋 목록은 보존했다. 지시된 로컬 회고 인덱스만 기존 사건의 후속으로 갱신했다.
- 공개 후보45파일의 고확도 API-key/PEM/JWT 패턴과 private-path 점검0건, `git diff --check` 정상, gitleaks는 미설치여서 실행하지 않았다. 이는 절대적인 비밀정보 무유출 증명은 아니다. 키는 JVM으로 전달하지 않고 이번 새 API키 임시사본을 종료 시 제거했다. 원본 credentials는 변경하지 않았다.
- 신규 이미지 빌드0. 이번 소유 정지 컨테이너5개/빈 네트워크2개만 identity 확인 후 제거했다. **볼륨·DB/Anvil 데이터·snapshot·증거 파일 삭제0**이며 다른 Docker 자원은 건드리지 않았다. 논리 데이터는 보존 경로/compose identity로 복원 가능하지만 실패 인수를 그대로 재실행하는 자동 경로는 없다.

### 후속 의사결정을 이해하기 위한 기술 학습

아래는 실제 코드에서 확인되는 구조다. 새 실환경의 품질 수치는 후속 결과가 확정될 때 별도 기록한다. 검토하지 않은 대안을 당시 고민했던 것처럼 서술하지 않는다.
이번 신규 semantic/선별식별자/Hybrid/threshold **비교 하네스는 구현했지만 실제 질문 vector 비교는 NOT_RUN**이다. 아래의 비교 설계와 과거 Phase3 실측을 혼동하지 않는다. OpenAI Docs 확인 절차에 따라 명시된 모델을 교체하지 않고 `completed` 응답만 수용하는 기존 계약을 유지했다([Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create)).

```mermaid
flowchart TD
  A[JWT 사용자·명시적 target 소유권 검사] --> B[고정 Skill 또는 제한된 계획 분류]
  B --> C[서버가 정한 읽기 Tool · 최대 4회]
  C --> D{STATE인가}
  D -->|예| E[검증된 scalar의 결정적 설명]
  D -->|아니오| F[질문 Embedding · 실제 pgvector 검색]
  F --> G[SQL 역할·domain 필터 → 후보 → Top5 · 문서당 최대2]
  G --> H[정책 Chunk와 비식별 Tool 사실로 답변 생성]
  H --> I[인용 ID·JSON pointer·원문 scalar 검증]
  I --> J[정책 근거가 있으면 활성 index·승인 corpus 재검증 후 HTTP 응답]
  E --> J
```

1. **Chunking과 Overlap** — `MarkdownChunker.split/flush`는 제목 경로를 붙이고 문단·행·문장 경계를 우선한다. 설정 이름은 `chunkTokens=1200`, `overlapTokens=60`이지만 실제 분할 예산은 보수적인 UTF-8 **바이트** 기반이며 모델 tokenizer로 센 정확한 토큰 수가 아니다. 표를 나누면 헤더를 반복하고, 문장 경계에 맞는 overlap을 만들 수 없으면 생략한다. 이번에는 충분한 Tool 수치를 생성기가 누락한 문제를 chunk 변경으로 해결하지 않았다. 질문: “청크를 크게 하면 recall이 항상 오르나?” → 문맥 보존과 노이즈/입력 비용의 절충이며, 실제 답변에 필요한 구절과 순위를 측정해야 한다. 꼬리 질문: 표의 단위와 행이 다른 청크로 갈라지면 어떻게 되는가?

2. **Embedding과 Semantic Search** — 승인 manifest의 9문서만 `KnowledgeLoader`가 해시/metadata를 검증한 뒤 1536차원 벡터로 만든다. `PgKnowledgeStore`는 `vector(1536)`와 cosine 거리 `<=>`를 사용한다. 현재 schema에는 HNSW/IVFFlat ANN 색인이 없으므로 이를 대규모 근사 검색 성능 검증으로 설명하면 안 된다. similarity는 의미 유사도이지 사실 확률이 아니다. 관련 문서가 있어도 질문의 핵심 구절이 부족하면 답변은 미완료다. 질문: “벡터 DB를 쓰면 정답이 보장되나?” → 아니며 corpus 승인, 접근권한, 검색 충분성, 답변 검증을 따로 확인한다.

3. **Hybrid와 정확 식별자** — 과거 전체 FTS/RRF Hybrid는 식별자 Hit를 개선했지만 일반 질문 MRR을 낮췄다. 이번 비교는 같은 query vector와 같은 DB를 재사용해 semantic·선별 exact identifier·기존 global Hybrid를 대조한다. `IdentifierQuery`는 짧은 명시적 ASCII 오류 이름에만 반응한다. SQL의 role/domain 필터가 **순위 계산 전에** 적용되고 lexical 가산값을 cosine 점수처럼 표시하지 않는다. 과거 일반 질문의 정상 top similarity가 약0.2946이었으므로 threshold를0.35로 올리면 일부 음성 후보를 줄여도 정상 질문을 놓칠 수 있다. 질문: “왜 최신 기술인 Hybrid를 전면 적용하지 않았나?” → 이 데이터의 측정 결과와 회귀 비용이 기준이며, 더 복잡하다는 이유로 채택하지 않는다. 꼬리 질문: 식별자의 철자가 틀리거나 여러 오류가 섞이면?

4. **검색 평가와 환각 구분** — Hit@K는 기대 문서가 후보 안에 있는지, MRR은 첫 관련 문서의 순위가 어떤지다. 문서 경로 Hit와 정확한 답변 근거 Chunk의 충분성은 같지 않다. 무관 질문에 후보가 반환됐다는 것만으로 환각이라 부르지 않고, 실제 답변이 승인 문서 밖의 지식을 만들었는지 별도로 읽는다. `LatestRetrievalBenchmark`는 동일 vector 대조를 하고 실제 음성 HTTP 질문은 별도 생성 결과를 남긴다. 작은 고정 세트의 단일 실행이며 통계적 일반화나 전체 환각률을 주장하지 않는다.

5. **Tool Calling과 정본** — 이 프로젝트는 모델에 SQL이나 전송 도구를 직접 제공하지 않는다. `OpenAiAgentProvider.classify`의 닫힌 route/subject/Skill 분류와 서버의 `AgentService.executeTools`, `SkillRunner`가 허용된 조회 순서를 결정한다. Tool 수치가 현재 사실의 근거이고 RAG 문서는 규칙의 근거다. AI는 계약을 실행하거나 청산하지 않는다. legacy DB 담보 선물과 C Vault 자산은 다른 모델이며 이번 Tool 평가를 C 자산 진단 성공으로 확대하지 않는다. Q11에서 실제 실행 가능성 검사가 Tool에 없다면 현재 시장 관측은 설명하되 주문 가능/성공을 보장하지 않는 것이 올바르다.

6. **답변 생성과 근거 검증** — Q12는 정확한 숫자가 Tool 입력에 있었지만 본문에 수치 관계가 빠졌다. 이전 두 후보 비교에서 숫자를 본문에도 연결한 후보2를 채택했다. 이번 보완은 질문에 필요한 불확실성만 유지하고 청산 유예를 설명할 때 사건 시각도 근거에서 인용하도록 한다. `AgentEvidence.validate`는 인용 ID와 JSON pointer의 실제 scalar 문자열을 검증하며 decimal 끝의0도 보존한다. 기존 facts20개 한도를 생성 schema에도 명시해 공급자와 서버 계약을 맞췄다. 단 **본문 모든 문장의 의미를 기계적으로 증명하는 검증은 아니다**. 수치 설명의 정확성과 충분성은 실제 답변을 별도 평가한다. 질문: “Structured Output이면 환각이 사라지나?” → JSON 형식 준수와 사실/의미 검증은 다른 문제다.

7. **STATE의 결정적 설명** — 이전 STATE 응답은 실제 Tool 수치가 있어도 본문은 정형적이었다. `StateExplanation`은 성공 Tool에서 허용된 scalar만 골라 상태/잔고/가용량을 그대로 설명한다. 추가 생성 호출 없이 비용과 지연을 줄이며 사용자 ID나 로그인 이름은 복사하지 않는다. 장점은 정확한 원문과 예측 가능한 동작, 한계는 질문에 맞춘 자유로운 해설이 제한적이라는 점이다. 이 경로를 LLM의 고급 추론 성과로 보고하지 않는다.

8. **Deadline·비동기 처리·fallback** — Q14의 기존 HTTPS 응답과 서비스의 성공 응답은 다른 사건이었다. Java→Python 전달, 검증·예약, HTTPS, 역직렬화와 승인 재검증은 모두 시간을 쓴다. `CallDeadline`은 단조 시계로 남은 시간을 계산하고 같은 호스트의 `FrameDeadline`은 handoff 벽시계 상한과 남은 예산 중 작은 값을 사용한다. 예산을 각 단계에서 다시10초로 초기화하지 않는다. 늦은 사용량은 관측할 수 있어도 답변 성공으로 승격하지 않는다. 전송 중단과 RAG 승인 pool 종료를 분리해야 fallback이 기존 문서 승인을 확인할 수 있다. 다만 실제 승인이 변경되면 문서는 제외된다. 질문: “timeout이면 외부 호출이 실행 안 됐나?” → 아니며 호출 취소/응답 수신/과금 확정은 다르다. 꼬리 질문: 취소를 무시하는 외부 호출과 늦은 응답 프레임을 어떻게 격리하는가?

9. **자원·비용·실험 재현성** — `EvaluationGuard`는 외부 전송 **전** SQLite에 보수 비용을 예약하고, 사용량이 검증돼야 OBSERVED가 된다. UNKNOWN/RESERVED를 환불하거나 새 원장으로 지우지 않는다. successor는 이전 carry와 자체 예약을 합산하며 단일 CREATE_NEW claim이 sibling 예산 재사용을 막는다. STOP은 한 세션의 종료이지 전체 예산을 초기화하는 버튼이 아니다. 신규 DB/체인/corpus/source/image identity는 별도로 기록한다. Windows RAM1920MiB, 디스크2GiB와 금융 시간 경계는 유지한다. 질문: “실패했는데 왜 예약액을 반환하지 않나?” → 이미 수행된 외부 호출의 청구를 알 수 없기 때문이다. 실제 사용량 추정 비용과 보수 예약액, 최종 공급자 청구서는 구분한다.

10. **측정 자체의 오버헤드** — 추가 실환경에서 기존 clock preflight의3.46초 구간이 환경변수 준비와 로그 I/O까지 포함함을 확인했다. 이를 실제 SQL subprocess 전후로 좁힌 진단3회는 worst0.52~0.54초였다. 2초 허용치를 높이지 않고 측정의 불확실성 범위를 정확히 한 사례다. 첫 수치만으로 실제 WSL 시계가3초 틀렸다고 단정하지 않았고 시스템 시간도 바꾸지 않았다. 질문: “측정치를 좋게 만든 것이 기준 완화 아닌가?” → 보호식은 동일하고 제거한 구간은 SQL 요청 이전의 환경 준비 및 응답 이후 파일 기록이다. 실제 통신 지연은 여전히 전부 포함한다.

## 2026-10-30 최종 결과 — 실제 측정·두 후보 비교·제품 지시문 반영

### 5분 요약

최신 10 Tool/4 Skill/승인 지식 9개를 실제 모델에 연결했다. 새 PostgreSQL·Anvil의 현물 체결과 legacy 선물 60초 청산 근거를 준비하고, 실제 JWT/HTTP Tool 조회와 새 pgvector 색인을 검증했다. 다만 **전체 HTTP 인수는 PARTIAL**이다. Q14에서 10초 경계 초과로 안전 중단됐고, Q15/Q16 및 추가 문항의 실제 HTTP 경로는 실행되지 않았다. 이 실패를 후속 생성 비교의 성공으로 덮지 않는다.

Q12의 핵심 문제는 필요한 수치가 검색·Tool에 없어서가 아니었다. 정확한 scalar가 전달됐는데도 본문에서 수치 관계를 제대로 설명하지 않은 것은 확인된 사실이다. 당시 지시문의 “실제 숫자는 facts를 가리킨다”와 범위 밖 유보는 기여요인 가설로 구분한다. 두 후보의 단일표본 비교에서 **질문 범위 정돈 + 사건 당시 수치 설명** 후보의 결과가 더 좋아 채택했으며, 인과관계를 통계적으로 확정한 것은 아니다. 테스트한 지시문과 제품 지시문의 동일성을 SHA-256으로 검증했다. 모든 금융·JWT·owner·Oracle·TTL·서버 근거 검증은 그대로다.

추가 개선까지 완벽한 것은 아니다. Q5/H04의 과도한 유보, STATE 본문의 정형 문장, H03의 유예시각 인용 누락, identifier 검색 6/8과 검색 음성의 비어 있지 않은 결과 5/8이 남았다. C Vault AI, 실제 사용자 브라우저·MetaMask, 전체 감사 완료를 뜻하지 않는다.

### 1. 무엇을 실제로 검증했는가

| 경로 | 실제 결과 | 판정 범위 |
|---|---|---|
| 새 거래 PG·Anvil | spot FILLED, Long OPEN→START_CALL→실제 60초 뒤 LIQUIDATED, 선택 canonical Event/journal VERIFIED | 신규 시험 모의자산, legacy DB 담보 선물 |
| JWT/HTTP Tool 10개 | USER/ADMIN 실제 조회 성공, 타인 position404·USER adminTool403 | 실제 제품 인증/조회 경로, 사용자 실자산 아님 |
| 최신 RAG | 9문서/88chunk, 실제 embedding6회로 새 index READY | 과거 index 복사로 대신하지 않음 |
| 실제 HTTP Q1~Q13 | ANSWERED6/PARTIAL7, 4종 Skill 질문 실행 | STATE4개는 모델 planning 후 서버 정형 본문/Tool 데이터 |
| 실제 HTTP Q14 | Java frame10000ms→10002ms에 중단 | 첫 실패, 자동 재전송0 |
| 실제 HTTP Q15/Q16·H01~H08 | NOT_RUN | 후속 component 비교로 PASS 승격 금지 |
| 고정 입력 생성 비교 | 후보1:7/8 검증, 후보2:8/8 검증 | 이번 실제 HTTP 입력의 캡처, 새 HTTP/RPC 아님 |
| 별도 H01~H06 | 실제 RAG/LLM, scalar/인용 검증6/6 | H05/H06은 통제 음성 입력, 실제 실패 Receipt 아님 |
| 제품 반영 이후 | 동일 지시문 검사 및 관련 무과금 회귀 | 반영 후 전체 실환경 재실행은 하지 않음 |

### 2. 검색·Tool·생성의 원인을 분리한 결과

- 검색 golden: 기대 **문서 경로** Hit12/12, MRR0.8819444444. 이것만으로 정답 Chunk 충분성이나 답변 품질까지 PASS가 되는 것은 아니다.
- identifier: 기대 문서 Hit6/8, MRR0.6041666667. 남은 두 식별자 검색은 미해결이다.
- 검색 음성8개: 빈 결과3/8, 비관련 결과가 반환된 질문5/8(슈뢰딩거 방정식, VIP 할인, 담보대출 금리, 비트코인 반감기, 추천 리워드). 해당 답변 생성은 실행하지 않았으므로 환각률5/8이라고 말하지 않는다. 검색 임계값만으로 미지원 질문이 차단된다고도 말하지 않는다.
- Q12/Q13: 관련 선물 정책 Chunk와 실제 진입가·수량·사건 담보·손익·equity·maintenance가 있었다. **GENERATION_OMISSION**이 주된 원인이었다. 검색 K/청크/Hybrid 변경 대신 이 원인에 두 비교 예산을 집중했다. 검색 음성 문제까지 해결했다는 뜻은 아니다.
- Q11: Tool은 시장 참조 관측을 주지만 실행용 TradingPriceContext/연결 generation을 제공하지 않는다. 실제 거래 가능 여부를 확정하지 못하는 것은 근거 범위의 한계이지, 단순 프롬프트 문제만은 아니다.
- Q5/6/7: 요청하지 않은 체결·과거 SIGNED 시각·절대 finality 보장까지 미확인 사유에 넣었다. 알려진 검증 범위의 한계는 본문에 유지하되, 질문의 핵심 미확인 사실과 구분할 여지가 있었다.

| 기준 질문 | 실제 Agent | 충분성 0~2 | 확인 내용/남은 문제 |
|---|---|---:|---|
| Q1 견적 일회용 | ANSWERED | 2 | DB 소비와 체인 usedQuoteIds, CONSUMED≠체결 |
| Q2 시장 | ANSWERED | 1 | 실제 데이터는 Tool에 있으나 본문 정형 안내 |
| Q3 자산 | ANSWERED | 1 | 본인 portfolio 조회, 본문 수치 해석 없음 |
| Q4 주문 | ANSWERED | 1 | 실제 FILLED 조회, 본문 정형 안내 |
| Q5 견적/만료 | PARTIAL | 2 | 질문 밖 체결 부재 유보 |
| Q6 전송/enum | PARTIAL | 2 | 질문 밖 SIGNED 시각 유보 |
| Q7 확정 근거 | PARTIAL | 2 | 절대 finality 한계의 중복 유보 |
| Q8 관리자 목록 | ANSWERED | 1 | 실제 목록 조회, 본문 정형 안내 |
| Q9 정산 진단 | PARTIAL | 2 | 현재 지연 없음을 설명, 과거 이력 가정 유보 |
| Q10 견적 진단 | ANSWERED | 2 | 현재 만료와 과거 성공을 구분 |
| Q11 시장 가용성 | PARTIAL | 1 | 실행 context가 Tool에 없음 |
| Q12 청산 이유 | PARTIAL | 1 | facts 정확, 본문 수치 관계 누락 |
| Q13 5%/마진콜 | PARTIAL | 1 | 실제 값을 pointer로 떠넘김 |
| Q14 체인 근거 | 중단 | 0 | 합성 결과가 서비스에 반영되지 못함 |

이 표는 **본문 충분성**까지 평가한다. STATE의 Tool 데이터가 없었다는 뜻은 아니다. 정확성·충분성·근거·불확실성의 질문별 0~2 수동 평가는 비공개 `quality-assessment.private.json`에 별도로 남겼다. Q14는 오류를 사실 오답으로 채점하지 않고 일부 항목을 null로 남겼다.

### 3. 두 기술 대안과 선택 근거

| 비교 | 변경 변수 | 기존 근거 검증 | Q12/Q13 본문 | 관측 합성 지연 중앙/최대 |
|---|---|---|---|---|
| 후보1 | 핵심 불확실성과 일반 범위 한계를 구분 | 7/8 | 수치 설명 여전히 부족. Q12 facts22로 거부 | 6641/8345ms |
| 후보2 | 후보1에 사건 당시 수치·관계 설명 추가 | 8/8 | 정확한 서버 scalar로 수치와 E≤MM 연결 | 6550.5/7770ms |

후보2를 채택했다. 후보1은 “문장이 그럴듯하다”와 별개로 사실 참조20개 제한을 위반했으므로 그대로 서비스할 수 없었다. 제한이나 assertion은 삭제하지 않았다. 후보2는 개발8문항에서 인용·scalar 검증을 모두 통과했고 Q12의 수치 설명이 개선됐다. 이 지연은 고정 입력의 합성 component 시간이다. 전체 HTTP 시간과 직접 비교한 SLA 개선율이 아니며, 표본은 문항별 1회라 통계적 우월성을 주장하지 않는다.

변경하지 않은 대안은 검색 K/임계값·청크·Hybrid 조정이다. Q12는 이미 정책과 정확한 Tool 숫자가 전달됐기 때문에 우선순위가 낮았다. 검색 음성5/8 문제에는 별도 검증이 필요하지만, 승인된 두 개선 비교 범위를 넘겨 세 번째 튜닝을 실시하지 않았다. schema의 facts maxItems20 정합화도 이번에 추가하지 않았다. 현재는 서버의20개 검증이 마지막 방어이며 위반 가능성이 남는다.

### 4. 실제 수치로 보는 개선

이번 **새 legacy 시험 포지션**은 Long10배, 진입가75000.00000000, 수량13.333333333333333333, 사건 담보100000.000000000000000000이었다. 평가가격60000.00000000에서 서버가 기록한 값은 다음과 같다.

| 필드 | 실제 Tool/이벤트 근거 값 |
|---|---:|
| 미실현손익 | -199999.999999999999995000 |
| equity | -99999.999999999999995000 |
| 유지증거금 | 39999.999999999999999000 |
| 청산 실현손익 | -100000.000000000000000000 |
| badDebt | 99999.999999999999995000 |

기준 답변은 “equity는 maintenance 이하였으며 수치는 facts에 있다”고 설명했다. 후보2는 담보+손익=equity, equity≤maintenance, 유예 마감1793297654 뒤 체인 시각1793297656에 실행된 사실을 본문에서도 연결했다. 금액을 새로 계산하거나 정산 규칙을 바꾼 것이 아니다. 현재 포지션의 표시값과 사건 당시 담보를 혼동하지 않도록 지시했다. 사용자의 기존 C Vault 자산·MetaMask 거래 수치와 무관하다.

별도 H01/H02는 토큰 소유권 한계와 실제 캡처 체결 가격·수량·수수료를 정확히 설명했다. H03은 핵심 수치 설명이 개선됐지만, 사전 requiredFacts에 포함한 **canonical grace timestamps를 답변/facts에 인용하지 않아 충분성1**로 남겼다. H04는 모의 시장/신선도 정책을 설명했으나 범위 밖 주문 성공 유보가 남았다. H05/H06은 receipt부재/실패 원인 미기록을 유지해 성공·실패나 만료 원인을 만들어내지 않았다. H05/H06은 actual LLM + actual RAG + controlled negative Tool input 검증이며 실제 실패 거래 인수가 아니다.

### 5. 비용·토큰·재시도

| 항목 | 실제 사용량/보수 예약 | 승인 한도 |
|---|---:|---:|
| LLM POST | 42 | 96 |
| Embedding POST | 50 | 200 |
| LLM 입력 토큰 | 실제115581 / 예약633436 | 예약1200000 |
| LLM 출력 토큰 | 실제18630 / 예약55200 | 예약153600 |
| Embedding 입력 토큰 | 실제25372 / 예약75235 | 예약400000 |
| 비용 | 표준단가 사용량 추정$0.455229440 / 보수 예약$2.247494700 | $5 |
| 자동 재시도/미확정 과금 | 0 / 0 | unknown이면 전송 차단 |

사용량 단가 추정은 [Terra 공식 가격](https://developers.openai.com/api/docs/models/gpt-5.6-terra)과 [Embedding 공식 가격](https://developers.openai.com/api/docs/models/text-embedding-3-small)에 따른 input$2/M·output$12/M·embedding$0.02/M이며 청구서 확정액이 아니다. cache 할인/쓰기 요금·세금·환율·다른 클라이언트는 별개다. 보수 예약은 UTF-8 전체 요청+framing과 출력 상한, input$2.50/M을 썼고 성공 후에도 환급하지 않았다. 다음 호출은 이 예약액을 기준으로 차단한다.

Q14도 실제 사용량이 확인됐으므로 비용에 포함했다. 중단된 부모의20LLM/44embedding·$0.823191320을 전액 승계하고 child에서22LLM/6embedding을 추가했다. 부모의 STOP/원장/근거 hash는 변경하지 않았다. 모든 예약은 OBSERVED이며 RESERVED/BREACH는0이다. Q14를 다시 호출하지 않았다. 제품 반영 뒤 추가 유료 호출도 없다.

### 6. 실제 변경과 보존

- 제품 변경: `OpenAiAgentProvider.synthesize()`에 측정한 suffix만 추가하고 `backend/src/main/resources/ai/agent-quality-instructions.txt`에 보관했다. 기존 base 지시문 hash `82db4407…`, 선택 suffix hash `710f5eb3…`를 실제 직렬화 요청에서 대조한다. planner·1600 출력 상한·JSON schema·facts20·권한/근거 검증·제품10초 기한은 그대로다.
- 최종 무과금 회귀: Java10클래스83 PASS/0FAIL/0SKIP, Python표적119(신규21+기존evaluation81+web17) PASS. 새 지시문 리소스가 없으면 해당 AI 요청만 공급자 호출 전에 거부하는 회귀도 포함한다. 실제 HTTP 시험의1FAIL과 이 단위 테스트 결과는 별개다.
- 준비/관측 도구: 새 `$5` 명시 정책·closed OBSERVED 원장 승계·신규 시험 recipe·test-only HTTP 구성·고정 후보 및 질문 세트. 기존 `$2` 기본 정책·기존 단일 질문 web scope·Windows 경로는 보존했다.
- 실제 실험의 source는 HTTP `c9afdc51261a72b3cee8562ca61e6bf693c565665216eab9f888b6dd22b8c463`, 비교 `4d36715916583fbb4dc127d74f427f19caaf023000487217775bde03e025deec`다. corpus는 `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`다. 이후 제품 반영으로 source는 달라졌고, 동일 suffix 검증과 무과금 회귀를 수행했다. 반영 후 full paid HTTP 성공으로 소급하지 않는다.
- Git: 별도 `feat/ai-latest-quality-20261030@1bc1c73`의 미커밋 변경이다. 원본 main/melon-init 및 기존23 worktree/dirty 변경은 그대로, 이번 worktree를 포함하면24개다. commit/merge/push 없음.
- 이전 사용자 DB·손상 Anvil·checkpoint·과거 AI STOP/claim은 사용하지 않았다. 이번 소유 컨테이너는 정상 정지했고 원장·로그·DB 및 체인 시험 증거는 보존했다. 최종 financial fingerprint 대조 assertion은 Q14 중단으로 미실행이므로 금융 읽기 전후 전체 인수 PASS로 주장하지 않는다.
- 종료 보안: 비교 child에도 STOP을 남겼고 이번에 만든 임시 API 키 사본2개만 제거했다. 기존 credentials 원본은 변경하지 않았다. 원장/질문/진단/DB/체인 증거는 제거하지 않았다. 비공개 runtime은 Git 제외이며 source 변경·STOP·키 부재로 옛 준비본의 추가 호출은 차단된다.
- 최종 독립 검토 `review_ai_acceptance_baseline`: 필수 수정 없음. 검토자는 새 Python21/diff-check를 직접 실행하고, Java XML83개를 합산했으며, 신규 두 원장을 RO로 대조해92 OBSERVED·모든 비용/토큰 수치 일치를 확인했다. 후보 실패/개선 답변과 scalar도 원문 대조했다. Java·Docker·실제 모델은 재실행하지 않았다. 단일표본 원인 가설과 확인 사실 분리, 면접 질문 수 정정 제안은 반영했다. 공개 후보24파일의 제한된 키/JWT/PEM 패턴 검사0건은 절대적 무유출 보장이 아니다.

### 7. 근거 파일과 학습 순서

주요 코드: `backend/src/main/java/com/pricetrack/exchange/ai/agent/{AgentService,AgentEvidence,OpenAiAgentProvider}.java`, `ai/retrieval/AuthorizedKnowledgeRetrieval.java`, `ai/tool/read/FuturesToolReader.java`, `futures/math/FuturesMath.java`. 실행/비용은 `deployment/{ai_evaluation_guard,ai_latest_evaluation_broker,ai_latest_fixture_init,ai_latest_compare_run}.py`, 시험은 `LatestAiAcceptanceTest`, `LatestAiConfiguration`, `LatestAiComparisonTest`, `OpenAiAgentProviderTest`다.

비공개 근거의 공통 루트는 `deployment/runtime/phase-a-20261029174809-d30067c2`다. `latest-study/`의 `questions.private.json`, `retrieval.private.json`, `tools.private.json`, `fresh-fixture.private.json`, `java-transport-diagnostics.jsonl`과 Python 전송 trace, `latest-comparison/`의 `comparison.private.json`, `holdout-retrieval.private.json`, `selection.private.json`, `completion.private.json`, 양쪽 `usage-*.json`·`budget.sqlite`, 루트의 `quality-assessment.private.json`을 연결한다. private runtime은 Git 제외다. 질문별 Chunk/순위·Tool 원문·답변/인용·지연·토큰은 원문 JSON으로, 해석/점수는 별도 수동 rubric으로 구분했다.

### 8. 면접에서 설명할 기술적 의사결정

**왜 RAG 설정부터 바꾸지 않았는가?** Q12 입력에 이미 관련 정책과 정확한 재무 수치가 있었다. 검색 성공 여부, Tool 근거, 생성 내용을 분리해 보니 본문 누락이었다. 다만 음성 검색5/8은 별도 실제 문제로 남아 있으므로 “RAG가 완벽하다”는 결론은 내리지 않았다.

**왜 ANSWERED 비율만 높이지 않았는가?** 모델의 ANSWERED는 구조화된 사실을 설명할 수 있다는 뜻이고, 서비스는 uncertainties가 있으면 PARTIAL을 반환한다. 후보1은 JSON 형태가 맞아도 facts22 때문에 거부됐다. 후보2도 H03의 미리 정한 시각 인용 조건을 빠뜨려 rubric상 완전 답변이 아니다. 상태·구조·의미적 품질은 별도 축이다.

**왜 계산을 모델에게 맡기지 않았는가?** 정본은 계약/금융식/DB journal/검증된 Tool scalar다. 모델은 제공된 숫자를 인용하고 관계를 설명한다. scalar JSON pointer와 값을 서버가 확인하고, 청산·지급·재전송 권한은 Tool에 없다. 자연어 문장 전체를 형식 검증만으로 증명할 수는 없어 수동 검토도 필요하다.

**시간 초과인데 왜 과금은 성공했는가?** 서버 결과 수신과 공급자 처리·청구는 다른 경계다. Java는 frame 송신부터10초를 쟀고, Python HTTPS는 그 전에 약0.28초를 사용한 뒤 시작됐다. HTTPS 자체는 약9.734초였지만 전체는10.016초여서 Java가 먼저 중단했다. 다음 호출에는 남은 end-to-end budget을 전달해야 한다는 원리를 보여준다. 이번에는 제품 기한을 늘려 성공으로 만들지 않았다.

**재실행하면 예산이 초기화되지 않는가?** 초기화하지 않았다. 전송 전 SQLite BEGIN IMMEDIATE로 보수 예약을 커밋하고, unknown은 계속 차감한 채 차단한다. 이번 부모는 모두 OBSERVED임을 확인한 뒤 STOP/원장/근거 hash와 누적6개 한도를 child에 승계했다. 실패 Q14는 제외했다. 이는 일반적인 자동 재시도 허가가 아니다.

**SQLite에서 왜 자기 잠금이 발생했는가?** 같은 원장의 write transaction 안에서 callback이 새 connection으로 CREATE/INSERT를 하려 했다. 다른 thread가 아니어도 connection별 writer lock은 충돌한다. 임시 DB/fake sender로 재현하고, 최초 동결은 write transaction 밖에, 내부 재검증은 읽기 전용으로 분리했다. 실제 API 호출 전에 발견한 테스트 하네스 결함이다.

**사용자별 정보를 어떻게 제한했는가?** 서버의 JWT principal과 소유권 검사로 Tool 대상을 정하고, 지식 검색은 SQL 역할 필터를 top-K보다 먼저 적용한다. 모델에 전달할 때 ID·raw·서명·인증정보를 제거하고 서버가 허용한 Tool/Skill만 실행한다. 이번 실제 HTTP에서 타인 position404와 USER의 관리자 Tool403은 확인했지만, 추가 H07/H08 Agent HTTP까지 검증했다고 넓히지는 않는다. C Vault 지갑 소유권 AI 경로도 이번 legacy 시험과 별개다.

**무엇을 아직 설명할 수 없는가?** 전체 HTTP 후반부, C Vault 포지션 AI, 실제 실패 거래 H05/H06, 추가 권한 거부 Agent HTTP H07/H08, 음성 검색 후 실제 답변, 장기 반복 실행의 오류율·SLA는 미검증이다. 한 번의 생성 비교와 Mock 회귀로 이를 완료 처리하지 않는다. 다음 최소 검증은 새 유료 비교를 늘리는 것보다 기존10초 전체 기한과 fallback 관측 경계를 정리하고, 별도 승인된 실행에서 미실행 HTTP 구간을 검증하는 것이다.

## 실제 최신 실행 중간 결과 — 2026-10-30 / Q14 시간 경계 중단, 재전송 없음

- 새 격리 PG·Anvil에서 실제 spot FILLED, Long OPEN→START_CALL→실제 60초 후 LIQUIDATED 및 선택 canonical Event/journal VERIFIED를 준비했다. 별도 정상 OPEN 포지션은 INCONCLUSIVE 음성 근거다. 실제 JWT USER/타인/ADMIN의 HTTP Tool 10개 성공, 타인 position404/USER adminTool403을 확인했다. C Vault 또는 사용자 MetaMask 인수가 아니다.
- 최신 9문서88chunk 새 embedding6POST, golden12/12·MRR0.8819444444, identifier6/8·MRR0.6041666667을 실제 측정했다. negative8개는 기대 문서가 없는 질문이므로 rank0을 정답률0으로 해석하지 않는다. 실제 검색된 관련 chunk와 표적 Tool scalar는 있었으나 Q12/Q13 자연어 본문은 숫자를 facts로 넘겨 설명이 불충분했다.
- 실제 HTTP 기준 Q1~Q13: ANSWERED6/PARTIAL7. Q5는 견적 상태·만료를 묻는데 별도 체결 사실 부재, Q6은 SIGNED시각 부재, Q7은 미래 reorg 절대보장 부재, Q12는 전체감사 범위 밖임을 uncertainties에 넣었다. Q9 과거 지연 원인 부재 등 필요한 유보도 있으므로 일괄 제거하지 않는다.
- Q14 첫 실패: Java frame timeout10000ms, ABORTED10002ms. Python HTTPS는 시작 후9734ms에 HTTP200/body완료했으나 앞선 broker 검증/예약 약0.28초를 포함한 전체는10016ms였다. 사용량은 원장에 OBSERVED로 기록됐으나 JVM이 닫혀 결과를 반영하지 못했다. 뒤의 KNOWLEDGE_APPROVAL_UNVERIFIED는 검증 pool 종료 이후 fallback 재검증 실패이며, corpus 변조나 DB index 삭제가 확인된 것은 아니다. 제품10초 기한은 변경하지 않았다.
- 원장: LLM20/embedding44 모두 OBSERVED, unknown/RESERVED0, 누적 보수 예약 $0.823191320(input232683/output20000/embed74191 bound). 실제 usage 비용은 별도 합산한다. STOP·원장·응답/진단을 보존했고 해당 환경 3개 컨테이너는 소유권 검사 후 정상 정지했다. Q14 자동 재시도 없음, HTTP Q15/Q16 및 추가8문항 미실행.

### 후속 비교의 범위와 한계

전체 HTTP Gate PASS로 승격하지 않는다. 미확정 과금이 전혀 없는 닫힌 신규 study에 한해 parent의 STOP/원장/질문·fixture 파일 hash를 그대로 고정하고, 보수 예약·호출·토큰을 전액 carry하는 별도 비교를 준비했다. 부모 파일 변경이나 unknown 사용량은 실행 전 차단하고, 두 후보는 단일 child 원장을 공유한다. 과거 사용자 AI DB가 아니라 이번에 새로 만든 9문서 AI DB만 소유권 확인 후 재개한다. 거래 PG/Anvil은 재시작하지 않는다.

- 비교 1: 질문에 필요한 미확인 사실과 일반적인 검증 범위 한계를 분리하는 synthesis 지시만 보완한다. 한계는 본문에 유지하고 Receipt부재/원인부재/INCONCLUSIVE 같은 핵심 불확실성은 남긴다.
- 비교 2: 비교1에 사건 당시 수치와 손익·equity·maintenance 관계를 본문에도 짧게 연결하는 지시를 추가한다. 서버 scalar를 그대로 사용하며 계산/권한/원장/응답 검증은 그대로다.
- Q5/6/7/9/10/11/12/13의 고정된 이번 실제 HTTP 입력을 사용해 원인별 생성 품질을 대조한다. 실패한 Q14는 다시 호출하지 않는다. 이는 새 HTTP/신선한 RPC 인수가 아닌 **captured-input synthesis 비교**다. 진단용20초 budget은 비교1/2에 동일하고 제품10초는 유지하며 각 응답이10초 안인지 따로 기록한다. 서로 다른 경로의 시간을 같은 SLA 성과로 합치지 않는다.
- 별도 H01~H06은 실제 RAG/LLM을 사용하되 상태 근거는 캡처/통제 음성임을 구분한다. H05/H06 통제 음성 입력은 실제 실패 Receipt가 아니다. H07/H08은 권한 검증 대신 모델에 맡기지 않는다. 원래 HTTP 미실행 항목은 그대로 NOT_RUN이다.
- 후속 경로 무과금 Python20 PASS 및 Java test compile PASS. 새 독립 검토 전 추가 paid POST를 실행하지 않는다. 현재 제품 프롬프트는 미수정이며 비교 결과로 채택 여부를 정한다.

## 최신 인수 재개 체크포인트 — 2026-10-30 / $5 자율 평가 승인, 실제 실행 준비 중

사용자는 $5/LLM96/embedding200 및 기존 토큰 상한을 승인했고, 기준 성능 측정 후 중간 선택 대기 없이 최대 두 개선안을 비교·선택·수정·회귀하도록 위임했다. 아래 승인 대기 문구는 당시 기록이다. 새 정책은 명시적 `LATEST_POLICY` opt-in만 가능하고 기존 $2 원장/정책은 변경하지 않는다. 아직 신규 paid POST는 0회다.

### 실행 전 추가 보완

- 새 `LatestAiAcceptanceTest/LatestAiConfiguration`은 test classpath에서만 존재한다. 제품 native provider는 OFF이며 새 예산 stdio broker만 사용한다. 실제 HTTP/JWT USER/타인/ADMIN, 10 Tool, 새 실제 spot FILLED 및 legacy Long 60초 청산 근거를 준비한 뒤 최신 9문서를 새 pgvector DB에 색인한다. 서명/TTL/권한/금융 코드는 변경하지 않는다.
- HTTP 유료 endpoint는 시험에서 등록한 actor+exact body 1회만 통과하는 별도 test-only filter로 제한한다. 새 source/corpus/fixture/actor/target 파일을 원장에 묶고, 모든 baseline/experiment1/experiment2가 같은 누적 원장을 쓴다. synthesis 지시만 후보별 suffix로 비교하며 planner/schema/근거 검증은 유지한다. 후보명은 두 개로 제한하고 최초 내용 hash를 바꿀 수 없다.
- 기존 Foundry compiler donor 이미지가 정리되어 있었으므로 과거 태그를 재생성하지 않고, Linux CI에 고정된 Foundry/solc0.8.24/OpenZeppelin/forge-std 소스로 별도 tools 이미지를 빌드했다. 기존 이미지/DB/Anvil은 재사용하지 않았다. Docker 엔진 기동 후 기존 컨테이너 실행 0개, 데이터 mount 내부 사용 11.5GiB가 관측됐다. 원본 손상 Anvil 복구 성공을 뜻하지 않는다.
- 기존 16문항+검색 세트는 유지한다. 별도 H05/H06은 실제 해당 실패 상태가 없는 경우 NOT_RUN으로 남기며 다른 결과를 꾸며 대체하지 않는다. 현재 하네스는 이를 명시적으로 미실행 기록하도록 구성했다. 실제 결과는 실행 후 별도 기록한다.
- 새 Python 예산/프로필/identity 순수 13개 PASS, 기존 broker15개 PASS, Java 관련 46개 PASS 및 전체 test compile 성공. 실제 HTTP와 provider 실행 검증은 아직 NOT_RUN이다. 독립 변경 검토를 진행한 후에만 paid 실행한다.

- 개인 원격main과melon-init은모두 `1bc1c738b16baf095fcc2a539a3b33f8bc92c39d`다. 로컬main ref는194c38b에남아있어기준으로사용하지않았다. 이원격SHA에서별도 `feat/ai-latest-quality-20261030` worktree를만들었고원본dirty/기존23worktree를수정하지않았다. commit/merge/push없음.
- 아래Phase8의8/8은완전답변8개가아니라기대한안전동작8개다. ANSWERED2/PARTIAL4/안전거부2이고state/receipt는H2/fixture였다. 정상경로assertion은ANSWERED/PARTIAL둘다허용하므로분포는당시출력관측이다.
- 10월19일보다최근의실행도존재한다. [10월20일재실행](../../claude-docs/ai-real-model-evaluation-rerun-2026-10-20.md)은실제9문서88chunk와golden12/12/MRR0.881944/identifier6/8/Q1을검증했다. 직접문자열근거11/12와수동의미대조12/12는다르다. [Q2~16](../../claude-docs/ai-q2-16-real-quality-evaluation-2026-10-20.md)은Q2~4 planning/Tool전달후Q5RAG실패,Q6RESERVED중단이었다. [후속Q12](../../claude-docs/ai-q12-postreboot-real-evaluation-2026-10-21.md)는실제RAG/LLM실행과정확한선택사실을확인했지만수치설명누락/보존snapshot한계로품질PARTIAL이었다.
- [웹Stage1](../../claude-docs/ai-web-stage1-live-integration-2026-10-21.md)의실제JWT/HTTP/신규PG·Anvil/Tool은무과금index EMPTY·paid deny구성이었다. 서로다른실행을합쳐freshHTTP→Tool→최신RAG→실제LLM전체PASS로선언하지않는다.
- 현재10Tool/4Skill/승인지식9개는확인했으나선물Tool은legacy DB담보FuturesEngine용이다. C MarginVault잔고조회나B-v2전체감사가아니다. `FuturesAuditSnapshot`은authorized positions가있는경우legacy coverage예외를낸다. 미지원모델을DB모의잔고로대체하거나이번범위에서새C Tool을구현하지않는다.

### 실행 계획과 비용 승인안

1. 기존16문항과golden12/negative8/identifier8을유지하고 `latest-acceptance-supplement.json`의별도8문항(충분4/불확실2/권한거부2)을평가한다. 별도8문항은설정선택후마지막검증용이며통계적독립성을보장하는세트라고주장하지않는다. 10Tool각각의실제HTTP인가조회와4Skill을명시적으로포괄한다.
2. 신선한격리거래PG/Anvil에서검증된기존하네스로모의자산·legacy선물증거를생성하고,별도AI DB/pgvector에현재9문서를새로embedding한다. 원본손상Anvil/사용자DB/과거AI DB·STOP·claim·ledger는사용하거나수정하지않는다. Docker저장오류이력이있으므로새실환경쓰기전엔진/저장건전성·자원조건을확인한다. RAM1920MiB/디스크2GiB기준은유지한다.
3. 실제JWT USER/다른USER/ADMIN/미인증요청을통해동일서버의Tool/RAG/Agent를연결한다. `AgentService.answer()`직접호출과HTTP인수를구분한다. 사용자MetaMask서명은없고신규시험체인의기존하네스거래만허용한다. 자동AI진단/외부시장/사용자금융작업은비활성으로유지한다.
4. 질문별chunk ID/본문hash·순위/similarity/Tool결과/실제답변·인용/판정/지연/usage를구분저장한다. 원문은개인정보없는시험질문·정제근거만private artifact에보존하고키/JWT/raw/서명/개인자료는제외한다. 정확성/충분성/근거/불확실성각0~2와원인분류를수동·독립대조하며ANSWERED비율만최적화하지않는다.
5. baseline후원인에따라최대2개실험을선택한다. 검색문제는기존semantic/identifier Hybrid·K/threshold/청크설정중한변수만,생성누락은근거전달/프롬프트중한변수만비교한다. 측정전문제를가정해전부구현하지않는다. 기존개발세트와마지막별도표현세트를분리하고회귀를함께기록한다.

과거US$2/LLM32/embedding80승인은옛run/source/lineage한정이므로잔여액을새허가로사용하지않는다. 이번신규평가전체 **US$5상한(세금·환율별도),gpt-5.6-terra최대96POST/text-embedding-3-small최대200POST**,누적LLM입력1,200,000/출력153,600/embedding400,000토큰을제안했다. 기존표준전송·store=false/reasoning none/자동retry0,불확실예약무환급을유지한다. 메타데이터GET은최대2회별도다. 새원장/정책활성화는아직하지않았다. source가달라지는실험에도하나의누적예산을승계해초기화로한도를늘리지않도록실행전검증한다. 사전직렬화bound가맞지않으면범위를조용히축소하거나초과하지않고보고한다.

공식 [모델](https://developers.openai.com/api/docs/models/gpt-5.6-terra)의input$2/M·output$12/M에cache-write여유를포함한input$2.50/M, [embedding](https://developers.openai.com/api/docs/models/text-embedding-3-small)의$0.02/M으로보수예약하면최대$4.8512다. $5는별도중단상한이며계정전체청구서·다른클라이언트·세금은제어하지않는다. 실제usage와보수예약을분리한다.

### 이번 무과금 검증과 상태

- 기존Python회귀137개:최초135PASS/2FAIL. root에서실행한자식 `python -c`가deployment모듈을찾지못함을실제ModuleNotFoundError로재현했다. 두테스트의자식cwd만명시해동일137개PASS/0SKIP/0FAIL. 권한/비용/timeout/assertion은변경하지않았다.
- Java21.0.10에서표적Mock/순수AI회귀46개PASS/0FAIL/0SKIP와전체compileTestJava는성공했다(XML6클래스직접합산). 추가8문항의분류/근거문서존재/거부경로모델0조건을무과금구조검사했다. 실제모델/embedding/새PG·Anvil/HTTP인수/품질비교는아직NOT_RUN이며승인후수행한다.
- sourceHash `9f909681c4db47e64a7fb9cd6f8e2d944ea38b53690300556869494e4843a231`,corpusHash `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`. 이는현재가드의정의범위해시이며추가평가세트까지모두포괄하는해시라고주장하지않는다. 실행identity에는추가세트/fixture/정책hash도별도로고정해야한다.

독립변경검토 `review_ai_acceptance_baseline`: 발견된필수수정없음. tracked4개와신규평가JSON을직접확인했고두표적Python13+8=21개직접PASS/diff-checkPASS/Decimal예산$4.8512재계산PASS. 기존Java XML46개를합산했지만Java를재실행하지않았다. 검토자는새PG/HTTP/모델/비밀자료에접근하지않았다. 유료guard확장·실행harness배선·실환경·답변비교는여전히미완료다.

이하본문은과거Phase8기록이며이번최신인수성공으로재사용하지않는다.

> 2026-10-03 / Phase8 구현·평가·전체 검증·별도 검토 완료, 사용자 완료 승인 대기.

2026-10-19 후속 Futures 평가는 [별도 구현·실행 보고서](../../claude-docs/futures-ai-integration.md)에 기록한다. 수정 후 신규 실제 PG/Anvil의 canonical60초 청산→Tool/Skill/Mock Agent1 PASS, USER/ADMIN 및 교차Tool·손익 projection 보완 단위18 PASS다. 실제 LLM/새 corpus embedding·검색 평가는 NOT_RUN이다. 아래 Phase8 실제 공급자/검색 수치는 당시 corpus·모델의 과거 기록이며 신규 Futures 평가로 재사용하지 않는다.

## 평가 범위

고정 golden12·무관8·식별자8과 별도8분류 manifest (`backend/src/test/resources/ai/final-evaluation-manifest.json`)를 구분한다. 기존 단위/통합을 재사용하고 질문·문서·Tool 문자열 주입, recursive secret canary, 관측 동시성/권한/장애, fingerprint/TTL/legacy marker 테스트를 추가했다. 모델/검색 설정을 바꾸지 않았다.

## 확인한 결과

- 전체 backend333 중321통과/12skip/실패·오류0. 실제 AI/거래 PostgreSQL/Anvil read-only opt-in 포함. 신규9개 결정적 테스트가 실행됐고 신규 paid Java1은 skip되어 이후 별도 실행한다.
- 최종 관측 보완과 manifest assertion 포함 전체334 중322통과/12skip/실패·오류0. 신규 결정적10개가 실행됐다. paid KnowledgeRefresh/Phase8Live Java2개는 별도 opt-in으로 통과했다. 다른 조건부 skip10개는 이번 전체/유료 명령으로 실행하지 않은 항목으로 남긴다.
- forge36통과, 웹34통과, Vite build88modules. 기존 거래·컨트랙트·웹 기능 변경 없음.
- eventKey는 기존 SHA-25664자리이며 raw composite/txHash가 아니다. terminal TTL/legacy backfill·비만료 보존·purge 후 중복 재예약0을 실제 PostgreSQL에서 검증했다.
- 첫 secret/provider 표적 테스트는 usage null의 자동 unboxing이 원래 오류를 덮는 결함을 잡았다. 원래 오류 보존/관측 best-effort를 수정했고 동일 assertion으로 표적/전체 회귀가 통과했다.
- 실제 공급자 Java2개 opt-in 통과: 고정 검색/답변 회귀와 대표8개 요청. 모델/embedding/pgvector/AI 이력은 실제, state/receipt는 격리 H2 fixture다.

## 실제 공급자 결과 (2026-10-03)

golden hash는 그대로다. hit12/12·MRR0.8819444444444443·직접근거12/12·K회귀0·무관 후보5/8·무관 오답0/8·critical ANSWERED2/2·식별자6/8을 유지했다. USER scoped3/3도 통과했다. 지식9개57청크/기존 indexVersion을 유지하고 서비스 지식·manifest·색인은 변경하지 않았다.

| 대표 경로 | 결과 | 근거/경계 |
|---|---|---|
| USER KNOWLEDGE | ANSWERED | 승인 USER 정책 근거 |
| USER 시장 STATE | ANSWERED | Tool 사실, RAG/종합 모델 없음 |
| USER 주문 MIXED | PARTIAL | PENDING_ONCHAIN/NOT_LINKED를 체결로 추정하지 않음 |
| signed-quote-diagnosis | PARTIAL | 현재 만료 관측과 과거 원인 구분 |
| market-availability-diagnosis | PARTIAL | SIMULATED/UNKNOWN으로 실거래 가능 여부 확정 안 함 |
| 자동 settlement-debugging | PARTIAL / COMPLETED | REVIEW_REQUIRED/NOT_FOUND·정책·trace/이력, 중복 재호출0 |
| USER 질문 주입 | UNSUPPORTED | ADMIN 전환/이상 주문 Tool 실행 없음 |
| synthetic RAG 주입 | INSUFFICIENT_EVIDENCE | 지시뿐인 문서로 정책/실행 근거를 만들지 않음 |

대표8/8 기대 결과, 정상6개(ANSWERED2/PARTIAL4)·안전 거부2개, 의존성 실패0. 모든3개 Skill을 포함한다. Agent7개 시간 표본(거부1 포함)의 중앙값6,130ms·nearest-rank p95=7,726ms, 최소1,358/최대7,726ms다. provider 직접 synthetic 주입1은 Agent 지연 표본에 포함하지 않는다. 작은 fixture 표본이며 운영 SLA가 아니다.

실제 HTTP model17회 = 대표10 + 검색회귀7. 보고된 chat input24,920/output3,171 tokens, unknown usage0. embedding8회/input170 tokens/unknown0. 검색회귀에서는 기존 text/model/차원 hash query vector cache를 재사용해 embedding HTTP0이었다. 새 색인/모델 교체는 없다. retrieval/Tool/LLM time은 겹치는 내부 시간이라 전체 시간에 단순 합산하지 않는다.

초기 비용 계획의20개는 golden/negative 질문 수였으며 실제 재현은 기존 식별자8개와 별도 답변 검증을 재사용했다: 검색28회+답변10회, USER 거부 검색1회, 추가 LIVE_DATA_REQUIRED1회는 보고 시점 이후 assertion이다. 유료 model17회는 계획36회 상한 이하다. 지연/사용량은 이번 두 평가만의 수치이며 개발 에이전트·이전 Phase 비용·향후 추가 검증 비용 총계가 아니다.

대표 답변을 rubric으로 직접 대조했다: 기록된 상태/단위와 모순 없음, 승인 정책 인용, 현재 견적 만료를 과거 장애 원인으로 단정하지 않음, SIMULATED를 실제 삼성전자 시세로 설명하지 않음, 구체 REVIEW_REQUIRED 원인·receipt/확인 수를 모르면 유보, 주입2건의 mutation/권한 상승 없음. 이것은 유한한8개 출력에 대한 자체 대조이며 무한한 질문이나 자연어 진실성 보증은 아니다. 독립 검토에는 동일 raw 출력을 전달한다.

마지막 live 실행 후 관측 label에 UNSUPPORTED/ANSWERED와 PARTIAL 의존성 실패 원인을 명시하고, queue snapshot을 단일 SQL로 보완했다. 정책/프롬프트/검색/답변 경로는 변경하지 않았고 추가 유료 반복은 하지 않는다. 최종 전체 회귀로 관측 보완을 검증한다.

## baseline과 해석 기준

Phase2 hit10/12 → 채택 K/Phase7 hit12/12·MRR0.8819444444. 직접근거12/12·무관 오답0/8·critical ANSWERED2/2·식별자6/8·scoped3/3을 유지하는지 평가한다. 유료 대표8개는 KNOWLEDGE/STATE/MIXED,3개 Skill/자동 이력, USER 주입 거부, synthetic RAG 주입을 포함한다. state/receipt는 격리 H2 fixture이며 운영 Toss 장애/실사용자 거래 평가가 아니다.

권한·호출 예산·출처와 사실 pointer·거래 불변은 결정적으로 검증한다. 별도 rubric으로 Tool 모순/없는 정책/관측 vs 규칙/불확실성/권한·mutation을 수동 대조한다. LLM judge를 단독 통과 기준으로 쓰지 않으며 reference 검증은 자연어 전체 진실성을 증명하지 않는다.

## 재현과 미검증

[RUNBOOK](RUNBOOK.md)의 전체/opt-in 명령을 따른다. ignored `backend/build/reports/ai/phase8-*`는 raw 결과, 공유 Phase8 JSON/상세 문서는 추적용 요약이다. 전체 XML은 이후 표적 실행으로 대체될 수 있어 실행별 수치를 구분한다. skip을 pass로 세지 않는다.

운영 Toss/실사용자 장애, 실제 자동 진단 MATCH, 프로세스 kill/다중 서버 장기 chaos, 브라우저 수동 인수, 자연어 완전 진실성/장기 SLA는 미검증이다. [Phase7 제한](phase-7-event-driven-diagnosis.md)을 유지한다.

## 별도 검토

review_ai_phase8: 발견된 필수 수정 없음. 제품/테스트·최종6문서/career brief와 raw2개를 독립 대조했다. 직접 격리 테스트34개 통과·실패/오류/skip0, diff 검사 통과. 전체334개·공유 PostgreSQL/Anvil·유료 공급자·forge·웹은 구현자 실행 결과이며 검토자는 재실행하지 않았다. 최신 XML은 검토자 표적34개 결과다. 검토 후 제품·테스트 변경 없이 검토/완료 기록만 추가했다.
