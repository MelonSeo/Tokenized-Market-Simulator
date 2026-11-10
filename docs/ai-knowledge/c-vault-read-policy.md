---
title: "C 사용자 지갑과 Vault 읽기 경계"
domain: custody
type: policy
version: 1
status: active
minimum_role: USER
updated_at: 2026-10-31
---

# C 사용자 지갑과 Vault 읽기 경계

## 자산 모델과 소유권

C는 사용자 EOA의 cMKRW와 CMarginVault의 free/locked를 사용하는 온체인 모의자산 모델이다. 운영자 통합 지갑과 PostgreSQL 사용자별 mKRW/mSEC 장부를 사용하는 기존 현물 및 legacy 선물 모델과 다르며 합산하지 않는다. cMKRW는 실제 원화나 주식 상환권이 아니다.

로그인 JWT의 사용자와 EIP-191로 소유권을 증명해 연결한 지갑을 서버가 대응시킨다. 주소를 질문에 적거나 사용자 ID가 같다는 이유로 소유권을 인정하지 않는다. getCAccount와 getCPosition은 ADMIN도 연결된 본인 지갑만 조회한다. 타인/없는 포지션은 구분해 노출하지 않는다. C positionId는 bytes32이며 legacy UUID 포지션과 다른 식별자다.

## 토큰 이동과 담보 상태

ERC-20 approve는 Vault가 지갑 토큰을 가져갈 allowance를 정하며 예치 자체가 아니다. CMarginVault.deposit은 호출자의 토큰을 transferFrom으로 Vault에 옮기고 그 호출자의 free를 늘린다. OPEN의 lock과 TOP_UP의 increaseLock은 이미 예치된 free를 locked로 옮기는 내부 장부 변경이며 그 시점에 지갑에서 토큰을 다시 가져가지 않는다. lock과 정산 권한은 변경 불가능한 Controller에 제한된다.

CLOSE 정산은 locked를 해제하고 손실 또는 수익을 반영한다. 이익은 house에서 지급 가능한 부분을 free로 적립하며, 미지급 부분은 소유자가 정해진 FIFO Claim으로 남는다. house 자금과 시스템 전체 totalUnpaidProfit은 개인 지갑 잔고나 개인 Claims 합계가 아니다. getCAccount는 개인 Claims를 열거하지 않으므로 개인 미지급 총액을 확정하지 않는다. CLOSED라는 포지션 종료 사실과 이익 전액 지급 여부는 별개다.

CMarginVault.withdraw는 msg.sender의 실제 free 한도에서 지갑으로 ERC-20을 직접 전송한다. 서버의 서명이나 승인 없이 사용자 지갑의 트랜잭션으로 호출할 수 있지만 locked 또는 미지급 Claim을 free처럼 인출할 수는 없다. AI는 approve/deposit/OPEN/CLOSE/withdraw를 실행하거나 서명·재전송하지 않는다.

## 조회의 검증 범위와 미지원 진단

기존 CCustodyRpcReader는 chain/deployment identity, 계약 코드, 소유자와 자산 보존 조건을 검사하고 EIP-1898 blockHash/requireCanonical 고정 블록에서 읽은 뒤 같은 공급자의 헤더를 다시 확인한다. observation에는 관측 시각·블록 번호·해시가 있다. 이는 단일 RPC의 고정 블록 재조회이지 전체 체인 감사나 재조직 불가능한 finality 증명이 아니다.

getCPosition의 historicalCollateral은 과거 담보 값이며 lockedCollateral은 현재 해당 포지션에 잠긴 값이다. ACTIVE/MARGIN_CALL/CLOSED/LIQUIDATED는 관측된 계약 상태다. 현재 값만으로 과거의 청산 사유, 과거 가격, Oracle Report, 체결 Receipt 전체를 추측하지 않는다. 현재 두 C Tool은 개인 Claims 전체, 청산 사건의 원인 증거, C 자산 전체 감사와 지급 능력 보증을 제공하지 않는다. 없는 증거는 PARTIAL 또는 확인 불가로 남긴다.

근거: contracts/src/CMarginVault.sol의 deposit/lock/increaseLock/settle/withdraw, contracts/src/CCollateralizedFuturesController.sol, backend/src/main/java/com/pricetrack/exchange/custody/api/CCustodyController.java, custody/runtime/CCustodyRpcReader.java, custody/auth/CWalletProofService.java. 이는 기존 구현을 설명하며 금융 정책이나 실행 권한을 새로 정의하지 않는다.
