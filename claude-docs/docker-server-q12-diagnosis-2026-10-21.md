# Docker Server 장애 진단 및 Q12 재개 판단

> 2026-10-21 KST 10:47~10:54 읽기 전용 조사. **Docker 관리 경로 BLOCKED, Q12 NOT_RUN 유지.**

## 1. 범위와 결론

[이전 Q12 사전검사](ai-q12-real-evaluation-2026-10-21.md) 이후 Docker/WSL 프로세스·서비스·context·Server 응답·정제 로그·기존 웹 영향만 조사했다. Docker Desktop/Engine/WSL 재시작, 기존 컨테이너 종료, prune, image/volume 삭제, DB 초기화, 포트/context 교체는 하지 않았다.

**확인한 장애 경계:** WSL 배포판 열거와 docker-desktop 내부 명령은 응답하지만 Docker Server API와 Desktop status 관리 조회는 응답하지 않는다. 기존 Google 웹은 별도로 HTTP200이다. 따라서 WSL 전체 정지 또는 기존 모든 컨테이너 중단으로 단정할 수 없다. 정확한 backend/Engine 내부 원인은 아직 확정하지 못했다.

Q12의 실제 DB/index 생성 전제조건이 충족되지 않아 유료 평가를 재개하지 않았다. 신규 LLM/embedding/예약0, 새 continuation claim/run/approval/DB/index 생성0이다. 기존 US$0.30200540 누적 예약과 불확실 RESERVED 및 STOP을 유지한다. [준비된 Q12 정책](ai-q12-continuation-preparation-2026-10-21.md)의 LLM1/embedding1·신규예약최대US$0.10114064/과거포함최대US$0.40314604는 변경하지 않았다.

## 2. 실제 관측

| 항목 | 결과 | 해석의 한계 |
|---|---|---|
| Docker Desktop/backend/build 프로세스 | 존재. backend PID7212/14960, build19020. backend는10/18부터 실행 | 프로세스 존재가 Engine 정상 응답을 뜻하지 않음. 누적 CPU 시간으로 현재 CPU 폭주를 단정하지 않음 |
| Windows 서비스 | WslService Running, vmcompute Running, com.docker.service Stopped/Manual, LxssManager Stopped/Manual | WSL2의 com.docker.service 중지는 단독 원인 아님 |
| `docker context ls --format '{{json .}}'` | 정상. 선택 desktop-linux → `npipe:////./pipe/dockerDesktopLinuxEngine`. default → `npipe:////./pipe/docker_engine` | context 설정은 변경하지 않음 |
| 선택 context Server version | 12초 timeout | 현재 Server 정상 응답 확보 실패 |
| explicit default context Server version | 10초 timeout | 선택 context 이름만의 문제라는 가설을 뒷받침하지 못함. endpoint를 바꿔 실행하지 않음 |
| `docker desktop status` | 8초 deadline 초과, CLI 자식 plugin도 응답하지 않음 | Desktop이 stopped인지 running인지 결과를 확보하지 못함 |
| `wsl --list --verbose` | 8초 이내 성공. Ubuntu/Ubuntu-24.04/docker-desktop 모두 Running/WSL2 | 다른 두 배포판도 실행 중이므로 전체 wsl shutdown의 영향이 더 넓음 |
| `wsl -d docker-desktop --exec sh -c 'date -u; df -h / /var/lib/docker'` | 8초 이내 반환. UTC 시각 출력, `/` 약1006.9G 중60.3M 사용. `/var/lib/docker` mount 조회 실패 | bootstrap namespace의 root만 확인. 실제 Docker data VHD의 여유 공간이나 데이터 소실을 의미하지 않음 |
| 알려진 Linux socket 4개 존재 조회 | `/var/run/docker.sock`, `/run/docker.sock`, `/mnt/docker-desktop/shared-sockets/guest-services/docker.sock`, `/mnt/docker-desktop/run/docker.sock` 없음 | Docker 버전별 내부 위치를 모두 조사한 것이 아님. daemon 소멸이나 데이터 소실의 증거 아님 |
| C 드라이브 | 전체476.31GiB, 가용 **2.58GiB** | 적은 여유 공간은 위험 요인이나 현재 ENOSPC 직접 증거는 아님. virtual root df 수치와 혼동 금지 |
| 호스트 RAM | TotalVisibleMemorySize14,528,572KiB, FreePhysicalMemory1,460,216KiB (가용약1.39GiB) | 한 시점 값. 원인 확정이나 OOM 발생 증거 아님 |
| 5174 listener | 127.0.0.1:5174, 소유 backend PID7212 | 실제 서비스 내용/DB 상태는 listener만으로 증명 안 됨 |
| `GET http://127.0.0.1:5174/` | 3초 timeout 설정으로 실제 HTTP200, content-type text/html | 정적 웹 루트만 확인. 인증/DB/Anvil/API/WS의 정상 동작 검증 아님 |
| 5173/8080 listener 조회 | 이번 조회에서 없음 | 보존 환경 삭제나 의도적 종료로 해석하지 않음 |
| 현재 Java/Python 평가 프로세스 | 평가 관련 실행 프로세스 발견 안 됨. 조사 종료 시 진단 CLI도 남지 않음 | snapshot 관측이며 향후 동시 실행이 없음을 보장하지 않음 |

