# 선물·증거금·자동청산 — 48시간 구현 계획

> 작성: 2026-10-18 / 상태: **설계 제안·사용자 승인 대기 / 구현하지 않음**
> 조사 기준: 원본 `melon-init`, `9a9197b290c57ce3351452fcf7f0794e6f24cdfc`. 기존 제품 테스트 기준은 `426cb0b`이며 이후 커밋은 문서다.
> T0는 사용자가 이 설계·정책·검증 범위를 확정하고 실제 구현을 승인한 시점이다. 목표 T0+48시간이며 특정 요일과 무관하다. 이 문서 작성 시간은 개발 착수로 계산하지 않는다.

## 비전공 평가자를 위한 한 페이지 요약

**무엇을 추가하는가?** 지금은 모의 원화로 삼성전자 가격 추종 토큰을 사고판다. 확장에서는 사용자가 일정 금액을 담보로 맡긴 것처럼 서비스 장부에서 잠그고, 가격 상승에 투자하는 Long 또는 하락에 투자하는 Short를 선택한다. 2·5·10배 중 하나를 선택하면 같은 담보로 더 큰 금액에 해당하는 가격 변동 손익을 계산한다. 실제 주식 매매·대출·공매도는 하지 않는다.

**돈은 어디서 오는가?** 손실은 해당 포지션에 잠근 모의 증거금에서만 부담한다. 이익은 별도로 미리 조성한 유한한 운영자 모의 손익 풀에서 지급한다. 레버리지 배수만큼 원화를 발행하거나 다른 사용자의 담보를 무단 사용하는 방식이 아니다. 운영자 풀 부족과 급변에 따른 미회수 손실도 기록하며, 지급 재원이 없는데 성공한 것처럼 잔고를 늘리지 않는다.

**언제 강제 종료하는가?** 담보와 미실현 손익의 합이 현재 포지션 가치의 5% 이하로 떨어지면 마진콜을 보낸다. 온체인 마진콜이 확정된 뒤 유예 시간을 준다. 회복하거나 증거금을 추가하면 유지할 수 있다. 유예 후에도 새로 확인한 가격에서 위험하면 서버가 청산 거래를 자동 전송한다. 블록체인도 포지션 상태·서명 가격·유예 만료·위험 조건을 직접 검사하고 중복 청산을 막는다.

**48시간에 추천하는 범위는?** 사용자 자산은 지금처럼 PostgreSQL 모의 장부에 둔다(A안). 새 `FuturesEngine`에는 포지션·증거금 수치·마진콜·청산 상태를 기록하고, 청산 조건을 실제 Solidity로 검증한다. 정상 개설·종료·증거금 추가도 체인 확정 후 장부에 반영한다. 단순히 ‘청산했다’는 이벤트만 남기는 방식은 추천하지 않는다.

**블록체인이 하지 않는 일은?** A안에서는 증거금 ERC-20을 컨트랙트에 예치하지 않는다. 컨트랙트가 DB 잔고·지급 능력·사용자의 실제 개인 서명을 증명하지도 않는다. 따라서 ‘온체인 증거금 수탁/실제 토큰 지급 완료’라고 발표하면 안 된다. 이를 원제안의 `MarginVault`와 동일하다고 인정할지는 별도 확인이 필요하다.

**어떻게 증명하는가?** 결정적 합성 가격으로 Long 급락·Short 급등·회복·유예 후 청산을 재현한다. 계산 테스트, 실제 PostgreSQL/Anvil의 Receipt/Event와 잔고, 서버 재시작 복구, Chrome에서 실제 서버·체인을 연결한 데모를 각각 확인한다. 이전 테스트나 화면 fixture를 새 실제 거래 성공으로 대체하지 않는다.

**현실적 위험:** 48시간은 안전 검증을 생략하지 않는 **공격적인 로컬 프로토타입 목표**다. A안도 낙관적 직접 작업 약39시간+독립 검토·대기·휴식이 필요하고, 실패 재현/정산 검증이 길어지면 초과한다. B안(실제 온체인 증거금 보관)은 통상 수일 이상 추가로 필요하다. 필수 gate가 실패하면 미완료로 보고하지, 가짜 온체인 성공으로 마감하지 않는다.

## 1. 최초 목표, 현재 상태와 이번 완료 기준

### 1.1 원자료와 현재 사실

| 근거 | 확인한 사실 / 해석 경계 |
|---|---|
| [최초 HWP 제안서](<../졸업프로젝트 제안서_김성은, 서교빈.hwp>) §1.4~1.5/2.1~2.4/3.1 | Long/Short·2~10배·유지증거금5%·마진콜/유예/청산, `MarginVault`/`FuturesEngine`, Phase3 최소 성공. 이번에도 원본의 Phase3 최소 성공 및 계약 이름을 읽기 전용 텍스트 해독으로 확인. 본문 요구 복원 상세는 마스터 P00/X09~15 참조. 연락처 등 개인 정보는 옮기지 않음 |
| [기존 구현 계획](<../구현 계획.md>) §5.2/17 | 현물형 MVP에서는 선물/레버리지/청산을 후속으로 두었음. 현재 ‘거래소 Phase3=백엔드 체인 연동’은 최초 제안 Phase3와 다른 번호 |
| [현재 마스터](PROJECT_COMPLETION_MASTER_PLAN_2026-10-18.md) §1/2.6/4~7 | X09~11 미구현, X12 논문/연구 별도. 사용자 수탁 A 모델과 제외 기능, 최신 A~D 인수 경계 |
| [보관 모델 비교](../claude-docs/user-wallet-custody-architecture-review.md), [최종 점검](../claude-docs/asset-custody-final-check.md) | 사용자 자산은 DB 모의 장부, 체인 현물 자산은 operator/Vault. 사용자별 지갑/직접 서명/호가 매칭은 제외 |
| [최신 통합 증거](../claude-docs/google-sell-all-integration-2026-10-18.md) | 과거 새 실행: backend495 PASS/38 SKIP(총533), web107 PASS, Foundry36 PASS; 7 신규 PG/Anvil fixture+별도 JVM에서8 테스트 메서드 PASS. 이번 작업에서 재실행하지 않음 |
| [Synthetic 구현](../claude-docs/synthetic-market-design.md), [Chrome E2E](../claude-docs/synthetic-browser-e2e-2026-10-16.md) | 합성 엔진·영속·commit 후 공개 및 과거 실제 브라우저 현물 거래 성공. 선물 테스트가 존재한다는 뜻은 아님 |

현재 소스에는 선물 포지션·증거금 계정·청산 컨트랙트·위험 엔진이 없다. 현물 평균 매수가/평가손익, 조건부 BUY/SELL, 장기 거래 경고는 선물 엔진의 대체 구현이 아니다.

### 1.2 48시간에 제안하는 정확한 완료선

- 단일 `mSEC` 기준의 **고정 수량·격리 증거금·선형 차액결제 모의 선물**. Long/Short, 배수 `{2,5,10}`, 포지션 전체 개설/전체 종료, 추가 증거금, 마진콜→유예→회복/강제청산.
- 만기/펀딩비/실물 인도/실제 차입/공매도/사용자 간 매칭 없음. 현물 mSEC를 mint/burn하지 않고 가격 노출만 계산한다. 전통 선물 또는 완전한 무기한 선물 거래소와 동일하다고 부르지 않는다.
- 기본안은 사용자·종목별 비종료 포지션 최대1개, 부분 종료/증액/헤지 모드/교차 증거금/유예 중 증거금 인출 없음. Long/Short 동시 검증은 별도 사용자로 수행한다. 이 축소는 **승인이 필요한 제안**이다.
- 신규 로컬 격리 Synthetic+PostgreSQL+persistent Anvil+Chrome에서만 실행한다. KRX/Toss/NXT·유료 AI·Sepolia·배포는 이번 48시간 증명 대상이 아니다.
- 수학·자산 보존·서명·온체인 위험/유예 검증·멱등 정산·재시작 복구·실브라우저 청산을 전부 통과해야 한다. 일부 실패를 ‘UI만 성공’으로 대체하지 않는다.
- 원제안의 청산 핵심 경로를 강화하지만 A안은 **실제 온체인 증거금 예치, batchLiquidate, Android, Oracle 연구/논문까지 충족하지 않는다.** 학교 최소 성공 판정의 해당 차이는 사용자/팀 확인 대상이다.

## 2. 현재 재사용 가능한 코드와 수정 위험

이하 `B/`는 `backend/src/main/java/com/pricetrack/exchange/`, `R/`는 `backend/src/main/resources/`, `W/`는 `tools/websocket-test-client/`다. 제안 이름은 아직 없는 신규 파일이며 기존 파일과 구분한다.

