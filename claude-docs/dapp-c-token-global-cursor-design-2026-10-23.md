# C token-only 거래와 global cursor — 읽기 전용 설계

## 후속 결정 및 최초 독립 설계 검토

T1은 Vault 잔고 영향0 범위부터 실제 구현한다. 양수 Vault 유입/유출 및 Vault 양수 self-transfer는 성공 tokenPost를 반환하지 않고 UNSUPPORTED로 남긴다. Vault State/cursor를 입력·반환하거나 바꾸지 않으므로 T1만으로 다음 금융 거래가 연결되는 것은 아니다. 별도 T1 결과 문서에서 실제 테스트/검토를 기록하며 아래 약30개는 초기 계획이다.

T2 최초 설계 검토 `review_c_boundaries`: **필수 수정 지적 없음**, 구현·테스트·서비스 실행은 없음. 실제 State/reducer/codec까지 읽고 다음 주의점을 확인했다. `CCustodyReducer.validateState`는 과거 관측이 cursor 이하인지 검사하므로 cursor를 전진시킨 임시 State도 v1 codec에 형식상 통과할 수 있다. 따라서 기존 store가 자동 거절한다고 가정하지 않는다. base Vault/Token 동일 출발점, 모든 gap 원본 내부 재생, 각 단계 영향0, gap·금융 거래 중복/재포함 거절, 부분 결과 미반환, 반복 실행의 전체 원본 보존 및 scanner 누락 미증명 표시를 구현의 필수 조건으로 유지한다. 같은 T2 단위의 후속 재검토는 최대2회이며 별도 이름으로 초기화하지 않는다.

### 구현 시 API·원본 보존 경계

메모리 T2의 임시 cut과 내부 wrapper `Verified`/projection State를 반환하지 않는다. 외부에는 요약과 private 생성자의 불투명 checkpoint만 제공하고, 다음 단계에서도 checkpoint 내부의 base+전체 원본 transcript를 재검증한다. 이 checkpoint를 C4b `State` 인자로 넘길 수 없게 타입 경계를 둔다. raw/Receipt/입력 assertion은 private immutable snapshot으로 보존하고 `toString`에 노출하지 않는다. 현재 envelope transport DTO의 input lists는 불변이 아니므로 외부 List 참조를 그대로 checkpoint에 보관하면 안 된다. count/총 byte 상한을 먼저 검사한 뒤 각 log/topics까지 bounded deep snapshot해야 한다. 원본 자료 없이 digest만 통과시키지 않는다.

이 결정은 아직 T2 구현이 아니며 v1 codec/store/DB나 기존 projection-point 검사를 변경하지 않는다. actual canonical·source/prestate 진실성·PG 재개·전체 coverage의 승인은 아니다.

### T2 최초 구현 범위 확정

최초 메모리 구현은 **동일 block/hash/parent/timestamp 및 연속 txIndex(+1)** 로 제한한다. 다른 블록·index gap은 안전하게 미지원으로 남긴다. pending token gap64/전체 step128/원문+root 보존8MiB와 기존 하위 한도를 유지하고 실제 header는1개만 보존한다. 이는 전체 C 인수 기준을 줄이거나 multi-block/scanner/재시작을 완료하는 결정이 아니다. 그 후속은 미완료로 남는다. Root header/time와 configured sourceIdentity는 입력 assertion/설정의 바인딩이며, 실제 canonical header나 실행 artifact hash 검증은 후속 reader/harness의 책임이다. 새 저장 codec/JDBC 대신 메모리 내부 whitelist framing/hash만 사용하며, 실제 저장·PG Gate는 계속 BLOCKED다.

### 교차 step의 full rules 및 allowance 연결

총괄 사전 분석: 기존 독립 verifier가 확인한 각 prestate assertion을 묶기만 하면, 앞 OPEN에서 검증한 quantity/entry와 다음 TOP_UP의 full position assertion이 달라도 reduced Vault 필드만 맞아 통과할 여지가 있다. 앞 approve100 뒤에 deposit500의 allowance9999 assertion을 주는 모순도 독립 호출만으로는 연결되지 않는다. 이는 과거 실제 장애가 아니라 새 overlay 구현 전에 확인한 책임 경계다.

