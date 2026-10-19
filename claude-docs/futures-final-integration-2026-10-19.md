# Futures + read-only AI 무과금 통합 결과 — 2026-10-19

## 판정과 Git

승인된 선물 기능·실제 제품 Synthetic/Chrome·온체인 검증/복구·독립 감사와 AI read-only 연결의 로컬 구현/무과금 검증을 완료했다. **실제 LLM/유료 embedding/index·KRX/NXT 인수·전체 프로젝트 PhaseD 완료가 아니다.**

기능 HEAD: `e8ba5cd28b1450d13f6233680a95ed98709f51f8`, branch `feat/futures-engine`. 이 보고서의 후속 문서 커밋은 금융/AI 제품 소스를 바꾸지 않는다. 원본 `melon-init`은 `9a9197b290c57ce3351452fcf7f0794e6f24cdfc`로 보존했으며 merge/push/deploy하지 않았다.

| 로컬 커밋 | 단위 |
|---|---|
| 92ab261 | Gate7 UI·확정 거절 marker·실제 HTTP/STOMP/두 JVM 검증22경로, 이번 시작 때 완료 확인 |
| f5c5a28 | Scenario helper 분리로 기존 전체 forge build 스택 실패 복구, 로컬EVM dryrun |
| ded9b79 | 선물 canonical 양방향 감사·DB 수탁 원장/house/journal 대사 |
| 266465e | 실제 제품 Synthetic/Chrome·성공 CONFIRMED 두 JVM 및 실패/소유 프로세스 보존 검증 |
| e8ba5cd | 선물 Tool2/Skill1/RAG·웹 대상 연결·안전한 전수 worker 격리, 명시38경로 |

검증 기준 productHash: `172e90d1892a6038591eaf2b9a949ea122cf9b796e6c6e8da333aa1905fde4b3`. 기존 harness상 backend/src/main·계약 source/지정 배포 도구의 hash이며 모든 웹/테스트/문서의 통합 hash는 아니다. 웹은 동일 작업 트리의 실제 Vite/Chrome 실행과 Git 변경 범위로 연결했다. 증거 session의 sourceHead266465e와 당시 미커밋 AI 소스는 이후 e8ba5cd로 커밋됐으며 manifest를 덮어쓰지 않았다.

## 실제 기능과 책임

- Long/Short2/5/10, DB 격리증거금·추가담보·전체 종료, 유한10,000,000mKRW 초기 모의 house pool. 손실 담보 한도·악성 부채 별도, 수수료/펀딩0. 온체인 담보 예치 구현이 아니다.
- 실제 가격 commit 이후 서버 Worker가 위험을 평가하고 서명 transaction을 보낸다. FuturesEngine이 권한/가격 서명/버전/재사용/위험/실제60초 chain 유예를 검증한다. Worker가 필요하며 계약·AI가 스스로 청산하지 않는다.
- signed raw 선커밋→RPC→canonical Receipt/Event→DB 단일 journal 정산/AFTER_COMMIT 개인 WS. 불확실한 제출에 새 nonce/서명을 만들거나 시간만으로 잠금을 풀지 않는다.
- 독립 Futures audit는 DB원장/lock/house/operation/journal/chain position/canonical 거래·이벤트/역방향coverage를 확인한다. MATCH/MISMATCH/INCONCLUSIVE와 coverage를 분리한다. 기존 현물 baseline을 변경하지 않는다.
- AI는 본인 또는 ADMIN의 명시 포지션만 조회한다. 선택 CALL/LIQ 최대2건의 수학·Receipt/Event/journal 근거, snapshot 시점 일치, terminal 손익 projection을 확인한다. 불확실한 근거는 PARTIAL이며 모델 종합으로 확정하지 않는다. 기존8Tool/3Skill은 유지하고2Tool/1Skill만 추가했다.

## 최신 실행 증거

| 구분 | 새 실제 실행 | 결과 / 범위 |
|---|---|---|
| backend 전수 | e6f7cc06/backend-full-isolated-workers | 643중 **594 PASS/49 SKIP/0FAIL/0ERROR**, 같은 전체 선택,20클래스 단위 worker |
| 웹 단위 | 같은 session 새 회귀 | **120 PASS/0SKIP/0FAIL** |
| Chrome offline | 같은 session 새 회귀 | desktop/mobile fixture PASS, 실제 체인 검증과 별도 |
| Solidity | 같은 session Foundry | **52 PASS/0SKIP/0FAIL**. 전체 forge build --offline도 exit0, 기존 lint 경고는 남음 |
| harness | 수정 후 Python / Node | Python20 PASS, Node 브라우저 검증기8 PASS |
| 실제 제품 Chrome/PG/Anvil | 4ac049a3/futures-browser-655a2024 | **1 PASS/0SKIP/0FAIL**,8단계·6포지션·개인WS38 |
| 실제 별도 두 JVM | 4ac049a3/futures-confirmed-46c6222d | prepare1 PASS + recover1 PASS, 같은raw/hash/nonce로 실제 CONFIRMED 단일 정산 |
| AI 실제 원장/체인 + Mock 모델/검색 | 4ac049a3/futures-ai-0841d476 | **1 PASS/0SKIP/0FAIL**, 실제60초 청산·인가/정규증거·원장불변·Skill/Agent2Tool |
| 현물 실제 PG/Anvil | e6f7cc06/sellall-8fbaf77a | **1 PASS/0SKIP/0FAIL**, BUY/부분SELL/전량SELL3FILLED, 정확잔여0·잠금0·기존두대사MATCH/complete |

