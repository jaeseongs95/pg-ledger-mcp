# 1.x에서 마이그레이션

[English](migration.md) · [가이드 목차](guide.ko.md)

## 1.x에서 업그레이드

1.x는 PostgreSQL 함수 위에서 동작하던 DBHub와 Node 구현이었고, 2.0.0은 그 전부를 대체합니다. 기존 데이터는 제자리에서 변환하지 않고 옮깁니다.
1.x 스키마는 그대로 남으므로 되돌릴 때 쓸 수 있습니다. 계약 변경은 [릴리스 노트](release-notes/2.0.0.md)를 참조합니다.

1.x 호출자는 도구 호출을 다음과 같이 바꾸어야 하며, 2.0은 옛 형식을 새 형식과 함께 받아들이지 않습니다.

- `request_id`와 `batch_request_id`는 모든 쓰기 도구의 필수 인자 `operationKey`(1자 이상 128자 이하)로 바뀝니다. 옛 키는 알 수 없는 인자로 거절되고, `operationKey`가 없는 호출도 거절되며,
  서버는 키를 대신 만들지 않습니다. `ledger_batch` 안의 연산에는 자체 키가 없습니다.
- `author_id` 인자는 없어졌습니다. 작성자는 호스트 증명입니다.
- 1.x의 `fields` 맵은 평탄한 도구 파라미터로 바뀌며, 객체나 배열 인자(`ops`, `files`)는 문자열에 담은 JSON 문서가 아니라 JSON 객체나 배열로 보내야 합니다.
- 텍스트 `null`은 인자가 없다는 뜻이 아니라 하나의 텍스트입니다. 값이 없는 인자는 생략합니다.

2.0 자체는 1.x를 전혀 알지 못합니다. 서비스, 설치 프로그램, `ledger-cli`는 1.x의 설정, 파일, 표를 읽지 않습니다. 이전은 별도의 도구인
`ledger-migrate-1x` 모듈이 맡습니다. 빌드가 `ledger-migrate-1x/build/libs/ledger-migrate-1x.jar`로 만들며, 배포 폴더 `dist`에 다른 jar와 함께 둡니다(설치의 `cp` 단계).
설치 프로그램은 이 도구를 알지 못하고 복사하지도 않습니다. 도구는 서비스에 속하지 않기 때문입니다. 도구는 설치에 쓴 Java 25 런타임으로
`java -jar dist/ledger-migrate-1x.jar <command> [options]`처럼 실행합니다. 비밀 저장소를 쓰는 명령은 서비스 사용자로 실행하므로, `dist` 폴더는 서비스 사용자가 읽을 수 있는 곳(`/home` 아래가 아닌 곳)에 둡니다.
도구는 서비스와 같은 방식으로 설치의 설정을 읽습니다(설정 디렉터리 `ledger.secrets.home`의 `application.yaml`, `local.yaml`, 드라이버 등록, 비밀 디렉터리).
이름이 다른 인스턴스에는 `--ledger.secrets.instance=<name>`을 줍니다. 장부 데이터베이스가 필요한 명령에는 등록된 JDBC 드라이버가 필요하며,
`stop-1x`와 `import-secrets-1x`에는 필요하지 않습니다.

| 이전 명령 | 문법 | 하는 일 |
|---|---|---|
| `stop-1x` | `stop-1x [--name=<instance>] [--platform=<platform>] [--unit-dir=<dir>] [--env=<file> [--mcp-host=<host>] [--admin-host=<host>]]` | 첫 단계입니다. 1.x의 서비스 둘 `<name>`과 `<name>-admin`을 멈추고 비활성화하며, 각 정의를 옆에 백업(`<file>.1x-backup`)으로 보관합니다. `--env`를 주면 먼저 1.x의 공개 호스트 이름을 검사합니다 |
| `import-secrets-1x` | `import-secrets-1x --env=<file> [--replace]` 또는 `import-secrets-1x --stdin [--replace]` | 설치 프로그램을 실행한 뒤 1.x의 비밀을 2.0의 비밀 저장소로 옮깁니다. `--replace`를 주면 설치 프로그램이 생성한 토큰을 대체합니다 |
| `import-1x` | `import-1x [--verify-only] [--owner-account=<account>] [--stdin]` | 1.x 장부를 하나의 트랜잭션으로 새 스키마에 복사하고 모든 표를 행 수와 SHA-256으로 대조합니다. `--verify-only`는 대조만 다시 합니다 |
| `retire-1x` | `retire-1x [--confirm=<sha256> --backup-tested]` | 2.0을 받아들인 뒤, 1.x 설치기가 데이터베이스에 만든 것을 제거합니다. 옵션 없이 실행하면 목록만 보여 주고 아무것도 바꾸지 않습니다 |

