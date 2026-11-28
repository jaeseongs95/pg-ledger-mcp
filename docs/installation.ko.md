# 설치와 설정

[English](installation.md) · [가이드 목차](guide.ko.md)

## 설정

설정은 Spring Boot 속성입니다. 파일, 명령줄(`--name=value`), 비밀 저장소로 줄 수 있습니다. 비밀은 파일이나 환경 변수에 넣지 않습니다.
`ledger-cli secret-set <name>`은 숨김 입력으로 비밀을 받아 비밀 디렉터리 안에서 그 이름의 용도에 맞는 폴더의 파일 하나로 저장하며, 프로세스들은 이 폴더를 Spring 설정 트리로 읽습니다.
비밀은 그 속성 이름으로 저장하며, 제품의 비밀 이름이 아닌 이름은 거절합니다(`UNKNOWN_SECRET_NAME`). 저장된 비밀을 출력하는 명령은 없습니다.

비밀 디렉터리(`<settings folder>/secrets`)에는 용도마다 폴더가 하나씩 있고, 각 프로세스는 자기에게 필요한 폴더만 읽어 들입니다. 그래서 MCP 서버의 설정에는 관리 웹의 비밀이 들어오지 않고,
관리 웹에는 에이전트의 토큰이 그 이름으로 들어오지 않습니다.

| 비밀(파일 이름이 속성 이름) | 읽는 곳 | 비밀 디렉터리의 폴더 |
|---|---|---|
| `ledger.jdbc.password` | MCP 서버, 관리 웹, `ledger-cli`, `ledger-migrate-1x` | `common` |
| `ledger.admin.token`, `ledger.admin.agent-token`, `spring.security.oauth2.client.registration.<id>.client-secret`(OpenID Connect) | 관리 웹 | `admin` |
| `ledger.mcp.token` | MCP 서버, `ledger-cli mcp-check` | `mcp` |
| `ledger.import.source.password` | `ledger-migrate-1x` | `tool` |

첨부의 키 파일은 비밀 디렉터리에 두지 않고 `<settings folder>/attachment-keys`에 둡니다. 어떤 설정 트리 아래에도 없는 위치입니다(Spring Boot는 트리 아래의 모든 파일을 그 프로세스의 속성으로 만듭니다).

