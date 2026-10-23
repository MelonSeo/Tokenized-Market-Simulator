# B안 3단계 선행 Gate — 사용자 서명 실행 가능성 및 최소 수정 설계

작성 2026-10-22. `feat/dapp-user-authorization`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523` + 보존된 1·2단계 미커밋 소스. [2단계 기록](dapp-user-authorization-stage2-2026-10-22.md)을 대체하지 않는다.

## 결론과 이번 경계

**권고: 일반 지갑 UI 개발보다 사용자 Intent와 실행 가격 Report의 책임 분리를 먼저 설계 확정한다.** 현 구조는 조건을 맞추면 실행 가능하지만, 사용자가 읽고 승인하는 일반 지갑 UX에 충분하다는 근거는 없고 코드·무과금 재현에서 구조적 실패 조건이 확인된다. 브라우저 승인시간/채굴 성공률을 추정해 실사용 PASS로 판단하지 않는다.

이번에는 제품/계약/금융정책을 수정하지 않았다. 신규 Java 테스트 1개 파일과 본 문서·작업 로그, 기존 ignored 실행기의 선택 모드만 추가한다. 실제 PG/Anvil/브라우저/배포/유료 호출/merge/push는 NOT_RUN. 기존 전체 회귀의 Windows socket BLOCKED도 해소된 것으로 취급하지 않는다.

## 1. 현재 실행 시간선 — 실제 소스

소스 경로는 `backend/src/main/java/com/pricetrack/exchange/` 기준이다.

| 시점 | 실제 위치 | 검사/결과 |
|---|---|---|
| T0 공급자 관측 | `futures/runtime/FuturesMarketGate.java:18-28`, `market/TradingPriceContext.java:18-21` | currentForTrading의 allowed, CLOSED/STALE, 현재 관측 과거≤5초/미래≤2초; 가격 E8 범위 |
| T1 Report/서명 | `futures/authorization/AuthorizedFuturesService.java:40-49` | T0의 price/observedAt/provider/generation 고정. Report validUntil=floor(T0)+30초; Approval deadline도 동일. 서버가 rules domain에 서명 |
| T2 draft 응답 | 같은 파일 `:49,57`, `AuthorizedFuturesStore.java:56-72` | 저장 전 market.validate(T0); unsigned draft 저장, 자산 예약 없음. 전체 Report를 포함한 controller domain typed data 반환 |
| T3 지갑 서명 | `UserIntentCodec.java:33-40,56-65` | 현재 구현은 Java 테스트의 독립 EOA 서명뿐. 실제 브라우저/지갑 사용시간 미측정. 가격·시간·operationId까지 digest에 포함 |
| T4 submit | `AuthorizedFuturesService.java:58-62`, `AuthorizedFuturesStore.java:74-92` | 미수락 draft: 배포/EOA/nonce 재검사 후 market.validate(T0). Store는 now<deadline, 사용자 서명 및 고정 report 검증 후 기존 담보/house 예약과 metadata commit |
| T5 worker | `futures/runtime/FuturesExecution.java:26-38` | PREPARED만 새 전송. evidence의 T0에 market.validate; 고정 report/서버 서명 재사용. 자동 재가격/재승인 없음 |
| T6 raw 서명 직전 | `futures/chain/FuturesTransactionSender.java:36-52`, `FuturesExecution.java:38`, `AuthorizedFuturesConfig.java:43-45` | RPC 배포·nonce·gas/estimate 이후 T0의 market.validate와 사용자 nonce/EOA 재검사. 그 뒤 raw 서명→REQUIRES_NEW SIGNED commit→broadcast |
| T7 채굴 | `contracts/src/UserAuthorizedFuturesEngine.sol:89-106`, `FuturesEngine.sol:113-121` | controller는 사용자 nonce/서명/owner/deadline; rules는 가격 서명, validUntil=observedAt+30, 관측 미래≤2초, block.timestamp≤validUntil. 체인에는 시장 최신 tick/provider/generation 검사 없음 |
| T8 Receipt/정산 | `futures/chain/FuturesReconciler.java:22-46`, `FuturesReceiptVerifier.java:19-80` | canonical tx/블록/state/owner/이벤트, report TTL·사용자 deadline을 **채굴 블록 시각**으로 검증. 조회 시점이 30초를 지났다는 이유로 성공 Receipt를 실패 처리하지 않음 |

정확한 시간 구분:

- 5초는 지갑을 연 시점이나 draft 응답 시점이 아니라 **원래 관측 T0** 기준이다. 같은 가격의 fresh tick이 와도 T0가 갱신되지 않는다. 가격 Report의 초 단위 timestamp는 관측 Instant를 내림하므로 30초가 응답 이후 정확히 30초라는 뜻도 아니다.
- `FuturesMarketGate.validate`(`:30-37`)는 매번 현재 capture도 fresh인지 확인하고, 별도로 원래 T0의 age를 확인한다. provider/generation도 동일해야 한다. 새로운 tick의 관측시각 자체가 같아야 한다는 조건은 없다.
- 새 tick의 **가격이 달라지면 검증 시** PRICE_CHANGED. tick 이벤트가 draft DB 상태를 즉시 CANCELLED로 바꾸는 것은 아니다. 가격이 돌아오고 T0+5초 이내면 같은 서명을 수락할 수 있다. provider/generation이 달라지면 MARKET_CONNECTION_CHANGED다.
- Store 수락은 `now<deadline`(정확한 경계는 거절), 계약은 `block.timestamp<=deadline`(경계 포함)이다. 현재 서비스 경로는 30초 이전에 이미 5초 gate가 먼저 차단한다.
- PREPARED가 T5/T6에서 거절되면 unsigned 예약 해제. **SIGNED 이후에는 시장이 바뀌거나 만료돼도 임의 취소/실패 처리하지 않고 동일 raw/hash/nonce를 복구**한다(`FuturesTransactionSender.java:57-79`). canonical revert/성공으로만 정산한다.
- 자동 worker의 1초 보조 스캔과 pending 우선 처리(`FuturesWorker.java:26,34-45`)도 남은 시간의 일부다. 실제 지연 분포는 미측정이다.

## 2. 무과금 실행 검증

신규 `backend/src/test/java/com/pricetrack/exchange/futures/ledger/AuthorizedSigningFeasibilityTest.java`는 기존 H2 fixture/Store·Service·Sender를 재사용하고 **실제 FuturesMarketGate + 제어 Clock**을 연결한다. 공급자와 RPC만 Mock이며 사용자/서버 ECDSA는 실제 로컬 계산이다. wall-clock sleep이나 실제 시장/지갑을 측정한 테스트가 아니다.

| 신규 테스트 | 통과해야 하는 관측 |
|---|---|
| fiveSecondInclusiveBoundaryAcceptsSamePrice | T0+5초, fresh 같은 가격/세대 → PREPARED |
| freshSamePriceTickDoesNotRefreshOriginalDraftAge | T0+5.001초, 현재 tick은 fresh → 원래 age 때문에 거절, 예약 0 |
| priceChangingTickBlocksBeforeFiveSecondsButDoesNotPersistentlyCancelDraft | +1초 가격 변경 거절; 같은 시간 가격 복귀 시 같은 서명 수락 |
| samePriceNewTimestampAndUnchangedConnectionAllowsOriginalSignature | +3초 같은 가격 tick 수락; report observedAt은 T0 보존 |
| generationProviderAndDisconnectedSourceRejectEvenIfSamePrice | 세대/공급자 변경 및 연결 가용성 상실 거절 |
| thirtySecondDraftExpiryIsSeparateAndMarketGateRunsFirst | +30초 서비스는 market gate 거절; Store 직접 호출도 만료 거절 |
| tickChangesDuringGasEstimateRejectBeforeRawSigningAndReleaseUnsignedReservation | 수락 후 gas estimate 중 가격 변경 → raw/broadcast 없음, REJECTED 및 잠금 해제 |
| successfulShortDelaySignsOneDurableRawButDoesNotClaimMining | +2초 같은 가격 수락·raw 선저장. fake 응답 유실은 SIGNED이지 채굴 성공이 아님 |
| unusableFiveSecondDraftStillBlocksReplacementUntilThirtySecondExpiry | +6초 기존 draft 실행 불가지만 새 key도 WALLET_DRAFT_PENDING; +31초 새 draft 가능, 과거 서명은 새 draft에 무효 |
| fixedMarginLeverageAndQuantityCannotTrackVariableOpenFillPrice | 실제 quantity 함수로 새 가격의 수량 변경 확인 |
| laterStalePriceDoesNotCancelOrResignAnAlreadyDurableRaw | raw 저장 후 +31초/연결 상실 → 같은 raw/hash/nonce만 복구, 잠금 유지 |

최종 실행: **신규 11개 + 기존 FuturesMarketGateTest 5개 + UserIntentCodecTest 2개 = 18 PASS / 0 FAIL / 0 SKIP**. 실제 JDK21/프로젝트 JUnit classpath의 직접 Launcher 실행이며 정상 Gradle `test` PASS와 구분한다. `testClasses`는 cached Gradle의 오프라인 in-process 실행으로 BUILD SUCCESSFUL. 전체 백엔드 회귀는 재실행하지 않았다. 초기 8개 신규 + 기존 7개 =15 PASS도 별도 로그로 보존하고 최종 수치와 합산하지 않는다.

ignored 증거: `deployment/runtime/dapp-stage2-offline/classpath-20261021T180543020047.log`, `feasibility-20261021T180654157247.log`(최종18개), `feasibility-20261021T180116335397.log`(초기15개). 재현 명령은 `python -B -S deployment/runtime/dapp-stage2-offline/run-offline.py classpath`, 다음 `...run-offline.py feasibility`; 외부 opt-in 변수는 unset, 실제 서비스/인증정보 없이 수행한다.

이번 기존 Foundry 선택 재실행 **4 PASS / 0 FAIL / 0 SKIP**: `testDeadlineAndPriceReportThirtySecondBoundaries`, `testAllReportFieldsBoundByUserEvenWhenServerResigns`, `testBothSidesAutomaticLiquidationAndSixtySecondGrace`, `testPriceDomainAndExecutorMustUseInnerEngine`. `forge test --offline --match-contract UserAuthorizedFuturesEngineTest --match-test 'test(DeadlineAndPriceReportThirtySecondBoundaries|AllReportFieldsBoundByUserEvenWhenServerResigns|BothSidesAutomaticLiquidationAndSixtySecondGrace|PriceDomainAndExecutorMustUseInnerEngine)'` + 기존 검증된 로컬 remapping. 60초는 **vm.warp**이며 실제 대기/채굴이 아니다. stage1 전체 73 PASS와 별도의 새 선택 실행이다.

## 3. A/B 비교와 반드시 해결할 수량 제약

| 구분 | A 현재 nested 전체 Report | B 안정 Intent + 별도 fresh Report |
|---|---|---|
| 사용자 승인 대상 | 가격/관측시각/validUntil/entry/수량까지 완전 고정 | 소유자/작업/포지션/버전/정책/담보/수량 조건/허용가격/nonce/deadline/relayer 고정 |
| 지갑 시간 | T0+5초 및 그 사이 가격 변화에 민감 | 사용자 승인 시간과 실행 tick 수집을 분리할 수 있음 |
| 가격 Report | 사용자 서명 후 변경 불가 | 사용자 서명 후 현재 적격 tick으로 새 Report 생성; 짧은 시장 검사·30초 TTL 유지 |
| 서버 재량 | 서명된 정확한 report뿐 | **서명된 범위·계산식 안에서만**, 계약에서 전부 검증해야 함 |
| 금융·복구 | 검증된 기존 경로 | 실제 실행 Report를 한 번 고정·예약하고 이후 동일 raw 복구. 불확실 시 자동 Report 교체 금지 |
| 수정 위험 | 낮지만 UX 실패/최대30초 draft 대기 | 계약 ABI/코덱/Store/정산 검증 재검증 필수. 기존 1·2단계 PASS 자동 승계 불가 |

**중요: 가격만 바꾸면 되는 문제가 아니다.** 기존 rules의 OPEN은 `entryPrice=price`이며 `q=floor(C×L×10^8/P)`다(`FuturesEngine.sol:70-73`, `FuturesMath.sol:24-29`; Java `FuturesLedger.java:67`). 담보 C, 레버리지 L, 정확한 수량 q를 모두 고정하면 P를 자유롭게 바꿀 수 없다(아주 좁은 정수 내림 구간 제외).

예: C=100,000 mKRW, L=10일 때 P=75,000원은 q(18자리 정수)=13,333,333,333,333,333,333, P=75,001원은 13,333,155,557,925,894,321이다. 이를 같은 고정 수량이라고 처리하면 안 된다. 별도 Report만 재발급하고 quantity 검사를 생략하는 안은 배제한다.

B의 세부 선택:

1. **B-exact:** 수량까지 exact 서명하고 현재 금융식의 quantity 일치를 요구. 가장 엄격하지만 OPEN 가격 변동 대부분을 계속 거절하므로 범용 UX 해결책은 아니다. TOP_UP/CLOSE는 기존 포지션 수량이 고정이어서 분리가 상대적으로 단순하다.
2. **B-bounded-budget(추천, 추가 명시 승인 필요):** OPEN의 C/L/side는 exact, 가격의 min/max와 수량 min/max 및 **기존 quantity 계산식의 고정 policy/version**을 사용자가 명시적으로 서명한다. 계약은 `r.quantity == FuturesMath.quantity(C,L,r.price)`와 양쪽 범위를 검증한다. UI는 preview 수량을 확정 수량으로 표시하지 않는다. 실행 수량은 사용자가 승인한 함수·범위 안에서만 결정되며 운영자가 임의 변경할 수 없다. 단, 사용자가 이번 요구에서 '수량은 반드시 exact'를 의미했다면 이 선택은 허용되지 않으므로 **승인 전 구현하지 않는다**.

수량·담보·레버리지의 의미를 조용히 바꾸거나 기존 rules의 OPEN 식을 고치는 안은 추천하지 않는다. 손익/유지증거금5%/손실상한/60초 유예는 어느 경우도 변경하지 않는다.
또한 기존 D05의 'preview 가격 변경 시 재확인'을 조용히 없앨 수 없다. 새 B 모드에서 사용자가 명시적으로 서명한 가격·수량 범위를 사전 승인으로 인정할지 별도 확정해야 하며, legacy/현물의 정확 가격 재확인 정책에는 확대 적용하지 않는다.

## 4. 최소 B-v2 계약/저장 설계 제안 (미구현)

### 사용자 Intent

- v1과 구분되는 새 EIP-712 version/배포 identity. owner, relayer, nonce, userDeadline, intentId, operationId, action, positionId, accountRef, symbolHash, expectedVersion, expectedCallEpoch, side, leverage, collateralBefore/After, policyHash, priceMinE8/MaxE8를 바인딩한다.
- OPEN은 위 승인에 따라 exactQuantity 또는 명시된 계산 모드+quantityMin/Max. TOP_UP/CLOSE는 기존 entryPrice/quantity와 담보를 exact 바인딩. 사용자가 승인하지 않은 다른 position/version/action/담보로 바꾸면 계약이 거절한다.
- Report의 operationId/positionId/action/버전/side/레버리지/담보/계정/정책/executor와 Intent를 계약에서 비교한다. operationId는 현행처럼 draft에서 미리 고정한 DB UUID의 `keccak256(UTF8 UUID)`를 Intent에도 서명해 1:1 연결한다. 가격 Report 생성시 임의 operation 교체를 막으며 현재 `FuturesReport.from`/Receipt의 ID 매핑을 보존한다(최종 ABI 전 golden vector 확정).
- 사용자 deadline은 지갑 읽기·서명 시간을 위한 별도 값(예: 120초 **제안**, 미승인)이고 server Report TTL은 30초 그대로다. 기존 `a.deadline<=report.validUntil` 결합을 제거하되 실행 블록이 **두 deadline 모두 이내**여야 한다. 단순 가격 TTL 연장과 다르다.
- 현재 provider/generation은 체인이 아는 정보가 아니라 서버 가용성 근거다. Intent issuance의 market scope를 DB에 고정하고 generation/provider 변경 시 해당 Intent를 재확인하도록 차단한다. 체인이 그 연결 상태를 독립 증명한다고 표현하지 않는다. 이를 체인에서 추가 검증하려면 별도 서명 필드/Oracle 계약이 필요해 최소 범위에서는 제외한다.

### 실행·정산

1. draft는 사용자 조건만 준비, Report/가격 서명/담보 예약 없음.
2. 사용자 서명 수락 후 현재 eligible mark 수집; 동일 연결/허용 가격/수량 조건·deadline·nonce·버전 검증.
3. 실제 mark로 q/entry/손익/house 예약 계산 → **실행 Report+가격 서명+사용자 Intent+서명+metadata를 예약과 함께 원자적으로 고정**. RPC는 DB 잠금 밖에서 수행하고 버전/nonce 경합은 계약이 최종 차단한다.
4. estimate 후에는 **선택한 실행 mark**에 기존 5초/가격/provider/generation gate를 적용한다. 여기서 가격이 바뀌면 안전 거절. 사용자 지갑 대기와 분리될 뿐 relayer/채굴의 짧은 시간 창이 없어지는 것은 아니다.
5. PREPARED 실패 시 자동 반복/재가격은 최초 최소안에서 제외하고 명시적인 새 요청을 요구한다. 이미 SIGNED라면 승인 만료/시세 변화/취소 요청으로 raw를 교체하지 않고 기존처럼 해결한다.
6. `tx.from=relayer`, `tx.to=controller`, `report.executor=controller`, 가격 domain=rules, 사용자 domain=새 controller 유지. rules 자체 금융식은 수정하지 않는 합성을 우선한다.
7. controller 이벤트는 intentDigest와 **실행 reportDigest/operationId**를 연결하는 새 event를 추가하는 안 권고. 기존 rules 2event는 유지, 새 controller event 1개와 calldata/서명/owner/canonical tx를 대조한다. 정확한 이벤트 signature/순서는 v2로 분기하고 v1 decoder로 수용하지 않는다.

### 취소/재사용

현재 v1에는 명시적인 취소 entrypoint/API가 없다. nonce 1회 소비·deadline·DB inflight만 있으며, 팝업 취소/서버 취소는 이미 나온 서명을 온체인에서 폐기하지 못한다. 이 한계를 숨기지 않는다.

v2 제안: owner EOA가 직접 호출하는 `cancelNonce(expectedNonce)`로 현재 nonce를 증가시키고 취소 event 발행. 다른 거래 동작/자산 이동 권한은 부여하지 않는다. 새 backend cancel은 unsigned 요청을 차단하되 **온체인 취소 확정 전에는 chain-revoked라고 표시하지 않는다**. relay된 취소도 허용하려면 별도 typed Cancel·소유자 서명·deadline 검증이 필요하며 일반 거래 서명과 구분한다. 취소와 실행이 경합하면 먼저 canonical 확정된 쪽만 성공한다. SIGNED 요청의 자산 잠금은 취소 버튼만으로 풀지 않고 기존 canonical reconciliation을 따른다. nonce 취소는 자동청산 권한/유예를 취소하지 않는다.

## 5. 변경 파일, ABI 영향, 작업량과 Gate

**아래는 다음 단계 승인 대상이지 이번에 적용한 diff가 아니다.**

| 묶음 | 최소 대상/변경 | 추정 집중 작업 |
|---|---|---|
| 계약 v2 | FuturesUserIntentTypes/UserAuthorizedFuturesEngine의 별도 v2 타입·controller, bound 검증/nonce 취소/event. 기존 FuturesEngine/Math/ReportTypes는 유지 우선 | 1~2일 |
| 코덱·API·Store | UserIntentCodec, AuthorizedFuturesService/Store/Controller/IntentStore, additive v2 metadata/draft와 승인/실행 두 단계 상태 | 1~2일 |
| 전송·복구·검증 | FuturesExecution/TransactionStore/Sender/ReceiptVerifier/RpcGateway/Reconciler, Ledger의 기존 금융식 재사용. 실제 실행 Report/account 연결과 v1/v2 명시 분기 | 1~2일 |
| 회귀·격리 검증 | Foundry/Java golden 및 PG 경합/취소/같은 raw 복구/Anvil, 독립 검토 | 1~2일 |

합계 **4~8 집중 작업일의 거친 추정**, Windows socket 복구·실제 지갑/브라우저 구현·B 전체 감사·환경 대기 시간은 별도다. 소수 시간 내 수정으로 확약할 수 없다. 단계별 실패는 다음 단계 진행 차단이다.

- 기존 v1 ABI `(Report,priceSignature,Approval,userSignature)`와 digest가 바뀐다. v1 배포/증거를 교체하지 말고 새 identity로만 진행해야 한다. 내부 rules ABI·금융 event는 재사용 가능하지만 실제 검증 전 그대로 PASS를 승계하지 않는다.
- Java/Solidity 동일 typed-data golden, 전 필드/price·quantity 경계 변조, 다른 owner/domain/nonce/만료/취소 경쟁, 무서명 우회/자동청산 가장을 먼저 검증한다.
- 기존 v1 테스트는 보존하고 v2 테스트를 추가한다. 기존 golden/ABI/user event 기대값을 v2에 복사해 PASS로 처리하지 않는다. 사용자 승인 여유시간>5초 후 **새 Report**로 실행되는 시나리오와 Report 자체 >5초 차단, mined >30초 거절, Risk Worker 60초 유지가 별도 필수다.
- PG 단계는 중복 submit/receipt, house 예약, 롤백·재시작·canonical owner/report/event 불일치·취소와 체결 경합 검증이 필수. H2/Mock으로 완료 대체하지 않는다.
- B version별 감사 coverage 미구현과 기존 감사 INCONCLUSIVE는 별도 남은 작업이다. C안 Vault·ERC20 예치/실자산 정산은 이 설계/작업량에 포함하지 않는다.

## 6. 승인 전 결정과 최종 기록

1. OPEN은 정확한 수량의 B-exact를 유지할지, 기존 담보×레버리지 식과 사용자가 서명한 가격·수량 범위의 B-bounded-budget을 허용할지 결정 필요. 후자를 권고하되 승인하지 않은 수량 유동화를 구현하지 않는다.
2. 별도 사용자 승인 deadline(120초 제안), 사용자가 명시적으로 고르는 가격/수량 허용 범위와 nonce 취소 방식 승인 필요. 자동 slippage·기본 무제한 범위·가격 TTL 연장은 제안하지 않는다.
3. 이 결정 후 계약/코덱 v2를 먼저 검증하고, 기존 v1을 보존한 새 opt-in 경로에서만 PG/Anvil·그 다음 지갑 UI Gate를 진행할 것을 권고한다.

## 최종 검토·보존과 판정

- 독립 `review_dapp_feasibility`: **발견된 필수 수정 없음**. 실제 gate/Service/Store/Execution/Sender/controller/rules와 테스트·설계를 대조했다. 별도 테스트 실행은 없으며 미구현 v2의 보안 승인도 아니다. 시간 검증, 수량 제약, 불변 raw 복구, 기존 PASS 비승계와 실제 인수 NOT_RUN 경계를 확인했다.
- 원래 productHash `da8db664f50c92461e6950c7f7bb08b3f8bd5f8729287bcf331ce659548b208c` 유지. 기존 `deployment/phase-a.py::product_hash` 정의(backend main·계약 등)를 사용하며 신규 테스트/문서는 포함하지 않는 값이다. 1단계 계약·테스트 3개 SHA-256 동일, 보호 baseline 156파일 변경 0, Git diff --check PASS. 실제 DB/체인의 identity를 새로 조회한 것은 아니며 이번에는 그 서비스에 접근하지 않았다.
- 이번 추가 경로: 신규 `AuthorizedSigningFeasibilityTest.java`, 본 보고서, 작업 로그 누적; ignored 실행기의 `feasibility` 선택 모드. 이전 사용자 미커밋 및 단계별 성공/실패 로그는 보존한다. HEAD 변경/커밋/병합/배포 없음.
- **실행 조건 재현: PASS. 일반 지갑 UX 인수: NOT_RUN/충분한 실현 가능성 입증 못 함.** 현 nested 구조를 일반 UI에 그대로 확장하는 것은 비권고. 책임 분리 v2의 세부 승인 조건을 먼저 확정할 것을 추천한다. 이번 제품 변경이나 다음 구현은 시작하지 않고 사용자 결정을 기다린다.
