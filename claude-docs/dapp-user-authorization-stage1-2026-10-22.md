# B안 1단계: 사용자 승인 선물 계약 / Foundry

> 2026-10-22. 별도 `feat/dapp-user-authorization`, 기준 HEAD `f9353c33244565b30fb130dfb6159eab441a3523`. 신규 소스는 미커밋. **계약 단독 검증이며 웹·PostgreSQL·Anvil 통합, 실제 사용자 지갑 인수, 배포 완료가 아니다.**

## 1. 기존 초안 유지와 변경 범위

중지 당시 작성된 `contracts/src/FuturesUserIntentTypes.sol`, `contracts/src/UserAuthorizedFuturesEngine.sol`을 유지했다. 후자는 이번에 owner의 code.length 검사만 추가해 **일반 EOA 전용**임을 강제했다. ERC1271/위임 코드 계정은 지원하지 않는다. 사용자 개인키가 서버와 실제로 독립적으로 보관됐는지는 Solidity가 증명하지 않으며 외부 지갑 연결 단계에서 별도 검증해야 한다.

신규 `contracts/test/UserAuthorizedFuturesEngine.t.sol`을 작성했다. 기존 `FuturesEngine.sol`, `FuturesMath.sol`, 가격 Report/토큰/기존 테스트/배포 스크립트는 수정하지 않았다. 문서 이외 제품 변경은 이 신규 계약2개와 테스트1개뿐이다. 원본과 UI/AI worktree에는 반영하지 않았다.

## 2. 합성 구조와 msg.sender

```text
사용자 EOA ─ typed-data 승인 서명 ─┐
서버 priceSigner ─ 가격 Report 서명 ├→ relayer(외부 tx 서명/Gas)
                                  ↓ tx.to
                       UserAuthorizedFuturesEngine (controller)
                       - owner/조건/nonce/deadline 검증
                                  ↓ CALL, msg.sender = controller
                       새 전용 FuturesEngine (rules)
                       - immutable executor = controller
                       - 가격 서명/위험/상태/60초 유예 검증
```

controller 생성자는 `new FuturesEngine(address(this), priceSigner, symbol, policy)`를 실행한다. 일반 외부 CALL이므로 **rules 안의 msg.sender는 relayer가 아니라 controller**다. rules의 `msg.sender==executor && report.executor==executor` 조건을 만족시키기 위해 report.executor도 controller 주소여야 한다. `testCompositionExecutorAndOwnership`의 실제 성공·불변 executor assertion 및 `-vvvv` 호출 trace로 확인했다. 운영자 relayer가 rules에 직접 호출하는6종 action은 모두 거부된다.

기존 금융 코드를 상속하면 기존 무서명 external 함수가 노출될 수 있어 상속하지 않았다. 새 전용 rules는 기존 계약 소스를 재사용하지만 기존 배포 Engine 인스턴스가 아니다. controller에는 unsigned OPEN/TOP_UP/CLOSE overload, initializer, owner 교체, 범용 call/delegatecall, upgrade/관리자 bypass가 없다. constructor 외부 설정 단계도 없어 미초기화 takeover 창이 없다. 고정된 rules에는 외부 자산 hook/callback이 없고 승인 변경과 rules 전이는 하나의 트랜잭션에서 원자적으로 revert된다.

## 3. 서명·소유권 계약

| 항목 | 서버 가격 보고서 | 사용자 Intent |
|---|---|---|
| domain name/version | `TokenizedMarketFutures` / `1` | `TokenizedMarketFuturesUserIntent` / `1` |
| verifyingContract | **rules 주소** | **controller 주소** |
| chainId | 실제 실행 chainId | 실제 실행 chainId |
| executor/relayer 필드 | report.executor = controller | approval.relayer = 외부 relayer |
| 재사용 방어 | operationId + version/epoch | owner별 전역 순차 nonce |
| 만료 | observedAt+30초, 미래2초 | now≤deadline≤report.validUntil |

사용자 EIP-712 타입은 `FuturesUserIntent(address owner,FuturesReport report,uint256 nonce,uint256 deadline,address relayer)`다. Report를 nested struct로 묶어 operationId/positionId/accountRef/symbol/action/version/epoch/방향/레버리지/수량/진입가/담보전후/체결가/관측시각/만료/policy/executor **18개 필드 모두**를 승인한다. 구조 hash만 의미 없이 사용자에게 보여주는 UI는 이번에 구현하지 않았다. 실제 UI는 nested 값을 이해 가능한 항목으로 표시해야 한다.

