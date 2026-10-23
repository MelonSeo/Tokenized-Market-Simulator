# C E1a — immutable draft·사용자 동의 저장

## 최신 실제 결과 — E1a PostgreSQL 본시험 6 PASS

이번 resource 보완에 한한 사용자 예외 승인 후 `review_e1a_resource_exception` 추가검토1회에서 필수 지적이 없었다. 검토자는 실제 source13/snapshot13/fixture184 hash와 root/classes 두 SQL의 exact bytes, 제품·SQL 정의 불변을 확인하고 Python10개 및 compiled ClassLoader self-test를 직접 실행했다. 이전 한도를 재사용하거나 초기화하지 않았다.

신규 Gate `deployment/runtime/c-consent-pg/ccpg-54a43006c5954d1e/result.json` / source `2fbdfe8d3d28862fa615563d271252f31d8c83f21b6c6040f84970d97422b3a8`는 **C_CONSENT_ACTUAL_POSTGRES_6_PASS_NOT_SERVICE_OR_CHAIN**이다. `java-evidence/result.json`에서 found6/succeeded6/failed0/skipped0/aborted0를 확인했다. 실제 PostgreSQL에서 schema/SCRAM/최소권한/sentinel, key·사용자/FK/UNIQUE, append-only, 두 독립 연결 경합, rollback, commit 전후 응답 유실 관측의 6개 본시험이 실행됐다. H2나 과거 projection PG10개로 대체하지 않았다.

실행 RAM2,502,713,344bytes/disk31,874,854,912bytes, 19.219초/180초이며 PG·Java Exit0/OOMfalse 정상종료다. source/prepared/runtime fixture불변과 소유 network/volume 존재를 확인했고 새 DB/volume/network/증거를 삭제하지 않았다. 기존 실패 `ccpg-bf266731af0e4514`와 원래 RUN_CLAIM은 그대로 보존했다. 이번 추가검토1/실제PG1 예외는 사용 완료다.

이 결과는 동의 저장 metadata의 PG 인수이며 HTTP/JWT·fresh RPC nonce·실행 준비·실제 체결·MetaMask·전체감사 완료가 아니다. 다음 의존 단위는 C nonce admission이다. 아래 BLOCKED/승인대기는 과거 이력으로 유지한다.

## 이전 준비 완료 — resource 최소 보완10 PASS / 좁은 검토·실행 예외 승인 필요

새 `c-consent-pg-prepare/20261023T103437Z-199cd8bf/result.json` / source `2fbdfe8d3d28862fa615563d271252f31d8c83f21b6c6040f84970d97422b3a8`는 **PREPARED_NOT_POSTGRES**다. compile0/stderr0, 실제compiled `resource()` 무환경/무JDBC/socket self-test exit0·validtrue·stderr0, Python10PASS(0.045s), source/deps불변·root/classes SQL동등true를 확인했다. walletSQL SHA `5b1793a1d7b87126e0138555b146573c530e0f18b618acd31968d1bf202b5bd6`, consentSQL SHA `ba1e172eaf17ecaa62c13750c7067cb0eca58bb3dc41c6a137d11e9a54b81135`로 원본과 같다. Docker/유료 호출0이다.

수정은 PG 하네스3파일의 resource copy/동등 hash·실제 ClassLoader self-test/누락 negative뿐이며 기존 E1a production·H2·SQL·API·경제/권한/거래 정책은 그대로다. 새 self-test를 통과했다고 PG6개 성공으로 바꾸지 않는다. 과거 `ccpg-bf266731af0e4514` 실패·DB·volume/network·원본prepare와 RUN_CLAIM을 변경하지 않았다.

같은 E1a의 최초1+재검토2/actual1회는 사용 완료다. **이 resource 보완에 한한 추가 독립검토1회와 통과 후 신규PG1회 예외**가 필요하며 현재 둘 다 미실행이다. 이전 C서비스 capability/metadata 예외나 C4b 예외를 전용하지 않는다. 승인 후 정확한 다음 실행 후보는 `run 20261023T103437Z-199cd8bf`이며 새source/prepared/claim·RAM1920MiB/disk2GiB/image/owner를 다시 검증한다. 검토나 actual Gate 필수실패는 그대로 BLOCKED다. 기존 C 조회/지갑 연결 실제PASS와 H214PASS는 원래 범위로 유지한다.

