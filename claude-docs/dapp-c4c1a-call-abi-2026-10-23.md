# C4c-1a — C 호출 calldata strict ABI 단위

작성: 2026-10-23. 상태: **구현 완료 / Java 실행·독립 검토 결과는 총괄 진행 문서에서 연결 예정**. 이 문서는 C4b의 PostgreSQL 검증 또는 C4c 전체 proof 검증을 완료했다고 주장하지 않는다.

## 1. 구현과 승인 범위

신규 `custody/abi/CAbiModels.java`, `CAbiCodec.java`, `CAbiCodecTest.java`만 구현했다. 전체 18-field Report와 22-field C Intent를 immutable BigInteger/string record로 보존한다. 기존 축약 Bundle/reducer/store/schema/API·C 계약·다른 worktree는 수정하지 않았다. 실제 RPC/DB/Docker/AI 호출은 없다.

지원 범위는 controller `executeDirect/executeRelayed/startMarginCall/clearMarginCall/liquidate/cancelNonce/pauseNewPositions`, Vault `deposit/withdraw/fundHouse/processNextClaim`, token `transfer/approve/transferFrom`의 **호출 문법**이다. target 종류별로 selector를 분리한다. view getters, controller-only Vault lock/increaseLock/settle, 배포 constructor, Receipt/logs는 이 단위에서 미지원이다.

정확한 ABI는 다음 실제 Solidity를 읽어 대조했다.

| 소스 | SHA-256 |
|---|---|
| `CFuturesIntentTypes.sol` | `83153eb131dbb992385f474513bc77bef5dedf8d9c12a10f88851c18a394ae1b` |
| `FuturesReportTypes.sol` | `1017389990c40d35f04c208b98f4c2fdc04140557f4b23fa994840e7220b9a13` |
| `CCollateralizedFuturesController.sol` | `a175bdbd57ef2fcc2a6de5b1c1de631e18bab36aab67e1f664ebba459e7a2b99` |
| `CMarginVault.sol` | `8ab6a0fc5e775ac1c1fb8f9053d9dba8d48950205dff9e34a767f56acf5b30bb` |

이 지원 호출 인자의 정수는 uint256/uint8이다. int256은 settlement **이벤트** 및 미지원 privileged settle ABI에 존재하며 이 호출 단위에 가짜 signed 필드를 추가하지 않았다. 향후 Receipt decoder가 실제 signed financial event를 별도로 검증해야 한다.

## 2. strict 조건과 의도적인 경계

- calldata 최대 16KiB, signature bytes 각각 최대 512바이트. 65바이트만 강제하지 않아 empty/잘못된 서명의 실제 reverted calldata도 문법적으로 표현할 수 있다. 서명 유효성은 후속 검증기 책임이다.
- uint256 범위, uint8 상위 bit, address 상위 12바이트, bool 0/1, bytes32 정확 길이, 동적 offset의 정확한 head/tail 위치·비중첩·정렬·길이·zero padding·후행 데이터 없음 및 전체 re-encode 동일성을 검사한다.
- direct head는 41 words, relayed는 42 words, risk는 19 words다. dynamic bytes tail은 source 순서대로만 허용하며 임의 빈 gap/alias/역순 tail을 정규화해 수락하지 않는다.
- ERC-20 zero address·0 amount는 **ABI 문법상** 표현 가능하다. 실제 token/Vault가 실행 때 거절하는 의미 검증과 구분한다. constructor mint 이벤트의 zero from을 이 호출 decoder로 처리하지 않는다. 외부 mint 함수는 없으므로 whitelist에 추가하지 않는다.
- 문자열 hex의 대소문자는 byte 의미가 같아 normalize하지만 ABI word의 padding·숫자를 임의 보정하지 않는다. 큰 정수를 floating point로 바꾸지 않는다.
- 이 decoder는 가격·수량 범위 일치, owner/relayer 권한, nonce/version, 120/30/60초, Oracle/EIP-712 서명, canonical chain 상태를 검증하지 않는다. 문법상 유효한 불일치 Intent/Report와 empty signature도 round-trip 가능함을 테스트로 명시했다. `MATCH`나 전체 coverage API는 없다.

## 3. 독립 local cast 기준값

사용 도구: `cast 1.5.1-stable`, commit `b0a9dd9ceda36f63e2326ce530c10e6916f4b8a2`. `cast sig`와 `cast calldata`는 RPC/키/환경 없이 로컬 실행했다. 새 Java codec을 호출해 기대값을 생성하지 않았다.

tuple signature의 R과 I는 실제 필드 타입 순서다.

```text
R=(bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32,address)
I=(address,address,bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32)
cast sig "executeDirect(R,bytes,I)"                 -> 0xc24ad93a
cast sig "executeRelayed(R,bytes,I,bytes)"          -> 0xb6657fd9
cast sig "startMarginCall(R,bytes)"                -> 0xc5eeafdc
cast sig "clearMarginCall(R,bytes)"                -> 0xaade63fb
cast sig "liquidate(R,bytes)"                      -> 0x3f23aa97
```

