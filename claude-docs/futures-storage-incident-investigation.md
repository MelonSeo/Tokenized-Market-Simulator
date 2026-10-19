# Futures 저장공간 장애 조사 및 승인 전 해결안

> 조사: 2026-10-19. 읽기 전용 조사와 이 문서/구현 로그 기록만 수행.
> 브랜치 `feat/futures-engine`, HEAD `452c5db8e0ef9e92a81d79323af2e8b938b7c1dc`.

## 1. 결론 — 확인된 실패 경계와 아직 확정하지 못한 원인

**신규 PostgreSQL이 Windows NTFS bind mount에 데이터 파일을 쓰던 중 ENOSPC를 받았다는 점은 확정했다. Docker 데이터 VHD의 용량 한도 초과는 현재 측정으로 뒷받침되지 않는다.**

- 실패 당시 로그: `2026-10-19 03:38:37.578 UTC`, initdb post-bootstrap의 `base/4/2675` 쓰기에서 `No space left on device`.
- `PGDATA=/var/lib/postgresql/data`. 해당 경로는 Docker volume/컨테이너 overlay가 아니라 저장소의 Windows `deployment/runtime/.../postgres` 디렉터리로 bind mount된다.
- 현재 해당 경로의 WSL filesystem은 Windows C 드라이브를 공유하는 `9p`다. 약13.8GiB가 남아 있다.
- Docker backing filesystem `/mnt/docker-desktop-disk`는 약1006.9GiB 중44.5GiB 사용, 약911.1GiB 가용이다. inode도 약1% 사용이다.
- 따라서 **"Docker 가상 디스크가 꽉 찼으므로 캐시를 지우면 해결된다"는 결론은 내릴 수 없다.** 이전 보고의 캐시 정리 제안은 확정 해결책이 아닌 후보로 정정한다.
- 실패 당시 호스트 여유량을 연속 수집한 자료가 없어 순간적인 호스트 공간 소진 여부는 미확정이다. Windows 할당량/NTFS 제약 또는 공유 계층의 쓰기 오류 가능성도 아직 구분하지 못했다. 어느 하나를 원인으로 단정하지 않는다.

이번 조사에서 새 컨테이너/volume/fixture 생성, 서비스 시작·종료, 쓰기 probe, PostgreSQL 초기화, E2E/빌드, prune, 데이터 이동, Docker/WSL 재시작은 하지 않았다. 외부 API도 호출하지 않았다.

## 2. 기존 실패 증거와 실행 단계

| 항목 | 확인 값 |
|---|---|
| session | `phase-a-20261019004856-29dfdfbb` |
| 실패 child | `futures-restart-0b5f8978` |
| PG container | `phase-a-20261019004856-29dfdfbb-futures-restart-0b5f8978-postgres` |
| 시작/종료 | `03:38:15.876Z` → `03:38:38.346Z` |
| PG 종료 | exit1, OOMKilled=false |
| harness 중단 | 90초 SQL/RPC readiness 제한, JVM 실행 전 |
| 계약/거래 실행 | 이 child에서는 미실행 |
| 두 JVM 검증 | BLOCKED, 테스트0건 |

근거는 기존 `deployment/runtime/<session>/fixtures/<child>/run.json`, 같은 session의 `logs/<child>-container-log-*.private.log` 및 `deployment/phase-a.py`의 PG mount/readiness/소유권 기반 종료 코드다. private 원문을 문서/Git으로 복사하지 않았다.

실패 PG 로그에는 initdb 자체의 `removing contents of data directory`도 기록돼 있다. 현재 해당 PG 폴더는 파일0개다. 이는 initdb의 실패 후 자체 정리와 일치하며 에이전트가 기존 PG 디렉터리를 삭제한 결과가 아니다. 컨테이너/로그/실패 session은 보존돼 있다. 이 빈 폴더를 초기화해 재사용하지 않는다.

## 3. 저장 경계: 서로 다른 공간을 혼동하지 않는다

