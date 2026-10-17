# Google 사용자 인증 확장 — 실제 코드 조사 및 구현 승인안

> 2026-10-16 / 제안 상태. 제품 구현·migration 실행·테스트·실제 Google 인증은 하지 않았다.
> 원본 루트 `melon-init`, HEAD `a98ea4b47fdb66484efdc31d66ffddc4f2b75085` 기준. 기존 NXT 관측 보고서 및 implementation-log 미커밋 변경은 보존했다.

## 1. 추천 결론

**Google Identity Services(GIS)의 공식 로그인 버튼 → 브라우저 JS callback으로 ID Token 수신 → 백엔드 검증 → 기존 서비스 JWT**를 추천한다. 로그인만 필요하고 Google API 대리 호출은 필요 없으며, 현재 웹은 JWT 메모리 보관·same-origin REST·STOMP CONNECT 구조이기 때문이다. Google 로그인은 OIDC 신원 확인이며 Google access token으로 거래 API에 접근하는 구조가 아니다.

기존 ID/비밀번호와 동일한 users.id/USER·ADMIN/서비스 JWT를 재사용한다. Google sub만 외부 계정 식별자로 사용하며 이메일 일치로 계정이나 자산을 합치지 않는다. Google-only 신규 가입과 기존 계정 연결은 **서로 다른 사용자 의도**로 구분한다.

이번 필수 제안은 Google 로그인/가입·안전한 명시 연결·nonce/CSRF/중복 방지·웹 연동 및 격리 검증이다. 이메일 발송 인증·refresh/revoke·계정 복구·자동 로그인·Google API 권한은 후속으로 분리한다. 서버 WS 만료 보완은 아래 별도 승인 항목이다.

## 2. 현재 구현과 재사용 근거

경로 접두어 `B=backend/src/main/java/com/pricetrack/exchange/`, `W=tools/websocket-test-client/`.