| 키 | 사용처 | 의미 |
|---|---|---|
| `ledger.jdbc.url` | 전체 | 장부 데이터베이스의 JDBC URL |
| `ledger.jdbc.username` | 전체 | 데이터베이스 계정 |
| `ledger.jdbc.password` | 전체 | 데이터베이스 비밀번호(비밀) |
| `ledger.jdbc.pool-size` | 전체 | 연결 풀 크기, 기본 10 |
| `ledger.jdbc.connection-timeout-ms` | 전체 | 연결 풀에서 연결을 기다리는 최대 시간(밀리초), 기본 30000 |
| `ledger.jdbc.idle-timeout-ms` | 전체 | 유휴 연결이 풀에 유지되는 최대 시간(밀리초), 기본 600000 |
| `ledger.jdbc.keepalive-time-ms` | 전체 | 유휴 풀 연결을 점검하는 주기(밀리초, `0`이면 비활성, 그 외 30000 이상), 기본 0 |
| `ledger.jdbc.driver` | 전체 | `driver-register`가 기록한 등록된 드라이버(jar, 클래스, SHA-256)이며 시작할 때 읽습니다 |
| `ledger.schema.name` | 전체 | 표를 담는 스키마. 없으면 `migrate`가 만들고, 지정하지 않으면 계정의 기본 스키마입니다 |
| `ledger.schema.require-utc` | 서버, CLI | JVM이나 데이터베이스 세션이 UTC가 아니면 시작을 거부합니다. 기본 true |
| `ledger.mcp.token` | MCP 서버, `mcp-check` | 에이전트의 bearer 토큰, 16자 이상(비밀) |
| `ledger.mcp.allowed-hosts` | MCP 서버 | 허용하는 `Host` 값, 기본 `127.0.0.1`과 `localhost` |
| `ledger.mcp.allowed-origins` | MCP 서버 | 허용하는 브라우저 출처, 기본 없음 |
| `ledger.mcp.max-request-size` | MCP 서버 | 요청 본문의 최대 크기, 기본 1MB |
| `ledger.mcp.code-poll-interval` | MCP 서버 | 다른 프로세스의 코드 변경을 확인하는 주기, 기본 5s |
| `ledger.mcp.session-idle-timeout` | MCP 서버 | 비활성 Streamable HTTP MCP 세션을 메모리에서 자동 제거하기까지의 유휴 시간, 기본 30m |
| `ledger.mcp.max-sessions` | MCP 서버 | 메모리에 유지할 수 있는 동시 Streamable HTTP MCP 세션 상한, 기본 1000 |
| `ledger.mcp.keep-alive-interval` | MCP 서버 | Streamable HTTP SSE keep-alive 핑 주기(`0s`로 설정하면 POST 전용 클라이언트용 비활성화), 기본 30s |
| `ledger.admin.token` | 관리 웹 | 관리 토큰, 16자 이상(비밀): `/api` 아래 읽기 전용 JSON 엔드포인트의 bearer입니다. `ledger.admin.sign-in=token`이면 로그인 페이지의 비밀번호이기도 합니다 |
| `ledger.admin.agent-token` | 관리 웹 | 필수: 에이전트의 bearer 토큰으로 `ledger.mcp.token`과 같은 값이어야 하고 `ledger.admin.token`과는 달라야 합니다. 관리 웹은 이 토큰을 `AGENT_TOKEN_FORBIDDEN`으로 거절합니다(비밀) |
| `ledger.admin.allowed-hosts` | 관리 웹 | 허용하는 `Host` 값(DNS 리바인딩 방어), 기본 `127.0.0.1`과 `localhost` |
| `ledger.admin.driver-file` | 관리 웹 | 드라이버 화면이 기록하는 드라이버 등록 파일, 기본 `/etc/veridger/jdbc-driver.yaml` |
| `ledger.admin.driver-directory` | 관리 웹 | 드라이버 jar를 가져올 수 있는 유일한 디렉터리, 기본 `/etc/veridger/drivers` |
| `ledger.admin.sign-in` | 관리 웹 | 소유자의 로그인 방식으로 `token`, `oidc`, `passkey` 중 정확히 하나이며 로그인 페이지는 그것만 제공합니다. 없으면 새 설치는 `passkey`이고, `ledger.admin.oidc.*` 설정이 있는데 선택이 없는 설치는 시작할 때 거절합니다(추측하지 않습니다) |
| `ledger.admin.oidc.owner-email` | 관리 웹 | `ledger.admin.sign-in=oidc`일 때 소유자의 이메일 주소 하나이며 공급자가 확인한 주소여야 합니다. 공급자 자체는 `spring.security.oauth2.client.*`입니다 |
| `ledger.admin.oidc.issuer` | 관리 웹 | `ledger.admin.sign-in=oidc`일 때 그 주소가 유효한 발급자(`iss`): 주소는 그것을 확인한 발급자 안에서만 의미가 있습니다 |
| `ledger.admin.passkey.rp-id` | 관리 웹 | 패스키가 묶이는 호스트 이름. 기본값은 `ledger.admin.allowed-hosts`의 루프백이 아닌 호스트 하나이고, 없으면 `localhost`입니다 |
| `ledger.admin.passkey.origins` | 관리 웹 | 브라우저가 보이는 origin(스킴, 호스트, 포트): `http://localhost`를 뺀 모든 origin은 `https`입니다. 기본값은 `https://<rp-id>`이고 `localhost`는 `http://localhost:<server.port>`입니다 |
| `ledger.admin.lockout.window` | 관리 웹 | 자격 증명 확인(관리 토큰, 패스키 로그인, 일회용 코드)을 세는 기간, 기본 60s |
| `ledger.admin.lockout.global-max-failures` | 관리 웹 | 기간 안에 모든 호출자의 자격 증명 확인이 이 횟수를 넘으면 지연이 시작되는 한도, 기본 50 |
| `ledger.admin.lockout.global-delay` | 관리 웹 | 한도를 넘은 뒤 자격 증명을 비교하기 전에 기다리는 시간, 기본 1s. 늦출 뿐 잠그거나 거절하지 않으며, 맞는 자격 증명은 언제나 로그인됩니다 |
| `ledger.admin.mcp.health-url` | 관리 웹 | 개요 화면에 보여 줄 MCP 서버의 `/healthz` URL. 없으면 설정하지 않은 것입니다 |
| `ledger.admin.mcp.timeout` | 관리 웹 | 그 확인의 제한 시간, 기본 2s |
| `ledger.admin.update.enabled` | 관리 웹 | `/updates` 릴리스 확인 및 업데이트 예약 버튼 활성화 여부, 기본 true |
| `ledger.admin.update.repository` | 관리 웹 | 릴리스를 조회할 GitHub 저장소(`owner/repo`), 기본 `jaeseongs95/veridger` |
| `ledger.admin.update.directory` | 관리 웹 | 검증된 업데이트 JAR와 `request.properties`를 호스트 업데이터용으로 스테이징하는 디렉터리, 기본 `/etc/veridger/update` |
| `ledger.admin.update.schema-migration-marker` | 관리 웹 | 설치기가 설정하는 PostgreSQL 스키마 자동 마이그레이션 확인 파일 |
| `ledger.attachments.directory` | 서버, CLI | 첨부 파일을 두는 위치입니다(설치 프로그램이 `<data folder>/attachments`로 정합니다). 정하지 않으면 첨부 도구는 등록되지 않습니다 |
| `ledger.attachments.key-directory` | 서버, CLI | 키 파일을 두는 위치로 `directory` 밖이어야 합니다(안이면 시작이 실패합니다). 설치 프로그램이 비밀 디렉터리 안이 아니라 그 옆인 `<settings folder>/attachment-keys`로 정합니다 |
| `ledger.attachments.active-key-id` | 서버, CLI | 새 업로드를 쓰는 키입니다. `setup`이 `local.yaml`에 적습니다. 키 파일이 없으면 서버가 시작하지 않습니다 |
| `ledger.attachments.max-file-bytes` | 서버, CLI | 파일 하나의 최대 크기, 기본 64MB |
| `ledger.attachments.staging-budget-bytes` | 서버, CLI | 열린 업로드 전체가 선언할 수 있는 크기, 기본 512MB |
| `ledger.attachments.attachment-budget-bytes` | 서버, CLI | 보관 첨부 전체(와 열린 업로드)가 가질 수 있는 크기, 기본 10GB. 넘으면 새 `artifact_begin`을 거절하고 있는 첨부는 지우지 않습니다 |
| `ledger.attachments.max-open-per-owner` | 서버, CLI | 작성자 한 명이 열어 둘 수 있는 업로드 수, 기본 4 |
| `ledger.attachments.disk-margin-bytes` | 서버, CLI | 파일을 쓴 뒤에도 `directory`의 디스크에 남겨 두어야 하는 공간, 기본 64MB(가장 큰 파일 하나의 크기). `usable - size`가 이보다 작으면 begin이나 청크를 `ARTIFACT_STORAGE_FULL`로 거절하며, 소유자가 디스크를 비워야 합니다 |
| `ledger.attachments.upload-ttl` | 서버, CLI | 마지막 청크 뒤 업로드가 사는 시간, 기본 10m |
| `ledger.attachments.orphan-grace` | 서버, CLI | 행 없는 파일(또는 대체된 파일)을 `attachments-check`가 `orphans/`로 옮기기 전 유예, 기본 24h |
| `ledger.attachments.reaper-interval` | MCP 서버 | 만료된 업로드를 지우는 주기, 기본 1m |
| `ledger.secrets.home` | `ledger-cli` | 설정, 드라이버 등록, 드라이버 jar, 비밀 디렉터리를 담는 설치 디렉터리, 기본값은 Linux에서 `/etc/veridger`, Windows에서 `C:/ProgramData/veridger/etc`로, 그 호스트의 설치 프로그램이 두는 곳입니다 |
| `ledger.secrets.directory` | `ledger-cli` | 비밀 디렉터리(폴더 `common`, `admin`, `mcp`, `tool`을 담으며 그 자체는 설정 트리가 아닙니다), 기본값은 `ledger.secrets.home` 아래 `secrets`(Linux에서 `/etc/veridger/secrets`) |
| `ledger.secrets.instance` | `ledger-cli` | 기본 경로가 따르는 인스턴스 이름, 기본 `veridger` |
| `ledger.cli.driver.directory` | `ledger-cli` | `driver-register`가 jar를 가져오는 디렉터리, 기본값은 `ledger.secrets.home` 아래 `drivers`(Linux에서 `/etc/veridger/drivers`) |
| `ledger.cli.driver.file` | `ledger-cli` | `driver-register`가 기록하는 등록 파일, 기본값은 `ledger.secrets.home`의 `jdbc-driver.yaml`(Linux에서 `/etc/veridger/jdbc-driver.yaml`) |

