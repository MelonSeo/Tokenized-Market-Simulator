# C4b 저장 단위 계획 — C 전용 JDBC evidence / projection / checkpoint

작성: 2026-10-23. **C4a 28PASS·독립 검토 완료 후 C4b 구현 중; PostgreSQL 실행 NOT_RUN.** 아래는 구현 전 수립한 범위와 검증 계획이다. 실제 결과는 별도 결과 문서에 기록한다. C 계약 Foundry 성공과 C4a의 순수 Java 성공을 PostgreSQL 인수로 승계하지 않는다.

## 1. 독립 단위와 책임

C4b의 첫 단위는 별도 Spring 컴포넌트가 아닌 `custody.store`의 순수 JDBC 저장소, 전용 codec, C-only SQL 및 테스트다. RPC·signer·transaction sender·FuturesLedger·WalletService를 의존성으로 받지 않는다. 입력은 이미 검증됐다고 가정하는 C4a `Deployment / State / Bundle`이며 **저장 성공이 canonicality·서명·완전한 감사의 증명이 아님**을 같은 scope로 유지한다.

기존 `FuturesIntentStore`의 `FOR UPDATE`와 짧은 DB-only 트랜잭션, `FuturesSchema`의 PG append-only trigger, 기존 PG 경합 테스트의 barrier/timeout 방식은 개념만 참고한다. 기존 B 금융 테이블, 전체 worktree, Phase-A의 Windows 포트·Anvil 필수 가정을 복사하지 않는다.

최소 신규 파일 후보:

- `custody/store/CCustodyStateCodec.java`: versioned deterministic 명시적 binary 형식, BigInteger 보존, 타입 whitelist.
- `custody/store/CCustodyProjectionStore.java`: 명시 DataSource/JDBC, 원자 저장·replay·checkpoint lock.
- `resources/c-custody-schema.sql`: 아래 C-only namespace.
- codec 단위 테스트와 실제 신규 PG 저장소 테스트. 초기에는 Spring 자동 등록·scheduler·API 없음.

후속 서비스 연결 때만 `app.c-custody.enabled=false` 기본 OFF 구성을 추가한다. 현재 사용 중인 futures flag를 C의 활성화 조건으로 재사용하지 않는다. DB 설정을 받았다는 이유만으로 기존 schema를 자동 초기화하지 않는다.

## 2. 저장 구조와 불변/가변 경계

전용 PostgreSQL schema `tms_c_custody`를 우선한다. 모든 SQL은 schema-qualified하며 기존 `public.users`, `user_balances`, B 선물/감사 테이블과 FK 또는 UPDATE를 만들지 않는다. SQL은 C의 새 테이블·함수·trigger만 만들고 재적용 시 기존 C 행을 seed/reset하지 않는다.

| 테이블 후보 | 성격과 키 | 의미 |
|---|---|---|
| `deployments` | append-only, `deployment_id` PK | C4a Deployment 정규 payload·SHA-256·codec version; 동일 ID 다른 identity 거부 |
| `chain_observations` | append-only, `(deployment_id, block_hash, tx_hash)` PK | 한 tx Bundle 전체와 ordered log, 정규 payload·hash; reverted/no-op tx도 보존 |
| `canonical_epochs` | append-only, `(deployment_id, epoch)` PK | 선택 시작 anchor 및 이전 epoch/공통 조상 참조, 선택 이유/입력 scope. 과거 fork 삭제 없음 |
| `projection_snapshots` | append-only, `(deployment_id, epoch, revision)` PK | 완전한 bounded State payload·hash·cursor; DB 장애/재시작 replay 기준 |
| `projection_heads` | 가변, deployment별 한 행 | 활성 epoch/revision/snapshot hash/커서에 대한 FK와 동시성 잠금 행 |

관측은 typed prevalidated Bundle이며 raw ABI topics/calldata·codeHash 증거를 확보한 것처럼 표현하지 않는다. C4a Deployment에 없는 token/Vault/controller/rules codeHash와 실제 배포 Receipt는 C4c identity envelope에서 추가한다. 초기 anchor의 빈 Vault 역시 caller의 bootstrap assertion이지 실제 배포 검사 PASS가 아니다.