OPEN은 recovered signer=approval.owner를 검증해 positionOwner를 한 번 저장한다. TOP_UP/CLOSE는 저장 owner도 일치해야 한다. accountRef는 `keccak256(abi.encode(ACCOUNT_DOMAIN, owner, symbol))`로 도출해 서버 DB uid/기존 UUID accountRef와 분리한다. 같은 owner의 활성 포지션 중복은 rules의 accountRef 규칙으로 막는다. 같은 사람이 여러 지갑을 쓰는 문제나 DB 계정 귀속은 체인이 증명하지 않는다.

사용자 nonce와 서버 operationId는 서로 독립이다. nonce 예약·OPEN owner 저장 후 rules가 서명/금융 조건을 거절하면 모든 상태가 rollback된다. 성공한 operation의 user nonce 재사용, 새 nonce로 같은 server operationId 재사용은 각각의 검증으로 거절된다.

OPEN/TOP_UP/CLOSE에는 매회 사용자 서명이 필요하다. START_CALL/CLEAR_CALL/LIQUIDATE는 이미 사용자 승인으로 개설된 포지션에만 허용하며 기존 rules가 위험·회복·유예를 검증한다. OPEN에서 서명한 policyHash는 자동청산 규칙에 대한 사전 동의이며, 자동화마다 사용자 nonce를 소비하지 않는다. 위험하지 않은 포지션을 LIQUIDATE로 임의 종료하거나 CLOSE action을 자동 경로에 넣으면 기존 rules 검증이 거부한다.

## 4. 금융·담보 책임과 C안 경계

계산식은 기존 FuturesMath/Engine 그대로다: Long/Short·2/5/10배, 정수 수량/손익 보정, 현재 명목가치의5% 올림 유지증거금, Equity≤MM, 손실 담보 한도와 badDebt, 마진콜 블록 timestamp+60초 및 deadline 이후 새 관측가격을 요구한다.

**B안은 담보 사용 동의만 검증하며 담보 존재·예치·지급을 검증하지 않는다.** controller와 rules에는 ERC20 이동·보관·입출금·손익 지급 기능이 없다. DB 격리증거금/house/journal은 이번 미변경·미연동이다. Engine의 가격 입력은 여전히 서버 priceSigner를 신뢰하므로 공모/거짓 가격, 서버 지급 거부, DB 조작까지 방어한다고 주장하지 않는다. relayer 중단 시 사용자 직접 제출/취소·독립 인출도 지원하지 않는다.

C에 재사용 가능한 것은 nested 사용자 Intent의 조건 바인딩, owner/nonce/domain 검증 원칙, 별도 가격 서명, 기존 금융 규칙과 자동화 경계다. 다만 이 고정 controller의 rules 주소·constructor·ABI·저장 구조는 수정 불가다. C에서는 새 배포/새 domain이 필요할 수 있고 과거 서명을 새 계약으로 replay해서는 안 된다.

C 후속 재설계: 사용자별 ERC20 잔고, minter 교체/임의 burn/mint 제한, MarginVault 예치·잠금·인출, house 유동성/손익 지급·지급불능, DB와 체인 정본 관계/대사, 서버 독립 인출, Oracle/특권 신뢰, 공개 테스트넷. **Vault 인터페이스·가상 예치·담보 전송을 미리 추가하지 않았다.**

## 5. 실제 실행 검증

환경: Forge1.5.1-stable, Solc0.8.24, 기존 optimizer200/foundry.toml 사용, `--offline`. 기존 원본의 로컬 OpenZeppelin/forge-std를 절대 remapping으로 읽기만 했다. 의존성 설치·다운로드·Anvil/RPC 연결 없음. out/cache는 B안 worktree 안에 생성됐다.

| 실행 | 결과 |
|---|---|
| 최초 신규 테스트 | 16 PASS/0FAIL/0SKIP (EOA/차분 보강 전) |
| 보강 후 신규+기존 선물 | 36 PASS/0FAIL/0SKIP = 신규20 + 기존16 |
| 전체 `forge build --offline --sizes` | exit0, Scenario.s.sol 포함 전체 컴파일 성공. lint 경고/권고 존재 |
| 전체 `forge test --offline ... -vv` | **72 PASS/0FAIL/0SKIP** = 신규20 + 기존52 |
| 상세 composition trace 선택 | 1 PASS (별도 기능 수로 추가 합산하지 않음) |
| 자동권한/CLOSE위장·정확한 inner 거부사유 보강 후 최종 전체 `--junit` | **73 PASS/0FAIL/0SKIP** = 신규21 + 기존52 |
| 최종 전체 build 재확인 | exit0 (캐시 빌드), 크기/lint 로그 보존 |

