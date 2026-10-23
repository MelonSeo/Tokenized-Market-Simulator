# C4c-2c 계획 — signed transaction · Receipt · 전체 rules 필드 · 자산 projection 연결

작성: 2026-10-23. **설계만 작성. 신규 verifier 구현·JUnit·DB/RPC 실행 NOT_RUN.** 실제 C controller/rules/math, C4a reducer와 새 ABI/EIP-712/signed-envelope 소스를 읽고 수립했다. signed-envelope 단위는 총괄의 독립 검토 상태를 확인한 후 재사용한다. C4b PG Gate는 harness 장애로 BLOCKED이며 그 기록을 바꾸지 않는다. 순수 verifier 구현은 PG와 독립적이지만 실제 scanner/재시작 인수는 별도 PG 검증이 필요하다.

## 1. 먼저 고정할 신뢰 경계

현재 있는 것은 call/event ABI 문법, C typed digest/EOA 서명 복구, raw signed envelope 검증, 사전검증 입력을 받는 회계 reducer다. 각 부분의 성공을 합친다는 이유만으로 canonical chain 상태·코드 identity·정확한 transaction 직전 상태가 저절로 증명되지 않는다.

이번 최소 단위는 **`PREVALIDATED_DEPLOYMENT_PRESTATE_ONLY`**다. caller가 제공한 배포 조건·tx 직전 전체 rules/owner/code 상태를 전제로 원본 signed tx, calldata, Receipt/log의 내부 대응과 금융식을 검사한다. RPC를 호출하거나 블록의 cryptographic inclusion을 증명하지 않는다. 결과는 전체 감사 MATCH가 아니며 `completeCoverage=false`를 유지한다.

특히 다음을 금지한다.

- public constructor로 만들 수 있는 `CSignedTransactionVerifier.Proof`나 `CEip712Verifier.VerifiedSignature` record를 단순히 받아 진짜 검증 완료로 신뢰하지 않는다. **원본 raw와 예상 hash에서 실제 verifier를 내부 호출**하고 서명도 실제 원본 bytes로 다시 검사한다.
- C4a `Position`은 Vault lock의 축약 projection이다. q/entry/side/leverage/accountRef/lastObservedAt과 terminal 시 rules에 남는 collateral을 복원하는 자료가 아니다. 전체 rules prestate가 없으면 임의 0/현재 DTO로 채우지 않는다.
- signed tx의 sender 복구만으로 `owner.code.length==0`을 증명하지 않는다. EIP-7702 위임 등으로 키를 가진 주소에도 코드가 있을 수 있다. 실제 controller가 검사하는 시점의 **코드 없음 관측**은 별도 prerequisite다.
- parent-block `eth_getCode` 또는 end-block getter를 중간 transaction 직전 값으로 바꾸어 표현하지 않는다. 동일 블록 앞선 tx의 변화가 있을 수 있다. 아직 그 시점의 정확한 증거를 확보하지 못했다면 INCONCLUSIVE다.
- caller가 준 blockHash/timestamp/Receipt 일치만으로 fresh canonical RPC 검증이라고 표시하지 않는다.

## 2. 최소 신규 타입과 API

아래는 제안 ABI이며 아직 Java 구현이 아니다. 새 `custody.proof` 파일에 분리하고 기존 계약/회계 상태를 수정하지 않는다.

