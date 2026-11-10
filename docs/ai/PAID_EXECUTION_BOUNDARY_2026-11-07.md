# PAID v2 실행 경계 준비 — 2026-11-07

## 판정

**PARTIAL: 전송 경계·일정 coordinator와 Linux 주소 경로는 준비/표적 검증. PAID 전체 실행 준비 완료는 아님.**

기존 무과금 HTTP51/C3/반복12/Chrome6 PASS를 재실행하지 않았다. 실제 모델 품질, 공급자 접속, 계정 모델 권한 및 현행 가격은 NOT_RUN이다. 실제 공급자 API(메타데이터 포함), 키 읽기, 승인 활성화, Commit/Push/Merge/배포는 0이다.

이번 소스 변경은 Python 평가 실행 경계에 한정한다. 기존 제품 Java·웹·금융·인증·Timeout·PG 비용 정책과 동결 JSON/질문/기준을 변경하지 않는다. 준비된 일부 실행 부품을 전체 인수 PASS로 표현하지 않는다.

## 기존 UNPAID와 PAID의 차이

| 경계 | 기존 무과금 실행 | 준비한 PAID 경로 |
|---|---|---|
| 원장 | UNPAID immutable allocation | 새 PAID_V2 allocation만; 과거 원장 전환/복사 금지 |
| 승인 | 없음, 통제 sender | source/fixture/PG authority/path/기간에 결박한 별도 permit |
| DB 주소 | Linux `pg:5432` opt-in | PG namespace 공유로 `127.0.0.1:5432`, 주소 검사 확대 없음 |
| 외부 통신 | internal network only | 앱은 internal 유지, 별도 relay만 outbound 연결 예정 |
| 공급자 | 통제 모델·합성 벡터 | 고정 `api.openai.com:443` CONNECT + 종단 TLS 인증 |
| 비용 | shadow PG + SQLite | PG 선예약 + 동일 SQLite 예약 COMMIT 후에만 키/전송 |
| 품질 판정 | 연결성 assertion | 기존 질문/기대 근거와 실제 의미 대조 필요; HTTP200/PARTIAL만으로 승인 불가 |

`ai_pilot_paid_schedule.PaidSchedule`은 기존 `PilotHttpPlan`을 재사용해 두 색인, 6/30/3/12/6요청을 하나의 PAID allocation에서 실행한다. adapter의 실제 PG snapshot/금융 fingerprint/증거 검토/DOM 제출이 모두 필요하다. 요청·색인·DOM 응답은 assertion/의미 검토 전에 새0600/배타파일로 fsync하며 검토 결과도 별도 보존한다. 요청 credentials/headers는 기록하지 않고 파일당2.1MB를 넘으면 중단한다. 미완료 제출이 있는 세션을 자동 재실행하지 않고, 예외·검토 거절이면 원장을 STOP한다. 마지막 Embedding 여유1은 호출하지 않고 종료한다. 단위시험의 adapter는 명시적 double이며 실제 Spring/Chrome PAID 인수가 아니다.

## 고정 공급자 전송

- `PaidSender`는 기존 permit/UNKNOWN/요청 identity 검증 뒤에만 새 `paid_transport`를 만든다. 키 읽기 위치와 PG→SQLite 순서는 그대로다.
- 앱은 인터넷 경로가 없는 internal network에 둔다. PG의 network namespace를 공유하므로 Windows 포트 중계나 PAID JDBC 정규식 완화가 없다.
- 별도 nonroot relay는 정확한 CONNECT 대상만 허용한다. 기본은 deny-all이며 DNS/socket dial도 실행하지 않는다. 승인 후에만 별도 outbound network와 명시적 network capability를 사용한다. 이 capability 자체는 비용 승인이나 두 예약의 대체물이 아니다.
- CONNECT 뒤의 TLS는 앱이 공급자 hostname/certificate를 검증한다. proxy 환경변수·redirect·자동재시도는 사용하지 않는다. 메타데이터 경로는 sender에서 거절한다.
- relay는 동시2, 대기열2, 헤더4096byte, tunnel60초, nonroot/cap-drop/read-only로 제한한다. 앱은 기존 더 짧은 요청 deadline을 유지한다. 독립 초기 검토가 기존 무제한 DNS·connect 예산 재부여를 가짜150ms DNS로 재현했다. 이를 `-I/-B/-S` 최소환경 소유 DNS child로 분리하고 남은 monotonic 예산으로 communicate/connect한다. 기한 초과는 child kill/reap(정리 대기 최대0.5초)하며 실제 provider DNS는 호출하지 않았다. dummy sleep child 종료, DNS 후 connect잔여0.25초, 만료 시connect0/단일시도 회귀를 통과했다. 실제 resolver/TLS 지연은 승인 후 관측할 항목이다.
- transport 오류 후 예약은 UNKNOWN/STOP으로 남는다. 전송 여부가 불명확한 예약을 임의 환불하거나 자동 재전송하지 않는다.

