# C4c-2c-2a — 사용자 및 Oracle 승인 정책 순수 검증

작성: 2026-10-23. 초기 계획: 검토된 C controller/Intent/rules 및 C ABI·EIP-712 검증기를 재사용하여 **caller가 제공한 명시적 prestate context 안에서** 사용자 거래 승인만 검사한다. 구현·실행·검토 결과는 아래에 누적한다.

## 먼저 고정한 범위

신규 authorization 모델/verifier/test만 작성한다. direct 또는 relay 일반 OPEN/TOP_UP/CLOSE를 대상으로 실제 Report 65-byte Oracle 서명과 relay의 실제 user 65-byte 서명을 내부에서 다시 검증한다. 외부에서 만든 public cryptoProof를 신뢰하지 않는다.

Context는 fixed C controller/rules/relayer/Oracle/chain/symbol/policy, 실제 sender라는 사전 검증 입력, Ethereum tx nonce, timestamp 및 block/hash/txhash/txindex를 포함한 명시적 observation point다. Keyed Prestate의 owner nonce/positionOwner/full rules position과 owner code observation은 같은 point에 연결되어야 한다. 누락·다른 point는 명시적 INSUFFICIENT이며 비어 있는 code를 기본값으로 만들어 EOA라고 가정하지 않는다. Ethereum tx nonce는 기록용으로만 보존하며 사용자 Intent nonce와 같다고 요구하지 않는다.

검사 범위: Solidity C namespace accountRef, owner·EOA code 관측, direct caller 또는 relay caller와 사용자 서명, 120초 window·shared user nonce 및 overflow, 22-field Intent와 Report 공통 필드 연결, 가격·수량 유한 범위, OPEN entry0/수량식 및 TOP_UP/CLOSE의 정확한 기존 entry/quantity/collateral/version/callEpoch. 고정 identity의 Report.executor/symbol/policy 및 Oracle 서명을 확인한다.

**검사하지 않는 것:** caller가 제공한 sender/prestate/code/RPC의 진실성·canonical성, Report TTL/위험·60초와 rules 상태 전이 자체, operation-used/active-position 집합, Vault lock/자산·Claims·OPEN pause/미지급 차단, Receipt/Event/DB/전체 감사 MATCH. 이 조건들은 reviewed rules 검증기와 후속 wrapper에서 반드시 결합한다. 결과 scope는 PREVALIDATED_CONTEXT_ONLY이며 completeCoverage=false다.

## 구현 후 기록

최초 독립 검토 `/root/review_c_boundaries`: **발견된 필수 수정 없음**. 실제 controller의 direct/relay/_authorize/accountRef와 신규 소스/테스트, runner의 고정 selector·stderr fail-closed를 대조했다. authorization33 및 공유 rules34/EIP22/raw20의 원본 manifest·로그와 현재 소스 해시 불일치0을 확인했다. 검토자의 재실행·파일 수정·서비스 접근은 없으며 C4b 검토 주기를 다시 시작하지 않았다. 이 검토가 실제 sender/code/prestate·Report TTL/Vault/Receipt/canonical 검증을 대신하지 않는다.

총괄 실제 실행 `deployment/runtime/c-proof/20261023T024320Z-1bbf1900`: **33PASS/0FAIL/0SKIP/0ABORTED**, compile/test exit0, compile/test stderr 없음, source 불변. sourceHash `e4ecdb1b196b9ea0106e9124a135bfea20af71d366d6acf8dce8eccdc2811066`. 캐시 crypto/JUnit과 JDK21만 사용했고 DB/RPC/유료 호출0이다. 최초 독립 검토를 요청했으며 아직 그 결과를 완료로 선취하지 않는다.

공용 runner에는 compiler exit0이라도 stderr가 남거나 JUnit 미실행·stderr·timeout·소스 변경이 있으면 실패하도록 보완했다. Python5PASS, 변경 후 기존 rules34/EIP22/raw20 회귀 PASS이며 실행 identity는 주 진행 문서 및 rules 결과 문서의 후속 실행 절에 보존했다. 이 하네스 delta도 이번 최초 검토 범위에 포함한다.

accountRef의 별도 총괄 local cast 계산도 owner `0x11…11`, symbol `0x33…33`에서 `0x3b700c6618dfd86828ae9c5ffd523221face1cc6508e6d1dcb304bf84926c289`와 일치했다. domain은 `0xad4b5ba2035096cf74596cf39e97448918a89325f077e214ed9ae5d5bce842bc`, 정확한 ABI96byte를 keccak한 결과이며 RPC를 호출하지 않았다.

신규 `CAuthorizationModels.java`, `CAuthorizationVerifier.java`, `CAuthorizationVerifierTest.java`를 구현했다. 모델의 원문 서명을 가진 Input은 안전한 toString으로 가리며 오류는 고정 `C_AUTH_*` 코드와 INSUFFICIENT_CONTEXT/REJECTED를 구분한다. 공개 시험 정수17/23만 사용했다. Java 실행·독립 검토 결과는 총괄의 새 source 실행과 연결하기 전에는 PASS로 선취하지 않는다. 다른 제품/runner/schema·보호 worktree·DB/RPC·서비스·기존 키·유료 호출은 변경하지 않았다.

accountRef의 실제 Solidity 계산은 `keccak256(abi.encode(keccak256("TMS_C_FUTURES_WALLET_ACCOUNT_V1"),owner,symbol))`다. 즉 96 bytes이고 owner는 두 번째 word의 끝20바이트에 놓인다. 제품 Java encoder 없이 local cast1.5.1 `keccak`으로 domain `ad4b5ba2035096cf74596cf39e97448918a89325f077e214ed9ae5d5bce842bc`를 계산한 후 수동96바이트를 hash했다. owner=`0x11`×20, symbol=32-byte uint6일 때 `6453a33a384c95ac2e22861641952671527656a5ba5e1079f8454aad0cf620ac`다. 별도 총괄이 독립 확인한 동일 owner/symbol=`0x33`×32의 값 `3b700c6618dfd86828ae9c5ffd523221face1cc6508e6d1dcb304bf84926c289`도 literal test로 고정했다. cast는 RPC/키 없이 로컬 계산만 수행했다.

테스트 범위는 정상 direct/relay OPEN 및 TOP_UP/CLOSE, 22개 Intent/18개 Report 필드 각각 변조, 실제 user/Oracle 서명, 직접·relay sender 및 고정 relayer, tx nonce와 user nonce 분리/overflow, 120초 경계·미래·만료, code 누락/다른point/key/관측된bytecode, prestate 누락/point/키/owner/full position, 유한 가격·수량 범위와 OPEN q식, 기존 entry/q/담보 제약, chain/rules/Oracle domain, terminal/risk action 우회 거부 및 부분 scope·민감정보 방어다.

빈 code라는 caller 관측은 그 시점의 코드 없음 assertion일 뿐 영구 EOA·실제 사용자 독립 키 보유의 증명이 아니다. Report가 만료됐지만 사용자 window 안인 fixture도 이 승인 단위만 통과할 수 있음을 명시적으로 테스트했다. 후속 wrapper는 reviewed rules verifier의 Report TTL/시간/위험·전이 검증과 Vault 및 canonical Receipt 검증을 반드시 결합해야 한다. 이 단계 성공을 최신 체인 또는 전체 거래 인수로 승격하지 않는다.