서버는 루프백에서만 수신합니다(MCP `127.0.0.1:8765`, 관리 웹 `127.0.0.1:8865`). 두 서버의 코드에 고정되어 있어 주소를 바꾸는 설정은 없습니다. 외부에
공개하려면 운영자가 고른 역방향 프록시(HTTPS를 종단함)를 같은 호스트에 두고, 그 프록시가 넘기는 호스트 이름 하나를 서비스의 허용 호스트에
적습니다(`--mcp-host`, `--admin-host`). 관리 웹은 루프백 주소에서 온 `X-Forwarded-For`와 `X-Forwarded-Proto`만 신뢰합니다.
서버는 드라이버 등록 파일과 비밀 디렉터리를 `spring.config.import`로 읽으며 설치기가 이를 서비스 정의에 넣습니다(설치 참조). `ledger-cli`는 기본으로 읽습니다.

## 설치

설치기 두 개가 세 가지 대상을 다룹니다. 개인 PC와 Windows 서버는 `deploy\install.cmd`를, Linux 서버는 `sudo deploy/install.sh`를 실행합니다.
대상은 옵션으로만 다르며, 어느 대상에서나 두 서버는 루프백 주소에서만 듣습니다(밖에서 접근하기 참조). macOS는 지원하지 않습니다.
개인 PC가 Linux 데스크톱이면 Linux 절차를 따릅니다. 빌드에는 JDK 25가 필요하고, 호스트에는 모든 계정이 실행할 수 있는 경로(`/home` 아래가 아닌 곳, 서비스 계정도 실행하기 때문입니다)에 Java 25 런타임이 필요합니다.

모든 대상에 공통인 것은 다음과 같습니다.

- **서비스 계정 하나.** MCP 서버와 관리 웹은 어떤 사람에게도 속하지 않는 운영체제 계정 하나로 실행합니다. Linux는 시스템 사용자 `veridger`(로그인 없음,
  root 아님), Windows는 내장 계정 LocalService(비밀번호 없음)입니다. 목적은 장부의 비밀인 관리 토큰과 데이터베이스 비밀번호를 그 컴퓨터에서 일하는 사람의
  계정과 그 아래에서 도는 에이전트로부터 떼어 놓는 것이며, 비밀 디렉터리는 서비스 계정만 읽을 수 있습니다(Windows에서는 설치한 사용자와, 읽기만 하는 LocalService가 읽고 다른 누구도 읽지 못합니다). 비밀은 용도마다 폴더 하나에 두고 각 서비스는 자기 폴더와 공통 폴더만 읽어 들이므로, MCP 서버의 프로세스는 더 이상 관리 토큰이나 OpenID Connect 클라이언트 비밀을 설정에 가지지 않습니다(첨부의 키 파일도 어느 서비스의 속성도 아닙니다). 이것이 하지 못하는 일도 있습니다. 두 서비스는 같은 운영체제 사용자로 실행되므로 파일 권한이 한 서비스가 다른 서비스의 폴더를 읽는 것을 막지는 못합니다. 개인판은 설치 대상 전체를 신뢰하며, 이번 변경은 어떤 프로세스도 다른 용도의 비밀을 자기 환경에 싣지 않게 하는 것입니다. 한 설치를 인스턴스라고 부르며 서비스 이름은 그것을
  따릅니다. 인스턴스는 `--name=<name>`이 다른 이름을 주지 않으면 `veridger`이고, 서비스는 `<name>`과 `<name>-admin`입니다(기본값은 `veridger`와
  `veridger-admin`). Linux 사용자, 디렉터리(`/opt/<name>`, `/etc/<name>`, Windows는 `C:\ProgramData\<name>`), WinSW id가 이름을 따르므로, 두 번째
  인스턴스나 기본 이름으로 아직 1.x가 도는 호스트 옆의 설치에는 자기 `--name=<name>`과 자기 포트만 있으면 됩니다.