## 실제 PG 결과 — BLOCKED / classpath resource 배치 누락 확인

최종검토 필수없음 후 신규1회 `c-consent-pg/ccpg-bf266731af0e4514/result.json` / `02684157746a0a6ca40fc591923bb0cfa8fad5e2336f9e284d1785f7c5aa2cfa`는 BLOCKED다. `java-evidence/result.json`의 첫 실패는 `C_CONSENT_PG_RESOURCE_MISSING`이다. JUnit found6/success0/container setup failure1/skip·abort0이며 6개 본시험은 실행하지 않았다. RAM2,732,957,696bytes/disk32,079,818,752bytes·총14.797초/상한180초다.

소스·prepared fixture 직접 대조로 원인을 확인했다. 원문 SQL2개는 `/fixture`에만 있고 클래스 경로는 `/fixture/classes:/fixture/jars/*`이므로 `CConsentPostgresTests.class.getResourceAsStream('/c-wallet-schema.sql')`이 null이다. 컴파일·Python 테스트 성공이 실제 resource 접근을 보장하지 못했다. 신규 DB owner/SCRAM·공개sentinel/users8 생성 이후 walletSQL 반환 전에 실패했으며 wallet/consent DDL은 미적용이다. H2의 원문table prefix 결과를 이 실패에 대한 실제PG 성공으로 대체하지 않는다.

PG Exit0/OOMfalse 정상종료, Java 실패Exit1/OOMfalse다. `C_CONSENT_NORMAL_STOP_FAILED`는 마지막 cleanup 상태로, PG 강제 종료/불안정이 아니라 JUnit setup실패를 구분해 읽어야 한다. source/prepared/runtimefixture 모두true, network/volume 소유존재 확인·보존·재사용0, 실행컨테이너0·보호156개 변경/누락0·rootdirty10유지다. 원본실패/prepare/RUN_CLAIM은 그대로 보존한다.

최소 보완은 root SQL과 같은 bytes를 classes resource에도 배치·해시대조하고 실제compiled JVM의 무socket resource접근을 준비시 확인하는 것이다. 누락 negative도 추가하되 production/SQL/API/경제/키분리·실행정책은 유지한다. E1a 최초1+재검토2와 실제1회는 사용 완료했으므로 추가독립검토/새PG1회는 좁은 예외 승인 전 미실행이다. 새 offlineprepare는 실제PG 성공이 아니다. 기존read/wallet actualPASS·H214PASS는 각각 원래범위로 유지한다.

## 최신 실제 PG — resource bootstrap 실패 / 최소 오프라인 보완 중

승인된 실제1회 `deployment/runtime/c-consent-pg/ccpg-bf266731af0e4514` / source `02684157746a0a6ca40fc591923bb0cfa8fad5e2336f9e284d1785f7c5aa2cfa`는 **BLOCKED**다. `java-evidence/result.json`은 fixed6 found / success0 / container failure1 / skip·abort0이며 최초 원인은 **`C_CONSENT_PG_RESOURCE_MISSING`**다. BeforeAll에서 owner·SCRAM·새 public sentinel/users8까지 진행했으나 wallet/consent DDL 및6개 시험 본문은 실행하지 못했다. 이는 PG 동시성/append-only/서비스 권한 인수 실패로 구체화할 수 없는 bootstrap 결함이다.

확정 원인은 준비 하네스가 SQL2개를 fixture 루트에만 복사하고, 실제 classpath는 `/fixture/classes:/fixture/jars/*`로 제한한 것이다. `resource()`의 `ClassLoader.getResource('/c-wallet-schema.sql')`는 해당 루트를 찾지 못했다. 컴파일 성공이나 파일 원문 hash 확인이 실제 classloader resource 접근 성공을 보장하지 않았다.

