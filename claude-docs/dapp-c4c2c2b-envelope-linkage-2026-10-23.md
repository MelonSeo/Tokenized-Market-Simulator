# C4c-2c-2b — 원본 signed transaction과 Receipt metadata 연결

작성: 2026-10-23. **사전 소스 조사·작은 단위 계획; 구현/실행 결과는 아래에 누적한다.** 순수 Java 단위이며 C4b PG 재실행·새 서비스·금융 정책 변경이 아니다. C4c-2c-2a 사용자 승인/경제 wrapper가 검토 중이므로 해당 단위에 의존하지 않는다.

## 1. 구현 전 범위

**최신 실행은 아래 §6의25PASS이며 두 번째(최종 허용) 재검토에서 필수 지적 없음으로 완료했다.** 아래 초안·23/24개 단계의 NOT_RUN 및 검토 지적은 각 시점의 이력으로 보존한다.

실제 `CSignedTransactionVerifier`, `CAbiModels/CAbiCodec`, `CAbiEventModels/CAbiEventCodec`를 읽었다. 새 3개 Java 파일은 이들을 **내부에서 실제 호출**하고 raw tx와 Receipt/log metadata를 연결한다. caller가 만들어 준 public Proof record를 신뢰하는 입력 경로는 만들지 않는다.

입력은 원본 signed raw/예상 txHash, 명시적 chainId 및 서로 다른 C token/Vault/controller/rules 주소, block number/hash/timestamp, Receipt의 txHash/from/to/status/block/index 및 모든 log의 같은 metadata/index/removed/raw event다. 배포/code와 block은 **사전검증됐다는 caller 전제**이지 이 단위가 RPC로 증명한 사실이 아니다.

검사 순서는 (1) logs<=128·topics<=4·누적 data<=64KiB 및 필수 metadata의 bounded preflight, (2) raw verifier의 hash/chain/sender, (3) Receipt 대응·지원 target/call ABI 식별, (4) 확인된 C nonpayable 호출의 status1에서만 value0 강제 및 status0 logs 없음, (5) log metadata·순서·중복/removed·정확 emitter category·full event ABI다. 미지원 target/selector에 nonpayable 규칙을 먼저 적용하지 않는다. 금액·서명 bytes·0 token Transfer/Approval을 변경하거나 무시하지 않는다.

결과는 `LINKED_IN_SCOPE`, `INSUFFICIENT`, `UNSUPPORTED`, `REJECTED`와 안전한 reason code다. scope는 `PREVALIDATED_BLOCK_DEPLOYMENT_CONTEXT_ONLY`, completeCoverage=false. 알려지지 않은 raw type/selector/emitter/topic과 결측 자료를 실제 금융 모순으로 단정하지 않는다. 제공된 완전한 자료끼리 hash/from/to/block/status/log가 모순되면 scoped REJECTED다.

## 2. 명시적 비목표

사용자/Oracle 승인, EOA code, user nonce·pause·시간·금융식·Claims·예상 event 순서는 다음 wrapper가 검증한다. 여기서는 controller의 OPEN calldata에 전혀 다른 알려진 C event가 들어 있어도 **문법/metadata만 연결**할 수 있다. 경제적 거래 성공 또는 실제 canonical Receipt 확정으로 표현하지 않는다.

status0는 state/event 효과가 없다는 Receipt 모양만 확인하며 TTL/가스/승인 실패 같은 revert 원인을 추측하지 않는다. contract creation은 raw 문법에서 가능하지만 이 C 배포 후 호출 연결 단위에서는 UNSUPPORTED다. mint 모양의 zero-from Transfer나 inherited DomainChanged 문법을 decode해도 mint 허가·domain 변경 권한을 증명하지 않는다.

raw/call 안의 사용자·가격 서명과 원문을 `toString`/reason/cause로 노출하지 않는다. 필요한 원본은 입력 호출자에 남기고 linked 출력에는 후속 검사에 필요한 full call/event 값만 제공한다. public linked record 자체도 인증 토큰이 아니며 후속 wrapper는 신뢰할 수 있는 내부 호출 경로에서 결과를 받아야 한다.

## 3. 검증 계획

약 20개의 offline JUnit에서 실제 public deterministic 시험 정수 17을 이용한 web3j RLP signed fixture로 정상 legacy/type2를 만든다. 이는 보존 사용자·Anvil·MetaMask 개인키가 아니다. 기존 raw/ABI 독립 golden 테스트도 공유 회귀에서 그대로 유지한다.

필수 범위는 hash/from/to/chain/value/status/block/txIndex/logIndex/removed 각 변경, 알 수 없는 입력 분류, 모든 정확 emitter 범주, metadata 결측, 128/129·data 한도, 원본 내부 재검증, status0 no logs, 0 token Transfer/Approval 보존, safe 문자열과 입력/출력 불변성이다. 작성 개수는 실행 PASS가 아니다. 총괄 runner의 compile/JUnit 및 독립 검토 결과를 이후 기록한다.

## 4. 구현 및 실행 상태

새 `CReceiptEnvelopeModels`, `CReceiptEnvelopeVerifier`, `CReceiptEnvelopeVerifierTest`를 작성했다. 작성된 JUnit은 23개이며 **아직 compile/JUnit/독립 검토 NOT_RUN**이다. 기존 raw/call/event 소스, 경제 규칙, 저장소 및 runner를 수정하지 않았다.