따라서 재생 내부에서 positionId별 이미 검증한 full rules after를 유지하고 이후 해당 position의 full prestate11필드와 일치시킨다. Root에 이미 있던 포지션을 처음 접할 때의 assertion 진실은 아직 미증명이지만, 이후의 알려진 상태를 임의 교체할 수는 없다. 첫 OPEN 이후 quantity/entry/side/leverage/lastObserved까지 보존한다.

같은 방식으로 `(owner,spender)`별 **transcript에서 알려진** allowance를 유지한다. 처음 알려지지 않은 키의 첫 소비만 keyed snapshot assertion으로 시작하고, approve는 새 값을 기록한다. transferFrom과 deposit/fundHouse는 알려진 값과 snapshot을 일치시킨 뒤 finite만 차감하고 MAX는 유지한다. amount0도 assertion을 생략하지 않는다. 기존 TokenState의 마지막 Approval을 현재 값으로 초기화하지 않는다. 이 내부 일관성은 actual getter/canonical 현재 allowance 검증이 아니며 공개 판정은 false를 유지한다. 두 map은 checkpoint 내부 전체 transcript 재생에서 파생하며 외부 trusted proof로 받지 않는다.

작성 2026-10-23. **설계 제안이며 구현·정책 변경·PG 재실행 승인이 아니다.** 기존 source/codec/schema/store/세 wrapper를 수정하지 않았다.

## 1. 실제 충돌 지점

`CTokenLedgerReducer`는 zero/self transfer, wallet 간 transfer, Approval을 실제 관측으로 남기고 token cursor를 진행시킨다. 반면 C4a Call은 financial/control call과 양수 Donation만 표현한다. 모든 token-only 거래에 대응하는 Call이 없다.

현재 user/risk, Vault cash/Claims, controller control 세 wrapper는 두 State의 block number/hash와 lastTransactionIndex가 같아야 한다. token cursor만 진행한 후 기존 Vault State를 그대로 전달하면 PROJECTION_POINT에서 막힌다. 그렇다고 가짜 ProcessClaim을 적용하거나 0원을 양수 Donation으로 바꾸면 실제 calldata/권한/receipt와 다른 관측이 생긴다.

또한 현재 C4a codec은 VERSION=1의 고정 Call tag1~7과 strict canonical re-encode를 사용한다. JDBC schema의 codec_version=1, epoch=1 및 append-only payload/hash를 유지해야 한다. 기존 sealed Call에 태그를 추가하고 옛행을 새 의미로 읽는 방식은 안전한 우회가 아니며, C4b의 미해결 PG 검증·검토 상한과도 별개 승인이 필요하다.

## 2. 대안 비교

| 대안 | 보장/장점 | 수정·위험 |
|---|---|---|
| A. 명시적 비금융 관측을 가진 v2 모델 | 한 ordered journal에서 금융/비금융 자료를 구분하고 재생 가능. NoVaultEffect에 원본 raw·full token event·권한/allowance assertion을 붙일 수 있음 | v1 Call/codec/store를 조용히 수정할 수 없음. 새 v2 State/codec/저장 namespace 및 wrapper 버전이 필요. 기존 finance 재생 구현을 복제하면 위험 |
| B. global proof overlay | v1 Vault financial State/마지막 금융 지점을 원본 그대로 보관하고 global cursor와 gap 원본을 별도로 연결. 기존 순수 verifier/reducer를 그대로 호출 가능 | cursor를 임의 대입해 숨기면 안전하지 않음. gap 전체 재검증과 내부 전용 cut adapter·별도 versioned 저장이 필요. 실제 scanner completeness는 별도 |

**추천: B를 메모리 전용 순수 단위부터 구현한다.** A의 v2 저장 모델을 지금부터 만들기보다, token-only 원본 검증과 검증된 gap을 통해 다음 금융 거래까지 연결하는 정확성을 먼저 독립 검토한다. 기존 v1 행/codec/store를 사용하는 통합 완료나 실제 PG 재개로 표현하지 않는다.

## 3. 가장 작은 구현 단계

### T1. 독립 token-only 원본 verifier

새 `CTokenTransactionProofModels/Verifier/Test`가 원본 raw/full Receipt를 내부 envelope로 검증한다. 성공 call은 token Transfer/Approve/TransferFrom만 지원한다. raw target=정확 token, value=0, actual sender는 내부 서명 복구 결과다. 같은 tx의 full token event 전체를 ledger에 적용하고, Vault 관련 event는 예상하지 않으므로 거절한다. 결과는 TokenPost와 **명시적 Vault 영향 분류**를 반환한다.

