# C4a — 순수 Vault 자산·Claims projection

2026-10-23. [C4 계획](dapp-c4-projection-plan.md)의 첫 독립 단위다. C0~C3 완료 후 신규 C 패키지에만 구현했다. **DB/RPC/서명 검증기·전체 감사가 아니라, 사전 검증된 typed 거래/이벤트를 환원하는 순수 Java 회계 projection**이다.

## 구현과 한계

- `custody/model/CCustodyModels.java`: 배포 identity, block/tx/log 위치, 직접/relay/risk/cancel/자산 호출과 실제 ABI 회계 subset, immutable owner·position-lock·Claims·nonce·pause 상태.
- `custody/projection/CCustodyReducer.java`: BigInteger 최소단위, 같은 tx의 필수 이벤트·주소·순서·operation/position/owner 연결, 후단 실패 시 원본 상태 불변, exact replay no-op/동일 key 다른 payload 거부, 같은 canonical chain의 다른 block에서 같은 txHash 재귀속 거부.
- free/locked/house/실제 관측 Vault 자산·미귀속 차이와 Claims 부채를 분리한다. 기존 미지급 우선권·부분 지급·head/next/완료 기록·OPEN 차단·pause·취소 nonce·위험 action의 회계 불변식을 검증한다.
- 순서/상태·owner/position/Claim/transaction/log 수 한도, 잘못된 bootstrap 상태, 전역 nonce·operation map, 중복 active owner, FIFO를 어긴 seed를 거부한다. self-transfer로 미귀속/입금 자산을 만들어내지 않는다.
- 전체 bundle 완료 뒤 자산 보존식을 검사한다. ERC20 Transfer→Withdrawn 중간 상태만 보고 유효한 인출을 거부하지 않으며 ClaimCreated→PositionSettled를 한 tx로 연결한다.
- public 상태의 `completeCoverage()`는 **항상 false**, scope도 고정이다. RPC canonicality·ECDSA/domain/전체 calldata·가격/수량 산식·5/30/120/60초·전체 ERC20 supply/외부 지갑 잔고·canonical fork 선택은 이 단위가 검증하지 않는다. 모델의 terminal collateral은 **Vault lock0**이며 rules의 terminal 역사 담보 보존 필드와 혼동하지 않는다.
- 아직 Spring bean/API/scheduler/schema/ledger/sender에 연결하지 않았다. 예치/거래/복구를 실행하지 않는 읽기 전용 라이브러리다. 신규 DB 저장·실제 scanner·Receipt verifier·C 감사 MATCH는 후속이다.

## 검증과 발견 처리

초안 자체 점검에서 서로 다른 block의 동일 txHash 재귀속, Vault 자기 전송의 자산 증대, nonce/operation·불완전 seed·후순위 선지급 seed 허용 가능성을 발견해 첫 실행 전에 거부 검사와 회귀를 추가했다. 운영 환경에서 발생한 장애를 해결한 것으로 기록하지 않는다.

최초 runner 사전검사는 기존 Gradle runtime classpath에 compile-time APIGuardian annotation jar가 없어 `CACHED_JUNIT_DEPENDENCY_MISSING_OR_AMBIGUOUS:apiguardian-api`로 중단됐다. C 코드·DB·요청 실행 전이다. 캐시의 정확한 `apiguardian-api-1.1.2.jar`를 확인해 읽기 전용 연결했으며 다운로드/의존성 업그레이드/기존 app class 복사는 하지 않았다.

`python deployment/c-projection-check.py`:

- Java21.0.10 javac `--release21` / JUnit5.10.3 및 platform1.10.3 캐시7개만 사용. 기존 app classes·Spring·외부 API 설정·DB/RPC opt-in 환경은 로드하지 않는다.
- 직접 JUnit **28 found /28 started /28 PASS /0 FAIL /0 SKIP /0 ABORT**, compile/test exit0, source 불변.
- 실행 `deployment/runtime/c-projection/20261022T204008Z-2fc1cf7d`.
- Java3소스+전용 launcher aggregate `cb29e4ba980265c332b313063adfc89c5bdae9c4d4cf766ed937b04389e19de3`; Foundry 계약 aggregate `c42e…`와 다른 범위다.
- 새 실행 폴더에 정확한 소스 snapshot, 사용 JUnit jar SHA-256, compiler/test stdout·stderr·명령·JDK·exit를 보존했다. JVM/컴파일러192MiB, Gradle daemon/worker socket 및 Docker 사용 없음.
- 실제 테스트는 정상 현금/부채 시퀀스, 누락/재정렬/상충 증거·whole-tx rollback, 동일block순서·fork/gap 거부, 재시작 exact replay·보존 ancestor에서 별도 branch 재생, direct/relay/cancel/risk 구분, 호출자·대상·owner 불일치 거부, overflow/1wei/미귀속·자산 비전용·부실 seed를 포함한다. 실제 서명 검증 PASS를 뜻하지 않는다.

## 독립 검토 및 다음 단위

C4a 최초 독립 검토에서 **발견된 필수 수정 없음**이다. 별도 검토자가 실제 모델/reducer/테스트/runner와 28PASS 원본 로그·소스 해시·한계 표현을 읽기 대조했다. 검토자의 재실행은 없으며 구현자의 테스트 결과와 구분한다. 다음 C4b에서는 additive PostgreSQL evidence·projection·cursor 원자 저장을 구현한다. 일반 Gradle 전체 backend, 실제 PG/Anvil·canonical RPC·실제60초·MetaMask·최종 C 전체 감사는 NOT_RUN이다.

기존 root/B/UI/AI/감사 worktree 코드와 보존 DB/Anvil/사용자OPEN·평가 기록은 변경하지 않았다. 이 단위는 새 C Java 파일·테스트·무과금 직접 실행 도구만 추가하며 commit/merge/push/배포/유료 호출 없음.
