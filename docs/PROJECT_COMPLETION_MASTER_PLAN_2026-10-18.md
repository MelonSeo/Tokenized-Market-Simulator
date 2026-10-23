# Tokenized Market Simulator — 최종 개발·인수 마스터 계획

> 기준일: 2026-10-18 KST / 상태: **사용자 범위·순서 검토 대기**. 이 문서는 구현 승인이 아니다.
> 현재 원본 브랜치 `melon-init`, HEAD `9a9197b290c57ce3351452fcf7f0794e6f24cdfc`. 제품 검증 HEAD는 `426cb0b`이며 이후 커밋은 문서만 변경했다.
> 이번 작업은 저장소·Git·기존 실행 보고서의 읽기 조사와 문서 작성이다. 테스트·서버·Docker·DB/RPC·외부 API·유료 AI 실행, 커밋·병합·배포는 하지 않았다. 기존 NXT 미커밋 보고서와 implementation-log 구간은 보존한다.

## 1. 전체 개발 범위와 기존 계획 출처

### 1.1 가장 먼저 구분할 세 가지 완료 범위

1. **현재 합의한 제품:** 삼성전자 기준 모의 mKRW/mSEC, 운영자 통합 지갑 + PostgreSQL 사용자 장부, 서명 견적/Vault 정산, 웹 기준 클라이언트와 읽기 전용 AI. 실제 주식·원화·상환권을 제공하지 않는다.
2. **최신 버전의 최종 인수:** Phase A의 격리 회귀, 실제 KRX Phase B, 실제 AI Phase C, 증거 종합 Phase D다. 코드 구현이나 예전 실험 통과만으로 이 네 단계를 완료 처리하지 않는다.
3. **학교에 낸 최초 제안의 완료:** 원본 HWP는 **레버리지 선물·자동 청산을 최소 성공 기준**으로 제시했다. 현재 제품에서 제외/후속으로 바꾼 사용자 결정이 학교의 평가 기준 변경 승인까지 뜻하지 않는다. **Q00 확인 전 ‘졸업프로젝트 원제안 전체 완료’를 선언할 수 없다.**

전체 완료율을 단일 백분율로 계산하지 않는다. 이하 표의 구현·검증·통합·범위 네 축을 각각 관리한다.

### 1.2 출처 목록과 적용 순서

아래 P/E 식별자는 표에서 반복 사용하는 근거다. 과거 문서의 ‘미구현/다음 단계’는 작성 당시의 기록이며 현재 상태와 충돌하면 코드·최근 실행 증거와 함께 읽는다. 이 문서는 **전체 상태와 남은 결정의 색인**, 원본 문서들은 상세 계약·실행 증거다.

| ID | 출처 / 확인 위치 | 이번 복원에서의 역할 |
|---|---|---|
| P00 | [최초 HWP 제안서](<../졸업프로젝트 제안서_김성은, 서교빈.hwp>) §1.4~1.5, §2.1~2.4, §3.1, §4.1 | 이번에 OLE/압축 paragraph text를 읽기 전용 해독. 선물·청산·논문 등 원요구 복원. 원본·변환본 저장 없음, 연락처 등 개인 식별 정보는 옮기지 않음. 문서 레이아웃 검증은 아님 |
| P01 | [구현 계획](<../구현 계획.md>) §5, §8~18, §20~23 및 상단 후속 결정 | 현재 모의 토큰 MVP, Android 원안, replay/선물 확장 및 이후 제외 결정 |
| P02 | [추가 기능 통합 계획](../claude-docs/additional-features-integration-plan.md) §3~5 | 10-09 출발, 10-10 개발 순서, 10-11~12 Faucet/조건부/A 모델 결정 |
| P03 | [최종 인수 계획](../claude-docs/final-integration-acceptance-plan.md) §4~11 | 10-13 A~D, 정상/장애 환경 분리, 시나리오·증거·비용 승인 기준 |
| P04 | [경력 브리프](../claude-docs/career-project-brief.md) §7~8, §12~13 / [면접 학습 문서](../claude-docs/PROJECT_INTERVIEW_STUDY_MASTER.md) | 당시 범위와 후속 후보. 브리프는 10-04 본문+일부 후속 보강으로 **현재 기능 상태표가 아님** |
| P05 | [Google 설계](../claude-docs/google-oauth-implementation-design.md) §8~10, [구현](../claude-docs/google-auth-implementation.md), [실인증](../claude-docs/google-auth-live-acceptance-2026-10-17.md), [최신 수동 인수](../claude-docs/google-auth-finalization-and-integration.md) | GIS 범위 승인, email/refresh/recovery/unlink 후속, 실제 인증의 확인 범위 |
| P06 | [Google·전량 최종 통합](../claude-docs/google-sell-all-integration-2026-10-18.md), [전량 설계](../claude-docs/sell-all-precision-design.md)·[구현](../claude-docs/sell-all-precision-implementation.md) | 현재 HEAD의 통합과 새 회귀·격리 E2E |
| P07 | [자산 대사](../claude-docs/reserve-reconciliation-implementation.md)·[최종 검증](../claude-docs/reserve-reconciliation-final-validation.md), [전수 감사](../claude-docs/trade-full-audit-implementation.md), [Faucet](../claude-docs/faucet-policy-implementation.md), [조건부](../claude-docs/oracle-conditional-orders-implementation.md), [정체 관측](../claude-docs/transaction-stall-observability-implementation.md) | 각 기능의 승인 계약·실행 이력 |
| P08 | [서명 거래 설계](../claude-docs/oracle-price-execution-design.md), [보관 모델 비교](../claude-docs/user-wallet-custody-architecture-review.md), [A 모델 점검](../claude-docs/asset-custody-final-check.md), [Sepolia 발행 설계](../claude-docs/sepolia-mockkrw-issuance-design.md) | operator/DB 정본·지갑 제외, 유동성과 대사 구분, 고정 공급 방향 |
| P09 | [키움 공급자](../claude-docs/kiwoom-market-provider-implementation.md), [전환 보고](../claude-docs/kiwoom-transition-final-report.md), [P0/P1](../claude-docs/kiwoom-live-p0-p1-fix.md), [최신 가용성](../claude-docs/kiwoom-mock-trading-availability-implementation.md) | Toss 유지·Kiwoom 추가, 최신 KRX 모의 견적 정책 |
| P10 | [첫 실관측](../claude-docs/kiwoom-live-readonly-acceptance-2026-10-14.md), [재관측](../claude-docs/kiwoom-live-recapture-2026-10-14.md), [KRX E2E 기록](../claude-docs/kiwoom-live-e2e-acceptance-2026-10-15.md) | 읽기 시세 관측과 미완료 제품 거래 E2E 구분 |
| P11 | [NXT 진단](../claude-docs/kiwoom-nxt-observe-diagnostics.md), [메인 관측](../claude-docs/kiwoom-nxt-live-observation-2026-10-16.md), [애프터 관측](../claude-docs/kiwoom-nxt-aftermarket-observation-2026-10-16.md) | 고정 모드 A 방향, 제품 미구현. 애프터 보고서는 사용자 미커밋 원본 그대로 참조 |
| P12 | [AI 원 master guide](../claude-docs/AI_AGENT_RAG_IMPLEMENTATION_MASTER_GUIDE.md), [Phase1](ai/phase-1-knowledge-report.md), [2](ai/phase-2-basic-rag.md), [3](ai/phase-3-retrieval.md), [4](ai/phase-4-read-only-tools.md), [4 후속](ai/phase-4-knowledge-refresh.md), [5](ai/phase-5-rag-tool-agent.md), [6](ai/phase-6-skills.md), [7](ai/phase-7-event-driven-diagnosis.md), [8](ai/phase-8-evaluation-security-observability.md), [최종 평가](ai/EVALUATION_REPORT.md) | AI 구현·승인별 변경, 실제 모델/embedding 평가와 fixture 한계 |
| P13 | [시세 이용권/replay 조사](../claude-docs/public-market-data-research.md), [Synthetic](../claude-docs/synthetic-market-design.md), [웹 제품화](../claude-docs/web-product-ui.md) | 공개 synthetic 결정, 실제 시세 비공개 인수와 분리 |
| P14 | [외부 배포 설계](../claude-docs/external-deployment-design.md), [배포 준비](../claude-docs/deployment-preparation.md), [용량·비용](../claude-docs/deployment-capacity-cost.md), [EC2 운영 계획](../claude-docs/ec2-operational-validation-plan.md), [복원 검증](../claude-docs/validation-2026-10-07.md), [RUNBOOK](../deployment/RUNBOOK.md) | 로컬 준비/복원 통과, 부하·실제 Linux/AWS 보류 및 최소화 결정 |
| P15 | [실제 Toss/Android 전달 인수](../claude-docs/phase-6-3-acceptance.md), [Android README](../android/README.md), [Faucet 계약](../claude-docs/faucet-android-integration.md), [조건부 계약](../claude-docs/conditional-orders-android-integration.md) | 웹 우선·Android 별도 담당, 전달 계약과 기기 인수 구분 |

Git 근거: `a98ea4b` 원본 통합 기준 → Google `bc02330` → 전량 `16b8988` → 문서 `c6a7b60` → 정상 merge `426cb0b` → 문서 전용 `9a9197b`. 그 이전 `b6edc72`는 키움 거래 가용성, `79ae93e`는 진단/NXT 관측 도구, `fe361db`는 기존 KRX 실행 harness, `600f13b`는 Synthetic 브라우저 결과다. **마지막 커밋 하나의 성공을 과거 전체 기능의 실인수로 확대하지 않는다.**

### 1.3 이번에 새로 복원한 원제안의 차이

P00의 프로젝트명은 ‘블록체인 모의 선물 거래 플랫폼’이다. TSLA 등 기초자산, 최대10배 레버리지, 유지증거금5%·마진콜→유예→강제청산→온체인 기록, Price-Time Priority·부분 체결·호가창, Android 지갑 직접 연동을 계획했다. `MarginVault.sol`·`FuturesEngine.sol`과 Oracle 변동성 트리거/급변 거부, Oracle 주기·임계값 대비 청산 정확도/악성 부채/가스 실험 및 논문1건도 포함한다. **실제 현재 계약4개와 같은 계약 이름/책임이 아니다.**

