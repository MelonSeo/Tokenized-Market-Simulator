# Google GIS 실제 인증 인수 — 주요 흐름 PASS / 실패 경계·일반 Chrome 직접 관측 대기

> 2026-10-17 KST. 구현 기준: [Google 인증 구현·오프라인 검증](google-auth-implementation.md).
> **실제 Google 신규 가입·재로그인·로컬 계정 연결 및 두 로그인 수단 유지는 확인됐다.** 직접 관측 한계와 남은 항목은 아래 최신 진행을 따른다. 준비 당시 NOT_RUN 기록은 역사적 단계로 보존한다. 인수 과정에서 제품 코드 수정·commit/merge/push는 하지 않았다. HTTP 횟수는 각 시점 `docker logs --tail 500` 안의 관측 건수로서 영구 전량 누적이 아니며, 아래 '누적' 표기도 해당 로그 창 범위다.

## 1. Git 및 기존 환경 보존

- 원본 루트 branch `feat/google-auth`, HEAD `a98ea4b47fdb66484efdc31d66ffddc4f2b75085`. Google 구현 및 기존 NXT 문서의 미커밋 변경을 유지했다. `.env`는 사용자 설정을 읽었으며 도구가 다시 쓰지 않았다.
- 기존 KRX 프로젝트 `exchange-kiwoom-e2e-20261014171302-662c5ba0`, 보존된 DB/Anvil, 기존 playground는 사용·정지·초기화·재배포하지 않았다. 컨테이너 이름/상태/포트의 읽기 전용 충돌 점검만 수행했다.
- 기존 KRX 및 playground image를 덮어쓰지 않았다. 새 이미지 tag에 현재 JAR/웹 빌드를 넣었다. 기존 `playground-current.json` 포인터도 변경하지 않았다.
- 새 private 준비 adapter는 `deployment/playground.py`의 빌드 recipe, clean environment, private writer, source hash, image identity, compose/mount ownership 검사를 재사용한다. 원본 제품/공용 도구를 수정하지 않고 ignored runtime에만 배치했다.

## 2. 신규 격리 환경

| 항목 | 이번 환경 |
|---|---|
| run | `google-live-20261016175834-646c26ef` |
| Docker project | `exchange-google-live-20261016175834-646c26ef` |
| 웹 | **http://localhost:5173** — 반드시 이 origin으로 접속 |
| backend | loopback 전용56856 → container8082 |
| PostgreSQL | 새 인스턴스/데이터 디렉터리, 호스트 공개 포트 없음 |
| Anvil | 새 persistent chain/전용 operator와 price signer/신규 계약, 호스트 공개 포트 없음 |
| 시세 | SIMULATED / Synthetic |
| 외부 통신 범위 | 실제 Google SDK/JWKS만 이번 승인 대상. Toss/Kiwoom/유료 AI 비활성 |
| 데이터·로그·secret·브라우저 profile | 이번 run의 ACL 제한 runtime 하위. Git ignored |

Docker Server28.4.0 응답을 확인했다. 새 전용 network/subnet과 port5173을 사용하며 기존 network/volume을 삭제하지 않았다. postgres/anvil은 healthy, backend health=UP, 웹이 localhost5173에서 응답한다. source hash와 신규 image identity, persistent mount가 이번 run 아래인지 검사했다.

`backend/.env`에서 **Google 관련3개 값만** 새 전용 secret에 반영했다. 기존 DB URL, private keys, JWT secret, 관리자 암호, Toss/키움/AI 키를 가져오지 않았다. 새 DB 암호/JWT key/operator/signer/test-admin 암호는 이번 환경용으로 생성했다. Google Client ID 원문과 모든 secret은 이 문서/일반 출력에 기록하지 않는다.

## 3. 설정 및 준비 결과

