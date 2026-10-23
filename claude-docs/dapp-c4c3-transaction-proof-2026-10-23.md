# C4c-3a — 사용자 financial transaction 연결 계획

작성 2026-10-23. 먼저 실제 C controller/Vault와 기존 envelope·authorization·rules·Vault/token reducer를 읽고 범위를 고정했다. 이 단위는 순수 Java이며 C4b PG 실행 예외나 실제 scanner가 아니다.

## 최소 지원 범위

첫 검토 단위는 Direct/Relayed OPEN·TOP_UP·CLOSE와 해당 지원 call의 status0 관측이다. risk, Vault cash/process, nonce 취소, pause, token 직접 호출은 명시적 UNSUPPORTED로 남기며 금융 입력을 가공해 가짜 ProcessClaim 등으로 처리하지 않는다. 전체 call을 한꺼번에 결합하는 대신 실제 사용자 승인 세 경로를 원본부터 회계까지 연결한다.

## 입력과 검증 순서

입력은 원본 signed transaction/Receipt envelope와 명시적 prestate point(block number/hash, txHash/index), 배포 chain/token/Vault/controller/rules/relayer/Oracle/admin/policy/symbol, full rules position·mapping keys·active pointer·operation-used, 사용자 nonce/owner code 관측 및 Vault/token immutable State다. 외부가 만든 Proof/Linked/auth Result를 입력받지 않는다.

1. 자체 envelope verifier로 raw recovery/hash/from/to/chain/value/전체 log metadata 및 ABI를 검증한다.
2. 각 deployment identity, 정확한 transaction 직전 point, 두 projection cursor 및 token Vault balance==Vault actualAssets를 확인한다. prestate/code/identity가 없으면 INSUFFICIENT다.
3. 지원 사용자 call에서 내부 authorization verifier로 실제 owner/Oracle EIP-712 서명, sender, nonce, accountRef, 120초 승인·허용 범위를 확인한다. full rules prestate와 Vault position/nonces/used operation을 연결한다.
4. 내부 rules verifier로 기존 금융식·Report30초/future2·version/epoch/전체 OperationApplied+ReportConsumed를 대조한다. 경제 산식은 복제하지 않는다.
5. 모든 full event를 감소 없이 검사하고 정확 주소/순서/개수의 축약 Bundle을 생성한다. Vault lock/FIFO/paidProfit/claim 계산은 기존 Vault reducer가 검증한다. OPEN/TOP_UP/CLOSE가 token event를 발생시키면 거절한다.
6. 같은 transaction의 token 빈 subset을 기존 token reducer에 실제 적용하고 새 Vault actualAssets와 token balance를 다시 대조한다. 어느 한쪽 실패 시 두 성공 state 모두 출력하지 않는다. 입력 state는 불변이다.

status0는 사용자 승인·원인·금융 실행을 추정하지 않고 지원 call의 무이벤트 관측만 두 projection에 기록한다. 원본 block parent와 int/long 범위 밖 metadata는 지원 범위 밖으로 표시한다. SIGNED raw를 변경하거나 거래를 제출하지 않는다.

## 불변식·한계

Vault terminal position은 collateral=0을 보관하지만 rules는 역사 collateral을 유지한다. 해당 차이를 정상으로 구분하며 active 상태는 owner/status/version/epoch/deadline/collateral을 정확히 연결한다. 각 projection resource 상한, 128 logs envelope 상한, 소스/반환 원문 redaction을 유지한다.

결과는 VERIFIED_IN_SCOPE / INSUFFICIENT / UNSUPPORTED / REJECTED이며 completeCoverage=false, PREVALIDATED_CONTEXT_ONLY다. 실제 code/canonical/RPC·시장5초/provider·현재 allowance·외부 nonce 진실·PG 저장·reorg·전체 감사 MATCH를 증명하지 않는다.

## 약 30개 집중 회귀 계획

정상 Direct/Relayed 세 action·손실/이익/FIFO CLOSE·TOP_UP 위험회복, raw/identity/prestate point/code/nonce/mapping key 및 서명 변조, 모든 full rules/controller event 필드와 event 순서/개수/주소, source/math/timing/price bounds, Vault lock/free/house/claim 불일치, token balance/seed 불일치·두 reducer 원자성, status0 무변경 관측과 임의 로그 거절, unsupported call/strict ABI·큰 metadata 범위 및 immutable source/replay/conflict를 확인한다. 작성·실행·독립 검토는 각각 구분해 후속 기록한다.

## 구현 상태

### 최신 실제 실행 — 37 PASS / 최초 독립 검토 완료

별도 검토자 `review_c_boundaries`가 실제 controller/Vault·wrapper 및 하위 검증기·테스트·실행 결과와 현재 SHA를 대조했으며 **필수 수정 지적 없음**이다. 검토자가 테스트를 재실행한 것은 아니다. 아래 구현 직후의 NOT_RUN/runner 불변 문구는 당시 이력으로, 이번 실제 실행과 검토 결과를 대체하지 않는다. 다음 독립 단위는 keeper START_CALL/CLEAR_CALL/LIQUIDATE의 동일한 원본→서명→금융→장부 연결이며, 사용자 거래 검증의 변경 영향도 함께 회귀·검토한다.