후속 P01은 선물/레버리지/청산을 MVP 이후로 두고, 10-12 사용자 결정은 지갑·호가 매칭을 제외했다. 이를 현재 제품 범위에는 적용하되, P00 §3.1의 ‘Phase3가 최소 성공’과 연구 산출물 요구가 승인 없이 없어졌다고 쓰지 않는다. 최초 예상기간2026-03-01~09-30도 과거 제안 일정이지 현재 마감으로 재확정된 날짜가 아니다. Q00은 팀/지도교수와 사용자가 확인할 항목이며 에이전트가 임의 승인할 수 없다.

## 2. 기능별 구현 및 검증 현황

### 2.1 상태·검증 표기

- **유지:** 현재 승인 제품/인수의 대상. **후속:** 명시적으로 다음 범위로 남김. **제외:** 현재 프로젝트 또는 이번 기능에서 명시 제외(행에 범위를 적음). **결정 대기:** 방향/과거 계획은 있으나 이번 완료선·일정 포함 승인 없음.
- 구현/통합의 `예/예`는 코드와 melon-init 반영이지 실인수 완료가 아니다. `없음/아니오`는 해당 제품 기능이 미구현이다. 없는 기능의 테스트는 NOT_RUN으로 표시한다.
- **U:** 관련 기본 단위/H2/Mock/로컬 HTTP·WS 테스트가 E01의 전체 회귀에 포함. 기능의 모든 장애/실환경을 검증했다는 뜻이 아니다.
- **G/M/S/C/F/R/A:** E02의 실제 새 PG/Anvil 각각 Google/legacy migration/전량/조건부·관측/Faucet/자산 대사/거래 감사. **J:** 별도 JVM 재시작. 표의 `G+R` 등은 합산 시험 수가 아니라 해당 증거에 걸쳐 확인된 범위다.
- **AI 과거:** P12의 당시 실제 모델/embedding/pgvector·DB/RPC 선택 검증. 이번 HEAD에서 재실행한 실인수가 아니다. AI 거래 state/receipt는 H2/fixture였던 부분을 구분한다.
- 예상은 **승인·설정 확보 후 1명 기준 적극 작업 시간의 계획 추정**이다. 외부 승인/장 대기/결함 수정·재회귀는 별도이며 보장 일정이 아니다. 완료 행의 시간은 재개발이 아니라 남은 인수/문서 작업 시간이다.

| 증거 | 확인된 실행 / 소스 | 한계 |
|---|---|---|
| E01 | P06, 제품 HEAD426cb0b: backend533중495PASS/38SKIP/FAIL0/ERROR0, web107PASS, Foundry36PASS, Chrome desktop/mobile fixture PASS, harness/clock15PASS | 이전 실행을 이번 조사에서 다시 실행한 것이 아님. 38SKIP은 PASS 아님. 9a9197b는 문서만 추가 |
| E02 | P06, 새 session `phase-a-20261017180337-f3d6764c`: G google-a051627d / M google-migration-d65b9cfc / S sellall-5cfd3b4b / C conditional-e42df94f / J 같은 C의 별도 JVM / F faucet-cf92a75a / R reserve-7f13ada3 / A audit-76283827: 각각1PASS, 총8PASS/0SKIP | 서로 다른7개 fixture. H2가 아닌 실제 PG/Anvil. migration은 legacy users 보존/기존 Faucet 미활성 등이며 운영 DB 전체 migration 완료는 아님 |
| E03 | P05 최신5174: Google 명시 연결·Google 재로그인·브라우저 me.id2·연결후 개인WS/Faucet, id3 중복 거부·id4 취소 사용자 확인 및 정제 DB/HTTP 근거 | 취소 challenge 개별 소비·409 정확 코드/요청자 매핑은 직접 미검증, 사용자 수용. 에이전트가 모든 브라우저 원문을 직접 캡처한 것이 아님 |
| E04 | [Synthetic 실제 Chrome](../claude-docs/synthetic-browser-e2e-2026-10-16.md): 실제 Chrome→Spring/PG/Anvil BUY/SELL2건FILLED, receipt2건, 개인WS, 포트폴리오, 두 대사 MATCH | 당시 이미지/소스. Google·전량 보완 후 최신 버전의 Chrome→실DB 거래를 다시 한 결과가 아님. ADMIN 대사는 REST 호출이지 ADMIN 화면 클릭 인수 아님 |
| E05 | P12 AI 최종 평가: hit12/12·MRR0.881944·직접근거12/12, critical ANSWERED2/2, USER scoped3/3, 무관 최종오답0/8, 식별자6/8, 대표8/8 기대 결과 | 소규모 개발 평가, state/receipt fixture. 현재 corpus/최신 실제 거래의 성능이나 모든 답변 정확성으로 일반화 금지 |
| E06 | P10/P11: KRX 읽기 실관측, NXT 메인742건·애프터733건. 실제 KRX 제품 거래는 미실행 | 진단 OAuth/WS/봉 성공과 제품 BUY/SELL 인수는 다름. 0s 미수신 원인 미확정, NXT9081 의미 미확인 |
| E07 | P14: 당시 local-smoke16/16, 신규 빈root restore3/3, Linux ops19/19·Windows19/2SKIP 등 | 당시 소스/로컬 Linux 컨테이너. 현재 전체 변경의 production 재인수·EC2·부하·공인 TLS 결과 아님 |

코드 표기 `B/`는 `backend/src/main/java/com/pricetrack/exchange/`, `T/`는 같은 패키지의 `backend/src/test/java/com/pricetrack/exchange/`, `W/`는 `tools/websocket-test-client/`다. 모든 경로는 현재 Git 추적 소스에 대조했다.

### 2.2 거래·원장·정산

| ID / 기능 | 원계획 / 현재 코드 근거 | 구현/통합 | 단위·격리 결과 | 실제 외부/브라우저 | 남은 세부 작업 | 분류 | 의존 / 예상 |
|---|---|---|---|---|---|---|---|
| T01 ERC-20·Oracle·Vault | P00/P01→P08; `contracts/src/{MockKRW,SamsungPriceTrackingToken,PriceOracle,ExchangeVault}.sol` | 예/예 | U/Foundry36, G/S/C | E04 Anvil, KRX NOT_RUN | 최신 KRX 연결, 공개망 별도 | 유지 | B 0.5~1h, 새 환경 C절 선행 |
| T02 EIP-712·quoteId 귀속/일회소비 | P01/P08; `B/blockchain/oracle/PriceReportIssuer.java`, `B/quote/PriceQuoteService.java` | 예/예 | U/G/S/C; DB lock·서명·만료 | E04, Google fixture; KRX NOT_RUN | 실제 시세→quote→event/Trade 대조 | 유지 | 시세/기동검증 후 B |
| T03 즉시 BUY/SELL·입력 잠금 | P01; `B/order/{OrderService,OnchainOrderService,OnchainOrderPreparationService}.java` | 예/예 | U/G/S/F/C | E04 PASS·최신 KRX NOT_RUN | BUY=mKRW금액/SELL=mSEC수량 계약 유지 | 유지 | T02/T04/T05; B |
| T04 nonce/raw 선저장·동일원문 복구 | P01/P03; `B/blockchain/transaction/{BlockchainTransactionSender,BlockchainTransactionPersistence}.java` | 예/예 | U의 recovery, C의 응답유실/동일raw | 실제 장중 crash-window NOT_RUN | 프로세스 kill/지속 RPC 장애 전체는 운영 검증과 구분 | 유지+후속 운영 | 단일 JVM 전제; 운영 0.5~1d 별도 |
| T05 receipt/event 검증·멱등 DB정산 | P01/P03; `B/blockchain/{reconciliation,settlement,contract}` | 예/예 | U/G/S/C/F; 실패와 REVIEW_REQUIRED 분리 | E04, KRX NOT_RUN | 정상 B receipt→Order/Trade/잠금→WS 연결 | 유지 | T02~04; B |
| T06 장기 SIGNED/SUBMITTED 관측 | P07; `B/blockchain/observation` | 예/예 | U/C/J; 299/300/301·snapshot | Chrome fixture; 실장중 운영화면 NOT_RUN | 300초 LONG_WAIT는 실패/자동 해제 아님; 실제 admin 표시 | 유지 | T04/05; B 조회 0.25h |
| T07 operator/Vault 유동성·ETH 참고 | P08; `B/blockchain/contract/ContractGateway.java`, `B/reserve/RpcReserveChainReader.java` | 예/예 | U/G/S/R; 부족 시 거절/실패 경계 | 미래 Gas 보증 NOT_APPLICABLE | 실제 ETH부족·오랜 미채굴의 모든 원인 판정 미검증; 자동충전 없음 | 유지/자동충전 미승인 | B funding 0.5h; 운영별도 |
| T08 DB-only 모의 거래 | P01; `B/order/OrderService.java` | 예/예 | U/H2 | 온체인/실환경 증거 아님 | 새 Kiwoom 가용 정책 자동 확대 금지 | 유지(개발용) | AI 장애 격리 fixture에 활용 |
| T09 DB 잔고·잠금·평균가/평가손익 | P01; `B/wallet/{UserBalance,WalletService}.java`, `B/portfolio/PortfolioService.java` | 예/예 | U/G/S/F/C | E03/E04 | 사용자별 온체인 소유권/원화 상환 보증으로 표현 금지 | 유지 | T03/05; B·C 사실 대조 |
| T10 장부-체인 자산 대사 | P02/P07; `B/reserve` | 예/예 | U/R 및 G/S/F; cut 경합 | E04 MATCH, 최신 KRX NOT_RUN | 새 immutable baseline·stable cut, UNKNOWN/DB_ONLY 구분 | 유지 | 초기 유동성 후 첫 지급 전 기준점, B |
| T11 거래 전수 감사·coverage | P02/P07; `B/tradeaudit` | 예/예 | U/A 및 G/S/F; 고아Quote·양방향·한도 | E04 MATCH/coverage, 최신 KRX NOT_RUN | 일부 MATCH≠전수 MATCH, 큰 실제 원장 성능 별도 | 유지 | T02~05; B |
| T12 DB Faucet 정책·멱등/관측 | P02/P07; `B/wallet/faucet`, `B/wallet/FaucetGrant.java` | 예/예 | U/F/G/S; PG 경쟁/rollback/KST | E03 1회 지급, E04 | 100만/일1회/누적5회/전역5천만 유지; journal 정본 | 유지 | 신규 빈 장부; B 1회 |
| T13 기존 유잔고 환경 migration/공급 | P02/P07/P08; `FaucetStore.initialize`, reserve baseline | 일부 정책/예 | F/M의 fail-closed 확인 | 기존 실DB 변환 NOT_RUN | 과거 지급 추정·quota reset·baseline 재설정 금지. 기존 데이터 적용안 별도 | 별도 승인 | provenance 필요; 1~3d+자료조사 |
| T14 Oracle 가격 조건부 BUY/SELL | P02/P07; `B/conditional` | 예/예 | U/C; 취소·실행·만료·새견적·복구 | Chrome fixture, 실 KRX NOT_RUN | 장기 quote 미저장, 실행준비 시 잠금, 실제 조건/새견적 재검사 인수 | 유지 | T02~05+현재 generation; B 별도 한도 승인 |
| T15 정확한 전량 SELL | P06; `Portfolio.tokenAvailableAmount`, `W/src/sell-all.js` | 예/예 | U/S; 18자리·잠금 제외·늦은응답, Chrome desktop/mobile | E03 화면과 별개; 최신 Chrome→실PG 전량은 NOT_RUN | REST 문자열만 입력, 자동주문 없음; B 전량 버튼/서버 정산 검증 | 유지 | T09/T03; B |

