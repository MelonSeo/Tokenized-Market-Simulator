# Google GIS 인증 구현·검증 기록

> 2026-10-17, 원본 루트 `feat/google-auth`, 시작 HEAD `a98ea4b`. 사용자 승인 설계: [설계 근거](google-oauth-implementation-design.md).
> 승인 범위의 구현·오프라인 검증 완료. 이하 실행 기록은 오프라인 구현 완료 당시의 이력이다. 후속 실제 Google 가입·로그인·명시 연결은 [실계정 인수 기록](google-auth-live-acceptance-2026-10-17.md) §7~14에서 확인했으며, 남은 실패 경계·일반 Chrome 직접 관측은 그 문서의 최신 상태를 따른다. 과거 NOT_RUN 표시는 당시 범위이며 현재 전체 결과로 해석하지 않는다. 실행별 초기 실패와 후속 통과를 분리하며 기존 결과를 재실행 결과로 쓰지 않는다.

## 변경 경계

- `backend/src/main/java/com/pricetrack/exchange/auth/google/`: 설정, 고정 Google JWKS/RS256 검증, 일회성 challenge, 원자적 가입·로그인·연결, REST API.
- `auth/AuthService.token`: 기존 앱 JWT 발급 공유. subject=users.id 유지. Google-only loginId/password/email=null, emailVerified=false, 신규 USER·잔고 두 개 모두 0.
- `user/UserRepository`: LINK 최종 확인용 사용자 행 잠금. 기존 잔고·Faucet·주문·Trade 초기화/이동 없음.
- `schema.sql`: `google_auth_challenges`만 additive 추가. 사용자나 자산 backfill 없음. `application.yml`, `backend/.env.example`: feature off 기본, client ID/정확한 Origin 설정.
- `websocket/config/WebSocketExpiryGuard`: CONNECT 검증 만료시각을 세션에 연결. inbound 및 outbound enqueue/실제 handler 시점에 만료 차단, 1초 주기 transport 종료. 공개 익명 세션은 영향 없음.
- 웹 `src/google-auth.js`, `main.js`, `api.js`: 공식 GIS 버튼을 명시적 시작 때만 로드. 로그인과 현재 계정 연결 구분. 토큰은 메모리만 사용하며 /me 확인 전 현재 앱 토큰을 교체하지 않음. 취소·로그아웃·이전 callback은 세대로 무시.
- `deploy/security-headers.conf`: Google GIS에 필요한 script/frame/connect/style 경로만 추가. 일반 wildcard 추가 없음.
- `deployment/phase-a.py`, 테스트 `support/PhaseAFixture`: 기존 소유권·시각·해시·subnet guard를 유지하며 google/google-migration 새 fixture만 추가.
- 컨트랙트·거래·서명 견적·nonce·정산·대사·AI 제품 로직은 변경하지 않음. NXT 제품 기능 추가 없음.

## 인증과 원자성

Google verifier는 앱 HMAC JWT 검증과 별개다. 신뢰한 고정 JWKS에서 RS256 서명을 검증하고 issuer, audience, azp, exp/iat/nbf, sub, nonce 형식을 확인한다. Google email/role은 계정 계층으로 전달하지 않는다. 공개키 네트워크는 인증 시 지연 실행하며 2초 connect/read timeout을 사용한다. feature off 기동 시 실제 Google 요청이 없다.

challenge: UUID, LOGIN/LINK, nonce SHA-256, 브라우저 바인딩 SHA-256, LINK userId, 생성/만료/소비 시각. nonce·cookie는 32바이트 난수. TTL=300초. 바인딩 cookie는 host-only, HttpOnly, SameSite=Strict, /api; 정확히 허용한 Origin이 HTTPS면 Secure. HTTP는 localhost/loopback Origin만 설정 가능하다. 임의 forwarded header를 신뢰해 Secure 여부를 결정하지 않는다.

Google 전용 POST는 정확한 Origin+JSON+`X-Google-Auth: 1`을 요구한다. per-process IP 제한 5분40회/최대4096 IP bucket, 바인딩별 최근5분 challenge8개 제한. 임의 X-Forwarded-For를 신뢰하지 않는다. 다중 인스턴스 전역 rate limit은 이번 구현 범위가 아니며 프록시 공유 IP에서는 보수적으로 제한될 수 있다.

외부 서명 검증과 LINK BCrypt는 DB 잠금 전 수행한다. 짧은 transaction에서 challenge FOR UPDATE, 만료/목적/userId/nonce/binding/미소비 재검사, 계정 생성 또는 사용자 행 잠금 후 연결, challenge 소비를 함께 commit한다. 신규 계정 잔고 생성도 같은 transaction이다. Google sub unique 충돌은 알려진 google_sub 제약만 분류하며 LOGIN은 실패 transaction 밖에서 새 transaction으로 최대1회 재조회한다. LINK 충돌은409다. 다른 DB 오류를 성공으로 바꾸지 않는다.

