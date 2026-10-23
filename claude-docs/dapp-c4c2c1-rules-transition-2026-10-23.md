# C4c-2c-1 — 전체 rules 상태 전이와 금융 수학 연결

작성: 2026-10-23. **구현·직접 JUnit 34 PASS / 최초 독립 검토 필수 지적 없음.** [상위 계획](dapp-c4c2c-receipt-verifier-plan.md) §9의 작은 순수 단위다. 신규 DB/RPC/서비스·실계약 배포·사용자 키·유료 API를 사용하지 않는다. C4b PG BLOCKED 및 원본 성공/실패 기록은 그대로다.

## 1. 책임과 한계

최초 독립 검토자 `/root/review_c_boundaries`가 실제 Solidity/JavaMath와 모든 신규 소스·테스트·runner, 세 clean 실행의 원본 결과와 현재 해시를 대조해 **발견된 필수 수정 없음**으로 보고했다. 검토자는 테스트를 재실행하거나 파일·서비스를 변경하지 않았다. scope 외 Receipt/crypto/canonicality는 이 검토의 안전 보장으로 확대하지 않는다. epoch overflow 테스트가 version도 MAX로 두는 점은 선택적 격리 개선 제안이며, 구현의 명시적 epoch add guard와 도달 가능한 상태 조건을 확인해 필수 지적으로 분류하지 않았다.

### 최신 실제 실행 — 총괄 재검증

후속 authorization 실행기 추가와 stderr fail-closed 개선 뒤 동일 제품 소스의 회귀도 실행했다. rules34PASS `20261023T023704Z-37851948`/`28cbfa06e36e6ac96df8a7db6a0dc7b9ccd025393bd672822e7f0f0a3621085f`, EIP22PASS `20261023T023707Z-7689f9ed`/`5811250311108d3db7f41ea7cb1bb569d55970f4379beeebc11c67fb54f892c9`, raw20PASS `20261023T023710Z-e4fba741`/`19dd49dbcd657212b05fdffd9cd273a02731fd4e43cf8d3622a4126d16218769`다. 모두 compile/test0·소스 불변·stderr 없음이다. 실행기 성공 경계 Python5PASS(compile0이어도 stderr/실행 누락/timeout/source 변경이면 차단)도 추가했으며 이 실행기 후속 delta는 authorization 단위의 독립 검토 대상이다. 앞선 rules 최초 독립 검토를 새 실행기 검토로 소급 확대하지 않는다.

- rules 34 PASS / FAIL·SKIP·ABORT 0: `deployment/runtime/c-proof/20261023T022600Z-ca5f509d`, sourceHash `e0dbfdb9beec86acd63d69be3abe4fd43d5ce3098cae221a558f2573ad0f9ca3`.
- 공용 runner 변경 후 EIP-712 22 PASS: `20261023T022603Z-98562bdb`, sourceHash `bcfa6d900f0f1c71b5b283d29a9897715522a3de4f593dcc6a5297a58f59c3ca`.
- signed transaction 20 PASS: `20261023T022607Z-c5fd84bb`, sourceHash `2205442db8558f03f4c39681077e21566a05adb2f618504278f195ebd757cfd4`.
- 세 실행은 compile/test exit0, 소스 불변, compile/test stderr 없음이다. 실제 DB/RPC/유료 호출은 0회다. 최초 독립 검토에 소스와 위 원본 증거를 전달했다.
- 최초 `20261023T021848Z-2dd67b11`은 테스트 소스의 double 지수 literal55개와 BigInteger helper1개 때문에 compile56오류, JUnit NOT_RUN이었다. 정확한 long literal과 BigInteger overload로만 수정했으며 금융식·예상값·정책은 바꾸지 않았다.
- 이후 sandbox 실행 `20261023T022502Z-f385838f`, `20261023T022506Z-36a3c797`, `20261023T022519Z-a85a291b`은 34/22/20 테스트 통과·exit0이지만 javac JAR 닫기 단계의 `AccessDeniedException`이 있었다. 이 환경 경고를 숨기지 않고 사용자 실행 환경에서 같은 소스를 재검증한 것이 위 최종 세 실행이다. 모든 최초 기록을 보존했다.

