# NXT 애프터마켓 실제 응답 관측 — 2026-10-16

## 결론

사용자가 보류된 관측 재개를 승인하여 기존 미사용 run `kiwoom-diagnostic-20261016033307-3aee8495`에서 **읽기 전용 외부 관측1회**를 완료했다. 애프터마켓 시간대에 LOGIN/REG 승인, 동일 generation1에서 `item=005930_NX` 체결733건, `290="2"`를 확인했다.

**9081은733건 모두 존재하는 JSON 문자열이며 현재 숫자1~3자리 허용 형식에는 맞지 않는다.** 따라서 기존 exchangeCode=null은 이번에는 누락/null이 아니라 허용 형식 필터링 결과로 구분된다. 미허용 원문은 저장·출력하지 않았으므로 실제 문자열과 거래소 의미는 미확정이다. 0s는0건이었다. 제품 서버·Quote·BUY/SELL은 실행하지 않았으며 NXT 제품 지원 또는 실환경 거래 E2E PASS가 아니다.

## 사전 확인

- 호스트2026-10-16 19:33:33+09:00 및 별도UTC10:33:33 일치. 실행은19:34~19:37로 애프터마켓 시간대였다. 공급자 OPEN 상태를 이 시간만으로 추정하지 않았다.
- 보존된 worktree의 기존 진단 도구를 실행했다. 원본 루트로 merge했다고 기존 manifest를 바꾸거나 새 run으로 재생성하지 않았다.
- 19:34:30 preflight: prepared/sourceHashes/credentialReference/한도/REG payload 일치, private ACL 정상, real 인증정보 두 항목 존재, 기존 capture 없음(run/prepared만 존재).
- 로컬에서 matching live diagnostic/acceptance 프로세스는 발견되지 않았다. 이 점검이 타기기·동일 계정의 모든 WS 세션 부재를 보증하지는 않는다. 서비스/IP는 기존 사용자 확인을 재요구하지 않았고 이번 인증·구독 성공을 별도 실측 사실로 구분한다.
- 사전 요약 출력에서 Python tzdata 부재 오류1회가 있었다. 외부 연결 전 로컬 시각 출력 오류이며, 호스트 timezone 출력으로 재확인했다. 설치·시스템 시각 변경·외부 재시도 없음.

REG는 기존 승인 payload 그대로다.

```json
{"trnm":"REG","grp_no":"1","refresh":"1","data":[{"item":["005930_NX"],"type":["0B"]},{"item":[],"type":["0s"]}]}
```

## 실제 결과와 판정

| 항목 | 결과 | 정제 증거 |
|---|---|---|
| OAuth/LOGIN/REG | PASS | 각1회, 승인 |
| 실제 item | PASS | 733/733이 문자열005930_NX |
| 290 원래 코드값 | PASS(형식 관측) | 733/733이 문자열2; 세션 의미는 UNVERIFIED |
| 9081 존재/타입/형식 구분 | PASS | present=true / STRING / formatAllowed=false 각각733건 |
| 9081 허용된 값 | INCONCLUSIVE | exchangeCode=null733건. 미허용 원문 저장·출력 없음 |
| 0s/215 | INCONCLUSIVE | 0건, 상태값 없음. 미지원/초기스냅샷부재로 단정하지 않음 |
| generation | PASS | 모든 체결generation1 |
| 신선도 | PASS(진단 시각 기준) | 733/733이-2~5초, age1.552591~3.094123초 |
| 독립적인9081 거래소 코드/세션 의미 | INCONCLUSIVE | 관측되지 않은 값·의미를 추측하지 않음 |
| 제품 OPEN/LIVE/모의 거래 | NOT_RUN | Spring Boot·Quote·DB/Anvil 거래 미실행 |

메인마켓 관측742건과 이번 애프터 시간대733건에서 모두290=2였다. 따라서 **290=2만으로 KRX/NXT 또는 NXT 메인/애프터를 구분할 수 없다.** _NX 고정 구독과 동일 응답 item은 이번 NXT 라우팅의 관측 근거지만9081의 독립 교차검증을 완료했다는 뜻은 아니다. 프리마켓은 이번에도 NOT_RUN이다.

