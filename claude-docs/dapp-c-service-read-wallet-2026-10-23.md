# C 서비스 연결 1 — 배포 identity·지갑 귀속·고정 블록 조회

## 최종 — 신규 실제 HTTP/PG/Anvil Gate PASS

추가 예외 검토1회 필수없음 후 실행한 `c-service-live/c-live-6f3298d68b9b4fa6/result.private.json`은 **C_READ_WALLET_ACTUAL_HTTP_PG_ANVIL_PASS_NOT_FULL_TMS_OR_BROWSER**, failure null, source `3ba5d0753f7b9c3a429689ccf36d5f9e74fb4dd96606a67bfae67c9293939ac0` 불변이다. 실제 RAM2,989,068,288bytes·disk32,290,643,968bytes로 기존 기준을 충족했다. 이 예외의 검토1회·실제1회는 사용 완료했다. 아래 미실행·승인대기·실행중은 과거 기록이다.

실제 root JWT filter/JPA와 HTTP로 config·지갑 EIP191·계정·positions/{id}·claims/{id}를 검증했다. JWT 오류/없는user/Origin/타인 challenge·포지션·claim/ADMIN 미연결 우회를 차단했고 wallet동시연결은 정확히1성공이었다. PG SCRAM/API role·사용자변경/DDL차단·proof소모 오류주입에서 link+consume rollback·사용자4행/sentinel/링크유일성을 확인했다. pinned block의 free/실제Vault토큰과 header를 별도Driver RPC로 대조했다. 실제값은 free1200/총unpaid900·종료포지션locked0이며 작은house100 시험fixture다. 금융 상태 최종 불변을 확인했다.

실제 fault HTTP 503/redirect/auth는 wire각1·C_RPC_HTTP_STATUS(811/6/4ms), slowbody wire1·C_RPC_TIMEOUT/202ms, redirect-target0이었다. 과거 Windows RPC 원인 해결로 확대하지 않는다. driver/api/pg/anvil4개 정상종료 true, stop오류0, 정지Anvil state463670bytes/SHA `5b206b0ad0f3b537d335c56788720d47dacd8ade9effba1fd255dd41e723583b` 보존(재시작증명 아님)이다. 과거 Exit137 실패와 모든기존자원은 그대로다.

전체TMS context/브라우저/MetaMask/제품Synthetic/transactionSender/전역scanner·finality·completeCoverage는 검증하지 않았다. 새서버는keyless, trial키Driver만, paid0·oldResourceMutationsRequested0. 후속은 C작업metadata/전송·복구/웹이며 이 Gate를 전체거래서비스로 승격하지 않는다. 보호156개 변경/누락0·rootdirty10·실행컨테이너0을 최종 확인했다.

## 승인 수신 및 추가 독립 검토 완료 — 신규 Gate 1회 실행 중

사용자가 capability 표기 최소 보완의 추가 검토1회와 통과 후 신규 Gate1회를 승인했다. `review_c_capability_exception`은 이전 승인 snapshot과 현재 두 파일을 직접 비교해 **발견된 필수 수정 없음**으로 완료했다. 현재34소스, 이전/신규 server fixture181개·driver fixture78개·dependency123개 해시가 동일하며 core/contract/App/Driver/금융/API/키분리/`-parameters`/Exit137 거부 경계 불변이다. 검토자 순수 Python14 PASS(0.043s), Docker/HTTP 미실행이다. 다른 단위의 검토 상한은 변경하지 않는다.

실행 전 RAM3,330,052,096bytes·disk32,367,280,128bytes·실행컨테이너0·고정3이미지 Linux/amd64 일치·소스34개 불일치0 확인 후 `run 20261023T095259Z-6e3ef966`을 **한 번** 실행했다. 신규 identity `c-live-6f3298d68b9b4fa6`이며 과거 실패 fixture는 재사용하지 않는다. 아직 전체 결과 확정 전이다.

## 최신 확정 — 최소 보완 offline prepare 완료 / 추가 검토·실행 예외 승인 대기

`c-service-live-prepare/20261023T095259Z-6e3ef966/result.json`은 source `3ba5d0753f7b9c3a429689ccf36d5f9e74fb4dd96606a67bfae67c9293939ac0`, `PREPARED_NOT_ACTUAL_HTTP_PG_ANVIL`, server/driver self-test0 및 Python14 PASS(0.041s)다. 아래 보완 중/새 prepare NOT_RUN은 과거 시점이다. 실제 Gate는 `c-live-c43c47b33c3c4081`의 BLOCKED를 유지한다. metadata 예외와 실제1회는 사용 완료했으므로 이번 capability 표기 보완에 대한 추가 독립검토1회·통과시 신규 Gate1회 예외 승인 전에는 둘 다 실행하지 않는다. 보호 파일156개 변경/누락0·root dirty10 유지·실행컨테이너0을 재확인했다.

## 최신 — 실제 1회 PG 시작 경계 BLOCKED / CapDrop 표기 최소 보완 중

사용자의 메모리 확보 후 실행 직전 가용 RAM **3,390,267,392bytes**, 디스크 **32,585,322,496bytes**, 실행 컨테이너0으로 기존 1920MiB/2GiB 기준을 충족했다. 승인된 새 실제 실행 `deployment/runtime/c-service-live/c-live-c43c47b33c3c4081/result.private.json`은 source `e1d98f3cc88bbcf6d5de1c093b6a6ffa0771c9296f06360dede77e0ff1aec4f1`/core `34732172…` 불변이며 **BLOCKED / C_SERVICE_PG_INITIALIZATION_SCOPE**다. 이 실제 Gate1회와 앞선 metadata 예외 검토1회는 모두 사용 완료했다. 아래 자원 미달/실행 미사용은 과거 시점의 기록이다.

총괄의 실제 inspect 읽기 전용 확인에서 신규 소유 PG의 `Config.User=''`, `ReadonlyRootfs=false`, `CapDrop=['CAP_NET_RAW']`, `CapAdd=null`, 정확한 고정 PG image ID가 확인됐다. 하네스가 drop 집합을 문자열 `{'NET_RAW'}`와만 비교해 Docker의 `CAP_` 접두어 표기를 다른 권한으로 오인했다. 이 실패를 PostgreSQL/JDBC 또는 제품 RPC 결함으로 단정하지 않는다. Anvil/App/Driver 기동, 사용자 EOA 키 생성, 체인 거래 및 실제 HTTP 검증은 모두 **NOT_RUN**이다.

정리 단계의 소유 PG `docker stop` 제한10초 이후 **Exit137 / OOMKilled=false / not running**으로 관측됐다. 따라서 `ownedContainersStopped=false`, `C_SERVICE_NONZERO_OR_UNSAFE_EXIT`를 그대로 보존하며 정상 종료 성공으로 바꾸지 않는다. 새 네트워크와 PG/state volume 및 실패 증거는 보존했고 재사용하지 않는다.

