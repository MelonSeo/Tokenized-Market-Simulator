# B-v2 전체 감사 — 신규 Linux 실행 Gate와 실패 경계 누적 기록

> 최신 갱신: 2026-10-23 KST. **Linux5: 실제 PG/Anvil 감사 Gate PASS — 12거래·32이벤트 MATCH, negative INCONCLUSIVE/MISMATCH 및 원복 MATCH 확인. 최종 독립 evidence 검토는 대기.** Linux3 budget 차단·Linux4 RPC 실패는 그대로 보존한다. 최신 결과와 검증 범위는 §13을 따른다.

> 초안 기록(첫 두 실패 직후): **최종 Gate NOT_RUN / 다음 실행 준비 중.** 당시 전체 감사의 새 실제 거래 PASS는 아직 확보하지 못했다. 아래 최초 실패와 NOT_RUN은 당시 기록으로 보존하며 이후 실행의 성공으로 덮어쓰지 않는다.

관련: [감사 구현 기록](dapp-b-v2-authorized-audit-2026-10-22.md), [안전 통합 계획](dapp-worktree-integration-plan.md), 기존 DApp worktree의 `dapp-development-progress.md`와 `dapp-b-v2-rpc-server-recovery-2026-10-22.md`.

## 1. 목표와 명시적 경계

Windows native Java/Node socket 및 Docker 포트 게시 경계와 금융·감사 제품 기능을 구분하기 위해, **새 격리 Linux JVM에서 실제 PG/Anvil 감사 Gate**를 실행한다. Windows 네트워크 문제를 해결했다고 선언하는 작업이 아니다.

검증 목표는 승인된 B-v2 Intent, 최종 실행 revision, canonical Receipt/Event, DB journal·house·담보를 연결하는 전체 감사다. controlled 가격 공급자와 별도 시험 signer를 사용한다. 실제 사용자 MetaMask, 제품 Synthetic 및 Windows Chrome의 인수 결과로 대체하지 않는다. 기존 60초 유예·30초 Report·5초 서버 신선도·SIGNED raw 불변 정책을 완화하지 않는다.

이 단계에는 기존 데이터 이전·초기화, 기존 runtime 재시작, 유료 모델/Embedding, 원본 병합, commit/push 및 외부 배포가 없다. 새 fixture의 실패 자료도 삭제하지 않는다.

## 2. 실행 소스와 런타임 identity

- 작업 위치: `deployment/runtime/dapp-audit-development-worktree`, detached HEAD `f9353c33244565b30fb130dfb6159eab441a3523`의 미커밋 개발 소스.
- 두 실패 session의 `session.json`에서 직접 확인한 productHash: `b1a737a2625e5565e2a0454a417fd075f49d50ecb92df88457caf3974e237c3d`.
- 두 session의 productHash는 동일하다. shell 생성과 테스트 실행 방법의 수정이 과거 product 검증을 새 PASS로 바꾸지는 않는다.
- 문서 작성 시 `AuthorizedV2AuditPostgresGate.java` SHA-256: `2eb02337d0d03239175ba421a2654f5cb92096ab44c4599cad8aa7165059d23f`. 이는 test helper의 별도 identity이며 productHash와 다르다. 이후 수정 시 신규 실행 manifest에서 다시 고정한다.
- 컴파일: Windows JDK **21.0.10**에서 만든 class/dependency bundle. 파일별 SHA-256을 source/destination 대조 후 신규 portable 디렉터리에 보존한다.
- 실제 두 번째 JVM 로그: Linux Temurin **21.0.12.1+1 JRE**. 이는 **Linux에서 전체 Gradle/JDK build를 수행한 결과가 아니다.** portable class 실행과 정상 Linux 전체 build를 구분한다.
- runtime 이미지의 기존 app/entrypoint는 실행하지 않고 필요한 빈 working directory만 추가한 신규 이미지를 사용한다. 이미지 환경 변수는 비밀정보 없는 허용 목록만 인정한다.

## 3. Linux 격리 구조와 guard

```text
신규 Docker internal network (명시 /24 subnet, 기존 network 변경 없음)
  └─ 신규 Anvil anchor: RPC 35001, chainId 31337, 실제 2초 periodic block
       ├─ 신규 PostgreSQL: anchor와 network namespace 공유, TCP 35000
       └─ 신규 Linux Java: anchor와 network namespace 공유
             JDBC/RPC = 127.0.0.1:35000 / :35001
             Spring random HTTP port도 같은 namespace 내부에서만 사용

배포 도구: 같은 신규 internal network에서 anvil:35001 사용
Windows host port publish: 없음
```

### 소유권·네트워크

- 기존 `phase-a.py::create_fixture_network`의 확정 overlap만 최대 8개 후보로 제한하는 정책을 재사용한다. 후보는 명시 `10.240.<octet>.0/24`다. 불확실 생성·timeout·다른 오류는 중단한다.
- 신규 adapter가 정확한 `docker network create`에 `--internal` 및 새 owner label을 추가한다. 기존 Phase A label·subnet·이름을 유지한다.
- network의 `Internal=true`, 정확한 owner, 마지막 CREATED 할당의 subnet과 실제 IPAM 배열 일치를 검사한다.
- PG/Java의 `NetworkMode=container:<정확한 anchor ID>`, anchor의 유일 network, PortBindings 부재를 검사한다. 모든 bind mount는 새 run 아래여야 한다.
- 기존 Windows HostPort 검증과 **동일한 배포 방식은 아니다.** 대신 명시적인 신규 shared-namespace identity guard를 적용한다. 기존 manifest나 Java fixture guard를 바꿔 통과시키지 않는다.

### DB 인증·실행 identity

- 새 PG initdb에 `--auth-host=scram-sha-256 --auth-local=trust`를 지정한다. Java JDBC의 loopback **TCP는 SCRAM**, 컨테이너 내부 `psql`의 UNIX socket은 local trust다. UNIX readiness 성공을 JDBC SCRAM 증거로 혼동하지 않는다.
- `pg_hba_file_rules`의 TCP 규칙 중 비SCRAM count가 0인지 검사한다. 실제 Spring JDBC 연결은 별도 확인 대상이다.
- `PhaseAFixture.optional`은 Linux 경로에서도 session/child 부모·명명 규칙, runId, DB 이름, 포트 범위, 동일 loopback JDBC/RPC, AI OFF, config import OFF 및 DB marker를 검사한다.
- source/productHash·도구 image digest는 기존 Phase A 검증으로 사전/사후 대조한다. portable bundle 및 수정한 실행 helper identity는 별도 기록한다.
- 서버에는 사용자 시험 EOA 키를 Spring property로 제공하지 않는다. 독립 시험 signer 프로세스가 신규 fixture 전용 키로 서명한다. 이는 사용자가 직접 통제하는 MetaMask 승인 증거는 아니다.
- 신규 bounded JVM과 owned-label 정지 절차를 사용한다. 데이터·network·컨테이너는 보존한다. 정지 실패는 별도로 보고하며 보존과 정상 서비스를 같은 뜻으로 쓰지 않는다.

## 4. 첫 번째 실행 — shell CRLF 실패