- **데이터베이스 계정 둘.** 설치 전에 DBMS의 도구로 만들어 둡니다(계정 생성은 ANSI SQL이 아니며 제품에 속하지 않습니다). 앱 계정은 두 서버와 `ledger-cli`의
  소유자 명령이 함께 쓰며, `migrate`가 `ledger-database/src/main/resources/db-templates/dml-grants.sql`의 최소 권한을 부여합니다(DDL 없음, 이력에
  `UPDATE`와 `DELETE` 없음). DDL 계정은 `migrate`와 `setup`에만 쓰며, 비밀번호는 숨김 입력으로 받고 저장하지 않습니다. 데이터베이스의 세션 시간대는
  UTC여야 하고, 앱 계정이 스키마를 쓸 수 있도록 허용되어 있어야 합니다. 이는 DBMS의 단계이며 제품 SQL에 포함되지 않습니다. `setup`이 이를 확인하고 빠져 있으면
  알려 줍니다(첫 설정 참조).
- **jar와 드라이버.** `./gradlew assemble`로 jar를 빌드하고(jar만 만듭니다. `./gradlew build`는 시험까지 실행하며 시험에는 별도의 PostgreSQL이 필요합니다. 빌드와 시험 참조), jar를 한 디렉터리에 모읍니다. 호스트에 필요한 jar 세 개와, 1.x 설치를 옮기는 도구인
  `ledger-migrate-1x.jar`입니다(1.x에서 업그레이드 참조). 설치 프로그램은 호스트의 jar 세 개만 사용합니다. 이 도구는 서비스에 속하지 않으므로 설치 프로그램은 도구를 알지 못하고 복사하지도 않습니다.
  데이터베이스의 JDBC 드라이버는 직접 구하며 그 클래스를 지정합니다. 제품에 동봉되어 있지 않습니다.

```sh
mkdir dist
cp ledger-cli/build/libs/ledger-cli.jar ledger-mcp-server/build/libs/ledger-mcp-server.jar ledger-admin-web/build/libs/ledger-admin-web.jar ledger-migrate-1x/build/libs/ledger-migrate-1x.jar dist/
```

- **설치기가 하는 일.** 설치기는 얇습니다. Java가 할 수 없는 것만 직접 실행하고, 나머지는 모두 `ledger-cli`의 명령입니다. 호스트의 파일 작업은 Java가
  합니다. `render-deploy --check`가 모든 옵션을 먼저 검증하고, `prepare-host`가 폴더를 소유자·모드·ACL과 함께 만들고, jar와 드라이버 jar를 복사하고, 서비스 정의와
  설정을 씁니다(`application.yaml`과 서비스별 파일 하나씩이 한 디렉터리에 놓입니다. 운영자의 설정은 그곳의 `local.yaml`에 두며, 마지막에 읽고 교체하지 않습니다).
  이어서 설치기는 `ledger-cli`로 비밀을 저장합니다. 앱 계정의 데이터베이스 비밀번호(숨김 입력), `ledger.admin.token`과 `ledger.mcp.token`(생성하며 그 단계의 끝에
  한 번만 표시), 복사본 `ledger.admin.agent-token`입니다. 그리고 드라이버를 등록하고, DDL 계정으로 `setup`을 실행하고(스키마, 부여, 시드 코드. 첫 설정 참조), 두 서비스를 시작합니다. Java가 할 수 없어
  스크립트에 남는 것은 운영체제 사용자 생성(`useradd`), 그 사용자로 명령 시작(`runuser`), 서비스 관리자(`systemctl`, WinSW, `sc.exe`)입니다.
  Java 25 확인은 JVM이 합니다. jar는 Java 25용으로 컴파일되어 다른 런타임은 읽지 못합니다. 내려받거나 설치하는 것은
  없습니다. 더 새로운 jar로 다시 실행하면 업그레이드입니다. 파일과 서비스는 교체되고, 스키마와 권한은 다시 적용되며, 비밀·토큰·데이터는 유지됩니다. 중간에 멈춘 실행도 같은 방법으로 고칩니다. 같은 옵션으로 다시 실행하면 됩니다(비밀을 저장하는 명령은
  데이터베이스를 쓰지 않습니다).
  설정 디렉터리는 도구의 `ledger.secrets.home`이며, 서비스는 같은 출처를 `spring.config.import`로 읽습니다.

