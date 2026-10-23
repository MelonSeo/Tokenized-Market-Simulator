# C 거래 전송·복구·웹 연결 — 최소 후속 계획

## 현재 체크포인트 — E1a 실제 PG6 PASS / E1b55 PASS / E1c69 PASS·검토 대기

E1c 최종 동결소스의 직접 실행 `c-report-candidate/20261023T110636Z-387b1aef` / source `39b50dccf3631ee92dcc39a221d86d359cba4a20d1d251ede0367c46b82ad5d4`는 **C_REPORT_CANDIDATE_OFFLINE_PASS_NOT_SIGNED_OR_EXECUTED**, compile/test0·고정69PASS(기존55+새14)/FAIL·SKIP·ABORT0다. source/JAR/SQL resource 불변이며 Mock provider/prestate와 H2만 사용했다. 기존 금융/시장/proof/계약은 수정하지 않았다. builder는 RPC 완료 후 가격을 선택하고 명시 symbol을 고정한다. 실제 RPC가 소요된 정상 경로에서 prestate.observedAt이 시작시각보다 늦다는 사실을 반영하여 post-RPC clock으로 검사하며 +10초 관측 지연 정상시험을 포함한다. actual PG/RPC·서명·전송·웹 인수가 아니다. 현재 독립최초검토를 요청한다.

최신 실제 결과는 B `dapp-development-progress.md` 최상단을 따른다. C read/wallet 실제 HTTP·PG·Anvil `c-live-6f3298d68b9b4fa6` PASS, E1a metadata PG `ccpg-54a43006c5954d1e` 6PASS가 각각 완료됐다. E1b fresh pinned nonce·position 전제조건/nonce admission은 직접JUnit55PASS·기존조회53PASS·독립최초검토 필수없음이며 실제 nonce PG/RPC는 NOT_RUN이다. 아래 RAM 부족/의존개발 미시작은 과거 시점 이력이다.

현재 E1c는 unsigned `CReportCandidateBuilder`와 시험만 구현한다. 실제 고정 Reader 관측 뒤 명시 symbol과 identity.symbolHash의 연결을 확인한 `MarketPriceService`→기존 `FuturesMarketGate`로 가격을 선택한다. 기존5초/미래2초/동일provider·generation·exactprice 검증과 CRulesTransitionVerifier/FuturesMath의 산식·Report30초·상태전이를 재사용한다. 사용자120초 window와 Report시각 규칙은 분리한다. C controller executor/내부rules 가격domain을 사용하고 legacy UUID 재해시·ABI·DB잔고 예약은 사용하지 않는다.

candidate는 체인 확정·현재 서명동의·nonce 입장·Vault 예약·지급확정의 증거가 아니다. Mark/PnL은 결정적 예상치이며 실제 paidProfit/Claims를 관측 house잔액으로 확정하지 않는다. price signature/append-only revision/SIGNED/전송·복구·웹은 다음 범위다. nonce/revision의 실제 PG제약 검증은 관련 저장 경계 구현 후 새 단위에서 묶어 수행하며 현재 H2결과로 대체하지 않는다. 과거 Gate sourceHash/fixture/승인 claim은 수정하거나 재사용하지 않는다.

## 최신 순수 단위 완료 — 8 PASS / 최초 독립 검토 필수없음

`review_c_pure_typed_data` 최초독립검토는 **발견된 필수 수정 없음**이다. 실제 계약22필드/type/domain 및 renderer/test/새runner, 성공run `20261023T093549Z-839ee3e6`/`3fe389fd093f7fae4a02a1dad6bf71908a3e16d162fdb808517d5d7d848ec513` compile/test0·8PASS/무skip, source5개/class21개와 frozen Cread24/Gate34개 해시를 직접 확인했다. 검토자는 테스트/서비스/RPC를 재실행하지 않았다. 본순수단위 최초1회사용·후속재검토최대2회이며 기존C서비스 예외나한도를 재사용하지 않는다.