| 실제 코드·메서드 | 재사용 범위 | 그대로 쓸 수 없는 이유 / 변경 위험 |
|---|---|---|
| `B/wallet/UserBalance.java`: `amount/lockedAmount/getAvailableAmount`; `WalletService.getForUpdate`, `UserBalanceRepository.findForUpdate` | 사용자 가용 mKRW와 비관적 잔고 잠금 | `lock/unlock` 자체는 음수/과다 잠금을 막지 않음. 선물 서비스가 검증해야 함. amount에는 locked 포함. 증거금을 별도 amount로 이중 차감하지 않음 |
| `B/auth/google`, `B/auth/JwtTokenProvider`, 기존 Security 설정 | 내부 userId·USER/ADMIN·Google/local 동등 권한 | Google `loginId=null`도 동작해야 함. 새 인증/refresh/메일 기능을 끼워 넣지 않음 |
| `B/market/MarketPriceService.currentForTrading/requireTradingContext`, `TradingPriceContext.freshAt/sameConnection` | 공급자별 가용성·5초/미래2초·generation 근거 | require 메서드만 호출하면 `allowed`/fresh 전체 검증이 완료되는 것은 아님. 선물 경계에서 모두 검사 |
| `B/blockchain/oracle/PriceReportIssuer.issue`, `PriceReportEip712`, `PriceReportSigner` | EIP-712 계산/서명 방식·발급 전후 시세 검증 원칙 | 현재 BUY/SELL/minOutput/ExchangeVault 전용 RPC. `issue()`를 선물에 직접 호출하지 않음. 새 타입/domain/issuer 필요 |
| `contracts/src/PriceOracle.sol` | TTL30초·미래2초·서명/재사용 검증 알고리즘 참고 | `authorizedConsumer` **주소 하나**이고 현물 Vault를 가리킴. Futures로 바꾸면 현물 파손. 현물 Oracle/보고서/consumer는 수정하지 않음 |
| `ExchangeVault.sol`, `PriceReportTypes.sol` | 기존 현물 회귀 기준 | 두 방향 교환만 지원. 선물 상태/담보/청산 검증 없음. 선물용 상태나 타입을 끼워 넣지 않음 |
| `B/blockchain/transaction/BlockchainTransactionSender.submitInternal/recoverSigned`, `BlockchainTransactionPersistence.saveSigned` | pending nonce→gas추정→raw 서명→hash 계산→REQUIRES_NEW 저장→broadcast, 동일 raw 복구 패턴 | 현재 타입/DB 연결/정산은 현물 중심. 신규 futures를 단순 `submitSystem`으로 넣으면 기존 reconciler가 Order 없는 현물로 처리할 위험. 패턴을 별도 경로에 적용 |
| `B/blockchain/reconciliation/BlockchainReconciliationService.reconcileSubmitted` | receipt polling·확인 수·불확실 상태 유지 원칙 | UPDATE_PRICE 외에는 현물 정산. 현재 이 메서드의 확인 수 검사는 block 높이 기반으로, 새 선물 canonical blockHash 검사를 이미 제공한다고 가정하지 않음 |
| `B/blockchain/settlement/OnchainSettlementService.settleSuccess/Failure` | 원자 정산·unique·완료 guard·AFTER_COMMIT 원칙 | BUY/SELL Trade/평균가·입력 자산 전용. Futures PnL을 가짜 Trade로 만들지 않음 |
| `B/reserve/ReserveStore.cut`, `ReserveService.stable/boundary`, `ReserveCalculator.calculate` | repeatable-read/짧은 snapshot·explicit chain block·cut 변화 INCONCLUSIVE·불변 baseline | DB 기대액은 Faucet−BUY+SELL뿐. 선물 확정 PnL을 추가하지 않으면 정상 잔고도 MISMATCH. 선물 journal/cut/version 포함 확장 필수 |
| `B/tradeaudit/AuditStore.cut`, `AuditValidator`, `RpcAuditChain` | 현물 감사/coverage 유지 | BUY/SELL 및 Vault/Oracle 스캔 범위. 기존 MATCH를 ‘모든 선물도 감사’로 표현 금지. 별도 futures operation 검증과 coverage 필요 |
| `B/market/provider/simulated/SyntheticMarketEngine.next`, `SimulatedPriceProvider.advance` | seed/step/Clock, 가격·봉 저장 후 메모리/WS 공개 | seed 고정만으로 원하는 시간의 급락/회복을 보장하지 않음. 별도 fixture 전용 결정적 경로 필요. 현 provider의 private 메서드를 일반 관리자 가격조작 API로 노출하지 않음 |
| `B/websocket/publisher/WebSocketEventPublisher`, 사용자 publisher/기존 만료 guard | userId 기반 개인 AFTER_COMMIT 채널, JWT 만료/재접속 REST | 새 queue/type의 서버 allowlist와 브라우저 처리 추가 필요. 공개 가격 토픽에 포지션/담보 노출 금지 |
| `B/conditional/ConditionalOrderService`, 기존 PG/Anvil E2E | 의도/실행 분리·idempotency fingerprint·짧은 DB phase 참고 | 조건부는 선물 위험 상태/청산 아님. fixture DB 이름/localhost guard도 새 환경 그대로 허용하지 않음 |

추가로 현재 `PortfolioService`의 총액은 현물 amount 평가다. 담보가 amount에 포함되므로 다시 더하면 중복이고, 선물 미실현 손익은 기존 totalValue에 자동 포함되지 않는다. 기존 응답을 조용히 재정의하지 않고 별도 선물 요약과 합산 설명을 제공한다.

## 3. 자산 보관·온체인 책임 대안

| 비교 축 | A: DB 증거금 + 온체인 포지션/위험 검증 | B: 실제 ERC-20 MarginVault + FuturesEngine |
|---|---|---|
| 증거금 정본 | 사용자 DB amount/locked 및 선물 journal. 체인은 수치·상태를 기록 | Vault가 토큰을 보유하고 사용자 식별별 collateral/부채를 관리. DB는 투영/사용자 연결도 관리 |
| 청산 검증 | 체인에 저장한 Q/P0/C·마진콜 deadline으로 위험/유예/버전 검증 | 같은 검증에 더해 escrow·지급/차감까지 EVM 원자 처리 |
| 원제안 충족 | Long/Short·레버리지·마진콜·유예·실제 청산 Tx/상태는 가능. `MarginVault` 자산 예치와는 차이 | MarginVault/FuturesEngine 원안에 더 가까움. 그래도 Android/연구/실제 유동성 등은 별도 |
| 자산 정합성 | DB의 선물 손익↔house pool 보존을 구현. 체인이 DB 담보 존재를 보장하지 않음 | 토큰 보존은 더 강하지만 DB Faucet↔실제 onchain funding/입출금 bridge 정합성이 새로 필요 |
| 운영자 신뢰 | operator가 DB 담보와 사용자 귀속을 정직하게 등록한다는 신뢰 남음 | 위탁 operator라면 사용자 귀속/동의 신뢰 여전히 남음. 사용자 직접 서명 소유권은 별개 |
| 기존 영향 | 현물 계약 변경0, 선물 전용 tx 경로/DB·대사·UI 확장 | 신규 token transfer/allowance/실제 풀 자산/withdraw·담보 이전·대사 기준 추가. 기존 ‘CustodyVault 미도입’ 결정 재승인 필요 |
| 지급 재원 | 유한 DB house pool. 실제 온체인 지급 아님 | 별도 onchain payout pool과 담보를 분리. 예치액만으로 무한 이익 지급 불가 |
| 실패 복구 | chain 성공→DB 실패 window·확정 전 잠금 유지 | chain 보관 변화+DB 지급/잠금 이중 원장 window, 보상/출금 실패·재진입 등 더 큼 |
| 48시간 예상 | **고위험이지만 제한 프로토타입 후보** | 계획 추정 80~140 직접 작업시간 이상, 통합/검토 포함 약5~10 작업일. 48시간 권장 안 함 |
| 표현 | ‘온체인 검증을 동반한 DB 수탁형 모의 선물’ | ‘운영자 위탁형 온체인 증거금 선물’ — 개인 키 통제와는 다름 |

**추천: A의 상태 검증형.** 단순 이벤트-only 모델은 Solidity가 청산 가능성을 검증하지 않아 목표에 부족하다. B를 작은 함수 하나 추가로 가능한 것처럼 설명하지 않는다. A에서 체인이 확인하는 것은 **등록된 C가 사실이라는 가정 아래의 위험 수학과 상태 전이**이며, PostgreSQL 지급 재원을 독립 검증하는 것은 아니다.

48시간에 event-only로 축소하면 빨라질 수 있으나 ‘온체인 청산 조건 검증’ 완료 기준을 충족하지 못하므로 이번 추천 완료선으로 채택하지 않는다. 반대로 실제 온체인 collateral이 학교 필수라면 A를 동일 목표 달성으로 주장하지 말고 B와 일정 변경을 승인받아야 한다.

## 4. 권장 아키텍처와 처리 흐름

