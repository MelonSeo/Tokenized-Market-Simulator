# 전량 매도 정밀도 최소 보완안 — 승인된 설계 이력

작성: 2026-10-17. Google 인증과 별도 변경이다. 이하 문구는 제안 당시 조사·승인 경계의 이력이다. 이후 사용자 구현 승인을 받아 진행했으며 현재 구현·실행 결과는 [구현 보고서](sell-all-precision-implementation.md)를 따른다.

## 확인된 문제

- `tools/websocket-test-client/src/state.js:number`는 기본 소수4자리, `src/main.js:renderPortfolio`의 잔고 힌트는4자리/카드는8자리 표시다. 표시에는 JS Number 변환과 반올림이 적용된다.
- 실제 인수 BUY 수령 `1.328161833310517989`를4자리로 표시하면 `1.3282`다. 이는 정확한 보유량보다 많다. 실제 SELL 입력1.3282의 FAILED와 정합적이지만 당시 공개 오류 코드와 사용자 입력 경로는 수집하지 않아 실패의 정확한 예외는 미확정이다.
- `src/main.js:issueQuote`/`state.js:positiveAmount`는 소수18자리 입력을 허용하며, `src/api.js:quoteSell/sell`은 tokenAmount 문자열을 그대로 JSON 요청으로 전송한다. 서버 입력 계약은 유지 가능하다.
- `backend/.../portfolio/PortfolioService.java:Portfolio`의 tokenBalance는 BigDecimal **전체 잔고**다. lockedAmount/가용 잔고가 응답에 없다. 브라우저 `ApiClient.#request`는 JSON.parse를 사용하므로 JSON 숫자 값에서 원래18자리 문자열을 복원할 수 없다.
- 정확한 가용 수량 정본은 `backend/src/main/java/com/pricetrack/exchange/wallet/UserBalance.java:getAvailableAmount()`의 `amount.subtract(lockedAmount)`다. 전체 보유량을 전량 입력하면 잠긴 자산까지 포함할 수 있다.

## 추천 최소 변경

1. 기존 `GET /api/portfolio`에 **`tokenAvailableAmount` 문자열 필드 하나를 additive로 추가**한다. `getAvailableAmount().toPlainString()`을 사용한다. 기존 숫자 필드, DB, 거래/수수료/Quote/정산/잠금 정책은 변경하지 않는다. 전역 BigDecimal 직렬화 방식도 바꾸지 않는다.
2. 매도 모드에 `전량` 버튼을 추가한다. 버튼 클릭 시 인증된 본인의 portfolio를 새로 조회하고 해당 문자열을 Number/parseFloat/toFixed 변환 없이 입력한다. 버튼은 자동 주문·자동 Quote를 하지 않으며 기존 견적을 무효화한다.
3. 기존 WS 포트폴리오는 필드를 확장하지 않는다. WS snapshot을 전량 수량의 정본으로 사용하지 않고 클릭 때 REST로 재확인한다. 따라서 기존 WS 계약과 Android 소비자는 유지된다. Android는 선택적 새 필드를 사용해야 같은 정밀도 혜택을 받을 수 있으며 이번에는 Android를 수정하지 않는다.
4. 요청 중/거래 중/결과 불확실 시 비활성화하고, 로그아웃·계정 전환·매수 모드 전환 뒤 늦은 응답은 폐기한다. 누락/잘못된 형식/0/음수는 안전하게 거절하며 반올림값으로 대체하지 않는다.
5. 조회 후 다른 주문이 잠금을 만들 수 있다. 버튼은 시점별 가용 수량을 입력할 뿐 체결을 보장하지 않는다. Quote 및 최종 주문의 서버 잔고/잠금/가격 검증은 그대로 유지한다. 잔고 부족 시 자동 재주문하지 않는다.

UI만 수정하는 안은 정확한 잠금 제외 잔고를 얻을 수 없어 추천하지 않는다. 별도 endpoint나 전역 lossless JSON parser 도입보다 additive 필드1개가 변경 범위가 작다.

## 승인 후 테스트 계획

- 서버: 전체 잔고/잠금/가용 잔고 일치, 소수18자리·0·잠금 중 상태, 문자열 응답의 정확성, 다른 사용자 정보 비노출, 기존 portfolio 필드 및 입력량 검증 회귀.
- JS: `1.328161833310517989`, 잔여 `0.000161833310517989`, 큰 정수부, 잠금 제외 수량을 정확히 입력. 지수표기/비정상/필드 누락 fail-closed, Number 변환 금지, 기존 Quote 폐기, 중복 클릭, logout/계정·모드 변경/지연 응답.
- Chrome fixture desktop/mobile: SELL에서만 표시, 버튼 누른 뒤 요청과 입력 문자열 완전 일치, 자동 주문 없음, BUY 기존 동작 유지.
- 기존 Google 인증 및 서버/웹 회귀와 구분해 수정 후 새 결과를 남긴다. 새 격리 Synthetic PG/Anvil에서 잠금 없는 전량 매도 후 잔여mSEC0/Receipt·Trade·잠금0 확인, 잠금 있는 사례는 단위·통합 fixture로 구분한다. 기존 KRX/실제 시세를 호출하거나 기존 Google 거래 snapshot을 수정하지 않는다.

## 필요한 승인

`tokenAvailableAmount` 문자열1개를 portfolio REST 응답에 추가하는 읽기 전용 API 보완과 전량 버튼을 함께 승인받는다. 승인 전에는 UI/API 구현·재배포하지 않는다. Google 인증 커밋과 별도 변경으로 기록·검증한다.
