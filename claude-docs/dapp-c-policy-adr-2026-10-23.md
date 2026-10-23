# ADR C-001 — 신규 C 자산 정본, 종료와 FIFO 미지급 수익의 분리

최신 후속: T1 token-only31개와 T2 same-block opaque overlay43개의 실제 순수 테스트·독립 검토를 완료했다. T2는 최초+재검토2회 내 전역 logIndex 결함을 수정했고 원실패37PASS6FAIL도 보존했다. 실제 실행/해시는 `dapp-c-global-proof-overlay-2026-10-23.md`를 따른다. Root/prestate/canonical 진실 및 전체 coverage를 보증하지 않는다. 다음은 `dapp-c5a-anvil-only-gate-plan-2026-10-23.md`의 신규 Anvil 계약 단독 인수 하네스이며, 실제 실행 전 자원·독립 검토 Gate를 둔다. C4b 실제 PG10개 NOT_RUN/BLOCKED와 검토 예외 대기는 변하지 않았다. 아래 설계 중/구현 중 문구는 각 당시 이력이다.

최신 구현 상태(아래 이전 상태 문구보다 우선): C0~C3 계약/Claims·금융 구현과 Foundry141PASS·독립 검토, C4 순수 사용자 거래37·keeper32·Vault 현금/Claims32·cancel/pause29개 연결 및 각 최초 독립 검토를 완료했다. 실행별 hash는 각 `dapp-c4c3*` 보고서와 주 진행 문서를 따른다. C4b 실제PG10개는 NOT_RUN/BLOCKED·추가 검토 예외 승인 대기다. token-only/global cursor 연결은 설계 중이며 실제 canonical/RPC·전체 coverage·C 서비스/지갑 인수는 아직 NOT_RUN이다. 정책·기존 자산·보존 자료는 변경하지 않았다.

작성: 2026-10-23. 상태: **승인된 로컬 모의 정책 / C0~C3·C4a·순수 calldata·이벤트·서명·rules·사용자/Oracle 승인·Receipt envelope·token ledger·사용자 거래 full 금융 연결 검증·독립 검토 완료 / C4b 실환경 하네스 BLOCKED / keeper 위험 거래 연결 구현 중**. 사용자 full 연결37PASS 및 관련 순수159PASS의 실행별 identity는 `dapp-c4c3-transaction-proof-2026-10-23.md`를 따른다. 각 실행 결과는 별도 단계 보고서와 주 진행 문서를 따른다. 이 부분 검증으로 실제 PG/Anvil·canonical/full coverage·MetaMask를 완료로 표시하지 않는다. 후속 runner 변경은 별도 소스 identity와 회귀·검토로 관리하며 기존 검토를 소급 적용하지 않는다.

근거는 사용자가 제공한 `TMS_C_IMPLEMENTATION_APPROVAL_2026-10-23.md` 전체와 기존 `dapp-audit-development-worktree/claude-docs/dapp-c-custody-policy-design.md` §12~21이다. 승인 원문은 저장소 밖에 보존하며 개인키·시드·JWT·서명 원문은 이 문서에 기록하지 않는다. 과거 문서의 “승인 대기”와 원자적 전체 revert 추천은 당시 이력이다. 이번 결정은 과거 승인 사실의 소급 변경이 아니다.

후속 C4c-3b keeper 위험 거래 연결32PASS·사용자37PASS 회귀 및 최초 독립 검토 필수 지적 없음. 최신 wrapper source/범위는 `dapp-c4c3b-risk-proof-2026-10-23.md`를 따른다. 위 “keeper 구현 중”은 직전 이력이며, 다음 독립 구현은 Vault deposit/withdraw/fundHouse/processNextClaim의 원본→현금·FIFO 연결이다. 실제 C4b PG BLOCKED 및 canonical/full coverage 미검증 판정은 바뀌지 않았다.

## 1. 결정과 이전 B의 경계