Bundle 안의 ordered logs는 payload에 모두 보존하므로 별도 log 행 없이도 최초 저장 단위를 만들 수 있다. 향후 raw log evidence 테이블을 추가할 때 `(deployment,blockHash,txHash,logIndex)`를 사용하고 기존 typed payload를 원시 로그로 재명명하지 않는다.

행은 같은 deployment의 FK로만 연결한다. owner 주소는 실제 자산 소유자의 별도 식별이며 JWT userId나 B operation ID FK가 필요하지 않다. 직접 owner 거래에 서버 raw/revision이 없다는 이유로 저장을 거절하지 않는다. operation/position/claim은 State 안에서 C4a reducer의 귀속·중복·보존 검사를 따른다.

append-only 표에는 UPDATE/DELETE/TRUNCATE 거부 trigger를 둔다. heads는 파생 가변 포인터이므로 그 trigger를 적용하지 않는다. DB 관리자에게 악의적 변경 방지까지 보장한다고 주장하지 않는다. trigger/constraint 오류도 트랜잭션 전체 rollback 대상이다.

## 3. 직렬화와 수치 정확성

일반 Java serialization, Jackson default typing, 클래스명 기반 임의 polymorphism은 사용하지 않는다. 구현 선택은 의존성을 늘리지 않는 DataInput/Output의 명시적 version1 binary 형식이다. 고정 Call/Event tag whitelist·정확한 필드 순서·엄격 UTF-8·bool·알 수 없는 tag·trailing bytes 거부를 둔다. 아래 JSON 후보 설명은 이 binary 선택으로 구체화했다.

- BigInteger는 정확한 32byte uint256 또는 int256 two's complement다. 범위를 검증하고 double/long로 축소하지 않으며 가변 부호/길이의 대체 인코딩을 허용하지 않는다.
- State의 map은 키 정렬된 entry 시퀀스로 쓴다. 특히 복합 `TxKey`를 문자열 key로 손실 변환하지 않는다. Bundle/log/transaction 순서는 원본 순서를 유지한다.
- 정확한 record 필드·enum/tag를 명시한다. writer의 field 순서도 고정하여 같은 State를 map 삽입 순서와 무관하게 동일 bytes로 만든다.
- payload 최대 4MiB, UTF-8 문자열 최대 1,024 bytes, collection count 최대 4,096 및 reducer의 row/Bundle/log 한도를 함께 강제한다. 저장 batch는 최대 256개, 인코딩 누적 최대 8MiB이며 하나씩 인코딩하고 한도를 확인한다. recursive JSON graph나 동적 클래스 탐색이 없다.
- SHA-256은 저장 bytes의 손상/충돌 확인용이지 서명이나 chain 진실성 증명이 아니다. read 시 hash → 엄격 decode → reducer 상태 검증 → canonical re-encode 비교를 수행한다.
- C4a가 아직 공개 standalone state validator를 제공하지 않으면, 구현 전에 검증 진입점의 최소 추가 필요성을 검토한다. 예전 관측 하나를 임의 apply해 검증을 대신하거나 payload를 무검증 신뢰하지 않는다.

초기 codec 테스트는 배포·모든 Call/Event subtype, signed 손실·uint256 최대·18 decimals 큰 값·1wei, empty/terminal/Claims State, 무작위 map 순서와 unknown tag/중복 필드/초과 크기/손상 hash를 포함한다.

## 4. append와 checkpoint 원자성

저장 API는 caller가 기대하는 head identity를 받고 한 deployment의 bounded ordered batch를 저장한다. 각 호출은 새 Connection, 명시 transaction, 제한된 query/lock timeout을 사용한다. 자동 DB 재시도는 넣지 않는다.

