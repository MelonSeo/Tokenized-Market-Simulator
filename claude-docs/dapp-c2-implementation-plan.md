# C2 기술 구현 계획 — 승인·rules·실제 담보·FIFO Claims

> 이후 구현을 시작했다. 아래 NOT_RUN은 계획 작성 당시 상태이며, 현재 실행·검토 판정은 [C2 결과](dapp-c2-atomic-claims-2026-10-23.md)와 주 진행 문서를 따른다.

작성: 2026-10-23. **계획만 작성, C2 구현/실행 NOT_RUN.** 선행 조건은 C1 token/Vault 테스트와 독립 검토 완료다. 정책 기준은 [C-001 ADR](dapp-c-policy-adr-2026-10-23.md)과 승인 원문이다. 아래는 승인 정책을 구현할 최소 함수 분리이며 새로운 경제 정책이 아니다.

## 1. 변경 단위와 재사용

| 파일 역할 | 변경 | 보존 |
|---|---|---|
| 새 `CFuturesIntentTypes.sol` | C 전용 22-field Intent 및 TYPE_HASH | B-v1/v2 라이브러리/서명/배포 사용 안 함 |
| 새 `CCollateralizedFuturesController.sol` | C domain, owner 직접/relay/shared nonce, action dispatch, immutable rules/Vault 생성, OPEN-only pause | 기존 B controller 변경 안 함 |
| `CMarginVault.sol` | C1 예치/인출/실수령 검증 유지, controller-only lock/top-up/settle, FIFO Claims 추가 | 임의 EOA 경제 훅, 관리자 free 이전/house 인출 안 함 |
| 새 C2 Foundry 테스트 | 정상/거절/원자 rollback/재사용/Claims/권한 | 기존 C1 및 기존 재사용 수학·rules 회귀 유지 |

C worktree의 `FuturesEngine`, `FuturesMath`, `FuturesReportTypes`는 공통 HEAD에 이미 있던 파일이며 audit worktree와 byte-identical임을 SHA-256으로 확인했다. 별도 B 디렉터리를 복사하거나 기존 파일을 덮어쓰지 않았다. 기존 rules 주석의 “DB 담보를 신뢰한다”는 것은 해당 단독 계약의 성질이며 C 보장은 **새 controller가 Vault와 대조하고 같은 tx에서 호출하는 합성 구조**에서 얻는다. 이 파일 수정이 필요하면 새 의존성 변경으로 별도 검토하고 기존 PASS를 승계하지 않는다.

## 2. C 승인 데이터와 고정 권한

C 사용자 EIP-712 domain은 `name="TokenizedMarketCFuturesUserIntent"`, `version="1"`, 실제 chainId, 새 C controller 주소다. TYPE_HASH의 struct 이름도 `CFuturesIntent`로 분리하고 다음 정확한 순서와 타입을 사용한다.

```text
CFuturesIntent(address owner,address relayer,bytes32 operationId,bytes32 positionId,
bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,
uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 entryPriceE8,
uint256 collateralBefore,uint256 collateralAfter,uint256 priceMinE8,
uint256 priceMaxE8,uint256 quantityMin,uint256 quantityMax,uint256 nonce,
uint256 issuedAt,uint256 deadline,bytes32 policyHash)
```

위 줄바꿈은 가독성만을 위한 것이며 hash 문자열에는 줄바꿈/공백을 넣지 않는다. `keccak256(abi.encode(TYPE_HASH,intent))`를 사용한다. accountRef는 C 전용 상수 domain·owner·symbol로 파생한다. policyHash는 C 정책용 고정값이다. B 서명은 domain/struct/accountRef/policy에서 분리된다.

가격 Report의 schema/domain 이름은 재사용 rules의 실제 `TokenizedMarketFutures`, version `1` 그대로다. 하지만 **새 rules verifyingContract**, 새 Oracle signer, C policy, `report.executor=C controller`를 사용하므로 이전 Report 서명·배포를 재사용할 수 없다. chainId 및 주소 getter/codeHash를 실제 배포 identity에 기록한다.

