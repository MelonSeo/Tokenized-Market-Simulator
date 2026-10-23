# UI / AI / B-v2 / 감사 worktree 안전 통합 계획

> 2026-10-22 23:17 KST 전후의 읽기 전용 Git·파일 대조. 실제 merge/copy/commit, 환경 기동 또는 테스트는 수행하지 않았다. 이 문서만 새 audit worktree에 작성한다. 총괄의 감사 구현/검증은 병행 중이므로 최종 소스 identity는 통합 착수 전에 다시 고정해야 한다.

## 1. 현재 identity와 증거의 의미

다음 다섯 worktree의 Git HEAD는 모두 `f9353c33244565b30fb130dfb6159eab441a3523`다. 따라서 HEAD 일치는 미커밋 소스 일치를 뜻하지 않는다.

| 위치 (`deployment/runtime/` 기준) | Git 상태 / 현재 역할 |
|---|---|
| 원본 repository | `melon-init`, dirty 10경로. 보호 문서와 AI 평가 test2개·결과 문서·DApp 설계가 혼재. 구현 작업의 자동 staging 대상이 아님 |
| `futures-ui-p0-worktree` | `feat/futures-ui-p0`, tracked9 + untracked3. P0 표시 보완·모바일 감사·알림 배치와 검증 기록 |
| `ai-presentation-worktree` | `feat/ai-presentation-integration`, 미커밋 UI·AI 웹 Guard/harness 변경. P0 표시도 포함하지만 공용 decimal helper로 추출된 형태 |
| `dapp-user-authorization-worktree` | `feat/dapp-user-authorization`, 미커밋 B-v1/v2 계약·Java·schema·지갑 웹·RPC 관측 및 실제 사용자 인수 기록 |
| `dapp-audit-development-worktree` | detached HEAD, B-v2 Java/계약의 명시 복사본 위에 신규 승인 감사 구현. 웹은 원본 상태이며 DApp 웹/P0/AI UI가 들어 있지 않음 |

문서상의 기존 실행 identity:

- UI P0 최종 기록: 웹138 PASS, build/Chrome fixture PASS. 실제 PG/Anvil 결과가 아니다.
- AI 웹 최종 Gate: sourceHash `88df6616d69c46577f37fae07f1cfb4e3fee9b72889b6cbb12c7bddb9a1258f7`, productHash `d757f64f74fd089f4facc83318a25293d1bb8b4d7ef16efe462603f2e773b415`; Gradle native listen으로 **BLOCKED**. 이전 fake 통합 성공과 최종 Gate를 구분한다.
- DApp 실제 사용자 인수 기록: productHash `fd29bef4fbc32cb2ab9f2ad4785556727361de2373609840e2e310ed0d9ec7f1`, webHash `a9f621ee38d622184768f61b9581766df0a90b55aaceff42318239f8073fb7b1`. 최신 사용자 OPEN revision1 성공 후 RPC 재발로 TOP_UP/CLOSE 보류 기록이 있다. 과거 다른 OPEN 성공과도 구분한다.
- 위 값은 **기존 실행 기록**이며 이번 조사에서 새로 산출한 전체 source/productHash가 아니다. 현재 audit 최종 테스트와 새 PG Gate 결과는 총괄의 새 실행 보고서가 정본이다.

## 2. 실제 충돌·중복 경로

아래는 파일 SHA-256 앞12자리 비교다. 동일 표시는 조사 시점의 byte 일치를 의미하며 동작의 새 인수 성공이 아니다.

| 파일 (`tools/websocket-test-client/` 기준) | 실제 관계 | 통합 방법 |
|---|---|---|
| `index.html` | UI=AI `cc8c5b58edec`, DApp `00654a03a308`, 원본=audit `aa73e3b95b14` | P0 toast의 문서 흐름 배치와 DApp route/section을 hunk별 결합. 파일 전체 덮어쓰기 금지 |
| `src/main.js` | 원본=UI=audit `3f7020208a41`, AI `5bee0b1fc506`, DApp `1a5931633864` | AI 응답 순서/취소 보완과 DApp panel의 생성·route·사용자 변경 무효화 모두 유지 |
| `src/api.js` | 원본=UI=AI=audit `d1715e1c9539`, DApp `e91510920336` | B-v2 API 메서드만 추가; 기존 인증/AI 호출 계약 유지 |
| `src/style.css` | 원본=audit `77af104cf3db`; UI/AI/DApp 모두 다름 | P0 모바일 타일/알림, AI 근거 요약, DApp 승인 UI selector를 각각 보존하고 cascade 확인 |
| `src/futures-panel.js` | UI=AI `90b25822102d`, 원본=DApp=audit `82a1c9c69601` | 이미 같은 P0 구현을 한 번만 채택. 종료 상태 우선·draft 보존을 재검증 |
| `src/futures-audit-panel.js` | UI=AI `7a43b57ea01d`, 원본=DApp=audit `fd31035501ee` | 기존 감사 표시 개선 한 번만 채택. 신규 authorized 감사 DTO와 같은 계약이라고 가정하지 않음 |
| `src/futures-display.js` | UI `c611bf49f4d7`, AI `30431d1ecd9c`; 다른 위치 없음 | AI는 `decimal-display.js`에서 동일 정밀도 표시 함수를 import/re-export. AI helper와 P0 최종 테스트를 함께 유지해 중복 구현 방지 |
| `scripts/browser-smoke.mjs` | 원본=audit, UI/AI/DApp 각각 다름 | 각 fixture/assertion을 기능별로 결합. 하나의 파일을 선택해 나머지 회귀를 소거하지 않음 |

