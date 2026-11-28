# 관리자 가이드

[English](administration.md) · [가이드 목차](guide.ko.md)

## 소유자 점검 순서

1. 소유자 로그인 방식을 선택하고 로그인할 수 있는지 확인합니다. 소유자 인증 정보와 MCP Bearer 인증 정보는 분리해 관리합니다. [관리 웹](#관리-웹)을 참조합니다.
2. 프로젝트를 만들고 접근할 호스트와 세션을 확인합니다. 부여하기 전에 `ledger_whoami`로 세션을 확인하고, 작업이 바뀌면 **세션 권한**에서 권한을 수정하거나 해제합니다.
3. **첨부**에서 저장한 근거를 확인합니다. 인스턴스 점검, 백업, 업데이트, 복구는 [운영 및 장애 대응](operations.ko.md)을 따릅니다.

프로젝트의 기록을 조회하고 관리하는 방법은 [사용자 가이드](user-guide.ko.md)를 참조합니다.

## ledger-cli

`java -jar ledger-cli.jar <command> [arguments]`. 옵션은 `--name=value` 형태로 씁니다. Spring 속성(`--ledger.jdbc.url=...`처럼 점이 있는 이름)도 같은 방식으로 전달합니다.
이 도구는 비밀 디렉터리를 소유한 사용자(Linux에서는 서비스 사용자)로 실행하며, 모든 소유자 작업은 그 사용자 이름으로 기록됩니다.

| 명령 | 구문 | 내용 |
|---|---|---|
| `secret-set` | `secret-set <name> [--stdin] [--if-absent]` | 숨김 입력으로 비밀을 저장합니다. `--stdin`이면 표준 입력의 첫 줄에서 받으며(인수나 환경 변수로는 받지 않습니다), `--if-absent`이면 이미 있는 비밀을 그대로 두고 아무것도 묻지 않습니다. `--stdin`으로 읽는 줄은 UTF-8이며 그렇지 않은 줄은 거절합니다(`INVALID_ENCODING`). Windows PowerShell 5.1은 프로그램으로 파이프하는 글자를 `$OutputEncoding`으로 인코딩하고 이 값은 바꾸지 않으면 ASCII이므로, 다른 문자가 든 비밀을 파이프하기 전에 `$OutputEncoding = [System.Text.UTF8Encoding]::new($false)`(바이트 순서 표시 없는 UTF-8)를 설정합니다 |
| `driver-register` | `driver-register --jar=<path> --class=<driver-class>` | JDBC 드라이버를 등록합니다. 데이터베이스가 필요하지 않습니다 |
| `token-generate` | `token-generate <name> [--print] [--copy-to=<name>]` | 무작위 토큰(`SecureRandom` 32바이트)을 비밀로 저장합니다. 이미 있는 비밀은 그대로 두며, `--print`는 방금 만든 토큰을 한 번만 보여 줍니다. `--copy-to`는 같은 토큰을 두 번째 이름으로도 한 번 저장합니다 |
| `migrate` | `migrate [--owner-account=<account>] [--app-account=<account>] [--stdin]` | 아직 적용하지 않은 스키마 파일을 적용하며(`--owner-account`를 주면 DDL 계정으로 실행하고 그 비밀번호는 입력받거나 `--stdin`이면 표준 입력에서 읽으며 저장하지 않습니다), 이어서 `dml-grants.sql`의 권한을 앱 계정에 부여합니다 |
| `setup` | `setup [--owner-account=<account>] [--app-account=<account>] [--stdin]` | 첫 설정입니다. `migrate`처럼 마이그레이션하고, 앱 계정이 스키마를 쓸 수 있는지 확인하며(쓸 수 없으면 종료 코드 `3`과 빠진 단계를 알림), 없는 시드 코드를 추가하고(있는 코드는 바꾸지 않음), 시드와 데이터베이스를 개수와 다이제스트로 대조합니다. 다시 실행해도 아무것도 바뀌지 않습니다 |
| `passkey-code` | `passkey-code [--remove-existing]` | 관리 웹에 소유자의 패스키를 등록하는 일회용 코드를 발급합니다(한 번만 보여 주고, 15분간 유효하며, 해시로만 저장합니다. 새 코드는 쓰지 않은 이전 코드를 끝냅니다). `--remove-existing`은 패스키를 모두 잃은 소유자를 위해 유효한 패스키를 먼저 모두 닫습니다 |
| `project-add` | `project-add <id> <code> <title>` | 프로젝트를 추가합니다 |
| `code-set` | `code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>] [--inactive] [--direction=<direction>]` | 공통코드를 추가하거나 수정합니다. `--direction`(`GIVEN` 또는 `CANONICAL`)은 `RELATION_KIND` 그룹용입니다(`ledger_link` 도구 참고) |
| `codes` | `codes [<group>]` | 공통코드를 나열합니다 |
| `grant` | `grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]` | 작성자(`<vendor>:<session-id>`)에게 프로젝트의 범위를 바로 부여합니다 |
| `revoke` | `revoke <author> [<project> [<scope>]]` | 유효한 부여를 회수합니다 |
| `grants` | `grants [<author>]` | 유효한 부여를 나열합니다 |
| `attachments-check` | `attachments-check [--purge-orphans]` | 첨부를 그 파일 및 키와 맞춰 봅니다. 어느 행도 가리키지 않는 파일과 `attachments-rekey`가 대체한 파일은 지우지 않고 `orphans/`로 옮기며, `orphans/`에 있던 파일의 행이 다시 생겼으면 되돌립니다. 보고서는 건수와, 없어졌거나 손상되었거나 어느 이력 행도 가리키지 않는 첨부의 ID(저장 키나 경로는 출력하지 않습니다)를 보여 주고, 없어졌거나 손상된 첨부가 있으면 종료 코드는 `1`입니다. 키 파일이 없거나 디스크를 읽을 수 없으면 점검은 그 오류로 멈추며, 손상된 파일로 보고하지 않습니다. `--purge-orphans`는 먼저 `orphans/`에서 어느 행도 가리키지 않는 파일을 지우고(행이 다시 생긴 파일은 지우지 않고 점검이 되돌립니다) 그 수를 감사 기록에 남깁니다 |
| `attachments-rekey` | `attachments-rekey --from=<keyId>` | 키 `<keyId>`로 쓴 첨부 파일을 활성 키 `ledger.attachments.active-key-id`(다른 키여야 합니다)로 다시 씁니다. 옛 파일은 `ledger.attachments.orphan-grace`가 지난 뒤 `attachments-check`가 `orphans/`로 옮깁니다 |
| `attachments-key-create` | `attachments-key-create` | 키 디렉터리에 새 256비트 키 파일을 만들고(서비스 계정만 읽을 수 있습니다. Windows에서는 설치한 사용자와, 읽기만 하는 LocalService) 키 ID를 출력합니다. 키 자체는 출력하지 않으며, 새 업로드를 그 키로 쓰려면 `ledger.attachments.active-key-id`를 그 값으로 설정합니다 |
| `attachments-prepare` | `attachments-prepare <file> [--name=<name>]` | 일반 파일 하나를 스트리밍으로 한 번 읽어 `artifact_begin`에 쓸 JSON(`fileName`, `size`, `sha256`, `mediaType`)을 출력합니다. 서버나 데이터베이스에 연결하지 않습니다 |
| `attachments-send` | `attachments-send <file> --upload-id=<id> [--url=<url>] [--ledger.mcp.token=<token>]` | `artifact_status`와 `artifact_chunk`로 실행 중인 MCP 서버에 파일 청크를 직접 전송합니다(세션 증명 없이 `uploadId` 권한 사용). 중단되면 다시 실행해 서버가 받은 위치부터 이어 보냅니다. 데이터베이스는 필요하지 않습니다 |
| `mcp-check` | `mcp-check [--url=<url>] [--ledger.mcp.token=<token>]` | 실행 중인 MCP 서버에 연결해 도구를 확인합니다 |
| `render-deploy` | `render-deploy --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>] [--admin-host=<host>] [--root=<directory>] [--check]` | 서비스 정의(systemd 유닛, Windows에서는 WinSW 설정)와 설정을 씁니다. 설치기가 호출합니다 |
| `prepare-host` | `prepare-host --jars=<dir> --driver-jar=<file> [--winsw=<file>] --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>] [--admin-host=<host>] [--root=<directory>]` | 호스트의 파일을 준비합니다(root로, Windows에서는 관리자 권한으로 실행). 폴더를 소유자·모드 또는 ACL(Windows에서는 LocalService의 접근과 설치한 사용자의 비밀 폴더)과 함께 만들고, jar와 드라이버(Windows에서는 WinSW도)를 복사하고, `render-deploy`가 쓰는 것을 씁니다. 설치기가 호출합니다 |
| `install-default` | `install-default <name>` | 시드 번들에서 설치 기본값 하나(`instance-name`, `mcp-port`, `admin-port`)를 출력합니다. 설치기가 기본값을 이것으로 받습니다 |

## 관리 웹

개요는 검증 대기와 차단된 작업을 보여주고, 선택한 프로젝트의 버전별 출시 기준 진행률을 표시합니다.
목록에서 검색 결과의 총건수와 현재 쪽에 표시된 건수를 확인할 수 있습니다. 프로젝트명과 설명은 한 화면에서 함께 수정합니다.
이슈·명세·작업·출시 기준·이력에서 연결된 버전을 확인하고 버전으로 검색할 수 있습니다. 이력은 대상에 현재 연결된 버전을 표시하며, 관찰 사례와 같은 관련 기록은 상위 항목의 버전을 따릅니다.
출시 기준에서 취소된 마일스톤을 보관함으로 옮기거나 복원할 수 있습니다. 연결된 기준과 관계, 변경 이력은 보존됩니다.
세션 권한의 검색 조건은 여러 권한을 선택할 수 있고, 선택한 권한을 하나 이상 가진 프로젝트를 표시합니다.

관리 웹(`ledger-admin-web.jar`)은 소유자를 위한 서버 렌더링 콘솔이며 기본 주소는 `127.0.0.1:8865`입니다. 설치기가 만든 서비스 계정으로 실행하며 root로는 실행하지 않습니다.

- **로그인, 셋 중 하나**(`ledger.admin.sign-in`). `passkey`(새 설치의 기본값)이면 소유자는 패스키로 로그인합니다. `token`(개인판: 본인 컴퓨터나 서버의 한 사람)이면 애플리케이션의 로그인 페이지에 관리 토큰 `ledger.admin.token`을 입력합니다. `oidc`이면 OpenID Connect로 로그인하며 소유자는
  **이메일 주소 하나**입니다(`ledger.admin.oidc.owner-email`, `ledger.admin.oidc.issuer`의 공급자가 확인한 주소). 다른 주소나 공급자가 확인하지 않은 주소는 거절합니다.
  셋 중 하나만 제공하며, `oidc`나 `passkey`이면 토큰 입력란이 없고 보낸 토큰은 로그인으로 이어지지 않습니다. 관리 토큰 `ledger.admin.token`은 읽기 전용 JSON 엔드포인트의 bearer로도 남습니다. 한 번 로그인하면 소유자의 변경 작업은 세션과 CSRF 보호 아래에서 작업별 패스키 재승인 없이 바로 저장됩니다. 에이전트의 토큰은 거절되므로
  MCP 토큰을 가진 에이전트가 스스로 부여를 줄 수 없습니다. 자격 증명 확인(패스키, 일회용 코드, 토큰)은 기간 안에 한도보다 많이 들어오면 늦춰집니다(`ledger.admin.lockout.*`).
  아무것도 잠그지 않습니다. 틀린 자격 증명으로 누구나 걸 수 있는 잠금은 소유자도 잠그기 때문입니다. 클라이언트별 제한도 없습니다. 공개는 같은 호스트의
  운영자 역방향 프록시를 거치고, 그 프록시가 넘기는 주소는 요청의 기록일 뿐 신원이 아닙니다(프록시에 따라 모든 사용자가 한 주소로 들어옵니다). 그래서 주소별 잠금은 공격자와 함께 소유자를 잠그며,
  한 사람뿐인 개인판에는 구분할 상대가 없습니다. `token-generate`가 만든 토큰(`SecureRandom` 256비트)과 일회용 코드(100비트)는 추측할 수 없으므로 지연이면 충분합니다.
- **첫 패스키, 추가 패스키, 초기화.** 서버에서 서비스 계정으로 `ledger-cli passkey-code`를 실행하면 일회용 코드를 출력합니다(한 번만 보여 주고, 15분간 유효하며, 해시로만 저장합니다).
  로그인 화면의 "패스키 등록"에 그 코드를 입력하면 등록 전용 세션(15분, 패스키 등록 외에는 아무것도 할 수 없음)이 열리고, 거기서 "등록"으로 패스키를 만듭니다.
  `passkey-code --remove-existing`은 패스키를 모두 잃은 소유자를 위해 유효한 패스키를 먼저 모두 닫으며, 플래그가 없으면 패스키를 건드리지 않습니다. 코드 발급과 초기화는 모두
  소유자 감사 기록에 남습니다. 패스키는 관리 웹의 호스트 이름(`ledger.admin.passkey.rp-id`)에 묶이고, `localhost`를 빼면 HTTPS가 필요하며, 호스트나 origin이 다르면
  `PASSKEY_HOST_MISMATCH`로 거절합니다.
- **구성.** 인증 절차는 Spring Security의 WebAuthn 지원(`spring-security-webauthn`과 WebAuthn4J, 둘 다 Apache-2.0)이 하고, 패스키는 장부 데이터베이스에 저장합니다(V7: base64url 텍스트,
  BLOB 없음, 자격 증명은 바꾸거나 삭제하지 않고 닫는 것도 별도의 행입니다).
- **브라우저 보안.** Spring Security가 CSRF 토큰, 기본으로 `Secure`인 `SameSite=Strict` 세션 쿠키, `Host` 허용 목록, 콘텐츠 보안 정책을 제공합니다.
- **화면.** 키보드로 탐색할 수 있는 사이드바에서 장부를 조회하고 프로젝트와 세션 권한을 관리합니다. 첨부, JDBC 드라이버, 패스키, 업데이트도 이곳에서 엽니다. 목록과 양식은 한국어와 영어를 지원합니다. 시각은 UTC로 전송하고 브라우저의 시간대로 표시합니다.
- **부여.** 세션은 작성자별 카드로 묶여 호스트 배지와 세션 ID를 분리해 보여 주며, 저장 형식은 기존 `<vendor>:<session-id>`를 그대로 유지합니다. 소유자는 키 등록이나 세션별 토큰 발급 없이 모달에서 호스트(`codex`, `claude`, `gemini` 또는 직접 입력)와 세션 ID를 나누어 입력하고(`<vendor>:<session-id>`를 붙여넣으면 자동 분리), 프로젝트의 모든 권한 범위를 한 번에 원자적으로 동기화하거나, 확인 모달을 통해 부여를 회수합니다.
- **프로젝트 버전.** 버전을 열어 명세, 이슈, 작업, 출시 기준을 조회하고 등록하거나 연결을 변경할 수 있습니다. 에이전트는 `ledger_batch`의 `save` 생성에 `project_version` 이름을 지정하여 하나의 트랜잭션으로 등록과 연결을 처리할 수 있습니다. `ledger_get`에 `include_details=true`를 지정하면 네 가지 항목 모두 `project_versions`를 반환합니다.
- **업데이트 (`/updates`).** 소유자가 검증된 릴리스의 설치를 요청하면 호스트가 서비스 재시작과 DB 마이그레이션을 처리합니다. [PostgreSQL 스키마 자동 업데이트](installation.ko.md#postgresql-스키마-자동-업데이트)를 참고하세요.
