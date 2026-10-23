# Windows Gradle native socket 최소 진단 — 2026-10-21

## 결론

**이번 제한된 최소 실행에서는 재현되지 않았다. 근본 원인 미확정이며 전체 AI 웹 Gate는 여전히 BLOCKED다.** 같은 Windows/JDK에서 Java listener 12개, Gradle 실행 6회가 성공했다. client/build JVM 옵션을 맞춰 single-use daemon을 생략하는 대안은 **캐시 배포본의 작은 작업에서만** 확인됐다. 프로젝트 wrapper의 같은 옵션 테스트는 성공했지만 여전히 daemon을 만들었다. 이를 Windows 장애 해결이나 전체 통합 PASS로 확대하지 않는다.

Docker/PG/Anvil/새 AI DB/전체 fixture는 생성·실행하지 않았다. 유료 호출·승인 파일·continuation claim 생성 0회. Windows/WSL/Docker 재시작, 네트워크/방화벽/보안 설정 변경, 서비스 종료, 삭제, commit/merge/push 없음.

## 기준과 기존 장애

- 별도 `feat/ai-presentation-integration`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523`.
- sourceHash `88df6616d69c46577f37fae07f1cfb4e3fee9b72889b6cbb12c7bddb9a1258f7`, corpusHash `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`. 진단 후에도 동일하다.
- [직전 Gate](ai-web-final-gate-2026-10-21.md)의 `7527eafc`: `EvaluationIndexCopyTest`를 시작하려다 **daemon 시작** 실패. 실제 실패 위치는 `TcpIncomingConnector.accept:59/62 → ServerSocketAdaptor.bind → ServerSocketChannelImpl.netBind → sun.nio.ch.Net.listen(Native Method)`의 `Bad address: listen`이다. JUnit/JDBC/Agent 코드 도달 전이다.
- 과거 `7ea5b98b`: daemon이 127.0.0.1:59585에서 수신·연결에 성공한 뒤 `:test Bad address: bind`. 상세 caller 스택이 없어 test worker라고 확정하지 않는다. 두 실패는 같은 단계가 아니다.
- 성공했던 선택 테스트와 실패한 최신 IndexCopy 모두 JDK 21.0.10과 IPv4=true/IPv6Addresses=false를 사용했다. IPv4 설정만으로 해결됐다는 과거 가설은 성립하지 않는다.

## 실행 구조와 환경 차이

```text
gradlew.bat / cached gradle.bat
  → Gradle client JVM (launcher 기본 Xms64m/Xmx64m)
  → 필요하면 single-use daemon JVM (loopback TCP listen)
  → :test 실행 시 별도 Gradle Test Executor JVM (별도 통신 경계)
