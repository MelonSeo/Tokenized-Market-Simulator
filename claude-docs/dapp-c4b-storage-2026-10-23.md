# C4b — C 전용 증거·projection 저장소

## 최신 실제 Gate — 신규 PostgreSQL10 PASS

사용자 RAM 확보 후 Anvil Gate와 동시 실행하지 않고, 실제 가용 RAM2,020,921,344bytes·실행컨테이너0을 확인한 다음 승인된 신규 PG1회를 실행했다. `deployment/runtime/c-postgres/cpg-3dfbda7f33414a95/result.private.json`: **PASS/error=null/실제10 tests successful/FAIL·SKIP·ABORT0**, sourceHash `955e3cd88bd9236d361d15705447474c63534275488e8f51556b05598756b82d`, 소유 Java/PG 정상정지. 전용 internal network/no hostports/신규DB·owner·SCRAM/schema/보존 sentinel과 저장·경합·rollback·commit-unknown·append-only 검증을 통과했다. 기존 세 실패 원본은 그대로 보존했다.

예외 추가 독립 검토1회와 PG Gate1회는 모두 사용했다. 제품 코드·후보 하네스를 변경하지 않고 검토된 동결 후보로 실행했다. **C4b 실제 저장 인수는 완료**하되 canonical scanner/체인 원본→DB 전체 연결·reorg 복구·Spring/C웹·전체 감사 MATCH는 아직 미완료다. 아래 NOT_RUN/BLOCKED 기록은 과거 실행 이력이며 새 성공으로 소급 변경하지 않는다.

## 최신 — 사용자 승인 예외 검토 완료 / 실제 PG Gate 자원 대기

2026-10-23 사용자가 **추가 독립 검토1회와 신규 격리 PostgreSQL Gate1회**를 명시적으로 승인했다. `review_c4b_exception`이 ENTRYPOINT 명시/컴파일 whitelist·관련 Python8테스트·PG identity/SCRAM/schema guard·저장소/SQL/PG10테스트와 이전 실패 증거를 직접 대조했고 **발견된 필수 수정 없음**으로 완료했다. 코드·서비스·테스트를 새로 실행한 검토는 아니다. 예외 검토1회는 소진됐으며 다른 작업 상한은 변경하지 않는다.

검토자와 총괄이 `c-projection/20261023T015724Z-73178da2`/`955e3cd88bd9236d361d15705447474c63534275488e8f51556b05598756b82d`의 현재 소스14개/class59개 일치를 확인했다. 검토자는 dependency7개·47PASS/exit0/stderr0 원본도 확인했다. 기존 오프라인 결과를 실제 PG PASS로 재사용하지 않는다. 고정 PG/JRE 이미지 ID와 Linux/amd64도 재확인했다.

이번 호스트 가용 RAM은658,649,088bytes(약628MiB)이며 실행 컨테이너0이다. 자원 여유 없이 새 PG를 시작하지 않았으므로 **승인된 신규 PG Gate1회는 미사용, 실제10개 NOT_RUN**이다. 기존 앱·컨테이너·네트워크를 변경하지 않았다. 사용자가 불필요한 앱을 정상 종료한 뒤 자원을 재확인해 Anvil과 순차 실행한다. 아래 ‘추가 승인 대기’는 보존된 과거 이력이다.

작성 2026-10-23. **구현 후보 / 순수47PASS·PG 실행 전 검토 완료 / 실제 PostgreSQL 테스트 NOT_RUN·하네스 BLOCKED**다. C 계약141PASS와 C4a28PASS를 저장소의 실제 PostgreSQL 성공으로 재사용하지 않는다. 승인 근거와 경제 규칙은 [ADR C-001](dapp-c-policy-adr-2026-10-23.md), 저장 범위는 [계획](dapp-c4b-storage-plan.md)을 따른다.

## 해결할 문제와 구조

C 자산 정본은 ERC-20/Vault다. 이 저장소는 사전 검증된 typed 입력을 읽기 전용 projection으로 남기는 부분으로, DB 잔고를 수정해 온체인 자산을 만들거나 거래를 제출하지 않는다. Spring bean·scheduler·기존 futures/사용자 테이블 FK·기존 DB migration에 연결하지 않았다.