### 2.3 사용자 인증 확장 — Google 완료와 전체 완료를 분리

| ID / 기능 | 원계획 / 현재 코드 근거 | 구현/통합 | 단위·격리 결과 | 실제 외부/브라우저 | 남은 세부 작업 | 분류 | 의존 / 예상 |
|---|---|---|---|---|---|---|---|
| U01 자체 가입/로그인·JWT·USER/ADMIN | P01/P02; `B/auth/{AuthController,AuthService,JwtTokenProvider}.java`, `B/admin` | 예/예 | U/G/F 등 | E03/E04 | 신규 가입은0원·JWT sub=내부 id 유지 | 유지 | B 역할/사용자 격리 재확인 |
| U02 GIS 신규/재로그인·IDtoken 검증 | P02/P05; `B/auth/google` | 예/예 | U RSA/JWKS/nonce 등, G/M | E03 PASS; Google 계정 선택은 사용자 직접 | Android audience/native Google 미포함 | 유지 | web ClientID/origin, 재구현 불필요 |
| U03 명시적 Google 연결 | P02/P05; `GoogleAccountService`, `W/src/google-auth.js` | 예/예 | U/G; 경합·rollback·기존 Order/Trade 보존 | E03 PASS/사용자 취소·중복거부 수용 | 개별 challenge 소비·409 정확 매핑 직접 미검증 유지 | 유지 | JWT+로컬pw+검증IDtoken+LINK, 추가 0~0.5h 증거정리 |
| U04 JWT 만료·서버 개인 STOMP 차단 | P05; `B/websocket/config/WebSocketExpiryGuard.java` 등 | 예/예 | U 실제 embedded native/SockJS/outbound 검사 | 실제 expiry까지 기다린 Google 인수는 별도 미기록 | 만료 강제 차단≠발급 token 즉시 폐기 | 유지 | 신규 세션 설계 때 회귀 필요 |
| U05 새로고침 로그인 복원/Refresh 발급 | P02/P04/P05 §8; `W/src/api.js` 메모리token·TokenResponse accessToken만 | 없음/아니오 | 해당 기능 NOT_RUN | 새로고침 로그아웃 실제 관측 | HttpOnly 쿠키 등 저장/CSRF/수명/경로 설계 승인 필요 | **명시 후속, 이번 일정 결정 대기** | U06~08 함께; 12~24h(통합 묶음) |
| U06 Rotation·재사용 탐지 | P05 §8 | 없음/아니오 | NOT_RUN | NOT_RUN | family·원자적 소비·경합·유출 token 재사용 시 폐기 정책 | 후속/일정 결정 대기 | U05/07/08; 위 묶음에 포함 |
| U07 서버 폐기·로그아웃 API | P02/P05; 현재 `W/main.js.logout`은메모리삭제/WS종료 | 클라이언트만/예, 서버없음 | U 현재 logout·늦은응답; 서버폐기 NOT_RUN | 로컬로그아웃 PASS≠JWT무효화 | 단일/전체 세션 폐기와 Access JWT 잔여수명 처리 | 후속/일정 결정 대기 | U05~08; 위 묶음 |
| U08 기기별 세션·강제 WS종료 | P05 §8 | expiry만/예, 서버세션관리없음 | U04만 PASS | 다기기 원격로그아웃 NOT_RUN | 세션ID·강제종료·REST/WS 일관 인가·브라우저 탭 경합 | 후속/일정 결정 대기 | U05~07; 위 묶음 |
| U09 서비스 이메일 등록·발송 인증 | P02/P04/P05; `users.email/email_verified` 컬럼은 존재 | 검증기능없음/아니오 | NOT_RUN | NOT_RUN | 발송자/공급자·권한·비용·challenge·재발송·계정노출 방지 | **명시 후속, 일정 결정 대기** | 외부 발송 설정; U10포함10~20h+대기 |
| U10 비밀번호 재설정/Google-only 로컬수단 설정 | P05 §8 | 없음/아니오 | NOT_RUN | NOT_RUN | 검증메일 소유·일회 reset·rate limit·기존 세션 폐기 | 후속 | U09+U07; 위 묶음/정책에 따라 증가 |
| U11 계정 복구 | P05 §8 | 없음/아니오 | NOT_RUN | NOT_RUN | Google 접근 유실·이메일 접근 유실의 복구 증거/지원 정책 | 후속 | U09~10/U07; U12와8~16h+정책대기 |
| U12 Google 해제/로그인수단 교체 | P05 §4/8 | 없음/아니오 | NOT_RUN | NOT_RUN | 재인증·마지막 수단 제거 방지·기존 세션 철회. 이메일 자동병합은 계속 금지 | 후속 | U07/U09~11; 위 묶음 |

P05의 기존 권장 순서는 **Google+WS expiry → 이메일 검증/비밀번호 재설정 → refresh/rotation/revoke·세션 → 안전한 해제**다. 이번에 refresh를 앞당기는 새 우선순위를 확정하지 않는다. 새로고침 유지 요구로 순서를 바꾸려면 Q01에서 사용자 승인을 받는다. 메모리 JWT를 localStorage에 넣는 것만으로 Rotation/폐기/보안 요구를 완료 처리하지 않는다.

### 2.4 시장·차트·웹·실시간

| ID / 기능 | 원계획 / 현재 코드 근거 | 구현/통합 | 단위·격리 결과 | 실제 외부/브라우저 | 남은 세부 작업 | 분류 | 의존 / 예상 |
|---|---|---|---|---|---|---|---|
| M01 Toss provider 유지 | P01/P09; `B/market/provider/toss` | 예/예 | U REST/WS/캔들 Mock | 과거 REST/WS 일부 PASS, 최신 장중 전체 NOT_RUN | Kiwoom 추가가 Toss 삭제/실인수 완료를 뜻하지 않음 | 유지, 최종 주대상 전환 | Toss 전체 추가인수 포함 여부 Q03; 1시장창+준비 |
| M02 Kiwoom KRX REST/WS·재연결/token | P09; `B/market/provider/kiwoom` | 예/예 | U Kiwoom5클래스 | E06 진단 PASS, 제품서버 E2E NOT_RUN | 실제 서버 OAuth/WS/차트·거래 연결 | 유지 | 신규 B환경; C절 준비 4~8h 추정 |
| M03 Kiwoom 모의 견적 가용성 | P09; `TradingPriceContext`, `KiwoomPriceProvider.currentForTrading()`, `MarketPriceService.requireTradingContext()`, `PriceReportIssuer`, `ConditionalOrderService` | 예/예 | U/조건부 회귀·E02 | KRX 제품 거래 NOT_RUN | current generation 정상0B/290=2·LIVE·fresh, 명시CLOSED 우선, 발급전후 generation 일치 | 유지 | Q02 판정표현 확인, B |
| M04 KRX 분봉/asOf·6주기 차트 | P09/P10; `KiwoomCandleProvider`, `MarketCandleService`, `W/src/chart-controller.js` | 예/예 | U/웹·Chrome fixture | 저장capture의 native1m 시작시각/OHLCV 분석, 제품 차트 live NOT_RUN | PRE_ASOF_MISSING2와 수신시각차이1/0 구분; 혼합tick·재접속·페이지 실인수 | 유지 | B 기본1m, 6주기 별도조회 승인; 0.5~1h |
| M05 NXT 진단·메인/애프터 수신 | P11; `deployment/kiwoom-diagnostic.py`, `kiwoom_diagnostic_core.py` | 도구예/예; 애프터보고서미커밋 | 과거 진단68PASS, 이번 재실행아님 | 메인742/애프터733건, item005930_NX/290문자열2, 0s0 | 9081 존재STRING/허용형식false, 코드·세션 의미 미확인. 재연결로 성공 만들지 않음 | 관측완료 | 원본 capture/run 보존, 재개발 불필요 |
| M06 NXT 고정 거래소 모드 A | 사용자 A방향/P11 | **제품없음/아니오** | 제품테스트 NOT_RUN | 제품 Quote/거래/차트 NOT_RUN | WS/REST/캔들 같은거래소, KRX CLOSED와NXT 분리·UI; 공식 미확인 계약 해결 | 방향승인/착수·일정 결정 대기 | M02~04·추가 실조회 승인; 2~4d+외부대기 |
| M07 Synthetic 엔진/영속/공개표시 | P13; `B/market/provider/simulated`, public profile·W static banner | 예/예 | U/과거실PG통합, E02 Synthetic | E04 실제Chrome PASS, 공개배포아님 | seed/Clock·checkpoint·commit후공개 유지.30일bootstrap/장기catch-up 없음 | 축소승인범위 완료 | 공개모드 재검증은 배포 범위 |
| M08 실제 과거 데이터 replay | P01/P13 | 없음/아니오 | NOT_RUN | NOT_RUN | 원본시각/재생clock·권리·데이터버전·미래노출 방지 설계 | 현재 미채택 대안/후속 | 공개Synthetic 결정; 2~5d+이용권 |
| M09 공개/개인 STOMP·재접속 REST | P01/P15; `B/websocket`, `W/src/websocket.js` | 예/예 | U·native/SockJS, Chrome fixture | E03 개인WS/E04 거래이벤트 | 메시지 영속 로그·전역exactly-once는없음, 실제 B 이벤트 확인 | 유지 | U04/T05; B |
| M10 서비스형 웹·모바일 반응형 | P13/P15; W `index.html/src/main.js`와패널 | 예/예 | web107·Chrome desktop/mobile fixture | E03/E04 부분 실제흐름 | 최신 서버 ADMIN화면 전체 수동·실기기와 fixture 구분 | 유지 | B/C UI체크 0.5~1h |
| M11 사용자 지정 가격편차 UI | P04/P08; 현재최소수령량은서버산정 | 없음/아니오 | 해당 기능 NOT_RUN | NOT_RUN | 서버 minOutput 검증 완료와 사용자 tolerance 조절은별개 | 확장 후보/결정 대기 | quote/서명 계약 변경설계; 1~3d |

