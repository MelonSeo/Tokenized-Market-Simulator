# C4c-1b — C 전체 이벤트 ABI 문법 codec

작성: 2026-10-23. **구현 초안 / Java 실행·독립 검토 결과는 아래에 누적.** C4b 신규 PG harness 장애와 독립된 순수 Java 단위다. PostgreSQL·Docker·Anvil·외부 API를 실행하지 않았으며 C4b BLOCKED를 성공으로 바꾸지 않는다.

## 1. 문제와 검증 경계

C4a reducer의 `RuleApplied`는 회계에 필요한 필드만 가진다. 실제 `OperationApplied`에는 action/version/price/collateral/unrealized/realized/badDebt/callEpoch/deadline이 모두 있으므로 그 축약 타입을 실제 Solidity ABI decoder로 오인하면 안 된다.

이번 단위는 **실제 전체 ABI 필드의 정확한 해석과 canonical static encoding만** 구현한다. emitter 주소는 형식 검사 후 보존하고 caller가 기대하는 TOKEN/VAULT/CONTROLLER/RULES 범주를 대조하지만 배포 주소·codeHash·chainId를 검증하지 않는다. user/Oracle 서명, Intent/Report 대응, 금융식·시간·nonce, 로그 순서, Receipt status, blockHash/canonicality, 전체 coverage와 MATCH는 후속 verifier의 책임이다.

유효한 문법과 유효한 거래는 다르다. 0 주소의 Transfer는 mint 모양으로 해석할 수 있지만 발행 권한을 입증하지 않는다. Approval의 0 owner/spender, 0 수치, uint8 255 또는 만료 순서가 뒤집힌 ReportConsumed도 ABI 타입에는 들어갈 수 있다. 이 codec에서 경제 정책을 임의로 대신 검증하지 않는다.

## 2. 파일과 정확한 ABI

신규 파일만 작성했다.

- `custody/abi/CAbiEventModels.java`: sealed Event와 전체 필드 record 16개, bounded immutable Log.
- `custody/abi/CAbiEventCodec.java`: exact topic0/개수/데이터 word 수, static word encode/decode.
- `custody/abi/CAbiEventCodecTest.java`: 독립 cast topic/data golden과 실패·경계 테스트 25개.

기존 call ABI/model/store/reducer, 금융 계약과 runner를 수정하지 않는다. Java 표준 라이브러리만 사용하며 Ethereum keccak을 Java SHA3로 잘못 대체하지 않는다. topic 상수는 아래 독립 cast 실행값이다.

| 이벤트 signature | indexed 필드 | data words | 범주 |
|---|---|---:|---|
| `Transfer(address,address,uint256)` | from, to | 1 | TOKEN |
| `Approval(address,address,uint256)` | owner, spender | 1 | TOKEN |
| `Deposited(address,uint256)` | owner | 1 | VAULT |
| `Withdrawn(address,uint256)` | owner | 1 | VAULT |
| `HouseFunded(address,uint256)` | contributor | 1 | VAULT |
| `CollateralLocked(bytes32,bytes32,address,uint256,uint256)` | operationId, positionId, owner | 2 | VAULT |
| `PositionSettled(bytes32,bytes32,address,uint256,int256,uint256,uint256,uint256)` | operationId, positionId, owner | 5 | VAULT |
| `ProfitClaimCreated(uint256,address,bytes32,bytes32,uint256)` | claimId, owner, positionId | 2 | VAULT |
| `ProfitClaimPaid(uint256,address,uint256,uint256)` | claimId, owner | 2 | VAULT |
| `CUserIntentExecuted(bytes32,bytes32,address,bytes32,bytes32,uint8,uint256,bool)` | operationId, positionId, owner | 5 | CONTROLLER |
| `CRiskExecuted(bytes32,bytes32,address,bytes32,uint8)` | operationId, positionId, owner | 2 | CONTROLLER |
| `CNonceCancelled(address,uint256)` | owner | 1 | CONTROLLER |
| `NewPositionsPauseChanged(bool)` | 없음 | 1 | CONTROLLER |
| `OperationApplied(bytes32,bytes32,uint8,uint256,uint256,uint256,int256,int256,uint256,uint256,uint256)` | operationId, positionId | 9 | RULES |
| `FuturesReportConsumed(bytes32,uint256,uint256)` | operationId | 2 | RULES |
| `EIP712DomainChanged()` | 없음 | 0 | CONTROLLER 또는 RULES |

