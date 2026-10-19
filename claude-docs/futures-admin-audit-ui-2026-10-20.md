# 선물 ADMIN 감사 최소 화면 — 통합 보완

## 범위와 발견 근거

원 Futures 계획 F14의 `ADMIN scope·이력 최소화면`은 기존 구현에서 API만 완료되고 웹 연결이 남아 있었다. 이 누락을 새로운 경제 기능으로 확대하지 않고 기존 `/api/admin/futures/audits` API에만 연결한다. 원본 사용자 계획, NXT 변경 및 보존 환경은 수정하지 않는다.

## 변경

- `src/futures-audit-panel.js`, `src/api.js`, `src/main.js`, `index.html`: ADMIN 운영 화면에 현물 Vault/Oracle과 별도의 FuturesEngine 감사 범위를 표시한다.
- 불변 기준점 GET, 수동 감사 POST, 최근20건 GET, 선택 실행 상세 GET만 사용한다. 기준점 생성/재설정, 정산 보정, 재실행·잠금 해제 API를 추가하지 않는다.
- MATCH/MISMATCH/INCONCLUSIVE와 coverage 완전성을 별도로 표시한다. 원시 정수는 문자열 그대로 표시하고 담보 온체인 예치·준비금 보증으로 설명하지 않는다.
- 자동 실행·자동 재시도 없음. 중복 클릭 차단, 계정/route/로그아웃 epoch로 지연 응답 폐기, 401 세션 만료. 응답 유실 시 이력 조회를 안내한다.
- 서버 감사/정산/청산/가격 계약은 변경하지 않는다.

## 검증

- 웹 단위: 새7개 포함 **127 PASS /0 FAIL/0 SKIP**. ADMIN/USER 경계, 지연 응답, 중복 클릭, 응답 유실, exact integer, 판정/coverage, 기존 경로 호출 검증.
- 실제 Chrome-to-chain: 기존 제품 Synthetic 브라우저 harness에서 신규 fixture ADMIN 계정을 명시 생성해 일반 로그인·기준점·수동 감사·이력·상세·desktop/mobile을 확인하는 시나리오를 추가했다. 실행 결과는 완료 후 아래에 기록한다. private admin 비밀번호는 fixture `secrets/`에만 저장하며 로그/보고서/Git에는 넣지 않는다.
- 단위·offline Chrome와 실제 서버/PG/Anvil/Chrome 결과를 합산하지 않는다. 기존 실제 브라우저 PASS는 새 UI 검증으로 재사용하지 않는다.

## 진행 상태

새 `futures-browser-bf973f2e` 실제 PostgreSQL/Anvil/Chrome **1 PASS/0 FAIL/0 SKIP**. 일반 사용자 선물 거래·30초 견적 만료·양방향60초 이상 청산·개인 WS와 함께 새 ADMIN 로그인·기준점·감사 실행·이력·상세·desktop/mobile을 실제 검증했다. `browser/result.json`의 ADMIN UI 실행id/status/coverage를 Java에서 실제 감사 이력·ADMIN role과 대조한다. 기존 fixture 재사용·초기화 없음.

웹 빌드 및 Chrome desktop/mobile **offline fixture PASS**는 위 실제 연결 실행과 별도다. `review_futures_admin_ui` 최초 독립 검토: 발견된 필수 수정 없음. 소스·테스트·문서·기존 서비스/보안을 읽었으며 직접 테스트/DB/private 접근은 하지 않았다. 검토 후에는 실행 결과만 누적했다. 전체 백엔드 영향 회귀 진행 중. 실제 LLM·Kiwoom 호출 없음.

수정 후 새 전체 회귀 `backend-admin-ui-java21`: **648중599 PASS/49 SKIP/0FAIL/0ERROR**. Node 브라우저 판정8PASS, Foundry52PASS/0SKIP와 전체 `forge build --offline`도 새 실행으로 확인했다. 빌드는 성공했지만 기존 lint 경고/노트가 남는다. 위 진행 중 문구는 실행 당시 기록이며 현재 회귀는 완료했다.

정제 증거: UI 감사 `b3d3f412-04d9-4cbb-a05b-14b81f353c05` MATCH/coverage complete, 신규ADMIN userId2. 전체9단계/개인WS40건, Long65초·Short62초 이후 청산. 후속 선물 감사는19transaction/38event 검증·complete, 기존reserve MATCH, 소유Node descendants28개 종료 후 생존0. `browser/result.json`, `product-browser.evidence.json`, `owned-process-cleanup.evidence.json`은 private fixture에 보존하고 Git에는 넣지 않는다.
