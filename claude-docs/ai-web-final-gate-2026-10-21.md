# AI 웹 최종 Gate — 무과금 실행 및 유료 승인 준비

> 2026-10-21 최종 판정: **BLOCKED — Gradle single-use daemon native listen 실패.** 승인된 신규 전체 통합 fixture1회만 실행하고 중단했다. 실제 유료 요청·승인 파일 생성은0회다.

## 1. 기준과 보존

기준은 [2단계 보고서](ai-web-stage2-guarded-preparation-2026-10-21.md)다. 브랜치 `feat/ai-presentation-integration`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523`. 시작 sourceHash `66eaf1c80358d067c7d940b82760eda3a3ef2abcb24158e6584230b4c0b96eac`가 기록과 일치했다. corpusHash `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`. 기존 보호156개 파일 SHA-256 동일, 원본/UI/AI worktree·ledger·STOP·RESERVED·claim·approval은 보존한다.

Docker Server28.4.0 정상, 시작 C 여유46,557,077,504bytes, 실행 Java 프로세스0. 기존 환경은 시작·정지·초기화하지 않는다.

## 2. 기존 Gradle 오류의 확인 범위

`7ea5b98b`의 Index copy Gradle daemon13652는 17:29:13에 loopback127.0.0.1:59585 수신과 클라이언트 연결에 성공했다. 그 뒤 `:test`가 `java.net.SocketException: Bad address: bind`로 종료했다. 따라서 이 실행의 오류는 daemon 최초 시작 실패와 구분한다. 이전 다른 실행의 `Bad address: listen`을 같은 장애로 합치지 않는다.

기존 로그에 상세 스택이 없어 정확한 내부 bind 호출자·근본 원인은 미확정이다. PostgreSQL/JDBC proof 존재는 전체 Gradle 성공이 아니다. 포트/Java 프로세스 조회에서 현재 충돌을 확정하지 못했다. 현재 정상이라는 이유로 과거 실패를 성공으로 변경하지 않는다.

실행 환경 차이: 통과한 Java 선택 테스트는 `preferIPv4Stack=true`, `preferIPv6Addresses=false`를 모두 지정했지만 Index copy 도구는 IPv4만 지정했고, 통합 harness clean child는 둘을 명시하지 않았다. 이것은 확인된 설정 차이이며 인과관계의 확정은 아니다.

이번 변경은 `deployment/phase-a.py`, `deployment/ai_index_copy_fixture.py`의 해당 프로세스 `JAVA_TOOL_OPTIONS`에 두 옵션을 동일하게 지정하고 `--stacktrace`를 추가한 것이다. `test_phase_a.py`에서 옵션과 명령 계약을 검증한다. 전역 네트워크·방화벽·Windows/WSL/Docker 재시작과 제품 코드는 변경하지 않았다.

## 3. 무과금 사전검사

- 보완 후 sourceHash: `88df6616d69c46577f37fae07f1cfb4e3fee9b72889b6cbb12c7bddb9a1258f7`.
- 실제 정리된 child env의 Gradle `WebRunnerDeadlineTest`, `WebScopedTransportTest`, `FuturesWebStage1TransportTest`: **3 PASS/0FAIL/0SKIP**, exit0. 전송 중 중단·키 없는 전송·권한 거부 경계이며 DB/실모델 검증은 아니다.
- Python 관련4모듈: **42 PASS**. 최초 실행의 command-list assertion은 `--stacktrace` 추가를 기대하지 않아 실패했고, 해당 테스트 기대값 및 편집 중 들여쓰기 오류를 수정한 뒤 새 실행42 PASS. 테스트 오류를 제품 결함이나 환경 장애로 설명하지 않는다.
- 사전검사 로그 `deployment/runtime/final-web-gate-preflight-20261021T085109/gradle.private.log`. 기존 로그를 덮어쓰지 않는다.

## 4. 단 한 번의 신규 전체 통합

새 session/child identity, 실제 거래 DB·Anvil, READY 원본 readonly cold copy와 새 owner/IndexLease를 사용한다. 자동 재시도는 없다. 준비 또는 검증 실패 시 그 fixture를 종료하고 기존/실패 데이터는 유지한다.

검증 대상은 Long10 자동청산/실제60초유예, 실제JWT/Chrome/개인WS, 선택 범위 canonical Tool2와 journal, READY 정책 검색/인용, Guard fake embedding1+fake LLM1, 권한 및 중복 요청·STOP,1366/390px 화면, 감사와 소유 환경 정지다. fake 응답은 실제 LLM 품질로 판정하지 않는다.

## 5. 유료 직전 Gate 및 승인안

전체 통합이 성공해야만 새 source/owner/user/position/고정 질문/Skill의 실제 직렬화 dry-run과 carry를 검토한다. 기존 Q12 승인이나 simulation claim/STOP을 live로 바꾸지 않는다. live 승인 부재 시 HTTP sender 생성 이전에 차단되어야 한다.

후보 상한: `gpt-5.6-terra`1회, `text-embedding-3-small`1회, input32768/output1600/embedding1032, 신규 보수 예약US$0.10114064, 기존US$0.36634320 포함 누적US$0.46748384, 자동 재시도0. 이는 새 US$2 예산이 아니며 부모/child/Q12와 Q6 RESERVED를 누적 유지한다. 실제 요청 직렬화가 상한을 넘으면 READY가 아니다. 이번에는 실제 승인 파일과 유료 요청을 만들지 않는다.

## 6. 결과·독립 검토

전체 통합과 최종 증거, 독립 검토, 보존 재대조 결과를 아래에 기록한다. 이전12e72d92 PASS는 이번 최종 소스의 PASS로 재사용하지 않는다. 원본 병합/commit/push/배포 없음.

### 단일 실행 identity

- session: `phase-a-20261021085324-e379a1b4`.
- 신규 child: **`futures-ai-web-7527eafc`**. 이 session에 fixture는1개뿐이며 재시도하지 않았다.
- 실행 HEAD: `f9353c33244565b30fb130dfb6159eab441a3523`, sourceHash: `88df6616d69c46577f37fae07f1cfb4e3fee9b72889b6cbb12c7bddb9a1258f7`.
- productHash: `d757f64f74fd089f4facc83318a25293d1bb8b4d7ef16efe462603f2e773b415`(제품 불변). 새 tools image digest: `sha256:ba676fc1afdc73b070e0783f17e3ae8dca928c050170aa18d16b36ce64d03f9c`.
- 신규 AI DB identity: `eval_e15724ff62d141e2810caf7b6413cfaf`, Relay 예정 포트57501. 기존 AI DB/index와 다르다. 원본 network-none volume은 읽기 전용 mount로 복사했으며 원본 DB는 기동하지 않았다.

### 최초 오류와 정확한 실패 경계

실패 명령은 신규 AI 복사 검증의 `gradlew.bat test --offline --no-daemon --rerun-tasks --stacktrace --tests *EvaluationIndexCopyTest`다. `index-copy-java.private.log`에는 아래 스택이 남았다.

```text
Picked up JAVA_TOOL_OPTIONS:
  -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false
