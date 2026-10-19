# 선물 감사 fixture의 이동 cut 처리 보완

## 재현과 원인

최종 통합 source의 신규 `phase-a-20261019153910-daf57837/futures-audit-7952317a`에서 실제 PostgreSQL/Anvil 테스트1 FAIL이 발생했다. 실패 위치는 `FuturesAuditPostgresAnvilE2ETest.java:85`의 기존 reserve MISMATCH assertion이다. 선물 감사 자체는 이미 MATCH와 의도적 house1wei 차이의 MISMATCH를 반환했다.

새 실패 fixture의 DB만 소유권 확인 후 잠시 시작하여 `BEGIN READ ONLY`로 저장 이력을 조회하고 원래 정지 상태로 돌렸다. reserve 이력은 `MATCH/EXACT_QUANTITY_MATCH` → `INCONCLUSIVE/CUT_CHANGED`, 선물 감사 이력은 `INCONCLUSIVE/UNSETTLED_OR_REVIEW` → `MATCH/EXACT_LEDGER_CHAIN_MATCH` → `MISMATCH/LEDGER_CHAIN_OR_COVERAGE_CONFLICT`였다. 원장·house·baseline·체인은 보정하지 않았다. 의도적1wei 차이와 실패 증거는 그대로 남겼다.

Anvil은 이 fixture에서2초 자동 채굴한다. `ReserveService.stable`은 DB fingerprint·현재 head·명시 block의 canonical hash를 비교하고 바뀌면 INCONCLUSIVE를 반환한다. 테스트가 후속 reserve 한 번의 호출에서 stable cut을 가정한 것이 문제다. Java 소켓 장애와 다른 **실제 테스트 assertion 실패**이며 제품의 안전한 판정을 완화할 이유가 아니다.

## 최소 수정

테스트 클래스에만 `stableReserve(Supplier<Result>)`를 추가했다. 정확히 CUT_CHANGED일 때만 최대4회 새 읽기 관측을 요청한다. 다른 INCONCLUSIVE/UNAVAILABLE/예외는 재시도하지 않는다. 네 번 모두 이동하면 마지막 INCONCLUSIVE를 그대로 반환하여 기존 MATCH/MISMATCH assertion이 실패한다. 각 실제 관측은 기존 append-only 이력에 남고 baseline 재생성·거래 재실행·원장 수정은 하지 않는다.

정상 reserve와 의도적 차이 reserve assertion을 모두 같은 helper로 감싸고 reason을 실패 메시지에 포함했다. 성공 증거에는 실제 reserve 두 결과도 포함한다. `FuturesAuditCutRetryTest`4개로 즉시반환·CUT_CHANGED 뒤 실제MISMATCH·다른사유 비재시도·4회종료를 검증한다. 이 테스트는 제품 대사 정책이 아니라 fixture 관측 루프의 무료 단위 검증이다.

제품 Java/Solidity·경제모델·60초 유예·30초 quote TTL·정산·baseline 규칙은 변경하지 않았다. 신규 실제 E2E 및 독립 검토 결과는 실행 완료 후 아래에 기록한다.

## 검증·검토

- 신규 오프라인 `knowledge-audit-guards-java21`: **14 PASS/0SKIP/0FAIL**. corpus10과 fixture guard4이며 실제 체인 검증 수와 합산하지 않는다.
- 새 `futures-audit-36aaddcd`: 실제 PostgreSQL/Anvil **1 PASS/0SKIP/0FAIL**. 정상 선물 감사·기존 reserve MATCH, pending INCONCLUSIVE, 의도적 house1wei 차이의 선물 감사·reserve MISMATCH, canonical 거래3/event6 및 complete coverage, 불변 baseline과 append-only 경계 assertion을 유지했다.
- 실행은 해당 자식 프로세스의 Java21.0.10/JAVA_HOME으로 한정했다. 전역 Java/Docker/WSL 설정은 변경하지 않았다. Windows Java23 소켓 장애와 이 CUT_CHANGED 테스트 결함을 같은 원인으로 단정하지 않는다.
- `review_audit_fixture_cut` 최초 독립 검토: 발견된 필수 수정 없음. 실제3경로 diff·관련서비스·회계를 읽었으며 직접 테스트/DB/private/외부 접근은 하지 않았다. 검토 후에는 실행 결과만 누적했다.
- 기존 실패7952317a의 원장/house 의도적 차이·조회 이력·로그를 보존했다. 새 PASS로 이전1FAIL을 덮어쓰거나 기존 fixture를 원복/재사용하지 않았다. 최신 전체 영향 회귀와 로컬 커밋은 최종 통합 보고서에 이어 기록한다.