```java
record CDeploymentTerms(
    CCustodyModels.Deployment deployment,
    String supportedSymbolHash, String policyHash,
    String tokenCodeHash, String vaultCodeHash,
    String controllerCodeHash, String rulesCodeHash,
    String rulesExecutor, String vaultController,
    BigInteger reportTtl, BigInteger graceSeconds, BigInteger maxUserTtl,
    String intentDomainName, String intentDomainVersion,
    String reportDomainName, String reportDomainVersion) {}

record PrestatePoint(String parentBlockHash, String blockHash,
    BigInteger blockNumber, int nextTransactionIndex, String provenance) {}
record FullRulesPosition(String accountRef, BigInteger side, BigInteger leverage,
    BigInteger quantity, BigInteger entryPriceE8, BigInteger collateral,
    RulesStatus status, BigInteger version, BigInteger callEpoch,
    BigInteger deadline, BigInteger lastObservedAt) {}
// RulesStatus must include NONE; C4a accounting Status has no NONE.
record OwnerState(String owner, BigInteger nonce, String positionOwner,
    String activePositionForAccount, Optional<CodeObservation> ownerCode) {}
record FullRulesPrestate(PrestatePoint point, FullRulesPosition position,
    OwnerState owner, boolean ruleOperationUsed, boolean vaultOperationUsed) {}

record ReceiptLog(String transactionHash, String blockHash,
    BigInteger blockNumber, int transactionIndex, int logIndex,
    boolean removed, CAbiEventModels.Log raw) {}
record ReceiptEnvelope(String transactionHash, String blockHash,
    BigInteger blockNumber, int transactionIndex,
    String from, String to, int status, List<ReceiptLog> logs) {}
record BlockContext(CCustodyModels.Block block, BigInteger timestamp,
    String provenance) {}

record Input(String rawSignedTransaction, String expectedTransactionHash,
    CDeploymentTerms terms, FullRulesPrestate prestate,
    BlockContext block, ReceiptEnvelope receipt,
    CCustodyModels.State accountingBefore) {}
Result verify(Input input);
```

`CodeObservation`는 대상 주소, 해당 prestate point와 코드 내용 또는 정확한 codeHash, 출처를 명시한다. 누락/다른 point는 불충분이다. 단순 `isEoa=true` 기본값을 넣지 않는다. 실제 historical RPC/trace 검증은 이 단위 외부의 전제라는 표시를 결과에도 보존한다. constructor/getter 연결은 새 immutable C 계약과 일치해야 하며 C4a Deployment만으로 없는 codeHash·symbol/정책 조건을 만들어내지 않는다.

`Result`는 `CONSISTENT_IN_PREVALIDATED_SCOPE`, `CONTRADICTION_IN_SCOPE`, `INCONCLUSIVE` 중 하나와 안전한 reason code 목록, scope, `completeCoverage=false`를 반환한다. 일관된 경우만 검증된 `Bundle`, `accountingAfter`, 다음 tx에 사용할 **계산된 expected full rules poststate**를 반환한다. 계산된 poststate를 RPC에서 읽은 값으로 이름 붙이지 않는다.

불충분한 전제가 있으면 `verified Bundle`을 만들지 않는다. 완전한 입력 안의 구체적 모순은 후속 감사의 **MISMATCH 후보 근거**가 될 수 있지만 이 pure 단계가 전체 chain MISMATCH/MATCH를 선언하지 않는다. 원본 서명/raw/calldata는 결과 `toString`/보고서/이유 코드에 출력하지 않는다.

## 3. envelope와 Receipt의 첫 연결

1. 입력 수·raw/calldata/log/data 크기와 전체 작업 한도를 먼저 확인한다. 지원 envelope는 검토 완료된 legacy EIP-155/type 2뿐이다. type 1/4, contract creation 및 알 수 없는 타입은 미지원 INCONCLUSIVE이며 다른 타입으로 재해석하지 않는다.
2. `CSignedTransactionVerifier.verify(raw,expectedHash,terms.chainId)`를 실제 호출한다. raw의 keccak hash·chainId·low-s signature·복구 sender를 얻는다. 트랜잭션을 보내거나 nonce를 소비하지 않는다.
3. raw hash = 예상 hash = Receipt txHash = 각 log txHash, 복구 sender = Receipt.from, raw.to = Receipt.to를 대조한다. controller/Vault/token 중 해당 call의 정확한 주소가 아니면 거절한다. 지원 C 함수는 nonpayable이므로 value=0이어야 한다.
4. Receipt의 status는 정확한 0/1, block number/hash/index는 BlockContext 및 각 log metadata와 같아야 한다. removed log, 중복/역행 logIndex, 누락 metadata를 허용하지 않는다. 다른 tx/block의 log를 가져와 합치지 않는다.
5. `CAbiCodec`로 전체 calldata를 decode하고 원본 exact 재인코딩을 확인한다. 각 log는 실제 emitter 주소에서 기대할 수 있는 `CAbiEventCodec` 범주로 decode한다. 알려진 topic이라도 다른 C 계약 주소가 내보낸 log이면 모순이다. 알 수 없는 emitter/topic은 무시하지 않고 INCONCLUSIVE 또는 명시적 불일치로 보고한다.
6. status=0이면 로그는 없어야 하며 자산·nonce·position을 변경하지 않는다. **revert 원인을 TTL 또는 승인 실패라고 추정하지 않는다.** status=0 거래에 유효한 사용자 승인/가격이 있었다고 주장하지도 않는다. 지원되는 reverted call의 제한된 accounting observation만 후속 reducer에 넣고, enum으로 안전하게 표현할 수 없는 calldata는 별도 미지원으로 남긴다.