### 2.5 AI Phase 1~8와 최신 제품 통합

| ID / 기능 | 원계획 / 현재 코드 근거 | 구현/통합 | 단위·격리 결과 | 실제 외부/브라우저 | 남은 세부 작업 | 분류 | 의존 / 예상 |
|---|---|---|---|---|---|---|---|
| AI01 승인 지식9개·manifest/artifact | P12 Phase1/2; `docs/ai-knowledge`, `B/ai/knowledge` | 예/예 | U·과거문서/PG검증 | AI 과거9개 평가 | 최신성은AI10. 승인hash 일치≠내용최신 | 유지 | AI10 승인 후 index |
| AI02 Basic RAG·pgvector 분리 | P12 Phase2; `B/ai/{RagService,store,provider}` | 예/예 | U·AI 과거실pgvector/장애격리 | AI 과거실embedding/모델 | 새AI DB/현corpus index/외부모델 권한 인수 | 유지 | C독립 가능; 1~2h+색인승인 |
| AI03 Retrieval K·권한/domain | P12 Phase3/5/6; `EvidenceSelector`, `AuthorizedKnowledgeRetrieval` | 예/예 | U/AI 과거PG role-filter·golden | E05 10/12→12/12, MRR0.881944 | 최신corpus 동일golden회귀·식별자6/8 한계 | 유지 | AI10; C 0.5~1h |
| AI04 8 read-only Tools | P12 Phase4; `B/ai/tool/ToolRegistry`, `read/ToolReadFacade` | 예/예 | U·AI 과거PG/Anvil불변·timeout | 과거실RPC MATCH/NOT_FOUND 각각범위구분 | 최신 B 거래·사용자/권한·가격 수치 대조 | 유지 | AI DB/LLM 없이도 단독; C/B |
| AI05 제한 RAG+Tool Agent | P12 Phase5; `B/ai/agent` | 예/예 | U·AI 과거route/권한/예산 | E05 실제모델, statefixture | 최신실PG/실거래 근거 결합 NOT_RUN | 유지 | C 독립+ B연계 분리; 1~2h |
| AI06 3 Skills/trace | P12 Phase6; `B/ai/skill`, `resources/ai/skills` | 예/예 | U·AI 과거PG·role/domain | 3 Skill 실제모델평가, receiptfixture | 최신성·성공receipt MATCH·실시장 가용성 유보 | 유지 | AI04/05/10; C·B연계 |
| AI07 REVIEW_REQUIRED 자동 진단 | P12 Phase7/8; `B/ai/diagnosis` | 예/예, 기본OFF | U·AI 과거실PG claim/TTL+Anvil NOT_FOUND | 실제모델+H2/receiptfixture, 최신정상 B자동진단없음 | actor/schema/namespace·quota 활성 승인, 성공receipt+REVIEW_REQUIRED 별도fault시나리오 | 유지(활성별도승인) | C fault분리; 1~2h+유료승인 |
| AI08 injection/secret/TTL·최소관측 | P12 Phase8; `B/ai/observability`, `PgDiagnosisStore` | 예/예 | U·AI 과거TTL/비가역eventKey/공격case | E05 유한8개 기대결과 | 모든주입/자연어정확성·SLA 보증아님, 영속관측/Grafana없음 | 승인범위완료 | C 회귀; 운영확장 별도 |
| AI09 USER 진단·ADMIN 이력 UI | P12 Phase7/P13웹; `W/src/diagnosis.js`, main | 예/예 | U/Chrome fixture | 최신 실제모델 ADMIN history/detail 브라우저 NOT_RUN | USER/ADMIN·늦은응답·실제job 상세 확인 | 유지 | C 0.5h |
| AI10 최신 지식/manifest/index 갱신 | P03 §1.1/P06; 현재9문서10-13버전 | **일부낡음/예** | 옛corpus E05, 새corpus평가 NOT_RUN | 새유료색인 NOT_RUN | 0s필수/UNKNOWN거부 문구 충돌, Google/세션/전량 사실 반영·검토·hash·새index | 인수 전 필요한 정합화/실행승인대기 | 내용검토2~4h, 유료색인·평가별도 |
| AI11 최신 실거래와 실제모델 통합 | P03 PhaseC/사용자최종지시 | 코드경로예/예, 인수없음 | U/기존fixture만 | **NOT_RUN** | 성공receipt Skill·실가격Tool·포트폴리오·지정Order의Trade요약·ADMIN이력 일치(전체거래목록Tool없음) | 최종인수 대상 | C 독립부분은B불필요, 거래부분B 이후 |
| AI12 Hybrid/RRF·reranker·대화메모리 | P12 Phase3/5 | Hybrid비교test만/예; reranker/장기memory없음 | AI 과거K/L비교 | Hybrid식별자개선≠기본채택 | 기본K유지. 멀티턴/장기기억/별도검색서버·독립채팅UI 강제추가 안함 | 비채택/명시후속·선택 | 필요성근거+별도승인; 이번 추정/착수 제외 |

### 2.6 과거 확장·원제안·운영·인수 산출물