Docker 공식 문서에 따르면 WSL2 Linux 컨테이너에서는 `com.docker.service`가 필수가 아니며 자동 기동하지 않을 수 있다. 이 서비스를 임의로 시작해 “원인 수정”으로 취급하지 않았다. [Docker Windows 권한 및 helper 설명](https://docs.docker.com/desktop/setup/install/windows-permission-requirements/)

기존 [Google 환경 복구 이력](google-auth-finalization-and-integration.md)에도 service가 Stopped인 상태에서 Server가 정상 응답한 기록이 있다. 과거 정상 재시작 명령 실패 및 사용자 재부팅 이후 복구는 당시 기록이며 현재의 근본 원인/해결을 증명하지 않는다.

## 3. 제한시간과 안전한 관측 방식

- Docker/WSL CLI는 `python -B -S -c`의 `subprocess.run(..., capture_output=True, timeout=8/10/12)`로 범위를 제한했다. Server version1회/alternate endpoint1회/status1회만 수행했고 같은 검사를 무한 반복하지 않았다. 지난 요청의 `docker info`12초 실패는 이전 보고서에 남기고 이번에 재실행하지 않았다.
- 프로세스·서비스·포트·메타데이터 조회는 실제1~7초 이내 반환했다. 전체 서비스 조작 명령은 실행하지 않았다.
- `docker desktop status`는 부모 CLI timeout 이후 자식 `docker-desktop.exe status`가 pipe를 보유해 수집이 끝나지 않았다. 해당 **진단 plugin PID19052와 명령 identity만** 확인해 종료했다. Docker Desktop 앱·backend·WSL·기존 컨테이너를 종료한 것이 아니다. 이후 해당 진단의 TimeoutExpired 결과와 프로세스 종료를 확인했다. 향후 plugin 호출에는 프로세스 트리까지 제한하는 방식을 권장하지만 이번에는 제품/harness 코드를 수정하지 않았다.
- `Get-CimInstance Win32_Process`에서 평가 관련 여부만 boolean으로 출력하고 원문 CommandLine을 노출하지 않았다. 환경변수는 Docker override 이름만 조회했다. 인증정보·원문 로그·HTTP 본문·원본 응답·JWT를 출력하지 않았다.
- Server 오류는 timeout이다. 이번에 HTTP500, ENOSPC, OOM을 직접 확인한 것으로 기록하지 않는다.

## 4. 로그 근거와 가설

호스트 Docker log 디렉터리 파일 메타데이터와 아래 각 파일의 끝 **최대128KiB**만 읽었다. 원문은 출력하거나 문서에 복사하지 않고 고정 문자열 분류별 건수와 ISO 시각만 추출했다.

| 파일 | 이번 정제 결과 |
|---|---|
| host/com.docker.backend.exe.log | wsl315/engine315. no-space/context-deadline/connection-refused/io-timeout/panic/fatal/failed는 이 범위에서0. 마지막 추출 시각2026-10-17T16:51:11Z 부근으로 현재 장애 근거로 부족 |
| vm/init.log | dockerd34. ENOSPC/input-output/deadline/refused/io-timeout/panic/fatal/failed/unhealthy/OOM/timeout 분류0. 마지막 추출 시각2026-10-20T16:06:00Z |
| host/docker-desktop.exe.log | backend758. 위 주요 실패 분류0. 마지막 추출 시각2026-10-21T01:49:51Z |

이는 전체 로그 검색이 아니고 문자열에 없는 오류를 배제하지 못한다. “오류0이라 정상”이라고 판단하지 않는다. 파일 이름·내부 namespace만으로 실제 Engine lifecycle을 추정하지 않는다.

| 가설 | 현재 판단 |
|---|---|
| WSL 전체가 멈춤 | 현재 list/date/df가 응답해 이 설명만으로는 맞지 않음 |
| 단순 잘못된 Docker context | desktop-linux와 default 모두 timeout. 단순 context 교체 해결 근거 없음 |
| 평가 프로세스가 DB/port를 점유 | 현재 평가 프로세스 발견 안 됨. 새 Q12 DB/Relay는 아직 만들지 않음 |
| Desktop/backend 관리 경로 또는 Engine API가 stuck | status/API 무응답과 부합하지만 proxy/daemon/backend 중 정확한 지점은 미확정 |
| 호스트 공간·메모리 압박 | 실제 여유가 적어 가능성 있음. 현재 직접적인 ENOSPC/OOM 로그는 확보하지 못함 |
| com.docker.service Stopped가 원인 | 공식 WSL2 동작 및 과거 실제 정상 기록상 단독 원인으로 취급 불가 |

## 5. 복구 선택지 — 아직 실행하지 않음

| 선택지 | 영향/위험 | 승인 경계 |
|---|---|---|
| 현 상태 유지 후 사용자가 Desktop UI/Server 응답 확인 | 기존 Google 웹 유지. Q12는 계속 BLOCKED. 원인 해결 보장 없음 | 무변경이므로 지금 선택한 상태 |
| **계획된 Docker Desktop 정상 종료/재기동** | Google5174 및 같은 Engine의 KRX/playground/Google/AI 등 서비스가 중단될 수 있음. persistent DB/state는 보존 설계이나 마지막 미저장 메모리 state·진행 중 작업 손실 가능성을 사전 확인해야 함. API 불능이라 현재 전체 컨테이너 inventory는 확보 못함 | **사용자 명시 승인 필요**. factory reset/clean/purge/force-kill은 포함하지 않음. 과거 정상 재시작 실패 이력도 있어 성공 보장 안 됨 |
| docker-desktop 배포판만 terminate | Linux Engine 및 해당 컨테이너 전체 중단, 비정상 종료 위험. 다른 Ubuntu를 보존하더라도 기존 Anvil state flush를 보증 못함 | 별도 승인 필요. 정상 종료 실패 시 영향 보고 후 검토할 후순위 |
| `wsl --shutdown` 또는 Windows 재부팅 | 위 영향에 더해 Running Ubuntu/Ubuntu-24.04 작업까지 중단 | 더 넓은 별도 승인 필요, 자동 실행 금지 |
| 저장공간 확보 | 현재2.58GiB로 큰 복사/빌드는 부적절. 정확한 삭제 대상·재현 의존성·보존 증거 확인 필요. 공간 확보만으로 API 회복을 보장 못함 | 기존 승인 없는 prune/volume/image/fixture 삭제는 금지. 필요하면 별도 선정/승인 |

**권장:** 진행 중인 Google 사용을 끝내고 보존 서비스 중단을 허용할 수 있는 maintenance window를 먼저 정한다. 사용자가 승인하면 Docker Desktop의 정상 종료/재기동만 제한된 시간으로 시도하고, 실패 시 force-kill/WSL shutdown으로 확대하지 않는다. 호스트 여유 공간 확보도 별도로 검토한다. 승인 전까지는 현재 서비스를 유지한다.

기존 Google/KRX 구성 문서는 persistent Anvil·독립 DB/mount/baseline을 사용한다고 기록하지만, 이번에는 Engine API가 불능이므로 현재 모든 mount와 state flush를 재검증하지 못했다. “데이터 손실이 절대 없다”고 보증하지 않는다. manifest를 고쳐 재시작 성공으로 처리하거나 기준점을 재생성하지 않는다.

## 6. 정상화 후 Q12 재개 절차

1. 제한시간 내 Docker Server 응답을 확인한 뒤 기존 보존 환경의 image/mount/chain baseline identity를 가능한 범위에서 재확인한다. reset/redeploy 없이 확인한다.
2. feat/ai-failure-observability source/corpus/old ledger·STOP·approval 만료·claim·보존 Long10 hash를 다시 RO 검증한다. 현재 승인은 Q12만이며 old RESERVED는 계속 차감한다.
3. 새 continuation claim/owner를 가진 별도 network-none PostgreSQL/pgvector cold copy를 준비한다. 기존 READY volume은 RO로만 복사한다. 신규 JDBC/SCRAM/IndexLease/owner/checksum을 무과금 검증한다.
4. Q12 단일 dry-run과 누적 Guard·신규 local cap을 검증한 뒤 현재 승인 범위의 신규 approval을 만든다. 원본 approval/STOP/ledger/claim을 바꾸지 않는다.
5. LLM1/embedding1 이내로 Q12만 평가한다. 실제 Tool2개/RAG/한국어 답변·정확한 수치·60초 유예·canonical Receipt/Event·journal/보증 한계를 대조해 별도 결과 보고서를 작성한다. 오류/불확실 시 추가 호출 없이 STOP.

현재는 1단계가 BLOCKED다. 이번 조사에서는 실제 AI 요청·새 DB/index·Q14/Q15·Chrome·기존 선물 전체 E2E·원본 병합을 수행하지 않았다. Git commit/push/배포도 없다. 기존 Q12/continuation/실패 이력은 덮어쓰지 않았다.

최종 원본 Git 상태 동일, 기존 보호 파일53개 SHA-256 대조 변경0을 확인했다. 이번 변경은 별도 AI worktree의 본 진단 문서와 implementation-log 누적 기록뿐이다. 기능/절차 코드를 수정하지 않은 읽기 전용 조사 및 문서화이므로 새 독립 코드 검토는 호출하지 않았다.

## 후속 승인된 정상 종료·재시작 시도

사용자공간확보후현재29.25GiB를새로측정했다. 승인된정상stop1회를시도했으나완료미확인으로start하지않았고Q12유료호출0을유지했다. 당시가용2.58GiB는이전관측으로보존한다. collector인코딩오류·CLIdeadline로그·전후서비스영향및미검증항목은[새실행기록](docker-normal-restart-q12-attempt-2026-10-21.md)에분리했다. 강제복구로확대하지않는다.

## 사용자 재부팅 후 후속 결과

사용자재부팅후Docker28.4.0의Server/API가정상화됐고현재C여유48.06GiB를측정했다. 기존Google/KRX는중지상태를자동복구하지않았다. 새Q12격리index의실PG검증후실제LLM1/embedding1을완료했다. 최종품질은PARTIAL이며이전Docker/AI실패를소급수정하지않는다. [복구·자원진단·실제한국어답변·비용·한계](ai-q12-postreboot-real-evaluation-2026-10-21.md)를따른다.
