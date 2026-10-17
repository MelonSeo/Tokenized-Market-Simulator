# 전량 매도 정밀도 보완 — 구현·격리 검증·독립 재검토 완료

> 2026-10-17. `feat/google-auth`, 시작 HEAD `a98ea4b`. [승인 설계](sell-all-precision-design.md). Google 인증과 별도 변경이며 commit/merge/push하지 않는다.

## 변경과 계약

- `backend/src/main/java/com/pricetrack/exchange/portfolio/PortfolioService.java`: 기존 GET `/api/portfolio` 필드7개를 유지하고 `tokenAvailableAmount` **문자열1개만** 추가. `token.getAvailableAmount().toPlainString()`이며 `amount-lockedAmount`가 정본이다. 전체 잔고나 반올림 표시로 추정하지 않는다. DB/schema/잠금/주문/견적/수수료/정산 정책은 변경하지 않는다.
- `tools/websocket-test-client/index.html`, `src/main.js`, `src/sell-all.js`: SELL에서만 전량 표시. 클릭 때 본인 portfolio REST를 새로 읽고 문자열을 그대로 입력한다. Number/parseFloat/toFixed 변환·자동 Quote·자동 주문은 없다. 이전 Quote는 무효화한다.
- 자체 generation과 기존 세션 epoch/userId/side/busy/불확실 요청 상태로 늦은 응답을 폐기한다. 중복 클릭, 로그아웃/계정·방향 변경, 수동 수량 변경, 주문/견적 중 충돌을 차단한다. 누락/숫자 타입/0/음수/지수/18자리 초과 응답은 표시 잔고로 대체하지 않는다.401은 기존 세션 만료 경로를 사용하고 자동 재시도하지 않는다.
- `PortfolioEventPayload`는 그대로다. WS snapshot에 새 필드가 없어도 전량은 fresh REST로 얻으므로 WS 계약 확장이 필요 없다. 기존 `UserWebSocketPublisherTest`의 Portfolio 생성 인자만 보완했다.
- Android 코드는 접근·수정·빌드하지 않았다. 기존 numeric 필드는 유지하며 새 선택적 문자열 필드를 사용해야 정확한 전량 입력이 가능하다. 조회 후 다른 주문이 잠금을 만들면 기존 서버 검증이 거절할 수 있으며 체결 보장이나 자동 재주문은 없다.

## 테스트 파일과 도구

- `portfolio/PortfolioAvailableAmountTest`: 소수18자리/1wei 잔여/전부 잠금/타사용자0, JSON string/기존 numeric 필드 유지.
- `portfolio/SellAllPostgresAnvilE2ETest`: 새 owned Synthetic PG/Anvil에서 신규 USER·Faucet·BUY→부분 SELL 잠금→REST 가용 수량 확인→정산→fresh REST 전량 SELL→잔여0/잠금0/Receipt/Trade/두 대사 MATCH 및 coverage.
- `test/sell-all.test.js`: 정확한 문자열·가용/전체 구분·중복·역순 응답·취소/세션/모드·진행 중·형식/401. 외부 서비스나 DB 없음.
- `scripts/browser-smoke.mjs`: 기존 desktop/mobile에 새 버튼의 정확한18자리 입력/BUY 숨김/이전 Quote 무효/자동 Quote·주문0 및 모바일 overflow 검증 추가. Google은 오프라인 fixture만 사용한다.
- `deployment/phase-a.py`의 sellall case와 `support/PhaseAFixture`의 자식명 허용목록만 추가. 기존 소유권/시각/subnet/hash/외부 차단 guard 유지.

## 수정 전 기준 및 이번 실행

수정 전의 별도 새 회귀 `google-final-regression-20261017031656-3c6a5e16`: backend493PASS/37SKIP, web93PASS, Chrome desktop/mobile offlineGIS PASS, Foundry36PASS. 실제 사용자 매도에서 반올림 표시와 잔여량 문제는 Google 인수 문서 §10~11에 보존했다. 이 결과를 수정 후 실행으로 재사용하지 않는다.

- 최초 새 웹107개:106PASS/1FAIL. 테스트가 mutable context 참조 때문에 계정 변경을 놓치는 문제를 검출해 시작 context를 복사하도록 보완했다. 이후 새 웹 **107PASS/FAIL0/SKIP0**.
- `compileTestJava --offline --no-daemon`: PASS. 실행 환경 JDK21.0.10.
- `npm run test:browser`: 실제 Chrome desktop/mobile + 신규 전량18자리/자동주문0 + 기존 offline GIS 정상종료/PASS. Google 실제 SDK/계정 인수가 아니다.
- 새 Phase A session `phase-a-20261017060611-73283ef3` 준비. 도구 이미지는 새 고유 태그/로컬 캐시/network=none. sellall PG/Anvil E2E 실행 중이며 결과는 추기한다. 원래 Google/기존 KRX DB·Anvil/이미지/manifest를 재사용하지 않는다.
- 첫 `sellall-a045eaf8` 실제 PG/Anvil 실행은 Faucet 단계400/FAUCET_INVALID_KEY로1FAIL. 새 테스트의 임시 키가 기존 최소 길이 계약보다 짧았던 원인이다. 제품 Faucet 정책을 바꾸지 않고 UUID를 붙인 테스트 키만 수정했다. 실패fixture/로그/XML/DB/chain을 보존하고 새 fixture로 재검증한다.
- 독립 신규 `sellall-ba3ac40a`: **실제 PostgreSQL/Anvil1PASS/FAIL0/ERROR0/SKIP0**. 신규 USER2명 중 테스트 사용자만 Faucet1회, BUY1/부분SELL1/남은 정확한 전량SELL1의 Trade3건. 각 주문FILLED/Receipt statusOK/log 존재, 타사용자 가용잔고0, 부분SELL 미정산 시 lockedAmount와 REST 가용 문자열 일치, 마지막 SELL 후 mSEC amount/locked0 및 mKRW locked0. 자산대사MATCH, 거래감사MATCH와 dbComplete/chainComplete/cutStable/environmentVerified 모두true를 assertion으로 확인했다. 실제 Google proof나 외부 시세는 사용하지 않았다.

