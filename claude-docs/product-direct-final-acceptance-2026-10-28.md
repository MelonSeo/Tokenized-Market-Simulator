# 통합 웹 C owner-direct 최종 인수 및 로컬 통합

## 판정과 범위

- 통합 웹에서 실제 사용자 확장 MetaMask로 **APPROVE → DEPOSIT → OPEN → CLOSE → WITHDRAW**를 직접 승인했다. 시험 EOA의 자동 승인을 실제 사용자 인수로 대체하지 않았다.
- 기존 다섯 거래의 canonical Receipt, calldata와 이벤트를 읽기 전용으로 대조했다. OPEN/CLOSE의 PostgreSQL 불변 실행 자료, 사용자 승인 범위와 실제 Report를 대조했고 포지션 CLOSED, Vault free/locked/unpaid 0, 빈 txpool, 지갑으로 전액 인출을 확인했다.
- 실제 지갑 주소, txHash, 배포 주소, DB, snapshot, 인증정보 및 상세 사용자 증거는 **로컬 비공개 runtime에만 보존**한다. 이 문서는 해당 자료 자체를 Git에 싣지 않는 공개 가능한 요약이다.
- 로컬 Anvil / 명시적인 Synthetic 3초 설정 / owner-direct / Long 거래의 인수다. 1초 Synthetic 안정성, relayer EIP-712/SIGNED 복구, C 자동청산 제품 연결, 전체 감사, AI, 공개 블록체인 배포 완료를 뜻하지 않는다.
- 로컬 통합의 최종 회귀·검토·커밋 결과는 아래 검증 항목에 기록한다. 원격 Push와 새 배포는 이번 범위가 아니다.

## MetaMask 실패 표시 조사

실제 사용자 화면에서 OPEN/CLOSE의 MetaMask Activity 실패 표시와 웹 CONFIRMED가 동일 거래에서 공존했다.

확인한 사실:

1. 다섯 거래 모두 실제 RPC 재조회에서 status `0x1`, type `0x2`, 원래 증거와 동일한 canonical blockHash, 연속된 사용자 nonce였다.
2. 현재 Receipt 필수 필드와 hex quantity 형식에 명백한 오류를 발견하지 못했다. 당시 MetaMask가 실제로 받은 응답과 확장 내부 상태는 보존되지 않아 현재 응답으로 당시 관측을 대신할 수 없다.
3. `CDirectWallet.send()`는 `eth_sendTransaction` 한 번만 호출하며 Activity 상태를 변경하는 API를 사용하지 않는다. 명시 거절과 UNKNOWN을 구분하고 늦은 txHash도 보존한다.
4. `resolve()`는 provider의 transaction/calldata/Receipt/canonical blockHash와 서버 검증 결과를 대조한다. `CDirectReceiptReader`는 owner, 전체 calldata, 이벤트 및 반복 canonical 조회를 검사하며 `completeCoverage=false`를 유지한다.
5. 최종 지갑·Vault·house·nonce·포지션이 보존된 정산 결과와 일치한다. 이번 완료 거래의 자산 정합성을 훼손한 증거는 없다.

**근본 원인은 미확정이다.** 당시 RPC 지연, 시각 차이, 같은 chainId를 쓰는 다른 로컬 체인의 이력, 확장 내부 관측 문제는 가설일 뿐이다. CLOSE에서도 발생했으므로 최초 OPEN의 시각 드리프트만으로 설명하지 않는다. 근거 없는 지갑 결함 단정이나 추측 코드 변경은 하지 않았다.

실패 표시를 믿고 같은 거래를 새로 전송하는 위험은 남는다. `기존 거래 조회 (재전송 아님)`을 먼저 사용한다. UNKNOWN/SUBMITTED/REVIEW_REQUIRED는 임의 해제하거나 다시 보내지 않는다. 지갑 프로필 초기화, nonce 초기화, 자동 재서명·재전송은 해결책으로 사용하지 않았다.

## 실제 코드와 책임

| 구성 | 책임 |
|---|---|
| `src/c-direct-panel.js` | 기존 TMS 로그인과 C 화면 연결, 명시적인 사용자 조건 확인, 버튼 인접 진행/오류 및 비활성 이유 표시 |
| `src/c-direct-wallet.js` | chain/genesis/owner 확인, EIP-191 소유 증명, 사용자 MetaMask 전송, 배포·사용자별 pending 보존 및 결과 조회 |
| `CDirectService` / `CDirectExecutionStore` | 동일 JWT 사용자와 지갑 연결, 실행 가격과 Oracle Report 준비, 불변 실행 자료 저장 |
| `CDirectReceiptReader` | canonical Receipt/calldata/events 검증; 전체 감사와 구분 |
| `ProductManualActor` | 새 로컬 시험 환경 준비와 keyless Spring 실행; 사용자 개인키나 자동 승인 도구 없음 |
| `CDirectOracleServer` | 별도 Oracle 역할만 가격 서명; 사용자키/relayer키 없음 |
| `product-manual.py` | source/build/계약 identity 검사, 자원 Guard, 소유 자원 기동·정상 종료와 상태 보존 |