`deploy/install.sh`의 옵션은 `--name=value`로 줍니다. 스스로 읽지 않는 옵션은 `render-deploy`로 넘어갑니다.

| 옵션 | 뜻 |
|---|---|
| `--name=<name>` | 인스턴스 이름, 기본값 `veridger`: 소문자, 숫자, 하이픈, 23자 이내, `-admin`으로 끝나지 않음. Java가 무엇이든 만들기 전에 검증합니다 |
| `--java=<path>` | Java 25 런타임. 기본값은 root의 `PATH`에 있는 `java`이며, 서비스 사용자도 실행하므로 모든 계정이 실행할 수 있는 경로(`/home` 아래가 아닌 곳)여야 하고, 스크립트가 서비스 사용자를 만든 직후, 다른 것을 만들기 전에 이를 확인합니다 |
| `--jars=<dir>` | 선택: jar 세 개가 있는 디렉터리. 생략하면 릴리스 jar를 내려받고 `SHA256SUMS`를 자동 검증합니다 |
| `--pg-dump=<path>` | 선택. 호스트에 설치된 `pg_dump` 절대 경로를 지정하여 PostgreSQL 스키마 자동 업데이트를 활성화합니다 |
| `--version=<tag>` | 선택: `--jars` 생략 시 내려받을 릴리스 태그(`latest` 또는 `vX.Y.Z`), 기본값 `latest` |
| `--repo=<owner/name>` | 선택: `--jars` 생략 시 릴리스를 내려받을 GitHub 저장소, 기본값 `jaeseongs95/veridger` |
| `--driver-jar=<file>` | JDBC 드라이버 jar |
| `--driver-class=<class>` | 드라이버 클래스. JDBC URL을 받아들여야 합니다(PostgreSQL JDBC 드라이버는 `org.postgresql.Driver`) |
| `--owner-account=<account>` | DDL 계정(비밀번호를 물어봅니다) |
| `--app-account=<account>` | 앱 계정 |
| `--jdbc-url=<url>` | 장부 데이터베이스의 JDBC URL(비밀번호를 넣지 않습니다) |
| `--schema=<name>` | `ledger.schema.name`, 선택 |
| `--mcp-port=<n>` | MCP 서버의 루프백 포트, 기본값 8765 |
| `--admin-port=<n>` | 관리 웹의 루프백 포트, 기본값 8865 |
| `--mcp-host=<host>` | 선택 옵션입니다. 운영자의 역방향 프록시가 MCP 서버를 위해 `Host` 헤더로 넘기는 호스트 이름 하나(소문자, 포트 없음)이며, MCP 서버의 `Host` 검사가 `127.0.0.1`과 `localhost`(항상 허용)에 더해 허용합니다. 주지 않으면 서버는 루프백으로만 접근합니다 |
| `--admin-host=<host>` | 선택 옵션이며 관리 웹에 대해 같은 뜻이고, `--mcp-host`와 다른 이름이어야 합니다. 이 옵션이 있으면 설치기가 `admin.yaml`에 `ledger.admin.passkey.rp-id`(그 이름)와 `ledger.admin.passkey.origins`(`https://<name>`)도 써서, 원격 설치의 passkey가 직접 고치지 않아도 동작합니다(다른 구성은 `local.yaml`에 `rp-id`와 `origins`를 지정) |
| `--stdin` | 비밀번호를 터미널 대신 표준 입력에서 읽음. 먼저 앱 계정의 비밀번호(아직 저장되어 있지 않을 때만), 그다음 DDL 계정의 비밀번호. `docker login --password-stdin`과 같은 관례입니다 |

`render-deploy`와 `prepare-host`에는 설치기가 스스로 정하는 옵션이 다섯 더 있습니다. `--platform=<platform>`(`linux`가 기본값, 또는 `windows`),
`--home=<path>`(Windows: 설치 디렉터리), `--java=<path>`(Java 실행 파일), `--root=<directory>`(파일을 제 경로 대신 한 디렉터리 아래에 둠. 제자리에 두기 전에
살펴볼 때 씁니다), 그리고 `render-deploy`에만 있는 플래그 `--check`(모든 옵션을 검증하고 아무것도 바꾸지 않음. 설치기가 가장 먼저 실행하므로, 이름을 포함해
잘못된 값은 아무것도 없을 때 설치를 멈춥니다)입니다. `prepare-host`는 `--jars=<dir>`, `--driver-jar=<file>`, Windows에서는 `--winsw=<file>`도 받습니다.
`--root` 없이는 플랫폼이 명령을 실행하는 호스트의 플랫폼이어야 합니다. 다른 플랫폼의 경로는 이 호스트의 경로가 아니어서 작업 디렉터리 아래에 쓰이게
되므로, 명령은 아무것도 읽거나 쓰기 전에 `--platform`을 지목해 거절합니다.

### Linux 서버