| 점검 | 상태 | 근거 |
|---|---|---|
| feature 활성화/Client ID 존재 | PASS | `.env`의3개 키만 확인; 원문 출력 없음 |
| 실제 backend 적용 | PASS | `/api/auth/google/config` enabled=true, 반환Client ID와 전용설정 일치 여부를 bool로 확인 |
| Origin의 로컬 설정/접속 주소 일치 | PASS | GOOGLE_ALLOWED_ORIGINS에 localhost5173; 새 웹도 같은 주소 |
| Google Cloud 등록 화면 일치 | NOT_RUN | 사용자가 Cloud 설정 완료했다고 알렸으며, 정확한localhost5173 등록 여부를 추가 확인 요청했다. 도구가 Cloud 계정관리 화면에 접근하지 않음 |
| 신규DB/Anvil 분리 | PASS | 고유 project/data/secret/network/mount·새 image 검사 |
| 신규 대사 기준점 | PASS | 관리자만1명, Order/Trade/Faucet/baseline각0건 확인 후 새 baseline을1회 생성 |
| Chrome 준비 | PASS | 별도profile의 실제 시크릿 Chrome, 사용자 조작용 창 열기 |

준비 도중 backend 기동 직후 probe에서 `RemoteDisconnected`로 adapter가 종료됐다. 이후 **동일한 신규 환경**을 읽어 health=UP/SIMULATED/Google 활성화를 확인했고 baseline evidence와 ready marker가 없음을 확인했다. 새 DB의 `users=1, orders=0, trades=0, faucet_grants=0, reserve_baselines=0` 확인 후 최초 baseline만 생성했다. DB reset/재배포/제품 정책 변경은 없었다. 이 준비 실패는 실제 Google 인증 실패가 아니다.

## 4. 사용자와 순차 검증

최초 사용자 조작 전 관찰(UTC18:06:28): authenticated=false, 개인 이벤트0. 실제 SDK 로그인 버튼 클릭/Google token 검증은 아직 하지 않았다.

1. Google Cloud Authorized JavaScript origins에 `http://localhost:5173`이 등록됐는지 사용자 확인.
2. 전용 Chrome에서 로그인 → Google 로그인/신규 가입 → 공식 Google 버튼. **계정 A**의 선택·비밀번호·동의는 사용자만 수행.
3. 성공 시 Faucet/거래 전에 잠시 멈추고 사용자 완료 통보. 새 users.id, USER role, google 연결 여부, nullable loginId/email, 잔고0/잠금0을 **새 DB에서만** 확인한다. sub/email/token 원문은 수집하지 않는다.
4. 로그아웃 후 같은 A 재로그인, 내부 userId·잔고·Faucet 이력 유지와 REST·포트폴리오 확인.
5. 별도 로컬 계정을 브라우저에서 사용자가 정한 비밀번호로 가입. 신규 fixture의 Faucet/모의 거래로 자산과 Order/Trade 기록을 준비하고 snapshot 후 연결을 확인한다. Google **계정 B**로 명시 연결하며 로컬 비밀번호 입력도 사용자만 수행한다.
6. 연결 전후 내부 userId/role/잔고/lockedAmount/Order/Trade/Faucet 소유권 snapshot 동일, 기존 로컬 로그인 및 B Google 로그인 경로 확인.
7. 개인 WS는 승인된 사용자 조작으로 실제 개인 이벤트가 발생한 뒤 판단한다. 단순 연결/포트폴리오 GET만으로 개인 이벤트 수신 PASS라고 쓰지 않는다.
8. 취소·동일Google sub의 다른 로컬 계정 연결 거부 등 실패 경계 확인. 사용자 거부/취소가 기존 사용자 장부 변경으로 이어지지 않는지 확인.

**성공적인 신규 Google 가입과 성공적인 별도 로컬 연결을 모두 검증하려면 서로 다른 계정 A/B가 필요하다.** A가 이미 신규Google 계정으로 가입된 뒤 같은 A를 다른 로컬 계정에 붙이면 올바른 결과는409 거부다. 계정 하나만 가능하면 이를 자동 병합·삭제·초기화로 우회하지 않고 연결 성공 시나리오를 NOT_RUN으로 남긴다.

## 5. 실제 인수 판정 — 사용자 조작 전

