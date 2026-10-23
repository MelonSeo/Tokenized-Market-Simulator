# C4c-2b — signed transaction envelope 검증 단위

작성: 2026-10-23. **구현 완료 / Java 실행과 독립 검토는 총괄의 새 실행 identity 결과로 연결 예정**. Receipt/math/canonical verifier, PG projection 및 실환경 거래 인수 완료가 아니다.

## 1. 구현 범위

신규 `custody/proof/CSignedTransactionVerifier.java`, 테스트 파일 및 이 문서만 작성했다. 기존 C 계약·ABI·EIP-712 verifier·store·DB·runner·보존 자료는 수정하지 않았다. crypto recovery는 검토된 `CEip712Verifier.recover`를 사용한다. 실제 DB/RPC/Anvil/HTTP/지갑·유료 서비스 호출은 없다.

API는 `verify(rawHex,expectedHash,expectedChainId)`이며 legacy EIP-155와 typed EIP-1559(type2)의 실제 signed bytes에서 signing digest·복구 sender·정확 raw hash를 계산한다. 결과는 type/hash/signingHash/chain/sender/to/nonce/gas/value/fees/calldata/accessList의 immutable proof다. raw transaction·r/s/v·SignatureData는 반환하지 않는다. calldata가 사용자/Oracle 서명을 포함할 수 있으므로 `Proof.toString()`은 type/hash/chain/sender/target/calldata 길이만 출력한다.

이 proof는 `SIGNED_ENVELOPE_ONLY_NOT_RECEIPT_CANONICAL_EXECUTION_OR_AUTHORIZATION_PROOF`다. nonce 미사용, sender 잔고, baseFee/intrinsicGas, EVM 실행 성공, 사용자 Intent와 sender 관계, Receipt·canonical inclusion을 증명하지 않는다. expected hash와 chain의 모순도 아직 canonical 거래의 MISMATCH 판정이 아니다. 후속 proof verifier가 실제 Intent·배포·Receipt와 연결해야 한다.

## 2. 정규화하지 않는 strict RLP

일반 TransactionDecoder만 믿지 않고 whitelist Node parser와 자체 canonical re-encoder를 사용했다. root list/필드 개수(legacy9/type2 12), trailing/truncation, 55/56 short/long 형태, length leading zero, 1-byte overlong string, parent list boundary를 검증한다. uint zero는 empty bytes여야 하고 leading zero 또는 32바이트를 넘는 일반 수치는 거절한다. address target은 정확히 20바이트 또는 생성용 empty다.

- raw 64KiB, calldata16KiB, RLP 깊이4/nodes2048, access entries64/storage keys총256 한도다.
- type2 accessList는 `[20-byte address,[32-byte storageKey…]]` 모양을 검증한다. EVM이 문법상 허용하는 중복 entry/key와 순서를 임의 제거하지 않는다.
- legacy v는 EIP-155 이상만 허용하고 chain `(v-35)/2`, parity `(v-35)%2`를 직접 계산한다. 일반 uint256 chain의 최대값은 v가 **258비트/33바이트**가 될 수 있어 v만 33바이트를 허용한 뒤 derived chain 범위와 재조합을 검증한다. long으로 truncation하지 않는다.
- type2 yParity는 0/1만 허용한다. r/s 및 low-s/v 검사는 기존 검토된 EOA recovery 경계에서 동일하게 적용한다.
- unprotected legacy v27/28, type1/3/4(7702)·기타 typed envelope는 명시적 `UNSUPPORTED_INPUT`이다. 지원하지 않는 타입을 legacy로 오독하거나 성공 proof로 정상화하지 않는다.
- 오류는 `C_TX_*`와 FailureKind(unsupported/malformed/resource/identity/signature)로 분리한다. raw/signature·외부 예외 cause를 반환/출력하지 않는다.

## 3. 독립 로컬 cast golden

제품 Java parser/encoder 없이 Python 표준 정수→최소 bytes 및 수동 RLP 함수를 사용했다. unsigned payload를 local `cast keccak`, 명시적인 공개 시험 정수 17/23으로 local `cast wallet sign --no-hash`, 서명 r/s/parity를 수동 RLP에 추가한 뒤 `cast keccak`과 `cast wallet address`로 기준값을 확보했다. 사용한 cast는 1.5.1-stable이다. RPC 옵션·provider·mktx/broadcast·기존 키는 사용하지 않았다. 아래 scalar는 **공개 단위시험 재료**이며 사용자 개인키나 보존 Anvil 키가 아니다.

### legacy 기준값

입력: nonce9, gasPrice20,000,000,000, gasLimit21000, to=`0x35` 반복20바이트, value10^18, data empty, chain1, 공개 시험 정수17.