총괄의 PG 로그 키워드 대조에는 initdb→임시 서버 ready09:42:23.948→임시 서버 fast shutdown24.280/종료24.512→init process complete→최종 PID1 ready24.642가 있다. 최종 정상 shutdown 기록은 없고 Docker Finished09:42:32.046/137이다. 따라서 **PG 초기화 자체 실패로 단정하지 않는다**. guard가 readiness 검사 전에 중단한 것이며 C schema/custom marker/Driver 실행은0이다. cleanup과 초기화 경합은 정확한 stop 신호 시각이 없어 미확정 가설로 남기고,137 정상 종료 기준을 완화하지 않는다.

이번 최소 보완은 PG `CapDrop`이 **정확히 하나**이며 `NET_RAW` 또는 `CAP_NET_RAW`인 두 Docker 동등 표기만 허용하는 것이다. 추가 capability, `CapAdd`, privileged, user/rootfs 및 기존 자원/키분리/컴파일 metadata 정책은 완화하지 않는다. 실제 inspect 모양 positive와 이상값 negative를 순수 Python으로 검증한다. core/API/계약/금융/App/Driver/DB role 및 기존 C5는 변경하지 않는다. 추가 actual 또는 독립 검토는 승인되지 않았으며, 새 offline prepare 검증 후 필요한 좁은 검토·실행 예외를 별도로 판단한다.

보완 후 `python -B -m unittest discover -s deployment -p test_c_service_live_check.py`는 **14 PASS**(0.040s)다. raw list 길이1과 두 표기를 직접 확인하고 null/빈값/문자열/다른 cap/혼합·중복2개/추가 cap을 거부하며 CapAdd·rootfs·privileged·user 경계도 유지한다. 동결 SHA는 runner `de7fbe21c010d634050bc24a25790b3ac6564678b4aabc1462d2767a12282db4`, tests `d77e33a513894f2de49169c447dd2a356e79af15ac18bf3435119d02d409aec5`다. App `3d3c9a04…`와 Driver `3cccb979…`는 불변이다. 이는 Mock inspect/Python 회귀만의 결과이며 새 prepare/추가 독립검토/실제 재실행은 아직 **NOT_RUN**이다.

## 최신 — metadata 예외 검토 완료 / 실제 Gate RAM 사전조건 미달

사용자가 이번 compiler metadata보완만 추가독립검토1회와 조건충족 후 실제Gate1회를 승인했다. `review_c_metadata_exception`은 **발견된 필수 수정 없음**이다. 새prepare source/fixture SHA, server/driver compile·selftest0·Python13PASS/stderr0, 실제javap의 position/claim `MethodParameters u,id` 및 self-test를 직접 대조했다. core24·계약SHA/Driver/키분리 불변이다. 파일수정·테스트재실행·Docker/DB/RPC/HTTP는 검토자가 하지 않았다. 예외1회 사용완료이며 다른cap는 유지한다.

실제기동 전 main읽기전용 자원 확인은 RAM1,522,970,624bytes(약1452MiB)<1920MiB로 약468MiB 부족, 디스크32,564,174,848bytes/실행컨테이너0/고정이미지3ID Linux·amd64 일치였다. 새run/fixture/네트워크/컨테이너를 만들지 않았고 실제 C 서비스Gate는 **NOT_RUN/RESOURCE_BLOCKED**다. 사용자에게 불필요한앱의 정상종료로 여유0.5~0.8GiB 추가확보를 요청했으며 임의종료·기준인하·전역재시작은 하지 않는다.

자원 충족 후 최신소스/새prepared `20261023T085722Z-ee075b27`/`e1d98f3c…` 및 image/owner를 재확인하고 **새 Gate1회**를 실행한다. 별도실행승인 대기는 없고 아직 이 실행은 미사용이다. 실제두 HTTP경로/JWT/EIP191/PG/pinnedRPC는 컴파일·direct-controller결과로 대체하지 않는다. 아래 승인대기는 과거 이력이다.

## 최신 준비 결과 — 최소 보완13 PASS / 추가 독립 검토1회 승인 대기

마지막 재검토2/2의 필수1건을 core변경 없이 컴파일 옵션/compiled인수 self-test만 보완했다. 새 `deployment/runtime/c-service-live-prepare/20261023T085722Z-ee075b27/result.json`은 **PREPARED_NOT_ACTUAL_HTTP_PG_ANVIL/error=null**, sourceIdentity `e1d98f3cc88bbcf6d5de1c093b6a6ffa0771c9296f06360dede77e0ff1aec4f1`이다. server/driver compile0·self-test0·관련stderr0·Python **13 PASS**/source불변을 실제 확인했다. 실제Docker/PG/RPC/HTTP/Spring기동0이며 old prepared/metadata누락 증거는 보존했다.

이 보완만 대상으로 추가 독립검토1회 예외를 사용자에게 요청했고 아직 승인 미확인이다. 승인 전 추가검토/새실제Gate는 금지다. 기존 계획의 Gate1회는 미사용이며, 승인 후 현재source/새fixture hash확인→추가검토1회→필수없음 후 RAM최소1920MiB/디스크2GiB·이미지/owner검사→신규Gate1회다. C4b 예외는 사용완료한 별개 승인으로 이 단위에 승계하지 않는다. 아래 '새컴파일대기'는 이전 시점 기록이다.

## 최신 — 최종 검토 필수1건 / 실행 전 metadata 보완·검토 예외 대기

마지막 구현 재검토2/2는 **필수 수정 있음**으로 종료됐다. 지적은 신규 준비 javac가 `-parameters`를 주지 않아 C controller의 bare `@PathVariable String id` 두 경로(`/positions/{id}`, `/claims/{id}`)가 실제 HTTP에서 인수명을 찾지 못할 수 있다는 것이다. core 자체를 다시 쓰거나 direct-controller 테스트를 HTTP 인수로 승격하지 않는다. 기존 `d2ee65f4…` prepared 결과와 source snapshot을 보존하며 실제 HTTP 실패가 이미 발생했다고 기록하지 않는다. 실제 신규 PG/RPC/Spring/HTTP Gate는 **미실행**이다.

최소 수정은 새 Gate의 실제 서버 compile argument에 production 동등 `-parameters`를 추가하고, 선택 App의 무소켓 self-test에서 두 method의 `@PathVariable` 명시 이름 또는 `Parameter.isNamePresent()/getName()==id`를 실제 compiled class에서 확인하는 것이다. compile argument 회귀도 실제 builder를 검사한다. 금융·C core53 source `34732172…`·기존 C5·old prepared bytecode는 변경하지 않는다. 총괄이 원래 prepared bytecode의 무소켓 재현을 담당하며 결과는 후속으로 기록한다.

최초 설계1회+구현 재검토2회는 소진됐다. 추가 독립 검토 예외1회는 사용자 승인 요청 중이며, 승인 전 추가 검토나 실제 Gate를 실행하지 않는다. 무과금 최소 수정·준비 재검증만 진행한다. 새 하네스명으로 cap를 초기화하거나 기존 PASS의 범위를 바꾸지 않는다.