| 현재 코드 | 확인 사실·재사용 |
|---|---|
| `B/auth/AuthController.java:58,63` | POST /api/auth/signup·login. signup도 TokenResponse를 반환해 자동 로그인한다. /api/me로 현재 사용자 조회 |
| `B/auth/AuthService.java:41,57,84` | loginId 정규화, BCrypt 대조, 사용자 저장+잔고 초기화, 기존 JWT 발급. Google 인증 경로는 없음 |
| `B/auth/JwtTokenProvider.java` | HMAC 서명, subject=DB userId, loginId claim, iat/exp. 기본1시간(`application.yml:85`). refresh/jti/revoke 저장 없음 |
| `B/auth/JwtAuthenticationFilter.java` | Bearer 검증 후 DB 사용자·역할을 조회해 AuthenticatedUser를 설정. 역할은 클라이언트/Google 주장값이 아님 |
| `B/common/config/SecurityConfig.java:36` | Spring Security 이미 사용. API STATELESS, 전역 CSRF disabled. /api/auth/** 공개, ADMIN/AI 경계 분리 |
| `B/user/User.java:29~62` | nullable loginId/passwordHash/email/googleSub, email unique, googleSub unique, emailVerified=false 기본. supportsLocalLogin/GoogleLogin 이미 존재 |
| `B/user/UserRepository.java` | findByGoogleSub/existsByGoogleSub와 email 조회 메서드는 있으나 현재 Google 인증에서 사용하지 않음 |
| `backend/src/main/resources/schema.sql:3~28` | users 로그인수단 CHECK, nullable 컬럼 및 google_sub unique index가 이미 있음. 신규/과거 SQL의 적용 방식은 다르므로 실제 오래된 DB에 제약이 모두 있다고 단정하지 않음 |
| `B/wallet/WalletService.java:32` | initializeBalances(userId)로 0원 mKRW/mSEC 생성. 신규 사용자에게만 호출해야 함 |
| `B/order/OrderController.java`, `OrderService.java` | principal.userId로 BUY/SELL, 목록·상세는 사용자 조건 조회. 상세 findByIdAndUserId |
| `B/trade/TradeService.java:19`, `B/portfolio/PortfolioController.java:21` | 사용자별 거래·포트폴리오도 DB userId 기준 |
| `B/websocket/config/WebSocketAuthInterceptor.java:50~89` | CONNECT의 서비스 JWT를 확인하고 공개/개인 SUBSCRIBE 분리, 임의 SEND 거부 |
| `B/websocket/auth/WebSocketPrincipal.java:12` | getName()=DB userId 문자열. Google sub나 이메일을 개인 destination 키로 쓰지 않음 |
| `W/src/api.js:10,153` | 서비스 JWT를 private 메모리에 보관, 요청 Authorization에만 사용 |
| `W/src/main.js:100~115`, `src/websocket.js` | 로그인 후 me/개인 상태 초기화/WS 재연결. 로그아웃·요청세대·만료 타이머가 존재 |
| `W/deploy/security-headers.conf` | CSP가 script/connect 기본 self라 현재 그대로는 GIS 로드가 차단됨 |
| `W/vite.config.js`, `deploy/default.conf.template` | /api 및 WS proxy만 있음. GIS JS callback은 별도 /oauth2·/login callback proxy를 추가하지 않아도 됨 |

JWT 클래스의 SecurityFilterChain 연결 TODO 주석은 오래된 설명이다. 실제 필터는 이미 연결돼 있다. 기존 문서의 Google 지원 예정은 컬럼 준비를 뜻하며 실제 OAuth 구현 완료가 아니다.

Google-only는 loginId/passwordHash=null, google_sub만 저장하는 모델을 추천한다. 현재 userId 기반 소비 경로와 맞지만 **null loginId의 JWT claim/MeResponse/WS/AI 호환은 신규 테스트로 확정**해야 한다. 임의 공통 비밀번호나 사용자가 입력하지 않은 로컬 로그인 자격을 생성하지 않는다.

## 3. 방식 비교

| 방식 | 현재 구조에서의 장단점 | 제안 |
|---|---|---|
| GIS 로그인 버튼 + ID Token 서버 검증 | 현재 JS callback/JWT 메모리/REST 구조와 직접 연결. Google API access/refresh token 및 client secret 불필요. nonce·Origin·브라우저 바인딩과 GIS CSP 설정 필요 | 이번 추천 |
| 서버 Authorization Code + PKCE(S256) + OIDC | Spring OAuth2 Client로 구현 가능. state/nonce/code verifier 임시 보관, confidential client secret, callback proxy와 JWT 전달 절차 추가 필요. Google API 위임이나 다양한 OAuth 공급자 요구가 커지면 유리 | 후속 확장 대안 |
| SPA가 code/access token을 직접 관리 | PKCE를 적용해도 백엔드 신원 검증은 필요. 현재 목표에 불필요한 토큰 종류·경계 추가 | 이번 제외 |

GIS **인증용** API와 Google API **인가용** code/token API는 구분한다. 추천 방식은 authorization code flow가 아니므로 PKCE/state callback을 형식적으로 붙이지 않는다. 대신 서버 발급 nonce·브라우저/요청 목적 바인딩·Origin/CSRF 검증이 필수다. Code flow를 선택하면 state/nonce/PKCE와 고정 redirect 검증을 별도 구현해야 하며, ID Token·서비스 JWT를 URL에 넣지 않는다. [공식 GIS 설명](https://developers.google.com/identity/gsi/web/guides/overview), [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect).

## 4. 추천 로그인·연결 흐름

### 로그인/신규 가입

1. 웹이 로컬 config/challenge를 읽는다. disabled면 Google SDK 자체를 로드하지 않는다.
2. 서버는 purpose=LOGIN의 단기 challenge와 고엔트로피 nonce를 발급하고 임의 브라우저 바인딩 cookie를 설정한다. cookie는 사업 API 인증수단이 아니다.
3. 공식 GIS 버튼에 clientId/nonce를 전달한다. One Tap/자동 로그인은 이번 제외한다.
4. callback에서 받은 ID Token은 메모리에만 두고 same-origin JSON POST로 보낸다. raw token/비밀번호/nonce를 로그·URL·localStorage에 쓰지 않는다.
5. 서버는 Google 토큰 검증을 마친 뒤 짧은 DB transaction에서 challenge 만료·목적·바인딩·미소비를 재검증한다.
6. 검증된 sub가 있으면 기존 사용자다. 없으면 USER 신규 생성+0원 잔고2개를 동일 transaction으로 만든다. 중복 sub 경쟁은 DB unique로 보호하고 실패 transaction의 사용자/잔고가 함께 rollback돼야 한다.
7. challenge 성공 소비와 사용자 처리를 원자적으로 완료하고 기존 TokenResponse를 반환한다. 기존 Google 사용자는 잔고 초기화/Faucet 자동 지급을 하지 않는다.
8. 웹은 현재 인증 요청 세대를 확인하고 기존 me→개인 상태→STOMP 경로로 진입한다. 오래된 callback으로 로그아웃/다른 사용자 화면을 덮지 않는다.

Google 로그인은 신규 계정을 만들 수 있다는 점을 버튼/확인 문구에 명시한다. 기존 자산을 사용하려면 먼저 기존 계정으로 로그인한 뒤 연결해야 한다. 이미 다른 userId에 귀속된 Google 계정을 발견하면 자산을 합치지 않는다.

### 기존 계정의 명시 연결

1. 현재 서비스 JWT로 인증한 사용자만 LINK challenge를 발급받는다. userId는 principal에서만 가져오고 request body의 대상 userId는 받지 않는다.
2. GIS 인증 후 사용자가 현재 로컬 비밀번호를 다시 입력하고 '현재 계정에 Google 연결'을 명시적으로 확정한다.
3. 서버에서 JWT+LINK challenge+Google ID Token+현재 비밀번호를 모두 검증한다. Google 로그인 상태나 이메일 일치만으로 로컬 재인증을 대체하지 않는다.
4. 최종 짧은 transaction에서 사용자 row/현재 자격을 재확인하고 google_sub unique를 보호한다. 외부 JWKS 조회 동안 사용자/잔고 row 잠금을 유지하지 않는다.
5. 해당 사용자의 비어 있는 google_sub만 설정한다. 같은 sub가 이미 연결된 경우 추가 장부 작업 없이 처리하고, 다른 sub 교체/다른 사용자 연결은409로 차단한다. 재사용된 성공 challenge는 거절한다.
6. 기존 userId/role/잔고/lockedAmount/Order/Trade/Faucet 이력은 그대로다. 기존 잔고 초기화 함수를 호출하지 않는다.

Google 신규 가입과 로컬 계정 연결이 같은 sub로 동시에 경쟁하면 unique 위반을 일반 성공으로 처리하지 않는다. 실패한 transaction 밖에서 정확한 sub를 제한적으로 다시 조회하는 경로를 테스트하고, 다른 오류를 계정 충돌로 뭉개지 않는다. role은 DB만 신뢰한다. 신규 Google 사용자는 언제나 USER이고, ADMIN 연결을 허용하더라도 기존 ADMIN의 비밀번호 재인증을 거친 역할 유지일 뿐 신규 승격이 아니다.

### 이메일과 해제 정책

- 이번 추천: Google의 email/email_verified를 users.email/email_verified에 자동 복사하지 않는다. 해당 필드는 이후 **서비스 이메일 등록·검증**용으로 유지한다. 기존 email UNIQUE를 제거하거나 기존 사용자 이메일을 덮지 않는다.
- 같은 이메일이어도 sub가 다르면 별개 계정이다. 새 Google 계정은 서비스 email을 비워 두므로 같은 이메일을 가진 로컬 계정과 unique 충돌 때문에 합칠 필요가 없다. 이름은 표시용으로 제한·정제하며 인증 식별자가 아니다.
- Google의 이메일 검증 주장과 현재 서비스의 이메일 소유 확인을 혼동하지 않는다. 특히 외부 메일 기반 Google 계정의 email_verified만으로 현재 메일 소유권을 보증하지 않는다. [공식 서버 검증 주의사항](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token).
- 이번에는 Google 연결 해제/교체 API를 제공하지 않는 것을 권장한다. 마지막 로그인 수단 제거를 차단하고, 기존 JWT/WS를 즉시 폐기할 수 없는 상태에서 '연결 해제=모든 접근 철회'를 약속하지 않기 위해서다. 해제는 세션 폐기 설계와 함께 별도 승인한다.
- Google 취소·검증 실패·JWKS 장애는 사용자 생성/연결 없이 종료하며 기존 로그인과 거래 복구는 계속 동작해야 한다. 로컬 로그아웃은 Google 전체 계정 로그아웃이 아니다.

## 5. 검증·CSRF·보안 경계

### GoogleIdentityVerifier(제안 경계)

기존 JJWT 앱 HMAC 검증과 분리한다. Spring Security의 관리 버전과 맞는 `spring-security-oauth2-jose`/Nimbus 기반 verifier adapter를 우선 제안한다. 앱 API를 Google token을 받는 resource server로 교체하지 않는다. 실제 의존성 버전·기본 validator는 구현 시 관리 BOM과 맞춰 검증한다.

- 신뢰한 Google JWKS의 서명과 허용 algorithm만 검증. JWT의 임의 jku/issuer에서 키를 가져오지 않음.
- Google 공식 issuer만 허용, aud는 설정한 web Client ID, 필요 시 azp도 확인. 다른 앱/Android Client ID를 자동 허용하지 않음.
- exp/iat 및 존재하는 nbf, 빈/과대/잘못된 sub, nonce 일치/한번 소비 검증. 시간 허용치는 **인증용** 설정이며 시장5초/미래2초/Oracle TTL과 무관함.
- provider JWKS 캐시·연결/읽기 timeout·요청 크기 제한 적용. 시작 시 eager discovery나 Google 연결을 강제하지 않음. feature off에서는 실제 endpoint 접근0.
- decoder/네트워크 오류를 인증 통과나 로컬 사용자 fallback으로 바꾸지 않음. ID Token이나 외부 원문 오류를 반환·로그하지 않음.

### nonce/브라우저 바인딩과 API

같은 브라우저에서 시작한 요청의 token인지 확인해야 login CSRF를 막을 수 있다. nonce만 받고 임의 브라우저에서 재사용 가능한 공개 교환 API로 만들지 않는다.

- 바인딩 cookie: 임의값, HttpOnly/host-only/SameSite=Strict, HTTPS에서는Secure. HTTP 예외는 명시적 localhost 개발 환경에 한정.
- DB에는 nonce·cookie 원문 대신 고엔트로피 값의 fingerprint를 저장. cookie가 거래 REST나 STOMP 사용자를 인증하지 않음.
- Google 전용 POST는 정확한 허용 Origin, JSON/custom header 및 challenge/cookie 일치를 강제. wildcard CORS·Origin 누락 허용으로 브라우저 경계를 우회하지 않음.
- 추천 JS callback에서는 GIS redirect POST의 g_csrf_token이 자동 검증된다고 가정하지 않는다. 서버 자체 challenge 바인딩을 사용한다. 전역 csrf.disable만으로 OAuth 보안이 해결됐다고 표현하지 않음.
- challenge TTL5분/동시 활성 수 제한/발급·재인증 실패율 제한을 초기 제안값으로 두며 구현 시 수치와 테스트를 고정한다. 로그인 완료·연결 완료는 DB 잠금/CAS로 한번만 소비.
- 기존 /api/auth/**는 공개이므로 연결 endpoint를 그 아래 무심코 넣지 않는다. 아래처럼 /api/me 하위로 두어 기존 authenticated 규칙을 재사용한다.

| 제안 API | 권한·용도 |
|---|---|
| GET /api/auth/google/config | 공개 clientId/활성 여부만. Secret 없음 |
| POST /api/auth/google/challenges | 공개 LOGIN 시작, Origin·발급 제한 필수 |
| POST /api/auth/google/login | 검증된 token+LOGIN challenge → 기존 TokenResponse |
| POST /api/me/google/challenges | JWT 인증된 LINK 시작 |
| POST /api/me/google/link | JWT+로컬 비밀번호+검증된 token+LINK challenge로 확정 |
| GET /api/me/auth-methods | 본인 local/google 연결 여부만. 토큰·sub 원문 비노출 |

## 6. DB와 JWT/WS 영향

`users`의 기존 google_sub UNIQUE와 nullable loginId/passwordHash를 재사용한다. 새 인증수단 테이블이나 사용자의 자산 이동 migration은 필요하지 않다. 다만 nonce 재사용/동시 요청/재시작을 확실히 구분하기 위해 **google_auth_challenges 1개 추가**를 추천한다.

필드 제안: 임의 id, purpose, nonce fingerprint, browser-binding fingerprint, LINK용 user_id, created_at/expires_at/consumed_at. nonce/challenge는 짧은 인증 절차용이며 refresh/session 테이블이 아니다. 만료 행만 제한적으로 정리하고 성공 사용자/장부/Faucet 이력은 삭제하지 않는다. ID Token·Google access/refresh token·비밀번호는 저장하지 않는다.

- 기존 SQL은 Flyway가 아니라 schema.sql 반복 실행이다. 기존 테이블을 DROP/재작성하지 않는 additive SQL과 H2용 entity/test schema를 맞춘다. 기존 사용자 행의 sub/email/verified/자산은 backfill하지 않는다.
- 실제 기존 DB 구조는 이번 조사에서 접속하지 않았으므로 migration 통과를 주장하지 않는다. 신규 PG 및 가상 구버전 로컬 fixture에서만 idempotent DDL/제약/nullable 경로를 검증한다.
- 앱 JWT 형식·키·1시간 기본값·userId subject 유지. AuthService의 공통 token 발급만 공유하고 사용자 인증 전체를 중복 작성하지 않는다.
- REST 역할은 기존 DB 조회, 개인 WS는 기존 userId destination을 유지한다. Google sub/email로 사용자 소유권을 재지정하지 않는다.
- 현재 서버는 STOMP CONNECT 때 JWT를 검증하고 이후 개인 구독에서는 principal 존재만 확인한다. **연결 중 exp 경과 시 서버 강제 만료/폐기는 구현돼 있지 않다.** 브라우저 타이머가 있지만 악의적 클라이언트의 보안 경계는 아니다.
- 따라서 서버 WS의 exp 이후 개인 이벤트 차단·세션 종료는 인증 보안 최소 보완으로 **함께 승인받는 것을 권장**한다. JWT claim 형식과 거래/정산을 바꾸지 않고 세션별 expiry를 전달·차단하는 별도 작은 작업 단위다. refresh/revoke/다기기 강제 로그아웃과는 구분한다. 승인되지 않으면 기존 한계로 남기고 완료라고 보고하지 않는다.

## 7. 변경 예정 위치

- backend build.gradle: Google verifier용 관리 의존성. 전체 Spring Boot 업그레이드를 끼워 넣지 않음.
- auth/google/: config/verifier/challenge/controller/account service 및 테스트. user/UserRepository에 링크용 동시성 조회가 필요할 수 있음.
- AuthService/JwtTokenProvider: 기존 발급 재사용·null loginId 경로 테스트. Google token을 기존 JwtAuthenticationFilter가 앱 token으로 인정하도록 바꾸지 않음.
- schema.sql/application.yml/.env.example/README: additive challenge DDL, feature off 기본, 공개 Client ID/허용 Origin/TTL 설정. 실제 .env를 임의 수정하지 않음.
- 웹 index.html/main.js/api.js와 작은 Google auth 모듈: 공식 버튼/연결 UI/취소/오래된 응답/기존 로그인 완료 처리 공유.
- deploy/security-headers.conf: GIS script/frame/connect/style에 필요한 정확한 Google 경로만 허용. localhost Referrer-Policy 및 필요한 COOP/FedCM 호환은 공식 가이드와 실제 브라우저에서 확인. wildcard로 CSP를 풀지 않음.
- 기존 quote/Vault/receipt/nonce/장부 대사/AI 실행 코드 및 스마트컨트랙트는 변경 대상 아님.

## 8. 이메일·refresh·해제의 권장 순서

1. 이번 Google 로그인·명시 연결 및 기존 인증 호환성.
2. 승인 시 서버 WS 만료 강제 차단을 함께 닫기. 성공한 기존 거래의 복구/정산은 인증 세션 만료와 별개로 유지.
3. 이후 서비스 email 등록/검증·비밀번호 설정/재설정. 공급자/발송 비용·검증 challenge·재시도·계정 복구 정책 별도 설계.
4. 이후 서비스 refresh token rotation/재사용 탐지/폐기·서버 세션 식별자·WS 강제 종료를 하나의 설계로 진행. Google refresh token을 서비스 refresh token처럼 사용하지 않음.
5. 그 기반 위에 Google 연결 해제·모든 기기 로그아웃·안전한 로그인수단 교체를 제공.

Google만 가입한 사용자는 이번에 로컬 비밀번호 재설정/계정 병합/Google 연결 해제로 복구할 수 있다고 안내하지 않는다. 현재도 로그아웃은 메모리 token 제거이며 서버 token 폐기는 아니다. 새 OAuth 작업이 이 기존 한계를 자동 해결하지 않는다.

## 9. 구현·검증 순서 및 통과 기준

설계 승인 후 원본 폴더에서 새 기능 브랜치를 만들되 worktree를 추가·제거하지 않는다. 기존 NXT 문서 변경은 혼합 커밋하지 않고 보존한다.

1. feature off/config/검증 adapter/로컬 RSA JWKS fixture. 서명·issuer·aud/azp·exp/nonce·크기·알고리즘·키회전·timeout/오류 검증. 단순 decode나 mock verifier만으로 암호 검증 PASS를 주장하지 않음.
2. challenge 및 사용자 가입·로그인·연결 서비스/SQL. 신규 PG에서 같은 sub 로그인 경쟁, 링크 경쟁, nonce 경쟁, transaction rollback 및 구버전 fixture additive migration. H2/Mock와 구분.
3. 웹 official button adapter를 fake SDK로 검증. 실제 Google SDK를 오프라인 파일로 복제하지 않음. 서버 off·SDK실패·취소·만료·여러 탭·중복 callback·로그아웃 후 늦은 응답·CSP·모바일 UI 검증.
4. 기존 AuthServiceTest/AuthIntegrationTest/JwtTokenProviderTest 및 Google-only null loginId 회귀. local/google 둘 다 기존 JWT/USER·ADMIN·다른 사용자 자산/주문/거래 거절 검증.
5. WebSocketAuthInterceptorTest/WebSocketConnectionIntegrationTest, Google 로그인 후 개인 userId 격리와 임의 SEND/타인 destination 차단. WS expiry 보완 승인 시 이미 구독된 개인 이벤트의 exp 이후 전달까지 검증.
6. 기존 backend/web/Foundry 회귀, 필요한 신규 PG/Anvil/Synthetic에서 Google fixture 사용자로 Faucet→BUY/SELL→Receipt→개인WS→포트폴리오→두대사. 실제 OAuth와 분리.
7. AI는 권한/실사용자 context와 기존 Agent/Tool fixture 회귀만. 유료 모델·실제 공급자 평가 없음.
8. 변경·테스트·문서 초안을 독립 reviewer에게 전달, 필수 지적 보완/재검증 후 보고. Google 실인증은 별도 설정·네트워크 승인 후 최소 횟수로 실행하고 오프라인 결과와 구분.

필수 account 사례: 동일 이메일 별개 sub 자동병합0, 기존 링크 시 userId/잔고/잠금/Order/Trade/Faucet 불변, 이미 타인에게 연결된 sub409, 역할/이메일 클레임 위조 거부, Google 실패에도 자체 로그인 정상. 새 Google 계정을 여러 개 만들 수 있으므로 계정별 Faucet이 사람당 제한으로 바뀌는 것은 아니다.

## 10. 사용자 결정·설정과 일정

승인 필요:

1. GIS JS callback + nonce/브라우저 바인딩으로 진행할지. 서버 Code+PKCE를 원하면 별도 callback/session 설계로 변경.
2. 서비스 email 자동복사 없음, Google-only의 로컬 로그인 없음, 기존 계정 연결은 비밀번호 재인증, 해제·refresh·이메일 발송은 후속으로 할지.
3. 기존 서버 WS 만료 보완까지 이번에 포함할지(권장). 이는 신규 승인 범위임.
4. 기존 KRX 인수를 기존 이미지로 끝낼지, 최신 Google 소스까지 포함하는 새 인수 환경을 별도로 승인할지. **기존 환경을 그대로 보존하면서 그 이미지를 최신 Google 기능까지 검증한 것으로 표시할 수는 없다.** 권장은 기존 환경 보존+Google Synthetic 통합 검증 후, 필요 시 최신 소스로 신규 KRX fixture를 별도 승인하는 것.

사용자 직접 준비:

- Google Cloud의 웹 애플리케이션 OAuth Client ID, 앱 이름/동의 화면/테스트 사용자, 사용할 로컬 웹 origin 확정. 예: 새 개발 웹 `http://localhost:5173`; 실제 선택 포트를 등록하며 기존 KRX origin/키를 임의 재사용하지 않음.
- GIS popup/JS callback 추천안은 Authorized JavaScript origins를 설정하며 **Client Secret과 backend Redirect URI는 필요하지 않다.** redirect UX/Code flow를 선택할 때만 정확한 redirect URI·서버 Secret을 추가 준비.
- 구현 시 Google config는 원본 개발 전용 파일에만 두고 Client ID만 공개 설정으로 전달. 기존 KRX/NXT secret은 접근·수정하지 않음.
- 실제 브라우저 Google SDK 및 JWKS/인증 네트워크 사용은 별도 승인. 이번 문서 조사는 공개 공식 문서 조회만 수행했으며 실제 인증/계정 생성/credentials 입력은 하지 않음.

주말 목표는 외부 계정 없이도 verifier/nonce/DB race/웹 fake SDK/Synthetic 통합 및 독립 검토까지 닫는 것이다. 실제 Google 동의 화면·origin·브라우저 호환성은 실인증 없이는 PASS가 될 수 없다. 예상 밖의 인증 구조 변경이나 dependency/네트워크 문제가 생기면 범위를 숨겨 줄이거나 월요일 실인수 완료를 약속하지 않고 보고한다.

## 11. 공식 근거

- [Google GIS 개요](https://developers.google.com/identity/gsi/web/guides/overview): 인증과 Google API 인가 구분.
- [Google ID Token 서버 검증](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token): 검증·sub·이메일·기존 계정 소유 확인.
- [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect): Code flow의 state/nonce와 sub.
- [GIS JavaScript nonce](https://developers.google.com/identity/gsi/web/reference/js-reference): 서버 challenge 연동 근거.
- [GIS 설정](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid): client ID/origin/redirect/CSP·브라우저 설정.
- [Spring JWT 검증 구성](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html): 서명/JWKS validator 개념. 최신 예제 API를 현재 Boot3.3.4에 그대로 복사하거나 전체 API security를 대체하지 않음.

현재 단계 검증 상태: 코드·문서 읽기 조사 및 설계만 완료. 제품 코드·스키마·실제 .env·Docker·DB·Anvil·기존 worktree/private evidence 미변경, 테스트/실인증/commit/merge/push 미실행.

## 12. 독립 설계 검토

`review_acceptance_baseline`이 현재 auth/user/schema/Security/WS/웹 코드와 제안을 대조했다. 결론은 **발견된 필수 수정 없음 — 구현 전 설계 검토 기준**이다. sub/이메일/명시 연결/nonce 바인딩·원자성과 기존 WS 만료/null loginId/KRX 이미지 한계 구분을 확인했다. 제품 구현·테스트·DB/private/runtime/Docker·실제 인증 및 공식 링크 독립 재검증은 하지 않았다. 구현 승인이나 실인증 보안 검증 완료로 해석하지 않는다.
