# Futures UI P0-A / P0-B 구현·검증

작성: 2026-10-21. 구현·검증만 승인된 별도 worktree이며 원본 병합·커밋·push·배포는 수행하지 않았다.

## 기준과 작업 격리

- Work의 `tms-architecture-2026-10-20.md`, `tms-ui-presentation-plan-2026-10-20.md`를 저장소 외부 output에서 읽고 P0-A/B만 적용했다. 두 원본 문서는 수정하지 않았다.
- 조사 기준 및 작업 시작 HEAD: `melon-init@f9353c33244565b30fb130dfb6159eab441a3523`.
- 구현 브랜치: `feat/futures-ui-p0`. worktree: 원본의 ignored `deployment/runtime/futures-ui-p0-worktree`.
- 원본 dirty 9경로(추적 수정4/미추적5)를 확인했다. Work 작성 당시 상태와 달리 최신 AI 평가 관련 파일이 추가돼 있었다. 이 작업으로 원본 파일을 복사·stash·reset·clean하지 않았다.
- 원본의 AI 평가 ledger 두 개, STOP, 승인/실행 manifest, lineage child claim과 dirty 파일 SHA-256을 시작 시 보관하고 종료 시 다시 대조한다. 승인 한도/RESERVED/STOP 상태에는 접근 변경을 하지 않는다.
- backend, Solidity, API DTO, 금융 산식, Worker, 거래 서명, 멱등 요청, AI 모델/평가/프롬프트는 수정하지 않았다. Docker·DB·Anvil·KRX/Google/NXT 환경을 실행하거나 조작하지 않았다.

## P0-A: 포지션 표시

- `src/futures-panel.js`: 활성/처리 중 포지션을 먼저 정렬하고 종료 포지션을 구분한다. LONG/SHORT·배수와 최종 상태를 분리한다. 포지션 목록을 신규 개설 폼 위로 이동했다.
- `src/futures-display.js`: 금융 문자열을 Number로 바꾸지 않는 표시 전용 요약. mKRW 2자리/mSEC 6자리 이하를 생략하고 생략 부호를 표시한다. 극소 양수/음수는 범위 표현으로 표시해 0으로 오인하지 않게 했다. 정확한 원본 문자열은 접힌 상세에 모두 유지한다. 주문 입력값으로 요약을 사용하지 않는다.
- 미종료 카드: 담보·미실현손익·Equity·유지증거금·진입/현재 참고가격·노출수량. 종료 카드: 확정 실현손익·악성부채·진입/참고가격·노출수량. 종료된 담보 기록을 현재 잠금액으로 표시하지 않는다.
- 진행 중 유예는 `status=OPEN && riskStatus=GRACE`에만 표시한다. 종료 후 과거 GRACE/deadline은 이력 상세로 남긴다. 남은 시간은 화면 추정이며 0 이하이면 서버의 체인 확인 대기로 안내한다. 클라이언트 시간이 상태를 LIQUIDATED로 만들지 않는다.
- 현재 참고가격을 실제 청산 체결가로 표현하지 않는다. `priceAvailable`이 true가 아니면 평가 불가 경고를 표시한다. 관측 시각은 원본 상세에 남긴다.
- 긴 위험 안내는 접고 DB 담보/서버 executor/계약 검증/유한 손익 풀 한계는 상시 노출한다.
- 기존 입력 draft·동일 Idempotency-Key 재확인·계정/route epoch·REST 응답 순서 방어를 유지한다. 오래된 실패 응답도 최신 성공을 덮지 않도록 serial을 검사한다. 정확값 상세 열림은 포지션별로 보존하고 계정/route 변경 시 해제한다.

## P0-B: 감사 표시

- `src/futures-audit-panel.js`: 판정을 첫 배지로 표시하고 coverage.complete를 독립 표시한다. MATCH이면서 complete=true일 때만 녹색; MISMATCH는 적색, INCONCLUSIVE 또는 불완전 MATCH는 황색이다.
- 검증/전체 거래, 검증/스캔 이벤트, DB 검사/불일치, 감사 블록/불변 기준점 블록, 관측/DB 시각과 판정 사유를 우선 표시한다. 누락 수치는 0으로 추정하지 않고 `?`/확인 불가로 남긴다.
- 불일치/판정 불가 item reason 및 실패 DB check 이름을 요약한다. 원시 정수 expected/actual/difference, txHash, operation/position/user, 개별 상태·블록은 접힌 상세에 보존한다.
- 기존 명시적 POST 수동 감사와 GET 기준점/이력/상세는 그대로다. 화면 진입·상세 펼치기는 감사 실행이나 추가 조회를 하지 않는다. 자동 보정/기준점 생성/재시도 없음. 결과는 담보 온체인 예치·소유권·지급능력 보증이 아님을 유지한다.

