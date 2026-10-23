# C5a1 — Anvil 단독 계약 인수 하네스 구현

## 최신 실제 Gate — C5a1 계약 자산 흐름 PASS

사용자 RAM 확보 후 실제 RAM3,158,458,368bytes/Cfree28,287,913,984bytes·실행컨테이너0을 확인했다. 최종 동결 offline `20261023T055323Z-d714d8bd`를 그대로 소비한 **새** `deployment/runtime/c-anvil/canvil-468f07cdb57c46aa/result.json`은 **C5A1_CONTRACT_PASS_NOT_C5A2_OR_FULL_AUDIT/failure=null/source불변**이다. 기존 RAM 실패 fixture는 재사용하지 않았다.

신규 내부망 Anvil/JRE에서 고정공급20m·H10m/U2m/R8m 실제배분, approve/deposit/free withdraw, Long10 OPEN→TOP_UP→CLOSE 및 11개 유효경제조건의 승인 거부, 실제 canonical 거래·Receipt/Event·code/getter/domain·Vault/token/house/terminal lock 정합성을 검증했다. sourceIdentity `b1645a5e15efa09e420d33ceca6aa6842eaf3b25be8048d4b5f19c7f40095a84`, chainId31337/genesis `0xae283835936cb9220312d3784866696da74e5aad65557862d200830d4f903518`. token `0x7d55968990226d1f75d27648eaf4fadf226b9da7`, controller `0xbe8da7724ec51025f0bb50649a57e56c36633538`, rules `0xe69368becd32774694fe3318b9a8089138e065bd`, Vault `0x2f793d93fba1b1f1490ef3d05ec7d0bbb9d3f320`.

소유 Java/Anvil 모두 정상정지 true/stopOrPreservationFailures={}이며 Anvil stopped-state 파일 보존을 확인했다. 신규 네트워크/볼륨/컨테이너/실패·성공 원본은 삭제하지 않았다. 시험 EOA 결과다. **realMetaMask=false/riskWall60Run=false/claimsInsolvencyRun=false/completeCoverage=false/postgresRun=false**를 유지한다. 제품 Synthetic/Worker/서버5초/실제60초·Claims·새 C서비스/웹·전체 감사 인수로 표현하지 않는다. 이어 별도 신규 PG Gate를 순차 실행했다.

## 최신 재개 — 소스·산출물·이미지 불변 / RAM 부족으로 새 Gate 미시작

사용자는 기존1.25GiB RAM 기준 유지와 조건 충족 시 새 Gate1회를 승인했다. 총괄이 최종 offline `20261023T055323Z-d714d8bd`/`b1645a5e15efa09e420d33ceca6aa6842eaf3b25be8048d4b5f19c7f40095a84`의 현재 source/contract/fixture SHA 전부 일치를 재확인했다. 고정 Foundry/JRE 이미지 실제 ID 및 Linux/amd64도 일치한다. 동일 하네스 재작성·불필요한 build/회귀는 수행하지 않았다.

읽기 전용 관측 RAM658,649,088bytes(약628MiB)는1,342,177,280bytes 기준보다 약652MiB 부족하다. Cfree27,088,199,680bytes, 실행 컨테이너0. 이번 재개에서 `run`을 호출하지 않았고 새 fixture/network/volume/container/RPC/계약배포/거래는0이다. 이전 RAM 실패 fixture도 그대로다. 사용자가 작업 저장 후 사용하지 않는 IDE/탭을 정상 종료하도록 구체적으로 안내했으며 에이전트가 앱을 종료하거나 기준을 내리지 않았다.

C4b 추가 독립 검토1회는 별도 승인으로 필수 수정 없음 완료, 새 PG Gate1회는 미사용이다. 자원 확보 후 Anvil→PG를 순차 실행하고 통과 의존성에 따라 실제60초/Claims로 이어간다. 실제 C5a1/2 자산 흐름은 계속 **NOT_RUN/BLOCKED**이며 과거 오프라인 PASS를 실제 거래로 바꾸지 않는다.

