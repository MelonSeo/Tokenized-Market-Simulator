# C5a — Anvil 단독 실계약 Gate 계획

작성: 2026-10-23. **계획만 작성했으며 신규 하네스·컨테이너·계약 배포·RPC 실행은 아직 없다.** 사용자 C 구현 승인 원문의 신규 로컬 격리 테스트 범위에 한정한다. C4b PostgreSQL 실제 테스트·추가 검토 예외는 여전히 승인 대기이며, 이 계획은 PG를 다른 이름으로 실행하는 우회가 아니다.

## 가장 작은 실행 단위

우선 **C5a1: 배포 identity + 실제 자산 입출금 + 사용자 OPEN/TOP_UP/CLOSE**를 독립 단위로 추천한다. 그 이후 **C5a2: 실제 60초 위험 처리 + 지급부족/FIFO Claims**를 별도 소규모 배포에서 검증한다. C5a1 성공을 C5a 전체 성공으로 표시하지 않는다. 현재 C 계약은 변경하지 않으며 기존 Foundry141 PASS와 새로운 EVM 실행을 구분한다.

일반 RPC 수집기·스캐너·Spring 서비스 대신, 고정된 시험 시나리오만 실행하는 좁은 Java 프로그램을 사용한다. reviewed ABI/EIP-712/raw/Receipt/회계 순수 검증기를 재사용할 수 있으나, 실제 RPC·배포·생성 트랜잭션 검증까지 기존 순수 단위가 보장한다고 가정하지 않는다. 특히 현재 envelope는 contract creation을 지원하지 않으므로 배포 영수증 검사는 별도로 명시한다.

### 신규 파일 제안 (아직 생성하지 않음)

- `deployment/c-anvil-check.py`: 자원/이미지/경로 preflight, append-only run manifest, 신규 내부 네트워크와 소유 Anvil/JRE 컨테이너 관리, bounded 실행 및 소유 컨테이너 정지.
- `deployment/test_c_anvil_check.py`: fake Docker/RPC/파일 경계 시험. 실제 네트워크/서비스를 호출하지 않는 오프라인 테스트.
- `deployment/CAnvilContractGate.java`: JDK21의 명시적 HTTP RPC 클라이언트, 고정 C5a1 시나리오와 safe evidence 기록. 실제 사용자 키·기존 fixture 접근 경로 없음.
- 필요한 경우 같은 `deployment` 아래 고정 whitelist ABI/RPC helper 및 JUnit 테스트만 분리한다. 기존 C 제품·codec/store/financial wrapper·기존 하네스는 수정하지 않는다.

## 실행 권한과 격리 경계