- `CCustodyStateCodec`: 명시적 version1 binary whitelist. 모든 Call/Event를 고정 tag/필드 순서로 보존하며 32byte uint256/int256, UTF-8, bool, map 정렬·중복 키·후행 데이터·payload4MiB·collection4096 제한을 강제한다. Java serialization/default typing/임의 class명은 쓰지 않는다.
- `CCustodyReducer.validateState`: 관측을 조작해 적용하지 않고 복원 snapshot을 기존 불변식으로 검사하는 공개 진입점. 이 검사가 서명·체인 진실성 증명으로 바뀌지는 않는다.
- `CCustodyProjectionStore`: 명시적 DataSource의 순수 JDBC. C 배포 행 `FOR UPDATE`로 writer를 직렬화하고 기대 head와 원본 payload를 확인한다. 최대256건/총 encoded8MiB batch, DB statement5초/lock2초, 자동 재시도 없음.
- `tms_c_custody` 전용 additive SQL: deployments/chain_observations/canonical_epochs/projection_snapshots는 append-only trigger로 UPDATE/DELETE/TRUNCATE를 거부한다. projection_heads만 파생 가변 포인터다. schema owner/superuser의 악의적 DDL까지 방지하는 권한 보장은 아니다.
- 관측·snapshot·head 이동은 한 DB tx다. 실패 시 모두 rollback, commit 응답 유실은 `C_STORE_COMMIT_UNKNOWN`이며 읽기 재조회/동일 payload replay만으로 귀속을 확인한다. 새 거래 생성·자동 전송은 없다. SQLException은 안전한 코드와 SQLSTATE만 노출한다.
- exact 전체 replay만 stale 기대 head를 허용한다. 서로 다른 payload/부분 old+new batch·누락 증거는 거부한다. load는 hash/codec/상태 불변식·원본 관측 bytes를 대조하며 누락 증거를 임의 복구하지 않는다.

## 범위와 실제 인수 구분

초기 저장 API는 epoch1 bootstrap/append/load/explicit empty block에 한정한다. bootstrap은 호출자의 빈 Vault anchor 주장이다. canonical reorg 선택·새 epoch 전환·RPC scanner·전체 Receipt/calldata/서명 검증은 **미구현**이며 별도 [C4c 계획](dapp-c4c-verification-plan.md) 대상이다. `completeCoverage=false`, prevalidated input scope를 그대로 보존하고 감사 MATCH를 반환하지 않는다.

실제 PG 테스트의 가격/거래/Claims는 **typed 통제 fixture**다. PostgreSQL 동시성·trigger·rollback을 검증해도 실제 ERC-20 전송·새 Anvil 거래·canonical Receipt 인수로 표현하지 않는다. 일반 Gradle 전체 backend 회귀나 실제 Spring/API/Chrome 결과도 아니다.

## 실행 도구와 보존 경계

`c-projection-check.py`는 C Java 소스·전용 launcher만 Java21로 컴파일하고 고정 reducer/codec JUnit을 직접 실행한다. 기존 app class를 로드하지 않으며 cached JUnit7개와 소스/class SHA를 기록한다. PG opt-in 테스트는 컴파일만 하고 여기서는 선택하지 않는다.

`c-postgres-check.py <새 offline run>`은 해당 소스·class·의존성 hash를 재확인하고 새 이름/owner label의 internal network, 새 volume, 새 PG와 Java만 생성한다. cached image ID 고정·pull금지·호스트 포트 미게시·Java RO 입력/192MiB heap·최대150초 실행·PG readiness 최대20회/명령별 timeout이다. DB owner/run marker/SCRAM/SQL checksum 확인 전 C schema를 설치하지 않는다. parent fixture나 원본 DB mount는 없다. 실패 자료·DB/volume/network/container는 보존하고 소유 label이 일치하는 새 컨테이너만 정지한다.

기존 실행 중인 컨테이너는 사전 점검에서0개였다. 원본 Git HEAD `f9353c33244565b30fb130dfb6159eab441a3523`, dirty10은 기존 사용자 상태와 일치했다. root/UI/AI/B/감사 제품·기존 OPEN·평가 ledger/STOP/claim을 변경하지 않는다. 유료 호출/배포/commit/merge/push 없음.