`deployment/runtime/c-proof/20261023T033140Z-8dcb564c`: **37PASS/0FAIL/0SKIP/0ABORTED**, compile/test exit0·stderr 없음·source 불변. sourceHash `3ef2666bbf0d369bda3a41cee8f85502567f483a08f06ede3656792f5efa1658`다. 최초 `20261023T033108Z-c698c071`/`acacba9a…`은 테스트 RLP helper의 닫는 괄호1개 누락으로 compile1오류/JUnit NOT_RUN이었다. 총괄이 해당 괄호만 수정했고 원래 실패 snapshot을 보존했다. 제품 금융 로직·기대값 변경은 없다.

공용 runner의 user mode 추가 뒤 관련 순수 회귀도 실행했다(모두 compile/test0·stderr 없음·source 불변·FAIL/SKIP/ABORT0).

| 범위 | PASS | `c-proof/` 실행 | sourceHash |
|---|---:|---|---|
| Receipt envelope | 25 | `20261023T033231Z-76cc453c` | `a8f1875135b7ffd03e15a067f3d86249124d66a990bb2574c05b1b0fa992ce47` |
| Authorization | 33 | `20261023T033237Z-5b9add87` | `23b3e585c9a4cb46d2c1e3c57b8709870a69462cd6842447fd39dd67a6a8ae0c` |
| Rules | 34 | `20261023T033240Z-852be9bb` | `fb233630f01e8c2c2ab1b8f7d9d62c473dd1c9dfed748774e3e830d697d8c77b` |
| Signed raw | 20 | `20261023T033244Z-2823f9d4` | `af45f50cc77777509fb66ca3c5bdc3fc6afad7844054a6866100a5ff490df3cd` |
| Token ledger | 25 | `20261023T033247Z-04eeda60` | `a7e51373cf053bb618364ceaa61a6f55f034d9a3889ced4758899c4099e627ce` |
| EIP-712 | 22 | `20261023T033251Z-5780de38` | `141f23349b3444a84e3f8a061faa0a0cda7fe33315c3620a39342b4841694c23` |

위7개 범위의 합계196개는 **각각 실행한 순수 JUnit**이며, 정상 Gradle 전체·실제 PG/Anvil E2E 또는 C 전체 감사 성공이 아니다. Python runner 경계도5PASS였다. 실제 DB/RPC/유료 호출0이며 C4b BLOCKED·검토 상한과 예외 승인 대기도 그대로다. C 계약/test aggregate는 `c42e0488…`로 유지했다(앞선141PASS에 대응하는 소스 불변 확인일 뿐 새 실행이 아니다).

새 `CTransactionProofModels`, `CTransactionProofVerifier`, `CTransactionProofVerifierTest`만 작성했다. `Context`는 기존 증거의 명시적 transaction 직전 assertion이며 신뢰 토큰이 아니다. 검증기는 자체 envelope/auth/rules/reducer 인스턴스를 생성하고 외부 Result 주입 경로를 제공하지 않는다. 두 reducer의 새 상태는 모두 성공한 뒤에만 반환한다.

구현 직후 기록: JUnit **37개 작성**, 당시 compile·실행·독립 검토는 NOT_RUN이었다(현재 결과는 위 최신 실행 절). 정상 Direct/Relayed OPEN/TOP_UP/CLOSE, profit/partial claim/loss, full rules/controller field 변조, raw/서명/nonce/identity/cursor/잔고/seed/순서/원자성을 다룬다. 총괄 사전 점검에 따라 기존 unpaid+현금 house가 있어도 새 CLOSE profit은 전액 후순위 claim, MARGIN_CALL TOP_UP 위험 회복(epoch 유지·deadline0·version3), pause 및 unpaid의 OPEN 차단을 wrapper 테스트로 추가했다. 선행 snapshots는 기존 C4a/token reducer의 관측으로 구성하며 새 raw wrapper 실행의 결과와 구분한다. 기존 하위 PASS를 신규 wrapper PASS로 승계하지 않는다.

시험 키는 총괄이 허용한 공개 정수17/23/29로 사용자17·Oracle23·relayer29를 분리했다. 사용자/Oracle 서명을 서로 바꾸면 실패하는 실제 ECDSA 회귀를 추가했다. 이는 실제 배포나 실제 사용자 지갑 인수 증거가 아니다. 보존 환경·키·DB·RPC·서비스를 사용하지 않았다. 기존 모델/reducer/계약 및 runner는 수정하지 않았다.

또한 총괄 사전 소스 점검에서 envelope 원본 deployment나 RPC positionOwner의 checksum 대문자 주소를 소문자 projection과 그대로 비교하면 오거절될 수 있음을 확인했다. 신규 wrapper 내부 비교만 기존 address 정규화 함수를 사용하도록 수정했고 대문자 deployment 및 TOP_UP/CLOSE owner 회귀를 추가했다. 거래 조건이나 서명 bytes는 변경하지 않는다.