Unable to start the daemon process.
org.gradle.api.UncheckedIOException:
  java.net.SocketException: Bad address: listen
  org.gradle.internal.remote.internal.inet.TcpIncomingConnector.accept:62
  org.gradle.launcher.daemon.server.DaemonTcpServerConnector.start:86
  org.gradle.launcher.daemon.server.Daemon.start:175
Caused by: java.net.SocketException: Bad address: listen
  sun.nio.ch.Net.listen (Native Method)
  ServerSocketChannelImpl.netBind / bind
  ServerSocketAdaptor.bind
  TcpIncomingConnector.accept:59
```

이번 실패는 **single-use Gradle daemon의 수신 소켓 시작 단계**이며 JUnit worker/JDBC 검증이나 제품 Agent/금융 경로에 도달하지 않았다. 이전 `7ea5b98b`는 daemon 시작 성공 후 :test bind 실패였으므로 동일 단계로 단정하지 않는다. 새 JVM 양쪽에 옵션이 반영됐는데도 재발했으므로 IPv4/IPv6 옵션 누락만이 근본 원인이라고 주장할 수 없다.

확정한 것은 Windows/JDK의 native listen 호출 실패 위치다. Windows 내부 원인·보안 소프트웨어·주소 자원·특정 드라이버의 영향은 **미확정**이다. 현재 포트 현황과 사전검사3 PASS만으로 과거 충돌을 증명하거나 장애 해결을 선언하지 않았다. 전역 설정 변경/방화벽 해제/재시작/프로세스 강제 정리 없이 중단했다.

### 항목별 판정

| 검증 | 이번 실행 결과 | 증거와 한계 |
|---|---|---|
| Git/source/corpus/보호파일 | PASS | 시작/종료156개 SHA-256 동일, 원본 dirty9 보존 |
| Docker/디스크/사전검사 | PASS | Server28.4.0, 충분한 C 여유, clean-env Java3/Python42 PASS |
| 신규 PG/Anvil/계약·소유권 | PASS(준비) | 신규 로컬 체인 배포·DB marker·소유권 검사, 실제 거래는 미실행 |
| 기존2초 clock guard | PASS | DB-host0.01002717초, RTT0.6594317초, 오차+불확실성 약0.339744초 |
| READY 원본 readonly cold copy | PASS(복사) | 원본 owner READY 확인·새 cold copy/export. 새 DB IndexLease PASS와 구분 |
| 신규 JDBC/SCRAM/pgvector/IndexLease/새 checksum | BLOCKED | Gradle daemon 실패, `verified=false`, 새로운 JUnit XML/검증 proof 없음 |
| Long10 마진콜/유예/청산·DB 정산 | NOT_RUN | 준비 중단 후 추가 진행 금지 |
| 실제 Chrome/JWT/Tool2/fresh RPC/journal | NOT_RUN | 새 웹/Agent를 시작하지 않음 |
| 정책 검색·인용·guarded fake 응답/UI | NOT_RUN | 과거12e72d92 성공을 이번 결과로 대체하지 않음 |
| 이번 새 context 직렬화 dry-run | NOT_RUN | 전체 통합 PASS 전에는 추가 유료 직전 Gate를 진행하지 않음 |
| 실제 유료 승인/LLM/Embedding | NOT_RUN / 각각0회 | 실제 live approval 및 live claim 생성하지 않음 |
| 신규 환경 정지·보존 | PASS | 아래5개 정확한 owner/immutable ID 정지 및 exit0, 삭제 없음 |

정지 확인 대상은 신규 trade PostgreSQL/Anvil 및 신규 AI DB/source-snapshot/cold-copy helper5개다. 최종 inspect에서 모두 Running=false, ExitCode=0이었다. volume, chain, raw export, manifest, 실패 로그는 보존했다. 기존 Google/KRX/playground나 보존 DB를 시작/정지하지 않았다. 실행 Java 프로세스0, 최종 C 여유46,428,053,504bytes. 원본/별도 worktree에 commit/merge/push/배포 없음.

### 실제 증거 위치

본 worktree의 `deployment/runtime/phase-a-20261021085324-e379a1b4/fixtures/futures-ai-web-7527eafc/`에 `run.json`, `preflight-clock.json`, 신규 `ai-db/run.json`, `copy-provenance.json`, `source-index.json`, `index-copy-java.private.log`, `db.env`가 남아 있다. 마지막 파일은 private secret이며 문서/로그/Git에 내용을 옮기지 않는다.

session `logs/`에는 cold-copy 성공과 copy-check exit1/verifiedfalse, 새 자원별 owner/정지 기록을 남겼다. 과거 실행 파일/STOP/ledger/claim을 덮어쓰지 않았다. 새 로그의 stacktrace는 진단용이며 인증정보나 전체 환경변수를 출력하지 않았다.

### 독립 검토

`review_ai_web_stage1`은 이번 세 경로의 프로세스 옵션/stacktrace 및 테스트·문서를 독립 검토해 **발견된 필수 수정 없음**으로 보고했다. 검토자가 직접 실행한 관련4모듈40 PASS는 구현자의 다른4모듈42 PASS와 서로 다른 선택이다. 검토자는 Docker/실제 fixture/키/유료 호출을 실행하지 않았다. 소스 검토 통과는 이번 전체 통합 BLOCKED를 해소하지 않는다.

### 다음 조치와 유료 승인 상태

**READY 아님. 유료 실행 금지.** 신규 직렬화와 사용자/포지션의 최종 live identity가 없으므로 US$0.10114064/누적US$0.46748384를 이번 실행의 확정된 실제 dry-run 값으로 승인 요청하지 않는다. 기존 누적US$0.36634320과 Q6 RESERVED는 그대로이며 이번 추가 실지출은0이다.

다음 작업은 거래 E2E 반복이 아니라 **최소 Gradle daemon socket 재현으로 Windows native listen 실패를 좁히는 것**이다. 필요 시 Linux JVM 대안을 검토할 수 있지만, 저장 위치/실행 경계/격리 구성 변경은 먼저 범위와 영향을 설명한다. 이번 요청의1회 fixture 한도 때문에 추가 신규 fixture나 기존 실패 환경 재사용은 하지 않았다.

환경이 해결된 뒤에만 별도 신규 fixture의 전체 통합1회와 정확한 source/owner/context 직렬화, 누적 Guard·승인 부재 차단·bounded harness/정리를 다시 확인해야 한다. 그때 별도 유료 승인안을 제출하며 현재 기존 Q12 승인/claim 또는 simulation ledger를 재활용하지 않는다.
