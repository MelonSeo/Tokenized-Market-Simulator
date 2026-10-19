# Futures 신규 PG/Anvil 실패 조사와 재검증

> 2026-10-19 KST / **진행 중 — 최종 통합 PASS 아님**
> 기능 브랜치 `feat/futures-engine`, 커밋 HEAD `93e5445`. 아래 재검증은 미커밋 Gate4b/5 소스도 포함한다. 기존 `melon-init`/KRX/Google/playground와 실패 증거는 보존한다.

## 1. 실패 명령과 최초 오류

공통 wrapper: `python deployment/phase-a.py fixture --session deployment/runtime/<session> --case futures-chain`.
실제 실패한 하위 명령: `backend/gradlew.bat test --offline --no-daemon --rerun-tasks --tests *FuturesChainPostgresAnvilE2ETest`.
케이스: `actualEngineReceiptsLossRecoveryGraceAndSameRaw`. 각 실행은 테스트 메서드 **1 FAIL/0 PASS/0 SKIP**이며, `CommandFailed`는 이 하위 Gradle 종료 코드1을 wrapper가 보존한 결과다. Docker 생성·DB marker·clock preflight·로컬 계약 배포가 실패한 것과 구분한다.

| session / child | 최초 실패 | 분류와 근거 | 최소 조치 |
|---|---|---|---|
| `phase-a-20261018145558-84d44508` / `futures-chain-e7f17884` | `EmptyResultDataAccessException`, 당시 테스트47줄/100줄 | **테스트 계정 조회 결함.** 기존 `AuthService.normalizeLoginId`는 ROOT 소문자로 저장하는데 테스트 helper는 `LONG/SHORT` 대문자가 섞인 원래 ID로 조회 | 테스트 helper만 `Locale.ROOT` 소문자로 맞춤. 제품 인증 수정 없음 |
| 같은 session / `futures-chain-bb5b31e6` | `FUTURES_RPC_UNAVAILABLE`, sender47줄, 테스트65/131줄 | 정상 종료·회복·top-up 검사 이후 **청산 전 gas 추정 경계**에서 중단. 당시 gateway가 RPC 오류 본문을 숨겼기 때문에 그 실행의 원래 revert selector는 미보존. latest 블록의 과거 timestamp를 새 거래 실행 시점으로 사용하는 문제를 후속 재현으로 확인할 필요가 있었음 | 새 거래의 gas 추정에 명시적 `pending` 태그, code/immutable 검증 및 actual mined deadline 검사는 유지. 원문 대신 allowlist 오류 코드만 분류 |
| `phase-a-20261018153553-e8c95a4a` / `futures-chain-09c4bec4` | 테스트 당시187줄: 만료 raw 복구의 예상 `REVERTED`, 실제 `CONFIRMED` | **만료 시나리오의 전제 부족.** 호스트 `Instant.now()>validUntil`만 기다렸으며 체인 후보/최종 블록이 만료 뒤라는 증거는 검사하지 않았음. Reconciler의 CONFIRMED는 verifier의 `blockTimestamp<=validUntil` 검사도 통과한 결과이므로, 단순히 30초를 기다렸다는 사실을 체인 만료와 동일시할 수 없음. 당시 정확한 최종 timestamp 수치는 별도 파일로 남기지 않았으므로 추정 수치를 쓰지 않음 | 실제30초 monotonic 경과와 `pending.timestamp>validUntil`을 모두 기다린 뒤 같은 raw 복구. final Receipt blockTimestamp도 TTL 뒤인지 검사. mined receipt는 bounded polling |
| `phase-a-20261018162056-eccae083` / `futures-chain-c51c062e` | `FUTURES_GRACE_NOT_OVER`, Gateway45줄/테스트152줄 | **확인된 시간 전제 결함.** 정제된 실제 증거는 latest `1792340670`, deadline/보고서 관측 `1792340730`, pending `1792340729`, latest 오류 `FUTURES_INVALID_REPORT`. idle automine의 확정 블록은60초 전이며 pending 힌트도 보고서보다1초 뒤처져 있었다. 호스트 또는 pending 힌트만으로 Solidity의 `observedAt<=block.timestamp` 충족을 보장할 수 없음. Windows NTP 문제로 단정하지 않음 | 새 futures-chain fixture만 실제1초 주기 블록. report의 원래 observedAt은 유지하고 canonical timestamp가 도달했는지 bounded readiness 확인. TTL은 canonical block이 validUntil을 넘은 후 복구. 기존 실패 fixture의 mining/시간/DB는 바꾸지 않음 |
| `phase-a-20261018162922-fb968e22` / `futures-chain-0b5beeeb` | `CHAIN_CLOCK_PRECONDITION_FAILED`, 당시 테스트54/58/112줄 | **새 fixture clock readiness 차단.** 최초 정상 포지션 준비 전 중단. SQL/호스트 preflight는 offset+0.247초/RTT0.612초로 통과했지만, 보존된 Anvil 마지막 timestamp는1792341147(16:32:27 UTC), 종료 metadata는16:32:20 UTC 부근이었다. Anvil 논리 블록 시각이 호스트보다 앞서는 증거이며 NTP 장애로 단정할 수 없음 | 이후 새 fixture만 실제2초 주기 블록으로 설정. asset/house 초기화 전에 bounded chain/JVM readiness 증거를 남김. 허용 차이는 그대로, 시각 override 없음. mining cadence는 metadata/실DockerCmd/Java guard로 일치 검사 |
| `phase-a-20261018164330-b375a5df` / `futures-chain-471177d2` | 동시 정산 검사 당시108/203줄의 assertion | **테스트의 비동기 mining 전제 누락.** 2초 주기에서는 raw 전송 승인과 실제 mining이 다르므로 즉시 concurrent reconcile의 WAITING은 정상. 정상 개설/종료와 양방향 청산의 prior assertions를 통과했지만 전체 테스트는 FAIL | receipt가 실제 존재하고 canonical hash가 맞는지 읽기 전제만 bounded 확인한 다음 두 정산 worker를 동시에 시작. 대기 중 DB 정산하거나 강제 mine하지 않음 |

