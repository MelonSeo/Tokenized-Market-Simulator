# C4c-3c — Vault 현금 이동 및 Claims 원본 거래 연결

## 최신 실제 실행 — 32 PASS / 최초 독립 검토 완료

별도 `review_c_boundaries`가 실제 신규 모델/검증기/테스트/runner/문서와 CMarginVault·사용 중인 OZ `_spendAllowance`를 읽고 현재 manifest SHA 및 실행 결과를 대조하여 **필수 수정 지적 없음**으로 판정했다. 검토자 직접 테스트 실행은 없었다. 실제 getter/canonical/code/RPC·PostgreSQL/full coverage는 여전히 미검증이며 currentAllowanceVerified=false/completeCoverage=false다. 아래 “검토한다”는 요청 당시 이력이다.

`c-proof/20261023T035255Z-5b8f2080`, sourceHash `880703888961da5b9ed43244519ad34d46cc582104f43ab45c62b36350104c3d`: **32PASS/0FAIL/0SKIP/0ABORTED**, compile/test0·stderr 없음·소스 불변이다. 최초 `035201Z-457c25f8`/`2e8d1f0a…`는 테스트74줄 괄호 과잉으로 compile1/JUnit NOT_RUN이었다. Input 지역변수로 문법만 수정했고 기대값·제품은 그대로이며 원래 실패 snapshot을 보존했다.

공유 runner vault mode 추가 후 user37PASS (`035235Z-2e9ae327`, source `ba7616acd85b99345b3ccb1fbd0e2689f03384d9ddb4ad8e946c4faf64aa1446`), risk32PASS (`035241Z-634d1579`, source `6b05f4d21f598061b6e8e19cbde7b5bc794ec8be32e519f87983de011d5bab6b`), Python5PASS를 실행했다. 실제 DB/RPC·현재 allowance getter·canonical 또는 전체 인수 검증은 없었다. 이 결과와 실제 신규 소스/runner/문서를 최초 독립 검토한다. 아래 NOT_RUN은 계획 또는 구현 직후 이력이다.

작성 2026-10-23. 기존 사용자/keeper wrapper, envelope, 두 reducer 및 실제 CMarginVault/OZ ERC20의 책임을 읽고 새 단위의 계획을 먼저 고정한다. 기존 검토 중 소스와 DB/계약은 수정하지 않는다.

## 입력·순서·지원 범위

새 CVaultTransactionProofModels/Verifier/Test만 작성한다. 원본 raw/full Receipt는 내부 envelope로 hash/sender/chain/target/value/모든 log metadata와 ABI를 검증한다. Context는 명시적 transaction 직전 point, Vault/token State, parent block hash, 선택 allowance observation이다. envelope 배포와 Vault 배포 및 token 배포의 chain/genesis/address를 대조하고 두 projection cursor와 token.balanceOf(Vault)==Vault.actualAssets를 전후 확인한다.

지원 call은 Vault deposit/withdraw/fundHouse/processNextClaim뿐이다. cash3개는 raw.sender/amount에 대응하는 정확 Token Transfer→Vault event 두 개를 요구한다. process는 단일 head ClaimPaid 또는 queue empty/house0 무이벤트만 허용하며 실제 현금 전송은 없다. full event를 축약한 Bundle과 같은 transaction의 token subset을 두 reducer에 각각 적용한다. FIFO·house/free·잔고 계산은 복제하지 않는다. 둘 다 성공해야 새 두 상태를 함께 반환한다.

deposit/fundHouse 성공에는 **같은 tx 직전 point**, owner=raw.sender, spender=Vault의 명시적인 allowance observation과 allowance>=amount가 필요하다. 기존 Approval 로그를 현재 allowance로 추정하지 않는다. 이는 입력 assertion의 일관성을 검사할 뿐 실제 RPC getter를 조회하거나 post-tx allowance를 보장하지 않는다. OZ5.1 transferFrom 소비는 Approval을 emit하지 않으므로 추가 Approval을 정상 cash event처럼 허용하지 않는다. withdraw/process에는 allowance·EOA code·사용자 nonce·Oracle context를 요구하지 않는다.

status0·no logs는 승인·실패 원인을 추정하지 않는 금융 불변 관측이다. 미지원 user/risk/cancel/pause/token 직접 call은 UNSUPPORTED이며 가짜 ProcessClaim으로 변환하지 않는다. 검증 실패 시 성공 상태를 일부만 반환하지 않고 원래 immutable State를 보존한다.

## 검증 계획과 한계

약30개 순수 JUnit으로 cash3개, 실제 allowance 키/point/값·무한 allowance, zero/self/잔고부족·extra Approval/잘못된 Transfer·event 순서, process empty/house0/partial/full/한 head FIFO, 권한 독립·status0·배포/cursor/자산/seed·rollback·resource·불변성을 다룬다. 공개 시험키17/23/29만 사용한다.

scope PREVALIDATED_CONTEXT_ONLY, completeCoverage=false/currentAllowanceVerified=false다. 실제 code/RPC/canonical·allowance getter 진실·DB 저장·외부 네트워크·전체 감사 MATCH는 검증하지 않는다. 현재 구현·실행·독립 검토 NOT_RUN.

## 구현 상태

새 Models/Verifier 및 **32개 JUnit**을 작성했다. 내부 envelope와 두 reducer를 실제 호출하며 외부 Proof 결과를 입력받지 않는다. 테스트는 별도 기존 사용자 fixture helper를 composition으로 재사용하고, 실제 user/risk wrapper·모델·테스트·runner는 수정하지 않았다. 작성 완료는 실행 PASS가 아니며 compile·JUnit·독립 검토는 아직 NOT_RUN이다.

deposit/fund allowance의 누락·키/point 불일치는 INSUFFICIENT, 충분하지 않은 완전한 uint256 값은 REJECTED다. 무한 allowance도 uint256 그대로 비교하고 소비 뒤 실제 allowance는 계산/반환하지 않는다. 금융 이벤트의 정확한 순서·금액 및 FIFO 계산은 하위 reducer가 최종 거절하므로 중간 새 Vault 상태가 만들어져도 token 검증 실패 시 외부로 반환하지 않는다.

process 테스트의 선행 Claims/house snapshot은 기존 reducer 관측으로 구성한 fixture이며, 신규 원본 transaction wrapper의 실행 검증과 구분한다. 한 호출에서 두 head를 처리하거나 caller에게 수익을 지급하지 않는다. 추가 Approval이 없는 실제 OZ 소비 규칙과 무이벤트 process/실패 관측을 별도로 검증한다.