`CRulesTransitionModels/CRulesTransitionVerifier`는 전체 Report, 전체 rules position, block timestamp, 명시적 executor/symbol/policy, 이전 operation 소비 여부·activePosition 및 각 prestate mapping의 key를 받는다. 실제 `FuturesEngine`의 상태 전이·수학 조건을 계산해 전체 post-position, active pointer, operation 소비 결과와 **전체 `OperationApplied`/`FuturesReportConsumed` 기대값**을 만든다.

scope는 `RULES_TRANSITION_PREVALIDATED_CONTEXT_ONLY`이며 `completeCoverage=false`다. caller가 제시한 배포/tx 직전 값이라는 전제가 남는다. 이 코드에는 Oracle/user 서명, owner EOA 코드, 실제 executor msg.sender, 사용자 nonce 또는 Ethereum tx nonce, Receipt/canonical RPC/전체 감사 검증이 없다. executor 필드 일치는 실제 caller 권한 검증이 아니다. public Result record 자체를 위조 불가능한 인증 토큰처럼 사용하면 안 된다.

`PrestateKeys(positionId,accountRef,operationId)`는 어떤 position/activePosition/usedOperations 조회에 대한 입력인지 Report와 대조한다. bare boolean이 실제 chain query를 증명하지 않는다. NONE position은 실제 기본 storage처럼 모든 값 0이어야 한다. 축약 C4a Vault position에서 누락 필드를 임의 복원하지 않는다. terminal rules의 collateral/entry/q는 유지되며 Vault lock0과 다르다.

## 2. 변경 파일과 유지한 금융 규칙

신규 파일만 작성했다.

- `custody/proof/CRulesTransitionModels.java`
- `custody/proof/CRulesTransitionVerifier.java`
- `custody/proof/CRulesTransitionVerifierTest.java`
- 이 문서; 상위 계획에는 향후 token 0-transfer/no-op coverage 주의만 추가했다.

기존 계약과 Java `futures.math.FuturesMath`는 수정하지 않는다. **순수 Java 수학 한 파일을 직접 import**하여 q floor, 음수 PnL floor, MM ceil(현재 명목가치 5%), E<=MM, 담보 한도 realized 및 badDebt를 중복 구현하지 않는다. typed-data 서명과 회계/FIFO는 별도 검토된 부분이 후속 wrapper에서 연결한다.

Report TTL은 30초, grace는 60초 상수이며 caller가 늘릴 수 없다. observedAt+30, now+2, version+1, START의 epoch+1/now+60은 uint256 overflow를 거절한다. Report의 side/action/identity, observedAt 단조성, exact version/epoch/entry/q/before C, OPEN q·ACTIVE 중복·초기0, TOP_UP C 증액·회복, START/CLEAR·59/60·LIQ 신규 관측과 위험 지속을 검사한다.

`verifyEvents`는 계산한 전체 event record와 exact equals로 대조한다. price/unrealized를 생략하지 않는다. nonterminal의 event realized/badDebt는 0이지만 uncapped unrealized는 그대로다. 이 메서드는 Receipt event 개수/순서/emitter 검증을 대신하지 않는다.

## 3. C 전용 수치 golden과 독립 교차 계산

기존 C3 Foundry 시나리오의 **최소단위 wei 입력**과 실제 Solidity/Java 수학 소스를 읽고 아래 고정값을 JUnit에 명시했다. 이전 Foundry PASS를 새 Java PASS로 옮기지 않는다.

| 경우 | C / L / entry / mark | q | PnL | E | MM | realized / badDebt |
|---|---|---:|---:|---:|---:|---|
| Long 수익 | 1000 / 10 / 100e8 / 110e8 | 100 | 1000 | 2000 | 550 | 1000 / 0 |
| Long 손실 | 1000 / 10 / 100e8 / 80e8 | 100 | -2000 | -1000 | 400 | -1000 / 1000 |
| Short 손실 | 1000 / 10 / 100e8 / 120e8 | 100 | -2000 | -1000 | 600 | -1000 / 1000 |
| 음수 floor | 2 / 2 / 1e8 / 74999999 | 4 | -2 | 0 | 1 | -2 / 0 |
| E=MM | 2 / 2 / 1e8 / 75000000 | 4 | -1 | 1 | 1 | -1 / 0 |