| 시나리오 | 판정 |
|---|---|
| 실제Google 신규가입/서비스JWT | NOT_RUN |
| 같은Google 로그아웃·재로그인 | NOT_RUN |
| 로컬계정 명시Google연결 | NOT_RUN |
| 실제 연결 전후 자산/Order/Trade 소유권 | NOT_RUN |
| Google 인증 이후 REST/개인WS/포트폴리오 | NOT_RUN |
| 실제Google 취소/중복연결 실패경계 | NOT_RUN |
| 실인증 후 최종 회귀·별도 검토 | NOT_RUN |

이전 오프라인493PASS/37SKIP, 웹93PASS, Foundry36PASS 및PG/Anvil 증거는 구현 문서의 이전 실행이다. 이번 실제 Google 결과로 재사용하지 않는다.

## 6. 증거·종료·보안

- 일반 보고서/로그에 Google ID Token, 서비스JWT, 비밀번호, Client Secret, Google 화면 전체/계정 선택 화면/원문 응답을 저장하지 않는다.
- 앱 탭의 관찰 도구는 Network domain/HTTP payload/headers/console/screenshot 수집을 하지 않는다. 허용된 개인 WS 이벤트 type·수신시각·orderId·고정상태 및 앱 로그인 boolean만 기록한다. Google 인증창은 조회·조작하지 않는다.
- 검증 서비스4개는 사용자 수동 인증을 위해 실행 중이다. 확인된 새 project만 종료 가능하며 데이터와 baseline은 보존한다. Chrome은 전용 시크릿 창이고 종료 시 해당 인증 세션을 마친다. 기존 사용자 Chrome profile을 사용하지 않는다.
- 필요 시 이번 환경만 종료:

```text
python deployment/runtime/google-live-session.py stop --run deployment/runtime/google-live-20261016175834-646c26ef
```

제품 문제가 확인되면 새 주문/추가 연결 시도를 중지하고 원인·최소 수정안부터 보고한다. 실제Google 인증 조작 완료 전 전체 인수 PASS나 최신 통합 완료를 선언하지 않는다.

## 7. 실제 신규 Google 가입 확인 — 2026-10-17 KST03:33~03:35

사용자가 전용 Chrome에서 계정 선택·인증·동의를 직접 수행하고 로그인 완료를 알렸다. 도구는 Google 계정 선택/비밀번호 화면을 조회하거나 조작하지 않았다.

| 확인 항목 | 결과 / 근거 |
|---|---|
| 실제 Google 신규 가입·서비스 JWT 발급 | **PASS** — `POST /api/auth/google/login 200`1건; 운영 verifier/신규 runtime에서 처리 |
| 서비스 JWT로 본인 조회 | **PASS** — `GET /api/me 200`1건, 앱 authenticated=true |
| 포트폴리오 REST/화면 | **PASS** — `GET /api/portfolio 200`1건, 실제 asset card 렌더링 |
| 신규 사용자 | **PASS** — 내부userId2, USER, Google연결true, loginId/passwordHash는null |
| 이메일 자동 복사 금지 | **PASS** — 서비스email=null, emailVerified=false |
| 0원 신규 장부 | **PASS** — mKRW/mSEC잔고행2개, amount와lockedAmount모두0 |
| 신규 이력 | **PASS** — Faucet0/Order0/Trade0 |
| challenge | **PASS** — 발급응답200은2건, LOGIN성공소비1건. 미소비발급을 취소 성공으로 추정하지 않음 |
| 해당Origin의 실제GIS호환 | **PASS** — localhost5173에서 실제 인증 성공. Google Cloud관리 화면 자체 직접점검과는 별개 |
| 개인WS이벤트수신 | **NOT_RUN** — 허용된개인 이벤트0건. 로그인/포트폴리오GET만으로 수신PASS라고 판정하지 않음 |

