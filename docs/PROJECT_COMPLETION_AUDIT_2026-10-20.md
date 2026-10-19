# 프로젝트 최종 통합·잔여 범위 대조 — 2026-10-20

## 기준과 판정 원칙

원본 루트의 사용자 문서 `docs/PROJECT_COMPLETION_MASTER_PLAN_2026-10-18.md`의 **76개 ID를 모두 유지**한 보충 대장이다. 원본은 미추적 사용자 파일이므로 이동·덮어쓰기·임의 커밋하지 않는다. 해당 원본의 P00~P15 출처 및 Q00~Q07 결정 대장은 계속 유효하다. 이 문서는 원본을 대신해 과거 기록을 수정하는 것이 아니다.

조사 시작 원본: `feat/futures-engine@216ede45729907c2cc17de4d5a66fd1b39565cd9`, 원본 기준 브랜치 `melon-init@9a9197b290c57ce3351452fcf7f0794e6f24cdfc`. 별도 `integration/futures-final-20261020@f70c1eb5eb21e2bb848c132734402747a6182189`에서 정상 merge했다. merge tree와 Futures 부모 tree는 동일하다. 원본의 NXT/계획 사용자 변경 5경로는 보존한다.

아래 **구현**은 코드 존재, **증거**는 실제 실행 범위다. 이전 실행과 이번 실행을 혼합하지 않는다. `완료(로컬)`은 선택된 구현·로컬 검증 범위이며 최종 실환경 인수 완료가 아니다. 전체 완료율을 단일 백분율로 표시하지 않는다. 현재 새 통합 소스 검증은 진행 중이며 결과는 별도 실행 보고서에 연결한다.

이후 별도통합 검증/검토를 마치고 원본 `melon-init@0d36ebab`에 정상merge했다. 사용자5경로는 SHA-256 동일, 보존환경/기존인수manifest는 유지한다. 병합후 새 원본회귀는 진행 중이며 상세 실행별 결과·SKIP·이전실패는 [10-20 통합보고](../claude-docs/futures-final-integration-2026-10-20.md)에 누적한다. 실제 AI·KRX·PhaseD 미완료 판정은 바뀌지 않는다.

병합후 새 결과: backend648중593PASS/6FAIL/49SKIP.6FAIL은AI 로컬HTTP fixture의 Windows native listen 준비 오류이며 최신전체회귀PASS로 처리하지 않는다. 이후 Docker image API500/Server timeout으로 원본의실제Chrome·두JVM은NOT_RUN/BLOCKED. 별도통합의599PASS와실제E2E는당시증거로유효하지만 원본재실행결과가아니다. Git정상병합 완료와최종검증완료는구분하며 환경복구후재개가필요하다.

그 후 제품/테스트코드변경없이 새로 실행한 원본 전체제어회귀는648중599PASS/49SKIP/0FAIL, 웹127/Foundry52/Node8·offlineChrome/전체Solidity빌드PASS였다. 앞의6FAIL은지우지않는다. 원본실제PG/Anvil/Chrome 재검증과실제AI·최신KRX·최종D는여전히미완료이며 환경/비용승인이선행한다. 전체완료율로합산하지않는다.

주요 증거: `claude-docs/google-sell-all-integration-2026-10-18.md`, `futures-final-integration-2026-10-19.md`, `futures-ai-integration.md`, `futures-product-browser-and-restart.md`, `futures-audit-implementation.md`. 10-19의 backend594 PASS/49 SKIP·web120·Foundry52 및 실제 fixture 결과는 **이전 실행**이다.

## 1. 원본 ID별 대조

### 거래·원장 (T01~T15)