LINK는 JWT의 내부 사용자 ID+로컬 비밀번호+LINK challenge+검증된 Google proof를 모두 요구한다. 로컬 로그인 자격/비밀번호 hash가 잠금 전에 확인한 것과 같은지 재검사한다. 다른 sub 교체, 다른 사용자 소유 sub 연결, 이메일 기반 병합은 금지한다. 이메일 발송, refresh/revoke, recovery/unlink는 구현하지 않았다.

## REST 계약과 클라이언트

| 경로 | 권한 / 계약 |
|---|---|
| GET `/api/auth/google/config` | 공개 enabled/clientId만, no-store |
| POST `/api/auth/google/challenges` | LOGIN; JSON `{}`, 허용 Origin/custom header. 바인딩 cookie 발급 |
| POST `/api/auth/google/login` | `{challengeId, credential}`. 앱 TokenResponse 반환 |
| POST `/api/me/google/challenges` | 기존 JWT + LINK. userId 요청 입력 없음 |
| POST `/api/me/google/link` | JWT + `{challengeId, credential, password}`. 성공204 |
| GET `/api/me/auth-methods` | 본인 `{local,google}`. google_sub 원문 비노출 |

공개 callback API라고 하더라도 JSON token을 decode만 하거나 이메일을 신뢰하지 않는다. API 오류는 고정 코드만 사용하며 provider 원문/키/token은 반환·로그하지 않는다. Google credential은 기존 서비스 Bearer가 아니므로 REST/STOMP 인증에 직접 사용할 수 없다.

웹은 하나의 활성 LOGIN/LINK 시도만 유지하며 늦은 challenge·callback·로그아웃 후 응답을 폐기한다. 브라우저 Web Locks가 있으면 여러 탭의 최초 cookie/challenge 발급을 직렬화한다. 미지원 브라우저의 최초 cookie 경합은 바인딩 불일치로 실패해야 하며 잘못된 계정으로 인증을 통과시키지 않는다. One Tap/자동 계정 선택 없음. Google 인증 취소는 Google 전체 로그아웃이 아니다.

Android 코드는 접근·수정·빌드하지 않았다. 기존 내부 ID JWT와 REST/STOMP 계약은 동일하다. Android용 Google 인증 credential/client audience 허용은 이번 web Client ID 기반 구현의 실검증 범위가 아니며, 웹 GIS 계약을 네이티브 구현 완료로 표현하지 않는다.

## 실행 기록 — 갱신 중

- compileJava / compileTestJava PASS.
- 새 H2·실제 RSA·embedded WS 선택 실행: GoogleAuthIntegrationTest4, GoogleIdentityVerifierTest16, WebSocketConnectionIntegrationTest8 = 28 PASS/FAIL0/SKIP0. 이후 SockJS 만료 파라미터 테스트와 AI USER Tool 검증을 추가했으므로 최종 실행 결과와 구분한다.
- 웹 최초 단위93 PASS/FAIL0/SKIP0. Chrome desktop/mobile 기존 흐름 + 오프라인 GIS LOGIN/LINK/null loginId/외부 SDK 로드0 fixture PASS. 실제 Google 계정/GIS 서버 인수 아님.
- 신규 session `phase-a-20261016153410-53751378`, 최초 `google-e7a4a465`: PostgreSQL 경쟁/rollback 절차 이후 첫 Faucet503으로 FAIL. 구버전 사용자 선삽입 migration과 빈 장부 Faucet 활성화 테스트를 혼합한 fixture 문제. `FaucetStore.initialize`가 기존 사용자 존재 시 initialized=false로 차단하는 기존 정책을 확인했다. 이 실패를 보존하고 migration과 거래 fixture를 분리했으며 제품 Faucet 정책은 변경하지 않았다.
- 이후 실제 PG/Anvil 결과, 전체 회귀와 독립 검토는 완료 후 추가한다.

### 보완 및 새 실행