총괄은 원래 `c-service-live-prepare/20261023T084817Z-3bde2cd5`의 서버 `CCustodyController.class`를 읽기 전용 `javap`로 확인했다. class SHA `6b1769623c2480cad27f5bfdfa2aa3c1d3e714f489bb1e91a0e45d66f04a439e`, javap exit0, **PathVariableAnnotationsPresent=true / MethodParametersMetadataPresent=false**, HTTP0이다. 실제 HTTP 실패 재현이 아니라, 해당 compiled 산출물의 인수명 metadata 부재를 직접 확인한 결과다.

보완 후보 동결: App SHA `3d3c9a04f96dbdcb431f77d189df97dd823bf4010321e47f6fe8eeb7816d2ec2`, runner SHA `99405272f69c347fce231d14afd3ce9a31b1da42805b10675cf8f0ab6b81cdd8`, Python tests SHA `1fbe83bb8b7d4c6b07fd5fefacb9d370b34e6c235cf0c2865ac8778d3b6bf409`. 실제 prepare가 사용하는 `javac_args` builder에 server `-parameters`를1개 추가했고 그 builder를 직접 확인하는 회귀를 넣었다. **순수 Python13PASS**/Java·Docker·RPC0이다. Driver와 core53/기존 C5/원래 prepared는 불변이며 새 준비 컴파일 결과는 아직 대기다.

## 최신 실제 준비 — 분리 컴파일/self-test/준비12 PASS / 마지막 독립 검토 중

`deployment/runtime/c-service-live-prepare/20261023T084817Z-3bde2cd5/result.json`은 **PREPARED_NOT_ACTUAL_HTTP_PG_ANVIL/error=null**, sourceIdentity `d2ee65f444b39588409ec83f1d3d3af60e7be089e7cb76e2586331f5e8667731`이다. 실제 캐시 Lombok으로 root User를 재작성하지 않고 컴파일했으며 서버/Driver 각각 compile0·self-test0·관련stderr0, 순수Python **12 PASS**/source불변을 확인했다. 서버classpath에서 Driver/C5/JUnit·키생성클래스 분리를 실제 self-test로 확인했다. 이 준비에는 실제 Docker/DB/RPC/HTTP/Spring기동이 없다.

소스는 동결해 `review_c_read_wallet_design`의 **마지막 구현 재검토2/2**에 전달했다. core53PASS `34732172…` 및 기존 C5 하네스는 변경하지 않는다. 최종 검토 및 실행 직전 RAM최소1,920MiB/디스크2GiB·현재source/compiled/image/격리 사전검사를 통과한 경우에만 새 실제Gate1회를 진행한다. 필수 지적이 남으면 이 한도를 새 이름으로 초기화해 실행하지 않는다.

## 최신 검토 완료 — 구현 후보53 PASS / 실제 서비스 Gate 준비

`review_c_read_wallet_design` 구현 후 재검토1/2는 **발견된 필수 수정 없음**이다. 실제 소스24개/class67개/의존JAR147개 SHA, `20261023T075906Z-9a0a410e`/`34732172658d779342ccf5e8b8cfd78d135053d5238be6c65905a235f9dbb96e` 53PASS·compile/test0·stderr0·최초50/2실패 보존을 직접 대조했다. 검토자는 실제 테스트/서비스/Docker/RPC를 실행하지 않았다. 최초설계1회+이번재검토1회 사용/수정후재검토1회 남음이다.

후속은 기존 성공 환경 재시작이 아닌 **신규 실제C wallet PG·production RPC·SpringHTTP/JWTfilter Gate**의 최소 실행 설계다. 현재 이 실환경 항목은 NOT_RUN이며 Mock/H2 PASS를 인수 완료로 승격하지 않는다. C자산정산/Sender·새Report·재가격·웹은 여전히 후속이다.

## 최신 — 오프라인53 PASS / 구현 독립 재검토 중

후속: 총괄이 구현 후 첫 독립 재검토1/2의 최종 **발견된 필수 수정 없음**을 확인했다. C read/wallet core는 위 source로 동결하며 남은 재검토1회는 새 하네스 이름으로 초기화하지 않는다. 아래 §11 계획 작성 당시에는 새 Gate 코드도 없었으나, 이후 총괄 지시로 `CCustodyLiveApplication/CCustodyLiveDriver`와 신규 Python 준비/lifecycle·최소 회귀를 작성했다. 아직 실제 준비 컴파일·신규 컨테이너/PG/RPC/Spring 실행은 하지 않았다. core53·C5 원본은 변경하지 않았다.

`deployment/runtime/c-service-read/20261023T075906Z-9a0a410e`/source `34732172658d779342ccf5e8b8cfd78d135053d5238be6c65905a235f9dbb96e`: **53PASS/FAIL·SKIP·ABORT0**, compile/test exit0, compile/test stderr0, source/dependency 불변이다. 고정7개 테스트 클래스만 직접 JUnit으로 실행했고 기존 app classes·Gradle daemon·실제 RPC·DB 서비스·유료 호출은 없다. SQL 검증은 새 H2 in-memory fixture이며 PostgreSQL PASS로 기록하지 않는다. HTTP transport는 no-socket Fake Call/Interceptor 검증이지 실제 socket 전송/서버 timeout 검증이 아니다.

최초50PASS2FAIL의 원본은 보존했고, 발급 전 millisecond 정규화·지원하지 않는 직접 proof 정밀도 거부 및 JSON scope 명시 속성으로 보완했다. 원래 precision positive·제품형 JSON 직렬화 회귀는 유지했다. 초기 설계 검토는 필수 수정 없음으로 완료했고, 구현 후 **첫 독립 재검토1/2 진행 중**이다. 현재 소스는 동결했다. 실제 신규 PostgreSQL wallet SQL·RPC pinned read·Spring HTTP/JWT·Chrome/MetaMask Gate는 **NOT_RUN**이며, 재검토 전 완료로 승격하지 않는다.

아래 ‘설계 대기/실행 준비/수정 전’은 보존된 당시 이력이다. 최신 상태는 이 절을 우선한다.

최초 문서 작성 2026-10-23 당시 상태: **설계 초안 / 구현·테스트·실환경 NOT_RUN**. C5a2 실제 `canvil-9c60eadeb80647f8` PASS와 C4b 실제 `cpg-3dfbda7f33414a95` PG10PASS 이후의 첫 연결 단위다. 이 문서는 해당 결과를 Spring/API/브라우저 성공으로 승격하지 않는다. 정책은 ADR C-001 및 사용자 C 구현 승인 원문을 유지한다.

## 최신 — 설계 검토 완료·첫 연결 구현 후보 / 최초 무과금 실행 준비

후속 최초 실행 `deployment/runtime/c-service-read/20261023T075601Z-8f60115f`/source `5fb53cf6ca9bfa219584dc2e02f8b90cb30ebbcb64b17cfd2d8bd8215288b4ab`: compile0/test1/source불변, **52개 중50PASS/2FAIL/SKIP·ABORT0**, compile/test stderr0이다. 실패 원본을 보존했다. (1) `.123456789Z` 시계의 challenge가 H2 TIMESTAMP 왕복으로 `.123457Z`가 돼 원래 시각보다211ns 미래로 보였고, `CWalletProofService.link`의 `C_WALLET_EXPIRED_OR_USED`에서 실제 차단됐다. canonical 원문 재생성의 잠재 위험도 있으나 이 실행의 최초 실패는 그 이전 issued-time 검사다. 실제 PostgreSQL 장애나 과거 사용자 실행 사고로 기록하지 않는다. (2) 제품형 Jackson/JavaTime 직렬화에서 scope plain 메서드들이 JSON에 빠지는 것을 실제 재현했다. 직접 getter 검사가 통과하더라도 웹에 검증 범위가 전달된 증거가 아니었다.

