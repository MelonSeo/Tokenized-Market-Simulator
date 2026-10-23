# C 실제 사용자 거래 이후 checkpoint 재개 준비

상태: **순수 회귀 7 PASS / 독립 최초 검토 필수 지적 없음 / 실제 읽기 전용 서비스 복원 Gate PASS**. 기존 성공 `592d23aa…` 재개 코드는 변경하지 않았다. 다음의 최신 결과를 아래 준비 당시 기록보다 우선한다.

## 최신 실제 실행 결과 — 8d 자손이 다음 재개의 기준

`cdemo-8d5fd2ff832345bb` / source identity `4d7cee7e06a761f14d960e2ecb2ca729705b1437cce4d0487bf75c88ed12292d`에서 122.782초에 `PASS_READ_ONLY_CHECKPOINT_SERVICE_RECOVERY_NOT_NEW_USER_TRADE_OR_FULL_AUDIT`를 확보했다. 부모135의 PG/snapshot은 RO로 복제했으며 새 계약·자금 배분·서명·거래·금융 metadata 쓰기는 0이다. 승인된 공개 전 시각 동기화는 정확히1회다.

실제 JDBC·기존 JWT·지갑 연결·원래 genesis·4 runtime codeHash·기존8 Receipt·최종 자산·nonce8/2·CLOSED v2·pending0을 전후 검증했다. PG drafts3/claims3/executions2/consents0/users2/walletLinks1과 각 hash가 조회 전후 일치했다. 새 사용자 거래 인수나 전체 감사 PASS가 아니다.

소유 App/Oracle/Anvil/PG/copy는 모두 exit0, OOMfalse, normalStoptrue다. 원본 불변과 새 volumes/network 보존을 확인했다. **최신 snapshot 14,800,699 bytes / SHA `71192dd8e8cdbb58e78d6463efea3cb0d793964c9c1ccb71fae6b7ea123f6b19` / head7438**. 다음 실행은 이 자손을 기준으로 해야 하며 부모135로 되돌리지 않는다. 사용자용 1시간 창은 열지 않았고 후속 live launcher는 별도 준비 단위다.

증거: `deployment/runtime/c-checkpoint-resume/cdemo-8d5fd2ff832345bb/`의 `result.json`, `checkpoint-lineage.json`, `http-read-gate.json`, `before-clock.json`, `after-clock.json`, `metadata-before.json`, `metadata-after.json`, `anvil-state.private.json`. 원본 snapshot과 실패 실행은 보존했다.

## 목적과 보존 기준

03:00:58생성된135 result는safeError=null/originalUnchanged=true/clock1/자동거래0이다. PG·Anvil·Oracle·copy는exit0,App은143이고모두harness normalStop=true/OOMfalse,새PG/statevolume과network보존true다. 원래READY status를수동거래PASS로덮어쓰지않았다.

root읽기검증에서종료snapshot의SHA/bytes/head7333/tx14가일치했다. 실제저장ERC20/Vault/controller/rules storage를기존소스배치와대조해wallet1000003066148919985598442/Vaulttoken=house9999996933851080014401558/free0/locked0/unpaid0/EOA8/Cnonce2/positionCLOSED(3)/version2/terminalLock1/totalSupply20mE18를확인했고4runtimecodeHash도일치했다. 다섯저장Receipt의status1·기존canonicalblockHash·block번호·owner·nonce3~7·로그수1/2/4/4/2도일치했다. 이는저장snapshot검증이며새RPCcanonical검증의대체가아니다.

추가관측: snapshot blocks에는0번header후보가2개(원본과load시새processgenesis)가있다. 원본파일을수정하거나하나를삭제하지않는다. 실제복원Gate의0번블록이원본genesis952c…인지전후엄격확인해야한다. 이관측만으로체인훼손또는성공을단정하지않는다.

정상 종료한 최신 자손 `cdemo-135ae9a88bb244c6`를 기준으로 발표 당일 동일 계정·DB·체인 상태 재개 가능성을 확인한다. 원래 data run/source/config는 e0e5…지만 복원 부모와 스냅샷은 반드시 최신 135…다. 예전 미거래 snapshot이나 실패 child의 상태를 최신 결과로 대신하지 않는다.

최신 스냅샷은 14,591,003 bytes / SHA `eb9f99b9e305507d820f35b0e2c839b35877de91247c86ca98702b5e84fb50e6`, 거래 14개 / head 7333이다. 사용자 EOA nonce 8, C nonce 2, 기존 포지션 CLOSED/version 2, free/locked/unpaid 0을 유지한다. 원본 wallet `1000003066148919985598442`와 house `9999996933851080014401558`은 E18 정수이며 손익을 반영한 값이다. 초기 100만/house 1000만으로 초기화하지 않는다.

## 이번 최소 개발 단위