| ID | 현재 판정 | 코드·검증 근거와 남은 일 |
|---|---|---|
| T01 | 완료(로컬), 실환경 부분 | `contracts/src` ERC-20/PriceOracle/ExchangeVault, Foundry·Anvil. 최신 KRX 거래 인수 남음 |
| T02 | 완료(로컬), 실환경 부분 | `blockchain/oracle`, `quote`; EIP-712/quoteId/귀속/일회소비. KRX quote/event 대조 남음 |
| T03 | 완료(로컬), 실환경 부분 | `order` BUY=mKRW/SELL=mSEC, 잠금·실제 Synthetic 거래. KRX BUY/SELL NOT_RUN |
| T04 | 완료(로컬), 운영 부분 | `blockchain/transaction` raw 선커밋·같은 nonce/hash 복구. 지속 장애·실장중 crash 운영 인수는 별도 |
| T05 | 완료(로컬), 실환경 부분 | `blockchain/reconciliation`, `settlement`; canonical Receipt/Event·단일 정산. KRX 연계 남음 |
| T06 | 완료(로컬) | `blockchain/observation`; LONG_WAIT300초·최근 snapshot. 실패 확정·잠금 해제 기능 아님 |
| T07 | 완료(기존 범위), 보증 제외 | `contract/ContractGateway`, `reserve/RpcReserveChainReader`; 자산·ETH 참고. 미래 Gas/유동성 보증·자동충전 없음 |
| T08 | 완료(개발 모드) | `OrderService`; DB-only. 온체인 성공 또는 새 키움 정책 적용으로 간주하지 않음 |
| T09 | 완료(로컬) | `wallet`, `portfolio`; DB 잔고/locked/평가. 사용자별 ERC-20 소유권 아님 |
| T10 | 완료(로컬), 실환경 부분 | `reserve`; 불변 baseline·stable cut·Faucet/현물 및 선물 실현손익 반영. KRX 최종 MATCH 남음 |
| T11 | 완료(로컬), 실환경 부분 | `tradeaudit`; 양방향·고아Quote·coverage 분리. 현물 감사가 선물 감사까지 대신하지 않음 |
| T12 | 완료(로컬) | `wallet/faucet`; 100만/일1/누적5/총5000만, DB 시각/멱등·경합. 온체인 mint와 별개 |
| T13 | 부분/별도 승인 | 기존 유잔고 migration·과거 지급 provenance 적용 미실행. 초기화/추정 금지 |
| T14 | 완료(로컬), 실환경 부분 | `conditional`; 목표가격 의도→새 quote→Order. 시간 예약/호가 매칭 아님; 실 KRX 남음 |
| T15 | 완료(로컬), 실환경 부분 | `Portfolio.tokenAvailableAmount`, 웹 `sell-all.js`; 18자리 문자열·잠금 제외·실제 Anvil 잔여0. KRX 전량 남음 |

### 인증 (U01~U12)

| ID | 현재 판정 | 코드·검증 근거와 남은 일 |
|---|---|---|
| U01 | 완료(기존 범위) | `auth`, 내부 userId JWT·USER/ADMIN. 신규 계정0원 |
| U02 | 완료(웹 범위) | `auth/google`; GIS/서명·issuer/audience/nonce, 사용자 직접 실인증 수용. native Android 제외 |
| U03 | 완료(승인 범위), 세부 증거 부분 | JWT+로컬pw+LINK+검증 sub, 자동 이메일 병합 없음. 실제 개별 challenge 소비/정확409 매핑 미검증 유지 |
| U04 | 완료(서버 expiry) | `WebSocketExpiryGuard`; 이미 연결된 개인 STOMP 만료 차단 테스트. 즉시 token 폐기는 아님 |
| U05 | 미완료/후속 | 새로고침 복원·Refresh 없음; 메모리 access JWT 소실은 알려진 현재 계약 |
| U06 | 미완료/후속 | Refresh rotation·재사용 탐지 없음 |
| U07 | 부분/후속 | 클라이언트 logout만, 서버 폐기·전체 세션 종료 없음 |
| U08 | 부분/후속 | JWT expiry만, 기기별 세션/원격 강제종료 없음 |
| U09 | 미완료/후속 | email 컬럼 존재≠서비스 이메일 발송·검증 구현 |
| U10 | 미완료/후속 | 비밀번호 재설정·Google-only 로컬 수단 설정 없음 |
| U11 | 미완료/후속 | 계정 복구 정책/서비스 없음 |
| U12 | 미완료/후속 | Google 연결 해제·마지막 로그인수단 보호 없음 |

Google 완료를 U05~U12 완료로 바꾸지 않는다. 기존 이메일/reset→refresh/session→안전한 해제 권장 순서를 임의 변경하지 않는다. 이번 최종 통합 작업에 새 인증 기능을 끼워 넣지 않는다.

### 시장·웹 (M01~M11)