```text
Chrome → 기존 JWT → FuturesController → 짧은 DB prepare(가용액 잠금/의도 저장)
                                      ↓ DB lock 해제
                   FuturesReportIssuer ← 기존 TradingPriceContext
                                      ↓ 새 EIP-712(domain=FuturesEngine)
                   FuturesTxSender → raw/nonce/hash 독립 commit → 로컬 Anvil
                                      ↓
         FuturesEngine: 포지션/담보 수치·가격서명·상태·deadline·중복 검증
                                      ↓ canonical Receipt + 정확한 Event
         FuturesReconciler → DB 원자 정산 → AFTER_COMMIT 개인 WS → REST 재동기화

commit 후 합성 가격 이벤트 + 1초 보조 scan → RiskEvaluator → 동일 action claim 경로
```

### 4.1 현물과 실행 지갑 경계

추천은 **별도 로컬 futures executor 키와 전용 tx 원장**이다. 이는 사용자별 지갑 도입이 아니라 backend의 추가 운영 실행 계정이며 ERC-20을 보관/교환하지 않고 Gas만 쓴다. 현물 operator와 다른 주소임을 startup에서 강제해 두 개의 sender가 같은 nonce를 경쟁하지 않게 한다. 가격 서명 키는 executor와 반드시 분리하고 새 EIP-712 domain으로 서명한다. 신규 전용 서명 키 권장, 현물 키/환경을 자동 복사하지 않는다.

기존 sender 패턴을 재사용하되 직접 현물 `BlockchainTransaction`에 섞지 않는다. 공통 저수준 유틸 추출은 검토 후 최소 범위로만 허용하고, 기존 현물 전송·복구는 기본적으로 그대로 둔다. 사용자 요구가 반드시 단일 executor 주소라면 typed routing·통합 nonce coordinator·기존 reconciliation 분기를 먼저 재설계해야 하므로 48시간 추정 증가와 추가 승인이 필요하다.

### 4.2 개설·추가·종료·청산

1. **개설:** 사용자 margin M/L/side → fresh 가격 preview → 확인한 previewId 제출. DB에서 동일 user의 active guard와 balance를 잠그고 C를 예약, `OPEN` operation 저장. 서명은 서버 DB에서만 조회. 준비 후 새로 검증한 가격을 고정해 Q/entryPrice/서명 생성. preview와 실행 가격 차이는 승인한 최대편차로 검사하며 조용히 다른 노출로 개설하지 않는다. 기본 편차0 제안에서는 preview 가격/Q를 바꾸지 않고, 발급 전후 current generation·적격 가격이 맞지 않으면 미서명 operation을 거절하고 예약만 원복해 재확인을 요구한다. 체인 `open` 성공 receipt를 확인한 뒤 DB position을 OPEN으로 확정한다. pending 동안 margin 잠금 유지.
2. **추가 증거금:** 새로운 available mKRW만 예약하고 TOP_UP operation→체인 C 증가→확정 후 DB C 증가. Q/entryPrice/L 표시는 기존 개설값 유지(유효 레버리지는 감소). 실패하면 **추가분만** 해제. 기존 담보는 유지. signed 이후 취소/임의 해제 불가.
3. **정상 종료:** 최신 시세·expectedVersion/미실현 PnL 검증 → 양의 실현손익 지급에 필요한 house 가용액 선예약 → CLOSE operation → 체인 상태/손익 확정 → DB amount 증감·C unlock·house/journal·position 종료를 한 transaction으로 처리.
4. **마진콜:** 위험 감지는 DB 예고일 뿐. `START_CALL`을 온체인 확정하면 call epoch/deadline이 생긴다. 서버/화면은 이를 유예의 정본으로 사용한다. **서버 스케줄러가 스마트컨트랙트를 호출해야 하며 체인이 스스로 주기 실행하지 않는다.**
5. **회복:** 새 적격 가격에서 E>MM이면 `CLEAR_CALL`; 체인/DB 확정 후 정상. top-up으로 회복할 경우 TOP_UP에 새 가격을 결합해 원자적으로 clear 가능. 아직 미확정이면 기존 call을 지운 것으로 표시하지 않는다.
6. **강제청산:** 체인 deadline 경과·현재 적격 가격에서 여전히 E≤MM 확인→새 청산 보고서→`liquidate`→canonical receipt/event→실현 손익·담보 해제. 가격/버전이 맞지 않아 revert되면 청산 완료가 아니라 action 실패, position은 여전히 열려 있다.

48시간 최소 UI의 개설 preview는 현물 PriceQuote가 아니다. 짧은 선물 preview를 user/side/M/L에 귀속·일회 소비하고 서버가 보고서/서명 보관, 사용자는 ID만 제출하는 기존 원칙을 따른다. 상태가 불확실한 응답 후 새 ID로 자동 재개설/재종료하지 않는다.

## 5. 경제 모델·상태·API·DB/컨트랙트 계약

### 5.1 단위와 계산 — Java/Solidity가 같은 정수 규칙 사용

모든 항목은 아래의 **제안된 모의 금융 규칙**이며 기존 현물 수수료나 실제 거래소 표준을 바꾸는 설명이 아니다.

| 기호 | 정의 |
|---|---|
| M, C | 최초/누적 증거금(mKRW 최소단위, 18자리). C=M+확정 top-up 합 |
| P0, P | 개설/평가 가격 E8(소수8자리), 양수 |
| Q | 포지션 수량 최소단위 E18. Short도 Q 양수, 방향 d=-1 |
| L, d | L∈{2,5,10}, Long d=+1 / Short d=-1 |
| N, U, E, MM | 현재 명목금액, 미실현손익, equity, 유지증거금(모두 mKRW E18) |

```text
Q  = floor(M × L × 10^8 / P0)             # 수량 고정; 남는 극소 notional 오차는 노출 축소
N  = floor(Q × P / 10^8)
U  = floor_signed(d × Q × (P - P0) / 10^8)
E  = C + U
MM = ceil(Q × P × 500 / (10^8 × 10000))    # 현재 notional의 5%; 최초금액 5%가 아님
위험 = (E <= MM)                          # 동률도 위험, 나눗셈 ratio로 판정하지 않음
```

`floor_signed`는 음수도 −∞ 방향이다. Solidity signed `/`의 0 방향 절삭과 다르므로 보정 함수/Java BigInteger와 cross-language golden/fuzz가 필요하다. MM은 올림으로 1wei 경계에서 위험을 축소하지 않는다. 중간 계산 BigInteger/int256과 `mulDiv` overflow guard를 쓰고, decimals 초과는 반올림 접수하지 않고 거절한다. JSON 금액은 전부 문자열; 브라우저 `Number`를 정산/전량/서명 입력에 사용하지 않는다.

도메인 상한은 현재 DB `NUMERIC(30,18)`의 정수12자리 한도와 int256 중간곱을 동시에 만족해야 한다. 개발 시 확정할 제안은 M/Q 각 <10^12 실제 단위, 가격 <10^12 mKRW(E8<10^20), 최종 잔고/house도 <10^12 단위다. 초과 입력·손익은 명시 거절/검토 필요이지 wrap/clamp하지 않는다. 이 상한에서 Q×ΔP는 int256보다 작다는 경계 테스트가 필요하다.

**기본 비용 제안:** 개설/종료/청산 수수료0 bps, 펀딩0, 청산자 보상0. 기존 ExchangeVault의10bps는 그대로다. 48시간에 별도 수수료 회계·악성부채 우선순위를 추가하지 않기 위한 명시적 정책이다. 비영 수수료가 필수면 user debit/house·fee 귀속/예약/위험 수식에 포함한 재설계와 추가 테스트가 필요하다. 정책은 immutable version/hash로 저장하며 중간에 바꾸지 않는다.

### 5.2 잠금·실현손익·재원·악성부채

- 증거금 C는 `user_balances.amount`에 이미 포함되고 `lockedAmount`만 증가한다. available=amount−locked. 미실현 이익은 available에 더하지 않는다. spot/조건부/Faucet과 같은 balance 행 잠금을 사용하므로 이중 사용을 막는다.
- 정산 시 raw equity가 음수여도 user amount를 음수로 만들지 않는다. **격리 담보 손실 한도**를 적용하며 다른 가용자산을 자동 추징하지 않는다.

```text
D = max(0, -(C + U))                  # 담보를 초과한 이론적 미회수 손실
R = max(U, -C)                       # 사용자 실제 실현손익, 격리담보 한도
사용자 amount 변화 = R               # C를 다시 지급하지 않음: 이미 amount에 포함
사용자 locked 변화 = -C
house pool 변화 = -R                 # 양의 사용자손익은 house가 지급
사용자 변화 + house 변화 = 0
누적 badDebt += D                    # 실제 사용자 부채/추가 현금 청구 아님
```

예: C=100,000, U=−200,000이면 사용자 −100,000, house +100,000, badDebt100,000이다. house에 +200,000을 적거나, badDebt를 house에서 다시 차감하면 잘못된 이중 회계다. 이 구조의 badDebt는 무헤지 운영자의 이론적 채권 포기를 보여 주며 지급능력 보증이 아니다.