이와 별도로 `phase-a-20261018142550-cd91140d` 초기 준비는 Docker LinuxEngine HTTP500으로 중단됐다. 이것은 **환경 문제**이며 위 Gradle 테스트 실패와 다르다. 에이전트가 Docker/WSL을 재시작하지 않았고, 제한된 읽기 확인에서 Server28.4.0 정상 응답이 확인된 후 새 session을 사용했다.

## 2. 변경 범위와 정책 불변

- `FuturesRpcGateway.estimate`: 새 거래를 `eth_estimateGas(...,"pending")`에서 추정. 블록 timestamp override, 조건 완화 또는 latest fallback 없음. 명세를 지원하지 않거나 거부하면 서명 전 차단한다. 같은 RPC service로 chain/code/immutable identity와 전송을 검증한다.
- `precheckCode`: web3j가 `error.data`를 JSON 문자열로 감싼 형태와 평문 hex를 처리하되, 최대4096자·알려진 selector만 고정 코드로 변환한다. 미확인은 `FUTURES_ESTIMATE_REJECTED`; 원문 error/인증/보고서/서명은 출력하지 않는다.
- `pendingTimestamp`: 후보 블록 시각은 테스트 전제용 힌트다. 정산의 정본은 explicit block의 canonical Receipt/Event이며 pending 시각만으로 정산하지 않는다.
- 테스트의 유예는 chain deadline 도달과 마지막 call 확정 이후 **실제 monotonic60초**를 모두 확인한다. TTL도 실제30초·chain pending 만료를 둘 다 확인한다. `evm_increaseTime`, timestamp override, 임의 sleep으로 성공 처리하지 않는다.
- Receipt를 기다릴 때 WAITING/RPC_UNAVAILABLE만 bounded 재조회한다. 예상과 다른 CONFIRMED/REVIEW_REQUIRED를 건너뛰거나 강제 실패로 바꾸지 않는다.
- 경제 산식, MMR5%, 유예60초, report TTL30초, 미래2초, DB 담보/손익 풀, 원문/nonce/hash 복구와 기존 현물 계약은 변경하지 않았다.