| ID | 현재 판정 | 코드·검증 근거와 남은 일 |
|---|---|---|
| M01 | 구현/부분 인수 | Toss REST/WS/캔들 유지. 과거 일부 실제 연결≠최신 전체 장중 인수 |
| M02 | 구현/실환경 부분 | Kiwoom KRX provider, 실제 읽기 capture 성공. Spring Boot 실제 거래 E2E NOT_RUN |
| M03 | 구현/실환경 부분 | `TradingPriceContext`, 현재 generation 정규0B(290=2)/LIVE/fresh; UNKNOWN 허용 가능·CLOSED 우선. 0s 필수 아님 |
| M04 | 구현/실환경 부분 | Kiwoom candle/asOf·6주기. capture native1m 근거와 PRE_ASOF_MISSING 한계를 유지; 제품 실차트 남음 |
| M05 | 진단 완료(관측 범위) | NXT main742/after733 capture는 당시 보고 근거. 9081 코드·세션 해석 미확정; 이번 재호출 없음 |
| M06 | 미완료/착수 결정 대기 | NXT 고정 거래소 A 방향만 승인, 제품 provider/거래/차트 미구현 |
| M07 | 완료(로컬) | Synthetic seed/Clock/checkpoint/commit 후 공개. 실제 제품 Chrome/Anvil 선물 데모 있음; 실주가 복제 아님 |
| M08 | 범위 비채택/후속 | 과거 실데이터 replay 없음. 공개 Synthetic 선택을 유지 |
| M09 | 완료(기존 범위) | 공개·개인 STOMP/REST 재동기화. 영속 이벤트 로그·exactly-once 전달 보장 아님 |
| M10 | 완료(로컬), 인수 부분 | 제품형 웹/desktop-mobile fixture 및 실제 선물 Chrome. 모든 ADMIN 화면/기기 인수 완료 아님 |
| M11 | 미완료/범위 결정 대기 | 사용자 가격편차 UI 없음; 서버 minimumOutput 검증과 구별 |

### AI (AI01~AI12)

| ID | 현재 판정 | 코드·검증 근거와 남은 일 |
|---|---|---|
| AI01 | 구현/오프라인 갱신 완료 | 지식9문서 역할 유지·8문서 최신화·manifest9/9 및 loader10PASS. 실제 index/답변 평가와 별개 |
| AI02 | 완료(기존 로컬), 최신 인수 부분 | 별도 pgvector/Boot adapter; 새 격리 index·실제 provider 재인수 남음 |
| AI03 | 완료(기존 평가), 최신 평가 부분 | K semantic threshold.25/문서당2/Top5; 과거 golden12/12·MRR.881944. 새 corpus 수치 아님 |
| AI04 | 구현·무과금 검증 완료 | 기존8+선물2=10 read-only Tools. 신규 체인 Tool 실제PG/Anvil+Mock 모델 검증 |
| AI05 | 구현·무과금 검증 완료 | 제한 KNOWLEDGE/STATE/MIXED Agent, 사전 소유권·근거 검증. 최신 실제 모델 결합 남음 |
| AI06 | 구현·무과금 검증 완료 | 기존3+선물1=4 고정 Skills. pending/교차cut 불일치 PARTIAL, 실제 모델 신규 평가 남음 |
| AI07 | 구현, 활성화·최신 인수 부분 | spot REVIEW_REQUIRED polling 기본OFF. 정상 MATCH 자동 trigger 또는 선물 자동 진단 trigger 아님 |
| AI08 | 완료(승인된 유한 검증) | injection/secret/TTL/fingerprint/최소 관측. 모든 공격·환각 방지 보증 아님 |
| AI09 | 구현/인수 부분 | USER 진단·ADMIN 이력/선물 target UI. 최신 실제 모델 history/detail 브라우저 남음 |
| AI10 | 부분(오프라인 갱신 완료) | 옛0s/UNKNOWN 정책·Google/전량·Tool10/Skill4 사실 교정 및 manifest/loader 검증 완료. 새 색인·retrieval·유료 모델 평가 미실행 |
| AI11 | 미완료(최신 실제 모델 인수) | 실제 선물 체인·DB+Mock 모델 PASS는 있음. 실제 LLM 및 KRX 연계 답변은 NOT_RUN |
| AI12 | 일부 비교/비채택 | Hybrid/RRF 비교만, 기본K 유지. reranker/장기 기억/멀티턴 추가하지 않음 |

### 원제안·확장 (X01~X16)