## 최종 실행 결과 — 준비 검증 완료 / 실제 Gate RAM 사전검사 BLOCKED

마지막 독립 재검토2/2는 필수 지적 없음이고 신규3파일 소스는 최종 오프라인 identity `b1645a5e15efa09e420d33ceca6aa6842eaf3b25be8048d4b5f19c7f40095a84` 그대로다. 신규 실환경 시도 **1회** `deployment/runtime/c-anvil/canvil-b33d2f73684240df`는 `C5_RAM_LOW`로 **BLOCKED**다. 실행 직전 가용 RAM은1,285,423,104bytes(약1.197GiB)로 기존1.25GiB 기준보다 작았다. C 드라이브 여유27,112,255,488bytes는 충분했으나 RAM 검사를 무시하지 않았다. 앞선14:55KST1.335GiB 관측은 당시 값이며 실행 시 값으로 대체하지 않는다.

source불변이며 **network/volume/container 생성·Anvil/JVM 시작·RPC·계약 배포·토큰 이동·거래는 모두0**이다. 결과 commands의 두 container inspect not-found는 cleanup 소유권 확인이며 최초 실패 원인이 아니다. `ownedContainersStopped={}`와 `anvilStatePreserved=null`을 보존 성공으로 바꾸지 않는다. 새 fixture/source copy/resources/result는 그대로 보존했고 동일 fixture 재사용·자동 새 run 재시도·임계 인하를 하지 않았다.

따라서 구현·오프라인26PASS·독립 검토는 완료했지만 실제 C5a1 OPEN/TOP_UP/CLOSE/11거부·Linux JDK/mount/state와 C5a2 60초/Claims는 **NOT_RUN**이다. 메모리 여유가 안정적으로 확보되면 새 자원 사전검사 후 새 identity1회부터 재개한다. 기존 앱·서비스는 에이전트가 종료하지 않는다. C4b PG는 별도 검토 한도 예외 승인 전 계속 BLOCKED/10개 NOT_RUN이다.

## 마지막 독립 재검토 완료 / 실제 Gate 전제 확인

`review_c_boundaries` 마지막 재검토(2/2)는 **발견된 필수 수정 없음**이다. 이전4건의 fail-closed 설정·실패 원문 보존·신규 mount/정상stop/state 경계·11개 유효 calldata 거부 시나리오 및 최종26PASS 원본/source hash를 대조했다. 검토자가 실제 Docker/RPC를 실행한 것은 아니며 검토 주기는 소진됐다.

2026-10-23 14:55KST 총괄 읽기 전용 관측은 RAM1,400,348KiB(약1.335GiB), Cfree27,125,587,968bytes, Docker Server28.4.0·실행 컨테이너0이었다. 이전 RAM 미달 관측을 덮어쓰지 않는다. 이 관측은 실행 보장이 아니며 `run` 내부에서 자원과 source/compiled identity/격리를 다시 확인한 후에만 신규 전용 Gate1회를 시작한다. 현재까지 실제 C Anvil 거래는 NOT_RUN이다.

## 최종 보완 실제 실행 — 오프라인26 PASS / 마지막 재검토 요청

main 정상 권한 진단 `c-anvil-diagnostic/20261023T055259Z-7fa8332a`는 compile/self-test0, source불변이었다. 이어 새 전체 `c-anvil-offline/20261023T055323Z-d714d8bd`, sourceIdentity `b1645a5e15efa09e420d33ceca6aa6842eaf3b25be8048d4b5f19c7f40095a84`에서 fresh offline build/artifact 검증/Java compile/self-test/Python **26 PASS**, exit모두0·소스불변을 확인했다. JDK reflection 검사는 이 Windows JDK21.0.10의 실제 실행이며 Linux 고정 이미지에서도 전송 전에 다시 확인해야 한다. 실제 mount쓰기/정상stop/state/거부 Receipt는 아직 NOT_RUN이다.

