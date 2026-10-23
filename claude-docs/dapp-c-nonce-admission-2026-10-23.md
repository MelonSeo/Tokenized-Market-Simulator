# C E1b — nonce admission 및 단일 기준 블록 실행 전제조건

## 최신 구현·오프라인 검증 — 55 PASS / 최초 독립 검토 필수없음

`review_c_nonce_admission` 최초독립검토는 **발견된 필수 수정 없음**이다. 현재 source23/snapshot23/class62와 최종55PASS 및 기존조회53PASS의 hash일치, 기존 E1a 입력11개 불변을 직접 확인했다. 검토자는 테스트를 직접 재실행하지 않았다. 실제 nonce PG/RPC/HTTP와 SIGNED·해제 lifecycle의 미완료 경계는 유지한다. 이 독립단위 최초검토1회 사용, 후속 수정 재검토 최대2회이며 과거 예외를 전용하지 않는다.

`deployment/runtime/c-nonce-admission/20261023T105714Z-f76ee6ce/result.json` / source `9b8bce39e3d1a8351a62d235235005848831daf495f892954d3d6c732c4523a0`는 **C_NONCE_ADMISSION_OFFLINE_PASS_NOT_PG_OR_EXECUTION**이다. 직접 javac/test exit0, 고정55개 found/started/success, fail·skip·abort0, source/JAR/두SQL classpath자원 불변이다. consent14+identity/reader12+새 admission reader15/store14이며 Mock RPC와 H2만 사용했다. 기존 `c-service-read-check.py`에는 Reader의 새 FuturesMath 의존 source1줄만 추가했고 `c-service-read/20261023T105628Z-b0827ada` / `a46a32a7bf013e06ae5521da6a8aa404c2be55e80324a88a8329dad74d950b69`의 기존53개도 PASS했다. 이 회귀는 현재 소스의 실제 HTTP/PG/RPC PASS가 아니다.

최초 `20261023T105519Z-e201874e`는 55개 자체성공이나 실행중 테스트 수정으로 sourcesUnchanged=false여서 FAILED_OR_BLOCKED로 보존했다. 동결 후 `105620Z-f11142ab`은55PASS였다. 이후 저장JSON `null`/내부null 손상을 안전 `STORED_SCOPE`로 정규화하고 같은 시험의 corruption case를 확장한 최종 실행이 위 `105714Z-f76ee6ce`다. 기존 증거를 덮어쓰지 않았다.

reader는 같은 blockHash의 owner nonce, operation 사용여부(rules/Vault), OPEN 상태·pause·unpaid·free, TOP_UP/CLOSE owner·version·epoch·entry·quantity·담보·lock, 자산 보존을 확인한다. OPEN 정상경로40RPC이며 기존64회/15초/개별3초 제한을 지킨다. user Intent window는 서버Clock과 chain timestamp 모두 적용한다. store는 사용자 row lock 밖 RPC, 안쪽 exact wallet/consent/digest/window/관측경과 재확인, durable nonce UNIQUE, exact replay/재시작·서로 다른 operation 경합·rollback·commit unknown 관측을 검증했다. 공개 API는 caller가 만든 observation을 받지 않는다.

실제 새 PG의 nonce schema 권한/append-only/경합, 실제 RPC admission, HTTP 연결 및 실행 Report 이후는 **NOT_RUN**이다. E1a PG6 PASS와 현재 H2 PASS를 이 범위의 실제 인수로 대체하지 않는다. 기존 live Gate의 frozen hash guard는 수정하지 않았으므로 과거 actual PASS가 새 Reader 소스 검증으로 승계되지 않는다. 현재 단위 최초독립검토를 요청할 단계다.

## 구현 범위

E1a 실제 PG6 PASS 이후의 독립 실행 준비 단위다. 저장된 사용자 동의와 연결된 durable `(deployment,owner,userNonce)` 슬롯을 기록하고, 동일 기준 블록의 C 계약 nonce·포지션·Vault 상태를 조회한다. 사용자 승인이나 금융 정책을 새로 만들지 않는다. Report/revision/SIGNED/전송·복구/웹은 후속 범위다.

`CCustodyRpcReader.admissionPrestate`는 기존 EIP-1898 hash pin·identity/codeHash/domain/roles·header recheck를 재사용한다. 기존 64회/전체15초/개별3초 제한을 유지한다. OPEN·TOP_UP·CLOSE의 가격과 무관한 전제조건만 조회하며 현재 시장가격, 실제 실행 수량·손익, Report30초 및 시장5초 freshness 검증은 별도다. 사용자 Intent120초 window와 서버 Report의 미래2초 허용은 서로 다른 규칙이다.

공개 store는 고정 reader를 통해 직접 관측한다. 외부 caller가 임의 snapshot을 만들어 입장시키는 API를 제공하지 않는다. RPC는 DB lock 밖에서 수행하고, DB 트랜잭션 안에서는 실제 wallet·exact consent·digest·window를 재검증한다. 기존 E1a 제품/SQL을 변경하지 않고 additive schema만 추가한다. 원본 사용자 서명은 공개 응답이나 로그에 포함하지 않는다.

슬롯은 동의만 저장한 상태와 구별되지만 PREPARED 또는 실행 보장은 아니다. 같은 operation의 조회는 이력 관측이고 재승인·기한 갱신이 아니다. 다른 operation의 동일 nonce 입장은 차단한다. 이 단위에서는 해제·교체 API가 없으며 단순 만료/취소 제출/nonce 증가만으로 자동 해제하지 않는다. 이로 인한 unsigned 실패 후 liveness 제한은 후속 operation lifecycle에서 SIGNED 저장과 동일 lock/CAS 경계로 해결해야 한다. 자동청산 nonce와 사용자 nonce는 계약처럼 분리된다.

## 검증 계획

직접 JDK21 명시 source 목록과 캐시 third-party JAR만 사용한다. compiled ClassLoader SQL 자원 확인 후 기존 consent14개/identity·reader 회귀 및 새 pinned prestate/nonce store 테스트를 실행한다. H2·Mock 결과는 실제 PG 권한/append-only/경합 또는 체결 PASS로 표시하지 않는다. 검증 후 별도 독립 검토(최초1+재검토 최대2)를 수행한다. 본 단위의 검토 한도는 과거 E1a resource 예외와 별개이며 기존 변경의 지적을 숨기는 명칭 변경이 아니다.

기존 C 서비스 HTTP Gate·C5a 계약 Gate·E1a PG 증거와 보존 환경은 수정하거나 재실행하지 않는다. commit/merge/push/유료 호출/배포/전역 재시작/데이터 삭제는 없다.