root로 실행합니다. 사용자 `veridger`가 없으면 만들고, `prepare-host`(Java)가 `/opt/<name>`(root, 0755), `/etc/<name>`(서비스 사용자, 0750), 그 아래
`drivers` 폴더(root, 0755)와 `secrets` 폴더 및 그 아래의 `common`, `admin`, `mcp`, `tool` 폴더, 키 폴더 `attachment-keys`(서비스 사용자, 0700)를 만들고, jar와 드라이버를 복사하고, 유닛을 `/etc/systemd/system`에 렌더하며, 유닛은 이어서 활성화합니다.
비밀은 서비스 사용자가 저장합니다. 설치 프로그램이 렌더하지 않은 파일을 렌더한 파일이 대체하면 그 파일은 `<file>.backup`으로 보관합니다. 1.x 설치를 옮기려면 1.x에서 업그레이드를 참조합니다.

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url>
```

설치 프로그램은 서비스 둘(`<name>`과 `<name>-admin`, 기본값은 `veridger`와 `veridger-admin`)을 활성화하고 재시작하며, 최소 권한 호스트 업데이터(`veridger-updater.sh`, systemd `.path` 유닛으로 트리거)도 함께 설치합니다. `systemctl status veridger veridger-admin`으로 확인합니다. 대화형 터미널에서 `--jars`와 필수 옵션을 생략하면 `deploy/install.sh`가 `SHA256SUMS`를 검증해 내려받고 빠진 값을 물어봅니다.

### 밖에서 접근하기

두 서버는 `127.0.0.1`에서만 수신하며, 이를 바꾸는 설정은 없습니다. 다른 컴퓨터에서 접근하게 하려면 운영자가 같은 호스트에 역방향 프록시와 TLS 인증서를
운영자가 고른 도구로 둡니다. 설치기는 프록시 설정을 쓰지 않고, 인증서를 발급하지 않으며, 갱신 장치를 설치하지 않습니다. 프록시는 `127.0.0.1:8765`(MCP 서버)와
`127.0.0.1:8865`(관리 웹)로 연결하고 클라이언트의 `Host` 헤더를 그대로 넘깁니다. 각 서비스에는 프록시가 그 서비스를 위해 넘기는 호스트 이름 하나를
`--mcp-host=<host>`와 `--admin-host=<host>`로 줍니다. 그 이름은 해당 서비스의 `Host` 검사에서 `127.0.0.1`과 `localhost`(항상 허용)에 더해 허용됩니다.
관리 웹의 이름이 있으면 설치기가 그에 맞는 passkey 설정도 씁니다(위의 옵션 참조). 관리 웹은 루프백 주소에서 온 `X-Forwarded-For`와 `X-Forwarded-Proto`만
신뢰하므로, 프록시는 이 두 헤더를 자신이 받은 연결의 주소와 스킴으로 덮어써야 합니다(리다이렉트와 WebAuthn 출처 확인 및 요청 기록에 쓰이며, 자격 증명 속도 제한은 `ledger.admin.lockout.global-delay`를 쓰므로 클라이언트 주소별로 잠그지 않습니다). 오래 유지되는 MCP 연결(Streamable HTTP)에는 충분히 긴 프록시 타임아웃이
필요합니다. 인증서, 그 갱신, 방화벽도 운영자의 몫입니다.

### Windows: 개인 PC 또는 서버

관리자 권한 프롬프트에서 실행합니다. 스크립트의 인자는 아래 순서이며, 서비스는 Spring Boot 문서가 Windows 서비스로 안내하는 WinSW로 등록합니다. WinSW 2.x
실행 파일은 JDBC 드라이버처럼 직접 구하며, 설치기는 이를 동봉하거나 내려받지 않습니다.

| 인자 | 뜻 |
|---|---|
| `[--stdin]` | 선택이며 첫 번째 인자로만 줄 수 있음. 비밀번호를 터미널 대신 표준 입력에서 읽음(먼저 앱 계정의 비밀번호, 아직 저장되어 있지 않을 때만, 그다음 DDL 계정의 비밀번호). `install.sh --stdin`과 같으며 나머지 인자의 순서는 그대로임 |
| `<jars-dir>` | jar 세 개가 있는 디렉터리 |
| `<winsw-exe>` | WinSW 2.x 실행 파일 |
| `<driver-jar>` | JDBC 드라이버 jar |
| `<driver-class>` | 드라이버 클래스 |
| `<ddl-account>` | DDL 계정 |
| `<app-account>` | 앱 계정 |
| `"<jdbc-url>"` | JDBC URL, 따옴표 안에 |
| `"<name>"` | 선택, 따옴표 안에: 인스턴스 이름, 기본값 `veridger` |
| `"<more options>"` | 선택, 이름 뒤에 따옴표 안에: `render-deploy`의 다른 옵션, 예를 들어 서버의 호스트 이름 |

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>"
```