Receipt 객체와 block metadata가 서로 일치해도 실제 canonical inclusion 증명은 없다. `C_RECEIPT_HASH_CONFLICT`, `C_RECEIPT_SENDER_CONFLICT`, `C_RECEIPT_BLOCK_CONFLICT`, `C_LOG_REMOVED`, `C_LOG_ORDER`, `C_TX_VALUE`, `C_UNSUPPORTED_ENVELOPE` 등의 코드를 사용한다.

## 4. 성공 사용자 action의 검증 — 실제 controller와 rules를 함께 따름

### 4.1 승인과 사용자 범위

- direct selector: tx sender=Intent.owner; owner는 0이 아니며 제공된 정확한 시점의 코드 없음 관측이 필요하다. 별도 user signature가 없는 것이 정상이다.
- relayed selector: tx sender=고정 relayer, Intent.relayer=같은 주소; C `verifyIntent`를 실제 호출해 owner 복구. direct도 Intent.relayer 필드는 같은 고정 relayer 값이며 tx.from과 혼동하지 않는다.
- owner nonce가 prestate와 C4a accounting nonce에 일치해야 한다. issuedAt<=block timestamp, deadline>issuedAt, deadline-issuedAt<=120, now<=deadline. nonce+1/version+1의 uint256 overflow도 거절한다.
- accountRef는 실제 C `keccak256(abi.encode(ACCOUNT_DOMAIN,owner,symbolHash))`로 다시 계산한다. symbol/policy는 배포 terms에 일치한다. 이 account hash 계산은 현재 CEip verifier에 없으므로 최소 helper와 Solidity 고정 golden을 추가 검증해야 한다.
- Intent/Report의 operationId/positionId/action/accountRef/symbol/policy/version/callEpoch/side/leverage/collateral before-after를 전부 대조한다. 빈 operation/position, 이전 소비 operation, owner 바꾸기, terminal position 재사용을 거절한다.
- priceMin/Max는 양수·순서·상한 `<1e20`, quantityMin/Max는 양수·순서·상한 `<1e30`. Report.price/q는 해당 범위 안이어야 한다.
- OPEN: Intent.entry=0, Report.entry=Report.price, before C=0, expected version/epoch=0, 실제 q=`FuturesMath.quantity(Cafter,L,price)`.
- TOP_UP/CLOSE: Intent.entry=기존 entry, quantityMin=quantityMax=기존 q, 실제 Report의 entry/q/side/leverage/before C가 전체 prestate와 같아야 한다. TOP_UP delta>0, CLOSE C 불변.

### 4.2 가격 서명·신선도와 금융 수학