최소 수정은 사용자 서명 **이전 생성 경계에서만** millisecond precision을 고정하고 저장소도 그 정밀도 외 새 proof 입력을 거부하는 것이다. 이미 발급/서명된 원문을 다시 쓰지 않는다. TTL은 고정된 issuedAt+120초이며 DB 왕복 후 message·expiry 검증을 유지했다. 응답 scope4개 메서드는 `@JsonProperty`를 붙여 실제 JSON에 내보낸다. 원래 실패 회귀를 약화하지 않고 unsupported precision의 직접 저장 거부1개를 추가했다. 수정 후 재실행과 구현 독립 재검토는 아직 대기다.

총괄이 `review_c_read_wallet_design`의 최초 독립 설계 검토 **발견된 필수 수정 없음**을 확인하고 구현을 지시했다. 최초 설계1회 사용, 구현 후 재검토 최대2회는 아직 미사용이다. 생산 코드 후보는 runtime의 `CCustodyIdentity/CReadAbi/CReadRpc/CCustodyRpcClient/CCustodyRpcReader/CCustodyRuntimeConfig/CCustodyFailure`, auth의 `CWalletLinkStore/CWalletProofService`, API의 `CCustodyController` 및 명시 설치 전용 `c-wallet-schema.sql`이다. 기존 금융·JWT·C5 하네스는 변경하지 않았다.

OkHttp4.12.0 실제 캐시 SHA `b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0`의 바이트코드를 읽어 `retryOnConnectionFailure(false)`만으로 503 follow-up을 막았다고 가정할 수 없음을 확인했다. 전용 keyless client는 비200 응답을 network interceptor에서 close/IOException으로 종료해 follow-up 진입을 막고, retry/redirect/auth/proxy 차단·per-call 시도 계수·async 전체3초/본문 상한/cancel/늦은 응답 close를 함께 구현했다. 테스트는 현재 no-socket Mock이며 실제 HTTP 전송 차단/지연은 신규 Linux Gate에서 별도로 확인해야 한다. JDK 전역 설정·`--add-opens`·하네스 키/가스/전송 코드 재사용은 없다.

고정 조회는 snapshot 전체15초/최대64 RPC와 개별 최대3초를 적용한다. EIP-1898 blockHash/requireCanonical 고정 읽기 및 같은 provider의 최종 header·genesis 재확인이다. 포지션의 역사 collateral과 terminal lock0을 분리하고, claim은 명시 단일 조회뿐이다. `sameProviderHeaderRechecked`를 global canonical/finality/full audit로 확대하지 않는다.

총괄 자체 소스 점검에서 (1) challenge의 nanosecond Instant가 SQL timestamp 정밀도로 왕복하면 원문 검증과 충돌할 수 있는 위험, (2) scope를 plain record 메서드로만 두면 실제 Jackson 응답에서 누락될 수 있는 위험을 확인했다. 아직 과거 실환경 장애로 확인된 사실은 아니다. 실제 원본 precision·제품형 JSON 직렬화 positive 회귀를 **수정 전** 먼저 실행해 실패 여부를 보존한 뒤 최소 수정/재검증한다. 최초 Java compile/JUnit/실제 PG/RPC/서비스 실행은 아직 NOT_RUN이다.

## 1. 이 단위의 목표와 제외 범위

신규 C 전용 Spring opt-in 경로에서 JWT 사용자가 자기 EOA를 연결하고, 새 C 배포의 실제 자산·부채·포지션 값을 **같은 RPC의 한 고정 블록**에서 조회한다. DB는 사용자↔지갑 귀속만 저장하며 토큰/free/locked/house를 만들거나 예약하지 않는다.

`app.c-futures.enabled` 기본값은 OFF다. 기존 `app.futures.*`나 B-v1/v2 활성화에 의존하지 않으며 `/api/c-futures` namespace와 C 전용 설정·table만 추가한다. 이 단위에는 가격 Report 발급·서명, 거래 Sender/Worker·SIGNED 저장·재가격, 브라우저 화면, 실제 거래 수집/scanner/reorg 및 전체 감사 구현을 넣지 않는다. approve/deposit/withdraw도 이 API가 사용자 대신 실행하지 않는다.

## 2. 고정 설정과 실제 배포 검증

C 전용 immutable 설정에 chainId, genesisHash, token/controller/rules/Vault 주소 및 각 정확한 runtime codeHash, relayer/Oracle/pauseAdmin, symbolHash/policyHash, 허용 웹 Origin, 단일 RPC URI를 둔다. 비밀키는 설정하지 않는다. codeHash는 실제 신규 배포 manifest의 값이며 템플릿 bytecode나 기존 B codeHash로 대체하지 않는다.

검증은 실제 `eth_chainId`, genesis header, 선택 block의 `eth_getCode` 및 getter를 대조한다. token 고정 totalSupply20m·decimals18, controller의 rules/Vault/relayer/admin, Vault asset/controller, rules executor=controller·priceSigner·symbol/policy를 모두 확인한다. C 사용자 domain `TokenizedMarketCFuturesUserIntent`/`1`/chainId/controller와 가격 domain `TokenizedMarketFutures`/`1`/chainId/rules를 실제 IERC-5267 `eip712Domain()` 응답과 대조한다. 임의 signer·contract/version fallback을 하지 않는다.

설정 불완전/배포 불일치면 C bean 활성화를 실패시키거나 C 요청을 fail-closed로 거절하며 legacy 기능을 C로 자동 전환하지 않는다. 기존 `FuturesChainIdentity`는 LEGACY/1/2와 B 구조만 지원하므로 C를 version2로 위장해 넣지 않는다.

## 3. 단일 provider·pinned-block 읽기 계약

각 snapshot은 한 RPC에서 header H를 선택하고 모든 `eth_call`과 `eth_getCode`에 H의 blockHash를 명시한 EIP-1898 식별자를 사용한다. endpoint가 hash 지정 읽기를 지원하지 않으면 최신값/다른 provider/다른 blockNumber로 조용히 fallback하지 않는다. 조회를 마친 뒤 같은 provider의 H.number header를 다시 읽어 H.hash와 같은지 확인한다. 중간 RPC 실패, 불일치, 무효 hex/ABI/누락 응답이면 snapshot 전체를 반환하지 않는다.

응답에 blockNumber/hash/parentHash/timestamp, 서버 관측시각, `sameProviderHeaderRechecked=true`, `completeCoverage=false`와 명시 scope를 붙인다. 이는 같은 provider가 준 header/값의 일관성 검사이며 독립 노드 합의, 전역 canonical 진실, finality, 전체 스캔 coverage 또는 가격 진실성의 증명이 아니다. 재확인 이후 reorg 가능성도 남는다. `canonicalVerified=true`나 full-audit MATCH를 만들지 않는다.

