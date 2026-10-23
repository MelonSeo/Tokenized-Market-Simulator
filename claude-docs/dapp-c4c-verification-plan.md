# C4c 검증 계층 계획 — ABI·승인 증거·canonical RPC·전체 coverage

## 최신 실행 경계 — 2026-10-23 후속

후속 Vault cash/Claims32개와 controller cancel/pause29개도 각 실제 실행·최초 독립 검토 필수 지적 없음으로 완료했다. 공용 runner 최종 control 확장 뒤 user37/risk32/Vault32 회귀 및 Python5PASS를 확인했다. 상세 `dapp-c4c3c-vault-proof-2026-10-23.md`, `dapp-c4c3d-controller-control-proof-2026-10-23.md`. 아래 “다음 Vault” 등은 이전 상태 기록이며 현재 다음 작업은 token-only/global cursor 설계다. actual canonical/RPC·PG·전체 coverage 판정은 바뀌지 않았다.

아래 세부표/진행 중 문구는 최초 계획 당시 이력이다. 현재 strict calldata19·full 이벤트25·EIP22·signed raw20·rules34·authorization33·Receipt envelope25·token ledger25·사용자 full 거래37·keeper full 거래32의 각 순수 단위를 실행·독립 검토했다. 모든 숫자는 해당 소스별 보고서의 실행이며 전체 backend나 실제 C chain 인수가 아니다. keeper 추가 이후 공통 wrapper 사용자37개도 새로 PASS했다. 최신 source/검토는 `dapp-c4c3-transaction-proof-2026-10-23.md`, `dapp-c4c3b-risk-proof-2026-10-23.md`를 따른다.

다음은 별도 Vault deposit/withdraw/fundHouse/processNextClaim proof다. token-only 거래와 cancel/pause는 별도 후속이며 현재 모두 지원하는 것으로 표시하지 않는다. 특히 token-only Approval/0/self/wallet↔wallet 관측을 가짜 ProcessClaim/Donation으로 Vault 장부에 넣어 cursor를 전진시키지 않는다. token 현재 allowance는 Approval 이벤트에서 추정하지 않는다. 성공 deposit/fund에는 동일 prestate point의 owner/spender allowance assertion을 요구하되 실제 RPC 진실성과 persistence는 후속 과제다.

### 실제 scanner 전 선행 설계 조건

- 두 projection의 동일 cursor는 현재 지원 거래 내 연결 보장이다. 독립 token-only tx가 끼어드는 실제 체인은 명시적인 무-Vault효과 관측 또는 공통 coverage cursor 모델로 표현해야 한다. 금융 action을 위조해서 맞추지 않는다.
- full rules 상태·owner/nonce/code/allowance의 transaction 직전 증거는 아직 외부 assertion이다. 역사 RPC의 block N 끝 상태를 N 안의 매 transaction 직전 상태로 사용하지 않는다. 배포 이후 순서 replay와 명시적 역사 읽기의 연결이 필요하다.
- C4b version1 저장은 reduced Vault snapshot용이다. token/full rules/raw 및 envelope 증거의 저장·통합 checkpoint가 구현됐다고 간주하지 않는다. 새 스키마/codec의 additive 변경과 crash/replay/CAS 원자성을 별도 검증한다.
- canonical cut·배포 codeHash/getters·역방향 전체 tx/log/내부 호출 coverage를 확보하기 전에는 순수 verifier의 VERIFIED_IN_SCOPE를 전체 MATCH로 승격하지 않는다. 누락/미지원/trace 부족/움직이는 cut은 INCONCLUSIVE다.
- 실제 PG10개는 아직 NOT_RUN/C4b BLOCKED이고 검토 한도 예외를 기다린다. 이 제한을 새 이름의 하네스나 무한 fixture로 우회하지 않는다. 독립 pure 단위는 계속 진행한다.

작성: 2026-10-23. 실제 C 계약을 읽은 후 작성한 계획이다. **후속 순수 ABI·서명·rules 단위는 별도 실행·독립 검토를 완료했고 사용자 승인 연결은33PASS/최초 검토 중이다. 실제 RPC/canonical·전체 coverage·DB 연동은 NOT_RUN이다.** 단계별 source identity와 검증 결과는 주 진행 문서 및 각각의 결과 보고서를 따른다. C4a accounting reducer와 C4b codec/store는 이 계층을 대신하지 않는다. 정책 기준은 C 승인 원문 및 [ADR](dapp-c-policy-adr-2026-10-23.md)이다.

