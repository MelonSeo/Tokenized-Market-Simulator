# Linux CI 2차: Temurin 배포 식별자 보완

## 변경과 검증 경계

기준 commit `cbbfc34a3557f0974cd699a01161c2c3e1ba0f50`에서 workflow의 `java-version`만 `21.0.10+7` → `21.0.10+7.0.LTS`로 변경한다. setup-java 자체 SHA, 제품 소스, 계약, 인증, Fixture 경제 조건은 변경하지 않는다.

최초 실행 40163271068의 setup-java 제공 목록에서 후자 식별자를 확인했다. 공식 Adoptium GitHub 릴리스 태그는 `jdk-21.0.10+7`이다. Actions 배포 목록의 `.0.LTS`는 버전 선택 표현이며 의도한 Java feature/update/build는 21.0.10/build 7이다. Lock의 `jdk: 21.0.10+7` 및 Temurin 이미지 `21.0.10_7`을 유지한다. 실제 Runner vendor/runtime/build 및 javac 사용 성공은 신규 CI 결과와 구분해 확인한다.

workflow는 `product-read-linux.py:sources()`의 fingerprint 대상이므로 기존 준비 산출물이나 RUN_CLAIM을 재사용하지 않는다. push로 생성되는 새 checkout에서 prepare가 수행돼야 한다. Windows 체크아웃 fingerprint와 Linux 실제 fingerprint의 동일성을 가정하지 않는다.

- Python 순수 회귀 69 PASS.
- actionlint 1.7.7 PASS (shellcheck/pyflakes 미사용).
- Adoptium releases API에서 `jdk-21.0.10+7` 태그 확인. 별도 Adoptium assets/version 조회는 HTTP 404였으며 이를 성공 근거로 사용하지 않았다.
- 추가 독립 검토 및 최종 staged 보안 검사 후 개인 remote의 동일 CI 브랜치에만 commit/push한다.
- Lock/이미지 릴리스와 workflow fingerprint 포함 검사 PASS. 초기 보조 검사에서 Windows 경로 구분자를 정규화하지 않은 비교가 실패했으며, 플랫폼 구분자 정규화 후 PASS했다. 제품 코드는 변경하지 않았다.
- 독립 검토 `review_ci_jdk_resolution`: **발견된 필수 수정 없음**. 검토자는 diff/Lock/fingerprint/문서를 읽고 `git diff --check`를 직접 실행했다. 실제 Ubuntu vendor/runtime/javac 및 통합 결과는 미검증이다.
- 신규 CI는 1회만 관측한다. 새 실패의 자동 수정, 재push, rerun은 하지 않는다.

## 결과 기록

아래 실행 결과는 실제 신규 CI 관측 이후에만 추가한다. Java 설치 성공을 전체 통합 PASS로 취급하지 않는다.
