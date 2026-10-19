# Futures 개발 재개를 위한 선택적 이미지 정리

> 2026-10-19, `feat/futures-engine` / HEAD `452c5db8e0ef9e92a81d79323af2e8b938b7c1dc`.
> 사용자가 승인한 조건을 모두 만족하는 이미지만 정확한 ID로 개별 삭제. prune/강제삭제/컨테이너·volume·증거 삭제 없음.

## 재조사와 제외 기준

- 시작 시 Docker df image87개, 컨테이너214개. 모든 컨테이너 inspect의 실제 image ID 참조를 대조했다. 컨테이너 참조 이미지13종은 모두 보존했다.
- 저장소 `deployment/runtime`의 하위 worktree까지 조사했다. secret/log/DB/chain/evidence 내용은 제외하고 JSON/YAML/properties 설정의 image ID/tag 참조만 대조했다. 설정 원문은 출력하지 않았다.
- 초기 루트 조사에서 참조가 없어 보였던 과거 Phase A 도구도 하위 worktree의 session.json이 참조했다. 과거 PASS/실패 fixture의 image identity 재현성을 위해 보존했다.
- 이전 reserve 도구 `5df67bf3f296...`/`b59e6841063e...`는 run.json에 image ID가 직접 없지만 해당 이름의 fixture가 존재하며 `reserve-e2e.py`가 run명으로 image tag를 구성한다. 간접 의존성이므로 제외했다.
- 현재 Phase A compiler 기반 `tokenized-reserve-tools:reserve-e2e-20261009043259-4ac3a5`, 최신 Futures 도구, KRX/Google/playground 도구 및 backend/web 이미지는 보존했다.
- `tokenized-market-backend:release`/`tokenized-market-ops:release`는 현재 playground/KRX 준비/local-smoke 등의 기반이다. 컨테이너 참조0이어도 제외했다. web:release도 기존 production 배포 구성용으로 보존했다.
- 다른 프로젝트, dangling 소유권 불명 이미지, registry 이미지, DB volume은 삭제하지 않았다. 컨테이너가 중지됐다는 사실만으로 삭제 후보로 선정하지 않았다.

## 실제 삭제한 정확한 이미지

| 항목 | 확인 |
|---|---|
| ID | `sha256:e281ca41aacac1260417597bd34b019b688857f3527c4f5d7cb8fa3a2516d010` |
| 유일한 tag | `tokenized-market-web:local` |
| 생성 시각 | `2026-10-04T19:14:12.253054336Z` |
| 성격 | 초기 제품형 웹 Docker build 검증 이미지, 실제 backend 연결 인수 결과가 아님 |
| 근거 | `claude-docs/web-product-ui.md`, `tools/websocket-test-client/README.md`의 local build 명령, 해당 모듈 Dockerfile 및 image history의 nginx HTML/template COPY/8082 upstream |
| 컨테이너 참조 | 삭제 직전 ancestor 조회0, 유일 tag 재검증 |
| 보존 identity 참조 | 위 runtime 설정 검색에서0. local 명령 예제는 있으나 고정 image ID를 요구하는 실행 manifest가 아님 |
| 삭제 방법 | `docker image rm <위 전체 ID>` 한 번. `--force` 없음 |
| 실제 응답 | `Untagged: tokenized-market-web:local`, `Deleted: sha256:e281ca...` |

재생성 경로는 기존 모듈의 `docker build -t tokenized-market-web:local .`다. Git 소스·Dockerfile·package lock은 보존했다. 현재 nginx base는 로컬에 있으나 node:22-alpine은 없어 재빌드 시 base/npm 네트워크 취득이 필요할 수 있다. 재빌드는 이번에 하지 않았고 과거와 같은 image ID 재생성을 보장하지 않는다. 보존된 인수 환경은 이 local ID를 사용하지 않는다.

## 전후 실제 측정

| 항목 | 삭제 직전 | 삭제 직후 |
|---|---:|---:|
| Docker image 수 |87|86|
| Docker image 총량(df 표시) |42.1GB|42.1GB|
| Windows C 가용 bytes |15,708,020,736|15,707,893,760|
| containers |214|214|
| named volumes |15|15|
| build cache |223개/3.943GB|223개/3.943GB|

삭제 대상의 df UniqueSize 표시는26.4MB였지만 이것을 실제 회수량으로 보고하지 않는다. 총량은 표시 정밀도에서 같고 Windows 가용량은 약127KB 감소했다. 공유 레이어/캐시/동시 시스템 쓰기 및 VHD 공간 반환 경계 때문에 **측정으로 확인된 Windows 확보량은 없다**. 보조 스크립트의 미계산 `imageLogicalSizeBefore=0` 값은 용량 판정에서 사용하지 않았다.

현재 Windows bind 가용 약15,337,356KiB, Docker data ext4 가용955,373,992KiB/inode 약1% 사용을 읽기 전용 재확인했다. 이것은 현재 저장 경계의 여유 측정이며 이전 ENOSPC 원인 해결이나 영구 정상화의 증거는 아니다. 원인 한계는 [저장공간 조사](futures-storage-incident-investigation.md)를 유지한다.

## 신규 fixture 재개

- 최신 productHash `060d393d36a1e4a55ae834d9ba0ea50eac14be8cc349cdaf1b85099b7fb4282e`가 준비 session의 해시와 같음을 재확인했다. manifest/sourceHead/sourceHash/image ID를 새 값으로 덮어쓰지 않았다.
- 기존 session의 보존된 도구 이미지를 사용하되 `fixture --case futures-restart`는 완전히 새 child/DB/Anvil/키/포트/디렉터리를 생성한다. 실패한 `futures-restart-0b5f8978`는 재사용하지 않는다.
- 새 실행은 PostgreSQL 초기화/SQL readiness/ownership/clock guard가 통과해야 계약 배포 및 prepare/recover 두 별도 JVM으로 진행한다. 준비가 실패하면 JVM PASS로 보고하지 않는다. 자동 재시도/기존 환경 초기화 없이 증거를 보존한다.
- 신규 실행 결과는 후속으로 추가한다. 이 시점에는 재시작 검증 **진행 중/미판정**이며 기존 requests/API PASS를 재시작 PASS로 합산하지 않는다.