## 보존·승인 경계와 남은 검증

원래 Google 실제 인수 환경은 기존 이미지와 DB/chain을 그대로 보존한다. 제품 source 변경으로 그 환경의 source hash guard가 달라질 수 있으므로 manifest를 갱신해 우회하거나 새 제품을 그 환경에 배포하지 않는다. KRX worktree/image·DB·Anvil·baseline·capture·secret, 기존 NXT 미커밋 문서 보존. 새 runtime/키/로그/데이터는 ignored private 경로만 사용한다.

실제 Google 취소/중복 연결/일반 Chrome 본인ID·연결후신규WS 미완료와 이번 무과금 UI 검증을 구분한다. 키움/Toss/Google 외부 호출·유료AI·AWS/Sepolia 없음. 실제 PG E2E, 수정후 전체 회귀, 독립 검토 결과는 확정 뒤 기록하며 현재 모두 완료로 선언하지 않는다.

## 최종 수정 후 실행 결과

앞 절의 실행 중 표시는 당시 이력이며 현재 결과는 아래 표가 우선한다. 최종 소스의 새 session `phase-a-20261017060611-73283ef3`에서 E2E 후 `regression`을 연속 실행했다.

| 검증 | 수정 전 별도 실행 | 수정 후 이번 실행 |
|---|---|---|
| 백엔드 전체 | 530중493PASS/37SKIP/FAIL0/ERROR0 | **533중495PASS/38SKIP/FAIL0/ERROR0** |
| 웹 단위 |93PASS|**107PASS/FAIL0/SKIP0**|
| Chrome |desktop/mobile·offlineGIS PASS|**desktop/mobile·전량18자리/자동Quote·주문0/견적무효/모바일overflow/기존offlineGIS PASS**|
| Foundry |36PASS|**36PASS/FAIL0/SKIP0**|
| 전량 매도 실제 PG/Anvil |NOT_RUN|최초a045eaf8 **1FAIL**(테스트키), 신규ba3ac40a **1PASS/FAIL0/SKIP0**|
| Phase A harness/clock 단위 |이번 변경의 사전 수치로 집계 안 함|**15PASS**|

전체 실행의 추가SKIP1개는 새 opt-in SellAllPostgresAnvilE2ETest다. 전체 실행에서 SKIP됐으나 별도 신규 실제 fixture에서1PASS로 실행한 결과와 분리한다. 기존 PG Google 동시성/migration은 이번에 다시 실행하지 않았다. Google RSA/JWKS loopback·H2·서버 WS 만료·Chrome offlineGIS는 전체 회귀에 포함되지만 실제 Google 계정의 미완료 실패 경계를 대신하지 않는다.

Harness/clock 첫 단위 호출은 루트 cwd에서 test_phase_a_clock 모듈 import 경로가 맞지 않아12PASS+loader ERROR1이었다. 제품 수정 없이 `deployment/` cwd의 `python -S -m unittest test_phase_a test_phase_a_clock`로 새15PASS를 확인했다. 실패와 통과를 구분한다.

각 E2E 성공/실패 증거는 새 session의 fixtures/evidence/logs에 보존했다. 이번4개 postgres/anvil 컨테이너만 소유권 검사 후 stopped/exited 확인했으며 삭제·초기화하지 않았다. `.env`와 runtime session이 Git ignored임을 확인했고 강제추가·commit·merge·push하지 않았다. 현재 HEAD/원본 melon-init ref와 기존 NXT문서는 보존한다.

Chrome의 버튼 검증은 **오프라인 HTTP/WS fixture**, 실제 PG/Anvil 거래는 **Java HTTP/실제RPC** 검증이다. 이를 하나의 실제 Chrome→실DB→체인 라이브 실행으로 표현하지 않는다. 원래 Google/KRX 인수 환경에 새 JAR/웹을 배포하지 않았으므로 그 브라우저에는 전량 버튼이 아직 나타나지 않을 수 있다. Android 실기기·실제 공급자 거래·외부 배포는 미검증이다.

독립 최초 소스 검토 `review_sell_all_precision`: 발견된 필수 수정 없음. 당시 실제 E2E/최종 전체회귀는 미전달이었으므로 이 최종 결과와 문서 갱신을 재검토 대상으로 전달한다.

최종 독립 재검토 `review_sell_all_precision`: **발견된 필수 수정 없음**. 테스트키 보완·최종 수치/실패 이력·Chrome fixture/실제PG 구분·보존/미배포 경계를 확인했다. 검토자는 소스/문서 읽기 전용으로만 확인했으며 테스트 직접 실행/private 원본 증거 열람은 하지 않았다. `git diff --check` 통과, 현재 HEAD와 melon-init ref 모두 시작 a98ea4b 유지. 커밋·병합·push 및 기존 환경 재배포는 미실행이다.