1. deployment와 identity payload를 읽고 완전 일치 확인.
2. `deployments ... FOR UPDATE`로 같은 deployment의 저장을 직렬화한다. 초기 생성 경합도 deployment PK/같은 row lock으로 직렬화한다. 가변 heads는 이 잠금 안에서 기대 revision/hash를 조건부 UPDATE한다.
3. 기대 epoch/revision/cursor/hash와 현재 head를 대조한다. stale writer는 거절한다. **단, 전체 batch가 이미 동일 payload로 저장되고 현 State에 반영된 명백한 replay면 no-op**으로 반환할 수 있다. 일부 old + 일부 new batch의 stale 기대값을 묵인하지 않는다.
4. observation key 충돌 시 기존 bytes/hash와 완전 비교한다. 동일 payload만 replay, 같은 key 다른 payload는 명시 conflict다. `ON CONFLICT DO NOTHING`만으로 충돌을 숨기지 않는다.
5. 잠근 현 State에서 reducer를 적용한다. cursor ancestry/transaction/log 순서, owner/Claims/FIFO/cash/debt는 reducer가 검증한다. empty block도 별도 명시 입력이며 “실제 scan에 tx 없음”을 증명한 것으로 기록하지 않는다.
6. 신규 observations, append-only 새 snapshot/revision, head 이동을 같은 transaction에서 commit한다. 필수 insert/검증/head update 중 하나가 실패하면 전체 rollback한다.
7. commit 응답이 유실되면 결과를 UNKNOWN으로 분류한다. chain 전송이나 추가 금융 동작 없이 새 connection에서 동일 기대 identity/batch를 읽어 committed/replay 여부를 확인한다. 중복 free/claim 귀속은 금지한다.

테스트 실패 주입은 connection wrapper의 `commit()` 직후 예외와 특정 INSERT/UPDATE 전 예외 등 새 fixture에만 적용한다. SQLException 메시지 전체나 접속 암호를 보고하지 않고 안전한 단계·SQLSTATE·error class만 기록한다.

초기 입력 상한을 넘는 State/관측은 저장 실패로 유지한다. 과거 관측을 제거해 한도를 우회하거나 무한 batch 확장하지 않는다. snapshot이 관측을 포함하는 C4a 모델의 중복 공간 비용은 작게 제한된 최초 Gate에서는 감수하되, 운영 규모 최적화를 완료했다고 주장하지 않는다.

## 5. 재시작과 reorg 범위

저장소 재시작은 head가 참조한 immutable snapshot/hash를 읽고 같은 identity와 codec/reducer 조건을 재검증한다. 같은 tx를 다시 읽어도 원래 observation과 같은 payload면 현 State를 바꾸지 않는다.

C4a reducer는 canonical fork를 선택하지 않고 같은 State 내 txHash 재포함을 금지한다. 따라서 **C4b 최초 append 저장 PASS를 reorg 완료로 보고하지 않는다.** 첫 단위에서는 epoch 1 bootstrap과 append/restart를 먼저 인수한다. fork 전환 API가 없다면 그 항목을 NOT_RUN으로 남긴다.

후속 epoch 전환은 C4c가 확인한 bounded 공통 조상 snapshot과 canonical block 선택을 입력으로 받아 새 epoch를 append하고 새 branch를 replay한 뒤 head만 원자 전환해야 한다. orphan observations/snapshots는 남긴다. 다른 blockHash의 동일 txHash는 전역 evidence 이력에는 존재할 수 있지만 새 branch State의 canonical observation 집합 안에서는 한 번만 반영한다. 임의 caller가 현재 잔고를 seed하거나 기존 snapshot을 덮어써 MATCH를 만드는 복구 함수는 제공하지 않는다.

## 6. 실제 PostgreSQL Gate와 자원 경계

신규 소유 전용 internal Docker network에 PostgreSQL과 Java 21 JVM만 둔다. **호스트 `-p` 미게시, Anvil 불필요**, cached image/read-only 필요한 artifacts만 사용하며 기존 DB/container/network/volume/원본 worktree는 사용하지 않는다. PG identity marker, database/role/owner, network membership, manifest/sourceHash/class artifacts를 확인한 뒤 schema 생성한다.

기존 PhaseAFixture의 host `127.0.0.1:port`, Anvil identity와 futures database whitelist를 C용으로 완화하지 않는다. 대신 신규 작은 C fixture identity guard를 별도 검토한다. 같은 Windows 포트 게시 장애를 다시 만들 이유가 없다.

예상 작은 실행: Java heap 약 198MiB, PG cap 128MiB 후보, worker 2개 경합 한 번씩 bounded, statement/lock/connect timeout 명시. 실제 host 메모리/디스크·기존 컨테이너 상태가 충분하지 않으면 환경 BLOCKED다. 두 자원 상한은 검증된 충분치가 아니므로 OOM/기동 실패를 제품 결함으로 단정하지 않는다. 전체 회귀/무거운 빌드를 겹치지 않는다. 종료는 소유 컨테이너만 정지하고 실패 증거와 새 DB를 보존한다.