신규3파일을 동결해 마지막 독립 재검토(2/2)에 넘긴다. 기존 초기·실패·19PASS 증거는 보존하며 이26PASS도 실제 Docker/Anvil/C 자산 이동·PG·60초·MetaMask·전체 감사 성공이 아니다. 모든 구현 재검토와 실제 자원 Gate를 통과하기 전 live 실행하지 않는다.

## 최신 상태 — 첫 구현 재검토 필수4건 보완 / 최종 실행·검토 대기

첫 구현 독립 재검토에서 아래4건이 필수로 확정됐다. 설계 최초1회·구현 첫 재검토1회를 사용했으며 **마지막 재검토1회만 남는다**. 이전 source/실행 결과는 보존하며 아래 새 코드가 실제 실행됐다고 소급하지 않는다. 현재 자체 Python **26 PASS**, 수정 후 Java diagnose/최종 full offline/마지막 독립 재검토/실제 Docker Gate는 아직 NOT_RUN이다.

1. 기존 `supplier` 호출1회는 JDK HttpClient 내부 retry0을 입증하지 못했다. 새 JVM에 `jdk.httpclient.disableRetryConnect=true`, `jdk.httpclient.enableAllMethodRetry=false`, `jdk.httpclient.redirects.retrylimit=1`을 명시하고 시작 시 검증한다. process-local `--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED`로 고정 JDK21 `MultiExchange`의 `RETRY_CONNECT=false`, `RETRY_ALWAYS=false`, `max_attempts=1`을 whitelist reflection으로 실제 확인한다. 필드·타입·값이 다르거나 접근 불가면 RPC 이전 fail-closed다. host21.0.10과 이미지 JRE21.0.12.1을 동일하다고 가정하지 않는다. actual 이미지의 시작 검증은 실제 Gate에서 별도로 필요하다. 이는 패킷 캡처에 의한 wire 횟수 증명이 아니며 고정 JDK implementation 경계다.
2. 기존에는 Receipt 검증 실패 시 원본 관측을 잃을 수 있었다. 새 코드는 bounded HTTP Receipt 응답 bytes를 JSON-RPC parse 이전에 private append-only 저장하고, 얻은 전체 Receipt도 의미/canonical/status 검증 전에 저장한다. 이후 성공·검증 거부의 별도 verdict를 기록한다. malformed/불일치 관측을 정상 Receipt로 승격하거나 재전송하지 않는다. 크기 초과는 원본 전체 보존보다 메모리 상한과 STOP이 우선이다. 고정 무비밀 self-test는 관측 원문 유지·덮어쓰기 거부·실패 판정을 확인한다.
3. 실제 이미지 기본 사용자가 Foundry `foundry`, JRE `10001:10001`이므로 command 성공만으로 새 볼륨/증거 쓰기를 보장할 수 없었다. Anvil에 **명시적0:0**을 사용하되 새 owned named volume `/state`만 RW이고 host bind는 없으며 read-only root/cap-dropALL/internal no-p를 유지한다. 새 볼륨에만 실제 쓰기 probe를 수행하고 chmod/chown 또는 기존 권한은 바꾸지 않는다. Java는 명시적10001:10001, 새 private bind에서 실제 write/read probe를 키 생성·RPC보다 먼저 수행한다. stop return0뿐 아니라 actual State Running=false/OOMKilled=false/ExitCode=0/Error없음을 확인한다. 정상 정지한 Anvil의 state 파일을 bounded16MiB tar pipe로 새 private 위치에 복사하고 size/SHA를 기록한다. 추출 경로·symlink·중복 멤버·과대/빈파일을 거부하며 보존 불충분이면 Gate PASS를 금지한다. 이는 실제 재시작 복구 검증이 아니다.
4. 실제 거부 예정 시나리오를 완전한 현재 유효 OPEN calldata로 확장했다: empty user signature, Oracle-as-owner, 다른 사용자 signer, nonownerDirect, owner-as-Oracle, 다른 controller/chainId의 사용자 domain, 유효 서명이나 잘못된 nonce, 유효하게 서명한 범위 밖 실행가격/수량, relayer→rules.open 직접 호출이다. 성공 전 같은 operation/nonce가 아직 미사용임과 원상태를 대조한다. 실패 tx의 실제 block timestamp가 Report/Intent 범위 안인지, gasUsed가 gasLimit보다 작은지도 확인하며 만료/OOG에 의한 실패를 사용자 권한 거부 성공으로 표시하지 않는다. `rules.open` selector는 새 artifact methodIdentifiers와 대조한다. 실제 EVM 결과는 계속 NOT_RUN이다.

