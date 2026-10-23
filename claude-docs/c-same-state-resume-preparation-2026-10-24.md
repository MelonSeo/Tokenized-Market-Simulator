# C 동일 논리 상태 복원 준비

현재 상태: **2026-10-24 02:01 실제 동일 논리 상태 서비스 READY / 사용자 OPEN·CLOSE 대기**.

## 최신 실제 READY

사용자 RAM 확보 후2558MiB/disk25.98GiB/기존실행0과 최종 supervisor SHA 일치를 확인하고 같은 동결코드를 실행했다. 새 자손 `cdemo-135ae9a88bb244c6`, recovery source `16c3d610c84c1c1687f43c4f65fef2acc6c9d354c18873a6ec4c539b4c0a0acd`가 실제 READY다. 원래 논리 source `2ed9c86b83163a5741b0718c99f90e3442618ca49d22052e557d42795cb82fb5`·DB계정·지갑 연결·genesis·계약을 유지했다. PG coldcopy/systemid·실제DB owner/role/user/wallet/marker·미거래metadata0, 시각보정 전후4codeHash·세Receipt·자산·nonce·잠금0·pending0 검증을 통과했다. 비공개 exec-only 실제벽시계 보정은 `1792774796`에 정확히1회이고 기존2초 검사를 통과했다. 사용자·자동 거래/새배포/새잔고분배는0이다.

실제 loopback의 정적웹byte·JWT로그인·본인조회·기존지갑연결·account·제품Synthetic·기존3건ReceiptAPI CONFIRMED까지통과했다. 현재wallet1000000/free0/locked0/house10000000cMKRW, EOAnonce3/Cnonce0이다. 사용자 MetaMask OPEN/CLOSE는아직NOT_RUN이며 READY를 사용자거래PASS로확대하지않는다.

웹 `http://127.0.0.1:32171/c-demo.html`, RPC `http://127.0.0.1:32172`, chain31337. 기존로컬시험자격증명과동일지갑연결을유지한다. Oracle started02:00:07/App02:00:14KST, 각1시간 bounded이므로 **10/24 03:00KST 이전** 사용종료를안내한다. 원본/실패환경은정지그대로보존, 현재새PG/Anvil/App/Oracle4개만running이다. 감독exec session3052는종료까지유지하며 신규 STOP은 이자손의relayControl/STOP뿐이다. 현재run의 영구claim·원본 ancestorclaim은보존한다. 다음재개는현재자손의최신DB·종료snapshot에서이어야하며원본조상을다시사용하지않는다. 증거는 `deployment/runtime/c-direct-resume/cdemo-135ae9a88bb244c6/`의 lineage/pg-copy/before-clock-sync/clock-sync-claim/after-clock-sync/ready/relay파일이다.

## 최신 재개 위치 — 검토 대기가 아닌 자원 대기

수정 후 재검토1/2 `review_c_report_candidate`는 필수지적 없음, 직접11 PASS 및 최종SHA `592d23aa360eadd320fcd069e7d60ff9db2680240f12638b7d7785a6a26a93e6` 일치를 확인했다. 준비자와 검토자 작업은 완료됐으며 동일 소스의 추가 검토나 재컴파일을 기다리지 않는다.

그 후 실행은 `C_RESUME_RESOURCES`로 자원 preflight에서 중단돼 새claim·새fixture·새시각보정·새거래0이다. 읽기 재확인 RAM1831MiB(최소1920보다89MiB부족),disk25.99GiB/실행컨테이너0이었다. 상위working set은vmmem1289/DockerDesktop1001/ChatGPT926/Edge532/Chrome410MiB였으며 개별값을 단순 합해 회수가능량으로 단정하지 않는다. 앱종료·Docker/WSL재시작·RAM기준완화는 수행하지 않았다. 미사용Edge창/탭 등 사용자 자원정리 후 같은 동결 소스의 preflight부터 이어가며 새 코드나 반복 검토를 만들지 않는다. 현재 웹은 열리지 않았고 실제MetaMask OPEN/CLOSE는NOT_RUN이다.

원본 `cdemo-e0e5a24638b0416f`는 재시작하거나 수정하지 않는다. 기존 PostgreSQL volume을 읽기 전용으로 새 volume에 cold copy하고, 고정 SHA의 원본 Anvil 스냅샷을 새 체인 프로세스에 읽기 전용으로 제공한다. 새로운 계약 배포, 자금 배분, 사용자 키 생성이나 거래 자동 전송은 없다.

## 확인된 장애와 승인

첫 network-none 복원 시험은 genesis·계약 codeHash·자산·nonce·과거 세 Receipt를 유지했지만 벽시계 약 1463초 차이로 BLOCKED였다. Anvil 해당 빌드의 `load_state`는 저장 head 시각을 기준으로 시간 offset을 재설정한다. 자연 채굴만 기다려도 정지 기간의 차이가 유지된다. 이 실패를 PASS로 변경하지 않는다.

사용자는 이후 **새 복제 자손의 첫 신규 블록 시각을 현재 벽시계에 1회 동기화하고 동일 DB·웹을 연결**하도록 승인했다. 새 supervisor는 공개 relay를 열기 전, 포지션·잠금·미확정 거래가 없고 nonce·자산·기존 Receipt가 맞는 경우에만 `evm_setNextBlockTimestamp`를 현재 초로 한 번 호출한다. 실패·불확실 응답을 재시도하지 않는다. 자연 채굴한 새 블록과 시각·기존 증거를 재확인한다. 이를 실제 60초 유예 시험의 성공 근거로 사용하지 않는다.

