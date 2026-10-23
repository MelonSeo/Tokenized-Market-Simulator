# C3 — 실제 C controller를 통한 고갈·FIFO·권한 유지

2026-10-23. **Foundry 단위 EVM 시퀀스**이며 실제 PG/Anvil·MetaMask 인수가 아니다. [C2 검증](dapp-c2-atomic-claims-2026-10-23.md) 통과 뒤 새 `CVaultEconomicScenarios.t.sol`만 추가했다. 금융 제품 계약 코드는 변경하지 않았다.

## 선택과 실제 구현

C2의 Vault 시험 controller와 달리 이 단위는 실제 `CCollateralizedFuturesController`의 새 rules/Vault를 사용한다. 시험 EOA 두 명이 직접/서명 relay로 먼저 OPEN하고, 이후 고갈 상황에서 기존 포지션을 종료한다. 토큰은 동일20m 고정 공급이지만 각 시험의 **실제 house 출자는50 최소단위 wei**다. treasury의 미배분 전체 잔고를 house로 인정하거나 부족을 숨기지 않는다. 사용자 담보도1,000wei이고 표시 토큰1,000개가 아니다.

- 두 owner Long10/진입100e8/담보1,000wei/수량100을 미지급 발생 전에 개설한다.
- A의110e8 CLOSE는 이익1,000 중50 지급·950 Claim, terminal/원금 반환. 미지급 중에도 자신의 담보 원금 free 인출이 가능하다.
- house100을 추가 출자한 뒤 B가 TOP_UP·105e8 CLOSE하더라도 이익500을 선지급하지 않고 A 뒤에 Claim으로 append한다.
- house가 둘 다 갚을 만큼 있어도 process 한 번은 선두 한 건만 처리한다.1wei 잔여/부분/완료 후 다음 head/빈 queue 무이벤트/완료 기록 보존/새 claim3 append를 검증한다.
- 미지급 OPEN 차단과 관리자 OPEN pause는 독립이다. 미지급0이어도 pause면 차단하고, pause를 풀면 새 유효 OPEN이 성공한다.
- 미지급+pause 중 deposit/withdraw/TOP_UP/CLOSE/fundHouse/process/cancel/START/CLEAR/LIQUIDATE가 각 기존 검증 조건 아래 작동한다.
- 실제 손실 정산만 house에 귀속한다. 담보 초과 손실1,000의 badDebt는 추가 현금이 아니며 실제 담보 손실1,000만 house에 증가한다. 다른 user의 free/locked/신규 예치·미귀속 직접 송금은 지급 재원이 아니다.
- 반복 operation의 중복 Claim/자산 지급 거부와 두 owner의 현금/부채 보존 fuzz를 실행한다. 상태를 vm.store/토큰 deal로 조작하지 않는다.

## 실행 결과

신규 C3 **9개(집중8 + fuzz1)**, 기존132개와 합쳐 **141 PASS /0 FAIL /0 SKIP**다. 작성자의 최초 예상10개가 아니라 실제 소스 함수 수와 Foundry 실행 수9개를 기준으로 기록한다. fuzz256회, exit0/source 불변.

- aggregate 계약 sourceHash `c42e04888a601d23cab83f9f885723e2bf779d19d8b92c3ce748df8cf1797dac`
- 실행 `deployment/runtime/c-foundry/20261022T202307Z-0cb29292`
- source-snapshot, stdout/stderr, result.json에 당시 코드·검증 로그 보존

## 검토와 남은 범위

C3 최초 독립 검토 `review_c_boundaries`는 **발견된 필수 수정 없음**이다. 실제 신규9개 테스트/보고서·141PASS 원본 로그/manifest와 테스트 해시 `1af07bb1…`, C2 이후 제품 계약 해시 불변을 대조했다. 검토자의 재실행·파일/서비스/Git 변경은 없다. C4 계획의 순수 projection 단위 구현을 이어간다. 본 단위의 vm.warp59/60초는 실제60초 경과가 아니며, 통제된 가격·시험 EOA이지 제품 Synthetic·실제 사용자 MetaMask가 아니다. 실제 C 자산 이동 PG/Anvil과 직접 거래 수집·재조직·전체 감사·서버 독립 인출 Gate는 별도 미완료다.

기존 B 성공/실패 환경·사용자OPEN·UI/AI worktree·평가 ledger/STOP/claim을 사용하거나 변경하지 않았다. 원본 병합/commit/push/배포/유료 API 요청 없음.
