# C5a2 — 실제 유예 및 작은 house Claims 인수

## 최신 실제 결과 — C5a2 PASS

구현 후 독립 재검토1/2 `review_c5a2_implementation`은 실제 소스/계약/최종offline 원본을 대조해 **발견된 필수 수정 없음**이었다. 검토자는 테스트·Docker·RPC를 재실행하지 않았다. 첫 설계1회+재검토1회 사용, 구현 수정 시 재검토1회가 남는다.

실행 직전 RAM2,121,523,200bytes/Cfree32,780,410,880bytes·실행컨테이너0 확인 후 **새** `deployment/runtime/c-anvil/canvil-9c60eadeb80647f8/result.json`에서 **C5A2_CONTRACT_PASS_NOT_SERVER_OR_FULL_AUDIT/failure=null**을 확인했다. sourceIdentity `6ffb45c5e07187ed647f8281e048893c7dba522363ff55fdfeaf126deccbf1cb` 불변이다. 소유 Java/Anvil 정상정지 true/stopOrPreservationFailures={} 및 stopped-state 보존 완료. 기존 성공/실패 fixture는 재사용하지 않았다.

- 실제 Long START1792739588/deadline1792739648/LIQ1792739651, Short START1792739590/deadline1792739650/LIQ1792739652. Long monotonic62,334ms/wall62,418ms, Short monotonic62,125ms/wall62,208ms. 시계조작 없이 둘 다 실제60초와 블록 기한을 충족했다.
- START→CLEAR→재START·owner nonce 취소 후 keeper 청산, Long/Short 각각 realized−1000/badDebt1000·실제담보1000만 house 귀속·terminal lock0 및 원래 free 직접 인출을 확인했다. 조기 청산 거부는 실제 deadline 전 canonical Receipt와 무변경 상태로 검증하며 특정 revert reason decode로 과장하지 않는다.
- 작은 house100에서 A CLOSE profit1000→paid100/claim900, B TOP_UP 후 CLOSE profit1000→paid0/claim1000을 확인했다. FIFO400→500/house1wei→B1wei→나머지 상환, 빈 queue 무변경·한 호출 한 head·owner/op/position/original 불변·자산/부채 보존·미지급 및 pause OPEN 거부와 허용 동작·최종 free 직접 인출을 통과했다. claims-summary remaining0이다.
- Spring/JWT/Oracle Report 없이 직접 free 인출은 실제 성공했지만 실행중 서비스를 종료한 인수는 아니다. **serverShutdownAcceptance=false/realMetaMask=false/productSynthetic=false/postgresRun=false/completeCoverage=false**다. C4b PG10PASS와 본 결과를 합쳐 아직 없는 canonical scanner·C서비스·웹·제품 Worker 통합 인수로 표현하지 않는다. Oracle 중단 locked 실제30초도 별도 미검증이다.

다음은 승인 범위의 C 서비스/웹 연결이다. 먼저 C opt-in identity·지갑귀속·고정블록 RPC조회, 이후 C작업 metadata/raw·Receipt·한번재가격·직접거래 수집과 웹을 작은 검증 단위로 연결한다. B DB담보 예약·B domain/ABI를 C 자산 정본으로 재사용하지 않는다.

## 최신 — 구현 동결 / 오프라인28 PASS / 구현 후 독립 재검토 중

신규 `CAnvilRiskClaimsScenario.java`와 기존 gate의 좁은 dispatch/runtime 증거 suffix, Python의 명시 mode/scope/identity 및 회귀를 구현했다. 초기 독립 설계 검토는 `review_c5a2_scope`의 **발견된 필수 수정 없음**으로 완료했다. 계약·금융식·기존 transport/send/canonical Receipt·RAM·290/300초·128MiB 총상한은 변경하지 않았다.

실제 `deployment/runtime/c-anvil-offline/20261023T065808Z-9108ce4b/result.json`, sourceIdentity `6ffb45c5e07187ed647f8281e048893c7dba522363ff55fdfeaf126deccbf1cb`: fresh offline build/metadata·Java compile·기존 self-test·신규 scenario self-test·Python **28 PASS**. exit 모두0/source불변/compile 및 scenario stderr0이다. Docker/RPC/유료0. 구현 후 독립 재검토1/2를 `review_c5a2_implementation`이 수행 중이며 실제 C5a2는 **NOT_RUN**이다. 이전 C5a1/PG PASS는 각각 이전 identity의 증거로 보존한다.