## 신규 단위와 경계

- 실행 파일: `deployment/c-direct-same-state-resume.py`
- 순수 회귀: `deployment/test_c_direct_same_state_resume.py`
- 원본 모든 역할의 정상 정지와 기존 자손 유무를 확인한다.
- 별도 `c-direct-resume-claims/<parent>-<snapshotSHA>.claim`을 독점 생성하며 실패해도 남긴다. 원본 STOP/claim/manifest는 변경하지 않는다.
- PG 복제 helper는 network-none, 원본 volume RO/새 volume RW, 필요한 CHOWN/FOWNER/DAC_OVERRIDE만 사용한다. 정렬된 tar의 원본 전후·복사본 해시 및 `pg_controldata` system identifier가 같아야 한다.
- DB role·사용자·지갑 연결·marker·runId는 기존 값을 유지한다. draft/consent/direct execution/claim은 모두 0이어야 한다.
- App은 기존 keyless 설정을 그대로 복사하고 새 control/evidence에 연결한다. Oracle 키는 Oracle에만 RO로 제공한다. App에 사용자·Oracle·relayer 키를 제공하지 않는다.
- supervisor 라벨과 lineage는 새로운 자손 identity다. 다음 재개는 최신 자손 스냅샷을 사용해야 하며 기존 조상을 다시 복원하여 nonce를 되감지 않는다.
- 기존 loopback 32171/32172, 로그인 계정과 1시간 제한을 유지한다. JWT·wallet·account·Synthetic·세 거래 Receipt의 실제 HTTP 읽기 검증 이후에만 READY를 게시한다.

## 아직 검증하지 않은 사항

### 경로 및 미공개 실패 자손의 한정 재개 보완

후속 supervisor는 기존 claim을 그대로 남기고 SHA 고정한 실패 자손 `cdemo-5e4bd3b1140f4e63`를 부모로 삼는다. 원본 모든 역할과 실패 자손 역할 정지, 실패 당시 clock/거래/키생성0 및 App·Oracle 미생성을 확인하고 정상 종료한 자손 PG volume만 RO로 새 복제한다. 원래 DB marker/runId/source는 유지한다. 원본 snapshot 상속은 미공개·미거래 단계의 고정 예외이며 다음 자동 자손 생성을 허용하지 않는다. Windows/Docker Desktop 파일 경로 alias 정규화 이후에도 exact파일·RO 조건은 유지한다. 보정 직전 자연 채굴된 옛 시각 블록으로 대기를 조기 종료하지 않도록 기존10회 읽기 대기에서 새 블록번호와 요청한 실제시각 이상의 timestamp를 함께 확인한다. 시간 보정 요청은 여전히1회뿐이고2초 실제시각 검사는 유지한다.

순수 회귀11 PASS, 최종 supervisor SHA `592d23aa360eadd320fcd069e7d60ff9db2680240f12638b7d7785a6a26a93e6`다. 같은 서비스 재개 단위의 수정 후 재검토1/2를 진행하며, 실제 후속 실행은 아직 NOT_RUN이다.

### 첫 서비스 복원 실행의 실제 실패

`cdemo-5e4bd3b1140f4e63`는 `NEW_PG_ANVIL / C_RESUME_ANVIL_MOUNTS`에서 중단됐다. PG 원본전후·복사본 sorted-tar SHA 일치 및 systemIdentifier `7699878592856473638` 확인은 PASS다. Docker inspect는 정확한 원본 snapshot을RO로 연결했지만 Source를 `/run/desktop/mnt/host/c/...`로 반환했고, Windows `Path.resolve()` 직접 비교가 이를 동일 파일로 인식하지 못했다. 기존 원본App의 directory bind는 Windows경로 표기여서 모든 mount가 항상 같은 형식이라고 가정할 수 없다. 동등 경로 정규화는 기존 `deployment/ops.py:mount_path` 방식으로 한정하며 정확한 원본 파일/RO 조건을 유지한다.

시각보정0/Oracle·App·relay기동0/새서명·자동거래0/원본hash불변이다. PG·copy는exit0이고 Anvil은docker stop10초 후exit137/OOMfalse여서 정상종료나dump확보로 기록하지 않는다. 최초 실패·원본claim·새volume/network는 보존한다. 한정 재개는 이 실패 자손을 새 부모로 삼아 별도 exclusive claim을 만들고, 정상종료한 복제 PG를 다시 RO cold copy하는 방식만 검토한다. Anvil snapshot은 아직 미공개·미거래·시각미변경 단계에서 실패했으므로 원본 고정snapshot을 상속하는 예외를 명시한다. 실패 자손의 미공개 빈 블록까지 보존했다고 주장하지 않는다. 이 한정 추가 실행 뒤 또 실패하면 자동으로 새 자손을 반복 생성하지 않는다.

첫 실행 전 `python -m unittest discover -s deployment -p test_c_direct_same_state_resume.py`는8 PASS였고 최초 독립검토 필수지적은 없었다. PG 이미지가 선언하는 기본volume을 명시적1MiB tmpfs로 가려 불필요한 익명volume을 만들지 않도록 했으며 누락 negative test를 포함했다. 해당 실행 supervisor SHA는 `78125440fdd2bbf210b7573b77f452cc878981c3cb4494bfdc14a875aa4329d0`다. 이후 첫 실환경 실패는 위에 구분했다. MetaMask OPEN/CLOSE, 전체 감사 및 서버 독립 인출 인수로 확대하지 않는다. C 계약과 경제 정책·시장 신선도·Report TTL은 변경하지 않는다.
