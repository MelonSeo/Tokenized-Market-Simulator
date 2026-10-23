# Q12 실제 모델 평가 승인 후 사전검사 — Docker Server 응답 차단

> 2026-10-21. 판정 **BLOCKED (유료 전송 전)**. Q12 답변 품질은 **NOT_RUN**이다.

## 1. 승인 범위와 실행 identity

사용자는 고정 Q12 “내 Long 포지션은 왜 강제청산됐나요? 기록된 근거로 진단해주세요.”를 명시 Skill `futures-liquidation-diagnosis`로 평가하도록 승인했다. 모델은 gpt-5.6-terra / text-embedding-3-small, 신규 LLM1/embedding1, input32,768/output1,600/embedding1,032, 신규 최대 예약 US$0.10114064, 이전 실행 포함 최대 US$0.40314604, 자동 API 재시도0이다.

- 작업: 기존 별도 `feat/ai-failure-observability` worktree. HEAD `f9353c33244565b30fb130dfb6159eab441a3523`.
- 현재 sourceHash `86dcb3cef1b41a98f761a7c53286b252aca4f8ba8cfab6738729ab330de07fa8`.
- corpusHash `c106ed6e152602dd56a48c855316ad23fd4bb563ffc099755aabcbb7794067ba`.
- [continuation 준비](ai-q12-continuation-preparation-2026-10-21.md)의 최종 코드 identity와 일치한다. source가 바뀌지 않아 기존 무과금 직렬화 capture를 다시 만들지 않았다.
- **신규 continuation run/claim, AI DB/index, 유료 approval 파일은 생성하지 않았다.** 따라서 보고할 새 실행 UUID나 DB owner는 없다.

원본 부모 `eval_7a8f25154337449e9f49d676b4d5fbb7`, 기존 child `eval_d55c916420ae4a28a37a0ef11b1b2e6f`의 실패 이력은 보존한다. 본 문서는 [Q5/Q6 복구 조사](ai-agent-recovery-investigation-2026-10-21.md), [기존 실제 평가](ai-q2-16-real-quality-evaluation-2026-10-20.md)를 대체하거나 소급 수정하지 않는다.

## 2. 이번에 실제 확인한 사전검사

| 항목 | 판정 | 실제 확인 내용 |
|---|---|---|
| 현재 branch/HEAD/dirty | PASS | 기존 미커밋 진단·continuation·fixture 변경 유지. 제품 수정/커밋/병합 없음 |
| source/corpus identity | PASS | 준비 보고서 및 v3 capture와 일치 |
| root/child ledger 및 lineage | PASS | SQLite read-only로 exact identity·비용·기존 claim·approval 종료·STOP·hash 검증 |
| Q6 RESERVED 승계 | PASS | 불확실 예약1개 포함. 무료/미호출로 제외하지 않음 |
| 보존 Long10 증거 | PASS | 기존 snapshot SHA-256이 v3 capture와 일치 |
| 단일 continuation claim | PASS | 직전 child에 대한 새 continuation claim 미사용. 이번에도 생성하지 않음 |
| Q12 실제 직렬화 dry-run 산정 | PASS | 기존 최종 capture의 두 profile을 현재 `assess`로 재검증 |
| Docker version Server | BLOCKED | 제한된 재확인에서 12초 후 `subprocess.TimeoutExpired` |
| Docker info Server | BLOCKED | 12초 후 `subprocess.TimeoutExpired` |
| 신규 AI PostgreSQL/pgvector/JDBC/SCRAM | NOT_RUN | Docker Server gate 실패로 생성·연결하지 않음 |
| RO cold copy/새 IndexLease/owner/checksum | NOT_RUN | 기존 DB/volume/image 조회·복사도 시작하지 않음 |
| 신규 승인 파일 및 키 참조 | NOT_RUN | 전송 단계에 도달하지 않아 키 파일을 읽지 않음 |

실제 측정 명령은 각각 `docker version --format '{{json .Server.Version}}'`, `docker info --format '{{json .ServerVersion}}'`이며 Python `subprocess.run(..., capture_output=True, timeout=12)`로 한정했다. 공개 오류는 timeout이다. 현재 실행에서 Engine HTTP500이나 ENOSPC를 관측한 것은 아니다.

앞선 직접 `docker version` 조회도 응답하지 않아 해당 명령의 docker.exe PID와 전용 부모 명령을 대조한 후 **이번 진단 CLI만** 취소했다. Docker Desktop, WSL, daemon, 기존 컨테이너를 재시작/종료하지 않았다. 제한시간 wrapper 초안은 셸의 다중 줄 처리로 Python 구문 오류가 나서 실행되지 않았고, 한 줄 명령으로 바꿔 위 두 timeout을 확인했다. 이 구문 오류를 제품·RAG·모델 실패로 분류하지 않는다.

정확한 Docker 하위 원인은 미확정이다. “Server 응답을 확보하지 못해 새 DB 검증을 안전하게 시작할 수 없음”까지만 확인했다. daemon/VM/호스트 원인 중 하나를 추측하여 확정하지 않는다. 무한 재시도나 전역 재시작으로 우회하지 않았다.

## 3. 예산과 호출 결과

| 구분 | LLM | embedding | input 예약 | output 예약 | embedding 예약 | 무환급 예약 USD |
|---|---:|---:|---:|---:|---:|---:|
| 보존 부모+child 누적 | 11 | 37 | 87,575 | 6,800 | 73,395 | 0.30200540 |
| **이번 신규 실제 사용/예약** | **0** | **0** | **0** | **0** | **0** | **0** |
| 현재 전체 누적 | 11 | 37 | 87,575 | 6,800 | 73,395 | 0.30200540 |