## 1. 기존 축약 모델의 한계와 새 proof envelope

`CCustodyModels.UserCall/RiskCall`에는 전체 22-field Intent, 18-field Report, 사용자/Oracle 서명, tx envelope, raw Receipt, block timestamp, codeHash가 없다. `RuleApplied`도 원본 이벤트의 금융 필드를 일부 축약한다. 따라서 DB에 codec round-trip된 Bundle이 있다는 사실은 승인·정산·canonical 증명이 아니다. 순수 reducer는 여전히 `completeCoverage=false`이며 전체 MATCH를 반환하지 않는다.

새 계층은 최소한 다음 원본을 보존하는 별도 `CTransactionProof`(역할명)를 도입한다.

- deployment identity와 실제 chainId/genesis/deployment block·tx/codeHash/domain/정책.
- 실제 signed tx envelope 또는 hash/sender를 독립 검증할 수 있는 RPC 원본, 전체 calldata, tx type/nonce/value/chainId/from/to/index.
- receipt status/hash/from/to/block number/hash/index 및 전체 logs의 address/topics/data/removed/index.
- block header hash/parent/number/timestamp와 tx membership/순서, 명시적 cut 및 조회 출처.
- 전체 Intent/Report의 정확한 정수·주소 필드와 서명 검증 결과. 성공적인 direct 경로는 userSignature가 없는 것이 정상이다.
- 해당 block/transaction 순서의 pre/post 상태를 재구성하기 위한 배포부터의 replay 또는 신뢰할 수 있는 역사 증거. 같은 block N의 모든 tx마다 N-1 상태를 직전 상태로 취급하지 않는다.

API/공개 로그에는 JWT·개인키·원문 서명·raw transaction을 출력하지 않는다. 비밀이 아닌 tx hash/주소/phase reason/소요시간만 공개하고 필요한 proof 원본은 소유한 private evidence/제한 저장으로 분리한다. 어떤 proof가 아직 없고 어떤 단계까지만 검증됐는지 명시한다.

## 2. 독립 구현 단위와 선행 조건

| 단위 | 구현 역할 | 통과 기준 / 범위 제한 |
|---|---|---|
| C4c-1 순수 strict ABI decoder | `CAbiDecoder`, full `CIntent/CReport/CProofModels` | 모든 public action selector·이벤트 topic/정수/tuple/dynamic bytes를 정확 decode/re-encode; unknown은 거절, RPC 없음 |
| C4c-2 순수 승인·Receipt verifier | `CTransactionProofVerifier` | 실제 tx envelope·owner/Oracle digest·action별 log 연결·math/time 검증; 고정 golden/변조 회귀 및 독립 검토 |
| C4c-3 읽기 전용 identity/cut reader | `CReadOnlyRpc`, `CDeploymentVerifier` | explicit block·codeHash·getter·header canonical 재확인, 실패/timeout/한도 INCONCLUSIVE; sender/private key 없음 |
| C4c-4 bounded coverage/replay 감사 | `CCustodyCoverageObserver`, `CCustodyAuditService` | forward proof + reverse tx/log/trace 집합 대응·token/Vault/claims 합계, canonical 안정 cut; 불완전 범위는 complete=false |
| C4c-5 신규 실환경 연결 | C5 Linux 내부망 PG/Anvil harness | 두 사용자/실제 ERC20·Claims·실제 60초 및 장애/restart; 기존 fixture·DB/체인 미사용 |

각 단위를 관련 테스트·독립 검토·수정·재검증한 뒤 의존 단계로 진행한다. scanner 전체를 검토되지 않은 하나의 대형 구현으로 만들지 않는다. C4b의 epoch1 저장 제약은 이 계획의 reorg 완료 근거가 아니다.