PG는 **정상 stop / Exit0 / OOMfalse**다. Java는 bootstrap 실패 후 **자연 Exit1**이며 강제 종료가 아니다. 최종 wrapper의 `C_CONSENT_NORMAL_STOP_FAILED`는 Java Exit1을 normalStop=false로 집계한 결과이며, 최초 JUnit RESOURCE_MISSING과 구분한다. end core/prepared/runtime hash는 모두 불변이다. 원래 실패 run·claim·prepared·새 PG volume/network/증거는 보존하고 재시작·재사용하지 않는다. 실제 read/wallet Gate PASS와 H214 PASS는 각각 원래 범위로 유지한다.

검토 최초1+재검토2 및 실제 PG1회는 모두 사용 완료됐다. 현재 허용된 보완은 **새 prepare의 SQL2개를 정확한 classpath classes 디렉터리에도 복사**하고 원문/복사 hash를 대조하며, 실제 compiled tester의 `--resource-self-test <fixture>`에서 같은 `resource()` 호출을 무소켓·무JDBC·환경변수 조회 없이 확인하는 것뿐이다. fixture 루트를 classpath에 통째로 추가하지 않는다. missing/path/copy negative 순수 Python 회귀를 추가한다. production/core/SQL/경제 정책·권한·키분리·timeout은 변경하지 않는다. 추가 독립 검토/실제 PG 재실행은 승인되지 않았으며 작성자는 실행하지 않는다. 아래 ‘실행 중’은 보존된 과거 시점이다.

최소 보완 후보3파일을 동결했다. Java tester SHA `519e51e49767a61663aef079b5a3157bf1248cb258f7b7f14d0a753cbbd58c9e`, runner `9b67435229f2bd1620a444e05959cafa7ae5830961ccc7cd3caf87da859fba8e`, Python 회귀 `dbf0a193e78164c57a83f9cea8cb13fb66dfb457fe2fedf42211bd94c0c05722`다. prepare는 snapshot/root/classes SQL복사본의 hash를 대조하고 실제 compiled classloader self-test의 exit0/stderr0/정확한두SHA·scope JSON을 요구한다. Python 회귀는10개로 작성했으며 아직 실행하지 않았다. 새 prepare 명령은 동일하게 `python deployment/c-consent-postgres-check.py prepare 20261023T101729Z-24a8b6eb`이고 총괄만 실행한다. 이 변경은 오프라인 resource 접근 방어일 뿐 실제 PG6 PASS·추가 검토 완료 또는 재실행 승인이 아니다.

## PG 준비·마지막 독립 검토 완료 — 신규 실제 실행 1회 진행

`c-consent-pg-prepare/20261023T102752Z-48c7e49a` / source `02684157746a0a6ca40fc591923bb0cfa8fad5e2336f9e284d1785f7c5aa2cfa`는 compile0/stderr0·순수Python9 PASS(0.029s)·source/deps불변인 PREPARED_NOT_POSTGRES다. 고정 PG JUnit6은 준비 단계에서 실행하지 않았다.

`review_c_consent_store` 마지막재검토2/2는 **발견된 필수 수정 없음**이다. 현재source13/snapshot13/fixture182/JAR147 hash불일치0, E1a입력11/기존Gate34변경0을 직접 확인했다. 새로운 internal network/owner/권한/SQL/동시성·rollback시험, 정제오류·동등cap표기·실측ENTRYPOINT·timeout unknown-created cleanup·단일180초·전후hash·정상정지·보존조건을 대조했다. 실제Docker/PG/JUnit6은 검토자가 실행하지 않았다. 같은 단위 최초1+재검토2 상한을 모두 사용했으며, 이름 변경으로 초기화하지 않는다.

실행 직전 자원 읽기는 RAM3,048,747,008bytes·disk32,163,696,640bytes·실행컨테이너0이었다. 현재소스13개불일치0 및 미사용RUN_CLAIM 확인 후 `run 20261023T102752Z-48c7e49a` 새1회를 실행했다. 아직 실제 결과 확정 전이며 과거 준비/검토를 실제PG PASS로 승격하지 않는다.