## 변경 파일

웹 모듈 `tools/websocket-test-client/` 아래:

| 경로 | 역할 |
|---|---|
| index.html | 포지션 우선 배치, 위험 안내 축약/상세, 기존 ID 유지 |
| src/futures-display.js (신규) | 정확 문자열 기반 표시, 유예 안내, 원본 상세 |
| src/futures-panel.js | 표시 조립·정렬, 상세 열림/오래된 실패 방어 |
| src/futures-audit-panel.js | verdict/coverage 요약과 원시 증거 분리 |
| src/style.css | 기존 밝은 테마 유지, 선물 범위 scoped 레이아웃 |
| test/futures-display.test.js (신규) | 정밀도·극소값·종료/유예 경계 |
| test/futures.test.js | 정렬·원본 상세·응답 순서·펼침 보존 |
| test/futures-audit.test.js | 불완전 MATCH·원시 증거·누락/사유 |
| scripts/browser-smoke.mjs | 기존 Chrome fixture 재사용·격리 강화·화면 검증 |
| README.md | 표시/검증 방법 안내 |

본 결과 문서와 별도 worktree의 implementation-log에도 기록한다. 원본 로그는 수정하지 않는다.

## 실제 검증과 한계

| 실행 | 결과/범위 |
|---|---|
| npm ci --offline | 캐시만으로 별도 worktree dependency 준비, 성공 |
| 최초 표시 변경 후 npm test | 127 PASS, FAIL/SKIP 0 (기존 테스트만) |
| 경계 테스트 추가 후 npm test | 135 PASS, FAIL/SKIP 0 |
| 최종 npm test | 상세 열림 보존 테스트 추가 후 최종 실행 결과를 아래 확정 기록 |
| npm run build | Vite production build 성공; 최종 재실행 아래 기록 |
| 실제 Chrome headless + 로컬 REST/STOMP fixture | 전체 기존 화면 smoke 및 1366×768/390×844 선물 검증 성공; 최종 재실행 아래 기록 |
| 독립 검토 | 별도 reviewer가 실제 diff/신규 파일/DTO를 확인, 관련 31개 테스트 직접 PASS. 최종 결과 아래 기록 |
| 실제 서버/PG/Anvil/키움/Google/LLM | NOT_RUN. 표시만 변경했고 이번 Chrome는 fixture이지 실제 체인 E2E가 아니다 |

Chrome는 기존 `scripts/browser-smoke.mjs`를 재사용한다. 새 임시 프로필·랜덤 loopback HTTP 서버·정제 fixture만 사용하고 CDP Fetch로 이 서버 이외 요청을 차단한다. Google는 offline adapter, STOMP는 fake transport이다. 보존된 브라우저/실서비스는 조작하지 않는다.

추가 검증: 미세 수량·큰 정수 문자열 원본 유지, terminal GRACE 숨김, OPEN 유예 경계, 활성 우선, draft 유지, 상세 polling 유지/계정 격리, 동일키·지연 응답 회귀, 사용자/ADMIN 구분, XSS text-only 출력, 감사 펼침 시 API 호출 없음, MATCH/complete=true 및 불완전 MATCH/MISMATCH/INCONCLUSIVE 별도 색상, 가로 overflow 없음.

### 수정 전·후 화면

이미지는 Git에서 제외된 이 worktree의 `deployment/runtime/ui-p0-evidence/`에 남는다. BEFORE/AFTER는 실제 거래 결과가 아니라 결정적 fixture 화면이다.

- before/futures-terminal-1366.png, before/futures-terminal-390.png: 기존 상단/개설 폼이 포지션보다 먼저 나오는 화면. 카드 자체가 아래에 있어 이 이미지로 종료 카드 세부를 판정하지 않는다.
- after/futures-terminal-1366.png, after/futures-terminal-390.png: 종료 상태, 진행 중 유예 없음.
- after/futures-active-1366.png, after/futures-active-390.png: 활성 SHORT 및 담보/손익/정확값/조작 UI.
- after/futures-audit-1366.png, after/futures-audit-390.png: **fixture** MATCH, complete=true, 19/19 거래·38/38 이벤트. 실제 신규 체인 감사 건수로 오인하지 않는다.
- after/futures-audit-incomplete-MATCH.png, after/futures-audit-incomplete-MISMATCH.png, after/futures-audit-incomplete-INCONCLUSIVE.png: 판정/coverage 분리.
- 최초 browser 보완에서는 감사 route의 fixture가 없어 빈 응답에 대한 fallback을 캡처했다. 텍스트 `MATCH` 대기도 경고 문구에 걸릴 수 있어 부족했다. 이 초기 before 감사 이미지는 정상 감사의 수정 전 비교 근거로 사용하지 않는다. 이후 실제 `/api/admin/futures/audits` fixture를 추가하고 고유 reason·dataset·색상·원시값·호출 수를 검증했다. 중간 실행을 체인 감사 성공으로 주장하지 않는다.