| 항목 | 실제 기록 |
|---|---|
| session | `phase-a-20261022145701-4e49ea36` |
| fixture | `futures-api-11b4ab68` |
| tools digest | `sha256:acd9b91c1fcb64d8b4a5df05e616285f47a5fd2526155be73ba8eb1c725e3de6` |
| 준비 확인 | 신규 PG/SCRAM 설정 검사, internal network/shared namespace/no-publish 검사 통과 기록 |
| 최초 오류 | `/audit-tools/init-chain.sh: 2: set: Illegal option -` |
| 실패 경계 | 첫 배포 shell의 `set` 해석 단계. 거래·감사 실행 이전 |
| 독립 읽기 확인 | 보존된 `tools/init-chain.sh`에 CR 바이트 **18개**, `.evidence.json` 거래 결과 파일 **0개** |
| 종료 분류 | `linux-failure.json`: CommandFailed / retry=false |
| cleanup | `errors=[]`, `dataPreserved=true`. 신규 소유 자원 정지·자료 보존 기록 |

Windows에서 텍스트를 생성하면서 shell 파일이 CRLF가 된 것이 이 실패의 확인 가능한 원인이다. Windows RPC 장애나 계약 금융 규칙 문제로 분류하지 않는다.

수정은 신규 private shell 파일의 LF 바이트 생성 경계에 한정했다. `private_lf`는 `newline='\n'`, 기존 CRLF 정규화, exclusive create를 사용하며 원본 shell이나 실패 fixture를 덮어쓰지 않는다. LF 실제 바이트와 덮어쓰기 거부를 포함한 runner 안전 테스트 **7 PASS** 및 별도 검토 기록을 총괄이 확보했다. 이는 거래 Gate PASS가 아니다.

원본 근거는 해당 session의 `logs/deploy-init-chain.sh-20261022T145807652036.private.log`, `linux-failure.json`, `cleanup-result.json`과 fixture 내부 shell이다. private 원문 전체는 이 문서에 옮기지 않는다.

## 5. 두 번째 실행 — Mockito JRE attachment 실패

| 항목 | 실제 기록 |
|---|---|
| session | `phase-a-20261022150500-9be79a64` |
| fixture | `futures-api-dc6f453c` |
| tools digest | `sha256:486f4cd1d596d3427e74ff0d0fe25c7a7a5bb1bd47d3a4b3d657e0d57a8200f8` |
| 첫 수정 효과 | 신규 shell CR 바이트 **0개**. 실제 계약 배포 단계와 Spring 시작까지 진행 |
| JDBC 시작 | `2026-10-22T15:07:58.368Z` HikariPool Start completed |
| Spring 시작 | `2026-10-22T15:09:06.028Z` 테스트 ApplicationContext 시작, 129.646초 |
| 최초 핵심 오류 | MockitoInitializationException → `No compatible attachment provider is available` |
| 실패 경계 | Spring 시작 뒤 Mockito 테스트 초기화/attachment 경계. 거래 시나리오 실행 이전 |
| 독립 읽기 확인 | fixture의 `.evidence.json` 거래 결과 파일 **0개**, Linux JRE 21.0.12.1 로그 |
| 종료 분류 | `linux-failure.json`: RuntimeError / retry=false |
| cleanup | `errors=[]`, `dataPreserved=true`. 신규 소유 자원 정지·자료 보존 기록 |

실제 RPC/배포/startup까지 도달했다는 사실은 이번 Linux 거래·감사 PASS가 아니다. 이 오류는 Windows native listen이나 첫 shell 오류와도 다르다. 기존 JRE의 동적 attachment 경계를 조사하며 cached Byte Buddy premain agent를 테스트 JVM에서 명시적으로 제공하는 별도 무과금 probe를 준비 중이다. **이 초안 시점에는 probe 결과 및 수정 후 전체 Gate 결과가 없다.**

수정은 테스트 실행 도구에 한정해야 한다. Mockito 오류를 이유로 금융·권한·정답 검증을 삭제하거나, 제품 클래스를 가짜 구현으로 바꿔 실행해서는 안 된다. premain 방식이 통과해도 신규 run의 전체 Gate로 다시 확인하고 이전 실패의 결과를 바꾸지 않는다.

원본 근거는 해당 session의 `logs/linux-java-log-20261022T150909945900.private.log`, `linux-failure.json`, `cleanup-result.json`과 portable manifest다. API 키·JWT·서명·raw·DB 비밀번호는 공개 보고서에 기록하지 않는다.

## 6. 다음 신규 Gate의 완료 조건 — 아직 실행 결과 없음

1. 최종 helper/bundle·productHash·image 및 Linux 실행 경계를 고정하고 무과금 premain probe를 통과한다.
2. 새로운 session/fixture를 만든다. 실패 session을 재기동·초기화하거나 `result.json`을 성공으로 수정하지 않는다.
3. 실제 Config/Service/Controller·trusted registry를 사용한다. 익명401, USER403, ADMIN의 target/baseline 조회 및 baseline POST를 실제 내부 HTTP로 검사한다.
4. baseline 등록 전 INCONCLUSIVE, fresh baseline 등록 후 정상 경로를 검증한다. legacy id1 기준점은 변경하지 않는다.
5. 실제 revision0 OPEN/TOP_UP/CLOSE 3개 + revision1 3개 + Long/Short 각각 OPEN/START_CALL/LIQUIDATE 6개를 실행한다. 기대 **12개 canonical 거래 / 32개 이벤트**다. 두 자동청산은 각각 실제 60초 이상 유예를 기다린다.
6. 정상 정산 이후에만 새 owned Anvil의 mining을 시험 목적으로 정지해 bounded 감사 cut을 고정한다. 유예를 warp하거나 대체하지 않는다.
7. canonical Receipt·3이벤트·사용자 승인·최종 revision·DB journal·잠금·house를 연결해 MATCH를 확인한다.
8. 별도 readonly observer의 target 부족 → INCONCLUSIVE, 새 unsigned PREPARED 주문 → INCONCLUSIVE 및 잠금 보존을 확인한다. 기존 거절 경로로 시험 주문을 종료하고 RESERVE/RELEASE 이력·잠금 반환·MATCH 복귀를 검사한다.
9. 새 fixture의 mutable house projection만 의도적으로 오차 주입 → MISMATCH, 정확한 원본 값 복구 → MATCH를 확인한다. append-only journal/승인/기준점은 지우거나 바꾸지 않는다.
10. 최대30초 내부 observer budget, 40초 서비스 deadline과 fail-closed를 유지한다. 12거래 규모에서 제한을 넘으면 원인을 보고하고 PASS를 만들기 위해 한도를 완화하지 않는다.
11. 신규 owned 자원 정지 및 기존 환경 보존을 확인한다. 실제 결과·시간·비용0·증거 경로와 PASS/FAIL/BLOCKED/NOT_RUN을 새 절에 추가한다.

PREPARED 거절 시험을 포함하면 operation은 13개일 수 있지만 실제 온체인 거래는 12개다. unsigned 주문을 체인 실패 거래로 세지 않는다. baseline nonce-cancellation coverage 등 기존 감사의 미지원 범위는 명시적으로 유지한다.