- 매번 새 runId, 전용 Docker `--internal` 네트워크, 새 Anvil 데이터 볼륨, 전용 Anvil/JRE 컨테이너를 만든다. `-p`, `--network host`, 기존 네트워크/볼륨/컨테이너 연결은 금지한다.
- 두 컨테이너는 `http://anvil:8545`로만 통신한다. JRE는 RPC 목적지를 manifest의 해당 내부 주소로 고정하고 외부 URL·리디렉션·HTTP 프록시·환경 프록시를 거부한다. 모든 서비스는 소유 label/runId로 묶는다.
- 기존 캐시 이미지 digest만 사용한다. 새 pull/build/다운로드는 하지 않는다. Foundry 후보 digest는 `ghcr.io/foundry-rs/foundry@sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17`, JRE 후보 ID는 `sha256:35be56d41389c9ad515c089dfded911ae9a8bdb362c8a303224666cd358bead3`이다. 실제 image 존재·실행 파일·JDK21은 preflight로 재확인한다.
- 캐시 JRE 이미지에는 Spring ENTRYPOINT가 있을 수 있다. **명시적 `--entrypoint java`**를 사용하며, C4b에서 실패한 기본 ENTRYPOINT 실행을 반복하지 않는다. Anvil도 실행 파일 경로와 entrypoint를 사전 확인한다.
- source/artifact/classes는 새 snapshot을 read-only로 제공하고 결과는 새 run 경로에만 쓴다. Linux 빌드 캐시와 산출물은 원본/다른 worktree에 쓰지 않는다. 실행 전후 소스 SHA-256 및 보호 목록을 비교한다.
- JRE `-Xms32m -Xmx192m`, 단일 실행, Anvil 메모리 제한 후보256MiB를 사용한다. 시작 전 호스트 실제 가용 RAM/디스크, Docker Server 응답, 캐시 크기를 제한시간 내 확인하고 안전 여유가 없으면 BLOCKED한다. sandbox의 `Get-CimInstance` 권한 거부와 비어 있는 PSDrive Free는 측정 실패일 뿐 실제 가용 RAM/디스크 값으로 쓰지 않는다.
- 시작 기준 후보는 호스트 가용 RAM 최소1.25GiB·해당 드라이브 가용2GiB·Docker Linux 내 두 컨테이너 제한 합계+JVM native overhead를 감당할 여유다. 이는 관측 보장값이 아니라 보수적 하네스 사전 기준이며 부족하면 무리하게 heap을 줄여 PASS를 만들지 않는다. 신규 PG를 띄우지 않아 PG/Anvil/JVM 동시 환경보다 부담이 작다. 초기 증거/Anvil 디스크 예상량은 수십~수백MiB 이내지만 run 디렉터리와 로그 상한(예: 각10MiB/합계128MiB)을 강제하고 한도 도달은 STOP한다.
- 네트워크·볼륨·컨테이너의 이름/label/mount identity를 기록한다. 종료 시 **해당 run 소유 컨테이너만 정상 stop**하고 데이터 볼륨·실패 로그·manifest를 보존한다. 삭제/prune/전역 재시작은 하지 않는다.

## 소스·artifact·키·배포 identity

1. 제품 계약 source snapshot 및 compiler/remappings/settings SHA를 기록한다. 기존 Foundry 증거 `c-foundry/20261022T202307Z-0cb29292`의 source `c42e04888a601d23cab83f9f885723e2bf779d19d8b92c3ce748df8cf1797dac`와 byte-identical 여부를 직접 확인한다. 기존141 PASS를 새 체인 PASS로 승계하지 않는다.
2. 캐시 `c-foundry-out/CMockCollateral.sol/CMockCollateral.json` 및 `CCollateralizedFuturesController.sol/CCollateralizedFuturesController.json`은 현재 존재한다. **존재만으로 source 대응을 확정하지 않는다.** artifact metadata/build-info, compiler settings, source hash, bytecode/immutableReferences를 검증하고 대응이 불명확하면 오프라인 새 artifact 검증을 별도 수행하거나 BLOCKED한다.
3. Java 내부에서 신규 로컬 시험 EOA를 만든다. treasury/admin/relayer/Oracle/userA/userB를 분리하고 기존 MetaMask·사용자·B/AI 시험 키를 사용하지 않는다. 개인키는 새 private evidence에만 저장하고 stdout/보고서/예외에 출력하지 않는다. 기존 공개 fixture 키17/23/29를 실제 독립 사용자 통제 증거로 주장하지 않는다.
4. Anvil 내장 funded key를 treasury처럼 쓰지 않고 계정 자동생성은 최소화한다. 신규 시험 주소의 **native gas만** 신규 Anvil의 명시적 bootstrap RPC로 공급할 수 있다. ERC-20 잔고는 constructor mint와 실제 transfer/fund/deposit만 사용하며 storage override는 금지한다. native gas bootstrap 사실과 금액은 manifest에 표시한다.
5. 실제 chainId, genesis hash, 배포 raw/hash/nonce, canonical 생성 Receipt, token/controller 주소, controller 내부 생성 rules/Vault 주소, 각 runtime codeHash를 기록한다.
6. token 고정 공급20m·18dec·treasury constructor mint, Vault.asset/controller, controller.relayer/pauseAdmin/rules/vault, rules.executor/priceSigner/symbol/policy를 실제 getter로 확인한다. 각 immutable role의 관계와 모든 주소의 distinct 조건을 확인한다.
7. immutables가 있는 runtime bytecode는 artifact template과 단순 hash 일치시킬 수 없다. `immutableReferences`의 위치/폭과 getter 값, 나머지 bytecode 구간의 일치로 검증하고 실제 deployed codeHash를 별도 보존한다. 단순히 현재 runtime hash를 처음 읽었다는 이유만으로 reviewed artifact identity가 입증됐다고 쓰지 않는다.
8. 사용자 domain은 controller, 가격 domain은 rules임을 실제 digest getter와 reviewed Java EIP-712 계산으로 대조한다. Ethereum tx nonce·controller user nonce·rules operationId를 별도 기록한다.