위 명령의 R/I는 실제 실행 시 완전한 tuple 문자열로 치환했다. simple selector도 local `cast sig`로 확인했다: cancelNonce `cfdd7aea`, pauseNewPositions `5fefcf07`, deposit `b6b55f25`, withdraw `2e1a7d4d`, fundHouse `8746cf98`, processNextClaim `da28fbe7`, transfer `a9059cbb`, approve `095ea7b3`, transferFrom `23b872dd`.

golden fixture에서 `h(n)`은 n을 왼쪽 zero pad한 32바이트 hex, `a(n)`은 20바이트 hex다. 정수는 아래 decimal 그대로이며 **실제 경제적으로 유효한 거래나 사용자 승인이 아니다**.

```text
R value=(h(1),h(2),h(3),h(4),4,5,6,1,10,9,10,11,12,13,14,44,h(17),a(18))
I value=(a(17),a(34),h(3),h(4),h(5),h(6),4,7,8,1,10,12,13,14,15,16,17,18,19,20,21,h(22))
priceSignature=0x0102030405
userSignature=0xaabbcc

cast calldata "executeDirect(R,bytes,I)" R_value priceSignature I_value
cast calldata "executeRelayed(R,bytes,I,bytes)" R_value priceSignature I_value userSignature
cast calldata "cancelNonce(uint256)" 7
```

실제 cast 출력의 raw calldata bytes에 대한 SHA-256을 PowerShell의 표준 SHA256.ComputeHash로 계산했다. 첫 조사 명령은 구버전 Windows PowerShell에서 최신 .NET `FromHexString/HashData` API가 없어 hash 계산만 실패했다. cast 출력은 성공했으며, compatible hex 변환·ComputeHash를 사용한 다음 로컬 명령에서 아래 값을 확보했다. RPC나 전역 설정은 변경하지 않았다.

| 고정 calldata | bytes | SHA-256 |
|---|---:|---|
| direct | 1380 | `7802c5aed14c5e4ef65a17168b3f1e9842efd7975f91738d7eb741553597ecd3` |
| relayed | 1476 | `252f9fe57b5e6c61ea7b2992031e0ff4b42ac189871329e3a79ebdf70d47b391` |
| cancel 7 | 36 | `f1549845a83b6a9488866380cddb375cb1f98bedc77ba72714af34079c82f60d` |

cancel raw golden은 `0xcfdd7aea0000000000000000000000000000000000000000000000000000000000000007`이다. 테스트는 Java 결과의 전체 바이트 SHA를 독립 cast 결과와 비교하며 simple golden은 literal decode/encode도 확인한다. 실제 chain 승인 증거가 아닌 ABI 회귀 기준이다.

## 4. 테스트와 다음 연결

테스트 함수 19개를 작성했다. 모든 selector·full Report/Intent golden, uint256 최대·uint8 상위 bit·address padding·bool, zero 주소/금액의 syntax 의미 분리, empty/1/31/32/33/64/65/512 bytes, offset alias/정렬/역순/gap, 동적 길이/zero padding, unknown selector/target·mint 미지원, truncation/trailing·크기/hex, immutable 모델 및 경제 검증 미포함을 검사한다.

실제 Java 실행과 독립 검토는 아직 이 문서 작성자가 수행하지 않았다. 총괄의 새 source identity 실행과 검토 결과를 연결한 후 이 단위를 완료로 판정한다. 이후 C4c-1b Receipt/log decoder, C4c-2 proof verifier, read-only RPC/cut·전체 coverage는 별도 독립 단위다. C4b PG 단계 장애와 이 순수 ABI 구현은 의존성이 없으며 실패를 PASS로 바꾸지 않는다.

## 5. 총괄 실행 결과

`python deployment/c-abi-check.py calls`에서 Java21 직접 컴파일/test exit0, **19PASS/0FAIL/0SKIP/0ABORTED**, source 불변을 확인했다. 신규 실행 `deployment/runtime/c-abi/20261023T013821Z-8b59f9c1`, 범위 `PURE_C_ABI_CALLS`, sourceHash `034a59a01e710d8bee6ec5d8549fcea7697b6a27b528b344cbcc948e339ca27a`다. sourceHash는 이 단위 Java/전용 runner와 실제 C Solidity6개를 포함하며 C4b 저장 후보의 해시와 범위가 다르다.

컴파일 대상은 명시적으로 calls 소스3개와 launcher뿐이며 작성 중인 events 소스나 기존 app classes를 로드하지 않았다. 캐시 JUnit7개, 정제된 환경, 별도 classes/소스 snapshot/stdout·stderr/hash로 검증했고 Gradle socket/DB/RPC/Docker/유료 요청을 사용하지 않았다. 독립 최초 검토는 요청했으며 최종 판정 전에는 완료로 승격하지 않는다. 별도 C4b 하네스 검토 한도를 초기화하는 작업이 아니다.

최초 독립 검토에서 **발견된 필수 수정 없음**이다. 실제 Solidity/Java/테스트/runner/보고서와19PASS 원본 및 현재 source 차이0을 확인했다. 검토자의 재실행은 없다. strict decoder의 미지원 호출이나 canonical 형식 거부만으로, EVM이 실제 수락한 거래를 감사 MISMATCH라고 단정해서는 안 된다. 후속 proof/coverage 계층에서 그 상황은 검증 범위 부족/INCONCLUSIVE로 남기고, 완전한 증거로 확인한 사실 상충과 구분한다.