## 7. 현재 판정 및 보존

| 범주 | 판정 |
|---|---|
| Linux network/PG/Anvil 신규 격리 준비 경계 | Linux5 신규 isolated/shared namespace/internal/no-publish 실제 Gate PASS. Windows 경로 정상화와 별도 |
| shell LF 보완 | 무과금 보완·안전 회귀 완료 기록, 두 번째 shell CR=0 직접 확인 |
| Linux Spring/JDBC startup | Linux5 실제 Spring/JDBC/RPC 및 ADMIN HTTP 감사까지 PASS |
| Mockito premain probe | 신규 격리 무과금 실제 PASS. 기존 두 번째 실패를 성공으로 바꾸지 않음 |
| Linux3 거래·선택 증거 | canonical CONFIRMED 12거래 / 32이벤트 verified, Long/Short 실제 60초 이상 유예 |
| Linux3 전체 감사 Gate | INCONCLUSIVE / BOUNDED_READ_LIMIT / complete=false / mismatchCount=0. 최초 MATCH assertion 1 FAIL, 후속 NOT_RUN |
| 최소 helper 보완 회귀 | 제품 한도 변경 없이 늦은 legacy 최초 baseline 등록. 최종 helper 별도 검토 필수 지적 없음, 새 회귀 포함 감사111 PASS(총괄 기록) |
| Linux4 전체 감사 Gate | 실제 Spring SUBMIT RPC timeout으로 1 FAIL/STOPPED. 후속 검증 NOT_RUN(§10) |
| Linux5 전체 감사 Gate | 1 PASS/0 FAIL/0 SKIP/0 ABORTED. 실제12거래32이벤트 MATCH complete=true, unknown/pending INCONCLUSIVE, house MISMATCH→복원 MATCH |
| Linux5 종료·독립 검토 | cleanup errors=[]/dataPreserved=true 확인. 최종 독립 evidence/전후 보존 검토 대기 |
| 실제 사용자 MetaMask TOP_UP/CLOSE | 이 Linux Gate 범위 아님. 기존 미완료 상태 유지 |
| Windows RPC/native socket 근본 해결 | 미확정, Linux 성공 여부로 해결 선언하지 않음 |
| 실제 제품 Synthetic·Chrome 통합 | 이 Gate에서 NOT_RUN |
| C 실제 ERC-20 보관·정산 | 별도 정책 승인 대기 / NOT_RUN |
| 유료 AI·원본 병합·commit/push·외부 배포 | 실행하지 않음 |

원본/UI/AI/DApp worktree와 기존 DB/Anvil, 모든 ledger/STOP/claim 및 private evidence는 보존 대상으로 유지한다. 이번 문서 작성자는 첫 두 신규 session의 비밀 없는 identity·오류 표식·CR 개수·거래 증거 개수·cleanup 결과만 읽었다. 기존 거래 DB나 AI 원본 ledger를 조회·이전하지 않았으며 과거 실패를 소급 성공으로 처리하지 않았다.

## 8. 세 번째 실행 — 실제 거래 검증 후 전체 감사 budget 차단

실행 identity: `phase-a-20261022151405-e6b20653/futures-api-e1dacade`. 이전 두 실패를 재사용하지 않은 신규 Linux fixture다. premain 무과금 probe의 실제 PASS 이후 실행했으며 제품 금융·권한 규칙은 바꾸지 않았다.

### 8.1 실제 확보한 결과

보존된 `audit-observation-match.evidence.json`을 정제해서 직접 확인한 결과:

- 전체 상태 **INCONCLUSIVE**, reason **BOUNDED_READ_LIMIT**, complete=false.
- DB 검사 checkCount=266, mismatchCount=0.
- B-v2 operations=12 / transactions=12 / verifiedTransactions=12.
- scannedEvents=32 / verifiedEvents=32. 최종 provisional 처리로 target coverage.complete도 false다.
- 두 target의 범위 fromBlock=140, toBlock=228. legacy 대상 거래·이벤트는 0이다.

**개별 거래·증거 검증을 확보했지만 전수 감사 MATCH는 확보하지 못한 실행**이다. mismatch=0이나 verified 수치만으로 complete=false를 MATCH로 승격하지 않는다. 실제 JUnit은 최초 MATCH 기대 assertion에서 1 FAIL이며 그 뒤 unknown-target·PREPARED·house fault/원복 Gate는 이 실행에서 NOT_RUN이다.

### 8.2 실제 유예 증거

| 방향 | START_CALL 체인 초 | 유예 deadline | LIQUIDATE 체인 초 | 체인상 경과 | 실제 wall 경과 |
|---|---:|---:|---:|---:|---:|
| Long | 1792682393 | 1792682453 | 1792682458 | 65초 | 73.938974717초 |
| Short | 1792682473 | 1792682533 | 1792682538 | 65초 | 75.838386226초 |

각 deadline은 START_CALL+60초, 청산은 deadline+5초다. 위 값은 `auto-long.evidence.json`, `auto-short.evidence.json`에서 읽었다. 실제 시간 경과이며 vm.warp·합성 시간 대체가 아니다. controlled provider·trial signer 범위이며 제품 Synthetic/MetaMask/Chrome 성공으로 확장하지 않는다.

### 8.3 제한에 도달한 경계

코드의 `Budget.use(...)`를 기준으로 총괄이 계산한 값:

| 구간 | 코드 파생 호출 예산 |
|---|---:|
| legacy 배포·head·코드20 + log1 + block scan178 | 199 |
| v2 초기21 + 거래 검증72 + log/canonical33 + block scan178 + 현재 position/owner8 | 312 |
| 두 target까지 | 511 |
| 마지막 stable 검사 `use(1)` + `head`의 `use(2)` | 514로 증가하여 최대512 초과 |

**511/514는 runtime counter를 직접 기록한 수치가 아니라 코드와 실행 범위에 근거한 계산값**이다. 실제 관측은 BOUNDED_READ_LIMIT이며 이 계산을 별도 계측 결과처럼 설명하지 않는다. 제품 cap을 늘려 PASS시키지 않는다.

### 8.4 보존과 최소 해결 방향

- 이 session의 `cleanup-result.json`을 직접 확인했으며 errors=[] / dataPreserved=true다.
- 별도 독립 검토에서 portable 파일995개 SHA 대조와 기존 컨테이너383개 ID/이미지/상태의 변경·누락0을 확인했다는 총괄 검토 기록이 있다. 문서 작성자가 같은 검사를 다시 실행한 것은 아니다.
- 최소 수정은 **시험 helper의 baseline 등록 순서**다. V2 baseline은 모든 V2 거래 전에 등록하고 불변성을 검사한다. 아무 거래도 하지 않은 legacy는 최종 frozen cut에서 제품 baseline API로 최초 등록한다.
- 제품 API는 legacy의 genesis부터 현재까지 전체 이력을 읽어 거래·로그·대상 포지션이 없음을 확인한다. 기존 baseline을 덮어쓰거나 V2 과거 이력을 줄이는 방식이 아니다. 이후 결합 감사에서 legacy의 긴 빈 prefix를 매번 다시 스캔하지 않도록 한다.
- 제품 MAX_RPC=512, MAX_BLOCKS, 내부 시간 budget, 서비스 deadline 및 금융 정책은 유지한다. legacy의 fresh 등록 기준을 만족하지 못하면 다음 실행도 실패해야 한다.
- 최종 helper SHA-256: `0c1b68e9bd28cf3cc8abbfcf0519839a838a80b25094402d77b13041365ade1a`. 별도 최종 diff 검토 필수 지적 없음. 새 회귀3개 포함 감사 선택 테스트111 PASS는 총괄의 실제 새 실행 기록이다. 단위 회귀를 다음 PG/Anvil Gate PASS로 대신하지 않는다.

