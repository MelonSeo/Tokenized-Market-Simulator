# Futures 감사·DB 자산 대사 — 구현 중 (2026-10-19)

Gate7 기준 HEAD는 `92ab261`이다. 이 문서는 후속 미커밋 구현의 기록이며, 기존 Gate7 PASS를 이 코드의 PASS로 재사용하지 않는다.

## 책임과 계산

- `futures/audit/FuturesAuditSnapshot`, `FuturesAuditAccounting`: REPEATABLE_READ/read-only DB cut에서 포지션·operation·transaction·journal·사용자 guard·house를 읽는다. 금융 원장 수정, 서명, 전송, 복구 호출은 하지 않는다.
- 사용자의 amount는 Faucet 및 현물 정산에 **확정 Futures CONFIRM journal의 실현손익**을 한 번만 더한다. 증거금 자체를 amount에서 다시 차감하지 않는다.
- mKRW lockedAmount는 활성 포지션의 확정 담보 + 준비 중인 OPEN/TOP_UP의 신규 예약액이다. 종료·청산·개설 실패 포지션의 최종 잠금은 0이다.
- house = 불변 H0 + journal house_delta 합, reserved = 미확정 operation의 reserved_house 합. CONFIRM의 wallet_delta + house_delta = 0; 손실 상한 및 badDebt는 기존 정수 산식을 재사용한다.
- 기존 `ReserveCalculator`의 사용자 원장 계산에만 Futures 효과를 합친다. operator/Vault/ERC20 공급량 계산과 기존 불변 현물 baseline은 변경하지 않는다.
- **A안: DB 수탁형 모의 담보. 온체인 담보 예치·사용자별 ERC20 소유권·미래 지급 능력 보증이 아니다.**

## 독립 온체인 감사

- `FuturesAuditService`: ADMIN 수동 실행, 별도 단일 worker(대기열 없음), 30초 작업 예산/512 RPC/50 transaction/250 block 제한. HTTP 대기 상한 40초이며 포화는 429다.
- 별도 Futures baseline은 신규 빈 Futures 원장과 실행 이력이 없는 Engine에서만 생성한다. executionId, chainId/주소, runtime code hash, 정책/H0, blockNumber/hash, DB 시각/fingerprint를 append-only 저장한다. 재설정 API는 없다.
- operation ↔ 저장 signed raw/hash/nonce/domain/signature ↔ 실제 transaction/Receipt ↔ canonical block ↔ 두 이벤트 ↔ 해당 거래 직전/직후 및 현재 position을 비교한다.
- 과거 상태는 거래 직전 블록에서 복원한다. 같은 포지션의 여러 operation이 동일 블록에 있어 개별 상태를 복원할 수 없으면 INCONCLUSIVE다. 현재 deadline/risk를 과거에 대입하지 않는다.
- 역방향으로 Engine 이벤트와 Engine을 호출한 모든 transaction(이벤트 없는 reverted 포함)을 스캔한다. DB에 없는 거래도 탐지한다.
- 최종 DB fingerprint와 pinned head/canonical hash를 재확인한다. DB/chain cut 변화, pending/REVIEW_REQUIRED, RPC/과거 상태 부족, 범위 초과는 INCONCLUSIVE다. 증거가 완전한 경우만 MATCH/MISMATCH를 판정한다.
- coverage(대상·검증 transaction, 스캔/검증 이벤트, 범위, complete)와 개별 거래 verdict를 분리한다. 일부 조회 실패를 전체 MATCH로 승격하지 않는다.

## API/저장 경계

`/api/admin/futures/audits`: POST `/baseline`, GET `/baseline`, POST(감사 실행), GET(최근20건), GET `/{id}`. 기존 ADMIN matcher를 사용한다. 임의 정산/보정/강제 종료는 없다.

신규 append-only `futures_audit_baselines`, `futures_audit_runs`만 추가한다. API/history에는 안전한 DTO만 저장하며 raw, 서명 원문, 개인 키, RPC 원문 오류를 노출하지 않는다. 내부 Cut/Snapshot은 서명 증거를 포함하므로 반환/저장하면 안 된다.

## 검증 상태

- 신규 단위 테스트: 정수 장부/잠금/house/중복 journal, historical prestate, complete/partial/moving-cut/reverse orphan. 실행 중이며 결과는 후속 누적한다.
- 실제 PG/Anvil: 새 `futures-audit` case를 준비했다. 신규 전용 fixture에서 baseline → Faucet → OPEN/TOP_UP/CLOSE → canonical 감사 및 기존 reserve MATCH → 의도적 house 불일치 MISMATCH를 검증할 계획이다. 최종 변조 fixture는 원복/재사용하지 않는다.
- 신규 테스트 작성 중 Java import 및 checked-exception 컴파일 오류가 있었고 테스트 코드에서 수정했다. 이를 제품 테스트 실패나 실제 chain 실행 결과로 혼동하지 않는다.
- 실제 제품 Synthetic/Chrome, CONFIRMED 별도 JVM 복구, AI 연동, 최신 전체 회귀 및 독립 검토는 아직 완료되지 않았다. 실제 공급자/유료 AI 호출 없음.