총괄의 사전 검토에 따라 strict call ABI의 offset/trailing/padding/noncanonical 거절은 `UNSUPPORTED / CALL_ABI_SUBSET`으로 분류한다. 해당 bytes가 실제 EVM에서 허용될 가능성이 있으므로 이를 금융 모순이나 MISMATCH 근거로 승격하지 않는다. 크기 한도는 INSUFFICIENT이며, 알려진 emitter의 고정 event ABI와 완전 metadata의 명시적 모순만 scoped REJECTED다. 추가 회귀는 정상 calldata 뒤 trailing word 및 주소 padding 변형의 안전한 UNSUPPORTED 분류를 확인한다.

bounded snapshot 중 입력 목록이 변경되면 안전한 `INPUT_CHANGED` INSUFFICIENT로 종료한다. 입력/원문 서명/예외 원인을 reason에 포함하지 않는다. 실제 RPC, PostgreSQL, canonical scanner, 사용자/Oracle 승인 및 거래 금융 정합성은 이 단위에서 실행하거나 증명하지 않았다.

## 5. 초기 실행·독립 검토 및 최소 수정

총괄 runner의 초기 실제 JUnit은 **23 PASS**였다. 보존 실행은 `deployment/runtime/c-proof/20261023T025834Z-19ffd36c`, sourceHash 접두사는 `13a284…`다. 이 결과는 당시 초안의 실행 증거이며 독립 검토 완료나 최종 완료를 뜻하지 않는다.

초기 독립 검토에서 필수 수정 1건을 확인했다. 알려진 nonpayable target에 value>0을 보냈더라도 status=0·logs=[] Receipt는 정상적인 실패 관측일 수 있다. 기존 무조건 value0 조건은 이를 REJECTED로 잘못 분류했다. 성공 status=1에만 value0를 요구하도록 최소 수정했으며, status=0는 metadata 일치와 logs 없음 조건에서 원인 추정 없이 연결한다. nonpayable 검사 자체를 성공 거래에서 완화하지 않는다.

TOKEN/VAULT/CONTROLLER 각각 value>0·status0·logs[] 연결과 status0에 logs가 있으면 거절하는 회귀를 새 테스트 한 개에 추가했다. 기존 status1 value 거절도 유지한다. 현재 작성 테스트는 **24개**이며 수정 후 실행 및 첫 재검토는 아직 NOT_RUN이다. 가격·자산·승인 정책, 기존 ABI/raw 검증기 및 runner 변경은 없다. C4b PG 검토 한도나 실행 승인도 변경하지 않는다.

## 6. 첫 재검토의 후속 지적 및 회귀 재현

첫 수정의 실제24PASS는 `c-proof/20261023T030211Z-1b5ade0e`, sourceHash `b22dcd3f04d50399694db8306f39ef26798db743f0304441b3de6f5d10230158`다. 첫 재검토는 초기 status0 문제 해결을 확인했지만, value guard가 target 식별보다 앞서 있어 미지원 payable 주소의 정상 status1/value>0까지 REJECTED로 잘못 분류하는 필수 지적1건을 남겼다.

총괄이 먼저 `unknownPayableTargetsAndUnsupportedSelectorsAreNotNonpayableContradictions`를 추가하여 **24PASS/1FAIL**로 재현했다. 실행 `20261023T031255Z-a966df7b`, source `8395b825dc17ba0e9c140049305d455954ceb23f7e30040e96b6bc3869a1f4ae`; 최초 오류는 `expected UNSUPPORTED but was REJECTED`다. 기존 실패 로그·소스 snapshot을 보존한다.

최소 수정은 guard를 **지원 target 및 strict 지원 ABI 식별 뒤**로 옮기는 것이다. 새로운 테스트는 legacy/type2의 미지원 주소+value>0을 `UNSUPPORTED/CALL_TARGET`, 알려진 C 주소의 미지원 selector+value>0을 `UNSUPPORTED/CALL_SELECTOR`로 확인한다. 알려진 지원 C 호출의 status1/value>0은 여전히 거절하고 status0/value>0/logs[] 관측도 유지한다.

수정 후 **25PASS/0FAIL/0SKIP/0ABORTED**: `deployment/runtime/c-proof/20261023T031324Z-ef952cf1`, sourceHash `fdb26cbfc366a429eb08006c4165519af429239fa9b11c35e5ed822c480bf6e3`. compile/test exit0, stderr 없음, 소스 불변이다. 이 후속 수정은 총괄이 수행했고 작성자 초안·최초 검토·첫 재검토와 구분한다. 두 번째(최종 허용) 재검토 전이며 아직 단위 완료로 승격하지 않는다. C4b 검토 주기는 바꾸지 않았다.

최종 후속 기록: `/root/review_c_boundaries` 두 번째·최종 재검토(2/2)에서 **발견된 필수 수정 없음**. 두 지적 해결, 24/1 실패→25/0 성공 원본·현재 source manifest 불일치0을 직접 읽기 대조했다. 파일 수정/재실행/서비스 접근은 없다. 이 순수 envelope 단위만 완료이며 사용자 승인/금융 전이/예상 이벤트 조합/canonicality 및 전체 감사는 여전히 후속 검증이다. 위 “재검토 전”은 수정 직후의 이력이다.