- `deployment/c_checkpoint_profile.py`: 최신 result/lineage/manual5거래와 snapshot의 고정 identity·expected state
- `deployment/c-checkpoint-resume-gate.py`: 기존 성공 helper의 PG cold-copy/claim/경로/시각 race guard 및 기존 배포·웹 산출물을 재사용한 복원 Gate
- `deployment/test_c_checkpoint_resume_gate.py`: 순수 policy 회귀

현재 Gate는 **복원 → 실제 조회 검증 → 즉시 정상 종료**만 수행한다. App/Oracle/relay의 1시간 지원 설정은 유지하지만 사용자가 없는 1시간 시연 창을 열지 않는다. 실제 사용자 MetaMask OPEN/CLOSE를 반복하거나 기존 거래를 재전송하지 않는다.

## 검증 경계

1. 최신 부모 모든 역할 정상 종료, 단독 자손 claim과 이전 lineage claim 확인
2. 최신 PG volume RO → 신규 volume RW cold copy, system identifier·바이트 해시 일치
3. 최신 snapshot RO 복원, genesis/4 codeHash/최종 자산·nonce/CLOSEDv2/미확정 0 확인
4. root 3개와 최신 실제 사용자 5개, 총 8개 Receipt/Event의 과거 canonical hash 및 현재 읽기 API 연결 확인
5. 공개 전 현재 벽시계 1회 동기화와 자연 블록 확인, 자산·상태 재검증. 실제 60초 유예 시험으로 재사용하지 않음
6. 실제 JWT 로그인·기존 wallet link·현재 account/position/Synthetic 및 OPEN/CLOSE intent metadata를 연결한 Receipt 조회
7. PG 금융 metadata와 사용자/지갑 행의 조회 전후 hash 일치. 원래 미완료 draft도 보존. 제품 Synthetic 가격 상태의 정상 commit과 구분
8. relay 즉시 종료, 신규 역할 정상 종료, 최신 자손 PG와 새 snapshot 보존

`positionLocks`의 owner/amount 0/terminal true와 실제 Vault token·recognized assets·house의 일치, unassigned 0도 확인한다. 이전 실제 서비스 관측에서 pending 0이었다는 사실과 **복원 후 RPC에서 pending 0**이라는 검증은 구분한다. Anvil dump는 mempool을 저장하지 않으므로 스냅샷 파일만으로 종료 순간 미확정 거래가 없었다고 확정하지 않는다. 14개 저장 거래의 Receipt 존재는 저장된 거래의 확정을 확인하는 범위다.

상태 archive reader는 기존 helper의 **16MiB** 제한을 그대로 사용한다(24MiB 지원이 아님). 새 dump가 제한을 초과하면 PASS가 아니라 BLOCKED로 기록하고 소유 volume과 실패 증거를 보존한다.

실패 claim은 삭제하지 않는다. Gate 성공 이후의 다음 재개는 이 Gate가 보존한 **최신 자손** snapshot을 기준으로 해야 한다. 이번 고정 135 profile을 반복 실행하여 ancestor nonce를 되감는 것은 허용하지 않는다. 해당 다음 시연 launcher 연결은 실제 Gate 결과를 확인한 뒤 최소 후속 단위로 진행한다.

당시 순수 회귀는 `python -B -m unittest discover -s deployment -p test_c_checkpoint_resume_gate.py -v`로 7 PASS/0 FAIL(2026-10-24)을 확인했다. 이 순수 시험 자체는 Docker/RPC/PG/App 실환경 PASS·전체 감사·새 사용자 거래 인수의 근거가 아니다. 이후 실제 8d 복원 Gate의 PASS는 아래 별도 실행 결과다.

## 최신 8d checkpoint의 명시적 1시간 시연 명령 준비

**후보 검토 상태: 최초 독립 지적 1건 수정 후 재검토1/2에서 발견된 필수 수정 없음.** 검토자 직접 순수10 PASS/기본 CLI 계획 확인/Docker·claim·유료 호출0 및 최종4파일 SHA 일치를 확인했다. 실제 `--live-window`는 아직 실행하지 않았다. 아래 명령은 최종 소스·오프라인 경계가 검증된 재개 도구이며 발표 당일 새 서비스 기동 성공이나 사용자 거래 성공을 선행 선언하지 않는다. 실제 8d 읽기 복원 PASS는 별도 결과다.

후속 실제 조회 복원 Gate `cdemo-8d5fd2ff832345bb`는 122.782초에 PASS했다. safeError null, 원본 불변, 거래/금융 metadata 신규 쓰기 0, 실제 genesis·8 Receipt API·최종 자산/CLOSEDv2·PG metadata 전후 동일을 확인하고 모든 역할을 exit 0/OOM false로 정지했다. 현재 최신 부모는 **8d**이며 snapshot은 **14,800,699 bytes / SHA `71192dd8e8cdbb58e78d6463efea3cb0d793964c9c1ccb71fae6b7ea123f6b19` / head 7438**이다. 과거 135 상태로 다시 재개하지 않는다.