손익 풀은 새 환경에만 불변 개시자본 journal로 준비한다(데모 제안10,000,000 mKRW, **수치 승인 필요**). DB 모의 house 자본이며 온체인 mint/Vault 유동성이나 DB Faucet grant가 아니다. Faucet 전역5천만 예산과도 별개로 표시한다. 사용자 손실은 house로 이전되고 사용자 이익은 house에서 이전된다. 실제 주식 헤지/원화 준비금은 없다.

**유한 풀의 한계:** Long 이익은 상한이 없으므로 유한 자본으로 모든 미래 이익을 보증할 수 없다. 이번 추천은 손익 상한을 몰래 넣지 않고, 종료 시 양의 R만큼 house 가용액을 미리 예약한다. 부족하면 **SETTLEMENT_BLOCKED(POOL_INSUFFICIENT)**로 신규 종료 tx를 보내지 않고 잠금과 열린 노출을 유지, ADMIN/사용자에게 명시한다. 자동 mint·부분 지급·성공 처리 금지. 이는 사용자 출구가 막힐 수 있는 실제 제한이다. 그 제한을 수용할 수 없다면 지급 cap/헤지/보험기금/초과 부채 모델 중 별도 경제 설계가 필요하며 48시간 안전 완료를 약속하지 않는다.

주문 부하로 손익 풀을 이미 예약한 뒤 가격을 바꾸어 더 큰 이익을 지급하지 않는다. action에 고정된 서명 가격의 R을 예약하고 체인이 같은 수학으로 확인한다. final settlement가 DB 실패하면 예약을 유지한다. DB journal unique(operationId, entryType)와 정산 완료 guard로 한번만 반영한다. C 증가/종료/현물 거래가 동시 실행되면 동일 lock을 통해 순서대로 결정한다.

### 5.3 숫자 예시와 위험 경계

M=100,000, P0=75,000에서 최초 Q는 2배 약2.666666…, 5배 약6.666666…, 10배 약13.333333…다. 가격이 +1%면 Long 이익은 각각 약2,000/5,000/10,000이고 Short는 같은 크기 손실이다. 정확한 마지막 wei는 위 floor 규칙으로 판정한다.

- 10배 Long: P=70,000 → E 약33,333, MM 약46,667 → 마진콜. 유예 내74,000 → E 약86,667, MM 약49,333 → 회복.
- 10배 Short: P=80,000 → E 약33,333, MM 약53,333 → 마진콜. 유예 내76,000 → E 약86,667, MM 약50,667 → 회복.
- 청산가의 안내용 연속식은 Long `(Q×P0−C)/(0.95×Q)`, Short `(Q×P0+C)/(1.05×Q)`이며 단위 환산 필요. **실제 판정은 정수 E≤MM**으로만 한다. 0이하 Long 경계는 유효 양수가격에서 위험이 없을 수 있으며 무조건 양수 청산가를 만들어 내지 않는다.

### 5.4 상태 머신과 시각 정본

상태를 한 enum에 뒤섞지 않고 **포지션 lifecycle / 위험 / action 전송**을 분리한다.

| 축 | 상태와 허용 전이 |
|---|---|
| DB position | OPEN_PENDING→OPEN 또는 OPEN_FAILED; OPEN→CLOSING/LIQUIDATING→CLOSED/LIQUIDATED. 확정 revert이면 CLOSING/LIQUIDATING→OPEN. 서명 후 불확실/이벤트 불일치는 REVIEW_REQUIRED overlay로 잠금 유지 |
| risk | HEALTHY→CALL_PENDING→GRACE(체인 call 확정); GRACE→RECOVERY_PENDING→HEALTHY; GRACE→LIQUIDATION_PENDING. PRICE_UNAVAILABLE/POOL_INSUFFICIENT는 원상태를 지우지 않는 사유/overlay |
| action | PREPARED→SIGNED→SUBMITTED→CONFIRMED 또는 REVERTED. 증거 불일치 REVIEW_REQUIRED. raw가 존재하면 시간 초과만으로 ABORTED 불가 |
| chain | NONE→ACTIVE→MARGIN_CALL→ACTIVE 또는 LIQUIDATED; ACTIVE/MARGIN_CALL→CLOSED. TOP_UP은 활성 상태의 C/version 변경 |

제안 grace는 **60초**(정책 승인 필요, 데모용이며 실제 거래소 규칙 아님). 마진콜 tx의 `block.timestamp + grace`를 체인 deadline으로 저장한다. UI는 이 deadline을 표시하고 DB 시각으로 임의 앞당기지 않는다. 청산 tx는 **block.timestamp≥deadline, report.observedAt≥deadline**, 마지막 accepted 표본 이상인 새 적격 가격의 E≤MM을 모두 만족해야 한다. 단순히 유예 전 보고서를 유예 후에 채굴하는 것은 거부한다. 체인과 호스트 시각 preflight를 통과하지 못하면 시험을 중단한다.

청산 issuer도 observedAt≥deadline을 별도로 검사한다. observedAt은 Unix 초로 내림한 값이고 deadline도 초다. 미래2초 허용으로 유예 이후 표본을 앞당기지 않도록 청산에 한해서 **observedAt≤floor(현재 서버 UTC 초)**를 추가하고, chain도 liquidate에서는 observedAt≤block.timestamp를 요구한다. 공통 가격의 과거5초/미래2초 정책과 현물 계약은 그대로이며 이는 새 선물 청산의 추가 보수 조건이다. deadline−1은 거절, deadline은 실제 관측/채굴시각이 모두 도달한 경우에만 허용한다. 시각 불일치나 채굴 지연은 대기/실패로 남기고 임의 timestamp 보정하지 않는다.

회복/추가 담보/정상 종료/청산이 경합하면 expectedVersion과 active operation unique guard를 모두 쓴다. 한쪽 성공 후 다른 action은 stale version으로 거절하며 잠금을 두 번 해제하지 않는다. 사용자는 signed 청산을 취소할 수 없다. 이미 전송된 청산과 뒤늦은 회복 가격의 결과는 **체인에 수락된 보고서 가격**이 정본이지 화면 최신 가격이 아니다.

### 5.5 가격·시장·중복 감시·중단 정책

- 위험 감지는 **commit 후** 가격 이벤트와1초 보조 scheduler에서 같은 `RiskEvaluator`를 호출한다. public WS 전달 성공에 의존하지 않으며 scanner는 최대50개 페이지/짧은 예산으로 처리한다. 동일 position version/mark 식별자는 중복 enqueue를 줄일 뿐, 최종 안전은 DB claim/unique+chain version이다.
- 가격 가용성은 기존 `TradingPriceContext`의 allowed/fresh/generation 정책을 따른다. 실제 공급자 적용은 코드 호환 범위이며 이번 검증은 Synthetic만 한다. 명시 CLOSED, STALE, 연결 상실, 이전 generation은 신규 open/close/call/clear/liquidate의 서명 금지. 과거 가격으로 강제청산하지 않는다.
- 닫힌 시장에서도 현재 유효한 미전송 TOP_UP 의도와 가용 자금 예약은 허용할 수 있지만, 이번 최소안은 **가격이 필요한 체인 위험 해소까지 한 동작으로 확정**하므로 전체 TOP_UP 실행도 적격 가격을 기다린다. 요청만으로 위험이 해소됐다고 표시하지 않는다.
- 장애/시장 마감 중 deadline의 벽시계는 멈추지 않는다. 실행은 멈추고 재개 후 새 적격 가격으로 재판정한다. 재개 즉시 기존 유예가 끝나 있을 수 있고, 장애 중 잠깐 회복했는지는 증명할 수 없다. 추천 의미는 ‘관측된 회복이 없고, 마진콜과 유예 후 판정 시점 모두 위험’이지 **모든 순간 연속 위험의 증명**이 아니다. 중단 후 새 유예 보장을 요구하면 별도 outage/재유예 프로토콜이 필요하다.
- 30초 TTL은 ‘체인 저장이 느려서 가격이 같다’는 보장이 아니다. 발급 시 과거5초/미래2초와 전후 generation을 검사해도 전송 후 가격이 변할 수 있다. 30초 내 고정 서명 가격으로 검증되는 한계를 UI/데모에 표시한다. 기왕 signed한 tx는 시세 장애로 임의 폐기하지 않고 복구한다.
- 같은 operation의 고정 가격/관측 식별자를 중간에 바꾸지 않는다. position별 마지막 accepted observedAt보다 과거 보고서는 거절한다. 보고서의 초 단위 시각이 같은 체결의 전체 순서까지 증명하지는 못하므로, 같은 초의 action은 expectedVersion으로 직렬화하고 backend에는 원래의 밀리초/표본 식별자를 별도 보존한다. 최신 healthy 표본이 확인되면 기존 call epoch를 clear하는 action을 우선하고, active action이 signed면 그 결과 전까지 다른 action을 새로 전송하지 않는다.
- 큰 갭을 자동 clipping하지 않는다. 음수 equity와 D를 기록한다. 가격 폭락/폭등 차단 임계값을 새로 넣어 청산을 숨기지 않는다. 포지션 급증/감시 지연/ETH부족/RPC장애는 관측하되 자동 담보 해제나 nonce 교체를 하지 않는다.