## C5a1 실제 시나리오와 성공 기준

1. **배포와 공급:** treasury constructor mint Transfer 한 건, 총20m. 실제 transfer로 H10m/U2m(A/B 각1m)/R8m 분배. treasury 잔고와 supply 합계, 각 주소 balance를 대조한다. owner/mint/burn/sweep 등 존재하지 않거나 허용되지 않는 권한을 새 시험 주소에서 실제 실패 트랜잭션으로 구분한다.
2. **실제 house와 사용자 담보:** treasury의 approve→fundHouse10m, 각 사용자 approve→deposit. actual ERC20(Vault), free/locked/house/recognized/unassigned를 실제 getter 및 이벤트와 대조한다. 사용자 own-free withdraw는 직접 사용자 트랜잭션으로 수행한다. transferFrom allowance 소비는 OZ5.1의 finite/MAX 및 no-Approval 규칙을 따른다.
3. **OPEN:** 사용자A의 실제 Intent EIP-712 서명과 별도 Oracle Report 서명으로 relayer executeRelayed. 서버 가격 진실이나 Synthetic 시세를 증명하는 시험이 아니라 신규 Oracle 시험 값임을 명시한다. 120초/30초·신선도는 계약/하네스 각각 범위를 표시한다. 실제 canonical receipt의 rules2+Vault lock+controller 이벤트 순서와 전체 필드, locked/free/position/nonces/usedOperation을 검증한다.
4. **TOP_UP:** 동일 사용자·포지션·정확 entry/quantity/version에 대해 새로운 사용자 승인/Report로 실행한다. 실제 lock delta·free·totalLocked, rules state와 operation 이벤트를 대조한다.
5. **CLOSE:** direct 또는 relayed의 반대 경로로 정상 종료하고 full Report/Intent/Receipt·Vault principal/profit settlement·house 잔고를 연결한다. terminal rules는 역사 collateral을 보존하지만 Vault lock은0/terminal=true임을 구분한다. principal과 지급 profit만 free로 들어가며 claim이 없는 충분한 house 시나리오를 사용한다.
6. **거부:** 사용자 서명 없는 relayer, 다른 owner/domain/nonce, 변조된 범위, 직접 rules/Vault privileged 호출, Oracle 키를 사용자 승인처럼 사용한 호출을 실제 EVM 실패 Receipt로 확인한다. estimate/eth_call 사전 거부는 별도 결과이며 실제 실패 Receipt PASS를 대신하지 않는다. 실패 nonce/가스 지출과 금융 원상 보존도 확인한다.
7. **동일점 실제 읽기:** fresh block/tx/receipt/코드/상태를 해당 block hash·number에 연결한다. 이후 receipt/block hash를 다시 조회해 해당 시험 시점 canonical 연결을 확인한다. 이는 전체 체인 coverage/reorg 대응 스캐너를 구현했다는 의미가 아니다.

## C5a2 후속 필수 범위 (C5a1 성공과 분리)