현재 세부 순서: C4c-1a 호출 ABI19PASS/독립 검토 완료 → 독립 C4c-1b 이벤트 ABI25PASS/독립 검토 완료 → C4c-2a typed digest/EOA recovery22PASS/독립 검토 완료 → C4c-2b signed legacy/type2 tx의 sender/hash/chain 검증(진행 중) → C4c-2c 전체 action/Receipt·경제·시간·상태 연결(미구현). 2a의 서명 복구만으로 실제 tx caller를 신뢰하지 않도록 2b를 별도 검증 단위로 분리했다. 단계 이름의 구체화이며 사용자 승인 정책 변경이나 C4b 검토 회차 초기화가 아니다.

## 3. strict ABI와 실제 계약 이벤트 순서

controller의 실제 함수는 `executeDirect(Report,bytes,Intent)`, `executeRelayed(Report,bytes,Intent,bytes)`, `startMarginCall/clearMarginCall/liquidate(Report,bytes)`, `cancelNonce(uint256)`, `pauseNewPositions(bool)` 및 read getters다. Vault는 `deposit/withdraw/fundHouse(uint256)`, `processNextClaim()`과 controller-only lock/increaseLock/settle다.

decoder는 canonical ABI padding/offset/alignment·주소 상위 12바이트·bool 0/1·uint8 상위 bit·signed int256·정확 bytes 길이·중복/겹친 dynamic 영역·후행 calldata를 검증한다. 전체 원본 calldata를 re-encode하여 동일성 확인한다. 32바이트 full uint/int는 BigInteger로 처리하고 Number/double로 변환하지 않는다. 버전/contract identity에 맞는 selector만 수락한다. view call과 실패한 unknown selector를 성공 금융 action으로 해석하지 않는다.

strict canonical 형식은 구현된 decoder의 지원 범위이지 모든 EVM 수락 calldata의 전체 집합은 아니다. 미지원 selector/표현이나 decoder의 형식 거부만으로 체인 거래의 MISMATCH를 선언하지 않는다. 그런 proof는 INCONCLUSIVE로 분류하고 완전한 근거의 실제 상충만 MISMATCH로 판정한다.

성공 tx 로그의 실제 순서는 다음과 같다.

| 동작 | 필수 순서 |
|---|---|
| deposit/fundHouse | token `Transfer(caller,Vault,amount)` → Vault `Deposited/HouseFunded` |
| withdraw | token `Transfer(Vault,caller,amount)` → Vault `Withdrawn` |
| OPEN/TOP_UP | rules `OperationApplied` → `FuturesReportConsumed` → Vault `CollateralLocked` → controller `CUserIntentExecuted` |
| CLOSE | rules 2개 → 미지급 생성 시 `ProfitClaimCreated` → `PositionSettled` → `CUserIntentExecuted` |
| LIQUIDATE | rules 2개 → 동일 settlement 이벤트 → `CRiskExecuted` |
| START/CLEAR | rules 2개 → `CRiskExecuted`; Vault 자산 이벤트 없음 |
| Claims 상환 | Vault `ProfitClaimPaid` 정확히 head 1개; token Transfer 없음 |
| Claims 무변경 성공 | 해당 calldata+canonical status1, 지급 이벤트 없음. 지급 성공 건수 증가 금지 |
| cancel/pause | controller의 `CNonceCancelled/NewPositionsPauseChanged`; 자산 이동 없음 |

모든 log는 동일 tx/receipt/blockHash/index에 속해야 한다. 정확 emitting address·topic 개수·data 길이·operation/position/owner/금액·순서를 확인한다. 잘못된 주소에서 같은 topic이 발생해도 수락하지 않는다. status0은 canonical revert이며 Receipt 로그는 없어야 한다. revert 원인은 추가 증거 없이 TTL 등으로 단정하지 않는다.

## 4. 사용자/Oracle 승인과 금융 결과 검증