## 9. 네 번째 신규 실행 — 최종 판정 대기

- session `phase-a-20261022153056-c8496e93`, fixture `futures-api-2c2227fd`.
- session.json에서 직접 확인한 HEAD `f9353c33244565b30fb130dfb6159eab441a3523`, productHash `b1a737a2625e5565e2a0454a417fd075f49d50ecb92df88457caf3974e237c3d`.
- 현재 신규 실행 중이다. §8.4의 V2 baseline 불변·legacy 빈 전이력 확인 후 늦은 최초 등록 구조로 진행한다. 이전 session이나 baseline을 재사용하지 않는다.
- 기대 검증은 기존 §6의 12거래/32이벤트, 양방향 실제 유예, ADMIN/USER 경계, baseline/unknown/pending INCONCLUSIVE, unsigned 예약 반환, house MISMATCH 및 원복 MATCH다.
- **갱신 시점에 Linux4 최종 실행·cleanup·보존 판정은 아직 없다.** PASS를 미리 기록하지 않는다. 실패 또는 불확실성이 생기면 증거를 보존하고 총괄이 결과를 추가한다.

이번 갱신은 Linux3의 정제된 observation·유예·cleanup 자료 및 Linux4의 session/child identity만 읽었다. 기존 거래 DB·AI ledger·키·JWT·원문 서명·raw는 조회하거나 옮기지 않았다.

## 10. Linux4 최종 중단 및 후속 무금융 진단

이 절은 §9 작성 이후 실제 종료 결과다. 이전 실행 중 문구를 성공으로 고치지 않는다.

- JUnit **1 FAIL**, 실제 오류는 `/api/futures/authorized-v2/drafts/{id}/submit`의 HTTP503 `FUTURES_RPC_UNAVAILABLE`. `revisionTrades`의 submit(assert line174), revision1 loop(run line76)에서 중단했다. 최초 총괄 초안의 revision0 loop 해석은 독립 검토가 바로잡았다. revision0 OPEN/TOP_UP/CLOSE 메서드 반환·revision1 OPEN 검증 이후 다음 TOP_UP 제출 실패가 소스/로그상 도달 범위다. 정지된 DB를 재시작해 직접 조회한 결과는 아니므로 전체 미확정0/잠금0이라고 선언하지 않는다.
- 실제 Spring trace `60cb6f29-5745-44b3-a002-3aaa1b5ac198`: CHAIN_ID/DEPLOYMENT/SUBMIT에서 timeout. HTTP 고정 관측은 `REQUEST_BODY_COMPLETED`, reused=true, proxy=DIRECT, callFailed3033ms/submit3037ms다. 이는 원격 peer의 수신·응답 여부까지 증명하지 않는다.
- 신규 PG migration, 권한·baseline 부족 INCONCLUSIVE, 최초 v2 baseline 증거는 생성됐다. 실제 거래 도달 범위는 독립 검토 중이다. 이후 revision1 전체·양방향 유예·감사 MATCH/house MISMATCH/원복은 이 실행에서 NOT_RUN이다.
- `linux-failure.json` RuntimeError/retry=false, cleanup errors=[]/dataPreserved=true. 실패 fixture를 재시작·초기화하거나 거래를 재전송하지 않는다.
- Windows host publish를 하지 않는 Linux 환경에서도 유사 timeout이 발생했다. 따라서 Windows-only socket이 이번 오류의 확정 원인이라고 말할 수 없다. 연결 재사용·HTTP peer·Anvil·런타임 자원/일정 등은 아직 가설이다. 금융 정책/3초 timeout/pool/retry를 바꾸지 않는다.
- 새 전체 거래fixture를 즉시 반복하지 않는다. 계약 배포·DB·금융 거래 없는 bounded Linux RPC-only 진단을 먼저 설계한다. 실제 사용자 추가 서명이나 기존환경 조작은 없다.

### 최신 영향 회귀와 보호 확인

- 최신 컴파일 PASS 및 감사111 PASS(`audit-20261022T152740635222.log`), 새 observer3개 회귀 독립 검토 필수지적 없음.
- 전체 backend 직접 JUnit: **810 PASS/53 SKIP/1 ABORTED/0 FAIL**(`all-20261022T153232324154.log`, 864개발견). ABORTED는 Windows listener를 생성할 수 없으면 `LOCAL_LISTENER_UNAVAILABLE_SOCKET_BOUNDARY` assumption으로 중단하는 유일한 경로인 `FuturesRpcHttpTest.delayedLocalHeadersRespectProductionCallDeadline`이다. exit0은 전체 테스트 PASS와 다르다. 선택 실행의 해당 테스트 PASS를 전체 실행 ABORTED에 소급하지 않는다.
- 보호 baseline156개 SHA-256 읽기 전용 재대조 변경0/누락0, 원본 HEAD f9353c3 및 기존 dirty10 보존. commit/merge/push/유료호출 없음.

### Linux4 독립 증거 검토 확정 및 하네스 후속

- 기존386개 컨테이너의 ID/이름/이미지/상태 분류 변경·삭제0, 신규3개만 추가됐다. 신규PG/Anvil Exited0, Java Exited1. internal/no-publish/shared namespace/owner 일치, portable995개 SHA 전수일치/누락0을 독립 검토했다.
- 보존된 compiled helper의 SHA와 소스 라인 대조 및 4회 성공 SUBMIT/PREPARE_COMMIT/SIGNED_COMMIT를 확인했다. revision0 세 거래와 revision1 OPEN의 코드 검증 도달은 인정하되, 거래별 증거 파일이 없으므로 독립 txHash/Receipt 아카이브 대조와 구분한다.
- 후속 하네스에서 검증에 성공한 각 action의 public operation/hash/block/revision/state를 즉시 CREATE_NEW 파일로 남기도록 최소 보완한다. 나중 action 실패로 앞선 성공 식별자가 누락되는 관측 문제를 줄이며 기존 실패 자료를 사후 보충하거나 금융 검증을 바꾸지 않는다. 새 helper compile/독립 검토는 아래 후속 기록에 연결한다.
- Linux4 최초v2 baseline block160을 확인했다. Spring startup 중 빈 periodic block 누적은 다음 두 실제 유예를 더하면 fresh legacy baseline의 genesis→head MAX_BLOCKS250도 초과할 수 있다. 기존 한도를 늘리지 않는다. 다음 전체 Gate 전, 새 fixture의 startup은 기본 automine로 배포하고 실제 거래 시작 전부터 2초 interval을 명시 활성화하는 시험 lifecycle 대안을 검토한다. 실제 마진콜 이후 interval을 멈추거나 시간 warp로 60초를 대체해서는 안 된다. 이 대안은 아직 적용·실행하지 않았다.
- 거래별 즉시증거 보완 compile PASS `classpath-20261022T154946549855.log`, 독립 검토 필수지적 없음. 최신 helper SHA `12f81f8cde00f274d26f099901acdce371f4288d2737e280e53166e788fa1b8b`. 실제 새 run에서의 파일 생성은 NOT_RUN이다.
- Foundry 최신 오프라인 회귀 **101 PASS/0 FAIL/0 SKIP**, Solc0.8.24 compile PASS: `foundry-20261022T155222996414.log`. 처음에는 새 worktree의 ignored lib 부재로 missing import가 발생했으며 기존 검증된 로컬 lib를 읽기 전용 remapping해 새 산출물만 만들었다. 기존 lib/계약·배포를 변경하지 않았다. 이 회귀는 실제 PG/Anvil 감사 및 real60초의 대체 증거가 아니다.