constructor 입력은 asset, relayer, Oracle signer, OPEN pause admin, symbol, C policy다. 주소는 0이 아니며 admin/relayer/signer는 서로 다르다. controller는 `new FuturesEngine(address(this),signer,symbol,policy)`와 `new CMarginVault(asset)`로 immutable 관계를 확정한다. setter/init/upgrade/generic call은 없다. keeper는 지정 relayer를 재사용한다. controller 생성 중 code.length=0을 계약 부정으로 판정하지 않으며 경제 훅 실행 시 controller-only+runtime code 존재를 확인한다.

## 3. Controller 함수와 공통 내부 검증

```solidity
executeDirect(Report report, bytes priceSignature, Intent intent)
executeRelayed(Report report, bytes priceSignature, Intent intent, bytes userSignature)
startMarginCall(Report report, bytes priceSignature)
clearMarginCall(Report report, bytes priceSignature)
liquidate(Report report, bytes priceSignature)
cancelNonce(uint256 expectedNonce)
pauseNewPositions(bool paused)
intentDigest(Intent intent) view
accountRef(address owner) view
```

`execute*`는 OPEN/TOP_UP/CLOSE만 dispatch한다. START_CALL/CLEAR_CALL/LIQUIDATE는 executeDirect나 사용자 서명 CLOSE로 접근하지 못한다. risk 함수는 relayer만 호출하고 user nonce를 소비하지 않는다.

직접 경로는 EOA owner=msg.sender를 요구하며 서명만 생략한다. relay는 지정 relayer=msg.sender, intent.relayer=고정 relayer, ECDSA owner를 검증한다. **직접 경로에서도 intent.relayer는 같은 고정 relayer 값**으로 유지하되 실제 tx.from이 relayer라고 오인하지 않는다. 두 경로는 같은 `_executeUser`와 nonces를 사용한다.

공통 검사:

1. owner EOA/position owner, nonzero operation/position, nonce 정확 일치, issuedAt<=현재, deadline>issuedAt, window<=120초, 현재<=deadline.
2. accountRef/symbol/policy/action/version/callEpoch/방향/레버리지/담보 before-after의 Intent/Report 일치.
3. 양수 유한 가격·수량 범위, 가격 `<1e20`, 수량 `<1e30`; Report가 사용자 범위 안에 있음.
4. OPEN은 intent.entry=0, Report.entry=Report.price, q=`FuturesMath.quantity(collateralAfter,leverage,price)`; 담보 before=0.
5. TOP_UP/CLOSE는 정확한 기존 entry 및 quantity(min=max), rules 포지션과 Vault 잠금의 owner/담보 일치. OPEN의 방향·레버리지·담보 승인 변경 불가.
6. Report.executor/controller 및 rules domain·서명·30초 TTL·미래시각 오차는 실제 rules가 검증한다. 서버 5초/provider/generation을 온체인에서 확인했다고 주장하지 않는다.
7. OPEN에만 `!paused && vault.totalUnpaidProfit()==0`, positionId 과거 사용 금지. owner/symbol 활성 1개는 rules.activePosition이 확인한다.

cancelNonce는 owner 자신의 expected nonce를 +1하며 position/lock을 변경하지 않는다. 직접/relay/cancel 중 먼저 canonical 성공한 경로만 nonce를 소비한다. risk action은 사용자 취소와 독립적이다. 외부 진입점 reentrancy를 보호하고 내부 dispatch 함수를 외부 우회 함수로 노출하지 않는다.

## 4. 같은 tx의 action별 원자 순서

사전 Vault position-lock 확인 → 검증된 Report의 `FuturesMath.mark` 산출 → rules action 호출 → post-position을 expected state와 정확 비교 → Vault action → controller 승인/결과 이벤트 순서를 기본으로 한다. user nonce와 positionOwner 설정은 외부 calls 전 effects로 하더라도 후속 실패 시 EVM 전체 revert해야 한다. rules 함수 반환값/로그를 호출 중 조회할 수 있다고 가정하지 않는다. 실제 `rules.getPosition`과 동일 math로 검증한다.