필수 실제 PG 검증:

1. 최초/반복 additive SQL, 기존 별도 sentinel 테이블/행 불변; 새 deployment 같은 payload replay/다른 payload 충돌.
2. UNIQUE/FK/CHECK 및 append-only UPDATE/DELETE/TRUNCATE 거부, heads 정상 이동.
3. deposit→lock→CLOSE/Claims→partial repayment→withdraw의 C4a typed bundle 저장·load/restart 일치. chain/RPC 검증 아님을 명시.
4. 동일 observation replay no-op, key 동일 payload 상충 차단, tx 순서/owner/금액·Claim 오류 rollback.
5. 두 connection이 같은 head에서 경합: 한 번만 귀속, 상대는 동일 replay 또는 stale 거절. 두 서로 다른 batch라면 승자만 commit.
6. snapshot insert/head update/commit 전 실패 후 observation·snapshot·head 모두 이전 상태, commit 후 응답 유실 후 동일 replay 중복 귀속 없음.
7. 별도 JVM 또는 새 store instance 재시작 read/append, hash 손상/codec version/배포 identity 불일치 fail-closed.
8. owner A/B 자산·미지급 수익 분리, direct owner bundle이 B user/operation/raw 없이 저장 가능.

H2/Mock 성공을 실제 PG로 바꾸지 않는다. 이 단위에는 계약 RPC의 fresh canonicality, full coverage MATCH, Java Spring 서비스/ADMIN HTTP, 실제 chain reorg, 실시간 token 이동, 실제 60초/MetaMask가 없다. 각각 C4c~C7의 후속 Gate다.

## 7. 다음 실행 조건

C4a 독립 검토 완료·최종 모델 확인 → 이 저장 단위의 구체 schema/codec/API 확정 → 무과금 codec/JDBC 구현 → 관련 테스트 → 독립 검토와 수정 → 신규 Linux 내부망 실제 PG Gate 순서다. 총괄이 C4a 결과와 자원 상태를 대조해 다음 작업을 시작한다. 일반적인 구현 승인 질문을 사용자에게 반복하지 않는다.

## 8. 구현 초안 인계 — 아직 PG 실행 PASS 아님

- `CCustodyProjectionStore`: 명시 DataSource의 순수 JDBC, `bootstrap/load/append/advanceEmpty`, epoch 1 전용. 기대 head 불일치는 전체 동일 replay가 아니면 거절한다. State에 이미 있는 observation의 실제 DB 행이 없으면 조용히 복원하지 않고 실패한다.
- `c-custody-schema.sql`: 전용 namespace의 5개 테이블, FK/CHECK/UNIQUE, 4개 불변 표의 UPDATE/DELETE/TRUNCATE trigger. heads만 가변이다. 기존 B 자산·user·wallet 표를 참조하거나 수정하지 않는다.
- schema 설치는 명시 메서드이며 자동 Spring 등록·기본 활성화 없음. 오류는 안전한 단계 코드와 SQLSTATE만 노출한다. commit 또는 commit 이후 connection close 오류는 결과 불확실로 보존하고 내부 재시도를 하지 않는다.
- `CCustodyProjectionStorePostgresTest`: 지정 Linux `/fixture/manifest.properties`의 새 run/DB/user/JDBC URL 및 DB marker를 먼저 SELECT 검증한다. schema bytes도 classpath와 fixture 간 비교한다. 실제 PG opt-in 테스트 초안은 replay·충돌·동시 writer·전체 rollback·commit 이후 응답 유실·불변 trigger·FK/CHECK·손상 payload 및 직접 owner Claims 환원을 다룬다.
- 새 SQL 저장은 실제 chain 거래·사용자 서명·canonical fork 선택·full coverage MATCH를 검증하지 않는다. 실제 PG 실행은 소유 harness 독립 검토와 자원 확인 이후 총괄이 수행한다. 컴파일·실행·독립 검토 결과는 별도 실제 결과 문서로 누적한다.