업그레이드의 순서는 백업, `stop-1x`, 데이터베이스 계정 준비, 설치 프로그램으로 2.0 설치, `import-secrets-1x`, 서비스 재시작, `import-1x`, 확인이며, 2.0을 받아들인 뒤에야 `retire-1x`를 실행합니다.
1.x 서비스는 멈출 때까지 1.x 데이터베이스를 쓰며, 2.0 설치 프로그램은 1.x의 어떤 것도 멈추지 않습니다. 이 순서대로 진행합니다.

1. **백업.** DBMS의 덤프 도구로 장부 데이터베이스를 덤프하고, 1.x 설정 디렉터리 `/etc/pg-ledger-mcp`(파일 `/etc/pg-ledger-mcp/env`에 1.x의
   토큰과 비밀번호가 있습니다)와 `/etc/systemd/system`의 1.x 유닛 파일을 복사해 둡니다.
2. **`stop-1x`로 1.x 서비스를 중지합니다.** 1.x 호스트에서 root로 가장 먼저 실행합니다. 1.x 환경 파일과 호스트 옵션(4단계에서 2.0 설치 프로그램에 줄 것과 같은 옵션)을 주면,
   아무것도 멈추기 전에 1.x의 공개 호스트 이름을 검사합니다.

   ```sh
   sudo java -jar dist/ledger-migrate-1x.jar stop-1x --env=/etc/pg-ledger-mcp/env --mcp-host=<mcp-host> --admin-host=<admin-host>
   ```

   - **바꾸는 것.** 1.x의 서비스 둘, `<name>`(MCP 서버)과 `<name>-admin`(관리 웹이며 1.0.0에는 없었습니다)을 멈추고 비활성화합니다. `<name>`은 `--name=<instance>`이며 기본값은
     `pg-ledger-mcp`입니다. 각 정의는 옆에 백업으로 보관합니다. 유닛 파일 `<unit-dir>/<service>.service`를
     `<service>.service.1x-backup`으로 옮기며, 디렉터리는 `--unit-dir=<dir>`이고 기본값은 `/etc/systemd/system`입니다. 1.x에는 Linux(systemd) 설치만 있었으므로
     Windows에서 멈출 것은 없습니다. `--platform=windows`는 `NO_1X_SERVICE_WINDOWS`로 거절하며, 그곳의 같은 이름 서비스는 2.0 또는 시스템의 것이므로 도구는 절대 건드리지 않습니다.
     1.x 데이터베이스와 1.x 환경 파일은 건드리지 않습니다.
   - **읽는 것.** 두 서비스의 정의와, `--env=<file>`을 주면 1.x 환경 파일이 허용하는 호스트 이름입니다.
   - **프로세스를 시작하는 이유.** Java에는 호스트의 서비스 관리자를 다루는 API가 없으므로, 명령은 고정된 인자(`stop`, `disable`, `daemon-reload`)와
     검증한 이름으로 `systemctl`을 코드의 한 곳에서 실행합니다. 이름이 `<name>` 또는 `<name>-admin`이고 정의가 1.x의 것인 서비스만 건드립니다.
     1.x의 유닛에는 `/etc/<name>/env`를 가리키는 `EnvironmentFile=` 줄이 있습니다.
     첫 변경 전에 모든 서비스를 확인하며, 그렇지 않으면 `NOT_1X_SERVICE`로 멈추고 아무것도 바꾸지 않은 상태로 남습니다.
   - **1.x의 공개 호스트 이름.** 1.x의 클라이언트는 1.x 설정 `DBHUB_ALLOWED_HOSTS`(1.1.0의 관리 웹은 `PGLEDGER_ADMIN_ALLOWED_HOSTS`)가 허용하는
     호스트 이름, 예를 들어 리버스 프록시의 이름으로 1.x 서버에 접속합니다. 2.0의 `Host` 검사는 `127.0.0.1`, `localhost`와 `--mcp-host`의 이름 하나, `--admin-host`의 이름 하나만
     허용하므로(설치 프로그램은 서비스마다 호스트 이름 하나를 받습니다), 클라이언트가 아직 쓰는 이름이 빠지면 이전 직후 그 요청이 HTTP 403으로 실패합니다. 그래서 `--env=<file>`을 주면 명령은 아무것도 멈추기 전에, 파일이 허용하는
     공개 호스트 이름 가운데 옵션이 담지 않은 이름을 2.0이 더는 받지 않는 이름으로 알립니다. 호스트 이름과 옵션을 알리며, 이를 이유로 거절하지는 않고 계속합니다.
     프록시가 넘기는 이름 하나를 옵션에 줍니다(`--mcp-host=<name>`, 설치 프로그램과 같은 형식의 호스트 이름 하나: 소문자, 포트 없음, 목록 없음. `--mcp-host`는 `--admin-host` 없이 줄 수 있습니다).
     같은 옵션을 설치 프로그램에도 줍니다. 루프백 이름은 옵션이 필요 없고, 파일의 그 밖의 내용은 출력하지 않습니다.
     `--env` 없이 두 호스트 옵션만 주면 거절합니다.
   - **다시 실행.** 중간에 멈췄다면 다시 실행합니다. 이미 멈춘 서비스는 그렇다고 알리고, 정의와 다른 내용의 백업 파일은 절대 대체하지 않으며(`BACKUP_DIFFERS`), 서비스 관리자 호출이 실패하면
     (`SERVICE_COMMAND_FAILED`) 이미 한 일은 그대로 두고 다음 실행이 이어서 처리합니다. 이 시점부터 1.x의 클라이언트는 2.0 서버가 실행될 때까지 거절됩니다. 1.x를 새 설치와 나란히 두려면
     `stop-1x`를 실행하지 말고 새 설치에 자체 `--name=<name>`과 포트를 줍니다.