| ID / 기능 | 원계획 / 현재 코드 근거 | 구현/통합 | 단위·격리 결과 | 실제 외부/브라우저 | 남은 세부 작업 | 분류 | 의존 / 예상 |
|---|---|---|---|---|---|---|---|
| X01 수탁 A 모델 | P08/10-12결정; operator/Vault+DB | 예/예 | E01/E02 | E04 | 사용자ERC20소유·준비금보증으로표현금지 | 확정 유지 | 기존거래/대사 보호 |
| X02 사용자지갑/직접서명/CustodyVault | P00/P01/P02→P08 | 없음/아니오 | NOT_RUN | NOT_RUN | 주소필드/주소생성만으로소유권완료불가 | **현재프로젝트 제외**, 학술Q00별도 | 재도입시전체보관/정산/대사재설계, 주단위 |
| X03 주문장·사용자매칭·부분체결 | P00/P01→P08/조건부승인 | 없음/아니오 | NOT_RUN | NOT_RUN | Price-Time Priority도없음, 조건부≠호가주문 | **현재프로젝트 제외**, Q00 | 재도입시새거래엔진, 주단위 |
| X04 시간지정 예약 | P02→조건부승인 | 없음/아니오 | NOT_RUN | NOT_RUN | Oracle목표가격조건과구별 | 조건부 첫범위 제외/향후여부결정대기 | T14 이후별도; 1~3d |
| X05 분산 nonce | P02/P04; sender는`synchronized` | 없음/아니오 | 다중JVM sender E2E NOT_RUN | NOT_RUN | DB lease/fencing/승계·pending nonce, Redis필수전제없음 | **범위 결정 대기** | 다중sender 이전필수, 단일서버B의자동선행아님; 2~5d |
| X06 Merkle liability/PoR proof | P02/P04 | 없음/아니오 | NOT_RUN | NOT_RUN | 부채·locked/DB_ONLY·root/proof·공개범위/시간 정의 | **범위 결정 대기** | T10/11+정본결정; 3~7d, 공개anchor별도 |
| X07 Sepolia 고정 공급 mKRW | P08 고정공급A방향 | 설계만/문서예 | variant NOT_RUN | NOT_RUN | S0/O0/V0·키·배분승인, 최초이후mint없음. 현재MockKRW공개faucet유지 | 방향확정/수치·구현승인대기 | 신규contract/local회귀·새baseline; 1~3d |
| X08 Sepolia 배포/explorer | P01/P02/P04/P08 | 없음/아니오 | 로컬Foundry≠공개망 | NOT_RUN | chainId/domain/주소·ETH·RPC·confirm/reorg·공개데이터권리 | **범위 결정 대기** | X07+외부승인; 1~3d+ETH/접근대기 |
| X09 마진계좌·Long/Short 선물 | P00 §1~3/P01 §17 | 없음/아니오 | NOT_RUN | NOT_RUN | MarginVault/FuturesEngine·예치/회수/만기정산없음 | 제품MVP후속/학교기준Q00 | 계약·경제모델·보관충돌; 1~3주+설계 |
| X10 2~10x 레버리지·위험 계산 | P00/P01 | 없음/아니오 | NOT_RUN | NOT_RUN | 포트폴리오현물평가손익≠선물증거금/유지증거금/펀딩비 | 제품MVP후속/학교기준Q00 | X09; 추가1~2주추정 |
| X11 마진콜→유예→강제청산→온체인기록 | P00 최소성공/P01 후속 | 없음/아니오 | NOT_RUN | NOT_RUN | 스케줄러+tick dual trigger·batchLiquidate·악성부채 없음 | 제품MVP후속/학교기준Q00 | X09/10·Oracle변동위험; 추가1~3주 |
| X12 Oracle N/임계값·청산/가스 연구·논문 | P00 §1.4/1.5/3/4 | 미실험·논문미확인/아니오 | 해당실험 NOT_RUN | 논문제출 미확인 | 현재서명지연해결은청산연구실험대체아님 | **졸업산출물 범위 결정 대기 Q00** | X09~11 또는연구주제변경승인; 1~3주+자료 |
| X13 TSLA·다종목/원제안자산 | P00→P01 mSEC005930 | 삼성단일예/예, TSLA없음 | mSEC U/fixture | KRX/NXT는동일삼성·독립자산아님 | 종목변경의학교제안승인여부미확인 | 제품삼성확정/학술Q00 | 다종목재도입2~5d+데이터권리 |
| X14 가입 에어드롭·동적Oracle/급변reject | P00→P01/P08 | 가입0원·수동Faucet/서명보고서로대체 | E01/E02 해당현계약 | 현재흐름 E03/E04 | 과거‘가입시airdrop’·주기updatePrice를잔여필수로재구현금지 | 제품설계대체 승인/학술Q00 | 옛경제/위험엔진필요시별도설계 |
| X15 이벤트소싱·Kafka·Redis·CB/DLQ | P00 §1.3/2.4 | 일반인프라없음/아니오 | 개별DB lock/raw복구·WS테스트는존재 | 전문분산event-sourcing운영 NOT_RUN | journal·저장raw·AFTER_COMMIT≠전체상태재구성가능event store/DLQ | 현구조비채택/원제안달성여부Q00 | 다중엔진/신뢰성요구결정; 3~10d+구조변경 |
| X16 실제원화/실주식·배당·상환권 | P01/P08 | 없음/아니오 | 대상아님 | 대상아님 | 증권사실주문/계좌관리추가금지 | **명시 제외** | 금융서비스로확대금지 |
| O01 production Compose·Nginx·artifact·secret | P14; `deployment/compose.yml`, `ops.py`, Dockerfiles | 예/예 | 당시E07·helper | 외부배포 NOT_RUN | 최신Google origin/CSP/쿠키/노출API·이미지 재인수 | 로컬준비완료/배포후속 | 공개Synthetic, 실데이터배포권리별도; 1~2d |
| O02 backup/restore/readiness·보존 | P14; `ops.py`, `postgres-readiness.sh`, `restore-regression.py` | 예/예 | E07 restore3/3·smoke16/16 | 실제EC2복원 NOT_RUN | 최신schema/version backup복원은추가개발 후재검증 | 구현완료/운영후속 | stable DB+chain동일checkpoint; 0.5~1d |
| O03 로컬 capacity/호스트자원 측정 | P14; `resource-probe.py` | 저부하도구예/예 | 과거실측 | 장기서비스용량보증아님 | host/DBlock·latency전범위와분리 | 측정완료/확장후속 | 현재이미지측정다시하면별도승인 |
| O04 1→3→5 부하·AI/RPC/DB/backend/Anvil 장애 | P14 운영계획 §4~5 | 전용runner3종미구현 | 전체계획 NOT_RUN | EC2 NOT_RUN | `validation-runner/metrics/faults`는제안이었음. local-smoke를부하완료로쓰지않음 | **사용자보류** | 도구범위승인+새fixture; 2~4d+실험 |
| O05 EC2 Linux 최소실험 | P14 최신최소안 | AWS생성없음/아니오 | 로컬≠EC2 | NOT_RUN | EC2/EBS/SG/publicIPv4·SSH /32, 종료후차단. t3a.small후보/예산미확정 | 보류/생성별도승인 | O04결과·비용; 준비/실험1~3d+승인 |
| O06 공인TLS/DNS·SSM/S3 등 | P14 과거외부배포→최소실험 | 로컬TLS/SSM함수예/예,실제서비스없음 | 테스트CA/WSS/helper만PASS | 공인발급·AWS이용 NOT_RUN | 수동runtime file사용계획. RDS/S3/CMK/SecretsMgr/ECR/SSM필수아님 | 초기EC2범위제외/장기선택 | 확대승인·비용·도메인별도 |
| O07 Android 앱/기기/Google native | P00/P01/P15; `android/app/src/...` | scaffold만/예 | 현재build/기기인수 NOT_RUN | NOT_RUN | placeholder/TODO, REST·STOMP·Faucet/조건부/전량·Google계약전달/팀원연동 | **별도담당·최후순위**, 학교Q00 | 웹완료≠Android완료; 담당자견적필요 |
| O08 문서/API/도식·최종시연·영상·블로그 | P00/P01 §16/21/P04 | 문서다수예/예, 최종산출물일부미확인 | 문서/자동검증이력 | 최종실장중시연 NOT_RUN | 최신brief/학습자료 정정, 스크린샷·영상·팀인수·논문별도 | 제출범위 결정 대기 | D 이후정리0.5~2d, 논문X12별도 |
| O09 강제 CI/외부모니터링 | P00/P04/관측설계 | repo `.github`없음, 로컬harness/내부관측예 | 로컬자동회귀PASS | CI/Grafana운영 NOT_RUN | 로컬테스트≠CI필수gate·무한SLA. 외부Grafana이번제외 | 후속/도입결정대기 | 사용자승인; 1~3d |
| O10 최종통합인수 D | P03 | 계획만/문서예 | A증거존재 | B/C최신결합미완료 | 모든필수ID/예외·비용·보존·소스연결해최종판정 | 필수인수 | A/B/C·Q00~07; 0.5~1d |

## 3. 누락되거나 미구현된 작업과 계획 충돌

### 3.1 누락시키면 안 되는 잔여 묶음

- **인증:** U05~12. Google 완료는 로그인 공급자 확장의 일부다. Refresh/Rotation/재사용 탐지/폐기/서버 로그아웃/세션·메일/비밀번호 재설정/복구/unlink는 별도 상태를 유지한다.
- **기술 확장:** X05/06/07/08, M06. 순서에 등장했다는 이유만으로 이번 MVP 필수나 구현 승인으로 보지 않는다. 반대로 A 모델 채택을 이 항목들까지 제외하는 결정으로 확대하지 않는다.
- **원제안:** X09~15와 Android/논문. 제품 제외/후속 결정과 학교 제출 변경 승인 여부를 별도로 기록한다.
- **통합 검증:** AI10/11, 신규 B 환경/안전 도구, 실KRX BUY/SELL, 실제모델과최신원장 결합, 실제 ADMIN UI, D 판정. 옛 provider/옛 이미지의 성공을 최신 인수로 쓰지 않는다.
- **운영/제출:** O04/05는 사용자 보류다. 복원 성공·자원 스냅샷은 단계부하/chaos/EC2 성공이 아니다. 제출 영상·팀 인수·논문 확인을 ‘문서가 있으니 완료’로 덮지 않는다.

### 3.2 변경·충돌 대장

| 원래 계획 | 실제 변경/계기·현재 근거 | 승인 사실 / 남은 차이 |
|---|---|---|
| P00 TSLA선물·청산·논문 최소목표 | P01 삼성 모의토큰 MVP; 사용자현물형Vault 유지 | 제품방향은합의. 학교제안변경/최소성공기준승인은**확인 불가 Q00** |
| P01 MVP사용자지갑 / P02개발순서지갑 | P08 A수탁모델 확정·직접보관복잡도검토 | 10-12사용자명시제외. 재개발하지않음 |
| P00호가·부분체결 / P02지정가예약 | 조건부가격트리거로승인·구현,매칭/시간예약제외 | 범위를분리한명시승인. 조건부완료로호가/시간예약완료표기금지 |
| 주기updatePrice·계약실행시점가격 | 주문별PriceReport+signature,사용자는quoteId만제출 | P08의서명가격원자정산승인. 옛경로복구금지 |
| P02인증확장미구현·Google+email순서 | P05Google/명시연결/서버WS만료구현,나머지후속 | 사용자가후속범위승인. 이메일을조용히삭제하거나Google로전체닫지않음 |
| 과거브리프의대사/Faucet/조건부/Google미구현 | P06/07 및 Git에실제구현·통합 | 완료된기능의재설계금지; 브리프현황정정은후속문서작업 |
| P03 즉시DEGRADED허용/조건부LIVE차이 | 시장eligibility통일후키움전용TradingPriceContext로진화 | 사용자단계별승인. DB-only에새정책확대안함 |
| P03 Toss OPEN+LIVE 장중필수 | 실제인수주대상을Kiwoom KRX로전환;Toss유지 | 사용자키움전환승인. Toss최종완료또는Toss코드삭제아님 |
| 키움0s초기수신필수·0s시간5초 | 0s미수신관측후current-generation정규0B로모의견적허용,marketStatusUNKNOWN가능 | 사용자명시승인. 0B를OPEN확정으로표현금지. 이번‘OPEN+LIVE’문구와Q02대조 |
| P03지식9개가추가5기능누락 | 10-13이미대사/감사/Faucet/조건부/관측/Kiwoom초기정책반영 | 기존갱신을되풀이하지않음. 현재충돌은특히0s필수/UNKNOWN불가·Google/전량·세션설명 |
| AI 원 guide Hybrid/RRF/메모리기대 | 실제Ksemantic선택·Hybrid비교만,stateless단일turn로축소 | 사용자Phase3/5승인. 미채택기술을미완료필수로재분류금지 |
| ‘성공MATCH 자동진단’ 인수요청 | source는BUY/SELL REVIEW_REQUIRED만,CONFIRMED/MATCH자동trigger없음 | 기존인수계획에도구분. 성공거래Skill수동과성공receipt있는REVIEW_REQUIRED fault자동을분리 |
| 공개실제시장데이터 | 이용권조사후publicSynthetic확정,실provider로컬유지 | 사용자명시승인. replay/유료공급자계약은현재미채택 |
| AWS장기공개·다수managed services | 단일EC2기본EBS/SG/IPv4·수동secret·검증목적 | 사용자최소화·운영보류결정. 예산/클라우드생성미승인 |
| 기능통합worktree중심/원본영구분리 | 원본melon-init로일반merge;worktree/runtime보존 | 이후사용자통합승인. 앞으로 원본루트개발,기존manifest갱신우회금지 |
| Google5174/KRX기존이미지재사용 | Google/전량추가및CRLFcheckout으로sourceguard불일치;구KRX는옛소스 | 이미지/DB/state/manifest보존. 최신B새환경필요,옛검증이력유지 |
| 원래추가기능전부후최종인수 / 주말개발·월요일B | 실제남은인증전체/분산nonce/PoR/Sepolia/연구는수일~주단위 | 모든확장을월요일전에닫는일정은충돌. Q00~05로이번필수/후속을명시선택해야함 |