- C 사용자 domain: `TokenizedMarketCFuturesUserIntent`, `1`, 실제 chainId, C controller 주소; struct 이름 `CFuturesIntent`와 실제 22개 필드 전부 hash한다.
- Report domain: `TokenizedMarketFutures`, `1`, 같은 chainId, **새 내부 rules 주소**. 실제 18-field Report hash와 Oracle signer를 확인한다. 같은 문자열 domain 이름을 쓰더라도 B 주소·policy·서명을 재사용하지 않는다.
- direct: tx signer/from=Intent.owner, controller target 및 direct selector; 추가 사용자 메시지 서명은 요구하지 않는다. relayer 필드는 고정 relayer를 유지하지만 tx.from이 relayer라고 잘못 요구하지 않는다.
- relay: tx signer/from=고정 relayer, userSignature가 owner를 복구하고 Intent.relayer=고정 relayer. API JWT만으로 거래 승인을 대체하지 않는다.
- risk: 지정 relayer/keeper만, user Intent 없이 독립 rules action이다. 정상 CLOSE로 위험 action을 위장하거나 자동청산이 사용자 nonce를 소비했다고 기록하지 않는다.
- owner/nonce/position/version/callEpoch/operation/accountRef/symbol/policy·담보/방향/레버리지·가격/수량 범위를 실제 calldata와 이벤트에 모두 연결한다.
- 120초 사용자 window, Report `validUntil=observedAt+30`, 최대 미래 2초, 실제 block timestamp와 마진콜 60초/새 관측가격 조건을 확인한다. direct가 서버의 5초/provider/generation 검사를 수행했다고 주장하지 않는다.
- q floor, 음수 PnL floor, MM ceil 5%, 실현손실 담보 한도/badDebt를 정확 재계산하고 OperationApplied의 전체 금융 값·position post-state 및 Vault 정산과 대조한다.
- `positionLocks.amount`는 terminal0이지만 rules position.collateral은 과거 C를 보존한다. 두 값을 무조건 동일 비교하지 않는다. terminal 때 lock0·owner/정산/과거 담보 연결을 확인한다.

tx envelope 복구는 처음에는 로컬 Gate가 사용하는 legacy EIP-155/type2를 명시 지원하고 type3/7702 등 미지원은 INCONCLUSIVE로 둔다. EOA code 검사는 시점·delegation·생성자 경계의 한계가 있으므로 빈 code만으로 영구 독립 EOA를 증명했다고 하지 않는다. 내부 호출의 msg.sender를 단순 tx.from으로 대체하지 않는다. direct internal-call 형태를 지원할 수 없으면 증거/trace를 요구하고 미지원 범위를 남긴다.

## 5. 고정 공급·지갑 원장과 Vault 원장의 분리

현재 CMockCollateral은 생성 시 treasury에 `20_000_000*10^18`을 `_mint`하며 최초 Transfer의 from은 **zero address**다. C4a의 Vault accounting `Transfer` 입력과 codec은 zero owner를 허용하지 않으므로 mint를 억지로 그 모델에 넣지 않는다.

별도 token-supply/wallet proof 타입에서 zero address를 “생성 발행”으로만 허용한다. 실제 생성 Receipt·constructor treasury·고정 supply/codeHash 및 한 번의 mint를 확인하고 이후 mint/zero-to burn/관리자 발행을 정상화하지 않는다. 외부 지갑 간 Transfer와 Approval/allowance도 별도 범위다. 예치·출자·인출에서는 해당 Transfer를 Vault 이벤트와 연결하여 중복 합산하지 않는다.

전체 공급식은 토큰 owner 잔고의 합으로, Vault 현금식은 `totalFree+totalLocked+house`로, Claims 부채식은 `Σremaining`으로 각각 검증한다. token wallet 표시는 Vault 안의 free/locked와 별개다. token 직접 송금은 실제 balance 증가지만 free/house가 아니며 미귀속 차이로 보고한다. `Approval`은 자산 이동이나 사용자 거래 Intent가 아니다.

## 6. canonical cut, 전체 coverage와 reorg

각 read는 명시 cut block을 사용한다. chainId+genesis+배포 blockHash, token/Vault/controller/rules codeHash, controller relayer/admin/rules/vault, Vault asset/controller, rules executor/signer/symbol/policy getter를 확인한다. 가능한 EIP-1898 blockHash 기반 읽기 또는 block-number 읽기 전후 hash 재확인을 사용한다. historical 자료 누락·LATEST fallback·moving cut은 전체 MATCH 금지다.

감사 시작 범위는 실제 deployment부터 확정한 cut까지다. token mint/분배, deposit/withdraw/fundHouse, 정상/위험 거래, cancel/pause, Claims 지급/무변경 처리, 직접 송금, 실패 tx까지 범위에 넣는다. 다음 두 집합을 역방향으로 대조한다.