전체 backend의 환경 gate49 SKIP을 PASS에 포함하지 않는다. 단위/Mock과 각 실제 PG/Anvil·Chrome 결과를 중복 합산하지 않는다. Google 실제 계정 인증·조건부 별도 실환경·KRX/NXT를 이번에 새로 수행했다고 표현하지 않는다. 기존 기능의 단위/보안 회귀는 전수에 포함하고 실제 현물은 위 새 fixture 범위만 인정한다.

최신 제품 Chrome의 양방향 청산:

- Long call1792411670→LIQ1792411732, **62초**. tx `0xfa47fc2aafc5c9ed5c9d9234113f6156d0a1609fb67b175baf0e450791c7a82f`.
- Short call1792411736→LIQ1792411798, **62초**. tx `0x2672d2a671154c0ef39b57cbc99e1d2076e01dcd7dac1108202deded0fddc178`.
- 실제31초 preview 만료, 유예 내 가격회복/추가담보, Long2/Short5 정상 종료, 해당 포지션/operation 개인 이벤트 및 DOM 숫자/desktop/mobile를 확인했다. Futures audit **MATCH**,19tx/38events 전부검증/coverage complete(block44~147), 기존reserve **MATCH**. 신규baseline 불변, 최종잠금0, wallet+house journal보존. own Node descendants42개 잔존0.

## 실패·검토·미검증 보존

- 단일 worker 전체 회귀 두 실행은 각각591PASS/49SKIP/3FAIL과593PASS/49SKIP/1FAIL이다. 서로 다른 loopback fixture 서버의 native `Bad address: bind/listen`이며 제품/외부 공급자 실행 전이다. 실패 XML/로그를 보존했다. 원인의 영구 해결을 주장하지 않는다.
- 선택적 Gradle init(maxParallelForks1/forkEvery20)으로 테스트·assertion·gate를 제외/완화하지 않고 전수 통과했다. 기본 회귀 경로는 그대로다. 한 번의 worker 격리 성공이 Windows 환경 안정성을 보장하지 않는다.
- 감사 검토의 terminalR/D projection 누락, 브라우저 검토의 DOM/개별WS 근거 및 owned-process cleanup, AI 검토의 terminalprojection/교차Tool 시점혼합을 실제 수정·재검증했다. 각 단위 최종 독립 검토에 남은 필수 수정은 없다. 검토자 소스 검토와 구현자 실제 실행은 구분한다.
- 과거 브라우저 실패6개, CONFIRMED 테스트 최초 compile 타입 오류, Gate7 REVERTED 복구와 정확한 revert 원인 미확정, ENOSPC/단일 이미지 정리의 인과 미확정 이력은 별도 문서·private 증거에 보존한다.
- 실제 LLM/새 corpus embedding·검색/AI 브라우저 공급자 데모 **NOT_RUN**. 기존9문서 구조와 manifest는 오프라인 갱신했지만 실제 index 재색인하지 않았다. 기존 active index와 새 corpus가 다르면 fail-closed하므로 모델 데모에는 별도 비용/재색인 승인이 필요하다. 선물 질문5개는 모델최대10회·검색최대5회 후보, 정확 비용은 실제 공급자 요금·token 확인 전 미확정이다.
- DB 수탁 담보/유한 pool, 단일 서버 nonce 신뢰, 가격 재확인 UX와 미완료 기존 마스터 항목을 축소하거나 완료로 처리하지 않는다. 사용자별 지갑·직접 서명·주문장은 이번에 도입하지 않았다.

## 보안·환경 및 다음 단계

기존 KRX/Google/playground/NXT와 그 DB/Anvil/images/baseline/manifest/인증정보·이전 실패 세션을 삭제·초기화·이동하지 않았다. 새fixture 컨테이너만 소유권 검증 후 정지하고 DB/state/정제증거를 보존했다. sourceHead/hash/manifest를 새 값으로 덮어써 통과시키지 않았다.

AI staged38경로 검사: 보호 사용자경로0, private/runtime/빌드 금지경로0, 신규생성 비밀값18개와 실제diff대조0일치, diff check PASS. 사용자 변경5개(README/implementation-log의NXT, NXT관측문서, 두계획문서)는 그대로 남겼다. .env/JWT/개인키/raw/capture/로그/DB/Anvil state는 커밋하지 않았다.

현재 source는 원본 루트의 `feat/futures-engine`에 있다. 원본 melon-init 통합·최신 KRX용 신규 인수 환경·실제 모델 비용/재색인·최종 PhaseD는 이 로컬 성공과 별개로 결정한다. 구 KRX 이미지는 새 Futures/AI 버전을 포함한 것으로 간주하지 않는다. [발표·재현 순서](futures-final-demo-guide.md), [AI 상세](futures-ai-integration.md), [감사](futures-audit-implementation.md), [제품 Chrome/재시작](futures-product-browser-and-restart.md), [누적 로그](futures-implementation-log.md).