외부에서 접근하는 서버는 서비스마다 호스트 이름을 더하고, 직접 준비한 역방향 프록시를 두 루프백 포트 앞에 둡니다(밖에서 접근하기 참조).

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>" "veridger" "--mcp-host=<mcp-host> --admin-host=<admin-host>"
```

`SHA256SUMS`를 자동 검증해 내려받는 대화형 설치는 `deploy\install.cmd --download`로 실행합니다. 설치기는 관리 웹 업데이트를 위한 호스트 업데이터 스크립트(`veridger-updater.cmd`)도 작업 스케줄러에 함께 등록합니다.

#### PostgreSQL 스키마 자동 업데이트

Linux에서는 `--pg-dump=/absolute/path/to/pg_dump`를 추가하고 Windows에서는 `VERIDGER_PG_DUMP`에 `pg_dump.exe`의 절대 경로를 지정한 뒤 최신 설치기를 실행합니다. 기존 설치도 이 설정은 한 번 필요합니다. DB 서버와 메이저 버전이 같은 클라이언트와 단일 호스트 및 데이터베이스 이름이 있는 PostgreSQL JDBC URL을 사용합니다. URL에는 `sslmode`를 지정할 수 있습니다. 설치할 때 입력한 DB 소유자 자격 증명은 호스트 업데이터의 전용 디렉터리에 저장됩니다. DB 주소, 스키마 또는 소유자 자격 증명이 바뀌면 다시 설정합니다.

같은 주 버전 내에서 업데이트할 때 호스트는 설치본과 새 JAR의 마이그레이션을 비교합니다. 변경이 있으면 두 서비스를 중지하고, DB를 백업한 뒤 마이그레이션과 앱 계정 권한 갱신을 적용합니다. 이후 두 서비스를 시작하고 상태를 확인합니다. 백업은 Linux의 `/opt/<name>/backup-*` 또는 Windows의 `C:\ProgramData\<name>\lib\backup-*`에 남습니다.

마이그레이션 시작 후 실패하면 JAR 디렉터리의 `.schema-update-in-progress`에 백업 위치와 대상 버전이 기록됩니다. 백업 폴더의 `backup.log`와 `migration.log`를 확인해 대상 버전의 마이그레이션을 완료하거나 백업과 일치하는 DB 및 JAR를 복원합니다. DB와 두 서비스가 일치하는 버전으로 정상 동작할 때 복구 표시를 제거합니다. 주 버전 변경에는 기존 설치 절차를 적용합니다.

모든 것은 `C:\ProgramData\<name>`에 놓입니다. 두 서비스는 LocalService로 실행하고, 실패하면 다시 시작하며(WinSW 설정), 로그는 `logs` 폴더에 씁니다.
`prepare-host`는 폴더를 SYSTEM과 Administrators 외 모든 계정에게 닫고 같은 단계에서 LocalService에 필요한 접근을 줍니다(Java가 ACL로 하며 `icacls`는 쓰지 않습니다).
jar는 읽고 실행하고, 설정, 로그, 데이터는 바꿀 수 있습니다. 비밀은 설치한 사용자의 것입니다. 비밀 폴더, 그 아래의 용도별 폴더, 키 폴더 `attachment-keys`, 그 안에 만드는 비밀은 설치한 사용자(전체)와
LocalService(읽기만)의 ACL로 만들어지고 다른 누구에게도 접근이 없으므로, 서비스는 읽을 수 있고 에이전트가 실행되는 계정은 읽을 수 없습니다.
LocalService에 넘기는 것은 없으므로(설치한 사용자가 소유자로 남고, 소유권을 가져오는 권한도 쓰지 않습니다) `ledger-cli`의 소유자 명령은 설치한 사용자에게 계속 동작하고, 설치기를 다시
실행해도 모든 것이 그대로입니다. 같은 사용자로 다시 실행합니다(개인판은 사용자가 한 사람입니다). 소유자 작업은 관리 웹에서도 합니다(관리 웹 절 참고: 패스키 또는
OpenID Connect로 로그인하고, 첫 패스키는 `passkey-code`로 등록합니다). 개인 PC에서는 관리 웹을 `http://localhost:8865`로 엽니다(포트는 `--admin-port=<n>`입니다). 관리 웹은
`Secure` 세션 쿠키를 설정하는데, 주요 브라우저는 `localhost`를 보안 컨텍스트로 취급하므로 이를 받아들이고, 패스키도 `localhost`에서는 HTTPS 없이 동작합니다.

### 첫 설정

설치 스크립트는 `setup`을 호출하며, 업그레이드한 뒤나 직접 준비한 데이터베이스에서는 운영자가 직접 실행합니다. 새 장부는 처음에 담을 데이터가 필요하므로 `ledger-cli`는
시드 번들을 함께 가지고 있습니다. 시드 번들은 파일 `ledger-seed.mv.db`이며, 빌드가 `ledger-cli` 모듈의 텍스트 파일 `src/seed/issue-categories.tsv`와
`src/seed/install-defaults.properties`에서 Java 프로그램으로 만들어 `ledger-cli.jar`에 넣습니다. 이 파일은 저장소에 커밋하지 않습니다. 번들에는 기본 결함 분류 14개
(코드, 한국어·영어 이름과 설명)와 설치 기본값(인스턴스 이름과 두 포트)이 들어 있으며, 비밀값은 없습니다. 파일 형식이 H2 데이터베이스일 뿐이며 H2는 여기서 그 용도로만
쓰입니다. 장부는 언제나 운영자가 설치한 데이터베이스 시스템에 저장되고, H2 드라이버나 H2의 JDBC URL을 등록하려 하면 `driver-register`와 관리 웹의 드라이버 화면이
거절합니다.

`setup [--owner-account=<account>] [--app-account=<account>] [--stdin]`은 `migrate`의 옵션을 그대로 받고, 다음 네 가지를 이 순서로 합니다.

1. `migrate`와 똑같이 스키마를 마이그레이션하고 앱 계정의 부여를 적용합니다.
2. 앱 계정의 연결로, 그 계정이 스키마를 쓸 수 있는지 확인합니다. 스키마를 쓸 수 있는 권한은 데이터베이스 시스템의 문장이며 제품 SQL에 포함되지 않습니다. 따라서 그 권한이
   빠져 있으면 `setup`은 종료 코드 `3`으로 멈추고, 계정을 밝히고, 운영자가 데이터베이스 시스템의 도구로 그 권한을 줘야 한다고 알려 줍니다. 그 시점에는 그 밖의 것이 바뀌지
   않았으며, 그 단계를 한 뒤 `setup`을 다시 실행하면 멈춘 곳에서 이어집니다.