- `CEip712Verifier.verifyReport`를 실제 호출한다. Report domain은 **새 rules 주소**, executor는 **C controller**이며 고정 Oracle signer에 일치해야 한다. 사용자 Intent domain과 섞지 않는다.
- Report.validUntil=observedAt+30, observedAt+30의 uint256 overflow 차단, observedAt<=now+2, now<=validUntil, 기존 position의 lastObservedAt 이상을 확인한다. 미래 timestamp 덧셈도 Solidity와 같은 안전한 범위에서 처리한다.
- **직접 경로의 이 검사는 서버 5초/provider-generation 검사와 다르다.** pure verifier가 서버 실행 전 시장 gate를 확인했다고 쓰지 않는다. 저장된 relay revision이 있다면 최종 Report와 별도 provenance 비교 대상이지 owner direct의 필수 FK가 아니다.
- 금액/q `<1e30`, 가격 `<1e20`, side 0/1, 신규 OPEN leverage 2/5/10과 기존 leverage 불변을 확인한다. signed 손익의 int256 범위를 검증한다.
- `OperationApplied`의 **action/version/priceE8/collateral/unrealized/realized/badDebt/callEpoch/deadline 전체**를 계산 결과와 비교한다. C4a가 생략한 price/unrealized를 누락하지 않는다.
- `FuturesReportConsumed`의 operation/observedAt/validUntil은 실제 calldata와 같아야 한다. controller의 두 digest/owner/operation/position/action/사용 nonce/direct flag 역시 selector와 실제 검증 결과에 일치해야 한다.

### 4.3 상태 전이

| action | 전체 prestate 조건 | 정확한 poststate/로그 조건 |
|---|---|---|
| OPEN | NONE, owner/account 활성 position 없음, 미지급0·OPEN pause=false, Vault 미사용 position | ACTIVE/version1/epoch0/deadline0, q/entry/C 고정, ownerNonce+1, free→locked |
| TOP_UP | ACTIVE 또는 MARGIN_CALL, exact version/epoch/q/entry/C와 lock owner | C 증액, version+1, q/entry 불변; call 중 위험 해소면 ACTIVE/deadline0, 아니면 기존 call 유지 |
| CLOSE | ACTIVE 또는 MARGIN_CALL, exact owner/version/epoch/entry/q/C | CLOSED/version+1, rules는 terminal collateral 수치를 유지, Vault lock0·자산 정산·Claims; activePosition clear |
| START_CALL | ACTIVE이며 E<=MM | MARGIN_CALL/version+1/epoch+1/deadline=now+60 |
| CLEAR_CALL | MARGIN_CALL이며 E>MM | ACTIVE/version+1/epoch 유지/deadline0 |
| LIQUIDATE | MARGIN_CALL, now>=deadline, **새 Report observedAt>=deadline 및 <=now**, 여전히 E<=MM | LIQUIDATED/version+1, active clear, Vault terminal 정산; 사용자 nonce 소비 없음 |

risk selector는 지정 relayer/keeper만 호출한다. START/CLEAR/LIQ를 사용자 일반 CLOSE로 혼합하지 않는다. 사용자 cancel은 risk 권한/60초 조건을 제거하지 않는다. risk는 rules의 operation을 소비하지만 Vault의 usedOperations는 lock/top-up/settlement 경로에서만 소비된다는 실제 차이를 유지한다.

## 5. 회계와 full event 연결 — 중복 금융 구현을 피함

Receipt의 모든 full event를 먼저 확보하고 위 승인/Report/rules 검사를 통과한 후 C4a 타입으로 **명시적 손실 없는 매핑**한다. `OperationApplied.priceE8/unrealized`는 full verifier에서 확인한 뒤 reducer의 축약 RuleApplied로 넘긴다. 부가 필드를 확인하지 않고 버리는 방식은 금지한다.

Vault의 CollateralLocked/PositionSettled/ClaimCreated/ClaimPaid와 controller 승인 이벤트는 전체 emitter·순서·개수로 매핑하고 C4a reducer를 호출한다. **free/locked/house/FIFO/paidProfit/claimId 계산을 verifier 안에서 두 번째로 구현하지 않는다.** reducer가 기존 accounting state에서 실제 예상 Vault 이벤트를 계산하여 exact 대조하므로 double accounting을 피할 수 있다.