formatter 범위만 완료다. 실제 사용자승인/MetaMask·JWT/PG/RPC·거래 실행·C서비스 연결 PASS가 아니다. read/wallet실제Gate는 metadata예외검토완료/추가실행승인유효지만 RAM1486MiB<1920MiB로 아직미시작이다. 실제Gate가 통과하기 전 C작업metadata/Sender·복구·웹의 의존구현을 진행하지 않는다.

## 최신 독립 구현 — 순수 formatter8 PASS / 첫 독립 검토 중

RAM이 마지막 제한된 읽기에서1,558,663,168bytes(약1486MiB)<1920MiB여서 실제C서비스Gate를 만들지 않고, 계약/기존crypto에만 의존하는 순수formatter3파일만 구현했다. `CUserIntentTypedData.java`는 기존 Intent를 exact C domain/22필드/decimal문자열/deepimmutable JSON으로 표현할 뿐 현재nonce·권한·기한·가격·금융 유효성을 재판정하거나 서명/거래를 생성하지 않는다. frozen Cread347321/liveGatee1d/C5/API/schema/기존runner는 변경하지 않았다.

첫 실행 `c-intent-typed-data/20261023T093415Z-b225fafe`/`63fb67da…`는 javac JVM `-J-Xmx192m`을 @argfile에 넣은 실행기 인수 결함으로 compile2/testNOT_RUN이었다. 원본을 보존하고 해당 JVM 옵션을 directargv로만 분리했다. 후속 `20261023T093549Z-839ee3e6`/`3fe389fd093f7fae4a02a1dad6bf71908a3e16d162fdb808517d5d7d848ec513`은 **compile0/test0/8PASS/FAIL·SKIP·ABORT0**다. 실제JSON→web3j StructuredDataEncoder digest가 C golden/CEip검증기와 같고2^53초과/uint256Max도 보존한다. DB/RPC/서비스·키서명·유료0이다.

`review_c_pure_typed_data`가 이 독립 formatter 단위의 최초검토1회를 진행 중이다. 기존C서비스 검토한도를 초기화하는 변경이 아니며 본코드를 기존Gate나API에 연결하지 않았다. 첫검토 완료 전 안전완료로 표시하지 않는다. 실제서비스Gate는 metadata예외검토 완료/자원만대기 상태이며 실행1회는 미사용이다.

작성 2026-10-23. **최초 읽기 전용 계획 이후 총괄이 순수 C typed-data formatter 단위만 구현하도록 범위를 확정했다. 현재 소스 작성 완료·실행 및 독립 검토 대기이며, 실제 서비스/거래 완료가 아니다.** 승인된 C 경제 정책은 ADR C-001을 유지한다. 현재 상태는 B worktree `dapp-development-progress.md` 최상단과 C `dapp-c-service-read-wallet-2026-10-23.md`가 우선한다. ADR 아래쪽의 과거 C4b 미승인/NOT_RUN 문구를 현재 판정으로 사용하지 않는다.

## 1. 현재 근거와 선행 조건

- C5a1 실제 자산 흐름, C4b 실제 PG10개, C5a2 실제60초·FIFO Claims는 각각 별도 신규 환경/identity로 PASS했다. 시험 EOA·통제 Oracle/가격이며 MetaMask나 제품 Worker 성공 증거는 아니다.
- C read/wallet core `34732172…`의53PASS는 no-socket Mock/H2·직접 controller 범위다. 새 실제 서비스 Gate 준비 `e1d98f3c…`는 컴파일·self-test·Python13PASS이며, 컴파일 metadata 보완의 추가 예외 독립 검토는 필수 지적 없음으로 완료됐다.
- 계획 작성 중 최초 가용 RAM은 약1452MiB였고 총괄의 최종 읽기 전용 관측은1,558,663,168bytes(약1486MiB)로 최소1920MiB 미만이다. 실제 조회/지갑 연결 Gate는 **미실행**이며, 사용자 측에서 약0.5~0.8GiB를 추가 확보한 뒤 실행 직전 재확인이 필요하다. 이 문서는 기준 인하, 기존 앱 종료, 새 환경의 반복 생성 또는 미검증 read 서비스의 완료 승격을 허용하지 않는다.
- 본 분석은 서비스/DB/체인/테스트/유료 API를 실행하지 않았다. prod/test 소스·schema·기존 하네스·보호 파일·ledger/STOP/claim도 변경하지 않았다.