## 발표와 후속 경계

- 발표 화면에서는 활성 포지션 → 마진콜 → 서버가 확정한 자동청산 → ADMIN 수동 감사 → 원시 증거 순서를 권장한다. 숫자 생략 부호는 상세 펼침으로 설명한다.
- OPEN/GRACE 남은 시간은 클라이언트 추정이며 확정은 서버/체인에 따른다. 종료 후 현재 참고가격으로 청산가격을 추정하면 안 된다.
- 실제 발표용 최신 서버 배포/실데이터 브라우저 재인수는 별도 단계다. 이번 worktree 변경을 원본이나 기존 KRX/Google 이미지에 반영하지 않았다. 실제 AI 평가의 STOP/불확실 상태는 그대로이며 이 UI 작업으로 해결했다고 주장하지 않는다.

## 최종 확정 기록

- 최종 소스 새 실행: `npm test` **136 PASS / 0 FAIL / 0 SKIP**, `npm run build` 성공, `node scripts/browser-smoke.mjs` PASS. 실제 Chrome 1366×768/390×844 선물 및 감사 화면과 기존 360px 전체 route 가로 overflow 검증 성공.
- 독립 검토 `review_futures_ui_p0`: 최종 문서/README/마지막 diff/DTO/스크린샷 확인 후 **발견된 필수 수정 없음**. 관련 4파일 **32 PASS / 0 FAIL / 0 SKIP**를 검토자가 직접 실행했다. 비필수 제안인 상세 펼침 보존은 구현 및 회귀 테스트로 반영했다.
- 원본 HEAD와 Git status 9경로가 동일하다(줄바꿈을 정규화해 비교). 보호 파일 **16/16 SHA-256 동일**: 사용자 dirty 파일 9개 + 부모/child ledger·STOP·manifest·승인·lineage 7개. 원본 AI 실행을 재개하거나 STOP/RESERVED를 변경하지 않았다.
- `git diff --check` 성공. worktree의 추적 runtime 파일 없음. 신규 파일은 표시 helper/test/본 문서이며, 키·실제 .env·DB/chain·private evidence·빌드 결과는 추가하지 않았다. Git commit/merge/push 없음.
- 새 화면과 소스는 별도 worktree에만 존재한다. 원본 서버에서 자동으로 보이는 상태가 아니다. 제품 서버/PG/Anvil/실모델 인수는 **NOT_RUN**이며 기존 성공 기록을 새 실행으로 재사용하지 않았다.

## 사용자 검토 후 표시 보완 — 2026-10-21

이 절이 현재 UI 표시 기준이며 위 136 PASS 및 생략 부호 설명은 첫 P0 구현 당시 이력이다. 원본 병합은 여전히 하지 않는다.

### 추가 수정

- `futures-display.js`: 생략 부호를 제거했다. `200000.000000000000000000`은 정확히 `200,000`, `200000.000000000000000001`은 `약 200,000`, `-100000.999`는 `약 −100,000.99`로 표시한다. 반올림이나 Number 변환이 아니라 기존 문자열 자릿수 절삭의 **표현만** 변경했다. 극소 비영 양수/음수 범위 표현과 소수18자리 원본 상세는 유지한다. 새 테스트 2개로 정확값/후행0/근삿값/중복 상태를 보강했다.
- `futures-panel.js`: OPEN 표시를 `진행 중`으로 정리하고 HEALTHY 행을 주 화면에서 반복하지 않는다. 위험 경고·최종 상태·원본 위험 상태 상세는 유지한다. 상태 전이나 판단 조건은 바꾸지 않았다.
- `futures-audit-panel.js`/`style.css`: 판정과 coverage 바로 아래에 거래·이벤트·DB 검사 건수를 3개 타일로 배치한다. 모바일 기준 블록과 시각은 label/value 한 행으로 줄이고 주요 수치의 크기·간격을 개선했다. 상세 원시 증거와 수동 실행/조회 경계는 그대로다.
- `index.html`/`style.css`: 기존 `#toast`를 문서 끝의 fixed overlay에서 header 다음의 문서 흐름으로 이동했다. 로그인/거래 알림의 내용·role=status·5초 타이머 및 인증 로직(`main.js`)은 수정하지 않았다.

### 로그인 알림 조사