| 정책 | 이번 확정 | 보장하지 않는 것 |
|---|---|---|
| C-D01 | 신규 단일 담보 토큰의 선물부터, 온체인 ERC-20/Vault가 자산 정본; DB는 canonical projection | 기존 DB 잔고·B 포지션 자동 이전, 현물 전체의 비수탁 전환 |
| C-D02 | `TMS C Mock Collateral` / `cMKRW` / 18 decimals; 고정 20,000,000을 신규 treasury에 생성 시 한 번 발행 | 추가 mint, 타인 burn, minter 변경, upgrade, 원화 상환 |
| C-D03 | 실제 유한 house; 정상 CLOSE와 미지급 수익 분리; FIFO 부분 상환; 미지급 중 신규 OPEN 차단 | 미래 지급 보증, 손익 haircut, 임의 수익 상한, 최악 이익 선예약 |
| C-D04 | 자신의 free를 자신의 EOA로 직접 인출; 유효 Report가 없는 locked는 fail-closed | Oracle 없는 항시 종료, 관리자 강제 unlock |
| C-D05 | immutable token/Vault/controller/rules, owner 직접 및 승인 relay, 위험 action은 지정 keeper, 고정 Oracle signer, 관리자 OPEN만 pause | 사용자 자산 sweep/보정, signer 교체, 전면 출금 pause, 관리자 house 인출 |

기본 분배는 표시 토큰 기준 `S=20m=H10m+U2m+R8m`이다. U는 서로 다른 두 수령 EOA에 각 1m, H는 실제 `fundHouse` 전송, R은 treasury의 미배분 자산이다. 실제 수령 주소·소유 증명 범위·분배 tx는 새로운 실행 manifest로 확정한다. 시험 EOA는 MetaMask 사용자 승인으로 기록하지 않는다. R은 지급 보증이나 자동 구제 재원이 아니다. 고갈 Gate는 별도 소규모 신규 자본으로 실행한다.

기존 `0029…`의 B 전체 감사 PASS, `dac4ee…` 취소 후보 및 C는 별도 검증 identity다. B 배포·상태·서명·DB 자산 예약을 C에 그대로 사용하지 않는다. B 취소 하네스 BLOCKED와 Windows socket 원인 미확정은 독립 C 계약 구현의 선행 조건이 아니다. 재사용한 수학·nonce·검증 개념도 C 테스트에서 다시 검증한다.

## 2. 계약 책임과 고정 배포 연결

- `CMockCollateral`: 표준 고정 공급 ERC-20. constructor에서 treasury에 한 번 mint하며 외부 mint/burn/owner/minter/setter/upgrade가 없다. fee/rebase/hook 자산을 지원한다고 주장하지 않는다.
- `CMarginVault`: 실제 token 수령과 free/locked/house 귀속, C2부터 position별 잠금과 settlement/Claims. 자산 주소와 controller는 immutable. 외부 자산 출금은 오직 owner free → 동일 owner다.
- `CCollateralizedFuturesController`(역할명): owner 직접/relay 승인, shared nonce, operation/position 귀속, rules와 Vault의 원자적 호출, OPEN pause 및 미지급 OPEN 차단.
- 새 `FuturesEngine` rules instance: 기존 금융 수학·Report 검증·포지션/version/위험/60초를 재사용하되 새 controller만 executor다. 현재 배포된 immutable B rules는 사용하지 않는다.

**배포 binding:** C2 controller constructor가 자신을 executor로 새 rules, 자신을 controller로 새 Vault를 생성한다. 외부 초기화/setController/setRules/upgrade는 없다. Vault constructor는 token을 고정하고 `controller=msg.sender`로 고정하는 최소 형태를 채택한다. C1은 계약 factory/harness가 생성해 이 관계를 검증하되 C2의 실제 생성 관계를 대신 인수했다고 주장하지 않는다.

생성 중 controller의 `code.length`는 0이므로 constructor의 code 존재 검사로 계약 배포를 거부하지 않는다. C2의 privileged 경제 훅은 `msg.sender==controller`와 실행 시 controller code 존재를 함께 요구하여 EOA가 직접 만든 Vault를 privileged EOA 금고로 사용하지 못하게 한다. C1에는 그런 lock/settle 훅 자체를 노출하지 않는다. 실제 배포 manifest는 token/Vault/controller/rules 상호 getter·codeHash·chainId·domain·policy를 모두 확인한다.

