# DApp 사용자 승인·자산 통제 강화 분석

> 2026-10-21 / **읽기 전용 코드 조사와 설계 제안. 구현 승인·구현 완료 아님.**
> 원본 `melon-init@f9353c33244565b30fb130dfb6159eab441a3523`. 이번에는 테스트/서버/RPC/DB/Docker/유료 AI를 실행하지 않았다. 새 보고서만 작성하며 기존 사용자 파일·UI/AI worktree·평가 상태를 변경하지 않는다.

## 1. 결론과 이번 의사결정

**우선 추천: B를 선물의 신규 opt-in 경로에 먼저 적용한다.** 사용자 독립 지갑의 EIP-712 서명을 새 FuturesEngine에서 직접 검증하고, 운영자는 Gas를 내는 relayer로 유지한다. OPEN·TOP_UP·CLOSE에 사용자 승인을 강제하고, 이미 승인된 포지션의 규칙 기반 마진콜·회복·청산만 서버가 자동 제출하도록 한다. 기존 현물과 기존 선물 계약/환경은 그대로 보존한다.

새 보장은 **“이 Engine의 이 사용자 소유 포지션은 사용자 서명 없이는 개설·임의 증액·임의 정상 종료할 수 없다”**다. 그러나 B는 DB 잔고 조작·지급 거부·가격 서명자 공모까지 방지하지 않는다. 전체 프로젝트의 모든 현물/선물 거래가 탈중앙화됐다는 설명은 불가하다. 실제 ERC-20 통제까지 목표라면 C와 특권 제거가 추가로 필요하다.

현재 계약의 온체인 위험 검증 자체는 이미 실질적이다. 문제는 블록체인을 쓰지 않았다는 것이 아니라 **사용자 승인과 자산 보관은 여전히 서버 신뢰 경계**라는 것이다.

과거 [자산 보관 모델 확정](asset-custody-final-check.md)은 A 유지/직접 서명 제외였다. 이번 요청은 그 결정을 변경할지 검토하는 설계 단계로 해석한다. 새로운 구현 승인 전에는 기존 정책·계약·일정을 변경하지 않는다.

## 2. 조사 기준과 보존 경계

- [최소 Gradle 조사](../deployment/runtime/ai-presentation-worktree/claude-docs/windows-gradle-native-socket-investigation-2026-10-21.md), [AI 웹 최종 Gate](../deployment/runtime/ai-presentation-worktree/claude-docs/ai-web-final-gate-2026-10-21.md)를 확인했다. Windows native socket 근본원인 미확정, 전체 AI 웹 Gate BLOCKED를 그대로 유지한다.
- 저장소 밖 Work의 `output/tms-architecture-2026-10-20.md`를 실제 열람했다. 기준 HEAD는 같으며, 아래 결론은 원본 현행 Solidity/Java 코드로 다시 확인했다. 보고서를 저장소에 무단 복사하지 않았다.
- 원본 시작 dirty9개(기존 AI 테스트2/문서2 수정, 미추적5), UI/AI/발표 통합 worktree 모두 보존. 원본과 발표 통합 worktree의 sourceHash는 다른 범위이므로 이 보고서에 AI worktree의 `88df...`를 원본 실행 해시로 붙이지 않는다.
- 코드상 권한과 배포 스크립트의 의도된 설정을 분석했으며 **현재 배포된 주소·실제 키 보유자·권한 값을 RPC로 확인한 것은 아니다**.

## 3. 현재 실제 신뢰·권한 구조

### 3.1 거래별 책임

| 구분 | 현물 BUY/SELL | 선물 OPEN/TOP_UP/CLOSE | 자동 마진콜/회복/청산 |
|---|---|---|---|
| 요청자 | JWT 사용자 REST | JWT 사용자 REST | 서버 Worker/RiskEvaluator |
| Ethereum tx 서명자 | operator 키 | 별도 futures executor 키 | 같은 futures executor 키 |
| 별도 업무 서명 | 서버 PriceReportSigner | 서버 futures priceSigner | 같은 futures priceSigner |
| 컨트랙트 인증 대상 | report.executor=msg.sender와 Oracle priceSigner | immutable executor와 immutable priceSigner | 동일 |
| 온체인 사용자 식별 | 이벤트 `user`는 caller, 현재 서비스에서는 operator | bytes32 accountRef, Ethereum 사용자 owner 필드 없음 | 같은 accountRef의 포지션 |
| 자산·결과 귀속 | ERC-20은 operator/Vault, 사용자 몫은 DB Order.userId | 포지션은 Engine 저장소, 담보·손익은 DB 사용자/house | Engine 상태 전이 후 DB journal 정산 |
| 사용자 독립 서명 | 없음 | 없음 | 없음(자동화 자체와 사용자 승인 부재는 별개) |

