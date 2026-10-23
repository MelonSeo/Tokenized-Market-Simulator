# B-v2 RPC 및 실행 전 가격 변경 조사 — PARTIAL / 실제 서버 관측 BLOCKED

> 2026-10-22. 기존 [브라우저 지갑 인수](dapp-b-v2-browser-wallet-integration-2026-10-22.md)의 후속 기록. 기존 성공/실패 및 정지 fixture는 수정하지 않았다. 이번 변경은 진단과 회귀 테스트이며, 거래 정책 변경이나 RPC 장애의 해결 완료가 아니다.

## 1. 기준과 실제 결과

- 작업 브랜치 `feat/dapp-user-authorization`, HEAD `f9353c33244565b30fb130dfb6159eab441a3523`. 기존 대규모 미커밋 B-v1/v2 및 다른 worktree 변경은 그대로 유지했다.
- 이번 신규 fixture productHash: `4fda0ffee9039249c3be19bed2219822e75e2fecf219f0e3555da24b41ddf19f`. 테스트/문서/웹 전체를 포괄하는 해시가 아니라 기존 Phase-A `product_hash`의 범위다.
- 기존 실제 MetaMask OPEN의 canonical Receipt/DB CONFIRMED는 이전 실행의 성공이다. 이번 소스의 새 거래 성공으로 소급하지 않는다.
- 기존 TOP_UP `fc3d4a18-348a-4688-b832-d09c24428a58`은 PRICE_CHANGED로 REJECTED, tx 없음, 추가 예약 반환. 기존 OPEN과 총잔고/기존 잠금은 보존됐다는 당시 기록을 유지한다.

| 이번 검증 | 판정 | 범위 |
|---|---|---|
| compileJava / compileTestJava | PASS | 기존 무과금 직접 JUnit 준비 경로 |
| 직접 JUnit 선물 회귀 | PASS | 158 PASS / 12 SKIP / 0 FAIL. 정상 Gradle 전체 회귀가 아님 |
| 새 진단/가격/복구 테스트 6개 | PASS | 아래 테스트 참조 |
| 새 실제 PG/Anvil 배포 및 Spring Boot 시작 | PASS | 아래 신규 identity에 한함 |
| 실제 서버 HTTP draft/RPC 관측 | BLOCKED | Vite 시작 EFAULT로 controlled probe 미실행 |
| 실제 MetaMask TOP_UP/CLOSE 재인수 | NOT_RUN | 환경/정책 문제 해결 전 사용자 서명 요청하지 않음 |
| 기존 503의 근본 원인 해결 | 미확정 | 기존 서버 로그에 단계/원인 없음 |
| 전체 B-v2 감사 | INCONCLUSIVE | 이번 구현 범위 아님 |

## 2. RPC 오류 경계와 최소 보완

`AuthorizedV2Config`의 ChainAccess는 verify/eoa/nonce IOException을 같은 `FUTURES_RPC_UNAVAILABLE`로 변환했다. 기존 예외 처리기는 응답에 code만 제공하고 내부 오류를 기록하지 않으므로, 당시 서버의 어느 RPC가 실패했는지 복원할 수 없다. 별도 JVM에서 첫 chainId 호출 지연/timeout이 관측된 기록은 있으나, 그 JVM과 실제 서버의 실행 조건이 달랐으므로 원래 서버 503의 확정 원인이 아니다. 서버는 이미 프로세스 범위 IPv4 옵션을 사용했다.

이번 변경:

- `FuturesDiagnostics`: 내부 UUID trace, 고정 enum stage, 고정 오류 분류, monotonic elapsedMs만 기록한다. 예외 원문/Throwable, HTTP 본문, JWT, 개인키, 서명, raw transaction, URL/비밀 설정은 기록하지 않는다.
- `ObservedFuturesRpc`: 기존 동기 Web3j 전송을 감싸 chainId, controller/rules code, executor/rules/TTL/signer/symbol/policy getter, EOA, user nonce, relayer nonce, gas price, estimate, broadcast를 구분한다. 결과/예외는 그대로 전달한다.
- ChainAccess IOException의 내부 cause를 보존하되 공개 API code는 유지한다. `AuthorizedV2Service`와 Worker/Sender에 시장 관측, Report 서명, DB prepare commit, estimate, raw 전 검사, raw 서명, SIGNED commit, 복구 단계를 기록한다.
- timeout 3초, 재시도 없음, 검증 순서, 시장 gate, 담보 예약, 서명/전송 및 동일 raw 복구는 변경하지 않았다.

관측 한계:

1. UUID trace는 **동기 호출 scope**에서만 이어진다. API submit과 별도 Worker의 trace는 다르며, operation 전 생애의 단일 correlation ID는 아니다. 기존 DB operation/시각과 함께 대조해야 한다.
2. 하위 RPC 단계 `OK`는 전송 반환을 뜻한다. JSON-RPC error, codeHash/immutable 의미 검증의 성공은 외부 DEPLOYMENT 등의 결과와 함께 판단한다.
3. Gateway의 현재 동기 경로가 대상이다. 미사용 async/batch/subscribe 인터페이스는 기존대로 위임하며 새 진단 대상이라고 주장하지 않는다.
4. 이번 신규 실제 HTTP probe가 BLOCKED이므로 새 서버에서 실패 원인을 특정한 결과는 아직 없다.