3. **데이터베이스 계정 두 개 생성.** 새 설치의 앱 계정과 DDL 계정을 DBMS 도구로 만듭니다(설치 참조). 1.x의 계정은 그대로 둡니다. 새 스키마를 1.x 장부와 같은
   데이터베이스에 둘 때는 `ledger.schema.name`을 1.x 스키마 `ledger`가 아닌 스키마로 지정합니다.
4. **2.0 설치.** 호스트에 맞는 설치 프로그램으로 설치합니다(설치 참조). 설치 프로그램은 1.x를 알지 못하며, `stop-1x`에 준 호스트 이름을 그대로 줍니다.

   ```sh
   sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url> --mcp-host=<mcp-host> --admin-host=<admin-host>
   ```

   설치 프로그램은 자체 토큰을 생성하고(각각 한 번 보여 줍니다) 그 토큰으로 두 서비스를 시작합니다.
5. **`import-secrets-1x`로 1.x의 비밀을 옮깁니다.** 비밀 저장소는 설치 프로그램이 만드는 서비스 계정의 것이므로 설치 프로그램을 실행한 뒤에 실행합니다.
   1.x 파일은 root가 소유하고 비밀 저장소는 서비스 사용자가 소유하므로, root가 파일을 열어 서비스 사용자로 실행하는 도구에 넘겨 줍니다.

   ```sh
   sudo sh -c 'runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar import-secrets-1x --replace --stdin < /etc/pg-ledger-mcp/env'
   ```

   - **읽는 것.** 1.x 환경 파일이며, 위처럼 `--stdin`으로 주거나 서비스 사용자로 파일을 읽을 수 있는 운영자는 `--env=<file>`로 줍니다. 둘 중 정확히 하나를 줍니다.
     에이전트 토큰만 읽고, 무엇이든 쓰기 전에 그 값을 확인합니다(16자 이상). 데이터베이스는 쓰지 않습니다.
   - **바꾸는 것.** 설치 프로그램이 이미 자체 토큰을 생성했으므로, `--replace`가 없으면 이미 있는 비밀은 그대로 둡니다. `--replace`를 주면 에이전트용 두 비밀이 1.x의 값을 갖습니다.
     1.x의 `DBHUB_AUTH_TOKEN`은 `ledger.mcp.token`과 `ledger.admin.agent-token`이 됩니다.
     1.x의 클라이언트가 그대로 동작합니다. 비밀의 이름만 출력하고 값은 출력하지 않습니다.
   - **관리 토큰은 가져오지 않습니다.** 1.x의 `PGLEDGER_ADMIN_TOKEN`은 옮기지 않으며(1.x 환경에 있으면 명령이 그 사실을 알립니다), 2.0의 관리 토큰은 설치 프로그램이 `token-generate`로 만든
     `ledger.admin.token`입니다(`SecureRandom` 256비트, 한 번만 표시). 사람이 정한 토큰은 더 약할 수 있고, 2.0의 자격 증명 확인 제한은 추측할 수 없는 토큰을 전제로 한 지연이기 때문입니다.
     이 토큰은 읽기 전용 엔드포인트의 bearer로 남습니다(브라우저는 `ledger.admin.sign-in=token`일 때만 로그인시킵니다).
   - 이름이 다른 인스턴스에서는 서비스 사용자가 그 이름이며(`runuser -u <name>`), 도구에는 `--ledger.secrets.instance=<name>`을 줍니다.

   서비스는 시작할 때 비밀을 읽으므로 2.0의 두 서비스를 재시작합니다.

   ```sh
   sudo systemctl restart veridger veridger-admin
   ```