full prestate의 owner/nonce/status/version/epoch/deadline/lock amount와 accountingBefore가 같은 tx 직전 상태를 가리키는지도 대조한다. 서로 다른 시점의 true 값들을 섞지 않는다. 두 단계 중 하나라도 실패하면 원래 immutable State는 그대로이고 verified Bundle/after-state를 반환하지 않는다.

같은 block의 여러 거래는 실제 transactionIndex/logIndex 순서로, 바로 앞 성공에서 나온 expected poststate와 accountingAfter를 다음 입력의 전제로 연결해야 한다. parent block getter를 매 tx 직전 값으로 반복 사용하지 않는다. 직접 owner 거래는 server raw 저장 행이 없어도 실제 raw bytes를 RPC/fixture evidence에서 확보해 검증할 수 있지만, 원본 raw를 아직 얻지 못했다면 만들어내지 않는다.

## 6. 첫 구현에서 지원/보류할 범위

우선 성공 OPEN/TOP_UP/CLOSE, risk 3종과 앞선 full receipt 연결을 구현한다. 그 다음 같은 unit 내 가능한 범위의 Vault deposit/withdraw/fundHouse/process, nonce cancel 및 OPEN pause를 실제 caller/calldata/이벤트와 reducer에 연결한다. 이들은 사용자 가격 Report가 없는 것이 정상이다.

- direct deposit/withdraw/fundHouse의 owner/caller 및 token Transfer를 실제 calldata와 검증한다. approve와 deposit은 서로 다른 동작이다.
- process는 누구나 호출 가능하며 event가 없을 수 있다. 무변경 성공과 상환을 구별한다. nonce cancel은 제출이 아니라 status1+정확 이벤트로 확인한다.
- token의 approve, 일반 wallet↔wallet 전송, constructor mint/treasury 분배는 C4a가 전체 wallet/supply/allowance를 모델링하지 않는다. ABI를 해석할 수 있어도 자산 전체 검증 완료가 아니다. 후속 범위가 정해지기 전엔 `C_ACCOUNTING_UNSUPPORTED_TOKEN_CALL` INCONCLUSIVE로 남긴다. 가짜 REVERTED bundle로 바꾸어 cursor를 넘기지 않는다.
- 유효한 `transfer(Vault,0)` 또는 0/self wallet transfer의 Transfer event는 Vault 자산 변화가 없는 별도 no-op/token 범위일 수 있다. 양수 DonationCall로 조작하거나 가짜 ProcessClaimCall에 넣지 않는다. raw/token 증거 및 coverage에 보존하고, C4a가 아직 표현하지 못하면 명시 미지원으로 유지한다. 0 금액을 건너뛴 뒤 전체 coverage 완료를 선언하지 않는다.
- Vault 직접 donation은 현재 reducer의 DonationCall 범위 안에서만 처리하며 정식 deposit/house와 구분한다. transferFrom의 allowance/prestate까지 확인하지 않은 owner 승인 주장은 하지 않는다.
- inherited `EIP712DomainChanged()`는 ABI 문법은 지원하지만 현재 immutable 계약에서 정상 변경으로 기대하지 않는다. 성공 receipt에 나오면 배포/code 전제와 상충하거나 scope 밖이므로 무시하지 않는다.
- 알 수 없는 selector/type/topic, 누락 raw/Receipt/prestate/code evidence, moving/unknown canonical cut, 지원 범위 초과는 INCONCLUSIVE다. 미래 전체 scanner가 이 거래를 건너뛴 채 complete=true를 선언하면 안 된다.

지원하지 않는 금융 동작을 억지로 통과시키기 위한 calldata/원본 로그 변경은 없다. Receipt status=0 원인 분석이나 nonce 경합의 최종 canonical 결과는 별도 실제 체인 Gate가 필요하다.

## 7. 최소 재사용 파일과 별도 검증

다음 최소 Java 구현을 그대로 참조하거나 작은 adapter로 연결한다.