## 2. 지금 독립적으로 가능한 최소 단위: C typed-data formatter

**C 전용22필드 Intent를 EIP-712 JSON으로 표현하는 순수 formatter/DTO**는 실제 read 서비스·JWT·PG·RPC를 필요로 하지 않는다. 따라서 메모리 Gate와 독립적인 구현 후보가 될 수 있다. 단, 실제 사용자 승인 수락 API·Report·Sender·복구를 먼저 구현해 검증된 것처럼 표현해서는 안 된다. 구현 착수/분리 범위는 총괄이 확정한다.

추천 입력은 기존 `CAbiModels.Intent`와 명시적인 `chainId/controller`다. 기존 `CEip712Verifier`를 재사용하며 새 서명 알고리즘·금융식을 만들지 않는다. 후보 이름은 `custody/authorization/CUserIntentTypedData`다. 별도 금융 Vault/가상 예약 추상화나 자동 key/signature 생성은 필요 없다.

typed data의 네 최상위 키는 `domain/types/primaryType/message`로 고정한다.

- `primaryType = CFuturesIntent`
- 사용자 domain: `TokenizedMarketCFuturesUserIntent`, version `1`, chainId, **C controller**
- 서버 Report domain: `TokenizedMarketFutures`, version `1`, chainId, **내부 rules**. 사용자 JSON 안에 실행 Report를 다시 nested하지 않는다.
- 필드 순서는 Solidity `CFuturesIntentTypes.TYPE_HASH`/`Intent`와 정확히 같아야 한다.

```text
owner,address; relayer,address;
operationId,bytes32; positionId,bytes32; accountRef,bytes32; symbolHash,bytes32;
action,uint8; expectedVersion,uint256; expectedCallEpoch,uint256;
side,uint8; leverage,uint8; entryPriceE8,uint256;
collateralBefore,uint256; collateralAfter,uint256;
priceMinE8,uint256; priceMaxE8,uint256; quantityMin,uint256; quantityMax,uint256;
nonce,uint256; issuedAt,uint256; deadline,uint256; policyHash,bytes32
```

모든 정수 값과 chainId는 정확한 **decimal 문자열**로 직렬화한다. uint8도 JSON Number로 바꾸지 않는다. immutable map/list snapshot을 반환하고 null/변형·누락·추가 필드가 서명 대상과 다르게 보이지 않게 한다. 이 formatter는 구문·표현과 digest 일치만 다루며, 현재 JWT owner·현재 nonce·가격 신선도·실행 가능성·체인 확정·잔고를 검증했다고 주장하지 않는다. 구문상 유효한 Intent가 곧 실행 가능한 거래는 아니다.

### 순수 단위의 최소 검증

1. 22개 필드의 순서/타입/값과 domain/primaryType exact 일치, 원본 객체 불변.
2. 실제 JSON 직렬화→typed-data encoder digest와 기존 `CEip712Verifier.intentDigest` 일치. 단순 단어 포함이나 필드 개수만으로 PASS하지 않는다.
3. 기존 `CEip712VerifierTest.independentTwentyTwoFieldIntentGoldenMatchesAllHashStages`의 고정 vector 재사용: typeHash `0xae1bef5d472f4b46787dd0444f164ec31ebf4fc901b01ddf69c8ddf6a1733ff7`, 최종 digest `0xb81cb4bf2b9bad10a2613da0ec1df89549be8aaf991f3cf4149ef39ca02a3e67`. 이는 해시 vector이지 모든 금융 조건이 유효한 주문이라는 뜻은 아니다.
4. 2^53 초과 값/uint256 경계/8·18 decimal 정수 문자열을 정확히 보존. 잘못된 null/uint/주소/hash는 기존 syntax 모델과 같은 경계로 거부한다.
5. C/B domain·primaryType·chain/controller/필드 변경 시 digest가 달라짐을 확인. deterministic 공개 시험 key의 EOA 회복과 실제 사용자 지갑 서명을 구분한다.
6. Mock/JUnit/JSON formatter 결과를 실제 MetaMask `eth_signTypedData_v4`, 최신 C API 또는 PG/Anvil 거래 PASS로 기록하지 않는다.