### 5.6 FuturesEngine 계약 — 제안 ABI/서명/권한

신규 `contracts/src/FuturesEngine.sol`, `FuturesReportTypes.sol`, `FuturesMath.sol`을 제안한다. **A에는 MarginVault/토큰 이동/mSEC 발행·소각이 없다.** 기존 PriceOracle의 priceSigner/consumer나 ExchangeVault의 허용 경로를 바꾸지 않는다.

체인에 positionId(bytes32), pseudonymous accountRef, symbolHash, side, Q, P0, C, policyHash, status, version, callEpoch, callStartedAt, deadline, lastObservedAt/price를 저장한다. 개인 email/loginId/raw DB userId는 공개하지 않는다. accountRef도 가명 식별자이지 개인 소유권 증명은 아니다.

제안 동작: `open(report,signature)`, `topUp(report,signature)`, `startMarginCall(report,signature)`, `clearMarginCall(report,signature)`, `close(report,signature)`, `liquidate(report,signature)`. 모든 동작은 전용 executor만, 폐쇄적 role로 호출한다. owner에게 임의 liquidated/잔고 변경 shortcut을 주지 않는다. 배포 후 테스트 중 가격 signer/executor/policy 변경은 금지하는 immutable 모델 권장.

```text
domain: name=TokenizedMarketFutures, version=1, chainId, verifyingContract=FuturesEngine
FuturesReport:
 operationId, positionId, accountRef, symbolHash, action,
 expectedVersion, expectedCallEpoch, side, leverage, quantity, entryPriceE8,
 collateralBefore, collateralAfter, priceE8, observedAt, validUntil,
 policyHash, executor
validUntil = observedAt + 30s; observedAt <= block.timestamp + 2s
```

action마다 필요 없는 필드는0 등 **정규 표현을 강제**하고 기대 상태와 일치해야 한다. OPEN에서 collateralBefore=0, collateralAfter=M, entryPrice=price, Q=floor(M×L×10^8/P0), 허용 L·최소/최대 C·즉시 위험 아님을 검증한다. 청산/종료는 저장 Q/P0/C/side를 사용해 계산하며 보고서가 이를 임의 교체할 수 없다. TOP_UP은 C 증가만, 인출 불가. 같은 positionId 재개설 불가.

가격 nonce는 Ethereum nonce와 다르다. operationId 일회 소비+position version+callEpoch+typed action+domain으로 재사용/다른포지션/다른체인/다른계약 공격을 차단한다. 트랜잭션 revert 시 operation 소비도 revert된다. report 고유성과 DB 성공 멱등성은 별도 관리한다.

청산에서 chain이 직접 검사: 존재/활성→caller→서명/종목/시각/정책→version/epoch→마진콜 존재·deadline 경과→**deadline≤observedAt≤block.timestamp**, lastObservedAt 이상→현재 서명 가격의 E≤MM→terminal 한번만. 체인은 DB margin lock·house funding·‘공급자 실제 가격’·user consent 자체는 검사하지 못한다. 가격 signer와 operator의 결탁 신뢰는 남는다.

이벤트는 `PositionOpened`, `MarginAdded`, `MarginCallStarted`, `MarginCallCleared`, `PositionClosed`, `PositionLiquidated`, `FuturesReportConsumed`를 제안한다. operationId/positionId/version/accountRef/price/observedAt/C/U/R/D/deadline 등 동작별 기대값을 담아 검증한다. 로그만 보고 DB를 믿는 것이 아니라 contract state와 동일 수학을 대조한다.

### 5.7 DB/API 계약 및 이중 사용 방어

**신규 테이블 제안:**

| 테이블 | 필수 계약 |
|---|---|
| futures_policies | hash/version, leverage set/MMR/grace/fees/손실한도 규칙, 배포주소/chainId, immutable |
| futures_positions | UUID/userId/market/side/Q/P0/C/개설L·유효L·lifecycle/risk/version/callEpoch/deadline/확정blockHash·tx 연결 |
| futures_previews | user/action/positionId·expectedVersion/side/M/L/price/provider/generation/원래 관측식별자/input hash/TTL·상태, 단기 ID. 발급 전후와 소비·서명 직전 가용성/generation 재확인, signature 원문 응답 금지 |
| futures_operations | operationId/positionId/userId/action/expectedVersion/idempotency fingerprint/request hash/고정 report·signature/예약C·house/state/reason |
| futures_transactions | op 연결 unique, sender+nonce unique, raw/hash/chainId/contract/status/blockNumber/hash/attempt metadata. 일반 API에 raw/signature 노출 금지 |
| futures_margin_journal | immutable op별 예약/추가/해제/실현 R/D·wallet delta·house delta/정산 근거. unique(op,type), 성공 후 삭제·초기화 금지 |
| futures_house_pool / house_journal | initial capital 및 확정 이전만, balance/reserved, 거래중 자동 리필/임의 reset API 없음 |
| futures_user_guards / risk_observations | user·종목 active position unique 보호, 제한된 위험 표본/마지막가격/lag/claim. 잔고 정본과 분리 |

새 `R/futures-schema.sql` + 명시적 schema initializer를 feature OFF 기본으로 추가한다. 기존 schema init·Google additive migration과 충돌하지 않게 실제 PG에서 반복/부분 기존 schema 검사. **기존 유잔고 DB에 적용하는 migration 실행은 이번 범위 밖**이며 새 fixture에서만 증명한다. futures enabled 상태에서 schema/policy/contract/주소 불일치는 fail-closed.

lock 순서 제안은 futures user guard→position→operation→house pool→사용자 mKRW balance다. prepare와 settle가 동일 순서를 사용하고, RPC 중 DB lock을 잡지 않는다. 관측 단계에서 읽은 ID를 이용하되 settle에서는 정해진 순서로 재검증한다. Faucet quota→global budget→balance 및 현물 Order/tx→balance와 교차할 때 선물 코드가 기존 Order/quota를 역으로 잠그지 않는다. 실제 PG 동시성/lock timeout 테스트가 gate다.

idempotency는 user+fingerprint로 성공/접수 intent를 조회하고 같은키 다른요청은409, replay는 원본 ID/상태를 반환한다. action unique partial index로 position당 비종료 operation1개를 강제한다. 서버 재시작도 이 제약을 사용한다. 요청응답 유실 시 client는 같은키/operation 조회, 새 position 자동생성 금지.

**USER API 제안(기존 API 변경 없이 추가):**

- `POST /api/futures/previews` OPEN은 `{action:"OPEN",side,marginAmount,leverage}`, CLOSE는 `{action:"CLOSE",positionId,expectedVersion}` → 사용자/동작/포지션 버전에 귀속된 previewId/고정가격/Q/예상 MM·R/위험 안내/expiry. 다른 동작의 ID를 소비할 수 없음.
- `POST /api/futures/positions` `{previewId}` + Idempotency-Key →202/positionId/operationId.
- `GET /api/futures/positions[?status&before]`, `GET /api/futures/positions/{id}`, `GET /api/futures/operations/{id}` — 본인만.
- `POST /api/futures/positions/{id}/margin` `{amount,expectedVersion}` + key, `POST .../{id}/close` `{expectedVersion,previewId}` + key. close preview도 같은 서버 발급 경로 사용, client 목표가격 임의 지정 금지.
- `GET /api/futures/summary` — locked collateral, unrealized, realized, badDebt 안내, 가격 신선도/계산 시점. wallet 현물 필드 의미는 유지.
- `GET /api/admin/futures/positions`, `/operations`, `/risk`, `/house`, `/audit` — 관측/대사 이력, raw/key 비노출. 강제 해제/보정/임의 청산 버튼 없음.

금액·가격·수량·ratio는 decimal 문자열, 시각은 UTC ISO8601, version/ID·reasonCode·price freshness·confirmed/pending를 명시한다. 409은 상태/한도/가격 만료, 422은 도메인 입력, 503은 dependency라는 식의 일관된 공개 매핑을 설계하되 원문 RPC 예외는 내보내지 않는다.

### 5.8 실제 raw 전송·Receipt·복구

Futures 전용 sender는 단일 프로세스/전용주소만 허용한다. unresolved SIGNED가 있으면 신규 nonce 서명을 우선 진행하지 않고 그 raw를 복구해 nonce 충돌/구멍을 줄인다. `pending` nonce와 DB sender+nonce unique, 전송 전 signed raw/hash의 REQUIRES_NEW commit, eth_sendRawTransaction 순서다. process/JVM 둘 이상에서 같은 executor 사용은 이번 범위 밖이고 startup/run guard로 금지한다.