## 구현 검토 확정 — 필수 수정 없음 / 실제 PG 보완 준비

`review_c_consent_store` 구현 후 재검토1/2는 **발견된 필수 수정 없음**이다. 신규7파일과 기존 계약/crypto/identity/wallet SQL을 읽고, 실행 source/resource/runner11개·class37개 hash 불일치0 및 기존 실제 read/wallet Gate34개 변경0을 직접 확인했다. 테스트 재실행/Docker/DB/RPC/파일수정은 하지 않았다. 이 단위는 최초 설계1회와 재검토1회를 사용했으며 최종 재검토1회가 남는다.

후속 검증은 새 PG+Linux 시험JVM만으로 고정6개 JDBC/JUnit을 수행하는 작은 Gate다. 신규 tester/prepare-run runner/순수Python회귀3파일만 추가하고 기존 C4b/live Gate/helper를 변경하지 않는다. frozen E1a소스·클래스·실물wallet/consent SQL을 hash로 바인딩한다. 내부 전용network/새owner·API role/SCRAM·sentinel·권한·append-only/FK·동시draft/consent·rollback·commit-unknown 관측을 검증한다. Anvil/서버/host port/실제 사용자키/자산/유료 호출은 필요 없다. 새하네스 오프라인 준비 후 같은 단위 마지막 재검토를 완료하고 자원≥1920MiB/2GiB·이미지/소유권을 확인한 뒤 신규1회만 실행한다. 현재 실제PG는 아직 NOT_RUN이다.

## 최신 — 오프라인14 PASS / 구현 후 독립 검토 중

총괄 단독 실행 `deployment/runtime/c-consent-store/20261023T101729Z-24a8b6eb/result.json`의 source `d094ad7622ace598cd72e0430ffe4b8f167d636005449ad767f14126bc692d18`은 **C_CONSENT_H2_OFFLINE_PASS_NOT_POSTGRES**다. 직접 compile/test exit0, 고정14개 PASS/FAIL·SKIP·ABORT0, compile/test stderr0bytes, source/dependency/실제 classpath schema resource 불변이다. 유료/Docker/RPC 호출0이다. `review_c_consent_store` 구현 후 재검토1/2를 요청했으며 아직 최종 결론 전이다. 아래 NOT_RUN·예정 문구는 최초 작성 당시 기록이다.

실제 PostgreSQL의 DDL/append-only trigger/권한/동시성, HTTP 서비스·새 실행 Report/nonce admission·Sender·revision/raw·체결·MetaMask·전체감사는 여전히 NOT_RUN/미연결이다. H2 두 worker 결과를 PG 성공으로 승격하지 않는다. 기존 실제 read/wallet Gate와 계약·금융·보호자료는 변경하지 않았다.

작성 2026-10-23. **현재 구현 후보 작성 완료 / 컴파일·테스트 및 구현 독립 검토 NOT_RUN**. 총괄의 최초 설계 독립 검토는 필수 수정 없음으로 완료됐으며 구현 후 재검토 최대2회는 아직 미사용이다. 총괄이 순차 실행을 담당한다. C read/wallet 실제 Gate `c-live-6f3298d68b9b4fa6` PASS는 이 신규 저장소의 인수 결과가 아니다.

## 범위와 결정

E1 전체를 한꺼번에 구현하지 않고 `drafts`와 `consents` 두 테이블만 추가했다. 고정 identity/Clock/DataSource를 받는 `CConsentStore`가 서버 호출자의 userId와 실제 users/wallet link를 같은 연결의 사용자 row lock 아래 대조한다. **이 단위에는 HTTP/JWT 인증·API 연결이 없다.** 요청 body userId를 신뢰하지 않는 API 경계는 후속 작업이다.

기존 C22필드 Intent와 digest/EOA 검증기를 재사용한다. C accountRef prefix·controller domain·chainId·relayer/symbol/policy·nonzero bytes32 ID·사용자 action0/1/4·유한 가격/수량 범위·로컬 최대120초 window를 검증한다. 가격과 수량 상한은 기존 FuturesMath 상수만 사용하며 새로운 금융 계산을 만들지 않는다. 계약과 동일하게 issuedAt<=현재초<=deadline, deadline-issuedAt<=120이다.