추가로 `src/ui.js`, `ai-futures-evidence.js`, `decimal-display.js`, AI fixture/test는 AI 소유 변경이다. `dapp-intent.js`, `dapp-panel.js`, `dapp-wallet.js`, DApp fixture/test는 DApp 소유 변경이다. 이름이 겹치지 않는 신규 파일도 import·테스트·API 의존 단위로 검토해야 한다.

백엔드 충돌:

- `audit/FuturesAuditSnapshot.java`: 원본/UI/AI `6336e0991aa0`, DApp `454e5ae45b98`, audit `60593fbeb1e8`. audit에는 B-v2 승인 증거·revision 및 제한적 읽기 보완이 추가되어 있으므로 DApp 사본으로 역덮어쓰면 감사가 유실된다.
- `audit/FuturesAuditService.java`: DApp=audit `052e595c1d79`, 원본/UI/AI `3dc160a94c3e`. 기존 legacy의 INCONCLUSIVE guard를 유지한다.
- audit의 `FuturesAuditAccounting/Store`, `FuturesRpcGateway`, `FuturesSchema` 및 신규 authorized 감사 파일은 B-v2 기반 위의 추가 변경이다. audit를 통합 후보 backend로 삼되 DApp 원본과 최종 delta를 재확인한다.
- AI worktree의 Java 변경은 평가/브라우저 harness 위주이며 `GuardedAiEvaluationRunner`, `PhaseAFixture`, `FuturesBrowserPostgresAnvilE2ETest`가 포함된다. 신규 감사 harness와 일괄 대체하지 않는다.
- `deployment/phase-a.py`, AI index copy/broker/continuation/Guard, browser scripts의 수정은 실행 identity·비용 경계를 변경할 수 있다. 제품 웹 UI와 별도 검토 단위로 취급한다.

## 3. 보호 파일 — 구현 diff와 분리

원본에서 실제 확인한 미커밋 경로를 전부 보존한다. 기존 기록의 “보호5개”라는 숫자를 현재 dirty 수로 오인하지 않는다.

| 구분 | 실제 경로 |
|---|---|
| 기존 사용자·계획 보호 문서 5경로로 계속 보존할 대상 | `claude-docs/README.md`, `claude-docs/implementation-log.md`, `claude-docs/kiwoom-nxt-aftermarket-observation-2026-10-16.md`, `docs/FUTURES_48H_IMPLEMENTATION_PLAN.md`, `docs/PROJECT_COMPLETION_MASTER_PLAN_2026-10-18.md` |
| 별도 AI 평가 미커밋 test2개 — 무관한 수정으로 보호 | `backend/src/test/java/com/pricetrack/exchange/ai/evaluation/EvaluationPositiveSmokeTest.java`, `EvaluationToolFixture.java` |
| 원본의 추가 미커밋 결과·설계 문서 3개 — 전체 덮어쓰기 금지 | `claude-docs/ai-q2-16-presentation-preflight-2026-10-20.md`, `ai-q2-16-real-quality-evaluation-2026-10-20.md`, `dapp-user-authorization-design-2026-10-21.md` |

보호5경로의 출처 범주는 기존 사용자 보존 지침을 따른다. 이번 조사만으로 각 문장의 최초 작성자를 판정한 것은 아니다. 실제 현재 dirty10경로와 과거5경로의 차이를 명시한 것이며 이번에 원본 hash 불변 검사를 새로 실행했다고 주장하지 않는다.

각 worktree의 `claude-docs/implementation-log.md`와 웹 README는 다른 내용을 누적했다. 전체 파일 복사 대신 향후 별도 통합 기록을 추가하고 원본 사용자 내용을 남긴다. 모든 ignored runtime, DB/Anvil, ledger/STOP/RESERVED/claim/approval, manifest, private evidence는 소스 통합 대상이 아니다. 이번에는 해당 AI 원본 기록을 조회할 필요도 없어 조회하지 않았다.

## 4. 권장 통합 단위와 순서 — 아직 실행하지 않음