- 같은 session의 새 `google-4e676684`: 실제 PostgreSQL/Anvil 복합 시나리오 1 PASS/FAIL0/SKIP0. 서로 다른 challenge의 동일 sub 가입 경쟁에서 사용자1명/잔고2개, 같은 challenge 동시 소비1회, 중복 LINK1명만 성공, 잔고 초기화 예외 rollback 뒤 같은 challenge 재시도 성공을 확인했다. Google-only USER의 실제 서명 BUY/SELL FILLED·Trade2건·잠금0, 기존 로컬 계정 연결 전후 balances/orders/trades/Faucet 일치, 장부-체인 MATCH 및 거래 감사 MATCH를 확인했다. 실제 Google 토큰이 아닌 로컬 RSA issuer의 proof를 사용했다.
- 독립 신규 `google-migration-ea32f07b`: 실제 PG additive migration 1 PASS/FAIL0/SKIP0. 이전 NOT NULL email/password schema의 기존 사용자 값 보존, SQL 재실행 멱등성, Google-only nullable 필드/0원 잔고 생성, 기존 사용자 존재 시 Faucet 자동 활성화 금지를 확인했다. 기존 개발 DB migration 실행이 아니다.
- 독립 최초 검토 `review_google_auth`: 필수 수정3건. (1) 로그아웃 후 Google 패널 DOM 복원 누락 (2) production JWKS decoder 경로 검증 누락 (3) challenge/WS 부정 테스트 누락. (1) state.user 초기화 후 context 동기화, 모달 내 실제 표시 위치 검사와 LOGIN→logout→LOGIN Chrome 회귀로 수정했다. (2) 고정 production URI는 유지하고 동일 decoder factory를 loopback HTTP JWKS fixture에서 키회전/cache/RS256 외 alg 거부/timeout/503/disabled요청0으로 검증한다. (3) 누락·다른 cookie, 목적 혼용, 만료, 다른 LINK userId와 outbound enqueue/handler 직전 만료 차단을 추가했다. 최종 결과는 다음 실행에 기록한다.
- 보완 후 Chrome desktop/mobile + 오프라인 LOGIN/LINK/Google-only 로그아웃 재로그인 PASS. JS `element.click()`만으로 숨겨진 버튼 접근 가능성을 인정하지 않고 모달 내부와 `getClientRects()`를 함께 확인했다.
- 새 session `phase-a-20261016161110-7bd9faa6`, `backend-full`: 530중487 PASS/6 FAIL/37 SKIP/ERROR0. 신규 Google H2 context의 `@DirtiesContext`가 다른 cached context와 공유하던 `mem:exchange` 테이블을 drop한 테스트 격리 문제다. GoogleAuthIntegrationTest만 `mem:google_auth`로 분리했다. 최초 실패 XML/로그는 유지하며 재실행은 `backend-full-h2-isolation-fix`로 구분한다.
- Foundry는 `contracts/`에서36 PASS/FAIL0/SKIP0. 앞선 잘못된 작업 경로의 Foundry 호출은 import 해석 실패로 종료됐고 통과 결과에 포함하지 않는다. 컨트랙트 소스 변경 없음.
- `backend-full-h2-isolation-fix`: 최종 백엔드 전체530중493 PASS/37 SKIP/FAIL0/ERROR0. Google RSA16·loopback JWKS5·Google H2 통합5·WS outbound guard2와 기존 서버 native/SockJS 만료 테스트를 포함한다. 37 SKIP은 PASS가 아니며 opt-in 실제 PG/Anvil·AI 환경 테스트의 미실행을 뜻한다. 실제 PG 새 실행2개와 따로 집계한다. 기존 관측 제품은 변경하지 않고 신규 H2 테스트 DB 이름만 격리해 전체 회귀가 복구됐다.
- 최종 session의 `google-c0dd0502`도1 PASS. 이후 **기존 사용자에게 실제 확정 Trade1건과 잠긴 미정산 Order를 동시에 만든** 강화 사례 `google-f7e10bae`를 새 fixture에서 실행해1 PASS/FAIL0/SKIP0를 확인했다. 연결·Google 재로그인 전후 userId/role/잔고/잠금/Order/Trade/Faucet snapshot 동일, 기존 로컬 JWT의 /me·portfolio 접근 유지, 정산 후 장부-체인 MATCH/거래 감사 MATCH다. 테스트 소스만 강화했으며 제품 소스는 위 전체 backend 실행과 같다.
- 최종 별도 `google-migration-9bf0b82f`1 PASS/FAIL0/SKIP0. 최종 인수 단위는 강화 Google 거래1 + migration1 = **실제 PG/Anvil fixture2종 PASS**다. 앞선 재실행들을 서로 다른 기능 수로 합산하지 않는다. session별 모든 결과와 초기 실패 XML/로그는 ignored runtime에 각각 보존했다.
- 최종 웹93 PASS/FAIL0/SKIP0, Vite build 및 실제 Chrome desktop/mobile/오프라인 LOGIN/LINK/로그아웃 후 Google 재로그인 PASS. backend `bootJar --offline --no-daemon` PASS. Android 코드는 diff0이며 실행/빌드하지 않았다.
- 독립 1차 재검토: 최초3건 해결, 추가 필수 수정 없음. 검토자는 소스·테스트·문서를 읽었으며 실제 테스트·private 환경을 독립 실행한 것은 아니다. 이후 강화한 PG 기존 Trade 보존 사례와 최종 기록은 2차 재검토 대상으로 전달한다.
- **최종 2차 재검토: 발견된 필수 수정 없음.** 강화한 기존 Trade/미정산 Order/자산·Faucet/userId·role 보존 및 기존 JWT 검증과 문서 정합성을 확인했다. 검토자 직접 테스트 실행/기존 운영 DB/private 증거 열람/실제 Google 인증은 없다. 이번6개 fixture의 PostgreSQL/Anvil12개 컨테이너가 모두 stopped임을 소유권이 확인된 정확한 이름으로 조회했다. 삭제/초기화 없이 데이터·로그·체인은 보존했다.
- Git 변경 후보에서 기존 미추적 설계/NXT 문서2개와 이번 변경을 구분했다. 경로 검사 및 Google secret/private-key 패턴 검사에서 민감 파일/실키 패턴 발견0. 실제 .env/runtime/DB·chain/로그는 Git ignored이고 강제 추가하지 않았다. 이는 전용 secret scanner에 의한 무결점 보증은 아니다. HEAD와 melon-init ref는 모두 시작 a98ea4b이며 커밋하지 않았다.