## 3. 실제 C 실행 연결 순서

다음 범위는 실제 read/wallet Gate의 필수 경로가 통과한 뒤 의존 구현한다.

### E1 — C 전용 draft·사용자 동의·작업 metadata

1. JWT 본인 C link와 배포 identity를 고정하고 실제 pinned state에서 owner nonce/position version/epoch·현재 Vault 상태를 읽는다.
2. 사용자 선택의 담보/방향/레버리지·유한 가격/수량 범위를 받아 C Intent를 고정한다. OPEN entryPrice는0, TOP_UP/CLOSE는 기존 entry·quantity 조건을 유지한다. C accountRef는 `keccak256(abi.encode(keccak256("TMS_C_FUTURES_WALLET_ACCOUNT_V1"),owner,symbol))`다.
3. API의 position/operation 식별자를 C `bytes32`와 명확히 대응시킨다. B의 UUID를 hash하는 코드를 무심코 재적용해 이미 bytes32인 C ID를 다시 hash하지 않는다.
4. 사용자 서명은 외부 EOA가 생성한다. 수락 시 digest/owner/deadline/nonce/배포/position 조건을 다시 확인한다. 재서명·범위 확대·기한 연장을 서버가 자동 생성하지 않는다.
5. 가격 비의존 권한/RPC 준비를 가능한 한 먼저 끝낸 뒤 제품 Synthetic의 실제 commit/공개 경계에서 새 적격 가격을 선택한다. 서버5초/provider/generation, Report30초/미래오차2초, Intent최대120초를 각각 유지한다.
6. 기존 `FuturesMath.quantity/mark`와 실제 상태로 계산한 Report·서버 서명을 사용자 범위와 대조하고 revision0 및 작업 metadata를 원자적으로 저장한다.

**DB에는 작업·동의·관측 증거만 쓴다.** free/locked/house를 DB에서 예약·증가시키거나 잔고가 존재한다고 만들어서는 안 된다. Vault가 실제 실행 시 자금 조건을 원자적으로 결정한다. 사전 Vault 읽기는 실행 권한을 보증하지 않으므로 직접 인출·다른 거래와의 경합으로 최종 EVM이 거절할 수 있다.

최소 additive schema 후보는 C 배포별 drafts/operations/append-only execution revisions/active head/signed transactions/Receipt observations다. 기존 C wallet schema와 B 금융 tables를 수정하지 않는다. 각 요청은 `(deployment,user,idempotency fingerprint)`와 canonical request hash에 귀속하고 충돌·재전달을 구분한다. RPC·서명 작업은 장기 DB lock 밖에서 수행하며, commit 아래 exact draft/hash/scope/head를 재대조한다. commit-unknown이면 기존 작업 재조회 전 신규 operation을 만들지 않는다.

### E2 — Sender·SIGNED 경계·제한1회 revision

- 실제 관계는 `tx.from=relayer`, `tx.to=C controller`, `report.executor=controller`, 가격 domain=rules, 사용자 domain=controller다. 고정 relayer/Oracle 운영 키와 사용자 EOA 키를 혼동하지 않는다.
- 시장 가격이 바뀐 경우에만 PREPARED/raw없음/revision0에서 최대1회 새 Report를 확정한다. Intent/owner/user nonce/operation/position/정책/담보 승인·방향·레버리지·가격/수량 범위는 불변이다. stale/provider-generation 변경을 PRICE_CHANGED로 위장하지 않는다.
- OPEN 수량/entry는 기존 수학으로 재계산하고 TOP_UP/CLOSE의 entry·quantity는 그대로다. C에는 B의 DB 담보/house 예약 수정이 없지만 Report와 계산 근거·revision head·실행 calldata는 원자적으로 결합해야 한다. 이전 revision은 append-only로 보존한다.
- Sender는 operation/head와 durable relayer guard를 잠그고 활성 revision을 재확인한 뒤 raw/hash/Ethereum nonce를 **먼저 commit**한다. chain/genesis/relayer가 같은데 controller만 다르다고 별도 nonce guard를 만들어서는 안 된다. 초기에는 전용 relayer를 한 배포에 고정하는 방식으로 경합을 좁힌다.
- SIGNED가 한번이라도 영속화되면 raw/hash/Ethereum nonce/Report/revision을 바꾸지 않는다. 두 Worker 중 stale worker는 성공한 revision을 거절하거나 metadata lock을 지우지 못해야 한다. DB 상태 경합이 송신 금지 상태를 만든 경우 safe reject/observe로 처리한다.
- 원격 전송 전 raw 저장과 단일 전송을 지키고, 전송 결과가 불확실하면 원본 증거와 작업을 유지한다. HTTP 자동 retry0을 명시적 복구와 혼동하지 않는다.

