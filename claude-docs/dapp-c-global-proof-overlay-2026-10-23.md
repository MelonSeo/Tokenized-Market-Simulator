# T2 — memory-only global proof overlay

## 최종 후속 — 43 PASS / 마지막 독립 재검토 완료

수정 후 총괄 실제 실행 `deployment/runtime/c-proof/20261023T045618Z-70cb5a96`, sourceHash `42612c6e6fa7fe375b065c6358c1b478e882268c6e431715f785eee17918f5d2`는 compile/test exit0, **43 PASS/0 FAIL/0 SKIP/0 ABORT**, stderr 없음·소스 불변이다. 아래 수정 전 37 PASS/6 FAIL 및 이전36PASS 기록은 그대로 보존한다. 공유 runner 및 하위 소스는 변경하지 않았으므로 별도 기록한 관련5단위161PASS/Python5PASS의 실행 identity도 바꾸지 않는다.

`review_c_boundaries`의 마지막 재검토(2/2)는 **발견된 필수 수정 없음**이다. Root 동일블록 Vault 전체 logs+Token subset의 최대값, 각 내부 검증 full receipt의 엄격 증가, 빈 noop/status0의 기준 보존, private checkpoint/원본 재검증/실패 원자성을 실제 소스·43개 테스트·실행 manifest와 대조했다. 검토자가 직접 테스트나 서비스 접근을 한 것은 아니다. 설계 최초1회+재검토2회를 소진했다.

이 완료는 **memory-only same-block 내부 일관성 단위**에 한정한다. 실제 canonical/RPC, Root assertion 진실성, multi-block, PG/저장/재시작 및 전체 coverage는 여전히 미검증/미완료이며 MATCH로 승격하지 않는다. 아래의 당시 NOT_RUN·미완료 표시는 과거 시점 이력이다.

## 최신 독립 판정 — 첫 재검토 필수 수정1건 / T2 미완료

`review_c_boundaries`의 구현 후 첫 재검토(1/2)는 **필수 수정 있음**이다. 같은 블록 txIndex 연속성만 검사하며 거래 간 block-global logIndex를 연결하지 않아 token gap log1000 뒤 OPEN log400..403 같은 모순이 각 reducer를 통과할 수 있다. 코드 근거로 발견했고 검토자가 재실행한 것은 아니다. Root 동일블록의 Vault full 관측·token subset 최대 logIndex부터 이어가며 다음 nonempty full logs의 첫 index가 엄격히 커야 한다. token→금융/금융→token·중복/역행·빈 step 사이·Root 이후 첫 step·정상 순서를 회귀한다. 신규 overlay의 수정·실행 후 **마지막 재검토1회**만 남으며 이전36PASS를 최종 완료로 승격하지 않는다. 하위 source/기존 금융 정책·저장소는 변경하지 않는다.

## 최신 실제 실행 — 36 PASS / 구현 후 첫 재검토 요청

### 첫 재검토 지적의 실제 재현 및 최소 수정 (수정 후 실행 전)

독립 검토의 block-global logIndex 연결 누락 지적을 확인하기 위해 제품을 그대로 둔 채 7개 회귀를 먼저 추가했다. `c-proof/20261023T045236Z-b363bded`에서 compile0/test1이며 43개 중 37 PASS/6 FAIL이다. 이 중 5개는 token→금융·금융→token 역행/중복, 빈 noop 경계, Root full Vault 관측 이후 첫 token의 모순을 실제로 VERIFIED로 수락한 재현이다. status0 사이 중복 회귀 1개는 하위 token replay가 이미 거부했지만 새 전역 reason이 없어 실패한 것으로, 해당 경우까지 잘못 수락했다고 과장하지 않는다. 정상 증가 회귀는 통과했다. 기존 실패 snapshot과 하위 제품은 보존한다.

신규 Overlay의 private `BigInteger lastBlockLogIndex`만 추가했다. Root cursor와 block number/hash가 모두 같은 Vault Bundle 전체 logs 및 token Transaction subset logs의 합집합 최대값으로 초기화한다. 매 step의 내부 envelope 검증을 통과한 bounded **전체 decoded logs**가 비어 있지 않으면 첫 logIndex가 이전 최대보다 엄격히 큰지 검사하고 마지막 index로 갱신한다. noop/status0의 빈 logs는 최대값을 초기화하지 않는다. 하위 projection, 금액, 금융식, 서명, 저장소/codec 및 runner를 변경하지 않았다. 이 값은 독립 projection 사이 transcript 일관성만 보완하며 실제 블록/로그 진실이나 scanner 완전성을 입증하지 않는다.

수정본 Overlay SHA-256은 `a3378783824ad12dbf3fe575d3ed7bd3a0a0e3047d2f73640edefe8bf41fb645`, Test는 `0fca67423d38bea4d21ef4aa4698375373c86449b2d8c7af94580acc92ff9ccc`이다. 현재 작성43개이며 **수정 후 실제 실행 및 마지막 독립 재검토는 아직 NOT_RUN**이다. 설계 최초1회와 구현 첫 재검토1회를 이미 사용했으며 마지막 재검토1회만 남는다. 이전36PASS나 부분 재현을 최종 완료로 바꾸지 않는다.