DB 조회는 이번 신규 project의 소유권·image·mount 확인 뒤 허용필드 SELECT만 수행했다. google_sub/email/password_hash의 **실제 값은 조회하지 않고 NULL/연결 여부만** 확인했다. HTTP 증거는 새 웹 access log에서 허용된 path·method·상태코드 건수만 추출하고 원문 로그/요청본문/헤더는 추가 저장하지 않았다. 정제 결과는 이번 run evidence의 browser sample/google-users/http-status 파일에 보존했다.

다음 사용자 조작: 내모의자산에서 Faucet1회 → 100만mKRW확인 → 로그아웃 → 같은Google계정A재로그인 → 내모의자산확인. 이를 통해 실제 개인WS이벤트와 동일userId·기존지급이력/잔고 유지 여부를 이어서 검증한다. 거래·계정 연결·취소·중복연결·최종회귀/별도검토는 아직NOT_RUN이다.

## 8. 동일 Google 계정 재로그인과 Faucet 보존 — 2026-10-17 KST03:37~03:40

사용자가 Faucet 1회 지급 후 로그아웃하고 같은 Google 계정으로 재로그인했다고 알렸다. 전용 Chrome의 허용된 UI/개인 이벤트와 이번 격리 PostgreSQL의 SELECT 및 정제 HTTP 상태를 다시 확인했다.

| 확인 항목 | 판정 / 실제 근거 |
|---|---|
| 동일 Google 계정 재로그인 | PASS — Google login 200 누적 2건, 내부 userId=2 유지, USER/Google 연결 상태 유지 |
| 기존 잔고 및 지급 이력 보존 | PASS — mKRW 1,000,000, mSEC 0, 두 자산 lockedAmount 0, FaucetGrant 1건, Order/Trade 0건 |
| REST 및 포트폴리오 표시 | PASS — `/api/me` 200 누적 2건, `/api/portfolio` 200 누적 6건, authenticated/portfolioRendered=true |
| 실제 개인 WebSocket 이벤트 | PASS — Faucet 이후 `PORTFOLIO_UPDATED` 1건 수신, UTC18:37:01.034. 로그아웃 이전 이벤트이며 재로그인 이후 신규 이벤트 수신까지 증명하지는 않음 |
| 서비스 이메일 자동 복사 금지 | PASS — email=null, emailVerified=false 유지 |
| challenge 성공 소비 | LOGIN 성공 소비 누적 2건. 발급 3건만으로 취소/실패 경계 검증을 추정하지 않음 |

첫 확인 UTC18:37:48~51, 재확인 UTC18:40:16~26. 증거는 같은 private run의 timestamp별 browser-sample/google-users/http-status 파일로 추가 보존했다. 원문 Google sub, email, token, 비밀번호, HTTP 본문/헤더는 조회·기록하지 않았다.

로컬 계정 명시 연결과 연결 전후 자산·주문·거래 소유권, 실제 취소/중복 연결 실패, 실제 인증 이후 최종 회귀 및 독립 검토는 아직 NOT_RUN이다. 다음 단계에서는 별도 로컬 계정을 만들고 연결 전 snapshot을 확보한다. 연결 성공에는 이미 userId=2에 연결된 Google 계정 A가 아닌 별도 계정 B가 필요하며, 자동 병합이나 기존 사용자 초기화를 하지 않는다.

## 9. 로컬 계정 준비 확인 — 2026-10-17 KST03:42

사용자의 로컬 계정 준비 완료 통보 후 같은 신규 환경에서 허용된 SELECT로 userId=3(USER)을 확인했다. loginId/passwordHash는 존재 여부만 확인했고 Google 연결=false, email=null, emailVerified=false, mKRW/mSEC amount·lockedAmount 모두0, Faucet/Order/Trade 모두0이다. 기존 Google userId=2의 100만 mKRW와 Faucet1건도 유지됐다. Chrome은 인증됨/포트폴리오 표시/비밀번호 재확인 후 Google 연결 가능 안내 상태다.

연결 전 자산·주문·거래 이력을 실제로 준비한 뒤 추가 snapshot을 확보할 예정이다. 현재0건끼리 비교해 기존 Order/Trade 소유권 보존을 검증 완료로 처리하지 않는다. 이전 개인WS 이벤트 배열은 SPA에 남아 있으므로 userId=3의 이벤트로 재분류하지 않는다. private 조회 도구에 `--include-local-users` 옵션만 추가해 이번 격리DB의 USER 행을 포함했고, 제품 코드·기존 환경 변경은 없다.