Vault의 controller 권한은 임의 EOA에게 주지 않으며 controller에도 일반적인 `call(target,data)`, 임의 lock/settle 관리자 진입점이나 사용자 free 이동 함수가 없다. 실제 경제 훅은 승인/위험 규칙이 검증된 action에서만 도달해야 한다. 각 공개 진입점을 독립 검토한다.

## 3. C1의 구현 경계와 제안 ABI

**C1은 토큰과 예치/free 인출/정식 출자만 구현한다.** C2 lock/settle/Claim 생성/거래/Oracle/nonce 경제 경로는 C1에 먼저 만들지 않는다. C1 `locked=0`은 미구현 결과이지 활성 포지션 지원 증거가 아니다.

```solidity
// 실제 이름과 custom error는 구현 단위에서 고정하되 의미는 유지한다.
constructor(address treasury); // CMockCollateral; 20_000_000 * 1e18

constructor(address asset);    // CMarginVault; controller = deploying contract
function deposit(uint256 amount) external;
function withdraw(uint256 amount) external;
function fundHouse(uint256 amount) external;
function freeBalance(address owner) external view returns (uint256);
function totalFree() external view returns (uint256);
function totalLocked() external view returns (uint256);
function houseBalance() external view returns (uint256);
function recognizedAssets() external view returns (uint256);

event Deposited(address indexed owner, uint256 amount);
event Withdrawn(address indexed owner, uint256 amount);
event HouseFunded(address indexed contributor, uint256 amount);
```

`deposit`은 caller 자신을 입금 owner로 한다. `fundHouse`는 caller가 자신의 token/allowance를 명시적으로 house에 출자하는 경로이며 LP 지분이나 환급권을 만들지 않는다. 이 함수들이 제삼자의 allowance나 token을 임의로 사용하지 못한다.

입금은 `Token.approve(Vault, amount)`와 별도 `Vault.deposit(amount)` 호출이다. 수령 전후 Vault token 잔고 차이가 amount와 정확히 일치할 때만 free/house를 인식한다. false 반환/revert/잔고 차이 불일치는 전체 rollback한다. withdraw는 caller의 free만 감소시키고 caller에게만 전송한다. Vault 감소와 owner 실제 수령 증가가 amount에 일치하는지 확인한다. 모든 외부 token 호출은 reentrancy guard로 보호하고 effects와 transfer는 같은 tx에서 함께 성공하거나 revert한다.

직접 token 송금은 `recognizedAssets`/free/house를 증가시키지 않는다. 실제 `balanceOf(Vault)-recognizedAssets`는 미귀속 차이로 읽고, 차이가 음수이면 불변식 실패다. 미귀속 자산 sweep 또는 관리자 귀속 함수는 만들지 않는다. 0 금액/0 treasury/코드 없는 asset/자기 Vault 수령 등 모호한 입력은 명시 거절하는 최소 정책이다. 일반 user 계정은 초기 EOA 범위이며 실제 코드 검사는 지원 범위를 좁히는 검사일 뿐 영구 EOA 보증은 아니다.

## 4. C2 Claims 및 정산 제안 ABI — C0 검토 대상, 아직 구현 아님

```solidity
struct PositionLock { address owner; uint256 amount; bool terminal; }
struct Claim {
    address owner;
    bytes32 operationId;
    bytes32 positionId;
    uint256 originalAmount;
    uint256 paidAmount;
    uint256 remainingAmount;
}
// controller-only; amount/PnL은 controller가 검증된 rules 결과와 대조한다.
function lock(bytes32 operationId, bytes32 positionId, address owner, uint256 amount) external;
function increaseLock(bytes32 operationId, bytes32 positionId, address owner,
    uint256 expectedBefore, uint256 amount) external;
function settle(bytes32 operationId, bytes32 positionId, address owner,
    uint256 expectedCollateral, int256 realizedPnl, uint256 badDebt)
    external returns (uint256 paidProfit, uint256 claimId);

function processNextClaim() external returns (uint256 claimId, uint256 paidAmount);
function claims(uint256 claimId) external view returns (Claim memory);
function totalUnpaidProfit() external view returns (uint256);
function headClaimId() external view returns (uint256);
function nextClaimId() external view returns (uint256);

event CollateralLocked(bytes32 indexed operationId, bytes32 indexed positionId,
    address indexed owner, uint256 delta, uint256 lockedAfter);
event PositionSettled(bytes32 indexed operationId, bytes32 indexed positionId,
    address indexed owner, uint256 collateral, int256 realizedPnl,
    uint256 badDebt, uint256 paidProfit, uint256 claimId);
event ProfitClaimCreated(uint256 indexed claimId, address indexed owner,
    bytes32 indexed positionId, bytes32 operationId, uint256 amount);
event ProfitClaimPaid(uint256 indexed claimId, address indexed owner,
    uint256 paidAmount, uint256 remainingAmount);
```