각 복구 tick은 SIGNED→hash lookup/동일 raw 재전송, SUBMITTED→receipt/확인 수를 검사한다. **new nonce 재주문/새 raw 재서명으로 응답 유실을 해결하지 않는다.** expired report도 이미 signed한 tx를 우선 확정/실패 확인한다. status0의 canonical receipt가 확정된 뒤에만 기존 action을 REVERTED로 닫고 새 operation/새 보고서로 상태를 재평가할 수 있다.

canonical 검증: receipt의 txHash/from/to·blockNumber/blockHash, 연결 RPC와 저장된 raw의 chainId 기대값을 확인하고, 그 높이의 현재 canonical block hash와 동일해야 하며 요구 confirmation을 충족해야 한다. 계약주소·정확한 event 개수/종류·operationId·positionId·version·가격·U/R/D·정책과 저장값/해당 receipt block 기준 contract state가 맞아야 정산한다. canonical 확인과 DB commit 사이 deep reorg 위험은 남고, Anvil 실험은 공개망 finality 검증을 대체하지 않는다.

성공 시 operation/tx/position/journal/house/userbalance를 한 transaction으로 확정한다. 실패 receipt라면 OPEN action의 C만 unlock, TOP_UP은 추가분만 unlock, CLOSE/LIQUIDATE는 기존 C 그대로·position 열린 상태로 복원하며 house 예약만 해제한다. 그때 즉시 다음 위험 평가를 예약하되 무한 tx 실패 loop는 막는다(시세/버전 변화 또는 bounded backoff 조건). 이벤트/금액 불일치는 REVIEW_REQUIRED, 보정/잠금 해제 금지.

crash window는 prepare 전후, signed commit 전후, RPC 수락 응답 유실, receipt 후 DB commit 전후, WS 공개 전후를 나누어 검증한다. signed commit 결과를 모르면 unique operation으로 먼저 조회하며 raw가 없는 것으로 추정해 새 서명을 만들지 않는다. 서버 재시작 시 prepared lease/보고서 TTL과 signed 상태를 구분하고 동일 op를 이어간다.

### 5.9 기존 대사·감사·UI 경계

자산 대사의 사용자 기대 mKRW는 **Faucet−현물BUY+현물SELL+확정선물 R 합**으로 확장한다. locked는 pending 현물 입력+선물 C+미확정 TOP_UP 등 중복 없는 예약 항목의 합과 비교한다. 각 선물 journal이 검증된 canonical operation에 연결됐는지 확인한다. house H=H0−ΣR, user+house 보존과 D를 별도 표시한다. chain 현물 operator/Vault/tokenSupply 식에는 DB 선물 손익을 넣지 않는다(토큰 이동 없음). futures executor ETH는 Gas 참고 정보다.

`ReserveStore.cut`/fingerprint에 선물 position/op/journal/house를 같은 read-only snapshot으로 포함하고 chain의 FuturesEngine 상태를 explicit block으로 읽는다. old baseline을 갱신하지 않고 새 환경에서 engine/policy identity를 포함한 **새 불변 futures 기준점**을 별도 생성한다. pending/review/cut 변경/과거근거 부족은 MISMATCH 대신 INCONCLUSIVE. 이 확장 없이 기존 자산 대사를 MATCH라고 발표하는 것은 불가다.

기존 거래 감사 API/coverage는 **현물 감사** 의미를 유지한다. 신규 futures operation audit는 DB↔receipt/events/state 양방향, open/close/topup/call/clear/liquidate를 스캔하고 호출/블록/시간 제한과 개별 판정·coverage를 제공한다. 최소 구현은 전용 서비스/ADMIN 읽기 조회로 분리하며 기존 감사의 결과를 전상품 MATCH로 확대하지 않는다. UI에는 현물/선물 감사 범위를 나란히 명시한다.

웹은 별도 선물 탭/패널을 추가한다: 담보·방향·배수·고정수량 preview, 실제 lock/available, entry/mark·PnL·equity/MM·위험·유예 countdown, pending tx/receipt link, 추가담보/전체종료, liquidation 결과와 D. 0수수료/격리손실/house부족/합성/서명가격고정/온체인예치아님을 표시한다. 큰 수치는 문자열 보존, 화면 표시 반올림을 주문값으로 재사용하지 않는다.

개인 `/user/queue/futures`와 새 event type을 서버 allowlist에 추가하고 기존 publisher의 AFTER_COMMIT를 따른다. USER는 본인 REST/WS만, ADMIN은 운영조회만. 계정/로그아웃/지연 응답 epoch 보호, 재접속 REST 동기화, JWT 만료 차단을 기존 방식으로 유지한다. 공개 가격 채널은 unchanged. Android는 계약 문서만 후속 전달, 구현/빌드 완료로 보고하지 않는다.

## 6. Day 1/Day 2 의존·시간·변경 파일·중단 gate

### 6.1 추정의 전제와 신뢰도

아래는 **A안 제한 프로토타입의 낙관적 48시간 경로**다. 직접 작업 약39h, 실행 대기/휴식/수정 여유 약9h에 의존한다. 독립 검토는 별도 에이전트 약3~6h를 필요한 시점에 수행하지만 그것이 구현자의 휴식이나 재검증을 대체하지는 않는다. 통상적인 결함 수정/환경 장애까지 반영하면 A안도 약56~80 직접 작업시간이 더 현실적일 수 있다. 안전한 완수를 보장하는 견적이 아니며 T+24/T+38에서 재예측한다.

| 경과 창 / 직접 작업 추정 | 작업·의존 | 제안 변경 파일/모듈 | 새 테스트·필수 통과 gate / 중단 기준 |
|---|---|---|---|
| D1 T+0~3h / 2h | 설계·경제·A 책임·정책/fixture 승인 확정 | 본 문서/정책 fixture/향후 branch 계획 | Gate0: 결정 D01~09 명시. 온체인예치가 필수면 A 착수 중단 |
| T+3~9 / 5h | 순수 math/정책·가격 경계 | 신규 `B/futures/math/FuturesMath`, policy/types; 신규 contracts math/types | Gate1: Java/Solidity 교차 vectors, 2/5/10·±PnL·5%·1wei·음수·overflow·house 보존. 수학 불일치면 진행 금지 |
| T+9~15 / 5h | engine open/call/clear/topup/close/liquidate | 신규 `contracts/src/FuturesEngine.sol`, test/script | Gate2: Solidity 권한/domain/TTL/replay/version/grace/recovery/duplicate 실제 Foundry 통과. event-only로 대체 금지 |
| T+15~21 / 4h | DB position/op/journal/house 및 short prepare/lock | 신규 `B/futures/{position,operation,margin}`, `R/futures-schema.sql`, init/config | Gate3: 실제 새 PG 동시성·rollback·idempotency·기존 Faucet/현물 available 경합. 과다잠금/돈 생성 발견 시 중단 |
| T+21~24 / 2h | Day1 계약/DB checkpoint·초기 검토 | 신규 gateway/reportissuer, featureOFF | Gate4: 별도 chain-only 신규 Anvil에서 script OPEN/TOP_UP 상태 일치, 별도 DB fixture에서 prepare/house 불변식. 아직 앱→체인→DB 정산 E2E 아님. 지연이면 일정 재보고 |
| D2 T+24~30 / 5h | tx sender/persistence/reconciler·canonical/정산 | 신규 `B/futures/chain`, `FuturesSettlementService` 및 테스트 | Gate5: raw선저장·응답유실·동일 raw복구·이중정산0·revert/review 보존. 충족 못 하면 UI 데모 진행하지 않음 |
| T+30~34 / 3h | event+scheduler 위험/claim·결정적 시나리오 | 신규 `B/futures/risk`, fixture profile/scenario adapter, 기존합성 commit 이벤트 최소 hook | Gate6: CALL→GRACE→회복/청산, 동시 topup/close/liq1회, staleclosed정지. 단순 sleep 의존 테스트 금지 |
| T+34~38 / 3h | 최소 UI·개인 WS·대사/감사 확장 연결 | `W/src/futures.js` 등 새패널, 기존 main/api/ws/security allowlist 최소; `B/reserve` adapter/cut; 신규 futures audit | Gate7: 정상 rest/WS user격리, 기존API불변, journal 반영된 대사·범위표시. 누락되면 감사완료로 표기 금지 |
| T+38~43 / 5h | 실제 PG/Anvil·Chrome 거래/청산·재시작 E2E | 신규 futures E2E/배포fixture harness, 기존 runner guard재사용 | Gate8: §7 필수 real경로 전부 신규실행. 테스트간 fixture재사용/reset 금지. 시간문제 재발 시 guard우회금지 |
| T+43~46 / 3h | 전체 회귀·독립 검토 수정/재검증 | backend/web/contracts 전체 및 검토보고 | Gate9: 새 PASS/FAIL/SKIP와 추가gate, 필수결함0. 기존결과를새실행수로쓰지않음 |
| T+46~48 / 2h | 실제 Chrome 데모·증거/마스터 연결/인수 보고 | 신규 implementation/report, 승인 후 마스터F-ID갱신 | Gate10: 실제 청산 Tx/DB/WS/두범위감사·정밀단위·한계 설명. 미완료 있으면 그대로 미완료 보고 |