엄격 UTF-8 수정 후 `c-proof/20261023T044037Z-6843f7d5`, sourceHash `d89afa66b3796db129896f3df0f33f4cc1ea69f0ca137d6b089a699f6284d437`에서 **36PASS/0FAIL/0SKIP/0ABORTED**, compile/test0·stderr 없음·소스 불변을 확인했다. 최초34PASS와 추가회귀34PASS1FAIL은 아래 이력 및 원본 snapshot으로 보존한다. root/key budget 보완·교차 rules/allowance 연결·strict UTF-8·새 runner overlay mode와 관련 회귀까지 같은 T2 구현 후 첫 재검토 범위다. 설계 최초1회에 이어 첫 재검토이며, 이후 필수 수정이 있다면 마지막 재검토1회만 남는다. 아래 NOT_RUN은 구현/수정 직후 이력이다. 실제 DB/RPC/서비스/유료 호출0이며 multi-block/저장/전체 coverage는 미완료다.

공유 runner 후속 회귀도 모두 compile/test0·stderr 없음·소스 불변·FAIL/SKIP/ABORT0이다: user37PASS (`044102Z-1e13ed50`), risk32PASS (`044107Z-1becb80e`), Vault32PASS (`044113Z-4017fce6`), control29PASS (`044118Z-7da31156`), T1 token-call31PASS (`044123Z-5910981b`) 및 Python5PASS. 각 전체 SHA와 실행 명령은 해당 `c-proof/20261023T.../result.json`에 있다. 이197개 JUnit 합계는 선택한 순수 단위들일 뿐 전체 Gradle/실환경 인수가 아니다.

작성 2026-10-23. 기존 설계의 최초 독립 검토 1회가 완료된 후 승인된 구현 단위다. 구현 후 첫 재검토와 추가 마지막1회만 가능하며 상한을 초기화하지 않는다. 기존 source/codec/store/wrapper/runner는 변경하지 않는다.

## 구체 API·보존 계획

새 `CGlobalProofOverlay`의 Root는 sourceIdentity SHA256, symbolHash, same-block timestamp 및 prevalidated baseVault/baseToken을 담는다. Step은 원본 envelope와 sealed Witness(Token/Trading/Vault/Control)만 받으며 State/Verified/임시cut 입력은 없다. public 출력은 Summary와 private-constructor opaque Checkpoint뿐이다. 내부 State·기존 wrapper Verified·원문/서명에 접근하는 getter/콜백/저장 API를 만들지 않는다.

`verify(root,전체 transcript)`와 `append(root,checkpoint,nextStep)`는 매번 base부터 모든 원본을 재검증한다. sourceIdentity/정확 deployment/root 상태는 deterministic 허용 record framing과 SHA256으로 바인딩한다. 이는 새 DB codec이나 실제 source/codeHash 진실 증명이 아니다.

최초 범위는 root와 동일 block number/hash/parent/timestamp 및 연속 txIndex+1이다. block 이동·header 생략·index gap은 UNSUPPORTED/INSUFFICIENT이며 실제 block scanner 완전성이나 globalCoverage를 선언하지 않는다. pending token gap64/전체step128/원본+root aggregate8MiB, header는 같은블록1개만 지원한다. 기존 raw/calldata/log/data/ledger cap도 유지한다. mutable Receipt/log/topics는 count와 원문 크기를 먼저 검사하고 private deep snapshot한다.

Root는 전체4주소/chain/genesis/token/출발cursor와 token Vault 잔고=Vault actualAssets가 같아야 한다. 매 step도 전체4주소/chain을 root와 정확히 비교한다. TOKEN은 매번 T1을 내부 호출하고 normalized declaredVault가 root Vault와 같아야 한다. 금융 step은 원본으로 기존 user/risk/Vault/control verifier를 내부 호출한다.

영향0 gap 증거 전체를 검증한 뒤에만 private temporary view의 cursor metadata를 global point에 맞춘다. balances/owner/positions/Claims/nonce/op/기존 observation은 원본 금융 snapshot 그대로다. 실제 금융 step 반환 후 금융 cursor를 갱신하며 gap과 전체 원본은 checkpoint 안에 보존한다. v1 codec/store가 임시 State를 형식상 받을 수 있으므로 자동 차단을 가정하지 않는다. 이 API는 임시 State/내부 Verified를 노출하거나 v1 store에 전달하지 않는다.

실패 시 checkpoint나 성공 상태를 일부 반환하지 않는다. 동일 raw/hash의 반복·다른 point·역순·금융 중복·root/source 바꾸기를 거절한다. nonce/code/allowance 등 Witness는 실제 필요한 해당 verifier의 조건을 그대로 따른다. T1 양수 Vault 자산 영향은 unsupported로 남긴다.

## 검증 계획·한계