최종 증거는 본 worktree `deployment/runtime/dapp-stage1-20261022/foundry-final.xml` 및 `build-final.log`다. JUnit timestamp `2026-10-21T17:08:10.556+00:00`(10월22일 KST),5suite/73tests/failures0/errors0이며 각 suite의 disabled0·실제 PASS 기록을 확인했다. 최초72 PASS와 최종73 PASS를 같은 실행으로 혼합하지 않는다. 빌드는 기존 lint 경고(typecast/unchecked transfer 등)와 신규 named-struct 권고가 있으며 경고0이라고 주장하지 않는다.

fuzz는 기존 선물 손실한도, 현물 quote2개, 신규 legacy 차분1개에서 각256회다. 신규 차분은 양 방향/2·5·10배와 폭넓은 평가가격(손실한도 초과 포함)에 대해 **기존 Engine과 state 및 금융 event data/topics가 동일**함을 검증한다. 결정적 Long/Short×2/5/10배 정상종료6조합도 별도 검증한다. 이것은 무작위 가격 표본이며 모든 전이의 형식 증명은 아니다.

| 필수 요구 | 신규 실제 테스트 근거 |
|---|---|
| 정상 승인 OPEN/TOP_UP/CLOSE | `testCompositionExecutorAndOwnership`, `testNormalOpenTopupCloseAndNonceIndependentOfOperation` |
| 사용자 서명 없이3종 action 거부 | `testMissingUserSignatureAllUserActionsRejected` |
| 타인 키/owner 불일치/위조 account/relayer 거부 | `testWrongUserAndOwnerMismatchRejected`, `testForgedAccountAndNonRelayerRejected` |
| 서버 재서명에도18조건 변조 거부 | `testAllReportFieldsBoundByUserEvenWhenServerResigns` |
| nonce/동일서명 replay 거부 | `testUserNonceAndSignatureReplayRejected` |
| 만료·30초 경계·다른chain/contract domain 거부 | `testDeadlineAndPriceReportThirtySecondBoundaries`, `testWrongUserDomainChainAndContractRejected` |
| 사용자/가격 domain 및 executor 분리 | `testPriceDomainAndExecutorMustUseInnerEngine`, `testIndependentNestedTypedDataDigest` |
| 직접 rules6종/옛 무서명3종 우회 거부 | `testDirectInnerAndLegacyOuterEntrypointsCannotBypass` |
| 자동3종 relayer 제한/CLOSE 위장 거부 | `testAutomaticPathsRequireRelayerAndCannotDisguiseClose` |
| EOA/initializer/owner setter 방어 | `testNonEoaOwnerRejectedAndNoUnsignedInitializer` |
| owner 보존/활성 중복/reopen/원자 rollback | `testOneActivePositionNoReopenAndOwnerNeverChanges`, `testRollbackInvalidReportAndDuplicateServerOperation` |
| 양방향 마진콜/유예/청산, 회복/추가담보 | `testBothSidesAutomaticLiquidationAndSixtySecondGrace`, `testRecoveryTopupAndHealthyCannotLiquidate` |
| 기존 산식/금융 event 동일 | `testLongShortAllLeveragesMatchUnmodifiedEngine`, `testFuzzCloseStateAndFinancialEventsMatchLegacy` |
| 승인과 내부 event의 연결 | `testApprovalAndInnerEventsAreLinkedAndNoEscrowClaim` |

60초 검증은 Foundry `vm.warp`로 deadline-1 거부, deadline 이전 관측을 늦게 채굴해도 거부, 새 관측 후 청산 성공을 확인한 **EVM 시각 단위 테스트**다. 실제60초 wall-time/Worker/PG/Anvil 검증은 이번에 하지 않았다. 테스트 서명 키는 공개 deterministic fixture이며 실제 사용자 지갑 시연이 아니다. 실제 DB 담보 정산·canonical RPC 인수도 NOT_RUN이다.

대표 명령(contracts cwd): `forge test --offline --match-contract 'UserAuthorizedFuturesEngineTest|FuturesEngineTest' -R '@openzeppelin/=<기존로컬contracts/lib/openzeppelin-contracts/>' -R 'forge-std/=<기존로컬contracts/lib/forge-std/src/>' -vv`. 전체 회귀는 match 옵션만 제거, 전체 빌드는 `forge build --offline --sizes`에 같은 remapping을 사용했다. 전역 설정·기존 의존성 파일은 수정하지 않는다.