신규 usage, 모델 응답, 신규 실제 청구 토큰은 없다. US$0.10114064는 승인된 상한이지 지출 또는 이미 예약한 금액이 아니다. Q6의 불확실한 input15,490/output1,600/US$0.057925는 과거 누적에 계속 포함한다.

기존 v3 dry-run을 이번 source에서 판정한 결과 두 profile 각각 LLM1/embedding1, input19,063/output1,600/embedding140, 보수 예약 US$0.06686030이다. **실제 요청/토큰 사용이 아니라 로컬 직렬화 상계 산정**이다. 실제 Guard의 더 큰 승인 local cap은 바꾸지 않았다. Q1·검색 golden·전체 색인·기존 선물 E2E를 반복하지 않았다.

## 4. Q12 실행 및 답변 품질 상태

| 단계 | 판정 | 근거 |
|---|---|---|
| 실행 중 사용자/포지션 권한 검사 | NOT_RUN | 신규 live harness 미시작. 기존 offline 권한 PASS와 구분 |
| getFuturesPosition | NOT_RUN | 이번 Tool 실행 결과 없음 |
| getFuturesLiquidationEvidence | NOT_RUN | 이번 Tool 실행 결과 없음 |
| 실제 scoped RAG 및 embedding | NOT_RUN | 새 index 미준비, 유료 요청0 |
| 한국어 LLM 종합 및 인용 | NOT_RUN | 응답 없음 |
| 수치/산식/60초 유예/canonical/journal 대조 | NOT_RUN | 답변 자체가 없어 품질 PASS/FAIL 부여 불가 |
| 실제 웹 AI 통합/Chrome | NOT_RUN | 승인 범위 밖 |

**최종 한국어 모델 답변과 인용은 확보되지 않았다.** 사전검사 설명이나 과거 fixture 답변을 이번 모델 답변으로 대체하지 않는다. RAG·Java/Python HTTP 단계별 latency 역시 live harness를 실행하지 않아 새 로그가 없다. 관측된 latency는 Docker CLI의 12초 timeout 두 건뿐이다.

## 5. 과거 장애 이후 개선 효과와 한계

이번 source에서는 원본 실패 기록과 불확실 비용을 보존한 상태로 RO ancestor 검증, 단일 claim 미사용, immutable evidence 및 직렬화 상한을 실제로 재확인했다. 개선된 preflight 경계가 **DB를 확인하지 못한 상태에서 유료 요청으로 진행하는 것을 막았다**.

반면 추가한 retrieval/transport 관측의 실제 API 효과와 Q5/Q6 재발 여부는 검증하지 못했다. Q5의 RAG 하위 실패 지점, Q6 원격 처리 여부는 여전히 미확정이다. 이번 장애는 모델이나 RAG 실패가 아니라 **새 환경 구축 전 Docker Server gate 실패**다.

OpenAI Docs의 [지정 모델 공식 문서](https://developers.openai.com/api/docs/models/gpt-5.6-terra)를 읽었으나 사용자 계정의 현재 모델 접근 권한이나 실제 inference 성공을 증명한 것은 아니다. 모델 대체, 프롬프트·금융 정책·제한시간 변경은 하지 않았다.

## 6. 보존 및 재개 조건

- 원본 melon-init HEAD/status 유지, 보존 파일 **53/53 SHA-256 동일**. 원본 사용자 변경과 UI worktree, 부모/child ledger·STOP·approval·manifest·claim·증거를 유지했다.
- 키/JWT/개인정보/원문 인증 오류를 출력하지 않았다. 기존 DB/Anvil/Google/KRX/NXT 환경, Docker 컨테이너/volume/image/network는 변경하지 않았다.
- 이번 새 변경은 이 결과 문서와 별도 worktree의 implementation-log 누적 기록뿐이다. Git commit/merge/push/배포 없음.
- 재개 전 Docker Desktop Linux Engine이 사용자 및 에이전트 양쪽에서 제한시간 내 Server 응답을 반환해야 한다. Docker/WSL 전체 재시작이나 저장공간 정비가 필요하다면 별도 영향 확인/승인 없이 실행하지 않는다.
- Docker 정상화 후 현재 승인 범위에서 source/corpus/ancestor/evidence/claim을 다시 대조하고 **새 격리 cold-copy fixture**의 실제 PostgreSQL/pgvector/JDBC/SCRAM/IndexLease/owner/checksum 검증 후에만 Q12를 실행한다. 현재 실제 continuation claim·새 ledger·approval은 없으므로 이번 시도로 소비된 새 슬롯은 없다. 원본 child claim은 계속 보존한다.
- 이후 Q12가 실패하면 자동 재시도/모델 변경/새 child/예산 재부여를 하지 않는다. Q14/Q15, 실제 웹 시연, 원본 병합은 별도 승인 전 실행하지 않는다.

**발표 판정: 실제 AI 청산 설명 시연 권장은 아직 보류.** 검증된 선물/자동청산/체인 감사 데모와 AI 답변 품질을 분리한다. 실제 Q12 한국어 답변을 확보해 사람이 원본 decimal·조건·확정 근거와 대조하기 전에는 라이브 또는 성공 영상으로 소개하지 않는다.

## 후속 재부팅 후 실제 Q12 실행

이번문서는재부팅전BLOCKED/유료0회기록으로보존한다. 사용자재부팅후별도의신규continuation에서실제LLM1/embedding1과한국어답변을확보했다. Tool14facts/원본산식대조는일치했으나최종Agent및종합품질은PARTIAL이다. [새실행결과와정확한예약·미검증범위](ai-q12-postreboot-real-evaluation-2026-10-21.md)에기록했고과거STOP/RESERVED는변경하지않았다.
