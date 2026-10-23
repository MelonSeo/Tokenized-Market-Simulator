# 승인된 Docker 정상 종료·재시작 시도와 Q12 인계

> 2026-10-21 KST. **정상 종료 완료 미확인 / 재기동 NOT_RUN / Q12 BLOCKED.**

## 1. 승인과 보존 범위

사용자는 Docker 서비스의 일시 중단과 **정상 종료·재시작 1회**를 승인했다. 정상 종료가 불가능하면 강제 복구로 확대하지 않고 중단하도록 했다. Q12 단일 유료 승인(LLM1/embedding1, 신규예약최대US$0.10114064, 과거포함최대US$0.40314604, 자동재시도0)은 유효하지만 사전조건 충족 시에만 실행한다.

기준은 [Docker 진단](docker-server-q12-diagnosis-2026-10-21.md), [Q12 차단 보고서](ai-q12-real-evaluation-2026-10-21.md), [continuation 준비](ai-q12-continuation-preparation-2026-10-21.md)다. 이번 결과를 과거 실패나 승인 기록에 덮어쓰지 않는다.

## 2. 실행 전 실제 확인

| 항목 | 결과 |
|---|---|
| C 드라이브 가용 공간 | **29.25GiB**, 전체476.31GiB. 과거2.58GiB를 현재 값으로 재사용하지 않음 |
| Docker Server | `docker version --format '{{json .Server.Version}}'`가12초timeout. 자연 정상화 확인 안 됨 |
| Docker 관련 프로세스 | backend7212/14960, build19020 및 기존 Desktop PID13532/13564/14300/15544 존재 |
| WSL 관련 프로세스 | 기존wsl/wslhost/wslservice 프로세스 존재. 배포판/서비스 종료 명령 없음 |
| Google 웹 | localhost5174 정적루트 HTTP200 (3초timeout설정). 로그인/거래/DB변경없음 |
| 진행 중 평가 프로세스 | Java/Python 평가 관련 프로세스 발견 안 됨 |
| 원본 사용자·UI·평가 증거 | 보호53파일 SHA-256 대조 변경0. 원본HEAD/status 동일 |

저장공간은 개선됐지만 **실제 원본 READY PostgreSQL volume 크기와 두 개의 신규 cold/data 복사 필요량을 Engine API 불능으로 측정하지 못했다**. 벡터88개×1536×4bytes는약0.516MiB의순수float payload일뿐이며 PostgreSQL base/WAL/색인/원본volume전체 크기를 대표하지 않는다. 따라서29.25GiB를 이유로 전체복사 공간충분PASS를선언하지않았다. 실제복사를시작하지않았다.

## 3. 정상 종료 실행 — 정확히1회

