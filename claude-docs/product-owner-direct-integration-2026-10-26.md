# 통합 TMS 웹 C owner-direct 연결

## 목표·현재 작업 경계

기준melon-init83e13ec, 개발branch feat/c-owner-direct-integration/별도worktree. 기존owner직접호출·JWT·EIP191·Oracle·Receipt검증을재사용한다. 금융/계약/인증완화없음. 사용자보존자산·체크포인트접근없음. 신규격리LinuxCI최대3회, melon-init최종통합은별도검토대상이다. 실제MetaMask서명직전에는중단한다.

## 조사 결과와 재사용

- 기존통합웹 c-direct-panel/intent/wallet/adapter에approve/deposit/OPEN/CLOSE/withdraw,정확한calldata검증,chainId/genesis/linked-owner,WebLocks,만료·응답유실·기존거래조회가이미있다. 별도C전용시연화면을복사하지않는다.
- CCustodyController의readOnly는direct.enabled기본OFF에대응한다. CDirectController/RuntimeConfig/OracleConfig/ReceiptController는기존명시적opt-in일때만활성화한다. 제품기본값은바꾸지않는다.
- CDirectService는draft120초→실행시점가격선택→Oracle서명검증→불변실행저장만담당하며사용자tx를서명/전송하지않는다. Report30초·시장5초/동일provider-generation-exactprice검사는유지하고가격변동시무단재가격하지않는다.
- Controller owner직접호출과ERC20/Vault/내부rules가자산정본이다. PostgreSQL은계정·소유증명·draft/실행증거,기존모의장부는별개다. CDirectReceiptReader는calldata/owner/canonicalReceipt/이벤트를확인하지만전체감사·다중providerfinality를보장하지않는다.
- 과거MetaMask5거래성공은기존개발기록의별도시연환경결과다. 최신통합웹/새배포의성공으로재사용하지않는다.

## 최소 연결·검증 계획

제품의기존거래경로는이미연결돼있어우선새쓰기실행프로필/시험하네스로실제연결을인수한다. read workflow/runner/정책은변경하지않는다. 새개발branch workflow에서기존readGate를먼저수행하고, 별도PG/Anvil/Oracle/제품Spring/시험지갑actor를순차실행한다.

- ProductDirectFixture: 기존CAnvilContractGate의소스attestation/배포/역할/코드hash/실제전송검증재사용. 매번새시험EOA,고정공급20m/house100k/각사용자1m으로자금분리. 사용자자산snapshot은사용하지않는다.
- ProductDirectDriver: 공통실제AuthController/JWT를사용하는제품schemabootstrap과동일사용자지갑증명. 사용자키는시험walletactor에만,Oracle키는별도Oracle에만둔다. Spring에는키를주지않는다.
- ProductDirectGate: 실제ExchangeApplication에시험표면제한filter만덧댄다. 기존JWT/owner/권한검사는그대로통과해야한다. 기존시연용대체로그인Controller를사용하지않는다.
- Chrome은기존통합웹의실제버튼으로5거래를실행하고, 시험EIP1193provider는CDP→격리actor로요청을전달한다. 자동시험EOA이며확장MetaMask/실제사용자승인이아니다. 개인키를페이지/Node/Spring에넣지않는다.
- 직접Report준비전에3초Synthetic의다음tick을제한된읽기로관측한다. 시간/가격을조작하거나전송이후재가격하지않는다. 실제사람서명시간의사용성인수는별도다.
- 각거래의소유권/자산보존,OPEN잠금,CLOSE손익/house,최종free/locked/claims0,실제Receipt/이벤트,외국계정차단및기존DB장부0유지를검사한다. 불확실전송은추가거래없이실패·증거보존한다.

## 기술 판단·한계와 경험 기록

사용자승인/자산정본은온체인,DB는불변준비증거다. HTTP준비는동기응답이지만온체인확정은별도단계이며타임아웃을성공/실패로추정하지않는다. 기존브라우저pending/WebLocks/epoch/동일calldata조회는재사용하며광범위한새복구시스템은만들지않는다. actor의자동서명은시험만을위한것이고실제운영권한이아니다.

예상위험: 3초tick사이에Report선택/Oracle후정확가격검사가끝나지않으면PRICE_CHANGED로안전거부될수있다. 실제발생여부는실행로그로판정한다. 기존5초/30초/120초를늘리거나assertion을지워시험을맞추지않는다. 거래이력은기존resolve가CONFIRMED로검증한결과만이번세션최대20개로표시하도록최소보완했다. 미확정/중복tx는추가하지않고JWT·지갑·체인epoch변경때지운다. textContent만사용하며전체Scanner/DB영속거래이력은아니다.

독립검토/보안검사후개발branch만push한다. 실제실행별결과·결함·수정근거는아래누적한다. 로컬취업용회고인덱스는GitHub업로드제외다.

## 사전 검증과 실제 개발 사건

- 신규Java시험helper4개 javac21.0.10 컴파일PASS(기존검증된제품classes/cache를읽기재사용,실제Linux/EVM/PG로대체하지않음). 최초VaultAction의이벤트모델import오류를실제compiler가탐지해정확한ABI모델enum사용으로최소수정했다.
- 신규Python6/기존readPython72/JS문법/actionlintPASS. 최초Windows기본cp949로한글JS시험소스를읽는회귀에서decode실패→해당read_text에UTF8명시. Linux/Gate정책변경없음.
- 새worktree에서node_modules미설치로웹전체시험1파일import실패→기존lock의npm ci --ignore-scripts후웹224 PASS/build107modules. 기존test를skip하거나lock을변경하지않았다.
- npm audit는기존lock의Vite7.3.6와source-map-js에high2건을보고했다. 이번branch가도입한의존성변경은0이다. Vite개발서버/사용자입력source-map서비스를열지않고신뢰된소스build와Spring정적자원제공만사용한다. 해당advisory의제품전체노출영향을모두해소했다고주장하지않으며의존성보안갱신은별도후속으로남긴다.
- 사용자결정: 개발범위·최대CI3회·기존경제정책·실제사용자승인전중단. Codex판단: 기존직접경로재사용,제품인증을대체하지않는시험프로필,독립actor/CDP시험어댑터,세션CONFIRMED목록최소보완. 기존시연서버재사용/키를Spring에주는우회는채택하지않았다.
- 독립검토`review_product_direct_integration`: 최초지연Receipt의최종UI재노출경계를발견했다. core의마지막epoch검사후Promise/lock이UI로돌아오는사이계정전환이끼는조건이다. c-resolve의await이후에도epoch/active를재확인하고chain/JWT전환/logout3경계회귀를추가했다. 재검토 **발견된필수수정없음**. 직접Python6+72/관련웹36/JS문법/diffPASS. 구현자전체웹225/신규helper4javacPASS와구분한다. 실제새PG/Anvil/Oracle/Spring/Chrome5거래/MetaMask는아직미검증이다.
- 첫실패의단계·고정오류코드만남기도록새browser stage와wallet-failure safeCode/causeType을추가했다. 원문서명/JWT/raw/개인키를기록하지않으며검토범위에포함했다.