## 10. 로컬 계정 실제 거래 후 연결 전 snapshot — 2026-10-17 KST03:45~03:47

사용자 거래 완료 통보 후 같은 신규 PG와 Anvil을 읽기 전용으로 확인했다. userId=3은 Google 미연결이며 mKRW=999764.774745984709920000, mSEC=0.000161833310517989, 두 lockedAmount=0, Faucet1/Order3/Trade2다. 계정 A의 userId=2/잔고/지급 이력도 그대로 유지됐다.

| 주문 | DB 결과 | 체인 증거 |
|---|---|---|
| Order1 BUY 100000mKRW | FILLED, ONCHAIN, Trade1 소유 userId=3 | transaction CONFIRMED, block3, Receipt status0x1/log5 |
| Order2 SELL 1.3282mSEC | FAILED, Trade 없음 | txHash/BlockchainTransaction 없음 |
| Order3 SELL 1.328mSEC | FILLED, ONCHAIN, Trade2 소유 userId=3 | transaction CONFIRMED, block4, Receipt status0x1/log5 |

BUY 수령량은1.328161833310517989이며 실패 SELL 입력1.3282는 이를 초과한다. 잔고 부족 가설과 일치하지만 이번 관측에서 해당 요청의 공개 오류 코드를 수집하지 않았으므로 정확한 실패 예외는 미확정으로 남긴다. 마지막 SELL은1.328만 매도했으므로 전량 매도/잔여mSEC0을 PASS로 기록하지 않는다. 사용자 입력인지 UI 반올림인지도 미확인이다. 추가 주문이나 제품 수정은 하지 않았다.

브라우저에서 Order1·3의 PENDING_ONCHAIN/FILLED, Order2의 FAILED 및 거래 후 PORTFOLIO_UPDATED를 실제 수신했다. 이전 userId=2의 이벤트와 시각/orderId로 구분했다. Receipt 로그 개수 확인을 별도의 이벤트 필드 전체 재검증으로 표현하지 않는다.

private `google-live-ownership-check.py`는 허용된 주문/체결/지급 ID·userId·수량·상태·txHash와 해당 Receipt status/block/logCount만 조회해 같은 run evidence의 `local-ownership-*`로 저장한다. 서명 원문/개인키/비밀번호/원문 오류는 조회하지 않았다. 연결 후 같은 주문/체결/지급 ID의 소유권과 잔고를 비교할 기준으로 보존했다. 연결 검증은 아직 NOT_RUN이다.

## 11. 사용자 보고: 전량 매도 UX와 Google 브라우저 차단 — 2026-10-17

사용자가 표시 수량으로는 전량 매도가 어렵고 Google 연결 시 브라우저가 안전하지 않다는 차단을 받았다고 보고했다. 코드 확인 결과 `state.js:number` 기본 소수4자리, `main.js:renderPortfolio`의 거래 잔고 힌트는 기본4자리/자산 카드는8자리 표시이며, 별도 전량 입력 버튼은 없다. 입력 자체는 소수18자리 문자열을 허용한다. 실제 BUY 수령1.328161833310517989가 힌트에서는1.3282로 반올림돼 잔고보다 커지는 UI 한계가 확인됐다. 실패 요청이 그 표시를 보고 입력됐는지는 사용자 진술 외에 추가 확인하지 않았다. 표시값을 그대로 입력해 전량 매도하도록 안내한 것은 부정확했다. 최소 수정 후보는 정확한 사용 가능 잔고 문자열을 반올림/Number 변환 없이 넣는 전량 버튼이며, 아직 제품 수정·재빌드하지 않았다.