## 무과금 검증과 증거

새 `pilot-paid-cdc5634f21f99140`에서 기존 고정 이미지의 실제 ID만 사용했다. PG384MiB, 기본 거절 relay64MiB, 일회 Python 진단 컨테이너를 생성했다. 앱이 PG namespace에서 PostgreSQL protocol 응답을 받고 `pilot-egress` CONNECT가403으로 거절되는 것을 확인했다. PG 실제 SQL SELECT1도 성공했다. outbound network/키 mount/Anvil/금융 fixture/모델 호출은 없었다. 소유3컨테이너 모두 STOPPED, 신규 빈 PG 볼륨과 소량 증거는 보존했다.

RAM은 시작3010.90/생성직전3010.06/종료3011.88MiB, 디스크 약38GiB로 기존1920MiB/2GiB Guard를 충족했다. 이 수치는 작은 topology 진단의 샘플이며 전체 서버+Chrome 동시 실행의 자원 인수를 대체하지 않는다.

앞선 `pilot-paid-73470eb42195237a`는 CREATE에서 중단·컨테이너0이었다. 원래 stderr를 보존하지 않아 정확한 실패 코드는 미확정이다. 이후 기존 route/subnet inventory로 명시적 빈 subnet을 선택한 실행이 통과했다. 이 결과만으로 첫 실패를 주소 풀 고갈로 확정하지 않는다. blueprint도 두 명시적 /28 subnet을 요구하고 기존 route와의 충돌 검사는 실제 생성 직전에 다시 해야 한다.

개인정보 최소화: 새 probe는 작은 고정 schema만 출력하고 Docker/공급자 임의 예외문구, properties, JWT, 체인 state 원문을 출력하지 않는다. relay는 TLS를 해독하거나 payload를 기록하지 않는다. 이전 불필요한 시험JWT/대형 합성 state 출력의 사실은 기존 보고서에 보존하며 이번 자료에 원문을 복사하지 않는다. 로그 최소화가 모든 과거 출력의 삭제·절대 무유출을 뜻하지 않는다.

## 실행 전 남은 조건 — 자동 활성화 금지

1. blueprint의 `/PREPARED` 자리표시자는 실행 명령이 아니다. 새 fixture를 만들고 금융 writer를 동결한 뒤 bound fixture identity를 준비해야 한다. 기존 UNPAID 원장을 PAID로 변경하지 않는다.
2. PAID server/DOM adapter의 실제 결박은 미완료다. 기존 `PilotV2LiveHttpTest`/Chrome resume는 UNPAID 기동·결과 메타데이터를 고정하고 있다. 그대로 mode만 바꿔 유료 실행하거나 일반적인 `review=True`로 품질을 자동 승인하면 안 된다. 새 schedule의 callbacks를 실제 PG/HTTP/같은 Sandbox DOM 및 의미 검토에 연결한 최종 무과금 시험이 필요하다.
3. `topology()`는 자원/주소/이미지/권한의 blueprint이며 turnkey 전체 평가 런처가 아니다. `guarded_launch()`도 permit 선검사 경계일 뿐, source copy/fixture/원장/종료를 검증한 전체 supervisor는 아니다. 현재 preview는 계속 BLOCKED다.
4. 공급자 DNS/TLS/모델 entitlement/가격 확인은 이번 승인 경계 때문에 미실행이다. 실제 요청이나 키를 사용하기 전에 별도 사용자 승인이 필요하다. 미승인 모델 이름을 임의의 다른 모델로 대체하지 않는다.
5. 기존 `ai_pilot_preflight.py check`의 plan-only exit2/ready=false 및 정책 `activationAllowed=false`는 그대로 유지한다. 실제 실행 증거와 오래된 static blocker 문구를 구분한다.