## 시각·수량·종료

- supervisor 시작: UTC10:34:53.482025 / KST19:34:53.482025.
- 첫 체결: wireTime193452, 가격215500, 거래량50, 누적13114015. 수신UTC10:34:54.554650, 체결UTC10:34:52, age2.554650초.
- 마지막 체결: wireTime193752, 가격216000, 거래량10, 누적13207047. 수신UTC10:37:54.353181, 체결UTC10:37:52, age2.353181초.
- supervisor 결과 파일 시각: UTC10:37:54.384336. 시작과 파일 시각 차이 **180.902311초**. 이는 파일 시각 기반이며 별도 monotonic 실측 저장값이 아니다. 정상 관찰 설정180초, supervisor 전체 제한210초를 적용했다.
- 정제737행: CONNECTED/LOGIN/REG_SENT/REG 각1 + NXT_TRADE_WIRE733. captureTruncated=false.
- 외부 OAuth1, 시세/차트 REST0, WS1, LOGIN/REG 각1. 수신657프레임, 송신19프레임. 나머지17은 기존 도구의 PING 응답이며 추가 구독이 아니다.
- captureResult=COMPLETE, workerStopped=true, workerExitCode=0, CLI exit0. 재시도·재연결·토큰재발급0. 최초 승인1회를 사용했으며 이 run은 재사용하지 않는다.

## 증거·보호 범위

기존 worktree의 해당 run에 원래 run/prepared와 신규 supervisor-start/attempts/events/report/supervisor-report를 보존한다. 민감정보·원본응답은 문서/Git에 포함하지 않는다. 과거742건 capture를 덮어쓰지 않았다. 기존 KRX DB/Anvil/baseline/인증정보·제품 코드·웹·이미지 변경 없음. 실제 증권사 주문·모의 주문·유료AI·배포·commit/merge/push 없음.

종료 후 sourceHashes 재대조true, 정제events/report/supervisor/attempts의금지필드명검사0건, run내credential/secret복사파일0건, workerStopped=true를확인했다. 패턴검사는비밀부재의완전한보증은아니다. 사후집계명령의PowerShell인용오류1회는외부호출없이로컬에서바로잡았다. 원본Git변경은이번보고서와implementation-log2개뿐이며diff check통과다.

## NXT 고정 모드의 최소 후속 변경안 — 아직 구현하지 않음

1. KRX/NXT를 시작 시 고정 선택하고 KRX 기본값을 유지한다. NXT는 구독/파서에서005930_NX를 엄격히 대조하며005930/통합item을 섞거나 자동fallback하지 않는다. 290=2 단독으로 거래소를 판정하지 않는다.
2. 선택 모드별 현재 generation·체결·누적량·시장 상태를 혼용하지 않는다. 기존5초/미래2초/TTL30/generation 재검증/명시적CLOSED 우선은 유지한다. 0s가 어느 거래소 상태인지 불확실한 상황을 무시하거나 OPEN으로 보정하지 않는다. 이번0s0건은 상태 정책 완화 근거가 아니다.
3. REST 초기값·차트의 거래소 선택도 같은 모드로 맞추는 최소 연결부가 필요하다. 이번 REST0이므로 NXT 분봉/일봉 요청·응답 계약은 실측 미검증이다. 기존 KRX 봉을 NXT 봉처럼 표시하지 않으며, 공식 지원과 Mock 및 필요한 별도 실전 승인을 기준으로 확인한다.
4. 웹에 선택 거래소를 구분해서 안내한다. 기존 EIP-712/Vault/DB정산/복구는 그대로 재사용하는 방향이며 이 관측만으로 새 계약·지갑·DB migration을 추가할 이유는 확인되지 않았다. 현재 제품 KRX 파서가_NX를 그대로 처리하는 것은 아니므로 구현 승인과 회귀·별도NXT제품E2E가 필요하다.

이번 작업은 승인된 관측과 정제 사실 보고다. 제품 변경이 없으므로 새로운 구현 검토·테스트를 실행하지 않았고 기존68개 오프라인 통과를 이번 재실행 결과로 기록하지 않는다.