Google 차단의 정확한 문구/코드와 발생 화면은 사용자에게 확인 요청 중이며, 원인 미확정/BLOCKED로 기록한다. 보안 경고 우회나 자동 재시도하지 않는다. UTC18:52:46 신규 격리DB 재확인에서 userId=3의 Google 미연결, 기존 잔고·잠금·Faucet1/Order3/Trade2가 유지됐다. 연결 성공으로 처리하지 않는다. Google 로그인 신규 가입/재로그인 성공 사실과 이번 별도 연결 시도 차단을 구분한다.

사용자가 정확한 오류 문구로 “브라우저 또는 앱이 안전하지 않을 수 있습니다. … 다른 브라우저를 사용해 보세요”를 제공했다. Google 공식 지원 문서 `https://support.google.com/accounts/answer/7675428`와 대조했다. 이번 Chrome은 private adapter의 별도 profile/시크릿/remote-debugging 옵션으로 실행됐다는 사실을 확인했다. 이 실행 방식이 차단에 영향을 줬을 가능성은 있으나 특정 옵션을 확정 원인으로 단정하지 않는다. 일반 지원 브라우저에서 동일 Origin의 로컬 계정으로 로그인한 뒤 새 LINK challenge로 수동 연결하는 비교 검증을 제안한다. 기존 challenge/token/cookie를 브라우저 사이에 복사하거나 보안 설정을 낮추지 않는다. 이 비교 검증과 연결 성공은 아직 NOT_RUN이다.

## 12. 명시적 Google 연결 후 DB 비교 — 2026-10-17 KST04:02

일반 Chrome에서 수동 연결하도록 안내한 뒤 사용자가 연결 완료를 알렸다. 사용자 직접 조작 사실과 별도로 도구는 동일 신규 환경의 소유권·소스/image guard 통과 후 PG SELECT와 해당 Anvil Receipt만 읽었다. 일반 Chrome의 인증 화면이나 토큰은 조회하지 않았다.

| 비교 항목 | 결과 |
|---|---|
| 기존 로컬 사용자 연결 | PASS — userId=3/USER 유지, Google 연결 false→true, 로컬 loginId/passwordHash 존재 유지 |
| 자산/잠금 보존 | PASS — mKRW=999764.774745984709920000, mSEC=0.000161833310517989 및 두 lockedAmount=0으로 연결 전과 동일 |
| 주문 소유권/상태 | PASS — Order1/2/3 모두 userId=3, FILLED/FAILED/FILLED 그대로 |
| 체결 소유권/내용 | PASS — Trade1/2 모두 userId=3, Order 연결·수량·수수료·txHash 유지 |
| Faucet 지급 이력 | PASS — 같은 Grant ID/userId=3 및1건 유지 |
| 기존 온체인 거래 | PASS — Order1/3 CONFIRMED, block3/4, Receipt status0x1/log5 유지 |
| 이메일 자동 복사 금지 | PASS — email=null/emailVerified=false 유지 |
| 별도 Google 사용자 보존 | PASS — userId=2/USER, 100만mKRW/Faucet1건 유지. 다른 사용자와 병합되지 않음 |

UTC19:02:03과19:02:23에 확보한 google-users/http-status/local-ownership 증거는 같은 private run에 추가 보존했다. 신규 코드 수정·재배포·DB 보정·추가 거래는 하지 않았다. 일반 Chrome에서의 성공으로 이전 디버깅 Chrome 차단의 특정 옵션 원인까지 확정하지 않는다.

연결한 Google 계정으로 로그아웃 후 재로그인하여 기존 userId=3으로 돌아오는지, 연결 후 로컬 로그인 유지, 실제 취소/중복 연결 실패, 최종 회귀 및 독립 검토는 아직 NOT_RUN이다. 이번 일반 Chrome의 개인WS 신규 이벤트 수신도 별도로 확인하지 않았으며 기존 전용 Chrome의 거래 이벤트 증거와 구분한다.

## 13. 연결 계정 Google 재로그인 — 2026-10-17 KST04:04