**현재 체인 nonce, position 권한/version/epoch, Oracle·시장 신선도, 금융식, 자산 충분성 또는 실행 가능성은 검증하지 않는다.** 이 책임은 다음 서비스가 실제 fresh RPC/시장 입력으로 다시 확인해야 한다. 서명 기록의 scope는 `USER_SIGNATURE_RECORDED_NOT_PREPARED_OR_EXECUTABLE`이며 PREPARED 상태를 생성하지 않는다. 기존 wallet schema/core/API/계약/금융·Gate는 수정하지 않는다.

## 파일과 저장 API

- `backend/src/main/java/com/pricetrack/exchange/custody/authorization/CConsentModels.java`: immutable 결과, key fingerprint와 request hash 분리, C domain digest/accountRef 및 정적 범위 검증.
- 같은 디렉터리 `CConsentStore.java`: `createDraft(uid,key,intent)`, `findByKey(uid,key)`, `findDraft(uid,operationId)`, `findConsent(uid,operationId)`, `acceptConsent(uid,operationId,expectedDigest,signature)`.
- `backend/src/main/resources/c-consent-schema.sql`: 새 `tms_c_execution.drafts/consents`, FK/UNIQUE/CHECK, PostgreSQL UPDATE/DELETE 거부 trigger.
- `backend/src/test/java/com/pricetrack/exchange/custody/authorization/CConsentModelsTest.java`, `CConsentStoreTest.java`: 고정 **14개**(Models4/Store10).
- `deployment/c-consent-store-check.py`: 명시 source snapshot/캐시 third-party JAR/SHA/JDK21/제한 heap·timeout/직접 JUnit. Docker·서비스·네트워크 실행 없음.

`installSchema()`는 Spring에 연결하지 않는다. 새 소유 DB owner만 명시 호출하며 PostgreSQL만 허용하고 이미 `tms_c_execution` schema가 있으면 거부한다. 기존 wallet link `(deployment_id,user_id)` FK를 사용하고 기존 schema를 변경하지 않는다. 현재 link owner는 매 쓰기마다 재대조한다.

## 불변식과 실패 동작

1. 고정 deploymentId는 chain/genesis/controller·rules·Vault·token 주소/codeHash/역할/domain을 포함한다. C operation/position은 bytes32를 그대로 사용한다.
2. raw Idempotency-Key는 저장하지 않고 ASCII8~128자만 받아 별도 SHA-256 fingerprint를 저장한다. 동일 key+동일 Intent만 기존 draft를 반환하며 조건 변경은409다. 만료된 동일 요청도 **과거 기록 조회**로만 반환하고 기한을 늘리지 않는다.
3. 외부 EOA 서명은 DB lock 전에 실제 C digest/recover로 검증한다. lock 아래 exact draft/digest/current link를 재대조한다. 신규 수락 시 한 번 capture한 시각을 window 검사와 recordedAt 양쪽에 사용한다.
4. 동일 digest/정규화된 동일 signature 재전달만 과거 consent를 반환한다. 다른 유효 signature도 기존 기록을 교체하지 못한다. 원문 signature는 private DB에만 저장하고 public Consent/toString에는 없다. 실제 Jackson/JavaTime 직렬화로 scope·시각과 signature 부재를 검증한다.
5. 두 테이블은 append-only이며 asset balance/예약/house/journal/operation execution state를 만들지 않는다. nonce 영구 UNIQUE/입장 예약도 아직 없다. 후속 서비스가 pending nonce admission을 명시적으로 설계해야 한다.
6. SQL 실패는 rollback·안전한 오류 코드만 반환한다. commit 응답이 불확실하면 `C_CONSENT_COMMIT_UNKNOWN`이다. 자동 재시도·새 key/operation·전송은 없으며 같은 key 조회가 먼저다. commit 성공 후 응답 유실과 commit 전 실패를 별도 주입 시험으로 구분한다.