## 3. 오프라인 검증과 독립 검토

- Gate4b 초기35 PASS/3 SKIP → 검토 보완 후43 PASS/3 SKIP. 첫 보완 실행의1 FAIL은 Mockito의 예외 stubbing 재설정 문제였고 `doReturn` 수정 후 새 통과를 확인했다.
- pending gas 단위 추가 첫 실행43 PASS/1 FAIL/3 SKIP: web3j `error.data`의 JSON 문자열 wrapper 때문에 알려진 selector가 generic으로 분류됨. bounded JSON text 처리 후 새 선택 실행44 PASS/0 FAIL/3 SKIP.
- 검토 보완 후 전체 backend538 PASS/41 SKIP은 **pending gas/TTL 후속 변경 전 실행**이다. 현재 최종 소스 전체 회귀로 재사용하지 않는다. 웹107 PASS·Chrome desktop/mobile offline fixture PASS·Foundry52 PASS도 각각 기록된 별도 실행이다.
- 최초 독립 `review_futures_receipts`의 log canonical 메타데이터 결합/직접 Reconciler 테스트 누락2건을 수정했고 첫 재검토는 추가 필수 수정 없음이었다. pending gas와 새 시각 전제/actual E2E 결과를 포함한 최종 재검토는 별도로 수행한다.
- 전체 `forge build --offline`의 기존 Scenario stack-too-deep FAIL은 숨기지 않는다. Foundry52 PASS 및 새 `DeployFuturesFixture.s.sol` 단독 컴파일 PASS와 다르다.

## 4. 신규 재검증과 증거

- 네 번째 실행 당시 session은 `phase-a-20261018162056-eccae083`. 이후 실패 이력을 보존하고 신규 session/child로 진행한다.
- 새 test는 private run 하위에 `latest-gas-clock.evidence.json`, `liquidation-long/short.evidence.json`, `expired-same-raw.evidence.json`을 CREATE_NEW로 남긴다. source/manifest·DB·chain과 함께 보존하며 raw/signature/키/JWT는 넣지 않는다.
- latest-vs-pending 비교 결과, call deadline·최종 블록 시각, 실제 경과시간, TTL·실패 Receipt와 같은 raw/hash/nonce 여부를 새 실행으로 확인해야 한다.
- 완료 판정 전 남은 항목: 새 actual PG/Anvil, 최종 소스 전체 회귀, 최종 독립 재검토. 자동 위험 worker/HTTP·브라우저 선물/별도 JVM restart/선물 감사·대사/AI는 후속 미구현·NOT_RUN이므로 이번 core E2E 통과가 전체 선물/Phase D PASS가 아니다.

## 5. 보호 및 중단 기준

- 실패 fixture·로그·DB·chain은 보존하고 각 run에서 생성한 정확한 소유 컨테이너만 종료한다. 기존 Docker 네트워크/볼륨/사용자 환경 삭제 없음.
- 원문/nonce/hash 변경, 강제 정산, 불확실 거래의 해제, 경제 정책 변경이 필요하면 진행하지 않는다.
- 검증 전 Gate4b/5를 완료 커밋/병합하지 않는다. 원격 push·실시세·유료AI·Sepolia·외부 배포 없음.

### 2026-10-19 추가 보완

- 위 네 번째 증거를 반영해 pending 시각을 정산/만료의 정본으로 쓰지 않는다. 새 fixture만 실제 주기 블록을 생성하고, canonical block timestamp predicate를 확인한다. 유예는 실제60초 monotonic와 chain deadline 둘 다, 만료는 실제30초 monotonic와 canonical block의 TTL 초과 둘 다 필수다. 조건이 충족되지 않으면 bounded timeout으로 실패한다.
- mining cadence는 새 run metadata와 전용 test properties에 기록하고, Python Docker inspect가 실제 Cmd의 `--block-time 1`을 확인한다. Java guard도 metadata/config를 대조한다. 소유권/loopback/소스hash/2초 clock preflight는 유지한다. 다른 Phase A case의 mining 설정은 변경하지 않는다.
- Python guard/clock 새16 PASS. Java 선택 회귀는 재실행 중이며, 신규 별도 session/child에서 actual PG/Anvil을 다시 실행한다. 이 보완은 financial policy 변경이 아니다.