### 3.3 AI 최신성의 구체적 차이

`docs/ai-knowledge/market-data-policy.md`는 10-13 version3에서 ‘UNKNOWN은 신규실제서명에사용불가’와 ‘같은세대명시0s가필요’를 설명한다. 실제 `TradingPriceContext`/`KiwoomPriceProvider.currentForTrading()`/`MarketPriceService.requireTradingContext()`/`PriceReportIssuer.issue()`는 승인된 정상0B 기반 UNKNOWN 모의 견적을 허용한다. **현재 승인 corpus와 코드가 충돌한다.** 파일/manifest hash가 맞아도 사실 최신성은 증명되지 않는다.

현재 9개 지식 문서의 메타데이터는 모두10-13로, 대사/Faucet/조건부/관측을 이미 설명한다. 필요한 부분만 버전·근거·manifest를 검토해 갱신해야 한다. `ToolReadFacade.snapshot()`은 marketStatus/priceStatus/provider/시각을 전달하지만 **mockQuoteAllowed/generation을 전달하지 않는다.** 현재 Tool만으로 키움 견적 가능성을 단정하면 안 된다. 문서/답변 유보로 충분한지, Tool의 최소 additive 근거 확장이 필요한지는 Q06에서 설계 판단하며 이번에는 수정하지 않는다.

## 4. 범위 확정이 필요한 사용자 결정

| 결정 ID | 확인할 결정 | 현재 안전한 해석 / 선택의 일정 영향 |
|---|---|---|
| Q00 | 학교의최소성공기준·제출물은현재모의현물/Vault+AI로변경됐는가? 논문/선물/청산·Android요건은누가승인했는가? | 제품MVP합의를학교승인으로대체하지않음. 미변경이면월요일B는**현재제품인수**일뿐졸업제안전체완료아님. 연구/파생엔진재포함은주단위재계획 |
| Q01 | U05~12중이번주말필수는무엇인가? 기존email→refresh→해제순서를유지할까? | 이전명시후속은유지하되사용자가이번일정으로승격할수있음. refresh/session묶음12~24h,메일/reset10~20h,복구/unlink추가8~16h로전부일괄은주말충돌. 우선순위변경은승인후 |
| Q02 | PhaseB의‘OPEN+LIVE’는literal marketStatus OPEN까지인가, 승인된KIWOOM 정규0B·LIVE·fresh/mockAllowed인가? | 후자를추천하되OPEN확정이라표기하지않음. literal OPEN이필수면0s없을때그항목INCONCLUSIVE/BLOCKED로남김. 기존UNKNOWN정책을임의재강화/완화하지않음 |
| Q03 | KRX를최종실시세대상으로삼고Toss의옛미완료전체인수는후속으로둘까? NXT제품은이번완료선인가? | Kiwoom전환승인은있지만Toss의완료실적은생기지않음. NXT는방향만확정·핵심응답/차트미검증으로이번포함시2~4d+대기예상 |
| Q04 | 분산nonce/PoR/Sepolia를이번필수인가후속인가명시할까? | 단일operator/Anvil인수에는자동선행아님. 분산sender를실제도입하면nonce가선행. Sepolia의S0/O0/V0/RPC/ETH는별도승인 |
| Q05 | 운영부하/EC2보류유지와최종시연·영상·브리프/블로그·논문제출물범위 | 보류를자동해제하지않음. 학교제출범위와제품인수범위별도서명 |
| Q06 | 최신9지식갱신·Tool시세근거제약대응·PhaseC독립/연계범위·유료상한 | 새색인/모델호출자동승인없음. 기존8개Tool을상태조정도구로확대하지않음 |
| Q07 | 최신B환경생성·runner/safety새 run대응·키/유동성·10분상한·차트/조건부추가case를승인할까? | 구OWNER하드코딩수정필요. 1탭1m/BUY1/SELL1기존제한에조건부·6차트를끼워넣지않음. 별도창/호출한도승인 |

결정 근거가 저장소에 없거나 대화에서 학교 승인 여부를 확인할 수 없으면 ‘범위 결정 대기’다. 구현 에이전트가 선택해 완료표를 줄여서는 안 된다.

## 5. 의존관계와 권장 실행 순서

### 5.1 실행 순서 제안 — 아직 실행 승인 아님

```text
Q00 제출 범위 / Q01 이번 인증 범위 / Q02 KRX 판정 의미 확정
  → A. 승인한 잔여 개발만 수행 → 회귀·독립 검토 → 제품 HEAD 동결
       ├→ B. 최신 지식/manifest 검토 → 별도 승인 후 Phase C 독립 검증
       └→ C. 최신 HEAD 신규 KRX 환경·전용 안전 runner 준비/무외부 검증
             → D. 월요일 Phase B 실제 KRX 거래 + 그 거래에 연결된 Phase C
                  → E. Phase D 증거 종합·제한사항·최종 판정
```

여기 A~E는 작업 묶음이고 인수 Phase A~D와 다른 표기다. **이번 문서 작성 후에는 Q00~07 검토와 사용자 순서 승인에서 멈춘다.**

- **A — 잔여 기능 개발:** 이미 구현한 Google/전량/Faucet/조건부/대사를 다시 만들지 않는다. 인증 확장은 Q01에서 이번 필수로 선택한 것만 착수한다. 기존 설계의 권장 순서는 이메일/비밀번호 설정·재설정 → refresh/rotation/reuse 탐지/폐기/세션 → unlink/전체 로그아웃·복구다. 새로 refresh를 먼저 하자는 제안은 가능하지만 기존 합의처럼 기록하지 않는다. 세션 폐기를 선택하면 이미 연결된 STOMP와 계정 연결·재인증에 미치는 영향도 함께 검증한다.
- **B — 시장 거래 없이 가능한 실제 AI 인수:** 최신 지식의 사실 정정과 검색 회귀, 일반 지식 질문, USER/ADMIN 권한, 자기 잔고/지정 주문의 Trade 요약 Tool, 제한된 Skill, 실모델 안전 응답을 독립 검증한다. 전체 거래 목록 전용 Tool은 없다. 시장/체인 자료가 fixture면 그대로 표시한다. 모델 응답이 성공해도 실제 KRX 거래 근거를 사용한 AI 인수는 남는다.
- **C — 신규 KRX 환경/도구:** 제품 동결 이후 새 DB/AI DB/persistent Anvil/키/계약/초기 유동성과 불변 기준점을 만든다. 구 환경은 사용하지 않는다. 현재 runner/safety의 구 run 소유자 상수를 새 run을 명시적으로 검증하는 방식으로 최소 보완하고, 해시·소유권·시각·독립 watchdog·egress 차단을 외부 연결 없이 검증해야 한다. 기존 manifest에 새 HEAD/hash를 덮어쓰지 않는다.
- **D — 월요일 실제 시세:** 목표는 **2026-10-19**다. 날짜/현지 시각만으로 시장 OPEN이나 휴장 여부를 확정하지 않는다. 실제 공급자 데이터와 승인된 가용성 정책을 통과할 때만 거래한다. Phase B 거래 확정 후 동일 주문/receipt/원장에 대한 실제 AI 설명·수동 Skill을 검증한다. 자동 진단은 별도 격리 fault 시나리오와 승인 한도에서만 실행한다.
- **E — 최종 판정:** 선택한 필수 항목의 증거를 종합한다. 실패를 범위에서 조용히 빼거나 Synthetic 성공으로 바꾸지 않는다. 후속/제외 항목은 이유와 승인자를 명시해 이력으로 유지한다.

### 5.2 일정의 현실성 및 선행 관계

아래 시간은 **작업량 추정치**이지 실행 결과나 확정 일정이 아니다. 1d는 집중 작업 약 8시간이며 외부 설정·사용자 조작·장중 대기를 제외한다. 표의 개별 추정은 병렬 가능하므로 단순 합산하지 않는다.

| 묶음 | 예상 작업량 | 선행·중단 조건 |
|---|---|---|
| 범위/완료 기준 확정 | 사용자 검토 0.5~2h | Q00~07. 학교 확인은 별도 대기 가능 |
| 인증 추가 개발 | 선택별 U05~12 추정 참조 | 전체 선택 시 수일 이상. 월요일 전 완료를 보증할 수 없음 |
| 지식 9개 필요한 부분 갱신·무과금 검색/보안 회귀 | 3~6h | 코드 동결, 문서별 승인/manifest/index version 검증. 실제 embedding은 비용 승인 별도 |
| 신규 KRX runner/safety·환경·무외부 사전 점검 | 4~8h | 최신 제품 동결, 사용자 환경 생성 승인, Docker/시각 안정성 |
| 실제 AI 독립 인수 | 준비 2~4h + 실행 1~2h | 공급자 접근·최신 index·호출 예산 승인, 사용자/ADMIN 격리 |
| 실제 KRX 핵심 거래 + 연계 AI | 준비 1~2h + 승인된 연결 창 | 유효 체결·watchdog·등록 IP/WS 충돌 확인. 실패 시 같은 창에서 무제한 반복 금지 |
| Phase D/제출 정리 | 0.5~1d 이상 | B/C 실제 증거 및 미검증 목록. 논문·Android는 별도 일정 |