6. **`import-1x`로 장부를 가져옵니다.** 서비스 사용자로 1.x 데이터베이스의 읽기 전용 계정 비밀번호를 저장한 다음, DDL 계정으로 가져오기를 실행합니다.
   1.x 원본의 설정은 아래 표에 있습니다.

   ```sh
   sudo runuser -u veridger -- java -jar dist/ledger-cli.jar secret-set ledger.import.source.password
   sudo runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar import-1x --owner-account=<ddl-account> --ledger.import.source.url=<jdbc-url> --ledger.import.source.username=<account>
   ```

   가져오기는 장부 전체를 쓰며, `--owner-account=<account>`가 DDL 계정으로 실행합니다. 그 비밀번호는 숨김 입력으로 받으며 저장하지 않습니다
   (`--stdin`은 대신 표준 입력의 첫 줄에서 받으며 `--owner-account`가 필요합니다). 1.x 스키마는 읽기 전용으로 읽고 새 스키마는 하나의 트랜잭션으로 씁니다. 아래 가져오기가 하는 일을 참조합니다.
7. **가져온 부여를 확인합니다.** 1.x의 부여는 그대로 복사되며, 클라이언트 훅이 세션 작성자 ID를 각인하면 즉시 동작합니다.
8. **확인.** `ledger-cli mcp-check --url=<its public URL>`와 `import-1x --verify-only`(1.x 원본의 설정이 필요하며 아무것도 바꾸지 않습니다)를 실행하고, `ledger-cli passkey-code`를 실행해 관리 웹에서 첫 패스키를 등록합니다(설치 프로그램이 만든 관리 토큰은
   `ledger.admin.sign-in=token`일 때만 브라우저를 로그인시키며, 기본값은 `passkey`입니다). 클라이언트: `execute_sql_reader`와 `search_objects_reader` 도구는 제거되었으며 읽기 도구가 대체합니다. 쓰기 도구의 결과는 `rows[0].result` 감싸기 없이 키를 그대로 유지합니다.
   서버를 전환하기 전에 모든 클라이언트 호스트에 훅(플러그인 폴더의 두 jar와 Java 25 런타임)을 설치합니다. 확인을 마치면, 가져오기를 위해 1.x 데이터베이스에 만든 읽기 전용 계정은 삭제해도 됩니다.
9. **되돌리기.** 확인이 실패하면 2.0 서비스를 멈추고, 각 `<unit>.1x-backup`을 2.0 유닛 자리에 원래 이름으로 되돌립니다(백업이 남도록 복사합니다). `sudo systemctl daemon-reload`를 실행한 뒤
   `sudo systemctl enable --now <name>`과 `<name>-admin`에 대한 같은 명령으로 1.x 서비스를 이전 설정으로 시작합니다. `stop-1x`가 이 절차를 출력합니다. 1.x 데이터베이스는 읽기만 했으므로 1.x는 그대로 이어집니다.