| ID | 현재 판정 | 코드·검증 근거와 남은 일 |
|---|---|---|
| X01 | 확정 유지 | A 운영자 통합 지갑+DB 모의자산 장부. 선물도 DB 담보 정본 |
| X02 | 명시 범위 제외 | 사용자 지갑/직접 서명/CustodyVault 없음 |
| X03 | 명시 범위 제외 | 사용자 호가 매칭/부분 체결 없음 |
| X04 | 첫 범위 제외/후속 결정 | 시간 지정 예약 없음; 가격 조건부와 구별 |
| X05 | 미완료/범위 결정 대기 | 분산 nonce 없음. 단일 실행자 전제; 다중 서버 도입 전 필요 |
| X06 | 미완료/범위 결정 대기 | Merkle liability/PoR 없음; 대사 MATCH로 대신하지 않음 |
| X07 | 설계 방향만 | Sepolia 최초 고정 공급 variant A, S0/O0/V0·구현 승인 미정 |
| X08 | 미완료/별도 승인 | Sepolia 배포/RPC/ETH 없음. Anvil 성공과 구분 |
| X09 | 신규 구현·로컬 검증 완료 | Long/Short 격리 담보·전체 종료/추가담보. **온체인 담보 예치·만기·부분 종료는 아님** |
| X10 | 신규 구현·로컬 검증 완료 | 2/5/10배, 현재 명목가치5% 유지증거금·정수 산식. 교차/펀딩 없음 |
| X11 | 신규 구현·로컬 검증 완료 | worker→계약 위험 검증→canonical call→실제60초 유예→새 가격 청산·복구·감사. batch 청산은 없음 |
| X12 | 미완료/학교 범위 확인 | Oracle N/임계값·청산/가스 연구와 논문은 제품 테스트로 대신하지 않음 |
| X13 | 현재 삼성 단일 종목 | 원제안 TSLA/다종목·증권 상환권 구현 아님; 학교 변경 승인 확인 필요 |
| X14 | 변경된 구현 유지 | 가입0원+정책 Faucet·Pull Oracle. 원제안 자동 airdrop/주기 push와 다름 |
| X15 | 비채택/학교 범위 확인 | Kafka/Redis/전체 event-sourcing/CB/DLQ 없음. journal·raw 복구는 전문 분산 event store 아님 |
| X16 | 명시 범위 제외 | 실제 원화·주식·배당·상환권·증권사 주문 없음 |

### 운영·전달 (O01~O10)

| ID | 현재 판정 | 코드·검증 근거와 남은 일 |
|---|---|---|
| O01 | 로컬 준비/최신 인수 부분 | production Compose/Nginx/artifact/runtime secret. 최신 Futures 이미지 운영 재인수·외부배포 없음 |
| O02 | 기존 검증 완료/최신 부분 | readiness·restore3/3·smoke16/16은 과거 schema. 최신 Futures DB+chain 일관 복원은 미실행 |
| O03 | 과거 측정/확장 후속 | 저부하 자원 스냅샷≠최신 부하 용량 보증 |
| O04 | 사용자 보류 | 1→3→5/AI/RPC/DB/backend/Anvil 운영 부하·장애 계획은 자동 재개하지 않음 |
| O05 | 사용자 보류/비용 승인 | EC2 Linux 최소 실험 미실행. AWS 생성 없음 |
| O06 | 초기 클라우드 범위 제외/선택 | 공인TLS/DNS/SSM/S3 등 실제 운영 미실행; 수동 runtime secret 최소안 유지 |
| O07 | scaffold/별도 담당 | Android build·기기·native Google 인수 없음. 웹 완료로 닫지 않음 |
| O08 | 부분/제출 범위 결정 | 최신 발표용 가이드 있음; 최종 영상/팀 인수/블로그/논문 완료 확인 불가 |
| O09 | 선택 후속 | 외부 CI/Grafana 없음. 로컬 harness/내부 관측과 구별 |
| O10 | 미완료 | 최종 Phase D는 최신 A/B/C와 승인 범위·잔여 예외 종합 후에만 판정 |

## 2. 신규 Futures 묶음과 책임

F01=X09 포지션·DB 담보, F02=X10 산식/유한 house pool, F03=X11 Worker·Solidity 위험/60초 검증, F04=raw/Receipt/journal 복구, F05=선물 전용 감사/coverage 및 기존 reserve 손익 연계, F06=REST/개인 WS/실제 Chrome, F07=read-only Tool2/Skill1/RAG, F08=실제 모델 평가. F01~F07은 구현·이전 실제 무과금 증거가 있고 새 통합 회귀 대상으로 유지한다. F08은 비용 승인 전 NOT_RUN이다.