## 11. 금융 상태 없는 RPC-only 최소 진단

- 첫 run `rpc-only-20261022155744-9eb342`는 기본 Docker 주소 풀이 소진되어 network create에서 중단했다. 실제 RPC0회/컨테이너0, cleanup오류0. 실패 증거를 그대로 두고 기존 명시적10.240.x/24 allocator를 internal/새owner adapter로 재사용했다. 기존 네트워크 변경·삭제 없음, 확정overlap만최대8후보/그외오류·timeout즉시중단을 유지했다.
- 필수 Relay bytecode SHA 누락을 독립 검토에서 찾아 모든 launcher class SHA/누락·변조 거부를 보완했다. 안전8개 Mock 테스트 PASS, allocator adapter와 최종 수정 재검토에서 필수 지적 없음.
- 새 run **`rpc-only-20261022160240-9e9146` PASS**: 같은 제품 `FuturesRpcHttp.client()`의 직접3회와 관측relay3회, 고정 `eth_chainId` HTTP6회. 신규DB·계약배포·서명·거래 없음. hostpublish없음/internalnetwork/sharednamespace/50초 JVM, 동일ID HTTP신규전송차단·애플리케이션 재시도0. 연결 이전 TCP시도까지0회재시도라고 주장하지 않는다(기존 OkHttp connectionRetry=true).
- 직접 응답335/1/1ms, relay20/89/54ms. relay3회에서 요청 수신완료/Anvil 응답완료/client향 flush 표식을 확인했다. flush만으로 상대 수신까지 증명하지 않는다. relay 경유 경로는 실제 직접RPC 경로와 다른 시험 경로다.
- 유휴는 코드상 `Thread.sleep(5000)` 설정이며 단조시계 idle 실측 로그는 없다. 벽시계 로그에 직접2→3 구간이 약3.4초로 나타나므로 벽시계 차이만으로 정확한 idle 경과를 확정하지 않는다. RPC 호출 경과는 단조시계를 사용한다.
- 독립 검토가 manifest573 artifact SHA 불일치0, JRE imageID, owner/internal10.240.13.0/24/namespace/RO새mount/미게시포트, cleanup오류0을 확인했다. 두 새 컨테이너·network·로그는 정지 보존했다.
- **실제 Spring timeout 원인은 여전히 미확정**이다. 제한된 작은 시험에서 재현되지 않았을 뿐이며 Windows/Vite·Linux4·전체감사/MetaMask 인수 PASS로 승계하지 않는다. timeout/pool/proxy/retry/금융 조건 변경 없음.
- 현재 시점 별도 읽기 진단: C 여유32.03GiB/호스트가용메모리1.86GiB(총13.86GiB). 실패 당시 실측이 아니므로 자원 부족을 과거 timeout의 확정 원인으로 기록하지 않는다. 다음 Gate와 무거운 전체회귀를 동시에 실행하지 않도록 시험 간 자원 간섭만 줄인다.

## 12. Linux5 — 검토된 테스트 lifecycle과 제한된 신규 실행

- session `phase-a-20261022160814-5c27892a`, fixture `futures-api-c67e150d`. 이전 fixture/network/계정/DB를 재사용하지 않는다. 제품hash b1a737... 불변, 최종helper SHA `8b7e01cd6274c32e79c4aefaf55f7ed325c4a9f8088c59ba4b1c442fb987c328`.
- 신규 테스트 체인의 startup은 기본 AUTOMINE, 모든 거래 전 automine=false/interval2로 전환한다. 성공응답만으로 주기를 보증하지 않고 최대10초/20회/500ms 성공readpoll로 첫새block을 확인해 번호·timestamp·hash를 저장한다. 개별RPC3초/오류즉시중단/no자동연결retry, 시간warp·채굴skip없음. 전체 거래·두 실제 유예가 끝난 다음에만 기존 freeze가 실행된다.
- metadata는 startupAUTOMINE과tradeScenarioIntervalSeconds2를 분리한다. 기존 fixture guard용2초 필드는 거래 단계 설정이며 startup 전체가2초였다고 주장하지 않는다. 첫새block 관측과 장기 주기 실측도 구분한다.
- compile PASS `classpath-20261022T160734832881.log`, private 안전10 PASS. lifecycle 최초 및 최종 독립 검토에서 필수 수정 없음. 이 준비 결과는 실제 새 Gate PASS가 아니다.
- RPC-only6회 미재현 이후 제한된 새 전체Gate1회를 시작했으며 무거운 병렬 회귀는 실행하지 않는다. RPC재발/identity/금융검증 실패는 STOP·증거보존하며 이전 성공을 재사용하거나 자동거래 재시도하지 않는다.
- 이 Gate는 legacy+v2 등록2target의 bounded 감사다. B-v1 실제 등록 배포, 취소nonce연속fold, 장기동적cut, 제품Synthetic/Chrome/MetaMask TOP_UP·CLOSE와 C 자산보관은 별도 미인수 범위다.
- 현재 실행 중. 실제 거래·유예·MATCH/MISMATCH/INCONCLUSIVE 및 cleanup 판정은 증거 생성 후 추가한다.

## 13. Linux5 최종 실제 결과 — runner PASS / 독립 evidence 검토 대기

이 절은 §12 실행 중 기록 이후 확보한 결과다. 기존 실패 fixture를 다시 기동하거나 과거 결과를 수정하지 않았다. 문서 작성자는 아래 새 run의 JSON·JUnit 집계·cleanup만 읽었으며 DB/RPC를 다시 호출하지 않았다.

### 13.1 실행 및 배포 identity

