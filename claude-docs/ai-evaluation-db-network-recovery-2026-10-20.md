# AI 평가 DB 네트워크 장애 복구 — 2026-10-20

## 범위와 판정

기준 `melon-init@e0c085c`. 사용자가 승인한 것은 신규 소유 fixture의 네트워크·JDBC·pgvector·IndexLease 무과금 검증과 최소 진단 보완·로컬 커밋이다. 실제 LLM/Embedding 재개는 승인되지 않았다. [원래 실패 보고서](ai-real-model-evaluation-2026-10-20.md)의 BLOCKED/NOT_RUN 이력을 덮어쓰지 않는다.

**신규 DB 접근 대안과 무과금 Java 통합 검증 PASS. 실제 16문항/재색인은 여전히 NOT_RUN.** 신규 DB/index는 진단 전용이며 live 평가로 재사용하지 않는다. 실제 embedding 대신 동일한 1536차원 로컬 합성 벡터로 저장/검증 계약만 확인했다. 검색 품질이나 모델 정답률을 검증한 결과가 아니다.

## 1. 원인과 근거

기존 실패는 PostgreSQL ready/SQL 성공과 `HostConfig.PortBindings` 설정에도 불구하고 `NetworkSettings.Ports={"5432/tcp":[]}`였으며 Windows TCP는 10061이었다. internal-only 네트워크였고 Engine은 28.4.0이었다.

Docker/Moby **v28.4.0 `libnetwork/endpoint.go` 661~670행**은 `!n.internal`일 때만 `ProgramExternalConnectivity`를 호출한다. `default_gateway.go` 116~118행에서도 internal endpoint는 default gateway 생성 대상으로 보지 않는다. 이 소스와 실제 비어 있는 port mapping이 일치하므로 이번 구성의 internal-only network에서 host port publishing을 기대한 것이 원인이다. PostgreSQL init/비밀번호/모델 접근 거부가 최초 port 미게시 원인은 아니다. Windows Docker Desktop은 컨테이너 bridge에 직접 연결되는 네이티브 Linux 호스트와 달리 VM을 통하므로 bridge IP로 우회하지 않는다.

