# 운영 및 장애 대응

[English](operations.md) · [문서 목차](guide.ko.md)

## 설치와 상태 확인

호스트 요구사항, 서비스 계정, 포트, 설정은 [설치와 설정](installation.ko.md)을 참조합니다. 소유자 로그인과 권한 부여는 [관리자 가이드](administration.ko.md)에 있습니다.

아래 Linux 명령은 시스템 상태만 조회합니다. 실제 설치에서 사용 중인 인스턴스 이름과 포트에 맞춰 실행합니다.

```sh
systemctl is-active veridger.service veridger-admin.service
systemctl is-active veridger-updater.path
curl -fsS --max-time 5 http://127.0.0.1:8765/healthz
curl -fsS --max-time 5 http://127.0.0.1:8865/healthz
```

Windows에서는 `Get-Service veridger,veridger-admin`으로 WinSW 서비스 두 개를 확인하고 동일한 상태 확인 주소를 조회합니다. 인스턴스 설정으로 `ledger-cli mcp-check`를 실행해 도구 목록에 접근할 수 있는지 확인합니다. 관리자 화면에 로그인해 프로젝트가 열리는지도 확인합니다.

## 업데이트와 마이그레이션

- 같은 메이저 버전의 업데이트는 **업데이트** 화면에서 검증된 릴리스를 준비합니다. 호스트 설정과 마이그레이션 자격 증명은 [PostgreSQL 스키마 자동 업데이트](installation.ko.md#postgresql-스키마-자동-업데이트)에 따라 구성합니다.
- 대상 릴리스에서 스키마가 바뀌면 호스트 업데이트 프로그램이 두 서비스를 중지하고 PostgreSQL 덤프를 만든 뒤 미적용 스키마와 앱 계정 권한을 적용합니다. 처리 결과, 설치 버전, 두 서비스의 상태를 확인합니다.
- 메이저 버전을 올릴 때는 대상 릴리스 노트의 절차를 따릅니다. 기존 1.x 장부를 옮길 때는 [1.x 마이그레이션 가이드](migration.ko.md)를 참조합니다.

## 백업과 복구

데이터베이스, 첨부 파일, 첨부 암호화 키, 인스턴스 기동에 필요한 설정과 비밀 값을 함께 백업합니다. 설치 버전과 스키마 버전도 기록합니다. 쓰기를 멈춘 상태에서 백업하거나 같은 시점의 일관된 사본을 확보할 수 있는 방식을 사용합니다. 백업에도 인스턴스와 같은 접근 제한을 적용합니다.

호환되는 실행 파일과 설정을 갖춘 격리 인스턴스에 복구합니다. 두 서비스가 기동하고 복구한 기록을 읽을 수 있는지 확인합니다. `ledger-cli attachments-check`를 실행해 누락·손상이 0건인지 확인합니다. 스키마 업데이트가 만드는 덤프는 데이터베이스를 담으므로 첨부 파일과 키는 백업 계획에 따로 포함합니다. [첨부 저장소와 점검](mcp.ko.md#첨부)

## 장애 진단

| 증상 | 확인할 내용 |
|---|---|
| 공개 주소가 `502` 또는 `503`을 반환 | 서버에서 직접 조회한 상태 확인 결과와 공개 경로를 비교합니다. 서버가 정상이면 역방향 프록시나 터널을 확인합니다. 서버에서도 실패하면 서비스 로그, PostgreSQL 상태, 디스크 여유를 확인합니다. |
| 업데이트가 `RECOVERY_REQUIRED`로 표시 | `.schema-update-in-progress`에서 백업 위치와 대상 버전을 확인합니다. 백업 폴더의 `backup.log`와 `migration.log`를 확인한 뒤 마이그레이션을 완료하거나 서로 맞는 데이터베이스와 JAR로 복구합니다. 서비스와 스키마 버전이 일치할 때 표식을 정리합니다. |
| 서비스가 `SCHEMA_PENDING`을 보고 | 설치한 실행 파일 버전과 마이그레이션 이력을 확인합니다. 해당 릴리스의 문서에 따라 스키마 소유자 계정으로 마이그레이션하고 앱 계정 권한을 적용합니다. 적용된 스크립트와 체크섬은 보존합니다. |
| 조회는 되지만 쓰기가 `HOST_ATTESTATION_REQUIRED`로 거절됨 | [에이전트 연결 확인](client-hooks.ko.md#연결-확인) 단계에 따라 훅 동작과 세션 식별을 확인합니다. |
| 쓰기가 `SESSION_NOT_GRANTED`로 거절됨 | **세션 권한**에서 실제 호스트·세션 ID, 대상 프로젝트, 필요한 권한과 만료를 확인합니다. |

Linux에서는 `journalctl -u veridger.service -u veridger-admin.service -u veridger-updater.service -n 100`과 `df -h /run /opt/veridger`로 로그와 여유 공간을 확인합니다. Windows에서는 인스턴스 `logs` 폴더의 WinSW 로그를 확인합니다. 공유하는 장애 기록에서는 비밀 값과 세션 ID를 제외합니다.
