# B-v2 사용자 Intent / 실행 Report 분리 — 계약 단독 구현

작성 2026-10-22. `feat/dapp-user-authorization`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523` + 기존 미커밋 + 이번 신규 파일. [선행 Gate](dapp-user-authorization-stage3-preflight-2026-10-22.md), [B-v1](dapp-user-authorization-stage1-2026-10-22.md), [백엔드 v1](dapp-user-authorization-stage2-2026-10-22.md) 기록을 보존한다.

## 범위

이번 신규 제품은 `contracts/src/FuturesUserIntentV2Types.sol`, `contracts/src/UserAuthorizedFuturesEngineV2.sol` 두 파일이다. 신규 테스트는 `contracts/test/UserAuthorizedFuturesEngineV2.t.sol`. 기존 B-v1/Engine/Math/Report 및 Java/DB/웹 소스는 수정하지 않았다. 보조 실행기와 로그는 ignored `deployment/runtime/dapp-v2-stage1-20261022/`에만 둔다. 계약 배포, Anvil/PG/브라우저 기동, 유료 API, 원본 병합/commit/push는 하지 않았다.

## 설계와 권한

- B-v1을 교체하지 않고 별도 `UserAuthorizedFuturesEngineV2`를 추가한다. 합성한 **새 내부 FuturesEngine의 immutable executor는 controller**다. 외부 executor/relayer는 내부 rules를 직접 실행할 수 없다. 내부 rules를 바꿀 setter, owner override, upgrade, fallback, 임의 call은 없다.
- 사용자 EIP-712 domain은 `TokenizedMarketFuturesUserIntent`, **version 2**, chainId, 새 controller 주소. 가격 domain은 기존 `TokenizedMarketFutures`/1, 새 내부 rules 주소다. 가격 `report.executor=controller`, tx.from=relayer, tx.to=controller다. 사용자·가격 서명은 서로 대체하지 못한다.
- 기존 wallet accountRef 산식은 재사용하되 DB 사용자 식별자는 이 계약에 존재하지 않는다. JWT userId↔wallet 연결은 후속 백엔드 책임이다. EOA/code.length=0만 허용하며 ERC-1271/위임 코드 지갑은 지원하지 않는다.

### 사용자가 서명하는 22개 필드

`owner, relayer, operationId, positionId, accountRef, symbolHash, action, expectedVersion, expectedCallEpoch, side, leverage, entryPriceE8, collateralBefore, collateralAfter, priceMinE8, priceMaxE8, quantityMin, quantityMax, nonce, issuedAt, deadline, policyHash`.

- 전체 가격 Report/observedAt/validUntil/서버 서명은 사용자 Intent에 없다. 다른 Report라도 아래 조건을 모두 만족해야만 같은 사용자 승인으로 실행할 수 있다.
- operationId/positionId/계정·종목·action·버전·epoch·방향·레버리지·담보·policy는 Report와 exact 비교한다. rules도 지원 종목/정책/기존 상태를 독립 검증한다. operationId는 사용자가 서명한 값이므로 서버 임의 치환 불가. 향후 DB UUID→bytes32 매핑은 기존 방식으로 유지할 수 있다.
- 양쪽 가격·수량 범위는 명시적 필수 필드다. min>0, min≤max, priceMax<1e20, quantityMax<1e30을 요구한다. 0/역전/max uint의 무제한 표현은 거절한다. UI/서버가 실제 사용자가 확인한 범위를 전달하는지는 **아직 구현·검증되지 않았다**. 계약만으로 사용자가 서명 내용을 읽었음을 증명할 수는 없다. 매우 넓지만 유효한 범위를 사용자가 직접 서명하는 경우까지 임의의 좁은 slippage 정책으로 변경하지 않는다.
- **OPEN bounded-budget:** collateral/side/leverage exact, entryPriceE8 Intent 필드는 0 sentinel. 실제 Report entryPrice=execution price, quantity=`FuturesMath.quantity(collateralAfter,leverage,execution price)`이며 사용자 수량 범위에도 포함돼야 한다. '어떤 in-range 수량이든' 고르는 방식이 아니다.
- **TOP_UP/CLOSE:** 원래 entryPrice exact, quantityMin=quantityMax=Report.quantity. rules가 기존 position의 q/entry/side/leverage/담보/version/epoch와 다시 비교한다. 새 실행가격은 사용자가 승인한 가격 범위 안에서만 바꿀 수 있다.

### 독립 시간 창

사용자 Intent는 signed issuedAt/deadline에 대해 `issuedAt≤block.timestamp≤deadline`, `0<deadline-issuedAt≤120초`를 요구한다. 실제 지갑 서명 순간을 체인이 알 수 있는 것은 아니며 서명한 유효 창을 검사한다. 가격 Report는 기존 rules의 `validUntil=observedAt+30`, 관측 미래≤2초, `block.timestamp≤validUntil`을 그대로 사용한다. 두 기한은 독립적이며 사용자 deadline이 Report expiry보다 늦어도 된다.

새 테스트에서 1000초 사용자 서명→1010초 새 가격/새 수량/새 Report로 실행을 검증했다. 이것은 **Foundry vm.warp**, 실제 지갑 대기·네트워크·채굴 성공률 검증이 아니다.

**5초 시장 gate는 기존 Spring Boot의 raw 서명 전 검사다.** 이를 계약의 채굴 시점 5초 제한으로 바꾼 적 없고, B-v2 계약이 provider/generation/외부 시장 연결을 독립 검증한다고 주장하지 않는다. 후속 B-v2 백엔드는 사용자 서명 뒤 선택한 실행 tick에 기존 5초/가격 동일/provider/generation gate를 적용해야 한다. 이번 백엔드는 그대로 B-v1이며 v2 전송을 지원하지 않는다. 체인상의 기존 30초 TTL을 검증했다는 것과 시장 gate가 실제 v2 서비스에 연결됐다는 것은 구분한다.

## 실행과 이벤트

사용자 entrypoint는 `open/topUp/close(Report,bytes priceSignature,Intent,bytes userSignature)`다. 검증 후 nonce를 소비하고 rules 호출, 성공한 경우에만 controller 이벤트를 발행한다. 가격 서명/Report/상태 검증이 실패하면 owner 설정과 nonce까지 EVM 트랜잭션 전체가 rollback한다.

동일 tx의 순서:

1. rules `OperationApplied` (기존 ABI)
2. rules `FuturesReportConsumed` (기존 ABI)
3. controller `UserIntentExecutedV2(bytes32 indexed intentHash,address indexed owner,bytes32 indexed positionId,bytes32 operationId,bytes32 reportDigest,uint8 action,uint256 nonce)`

reportDigest는 **rules의 실제 EIP-712 reportDigest**다. 새 event와 calldata를 함께 검증해야 exact 실행 Report·사용자 Intent의 연결이 된다. v1 `UserIntentConsumed`와 event signature/data가 다르므로 v1 decoder로 v2를 수락하면 안 된다. 최종 canonical Receipt/DB 연결은 후속 NOT_RUN이다.

자동 `startMarginCall/clearMarginCall/liquidate(Report,priceSignature)`는 별도 경로다. relayer+기존 owner 존재를 확인하고 rules가 action·위험·상태·서명·60초 유예를 검사한다. 사용자 nonce 취소나 만료로 자동청산을 철회할 수 없다. 정상 CLOSE를 이 경로에 주입하면 rules의 action 검사가 거절한다.

## 취소

최소 `cancelNonce(expectedNonce)`는 **호출 EOA 자신의 현재 nonce만** 증가시킨다. relayer가 자기 nonce를 취소해도 사용자 nonce는 바뀌지 않는다. `UserNonceCancelledV2(owner,oldNonce)` 이벤트로 표시한다. 다른 사용자 지정, 서명 대행 취소, 자산 해제, 포지션 수정, 자동청산 취소는 없다.

실행과 취소는 canonical 순서상 먼저 확정된 쪽이 이긴다. 취소 제출만으로 확정 취소라고 표시해서는 안 된다. 사용자가 직접 보내는 nonce 취소 트랜잭션의 gas·지갑 UI/Receipt 추적은 후속이다. 이미 SIGNED된 서버 raw는 기존처럼 동일 hash/nonce로 해결해야 하며, 서버가 취소 요청만 받고 DB 예약을 임의 해제하는 기능을 구현하지 않았다.

## 실제 Foundry 결과

- 최초 Solc 컴파일 성공, 선택26개 중25 PASS/1 FAIL. 실패는 `vm.prank(user); cancelNonce(c.nonces(user))`에서 getter가 일회 prank를 먼저 소비한 테스트 결함이다. nonce를 먼저 읽도록 수정했으며 계약의 EOA/권한 검사는 변경하지 않았다. 초기 tool 결과 요약 `initial-result.md` 보존.
- 보강 선택 **28 PASS / 0 FAIL / 0 SKIP**, fuzz256 포함. 로그 `selected-20261021T182355151236.log`.
- 전체 새 실행 **101 PASS / 0 FAIL / 0 SKIP** = 기존73 + 신규28. 로그 `all-20261021T182418041469.log`. 첫 실행기의 콘솔 출력 CP949 인코딩 오류는 forge exit0/원본101PASS 로그가 기록된 **후** 발생했다. stdout UTF-8 출력만 수정했으며 이를 계약/테스트 실패와 구분한다. 이후 최종 전체 실행은 아래에 별도로 기록한다.
- 전체 `forge build --offline --sizes` 성공. 기존 금융/토큰 test의 lint 경고와 신규 immutable naming/hash 권고는 존재한다. 미사용 신규 테스트 import는 제거했다. 배포 스크립트 실행은 하지 않았다.

핵심 커버리지: 22개 Intent 필드 변조, 서버가 재서명한15개 고정 Report 필드 변조, 가격/수량 양쪽 경계, 수량식, 독립120초/30초·미래 관측·만료, 다른 owner/nonce/chain/주소/version·서명 없는3종, 내부6종/자동3종 권한, 중복operation rollback, 취소/실행 경합, EOA 제한, 두rules+controller event의 digest 연결, 양방향×2/5/10배 실제 rules 마진콜/회복/증거금 추가/60초 청산 및 CLOSE 위장 거절. 60초는 모두 vm.warp다.

## 백엔드 예상 변경 (이번 미구현)

- UserIntentCodec의 v2 별도 타입/domain/golden vector, 기존 v1 DTO/ABI decoder와 명시 분기.
- AuthorizedFuturesService/Store/Controller: 사용자 범위/issuedAt/deadline/고정 ID draft와 사용자 서명 수락 후 새 시장 가격 선택·Report 생성 분리. 암묵적 무제한 범위/자동 재승인 금지.
- Execution/TransactionStore/Sender: 고정된 실제 실행 Report+사용자 Intent를 예약·SIGNED 원문에 연결. 최초 최소안 PREPARED 자동 재가격 없음, SIGNED 후 같은 raw/hash/nonce 복구 유지.
- RpcGateway/ReceiptVerifier/Reconciler: controller/rules 새 codeHash/domain, full calldata의 두 서명 및 새 event 연결·순서/owner/canonical block 검증. 취소 event/nonce 변경과 실행 Receipt의 경합 처리.
- additive schema와 버전별 감사 coverage; 기존 v1·legacy 정확가격 정책과 금융식을 유지. 실제 PG/Anvil/웹/독립 지갑 인수는 별도 승인·증거가 필요하며 이번 Foundry PASS로 대체하지 않는다.

## 신뢰 한계와 보존

운영자는 사용자 서명 없이 사용자 OPEN/TOP_UP/CLOSE를 확정할 수 없지만, 사용자 범위 안에서 불리한 시점/가격을 선택하거나 relay를 지연·거부할 수 있다. 가격 signer와 외부 시세 정확성은 계속 신뢰한다. Report의 block 시간 범위를 검사하는 것만으로 외부 시장 진실성·best execution을 증명하지 않는다.

담보 정본은 여전히 DB 모의장부다. 실제 ERC20 보관·인출·풀 지급능력·비수탁·C안 MarginVault는 구현하지 않았다. 서버 자동 worker/nonce/DB 복구 구현이 v2에 이미 연결됐다고 표현하지 않는다.

보호 baseline156파일과 v1 계약/테스트3개 해시 변경0 확인. 신규 v2 source 2개만 제외하고 기존 product_hash 정의로 재계산하면 **2단계 hash `da8db664f50c92461e6950c7f7bb08b3f8bd5f8729287bcf331ce659548b208c`와 일치**한다. 따라서 기존 backend main/Engine/Math/Report 등 기존 제품 내용은 그대로다. v2 포함 최종 productHash는 `fefe70447c360afba0925170766791bfe0ad1b8dd31fc8528cc26d79253a6e5c`이며 기존 manifest를 새 값으로 갱신해 guard를 우회하지 않았다. productHash는 기존 정의상 테스트/문서는 포함하지 않는다.

## 최종 Gate 및 독립 검토

- 미사용 import 제거까지 반영한 전체 새 실행: **101 PASS / 0 FAIL / 0 SKIP**, 6 suites, 신규28·B-v1 21·기존52. `all-20261021T182634675705.log`. 기존 결과의 단순 합산이 아니라 해당 소스에서 실제 함께 실행한 결과다.
- v2 controller runtime 6,988 bytes, initcode 17,067 bytes로 현재 compiler 설정의 크기 제한 이내. **최종 전체 build 성공** 로그 `build-20261021T182821709612.log` 보존. Lint가 없는 코드라는 뜻은 아니다.
- 독립 `review_dapp_v2`: **발견된 필수 수정 없음**. 소스/기존 rules/테스트/문서를 검토하고 **별도 선택28 PASS/0 FAIL/0 SKIP, fuzz256**을 확인했다. 전체101은 구현자 실행 결과이며 검토자의 재실행과 구분한다. 실제 배포/PG/Anvil/웹/시장5초 gate 연결과 canonical Receipt/DB 정산은 미검증이라는 한계를 확인했다. 최종 검토 후 제품 소스 변경 없음.
- 소스 SHA-256: `FuturesUserIntentV2Types.sol`=`dacdeafca8ff4c467cdad77eb762ac447caf54d145a08f615343d5894335f2ca`, `UserAuthorizedFuturesEngineV2.sol`=`4e19b18bb87e9d3d3ae75d414cfb6a5d603c3a412ca09a0450412af4ede1e074`, 신규 테스트=`168d45424ab0996a19e7a96bf18150d457a77c2d6c09b87d7545a6b8e5ec834a`.
- **이번 Gate는 계약 단독 검증**이다. backend v2, DB 정산/복구/감사, 실제 RPC/채굴, 시장 5초 gate 연결, 사용자 지갑/브라우저는 NOT_RUN/미구현. 실제 담보 통제·지급능력/C안은 범위 밖이다. 다음 단계로 자동 진행하지 않는다.