사용자가 연결한 Google 계정으로 로그아웃 후 재로그인 완료를 알렸다. UTC19:03:59~19:04:05에 같은 격리 환경의 허용된 PG/Receipt 조회와 정제 HTTP 상태를 재확인했다. Google login200 누적2→3, `/api/me`200 누적4→5, portfolio200 누적25→26, LOGIN 성공 소비2→3으로 증가했다. 따라서 실제 Google 인증 및 인증 후 REST 성공은 PASS다.

DB에서 Google 연결된 USER는 기존 userId=2/3이며 새 Google 사용자 행이 생기지 않았다. userId=3의 잔고/잠금/Faucet1/Order3/Trade2, 각 주문·체결·지급 ID와 소유권, txHash와 성공Receipt가 연결 전후 그대로 유지돼 데이터 보존 PASS다. 연결된 계정으로 로그인했다는 사실은 사용자 보고이며, 일반 Chrome의 현재 `/api/me` 응답 userId를 도구가 직접 캡처한 것은 아니다. 로그인 성공·추가 사용자 미생성·기존 데이터 유지와 현재 브라우저 응답 userId 직접 확인을 구분한다.

다음으로 연결 후 기존 로컬 ID/비밀번호 재로그인과 사용자 화면의 잔고/거래 이력 조회를 확인한다. 실제 취소/중복 연결 실패, 일반 Chrome에서의 연결 후 신규 개인WS 이벤트, 최종 회귀 및 독립 검토는 아직 NOT_RUN이다. 제품 코드/기존 환경/계정 소유권 변경이나 추가 거래는 수행하지 않았다.

## 14. 연결 후 로컬 로그인 유지 확인 — 2026-10-17 KST04:06

사용자가 일반 Chrome에서 기존 로컬 ID/비밀번호 재로그인 완료를 알렸다. UTC19:06:04 같은 격리 환경의 소유권·소스/image 검사 후 허용된 SELECT와 정제 access status를 확인했다. userId=3은 로컬 인증정보 존재/Google 연결 true를 유지하고, 잔고·잠금·Faucet1/Order3/Trade2 및 기존 userId=2 상태도 변하지 않았다. 원문 비밀번호/해시/sub/email/token은 조회하지 않았다.

private 조회 도구의 허용 path에 `/api/auth/login`, `/api/me/auth-methods`, `/api/me/google/challenges`, `/api/me/google/link`만 추가했다. 해당 로그 창에서 로컬 login200 누적3건, LINK challenge200 2건, Google link204 1건, `/api/me`200 7건, portfolio200 29건을 확인했다. 사용자 재로그인 보고와 이 HTTP 성공/DB 보존 증거로 연결 후 로컬 로그인 유지 PASS를 기록한다. 일반 Chrome의 `/api/me` 응답 userId 자체를 직접 읽은 것은 아니며 다른 브라우저의 token을 가져와 확인하지 않는다.

주요 실제 사용자 흐름인 Google 신규 가입→동일 계정 재로그인→별도 로컬 사용자 연결→연결한 Google 계정 로그인→기존 로컬 로그인은 사용자 직접 조작과 서버/DB 증거를 결합해 확인했다. 실제 취소·중복 연결 실패 경계, 일반 Chrome의 연결 후 신규 개인WS 수신, 실제 인증 후 최종 회귀/독립 검토는 여전히 미실행이며 전체 인수 완료로 선언하지 않는다. 전량 매도 표시 정밀도 문제와 이전 디버깅 Chrome 차단 기록은 그대로 유지한다. 제품 수정·commit/merge/push 및 기존 KRX 환경 변경은 없다.

## 15. 남은 인수 착수와 새 최종 회귀 — 2026-10-17

전량 매도는 Google 인증과 별도 [최소 변경·테스트 계획](sell-all-precision-design.md)만 작성했다. 기존 portfolio 필드 유지 + `tokenAvailableAmount` 문자열1개 + 클릭 시 fresh REST를 추천하며 제품 구현은 하지 않았다. JSON 숫자 정밀도와 잠금 제외 잔고 미제공 때문에 현재 UI만으로 정확한 전량을 보장할 수 없다.

### 이번 새 실행: 실제 Google 사용자 조작과 분리한 무과금 fixture 회귀