## 검증 계획 및 정직한 경계

예정 명령은 C worktree에서 `python deployment/c-consent-store-check.py`다. 총괄만 단독 실행한다. 아직 실행하지 않았으므로 PASS가 아니다.

순수/Mock/H2 고정14개는 fingerprint/scope/실제 JSON·Instant·서명 비노출, inclusive window/future issuance, bounds·잘못된 scope, 동일 key 재조회와 충돌/사용자 격리, 실제 EOA 서명·다른 owner/chain/controller 거부, 만료 이후 신규 수락 거부와 기록 조회, 서로 다른 유효 signature 불변, 단일 clock capture, INSERT 실패 rollback, commit 전후 불확실·조회 우선, 두 H2 worker 중복 차단을 검증한다. 공개 시험 EOA 서명은 실제 MetaMask 인수와 다르다.

H2 fixture는 기존 wallet link의 최소 PK/FK/UNIQUE 모양과 새 SQL의 table prefix만 설치한다. PostgreSQL trigger 문법/실효·least-privilege role·실제 PG 잠금/동시성·SCRAM은 **NOT_RUN**이다. H2 동시성 성공을 PG 인수로 표시하지 않는다.

다음 실제 PG Gate는 신규 소유 DB에서만 schema·append-only UPDATE/DELETE·FK/UNIQUE·동시 요청·rollback·기존 sentinel/권한을 확인한다. Anvil/가격 Report/사용자 키 또는 전체 C Gate 반복은 필요 없다. 실제 Gate는 이 구현 검토 및 무과금 검증 이후 별도 순차 작업이다.

## 후속 경계

다음 단위가 fresh nonce/position·권한·금융식과 제품 Synthetic 적격 가격을 확인하고 실행 Report·Oracle 서명을 생성한다. 그 이후 append-only revision0/1/head와 SIGNED raw 영속화, Sender·canonical Receipt/Claims·복구를 별도 단위로 연결한다. 현재 consent 저장소를 실행 승인 완료 또는 Vault 자산 정산으로 취급하지 않는다. 기존 성공/실패 fixture·DB/Anvil·AI 증거·사용자 미커밋 파일은 건드리지 않는다. commit/merge/push/유료 호출은0이다.

## 실제 PG Gate 후보 — 소스 작성 중 / 실행 NOT_RUN

새 파일은 `deployment/CConsentPostgresTests.java`, `c-consent-postgres-check.py`, `test_c_consent_postgres_check.py` 3개뿐이다. 기존 E1a production/test/schema 및 기존 C4b/read Gate/helper는 수정하지 않는다. `c_gate_network.select_subnet`의 순수 충돌 검사만 그대로 호출하고, 예전 projection용 `CPostgresTests`와 전체 하네스를 복제하지 않는다. 새 prepare/run의 최종 source/hash/명령은 오프라인 준비 완료 후 기록한다.

고정 actual PostgreSQL 시험6개는 (1) 새 schema 설치와 재설치 거부·SCRAM·least-privilege·sentinel, (2) exact key/사용자 격리와 실제 UNIQUE/FK, (3) owner UPDATE/DELETE trigger55000 및 API role 권한42501/TRUNCATE 거부, (4) 두 독립 연결의 같은 draft/consent 동시 저장, (5) 새 fixture 전용 AFTER INSERT 실패 trigger의 rollback, (6) commit 전 실패/실제 commit 후 응답 유실의 동일 key 관측이다. 새로운 DB의 users/link/sentinel을 매 시험 뒤 확인한다.

원문 `c-wallet-schema.sql`과 E1a `c-consent-schema.sql`을 hash 확인한 동일 resource로 적용한다. wallet link는 공개 시험 EOA의 **통제된 PG fixture seed**이며 새 EIP191 소유 증명 성공으로 표현하지 않는다. fixed trial deployment identity/Clock도 실제 계약 배포·RPC·체인 신선도 또는 현재 nonce의 증거가 아니다. private owner/API password는 새 env 파일만 사용하고 Java 결과에는 정제한 testcase/type/SQLSTATE만 남긴다.