- transfer: from=raw sender, to/amount=calldata와 정확히 같은 Transfer 한 개.
- approve: owner=raw sender, spender/value=calldata와 같은 Approval 한 개. 실제 OZ 정책상 spender0는 거절한다. 잔고/공급 변화는 없음.
- transferFrom: from/to/value가 calldata와 같은 Transfer 한 개. **from=raw sender여도 예외 없이**, 같은 tx 직전 point의 allowance(owner=from, spender=raw sender)>=amount assertion이 필요하다. 0원에도 명시적 allowance0 관측을 요구한다. MAX는 무소비, finite는 감소하지만 OZ5.1이 Approval을 emit하지 않으므로 post allowance를 마지막 Approval에서 추정하지 않는다.
- zero transfer/self transfer도 이벤트를 보존하고 self amount에 충분한 잔고가 있어야 한다. token reducer가 금액/합계/최초 mint/재사용을 검증한다.
- status0는 no-event 관측과 불변 잔고만 반환하고 권한·원인 추정은 하지 않는다.

T1은 token cursor가 Vault보다 앞설 수 있음을 **출력에 명시**하고 기존 wrapper를 재개하지 않는다. 이 결과만으로 global coverage PASS를 선언하지 않는다.

### T2. bounded global overlay와 다음 금융 거래 연결

새 `CGlobalProofOverlay`는 v1 Vault financial State 및 그 마지막 금융 cursor, TokenState/global cursor, 원본 gap 입력들(전체 raw+Receipt+prestate assertion), header ancestry와 배포 identity를 함께 보존한다. 공개 Proof/Verified record를 받아 건너뛰지 않고 overlay verifier가 T1을 내부 호출해 gap 전체를 다시 검증한다.

원본과 global cursor가 하나라도 빠지거나 중복/역순/hash/범위/자산 영향이 충돌하면 cut 생성이 불가능하다. 단순히 State.cursor만 덮어쓴 public record를 증거로 받지 않는다. 같은 블록의 무관 txIndex 간격이 실제로 모두 스캔됐다는 것은 별도 block tx manifest/RPC 증거 없이는 주장하지 않는다.

검증된 **Vault 자산/장부 변화0** gap에 한해 내부 `VerifiedProjectionCut` adapter가 후속 wrapper 호출용 temporary view를 만든다. 금액/owner/position/Claims/nonce/operation map·기존 observation은 원본과 동일하고 cut metadata만 global point에 맞춘다. 이는 금융 Bundle을 만든 것이 아니며 v1 financial State/마지막 금융 지점은 overlay에 별도로 보존한다. adapter 결과를 외부가 임의 생성할 수 있는 신뢰 토큰으로 노출하지 않는다.

기존 wrapper는 그 view와 검증된 TokenState를 받아 실제 다음 금융 거래를 검증한다. wrapper 반환만 따로 저장하거나 반환하지 않고 gap 원본 및 새 금융 관측까지 포함한 overlay 결과를 원자적으로 반환한다. 전체 global 자료를 보유하지 않은 경우 기존 PROJECTION_POINT 거절을 우회하지 않는다. 기존 세 wrapper 소스의 검사 삭제는 필요하지 않다.

**임시 view를 기존 v1 JDBC store에 저장하지 않는다.** gap 자료가 저장되지 않기 때문이다. 메모리 단위의 성공 다음에만 별도 v2 overlay codec/namespace/원자 저장과 재시작 검증을 설계한다. 이 저장 단계는 C4b 검토 상한을 이름만 바꾸어 회피하지 않으며 기존 미해결 절차와 별도 판단을 받는다.

## 4. Vault 자산 영향은 별도 분류해야 함

token-only는 항상 NoVaultEffect가 아니다.

