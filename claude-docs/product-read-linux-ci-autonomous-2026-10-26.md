# Linux 통합 조회 CI 자율 오류 해결

기준 `32502353c69fc4c7fa68c030554451fedc51545b`. 사용자는 일반 CI 오류의 최소 수정/검토/보안 검사/개인 브랜치 push 및 신규 CI 최대 5회를 승인했다. 금융·권한·Fixture 경제 조건은 변경 대상이 아니며 전체 PASS 즉시 종료한다. 실사용자 자산 접근과 유료 API 호출은 하지 않는다.

## 사건 1 — 시험 컴파일 의존성 scope

- 시점/목적: 2026-10-26, Linux 통합 조회 Gate를 실제 실행 단계까지 연결.
- 근거: run 40215304287의 `compileTestJava` 19 errors. 첫 오류 `org.h2.jdbcx` 부재, 이어 JUnit Platform launcher/engine 부재. main/helper 컴파일 및 웹 테스트는 이미 성공했다.
- 조건/원인: 시험 소스가 H2 `JdbcDataSource`와 JUnit Launcher를 직접 참조하지만 Gradle은 둘을 `testRuntimeOnly`로 선언했다. 런타임 전용 의존성은 시험 컴파일 classpath에 없다. `--tests`는 실행 필터이지 전체 시험 소스의 컴파일 필터가 아니다.
- 판단: runtime-only를 그대로 두고 시험을 제외하는 방법은 검증을 약화하므로 채택하지 않는다. 직접 참조 의존성 두 개만 `testImplementation`으로 옮긴다. 버전은 기존 Spring dependency management를 유지하며 제품 runtime/금융 코드는 변경하지 않는다.
- 검증: 의존성 scope 순수 회귀를 추가하고 독립 검토 및 staged 보안 검사 후 새 CI에서 실제 compileTestJava/선택 시험 결과를 확인한다. 로컬 순수 시험을 Gradle 컴파일 성공으로 대신하지 않는다.
- 결정 귀속: 자율 실행 한도/안전 경계는 사용자 승인, 두 의존성의 최소 scope 수정은 실제 로그와 코드에 근거한 Codex 판단이다.
- 면접 쟁점: compile/runtime classpath의 차이, test filtering과 compilation 구분, CI의 clean checkout이 로컬 직접 JUnit 실행과 다른 결함을 드러내는 이유.
- Python70 PASS. 독립 검토 `review_ci_test_dependencies`: 발견된 필수 수정 없음. 검토자 직접 관련Python19/diff검사 PASS. 실제 Gradle 검증은 신규 CI에 남긴다.

## 실행 장부

이번 승인에서 신규 CI 사용: 0/5. 실행 URL과 최초 실패 경계는 실제 관측 후 누적한다. 취업용 사건 후보 인덱스는 ignored runtime의 로컬 문서로만 유지한다.