최종 대조에서 원 Futures 계획 F14의 ADMIN 감사 최소 화면이 API 구현 후 누락된 것을 발견했다. 기존 API만 연결하는 ADMIN 기준점 조회·수동 감사·이력/상세 패널로 보완했으며 웹127PASS·새 실제Chrome/PG/Anvil `futures-browser-bf973f2e`1PASS·별도검토 필수수정 없음을 확인했다. [범위와 실행 증거](../claude-docs/futures-admin-audit-ui-2026-10-20.md). 기존 현물 감사와 별도 범위이고 baseline 재설정·자산 보정·온체인 담보 예치 주장은 없다.

담보는 PostgreSQL에 잠기며 FuturesEngine은 포지션·서명·위험·유예·상태 전이를 검증한다. 온체인 MarginVault 예치가 아니다. 이익 지급 재원은 신규 fixture 초기10,000,000mKRW 유한 pool이며 지급 부족을 성공 처리하거나 mint하지 않는다. 사용자 손실 담보 한도·bad debt 별도 기록, 수수료/펀딩0, 사용자/종목당 활성1개 정책은 유지한다.

## 3. 다음 실행 순서와 승인 경계

1. **통합 A:** 별도 통합 소스에서 전체 회귀, 새 PG/Anvil·실제 Chrome·두 JVM·현물/조건부/인증 호환 증거. 실패는 보존한다. 필수 결함 해소·독립 검토·보안 점검 후 원본에 안전한 일반 merge.
2. **AI 내용 정합화:** 기존9문서 역할 유지,8문서와manifest를 갱신했고 정규화 해시9/9 및 loader10PASS·독립 재검토를 확인했다. 실제 검색 품질과 다른 검증이다. 기존 AI DB/index는 변경하지 않는다.
3. **독립 C:** 사용자 비용 승인 후 신규 AI DB/namespace에서 재색인, 기존+선물 16질문 및 권한/불확실성 평가. 요청·토큰·비용 한도와 실패 중단. 실거래는 Synthetic/Anvil 증거이며 KRX 연계는 아님.
4. **B:** 최신 통합 HEAD용 신규 KRX 환경·신규 불변 baseline/이미지·watchdog/egress·공인IP/WS 충돌 확인과 별도 외부 호출 승인. 정상 현재 generation0B·LIVE/fresh/allowed로 quote→BUY/SELL→WS/두 대사. 0s/OPEN 표기만을 새로 필수화하지 않는다.
5. **B 연계 C → D:** 실제 KRX 거래를 AI가 조회해 설명한 증거와 독립 C를 구분하고 최종 선택 필수 범위를 판정. 모든 미완료 확장을 자동 필수·자동 제외로 바꾸지 않는다.

## 4. 졸업 완료 판단에 남는 결정

- Q00 학교 최소 기준·논문/연구/Android/TSLA 변경 승인: Futures 추가로 선물 핵심 일부를 충족했지만 학교 승인 자체는 확인 불가.
- Q01 U05~U12는 명시 후속. 현재 완료선으로 승격할지 사용자가 결정하며 이번 작업에서는 구현하지 않는다.
- Q03~Q05 NXT 제품·Toss 전체 인수·분산 nonce/PoR/Sepolia·운영/EC2·제출물 범위는 기존 결정을 유지한다.
- 실제 모델 비용/호출 상한 및 최신 KRX 환경 외부 실행은 별도 승인. 이전 run 승인을 신규 환경에 자동 이전하지 않는다.
- Windows native socket 오류는 단일worker뿐 아니라 병합후Java21/격리worker에서도 재발했다. 실패클래스 단독7PASS와로컬listener20PASS만으로 영구해결을주장하지않는다. 전체제어재실행과실제E2E환경정상화를구분한다.

소요 추정(실행 결과 아님): 무과금 통합 검증 수십 분~수 시간, 실제 모델 배치30~90분+승인, KRX 새 환경/장중 창 수 시간+시장·승인 대기, 최종 증거 정리 반일. U05~U12·운영·공개망·논문을 이 추정에 포함했다고 주장하지 않는다.