### 현재 재검증 상태

- 최신 mining cadence는 **2초**다. 앞의1초는 실패 당시 이력이며 현재 정책으로 재사용하지 않는다. [공식 mining 안내](https://www.getfoundry.sh/anvil/index.html)와 [시간 관리자 구현](https://github.com/foundry-rs/foundry/blob/master/crates/anvil/src/eth/backend/time.rs)은 참고 근거다. 공식 master가 고정 Docker image와 동일 버전이라고 가정하지 않으며 실제 clock 증거가 우선이다.
- `futures-chain-471177d2`의 실제 Long 청산: deadline1792342033, report observedAt1792342037, canonical block1792342039, 마지막 call 확정 후62,546ms. Short: deadline1792342037, observedAt1792342039, canonical block1792342041,64,330ms. 두 Receipt/DB 확인 이후 동시 정산 테스트에서 중단했으므로 **전체 E2E PASS로 승격하지 않는다**.
- 비동기 receipt 준비 전제를 보완한 뒤 같은 최신 제품 sourceHash의 `phase-a-20261018164330-b375a5df` 아래 **새 child**에서 재실행 중이다. 이전 child는 재사용하지 않았다. 현재 Java 선택44 PASS/3 SKIP, Python16 PASS이며 최종 actual E2E/전체 회귀/재검토 결과는 아래에 추가할 예정이다.

### 새 실제 PG/Anvil 재실행 결과

- 신규 child `futures-chain-2b2c3e6a`: **1 PASS / 0 FAIL / 0 ERROR / 0 SKIP**. DB loopback48461/RPC42147의 소유권·격리·clock 검증 후 실행했고, 생성한 컨테이너만 종료했다. 앞선 실패 child/로그/DB/chain은 보존한다.
- Long 청산: deadline1792342728, observedAt1792342733, canonical block1792342736, 마지막 call 확정 이후 **64,421ms**. Short: deadline1792342732, observedAt1792342737, canonical block1792342740, **68,354ms**. 양쪽 모두 canonical Receipt/Event 검증 및 DB LIQUIDATED·잠금 해제·손실 한도·badDebt 검사를 통과했다.
- 만료 raw: observedAt1792342745, validUntil1792342775, 최종 block1792342779, 실제 **34,158ms**, Receipt status0/REVERTED. 저장 raw/hash/nonce 불변과 OPEN 예약만 해제됨을 검증했다. 시간을 앞당기거나 정책을 변경하지 않았다.
- 실제 전송 후 응답 유실, 새 sender 인스턴스의 같은 raw 복구, 두 concurrent 정산의 단일 journal, 최종 미확정0·잠금0·house/journal 보존도 동일 테스트에서 통과했다. **새 sender 인스턴스는 별도 JVM 재시작 검증이 아니다.**
- 최종 두 번째 독립 재검토 `review_futures_receipts`: 발견된 추가 필수 수정 없음. pending 추정·정제 오류·canonical 시각 전제·2초 cadence guard·동시 정산 준비와 조사 문서를 읽기 검토했다. 검토자 직접 실행은 없으며, 위 actual 결과는 구현자가 확인했다.
- 같은 최종 소스에서 `phase-a.py regression`을 새로 실행 중이다. 완료 수치는 후속 기록 전까지 미확정이다. 자동 위험 worker·REST/Chrome 선물·별도 JVM restart·선물 감사/대사·AI 연결은 여전히 미구현 또는 NOT_RUN이다.

### 최종 소스 전체 회귀 결과

실행 sourceHead는 `93e544558066615ca7e87704168bc63cc2d1e7af`, 미커밋 변경을 포함한 productHash는 `fa949057e0f12034f99afc80a2bdac344a4dde1d888d92713f9c0647f7fb3ee8`이다. session/manifest를 새 값으로 덮어쓰지 않았다.

같은 session에서 새 `regression` 실행 완료: backend **580개 중539 PASS/41 SKIP/0 FAIL/0 ERROR**, 웹 단위 **107 PASS**, Chrome desktop/mobile **offline fixture PASS**, Foundry **52 PASS/0 FAIL/0 SKIP**. 기존 실행 수치를 재사용하지 않았다. Chrome fixture는 실제 선물 서버/체인 브라우저 데모가 아니다. 41 SKIP은 opt-in 환경 테스트이며 별도 실제 fixture 결과와 합산하지 않는다.

같은 제품 소스의 새 `futures-ledger-299995bd`도 **1 PASS/0 FAIL/0 ERROR/0 SKIP**. 실제 PostgreSQL 행 잠금/담보·house 준비/rollback 검증이며 chain Receipt 검증은 위 별도 futures-chain 결과를 따른다. 생성한 컨테이너만 종료하고 상태는 보존했다.

또 다른 신규 `futures-db-only-4d54fc1c`도 **1 PASS/0 FAIL/0 ERROR/0 SKIP**. 실제 PostgreSQL에서 DB_ONLY 현물/Faucet와 선물의 공유 available/locked 행 잠금 경합을 확인했다. 이는 ONCHAIN 현물 거래 실환경 인수를 대신하지 않는다. 세 독립 E2E는 각각 새 fixture이며 실패 DB를 초기화해 재사용하지 않았다.

최종 보안 점검은 이번18개 명시 경로의 staged 목록·diff와 private 경로/키·JWT 패턴을 확인했다. runtime·state·실제 환경 설정·원본 증거는 포함하지 않는다. 기존 사용자 README1줄/implementation-log7줄과 미추적 사용자 문서는 별도로 보존한다. 이후 로컬 커밋으로 HEAD가 바뀌어도 기존 session/manifest의 sourceHead/productHash를 고치지 않으며, 새 검증은 당시 identity guard에 맞는 새 session을 사용한다.

| 선물 기능 | 현재 판정 | 실제 검증 범위와 한계 |
|---|---|---|
| Long/Short, 2·5·10배 개설/전체 종료 | PASS | 새 PG 장부+실제 Anvil Engine, canonical Receipt/Event와 DB 정산 |
| 마진콜 후 회복/추가 증거금 | PASS | 같은 actual E2E의 명시적 action 호출. 자동 worker 아님 |
| 60초 유예 후 양방향 강제청산 | PASS | 실제 monotonic60초와 chain deadline, 새 보고서 및 canonical Receipt/Event |
| 손실C 한도·badDebt·house/journal | PASS | actual E2E 최종 보존식과 H2 경계 테스트. 지급불능 실운영 인수 아님 |
| 응답 유실·같은 raw/nonce/hash 복구 | PASS | 실제 Anvil 전송 후 클라이언트 응답 유실 주입, 새 sender 인스턴스 |
| 동시 중복 정산 방어 | PASS | 실제 PG의 두 reconciler와 단일 CONFIRM journal |
| 실제30초 보고서 만료·status0 정산 | PASS | 체인 TTL 초과 후 같은 raw 전송, 예약 해제와 raw/nonce/hash 불변 |
| invalid proof·REVIEW_REQUIRED 보존 | PASS | H2/Mock canonical 변조 및 Reconciler 테스트. 실제 체인 변조 성공으로 주장하지 않음 |
| 별도 JVM 재시작 | NOT_RUN | 새 sender 인스턴스 검증과 구분. 후속 필수 |
| 자동 위험 감시/선물 REST·UI/실Chrome 데모 | NOT_RUN | 후속 미구현 |
| 선물 전용 감사·기존 자산 대사 확장 | NOT_RUN | 후속 미구현. 기존 현물 감사의 PASS로 대체 불가 |
| AI 선물 Tool/Skill/RAG·실모델 평가 | NOT_RUN | 연결 미구현, 유료 호출 미승인 |

이 표는 core 단위 결과다. **전체 Futures 통합 성공 또는 최종 Phase A/D 통과를 선언하지 않는다.** 기존 전체 `forge build`의 Scenario 오류도 여전히 별도 FAIL 이력/미해결로 유지한다.