공식 절차인 `docker desktop stop --timeout 45`를 **--force 없이1회** 실행했다. [Docker 공식 stop 명령](https://docs.docker.com/reference/cli/docker/desktop/stop/)에 따라 정상종료 요청과 명령 대기시간을 제한했다. 바깥 diagnostic collector는55초timeout을설정했다.

정상 종료 완료를 확인하지 못했다.

- collector에서기본Windows CP949 text decoder가Docker CLI의UTF-8출력을읽다가 `UnicodeDecodeError`를냈고,그후수집결과의None을슬라이스하려다TypeError가발생했다. **이는collector오류이며Engine실패원인으로혼동하지않는다.** 해당wrapper종료코드는1이나Docker stop명령자체의정확한returncode/원문출력은확보하지못했다.
- 같은 시도 시간대 Docker Desktop CLI 로그 끝 최대128KiB의정제분석에서 `context deadline exceeded`2건을관측했다. 마지막추출시각은2026-10-21T02:51:29Z부근이었다. `Failed to stop Docker Desktop`문구자체는이로그범위에서관측0이며임의로원문오류를추가하지않는다.
- 정상 종료 후 없어져야 할 Desktop/backend/build가 **기존 PID 그대로 존재**했다. 종료 완료나 새프로세스시작을확인하지못했다.
- 실제 Google5174 정적루트는시도전·후모두HTTP200이었다. 이는웹응답유지의증거이지모든컨테이너·DB정합성확인의증거는아니다.
- 이후Server최종조회1회도10초timeout이었다. 해당조회는bytes로수집해collector의CP949문제를피했지만Server무응답은동일했다.
- 마지막프로세스조회에서이번stop CLI/plugin이나평가프로세스가남아있지않음을확인했다. **정상종료명령을다시실행하지않았다.**

따라서Docker가완전히종료됐다는전제없이start/restart명령을추가하지않았다. 실제시도수는 **정상stop1 / start0 / force0 / WSL종료0** 이다. 승인된재시작1회를완료한것으로표시하지않는다.

## 4. 기존 환경 상태와 확인하지 못한 부분

| 항목 | 판정 | 근거/한계 |
|---|---|---|
| 기존Google정적웹 | PASS | 시도전후200. 인증·API·WS·DB검증은아님 |
| 원본Git/보호자료 | PASS | 기존dirty유지,보호53hash동일 |
| Desktop정상종료 | FAIL/완료미확인 | 기존PID유지,CLI로그deadline,Server계속timeout |
| Desktop재기동 | NOT_RUN | 종료확인실패로시작하지않음 |
| 기존컨테이너inventory/volume/mount/network | BLOCKED | 관리API불능으로전후대조불가 |
| 기존PG·Anvil·immutablebaseline재검증 | NOT_RUN | API불능,준비/재배포/reset없음 |
| 신규AI DB/index및coldcopy크기검증 | NOT_RUN | 새fixture/claim생성전중단 |

기존 persistent mount와상태를삭제/재생성한행위는없지만,관리API를읽지못했으므로모든기존데이터가정상이라는포괄적보존PASS를선언하지않는다. 원본파일SHA검증과DB/체인내부정합성을구분한다.

종료시측정한C가용공간은 **28.85GiB**였다(12:06:38 KST). 동시Windows/기존서비스활동이있을수있어차이를이번DB복사나Docker재시작이사용한용량으로귀속하지않는다. 이번작업에서별도대규모빌드/복사를수행하지않았다.

## 5. Q12 실제 평가 상태

- 작업worktree: feat/ai-failure-observability, HEAD `f9353c33244565b30fb130dfb6159eab441a3523`.
- sourceHash `86dcb3cef1b41a98f761a7c53286b252aca4f8ba8cfab6738729ab330de07fa8`, corpusHash `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`, 기존준비와일치.
- 신규continuation/run/ledger/approval/DB/index생성0. 원본STOP/RESERVED/claim/사용량을변경하지않았다.
- 신규LLM **0**, embedding **0**, 신규예약 **US$0**. 과거누적예약US$0.30200540유지. 현재미확정Q6비용을환급하지않는다.
- Tool2개/실제RAG/실제LLM/한국어답변/수치·유예·canonical·journal품질대조는모두 **NOT_RUN**. 새RAG/HTTP관측로그도없다.
- Q14/Q15·웹AI실인수·전체선물E2E·원본병합·push/배포없음. 이번에모델·질문·프롬프트·금융정책·timeout을수정하지않았다.

## 6. 원인과 다음 경계

**확정:** 저장공간을확보한후에도Server가응답하지않고정상종료완료를확인하지못했다. 수집기인코딩오류와별개로기존프로세스/Server상태는그대로다. **미확정:** Desktop/backend/Engine내부의어느대기·교착·IO문제가원인인지,과거저장공간부족과인과가있는지.

현재사용자지침대로대기한다. 강제종료/WSLshutdown·terminate/Windows재부팅/초기화/삭제/다시stop을자동실행하지않는다. 추가복구는별도사용자결정이필요하다. 정상종료가확인되지않은상태에서재기동을겹쳐실행하지않는다.

환경이정상화되면제한시간내version/info의Server응답→기존환경identity/보존검사→실제원본volume크기와가용공간비교→새ROcoldcopy/JDBC/SCRAM/IndexLease/owner/checksum→현재승인범위Q12만순서로재개한다. 실패증거와원본STOP을바꿔성공처리하지않는다.

이번작업은운영복구시도와문서화이며제품/harness코드는변경하지않았다. 코드변경에대한새독립검토는필요하지않아호출하지않았다. 기존결과문서에는이문서링크만누적한다.