- branch `feat/google-auth`, HEAD `a98ea4b47fdb66484efdc31d66ffddc4f2b75085`, 기존 미커밋 변경 보존.
- 새 evidence run `google-final-regression-20261017031656-3c6a5e16`. private adapter가 기존 Phase A clean_env/java_test/execute 재사용. `.env` import 비활성, opt-in gate OFF, Synthetic·AI OFF, Gradle/Foundry offline, JVM 외부 HTTP(S) proxy 차단/loopback만 허용. 기존 DB/Anvil/컨테이너 lifecycle 조작 없음. 제품 해시는 private scope에 보존.
- **backend-final: 전체530 / PASS493 / SKIP37 / FAIL0 / ERROR0**. 새 XML/summary 보존. 37 SKIP은 통과가 아니며 opt-in PG/Anvil/AI를 이번 전체 실행에서 실행한 것이 아니다.
- **web-final: tests93 / PASS93 / FAIL0 / cancelled0 / SKIP0**.
- **browser-final: npm run test:browser 정상 종료/PASS**. 실제 Chrome desktop/mobile에서 오프라인 GIS fixture를 사용한 결과. 일반 Chrome 실제 취소/중복 연결/개인WS 증거로 대체하지 않음.
- **foundry-final: suites3 / PASS36 / FAIL0 / SKIP0**. 컨트랙트 변경 없음.
- 수정 전 UI/기존 Google 구현의 새 회귀다. 전량 버튼 수정 후 테스트는 **NOT_RUN(구현 승인 대기)**. 새 PG 동시성 fixture 재실행은 없으며 이전 PG2종과 실제 사용자 거래는 각 실행 기록으로 구분한다.
- 결과 집계 중 cp949 출력 오류1회와 일회성 Python 구문 오류1회는 테스트 실패가 아니다. 테스트 재실행 없이 같은 로그에서 숫자를 추출했고 초기 로그도 유지했다.

### 사용자 직접 조작 대기

| 항목 | 상태 / 필요한 증거 |
|---|---|
| 실제 Google 취소 | NOT_RUN — 팝업 사용자 취소 후 앱/DB 보존 및 로그인·연결 미실행 확인 필요 |
| 다른 사용자 소유 Google의 중복 연결 거부 | NOT_RUN — 새 격리 로컬 사용자에서 기존 소유 Google 연결 시409/GOOGLE_ACCOUNT_ALREADY_LINKED, 기존 소유권 유지 확인 필요 |
| 일반 Chrome `/api/me` 현재 id=3 | NOT_RUN — 사용자에게 Network Preview의 id 숫자만 확인 요청함. 기존 HTTP200과 DB snapshot만으로 직접 응답 id 확인을 주장하지 않음 |
| 일반 Chrome 연결 후 신규 개인WS | NOT_RUN — 수신 MESSAGE의 type/orderId/시각 확인 필요. 이전 디버깅Chrome 이벤트·CONNECTED로 대체하지 않음. CONNECT Authorization/전체 프레임 공유 금지 |

일반 Chrome의 Google 조작은 사용자만 수행하며 token/비밀번호를 받아 자동 인증하거나 보안 경고를 우회하지 않는다. 독립 최초 검토에서 구현 문서의 현재형 NOT_RUN 표현을 지적해 오프라인 당시 이력으로 한정하고 최신 실제인수 문서로 연결했다. HTTP 집계도 logs tail500 관측 창임을 명시했다. 최종 재검토는 별도로 기록한다. 전체 인수는 진행 중이고 commit/merge/push 및 제품 수정은 없다.

독립 재검토 `review_google_live_acceptance`: **발견된 필수 수정 없음**. 최초 문서정합성1건 해결, tail500 한계 및 실제/fixture/이전 결과 분리 확인. 검토자는 소스·문서를 읽었고 테스트 직접 실행/private 원본 증거 열람은 하지 않았다. 따라서 남은 사용자 직접 관측4항목의 통과나 전체 인수 완료를 의미하지 않는다.