중복 `operationId`는 Vault 전체에서 차단한다. positionId 재OPEN/다른 owner 교체/종료 position 증액 또는 재정산도 차단한다. rules에서도 operation/version 중복을 검증하여 양쪽의 같은 상태 전이가 원자적으로 연결된다. `settle`는 collateral과 lock owner를 조회·대조하고 `realizedPnl >= -collateral`을 강제한다. 임의 signed PnL 호출은 controller 밖에서 불가능하며 controller는 자체 임의 값이 아니라 rules의 검증된 결과와 정확히 대조한다.

금융 값이 signed int 범위 또는 기존 수학 상한을 넘으면 revert한다. 외부 입력을 unchecked cast해 음수 손익·큰 양수로 바꾸지 않는다. badDebt는 별도 손실 기록이며 사용자 추가 채무/토큰/house 자본이 아니다.

### 4.1 O(1) 종료와 FIFO 알고리즘

1. position owner/lock/terminal/operation 검증. controller가 rules의 동일 position/version/action 결과를 확인한다.
2. 담보 `C`의 locked를 제거한다. `P<=0`이면 `L=-P<=C`, owner free에 `C-L`, house에 `L`을 귀속한다. claim은 없다.
3. `P>0`이면 담보 C 전액을 owner free로 반환한다. 기존 `totalUnpaidProfit==0`인 경우만 `paid=min(P,house)`를 즉시 house→free 귀속한다.
4. 기존 미지급이 있으면 새 이익의 즉시 지급은 0이다. 기존 claim head를 건너뛰어 새 수익자에게 지급하지 않는다. 나머지 `P-paid>0`은 nextClaimId를 한 번 증가시키며 claim으로 append한다. 최초 amount는 **해당 claim으로 실제 생성된 미지급액**이다. 전체 P와 즉시 지급액은 PositionSettled에서 별도 보존한다.
5. terminal/operation 소비/nonce/version/rules/Vault/Claim 이벤트 중 어느 것이든 실패하면 같은 EVM tx 전체 revert한다. CLOSE의 house 부족만으로 전체 revert하지 않는다.

`processNextClaim`은 인자 없는 permissionless 함수다. queue가 비어 있거나 house=0이면 `(0,0)` 등 명시적 무변경 반환을 선택하고 지급 이벤트를 내지 않는다. 기존 head에 `pay=min(head.remaining,house)`를 귀속하며 house와 totalUnpaid를 pay만큼 감소, owner free와 paid를 pay만큼 증가시킨다. 전액 지급됐을 때만 head를 1 증가시킨다. 한 번의 호출에서 다음 head까지 추가 처리하지 않는다. claim 생성 시 0 금액을 금지하므로 빈 claim을 건너뛰는 무한 순회도 없다. 완료 claim은 삭제하지 않아 원본/누적 지급/잔액을 보존한다.

새 손실 정산 또는 fundHouse가 house를 증가시켜도 자동 queue 순회하지 않는다. 기존 claim 지급은 별도 bounded process 호출이다. 미지급이 있는 동안 house 현금이 남아도 새 이익은 반드시 뒤에 claim을 만든다. keeper/관리자/owner도 선순위를 바꿀 수 없다. 반복 호출과 호출자 경합은 EVM 직렬 실행에 따라 잔액만 지급하며 외부 token 전송 없이 free 귀속하므로 수령자의 오프라인 여부에 의존하지 않는다.

## 5. 승인, 직접 호출, 자동 위험 권한