| 항목 | 실제 공개 identity |
|---|---|
| session / fixture | `phase-a-20261022160814-5c27892a` / `futures-api-c67e150d` |
| HEAD | `f9353c33244565b30fb130dfb6159eab441a3523` + 미커밋 audit 개발 소스 |
| productHash | `b1a737a2625e5565e2a0454a417fd075f49d50ecb92df88457caf3974e237c3d` |
| runner SHA-256 | `2016a4be978fe7acc8c38cae725c1d71e06dc43acf0c9cd5b4116ab1e3e18905` |
| compiled audit helper SHA-256 | `7d12b41df8938be270ebdf76f38bbf0118da4b6ab9429ebbd044c258f2a4514d` |
| tools image digest | `sha256:5dfe0fc69989f5bb8234f83f15ec66541cd4f15b75a7efdc3e8eaca96c292674` |
| Linux JVM image | `tms-bv2-audit-jvm:phase-a-20261022160814-5c27892a` |
| JVM image digest | `sha256:78822e68c032b37546e3d362da6d9c6e396021f8af32d3597c2fb06f26491ad9` |
| chainId | 31337 |
| V2 controller | `0x4c43edcb4a8d78996a86a0ecc525cd818333e438` |
| V2 internal rules | `0x039562b974705504f53ed8d7b34bb1075fd3ffa9` |
| relayer | `0x9deb26afc224586367cbda60d311e0311b879628` |
| price signer | `0xa00f2747081b63b0533ae1b0ac5a0c1cc5c3a039` |
| controller codeHash | `0x177284d0b9bd76464d8921cc35cd1812ecf93860073a676b84b97d7224c527a5` |
| rules codeHash | `0x3edb8060e9775817c4e214d5ad77bdbbd0260cdf58982fad3567831b36f9ac32` |
| legacy engine | `0x15aedd3fce9ff9507e5e4f35639f544b5a73e2ed` |
| legacy codeHash | `0xf7d4e48cc0ac96153caff3a7613f12fe3bd28b554cefb5d4c69a9ecf547cad6f` |
| policyHash | `0xeacf99dcf1f6523cc734124b7f269daf8262245fe79f89f339630bf69e27becb` |

`result.json`은 PASS, externalProviders=false, publishedPorts=false, windowsRpcFix=false, metaMaskAcceptance=false를 기록한다. Windows JDK21.0.10 compile/portable SHA 보존 + Linux JRE21.0.12.1 실행 경계는 유지되며 Linux 전체 Gradle build PASS가 아니다.

### 13.2 SQL migration·권한·baseline

- `postgres-migration.evidence.json`: 별도 신규 migration schema에서 v1RowsPreserved=true, walletUnique=true, appendOnly=true, v2ForeignKey=true. 기존 보존 DB를 migration한 결과가 아니다.
- 실제 제품 Config/Service/Controller를 통한 ADMIN HTTP 및 USER403/익명401 검증을 helper가 통과했다. baseline 부재 상태는 먼저 INCONCLUSIVE로 확인한 뒤 API에서 최초 등록했다.
- V2 baseline은 block **4**, 이후 모든 실제 V2 거래를 포함하는 감사 cut은 block **102**다. V2 baseline Envelope는 처음·끝 비교에서 불변이다.
- legacy는 거래하지 않은 target이며 모든 실제 거래·유예·정산 후 frozen block **102**에서 제품 API가 genesis→102의 빈 이력을 검증한 뒤 최초 baseline 등록했다. 기존 legacy id1 기준점은 바꾸지 않았다.
- 따라서 V2 감사 범위는 block **5~102**다. legacy의 결과에 fromBlock103/toBlock102가 표시되는 것은 등록 직후의 빈 suffix 범위이며, 그 이전 genesis 이력은 별도의 fresh-baseline 검사 대상이었다. V2 이력을 잘라낸 결과가 아니다.
- PostgreSQL append-only zero-row UPDATE/DELETE/TRUNCATE 차단과 동일 Envelope 동시 재등록 멱등성 검증을 통과했다. 빈 테이블 최초 INSERT 경합과 같다고 주장하지 않는다.

### 13.3 실제 OPEN / TOP_UP / CLOSE

`gate.evidence.json`과 즉시 action evidence에 다음 여섯 사용자 승인 거래가 CONFIRMED로 기록됐다. 각 거래는 rules 2개 + controller 1개의 canonical 이벤트를 검증했다.

| revision | action | block | txHash |
|---:|---|---:|---|
| 0 | OPEN | 9 | `0x753129bd883ea0419d49bf14943ce5d1e1cf9325715a8542b7eb733f9b8f5fbd` |
| 0 | TOP_UP | 11 | `0x8c608f801f4df596fcb320a1a351f87fd09bfa7d50b21fa0f5836324485fdf55` |
| 0 | CLOSE | 15 | `0x7da3c0a242b050bfe5fcf48b2094459dadd9de691fafa9be3c03636ba1792fb1` |
| 1 | OPEN | 17 | `0xc0190243b87eaba500b69db9ab2938ea820554baf1c81ae743acca59bb8ff793` |
| 1 | TOP_UP | 20 | `0xa85545d2e8836189176bd9dbe58e3ff3ca25e3a40bd5068b27efed5907230ac8` |
| 1 | CLOSE | 24 | `0x761bfef18c5264b1f88ba975a8260bbbbc3fcf872a5b94e3989273bbdbe28207` |

추가로 Long/Short 각각 OPEN·START_CALL·LIQUIDATE 3거래를 실행했다. 합계는 **12 canonical 거래 / 32 이벤트**다. 사용자 승인 거래6개에서18이벤트, 양방향 위험 시나리오6개에서14이벤트다. 서명자는 신규 시험 EOA이며 실제 사용자 MetaMask가 아니다.

### 13.4 실제 interval·60초 유예

`trade-scenario-mining-start.evidence.json`: initial AUTOMINE, 거래 전 automine=false/interval2 요청 성공, beforeHeight4→firstPeriodicBlock5를 **2.061918636초**에 실제 관측했다. first block timestamp1792685540, hash `0x112692d3ac79c35486a42ce19cf0eb9fc3de9ea1b4bec44140808fb4409f99c6`다. `fullIntervalCadenceMeasured=false`이므로 장기 모든 block 간격이 정확히2초였다는 별도 실측 보장과 구분한다.

| 방향 | START_CALL 체인 초 | deadline | 새 위험 Report observedAt | LIQUIDATE 체인 초 | chain 경과 | wall 경과 |
|---|---:|---:|---:|---:|---:|---:|
| Long | 1792685587 | 1792685647 | 1792685648 | 1792685651 | 64초 | 71.546988009초 |
| Short | 1792685662 | 1792685722 | 1792685723 | 1792685726 | 64초 | 71.667954411초 |

둘 다 deadline은 call+60, 새 관측은 deadline+1, 실제 청산은 deadline+4다. 시간 warp·유예 생략 없이 실제 경과와 신규 적격 가격을 검사한 결과다. 채굴 freeze는 두 시나리오와 정산이 모두 끝난 다음에만 수행했다.

### 13.5 MATCH / INCONCLUSIVE / MISMATCH와 원복

| 실제 evidence | 판정 / complete | 검사 수 / 불일치 | v2 operation / tx / event |
|---|---|---|---|
| `audit-observation-match` | MATCH / true | 266 / 0 | 12 / 12 / 32 모두 검증 |
| `audit-unregistered-v2-target` | INCONCLUSIVE / false, UNREGISTERED_OR_UNROUTABLE_DEPLOYMENT | 256 / 0 | 명시적으로 부족한 readonly registry; 미등록 target을 정상으로 숨기지 않음 |
| `audit-pending-inconclusive` | INCONCLUSIVE / false, UNSETTLED_OR_REVIEW | 0 / 0 | 실제 새 PREPARED unsigned OPEN; 잠금 유지 |
| `audit-unsigned-rejected-release` | MATCH / true | 292 / 0 | 13 / 12 / 32; REJECTED1개는 tx 없음 |
| `audit-observation-house-mismatch` | MISMATCH / true | 292 / 1 | 13 / 12 / 32; 새 house projection에1wei 의도 오차 |
| `audit-observation-restored` | MATCH / true | 292 / 0 | 13 / 12 / 32; 정확한 house 원본 값 복구 |