마지막 이벤트는 OpenZeppelin IERC5267/EIP712에서 **상속된 ABI**다. 현재 immutable C controller/rules에는 domain 변경 진입점 또는 해당 emit이 없지만 컴파일된 ABI에는 포함된다. 문법 수용은 도메인 변경 가능성이나 실제 발생 사실을 추가하는 것이 아니다. TOKEN/VAULT 범주에서는 거절한다.

공통 검사는 topic0를 포함해 1~4개의 정확한 32-byte topic, event별 exact topic/data 길이, address 상위 12-byte zero padding, uint8 상위 byte 0, bool의 정확한 0/1, int256 two's complement 및 uint256 BigInteger다. payload는 최대 9 words(288 bytes), odd/non-hex/trailing/missing data를 거절한다. 동적 타입을 지원하지 않으므로 offset/length/tail을 해석하거나 관대하게 건너뛰지 않는다. 단, static uint 값 32 자체는 정상 숫자이지 무조건 잘못된 offset이 아니다.

## 3. 실제 소스와 artifact 대조

정확한 declaration과 `deployment/runtime/c-foundry-out/{contract.sol}/{contract.json}`의 event ABI에서 필드 순서·type·indexed·anonymous=false를 읽었다. 현재 소스 SHA-256:

| 소스 | SHA-256 |
|---|---|
| `CMockCollateral.sol` | `252e22290a3bff9982b88387b46fa50bbbfcedac1fb701feb159b7ad1344a3a0` |
| `CMarginVault.sol` | `8ab6a0fc5e775ac1c1fb8f9053d9dba8d48950205dff9e34a767f56acf5b30bb` |
| `CCollateralizedFuturesController.sol` | `a175bdbd57ef2fcc2a6de5b1c1de631e18bab36aab67e1f664ebba459e7a2b99` |
| `FuturesEngine.sol` | `ac8d8a8eb177045fe93431f98ae500aaaf721803200922345eb4967b51da9e40` |

root의 기존 OpenZeppelin v5.1.0 소스를 읽기 전용 대조했다. `EIP712.sol` SHA `a608140d94172183c6d8c6b229f0b78c64ab4dc77d58e5cfed4a5c5e3e83fd5a`, `IERC5267.sol` SHA `cfe67e3219c154cca752a2eb433687dc4165397d1f97e716e7fa0496a0a94c1e`다. 기존 artifact의 읽기 전용 대조는 이번 새 Java decoder의 실행 PASS를 대신하지 않는다.

## 4. 독립 golden 생성 근거

로컬 `cast 1.5.1-stable`, commit `b0a9dd9ceda36f63e2326ce530c10e6916f4b8a2`를 실제 실행했다. 네트워크/RPC 없이 위 16개 signature 각각 `cast keccak '<signature>'`로 topic0를 확보했다. 예:

```text
Transfer(address,address,uint256)
  ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef
PositionSettled(bytes32,bytes32,address,uint256,int256,uint256,uint256,uint256)
  f0d0093595ccb214443df0640d9b24560ffe0a1b34118fabc9b7bcfc425ca909
OperationApplied(bytes32,bytes32,uint8,uint256,uint256,uint256,int256,int256,uint256,uint256,uint256)
  5997542be234c7715122caa3d3458cb56eccb19559f19caa0b299736a2215fb7
EIP712DomainChanged()
  0a6387c9ea3628b88a633bb4f3b151770f70085117a15f9bf3787cda53f13d31
```

비-indexed 데이터는 다음 세 독립 cast 결과 전체를 테스트의 literal golden으로 보존했다. 테스트의 Java encoder로 정답을 생성한 것이 아니다.

```text
cast abi-encode 'f(uint256,int256,uint256,uint256,uint256)' 1000 -100 1000 25 3
cast abi-encode 'f(uint8,uint256,uint256,uint256,int256,int256,uint256,uint256,uint256)' 5 2 8000000000 1000 -2000 -1000 1000 1 1060
cast abi-encode 'f(bytes32,bytes32,uint8,uint256,bool)' <bytes32 6> <bytes32 7> 4 9 true
```