- `custody.abi.CAbiModels/CAbiCodec`: exact calldata 문법.
- `custody.abi.CAbiEventModels/CAbiEventCodec`: full event 문법.
- `custody.proof.CEip712Verifier`: C typed digest·Oracle/owner 서명 복구.
- `custody.proof.CSignedTransactionVerifier`: raw hash/chain/sender; **최종 독립 검토 완료 후** 사용.
- `custody.projection.CCustodyReducer`: 회계/FIFO/원자 event 대응.
- `futures.math.FuturesMath`: 기존 순수 Java 수학만 직접 재사용 후보. 다른 B ledger/service/schema/Receipt verifier는 가져오지 않는다.

실제 Java 수학은 Solidity와 같이 q floor, 음수 PnL floor, 유지증거금 ceil, E<=MM, realized=max(PnL,-C), badDebt=max(-E,0)을 구현한다. JavaDoc의 E18 표기는 표시 관례이며 본 검증은 C 최소단위 정수 자체를 다룬다. 재사용 파일을 C4c runner의 sourceHash에 포함하고 **C의 경계·golden/fuzz 테스트**를 다시 실행한다. B 수학 PASS나 C Foundry PASS를 이번 Java verifier PASS로 자동 승계하지 않는다.

accountRef의 ACCOUNT_DOMAIN/hash 계산 및 full state 전이는 새 코드이므로 Solidity golden 또는 실제 보존한 신규 C Foundry fixture와 독립 비교가 필요하다. 기존 B accountRef/domain helper를 같은 이름이라는 이유로 복사하지 않는다.

## 8. 필수 테스트와 완료 조건

먼저 offline signed fixture·실제 local cast/Foundry golden을 사용하고, 실제 서비스/DB/RPC는 호출하지 않는다. 시험 키는 공개 local fixture 전용이며 private 원본 사용자 키·JWT/서명·이전 평가 기록을 읽지 않는다.

1. direct/relay 정상 Long/Short 2/5/10 OPEN→TOP_UP→CLOSE, 각 full event와 expected poststate·회계 대응.
2. 실제 raw hash/Receipt hash/from/to/block/index/log metadata 변경, value>0, unsupported raw type, removed/중복/누락/역행 로그 거절.
3. owner 코드 관측 누락/다른 point, 전체 position q/entry/lastObservedAt 누락, 축약 accounting state만 제공 시 INCONCLUSIVE. public Proof record를 위조해도 raw 검증을 우회하지 못함.
4. 사용자 서명 없음/타 owner/high-s/다른 chain/controller/B domain·type, Oracle signer/rules domain/executor, accountRef/symbol/policy 변경 차단.
5. Intent 120초 및 Report 30초 독립 경계, 미래 2초, observedAt 역행, 사용자 서명 후 5초 이상 새 Report 정상. 서버 5초 검사 완료라는 표현 없음.
6. 승인 가격·수량 범위, OPEN q 산식, TOP_UP/CLOSE 기존 entry/q/version/epoch/C, operation·position 재사용 차단; 수치 UINT/int extrema/rounding·1wei.
7. `OperationApplied` 각 필드를 **하나씩** 바꿔 실패 확인. price/unrealized/realized/badDebt/epoch/deadline이 모두 검사됨을 증명. ReportConsumed/controller 두 digest/direct/nonce/owner도 각각 변경.
8. START/CLEAR/TOP_UP 회복, 59초/60초, LIQ의 새 Report observedAt>=deadline·<=now, 위험 지속/회복, cancel 후 risk는 유효. 이는 offline timestamp 검사이지 실제 벽시계 60초가 아님.
9. Claims 고갈·부분/기존 head 앞지르기·paidProfit/claimId/owner 변경을 실제 reducer가 차단; terminal rules 담보와 Vault lock0의 차이 보존.
10. 일반 CLOSE를 risk로 위장, 무서명 relay, 지정 keeper 아닌 위험 호출, pause/unpaid OPEN 거절. 다른 Vault/Controller가 낸 같은 topic의 log를 수락하지 않음.
11. canonical status0/no logs를 불변 accounting observation으로 처리하되 revert 이유·성공 승인 추정 없음. status0+logs 또는 status1에 누락 필수 log 모순 구분.
12. unsupported token approve/wallet/mint/DomainChanged·raw 없음·로그 cap·시간 cap은 INCONCLUSIVE 및 complete=false. 순수 검증 성공도 scope 밖 MATCH로 승격하지 않음.