| action | pre-state/math | rules 후 대조 | Vault 효과 |
|---|---|---|---|
| OPEN | 새 position, q 계산, free>=C | ACTIVE/version1/owner account/entry/q/C 일치 | free C 감소, position locked C 증가 |
| TOP_UP | 기존 ACTIVE/MARGIN_CALL, lock C0, delta=C1-C0>0, free>=delta, mark는 C1 | collateral C1, version+1, q/entry 유지; 회복 시 ACTIVE/deadline0 | free delta 감소, locked delta 증가 |
| CLOSE | 기존 lock C, mark(C,새 price), user 승인 | CLOSED/version+1, 담보 수치 유지, activePosition0 | terminal lock0, C/realized/house/Claims 정산 |
| START_CALL | 기존 ACTIVE, mark 위험 | MARGIN_CALL/version+1/callEpoch+1/deadline=blocktime+60 | 자산 이동 없음; lock 보존 |
| CLEAR_CALL | MARGIN_CALL, mark 회복 | ACTIVE/version+1/deadline0 | 자산 이동 없음; lock 보존 |
| LIQUIDATE | MARGIN_CALL, mark 위험, 새 Report 관측>=deadline, blocktime>=deadline | LIQUIDATED/version+1/activePosition0 | terminal lock0, 동일 realized/badDebt 정산 |

controller는 rules의 expected version, account, side/leverage/q/entry/collateral, status와 callEpoch/deadline/lastObservedAt을 action별로 검사한다. Vault는 lock owner/amount/terminal과 전역 usedOperations를 다시 검사한다. userOperation 이벤트는 intentDigest, reportDigest, owner, positionId, operationId, action, 사용 nonce 및 직접/relay 경로를 연결한다. 자동 action 이벤트도 같은 Report/operation/position과 Vault 정산을 연결하되 user Intent가 있다고 꾸미지 않는다.

어느 호출이든 실패하면 nonce/positionOwner/rules version/usedOperations/Vault 잠금/house/Claims/events까지 전부 rollback한다. DB는 이 EVM 원자성에 포함되지 않는다. C2에는 DB 잔고로 lock을 대신하는 경로가 없다.

## 5. Vault 경제 훅과 Claims

구체 ABI는 ADR §4를 따르며 controller 외 호출자는 거절한다. OPEN/TOP_UP/CLOSE 각 operationId는 전역 한 번 사용되고 positionId는 terminal 이후 재사용하지 않는다. 소유자 변경·담보 감소 TOP_UP·terminal 재정산은 금지한다. lock record와 totalLocked를 함께 관리하고 C1의 token 수령/인출 구현은 보존한다.

정산 `P=max(unrealized,-C)`와 `badDebt=max(-equity,0)`를 math에서 산출한다. Vault도 `P>=-C`를 검사한다.

- P<=0: locked C 제거, owner free `C+P`, house `-P`; claim 없음. badDebt는 실제 수령 자본이 아님.
- P>0: 원금 C 전액 free로 반환. 기존 미지급0이면 `paid=min(P,house)`, 나머지만 claim. 기존 미지급>0이면 paid0, P 전액 후순위 claim.
- claimId는 1부터 단조 증가, 0은 없음. head도1부터. 모든 claim은 양수 originalAmount, 고정 owner/position/operation, paid/remaining을 보존하고 삭제하지 않는다.
- `processNextClaim()`은 누구나 호출, head 하나만 `min(remaining,house)`만큼 free 귀속한다. house0/빈 queue는 무변경 `(0,0)` 반환 및 지급 이벤트 없음. head 전액 지급 때만 +1. 다음 head까지 같은 호출에서 처리하지 않는다.
- fundHouse/손실 정산은 house를 재충전하되 자동 queue 순회 없음. 기존 head가 있으면 새 수익의 선지급 금지. 전체 미지급0이 되기 전 OPEN 차단. free 인출/기존 CLOSE/TOP_UP/risk는 차단 안 함.