## 결과 — 실행 후 누적

- 최초 C4b 직접 C 단위 `20261022T205955Z-2f7f03c4`: **47PASS/0FAIL/0SKIP/0ABORTED**, compile/test exit0, source 불변. aggregate `da4a4a317fe99187c07c4a5f2da33414f34582a2783a68a78f015907f5dd4f42`. 저장소/PG 테스트도 컴파일했지만 실행은 reducer28+codec19만이다.
- 최초 독립 검토는 실제 소스·테스트·SQL·하네스·47PASS/해시 대조 후 **발견된 필수 수정 없음**이다. 검토자의 재실행/서비스 조작은 없다. 실제 PG 완료로 승인한 것이 아니다.
- 첫 실제 Gate `cpg-7764daff1e894cd1`은 **환경 BLOCKED**다. `docker network create --internal`에서 `all predefined address pools have been fully subnetted`가 발생했다. DB/volume/컨테이너 생성 전이며 PG 테스트는 NOT_RUN, 원래 실패 폴더와 로그는 그대로 보존했다. 자원 부재를 확인하는 inspect의 not-found는 예상 사전검사이며 최초 실행 오류와 구분했다.
- 안전한 하네스 보완: 기존 Docker subnet과 Windows의 non-default IPv4 route를 읽어 겹치지 않는 `10.254.0.0/16` 내 `/28` 한 개를 고른다. 기존 네트워크/route/default-pool은 변경하지 않고 새 internal network 생성에만 `--subnet`을 명시한다. 후보 부족/잘못된 inventory는 fail-closed, create 충돌 시 자동 반복하지 않는다. 선택·기존 subnet/route는 신규 private 실행 기록에 보존하고 생성 결과 IPAM도 재확인한다.
- 선택 로직 Python6PASS. 최종 후보 `20261023T002956Z-8c8becf7` 직접47PASS/0FAIL/0SKIP/0ABORTED, compile/test0/source 불변. aggregate `f1cd0683ac21a6a344153f41ce48143c86e9d9599772a145d5b7f4f377287b2e`. 제품 Java/SQL은 첫 후보와 같고 변경은 하네스·소스 증거 범위다. 이 보완의 첫 재검토 대기이며 아직 새 PG 실행을 하지 않았다.
- 실제 신규 내부망 PostgreSQL: 첫 실행 BLOCKED/테스트 NOT_RUN. 다음 실행은 재검토와 현재 자원 사전검사 후 새로운 identity만 사용한다.
- 첫 재검토: 네트워크 보완에서 **발견된 필수 수정 없음**. 현재 메모리1085.8MiB/C27.83GiB·중복 무거운 작업 없음·실행 컨테이너0 확인 후 새 `cpg-899cb42f6f974248`을 실행했다. 새 internal network `10.254.0.0/28`/새 volume/PG 생성은 성공했다. 그러나 schema 설치 전 fixture marker 생성에서 `FATAL: the database system is shutting down`으로 실패했고 Java는 생성하지 않았다. 소유 PG만 정상 정지하고 network/volume/컨테이너·실패 증거는 보존했다. 실제 PG 제품 테스트10개는 여전히 NOT_RUN이다.
- 두 번째 실패의 확인된 원인은 **하네스 readiness 경계**다. 보존 로그에서 init 임시 서버가 Unix socket만 열어 ready를 반환 → DB 생성 → 정상 init shutdown → 최종 서버 TCP listen 순서가 확인됐다. 기존 `pg_isready`에 host가 없어 임시 서버를 readiness로 오인했다. 이는 제품 금융/Claims의 실패도, Windows RPC 오류의 확정 원인도 아니다.
- 보완은 해당 새 PG 검사에 `-h127.0.0.1 -p5432`를 명시해 최종 TCP 서버만 기다리는 것이다. 기다림 횟수/timeout은 늘리지 않고, 컨테이너/DB/user의 동일 신규 identity도 검사한다. Python7PASS, 최종 직접47PASS/0FAIL/0SKIP/0ABORTED `20261023T012823Z-6cc1d50d`, source `cbbaf04ecbc6f50a20ff200c94ad7a143a4efada2f5f3d0189a5d90100a868a9`. 제품 Java/SQL은 처음부터 불변이다. 두 번째(최종 허용) 재검토 전이며 새 PG를 무작정 반복하지 않는다.