조회 항목은 linked owner의 token wallet/allowance(owner,Vault), Vault free/locked, totalFree/totalLocked/house/totalUnpaid/recognized/unassigned/실제 Vault token balance, controller nonce다. positionId 조회는 controller.positionOwner가 linked owner와 같은지 먼저 확인하고 rules position·Vault positionLock을 같은 H에서 읽는다. claimId 조회도 같은 H의 claim.owner 일치를 확인한다. 사용자가 보낸 wallet 주소로 다른 사용자 조회를 우회하지 못한다.

최초 own-claim API는 **명시한 단일 claimId** 조회로 제한한다. 전체 claim queue를 무제한 순회하거나 사용자 소유 claim 목록이 완전하다고 주장하지 않는다. claim.original/paid/remaining, 현금과 미지급을 분리해 반환하며 unpaid를 free/인출가능액에 더하지 않는다. API 숫자는 모두 정확한 정수 문자열이다. signed PnL 및 uint256을 JavaScript Number 전제로 변환하지 않는다.

## 4. 읽기 전용 RPC 전송 경계

전용 keyless client는 단일 설정 URI, no proxy/no redirect, 자동 재시도0, 요청당 전체 deadline3초 및 bounded response를 사용한다. 허용 method는 `eth_chainId`, `eth_getBlockByNumber`, `eth_getCode`, `eth_call`뿐이며 사용자에게 method/target/calldata를 임의 입력시키지 않는다. 호출 대상은 고정 네 계약과 linked owner의 code 조회만 허용하고 ABI selector·argument 길이를 서버가 생성한다. private key·서명 송신·`eth_send*`·시계 조작·storage override는 없다.

기존 C 하네스의 transport 안전 원칙을 재사용하되 Java21 JDK 내부 자동 connect retry를 막았다는 사실을 supplier1회 호출로 대신하지 않는다. 고정 client 구현에서 실효 retry0을 강제·검증할 수 없으면 RPC 전에 차단한다. JVM 전체 proxy/IPv4/IPv6/네트워크 설정은 변경하지 않는다.

strict JSON-RPC version/id/result-or-error·중복 key/후행 데이터/UTF-8·응답 cap·hex/ABI tuple 크기를 검사한다. snapshot은 요청 횟수 상한과 전체 deadline도 둬 같은 3초 실패를 많은 읽기로 증폭하지 않는다. 구체 상한은 실제 고정 selector 수를 세어 구현·테스트에서 고정하고 정상 identity 검증을 생략해 맞추지 않는다. 오류는 안전한 단계 코드·correlation ID·경과시간만 남기며 URI 비밀부분/SQL문/서명 원문/JWT를 출력하지 않는다.

## 5. JWT와 C 지갑 소유 증명

기존 `AuthenticatedUser`/JWT filter를 그대로 사용하며 body의 userId를 신뢰하지 않는다. USER·ADMIN 모두 최초 범위에서는 본인 linked wallet만 조회한다. ADMIN 타인/전체 자산 조회는 이 단위에 추가하지 않는다.

C challenge message는 거래 승인과 명확히 구분하고 Origin·JWT userId·정규화 owner·chainId·controller·deployment identity·32byte 무작위 nonce·challengeId·expiresAt을 canonical UTF-8로 고정한다. 기한은120초이고 서버가 생성한 원문에 대한 EIP-191 personal_sign EOA 회복만 받는다. 사용자 개인키/시드/지갑 비밀번호는 받거나 저장하지 않는다. EOA code 확인은 관측 H 시점의 지원 범위 검사이며 영구 EOA 보장이 아니다. 서명 검증과 별개로 실제 거래 시 계약의 owner/nonce 검증이 필요하다.

C 전용 schema에 wallet_links/challenges를 additive 설치한다. 기존 C typed projection schema와 기존 futures_wallet_* 및 금융 테이블은 변경하지 않는다. `users` row를 `FOR UPDATE`로 잠그고 challenge 귀속/expiry/consumed를 확인하며 link 삽입과 challenge 소비를 한 DB transaction으로 확정한다. deployment별 `(user_id)`와 `(wallet)` unique로 동시 중복 연결을 차단한다. challenge 재발급은 해당 사용자·배포의 미사용 challenge만 무효화한다. 타 사용자 challenge, B challenge, 다른 Origin/chain/controller의 challenge는 거부한다.

RPC·서명 회복 작업을 장시간 DB lock 안에서 수행하지 않는다. 사전 읽은 challenge 원문/hash와 scope를 commit 시 row lock 아래 재대조한다. 실패는 link와 소비를 함께 rollback하며 commit 응답 불확실은 새 challenge 자동 생성 대신 같은 challenge/link 상태 읽기로 확인한다. 최초 범위에는 unlink/relink/자동 지갑 교체를 넣지 않는다. Origin 비교는 정확히 설정값과 일치해야 하며 substring/wildcard는 쓰지 않는다.

## 6. 필요한 소스와 재사용 출처

신규 후보는 `custody/runtime/CCustodyIdentity`, `CCustodyRpcReader`, `CCustodyRuntimeConfig`, `custody/auth/CWalletProofService`, `CWalletLinkStore`, `custody/api/CCustodyController`, C 전용 wallet SQL 및 관련 테스트다. 실제 클래스 분리는 작은 책임을 유지하는 범위에서 조정할 수 있다.

읽기 전용으로 참조한 B worktree 원본:

| 경로(backend/src/main/java/com/pricetrack/exchange/futures/authorization/) | SHA-256 | 사용할 hunk / 제외 |
|---|---|---|
| AuthorizedFuturesService.java | `98fd2294e08c07744ef3dfe6a6f0ae7b0e7abedf315c11c9ea109610a23b8742` | challenge/link의 Origin·EOA·기한 흐름만 참조; 거래/ledger/signing 제외 |
| AuthorizedFuturesStore.java | `14f1abea0cf6f790fbf9b0612a4ecc998338ac893f77e64974690f9cd3280253` | user row lock·일회 proof·wallet unique 패턴만 참조; FuturesLedger·담보예약·B table 제외 |
| UserIntentCodec.java | `551bab6c4d79342129304257017ec56133d8a245257d5119fc6fb846aa910cc3` | EIP-191 회복 개념만 참조; B domain/accountRef/승인 type 제외 |

C의 `CEip712Verifier.recover`는 low-S/65byte/v27·28 검증을 갖는다. personal-sign message hash를 전달하는 작은 C proof adapter로 재사용 가능하지만 EIP-712 거래 승인과 혼동하지 않도록 별도 테스트한다. 파일 전체나 다른 worktree를 병합하지 않고 가져온 실제 hunk·새 source hash를 구현 기록에 남긴다.

## 7. API와 실행 흐름

- `GET /api/c-futures/config`: 인증된 사용자에게 비밀 없는 고정 C identity/scope만 반환.
- `POST /wallet/challenges`, `POST /wallet/link`, `GET /wallet`: JWT 본인만, Origin 검증, C 별도 proof/store.
- `GET /account`: linked owner의 pinned snapshot.
- `GET /positions/{bytes32}`, `GET /claims/{uint256}`: 같은 H의 실제 owner 확인 후 본인 단일 기록 조회.