## 3. PRICE_CHANGED: 실행 흐름과 재현

현재 실제 코드 순서:

```text
사용자 Intent 서명 제출
→ owner/nonce/기한/배포 검증
→ 새 적격 시장가격 capture
→ 수량 계산/허용 범위 검증/서버 Report 서명
→ 고정 가격 시장 재검사
→ 담보 예약 + immutable 실행 Report/승인 metadata DB commit
→ Worker 처리 시작 및 시장 재검사
→ 배포 identity RPC → relayer nonce → gas price → calldata 검증 → estimate
→ 시장 재검사 → EOA/user nonce 재검사 → 시장 재검사
→ raw 서명 → SIGNED 선저장 commit → broadcast
```

- 사용자 서명은 최대120초의 범위를 승인하지만, 수락 후 DB에 고정된 **실행 Report**는 기존30초 TTL/5초 가격 신선도 및 고정 가격 동일성 검사를 적용한다.
- 5초 검사는 오래되거나 미래인 증거를 차단한다. provider/generation 검사는 다른 연결/공급자로 바뀐 증거를 차단한다. PRICE_CHANGED는 그들과 별개로 `fixed.price == current.price`를 요구한다. 새 tick이어도 가격이 같으면 이것만으로 거부하지 않는다.
- 실제 제품 Synthetic fixture는 1초 tick 설정이다. Worker는 신호 및 보조 주기로 처리하고, 그 사이 순차 RPC/estimate 및 DB 처리가 있다. 따라서 서명 수락 후에도 raw 서명 전 새 가격이 달라지면, **사용자 승인 범위 안이고 신선하며 같은 generation이어도** 거부된다.
- 당시 TOP_UP의 각 단계별 실제 ms는 원래 로그에 없으므로 추정치를 측정값으로 쓰지 않는다. 이번 진단은 다음 실행의 단계별 소요를 확보하기 위한 것이다.

새 `FuturesTransactionSenderTest.nextFreshSyntheticTickDuringEstimateRejectsFixedReportWithoutRepricing`는 실제 MarketGate와 고정 clock/provider/generation을 사용한다. estimate 중 75000→75001로 바꾸면 PRICE_CHANGED, raw 저장/전송0, 원래 Report 가격 불변, unsigned rejection 이후 예약 반환을 검증한다. 이는 정책상 재현이며 가격 산식 결함을 발견한 결과가 아니다.

`signedRecoveryIgnoresLaterMarketChangeAndKeepsSameRaw`는 응답 유실 후 SIGNED 상태에서 시장 재가격을 호출하지 않고 동일 raw/hash로 복구하며 nonce를 다시 선택하지 않음을 검증한다.

## 4. 개선 대안과 승인 경계

| 대안 | 장점 | 위험 및 필요한 변경 | 이번 처리 |
|---|---|---|---|
| A. 불필요 지연 감소 | 기존 실행 가격 정책 유지 | 실제 느린 RPC를 먼저 특정해야 함. identity 검사를 삭제/무기한 cache해서는 안 됨. 지연을 줄여도 1초 tick 경계 거부를 제거할 수 없음 | 진단 추가까지만. 근거 없는 timeout 증가/재시도/검사 제거 없음 |
| B. Report 및 예약 확정을 Worker 실행에 가깝게 이동 | 서명 UX와 실행가격 선택을 더 분리 | 승인 Intent 영속 상태와 금융 PREPARED를 분리하고 원자성/경합/실패 반환 설계 필요. API/metadata 불변 경계 변경 | 별도 설계 승인 필요 |
| C. unsigned PREPARED만 승인 범위 내 명시적 재가격 | 사용자의 bounded 승인 취지와 정상 tick 변화를 양립 가능 | 현재 append-only immutable 실행 metadata와 충돌. Report revision/예약 조정/operation·nonce 경합/감사 대응을 원자적으로 설계해야 함. SIGNED 이후 절대 금지 | 구현하지 않음. 별도 정책 승인 필요 |

권장: 우선 실제 서버에서 새 진단으로 RPC 지연을 특정한다. 안정적인 정상 tick 거래를 보장하려면 A만으로 충분하다고 주장할 수 없다. B 또는 C 중 실행 가격 확정/불변 경계를 명시적으로 다시 승인받아야 한다. 현재는 PRICE_CHANGED 삭제, 고정 Report 교체, 자동 재서명/재주문을 하지 않는다.

## 5. 신규 실제 환경 시도와 실패 증거