Claims 상환은 내부 귀속이므로 외부 token 전송/owner 온라인이 필요 없다. 실제 withdraw는 C1 경로로 후속 실행한다. 현금 보존 `recognizedAssets=totalFree+totalLocked+house`, 부채 보존 `totalUnpaid=Σremaining`, 개별 `original=paid+remaining`을 별도로 검사한다. Claim을 withdraw 가능 현금에 더하지 않는다.

## 6. C2 구현 분할과 테스트 매트릭스

C1의 독립 검토와 필수 수정 완료 후 아래를 하나의 계약 금융 연결 단위로 구현한다. 큰 C3 고갈/실환경/DB/웹을 임의로 C2 PASS에 포함하지 않는다.

| 영역 | 필수 회귀 |
|---|---|
| digest/domain | 22-field 원본 golden, 다른 chain/controller/type/policy/B 서명 거부; owner/relayer/action/수량/담보 변조 |
| 권한 | 정상 직접 및 relay, 무서명 relay 거부, 타인 owner/position 거부, privileged Vault EOA/임의 caller 거부, directrisk/keeperCLOSE 위장 거부 |
| nonce | 직접→relay와 relay→직접 중복 차단, cancel 선행/후행, 오래된 nonce, user cancel 후 유효 자동 risk 유지 |
| 시간/가격 | Intent120초/Report30초 독립, 2초 미래 경계, 사용자 서명>5초 이후 새 Report 성공, 승인 가격·수량 범위 거부, 5초 서버 검사를 계약에 있다고 주장하지 않음 |
| OPEN/TOP_UP | 2/5/10 Long/Short math, free 부족, 기존 entry/q 정확, lock 대조, owner 활성1개, position 재사용·operation 중복 거부, 회복 상태 |
| CLOSE/Claims | house 충분/정확 소진/부족/0, 원금 free 반환, paid+claim=P, terminal/lock0, head가 있을 때 새 이익 선지급 금지 |
| FIFO/권한 | 두 owner A/B, 부분 지급/1wei/반복/전액/head이동/무현금 무변경, 호출자가 수혜자 바꾸지 못함, head 처리 O(1), 미지급OPEN차단·pause 독립 |
| risk/math | START/CLEAR, TOP_UP 회복, 59초 거부/60초 이후 새 Report 위험 지속 청산, stale pre-deadline Report 거부, 실현손실 C 한도/badDebt, 양수 PnL 위험 불가 fuzz |
| 원자성 | rules 성공 뒤 Vault 실패 시 nonce/rules/version/operation/owner/events 전부 rollback; Vault 성공만 남는 우회 경로 없음; terminal 재정산 거부 |
| 자산 | 다른 user free/locked를 지급 재원으로 사용하지 않음, 정식 출자/실손실만 house 증가, 현금/부채 invariant, 미귀속 token 영향 없음, C1 전송 rollback/재진입 회귀 |
| pause | admin만 신규OPEN pause; deposit/freewithdraw/TOP_UP/CLOSE/risk/cancel/fundHouse/process는 pause 중 유지, 관리자 sweep/house인출/setter 없음 |

Foundry `vm.warp` 60초는 C5의 실제 블록 timestamp/벽시계 60초 인수를 대체하지 않는다. C2 source/ABI/테스트 변경을 독립 검토하고 수정 후 관련 테스트를 재실행한다. 실패를 우회해 테스트를 약화하거나 전체 revert 지급정책으로 되돌리지 않는다.

## 7. 현재 실행 경계

이 계획 작성은 실계약 배포·DB·Anvil·브라우저·유료 호출을 하지 않는다. C1 완료 후 구현할 의존 계획이며, C2 정상 Gradle/Foundry/실환경 PASS를 선언하지 않는다. C4 이후 직접 canonical 거래의 projection, C5 Linux 내부망 실자산/Claims 및 C6 MetaMask는 별도 증거가 필요하다. 원본/B/UI/AI/STOP/ledger 및 실패 fixture는 보존한다.