```text
PostgreSQL PGDATA/base/4/2675
  → /var/lib/postgresql/data (bind mount)
  → repository/deployment/runtime/<session>/fixtures/<child>/postgres
  → Windows C: NTFS
  → Docker Desktop WSL에서는 /mnt/host/c/... (9p)

Docker images / build cache / named volumes / writable overlay
  → Docker Desktop data filesystem (/dev/sdf, ext4)
  → docker_data.vhdx
  → Windows C:의 동적 가상 디스크 파일
```

| 측정 대상 | 실제 관측 | 해석 |
|---|---|---|
| Windows C | 총511,439,310,848bytes, 가용 약14.85GB(약13.8GiB), NTFS | 호스트 여유는 작지만 조사 시점에0은 아님 |
| .NET DriveInfo | AvailableFreeSpace와TotalFreeSpace가 같은 약14.86GB | 현재 Windows 호출자에게 보이는 두 값이 같음. Docker 프로세스/실패 시점 할당량까지 증명하지 않음 |
| 실패 PG 경로 df | C: /9p, 가용 약14,503,336KiB | 해당 파일의 저장 경계는 host bind임 |
| Docker data ext4 | 총1,055,762,868KiB, 사용46,685,704KiB, 가용955,373,692KiB | 현재 논리 파일시스템 한도 소진 아님 |
| Docker data inode | 총67,108,864, 사용377,548 | 현재 inode 소진 아님 |
| Windows 공유 inode | total999/free1,000,000 등 일관되지 않은 합성 값 | 9p df-i의 초과 사용률을 inode 고갈 증거로 쓰지 않음 |
| data VHDX 파일 | `%LOCALAPPDATA%/Docker/wsl/disk/docker_data.vhdx`, 파일 길이49,112,154,112bytes | 약49.1GB 파일 길이. NTFS 실제 할당량이나 최대 논리 용량과 같다고 해석하지 않음 |
| WSL root ext4 | 약1006.9GiB 중60.3MiB 사용 | Docker data ext4 및 Windows bind와 또 다른 filesystem |

가상 디스크의 논리 공간이911GiB 남아 있어도, 실제 backing file이 놓인 Windows C의14.85GB를 넘겨 물리적으로 확장할 수 있다는 뜻은 아니다. 반대로 Docker 내부 캐시 삭제가 곧바로 Windows C의 동일 용량 반환을 뜻하지도 않는다. 이번 조사에서는 TRIM/compact/resize를 실행하지 않았다.

Docker settings-store의 확인 가능한 키에는 WSL 통합 배포판과 containerd snapshotter 설정이 있었으나 명시적 disk-size override는 없었다. 이를 무제한으로 해석하지 않는다. 위 df의 실제 논리 용량을 측정값으로 사용한다. 설정값 수정은 하지 않았다.

### 원인 판정을 제한하는 사항

- `fsutil volume diskfree`, `fsutil quota query`, `fsutil fsinfo ntfsinfo C:`는 접근 거부됐다. 실행 승인과 Windows 관리자 권한은 다르며 관리자 승격/설정 변경을 하지 않았다.
- 접근 가능한 최근24시간 System 이벤트에서 선택한 Disk/NTFS/storage 오류 이벤트는 발견되지 않았다. 모든 채널/실패 당시 모든 이벤트의 부재를 보장하지 않는다.
- Docker backend 로그5개(각 약1MB)에서 ENOSPC/disk-full 문자열은 발견되지 않았다. 전체 로그 이력·모든 공유 프로세스를 검사한 것은 아니다.
- WSL dmesg의 선택한 저장 오류 패턴에도 관측 항목이 없었다. PostgreSQL의 실제 ENOSPC 로그를 무효화하는 근거는 아니다.
- 기존 컨테이너 내부에서 DB를 조회하거나 PG를 재시작하지 않았다. 쓰기 재현도 하지 않았으므로 현재 정상화를 선언하지 않는다.

## 4. Docker 사용량과 실제 참조 관계