- 새 session `phase-a-20261022094325-6407a69a`, fixture `futures-browser-1b3e23ea`를 **1회** 생성했다. 이전 `futures-browser-9fe35e84`는 계속 STOP 상태다.
- 신규 PG35229 / RPC45629, 별도 계약 배포와 DB identity 사전검사 통과. 실제 Spring Boot Java21.0.10 PID26984, loopback43808, PostgreSQL JDBC/Hikari 연결과 Tomcat 시작 확인.
- 이후 Vite가 `Error: listen EFAULT: bad address in system call argument 127.0.0.1:30585`로 종료했다. `browser-failure.json`은 `Browser web exited`. **Gradle/Docker 포트 게시 실패가 아니라 이 실행의 Node listener 경계**다. 이전 RPC 503과 같은 원인이라고 단정할 근거는 없다.
- `check-server-rpc-observation.py`는 새 DB의 controlled fixture 계정/공개 시험 scalar만 쓰고 소유증명·nonce·draft까지만 점검하도록 준비했으나, 웹 준비 실패로 실행하지 않았다. 독립 MetaMask 승인/거래 증거가 아니며 EIP-712 거래 제출도 하지 않는다.
- parent exit1, own web/backend 정리 및 own PG/Anvil 정지 후 로그/DB/chain을 보존했다. 실패 fixture 재시작/재사용, 새 포트로 반복, 전역 네트워크 변경, Docker/WSL 재시작은 하지 않았다.
- private 로그: session `logs/`, session 루트 `browser-failure.json` 및 before/after container inventory, fixture `backend.private.log`/`web.private.log`. 민감 원문을 보고서에 복사하지 않았다.
- 종료 후 기존359개 컨테이너의 ID/이름/상태 분류 변경0, 신규2개만 Exited(0). 기존 보호 기준156개 SHA-256 대조 변경0(16진수 대소문자 표기 정규화). 기존 성공 OPEN fixture도 재시작하지 않았다.

## 6. 테스트와 독립 검토

- private direct-JUnit harness `run-offline.py classpath`, `run-offline.py futures`: `classpath-20261022T093643850162.log`, `futures-20261022T093853336774.log`. 158 PASS / 12 SKIP / 0 FAIL. H2/Mock 및 직접 JUnit 결과이며 실제 PG/Anvil 거래 전체 PASS가 아니다.
- 신규 `FuturesDiagnosticsTest` 4개: 원인/단일 delegate 호출 보존, timeout 고정 분류와 같은 trace, 민감 원문/Throwable 미기록, null-message/trace cleanup, elapsed/result 투명성.
- 신규 Sender 가격/복구 2개는 위 §3 참조. 웹/Foundry는 이번에 변경하지 않았고 이전 PASS를 새로 실행한 결과처럼 쓰지 않는다.
- 별도 검토 `review_rpc_diagnostics`: 핵심 소스/주변 로직/신규 테스트/실제 JUnit 로그 확인, 발견된 필수 수정 없음. 검토자가 테스트를 재실행한 것은 아니다. 위 관측 한계와 로깅 자체 예외 방어를 비필수 제안했다. 문서 후속 검토는 별도로 기록한다.
- 후속 문서/실제 서버 로그 독립 검토도 발견된 필수 수정 없음. 시험 Anvil 배포와 외부 배포를 구분하도록 문구를 수정했고, 실패 JSON의 session 루트 위치를 정정했다. 검토자는 Docker 상태를 별도 재조회하지 않았다.

## 7. 다음 실제 지갑 인수 조건

1. Node loopback listener 장애의 프로세스/환경 경계를 읽기 전용 최소 재현으로 확인한다. 전체 fixture 무한 재생성은 하지 않는다.
2. 새 source identity의 **새** fixture에서 actual-server draft/nonce RPC 관측을 수행하고 오류 단계/소요를 기록한다. 이전 실패 run을 재사용하지 않는다.
3. 정상 tick의 실행 안정성 개선에 필요한 가격 확정 정책을 사용자와 결정한다. 현재 정책을 조용히 완화하지 않는다.
4. 관련 금융/권한/복구 회귀와 별도 검토 후에만 사용자 MetaMask TOP_UP/CLOSE를 안내한다. 새 체인이라면 새 OPEN이 필요하고 이전 성공 포지션/잔고를 옮긴 것으로 표현하지 않는다.

이번 단계에 commit/merge/push/외부 배포/기존 환경 재배포/유료 AI/기존 DB·Anvil 초기화는 없다. 신규 격리 Anvil에는 시험 계약을 배포했다. C안의 실제 온체인 담보/자산 보관과 전체 감사 구현은 하지 않았다.

## 후속 실행 연결

[실행 환경 최소 진단 및 가격 확정 경계 후속](dapp-b-v2-execution-boundary-followup-2026-10-22.md): 같은 포트 Node/Vite 최소3건 PASS, 새 backend-only 실제 PG/Anvil/HTTP 소유증명·nonce·draft PASS, 선물 직접 JUnit160 PASS/12 SKIP. 과거 EFAULT/503 원인은 미확정이며 재가격은 설계 제안만이다. 위 실패 이력과 판정은 당시 기록으로 보존한다.