## 한계

bounded manual audit이며 대규모/장기 chain을 위한 페이지별 coverage 누적은 없다. 역사 상태를 제공하지 않는 RPC 및 동시 쓰기/빠른 블록 환경에서는 INCONCLUSIVE가 정상적으로 발생할 수 있다. pending 거래를 감사가 복구하거나 잠금을 해제하지 않는다. UI 연결은 아직 후속이다.

## 실행 누적 및 최초 독립 검토

- 수정 전 단위/기존 reserve 회귀 **50 PASS/1 SKIP/0 FAIL**, Python harness/clock **18 PASS**. SKIP은 실제 환경 opt-in 감사 클래스이며 PASS에 합치지 않는다.
- 신규 `phase-a-20261019082756-02b3bf7c/futures-audit-0fbae169` 실제 PostgreSQL/Anvil **1 PASS/0 SKIP/0 FAIL**. OPEN/TOP_UP/CLOSE의 canonical transaction3·event6, coverage.complete=true/MATCH, pending INCONCLUSIVE, house 1wei 의도적 차이 MISMATCH 및 기존 reserve 호환을 확인했다. baseline/history UPDATE/DELETE 거부, 조회 전후 사용자 잔고 불변도 확인했다. 변조 fixture는 원복하지 않았다.
- 이 결과는 최초 감사 소스에 귀속한다. 이후 독립 검토 `review_futures_audit`가 terminal Position.realized/badDebt projection과 확정 종료 operation 간 직접 비교 누락을 발견했다. 기존 journal/house/chain 검증이 이 두 필드 변조까지 보장하지는 못했다.
- 해당 비교, 종료 상태/action/version 일치 및 비종료 null 규칙을 추가하고 단위 변조 테스트를 보강했다. 새 실제 E2E에는 ADMIN 감사 URL의 HTTP 익명401/USER403/ADMIN200도 추가했다. 수정 후 새 session/fixture로 재실행하며 위 최초 결과를 수정 후 PASS로 재사용하지 않는다.
- 최초 검토자는 직접 테스트/DB/private 증거에 접근하지 않았으며, 추가 확정 필수 결함은 찾지 못했다. 재검토 및 수정 후 전체 회귀는 아직 진행 중이다.

### 수정 후 신규 실행

- 단위/기존 reserve **52 PASS/1 SKIP/0 FAIL**, 신규 `phase-a-20261019094753-bffb06d2/futures-audit-4b86bb80` 실제 PostgreSQL/Anvil **1 PASS/0 SKIP/0 FAIL**. 기존 정상/미확정/불일치 시나리오와 신규 ADMIN 감사 HTTP 익명401/USER403/ADMIN200을 모두 실행했다.
- `review_futures_audit` 1차 재검토: **발견된 필수 수정 없음**. terminal projection 누락 해소와 테스트 연결을 확인했다. 검토자는 직접 테스트/DB/private 증거를 실행하지 않았으며 실제 실행 결과와 구분한다.
- 별도 새 웹 단위119 PASS, 웹 build PASS, Chrome desktop/mobile **오프라인 fixture** PASS. 실제 제품 Synthetic/Chrome-to-chain 결과가 아니다。
- 수정 후 전체 backend 회귀는 현재 실행 중이다. 결과와 안전 커밋 여부는 후속 기록한다.

### 수정 후 전체 회귀 완료

동일 신규 session의 `backend-full` 새 실행은 **629중583 PASS/46 SKIP/0 FAIL/0 ERROR**다. SKIP46은 opt-in 외부/격리 환경 테스트이며 통과 수에 합산하지 않았다. 같은 회귀 실행의 web119 PASS/0SKIP, Chrome desktop/mobile 오프라인 fixture PASS, Foundry52 PASS/0SKIP를 각각 별도 로그로 보존했다.

준비 sourceHead92ab261에 당시 dirty audit 구현을 포함한 productHash는 `6c5c6930acb3d6dc4d17b98e20dd1acfea4a4d0592274fa4eb96dbcc97ba3246`이다. 기존 session/manifests를 변경하지 않았다. 독립 재검토 이후 제품 수정은 없고 실행 결과 기록만 추가했다. 실제 사용자 대상 제품 Synthetic/Chrome와 AI/최종 인수는 여전히 후속이다.