`docker system df`, `docker system df -v`, `docker inspect`의 필요한 필드, `docker builder du`를 읽었다. Env/credential/raw response는 출력하지 않았다. 각 API는 비원자적 관측이므로 집계 시점이 다른 값은 구분한다.

| 자원 | 관측 수/용량 | 삭제 안전성 판단 |
|---|---|---|
| images | df87개/42.1GB, reclaimable38.92GB | 실행·정지 컨테이너 외에 manifest와 오프라인 재현 의존성 확인 필요 |
| containers | 214개/writable layer 합계3.293MB | 개수는 많지만 writable layer는 주요 용량 원인으로 입증되지 않음 |
| named volumes | 15개/640.5MB, reclaimable443.3MB | 링크0이어도 다른 프로젝트 DB/보존 자료일 수 있으므로 모두 보존 |
| build cache | 223개/3.943GB, Reclaimable223개/Shared117개 | 비공유·비사용 후보만 선택 가능. 출력 합계를 단순한 host 회수량으로 보장하지 않음 |

이 값은 host bind에 저장된 PG/Anvil/runtime 데이터의 총량이 아니다. 또한 image Size/SharedSize/캐시 크기를 서로 더하면 공유 레이어가 중복될 수 있다.

### 214개 컨테이너 소유권

전체 inspect 수집 시점: running4, exited208, created2. 앞선 info의 running14/stopped200과는 관측이 달랐다. 이번 작업에서 start/stop한 것은 없으며 두 값을 하나의 원자적 snapshot으로 간주하지 않는다.

| 소유 그룹 | 수 | 상태 및 보존 사유 |
|---|---:|---|
| phase-a.fixture label |130|모두 exited. 성공/실패 fixture 증거와 DB/chain bind 경로 참조 |
| 기타 label 미분류 |56|모두 exited. 소유권 미확정이므로 삭제 후보로 승인하지 않음 |
| exchange |2|exited, 기존 개발 환경 |
| acceptance-20261007153304-9bc0b3 |4|exited, 기존 인수 보존 |
| exchange-ai |1|exited, 기존 AI DB |
| Google 20261016175834 |4|exited, 보존 인수 |
| Google 20261017083610 |4|running, 현재 별도 Google 환경 |
| Kiwoom 20261014171302 |5|exited3/created2, KRX 인수 보존 |
| playground 20261014013317 |4|exited, 기존 사용자 데이터 |
| synthetic-browser 20261016040925 |4|exited, 기존 브라우저 거래 증거 |

214개 컨테이너가 참조하는 서로 다른 image ID는13개다. PostgreSQL16과 Foundry image는 각각96개 컨테이너가 참조한다. 따라서 한 fixture 소유라고 추정해 공용 이미지를 삭제할 수 없다. KRX backend는3개, web은2개 컨테이너에서 공유된다.

df image87개 중 컨테이너 참조가 있는 항목13개, 참조0인 항목74개다. 그러나 session.json/manifest.json/prepared.json의 image/digest 필드와 대조하여 **참조0인 Phase A 도구 이미지 중13개가 session manifest에서 필요함을 확인**했다. 현재 session 도구 `f7f9c0a094b3...`, 이전 core/worker 검증 도구 `2c47e03453bb...`/`4ed4313044a7...`도 해당한다. 삭제 후 재빌드로 같은 image ID가 재생성된다고 보장할 수 없고 기존 identity guard를 새 값으로 덮어쓰면 안 된다.

별도로 KRX tools `1058f04692c6...`, Google tools `53f47edb3294...`/`b8d23d7cf440...`, playground tools `7f4cd4f8be6b...`도 컨테이너 참조0이나 보존 환경 도구이므로 제외했다. 자동 manifest 대조는 위 정해진 파일명에 한정돼 있으므로 그 밖의61개를 안전한 삭제 대상으로 간주하지 않는다.

이름 있는 volume 중 `exchange_ai-data`91.64MB와 `exchange_postgres-data`105.6MB는 각각 컨테이너1개가 참조한다. 링크0 volume에도 다른 프로젝트 PostgreSQL/MySQL 데이터가 존재한다. 익명 volume을 포함해 삭제 대상은0개다.