### 최종 재검토 후 실제 실행 — 테스트 미기동

두 번째 독립 재검토는 readiness 경계의 원본 로그와 보완 소스/47PASS를 대조해 필수 지적 없음이었다. 최초1회+재검토2회 한도는 여기서 소진됐다. 자원 사전검사(여유 RAM1141.1MiB/C27.79GiB/실행 컨테이너0/무거운 작업0) 뒤 **새** `cpg-f8b5877a3a024035`에서 network/PG/fixture marker 생성은 통과했다.

그러나 Java 컨테이너가 C JUnit 대신 캐시 이미지의 **기존 Spring app ENTRYPOINT**를 시작했다. 실제 이미지 inspect가 `["java","-jar","/app/app.jar"]`, Cmd=null임을 확인했고, 원본 Java 로그는 `ExchangeApplication` 시작→컨테이너 내부 `localhost:5432` 연결 거부였다. 새 테스트 클래스 선택/JDBC guard/제품 C schema 설치 전이다. 따라서 하네스의 `C_PG_TEST_FAILED` 결과를 제품 C PG 테스트10개의 FAIL로 해석하지 않는다. **10개 모두 NOT_RUN, Gate BLOCKED**다. 새 컨테이너 두 개는 정지했고 전체 DB/volume/network/로그는 보존했다. 추가 inspect에서 실행 컨테이너0을 확인했다. 이전 DB·체인을 연결하거나 변경하지 않았다.

최소 후속 수정은 `docker run --entrypoint java <고정 image> -X... CPostgresTests`로 이미지 ENTRYPOINT를 명시적으로 대체하는 것이다. 이 수정과 “기존 Spring ENTRYPOINT를 조합해도 실제 선택이 JUnit인지” 확인하는 회귀를 추가해 **Python8PASS**를 확보했다. 다만 **이 후속 변경은 재검토 미완료**이며, 검토 횟수를 새 이름으로 초기화하거나 실제 PG를 다시 실행하지 않았다. 전체 C4b는 PARTIAL/BLOCKED로 유지한다. 다음 인계에서 본 단위 검토 한도·세 실패 증거·미실행 PG10개를 먼저 확인해야 한다.

정상화된 실제 PG 저장 Gate에 의존하는 Spring 연결/전체 projection 인수는 진행하지 않는다. 반면 C4c-1a의 순수 calldata ABI 해석은 이미 검토된 C2 계약에만 의존하므로 독립 구현·오프라인 검증을 진행한다. 이것을 PG 인수 성공으로 우회하지 않는다.

### 보류 중인 후속 후보의 오프라인 결과

별도 C crypto/ABI가 추가돼도 PG 저장 Gate가 임의로 그 소스를 컴파일하거나 provider 의존성을 로드하지 않도록 runner의 입력을 모델/reducer/codec/store/관련 테스트7개와 두 고정 launcher로 명시했다. 원래 accounting/storage 단위의 의존성 범위를 고정하는 보완이며 제품 Java/SQL 변경은 없다.

ENTRYPOINT 명시·이 whitelist 후보 `20261023T015724Z-73178da2`는 직접 **47PASS/0FAIL/0SKIP/0ABORTED**, compile/test0/source 불변이다. sourceHash `955e3cd88bd9236d361d15705447474c63534275488e8f51556b05598756b82d`. Python8PASS와 함께 보존한다. 이것은 실제 PG 재실행이나 후속 독립 검토 PASS가 아니다. **현재도 C4b PARTIAL/BLOCKED**, 실제 제품 PG 테스트10개는 NOT_RUN이다.

총괄은 검토 횟수 상한의 예외로 이 변경의 추가 독립 검토1회와 신규 격리 PG Gate1회를 사용자에게 비동기로 요청했다. 답변 전에는 이 한도를 임의로 늘리지 않고 다른 독립 C 검증을 계속한다. 기존 실패 fixture/DB를 재시작·재사용하지 않는다.
- C4c canonical 검증/실제 chain reorg/전체 감사, C5 PG+Anvil/실제60초, C6 실제 사용자 지갑, C7 장애 경계: NOT_RUN.
