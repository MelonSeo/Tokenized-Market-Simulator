# C token 잔고 projection — 구현 계획과 검증 경계

작성: 2026-10-23. C4a Vault 회계와 독립적인 단위다. **먼저 계획을 고정하고 신규 token 모델/reducer/test만 구현한다.** DB codec/store/Vault reducer/계약/기존 환경은 수정하지 않는다.

실제 `CMockCollateral`과 설치된 OpenZeppelin ERC20 v5.1.0/IERC20 전체를 읽었다. C 생성자는 treasury에 20,000,000×10^18을 한 번 `_mint`한다. `_update`는 자기 전송도 Transfer를 남기며 zero-value transfer도 가능하다. `_spendAllowance`는 finite allowance를 감소시켜도 `_approve(...,false)`라 Approval을 내지 않고 무제한 allowance도 유지한다. 따라서 로그만으로 현재 allowance를 재구성하면 틀릴 수 있다.

## 범위와 설계

새 CTokenLedgerModels/Reducer는 명시적인 deployment(token/treasury/chain/genesis/배포 tx·block identity), block/tx/log 순서와 prevalidated Transfer/Approval만 입력받는다. 최초 배포 mint는 from0→manifest treasury·고정 공급·정확한 배포 위치에서 한 번만 허용한다. 이후 mint 및 모든 burn/tozero는 거절한다. Transfer는 외부 지갑/Vault 주소에 동일 ERC20 잔고 이동을 적용하고 zero/self transfer도 observation으로 보존한다. Vault로 송금해도 Vault free/house나 Claims를 만들지 않는다.

Approval은 원본 observation 및 마지막 관측 값으로만 분리 보관한다. 현재 allowance getter 또는 소비 trace 없이 실제 spendable allowance라고 표시하지 않는다. 토큰 balance/supply와 allowance observation은 합산하지 않는다.

전체 tx의 token 로그를 엄격한 순서로 적용한 임시 사본에서 합계/공급/uint256 불변식을 확인한 뒤 immutable 새 state를 반환한다. 같은 identity·blockHash·txHash 재생은 payload가 정확히 같을 때만 no-op, 다르면 conflict다. 같은 canonical replay branch의 txHash 재등장, block gap/fork, tx/log 역순, 발행자 불일치 및 resource 한도는 거절한다. 상한 안의 명시적인 empty block 이동만 허용하고 실제 scanner가 빈 블록을 입증했다는 뜻은 아니다.

공개 State의 seed도 그대로 신뢰하지 않는다. 보존된 모든 Transfer를 제한된 범위에서 다시 계산하여 각 주소 잔고와 정확히 비교하므로 공급 합계만 맞춘 임의 재분배를 거부한다. anchor부터 cursor까지 최대 2,048개 block header를 보존하고 순서·부모·관측 block·cursor를 대조한다. 이는 입력 header의 내부 일관성 검사이지 실제 RPC canonical 판정이 아니다. 거래 2,048개, 거래당 로그 128개, holder/approval pair 각각 4,096개가 구현상 최대치이며 실행자가 더 작은 한도를 지정한다.

scope는 `PREVALIDATED_TOKEN_LOGS_ONLY`, completeCoverage=false다. 입력 로그의 실제 canonical/hash/code/tx 권한·EOA·Allowance·Vault 귀속·지급능력을 증명하지 않는다. 실제 Receipt verifier 및 범위 scanner와 연결하기 전 전체 ERC20 온체인 인수/MATCH가 아니다.

## 테스트 계획

### 최신 실행·독립 검토

총괄 실제 실행 `deployment/runtime/c-proof/20261023T030353Z-88d1708a`: **25PASS/0FAIL/0SKIP/0ABORTED**, compile/test exit0·stderr 없음·소스 불변. sourceHash `797bd4cf93b25fb42c189e94c0699917951ebd4df18d5dd3e414a097c5dca0a0`다. crypto jar·DB·RPC·서비스·유료 호출 없이 std Java와 두 ABI 모델만 컴파일했다.

본 token projection을 구현하지 않은 `/root/c1_token_vault`가 reviewer 지침에 따른 읽기 전용 최초 검토를 수행해 **발견된 필수 수정 없음**으로 보고했다. 실제 CMockCollateral·OZ ERC20 v5.1.0/IERC20, 세 Java·25테스트·runner whitelist와 원본 실행 결과/현재 SHA를 대조했다. 검토자 재실행·파일 수정·서비스 접근은 없다. actual canonical/code/권한/현재 allowance/지급능력은 이 검토로 확보한 보장이 아니다.

작성 단계 총괄 자체 검토에서 합계만 맞는 위조 seed가 주소별 잔고를 바꿀 수 있는 경계를 발견했다. 독립 검토 전 전체 Transfer replay의 주소별 exact 비교와 bounded anchor~cursor header 보존·ancestry 검증으로 보완했다. 이 자체 지적을 독립 검토자의 발견으로 기록하지 않는다. 관련 위조 seed 회귀도 위25PASS에 포함한다.

25개 집중 테스트를 작성했다. 최초 mint/거부, 후속 공급 불변·burn 거부, 사용자/사용자·Vault 이동, zero/self transfer·Approval 보존/분리, wallet 합계/uint256, 전체 tx rollback, 원본 snapshot 불변, 동일 replay/conflict/crossblock txHash, deployment/key/emitter/order/fork/gap, collection/log 한도, 위조 seed 잔고·cursor·header gap·과거 Approval 재사용, explicit observation scope 및 reorg ancestor 재생 한계를 다룬다. 테스트는 실제 키/DB/RPC/Docker/유료 호출 없이 순수 Java만 사용한다. 작성 시점 실행 및 독립 검토는 아직 NOT_RUN이며 총괄 실행 결과와 연결한다.

소스 위치는 `backend/src/main/java/com/pricetrack/exchange/custody/token/CTokenLedgerModels.java`, `CTokenLedgerReducer.java`와 대응 `CTokenLedgerReducerTest.java`다. 의존성은 표준 Java 21 및 검토된 `CAbiModels`/`CAbiEventModels`뿐이다. 기존 projection/codec/store/계약/서비스는 변경하지 않았다.

## 최초 컴파일 실패 보존

총괄 실행 `deployment/runtime/c-proof/20261023T030144Z-a007cea2/compile.stderr.log`에 테스트의 wildcard import로 ABI `Log`와 token `Log`가 충돌한 오류 16건이 보존되어 있다. 전체 오류를 확인했고 모두 동일한 테스트 import 충돌이었다. 제품 소스 오류는 해당 로그에서 관측되지 않았다(후속 컴파일 성공을 의미하지 않는다). 테스트의 ABI import를 Event/Transfer/Approval/Deposited 명시 import로만 수정했으며 조건과 기대값은 바꾸지 않았다. 이 실행은 컴파일 FAIL, 테스트 NOT_RUN이다. 수정 후 실행 결과는 별도 기록한다.