실제 대기에서 두 START 이후 monotonic 및 wall elapsed 각각60초 이상과 자연 벽시각의 두 deadline 경과를 기다린다. 이후 미지급0인 Vault의 `processNextClaim` 무변경 실제 tx/empty logs로 자연 시각 새 블록을 확인하고 새 Report를 생성한다. 시간 조작 RPC는 추가하지 않는다. 실제 Oracle 중단30초 locked 및 실행중 서버 종료/C7 인수는 이 시나리오로 완료했다고 주장하지 않는다.

## 현재 상태

구현 초안 / Python28PASS / Java·실환경·구현 후 검토 NOT_RUN. 승인 경제 정책은 변경하지 않는다. C5a1 `canvil-468f07cdb57c46aa` 실제 자산 흐름 PASS와 C4b `cpg-3dfbda7f33414a95` PG10PASS는 각각 별도 증거다. C5a2의 위험·Claims 성공으로 자동 승계하지 않는다.

## 최소 구현 및 검토 범위

기존 `c-anvil-check.py`의 offline/source/artifact/image·신규 내부망·무공개포트·격리·RAM·단일전송·normal stop/state 보존을 재사용한다. 신규 scope와 Java 시나리오1개, 필요한 dispatch/테스트만 추가한다. 기존 C5a1 기능·계약·금융식·RPC allowlist·290/300초 watchdog은 완화하지 않는다. 초기 설계 독립검토1회+구현 후 재검토 최대2회로 관리한다. 기존 소진 단위의 지적이나 한도를 우회하지 않는다.

## 실제 60초 시나리오

새 배포에서 A Long10/B Short10 각각 entry100/C1000/q100. 가격94/106에서 위험(Equity400/MM470 또는530), START_CALL→100 회복 CLEAR_CALL→다시 START_CALL. 사용자 nonce 취소와 keeper 위험권한은 독립이다. 실제 canonical START block timestamp/deadline 및 monotonic 경과를 저장하고 두 유예를 함께 실제60초 이상 기다린다. warp/시계조작 RPC 금지. 각 새 실행 직전에 새 Report를 생성한다. 조기 LIQ 거부는 실제 mined timestamp가 deadline 전임을 확인해야만 인정한다. 최종80/120에서 각각 realized−1000/badDebt1000, 실제 담보1000만 house로 귀속하며 추가채무/새 현금/claim을 만들지 않는다.

## 작은 house FIFO 시나리오

같은 새 소유 체인에 별도 신규 controller/Vault를 배포하고 house100/A·B collateral1000, entry100/q100으로 둘 다 미리 OPEN한다. A CLOSE110은 profit1000 중100만 paid/free, claim1=900이며 position terminal/lock0이다. A principal+paid1100 직접withdraw 후 실제house400 출자. 기존 B TOP_UP10 허용 후 CLOSE110에서 house400이 있어도 paid0/claim2=1000이다.

미지급 신규 OPEN은 나머지 유효한 서명·Report로 EVM 거부하며 금융 변화0이다. 누구나 processNextClaim으로 A400 지급. 실제500tokens+1wei 출자 후 한번의 호출은 A500만 지급하여 head2/house1wei, 다음 호출은 B1wei만 지급. 실제1000tokens−1wei 출자 후 B완납, 빈 queue 재호출은 무이벤트/무변경이다. 각 claim owner/원 operation/position/original 불변과 original=paid+remaining, totalUnpaid=합계를 대조한다. 각 단계 실제VaultToken=free+locked+house, Claims 지급에는 외부 Transfer없음, ownerwithdraw에만 실제 Transfer를 확인한다. 필요한 OPEN-only pause 경계도 동일 정책으로 검증한다.

## 경계 및 다음 단계