1. 모든 대상의 HEAD·tracked/untracked 경로 및 필요한 파일 hash를 다시 고정한다. audit 개발 완료 여부와 사용 중인 fixture identity를 확인한다. 원본 사용자 dirty를 stash/reset/clean하지 않는다.
2. 별도 승인된 통합 worktree에서 **B-v2 계약/Java/schema/복구 + authorized 감사 backend**를 하나의 의존 순서로 준비한다. 관련 schema·decoder·승인 metadata 테스트를 빠뜨리지 않는다.
3. **P0 최종 표시 + AI 공용 decimal/근거 요약**을 중복 없이 합친다. 이미 동일한 futures-panel/audit-panel을 다시 수정할 이유가 없으면 그대로 재사용한다.
4. **DApp 지갑 UI/API**를 추가한다. main/index/style/browser-smoke는 양쪽 기능을 보존하는 수동 hunk 통합 대상으로 둔다.
5. **AI 안전 전송/harness**는 최종 Gate BLOCKED와 live approval 부재를 유지한 채 별도 묶음으로 검토한다. UI 통합을 이유로 모델 경로를 live 활성화하지 않는다. 기존 금액·호출 제한과 lineage를 초기화하지 않는다.
6. 문서 결과는 기능별 기존 보고서 링크로 연결한다. 현재 단일 개발 진행 문서와 마스터 계획은 각 증거 기준으로 갱신하되 과거 PASS/실패를 덮어쓰지 않는다.
7. 아래 새 회귀와 독립 검토를 통과한 뒤에만 원본 통합 방식을 사용자 권한 범위에 맞춰 확정한다. **현재 문서는 merge/commit/push 승인이 아니다.**

파일 이름이 같다는 이유로 `ours/theirs` 전체 선택, 다른 worktree의 전역 복사, private runtime 전환, 기존 fixture에 새 소스 적용은 하지 않는다.

## 5. 통합 후보에서 필요한 새 회귀

- 웹 전체 단위/build: 현물·Google·Faucet·전량 매도·조건부 주문·legacy 선물·B-v2 opt-in·AI·관리/감사 route.
- UI P0: 정확/근사/극소/음수 decimal, 종료 후 GRACE 이력, 1366×768/390px, 로그인 알림 비중첩, 원본 상세.
- 지갑: EIP-191/712 일치, 계정/chain/JWT 변경 무효화, 늦은 서명, 거절/만료/중복·응답 유실, 숫자 BigInt와 승인 범위. Mock과 실제 MetaMask는 별도 판정.
- AI: 진단 대상 ID, Skill, 수치 출처·스냅샷/fresh 범위, PARTIAL/오류·응답 순서·XSS, guarded fake transport의 scope·STOP·재시작·중복 방어. 새 유료 호출 없이 가능한 범위만 우선 실행.
- 백엔드 전체 및 Foundry: legacy/B-v1/B-v2 금융·서명·revision0/1·SIGNED raw 불변·정산·복구·자동청산. 직접 JUnit과 정상 Gradle 실행을 구분.
- 감사: 새 trusted identity registry/baseline, canonical 3이벤트와 최종 revision, 역방향 coverage, journal·house·사용자 장부, unknown identity/취소 coverage/미확정의 INCONCLUSIVE, ADMIN403 및 bounded deadline/cancel/commit 불확실성.
- 새 격리 PG/Anvil/제품 Synthetic/Chrome: 소스가 합쳐진 새 identity로 실제 거래·60초 유예·복구·감사·개인 WS를 검증한다. 이전 브랜치의 PASS를 통합 소스의 PASS로 재사용하지 않는다.

실제 MetaMask TOP_UP/CLOSE와 Windows RPC/소켓 장애, AI 최종 live Gate, KRX/NXT/Phase D는 각자의 미완료/환경 경계를 유지한다. UI 통합 또는 B-v2 감사 구현만으로 완료 처리하지 않는다. 이번 조사 결과는 **안전 통합 계획 준비**, 실제 통합 및 위 회귀는 **NOT_RUN**이다.

## 6. 2026-10-23 후속 읽기 전용 대조

원본 HEAD `f9353c33244565b30fb130dfb6159eab441a3523`, dirty10 경로를 다시 확인했다. 위 표의 웹8경로에 대해 UI/AI/DApp 파일 SHA 앞12자리를 새로 읽어 대조했고, 이전 관계와 같았다. DApp style `4827593cc790`, UI `13a42c30c5f8`, AI `70039abee1ae`; browser-smoke는 각각 `8ac0ba73e15b`/`1a35f8763d39`/`97dc87ad1140`으로 서로 다르다. 공용 main/index/style/browser-smoke를 어느 한쪽 파일로 대체하면 다른 기능을 잃을 위험이 유지된다.

감사 worktree는 취소 observer 후보 product `dac4ee…`로 추가 개발 중이며, 0029의 실환경 Gate와 이후 후보의 검증 결과를 구분한다. 이번에는 충돌 후보를 다시 확인했을 뿐 소스 복사·통합·staging·commit·merge를 실행하지 않았다. 기존 AI 유료 경로 및 보호 상태를 변경하지 않는다.