약30개 JUnit으로 approve→OPEN, zero/self→TOP_UP, token gap→risk/cancel/cash, 여러 append의 전체 이력 재검증, source/root/address/point/hash/중복/순서/누락, mutable input deep snapshot/caps/실패 원자성 및 public 상태 미노출을 확인한다. 실제 Ethereum nonce와 controller user nonce는 구분하고 각 새 raw fixture는 별도 Ethereum nonce를 사용한다.

complete/global/canonical/PG/restart coverage는 false다. 실제 RPC/DB/서비스·벽시계60초·overlay 저장·T2 재시작은 NOT_RUN이다. C4b PG/검토 상한을 우회하지 않는다. 현재 구현·실행·구현 후 재검토 NOT_RUN.

## 구현 상태·교차 step 검증

신규 `CGlobalProofOverlay`와 JUnit34개를 작성했다. 기존 모든 제품 파일·codec/store·wrapper·runner는 수정하지 않았다. compile·실행·구현 후 첫 재검토는 아직 NOT_RUN이다. same-block 부분 구현은 전체 C 인수 조건을 축소한 것이 아니며 multi-block/빈블록/움직이는 cut/actual scanner/restart는 미완료다.

이미 검증한 rules.after(full11필드)를 positionId별 private map에 보존하고 후속 full prestate와 정확히 비교한다. allowance도 원본 approve로 정해진 값 및 transferFrom/deposit/fundHouse 소비를 private map으로 이어간다. finite 차감/MAX 유지/0원 명시 관측을 적용하며, Root의 lastApproval 로그를 현재 allowance로 초기화하지 않는다. 처음 관측하는 pair만 prevalidated snapshot을 받아들이고 이후 모순은 거절한다. 이 조건은 새 overlay의 내부 일관성 요구이며 이전 단위의 실패로 소급 기록하지 않는다.

sameBlockTimestamp와 sourceIdentity는 configured input expectation이다. 실제 헤더 확정이나 현재 JVM class artifact 측정은 별도 runner/harness 영역이다. Summary의 financialSteps/financialPoint는 control/pause/process noop/status0도 포함하는 **C-domain 관측수/마지막 관측점**이지 성공한 금융 체결수/체결시각이 아니다. noop/status0 회귀로 구분한다.

Root 모델/reducer/token replay 및 키·개수 검증을 canonical framing보다 먼저 수행한다. framing은 명시한 final nested record owner whitelist·허용 정수/enum·sorted maps·depth/bytes cap만 사용한다. reflection은 이 고정 record accessor 읽기에만 사용하며 문자열 class명으로 객체를 생성하거나 polymorphic 역직렬화/JavaSerialization을 하지 않는다. map 정렬 key byte 배열에도 누적 budget을 두어 최종 출력 cap 이전 과다 보존을 차단한다. immutable typed Root 및 cap 확인 후 deep snapshot한 Step만 checkpoint가 보존한다.

## 첫 실행과 총괄 자체 점검의 Unicode 결함

최초 실제34개는 `deployment/runtime/c-proof/20261023T043705Z-b1a7b6e9`(source `1dda65005d57754061cc782e88b8adae4a4b8922003df4ea3d23c8e3f7bd54e3`)에서 PASS했다. 그 뒤 **총괄 구현 자체 점검**에서 35번째 malformed Unicode 회귀를 추가했고, `20261023T043825Z-64b1ee02`(source `deb055e8f8b111fedf0a1bf44a3ec86b765de19745e6dd05b8d16c538c46bc84`)는34 PASS/1 FAIL이었다. 독립 검토자가 발견한 문제로 기록하지 않는다. 설계 최초1회만 수행된 상태이며 구현 후 첫 재검토는 아직 미실행이다.

실패는 `_risk`가 의도적으로 검사하지 않는 ownerCode witness에 단독 surrogate Java String을 주었을 때 canonical frame이 이를 거절하지 않고 수락한 것이다. 기본 String.getBytes(UTF_8)는 malformed high/low surrogate를 replacement byte로 바꿀 수 있다. 최초 회귀는 첫 malformed 수락에서 실패했으므로 두 전체 transcript 해시의 동일성을 실제로 끝까지 assert한 실행 증거는 없다. **malformed 수락 실패 재현**과 **동일 치환 가능성이 있는 소스 경계**를 구분한다.

신규 overlay의 String framing만 REPORT strict CharsetEncoder로 변경했다. malformed/unmappable 입력은 CANONICAL_FRAME 안전 오류로 거절하며 예외 원문을 반환하지 않는다. 금융·가격·키·기존 서명 정책 및 기존 proof 소스는 바꾸지 않았다. 총괄의35번째 원본 회귀는 유지하고, 기본 JDK의 두 단독 surrogate 치환 byte 동일성과 정상 한국어/정상 surrogate pair의 수락·서로 다른 transcript hash를 확인하는 회귀를 추가했다. 현재 작성36개, 수정 후 compile·JUnit·구현 후 첫 재검토는 아직 NOT_RUN이다. 이전 성공·실패 snapshot은 보존한다.