3. 장부에 아직 없는 시드 코드를 `code-set`과 관리 웹이 쓰는 서비스로 추가합니다. 코드 개정 번호와 소유자 감사 기록이 그대로 남습니다. 이미 있는 코드는 절대 바꾸지
   않습니다. 이름을 바꾸거나 퇴역시킨 코드는 운영자가 둔 그대로 남습니다.
4. 시드와 데이터베이스를 코드 개수와 SHA-256 다이제스트로 대조하고 보고를 출력합니다. `setup`을 다시 실행해도 아무것도 바뀌지 않고 같은 보고가 나옵니다. 시드와 다른 운영자의
   코드는 바뀐 것으로 세고 그대로 둡니다.

설치 기본값은 시드 번들 한 곳에 적습니다. `render-deploy`와 `prepare-host`는 옵션을 주지 않으면 이름과 포트를 거기서 가져오고, 설치 스크립트는
`install-default <name>`(`instance-name`, `mcp-port`, `admin-port`)으로 받으므로 어느 스크립트도 기본값의 사본을 갖지 않습니다.

### 설치 뒤

1. `ledger-cli mcp-check`로 MCP 서버를 확인합니다. 등록된 드라이버가 없으면 MCP 서버는 이유를 밝히고 멈추며, 관리 웹은 로그인 화면만 제공하는 설정 모드로 시작합니다.
   그 화면이 `ledger-cli driver-register`로 드라이버를 등록하라고 알려 줍니다(데이터베이스가 없으면 로그인에 쓸 패스키가 없으므로 관리 웹의 드라이버 화면은 콘솔 화면입니다).
2. 소유자 쪽 설정을 합니다. `project-add`, `code-set`을 실행합니다. 1.x에서 온 경우에는 프로젝트를 직접 만들지 말고 1.x에서 업그레이드를 따릅니다. Linux에서는
   `ledger-cli`를 서비스 사용자로 실행합니다. 예: `sudo -u <name> java -jar /opt/<name>/ledger-cli.jar --ledger.secrets.instance=<name> project-add ...`.
3. 클라이언트 훅을 설치하고(클라이언트 훅 참조), `grant` 또는 관리 웹의 부여 화면에서 세션 권한을 바로 부여합니다.

`migrate`는 파일 `V{n}__*.sql`을 순서대로 적용하고, 시도마다 파일의 SHA-256과 함께 `schema_history` 표에 기록합니다. 표는 버전의 시도마다 한 행을
가지며 열은 `version`, `attempt`, `script`, `digest`, `applied_at`, `outcome`(`SUCCEEDED` 또는 `FAILED`)입니다. 시도 하나는 트랜잭션 하나입니다. 시도는
자기 행을 `FAILED`로 먼저 넣고 마지막에 그 한 행을 `SUCCEEDED`로 바꿉니다. 파일이 실패하면 시도를 되돌리고 행은 `FAILED`로 남으며, 다음 실행은
이전 행을 바꾸거나 지우지 않고 그 버전을 다음 시도로 다시 적용합니다. `migrate`를 동시에 두 번 실행해도 안전합니다. 두 번째는 데이터베이스에서 첫 번째를
기다린 뒤 첫 번째가 적용한 것을 건너뜁니다. 적용한 뒤에 바뀐 파일이 있으면 `migrate`는 아무것도 적용하기 전에 멈추고 모든 프로세스도 시작할 때 멈추며,
그 일로 행을 더하지 않습니다. 데이터베이스의 인코딩은 UTF-8이어야 하며 `migrate`는 아무것도 바꾸기 전에 방언의 탐침 질의로 이를 확인하고, 스키마 파일의 긴 텍스트 타입은 장부의 데이터베이스 방언이 정하며, `migrate`는 연결의 제품 이름에서 방언을 가져옵니다(그에 대한 설정은 없습니다).
각 선언은 대상 데이터베이스의 공식 문서에서 가져오며, 길이를 생략하면 기본 한도가 작아지는 데이터베이스의 선언에는 길이를 적습니다.
JDBC 드라이버의 타입 정보는 드라이버가 판단할 수 없는 형태로 보고할 수 있으므로 쓰지 않습니다.

장부는 Flyway를 쓰지 않습니다. 최신 Flyway는 DBMS마다 모듈이 있어야 하고 모듈이 없는 데이터베이스는 거부하지만, 장부는 사용자가 드라이버를 등록하는 어떤
데이터베이스에도 ANSI SQL을 요구하기 때문입니다(실제 검증된 데이터베이스는 PostgreSQL이며, 다른 DBMS 제품은 검증되지 않았습니다). 또 서버가 시작할 때가 아니라 이 명시적인 명령으로 마이그레이션하는 까닭은 서버가 DDL 계정의 비밀번호를
갖지 않기 때문입니다. 서버는 시작할 때 스키마를 확인만 하고, 적용하지 않은 파일이 있으면 시작을 거부하며 `ledger-cli migrate`를 알려 줍니다. 그래서
서버가 침해되어도 스키마는 바뀌지 않습니다.