10. **2.0을 받아들인 뒤 `retire-1x`로 1.x 데이터베이스 객체를 제거합니다.** 그때까지는 보관한 유닛(`<unit>.1x-backup`)과 1.x 데이터베이스가 되돌아갈 길이며, 어느 것도 그 전에 삭제하지 않습니다.
    1.x 설치기는 데이터베이스에 함수, 트리거, 뷰, 표, 스키마 하나와 데이터베이스 역할 네 개를 만들었고, `import-1x`는 그것을 읽기만 합니다. 1.x 데이터베이스를 `pg_dump`로 백업하고
    그 1.x 백업의 복원을 시험합니다. 다음 단계를 마치면 그 백업이 되돌아갈 유일한 길입니다. 1.x 서비스를 멈추고(`stop-1x`), 1.x 객체를 소유한 계정(1.x 설치기의 소유자 역할 또는 그 권한을 가진 역할)의
    비밀번호를 `secret-set ledger.import.source.password`로 저장하고 `ledger.import.source.username`을 그 계정으로 지정한 뒤 옵션 없이 `retire-1x`를 실행합니다.

    ```sh
    sudo runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account>
    ```

    이 명령은 2.0 장부가 1.x가 가졌던 것을 여전히 가지고 있는지(그 뒤 2.0을 썼을 수 있으므로 2.0이 추가한 행과 앞으로 옮긴 카운터는 보지 않고, 2.0이 고친 행은 받아들이며,
    이력·사례와 가져온 버전 그대로인 행은 가져온 그대로여야 합니다. `import-1x --verify-only`는 더 엄격해서 2.0이 추가하거나 바꾼 것을 차이로 보고합니다), 다른 세션이 1.x 역할로 실행 중이거나 1.x 스키마에 잠금을 잡고 있지 않은지, 계정이 객체를 소유하는지 확인하고,
    모든 객체를 제거 순서대로 보여 주며 그 목록의 SHA-256을 출력할 뿐 아무것도 바꾸지 않습니다. 목록을 읽은 뒤 `retire-1x --confirm=<sha256> --backup-tested`를 실행합니다.

    ```sh
    sudo runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account> --confirm=<sha256> --backup-tested
    ```

    명령은 백업을 확인할 수 없으므로 `--backup-tested`는 1.x 백업을 만들었고 그 복원을 시험했다는 사용자의 확인입니다. 명령은 트리거, 뷰, 표, 함수, 시퀀스, 스키마를 하나의 트랜잭션으로
    제거한 다음 `ledger.import.source.roles`의 역할을 제거합니다. 실행 중인 계정이나 2.0 설정의 계정이거나, 다른 구성원이 있거나, 다른 곳의 객체를 소유한 역할은 남기고
    `SKIPPED`로 표시합니다. 장부는 이 작업을 스스로 실행하지 않습니다.

### 가져오기가 하는 일

- 1.x 스키마를 읽기 전용으로 읽어 모든 행을 한 트랜잭션으로 복사하고, 하나라도 실패하면 되돌립니다. 이어서 모든 표의 행 수와 SHA-256 다이제스트를 대조합니다
  (`--verify-only`는 대조만 다시 합니다). 가져오기는 `migrate`와 `setup`이 만든 스키마, 곧 설치 프로그램을 마친 상태에서 한 번 실행합니다(설치 프로그램이 `setup`을 실행하며, 그 시드 코드와 감사 행은 받아들이고,
  1.x에도 있는 시드 코드는 1.x의 값을 따릅니다). 원장 데이터 표, 세션 키, 이전 가져오기 중 하나라도 이미 있으면 거부하며, 옛 데이터 중 새 스키마가 담을 수 없는 값은 모두 나열하고 값을 지어내지 않습니다.
- 1.x의 ID는 그대로 유지합니다. 작업이나 조건의 ID는 공백과 제어 문자가 없는 1자 이상 120자 이하이면 대소문자와 문자 종류(`:`, `/`, `#`, `.`, `_`, `-` ...)를 가리지 않으며,
  결함 ID는 기존 형식(`<CODE>-D-0001`)을 그대로 씁니다. 같은 종류에서 대소문자만 다른 ID는 새 스키마에서 하나의 ID이므로(조회는 ID를 정확히 비교하고, ID를 대문자로 바꾼 키는 어떤 DBMS에서도 유일합니다)
  가져오기는 그런 쌍을 거부하며 나열합니다.