현재가 일요일인 상황에서 인증 전체·NXT 제품·분산 nonce·PoR·Sepolia를 모두 새로 완료하고 월요일에 마지막 인수를 한다는 일정은 현실적으로 충돌한다. **기존 후속 결정을 유지할지, 일부를 이번 필수로 승격해 인수일을 바꿀지 사용자가 선택해야 한다.** 아직 선택하지 않은 항목을 자동 제외하거나 전체 완료로 처리하지 않는다.

분산 nonce는 다중 sender/서버 실행의 선행 조건이고 단일 backend Anvil 인수의 자동 선행 조건은 아니다. Sepolia는 발행량·키·ETH·공급정책·새 기준점·확인 수/체인 재구성 검증이 선행한다. PoR은 현재 대사 결과를 재명명하는 작업이 아니라 부채 집계/증명/개인정보·온체인 자산 통제의 별도 설계다. 사용자 지갑/호가/직접 서명은 재개발 대상이 아닌 명시 제외다.

## 6. Phase A~D 인수 계획

### 6.1 공통 실행·증거 계약

- 인수 시작 전 제품 commit/tree hash, 실행 이미지 ID, harness 버전, runId/fixtureId, 설정 이름과 feature flag, DB schema, chainId/계약 주소, baseline의 blockNumber/blockHash와 canonical 일치 여부를 기록한다. 비밀 값·JWT·서명 원문·raw transaction을 공개 보고서에 쓰지 않는다.
- 기존 보존 환경이 아니라 새 격리 fixture를 사용한다. 원본 데이터를 수정하거나 baseline 재생성으로 불일치를 숨기지 않는다. 실패 자료는 보존하고 재실행은 새 run/fixture와 별도 결과로 연결한다.
- 결과는 **PASS / FAIL / BLOCKED / NOT_RUN**으로 구분하고 증거가 충분하지 않은 분석·정책 판정에는 **INCONCLUSIVE**를 사용한다. 테스트 runner의 SKIP은 PASS에 합산하지 않는다. 대사 항목의 MATCH/MISMATCH/INCONCLUSIVE와 감사 coverage는 인수 실행 결과와 다른 축이다.
- 실행마다 sanitized summary와 단계별 증거 인덱스를 작성한다. private 원본은 Git 제외 경로에 유지하고 문서는 소유자/보존 위치의 논리 식별자만 참조한다. 새 서비스만 소유권 확인 후 종료하고 DB/체인/볼륨·불변 baseline은 보존한다.
- 구체 명령은 P03/P06과 해당 harness `--help`/소스의 현재 gate를 다시 대조한 **run 전용 실행서**로 확정한다. 이 문서에 구 run의 실행 명령을 복사해 즉시 실행 가능한 것처럼 제시하지 않는다.

### 6.2 Phase A — 무과금 사전 인수

**현재 근거:** E01의 병합 제품 HEAD `426cb0b`에서 backend 495 PASS/38 SKIP, web107, Foundry36, Chrome fixture, 실제 신규 PG/Anvil 7 fixture+별도 JVM 총8 테스트 메서드 PASS다. 현재 `9a9197b`는 이후 문서 변경만 있다. 이번 조사에서 재실행한 결과가 아니다.

남은 개발을 하지 않으면 이 증거를 제품 동일성 근거와 함께 재사용할 수 있으나, 이후 제품/설정/보안/harness에 영향을 주는 변경을 승인하면 그 변경을 포함한 최신 HEAD에서 영향 회귀와 필요한 전체 회귀를 **새로 실행**한다. Google-only/null loginId, JWT/STOMP 만료, 정확한 전량 문자열, Faucet 한도/동시성, 조건부 주문·복구·정체 snapshot 재시작, 두 대사와 coverage를 유지한다. AI는 Mock/fixture까지만 허용한다.

선행: 새 fixture 소유권, DB/RPC 접속 fail-closed, clock preflight, 외부 credential 자동 import 차단. 중단: 다른 환경 접속, 시각 오차 초과, Docker 불안정, 필수 회귀 실패. SKIP된 38개를 통과로 쓰지 않고 선택한 외부/통합 gate별 실제 실행 여부를 따로 남긴다.

### 6.3 Phase C-독립 — 실제 시장 거래 없이 가능한 AI 검증

| 시나리오 | 입력/전제 | 필수 사실·근거 / 실패 조건 |
|---|---|---|
| RAG 정상 | 고정 golden12와 최신 승인 corpus/index | 기대 문서·직접 답변 근거·권한 metadata 확인. hit@5/MRR@5와 기존 E05 비교; 답변 문장 완전 일치는 요구하지 않음 |
| 최신 정책 질문 | ‘UNKNOWN이면 키움 모의 견적을 낼 수 있는가?’ | 정상 현재 generation 0B/290=2·LIVE·fresh와 CLOSED 우선, UNKNOWN≠OPEN, DB-only 비확대 설명. 0s 필수라는 구답변이면 실패 |
| 인증 질문 | ‘Google 로그인 후 새로고침/로그아웃은?’ | 현재 memory access token·재로그인, refresh/서버 폐기 미구현을 구분. 이미 구현된 것처럼 설명하면 실패 |
| Tool/사용자 격리 | 서로 다른 USER/ADMIN 및 본인 원장·지정 Order | 실제 반환 수치와 확정 DB/REST 동일, 타인 정보/ADMIN 도구 접근 차단. 전체 거래 목록 Tool은 없고 지정 Order의 Trade 요약만 조회. 데이터 없는 값 창작 금지 |
| Agent/Skill | KNOWLEDGE/STATE/MIXED/안전 유보와 3 Skill | route·허용 Tool·RAG 근거·trace·예산 준수. 도구에 없는 mockQuoteAllowed를 추정하지 않음 |
| 장애/공격 | prompt injection·잘못된 식별자·Tool/RAG/LLM 실패 fixture | read-only·secret 비노출·거래 무변경·안전한 부분 답변/실패. fixture 성공과 실제 공급자 장애를 구분 |

실제 모델 검증은 기존 E05가 사용한 H2/receipt fixture 결과와 별개로 기록한다. 최신 index가 없으면 새 색인 승인이 선행한다. 모델·embedding 가용성과 가격은 이번에 외부 확인하지 않았으므로 **현재 호출당 비용 미확정**이다. 과거 E05의 모델17요청/input24,920/output3,171 token·embedding8요청/input170 token은 산정 참고량이지 이번 청구 금액이 아니다.

제안 예산은 우선 독립 대표 질문 8개, 모델 HTTP 총24회 이하, embedding 총32회 이하, 자동 진단 OFF다. 이는 **새 제안으로 아직 승인되지 않았다.** 재시도 포함 모든 호출을 계수하고 사용자 확정 통화 상한 또는 호출 한도 중 먼저 도달하면 중단한다. 전체 golden 실제 답변 생성/자동 진단까지 추가하면 별도 배치 상한을 제안한다. 이전 P03의 금액/호출 제한을 새 환경의 자동 승인으로 해석하지 않는다.

### 6.4 Phase B — 최신 melon-init 신규 환경의 실제 KRX 인수

**준비:** 기존 KRX `kiwoom-e2e-20261014171302-662c5ba0`, Google5173/5174, playground와 다른 이름/포트/볼륨/키/DB/Anvil을 사용한다. 최신 동결 소스로 이미지를 만들되 기존 태그·이미지·manifest를 덮어쓰지 않는다. 초기 funding/allowance/ETH 준비 후 첫 Faucet/주문 전 새 자산 대사 기준점을 불변 저장한다. DB Faucet은 온체인 공급을 늘리지 않는다. 인증 기능을 추가했다면 최신 계정 계약도 포함한다.

**가용성:** 현재 연결 정상 정규 체결 `0B`, `290=2`, LIVE, 과거5초/미래2초, 연결 generation 및 Quote 발급 전후 세대 일치, CLOSED/지원하지 않는 상태 우선 차단을 검증한다. UNKNOWN을 OPEN이라고 쓰지 않는다. Q02에서 literal OPEN을 요구한다면 해당 근거가 없을 때 그 항목은 BLOCKED/INCONCLUSIVE다. Synthetic으로 대체하지 않는다.

**실행 제안:** 사전에 IP/동일 계정 WS 충돌·조회 조건을 사용자 확인 사실과 도구 확인 사실로 구분하고, 새 대상 watchdog ARMED/egress 차단/10분 독립 종료를 검증한다. 구 run의 승인과 manifest를 새 환경으로 자동 이전하지 않는다. Q07 승인 후 한 탭·1분봉, Faucet1회 → 신규 quoteId → BUY100,000 mKRW1회 → receipt/event/DB FILLED → 정확한 가용 mSEC 신규 SELL quote → SELL1회 → 최종 잔고/lock/개인 WS → 두 대사 순서로 진행한다. 외부 OAuth/REST/WS/토큰 재발급/재연결은 실제 호출량을 기록하고 승인된 10분 상한 안에서만 허용한다.