- wallet↔wallet, Approval, 0원 transfer, Vault self/0원 유입은 actualAssets 변화0이므로 위 gap 대상이다. 이벤트는 그대로 유지한다.
- wallet→Vault 양수 transfer는 free/house를 만들지 않지만 실제 token 잔고와 **unassignedAssets가 증가**한다. 이를 NoVaultEffect로 묶으면 잘못이다.
- raw sender가 실제 donor인 양수 direct transfer는 기존 C4a DonationCall의 의미와 정확히 맞을 수 있다. 실제 원본 검증 뒤에만 해당 정상 축약을 쓸 수 있으며 0원/다른 owner를 가공하지 않는다.
- transferFrom의 from≠raw sender인 양수 Vault 유입은 기존 DonationCall이 기대하는 from=bundle.from과 다르다. Bundle.from을 donor로 위조하면 안 된다. 최초 최소 범위에서는 `UNSUPPORTED_VAULT_ASSET_DELTA`로 남기고 v2의 명시적 custody-asset-delta 관측을 별도 설계한다.
- Vault의 양수 유출은 일반 zero-effect gap이 아니다. 정상 Vault.withdraw는 이미 현금 wrapper 범위다. token-only 경로가 실제 Vault 권한·allowance 제약을 생략한 채 임의 유출을 정상으로 승격하지 않는다.

따라서 T2 최초 인수는 영향0 gap만 지원하고, 양수 donation은 명시적 별도 케이스로 남기는 것이 가장 작다. 토큰의 모든 함수/상황을 지원했다고 보고하지 않는다.

## 5. 최초 mint와 지원하지 않는 실행 형태

T1/T2의 시작 TokenState는 기존 ledger의 고정20m·treasury·정확 배포 위치 mint 및 전체 관측 replay 검증을 다시 통과해야 한다. 그러나 그것은 **prevalidated constructor mint assertion**이다. 현재 envelope는 contract creation을 지원하지 않으므로 토큰 생성 bytecode/constructor args/실제 codeHash/canonical mint를 새로 증명한 것으로 표현하지 않는다. 실제 deployment 인수는 별도 gate다.

raw가 token/vault/controller를 직접 호출하지 않고 multicall/factory/smart-wallet/internal call을 통해 실행되면 현재 ABI/trace 범위 밖이므로 UNSUPPORTED다. 부분적으로 알려진 Transfer만 추출해 전체 coverage로 바꾸지 않는다. Approval 마지막 관측, 실제 getter assertion, post allowance, actual canonical 증거는 계속 구분한다.

## 6. 원자성·재생·메모리 상한

- source state 및 v1 payload는 불변이다. token-only 검증이나 후속 금융 검증 중 어느 한쪽 실패하면 새 Token/Vault/overlay cut을 일부 반환하지 않는다.
- exact raw/hash/block/tx/log replay는 payload가 같을 때만 idempotent다. 같은 txHash의 다른 point·payload, gap 누락/중복/순서 변경은 거절한다.
- 최초 메모리 overlay는 pending gap 최대64 tx, header128개, raw+Receipt 원문 aggregate 최대8MiB로 제한하는 것을 제안한다. 기존 각 envelope의 raw64KiB/calldata16KiB/log128/data64KiB 및 ledger 한도도 유지한다. 목록 전체 복사 전에 count/aggregate를 점검하고 초과 시 INSUFFICIENT다.
- v1 base를 sourceHash/배포 identity/명시적 상태 digest와 연결하고 순서 있는 gap payload digest로 cut을 바인딩한다. digest만 보존하고 원본을 버려서는 재생 증거가 되지 않는다. 정확한 deterministic overlay codec은 후속 독립 검토 대상이다.

## 7. 필요한 검증

T1 약30개: 세 정상 call, from=caller transferFrom allowance도 필수, 다른 owner·spender·point·MAX/finite/0 allowance, 0/self 충분잔고/부족잔고, zero Approval, OZ no-Approval 소비, full event count/주소/필드, mint/burn/creation/internalcall unsupported, forged TokenState, status0, pre/post Vault 영향 분류.

T2 약25개: approve→user OPEN, wallet0/self→TOP_UP, 여러 token gap→risk/cancel/cash 재개, 정확 point/ancestry/배포/기존 observation 보존, 누락·순서·중복·source digest 충돌, public fake cut 차단, byte/count cap, 금융 검사 실패 후 rollback, gap 원본 없이 저장/재시작 재개 거절. fixture timestamp와 실제 RPC/PG 인수는 구분한다.

현재 결과: **설계만 완료. T1/T2 구현·실행·독립 검토·저장·실환경 모두 NOT_RUN.** 이 문서는 경제 정책을 바꾸거나 기존 C4b PG 재실행을 허용하지 않는다.