- 1.x의 빈 문자열(`purpose`, `success_condition`, `behavior`, `root_cause`, `condition_text`는 `''`를 허용했습니다)은 NULL로 저장하고 도구는 1.x처럼 빈 문자열을 돌려줍니다.
  조건의 `code`(200자)와 `ci_ref`(2000자)는 이전 2.0 빌드보다 길어졌고, 작업의 `origin_ref`는 긴 텍스트(쓸 때 20000자까지)여서 1.x의 모든 값이 들어갑니다.
- 1.x는 모든 관계를 작은 ID가 앞에 오도록 저장했고 호출자가 어느 결함을 먼저 적었는지 어디에도 남기지 않았습니다. 그래서 가져오기는 방향이 있는 종류(`caused_by`, `blocks`)의
  관계를 저장된 그대로 복사하고 짐작하지 않으며, 그 관계를 나열합니다(건수와 ID, 감사 행과 `--verify-only`에도 나옵니다). 하나씩 확인하여 방향이 거꾸로인 관계는 제거한 뒤
  `ledger_link`로 올바른 결함을 앞에 두고 다시 연결합니다.
- 1.x의 부여는 세션 키 없이 복사합니다(7단계). 1.x의 요청 영수증은 가져오지 않습니다. 2.0의 모든 쓰기는 `operationKey`를 요구하고 옛 `request_id`는 알 수 없는 인자로 거절하므로, 1.x에서 응답이 유실된 요청이 2.0에서 두 번째 쓰기를 일으킬 수 없습니다.
  1.x 이력 행의 `request_id`는 `operation_key`로 보존됩니다.
  1.x에서 온 이력 행은 중립적인 이름 `IMPORT`, `IMPORTED_NOTE`, `IMPORTED_CHANGE`, `IMPORTED_LOGIN`, `IMPORTED_SHARED`, `IMPORTED_UNKNOWN`을 쓰며, 1.x의 `legacy_ref` 필드는 읽기 도구에서 `source_ref`입니다.

### 1.x 원본의 설정

`ledger.import.source.*` 행은 `import-1x`와 `retire-1x` 명령에 속하며 2.0의 다른 부분에는 속하지 않습니다. 설정 디렉터리의 `local.yaml`에 두거나 명령줄에 `--name=value`로 줍니다.
비밀번호는 비밀이므로 파일에 두지 않습니다.

| 키 | 사용처 | 의미 |
|---|---|---|
| `ledger.import.source.url` | `import-1x`, `retire-1x` | 1.x 장부가 있는 데이터베이스의 JDBC URL |
| `ledger.import.source.username` | `import-1x`, `retire-1x` | 그 데이터베이스의 읽기 전용 계정(`retire-1x`는 1.x 객체를 소유한 계정이 필요합니다) |
| `ledger.import.source.password` | `import-1x`, `retire-1x` | 그 비밀번호(비밀) |
| `ledger.import.source.schema` | `import-1x`, `retire-1x` | 1.x 스키마, 기본 `ledger` |
| `ledger.import.source.roles` | `retire-1x` | `retire-1x`가 스키마 다음에 제거하는 1.x 설치기의 데이터베이스 역할, 기본 `ledger_owner`, `ledger_author`, `ledger_reviewer`, `ledger_reader` |

**업그레이드 안내: 관리 웹 로그인.** 관리 웹에 OpenID Connect로 로그인했다면 그렇게 하겠다고 밝히십시오. `ledger.admin.sign-in=oidc`를 설정하고(`ledger.admin.oidc.*` 설정이 있는데
`ledger.admin.sign-in`이 없는 설치는 그 키를 알리는 메시지와 함께 시작할 때 거절됩니다), 소유자의 이메일 주소 하나인 `ledger.admin.oidc.owner-email`과 그 주소가 유효한
발급자 `ledger.admin.oidc.issuer`를 주십시오. 패스키 로그인(`ledger.admin.sign-in=passkey`, 기본값)을 사용하는 경우
`passkey-code`로 한 번 등록해 두면 되며, 로그인 후에는 작업마다 패스키를 다시 승인하지 않고 세션과 CSRF 토큰으로 변경을 보호합니다.