### E3 — canonical 결과·Claims와 복구

Receipt의 전체 calldata/서명/digest/owner/action/operation/position, txHash/from/to/value/nonce, canonical header·blockHash·순서·emitter 및 정확한 C 이벤트를 연결한다. rules2개→Vault lock/settle 및 필요 claim→controller `CUserIntentExecuted`가 사용자 경로이며, 위험 경로의 `CRiskExecuted`는 별도다.

**유효 CLOSE의 DB 작업 완료와 profit 지급 완료는 다르다.** profit>0이어도 house/선행 Claims에 따라 paidProfit/claimId가 달라진다. 사전 house snapshot으로 지급량을 고정해 성공 Receipt를 잘못 거절하지 않는다. canonical 이벤트의 `paidProfit + createdUnpaid = positive realizedProfit`, claim owner/op/position/original 및 실제 거래 문맥을 검증하고, free/remaining의 현재 값은 기준 block을 붙여 별도 표시한다. badDebt는 정보이며 새 현금/사용자 추가 채무가 아니다.

같은 블록 안의 다른 거래 때문에 block-end getter가 해당 tx 직후 상태와 같지 않을 수 있다. 현재 `CTransactionProofVerifier`의 prestate와 `CGlobalProofOverlay`의 same-block root를 최신 getter로 임의 채워 full proof로 만들지 않는다. 정확한 prestate/cursor를 갖춘 범위만 기존 verifier로 연결하고, 부족하면 operation-level canonical Receipt 증거와 global projection 미완료를 분리해 `INCONCLUSIVE/completeCoverage=false`를 유지한다. `CCustodyProjectionStore`의 typed 입력 저장 PASS도 scanner/canonical truth를 대신하지 않는다.

복구의 초기 기본 동작은 observe-first다. 저장 raw/hash 검증→deployment/tx/Receipt/header 관측→같은 작업 상태 확인이며, unknown hash/nonce 증가/만료만으로 실패·잠금 해제·새 raw를 만들지 않는다. **B의 recover()가 unknown hash에 같은 raw를 자동 broadcast하는 부분은 그대로 복사하지 않는다.** 현재 불확실 자동재전송 금지 경계를 지키고, 실제 전송 재개는 명시된 복구 실행 권한과 확인된 상태·동일 raw 조건을 갖춘 별도 시험/동작으로 다룬다. 동일 raw 재관측·중복 정산 방지와 재방송 권한은 다른 조건이다.

canonical revert만 REVERTED로 판단한다. 증거 충돌·기한만료의 과거 처리 불명·reorg·nonce 소비 원인 불명은 REVIEW_REQUIRED/관측 미확정으로 보존한다. C DB는 실제 자산 잠금을 풀 수 없다. 서버 raw가 없는 owner 직접 거래·cancel은 실패가 아니라 별도 출처이며 canonical 수집/nonce event 연결이 필요하다.

## 4. B에서 재사용할 hunk와 제외할 것

아래는 읽기 시점의 B worktree SHA다. 파일 전체 복사/merge가 아니라 실제 필요한 hunk와 의존성을 대조한다.