이 수정 묶음은 신규3파일 및 해당 문서에만 반영했고 C 경제 정책·계약·기존 proof/DB/보존 fixture는 변경하지 않았다. 자원 기준1.25GiB는 낮추지 않으며 실제 리소스 시작은 main의 마지막 검토·자원 사전검사 이후에만 가능하다.

## 최신 실제 판정 — 최종 오프라인 PASS / 구현 후 첫 재검토 요청

### 후속 독립 판정 — 필수 수정4건 / 실제 실행 금지

구현 후 첫 재검토(1/2)는 **필수 수정 있음**이다. (1) supplier1회만으로 JDK HttpClient 내부 connect retry0을 보장하지 못하므로 process-local 설정·시작 검증 필요, (2) 예상 밖 revert/metadata 실패의 Receipt도 검증 전에 bounded private 원본으로 보존하고 판정을 분리, (3) 이미지 foundry/UID10001의 신규 mount 실제 쓰기 및 정상 종료 state flush 확인, (4) 계획의 owner/domain/nonce/범위변조·직접 rules 거부 시험이 누락됐다. 검토자는 소스·실행 원본을 읽고 로컬 JDK `javap`를 수행했으며 Docker/RPC는 실행하지 않았다. 실제 쓰기 실패나 실제 내부 재전송이 발생했다고 확정한 것은 아니다.

신규3파일에서만 보완하며 계약·경제 정책·기존 리소스 권한은 변경하지 않는다. 수정 후 오프라인 재검증과 **마지막 재검토1회**가 남는다. 이전 오프라인 PASS는 해당 당시 소스에 귀속되고 수정본의 완료 판정이 아니다. 실제 C5a1은 여전히 NOT_RUN이며 모든 준비를 통과하기 전에 실행하지 않는다.

총괄 정상 권한의 `c-anvil-diagnostic/20261023T053844Z-b50751eb`에서 Java compile/self-test exit0, source metadata 검증·오류 경계 시험을 통과했다. diagnostic은 live 전제조건으로 재사용하지 않는다. 이어 별도의 새 전체 `c-anvil-offline/20261023T053905Z-16539109`, sourceIdentity `484fe5933469194b07d16c24c05386d3d83b6707a3bf753e87450336c34ea3e1`은 fresh offline forge build/artifact 검사/Java compile/self-test/Python **19 PASS**, exit 모두0·소스 불변이다. Docker/RPC/유료 호출0이며 실제 Anvil 성공이 아니다.

신규3파일을 동결하고 같은 C5a1의 구현 후 첫 재검토를 요청한다. 최초 설계1회가 완료됐으며 구현 후 재검토는 최대2회다. 아래 NOT_RUN은 해당 당시 시점의 기록이며 원본 실패 증거를 덮어쓰지 않는다. 메모리는 실제 실행 직전에 다시 확인해야 하고 기준 미달이면 BLOCKED를 유지한다. 현재 실제 C 배포·자산 이동·거래·60초 유예는 NOT_RUN이다.

2026-10-23. C5a 계획의 최초 독립 설계 검토는 필수 지적 없이 완료됐다. 아래는 **하네스 작성과 실제 실행 결과를 구분한 첫 동결 기록**이며, 구현 후 독립 재검토는 아직 NOT_RUN이다. C4b PG 실행·검토 예외는 계속 승인 대기이고 이 하네스는 PG를 실행하지 않는다.