`JWT → C scope/Origin → C link lookup → single-provider identity/H 확정 → pinned code/call → owner 및 값 decode → H 재확인 → 정확한 DTO`다. 금융 write API, 임의 RPC proxy endpoint 및 관리자 asset mutation endpoint는 없다.

## 8. 최소 테스트와 정직한 판정

1. 단위/Mock transport: flag OFF에서 bean/RPC/SQL0, 네 계약·chain/genesis/hash/role/domain 불일치, pinned hash 누락·혼합 block·마지막 header 변경·반쪽 응답·잘못된 JSON/ABI 거부.
2. RPC: method/target whitelist, proxy·redirect·자동 retry0, 3초 deadline·bounded body·안전 단계 오류, 동일값의 잘못된 provider/block 혼합 방지. Mock request 성공을 실제 RPC 성공으로 표시하지 않는다.
3. proof/store: 정상 EIP-191, B/C/origin/owner/controller/chain/nonce/원문 변조·타인 challenge·expiry/replay·잘못된 ECDSA 거부; 사용자별/지갑별 concurrent link·재발급 경합·소비와 link rollback/commit-unknown.
4. API: JWT 없음401, 본인만200, 타인 position/claim 거부, ADMIN도 본인 범위, uint256/decimal 문자열 정확성, unpaid를 free에 미포함, no full MATCH.
5. 신규 실제 PG: 새 identity/owner/SCRAM/schema/sentinel, C wallet UNIQUE/FK/row lock/rollback를 실제 검증한다. 기존 cpg fixture를 재시작하지 않는다. H2·Mock SQL 결과와 별도 기록한다.
6. 신규 실제 Anvil+서비스: 새 C배포와 시험 EOA의 실제 지갑 연결 및 pinned 조회를 확인한다. 실제 토큰 이동 시험이 필요하면 명시 시험 EOA로만 별도 실행하며 MetaMask로 기록하지 않는다. 기존 Gate fixture를 재사용하지 않는다. PG/Anvil 무거운 실행은 자원을 확인해 순차 또는 승인된 단일 통합 Gate 안에서만 수행한다.

## 9. 완료·실패 기준과 다음 의존성

구현·관련 테스트·독립 검토를 통과하고 실제 실행 결과를 유형별로 기록해야 단위 완료다. Windows Gradle socket 실패는 환경 BLOCKED로 분리하고 직접 JUnit 결과를 정상 Gradle 전체 PASS로 기록하지 않는다. 실환경이 미실행이면 코드/Mock 완료와 PG/Anvil NOT_RUN을 분리한다.

필수 권한·서명·identity·snapshot 무결성 실패는 데이터나 codeHash를 바꿔 맞추지 않고 fail-closed로 원인을 수정한다. 실패 fixture와 로그를 보존하고 무제한 신규 Gate/포트 재시도를 하지 않는다. C5a2 완료도 read-service 인수나 실제 사용자 MetaMask를 대신하지 않는다.

이 단위 통과 후 C 전용 작업 metadata·새 Report·raw 선저장/동일 raw 복구·SIGNED 이전 최대1 revision을 연결하고, 그 결과로 C웹 approve→deposit→OPEN→TOP_UP→CLOSE→free withdraw를 인수한다. DB 현금 예약은 만들지 않는다. 실제 사용자 서명이 필요한 지점에서만 사용자에게 요청한다. Claims/실행중 서버 종료/Oracle 중단 locked는 별도 인수다.

## 10. 최초 설계 문서 작성 당시의 수행 기록 — 이력 보존

- 이번 문서 작성 전 읽기 전용 소스·정책·기존 지침 대조만 수행했다.
- 코드/SQL/설정 수정0, 테스트·Docker/RPC·유료 실행0, 기존 DB/체인/원본/UI/AI/B worktree 변경0.
- 독립 설계 검토: 대기. 구현·검증: NOT_RUN.

## 11. 다음 실제 연결 Gate — 최소 실행 계획, 아직 미실행

### 11.1 검증할 것과 제외할 것

새 소유 Linux 내부 네트워크에서 **실제 PostgreSQL + Anvil + 선택적 Spring C API**를 연결한다. root의 실제 `JwtTokenProvider/JwtAuthenticationFilter/SecurityConfig/User/UserRepository`와 검토된 C read/wallet core를 사용한다. 기존 `ExchangeApplication`의 전체 component scan, Google/AI/legacy 자산 서비스·스케줄러는 기동하지 않는다. 결과는 `SELECTED_PRODUCTION_C_SPRING_CONTEXT`로 명시한다. 실제 HTTP/JWT 필터·PG JPA 조회를 확인하더라도 비밀번호 로그인/AuthController·브라우저·MetaMask·제품 거래 Worker 인수는 아니다.

새 Boot 진입점은 `@Import`로 C config/controller와 실제 JWT/security만 선택하고 `@EntityScan(User.class)`, `@EnableJpaRepositories(UserRepository.class)`를 사용한다. root `GlobalExceptionHandler`는 거래·Google 등 전체 모듈에 연결되므로 가져오지 않고, 테스트 진입점 내부의 **InvalidTokenException만 처리하는 좁은 advice**를 둔다. 이 차이를 결과에 명시한다. root security의 unauthenticated entrypoint·실제 JWT 검증·PG 사용자 lookup은 교체하지 않는다. JWT는 새 시험 secret으로 실제 provider가 발급한다. 이 secret은 서버 인증용이며 사용자 EOA 개인키와 다르다.

### 11.2 필요한 신규 파일과 재사용

기존 C5/C4b 하네스를 변경하거나 전체 시나리오를 반복하지 않는다. 필요한 추가는 bounded lifecycle Python 1개, 선택 Boot 진입점 Java 1개, bootstrap/HTTP 시험 Driver Java 1개와 최소 준비 회귀에 한정한다. 구체 후보명은 `deployment/c-service-live-check.py`, `CCustodyLiveApplication.java`, `CCustodyLiveDriver.java`다. 기존 `c_gate_network.py`의 subnet 선택, C5의 artifact/source/code 검증·단일 raw 전송/Receipt 검증·normal-stop/state 보존 primitive는 원본 hash를 확인하고 재사용한다. 소진한 기존 검토 단위의 동작을 수정하지 않는다.

컴파일은 cached third-party jar만 사용한다. root User는 Lombok getter/setter를 쓰므로 **캐시된 정확한 Lombok processor**를 명시해 root 원본을 새 isolated out에 컴파일한다. 이를 피하려고 User를 재작성하거나 기존 app class를 가져오지 않는다. 서버 out에는 C production·선택 JWT/JPA/security·Boot 진입점만 넣는다. **C5 helper·시험 Driver·JUnit·EOA 키 생성 코드는 서버 classpath에서 제외**한다. Driver out만 필요한 C5 primitive·ABI/EIP712/수학을 포함한다. 53PASS의 core identity, 실제 Gate source/compiled/contract artifact/image identity는 각각 기록한다.

### 11.3 자원·소유권·네트워크