이 인수는 시험 EOA/통제 Oracle/Anvil-only다. free directwithdraw가 Spring/JWT/Report 없이 성공하더라도 실행중 서비스 종료·MetaMask 인수나 제품 Synthetic/서버5초/Worker/full coverage로 표현하지 않는다. Oracle 중단 locked fail-closed를 포함하면 실제30초 만료와 유효 서명·나머지 조건을 분리해 검증한다. 실제 실행 성공 후에만 승인된 C 서비스·projection·웹 연결을 다음 의존 단위로 진행한다. 기존 성공/실패 fixture·DB·체인·OPEN·AI ledger/STOP/claim·원본/다른worktree는 변경하지 않는다. commit/merge/push·유료·전역재시작0.

## 구현 경계

신규 `deployment/CAnvilRiskClaimsScenario.java`가 기존 package-private gate helper를 조합한다. Long/userA/action고정인 C5a1 `executeUser`는 변경하거나 재사용하지 않고, 각 owner/side/version/epoch/nonce·고유 operation을 받는 시나리오 helper를 사용한다. 기존 `CAnvilContractGate.java`는 명시 C5a2 scope dispatch와 배포별 runtime 증거 suffix overload만 추가했다. RPC/서명raw 선저장/단일전송/정확 Receipt·로그 검증/총290초·128MiB cap은 같은 gate 인스턴스를 사용한다.

`c-anvil-check.py`는 `offline-risk-claims`와 `run-risk-claims`를 명시 추가하고 mode를 sourceIdentity/manifest/result에 연결한다. 기존 C5a1 PASS를 C5a2로 올리지 못하도록 정확 scope/source/risk+claims boolean을 검사한다. 기존 이미지·RAM/격리·300초 외부 watchdog·normal stop/state 보존은 유지한다. 새 Java 소스는 컴파일 whitelist에 추가한다. Python 회귀는 scope 혼용·잘못된 boolean/identity 성공 승격 거부를 추가한다.

Anvil이 대기 중 새 block을 만들지 않는 조건을 고려해, 각 START 이후 monotonic/wall 모두60초 및 실제 wall epoch가 두 deadline을 지난 뒤 `processNextClaim` 무지급/no-event 트랜잭션으로 자연시각 block을 생성한다. 그 canonical timestamp가 deadline을 지났는지 확인하고 새 Report를 생성한다. 시계/광산 조작 RPC를 추가하지 않았다. 대기 중에도 총 watchdog은 초기화하지 않는다. 실제 조기청산 거부는 mined time<deadline/Report unexpired/not OOG/포지션·nonce·장부·operation 무변경을 확인한다. 구체 revert selector를 해독한 결과로 표현하지 않는다.

Claims 시험은 미지급 OPEN 거부를 pause가 꺼진 상태에서 먼저 검증한 뒤 OPEN pause를 켜고 실제 funding/process/기존 free 인출이 계속 가능함을 검증한다. 전액 상환 후에도 pause OPEN은 별도 거부된다. 실제 Oracle 중단·30초 만료 locked 시험과 실행중 서비스 종료 인수는 이 코드에서 아직 미구현이며 C7 후속으로 남는다.

## 실제 결과

- 초기 설계 검토 `review_c5a2_scope`: **발견된 필수 수정 없음**. 최초1회 사용, 구현 후 재검토 최대2회는 남아 있다. 검토자는 실제 서비스/시험을 실행하지 않았다.
- 구현 중 서버 세션 재시작으로 Python/기존 Java의 mode dispatch만 부분 저장됐고 신규 scenario는 미저장 상태였다. 복구 에이전트는 파일/hash/실행컨테이너0을 확인해 부분 변경을 유지하고 이어 구현했다. 성공 Gate를 반복 실행하지 않았다.
- `python -B -m unittest discover -s deployment -p test_c_anvil_check.py`: **28PASS/0FAIL**, 약0.036초. Docker/RPC/Java/forge 호출0인 순수 Python 시험이다.
- Java 컴파일/자가검증·신규 동결 offline identity·구현 후 독립 재검토·실제 새 C5a2 Gate: **NOT_RUN**. 실제60초/Claims/직접 free 인출은 통과 전 완료로 표시하지 않는다.