- 새 배포에서 Long/Short START_CALL→회복/CLEAR→다시 START_CALL→LIQUIDATE를 검증한다. deadline 전59초와 이후60초는 **실제 단조 wall clock과 채굴 block timestamp를 모두 기록**한다. `evm_increaseTime`, `evm_setNextBlockTimestamp`, vm.warp는 사용하지 않는다. fresh Report observedAt>=deadline 및<=현재 블록 조건을 지킨다. bounded60초 대기는 짧은 확인 단위로 나누며 Oracle Report는 끝난 후 새로 생성한다.
- owner cancelNonce 확정 후에도 keeper의 위험 처리에는 사용자 nonce가 필요 없음을 확인한다. 취소 전송과 확정은 구분한다.
- 실제 loss/badDebt 시 Vault에 들어가는 cash는 잠긴 실제 collateral 이내다. badDebt를 cash나 사용자 청구로 만들지 않는다. 토큰 보존과 house 회복을 실제 getter로 확인한다.
- 지급부족은 별도 **명시적 소규모 house 자본** 배포에서 시험한다. A/B를 unpaid 발생 전에 OPEN하고 A profit 종료→principal 인출→house 추가 자금→B profit 종료가 앞선 A를 추월하지 않는지 확인한다. 한 head만 partial/full,1wei,빈 queue,후속 claim append, OPEN-only block/pause와 기존 포지션 종료·입출금 허용을 검증한다.
- 실제 rule/Claims 상태와 full event는 재사용 순수 verifier의 사전 상태 입력과 비교할 수 있지만 prestate getter 관측/배포 artifact 신뢰·RPC 연결은 새 Gate에서 별도로 인수한다. 전체 감사 coverage.complete는 여전히 false다.

## 무과금 검증·실패 처리·실행 순서

1. 하네스 구현 전에 계획 검토/작은 범위 확정. 현재는 이 단계다.
2. fake RPC로 JSON-RPC id/result/error/HTTP·timeout/과대응답/unknown method/잘못된 chain/Receipt metadata/partial evidence를 시험한다. private 키·raw/signature·비밀 URL이 stdout/exception에 노출되지 않는지 검사한다.
3. 하네스는 `eth_sendRawTransaction` 자동 재시도0을 강제한다. 보낸 raw/hash/nonce는 신규 private evidence에 선저장한다. 응답 불확실은 UNKNOWN_TRANSMISSION으로 STOP하고 자동 재전송·새 거래 생성·새 fixture 루프를 하지 않는다. receipt polling만 bounded 읽기 전용으로 허용한다.
   RPC call timeout은 시험 전 고정(예: connect3초/read8초), 응답 크기1MiB, batch 비지원, receipt polling은 transaction당 최대45초/1초 간격의 읽기만 허용하는 최소안을 검토한다. 전체 watchdog은 C5a1 300초/C5a2 600초 후보로 두며 timeout은 정책/서명 TTL 자동 증가 근거가 아니다. RPC 대기 중 Report/Intent가 만료되면 새 승인/Report를 조용히 만들지 않고 해당 시나리오 실패로 남긴다.
4. source/artifact/compiled class hash manifest, 이미지·경로·RPC allowlist·소유 resources 검사를 offline 테스트하고 독립 검토한다. 실패 사례를 삭제하거나 성공으로 덮어쓰지 않는다.
5. 독립 검토 후 자원 preflight, 새 fixture **1회** 실행. 준비 실패 시 해당 fixture에서 중단한다. 단계별 PASS/FAIL/BLOCKED/NOT_RUN과 실제 raw submission/receipt 여부를 구분해 append-only로 남긴다.
6. 성공/실패 여부와 무관하게 bounded 종료와 소유 컨테이너 stop을 기록한다. 기존 자원과 소스 hash를 다시 대조한다. 독립 검토 상한을 다른 unit 이름으로 초기화하지 않는다.

## 완료로 표시하지 않을 범위

실제 PostgreSQL·C4b 저장/재시작, Spring Boot Worker, 제품 Synthetic 5초 시장 조건/provider/generation, Windows RPC/Vite 장애 해결, 실제 MetaMask 사용자 직접 승인, 브라우저 UI, 전체 감사·모든 tx coverage/reorg scanner는 **NOT_RUN**이다. C5a1/2에서 임의 지급 재원·자산 보관·청산 정책을 새로 결정하지 않는다. 현재 C 승인 정책을 실제 신규 로컬 토큰/계약으로 검증할 뿐이며 실제 가치·외부 네트워크·Oracle 가격 진실성이나 무조건 지급능력을 보장하지 않는다.