- [고정 버전 Moby endpoint.go](https://github.com/moby/moby/blob/v28.4.0/libnetwork/endpoint.go#L661)
- [고정 버전 default_gateway.go](https://github.com/moby/moby/blob/v28.4.0/libnetwork/default_gateway.go#L116)
- [Docker port publishing](https://docs.docker.com/engine/network/port-publishing/)
- [Docker Desktop networking](https://docs.docker.com/desktop/features/networking/)

위 원인 확인이 원래 JUnit에서 소실된 최초 SQLException 원문/SQLSTATE를 복원한 것은 아니다. 원래 보고서의 미확정 세부 정보는 그대로 유지한다.

## 2. 최소 수정과 보안 경계

`internal:false`, 외부 bridge 추가, host networking, firewall/daemon 변경 대신 다음 전용 경로를 선택했다.

`Windows Java → 127.0.0.1 전용 relay → Docker exec stdio → 동일 신규 DB 컨테이너의 127.0.0.1:5432`

- `deployment/ai_db_relay.py`: 고정 PostgreSQL 목적지이며 임의 목적지/명령/비밀 값을 인자로 받지 않는다. 무작위 runId·DB/user·image·소유 volume·immutable container ID를 검사한다. `network_mode=none`만 허용하고 privileged/추가 capability/게시 포트/bind mount를 거부한다. Docker socket을 컨테이너에 mount하지 않는다.
- 호스트 listener는 IPv4 `127.0.0.1`에만 bind한다. 포트 점유 시 실패하고 기존 listener를 종료하거나 공유하지 않는다. 연결 총량 기본32(최대128), 실행시간 기본600초(최대14400초)로 제한하며 종료 시 자신이 만든 client/exec child만 닫는다. 컨테이너 내부 Perl은 연결별600초 alarm/EOF로 종료한다. 앞으로 10분보다 긴 평가에서는 이 연결 수명과 pool의 재연결 동작을 고려해야 한다.
- PostgreSQL은 `network_mode=none`, 게시 포트/네트워크가 없다. 새 fixture `/proc/net/route`에 default route가 없음을 확인했다. 외부 API 접근/egress를 열어 해결하지 않았다. 호스트의 원래 Docker 관리 권한을 쓰는 통로이며 악의적인 로컬 관리자/Docker 사용자를 막는 보안 경계는 아니다.
- `ai_db_fixture.py`: 새 빈 private 디렉터리에만 무과금 DB 전용 manifest/env/Compose 생성. API key/approval/SQLite ledger는 만들지 않는다.
- `ai_live_run_prepare.py`: 미래 신규 live run도 network-none + 별도 `relay.json`을 생성하도록 보완했다. `run.json`/원장 identity를 수정하지 않는다. **이번에 live prepare는 실행하지 않았다.**
- `ai_evaluation_broker.py`: live 준비 도구와 DB relay 소스도 sourceHash에 포함한다. 옛 run의 해시/STOP를 새 값으로 덮어쓰지 않는다.

## 3. 실제 검증과 발견·수정한 문제

새 fixture 3개는 각각 별도 DB/user/volume/loopback port를 갖는다. pinned pgvector image `sha256:eac621400b7b7ff52493883e41e930e3d104695fea5b68cc0c42370cf7880067`을 pull/build 없이 사용했다. 비밀번호는 새로 생성하고 파일 ACL을 사용자/SYSTEM으로 제한했다.

| 실행 | 결과 | 실제 근거 |
|---|---|---|
| `ai-db-network-probe-20261020-01` | FAIL | Java 접속·pgvector·lease 검증 후 잘못된 비밀번호까지 허용되어 assertion 실패. 새 fixture의 localhost `pg_hba_file_rules`가 trust인 것을 실제 SQL로 확인. XML/DB/volume 보존 |
| `ai-db-network-probe-20261020-02` | FAIL | SCRAM 신규 DB. relay READY 후 실행 대기 중600초 deadline으로 자동 종료(연결0), 이후 JDBC 접속08001. 안전 진단은 `phase=CONNECT/category=SQL_EXCEPTION/sqlState=08001/vendorCode=0`만 저장. 실패 XML/DB/volume 보존 |
| `ai-db-network-probe-20261020-03` | PASS | 별도 신규 DB에서 relay 시작과 Java 실행을 한 orchestration에 묶음. 실제 JUnit1 PASS/0FAIL/0SKIP, relay7연결, 유료 호출0 |

첫 결함은 이미지 기본 localhost trust와 exec relay가 만난 신규 fixture 설정 문제였다. 기존 DB를 수정하지 않고 **새 DB 초기화에만 `POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256`**을 추가했다. 기존 데이터에 env만 바꿔 인증이 강화된 척하지 않았다. 최종 실행은 올바른 비밀번호 성공과 잘못된 비밀번호 **28P01** 거부를 모두 확인했다.

최종 실제 Java 검증 파일은 `EvaluationDatabaseProbeTest.java`이다.

- 실제 DB/user 식별자 대조, 엉뚱한 DB 대상 거부
- 빈 DB lease 생성 PREPARED, 새 연결의 PREPARED 재개
- INDEXING 이후 미완성 재개 거부
- 실제 pgvector extension/schema와 9문서88청크·1536차원 로컬 합성 벡터 저장
- READY 확정 후 새 JDBC 연결에서 READY 재개/개수·model·fingerprint 검증
- source/corpus identity 변경·비어 있지 않은 DB create·잘못된 chunk count 거부
- 실제 잘못된 비밀번호 SQLSTATE28P01의 allow-list 진단

이는 실제 PostgreSQL/JDBC 검증이며 H2/Mockito만의 결과가 아니다. READY 재개는 새 연결로 검증했으며 별도 JVM/호스트 재시작 시험이라고 확대하지 않는다. provider/ingest를 호출하지 않았다.

## 4. 오류 진단 보완

`EvaluationFailureDiagnostic`은 cause chain 최대16단계/순환 방어로 최초 SQLException의 SQLSTATE(영숫자5자리만)·vendorCode·고정 category만 기록한다. 메시지/URL/stack/SQL/비밀번호/설정값은 직렬화하지 않는다. `ApprovedAiModelEvaluationTest`의 stopped.json에 이 정제된 진단만 추가했다. 제품 예외/거래 로직은 변경하지 않았다.

원인 chain에 남아 있는 SQLException만 추출할 수 있다. 기존 `PgKnowledgeStore`처럼 원인을 제거한 `AiFailure`의 SQLSTATE까지 복원하는 보완은 아니다. 새08001/28P01 결과를 원래 실패 SQLSTATE로 소급하지 않는다.

## 5. 최종 오프라인 회귀·독립 검토

최종 새 실행:

- Python `python -B -S -m unittest test_ai_db_relay test_ai_evaluation_guard test_ai_evaluation_broker test_ai_model_access_preflight`: **59 PASS/0FAIL/0SKIP**. 앞의57PASS에 live 준비 보안2개를 추가한 최종 실행이다.
- Java `gradlew.bat test --offline --no-daemon --rerun-tasks` + 대상5종: **31 PASS/0FAIL/0SKIP**(GuardedRunner9/IndexLease7/FailureDiagnostic4/OpenAiProvider7/OpenAiAgentProvider4). 기존 Provider/AgentProvider는 로컬 HTTP fixture이며 외부 모델 호출이 아니다. 새 소스 컴파일 포함.
- 실제 새 PostgreSQL/pgvector fixture03: **1 PASS/0FAIL/0SKIP**. 첫 fixture1FAIL 및 relay 만료 fixture1FAIL과 분리한다.
- 독립 `review_ai_db_network_recovery` 최초 검토와 SCRAM/최종 문서 재검토2회: **발견된 필수 수정 없음**. 검토자는 소스/문서만 읽었으며 실제 테스트·DB·Docker·API/private 접근은 직접 하지 않았다.

기존 선물/Chrome 전체 E2E는 불필요하게 반복하지 않았다. 실제 모델/embedding/키움/Google/외부 배포 호출은 하지 않았다.

## 6. 보존과 다음 live 평가 승인

원래 `ai-real-evaluation-20261020-01`의 STOP/EVALUATION_STARTED/run.json/stopped.json/SQLite ledger를 변경하지 않는다. 새 probe에는 ledger 자체가 없다. 기존 KRX/Google/NXT/playground·DB/Anvil/manifest 및 사용자5개 dirty 파일은 그대로 둔다.

종료 시 신규 probe01/02/03의 immutable container ID·소유 label/volume을 재확인하고 그 3개만 정상 정지했다. DB/volume/실패 XML/성공 JSON은 보존했다. 원래 실패 run의 STOP/EVALUATION_STARTED/run.json/stopped.json/SQLite ledger는 SHA256 전후 모두 일치했다. 원격 push·배포·기존 Docker 서비스 조작은 하지 않았다.

다음 평가에는 수정된 소스의 **새 live run/새 전용 DB**, 사용자 재승인에 묶인 approval 파일, network-none DB와 relay 동시 운영, Java DB preflight를 유료 호출보다 먼저 확인하는 절차가 필요하다. 원래 run은 stopped 역사로 남긴다. 새 run 생성으로 한도를 초기화하는 것은 현재 승인 범위 밖이므로 이번에는 생성하지 않는다.

**비용 재승인 필요:** 같은16질문·Terra32회/embedding80회·자동재시도0·US$2 총상한 안에서 신규 run으로 재개하는 것을 사용자가 명시적으로 승인해야 한다. 이전 run의 유료 지출은0이므로 US$2를 추가해 US$4로 늘리는 요청이 아니다. 새 sourceHash/짧은 승인 유효기간을 다시 동결하고 새 예산도 US$2를 넘지 않게 강제한다. 승인 전 LLM/Embedding POST·live DB/index 생성·재색인은 하지 않는다.