PREPARED 주문은 신규 fixture에서 정상 승인·담보 예약까지 만든 뒤 raw 없이 관측했다. 감사는 상태나 잠금을 수정하지 않았다. 시험 helper가 기존 `rejectUnsignedAtReport`의 명시적 unsigned 중단 경로를 호출한 후 RELEASE1개·잠금 반환을 검사했다. 이를 canonical 체인 revert로 기록하지 않는다. journal·승인·baseline 이력은 삭제하거나 덮어쓰지 않았다.

사용자 wallet 합계 + house는 `13000000000000000000000000` 최소단위, 즉 **13,000,000 mKRW**를 보존했다. 정상 종료·청산·unsigned 거절 후 담보 잠금 및 house 예약 해제를 확인했다. 이는 PostgreSQL 모의 담보 보존식이며 실제 ERC-20 담보 예치·준비금·무조건 지급능력 보장이 아니다.

### 13.6 실행별 테스트 결과와 남은 판정

| 실행 범위 | 실제 결과 | 해석 |
|---|---|---|
| 이번 Linux5 실제 PG/Anvil 전체 helper | **1 PASS / 0 FAIL / 0 SKIP / 0 ABORTED**, JUnit elapsed393738ms | 이번 새 run의 결과. 전체 Linux Gradle suite 아님 |
| 별도 최신 backend 전체 직접 JUnit | **810 PASS / 53 SKIP / 1 ABORTED / 0 FAIL** | §10의 별도 실행. native listener assumption ABORTED를 PASS로 바꾸지 않음 |
| 별도 최신 Foundry | **101 PASS** | 계약 회귀. 실제 PG/브라우저 인수와 별도 |
| 별도 감사 선택 테스트 | **111 PASS** | Java 선택 회귀. Linux5 JUnit과 합산하지 않음 |
| Linux runner 안전 Mock/AST | **10 PASS** | 실제 Docker 거래 검증과 별도 |
| RPC-only 안전 Mock | **8 PASS** | 실제6 HTTP 호출 probe와 구분 |

새 session의 `cleanup-result.json`은 errors=[] / dataPreserved=true다. **최종 독립 evidence 검토와 전후 보존 검토는 현재 대기**이며 총괄이 확정 기록을 추가한다. 이 문서의 정제 JSON 대조를 독립 감사 코드 검토나 전수 artifact 보존 검사로 대신하지 않는다.

미인수 범위는 그대로다: B-v1 실제 배포 혼합 거래, 취소 nonce의 연속 fold, 장기 동적 cut, 제품 Synthetic·Chrome·실제 MetaMask TOP_UP/CLOSE, Windows/Vite/RPC 근본 원인 및 C안 실제 자산 보관. 이번 run에서 RPC 실패가 없었다는 사실만으로 반복 장애의 해결을 선언하지 않는다. 유료 AI 호출·원본 병합·commit/push·기존 데이터 변경은 하지 않았다.

### 13.7 최종 독립 증거 인수 완료

별도 검토자 `inspect_bv2_full_audit_gap`이 실제 보존 JSON·로그·동결 소스 및 artifact를 읽기 전용으로 대조하고 **발견된 필수 수정 없음**을 보고했다. 별도 DB/RPC 조회·컨테이너 조작·실패fixture 재시작은 수행하지 않았다.

- portable995개 직접 SHA 검증 누락0/불일치0, 현재productHash b1a737... 일치, helper 소스SHA8b7e01... 및 compiledSHA7d12b41...의 보존portable 일치.
- 여섯 revision0/1 action 파일과 gate/full-audit item의 txHash·blockHash·CONFIRMED·각3event 일치, 12tx/32events/266checks MATCH complete 및 모든 후속 불확실/불일치/원복 결과 대조.
- 두 actual grace·새 report·64초 canonical 청산, v2 baseline 불변 및 late legacy genesis 검증 범위 확인.
- 기존391개 컨테이너의 ID/이름/이미지/상태 분류 변경·누락0. 새소유3개 모두 Exited0/cleanup오류0/dataPreserved=true, 소유 label·internal network·shared namespace·게시포트0 확인.
- ADMIN/USER 인증은 실제 helper의 HTTP assert 완료와 JUnit PASS를 근거로 하며 별도 packet capture를 했다는 의미가 아니다. concurrent 등록도 기존동일Envelope 재등록 멱등성 범위다.

**최종 판정: 이 신규 등록legacy+B-v2/고정cut/controlled가격·시험EOA 범위의 Linux PG/Anvil 감사 Gate PASS.** 프로젝트 전체, 실제 MetaMask 인수, B-v1 실제배포, 취소noncefold, Windows원인해결 및 C자산정책은 이 판정에 포함하지 않는다. 과거 §4·5·8·10의 실패는 그대로 유지한다.

## 14. 후속 감사 보완 후 최종 소스 신규 Gate — 별도 실행 PASS

앞선 §13을 새 소스로 소급 승계하지 않았다. 순수 nonce-fold/취소 증거 verifier와 생산 observer의 전역50거래 union 보완을 검증한 뒤 새 identity로 1회 실행했다. 보완 과정의 실제 Mock 실패와 독립 검토 지적은 [승인 감사 후속 기록](dapp-b-v2-authorized-audit-2026-10-22.md)에 보존한다. 전체 오프라인 회귀가 종료된 뒤 실행하여 무거운 JVM을 겹치지 않았다.

### 14.1 실행 identity와 테스트 경계

- HEAD `f9353c33244565b30fb130dfb6159eab441a3523`의 detached 격리 worktree 미커밋 소스. productHash **`0029d2a80ae1b2bab5ceef74479fd3318f946afd246cb455a3f413f879972064`**.
- session **`phase-a-20261022171906-0b891df0`**, fixture **`futures-api-e9e07584`**. Windows 호스트 포트 게시 없이 새 내부 network/공유 namespace의 PostgreSQL·Anvil·Linux JVM을 사용했다. 기존 DB/체인·컨테이너·network를 재사용하지 않았다.
- Windows JDK21.0.10 오프라인 컴파일 산출물을 portable1014개 SHA로 고정해 기존 cached Temurin LinuxJRE21.0.12.1에서 실행했다. Linux JDK/Gradle 전체 build PASS는 아니다. runtime image `tms-bv2-audit-jvm:phase-a-20261022171906-0b891df0`, digest `sha256:501f26dd80d1985646ffcfb44f2a275d4bf34fbf285fab2d8afbbe4b6a1371fe`.
- runner/helper 실행정책은 §13과 같으며 추가 거래정책·한도·TTL/유예를 완화하지 않았다. helper SHA8b7e01... / compiled7d12b41...는 이 새 portable에서도 일치한다.
- 실제 JUnit **1 PASS/0 FAIL/0 SKIP/0 ABORTED**, **398251ms**. `result.json` PASS, externalProviders=false/publishedPorts=false/windowsRpcFix=false/metaMaskAcceptance=false.
- 새 소스 별도 직접 JUnit: 감사선택 **177 PASS/0 FAIL/0 SKIP/0 ABORTED**, 전체 backend **876 PASS/53 SKIP/1 ABORTED/0 FAIL**(930found/117309ms). 일반 Gradle test-worker 전체 성공 또는 SKIP/ABORTED의 성공 승격이 아니다. Foundry101 PASS는 §13의 기존 계약 회귀이며 이번에 재실행했다고 하지 않는다.