- 새 run/owner label의 internal network1개와 PG/Anvil 신규 named volume, 소유 컨테이너만 생성한다. `-p`, host network, 기존 mount/DB/체인·예전 Gate 재시작, pull/외부 egress는 없다.
- 총괄이 확정한 동시 최대는 PG256MiB + Anvil256MiB + Spring512MiB + **Driver384MiB**다. JVM은 서버/Driver의2개이며 서버 heap256MiB/meta128MiB, Driver는 기존 실제 PASS 구성의 heap192MiB/meta128MiB를 유지한다. 최초 Driver256MiB 후보는 native/JIT/stack 여유가 촉박해 실행 전에 상향했다. 실제 startup에 부족하면 상한을 없애지 않고 실패를 기록한다.
- 새 Gate 호스트 RAM 최소는 **1,920MiB**(컨테이너 합1,408MiB+호스트 여유512MiB)로 확정했다. 최초1,792MiB는 실행 전 후보 이력이며 현재 기준이 아니다. 기존 C5a1의1,280MiB 기준을 내리지 않는다. 실행 직전 실제 RAM·디스크·실행 컨테이너·중복 무거운 작업을 확인한다. source/artifact 준비 복사를 포함한 디스크2GiB 최소도 유지한다. 부족하면 앱을 종료하지 않고 필요한 사용자 조치를 보고한다.
- startup/각 명령/전체 Gate에 별도 deadline을 두고 PG→bootstrap→선택 Spring→HTTP 검증 순으로 진행한다. 최초 준비 실패 시 그 run에서 더 진행하지 않는다. 초기 전체 상한 후보300초이며 실제60초 시험은 이미 별도 통과했으므로 이 Gate에서 재실행하지 않는다.
- 종료는 소유 Driver/Spring을 먼저 정상 종료한 후 PG/Anvil 정상 정지·exit/OOM 상태·Anvil state 파일을 확인한다. network/volume/container/로그/실패 증거는 삭제하지 않는다. 소유권 불일치나 정상 종료 미확인이 있으면 PASS를 금지한다.

### 11.4 키·manifest·DB 역할 분리

Driver는 새 시험 treasury/admin/relayer/Oracle/수령 EOA 키를 자기 private 경로에 먼저 보존하고 신규 체인 시험 가스만 공급한다. 실제 사용자/MetaMask 키는 없다. 서버 mount에는 **user EOA key/raw/Driver private fixture가 절대 포함되지 않는다**. 서버는 C public manifest와 새 JWT secret·전용 JDBC credential만 읽는다. 서버에 relayer/Oracle private key도 필요 없다. public source/artifact/config와 private 키·JWT/서명·JDBC 파일은 별도로 저장하며 Docker command와 공개 로그에는 값이 아닌 파일 경로만 나타낸다.

PG는 새 database/owner/fixture marker/SCRAM을 실제 JDBC로 확인한 후 새 users 최소 테이블과 `c-wallet-schema.sql`만 설치한다. 원본 보존 sentinel과 별도 시험 사용자 USER1·USER2·ADMIN을 생성한다. 기존 C projection schema/금융 테이블이나 예전 PG는 쓰지 않는다. Spring의 SQL 자동 init과 Hibernate DDL 생성은 OFF이며 실제 `UserRepository.findById`가 이 새 PG를 읽게 한다.

가능하면 서버용 별도 DB role을 사용해 C identity 테이블의 필요한 SELECT/INSERT/consumed·proof UPDATE와 users 조회만 허용한다. **users SELECT FOR UPDATE에는 PostgreSQL UPDATE 권한도 필요**하므로 이 사실을 숨기지 않는다. `UPDATE(id)` 등의 필요한 최소 grant를 선택하고, 새 fixture의 user mutation 거부 trigger로 실제 user 변경을 막는 방법을 검토한다. 이 least-privilege 준비가 실제 동작을 보장하지 않으면 실행 전에 수정·검토하며 owner credential을 조용히 서버에 넘기지 않는다. 어떤 경우에도 사용 가능한 금융 자산 테이블은 만들지 않는다.

### 11.5 bootstrap과 실제 pinned 조회

Driver는 검증된 C artifact/compiler source를 읽기 전용 소비하되 새 체인에 새 token/controller/rules/Vault를 배포한다. 기존 C5 run 전체를 재실행하지 않고 필요한 배포·getter/codeHash/domain 및 최소 account/position/claim fixture만 준비한다. 실제 배포 주소·codeHash·genesis·chainId를 기준으로 서버 C config를 새로 고정한다.

최소 positive는 두 시험 수령 EOA의 실제 approve/deposit 및 본인 계정 조회다. 본인 terminal position/claim positive가 필요하면 새 배포 안에서 명시된 시험 조건의 OPEN/CLOSE로 기록을 만든다. 이 bootstrap은 독립 시험 signer/통제 Oracle 데이터이며 제품 Sender/Worker·실제 사용자 거래로 기록하지 않는다. Claims queue 전체·60초·동일 과거 C5 시나리오를 다시 실행하지 않는다. fixture의 자본 배분 변형이나 작은 house가 있으면 manifest에 명시하고 기본 H10m/U2m/R8m 인수로 대체하지 않는다.

실제 서버에서 config/account/position/단일 claim의 EIP-1898 pinned read가 성공하고, Driver의 같은 blockHash 직접 읽기와 반환 수치/owner/역사 collateral·terminal lock/claim paid·remaining을 대조한다. same-provider header 재확인과 `completeCoverage=false/globalCanonicalVerified=false`가 실제 HTTP JSON에 포함되는지 확인한다. full scanner·projection/reorg 전체 감사나 지급능력 보증을 주장하지 않는다.

### 11.6 실제 HTTP 인증·원자 SQL·전송 실패

1. JWT 없음/깨진 토큰/다른 secret/미존재 사용자 토큰의 실제 HTTP 거부를 확인한다. root provider/filter/security와 실제 PG UserRepository가 동작하며 임의 body userId는 쓰지 않는다.
2. 실제 사용자 JWT로 challenge→Driver의 독립 EIP-191 서명→link를 완료한다. 두 사용자의 동일 wallet 동시 link는 최대1개만 확정되고 상대 proof가 임의 소비되지 않는지 실제 PG에서 확인한다. 이후 남은 사용자는 자기 다른 시험 EOA에 연결해 자기 account를 조회한다. ADMIN도 타인 record 조회 우회가 없어야 한다.
3. 잘못된 Origin·다른 사용자 challenge·재사용·변조 signature·미연결 사용자 접근을 거부한다. 과거 B/C proof 혼용은 테스트 fixture 범위에서 안전하게 확인한다. 사용자별 link/원본 message/consumed/unique/FK와 보존 sentinel을 실제 SQL로 대조한다.
4. 신규 fixture에만 제한한 실패 주입으로 link INSERT 이후 proof consume 실패를 만들고 두 기록 모두 rollback됐는지 확인한다. 고정 source의 store를 바꾸지 않으며 실패 주입/해제와 대상 행을 기록한다. 원래 사용자 데이터·실패 기록을 삭제하지 않는다. commit-unknown이 확인되면 현재 상태 조회 없이 재시도하지 않는다.
5. Driver의 **Linux loopback 시험 HTTP 서버 한 개**에서 실제 client 요청을 관측한다. 503+Retry-After:0, redirect, 인증 응답, 지연 본문을 각각 고정 path로 주고 wire 요청 수1/자동 follow-up0·전체 deadline/cancel/안전 오류를 확인한다. 외부 네트워크·Windows listener·호스트 포트는 쓰지 않는다. 임의 포트를 반복 변경하지 않는다. Fake Call 시험과 실제 HTTP 시험을 별도 결과로 남긴다.