기존 `main.js`의 toast는 실제 5000ms 뒤 숨겨진다. 따라서 캡처 시 일시적으로 나타난 알림이지만, 기존 fixed 배치는 그 시간 동안 실제 본문을 가릴 수 있는 UX다. 보완 전 모바일 감사 캡처에서 하단 범위 안내를 덮고, 로그인 직후 시장 캡처에서는 시세 카드 일부를 덮는다. 영구 로그인 장애나 잔존 알림으로 단정하지 않는다.

보완 후에는 position=static인 상단 안내로 배치해 본문을 덮지 않는다. Chrome는 로그인 직후 실제 알림의 visible=true, computed position, 현재 main notice와의 겹침을 측정하고 5초 자동 숨김도 실제 타이머로 기다려 확인한다. 첫 로그인 화면의 before JSON overlaps=[]는 그 화면에서 notice와 안 겹쳤다는 뜻이지 모든 스크롤 위치에서 겹침이 없었다는 뜻은 아니다. before 감사/시장 이미지를 함께 근거로 사용한다.

### 실제 전후 증거

보완 전 현재 P0 소스를 먼저 build/Chrome 실행해 **새 `polish-before/`**에 캡처한 후 소스를 수정했다. 이후 `polish-after/`에 새로 build/Chrome 실행했다. 기존 before/after 증거는 덮어쓰지 않았다. 경로는 이 worktree의 ignored `deployment/runtime/ui-p0-evidence/` 아래다.

| 화면 | 보완 전 | 보완 후 |
|---|---|---|
| 활성 포지션 데스크톱 | polish-before/futures-active-1366.png | polish-after/futures-active-1366.png |
| 활성 포지션 모바일 | polish-before/futures-active-390.png | polish-after/futures-active-390.png |
| 감사 데스크톱 | polish-before/futures-audit-1366.png | polish-after/futures-audit-1366.png |
| 감사 모바일 | polish-before/futures-audit-390.png | polish-after/futures-audit-390.png |
| 로그인 직후 모바일 | polish-before/login-notice-390.png | polish-after/login-notice-390.png |

실제 Chrome지만 REST/STOMP는 기존 격리 fixture다. 19/19 거래·38/38 이벤트·8/0 검사는 화면 검증용 값이며 실제 신규 체인 감사 결과가 아니다. 제품 서버/Google 실인증/PG·Anvil/키움·LLM 호출은 이번에도 NOT_RUN이다.

### 보완 검증

- 새 실행 1차: `npm test` **138 PASS / 0 FAIL / 0 SKIP**, build PASS, `node scripts/browser-smoke.mjs --polish-after` PASS. 1366×768/390×844 및 기존360px, 원본 수치 상세 보존, 불완전 MATCH 색상, API 추가 호출 없음, 모바일 주요 건수 같은 행·판정 인근 배치, 알림 static/비중첩/자동 숨김을 검사했다.
- 마지막 모바일 라벨 변경 후 최종 재실행 및 독립 검토는 아래 확정 기록에 누적한다.
- 추가 변경 범위: 위 제품 표시 5파일 + 웹 README + display 단위 테스트 + 기존 Chrome fixture + 본 보고서/별도 worktree 로그. 금융 계산/API/상태 전이/AI 평가 파일 수정 없음.

### 표시 보완 최종 확정 기록

- 마지막 라벨(제목/설명 명시적 분리)까지 반영한 **새 실행**: `npm test` **138 PASS / 0 FAIL / 0 SKIP**; `npm run build` PASS; `node scripts/browser-smoke.mjs --polish-after` PASS. 기존 136 PASS를 최신 결과로 재사용하지 않았다.
- 실제 Chrome 1366×768/390×844에서 포지션 및 감사 전후 이미지를 직접 확인했다. 주요 감사 건수 3개가 같은 행·판정 바로 아래에 표시되고 가로 overflow가 없다. 로그인 알림은 실제 visible=true 상태에서 static이며 본문 notice와 겹치지 않고, 기존 5초 자동 숨김도 통과했다. 기존360px 전체 route 회귀도 통과했다.
- 독립 검토 `review_futures_ui_polish`: **발견된 필수 수정 없음**. 관련4파일 **34 PASS / 0 FAIL / 0 SKIP** 직접 실행, 최종 소스·문서 및 모바일 감사/포지션/로그인 전후 이미지 확인. 금융/API/상태 전이/인증 JavaScript 변경 없음 확인. 검토자의 직접 테스트 수는 전체138과 중복되므로 합산하지 않는다.
- 원본 HEAD/status 불변, 보호16파일 SHA-256 모두 동일. 기존 사용자 dirty9경로, ledger/STOP/manifest/child claim 및 기존 환경을 보존했다. 원본 병합·commit·push·배포 없음.
- 실제 서버·PostgreSQL·Anvil·Google 실인증·키움·유료 AI 검증은 **NOT_RUN**. 최신 화면은 별도 UI worktree에서만 준비된 상태다.