## 변경 범위

- `deployment/c-anvil-check.py`: 새 run만 쓰는 offline artifact/JDK 검증 및 post-review Anvil/JRE orchestration.
- `deployment/test_c_anvil_check.py`: 순수 Python guard 시험15개.
- 이후 artifact metadata 정규화 회귀2개를 추가해 현재 작성·자체실행17개다.
- `deployment/CAnvilContractGate.java`: 좁은 actual RPC/서명/배포/Receipt/C5a1 시나리오 및 네트워크 없는 self-test.
- 기존 계약, 금융식, proof/wrapper/reducer, schema/store, PG 하네스 및 다른 worktree는 변경하지 않았다.

## 준비·전송 경계

`offline`은 알려진 Foundry141 실행의 c42 계약28개 SHA를 대조하고 새 run의 독립 out/cache에 `forge build --offline --force -j1`만 수행한다. 테스트141개를 다시 실행하거나 실제 체인 성공으로 승계하지 않는다. 생성 artifact의 compiler/settings/rawMetadata/compilationTarget/source hash와 source-map을 보존하고, 외부 OZ 원본까지 새 fixture에 복사한다. Java가 실제 bytes의 Keccak를 metadata와 비교한 뒤에만 RPC에 접근한다. 캐시 artifact 존재만으로 provenance를 선언하지 않는다.

신규 역할키 treasury/admin/relayer/Oracle/userA/userB는 Java에서 만들고 첫 자산 설정/전송 전에 신규 private evidence에 선저장한다. 실제 사용자·MetaMask 키는 사용하지 않는다. 신규 Anvil의 native gas만 bootstrap하며 ERC-20 저장소 조작은 없다. stdout에는 raw/key/signature가 없고, 원본 raw·Receipt는 private 영역에만 쓴다.

RPC는 단일 내부 URL·method whitelist·명시적 no-proxy·redirect 금지다. 전체 비동기 응답 future에 8초 deadline을 적용하고 bounded body subscriber가 allocation 전1MiB cap을 강제한다. 실패/timeout은 future와 subscription을 취소하며 **유료 여부와 무관하게 거래 send 재시도0**이다. signed raw/hash/nonce 선저장 이후 응답 불확실은 STOP이고 자동 재전송·새 거래를 하지 않는다. 영수증 조회만 최대45초의 읽기 polling이다. Java 전체290초/Python wait300초 제한과 각 evidence2MiB/전체128MiB 한도를 둔다.

각 Receipt는 실제 txHash/block/hash/index/from/to/status와 전체 로그의 tx/block/index/removed/log순서를 확인한다. 조회 transaction의 nonce/input/value/to/hash 및 block.transactions[index]도 선저장 raw 예상과 대조한다. 생성 주소는 sender/nonce RLP의 Keccak와 대조한다. 정상 이벤트는 exact emitter/order/count/full value를 reviewed EventCodec으로 대조한다. codeHash/고정 runtime bytes/immutableReferences 반복 일관성과 실제 getter/domain을 확인한다. immutable AST 변수별 슬롯 이름까지 증명하는 것은 아니며 이 한계를 code evidence에 명시한다.

조회 getter들은 `latest`에서 순차 관측한다. 한 atomic blockcut 전체 state나 전체 체인 scanner/reorg 확정으로 표현하지 않는다. Receipt block hash의 숫자 조회 재대조는 해당 시험 거래의 시점 연결이다.

## 실제 C5a1 작성 시나리오

고정20m constructor mint → 사용자A/B 각1m transfer → house10m 실제 fund/treasury8m → 사용자 deposit/free withdraw → relayed OPEN1000 → relayed TOP_UP1500 → direct CLOSE(principal1500+profit500) → free/locked/house/Claims/terminal rules lock 차이 확인이다. 모든 token 단위는18dec 정수이고 가격은E8이며 계산은 기존 Java FuturesMath를 그대로 재사용한다.

