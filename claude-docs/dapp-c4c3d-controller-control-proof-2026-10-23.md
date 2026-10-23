# C4c-3d — nonce 취소 및 신규 포지션 pause 원본 거래 연결

## 최신 실제 실행 — 29 PASS / 최초 독립 검토 완료

별도 `review_c_boundaries`가 실제 controller의 cancelNonce/_owner/pauseNewPositions와 신규 소스·테스트·runner·문서 및 현재 manifest SHA를 대조하여 **필수 수정 지적 없음**으로 판정했다. 직접 테스트 실행은 없었다. 최초 same-pause 실패의 시험 Ethereum nonce 분리도 확인했으며 사용자 nonce·제품·중복 방어는 변경하지 않았다. 아래 검토 요청/NOT_RUN은 당시 이력이다. 실제 getter/code/canonical/RPC·지갑 취소·PG·전체 coverage는 여전히 미검증이다.

`c-proof/20261023T040103Z-73ebf312`, sourceHash `7e7f013c9cebf23cc05ac6ac26cc53031ea39d2f3a6a33366fdd48d0caafee31`: **29PASS/0FAIL/0SKIP/0ABORTED**, compile/test0·stderr 없음·소스 불변이다. 최초 compile0/28PASS1FAIL (`035812Z-82ba6d0f`, `f285af08…`)는 아래의 테스트 raw nonce 충돌이며, 실패 snapshot을 보존하고 제품이나 중복 방어를 완화하지 않았다.

최신 공용 runner에서 user37PASS (`040041Z-b106e37e`, `5afbf6f1a73d71ace8fac5dcfd84e55c8d3ba027da88b09e67f7c241c5b66d4e`), risk32PASS (`040046Z-94a5c84e`, `32f69f2742ff48e5bb7c538d1e3af2289f35506fed9adcadfa678462e004390b`), Vault32PASS (`040051Z-127268ae`, `e2163dae74722b636d72a1b3b685d3b0769cdae477be3f6e865015c786d335b3`) 및 Python5PASS를 실행했다. 위3개도 compile/test0·stderr 없음·소스 불변이다. 신규 code/nonce getter·실제 RPC/canonical·DB 호출0이다. 실제 실행과 새 소스/runner/문서를 최초 독립 검토한다. 아래 NOT_RUN은 계획/구현 직후 이력이다.

작성 2026-10-23. 실제 C controller의 cancelNonce/pauseNewPositions와 `_owner`, 기존 envelope·두 reducer를 읽고 별도 단위의 계획을 고정한다. 기존 사용자/위험/현금 wrapper와 계약·DB·runner는 수정하지 않는다.

## 입력 및 검증 계획

원본 signed raw/full Receipt를 내부 envelope로 검증한 뒤 정확 배포/transaction 직전 point/두 projection cursor/token Vault 잔고=Vault actualAssets를 대조한다. 신규 Context는 Vault/token state, parent hash, 선택 nonce/code observation만 운반한다. public Proof/Result를 입력으로 신뢰하지 않는다.

cancelNonce는 raw.sender가 owner다. 동일 point·owner key의 명시적 controller nonce와 code 관측이 필요하다. nonce는 calldata expected nonce 및 projection의 해당 owner nonce(미등재이면0)와 같아야 하며 uint256 MAX에서는 +1 overflow를 거부한다. owner code는 실제 계약 `_owner`와 동일하게 정확 empty `0x`만 허용한다. exact CNonceCancelled(sender,expectedNonce) 한 개를 연결한다.

pauseNewPositions는 raw.sender=fixed pauseAdmin과 exact NewPositionsPauseChanged 한 개만 요구한다. nonce나 owner-code 조건을 추가하지 않는다. 같은 bool 값을 반복 설정해도 실제 계약이 이벤트를 emit하므로 이벤트 누락은 거절한다. pause는 OPEN flag만 바꾸며 free/locked/position/Claims/house나 인출 권한을 바꾸지 않는다.

두 call은 token subset이 비어 있으며 두 reducer가 모두 성공해야 새 상태를 반환한다. status0는 nonce/code/권한·실패 원인 추정 없이 no-log 금융 불변 관측만 기록한다. 다른 call은 UNSUPPORTED이며 가짜 금융 action으로 변환하지 않는다.

약28개의 순수 JUnit으로 정상/overflow/키·point·코드·nonce·admin·이벤트 전체 필드/개수/추가token/두 번째 reducer 실패/상태 불변·재생을 확인한다. 시험 키는 공개17/23/29만 사용한다.

scope PREVALIDATED_CONTEXT_ONLY, completeCoverage=false다. 입력 getter/code assertion의 실제 RPC/canonical 진실, 실제 지갑 취소 확정, 서비스·DB·전체 감사 MATCH를 검증하지 않는다. 현재 구현·실행·독립 검토 NOT_RUN.

## 구현 상태

신규 Models/Verifier 및 **JUnit29개를 작성**했다. 작성은 실행 PASS가 아니며 compile·실행·최초 독립 검토는 아직 NOT_RUN이다. 기존 사용자/위험/현금 wrapper·기존 테스트·envelope·reducer·runner를 수정하지 않았다.

테스트의 pauseAdmin은 공개 시험키17에 대응하는 주소로 명시적으로 분리한 prevalidated deployment다(Oracle23·relayer29와 서로 다름). 선행 snapshot의 deployment metadata와 모든 보존 Bundle identity도 같은 시험 배포 assertion으로 맞췄다. 실제 보존 DB/계약을 바꾸거나 실제 admin 키를 가져오지 않았다. 이 fixture가 기존 실배포의 관리자 권한이나 canonical 취소 성공을 증명하지 않는다.

취소는 nonce map 한 항목과 observation cursor만 변경하고 pause는 flag와 observation cursor만 바꾼다. token ledger에도 빈 subset 관측을 적용하되 wallet 잔고/공급은 바뀌지 않는다. 두 번째 reducer 실패 시 첫 번째 결과도 반환하지 않는다. uintMAX-1→MAX 성공과 MAX→overflow 거절을 구분했다. status0는 원인이 코드/nonce/권한 때문이라고 추측하지 않는다.

## 첫 실제 실행 및 fixture 수정

총괄 실제 실행 `deployment/runtime/c-proof/20261023T035812Z-82ba6d0f`는 compile0, JUnit **28 PASS/1 FAIL**이었다. 실패한 same-pause 회귀는 fixture가 Ethereum nonce7을 고정해 동일 pause(true) calldata·sender·chain·gas의 동일 raw/hash를 새 Receipt txIndex에 재사용했다. C4a의 같은 TxKey payload 충돌 거절 경계와 일치하며 이를 정상 새 거래로 허용하지 않는다. 첫 실패 증거는 보존한다.

테스트 helper만 새 transaction index에 대응하는 독립 Ethereum nonce를 사용하도록 수정했다. 사용자 controller nonce와는 다른 값이며 금융·취소 정책 및 제품 소스는 변경하지 않았다. 같은 bool의 첫/후속 요청이 서로 다른 hash 및 Ethereum nonce7/8임을 실제 raw verifier로 검사하는 assertion을 추가했다. 기대값·동일 raw 재포함 거절을 약화하지 않았다. 수정 후 실행·독립 검토는 아직 NOT_RUN이다.