단위 완료 조건은 구현 → 고정 범위 offline 전체 회귀 → 독립 검토 → 필수 지적 수정/재검증이다. 실제 RPC/PG/chain canonical 인수는 별도이며 C4b harness를 새 이름으로 재실행하거나 검토 상한을 우회하지 않는다. 이번 문서는 설계만 작성했고 추가 구현은 총괄의 해당 단위 GO 뒤 진행한다.

## 9. 구현 단위 분할 — 먼저 작은 rules transition부터

위 통합 verifier 전체를 한 번에 구현하지 않는다. 기술 의존성에 따라 아래 두 단위로 검증한다.

### C4c-2c-1: 순수 rules 수학·상태 전이

신규 후보는 `proof/CRulesTransitionModels.java`, `proof/CRulesTransitionVerifier.java`, 관련 JUnit이다. 기존 `futures.math.FuturesMath.java` **한 파일을 읽기 전용 import**하며 자체 수학 복제나 B 금융 서비스 의존성을 만들지 않는다.

입력은 CAbiModels의 전체 Report, 전체 `FullRulesPosition`(NONE 포함), block timestamp, 새 rules의 executor/symbol/policy/TTL30/grace60 조건, 해당 operation의 이전 소비 여부와 account의 activePosition 값이다. owner/JWT/user nonce·Ethereum tx nonce·EOA code·서명·Receipt·Claims는 이 작은 단위 밖이다. **서버/owner nonce와 Ethereum sender transaction nonce는 서로 다른 값**이며 하나로 비교하지 않는다.

이 단위는 Report의 형식·조건·time/version/epoch와 순수 수학을 검사하고 정확한 full post-position, `OperationApplied` 및 `FuturesReportConsumed` 기대값을 산출한다. 실제 이벤트를 받는다면 산출 기대값과 모든 필드를 exact 비교한다. Report의 Oracle 서명 진위와 실제 rules msg.sender는 다음 wrapper가 검증한다. 단순 `signatureVerified=true`를 받아 전체 승인 증명이라고 표현하지 않는다. 결과 scope는 `RULES_TRANSITION_PREVALIDATED_CONTEXT_ONLY`다.

필수 테스트는 OPEN Long/Short×2/5/10, 기존 position 불변 필드/active 계정/operation, exact q, 금액·가격 strict 상한/0, 음수 손익 floor·MM ceil·E<=MM, TOP_UP/회복/CLOSE, START/CLEAR, 59/60·deadline 이후 새 가격 관측, TTL30/미래2초/lastObservedAt, version/epoch/deadline 덧셈 overflow 및 전체 OperationApplied 필드 변경 거절이다. FULL Solidity semantics와 다른 prestate를 정상처럼 채우지 않는다. 서명 없는 Report의 순수 계산 성공을 사용자 승인 거래 성공으로 보고하지 않는다.

### C4c-2c-2: 원본 transaction·crypto·Receipt wrapper

2c-1 테스트와 독립 검토가 끝난 후 raw sender/chain/hash, owner/relayer/EOA code 전제, C EIP-712, 사용자 Intent/범위/nonce, Receipt 메타데이터/full 이벤트를 연결한다. 2c-1의 pure 기대값과 실제 full 이벤트를 대조하고 마지막에 검토된 C4a reducer에 회계/Claims를 위임한다. 계정 참조 hash helper는 Solidity golden으로 새 검증한다.

정확한 tx 직전 full state와 code evidence 누락은 INCONCLUSIVE이며 pure 계산으로 대신하지 않는다. 실제 RPC canonicality/계약 identity/read-only scanner 및 C4b PG 저장은 더 뒤의 별도 실환경 단위다. 이 분할은 검증 범위를 줄여 전체 완료를 선언하기 위한 것이 아니라 각 책임을 독립 검토 가능한 크기로 제한하기 위한 것이다.