나머지 고정 layout은 cast topic 상수 + 독립 테스트 formatter의 명시 word/순서 기대값으로 검증하고, encode→decode roundtrip만을 정답 근거로 사용하지 않는다. 특히 `OperationApplied.priceE8/unrealized`와 `PositionSettled`의 signed loss, controller의 두 digest/nonce/direct를 전체 대조한다. 양수/음수 int256 extrema, uint256 max, random seeded 100개 raw signed words도 추가 대조한다.

## 5. 테스트 범위와 현재 판정

초안 25 tests는 16개 전체 이벤트 문법, inherited domainchanged의 두 허용 범주와 두 거절 범주, 정확한 indexed/data 분리, 0 주소의 mint/Approval 문법, type·padding·bool·uint8·int256, missing/extra topics, missing/trailing/dynamic-tail 데이터, immutability/hex 정규화 및 실패 이유를 검증한다.

현재 작성자의 실제 실행은 **local cast keccak/abi-encode PASS**뿐이다. Java compile/JUnit/독립 검토는 총괄의 순차 runner로 수행하고 실제 결과를 누적한다. 그 전에는 Java PASS를 선언하지 않는다.

후속은 caller/Receipt/canonical block/deployment identity/서명·필드 간 대응을 검증하는 별도 C4c 단위다. 이 decoder를 통과했다는 이유만으로 mint 허가, 사용자 승인, 가격의 진실성, 청산 조건 충족, DB 정산, C 전체 감사 MATCH 또는 full coverage를 주장해서는 안 된다.

## 6. 최초 Java 실행 실패와 fixture 보완

총괄의 첫 실행 `c-abi/20261023T014555Z-aabe383d`, 당시 source `0109ec…`: compile 성공, 이벤트 테스트 **24 PASS / 1 FAIL**. 실패는 `independentCastOperationAppliedPreservesPriceUnrealizedAndEveryRuleField`의 `C_ABI_EVENT_DATA_HEX`이며 기존 로그를 보존했다.

실제 cast 원본을 재실행해 전체 literal과 비교한 결과, 테스트에 옮기는 과정에서 `8000000000` price word의 선행 0을 두 글자 더 입력했다. 해당 한 word가 64가 아닌 66 hex 글자라 전체가 정상 578(`0x`+9×64) 대신 580글자였다. codec의 288-byte 상한이 이 잘못된 golden을 정상 거절한 것이다. 금융 코드나 decoder 허용 조건을 변경하지 않고 **테스트 literal의 두 초과 0만 제거**했다.

보완 후 전체 OP_DATA 578글자가 실제 `cast abi-encode` 출력과 case-sensitive 완전 일치함을 확인했다. 테스트 파일 SHA는 `16ee1443d33d865bf17e99915a43eadb15d02b37362f89d8c571f89024747b4a`다. 이후 JUnit 재실행·독립 검토 결과는 총괄이 기록하며 최초 실패를 PASS로 덮어쓰지 않는다.

## 7. 총괄 재실행

`python deployment/c-abi-check.py events`: compile/test exit0, **25PASS/0FAIL/0SKIP/0ABORTED**, source 불변。실행 `deployment/runtime/c-abi/20261023T014752Z-39dde769`, scope `PURE_C_ABI_EVENTS`, sourceHash `63906ab67956da990fe43f48f20e0e2c81e4d63d162422675a21d5596e39311f`다. 실제 C Solidity6개와 해당 Java/launcher/runner를 source snapshot으로 보존했다. 과거24/1 실패도 남아 있다.

Java21·cached JUnit7개만 사용했고 기존 app classes/DB/RPC/서비스/유료 API는 실행하지 않았다. 독립 최초 검토는 **발견된 필수 수정 없음**이다. 검토자가 실제 Solidity indexed/type/order, 신규 Java/테스트/문서 및25PASS 로그와 현재 source 차이0을 확인했다. 재실행은 하지 않았다. 호출 ABI와 마찬가지로 미지원/strict 형식 거부 자체를 canonical 거래 MISMATCH로 바꾸지 않는다.