일반 웹 JWT는 메모리 전용이다. 새로고침 후 재로그인이 필요하며 현재 수동 환경 JWT는 고정 5분이다. 거래마다 로그아웃하는 정책은 없고 자동 갱신도 없다. 제품 기본 만료와 수동 환경 설정을 혼동하지 않는다. 인증 정책은 이번에 변경하지 않았다.

## 수동 런처 운영

실제 사용자 개인키·시드·지갑 비밀번호를 입력하지 않는다. 새 Anvil에서 모의 토큰과 시험 가스만 제공하며 기존 사용자 체인이나 자산을 복사·초기화하지 않는다.

새 환경용 준비 순서(기존 보존 환경 재개 명령이 아님):

```powershell
# Java 21 / 기존 lock 의존성 / 고정 Solidity 빌드 산출물이 필요하다.
# backend 디렉터리에서 실행. 실행 중 서버와 무거운 빌드를 동시에 운영하지 않는다.
.\gradlew.bat -I ../deployment/product-read-ci.gradle -I ../deployment/product-direct-ci.gradle -I ../deployment/product-manual.gradle productManualBuildStamp --no-daemon --max-workers=1

# 저장소 루트에서: 현재 소스·빌드 stamp·helper self-test·SQL·계약 metadata 검사
python deployment/product-manual.py prepare

# 출력된 새 prepare 경로와 독립 시험 지갑의 공개 주소만 사용
python deployment/product-manual.py start <prepare-path> --owner <public-test-wallet-address> --execute

# 출력된 실제 session 경로 사용
python deployment/product-manual.py status <session-path>
python deployment/product-manual.py stop <session-path>
```

- Windows 가용 물리 RAM 1,920MiB / 디스크 2GiB Guard를 유지한다. 게스트 RAM/swap으로 대체하지 않는다. 사용자 프로그램 종료·전역 설정 변경으로 우회하지 않는다.
- 새 소유 네트워크·PG·Anvil·Oracle·Spring만 사용하며 웹/RPC는 IPv4 loopback으로만 게시한다. 일반 named bridge의 외부 통신 차단을 보장한다고 주장하지 않는다. 기존 read CI의 internal network 정책은 그대로다.
- 새 빈 볼륨의 루트 디렉터리만 UID/GID 1000 소유로 초기화한다. 기존 볼륨 재귀 chown/chmod나 privileged 실행은 하지 않는다. 상태 파일 쓰기와 컨테이너 내부/네트워크/호스트 RPC를 각각 검사한다.
- 수동 감독 프로세스와 App/Oracle는 **명시적인 STOP 또는 실패로 종료**한다. 자동 운영시간 만료는 없다. CI의 숫자 수명 제한은 유지한다. 거래 신선도 5초 / Report 30초 / Intent 120초는 운영시간과 별개다.
- `stop`은 요청일 뿐이다. 감독 프로세스의 normal-stop, 소유 컨테이너 종료 결과, PG dump와 Anvil snapshot의 보존 결과를 `status` 및 해당 lifecycle에서 확인해야 정상 종료 완료다. 자원 삭제는 하지 않는다.
- `start`는 새 fixture용이다. 과거 session을 새 배포·초기 자금으로 대체하거나 소비된 claim을 재사용하지 않는다. 기존 상태 재개는 동일 chain/DB/nonce/code/자산과 현재 산출물 identity를 별도 검증해야 하며 이 명령을 재개 명령으로 사용하지 않는다.

### 시계 유지보수의 경계

복원 체인의 latest/pending 시각과 실제 시계를 따로 확인한다. `product_manual_clock.py`는 명시적으로 승인된 기존 환경의 보정 도구이며 일반 기동 때 자동 실행하지 않는다. 신규 기동 Guard는 그대로이고, 별도 명시 옵션의 1GiB 예외는 승인된 기존 시계 유지보수에만 해당한다.

보정은 App/Oracle admission pause, 빈 pool, 사용자 pending 없음, DB 실행 자료 없음, locked 0, identity/과거 Receipt/proof 보존을 요구한다. 적용 후 응답 유실은 자동 재시도·rollback하지 않고 불확실 상태와 pause를 보존한다. ACTIVE 담보가 있거나 과거 이력이 도구의 제한을 넘으면 재사용할 수 없다. 정상 블록 간격 조정의 개별 운영 결과도 보편적인 Anvil/Windows 근본 해결로 확대하지 않는다.