## 5. 대안별 정확한 대상·예상량·위험

모든 양은 **미실행 추정**이며 승인 전 재조회가 필요하다. 아래 표는 삭제 허가가 아니다.

| 대안 | 대상 및 예상 확보량 | 보존 환경 영향/복구/실패 위험 | 승인 필요 |
|---|---|---|---|
| A 선택 캐시 정리 | 아래 비공유 immutable cache2건, 명목512.9MB. 전체3.943GB 정리는 제안하지 않음 | 컨테이너/volume/image/manifest는 제외. 캐시 재사용 이점 소실, 오프라인 재빌드에 필요한 단계는 재실행될 수 있음. 캐시 원상복구·C: 즉시 반환·ENOSPC 해결 보장 없음 | 정확한2개 ID와 filter 재검증 후 개별 정리 승인 |
| B 개별 비사용 자원 정리 | **현재 안전성을 승인할 수 있는 대상0개/확정 회수0bytes**. 잠재적으로 다른 프로젝트의 미참조 image1개는 UniqueSize13.38GB이나 소유자·보관 요구·재다운로드 권한 불명 | 다른 프로젝트와 보존 증거 손상 가능. 재다운로드는 인증/네트워크 및 동일 digest 필요, 로컬 전용 이미지 복구 불가 가능.214개 일괄 삭제는 효과도 작고 증거 손실 위험 큼 | 사용자 소유권 확인·export/복구 공간·구체적 image ID에 대한 별도 승인 |
| C 가상 디스크 한도/위치 변경 | 논리 크기만 늘리면 host 확보0bytes. 현재 약1TiB 한도라 증설 필요 근거 없음. 별도 물리 디스크로 data VHD를 옮기면 C: 약49.1GB 파일 길이 상당이 후보지만 실제 회수량 미확정 | Docker/WSL 정지·모든 환경 일시중단·백업·대상 디스크 용량/ACL·Desktop 지원 절차 필요. 실패 시 여러 보존 환경 복구 위험 | 전역 서비스 중단/이전/검증 별도 승인. 현재 추천하지 않음 |
| D 데이터 무삭제 경로 | 관리자 읽기 전용 NTFS/할당량 조사 → 소량 신규 쓰기 probe로 host 공유 경계 검증. 이후 필요할 때 **새 fixture PG만** 새 전용 Linux named volume 사용 | 기존 runtime/manifest/DB/chain은 그대로. volume 전환은 host Windows bind를 우회하지만 용량을 회수하지 않으며 host 물리 여유 제약은 유지. harness ownership·보존·백업 경계 변경 검토 필요 | 관리자 확인 및 새 probe/신규 volume/harness 변경은 별도 승인. 이번에 실행 안 함 |

### A의 최소 선택 후보 — 조회만 완료

현재 builder에서 `buildx du --filter id=<ID> --format json`으로 각각 정확히 조회했다. 둘 다 Reclaimable=true, Shared=false, Mutable=false였으며 마지막 사용 표시는10개월 전이다. 사용 목적을 출력 description만으로 완전히 증명하지는 않았다.

| 정확한 cache ID | 명목 크기 |
|---|---:|
| `lafz6m9snsfubgau95ekq0z81` |289.3MB|
| `58xgmj46y4254ts0nevv5rv9w` |223.6MB|

추가 후보 `toyziuog6sjntxcndeyn9ofu8`466.7MB는12일 전 사용으로 위2개보다 최근이므로 최소안에서 제외한다. `buildx prune --help`만 확인했고 prune 자체는 한 번도 실행하지 않았다. 향후 승인돼도 filter가 정확한 ID에만 적용되는지 재검증하고, 필터를 지원하지 않으면 범위를 넓히지 않고 중단한다.

**A를 실행하더라도 현재 실패 파일시스템은 Windows bind이므로 근본 해결 증거로 삼지 않는다.** Docker 내부 캐시 반환과 host NTFS 여유 회수는 별개다.