```text
raw=0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a0d39364197cfb2748c108a4394329910b568adff35651e4ecd8dacccce3b977a9a025b6b7a82d8765037e2a03d671503a9906042bc093a89929b7e9acd957587a4f
hash=0xf173e0f61cf078fb71542dc018198fb91d5f20fd3adf5fac4470b6a7a45dfffc
signingHash=0xdaf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53
sender=0x252dae0a4b9d9b80f504f6418acd2d364c0c59cd
```

### type2 기준값

입력: chain31337/nonce0/priority10^9/maxFee2×10^9/gas100000/to=`0x11` 반복20바이트/value0/data010203/accessList=`[(0x22×20,[0x33×32])]`, 공개 시험 정수23.

```text
raw=0x02f8a9827a6980843b9aca008477359400830186a09411111111111111111111111111111111111111118083010203f838f7942222222222222222222222222222222222222222e1a0333333333333333333333333333333333333333333333333333333333333333380a0ef6c089f4f32bb11de95210b092a31d92b7a83b412154c226e4745d268c8295da079f85aa673565ecfabb63c5ca755f32472c4badc62758561655e555bf9194e2b
hash=0x2c473f34953e5c6e94da854bb4634734d20c12f43e2c322839d6a1c1be5e4bd1
signingHash=0x65d3db06f6f4c08745d0a483d10310b88876d900e3d75091f9d2e2cda1e98401
sender=0x3bc8287f1d872df4217283b7920d363f13cf39d8
```

여기에 적힌 raw는 재현 가능한 **공개 시험 golden**뿐이다. 실제 사용자 raw를 문서에 기록하거나 제품 proof/toString으로 출력하라는 의미가 아니다. 서명 골든은 local 계산이며 실제 네트워크 거래가 아니다.

## 4. 테스트와 후속 경계

추가 단위 테스트는 두 독립 cast golden, 별도 web3j RlpEncoder로 생성한 정상 envelope, uint256 max chain/258bit v, expectedhash/chain, unsupported/unprotected, integer zero/leadingzero/폭, target empty/20bytes, low-s/r/s/parity, accessList 모양·중복·한도, 55/56 경계, overlong RLP/trailing/truncated/root/필드 개수, recursion/nodes/길이 한도, malformed identity, 안전한 proof 문자열·반복 검증을 다룬다.

오직 signed envelope의 sender를 복구하는 단위라 changed nonce/target에 기존 r/s를 붙이면 다른 sender로 복구되는 수학적 가능성을 정상적으로 구분한다. 원본 sender와 같다는 보장은 expected hash와 후속 owner/relayer binding이 함께 필요하다. Receipt 성공이나 Intent 권한 확인을 생략해도 된다는 뜻이 아니다.

의존성은 기존 cached web3j crypto/utils/rlp4.12.2 및 bcprov1.78.1이며 다운로드하지 않았다. Java/DB/RPC 실행 PASS나 독립 검토 결과를 이 문서 초안에서 선취하지 않는다. 후속 단계는 실제 full calldata·C EIP-712·sender 역할·Receipt/Event·canonical block proof를 결합하는 별도 단위다.
# 총괄 실행·검토 결과 — 2026-10-23

아래 구현 설명 이후 실제 실행을 연결한다. `python deployment/c-proof-check.py transaction`: **20PASS/0FAIL/0SKIP/0ABORTED**, Java compile/test exit0/source 불변. 실행 `deployment/runtime/c-proof/20261023T015921Z-5e947ebb`, scope `PURE_C_SIGNED_TRANSACTION_ONLY`, sourceHash `adeb2e1f8206a36915a5e4aab5a27272d8ed68603f43ff25b7d70fd5e7135cae`다.

shared runner/고정 launcher 변경으로 기존 EIP-712도 새로 검증했다. `python deployment/c-proof-check.py`: **22PASS/0FAIL/0SKIP/0ABORTED**, compile/test0/source 불변, 실행 `20261023T015918Z-96db84e6`, source `27de9030f14a1b95809cdaac51d0a6ef589da6636168aa3d449e3a68c98877b5`. 기존22PASS 기록을 새 runner의 결과로 자동 승계하지 않았다.

최초 독립 검토에서 **발견된 필수 수정 없음**이다. 실제 RLP/parser·서명 payload/chain·258-bit v·sender/hash·한도·원문 비노출·독립 golden/테스트·원본20/22PASS와 현재 소스 차이0을 확인했다. 검토자의 재실행은 없다. signed envelope만 검증했으며 Receipt/실행 성공/nonce 사용 가능성/사용자 Intent/금융 조건/canonical 포함 여부는 아직 증명하지 않는다. 미지원 타입/strict 형식 거부도 전체 감사 MISMATCH로 단정하지 않는다.