C 전용 EIP-712 domain 이름/버전/controller 주소/chainId, C policy와 accountRef를 새로 고정한다. B의 22-field bounded Intent 개념은 재사용하되 C domain으로 다시 서명해야 한다. 동일 owner의 직접/relay/cancel이 같은 `nonces[owner]`를 소비한다. 직접 호출은 msg.sender=owner를 확인하고 별도 userSignature만 생략할 수 있을 뿐 issuedAt/deadline<=120초/nonce/version/가격·수량 범위/action/담보/position/policy/Report 공통 필드는 생략하지 않는다.

제안 경로는 `executeDirect(intent, report, priceSignature)`와 `executeRelayed(intent, report, priceSignature, userSignature)`이다. 직접 경로는 외부 호출자가 자신 owner인지 검사하며 고정 relayer identity 필드가 있더라도 실제 tx.from과 혼동하지 않는다. relay 경로는 지정 relayer 및 ECDSA owner를 모두 검사한다. 두 경로는 하나의 내부 금융 실행 함수와 owner nonce·operation/position 검증을 공유한다. 직접 tx가 먼저 성공하면 지연된 relay는 nonce/version으로 거절하며 그 반대도 동일하다.

`cancelNonce(expectedNonce)`는 owner 직접 호출만 현재 nonce를 +1하고 이벤트를 남긴다. 담보 해제·포지션 종료·예약 취소가 아니며 canonical 확정 전에는 취소 완료가 아니다. SIGNED relay와 cancel의 경합은 실제 canonical 순서로 판단하고 미확정 상태를 임의 정산하지 않는다.

START_CALL/CLEAR_CALL/LIQUIDATE는 지정 keeper만 가능하다. owner 직접 일반 CLOSE로 LIQUIDATE를 위장하지 못하며 risk 경로로 정상 CLOSE를 우회하지 못한다. 자동 위험 action은 일반 거래의 user nonce를 소비하지 않고 rules의 operation/version/callEpoch/Report 및 60초 조건으로 통제한다. 사용자 cancel은 자동청산을 차단하지 않는다.

Report의 domain은 새 rules, executor는 C controller다. Oracle signer는 고정이고 30초 TTL·미래시각 오차를 rules가 검증한다. 서버 relayer 경로의 5초/provider/generation 검사는 별도 실행 안전장치이며 owner 직접 경로가 이 서버 검사를 했다고 주장하지 않는다. SIGNED 전 최대 1회 재가격은 별도 C 실행 증거와 실제 Vault 정본에 맞춰 구현·재검증하며 SIGNED 이후 동일 raw/hash/nonce는 불변이다.

관리자는 `pauseNewPositions(bool)`만 제어한다. OPEN은 `!paused && totalUnpaidProfit==0` 모두 필요하다. deposit/withdraw/TOP_UP/CLOSE/risk/fundHouse/processNextClaim/cancel/read에는 이 pause를 전파하지 않는다. Oracle는 객관적 가격 진실을 증명하지 않는다. signer/keeper 공모의 경제 손실, 고정 키 장애 및 Vault 버그에 전면 동결로 대응할 수 없는 한계는 남는다.

## 6. 필수 불변식과 감사 의미

- token totalSupply는 항상 `20_000_000*1e18`; 사용자 지갑 token과 Vault token을 실제 전송 없이 만들어내지 않는다.
- `recognizedAssets = totalFree + totalLocked + house`; 실제 Vault token balance는 그 이상이어야 하고 차이는 미귀속 자산이다. Claims는 이 현금식에 더하지 않는다.
- `claim.originalAmount = paidAmount + remainingAmount`, `totalUnpaidProfit = ΣremainingAmount`.
- payout마다 house 감소=owner free 증가=claim remaining 감소=paid 증가. deposit은 house를 늘리지 않으며 새 사용자 담보로 기존 이익을 갚지 않는다.
- terminal position은 lock0이며 정산 operation은 정확히 한 번만 처리된다. rules terminal/Vault 정산/Claims 중 일부만 남는 상태가 없다.
- 잔여 claim이 있으면 head가 가장 작은 미완료 claim이고 신규 OPEN은 불가능하다. 총 미지급 0이라도 관리자 OPEN pause는 독립적으로 유효하다.
- 양의 실현이익을 가진 위험청산 불가라는 기존 금융식 의존 명제를 C에서 다시 fuzz/invariant 검증한다. 이것이 모든 청산의 가용성 보증은 아니다.
- DB projection은 canonical blockHash/txHash/logIndex와 operation/position/claim을 연결하며 직접 거래도 서버 raw 부재만으로 오류 처리하지 않는다. MATCH는 기록 일치이지 미래 지급 가능성이나 Oracle 진실성의 보증이 아니다.