## 사용자 클릭 순서와 오류 확인

1. 로그인 → **내 지갑 · C 자산** → **MetaMask 연결** → **소유 증명 및 계정 연결**.
2. 명시적 금액 → **이 금액만 Vault 허용 (approve)** → 사용자 승인 → **기존 거래 조회 (재전송 아님)**.
3. 확정 확인 후 **내 토큰 예치 (deposit)**를 별도 승인·조회한다.
4. **새 포지션 OPEN**에서 방향·레버리지·담보·허용 최소/최대 가격을 직접 확인한다. **조건 고정 · draft 확인** 후, 서명 준비가 됐을 때 **새 실행 가격 확인** → calldata/조건 확인 체크 → **MetaMask에서 직접 거래 승인·전송**.
5. OPEN 확정 후 positionId를 보존한다. **기존 포지션 CLOSE** → 같은 ID → **본인 포지션 조회** → 허용 가격 범위도 직접 입력 → 새 draft와 Report를 확인하고 사용자 승인한다. CLOSE 선택만으로 가격 입력이 생략되지 않는다.
6. CLOSED와 담보 해제 확인 후 실제 free 금액만 **내 free 직접 인출**로 승인한다. 인출 Receipt 및 지갑/Vault를 다시 대조한다.

가격칸은 쉼표/단위 없는 일반 소수, 최대 8자리다. 형식 오류는 서버 요청 전에 차단될 수 있다. 상단 상태 외에도 버튼 바로 아래 진행/오류를 표시한다. 만료되거나 범위가 벗어나면 중단하며 조용히 허용 범위나 실행 데이터를 바꾸지 않는다. 보고서 발급 후 사용자 확인과 채굴 시간도 30초에 포함된다.

## 최종 검증·통합 상태

- 기준 자동 통합 CI: `4af087934519071de71884273fa680853b60c72f`, [run 40341072686](https://github.com/MelonSeo/Tokenized-Market-Simulator/actions/runs/40341072686). 실제 read/write Gate PASS는 당시 시험 EOA 실행 범위다.
- 현재 사용자 수동 5거래: PASS. 보존 자료와 별도 실제 RPC 재조회, 최종 자산 검증을 수행했다. 이미 성공한 거래를 재실행하지 않았다.
- 현재 웹 순수 회귀: 231 PASS / 0 FAIL / 0 SKIP.
- 수동/시계 Python 56, 직접 CI 하네스 8, 기존 read 하네스 72 PASS(자원22 포함). 실제 서버를 새로 실행한 결과로 표현하지 않는다.
- Java 21, 단일 worker, Gradle 256MiB/Test 192MiB의 순차 실행으로 manual/read/direct helper 컴파일과 관련 Java 75 PASS / 0 FAIL / 0 SKIP. 실제 컴파일된 Oracle/manual helper self-test PASS. 첫 단독 manual self-test 호출에서 기존 필수 transport JVM flags를 빠뜨려 차단됐으며, 런처와 같은 FLAGS로 실행해 통과했다. 정책 검사를 삭제하지 않았다. 기본 PATH JDK가21이 아니어서 차단된 것은 검증 프로세스에만 설치된21을 지정해 해결했다.
- 현재 웹 프로덕션 빌드 PASS(107 modules). 서버의 복사된 실행 bundle은 건드리지 않았다. 금융 계약·제품 Java·JWT/Oracle 정책의 새 수정은 없다.
- 실제 index와 들어오는 기존 커밋의 25파일·약832KB를 Gitleaks 8.24.2 및 식별자/금지파일 검사로 확인했다. 탐지0, 현재 사용자 지갑/실거래/배포 식별자0, 설치 절대경로0, 비공개 파일0. 절대적인 무유출 보장은 아니다.
- 최종 독립 검토: **발견된 필수 수정 없음**. 실제 index13파일과 기존3커밋, source/자원/소유권/금융·인증·TTL·pending 경계를 대조했고 검토자가 Python manual/clock56+direct8 및 웹41을 직접 PASS했다. MetaMask 별도 독립 조사는 실제5Receipt/현재자산과 direct25회귀를 확인했으나 근본원인은 미확정이다. 검토자는 Java75/전체웹231 또는 사용자서명을 직접 실행한 것으로 표시하지 않았다.
- 로컬 커밋과 병합 후 결과는 완료 후 기록한다. 새 실환경 Gate나 추가 거래는 실행하지 않았다.
- 원래 read/direct CI의 판정 기준과 workflow는 완화하지 않는다. 이번 작업은 로컬 검증·병합이며 CI 재실행 또는 원격 Push는 하지 않는다.
- 로컬 상세 인수 보고서·회고·runtime은 커밋하지 않는다. `implementation-log.md`에는 익명화된 통합 기록만 선별 반영한다.
