# T1 — Vault 자산 영향0 token-only 원본 거래 검증

## 최신 실제 실행 — 31 PASS / 최초 독립 검토 완료

별도 `review_c_boundaries`가 실제 token/OZ·envelope·ledger, 신규 소스/테스트/runner/문서와 실행 manifest SHA를 대조하여 **필수 수정 지적 없음**으로 판정했다. 검토자 직접 실행은 없었다. ZERO_DELTA는 normalized declaredVault 한 주소에 대한 입력 assertion 범위이며 실제 배포 인증/금융 cursor 진행 권한이 아니라는 점도 확인했다. 아래 검토 요청/NOT_RUN은 당시 이력이다. 실제 getter/canonical/생성 배포/T2/PG/full coverage는 여전히 미검증이다.

`c-proof/20261023T041722Z-1011c24c`, sourceHash `44114b9da0c60ba318648d5e6eff065010152190d21680601fe2b5fb60626ae0`: **31PASS/0FAIL/0SKIP/0ABORTED**, compile/test0·stderr 없음·소스 불변이다. 실제 DB/RPC/유료 호출0이다. 이 결과와 신규 소스/문서 및 runner token-call mode를 최초 독립 검토한다. 아래 NOT_RUN은 계획·구현 직후 이력이다. 실제 전역 cursor 연결이나 C 전체 coverage 검증은 아니다.

공유 runner 변경 후 user37PASS (`041748Z-b193870b`, `126395de1bc72e29cc4aafafa820fb3c9f770bed970302f9745ea4122d1f8737`), risk32PASS (`041753Z-badc7178`, `84902d400a6d546644b30ec7c342cbb0c6989e80af11790362128b0e541924b2`), Vault32PASS (`041758Z-61b0df5d`, `55477e17e1366c29a02b4d4049798daf7cc53884a708788cbc790066fc995aa9`), control29PASS (`041803Z-857c9e5e`, `7d0c38f835e007119fcb745dced87876eb15ee05d810203d12cff999df7e588c`) 및 Python5PASS를 실행했다. 모두 FAIL/SKIP/ABORT0·compile/test0·stderr 없음·소스 불변이다. 정상 Gradle 전체나 실환경 인수가 아닌 명시적으로 선택한 직접 JUnit 실행이다.

작성 2026-10-23. 구현 전 실제 C 고정 토큰/OZ ERC20, 기존 raw envelope와 token ledger를 읽고 승인된 최소 범위를 고정한다. 기존 wrapper/codec/store/schema/계약은 수정하지 않는다.

## 입력 및 검증 순서

새 CTokenTransactionProofModels/Verifier/Test만 작성한다. 입력은 original raw/full Receipt envelope와 정확 tx-prestate point, prevalidated TokenState, parent block hash 및 선택 allowance observation이다. **VaultState는 입력/반환하지 않는다.**

내부 envelope로 raw 서명/hash/sender/chain/target/value/log metadata/ABI를 확인하고 tokenState의 chain/token identity 및 block/header를 대조한다. 상태는 최초 constructor mint 자료를 포함해 token reducer로 검증한다. 이는 실제 생성 bytecode/RPC/codeHash 진실을 증명하지 않는다.

성공 transfer/approve/transferFrom만 지원하며 full Token event 한 개가 raw sender·전체 calldata와 일치해야 한다. transferFrom은 owner=caller 또는 amount0이어도 같은 tx 직전 point·owner=from·spender=raw sender의 allowance assertion>=amount를 요구한다. MAX도 정수로 비교하며 Approval 관측에서 현재 allowance를 추정하지 않는다. OZ5.1의 finite allowance 소비는 Approval을 emit하지 않는다.

0/self transfer는 원본 이벤트로 남기고 충분한 잔고를 요구한다. 실제 OZ와 같이 transfer/transferFrom의 zero from/to 및 approve의 zero spender는 0원이어도 거절한다. 성공한 양수 transfer에서 Vault가 from/to에 포함되면 양수 self까지 UNSUPPORTED_VAULT_ASSET_DELTA로 종료하고 새 TokenState를 반환하지 않는다. 현재 Vault가 임의 allowance를 만들 수 없다는 한계를 건너뛰지 않는다.

최종 token.balanceOf(Vault)가 이전 값과 같아야 ZERO_DELTA 결과를 반환한다. status0는 no logs·불변 잔고 관측만 기록하고 nonce/allowance/승인/실패 원인을 추정하지 않는다. creation/internalcall/다른 call은 지원하지 않는다.

## 검증 계획 및 미구현 경계

약30개 순수 JUnit으로 세 call, owner=caller allowance/0/MAX/finite, zero/self 잔고, zero spender/from/to, 정확 full event/추가Approval/잘못된 signer/point/identity/header/forged seed/resource/status0/원본 불변·재생, Vault 양수 유입/유출/self unsupported와 zero 유입 보존을 검증한다.

scope PREVALIDATED_TOKEN_CONTEXT_ONLY, globalCoverage=false/currentAllowanceVerified=false다. Vault cursor를 갱신하지 않으므로 token 관측 이후 기존 금융 wrapper는 PROJECTION_POINT에서 여전히 막힐 수 있다. T2 overlay/cut/global scanner·실제 RPC·DB 저장·canonical 인수는 NOT_RUN이다. 이 단위는 PG 검토 상한 우회나 전체 감사 MATCH가 아니다. 현재 구현·실행·독립 검토 NOT_RUN.

## 구현 상태와 선언된 Vault 주소 경계

신규 Models/Verifier 및 **31개 JUnit**을 작성했다. compile·실행·독립 검토는 아직 NOT_RUN이며 기존 소스/runner는 변경하지 않았다.

TokenState의 Deployment에는 Vault 주소가 없으므로 이 단위의 ZERO_DELTA는 **envelope.deployment에 선언된 해당 Vault 주소 하나**에만 바인딩된다. 결과 Verified에는 정규화된 declaredVault도 함께 반환한다. 이 주소가 실제 controller의 Vault인지는 증명하지 않는다. 다른 Vault 주소에 결과를 재사용하면 안 되며, 후속 T2는 원본을 다시 내부 검증하고 base의 전체4주소·chain/genesis와 정확히 비교해야 한다. public T1 Result 자체를 신뢰 토큰으로 받지 않는다.

constructor mint는 token ledger의 보존 replay로 검증하는 prevalidated assertion일 뿐 생성 raw/실제 codeHash/canonical proof가 아니다. 같은 인풋으로 수행한 zero/self token 관측을 실제 user/Vault 거래 성공이나 global cursor 인수로 승격하지 않는다. T2 구현은 이 단위에 포함하지 않았다.