## 7. 단계별 완료 조건과 현재 상태

| 단계 | 독립 검증 범위 | 현재 |
|---|---|---|
| C0 | 승인 원문 대조, Claims ABI/FIFO/authority/nonce/직접 경로 설계 독립 검토 | 완료, 최초 검토 필수 지적 없음 |
| C1 | 고정 토큰, 실제 approve/deposit/free withdraw/fundHouse, 실제 수령·정확 전송·실패/reentrancy rollback·권한/공급 보존 Foundry 및 검토 | C131/전체83 PASS·build PASS·독립 검토 완료; 당시 소스에 귀속 |
| C2 | C 승인/domain/직접/relay/nonce 및 기존 rules와 실제 Vault lock/settle/Claims 원자성 | 권한 회귀 보완 후 전체132 PASS/build PASS·첫 독립 재검토 필수 지적 없음 |
| C3 | 두 사용자 고갈·FIFO 부분/전액·정확 소진/0/1wei·중복/재충전/OPEN 차단·badDebt | 실제 C controller의 Foundry9개/전체141 PASS·최초 독립 검토 필수 지적 없음; 실환경 아님 |
| C4 | 신규 DB projection, 직접 tx·중복·reorg·응답 유실·재시작·자산/부채 감사 | C4a28PASS; C4b 순수47PASS/실환경 하네스BLOCKED・PG10개NOT_RUN・후속 ENTRYPOINT 수정 검토 미완료; 독립 C4c-1a 진행. canonical RPC·reorg·전체 감사 NOT_RUN |
| C5 | 새 Linux 내부망 PG/Anvil, 실제 ERC20/두 사용자/Claims 및 실제 60초 유예 | NOT_RUN |
| C6/C7 | 실제 MetaMask 및 서버 없는 free 인출/유효 Report 직접 CLOSE/Oracle 중단 fail-closed | NOT_RUN |

C0 독립 검토 이후 C1 구현·관련 테스트·독립 검토·수정·재검증을 완료해 다음 의존 단계로 진행한다. 단계 보고만을 이유로 사용자 재승인을 기다리지 않는다. 실제 MetaMask 서명, 승인 정책의 실질 변경, 추가 비용·외부 권한·기존 데이터 위험만 사용자 판단 지점이다. 초기 개발은 새 C worktree/신규 로컬 모의 환경에만 한정하고 원본·UI/AI/B worktree·보존 DB/체인·ledger/STOP/claim을 바꾸지 않는다. commit/merge/push·유료 호출·외부 배포는 승인되지 않았다.

## 8. 독립 검토 및 실행 증거

승인 원문 SHA-256: `2c44fd82308a6a8e1c92cd790c270424337a79f0841d10e7fcfd001f9c593b92`. 이 해시는 원문 식별용이며 과거 평가 승인/ledger를 재발급하거나 변경하지 않는다.

C0 최초 독립 검토 `review_c_boundaries`: **발견된 필수 수정 없음**. 검토 대상 ADR 해시 `fc1cd9ea16eff70ad30fcdb09b8533d9043a45299a000f3548d807900a418ced`; 승인 전체/기존 B 수학·실제 권한과 대조했다. FIFO/부분 지급/선순위 우회 금지, terminal·Vault·nonce 원자성, 현금·부채 분리, 직접/relay shared nonce와 immutable 연결/OPEN 한정 pause를 검토했다. 이는 C1/C2 코드·테스트 검토를 대체하지 않는다. C1 구현을 진행한다.

작성자는 승인 원문, 공통 지침, 과거 C 설계와 B-v2 controller의 실제 constructor/nonce/권한 경계를 읽기 전용으로 대조했다. 이 문서 자체는 계약 또는 Foundry PASS가 아니다. 독립 C0 검토 결과와 필수 수정은 이후 이 절에 누적하며 기존 이력을 지우지 않는다.