| 공개 배포 identity | 실제 값 |
|---|---|
| chainId | 31337 |
| controller | `0x1d0953123b2d11c49167848922208cd54c63308f` |
| rules | `0x5fb358d4457f2ba11ce71dc72dd52dfd13497237` |
| relayer | `0xbcc9374a6b1bfa4559408bea4d9ad1a05bb87660` |
| price signer | `0x7cb6963662e33d8939104231dcd2488e441671f7` |
| controller codeHash | `0xbb1a01f05e30dbe7002d49673233031009bfcef54073fe808675dae9d790ea0e` |
| rules codeHash | `0x7add47b6d79598194e39d76b0dba3edb6a6df636cb1b769bccde3eab128d75ab` |

### 14.2 사용자 승인·revision·정산과 감사

| revision | action | block | CONFIRMED txHash / 각 canonical3events |
|---:|---|---:|---|
| 0 | OPEN | 8 | `0x098446c9e3931bbb7ff413594e930c1c405b216497366ef0acea8c3f1e6d2550` |
| 0 | TOP_UP | 11 | `0xa90581015c5381ef84680be45154652b8f227713d285eaf055cb7f7a2ac16a4a` |
| 0 | CLOSE | 14 | `0xb3496af36e5bcf29b2e5b1314322969b06aa14815b3077216b0ee0eb45d7b0f5` |
| 1 | OPEN | 17 | `0x9ce34f635c71ebe88c58a1907ced6a1b4c77cde7dbac127abe7623edc30bc6d8` |
| 1 | TOP_UP | 21 | `0x53356ec6be1b6778acdb61eaf1baf8b078a4957f3da991a6aaf5a75d50ef10f8` |
| 1 | CLOSE | 24 | `0x41326b8078e6328dc3ccb4d60d817c33eb1d4153112b6d5829cd3d8dde411409` |

Long/Short 각 OPEN·START_CALL·LIQUIDATE를 포함해 **12 canonical tx/32events**, 실제 승인·Report·최종revision·DB journal 연계 MATCH complete=true/266checks/0mismatch. v2 baseline4→cut106으로 모든 V2 거래를 포함하고 Envelope 처음/끝 동일이다. 거래 없는 legacy는 마지막 frozen106에서 genesis 빈 이력 확인 후 최초 baseline 등록, 기존 legacyid1은 그대로다. legacy suffix107..106은 빈 범위이며 V2 이력을 잘라낸 것이 아니다.

미등록 target은 INCONCLUSIVE/false/UNREGISTERED_OR_UNROUTABLE_DEPLOYMENT, 실제 PREPARED unsigned 주문은 INCONCLUSIVE/false/UNSETTLED_OR_REVIEW 및 잠금 유지다. 별도 명시 unsigned 거절 경로 이후 REJECTED·RELEASE1·잠금 반환, 13ops/12tx/32event MATCH292/0이다. 이를 체인 revert로 표현하지 않는다. 새 fixture house projection1wei 오차는 MISMATCH/complete=true/292checks/1mismatch, 정확 원복 후 MATCH292/0이다.

사용자 wallet+house 합계 **13,000,000mKRW**, 최종 담보잠금·house 예약 해제를 확인했다. PostgreSQL 모의 담보 보존식이지 실제 ERC-20 예치·지급능력 보증이 아니다. 새 복제 migration schema의 v1행보존/unique/FK/append-only, baseline zero-row UPDATE/DELETE/TRUNCATE 거부·동일 Envelope 동시 재등록 및 실제 ADMIN/USER403/익명401도 통과했다. 빈 테이블 최초 INSERT 경합은 검증하지 않았다.

### 14.3 이번 실제 유예 — 이전 수치와 구분

| 방향 | call | deadline | 새 Report observedAt | liquidation | chain call→liq | wall 경과 |
|---|---:|---:|---:|---:|---:|---:|
| Long | 1792689847 | 1792689907 | 1792689926 | 1792689928 | 81초 | **87.111734047초** |
| Short | 1792689938 | 1792689998 | 1792689998 | 1792690001 | 63초 | **67.705291526초** |

둘 다 온체인 deadline=call+60, 새 적격 관측은 deadline 이상이며 Report→청산2/3초다. 실제 경과를 확인했고 warp하지 않았다. 이전 Linux5의71초/64초/+1 관측 값을 이번 결과로 재사용하지 않는다. 초기 AUTOMINE→실제 interval2 설정 후 block4→5 관측2.077579959초, 전체 cadence 전수 실측은 false다. 모든 유예/정산 후에만 frozen cut을 만들었다.

### 14.4 최종 독립 증거·보존 검토

별도 `inspect_bv2_full_audit_gap`이 새 결과 JSON/로그·소스·artifact·전후 inventory를 직접 읽고 **발견된 필수 수정 없음**을 보고했다. DB/RPC/컨테이너 조작·테스트 재실행은 하지 않았다.

- portable **1014개 SHA 누락0/불일치0**, 현재product0029·helper/compiledSHA 일치. 6즉시 action↔gate↔전체12개 audit item의 hash/blockHash/CONFIRMED/MATCH 일치 및 각각3events를 대조했다.
- 기존 **394개** 컨테이너의 ID/이름/이미지/상태분류 변경·누락0, 이후397개 중 새소유PG/Anvil/Java3개 모두 Exited0. cleanup errors=[]/dataPreserved=true, exact owner/internal/sharednamespace/PortBindings0 확인.
- 3판정/unknown/pending/unsigned 반환/정확원복·새 실측 유예·baseline 불변과 관측 범위를 확인했다. 과거 성공/실패·사용자 OPEN·평가 ledger/STOP/RESERVED는 이 새 시험 결과로 대체하지 않는다.

**최종 소스0029의 등록legacy+B-v2/고정cut/controlled가격·시험EOA Linux PG/Anvil 감사 Gate PASS.** 기술한도51거래 음성은 Mock 회귀이며 이번 실제 helper에서51거래를 생성하지 않았다. 새 순수 nonce-fold/취소 verifier는 아직 observer 미연결이므로 실제 취소 coverage는 여전히 INCONCLUSIVE다. B-v1 실배포·제품Synthetic/Chrome/MetaMask TOP_UP/CLOSE·Windows RPC/Vite 근본원인·장기 동적cut·C자산정책은 별도 미인수이며 전체 프로젝트 완료가 아니다.