## 6. 향후 Spring Boot 정산·감사 연결의 필수 변경 (이번 미구현)

주소를 하나로 취급하면 안 된다: **tx.from=relayer, tx.to=controller, report.executor=controller, price verifyingContract=rules**. 현재 backend `FuturesChainIdentity`의 단일 engine/executor 모델과 calldata 검사·nonce 저장·deployment identity를 그대로 연결할 수 없다. controller/rules 각각의 immutable 관계·codeHash·domain을 확인하는 별도 version 경로가 필요하다.

사용자 action의 Receipt에는 rules발 `OperationApplied`, `FuturesReportConsumed`와 controller발 `UserIntentConsumed`의3logs가 순서대로 있다. 자동 action은 rules발2logs이며 새로운 사용자 승인 이벤트를 만들지 않는다. 기존 verifier의 “같은 engine 주소의2logs/11words” 가정을 새 경로에 재사용하면 안 된다.

UserIntentConsumed는 digest/owner/positionId/action/nonce를 제공한다. digest는 **서명된 Report 전체**와 approval을 묶으므로 transaction input의2개 서명·report/approval, 같은 Receipt의 operationId/positionId·주소·순서·canonical block을 재검증해 연결해야 한다. owner 선언 event만 읽고 승인 검증을 생략하면 안 된다. getPosition의 owner+중첩 state ABI도 기존 decoder와 다르다.

DB에는 지갑 연결 소유증명, owner/intent/nonce/domain/2주소·version·원래 서명과 raw의 private 저장, immutable 사용자 귀속, 동일 Idempotency-Key 재조회가 필요하다. 사용자서명은 AI/공개WS payload로 내보내지 않는다. SIGNED 선저장/동일raw복구/확정 Receipt 후 정산/REVIEW_REQUIRED 보존 정책은 유지해야 한다.

30초 report 안에 사용자 지갑 승인·relay 채굴이 끝나야 한다. 만료 시 자동으로 가격·deadline을 바꾸거나 재서명하지 않고 새 preview와 새 사용자 동의를 받아야 한다. 이 UX 충돌을 해결했다고 주장하지 않으며 TTL 완화도 하지 않았다.

## 7. 독립 검토와 보존

독립 검토 `review_dapp_stage1`: **발견된 필수 수정 없음**. 코드3개·기존 Engine/Math/Report·최종21개 테스트·문서를 직접 검토하고 신규21+기존선물16=37 PASS/0FAIL/0SKIP를 별도 실행했다(fuzz256 포함). controller/rules 권한·두 domain·nonce·rollback·owner·event 연결과 마지막 자동권한/CLOSE위장/실제Unauthorized 거부사유 보강도 포함했다. 문서의 최종 결과 수치 갱신만 요청했고 위에 이전72와 최종73을 구분해 반영했다. 검토자도 외부 RPC/DB/배포를 실행하지 않았다.

치명적인 합성 구조 우회는 이번 검토·테스트에서 발견하지 못했다. 중지 초안의 code검사 부재를 보완해 EOA 전용 계약과 실제 허용 대상을 일치시켰으며, 자산 보관/가격 진실성/실제 지갑 키 독립성이 확보됐다는 뜻은 아니다. 보안 감사 완료나 향후 backend 정산 완료로 확대하지 않는다.

최종 소스 SHA-256: `FuturesUserIntentTypes.sol`=391dc38d4026ca7dd4ae1ebd161d686dc6c650c5dfb9ef1b2537780558915617, `UserAuthorizedFuturesEngine.sol`=cfab3ef95e6795c839e586c4d3f87c8301ea19e0988bcff984e557451694b8ab, `UserAuthorizedFuturesEngine.t.sol`=e2e29f41e3460498f451b37e9d26f521203c0c8c04225b1b1391ac60b8ee9d13. 기준 HEAD와 함께 미커밋 테스트 소스를 구분한다.

보호156파일 SHA-256 **변경0**, 기존 원본 사용자 변경은 유지됐다. B안 worktree만 신규3코드파일/이 보고서 및 implementation-log 추기이며 추적 제품 파일 diff는 없다. commit/merge/push/배포/기존 DB·Anvil/평가 상태 변경은 수행하지 않았다. 이번 단계가 끝나면 다음 개발을 자동으로 시작하지 않는다.