1. 로그: token/Vault/controller/rules의 전체 관련 logs가 forward 검증한 이벤트 집합과 일치하는가?
2. 거래/내부 호출: 해당 주소를 직접 호출한 전체 tx와 내부에서 호출된 경로, status0/no-event 실패 및 successful no-op이 모두 관측됐는가?

로그만으로는 실패한 internal call 또는 로그 없는 internal invocation을 찾을 수 없다. trace/call-tree 제공 여부를 capability로 표시하고, 요구 coverage가 internal-call 전체인데 trace가 없으면 `complete=false/INCONCLUSIVE`다. 직접 target tx만 훑고 “전체 체인 coverage”라고 하지 않는다. 미지원 trace 형태/unknown selector·contract creation 내부 호출도 같은 원칙이다. 지원하지 않는 상황을 금융 실패로 임의 정산하지 않는다.

block/tx/log/RPC/owner/position/claim 수와 총 deadline을 제한한다. 한도 초과 시 확인한 범위를 남기고 INCONCLUSIVE로 종료한다. Claimed queue 전체를 무제한 읽거나 Solidity process가 O(1)이라는 이유로 전체 감사를 O(1)이라고 하지 않는다.

canonical reorg는 기존 observation/epoch를 보존하고 새 canonical epoch가 공통 조상부터 재구성되도록 한다. C4b가 epoch1+CAS 저장만 제공하는 단계에서는 fork mismatch에 fail-closed하고 **reorg recovery 미구현**이라고 기록한다. projection을 덮어써 과거 관측을 소급 성공으로 바꾸지 않는다. C4a가 보존 ancestor에서 다른 branch를 재생할 수 있다는 사실은 실제 canonical fork 선택·DB epoch 전환의 PASS가 아니다.

## 7. C5 신규 로컬 실환경 인수 계획

새 Linux JVM/전용 내부 Docker network/호스트 포트 미게시 PG+Anvil을 우선한다. 기존 서비스·컨테이너·볼륨·DB/Anvil·AI ledger/STOP/claim을 사용하지 않고 자원/소유권을 먼저 검사한다. 별도 시험 relayer/Oracle/admin/두 EOA, 새 token/Vault/controller/rules 배포와 실제 codeHash/identity를 고정한다. 시험 EOA 서명을 실제 MetaMask 인수로 기록하지 않는다.

필수 실행 묶음:

- 실제 최초 공급·두 사용자 분배·정식 house 출자/approve/deposit·owner direct free withdraw.
- direct 및 relay OPEN/TOP_UP/CLOSE, 사용자 서명 후 지연+새 Report, canonical logs와 실제 Vault/token/projection 일치.
- house 충분/정확 소진/부족/0, terminal+Claims, A/B FIFO 부분/전액·1wei·신규 OPEN 차단·신규 사용자 담보 비전용.
- Long/Short 자동 마진콜과 실제 60초 block timestamp/벽시계 경과, 이후 새 Report 청산·정산.
- 같은 block 여러 owner tx, nonce cancel/relay 경합, 실패·무변경 tx, 직접 송금/공급 및 wallet 원장 분리.
- DB commit 전 실패/commit 응답 유실/별도 JVM 재시작·중복 apply; chain 성공을 재전송하지 않고 projection 복구.
- 코드/owner/signature/digest/Receipt/event/journal 수치 변조 MISMATCH; historical/trace/coverage 부족 및 pending INCONCLUSIVE.
- 새 시험 체인에서만 reorg 실패 주입. epoch 전환이 아직 없으면 안전 중단까지 검증하고 복구 완료로 표시하지 않는다.

실제 서비스 인수와 연결할 때도 C4a/b의 단위 PASS, Mock proof 검증, 실제 PG, 실제 RPC/Anvil, 실제 MetaMask 결과를 각각 남긴다. C4c는 관측·검증만 수행하고 token 이동/서명/거래 재전송·금융 장부 보정 API를 갖지 않는다. 모델 호출·기존 환경 변경·원본 병합·commit/push·외부 배포는 이 계획에 포함하지 않는다.