### 11.7 실행·검토 Gate와 판정

실행 전 source/compiled/runtime image·새 owner manifest·실제 RAM·DB/schema checksum·mount 분리 및 private 파일 비노출을 검토한다. 신규 하네스는 준비 회귀와 독립 검토를 통과한 뒤 **새 실제 Gate1회**만 실행한다. C core 결함을 발견하면 현재 C read/wallet 단위의 **남은 구현 재검토1회** 내에서 수정·재검증하며 새 하네스명으로 상한을 초기화하지 않는다.

성공은 `C_READ_WALLET_ACTUAL_HTTP_PG_ANVIL_PASS_NOT_FULL_TMS_OR_BROWSER` 같은 제한 판정으로 남긴다. 실제 조회/소유 proof/원자 SQL/정지·증거 보존 중 필수 실패가 있으면 해당 경계에서 BLOCKED/FAIL이며 나머지 NOT_RUN을 구분한다. C 거래 Sender·가격 Report/Worker·DB projection scanner·브라우저/MetaMask·실행중 서버 종료는 후속 별도 인수다. 이 절 작성 시에는 계획만 있으며 신규 Docker/PG/RPC/서비스 실행0이다.

### 11.8 Gate 구현 후보의 구체 실행 경계

선택 App은 실제 root JWT/provider/filter/security 및 Lombok로 컴파일한 User/JPA Repository만 import한다. 좁은 InvalidToken advice의 차이는 유지한다. App/DataSource startup에서 새 database/apiRole/marker와 비특권 role을 확인하고 실제 `UserRepository.findById(1)`를 수행한 뒤 server-ready를 남긴다. root 전체 App·기존 Google/AI/거래 스케줄러는 없다. Spring은 config·C production classes/jars만 받으며 Driver/C5/시험 classes와 EOA 키를 받지 않는다.

Driver는 시험 키를 자기 private 위치에 먼저 저장하고 한 신규 체인에 small-house100/U2m/미배분17,999,900의 실제 토큰 배분·최소2OPEN/1CLOSE만 준비한다. A는1100deposit/1000collateral OPEN→CLOSE at110로 free1200/claim900·terminal lock0, B는1000deposit/OPEN의 locked1000이다. 이 변형은 read account/terminal position/claim positive fixture이며 기본H10m 인수나 제품 Sender를 대신하지 않는다. 이후 Driver가 새 JWT로 실제 HTTP challenge·personal-sign·동시 link·자기/타인 read를 확인한다. nonce120/Report30·자산 산식은 변경하지 않는다.

새 PG API role은 users SELECT/UPDATE(id)(row-lock용), C challenge SELECT/INSERT 및 consumed/proof_signature만 UPDATE, link SELECT/INSERT 권한만 갖는다. 신규 users mutation 거부 trigger가 실제 UPDATE를 차단하고 row lock은 허용하는지 시험한다. 새 fixture의 사용자4 proof-consume trigger 실패를 주입해 link INSERT와 consume 모두 rollback되는지 확인한다. owner credential을 API에 넘기는 fallback은 없다.

총괄 선택으로 PG 컨테이너는 **기존 C4b 실제 PASS한 root filesystem RW + NET_RAW drop launch primitive**를 새 소유 자원에만 재사용한다. 임의 CHOWN 등 cap5개/read-only PG hardening 후보는 실행하지 않는다. 이 PG 실행 권한 한계는 App/Driver의 read-only root/cap-drop ALL/nonroot 격리와 별개로 명시한다. 신규 네트워크/no-p/volume/owner/SCRAM/최종 TCP readiness guard는 유지하며 기존 PG 설정·자원은 바꾸지 않는다.

Driver bootstrap-ready 후 같은 JVM이 대기하는 동안 Python이 App을 기동한다. Driver 결과 이후 새 fixture-local STOP 파일로 App context를 닫아 정상0 종료를 기다리고, 그 뒤 소유 PG/Anvil을 정지·보존한다. Driver와 App self-test는 소켓/DB/Spring을 기동하지 않는다. 준비 명령은 `python deployment/c-service-live-check.py prepare`, 실제 실행은 검토/자원 재확인 뒤 총괄만 `run <새 prepared-id>`로1회 수행한다. 현재까지 실제 Gate는 **NOT_RUN**이다.

### 11.9 신규 하네스 동결과 준비 회귀 — Java compile 전

신규4파일 후보를 동결했다. `CCustodyLiveApplication.java` SHA `e179e816ff597baa6e6cd6f9219acc6a5600e8c9a881c09d7aa8989e82a6f7a9`, `CCustodyLiveDriver.java` SHA `3cccb979d54e72208046779797e1131796333aeb24256c7e6d8572f3896215a2`, Python runner SHA `02e1768fa4410d70322e6e05667342ce23ad1c9f14c60d75d91104611e5732d9`, Python tests SHA `7769a293bcfb211ce30f9c5407d72cff34ac12b4dcab7efdf18924f6a9a1a0eb`다. 준비 회귀는 **순수 Python12PASS**이며 Docker/Java compile/RPC/PG/socket0이다. 다음 실제 `prepare`가 source snapshot·별도 out·Lombok·두 Java self-test까지 확인해야 한다.

총괄 소스 점검에서 container/image inspect 원문을 commands 로그에 복사하면 PG/Driver Env 비밀이 중복 기록되는 위험을 발견했다. 실제 Gate 실행 전이므로 누출이 실제 발생했다는 판정은 아니다. 원문은 검증용 메모리에만 두고, 결과 기록은 제한 metadata/Env **이름**만 허용하며 stdout/stderr·malformed JSON·알 수 없는 명령에도 원문 복사 기본 금지를 적용했다. password/JWT sentinel과 잘못된 inspect·argv 로그 누출 방지 회귀를 추가했다. 기존 실패 private 원본은 변경하지 않았다.

선택 App은 기본 password를 자동 생성할 수 있는 `UserDetailsServiceAutoConfiguration`을 제외하고 self-test로 고정했다. 실패는 stage/예외 class 이름/허용된 SQLSTATE만 별도 파일에 남긴다. 실제 HTTP fault는 기대값 검사 이전에 code/wire수/경과를 저장한다. 새 DB owner의 비밀번호 DDL은 해당 JDBC 세션만 SQL·parameter 오류 로그 억제를 적용하고 API role도 parameter-error logging 비활성을 확인한다. 기존 PG 전역 설정이나 다른 서비스 로그는 변경하지 않는다. C read/wallet core53·기존 C5 원본은 그대로다.