### 최종 주요 실행 명령

PowerShell에서 `JAVA_HOME`은 설치된 JDK21, `GRADLE_USER_HOME`은 기존 Gradle 캐시로 지정했다. 실제 secret을 CLI 인자로 전달하지 않았다.

```text
python deployment/phase-a.py init
python deployment/phase-a.py fixture --session <new-session> --case google
python deployment/phase-a.py fixture --session <new-session> --case google-migration
```

전체 backend는 같은 harness의 `clean_env/verify_session/java_test`를 사용해 `--offline --no-daemon --rerun-tasks`로 실행했다. 첫 `regression`은 backend 실패로 후속 단계를 중단했다. H2 수정 후 재실행은 `backend-full-h2-isolation-fix`로 별도 저장했고, 웹은 `tools/websocket-test-client/`의 `npm test`, `npm run test:browser`, 컨트랙트는 `contracts/`의 `forge test --offline`을 별도로 실행했다. 이 절차를 하나의 연속 regression 명령이 전부 성공한 것으로 표현하지 않는다.

## 보존 및 미검증

기존 NXT 미커밋 문서, 루트 melon-init, 기존 worktree, KRX sourceHead/sourceHash/image/DB/Anvil/baseline/secret, 기존 playground는 변경하지 않는다. 신규 fixture 데이터·개인키·로그는 ignored `deployment/runtime/` 아래 ACL 경로에만 보관한다. 테스트 종료 시 이번 소유권이 확인된 신규 컨테이너만 정지하고 상태·실패 증거는 보존한다. commit/merge/push/deploy 없음.

오프라인 구현 완료 당시 실제 Google JWKS·공식 SDK·계정 선택·브라우저 privacy/팝업·CSP/FedCM은 NOT_RUN이었다. 후속 실제 가입·재로그인·연결은 별도 [실계정 인수 기록](google-auth-live-acceptance-2026-10-17.md)에 추가했으며 모든 브라우저/privacy 조합까지 검증한 것은 아니다. 실제 운영의 기존 DB migration은 실행하지 않는다. 구버전 fixture SQL 통과는 모든 역사적 DB의 자동 이전 보장이 아니다. 기존 KRX 이미지에는 Google 기능이 없으므로 향후 KRX 인수 결과와 Google 최신 소스 검증을 구분해야 한다.

## 실제 Google 연결에 필요한 사용자 준비

1. Google Cloud/Google Auth Platform에서 Web application OAuth Client ID 생성. Client Secret은 이번 JS callback에 필요하지 않다.
2. 동의 화면과 테스트 사용자 준비. Authorized JavaScript origins에 **실제 접근할 웹 origin**(예: `http://localhost:5173`, 운영형 로컬 proxy라면 해당 별도 port)을 정확히 등록. JS callback이라 redirect URI 전달 흐름은 사용하지 않는다.
3. 승인된 신규 전용 설정에서 `GOOGLE_AUTH_ENABLED=true`, `GOOGLE_CLIENT_ID`, `GOOGLE_ALLOWED_ORIGINS` 지정. 실제 .env나 보존된 KRX secret을 자동 수정하지 않는다.
4. Google SDK 및 고정 JWKS 외부 연결과 실제 계정 사용을 별도 승인한 뒤 신규 가입/기존 로그인/연결/취소를 인수한다. 이 문서의 오프라인 테스트가 이를 대체하지 않는다.

공식 검증 기준: [Google 서버 ID Token 검증](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token), [GIS JS nonce/callback](https://developers.google.com/identity/gsi/web/reference/js-reference). 실제 제약과 결과의 정본은 현재 코드와 이번 실행 증거다.