사용자 승인 부재/다른 역할 서명·Oracle 부재는 **완전한 현재 유효 OPEN calldata**의 empty user signature/wrong Oracle-as-user/nonownerDirect/wrong user-as-Oracle 호출을 실제 실패 tx로 확인하도록 작성했다. nonce/free/usedop/positionOwner가 바뀌지 않는지도 확인한다. 별도의 token mint/burn selector·Vault privileged lock·비관리자 pause 실패는 해당 권한/ABI 경계 시험으로 구분한다. malformed ABI만으로 사용자 승인 거부가 입증됐다고 표시하지 않는다.

새 Docker internal network/no host ports/캐시 image ID/명시적 entrypoint만 사용한다. Foundry `sha256:0c00…`은 image config ID이며 검증하지 않은 repo manifest digest로 바꾸지 않는다. Anvil은 `/usr/local/bin/anvil`, `--accounts0 --silent`, 상태는 새 owned volume, JRE는 이미지 Spring ENTRYPOINT가 아닌 `java` override다. 실제 network internal/subnet/label, volume label 및 container network/ports/entrypoint/mount/source/RO를 inspect한다. 종료는 정확한 run label의 소유 컨테이너만 stop하고 데이터·실패증거·네트워크·volume을 삭제하지 않는다.

## 실제 검증 결과 / 미완료