새 `ccpg-*` internal network와 PG volume, PG256MiB 및 Linux Java384MiB/heap192/meta128만 사용한다. 최소 가용 RAM1920MiB/디스크2GiB, 전체180초, heavy Gate 순차 조건은 유지한다. fixed image/no-pull/no-host-port 및 PG의 검증된 rootfs RW·기본 user·정확1개 NET_RAW/CAP_NET_RAW drop, Java readonly/nonroot/capdropALL을 확인한다. PG readiness는 `pg_isready -h 127.0.0.1` TCP로 최종 서버를 기다린다. 종료 Exit0 또는143/OOMfalse/error없음을 확인하고137은 거부하며 소유 volume/network/실패 증거는 삭제하거나 재사용하지 않는다.

먼저 새 prepare에서 frozen E1a 실행 source/class/dependency/resource와 신규 tester/runner를 바인딩해 컴파일·순수 Python만 확인한다. 같은 단위에 남은 마지막 독립 재검토1회가 이 하네스를 포함해야 하며, 필수 지적이 없고 자원·image·identity 사전조건을 충족한 경우 총괄이 신규 실제 PG1회만 실행한다. 작성자는 Java/Python 테스트·Docker·PG·RPC·서비스를 실행하지 않았다. prepare 성공을 actual PostgreSQL PASS로 표시하지 않는다.

### 후보 동결 — 신규3파일 작성 완료 / 준비 실행 대기

고정 PG JUnit6개와 순수 Python9개를 작성했고 아직 실행하지 않았다. 실제 다른 owner/chain/controller 서명 거부를 기존 PG시험 안에 포함했다. 서로 다른 유효 signature 교체 거부는 기존 H214의 검증으로 남겨 두며, 미실행 PG 성공으로 보고하지 않는다. bootstrap 실패에는 testcase/type/SQLSTATE와 정확한 `C_CONSENT_PG_[A-Z0-9_]+` 오류 코드만 보존하고 SQLException 원문은 남기지 않는다.

총괄 자체 점검에 따라 docker run timeout 후 실제 생성됐을 수 있는 두 정확한 소유 이름을 finally에서 관측한다. started 성공목록만으로 정리 대상을 판단하지 않는다. owner 일치가 확인된 자원만 정지하며 daemon 오류를 미존재로 처리하지 않는다. 성공에는 Java/PG 모두 정상 종료가 필요하다. 시작/끝 소스·prepared fixture·runtime fixture와 신규 manifest hash를 대조하고 불일치는 BLOCKED다. 작업150초/청소여유30초를 **동일 총180초 deadline** 안에 두며 정리에서 시간을 새로 부여하지 않는다. 보존 의도(NO_DELETE)와 실제 network/volume 존재·owner 확인 결과를 구분한다.

PG 고정 이미지의 `Entrypoint=["docker-entrypoint.sh"]`, `Cmd=["postgres"]`는 총괄이 읽기 전용으로 실제 확인했다. 새 guard는 두 필드를 정확히 검사한다. 동결 SHA-256:

- `CConsentPostgresTests.java`: `a42b5657c52c67e6e4ba1940658401af3d564fa449ad6250402161d2fe676fb7`
- `c-consent-postgres-check.py`: `f65e057f6421adeb9eb2fc64a2f4152c4fb35bbd1efdb945089ce67f17a5caf8`
- `test_c_consent_postgres_check.py`: `88d466b45e2768cbe1c64fdcacb908cfda68ab51754c5f813a78bc690f7e28a8`

총괄 단독 준비 명령은 `python deployment/c-consent-postgres-check.py prepare 20261023T101729Z-24a8b6eb`다. 실제 실행은 새 prepared identity와 최종 독립 검토·자원 Gate 이후 `run <prepared-id>`이며, 현재는 Java compile/순수 Python/실제 PG 모두 NOT_RUN이다. 기존 E1a9개 Java 소스/SQL·11개 입력identity, H214 증거 및 기존 Gate/helper는 불변이다.
