# Linux CI 한글 폰트 및 실화면 검증

## 범위와 근거

기준 `8d9ca6c74df0e89750a40b83531b2bb2262a637c`, 이전통합PASS run40218771542. 실제PNG에서한글glyph가사각형으로표시됐다. 기존제품CSS는system-ui/Segoe UI/Noto Sans KR/sans-serif fallback을이미지정한다. 이전run에는fontconfig설치목록이없어당시패키지부재를직접측정했다고주장하지않는다. 이번Runner에서설치전후실제목록과Chrome사용font를확인한다.

## 최소 변경

- workflow: Ubuntu에서`fc-list :lang=ko`설치전목록, `fonts-noto-cjk`설치, `fc-cache -f`, 실제패키지버전/설치후목록/`fc-match`를기록한다. JDK/Node/도구이미지/금융검증은유지한다.
- browser하네스: Linux에서만DOM/CSS CDP관측을켜고한글대상element가실제로Noto Sans CJK glyph를사용하는지확인한다. 기존85초budget/별도profile/서버·계정검증/종료그대로유지한다. Windows에는Linuxfont조건을강제하지않는다.
- 로그인(빈자격증명), 시장, 자산, 포지션화면PNG와font metadata를남긴다. screenshot3개만명시적Artifact허용목록에추가한다. 제품CSS/UI를수정하지않는다.
- 설치/CDP성공만으로시각PASS를선언하지않고다운로드PNG를직접확인한다. 실제사용자MetaMask및제품금융요청은없으며CI새Fixture의시험거래만존재한다.

## 검증·실행

이번승인신규CI최대2회. 독립검토/순수회귀/YAML/보안검사후개인CI브랜치에만push한다. 실행후정확한결과·한계·이미지경로를누적하며PASS후다음제품개발을시작하지않는다.

- 순수Python72/JS문법/actionlint/diff검사PASS. 최초독립검토는포지션컨테이너의fontprobe가Chrome의2단계텍스트수집범위를넘는문제를지적했다. 캡처대상은유지하고fontSelector만`#c-position dt`및자산의직접텍스트부모`#c-account > div`로좁혔다. 해당분리를회귀로검사하고재검토후실행한다. 제품DOM/CSS나조회assertion은바꾸지않았다.
- 독립재검토`review_ci_korean_fonts`: 발견된필수수정없음. 직접관련Python21/JS문법PASS. 실제font목록/CDP사용글꼴/PNG가독성은신규CI후확인대상이다.

## 개발 경험의 연결

Windows가용RAM부족에따른로컬Gate차단 → 별도LinuxRunner및공개Fixture recipe → Temurin배포식별자해결 → 시험compile 의존성scope해결 → 실제조회통합PASS → 이번font가독성검증으로연결된다. [Linux준비기록](product-read-linux-ci-2026-10-25.md), [JDK기록](product-read-linux-ci-jdk-resolution-2026-10-26.md), [자율CI기록](product-read-linux-ci-autonomous-2026-10-26.md)을근거로한다. 단계별환경·원인·실제시도는각기록에한정하며처음부터모든해결책을알았던것처럼서술하지않는다. 과거문서의상세분석과취업용개인기여확정은별도이며로컬회고인덱스는이번push에서제외한다.