### 3.2 Solidity가 이미 강제하는 것 / 강제하지 않는 것

| 실제 위치 | 강제 규칙 | 빠진 보장·특권 |
|---|---|---|
| [`ExchangeVault.sol:73`](../contracts/src/ExchangeVault.sol#L73), `sell:91`, `_validateReport:115` | 방향, 입력>0, 최소출력, executor=caller, Oracle 보고서 소비; BUY transferFrom(caller)·mint(caller), SELL burn(caller)·transfer(caller), 재진입 방어 | DB userId/사용자 서명 없음. 사용자 대신 operator의 토큰을 거래. buy/sell 자체는 onlyOwner가 아니라 유효한 caller-bound 가격 서명이 필요 |
| [`PriceOracle.sol:72`](../contracts/src/PriceOracle.sol#L72) | authorizedConsumer, symbol, 가격>0, observedAt+30초 만료, 미래2초, quoteId 일회성, EIP712+ECDSA.recover(priceSigner) | 가격 공급자 승인이지 사용자 주문 승인 아님. PriceReport에 사용자 wallet/beneficiary 없음 |
| [`PriceOracle.sol:104`](../contracts/src/PriceOracle.sol#L104), `110`, `67` | onlyOwner의 signer/consumer 교체와 가격 update | owner가 priceSigner를 바꿀 수 있음. updatePrice만으로는 signed BUY/SELL을 대체하지 못함. consumer 변경만으로 Vault 자산이 임의 출금되는 것도 아님 |
| [`ExchangeVault.sol:109`](../contracts/src/ExchangeVault.sol#L109) | owner 수수료 변경, 상한10% | 직접 owner 출금 함수는 없음. 가격/최소출력과 별개로 정책 변경 특권은 존재 |
| [`SamsungPriceTrackingToken.sol:19`](../contracts/src/SamsungPriceTrackingToken.sol#L19), `30`, `37`, `42` | onlyMinter mint/burn, owner의 minter 교체 | **burn(from)는 allowance 불필요. owner가 minter를 자신/악성 계약으로 바꾸면 임의 주소 소각·발행 가능**. C에서 주소만 분리해도 이 특권을 유지하면 완전한 사용자 통제 아님 |
| [`MockKRW.sol:19`](../contracts/src/MockKRW.sol#L19) | caller에게 faucet mint, owner는 지급량 변경 | 공개 반복 mint 가능. DB Faucet quota와 다름. 실화폐/유한 공급/실제 담보 가치 보장 없음 |
| [`FuturesEngine.sol:57`](../contracts/src/FuturesEngine.sol#L57), `_execute:66`, `_validate:113` | executor와 서버 서명, operation 재사용 차단, position version/epoch·상태·수량·가격·담보 보고 일치, 5% 위험 조건, 블록시간 기준60초 유예와 유예 이후 새 관측가격, 조건 지속 시 청산 | **owner wallet·사용자 서명·실제 담보 잔고 검증 없음**. executor의 collateral 주장을 신뢰. open/topUp/close는 사용자 동의 없이도 두 서버 권한으로 실행 가능 |
| [`FuturesEngine.sol:13`](../contracts/src/FuturesEngine.sol#L13), constructor:49 | executor/signer/policy/symbol immutable, executor≠signer | owner 교체 함수·upgrade 함수는 이 소스에 없음. 서로 다른 주소라는 사실이 서로 독립된 운영 주체라는 뜻은 아님 |

현재 선물의 `open(r,s)`, `topUp(r,s)`, `close(r,s)`는 모두 같은 서버 보고서 검증만 거친다. 사용자 검증을 새 wrapper에만 추가하고 이 함수를 새 보호 포지션에도 그대로 열어 두면 **우회 경로**가 된다. `startMarginCall/clearMarginCall/liquidate`도 같은 공통 dispatcher를 쓰지만 자동화 허용 범위와 사용자 명령을 분리해야 한다.

금융 검증의 구체적 내용은 [`FuturesMath.sol:20~44`](../contracts/src/FuturesMath.sol#L20)에 있다. quantity는 collateral×leverage×1e8/entryPrice의 정수 내림, 미실현 손익은 Long/Short 방향을 반영하고 음수 나머지는 아래쪽으로 보정한다. Equity=collateral+PnL, 유지증거금은 현재 명목가치의5%를 올림하며 Equity≤유지증거금에서 위험하다. 실현 손실은 collateral로 제한하고 초과분을 badDebt로 구분한다. 이 산식·2/5/10배·유예 규칙은 B에서도 변경하지 않는다.

### 3.3 실제 서버 호출과 DB 신뢰

- 현물: [`OrderController:51/60`](../backend/src/main/java/com/pricetrack/exchange/order/OrderController.java#L51) → `OrderService.buy/sell:54/72` → `OnchainOrderService:33/38/48` → [`OnchainOrderPreparationService:65`](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderPreparationService.java#L65)의 quote-user 검증/입력잠금. [`PriceReportIssuer:46/64`](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportIssuer.java#L46)는 executor를 operator로 정해 가격 보고서를 서명한다.
- [`BlockchainTransactionSender:64`](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionSender.java#L64)는 operator Credentials로 raw를 서명하고, SIGNED/nonce/hash 저장 후 전송한다. 사용자 주문 서명과 Ethereum tx 서명은 현재 동일하지도, 둘 다 존재하지도 않는다. 지금은 후자와 서버 가격 서명만 있다.
- 선물: [`FuturesController:34`](../backend/src/main/java/com/pricetrack/exchange/futures/api/FuturesController.java#L34)의 preview/Idempotency-Key 확정 → `FuturesService.consume/margin` → `FuturesIntentStore/FuturesLedger` → [`FuturesExecution:22`](../backend/src/main/java/com/pricetrack/exchange/futures/runtime/FuturesExecution.java#L22)의 서버 report 서명 → [`FuturesTransactionSender:36`](../backend/src/main/java/com/pricetrack/exchange/futures/chain/FuturesTransactionSender.java#L36). `FuturesReport.from:16`은 DB operation.userId와 position.userId를 확인하지만 이것이 컨트랙트의 소유권 증명은 아니다.
- [`FuturesLedger:229`](../backend/src/main/java/com/pricetrack/exchange/futures/ledger/FuturesLedger.java#L229)의 accountRef는 UUID hash를 DB user guard에 저장한 값이다. 사용자 지갑에서 도출하거나 서명 검증한 값이 아니다.
- [`JwtTokenProvider:37`](../backend/src/main/java/com/pricetrack/exchange/auth/JwtTokenProvider.java#L37)는 내부 userId를 subject로 발급한다. [`User:51`](../backend/src/main/java/com/pricetrack/exchange/user/User.java#L51)/`schema.sql:12`에 wallet_address는 있으나 검색 범위의 제품 웹·Java에는 지갑 연결/소유증명/typed-data 주문 서명 경로가 없다. 컬럼 존재만으로 사용자가 키를 통제하지 않는다.
- [`WalletService:35`](../backend/src/main/java/com/pricetrack/exchange/wallet/WalletService.java#L35)와 `UserBalance`는 DB 장부다. [`FuturesLedger:55/109`](../backend/src/main/java/com/pricetrack/exchange/futures/ledger/FuturesLedger.java#L55)는 증거금 예약, 정산·house·journal을 DB 트랜잭션으로 변경한다. FuturesEngine은 ERC-20을 보관하거나 지급하지 않는다.
- [`FuturesReconciler:25`](../backend/src/main/java/com/pricetrack/exchange/futures/chain/FuturesReconciler.java#L25), [`FuturesReceiptVerifier:17/30`](../backend/src/main/java/com/pricetrack/exchange/futures/chain/FuturesReceiptVerifier.java#L17)는 tx/canonical block/calldata/정확한 event/state를 검증한다. 현물 `OnchainSettlementService:64`와 `tradeaudit/AuditValidator:62~80`도 대응을 검증하지만 **사용자 동의의 부재를 감사 MATCH가 보완하지는 않는다**.

일반 운영자 tx 키 **하나만**으로 모든 보고서를 위조할 수 있다고 설명해서는 안 된다. 유효한 미소비 서버 가격 보고서도 필요하다. 그러나 현 구조에서 사용자의 비밀은 온체인 조건에 전혀 없으므로 executor와 priceSigner를 통제하는 주체는 사용자 동의 없이 그 계약 규칙 안의 거래를 만들 수 있다. DB 관리자 권한은 별도로 장부를 직접 변경할 수 있다.

**DB만으로 구현 가능한 효과:** 로그인/소유권 API 검사, 잔고 잠금, 손익/유예 산식, 멱등성, Worker, 내부 감사 로그. **현재 체인이 추가하는 효과:** 계약에 고정된 전이·서명·시간·일회성 규칙과 공유 가능한 Receipt/Event. B는 여기에 서버가 단독 생성할 수 없는 사용자 승인 증거를 추가한다. 로컬 Anvil 운영자는 체인 자체를 통제하므로 공개망의 독립적 확정성과도 구분해야 한다.

## 4. A/B/C 비교

| 항목 | A 현재 통합지갑+JWT | B 사용자 EIP-712+계약 검증+릴레이 | C B+사용자별 실제 ERC-20 |
|---|---|---|---|
| 현물 | 현행 유지 | 새 Vault/Router의 **모든 보호 실행 경로**에 intent 검증 필요. operator 토큰으로 실행하면 자산은 여전히 operator 것 | relayer와 asset owner 분리. BUY transferFrom(owner), mint(owner); SELL owner의 허용된 소각/지급. allowance/별도 approve UI 필요 |
| 선물 | 서버 증거금 주장·보고서 검증 | owner-bound position 및 OPEN/TOP_UP/CLOSE 사용자 승인. 위험 자동화는 사전 동의한 규칙으로 유지 | 사용자 토큰 현물 보유만으로 선물 담보 보장 안 됨. 별도 MarginVault 예치·인출·house 유동성/정산 설계 필요 |
| 실제 사용자 키 | 없음 | 브라우저 외부 지갑이 보관. 서버는 공개주소·서명만 수신 | 동일+사용자 Gas/approve 또는 별도 제한된 위임 |
| 서버 단독 임의 거래 거부 | 불가(사용자 관점) | 새 보호 계약·position 범위에서 가능. 기존 경로/DB 신뢰는 남음 | 무단 자산 이동도 방어 가능하나 token minter/owner 특권과 과도한 allowance 제거 필요 |
| 정산/복구 | 현재 기준 | 새 intent hash/owner/nonce/ABI/event와 같은 raw 복구·감사 연결 | 이에 더해 사용자 주소별 잔고·입출금·외부 transfer, 기존 operator 집계 대사 재설계 |
| 발표 일정 위험 | 기능 변화 없음, 목표 미충족 | 선물 한정 중~높음; 현물까지 동시에 하면 높음 | 매우 높음. 보관 모델·Faucet·pool·감사 전반 변경 |

현물 B를 단순히 “새 Router에서 서명 확인 후 기존 Vault 호출”로 구현하면 Vault의 caller는 Router가 된다. Router가 보유/승인한 자산, 수령 토큰 전달, 환불·잔여 자산 처리까지 설계해야 한다. 더구나 기존 Vault 직접 호출을 막지 않으면 operator 보유 자산의 기존 무서명 거래는 계속 가능하다. **Router 추가만으로 기존 자산 전체가 사용자 통제로 바뀌지 않는다.** 새 고정 Vault 배포/issuer executor 변경/기존 ABI와 감사 영향 때문에 선물보다 최소 범위가 크다.

C에서는 기존 mSEC owner가 minter를 바꿔 임의 burn할 수 있는 길을 닫아야 한다. 새 독립 토큰의 immutable 발행·소각 정책 또는 권한이 제한된 minter 구조가 필요하고, 광범위한 operator/admin emergency 이동권을 남기면 그 한계를 공시해야 한다. 기존 토큰의 소유권 포기·재배포를 이번 설계만으로 수행하지 않는다. 무제한 mKRW faucet도 실제 금융 담보로 설명하지 않는다.

## 5. 추천 B: 새 서명 선물 경로의 계약

### 5.1 지갑·계정과 권한

1. 기존 로컬/Google 로그인과 userId/JWT는 유지. 지갑 연결은 **JWT+일회 challenge+wallet 소유 서명**으로 별도 처리한다. origin/domain/URI/chainId/nonce/만료/브라우저 세션에 바인딩하고 타계정 중복 연결·계정 전환·지연 callback을 차단한다. [ERC-4361](https://eips.ethereum.org/EIPS/eip-4361)은 연결 인증의 근거이며 거래 승인 자체가 아니다.
2. 최초 범위는 독립 **EOA 지갑** 권장. 서버 생성·서버 키 보관 주소는 제외. Anvil 기본 공개 개발키는 테스트 검증용일 뿐 독립 사용자 통제 시연의 근거가 아니다. 실제 브라우저 데모는 서버에 키를 전달하지 않는 사용자 테스트 지갑을 사용한다. 스마트계약 지갑은 [ERC-1271](https://eips.ethereum.org/EIPS/eip-1271) 검증을 별도 지원해야 하므로 초기 지원 여부를 명시한다.
3. 새 Engine에는 `position.owner`를 OPEN 서명 복구 주소로 불변 저장. 후속 TOP_UP/CLOSE는 저장 owner와 비교한다. owner/admin 교체·무서명 OPEN·임의 CLOSE·범용 execute/delegatecall·upgrade backdoor를 두지 않는다.
4. 새 accountRef를 owner+symbol의 domain-separated hash로 도출하는 방식 권장. DB uid 연결은 여전히 오프체인 관계이며 계약이 실제 사람/Google 계정을 증명하는 것은 아니다. DB userId/주소 unique와 활성 포지션1개 정책을 유지하고, 체인은 owner/symbol 단위 중복도 막는다. 기존 UUID accountRef·기존 포지션은 강제 마이그레이션하지 않는다.

### 5.2 두 서명과 nonce는 서로 다르다

| 항목 | 가격 보고서 | 사용자 거래 Intent | 릴레이 tx |
|---|---|---|---|
| 서명 키 | 서버 priceSigner | 사용자 지갑 | futures executor |
| 뜻 | 이 가격·시점·상태 전이에 대한 서버 근거 | 이 사용자가 이 조건의 행동에 동의 | 이 raw를 체인에 제출하고 Gas 지불 |
| 재사용 방어 | operationId/version/epoch/30초 보고서 | owner별 intent nonce/intent hash/deadline | Ethereum account nonce + 같은 raw/hash 복구 |

[EIP-712](https://eips.ethereum.org/EIPS/eip-712)는 형식과 domain 분리를 제공하지만 자체 replay 방지는 제공하지 않으므로 별도 nonce 소비가 필요하다. 신규 domain은 예를 들어 `TMSFuturesUserIntent`, version1, 실제 chainId, 새 Engine 주소로 고정한다. 기존 서버 `TokenizedMarketFutures` 가격 서명 domain과 구분한다.

사용자가 서명할 최소 금융 계약: `owner, accountRef, symbolHash, positionId, intentId, action, side, leverage, quantity, expectedVersion, expectedCallEpoch, collateralBefore, collateralAfter, entryPriceE8, executionPriceE8, policyHash, relayer, nonce, deadline`. 이 필드와 서버 report의 대응을 계약에서 모두 확인해야 한다. 현재 preview의 고정 가격 재확인 정책을 유지하며 최초안은 **정확히 승인한 가격/수량**에만 실행한다. 허용 가격 범위/슬리피지 새 정책은 임의 도입하지 않는다.

intentId/positionId는 서명 전 서버가 확정하고 서명 후 다른 operation으로 바꾸지 않는다. 기존 DB prepare→Worker 흐름에서 뒤늦게 생기는 식별자와 연결하려면 `FuturesIntentStore`에 서명 대기 draft를 추가해야 한다. 사용자 서명은 기존 server report 자체를 대체하지 않는다. report observedAt+30초/미래2초와 user deadline을 모두 만족해야 한다. 지갑 조작 중 만료되면 새 preview/새 명시 서명이며 자동 재승인·TTL 연장 금지다.

nonce는 Engine 저장소에서 원자적으로 소비하고 revert 시 소비도 되돌린다. 성공한 intent의 replay는 체인이 거부하되 HTTP 동일 Idempotency-Key는 기존 operation을 반환해야 한다. 사용자 취소를 원하면 사용자 직접 서명/tx로 nonce 무효화하는 경로가 필요하다. relayer만 알고 있는 미제출 서명을 사용자가 즉시 강제 취소할 수 있다고 설명하지 않는다. 최초안은 짧은 deadline과 서명 대기 취소/이미 SIGNED인 거래 취소 불가를 명확히 구분한다.

### 5.3 사용자 행동과 자동화 경계

| action | 새 사용자 서명 | 계약의 필수 조건 |
|---|---|---|
| OPEN | 필수 | signer=owner, 새 position/nonce, 승인 경제 조건 일치, 기존 위험 검사 |
| TOP_UP | 필수 | 저장 owner, version/epoch/담보 변경량 일치, 기존 상태/금액 검사 |
| CLOSE | 필수 | 저장 owner, 수량/가격/버전 일치, 기존 정산 규칙 |
| START_CALL/CLEAR_CALL | 매회 불필요 | 사용자 서명으로 개설된 포지션만, 기존 risk/회복·버전·서버 보고서 검증 |
| LIQUIDATE | 매회 불필요 | OPEN에서 고정 policyHash로 자동청산 사전 동의. 기존 블록60초·새 관측·계속 위험 검사. CLOSE로 우회 불가 |

사용자 승인 없는 **임의 거래** 거부와 승인된 위험 조건의 **자동청산**은 모순이 아니다. 다만 priceSigner가 악의적인 가격에 서명하면 위험 판정 입력을 왜곡할 수 있다. 이 추천은 가격의 현실 진실성, Oracle 독립성, DB 담보 존재나 house 지급능력을 보증하지 않는다. 이런 목표에는 별도 가격 신뢰·MarginVault 설계가 필요하다.

## 6. 수정 예상 범위와 기존 기능 보호

| 영역 | 주요 기존 파일 / 신규 후보 | 필요한 변화 |
|---|---|---|
| 계약 | `contracts/src/FuturesEngine.sol`, `FuturesReportTypes.sol` 기반 신규 `UserAuthorizedFuturesEngine.sol`, `FuturesUserIntentTypes.sol` | owner·nonce·서명 검증, 모든 사용자 action dispatcher 강제. 기존 계약은 보존. 새 Engine 코드/ABI/address identity |
| 지갑 인증 | `user/User.java`, `auth/*`, schema 기반 신규 wallet-link service/table | 소유증명/일회 challenge, unique 연결, 재인증·감사. Google-only 계정도 기존 credential 원칙 유지, 비밀번호가 없다는 이유로 인증 생략 금지 |
| 요청/저장 | `FuturesController`, `FuturesService`, `FuturesIntentStore`, `FuturesSchema` | typed data draft와 서명 제출, intent immutable 저장, 멱등 키·owner/version 바인딩. 사용자 서명 원문은 private 저장, 공개 WS/AI DTO에서 제외 |
| 전송/복구 | `FuturesExecution`, `FuturesReportCodec`, `FuturesTransactionStore`, `FuturesTransactionSender`, `FuturesChainIdentity` | 새 calldata/owner/intent hash 저장·검증. SIGNED 선저장·동일 raw/nonce/hash 복구 유지. recovery에서 새 사용자 서명 생성 금지 |
| 정산/감사 | `FuturesReceiptVerifier`, `FuturesReconciler`, `FuturesAuditVerifier/Service`, `FuturesAuditAccounting`, `FuturesLedger` | 새 owner/intent-consumed event와 block state 대조, 사용자 승인과 가격 근거 모두 검증. 현재 verifier는 정확히2logs·11words를 요구하므로 ABI/event 추가 시 반드시 version별 decoder 필요 |
| 웹 | `tools/websocket-test-client/src/futures-panel.js`, `api.js`, `main.js` 주변 + 신규 wallet adapter | wallet connect/network 확인, typed-data 표시·사용자 서명·거부/만료/계정변경, 기존 draft/중복응답 방어 |
| 환경/테스트 | 새 배포 script/harness와 새 fixture | 기존 Engine/집계 baseline/manifest 변경 금지. version별 routing, 새 baseline·새 감사 coverage |

기존 Solidity 주소는 불변 배포물이므로 소스를 고치는 것만으로 배포 계약의 권한이 바뀌지 않는다. 기존 선물 계약에는 in-place upgrade가 없어 새 계약/신규 fixture가 필요하다. 기존 active position을 새 Engine에 admin 이관하는 방식은 승인 우회가 되기 쉬워 최초 범위에서 제외한다. 기존 현물과 기존 선물 경로는 legacy로 명확히 표시하고, 새 보호 position은 old Engine에서 발생한 event로 정산하지 않는다.

현물에 B를 확대하면 `ExchangeVault/PriceReportTypes`, `PriceReportIssuer`, `ContractGateway/EventParser`, `OnchainOrderPreparationService`, sender/persistence, `OnchainSettlementService`, `AuditValidator`, reserve·조건부 주문도 변경 대상이다. 조건부 주문은 매번 사용자 팝업을 띄울 수 없으므로 **가격조건·금액·만료·횟수 제한의 사용자 서명 위임**을 별도로 설계해야 한다. 기존 unsigned conditional을 새 보호 계약에 자동 허용하면 안 된다.

C는 여기에 지갑별 Faucet/approve/외부 transfer와 수탁 해제, portfolio 정본, 주소별 자산 대사, 손익 풀 자본 예치 및 실제 정산을 추가한다. 기존 DB 담보를 그대로 두고 ERC-20 잔고 표시만 추가한 것을 C 선물 escrow로 보고하지 않는다.

## 7. 구현 순서·필수 테스트 계획 (이번에는 미실행)

1. **설계 확정:** B 선물 한정·신규 opt-in, EOA, 고정 가격/30초, legacy 격리, 자동청산 사전동의, DB 담보 한계에 사용자 동의. D01 경제·수탁 정책을 C로 자동 변경하지 않음.
2. **Solidity 단독 Gate:** 새 Engine과 typed data·owner·nonce 테스트. 기존 `FuturesEngine.t.sol`의 `testAuthoritySignatureAndReplayAndStaleVersion`, `testExpiryDomainAndReportMutation`, `testWrongChainAndKeyAndSymbolRejected`, `testBothSidesGraceAndLiquidation`는 서버 서명/위험 검증의 재사용 근거지 사용자 서명 테스트가 아님.
3. **Java 무과금 Gate:** 동일 typed-data digest golden vector, 지갑 challenge/권한/DB 원자적 멱등성/서명 저장/만료·동시성·WebSocket 사용자 격리. 현존 Windows Gradle socket 장애 해결 없이 통과를 약속하지 않음.
4. **새 PG/Anvil Gate:** 사용자 서명+서버 보고서+operator raw의 연결, canonical Receipt/Event/owner/nonce/장부/journal·감사, 같은 raw 두 JVM 복구, 실제60초 자동청산. 기존 환경/실패 fixture 재사용 없음.
5. **독립 검토와 Chrome Gate:** 독립 사용자 지갑2개, 계정·chain 변경/취소/지연서명/중복탭, 모바일 지갑 접근성, 발표1366px. 기존 현물/Google/전량/Faucet/조건부·AI 조회 회귀. 기존 branch PASS 재사용 금지.

| 시나리오 | 새 B 계약에서 요구할 판정 / 실제 가능성 |
|---|---|
| 정상 서명 OPEN/TOP_UP/CLOSE | 서버 가격 서명과 사용자 서명이 모두 유효하면 성공, DB 귀속 일치 |
| operator만 서명, 사용자 서명 없음 | 새 Engine의 모든 OPEN/TOP_UP/CLOSE 진입점 revert. legacy 계약까지 거부된다고 주장하지 않음 |
| 가격/수량/담보/방향/action 변조 | 사용자 intent와 report/state 불일치로 revert. 승인한 가격 자체의 시장 진실성 검증은 별개 |
| 다른 사용자 서명 | 저장 position.owner와 불일치 revert. 단 OPEN owner를 다른 주소로 바꾼 정상 서명은 그 주소의 별개 포지션이므로 '타인 거래'로 재귀속 불가 검증 |
| 동일 서명 재사용/동시 릴레이 | 한 번만 nonce 소비·최대1 operation 확정, 동일 HTTP key는 기존 결과 반환 |
| 만료/다른 chainId/다른 계약 주소 | domain/deadline 검증 revert. 체인 fork가 동일 chainId인 경우까지 domain만으로 막는다고 설명하지 않음 |
| 사용자 서명 없이 정상 자동청산 | 서명 개설된 포지션, 적격 위험·실제60초·새 report이면 성공; 임의 정상종료는 거부 |
| 사용자 서명 실패/체인 revert/응답 유실 | unsigned만 거절·해제, SIGNED/SUBMITTED 잠금 유지·동일raw복구, REVIEW_REQUIRED 강제정산 금지 |
| owner/admin 우회 시도 | 새 Engine setter/legacy overload/dispatcher/임의호출 모두 검사. C는 minter교체·무승인 burn·과도한 allowance 악용도 별도 필수 |
| 감사/자산 대사 | owner·intent와 contract identity·canonical event까지 일치해야 MATCH, coverage 불완전은 INCONCLUSIVE. B의 담보는 DB이며 온체인 예치 MATCH 아님 |

Foundry 단위 테스트와 실제 PG/Anvil의 유예 wall-time, Chrome fixture와 독립 키를 쓰는 실제 지갑 브라우저를 구분해 기록한다. 이번 분석의 테스트 판정은 전부 **NOT_RUN**이며 기존 테스트 소스를 읽은 사실은 실행 PASS가 아니다.

## 8. 일정·발표 가능 범위와 결정 대기

거친 구현 추정(실측 공수/완료 약속 아님): B 선물 한정 계약·테스트6~10시간, 지갑연결/서버/DB8~14시간, 웹4~8시간, 실제 복구/감사·Chrome/회귀·검토8~16시간 → **26~48시간 + 환경 장애 대응**. UI/AI worktree 미통합 및 현재 Gradle 장애 때문에 발표 전 보장할 수 없다. B 현물까지 동시에 하거나 C까지 하면 별도 수일 이상과 새 보관/정산 검토가 필요하다.

발표 전 현실적인 상한은 **신규 격리 환경의 B 선물 한 경로를 끝까지 증명**하는 것이다. 정상 사용자 서명 OPEN → 무서명/변조 CLOSE 거부 → 기존 규칙의 마진콜/60초/청산 → 서명·체인·DB 감사 장면이 적절하다. 계약 단위 테스트만 끝났다면 '권한 분리 프로토타입'으로만 발표하고 전체 웹 구현 완료로 표현하지 않는다. 환경/안전 Gate를 못 넘으면 현재 하이브리드 모델의 한계를 명확히 설명하는 기존 데모를 유지한다.

후속: 현물 서명/조건부 위임, 사용자 직접 제출·nonce 취소/키 교체·ERC1271, C의 token 권한 축소와 MarginVault/실제 자금 예치·손익풀·대사, Oracle 신뢰 분리/공개망. 기존 KRX/NXT/인증 확장/AI 최종 Gate 및 마스터 계획 미완료 항목을 삭제하거나 이 작업으로 대체하지 않는다.

**사용자에게 필요한 결정은 우선 하나:** “B를 신규 선물 opt-in 경로부터 구현하고, 현물·실제 토큰 자기보관은 후속으로 분리”할지 여부다. 승인 시 위 보장/한계와 독립 키·실제 PG/Anvil·Chrome 검증이 필수다. 이번 단계에서 구현·배포·데이터 변경을 시작하지 않는다.

## 9. 보존·검토 결과

이 보고서만 원본에 새로 추가했다. 기존 `implementation-log.md`는 사용자 dirty를 섞지 않기 위해 수정하지 않았다. 실행 설정·.env·credential·계약·Java/웹 코드·테스트·ledger/STOP/claim은 변경하지 않았다. 최종 Git 확인에서 기존 dirty9개에 이 보고서1개만 추가됐고, 원본/UI/AI 사용자 파일 및 평가 증거 **156개 SHA-256 변경0**을 확인했다. `git diff --check` exit0, 기존 파일에 대한 LF/CRLF 경고는 있었으나 변환 작업은 하지 않았다.

코드 실행 없는 정적 조사이며, 별도 독립 검토는 이번에 수행하지 않았다(공통 지침상 분석·문서 작업에는 생략 가능). 구현 승인이 내려지면 금융/권한/복구 변경의 별도 검토를 반드시 수행해야 한다. 이 보고서는 보안 감사 완료를 의미하지 않는다.