새 `c-live-latest-checkpoint.json`, `c_live_resume_support.py`, `c-checkpoint-live-launch.py`는 검증된 Gate 원문(`30801a29…`)과 기존 helper를 변경하지 않고 사용한다. **새 live 실행은 NOT_RUN**이며 다음 명령의 준비 단계다. C worktree 루트에서:

```powershell
python -B deployment/c-checkpoint-live-launch.py
python -B deployment/c-checkpoint-live-launch.py --live-window
```

첫 명령은 source/compiled/profile 정합성을 읽기 검증하는 offline plan이며 Docker·claim 생성 0이다. 두 번째 명시 옵션만 실행 시점 자원·포트·최신 lineage를 확인해 소유 격리 자손을 준비한다. 사전검증과 실제 JWT/8 Receipt 조회가 통과해야 `browser-ready.json` 경로를 출력한다. 로그인·RPC·남은 수명과 명시 STOP 경로는 그 파일의 실제 값으로 확인한다. 이 문서 작성 단계에서는 두 번째 명령을 실행하지 않는다.

사용자 창 이후의 정상 거래로 nonce·자산·DB 행이 변경되는 것은 허용되므로 초기 상태 불변 assertion을 적용하지 않는다. 종료 시 실제 상태와 metadata를 읽어 보존하고, pending이 있으면 BLOCKED로 기록하며 자동 재전송하지 않는다. 서명·계약 배포·신규 자금 배분은 agent가 수행하지 않는다. 만료 전에 포지션 종료·free 인출을 완료해야 하며 자동 종료 거래는 없다.

새 live 보존 reader만 32MiB의 단일 `anvil.json` tar 제한을 사용한다. 경로 탈출·symlink·복수 항목·초과 크기를 차단하며 기존 Gate의 16MiB reader는 변경하지 않는다. 모든 성공·실패 claim은 남긴다. 새 live 시도 이후 재개는 그 최신 자손 DB/snapshot을 기준으로 해야 하며 8d claim을 지우거나 ancestor를 재실행하지 않는다.

새 live 단위의 최종 순수/AST/CLI 회귀는 **10 PASS/0 FAIL**이며 실제 기본 CLI도 `OFFLINE_PLAN_ONLY_NOT_STARTED`와 Docker/claim/유료 호출 0을 반환했다. **`--live-window`는 실행하지 않았다.** claim 이후 구성 파일 복사/lineage 생성이 실패하면 서비스 생성 전 BLOCKED 결과를 가능한 범위에서 남기며 claim은 소비된 채 보존한다. 디스크 쓰기 자체가 불가능하면 claim만 남을 수 있으며 자동 초기화하지 않는다. 32MiB 초과나 종료 확인 실패 역시 소유 volume·실패 증거를 남기고 BLOCKED다.

최초 독립 검토에서 동적 import되는 browser-run/history helper 두 개가 기존 149 source 및 새 frozen 목록에 없다는 필수 지적을 확인했다. 실제 파일을 변경한 것이 아니라 이 두 helper의 검증 경계를 보완했다. 기존 원본 execution identity의 browser-run SHA와 현재 고정 history SHA를 추가하여 총 다섯 frozen helper를 시작·claim 직전·종료에 재검증하고 실행 source identity inputs에도 포함한다. 두 helper 변조 시 기본 plan과 claim 이전 검증이 실패하고 Docker/claim 생성이 없다는 회귀를 추가했다. 수정 이후 같은 검토자의 재검토 결과는 별도로 기록한다.

1시간은 서비스 실행 상한이다. 사전 HTTP 검증에 소요된 시간 때문에 READY 이후 사용 가능한 시간은 더 짧다. `expiresAtEpoch`는 가장 먼저 시작한 Oracle의 1시간 만료보다 5초 앞선 보수적 종료 시점으로 계산한다. 새 서명·종료·인출을 만료 직전에 시작하지 않는다. 직접 서명은 사용자만 수행한다.

최종 재검토 기록: `review_c_checkpoint_live` 수정 후1/2, 발견된 필수 수정 없음. 검토자 직접10 PASS 및 기본 CLI Docker/claim0 확인, live/Gate 재실행0. 최종 launcher SHA `10495587adfe28ede951580b0c488ac14f502d4fb12c72fe09be4706923806b2`, support `6971fd7fe27e0449fa894eecfc55f96341a9f41ab708a41b7a608d2562a6b8c4`, profile `c239e8ece7415cd4005a911c2b36346c6274b92519b6c5538a69161fb37ef0b6`, test `90958c5e6aa738905172df9f1f4868a1c15a5a1a8dee544d01aac68f4ab421c4`.