### B의 큰 이미지 — 삭제 추천이 아니라 승인 경계

잠재 후보 ID는 `sha256:52b6f2952ded316cc4ed4da1f9d259c7761c051ac77fe41a7b70489d17981ba3`다. Docker 컨테이너 참조0, 표시 Size13.4GB/UniqueSize13.38GB. 다른 프로젝트의 registry 이미지이므로 저장소/계정 식별자는 문서에 복사하지 않는다. 현재 프로젝트 manifest 제한 검색에서 안 나왔다는 사실만으로 소유자가 불필요하다고 판단했다고 간주하지 않는다. 현재 권고는 **보존**이다.

## 6. 권장하는 다음 승인 단위

즉시 대규모 삭제보다는 **D의 좁은 경계 진단**을 우선 추천한다. 현재 수치상 가상 디스크 포화가 아니므로 A/C만 실행하고 해결됐다고 보고하면 잘못된 원인 확정이 된다.

1. 사용자가 Windows 관리자 터미널에서 읽기 전용 `fsutil volume diskfree C:`, `fsutil quota query C:`, `fsutil fsinfo ntfsinfo C:`를 확인한다. 전체 사용자/개인 경로 원문을 일반 보고서에 붙이지 않는다.
2. 별도 승인 시 신규 소유 경로에서 최대32MiB의 제한된 쓰기·fsync·읽기 검증과 직전/직후 host/Docker 공간 측정을 한다. 기존 PG/Anvil/manifest를 건드리지 않고, 외부 네트워크나 제품 거래 없이 실행한다. 실패 시 errno/마운트/소요시간을 남기고 중단한다. **이번에는 실행하지 않았다.**
3. 공유 경계 문제가 계속되면 새 fixture PG 데이터만 Linux named volume에 두는 최소 harness 변경안을 승인받아 검토한다. 기존 실패 child를 이동하거나 빈 폴더를 재초기화하지 않는다. 신규 volume도 label/소유권 확인·실패 증거 보존·종료만 수행하도록 설계해야 한다.
4. 별도로 A의2개 캐시를 정리하고 싶다면 정확한 ID만 승인받는다. 이는 선택적 내부 여유 확보이며 현재 ENOSPC의 확정 해결책은 아니다.

현재 machine에서 확인한 일반 filesystem 드라이브는 C뿐이다. 별도 물리 디스크/외장 저장장치의 존재·용량은 미확정이다. Windows 사용자 파일을 임의 검색·삭제하거나 보존 runtime을 다른 위치로 옮기지 않는다.

## 7. 승인 후 정상화 판정과 개발 재개

저장공간 조치 및 경계 검증이 승인·완료되기 전에는 새 E2E를 반복하지 않는다. 현재 `futures-requests`와 `futures-api`의 해당 sourceHash PASS는 유지하지만 restart/최신 auto/전체통합 PASS로 확대하지 않는다.

정상화 후에는 사용자 지정 순서대로:

1. 최신 product/source hash 확인 후 완전히 새 격리 fixture 생성. 실패 child·baseline·manifest 재사용/값 덮어쓰기 없음.
2. 실제 PostgreSQL SQL readiness 및 시각/소유권 검증 후 `futures-restart`의 두 별도 JVM 실행. 같은 raw/hash/nonce, 원래30초 서명 만료 및 체인60초 유예 유지.
3. 최신 `futures-auto` 양방향 자동청산 재검증.
4. 전체 회귀/독립 검토 후 검증된 UI/marker/harness 명시 경로만 로컬 커밋.
5. 선물 감사/자산 대사 → Synthetic 제품 경로·실Chrome → read-only AI Tool/Skill/RAG 진행.

현재는 위 재개 단계 **미실행**이다. 기존 현물/Google/KRX/NXT/실패 증거와 미커밋 작업을 보존했다. commit/merge/push/배포 없음. 이번 조사는 동작 변경 없는 분석·문서 작업이므로 새 구현 검토를 완료했다고 주장하지 않는다.