- 자체 Python guard: `python -m unittest discover -s deployment -p test_c_anvil_check.py -v` **15 PASS**, 실제 Docker/RPC0. 이미지/entrypoint/계정0/호스트포트 금지·단위범위·immutable range/환경주입/append-only/실패승계 guard를 검증했다.
- `offline` 1차 `c-anvil-offline/20261023T050945Z-6f589dc9`: **BLOCKED**, forge `svm-rs could not detect user home directory` panic, exit3221226505. 계약 소스 불변. artifact/Javac/JVM self-test는 아직 미실행.
- 기존 validated runner와 맞춰 APPDATA clean-env 보존을 추가한 뒤 2차 `20261023T051008Z-6a0285ea`도 같은 경계 **BLOCKED**. 첫 실패를 덮어쓰지 않았고 반복 자동 실행은 하지 않았다. 별도 권한 환경의 main 검증을 요청한 상태다.
- main의 정상 권한 실행 `20261023T051039Z-a91a37f4`에서는 새 run의 forge build가 실제 exit0이었으나 `C5_ARTIFACT_METADATA`로 중단했다. 실제 fresh artifact를 비교하니 Forge parsed metadata는 solc rawMetadata의 빈 context remapping 앞 `:` 제거·NatSpec 일부 축약·ABI 순서 재정렬을 수행했다. compiler 원문 rawMetadata를 authoritative로 사용하고 compiler/language/sources/version, 빈 context만 정규화한 전체 settings, raw/parsed/artifact 전체 ABI 객체의 중복 없는 sorted 비교를 하도록 최소 수정했다. 실제 compiler/source/settings/ABI 차이는 계속 거부한다. 이 허용 범위와 변조 거부 회귀2개를 추가해 자체 Python **17 PASS**다. 새 수정본 forge/Javac/self-test 재실행은 아직 NOT_RUN이며 기존 실패를 덮어쓰지 않았다.
- 후속 main 새 실행 `20261023T053010Z-3239f38a`도 forge build exit0 이후 `C5_ARTIFACT_ABI`로 중단했다. 실제 생성4artifact를 읽기 전용으로 대조한 결과 raw compiler ABI와 deployable artifact ABI는 전체 객체가 일치했으며, Forge parsed metadata만 반환값 없는 함수의 `outputs:[]`를 생략했다. parsed의 function에서 outputs가 없는 경우만 빈 목록으로 정규화했고 raw/artifact 자체는 변경하거나 필드를 제거하지 않는다. 실제4artifact 파서 회귀와 빈목록 생략 허용/실제 반환값 누락 거부 회귀를 추가해 자체 Python **19 PASS**다. Java 컴파일이나 실제 RPC 성공을 뜻하지 않는다.
- main 자체 점검에서 getter bytes32 인자의 `0x` 접두어가 selector 중간에 삽입될 수 있음을 확인했다. 신규 Java getter framing만 prefix 제거·32byte 배수 hex 검증으로 보완하고 bytes32/address/다중word/zeroarg 및 잘못된word 거부 self-test를 작성했다. 이 수정본의 full offline 재실행과 구현 후 독립 검토는 아직 NOT_RUN이다. 실제 제품 금융/계약은 불변이다.
- main 후속 `20261023T053321Z-9fda0f09`는 fresh build/artifact 파서/Java compile을 실제 통과했지만 self-test가 고정 오류로 감싸져 `C5_OFFLINE_SELF_TEST`로 중단했다. 이 당시 첫 실패의 세부 원인은 보존 로그만으로 확정하지 않는다.
- 전체 force build 반복 대신 새 `diagnose <failed-offline-id>` 경로를 추가했다. 이전 fixture/hash는 읽기만 하고 새 diagnostic/classes/log에 현재 whitelist Java를 컴파일한다. diagnostic 결과는 live 실행 전제조건으로 수락하지 않는다. 자체 `053523Z-7794a65b`는 javac exit0이었지만 sandbox의 jar cleanup AccessDenied stderr가 있어 self-test를 미실행으로 차단했다.
- main 정상권한 진단 `053554Z-48484996`는 compile0/test1, 안전 코드 `C5A1_SOURCE_METADATA_KECCAK`를 확보했다. 현재 코드는 이미 `hex(Hash.sha3(bytes)).equals(metadataString)`이므로 byte[]/String 비교 결함이 아니었다. 보존된31개 source를 독립 local `cast keccak`로 읽기 전용 대조했다. CRLF를 포함한 raw27개는 metadata와 달랐고 **CRLF→LF만 변환한 compiler representation은31/31 metadata Keccak와 정확히 일치**했다. 원본 raw snapshot/SHA는 그대로 보존한다.
- Java 검증을 raw SHA256 exact 및 별도 compiler representation(CRLF→LF만)의 metadata Keccak exact로 구분했다. lone CR은 제거하지 않으며 본문 변경도 허용하지 않는다. 정상 CRLF/LF·standalone CR 보존·본문 변조 거부 회귀를 self-test에 추가했다. 이 수정 후 Java 진단과 최종 full offline은 아직 NOT_RUN이다. 새 진단의 원인을 원 `053321` 실패 원인으로 소급 확정하지 않는다. 실제 RPC/Docker/자산 이동은 계속0이다.
- Java self-test에는 JSON-RPC id/result/error/중복key/HTTP/과대body/stall deadline/cancel/한번전송/redaction 및 정확한 integer/서명 포맷 시험을 작성했으나 현재 **컴파일·실행 NOT_RUN**이다.
- actual Anvil binary 경로·이미지 실행·자산 이동·거래·거부 Receipt는 전부 **NOT_RUN**이다. 자원 실제 관측 RAM 약1.084GiB는 사전 기준1.25GiB에 못 미쳐 현재 실제 컨테이너 시작을 허용하지 않는다. 기준을 낮춰 PASS시키지 않는다.
- C5a2 real wall60/risk/Claims insolvency/FIFO, 실제 PG/Spring/시장5초/Synthetic/Worker/Windows/MetaMask/전체감사·재시작은 **NOT_RUN**이다. C5a1 일부 성공을 위 항목 성공으로 승격하지 않는다.

구현 후 독립 검토 및 필수 보완이 끝나고 자원 preflight까지 통과한 경우에만 main이 `run <offline-id>`를 새 identity로 한 번 실행한다. 현재 정책·코드 작성·실행 결과의 구분을 유지하며 이전 실패/사용자 자료는 보존한다.