### 새 실행 결과

- 신규 child `futures-restart-4f7696a5`, DB38537/RPC46961: PostgreSQL 초기화·SQL/RPC readiness·기존 격리 및 clock guard 통과. 기존 실패 child는 보존했다.
- prepare JVM **1 PASS/0 FAIL/0 SKIP**, recover 별도 JVM **1 PASS/0 FAIL/0 SKIP**. 두 실행을 한 JVM에서 수행한 테스트로 바꾸지 않았다.
- 정제된 복구 증거: nonce3, txHash `0x9f058e0d81ddd7649a926ebf8533aed07a54bc5ced2024842f5b83c69bddd627`, sameRawHash=true. canonical 실패 Receipt와 DB **REVERTED** 일치를 확인했다. 이 테스트에서 채굴 시각과 report.validUntil을 직접 비교하지 않았으므로 정확한 revert 원인은 미확정이며, TTL 만료로 단정하지 않는다. 이를 TOP_UP 성공으로 표현하지 않는다. 이후 기존 마진콜의 체인60초 유예 조건으로 위험 감시가 재개돼 최종 LIQUIDATED, journal 단일 정산과 잠금0 검증이 통과했다.
- 증거는 같은 session의 `evidence/futures-restart-4f7696a5`, `evidence/futures-restart-4f7696a5-restart` 및 child의 `restart-*.evidence.json`이다. raw/키/서명은 일반 문서에 포함하지 않았다.
- 이는 새 fixture의 실제 초기화/복구 성공이다. 이미지1개 삭제가 이전 ENOSPC를 고쳤다는 인과관계는 확인되지 않았다. 최신 양방향 auto/전체 회귀는 별도로 새 실행한다.
- 같은 productHash의 새 `futures-auto-f71b7b7b`: **1 PASS/0 FAIL/0 SKIP**. Long call1792390968→deadline1792391028→청산1792391031, 새 observedAt1792391029, 위험 이후64,642ms/GRACE 관측 이후62,843ms. Short call1792391035→deadline1792391095→청산1792391109, 새 observedAt1792391107,75,739ms/73,671ms. 실제 체인60초와 새 가격 조건을 우회하지 않았다. canonical Receipt/Event와 journal/잔고/잠금 검증을 통과했다.
- 새 실행 웹119 PASS, Chrome desktop/mobile **offline fixture** PASS, Foundry52 PASS/0 SKIP, Python18 PASS. `forge build --offline` 전체는 기존 `script/Scenario.s.sol:61` stack-too-deep로 FAIL이며 Foundry 테스트 통과와 분리한다. 백엔드 전체는 clean_env로 외부 provider/모든 opt-in gate를 끄고 별도 실행 중이다.
- 첫 새 전체 backend `futures-gate7-backend-full`:605중548 PASS/45 SKIP/**12 FAIL**. 세 클래스(OpenAiAgentProviderTest/ReadOnlyReceiptClientTest/GoogleJwksVerifierTest)의 loopback HttpServer 생성에서 native `SocketException: Bad address: bind/listen`이 발생했다. 외부 API 호출 전이며 제품 금융 로직 실패로 확정하지 않았다. 원래부터127.0.0.1/port0을 쓰는 테스트여서 임의로 바인딩/정책을 변경하지 않았다.
- 같은 소스/clean_env의 새 JVM에서 세 클래스만 원인 분리 실행한 `futures-gate7-loopback-diagnostic`은17 PASS/0 FAIL/0 SKIP. Python loopback bind/listen도 통과했다. 첫 실패를 삭제하지 않았고, 재발 여부 확인을 위한 전체 recheck를 별도 이름으로 한 번 실행 중이다. 정확한 Windows/JVM native 실패 원인은 미확정이다.
- 첫 staging 검사에서는 Java 소스 경로의 정상 `futures/runtime` 패키지를 실행 데이터 폴더로 오인해 중단했다. 실제 금지 대상인 `deployment/runtime`과 구분하도록 일회성 검사만 바로잡았으며 제품/harness 정책 수정은 없다. 재검사는 명시적22개 staged 경로, 신규 session fixture 비밀값33개 대조 일치0, 금지 경로0, cached diff check PASS다. 기존 NXT/사용자 문서는 제외했다. 전체 회귀 확인 전 커밋하지 않는다.
- 전체 새 JVM recheck `futures-gate7-backend-full-recheck`: **605중560 PASS/45 SKIP/0 FAIL/0 ERROR**. 앞선12 FAIL 실행과 별도 보존한다. 코드/정책을 바꾸거나 실패 테스트를 제외해서 통과시킨 결과가 아니다. native bind/listen 오류의 정확한 원인은 여전히 미확정이며 재발 시 별도 환경 조사 대상이다. SKIP45를 통과에 포함하지 않았다.
- 위 결과로 이 UI/marker/실제 PG·REST·STOMP/두 JVM/자동 worker 검증 단위를 명시적22개 경로만 로컬 커밋한다. 원본 melon-init 병합·push는 하지 않는다. 선물 감사·대사/실Chrome-to-chain/AI 및 기존 전체 Scenario build 실패가 해결된 전체 프로젝트 완료는 아니다.