위 다섯 경우를 verifier 밖의 로컬 Python 정수 `//` 및 ceil 식으로 실제 다시 계산해 표와 일치함을 확인했다. 파일/DB/RPC·서명을 사용하지 않은 독립 산술 교차 계산이다. 실제 on-chain 실행 증거 또는 Java 단위 테스트 성공은 아니다.

경제적 순서는 OPEN version1 → START at1000/deadline1060/version2 → LIQ at1060/new observed1060/version3다. 앞서 ABI 문법 golden의 임의 version을 경제적 실행 증거로 재사용하지 않는다.

## 4. 테스트 초안과 실제 실행 구분

JUnit 34개는 Long/Short×2/5/10, 양수/음수/1wei rounding, retained terminal collateral, TOP_UP 회복/위험 유지, START/CLEAR/LIQ, 59/60 및 신규 관측, TTL30/미래2초, lastObservedAt, strict 수치 상한·잘못된 레버리지, NONE 기본값, active pointer·terminal 재사용, identity/action/각 prestate 필드, operation 중복과 uint overflow를 포함한다.

전체 OperationApplied의 11개 필드와 ReportConsumed의 3개 필드를 하나씩 변경하여 실패를 확인하는 테스트를 작성했다. 입력 record 불변성과 결과 scope도 확인한다. 이 테스트의 시간값은 오프라인 수치이며 실제 벽시계 60초 인수를 대체하지 않는다.

현재 작성자가 실제 실행한 것은 독립 산술 다섯 경우 교차 계산뿐이다. Java compile/JUnit/독립 검토와 실제 source identity는 총괄의 순차 runner 결과로 누적한다. 테스트 초안 개수를 실행 PASS로 보고하지 않는다.

## 5. 다음 경계

이 단위 검증/독립 검토 후 C4c-2c-2가 실제 raw hash/sender, C EIP-712, owner/code/nonce/Intent 범위, Receipt metadata/full events와 연결한다. 그 뒤 C4a reducer가 기존 State에서 자산·FIFO 이벤트 대응을 계산한다. 순수 수학 성공을 사용자 승인·실제 청산·canonical 확정·전체 감사 MATCH로 표현하지 않는다.

## 6. 최초 compile 실패와 테스트 fixture 수정

실제 총괄 실행 `deployment/runtime/c-proof/20261023T021848Z-2dd67b11/compile.stderr.log`에 56개 compile 오류가 기록됐다. 이때 JUnit은 실행되지 않았으며 작성된 34개를 PASS로 취급하지 않는다. 원본 실패 로그와 source snapshot은 보존한다.

55개는 Solidity 스타일 `100e8` 등 표기를 Java 테스트 helper의 `long` 인자에 넘긴 오류다. Java에서 해당 literal은 정수가 아니라 double이며 compiler가 손실 변환을 거절했다. 나머지 1개는 UINT256 경계용 BigInteger timestamp를 long-only helper에 전달한 오류다.

금융 코드/검증 조건/기대 정답은 바꾸지 않고 테스트의 55개 price literal을 `10_000_000_000L` 등 **정확한 정수 literal**로 교체했다. BigInteger timestamp helper overload를 추가하여 원래 전체 폭을 보존하며, long helper는 `BigInteger.valueOf`로 그 overload에 위임한다. double cast 또는 `longValue` 절단을 넣지 않았다. 테스트 파일 SHA는 `04b8a7a2071a6ae5f04cecd11a0986534ba9c44eb639b3e01593a49d08a9cb5b`다. 수정 후 compile/JUnit·공유 회귀 재실행은 총괄이 수행해 별도 기록한다.
