# 기존 Scenario 전체 빌드 복구 — 2026-10-19

기존 전체 `forge build --offline`는 `script/Scenario.s.sol:61`의 stack-too-deep로 실패했다. Futures 전용 Foundry52 PASS와는 별개인 과거 결함이다.

## 최소 변경

`run()`의 BUY/SELL 구간을 내부 `_buy`/`_sell` 함수로 분리해 지역변수 동시 생존 범위를 줄였다. viaIR/컴파일 설정, 배포 순서, 키, 권한, 초기 유동성, 입력/가격, 최소수령량, executor, 서명 TTL, quoteId는 변경하지 않았다. 최종 검증에서 helper 안으로 이동한 지역변수 대신 원래 동일한 상수/quoteId를 사용한다.

## 새 실행 및 독립 검토

- `forge build --offline`: PASS. 기존 lint의 naming/unchecked transfer/test cheatcode/typecast 경고는 남아 있다. 경고를 전부 해결했다고 주장하지 않는다.
- `forge test --offline`: **52 PASS/0 FAIL/0 SKIP**.
- `forge script script/Scenario.s.sol:Scenario --offline`: **로컬 EVM dry-run PASS**, 서명 BUY/가격 상승 SELL 및 잔여 mSEC0/quote소비/수익 검증. RPC URL, broadcast, 외부 네트워크, 기존 Anvil은 사용하지 않았다. 실제 보존 체인 배포/거래 인수로 간주하지 않는다.
- `review_futures_scenario`: 실제 HEAD92ab261 대비 diff 및 현재 소스 검토, **발견된 필수 수정 없음**. 검토자는 직접 테스트/DB/private 증거를 실행하지 않았으며 위 실행은 구현자가 수행했다.

기존 사용자 변경과 Futures 감사 변경은 이 스크립트 커밋 범위에서 제외한다. 원격 push/원본 merge/환경 초기화 없음.