| 검증 단위 | PASS에 필요한 증거 | FAIL / 불충분 증거 |
|---|---|---|
| 실제 시세·서명 | 공급자/시각/가용성/generation, 서버 quote의 가격·만료·입력량 및 재사용 거절 | 시세나 generation 우회는 FAIL, 유효 시장 입력이 없으면 BLOCKED |
| BUY/SELL 각각 | Order/Trade/Quote/BlockchainTransaction 연결, txHash, canonical receipt status·Vault/Oracle event, 확정 DB 수량/수수료/lock | 이벤트 불일치/중복 정산 FAIL. SIGNED/SUBMITTED은 실패 확정 아님, REVIEW_REQUIRED 강제 정산 금지 |
| REST/개인 WS/전량 | 로그인 사용자 ID 일치·본인 portfolio·새 개인 이벤트, 정확한 가용 수량과 최종 mSEC0/lock0 | fixture WS 성공만 있으면 실제 개인 WS NOT_RUN/INCONCLUSIVE |
| 자산 대사·거래 전수 감사 | 동일 새 baseline에 대한 적절한 cut, 개별 판정 및 전체 scan coverage/양방향 조회 | coverage 불완전이면 전체 MATCH 금지. 운영자 잔고 비율을 준비금 보증/사용자 온체인 소유권으로 해석 금지 |

조건부의 실제 조건 미충족/충족/취소/만료, 만료 Quote, 유동성/응답 유실 등 전체 P03 시나리오는 삭제하지 않는다. 단 BUY1/SELL1·10분 창에 추가 거래를 몰래 포함하지 않는다. 실제 시세로 가능한 case와 신규 격리 fault/fixture case를 구분해 승인된 별도 배치로 진행한다. CLOSED/STALE 등 자연 발생하지 않은 상황은 실환경 PASS가 아니라 기존 fixture 근거 또는 NOT_RUN이다. 신규 주문을 중단할 일이 생기면 이후 SELL을 억지로 진행하지 않고 미완료 상태/체인 증거를 보존한다.

### 6.5 Phase C-연계 — B 거래를 근거로 한 AI 검증

- B에서 확정된 실제 사용자/Order/Trade/txHash/portfolio를 Tool이 조회하고 수량·상태·근거·권한이 실제 DB/receipt와 맞는지 확인한다. 성공 BUY/SELL 설명과 수동 `settlement-debugging`을 실행하되 사용자 범위와 ADMIN 범위를 분리한다.
- 성공 receipt의 MATCH를 설명하는 것과 **성공 거래가 자동 진단을 촉발하는 것**은 다르다. 현재 자동 진단 대상은 BUY/SELL `REVIEW_REQUIRED`다. 성공 MATCH 자동 인수를 요구하면 성공 receipt가 있으나 검토 상태가 된 별도 합법적 fault fixture를 설계·승인받는다. 정상 B 주문을 직접 DB 수정해 검토 상태로 만들지 않는다.
- 자동 실행은 default OFF에서 임의 활성화하지 않는다. ADMIN actor/권한, namespace, claim/dedup/한도/TTL·최소 저장/이력·상세 화면까지 포함한 실행과 비용을 별도 승인한다. 안전한 fault 재현을 못 하면 NOT_RUN으로 남긴다.
- 제안 추가 예산은 대표 4질문/Skill, 모델 HTTP12회 이하·embedding16회 이하, 자동 진단 최대1건이다. 앞 독립 예산과 별개로 사용자 확정이 필요하며 해당 count는 답변 품질/재현 성공을 보증하지 않는다.

### 6.6 Phase D — 최종 판정

선택된 필수 기능 ID마다 구현 HEAD → 테스트 실행 → 실제 인수 run → 증거 파일 → 남은 제한을 연결한다. A/B/C의 결과를 모으되 과거 PASS를 이번 새 실행으로 표현하지 않는다. B 거래 성공과 AI가 그 거래를 정확히 설명한 결과는 각각 판정한다. 외부 환경 미가용은 FAIL과 구분하되 PASS가 아니다.

필수 항목이 FAIL/BLOCKED/NOT_RUN/INCONCLUSIVE면 원인·재현·최소 수정 후보·재검증 범위를 보고하고 최종 PASS를 보류한다. 제품 수정은 새 승인 후 수행한다. 사용자에게 승인된 후속/제외 목록을 별첨해 **현재 제품의 선택 범위 완료**를 판정할 수 있지만, Q00 미해결이면 원제안/학교 요건 전체 완료를 주장하지 않는다. 전체 소요는 선택 범위와 시장/외부 서비스 가용성에 좌우된다.

## 7. 완료 판정 기준

| 판정 | 필요한 조건 | 이 판정만으로 주장할 수 없는 것 |
|---|---|---|
| 구현 완료 | 승인 계약과 실제 코드/API/schema 일치, 안전한 Git 통합·독립 검토 | 테스트/실서비스 성공 |
| 테스트 완료 | 대상 HEAD·명령·환경·신규 실행 수·실패/skip·증거 명시 | 외부 provider/Google/실시세 성공 |
| 실환경 인수 완료 | 승인된 외부 연결·실브라우저/실데이터·실제 DB/Anvil 경로의 단계별 증거 | 다른 공급자/다른 이미지/미실행 장애 성공 |
| 현재 제품 최종 완료 | 사용자 확정 필수 ID 전부 통과 + A~D 증거·제한·후속 목록 수용 | 학교 원제안 모든 요구·실금융 지급 보증 |
| 원제안 제출 요건 완료 | Q00에 따른 학교/팀 승인 범위 및 필수 제출물 확인 | 제품 테스트만으로 대체 불가 |

최소 공통 안전 기준은 타 사용자 자산 접근 차단, quote 소유자·방향·입력·일회 소비, 입력 자산 잠금/정산 원자성, 동일 raw/nonce/hash 복구, 불확실 거래 임의 해제 금지, AFTER_COMMIT 공개, 감사 불충분 시 INCONCLUSIVE/coverage 분리, secret 비노출이다. 기능 추가가 이 기준을 바꾸면 별도 설계·승인이 선행한다.

인증 확장은 선택된 세부 항목별 완료표를 사용한다. Google 성공만으로 U05~12를 닫지 않는다. Android 기기/실Google/시세 read-only/제품 거래/실AI/인프라 시험도 서로 대체하지 않는다. 팀원 또는 AI가 구현한 코드를 본인이 전부 독립 작성한 것으로 소개하지 않고, 개발자의 요구·검토·검증·학습 기여를 실제 근거와 분리한다.

## 8. 변경 이력 및 이후 계획 관리 규칙

### 8.1 이번 기록

| 날짜/기준 | 수행한 작업 | 실행하지 않은 작업 |
|---|---|---|
| 2026-10-18 / `9a9197b` | 원계획·최초 HWP 텍스트·현재 소스·Git·과거 테스트/독립 검토/인수 기록 조사, 기능 ID 및 범위 결정·의존관계 복원, 마스터 초안 작성 | 제품 코드 변경, 테스트 재실행, Docker/DB/Anvil 조작, 실제 API/유료 AI, commit/merge/push |

작업 시작의 사용자 변경 `claude-docs/implementation-log.md` NXT 구간 및 미추적 `claude-docs/kiwoom-nxt-aftermarket-observation-2026-10-16.md`는 그대로 보존한다. 이 문서를 새 기준으로 안내하는 README 링크 외에 기존 계획·실행 보고서의 당시 기록을 덮어쓰지 않는다.

### 8.2 앞으로의 갱신 규칙

1. **기능 ID와 원출처를 유지한다.** 추가 발견은 새 행, 삭제 대신 ‘제외/후속/대기’와 사용자 결정 날짜/근거를 남긴다. 학교 제출·제품 범위·인수 범위를 분리한다.
2. 새 작업 시작 전 본 문서의 해당 ID/의존/승인을 확인한다. 구현 승인과 환경 생성·외부 호출·비용·Git 통합 승인을 혼동하지 않는다. 불명확하면 결정 대기다.
3. 완료 시 해당 행의 네 축을 갱신하고 상세 implementation/검토/인수 문서로 연결한다. commit/build hash, schema/index version, runId·시각·테스트 수/skip·실환경 여부·미검증 한계를 필수 기록한다.
4. 수정 전/후, feature/merge HEAD, 실제 브라우저/fixture, H2/PG/Anvil, 모델Mock/실provider를 분리한다. 새 실행이 없으면 ‘과거 결과/제품 동일성 참조’라고 쓴다.
5. 외부 시세 계약·발행정책·보관모델·정산/복구·인증 보안 변경은 영향 설계와 독립 검토를 거친다. 기존 승인 경계나 guard를 보고서 문구만으로 우회하지 않는다.
6. 상태표와 원문이 충돌하면 과거 기록을 지우지 않고 변경 대장에 양쪽 근거와 현재 결정을 적는다. 범위 변경이 없으면 기존 완료 기능을 다시 구현할 계획으로 되돌리지 않는다.
7. 매 인수 종료 후 A~D gate와 선택 필수/후속 목록을 함께 확인한다. 전체 백분율 대신 완료/실인수 잔여/미구현/결정 대기/명시 제외를 보고한다.
8. 원본 HWP/제안·학술 산출물, 기존 runtime/실패 증거와 사용자 변경은 유지한다. private 원본·인증정보를 마스터 문서나 Git에 복사하지 않는다.

### 8.3 독립 검토 기록

2026-10-18 별도 에이전트 `review_completion_master`가 원계획·현재 코드·실행 보고서와 본 문서/README 색인을 대조했다. 발견한 실제 메서드명 오류 2곳을 `KiwoomPriceProvider.currentForTrading()` 및 `MarketPriceService.requireTradingContext()`로 정정했다. 추가 권고인 ‘거래 history’의 오해도 지정 Order의 Trade 요약으로 고치고, 전체 거래 목록 전용 Tool이 없음을 명시했다. 재검토 결론은 **발견된 필수 수정 없음**이다. 이것은 제품 회귀나 실환경 인수 PASS 판정이 아니다.

검토 한계: 원 HWP 본문의 독립 재해독은 검토자가 수행하지 않았으며, 해당 원요구는 주 에이전트의 읽기 전용 텍스트 해독 결과와 문서 기록을 검토했다. 두 역할 모두 이번에 테스트·Docker/DB/RPC·private 증거 조회·외부 API를 실행하지 않았다. 문서 작성자는 로컬 링크58개 존재, 필수8절 및 Git 상태/공백 검사를 확인했다. 제품 테스트 결과는 전부 명시한 과거 실행 근거다. 사용자 검토 전 다음 개발 단계로 자동 진행하지 않는다.