engine 작업을 Day1에 두는 이유는 경제 수식/유예가 DB와 컨트랙트에서 일치해야 Day2에 실제 청산 검증을 할 수 있기 때문이다. Day1 순수 엔진만 만들고 Day2 마지막에 Solidity를 시작하면 가장 중요한 복구·E2E가 밀린다. 잠금/회계·chain/core는 각각 완성 시 검토하고, UI만 먼저 ‘완료’하지 않는다.

### 6.2 예정 변경량과 기존 회귀 위험

신규 Java 약15~25개/DB8~9테이블/계약2~3개/UI패널1~2개/신규테스트 다수가 예상되는 **기능 Phase급 변경**이다. 파일 수는 추정이며 코드 생성 목표가 아니다. 대사/보안/WS/초기화/브라우저main의 기존 파일 약8~15개가 영향을 받을 수 있다. 기존 `Order/Trade/PriceQuote/BlockchainTransaction`의 의미나 spot ABI는 변경하지 않는 것을 목표로 한다.

가장 큰 일정 위험은 (1) economics 결정 지연, (2) DB+chain async2원장, (3) 회복/청산/topup 경쟁, (4) 기존 자산 대사 확장, (5) canonical/restart 실증, (6) Chrome handler와 fixture 격리다. 해당 기능을 단순 ‘재사용’이라고0시간 처리하지 않는다. 새 migration이 기존 유저/미커밋 변경에 충돌하면 자동 merge/수정하지 않고 보고한다.

## 7. 필수 검증·실패 재현·데모

### 7.1 결정적 Synthetic 경로

기존 random 엔진을 시드만 바꿔 강제 급락시키지 않는다. `futures-fixture`의 전용 scenario adapter가 버전/hash·가격/offset 목록·cursor를 DB에 저장하고 기존 봉/가격 commit 후 공개 경계를 재사용한다. 일반 public/실provider에서 로딩 불가, 신규 run의 DB/RPC/chain 소유권 guard가 전제다. control은 내부 fixture network/전용 harness만 가능하고 제품 ADMIN 가격설정 API는 만들지 않는다.

새 실제 브라우저 데모는 wall-clock/Anvil timestamp와 맞는 **실제 흐르는60초 유예**를 관측한다. 빠른 단위/Foundry에서는 Clock/시간 이동 가능하나, 기존 환경이나 real Chrome 실증에 그 결과를 섞지 않는다. scenario의 가격은 각 단계에 fresh tick으로 반복 갱신하여 고정가격 hold가 STALE이 되지 않게 한다. 원장이나 체인 상태를 바꾸는 가격이동 cheat와 정상 provider tick을 구분한다.

| 경로 | anchor 이후 가격 순서(예시) | 검증 목적 |
|---|---|---|
| 정상 Long/Short | 75,000→75,750(Long종료), 별도fixture75,000→74,250(Short종료) | L2/5/10 수량·부호·수익률·C잠금/해제 |
| Long 회복 | 75,000→70,000→call확정→유예내74,000→clear확정 | CALL 취소가 아닌 정당 회복 transition |
| Short 회복 | 75,000→80,000→call확정→유예내76,000 | Short의 반대방향 위험 |
| Long 청산 | 75,000→70,000을 유예 넘게 fresh유지→새report | 실제 deadline 후 Solidity 검증·자동 tx |
| Short 청산 | 75,000→80,000을 동일 방식 유지 | Long만 성공하고 전체완료 처리 금지 |
| 갭/악성부채 | Long60,000 / Short90,000 | E<0, user손실C한도, D·house 보존 |

fixtures의 시작시각을 UTC anchor로 기록하고, 재시작 cursor가 뒤로 돌아가 새 회복/청산을 만들지 않아야 한다. old capture/실시장 데이터 모방이 아닌 명백한 합성 경로다.

### 7.2 테스트 수준별 필수 매트릭스

아래는 모두 **계획/NOT_RUN**이다. 새 선물 테스트가 이미 PASS했다고 해석하지 않는다.

| ID | 필수 시나리오 | 순수/H2·Mock | 실제 신규 PostgreSQL/Anvil | 실제 Chrome→신규 서버/DB/체인 |
|---|---|---|---|---|
| F01 | Long/Short×2/5/10 OPEN→정상CLOSE | 전조합 계산/단위 | 6조합 C/R/house/journal·receipt | 대표 Long/Short 개설/종료 |
| F02 | E/MM 경계·1wei·signedfloor·수량18자리 | cross-language vector/fuzz | DB/ABI 왕복 | 문자열 입력/표시 |
| F03 | Long급락/Short급등 call→grace | 상태전이/Clock | chain call/deadline실증 | 경고/countdown개인WS |
| F04 | 유예내 가격회복·topup 회복 | 예상버전/회복 | call epoch clear·C추가/실패추가분해제 | 대표회복·추가담보 |
| F05 | 유예후 양방향 실제강제청산 | 조기/회복/liquidated중복revert, deadline전observedAt의미만료보고서를deadline후채굴해도거절, deadline−1/동률/이후·미래경계 | 실제Anvil의해당부정경계+canonical receipt/event/DB R/D/lock0 | 양방향 실제 청산 데모 |
| F06 | 중복 worker/재시작/close·topup·liq 경합 | staleversion/claim | 실제 PG barrier 경쟁+chain operation단1회 | 중복클릭/늦은응답 fixture별도 |
| F07 | 잔고부족/spot lock/Faucet 동시성 | validation | 실제 PG 자금보존/일일예산유지 | 부족 안내, 자동재주문0 |
| F08 | CLOSED/STALE/미래/generation·연결상실 | fakeprovider/Clock | 서명/tx0, lock의안전한처리 | warningfixture, 실KIWOOM아님 |
| F09 | 서명/issuer key/domain/chain/action/pos/version/TTL/replay | 실제 crypto/Foundry 부정 | 잘못된서명 estimate/revert, user정산0 | 노출/자동반복금지 |
| F10 | RPC수락후응답유실·signedcommit crash | fakeRPC정확window | 격리 proxy실Anvil·같은raw/hash/nonce 복구 | pending→확정 REST/WS |
| F11 | 실패receipt/잘못된event/canonicalhash·확인수 | parser/Mock | 실제status0와미채굴/지연·block대조 | 실패와검토필요구분 |
| F12 | receipt후DB실패·이중정산·별도JVM재시작 | transactionrollback | 실제PG 실패주입·새JVM 복구·journal1회 | 확정후재접속동기화 |
| F13 | 갭negativeequity·house부족 | R/D보존 property | 실제Long/Short갭, pool예약경합·부족tx0 | badDebt/blocked안내 |
| F14 | 선물 포함자산대사·현물/선물감사coverage | 수식/cut변화fixture | 정상MATCH·불일치·INCONCLUSIVE/양방향scan | ADMIN scope·이력 최소화면 |
| F15 | USER/ADMIN/다른user/Google-only/nullloginId/JWT만료WS | auth/role/latecallback | REST/STOMP실네트워크 사용자격리 | desktop/mobile fixture+실로그인 |
| F16 | 현물BUY/SELL·Google·전량·Faucet·조건부·관측 회귀 | 전체backend/web/Foundry | 필요한기존E2E를새fixture에서 | 기존flow fixture와실연결구분 |

실제 proxy 응답유실/프로세스 재시작은 **신규 futures run의 정확한 label/namespace만** 대상으로 한다. DB update로 결과를 가짜 성공시키거나 기존 fixture reset은 하지 않는다. canonical block 분기 자체를 실제로 재현하지 못하면 hash 검증 단위/조회 실증과 reorg Mock을 분리하고 공개망 reorg 미검증을 명시한다.

실행 gate 예시: 새 `FUTURES_E2E_TESTS=true`/전용run manifest·동적DB/RPC 주소/새테스트 전용키·chainId·engine주소를 검증한 다음에만 신규 `FuturesPostgresAnvilE2ETest`, `FuturesRestartPostgresTest`를 실행하도록 설계한다. 지금 그런 클래스/flag가 있다고 주장하지 않는다. 기존 `SyntheticMarketPostgresAnvilE2ETest`에는 고정포트 설정이 있어 그대로 실행하면 안 되고, 최근 Phase A 동적 fixture/guard 패턴을 재사용해야 한다.

### 7.3 관측 증거·데모·중단 조건

각 case의 HEAD/image/fixture/sourceHash/policyHash/seed·pathHash, 입력/정확 Q/C/U/R/D, riskprice/observedAt, call epoch/deadline, opId/txHash/blockNumber/hash/receipt event, DB journal/house/balance/lock, 개인WS eventId·시각, auditcoverage를 sanitized summary에 연결한다. raw/signature/JWT/키/DBdump는 Git 제외 private에만 보존한다.