따라서 지금은 **유료 실행을 승인만 받으면 즉시 실행할 수 있다고 보고하지 않는다.** 남은 adapter/supervisor 연결은 추가 유료 호출 없이 검증할 수 있는 작업이다. 본 기록은 완료한 전송/격리 준비 범위와 미완료 전체 런처를 명시적으로 구분한다.

표적 무과금 회귀는 schedule6/새경계17/기존sender8/coordinator16/HTTPplan2/Linux14/공통broker15=**78 PASS**, 환경변수 변경 관련 기존unpaid_live6을 포함하면 **84 PASS**다. 이 합계는 이번에 직접 실행한 서로 다른 시험만 센 것이며 기존Java/Chrome PASS를 더하지 않았다. 실제 Linux topology는 별도1회PASS이며 DNS 보완 후에는 새 DNS 경계 회귀만 실행했다. 공개후보의 비밀/개인경로 regex검사0건은 Gitleaks와 동등한 전면탐지 또는 절대 무유출 보장이 아니다. 원본25worktree HEAD·보호8파일 내용SHA 불변/HEADd52b29f/staged0을 새 durable manifest로 비교했다.

최종 독립 재검토1: 발견된 필수 수정 없음. 검토자는 새경계17/schedule6/diff-check를 직접 실행했고, DNS child/잔여connect/응답0600 fsync/허용환경값만조회를 코드로 대조했다. 실제 Docker/공급자/PG원장/TLS를 독립 재실행하지 않았다. 초기 DNS 지적의 재현과 수정 사실을 보존하며, 검토 통과가 미완료 PAID 전체 adapter를 완료로 바꾸지 않는다. 환경값 최소화 보완은 기존 `items()`를 실제 키파일/저장소 접근이나 유출로 단정한 것이 아니다. 현재는 제외 환경값을 materialize하지 않도록 구현·시험했다.

## 제안 비용 — 승인/현행 가격 확인이 아님

동결 v2 제안은 US$18 총상한, LLM92/Embedding70회다. 입력6,123,520/출력147,200/Embedding4,659,200토큰, call wire65,536byte+framing1,024/출력1,600 상한이다. 동결 단가로 계산한 보수 최대 예약은 **US$17.168384**, 여유는 **US$0.831616**이다. cache hit/벡터복사/실제 미사용을 사전 절감으로 가정하지 않는다. 이 금액은 계정 청구서·세금 또는 현재 공급자 가격 검증 결과가 아니다. 단가/모델/할증 조건이 확인되지 않으면 전송하지 않는다.

기존6+30+3+12+웹6=57 Agent 요청, 신규 색인2회(6+6 Embedding), 단계별 상한은 동결 계획 그대로다. 실제 지출 목표가 아니라 최대치이며 과거 승인/STOP/불명 예약과 별도다. 기존 원장·예약을 초기화하거나 합산하지 않는다.

## 기술 학습 연결

이 사건은 'timeout·주소 검사를 넓히지 않고 실행 topology로 localhost authority를 유지', '네트워크 capability와 과금 authorization 분리', '원장 COMMIT과 외부 부작용 사이의 UNKNOWN 유지', '연결 PASS와 의미 품질 PASS 분리' 사례다. 대안인 PAID `pg:5432` 무조건 허용과 앱 전체 인터넷 연결은 각각 authority 확대·egress 범위 증가 때문에 채택하지 않았다. GraphDB/모델 교체/금융 변경은 하지 않았다.

면접 질문: TCP CONNECT 중계와 TLS 종단 검증은 왜 다른가? 네트워크가 차단됐다는 사실만으로 비용 예약을 환불할 수 있는가? PG namespace 공유가 인증 완화를 뜻하지 않는 이유는? mock 전체 일정 통과가 실제 서버/품질 인수를 대체하지 못하는 이유는? 승인 전 검증 불가능한 경계는 어떻게 정직하게 표시하는가?