```

`--no-daemon`은 재사용 daemon 사용을 끄는 것이지 항상 단일 JVM 실행을 보장하지 않는다. client JVM이 build JVM 요구 옵션과 맞지 않으면 single-use daemon을 사용한다. 공식 [Gradle 설명](https://docs.gradle.org/current/userguide/gradle_daemon.html), [JVM 환경 설정](https://docs.gradle.org/current/userguide/build_environment.html)을 참고했으며, 현재 문서 설명을 8.10.2 실측 로그와 대조했다.

8.10.2 실측 daemon: `-Xms256m -Xmx512m -XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError`, UTF-8, KR/ko locale, Gradle의 9개 `--add-opens`/`--add-exports`, instrumentation agent. 단순 작업 출력과 daemon 로그에서 확인했다. test worker도 동일 JDK21.0.10이나 별도 프로세스이며 `-Xmx512m -ea`, Gradle classpath argfile을 사용한다. Java toolchain은 `backend/build.gradle`의 Java21이고 이번 실제 선택 경로는 해당 JDK였다.

| 실행 환경 | 확인한 차이 | 의미/한계 |
|---|---|---|
| 사용자 shell 기반 | Java 관련 override 환경변수는 조회 시 없었음. 최소 Java에서 IPv4/IPv6 속성 null | 기본 dual-stack 경로도 이번에는 성공 |
| 최소 clean child | 기존 clean_env와 같은 OS/JDK 경로 allowlist + 두 IPv4/IPv6 속성 | 계정·PATH 등 OS 필수값은 유지, 전체 환경 원문 출력 없음 |
| 실제 Phase A clean_env | 모든 opt-in test gate false, AI/chain false, config import 차단, localhost deny proxy, IPv4/IPv6 속성 | 이번 순수 테스트에 사용. 새 Phase A session을 만들지 않음 |
| IndexCopy child | `ai_index_copy_fixture.py:check`는 부모 환경 복사 후 일부 provider 변수를 제거하고 JAVA_TOOL_OPTIONS를 두 주소 속성으로 덮어씀 | Phase A proxy 옵션과 다르지만 실패가 HTTP/JDBC 전 listen이므로 이것이 원인이라는 근거 없음 |
| in-process 시도 | client JAVA_OPTS에 build의 메모리·module·encoding·locale 옵션 일치 | 캐시 launcher의 독립 작업만 fork 없이 수행. 프로젝트 wrapper는 fork 유지. worker 소켓도 제거하지 않음 |

정상/실패 daemon의 JDK·기본 메모리·module 옵션은 같았다. 실패 당시 전체 OS 상태와 포트 선택 이력이 없어, 지금의 성공 환경과 결정적인 차이는 확인하지 못했다. 사용자 shell 기반 실험도 무조건 모든 환경을 원문 보존한 것이 아니라 credential 이름을 제거했으며, 최소 작업은 외부 plugin/dependency가 없다.

추가로 `backend/gradlew.bat`의 DEFAULT_JVM_OPTS는 Xmx64m/Xms64m뿐이나 캐시 배포본 `bin/gradle.bat`에는 Gradle instrumentation javaagent가 포함된다. 메모리/module 옵션만 맞춘 두 실험에서 결과가 다른 **확인된 launcher 차이**다. agent 차이가 fork 유지의 단독 원인인지는 교차 실험하지 않아 미확정이다. wrapper를 수정하거나 instrumentation을 비활성화하지 않았다.

## 최소 재현과 결과

새 private 진단 폴더(거래 fixture 아님): `deployment/runtime/gradle-socket-probe-20261021/`.

Java 소스 launcher로 `ServerSocketChannel.open().socket().bind(address:0)` 및 `ServerSocket.bind(address:0)`를 각각 실행했다. `127.0.0.1`, wildcard `0.0.0.0`, JVM이 선택한 loopback의 세 주소를 사용했다. listener는 즉시 닫았다. 기본 channel wildcard의 실제 주소는 IPv6 wildcard였고 IPv4 옵션 환경에서는 IPv4 wildcard였다. 전체 socket flags를 덤프하지 않는다.

| 실행 | 시간 | 결과 | 증거 |
|---|---:|---|---|
| Java 사용자 shell, listener6개 | 0.672초 | 6 PASS | `java-shell.log` |
| Java clean/IPv4, listener6개 | 0.672초 | 6 PASS | `java-clean-ipv4.log` |
| 독립 Gradle socketEnvironment, shell | 6.906초 | exit0, single-use daemon | `gradle-minimal-shell.log` |
| 독립 Gradle socketEnvironment, clean | 6.344초 | exit0, single-use daemon | `gradle-minimal-clean.log` |
| 실제 backend help, clean | 7.718초 | exit0, single-use daemon | `gradle-project-clean.log` |
| 실제 backend 순수 테스트, Phase A clean | 16.891초 | 1 PASS/0FAIL/0SKIP, daemon+worker | `gradle-worker.log`, `worker-results/xml/` |
| 독립 Gradle, client/build 옵션 일치 | 4.437초 | exit0, in-process | `gradle-inprocess.log` |
| 실제 backend 같은 순수 테스트, 옵션 일치 시도 | 13.047초 | 1 PASS/0FAIL/0SKIP, daemon7384+worker, **fork 유지** | `gradle-worker-inprocess.log`, `worker-inprocess-results/xml/` |

마지막 두 테스트는 같은 테스트를 서로 다른 JVM 옵션 조건으로 비교한 것이며 둘 다 daemon+worker였다. 서로 다른 기능 2개 PASS라고 합산하지 않는다. 테스트명: `FuturesWebStage1TransportTest.everyProviderPathFailsBeforeHttpWithNoKeysNoApprovalAndNoRetry`. 실제 HTTP 이전 거부를 검증하며 PostgreSQL/Anvil/실모델 테스트가 아니다.

모든 Gradle 명령은 `--offline --no-daemon --stacktrace`, 선택 worker 검증은 `--info`도 사용했다. 독립 작업은 캐시된 배포본, backend 테스트는 프로젝트 wrapper를 사용했고 둘 다 Gradle8.10.2다. 새 다운로드 없음. Java20초, Gradle 최소65초, 선택 테스트90초로 제한했으며 timeout은 0회다. timeout 시 진단 자식 PID 트리 종료를 시도하도록 작성했으나 발동하지 않았고, 분리된 daemon까지 종료를 보장하는 경로는 미검증이다. 이번 정상 single-use daemon은 자체 종료했고 최종 Java 프로세스는 없었다. Java probe는 예외를 로그에 쓰고 계속하므로 exit0만으로 판단하지 않고 실제 PASS12개/FAIL0개를 직접 대조했다.

증거 코드: `SocketProbe.java`, `build.gradle`, `settings.gradle`, `probe.py`, `worker_probe.py`, `inprocess_probe.py`, `diagnostic.init.gradle`. 모두 신규 ignored runtime 안에만 두었다. 로그는 exclusive create로 기존 기록을 덮어쓰지 않으며 결과 XML도 신규 runtime으로 분리했다. `summary.json`, 각 worker/inprocess summary는 이번 진단의 실행시간/exit를 기록한다. 이 코드는 반복 자동 실행용 도구나 제품 설정이 아니다.

clean daemon22564는 `127.0.0.1:61823` 수신 후 client61824 연결에 성공했다. 독립 Java의 61803~61815 포트도 성공했다. 전송된 것은 로컬 진단 통신뿐이다.

## Windows 읽기 전용 관측

- IPv4/IPv6 TCP 동적 범위 모두49152~65535(16384개).
- 두 계열에서 관측된 제외 범위: 5357,49678~49977,50000~50359,50460~50559,51688~52287,52388~52487,54587~54786,63061~63260. 이 중50000~50059는 administered 표식이다. 이것을 삭제하거나 변경하지 않았다.
- 관측 순간 TCP 상태: Listen45/Bound81/Established68/SynSent1/TimeWait47/CloseWait6. 전역 포트 고갈을 입증하는 수치가 아니다. 실패 포트/당시 범위를 모르므로 충돌 원인 확정 불가.
- 물리 메모리 free3,518,180KiB / total14,528,572KiB. C여유46,420,639,744bytes. 현재 수치는 과거 실패 순간의 메모리/commit 사용량을 설명하지 않는다.
- 최근2시간 System의 경고/오류 최신100개에서 Tcpip/Winsock/AFD/Resource-Exhaustion provider 필터 결과 없음. 범위를 제한한 조회로, 관련 장애 기록 전체가 없다는 뜻은 아니다.
- Gradle 인터페이스 열거 로그에 WFP/Nprotect/Npcap 필터 이름이 나타난다. 설치/인터페이스 존재는 장애 유발 증거가 아니며 비활성화하지 않았다.

확인 명령: `netsh interface ipv4|ipv6 show dynamicport tcp`, `show excludedportrange protocol=tcp`, `Get-NetTCPConnection` 상태별 집계, `Get-CimInstance Win32_OperatingSystem`, `Get-PSDrive C`, 제한한 `Get-WinEvent`. 관리자 네트워크 설정 변경은 없다.

## 원인 판정

| 설명 | 판정 |
|---|---|
| 직전 실패의 직접 위치는 Windows JDK native listen | 확인됨(기존 stacktrace) |
| 이번 동일 JDK Java/Gradle/test-worker 정상 시작 | 확인됨(신규 실행) |
| 최신 실패가 제품 금융/Agent 로직 또는 PostgreSQL SQL 예외 | 해당 실패 경계에서는 아님 |
| IPv4/IPv6 옵션 누락만의 문제 | 최신 실패도 두 옵션이 있으므로 확정 불가 |
| 고정 포트 충돌/전체 포트 고갈 | 근거 부족 |
| 간헐 OS socket/보안 필터/JDK 내부/일시 자원 문제 | 남은 가설, 이번 무실패 표본으로 구분 못함 |
| in-process 방식이 모든 bind/listen 장애를 해결 | 미검증. daemon 수신만 줄이며 test worker/웹/JDBC 통신은 남음 |

따라서 원인이 명확할 때만 허용된 기존 harness 수정은 하지 않았다. 성공하도록 재시도하는 코드나 옵션 자동 fallback도 추가하지 않았다. 현재 전체 Gate의 과거 실패 판정은 변경하지 않는다.

## 가장 작은 실행 대안과 Linux 비교

1. **우선 조사 후보: Windows 프로세스 한정 in-process Gradle.** 캐시 launcher의 작은 작업에서만 확인됐고, 프로젝트 wrapper 테스트는 fork를 유지했다. 신규 진단 `inprocess_probe.py`와 `worker_probe.py --inprocess`에 정확한 옵션이 남아 있으며 파일명의 inprocess는 의도이지 backend 성공 판정이 아니다. 전역 JAVA_OPTS/gradle.properties를 수정하지 않고 해당 child에만 옵션을 줬다. 다음 최소 검증에서는 캐시 Gradle로 backend 선택 테스트를 실행하거나 동일 instrumentation 조건을 비교해 실제 fork 제거를 로그로 확인해야 한다. 아직 어느 쪽도 실행하지 않았다. Gradle 버전별 module/locale/메모리/agent와 wrapper 호환을 확인하기 전 영구 hardcode를 제품 harness에 도입하지 않는다. 현재 정책상 자동 대체/재시도도 금지한다. 전체 Gate 적용 전 명령·모드·옵션·실행 identity를 확정해야 한다.
2. **WSL Linux JVM:** Windows native listen 경계를 피하지만 Linux loopback의127.0.0.1은 Windows Relay와 동일하지 않다. Java/Gradle Linux 경로, `.bat/.exe`, Windows evidence 경로, source mount/case/권한, 새 project cache/temp 위치와 JDBC/Relay 주소를 별도로 검증해야 한다. 기존 worktree에 Linux 빌드 산출물을 쓰거나 기존 DB를 옮기지 않는다. 실행 경계와 저장 위치/접속주소 변경에 사용자 승인 필요. 이번에 WSL을 기동하거나 데이터를 복사하지 않았다.
3. **전용 Linux JDK 컨테이너:** JDK/Gradle 고정과 내부 네트워크 통신이 명확하지만 새 이미지/빌드 저장공간, isolated network/Relay, source readonly mount+별도 build output, host private ledger IPC 및 경로 검증이 필요하다. 기존 network-none DB의 격리를 단순 완화하면 안 된다. 새 이미지·네트워크·경로·실행 identity 설계 및 승인 후 적용해야 한다. 이번에 Docker 명령/컨테이너를 실행하지 않았다.

Linux로 옮겨도 소스 byte가 동일하면 기존 sourceHash 산식은 같을 수 있지만, 실행 OS/JDK/이미지/경로/owner/relay의 identity는 새로 확정해야 한다. 기존 manifest를 고치거나 성공 기록을 재사용해 대체할 수 없다. 경로 호환 수정으로 hash 대상 파일이 바뀌면 새 sourceHash 및 dry-run이 필요하다.

## 다음 전체 Gate 실행 조건

이번 범위에서는 실행하지 않는다. 현 상태는 **최소 조사 완료 / 근본 원인 미확정 / 전체 Gate BLOCKED 유지**다.

다음 승인 작업은 ① Windows process-only 모드 선택·wrapper 옵션 대조와 순수 테스트 사전검사 ② 최신 source/corpus/보호파일 확인 ③ 새 owner/identity의 전체 Gate 단1회 ④ 준비 실패 즉시 중단 ⑤ 전체 PASS 뒤에만 새 context 직렬화·누적 비용 Guard 검증 순서가 적절하다. 기존 실패 fixture는 재사용하지 않는다. 이 단계를 통과하기 전 유료 승인 파일을 만들지 않는다. Linux 전환은 Windows 대안이 다시 실패하거나 재현 근거가 생겼을 때 별도 승인 후 수행한다.

## 보존과 검토

원본/UI/AI worktree 사용자 파일43개 및 기존 평가 증거113개, 합계156개 SHA-256 비교는 대소문자를 정규화해 **변경0개**다. sourceHash/corpusHash도 동일하다. 현재 worktree에는 기존 dirty43개 항목과 이번 조사 문서1개가 있으며 기존 implementation-log에 이번 기록만 추가한다. 제품·테스트/harness 추적 소스는 수정하지 않았다. 임시 build/cache는 신규 진단 runtime 및 기존 일반 Gradle cache/build 산출물 범위다. DB/Anvil/ledger/claim 원문을 읽어 출력하지 않았다.

독립 검토 `review_gradle_socket`은 실제 신규 로그/XML 및 기존 실패 stack을 확인해 backend in-process 성공 범위 과장1건을 지적했다. 이를 수용해 wrapper 테스트의 fork 유지와 launcher 차이·미확정 인과관계를 정정했다. 재검토의 표현2곳도 바로잡았다. timeout detached daemon 종료 및 Java exit0 판정 한계를 추가했다. 검토자는 새 테스트/서비스를 실행하지 않았다.

마지막 표현 수정 후 검토자의 최종 재확인은 사용량 제한으로 중단됐다. 따라서 **최종 독립 재검토 완료로 표시하지 않는다**. 구현자가 마지막으로 실제 로그를 다시 비교해 listener PASS12/FAIL0, backend 옵션 시도 fork=true, 캐시 최소 작업 fork=false를 확인했고 `git diff --check`는 exit0이었다. 이는 자체 확인이며 독립 재검토를 대체하지 않는다. 이번 조사에서의 무재현을 root-cause 해결로 표현하지 않는 것이 핵심 제한이다.
