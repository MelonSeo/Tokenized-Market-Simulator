# C1 — 고정 공급 토큰과 실제 Vault 기본 자산 경계

작성 2026-10-23. C 전용 detached worktree HEAD `f9353c33244565b30fb130dfb6159eab441a3523`의 미커밋 후보다. 정책은 [ADR C-001](dapp-c-policy-adr-2026-10-23.md). 과거 B 검증 결과는 C에 승계하지 않는다.

## 구현

- `contracts/src/CMockCollateral.sol`: 18 decimals, cMKRW, 생성 시20,000,000개를 명시 treasury에 한 번 발행. owner/minter/mint/burn/upgrade/faucet 진입점 없음.
- `contracts/src/CMarginVault.sol`: immutable asset/controller, caller 자신 token만 deposit 또는 fundHouse, 자기 free만 자기 주소로 withdraw. 전송 전후 sender·recipient의 실제 잔고 차이를 대조하고 SafeERC20/ReentrancyGuard로 실패·비정상 토큰·재진입을 차단한다.
- 실제 자산과 `totalFree+totalLocked+houseBalance`를 분리한다. 직접 오발송은 `unassignedAssets`이며 자동 입금/출자로 인정하거나 sweep하지 않는다. deficit이면 명시 fail-closed다. 출자자는 환급권/LP 지분을 받지 않는다.
- C1 `lockedBalance/totalLocked`는0이며 이를 변경하는 진입점이 없다. lock/settle/Claims/거래 승인·pause는 C2 구현 대상이다. controller는 실제 배포 caller로 고정하되 C1에는 경제 특권 함수가 없다. 최종 controller 생성 관계를 이 factory 단위 시험으로 대신 인수하지 않는다.
- `contracts/test/CCustody.t.sol`: 정상/무권한/공급·보존식, allowance와 예치 구분, 전송 false/revert/fee/inflation/허위 mint/추가 차감/optional return,9개 교차 진입 재진입과 rollback, 두 owner fuzz.
- `deployment/c-foundry-check.py`, contracts README: offline·단일 작업·C 전용 cache/out·매번 새 실행 증거와 소스 snapshot. root 캐시 dependency는 읽기 전용 참조. Docker/RPC/배포/유료 요청을 실행하지 않는다.

## 실제 실행

Foundry1.5.1 / solc0.8.24 / OpenZeppelin 캐시5.1 계열. 계약 src/test/script/foundry.toml을 포함한 이 실행 전용 aggregate sourceHash:

`60a35d6ff5a20d64ff7389bc6b659680e6a566caff0b21d273c7ceaf491286cf`

| 실행 | 실제 결과 |
|---|---|
| `c1`, `20261022T195622Z-072ec70b` | sandbox compiler home 조회 실패, 실행 증거 보존 |
| `c1`, `20261022T195637Z-7ac9661d` |31 PASS /0 FAIL /0 SKIP, fuzz256 |
| `all`, `20261022T195706Z-b5da2d2e` | Foundry83 PASS /0 FAIL /0 SKIP이나 출력 wrapper cp949 UnicodeEncodeError로 종료1. 원본 로그/result 보존 |
| `build`, `20261022T195735Z-3434ceef` | 전체 build PASS /exit0. Scenario.s.sol 포함, skip/viaIR 우회 없음 |
| `all`, `20261022T195802Z-8e6fef66` | 출력 UTF-8 수정 후83 PASS /0 FAIL /0 SKIP, wrapper exit0 /sourcesUnchanged=true |

83은 C1 신규31 + 이 worktree의 기존 현물/가격/선물52다. 다른 worktree의 B-v1/v2 추가 테스트 전체를 실행한 숫자가 아니다. 로그·source-snapshot은 각 `deployment/runtime/c-foundry/<run>/`에 있다. 컴파일 lint 경고는 보존하며 compiler 실패와 구분한다. 이번 build에서 기존 Scenario 컴파일도 성공했으나 실제 시나리오 배포/거래는 하지 않았다.

## 검토와 미완료

C0 및 C1 최초 독립 검토 `review_c_boundaries`는 각각 **발견된 필수 수정 없음**이다. C1 검토자는 실제3소스/테스트/runner/README/ADR/본 보고와83PASS/build 원본 증거·소스해시를 독립 대조했으며 테스트 재실행·서비스 변경은 하지 않았다. C2에서 Vault가 변경되면 C1 회귀도 신규 소스로 다시 실행해야 함을 확인했다. C1 PASS는 거래 금융·청구권·실환경 자산 인수가 아니다. C2~C7의 atomic lock/settle/FIFO, 실제 PG/Anvil·60초·MetaMask·projection·감사·서버 중단은 이 검토 시점 NOT_RUN이다.

최종 C 배포는 검토된 고정 공급 token의 실제 identity를 검증해야 한다. Vault가 IERC20를 받는다는 사실로 arbitrary fee/rebase/악의적 자산 지원을 보장하지 않는다. 본 테스트의 adversarial 자산은 오류 주입 전용이며 제품 담보가 아니다.

기존 root dirty10 및 보호156개 해시 변경0/누락0을 시작 시 확인했다. 기존 서비스·DB·Anvil·OPEN·평가 증거는 사용/변경하지 않았다. C 단위 EVM에서만 모의 토큰 생성·전송을 실행했다. commit/merge/push/외부 배포/유료 요청 없음.