데모는 ①local 또는 기존Google 내부userId로 로그인 ②Faucet정책 유지 ③Long·Short 정상거래 ④risk경고/유예회복 ⑤양방향자동청산 ⑥receipt·lock0·house/D·감사 ⑦restart복구 증거 순이다. 신규 외부 Google 로그인은 필요 없고 기존local/검증된fixture사용자를 사용할 수 있다. 화면 fixture만 성공한 경우 chain 데모 완료라고 쓰지 않는다.

필수 중단: 자금보존/과다잠금/서명우회/이중정산·청산 발견, wrongDB/RPC/owner/source/clockguard, raw복구 불명확, 필수회귀 실패, 비밀노출, 검토 필수결함 미해소. T+24의 core gate 또는 T+38의 실제chaingate가 지연되면 남은작업/재현/새견적을 보고한다. 스코프 변경은 재승인 전 실행하지 않는다. T+48 도달 시 PASS/FAIL/BLOCKED/NOT_RUN과 미완료를 공개한다.

## 8. 48시간 밖의 범위와 기존 일정 보존

### 8.1 구조적 축소와 영향

- **수용 제안:** A상태검증형·단일종목·격리담보·배수3종·전체종료·수수료0·funding0·전용executor·최소UI·합성경로. 축소해도 사용자 요구의 양방향/레버리지/MM5%/마진콜/유예/실청산/복구/E2E는 유지한다.
- **수용 불가 축소:** Solidity 조건검증 제거, 가짜event/receipt, DB수동청산결과, H2를PG로보고, 브라우저fixture를실chain으로보고, 회귀/독립검토생략. 모두 완료기준 위반이다.
- **48시간 권장 제외:** B실escrow·실token정산, 원안 batchLiquidate·분산worker·수천포지션·스트레스, 시장간hedge/insurance·ADL·완전지급보증, funding/만기·금리, 부분청산/교차증거금, 실제KIWOOM선물인수, Sepolia/AWS/Android/논문연구. 원계획에서 삭제하는 뜻이 아니다.
- 비영수수료·사용자당다중포지션·기존유잔고migration·단일spotexecutor공유·public배포까지 요구하면 추가설계/테스트가 필요하다. 출시보안/감사나 실금융서비스 적합성은48시간결과로보증할수없다.

### 8.2 마스터에 제안할 연결 ID — 아직 원문 상태표 변경하지 않음

| 제안 ID | 연결/의존 | 완료 상태 갱신 조건 |
|---|---|---|
| FU-01 경제/격리담보·포지션 | X09/X10, T09/T12, U01~04 | 승인정책·수학·실PG 자산보존 |
| FU-02 위험/유예·결정적합성 | X11, M07/M03 | 실제call·회복·두방향위험 |
| FU-03 FuturesEngine·서명/청산 | X11, T02/T04/T05 | 실제Anvil state검증/receipt/동일raw복구 |
| FU-04 원장/대사·선물operation감사 | T10/T11, FU01~03 | wallet/house/journal/cut/coverage실증 |
| FU-05 UI/REST/개인WS·브라우저데모 | M09/M10, U04, FU01~04 | desktop/mobilefixture와실Chromechain을분리기록 |
| FU-06 기존제품회귀·최종인수편입 | O10, PhaseA~D, FU01~05 | 최신통합제품 새A회귀·향후B/C/D의범위재확정 |

X09~11을 삭제하거나 바로완료로 덮지 않고 이ID로 부분충족/미충족을 연결한다. X12연구/논문·U05~12인증후속·M06NXT·X05nonce·X07~08Sepolia·O07Android·O04~05운영부하/배포는 원래상태대로 유지한다. 학교의 Phase3 인정은 A/B의 온체인 책임 차이를 확인받아 별도로 기록한다.

이전 ‘주말개발→월요일KRX PhaseB’는 이번48시간요청과 충돌할 수 있다. **특정요일 종속을 제거한 새개발목표가 과거 B/C를 자동취소하거나통과시키지는 않는다.** 선택안은 (a)기존동결현물버전B를그대로 별도인수 후선물버전재인수, (b)FU기능동결·새A회귀 뒤새KRX환경으로B재예약이다. 최신전체버전을목표로하면(b)를추천하지만 사용자선택전일정을수정하지않는다. 선물은Synthetic에서만통과하므로 실제시장 기반선물까지 인수하려면 추후명시승인이필요하다.

### 8.3 작업·환경 보존

현재 작업 트리의 README/마스터 변경, implementation-log NXT 구간과 미추적 NXT 애프터 보고서를 그대로 둔다. 이번에는 **본 신규 설계 문서만 작성**하며 기존 마스터/README/log에 추가 수정을 하지 않는다. 구현 승인 후 원본루트에서 기능브랜치 생성 방식을 정하되 기존worktree제거/강제checkout/stash/reset을 자동수행하지 않는다.

신규run에서만 DB/Anvil/키·계약·baseline을 만들고 기존Google/KRX/playground의image/mount/볼륨/manifest/sourceHash/private증거는 변경하지 않는다. 신규envsecret은 사용자입력/전용생성, 구키 자동복사 금지. Git에 runtime/chainstate/credential/실행raw자료를 넣지 않는다. commit/merge는48시간기능승인에 자동포함되는것으로추정하지않고 별도사용자경계확인 후검토한다.

## 9. 사용자 결정과 승인 요청

| ID | 확정할 결정 | 추천/미확정 |
|---|---|---|
| D01 | A상태검증형 vs B실온체인증거금, 학교Phase3 인정범위 | 48시간은A추천. A의DB담보/지급신뢰한계·MarginVault미충족을수용하는지확인 |
| D02 | 선물제품축소 | 단일mSEC·격리·2/5/10·사용자당1활성·전체종료·추가담보/인출불가·funding/만기없음 승인필요 |
| D03 | MMR·위험경계·유예·시장장애 | MMR현재notional5%, E≤MM, grace60초, chain시간정본·장애중벽시계경과·재개새가격판정. 연속위험증명아님을수용할지 |
| D04 | 실제손실·house·지급불가 | 손실C한도, D추가청구없음, 유한house·부족시종료차단/노출유지. 데모초기자본1천만 제안. 손익상한/자동mint는추가하지않음 |
| D05 | 수수료·반올림/수량·preview 가격편차 | 신규선물0bps·signedfloor/MMceil·문자열. preview허용편차는명시승인값필요(제안0:가격바뀌면재확인); 현물수수료유지 |
| D06 | 전용executor/서명키·새서명/새chain경로 | 전용로컬운영실행주소와가격서명키분리, 현물주소와다르게검증. 사용자지갑도입아님 |
| D07 | 새격리환경·fixture/fault·테스트·최종Git경계 | 새SyntheticPG/Anvil·로컬proxy/서버재시작만; 외부API0. commit/통합은별도확인 |
| D08 | 48시간gate·미달시처리 | T+24/38재예측, 필수미달이면미완료로보고하고일정/범위재승인. 검증생략금지 |
| D09 | 기존B/C일정과마스터갱신 | 선물동결→A재회귀→최신B재예약 추천. 기존미완료기능/보존환경/실시장인수상태유지 |

이 결정들이 확정되기 전에는 코드를 수정하거나 계약/DB/환경을 생성하지 않는다. 사용자가 설계를 확정한 뒤 별도 구현 승인을 주면 T0를 기록하고 착수한다.

## 10. 이번 조사·문서 검증·독립 검토

- 읽기 조사: Git branch/HEAD/status, 공통/검토 지침, 현재 마스터/기획·보관모델·최신통합 보고서, Solidity Oracle/Vault/report, Java 잔고/잠금/서명/전송/복구/정산/합성/대사/감사/WS, 기존 테스트 소스.
- 이번 실행: **제품 테스트0, Docker/DB/RPC조작0, 외부API/유료AI0, Git commit/merge/push0**. 코드·설정·기존문서·사용자변경·runtime 수정 없음. 문서 안의 새 테스트 수나 시간은 계획이지 결과가 아니다.
- 독립 검토: `review_futures_48h_plan`이 공통/검토 지침·현재 코드·본 문서를 읽기 전용으로 대조했다. 최초 필수 지적1건은 ‘유예 전 표본을 유예 후 채굴해도 통과할 수 있음’이었다. issuer/chain의 `observedAt≥deadline`과 청산 전용 미래 표본 차단·초단위 경계, 실제 Anvil 부정/성공 테스트 계획으로 보완했다. preview의 action/position/version/provider/generation 바인딩도 명시했다. 첫 재검토 결과는 **발견된 필수 수정 없음**이다.
- 검토자는 테스트·DB/Docker/RPC·외부 호출·파일 수정을 하지 않았으며 HWP 본문 독립 해독/레이아웃은 검증하지 않았다. 설계 검토는 실제 구현·보안 감사·48시간 완료 보증·학교 승인을 대신하지 않는다. 최종 결과 기록 후 구현으로 진행하지 않고 사용자 검토에서 멈춘다.