| B 경로(backend/src/main/java/com/pricetrack/exchange/) | SHA-256 | 재사용/제외 |
|---|---|---|
| futures/authorization/UserIntentV2Codec.java | `5cac90edbe59c63262aa3d4d4881175ce8eca6aaa9aaf3e385e515b9aadad135` | 22필드 순서·값 문자열화/typedData 구조; B primaryType/domain/accountRef/ledger 검증 제외 |
| futures/authorization/AuthorizedV2Service.java | `e981e284dddf35da76b66b02594d0353373b5f88005a682416cc3e2f426bba9a` | draft/submit 분리·서명 후 가격 선택; DB position/담보 준비 호출 제외 |
| futures/authorization/AuthorizedV2Store.java | `9c789d802b113203c816de407d1a69fb0858e3fd2c06539db0253dcc18bf0c83` | request fingerprint·same payload replay·원자 head 패턴; FuturesLedger/잔고 예약 제외 |
| futures/authorization/AuthorizedV2RevisionStore.java | `eaad5bbc88d9f419d751f957ea8aeddfd03455570f02352f3272e4ac19a8bb68` | revision append+head CAS·SIGNED없음 검사 개념; reviseAuthorizedV2 금융예약 제외 |
| futures/authorization/AuthorizedV2Repricing.java | `eb1abd6cceefb31f928b43f5293cf9336781d35fcee976a292f480a54decfafe` | PRICE_CHANGED만/같은 provider-generation/범위 재검증; B chain/tx/ledger 결합 제외 |
| futures/chain/FuturesTransactionSender.java | `7e0840547e1f844859c3014769afa45e9a99debb7d626d4474aad2c0d054259e` | estimate→시장재검사→활성revision→raw선commit 개념; process-local만으로 분산보장 및 unknown 자동 broadcast 제외 |
| futures/chain/FuturesTransactionStore.java | `01c5d151fe7ff65e043ff31eff56e9f9434ef0554163da411caba0dbb38a407f` | durable sender guard·operation/head lock·saveSigned-before-send; B 테이블/버전/ABI 제외 |

`FuturesChainIdentity`를 C=version2로 위장하지 않는다. 기존 C `CAbiCodec`, `CAbiEventCodec`, `CEip712Verifier`, 수학·지원 범위별 proof verifier가 정확한 새 ABI의 재사용 지점이다.

## 5. 웹 연결의 최소 재사용

승인된 후속 C웹은 기본 exact `approve(Vault, 필요한 금액)`→별도 deposit→stable Intent 승인 OPEN/TOP_UP/CLOSE→owner free withdraw다. 지갑 잔고/free/locked/unpaid/house를 분리하고 미지급을 인출 가능액에 더하지 않는다. 서명/전송 pending과 canonical 확정을 구분한다. 직접 cancel의 제출을 자산 해제나 확정 취소로 표시하지 않는다.

B `tools/websocket-test-client/src/` 참조 SHA:

- `dapp-intent.js`: `162b8dcf6d3bbb756c6a34b6522fc1dd743dd1c3d99c6327bc30aaf09865017c`
- `dapp-wallet.js`: `bdaa0650b4c02703dbb75b8ec700a3602175ad08b205c5d9af57ef2da5a5c24d`
- `dapp-panel.js`: `956045eef0ec72810b9da0924393f5afb981d793271af024b7e480e4872d4a3d`
- `api.js`: `e9151092033600862433d9534dde21590d933565b7f23a5de8170e833ee3272c`

재사용은 EIP1193/epoch ticket/accountsChanged/chainChanged/WebLocks·요청전 localStorage·응답유실 후 같은키 조회·BigInt 단위 패턴만이다. B accountRef prefix·domain/version·hashText(UUID) 규칙·DB담보 문구/API·legacy AI 진단 버튼은 C에 그대로 쓰지 않는다. source 새C namespace는 chain/genesis/controller/JWT user에 묶는다. 실제 지갑 sign 후 owner/chain/epoch 재확인 전에는 submit하지 않는다. C지원이 없는 기존 AI를 C포지션에 연결하지 않는다.

## 6. 순차 완료 조건과 실패 시 다음 행동

| 단위 | 선행/실제 검증 | 완료로 표시하지 않는 범위 |
|---|---|---|
| 순수 typed-data formatter | C 계약/type/기존 crypto에만 의존; JSON/hash/정수 회귀+독립검토 | JWT/nonce/RPC/MetaMask/거래 실행 |
| C작업 metadata와 준비 | 실제 read/wallet Gate 통과 후; fresh owner/nonce/state+실제PG 원자성/중복/rollback | Vault 자산 예약·체결 보장 |
| C Sender/revision/복구 | 위 metadata 검증 후; 신규 PG/Anvil OPEN/TOP_UP/CLOSE/가격변화/두 Worker/raw·동일hash·별도JVM | 제품시장·사용자서명 없는 fixture를 MetaMask로 승격 |
| C 웹/실제 사용자 인수 | 실제 API·거래/복구 Gate 후; Mock와Chrome실환경 분리·사용자직접서명 | 사용자 개인키 하네스 제공·시험EOA를 실제사용자로 표시 |
| scanner/전체 감사/C7 | 원본 전체범위·직접거래/reorg/서버중단/Oracle중단을 각각 검증 | 단일Receipt·현재getter·순수overlay로 전체 MATCH |

사용자 범위 안의 일반적인 구현/회귀/독립검토는 자율 진행한다. 실제 서명·경제/수탁 정책 변경·추가비용·기존자료 파괴만 별도 판단 대상이다. 검토 cap/실행 안전 기준은 기존 단위에서 승계하며 이름을 바꿔 초기화하지 않는다. 현재 Gate의 RAM 부족은 fixture 미시작 상태로 보존하고, 순수 formatter처럼 진짜 독립 단위만 총괄 판단으로 진행할 수 있다.

최초 조사 산출물은 계획 문서1개였다. 이후 총괄의 별도 작업 지시에 따라 순수 단위 파일3개만 추가했다. 아래 상태는 **소스 작성 완료, 컴파일/실행/독립 검토 NOT_RUN** 시점의 체크포인트다.

- `backend/src/main/java/com/pricetrack/exchange/custody/authorization/CUserIntentTypedData.java`: C22필드/domain JSON formatter, SHA-256 `60579daf308edbeafc9dc6ef13cf290cd5a9141ce186648aa4ff79ea23be0354`.
- `backend/src/test/java/com/pricetrack/exchange/custody/authorization/CUserIntentTypedDataTest.java`: 순수 JSON/digest/정밀도/불변성8개, SHA-256 `b8d565d2a813c1d2c9844877c02cedb39cd180756774d2a061947b9630959490`.
- `deployment/c-intent-typed-data-check.py`: whitelist4소스·캐시 third-party JAR만 컴파일, 신규 evidence와 고정8개 JUnit 실행, SHA-256 `d77b1a6b749084900e6c3435e3a783b7b463053bb49211d609c4ff8dd04319fa`.

제안 실행 명령은 C worktree에서 `python deployment/c-intent-typed-data-check.py`다. 총괄과 무거운 Gate의 순차 실행을 조율한 뒤 수행한다. 실제 read/wallet Gate의 준비 소스/runner/core/API/schema는 변경하지 않았다. DB·RPC·서비스 기동/사용자 서명/유료 호출/Git staging·commit·merge·push는 수행하지 않았다. 이 단위는 일반 거래 metadata/Sender/복구/웹 연결을 구현하지 않는다.

### 최초 순수 단위 실행의 하네스 오류

총괄의 단독 실행 `deployment/runtime/c-intent-typed-data/20261023T093415Z-b225fafe`는 compileExit2, test NOT_RUN이었다. 최초 오류는 `error: invalid flag: -J-Xmx192m`이며, javac JVM 옵션을 `@argfile` 안에 둔 신규 실행기 결함이다. 제품 formatter/test 컴파일 또는 실행 성공으로 취급하지 않는다. 기존 실패 증거는 보존했다.

신규 실행기만 `-J-Xmx192m`을 직접 argv로 옮기고 나머지 안전한 argfile 인수는 유지했다. 수정 후 runner SHA-256은 `bf6162db9cf4af0e260fdde8cd0b57f8756e36ae9c332434191428a42042a60b`다. production/test 및 기존 실행기는 수정하지 않았다. 재컴파일·테스트 결과는 총괄의 별도 단독 실행에서 기록한다.
