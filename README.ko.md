# pg-ledger-mcp

[English](README.md)

AI 에이전트가 수행한 작업을 기록하는 장부이며, 하나의 MCP 엔드포인트로 제공됩니다. 결함, 작업, 수정과 그 검증을 누가 왜 수행했는지와 함께 기록합니다.
에이전트는 MCP 도구로 읽고 쓰며, 자신의 데이터베이스 로그인을 받지 않습니다.

2.0.0 버전은 Java 25와 Spring Boot로 다시 작성한 것입니다. 클라이언트가 의존하는 계약(쓰기 도구의 이름과 입력 키, 결과의 키, `_hostAttestation` 키)은 유지하며,
바뀐 내용은 [릴리스 노트](docs/release-notes/2.0.0.md)에 적습니다. 릴리스 노트의 형식은 [docs/RELEASE_NOTES_FORMAT.md](docs/RELEASE_NOTES_FORMAT.md)에 있습니다.

## 목차

- 개념
- MCP 도구
- 모듈
- 설정
- 설치
- ledger-cli
- 관리 웹
- 클라이언트 훅
- 1.x에서 이전
- 빌드와 시험
- 구성

## 개념

- **작성자 신원은 주장이 아니라 증명으로 확인합니다.** 모든 쓰기와 모든 `ledger_whoami` 호출에는 예약 키 `_hostAttestation`이 붙으며, 이 값은 클라이언트의 호스트 훅이 채웁니다.
  모델은 이 키를 넣지 않아야 합니다. 작성자는 `<vendor>:<session-id>` 형식이며 예를 들어 `claude:1f2e3d4c` 또는 `codex:9a8b7c6d`입니다. 장부의 소유자는 `user:owner`입니다.
  옛 `author_id`는 받아들이되 무시합니다.
- **쓰기에는 유효한 부여가 필요합니다.** 소유자는 `ledger-cli grant`(또는 관리 웹의 부여 화면)로 작성자에게 프로젝트의 범위를 부여합니다.
  범위는 `defect`, `task`, `fix`, `verify`입니다. 해당 부여가 없는 쓰기는 거절됩니다.
- **검증은 분리되어 있습니다.** `VERIFIED`, `MET`, `NOT_MET`은 검토자 도구 `ledger_review`와 `ledger_review_correct`로만 설정할 수 있으며, 이 도구들은 `verify` 범위를 요구합니다.
  결함은 증거 링크와, 검증자와 다른 세션이 기록한 수정이 하나 이상 있어야 검증됩니다. 검증 결과와 재작업 요청은 결함을 등록한 세션(`created_by`)이 아니라
  수정을 기록한 세션(`fixed_by`)에게 보냅니다. CI 결과는 선택 메타데이터입니다.
- **이력은 추가만 가능합니다.** 쓰기마다 이력 행이 추가되며, 시각, 작성자, 채널, 연산, 상태 변화, 사유를 담습니다. 애플리케이션에는 이력 행을 수정하거나 삭제하는 경로가 없고,
  데이터베이스 모듈의 선택적 부여 템플릿은 애플리케이션 계정에 이력의 `UPDATE`와 `DELETE`를 주지 않습니다. 잘못된 기록은 `CORRECT` 행으로 표시합니다.
  기존 행을 바꾸는 연산은 바꾸기 전의 전체 행도 함께 저장합니다(`before_data`, `ledger_history`에서 `include_before`를 주면 반환됩니다).
- **재시도는 안전합니다.** UUID인 `request_id`를 전달합니다. 같은 요청을 반복하면 저장된 결과를 돌려주며 두 번 기록하지 않습니다.
  1.x 장부에서 가져온 영수증으로 요청하면 `REQUEST_ID_LEGACY`로 응답하므로 새 요청 ID를 사용해야 합니다.
- **낙관적 잠금.** 수정과 전이는 `expected_version`을 가지며, 오래된 판은 `VERSION_CONFLICT`입니다.
- **프로젝트와 ID.** 결함은 프로젝트에 속하며 `<CODE>-D-0001`, `<CODE>-D-0002`와 같은 ID를 받습니다. ID 없이 만든 작업은 `<CODE>-T-0001`과 같은 ID를 받습니다.
  ID는 서버가 부여합니다.
- **결함 하나는 근본 원인 하나이고, 관찰된 사례는 여러 개입니다.** 결함에 대한 각 관찰(증상, 로그, 사용자 보고)은 그 아래의 사례입니다(`ledger_record_case`).
  병합된 결함(`MERGED`, `merged_into`)은 사례를 그대로 유지하며, `ledger_defect`는 병합 대상 결함 아래에 그 사례를 보여 줍니다. `MERGED` 결함에는 새 사례를 추가할 수 없습니다.
- **공통코드.** 결함 분류, 결함 심각도, 관계 종류는 세 그룹 `DEFECT_CATEGORY`, `DEFECT_SEVERITY`, `RELATION_KIND`이며 한국어와 영어 이름을 가집니다.
  활성 값은 도구 정의에 반영됩니다. 폐기한 코드는 기존 행에 남고 새로 지정할 때는 거절됩니다. 모든 결함에는 심각도가 필수입니다.
- **생성 출처는 바뀌지 않습니다.** 새 행에는 `created_by`가 자동으로 기록되며, 수정과 검토자 전이는 이를 유지합니다.
- **시각은 UTC입니다.** 시각은 UTC(`Z`가 붙은 ISO-8601)로 저장하고 전송하며, 서버는 시간대를 변환하지 않습니다. JVM과 데이터베이스 세션은 UTC로 실행되어야 하며,
  그렇지 않으면 서버가 시작되지 않습니다(`ledger.schema.require-utc`).
- **데이터베이스 중립.** 제품은 JDBC 드라이버를 동봉하지 않습니다. 사용자가 원하는 데이터베이스의 JDBC 드라이버를 직접 구해 `driver-register`로 등록합니다.
  ANSI SQL만 사용하며 DBMS 고유 기능은 사용하지 않습니다. 스키마 파일에는 트리거, 프로시저, 뷰, 시퀀스, 자동 증가 열, 불리언 열이 없고,
  데이터베이스가 맡던 규칙은 애플리케이션 서비스가 강제합니다. 데이터베이스 목록은 공개하지 않습니다.

MCP를 직접 호출하는 쪽은 호스트로 인증되지 않습니다. bearer 토큰은 호출자를 들여보내고, 서명된 증명이 작성자를 증명합니다.

## MCP 도구

서버는 `/mcp`(기본 `127.0.0.1:8765`)에서 Streamable HTTP로 MCP를 제공하고 `/healthz`에 응답합니다. 호출자는 bearer 토큰 `ledger.mcp.token`을 제시합니다.
쓰기 도구는 이름과 입력 키를 그대로 유지합니다. 결과는 키를 결과의 최상위에 담으며, 1.x의 `rows[0].result` 감싸기는 없어졌습니다. 모든 도구 입력은 닫혀 있어, 알 수 없는 인자는 거절됩니다.

쓰기 도구(로그인, 증명, 유효한 부여가 필요합니다).

| 도구 | 채널 | 내용 |
|---|---|---|
| `ledger_whoami` | author | 이 작성자 형식이 올바른지, 어떤 부여를 가졌는지 확인합니다(읽기 전용) |
| `ledger_save` | author | 결함, 작업, 조건을 만들거나 수정합니다 |
| `ledger_transition` | author | 상태를 바꿉니다(`OPEN`, `SOURCE_FIXED`, `MERGED`, `ACTIVE`, `REPORTED_DONE` 등) |
| `ledger_record_fix` | author | 수정 커밋과 선택적 CI 메타데이터를 기록합니다. 수정한 세션이 `fixed_by`가 되고 `OPEN` 결함은 `SOURCE_FIXED`가 됩니다 |
| `ledger_record_case` | author | 결함 아래에 관찰된 사례를 추가합니다(`MERGED` 결함은 거절되며 병합 대상을 알려 줍니다) |
| `ledger_link` | author | 결함, 작업, 조건을 연결하거나 연결을 제거합니다 |
| `ledger_correct` | author | 이력 행이 잘못되었음을 표시합니다 |
| `ledger_batch` | author | 위 연산을 한 트랜잭션에서 최대 200개까지 처리합니다. 모두 반영되거나 모두 반영되지 않습니다 |
| `ledger_review` | reviewer | `VERIFIED`, `MET`, `NOT_MET`과 그 재개를 처리합니다. 증거 링크가 필수입니다 |
| `ledger_review_correct` | reviewer | 검증 행이 잘못되었음을 표시합니다 |

읽기 도구(증명과 부여가 필요하지 않습니다. 1.x의 원시 SQL 도구를 대체하는 정형 조회입니다).

| 도구 | 내용 |
|---|---|
| `ledger_get` | 여러 ID(결함, 작업, 조건을 섞어도 됩니다)의 상태와 판을 한 번에 봅니다. 쓰기 뒤 확인과 `expected_version` 확보에 씁니다 |
| `ledger_defect` | 결함 하나와 그 수정, 작업, 조건, 사례(병합된 결함의 사례 포함), 관계, `fixed_by`, 최근 변경을 보여 줍니다 |
| `ledger_task` | 작업 하나와 목적, 성공 조건, 출처, 담당, 완료를 보고한 세션, 결함, 조건을 보여 줍니다 |
| `ledger_history` | 이력과 모든 쓰기의 영수증을 ID, ID 범위, 대상, 프로젝트, 연산, 작성자, 기간으로 조회합니다. `include_before`를 주면 `before_data`를 더합니다 |
| `ledger_search` | 키워드로 결함, 작업, 조건을 대소문자 구분 없이 검색합니다. `match`는 `ANY`(기본) 또는 `ALL`입니다 |
| `ledger_defects` | 프로젝트의 결함을 상태, 심각도, 분류, 주의 종류, 담당으로 거릅니다 |
| `ledger_tasks` | 프로젝트의 작업을 상태, 담당, 주의 종류로 거릅니다 |
| `ledger_conditions` | 프로젝트의 릴리스 조건을 상태와 릴리스로 거릅니다 |
| `ledger_attention` | 확인이 필요한 항목입니다: `OPEN_WITH_FIX`, `FIXED_WITHOUT_COMMIT`, `OPEN_NO_OWNER`, `TASK_UNKNOWN`, `DONE_NOT_VERIFIED` |
| `ledger_stats` | 한 프로젝트 또는 전체의 상태별, 심각도별 건수입니다. `bucket`을 `day`로 주면 UTC 일별 신규 건수를 더합니다 |
| `ledger_projects` | 프로젝트의 ID, 코드, 제목입니다 |
| `ledger_codes` | 공통코드입니다. 폐기한 코드는 `include_inactive`를 주어야 나옵니다 |

목록형 읽기 도구는 `max_rows`(1에서 1000, 기본 50)와 `cursor`를 받으며, 응답에 `truncated`와 `next_cursor`가 붙습니다.
1.x의 `execute_sql_reader`와 `search_objects_reader` 도구는 제거되었으므로 위의 읽기 도구를 사용합니다.

공통코드가 바뀌면 서버가 `notifications/tools/list_changed`를 보내므로, 연결된 클라이언트는 재시작 없이 도구 정의를 다시 읽습니다. 다른 프로세스(관리 웹, `ledger-cli`)가
바꾼 내용은 `ledger.mcp.code-poll-interval` 안에 감지합니다. 새 도구는 서버가 시작될 때 등록됩니다.

`ledger_record_case`는 `defect_id`, `observation`(1자에서 4000자), `reason`을 받고, 선택으로 `evidence`, `user_quote`, `observed_at`(`Z` 또는 오프셋이 붙은 ISO-8601)을 받습니다.
`ledger_batch`에서는 같은 인자를 연산 `{"op": "record_case", ...}`에 넣습니다.

```json
{"defect_id": "WEB-D-0007", "observation": "checkout returns 500 for a gift card",
 "evidence": "request 7f3a, api/checkout.ts:88", "user_quote": "I can't pay with my gift card",
 "observed_at": "2026-11-05T10:00:00Z", "reason": "second report of the same failure"}
```

## 모듈

빌드는 Gradle 다중 모듈 프로젝트입니다.

| 모듈 | 내용 |
|---|---|
| `ledger-domain` | 집합체, 값 타입, 상태 규칙, 오류 코드와 도구 이름 목록인 `ToolContract` |
| `ledger-attestation` | `_hostAttestation` 토큰, RFC 8785 정규형 JSON, Ed25519 키 형식입니다. 훅과 서버가 함께 씁니다(JDK만 사용) |
| `ledger-database` | Spring Data JDBC 저장소, 스키마 파일 `V{n}__*.sql`과 그 적용기, 등록된 JDBC 드라이버의 적재기 |
| `ledger-application` | 응용 서비스: 쓰기 관문, 영수증, 이력, 쓰기와 읽기 연산, 부여, 세션 키, 기존 데이터 가져오기 |
| `ledger-mcp-server` | Spring Boot MCP 서버(`ledger-mcp-server.jar`) |
| `ledger-admin-web` | 소유자용 Spring Boot 관리 웹(`ledger-admin-web.jar`) |
| `ledger-cli` | 소유자용 명령줄 도구(`ledger-cli.jar`) |
| `ledger-client-hook` | 모든 클라이언트가 함께 쓰는 훅 jar(`ledger-client-hook.jar`) |

## 설정

설정은 Spring Boot 속성입니다. 파일, 명령줄(`--name=value`), 비밀 저장소로 줄 수 있습니다. 비밀은 파일이나 환경 변수에 넣지 않습니다.
`ledger-cli secret-set <name>`은 숨김 입력으로 비밀을 받아 비밀 디렉터리의 파일 하나로 저장하며, 프로세스들은 이 디렉터리를 Spring 설정 트리로 읽습니다.
비밀은 그 속성 이름으로 저장합니다. 저장된 비밀을 출력하는 명령은 없습니다.

| 키 | 사용처 | 의미 |
|---|---|---|
| `ledger.jdbc.url` | 전체 | 장부 데이터베이스의 JDBC URL |
| `ledger.jdbc.username` | 전체 | 데이터베이스 계정 |
| `ledger.jdbc.password` | 전체 | 데이터베이스 비밀번호(비밀) |
| `ledger.jdbc.pool-size` | 전체 | 연결 풀 크기, 기본 10 |
| `ledger.jdbc.driver` | 전체 | `driver-register`가 기록한 등록된 드라이버(jar, 클래스, SHA-256)이며 시작할 때 읽습니다 |
| `ledger.schema.name` | 전체 | 표를 담는 스키마. 없으면 `migrate`가 만들고, 지정하지 않으면 계정의 기본 스키마입니다 |
| `ledger.schema.long-text-type` | 전체 | 스키마 파일의 긴 텍스트 타입이며 DDL에서 데이터베이스마다 다른 유일한 부분입니다. 기본 `TEXT` |
| `ledger.schema.require-utc` | 서버, CLI | JVM이나 데이터베이스 세션이 UTC가 아니면 시작을 거부합니다. 기본 true |
| `ledger.attestation.window` | 서버 | 증명의 시각이 서버 시각과 벌어질 수 있는 한도, 기본 `PT5M` |
| `ledger.mcp.token` | MCP 서버, `mcp-check` | 에이전트의 bearer 토큰, 16자 이상(비밀) |
| `ledger.mcp.allowed-hosts` | MCP 서버 | 허용하는 `Host` 값, 기본 `127.0.0.1`과 `localhost` |
| `ledger.mcp.allowed-origins` | MCP 서버 | 허용하는 브라우저 출처, 기본 없음 |
| `ledger.mcp.max-request-size` | MCP 서버 | 요청 본문의 최대 크기, 기본 1MB |
| `ledger.mcp.code-poll-interval` | MCP 서버 | 다른 프로세스의 코드 변경을 확인하는 주기, 기본 5s |
| `ledger.admin.token` | 관리 웹 | 소유자의 관리 토큰, 16자 이상(비밀) |
| `ledger.admin.agent-token` | 관리 웹 | 필수: 에이전트의 bearer 토큰으로 `ledger.mcp.token`과 같은 값이어야 하고 `ledger.admin.token`과는 달라야 합니다. 관리 웹은 이 토큰을 `AGENT_TOKEN_FORBIDDEN`으로 거절합니다(비밀) |
| `ledger.admin.allowed-hosts` | 관리 웹 | 허용하는 `Host` 값(DNS 리바인딩 방어), 기본 `127.0.0.1`과 `localhost` |
| `ledger.admin.driver-file` | 관리 웹 | 드라이버 화면이 기록하는 드라이버 등록 파일, 기본 `/etc/pg-ledger-mcp/jdbc-driver.yaml` |
| `ledger.admin.driver-directory` | 관리 웹 | 드라이버 jar를 가져올 수 있는 유일한 디렉터리, 기본 `/etc/pg-ledger-mcp/drivers` |
| `ledger.admin.oidc.owners` | 관리 웹 | OpenID Connect로 로그인할 수 있는 사람: 발급자와 subject 또는 이메일의 목록이며 공급자 자체는 `spring.security.oauth2.client.*`입니다 |
| `ledger.admin.lockout.max-failures` | 관리 웹 | 한 클라이언트가 잠기기 전의 토큰 실패 횟수, 기본 5 |
| `ledger.admin.lockout.window` | 관리 웹 | 실패를 세는 기간, 기본 60s |
| `ledger.admin.lockout.lock-period` | 관리 웹 | 클라이언트가 잠겨 있는 시간, 기본 60s |
| `ledger.admin.lockout.global-max-failures` | 관리 웹 | 전체 클라이언트의 실패가 이 횟수를 넘으면 전역 지연이 시작되는 한도, 기본 50 |
| `ledger.admin.lockout.global-delay` | 관리 웹 | 전역 한도를 넘은 뒤 토큰을 비교하기 전에 기다리는 시간, 기본 1s. 늦출 뿐 잠그지는 않습니다 |
| `ledger.admin.mcp.health-url` | 관리 웹 | 개요 화면에 보여 줄 MCP 서버의 `/healthz` URL. 없으면 설정하지 않은 것입니다 |
| `ledger.admin.mcp.timeout` | 관리 웹 | 그 확인의 제한 시간, 기본 2s |
| `ledger.import.source.url` | `import-legacy` | 1.x 장부가 있는 데이터베이스의 JDBC URL |
| `ledger.import.source.username` | `import-legacy` | 그 데이터베이스의 읽기 전용 계정 |
| `ledger.import.source.password` | `import-legacy` | 그 비밀번호(비밀) |
| `ledger.import.source.schema` | `import-legacy` | 1.x 스키마, 기본 `ledger` |
| `ledger.secrets.directory` | `ledger-cli` | 비밀 디렉터리, 기본 `/etc/pg-ledger-mcp/secrets` |
| `ledger.secrets.instance` | `ledger-cli` | 기본 경로가 따르는 인스턴스 이름, 기본 `pg-ledger-mcp` |
| `ledger.cli.driver.directory` | `ledger-cli` | `driver-register`가 jar를 가져오는 디렉터리, 기본 `/etc/pg-ledger-mcp/drivers` |
| `ledger.cli.driver.file` | `ledger-cli` | `driver-register`가 기록하는 등록 파일, 기본 `/etc/pg-ledger-mcp/jdbc-driver.yaml` |

서버는 루프백에서만 수신합니다(MCP `127.0.0.1:8765`, 관리 웹 `127.0.0.1:8865`). 외부에 공개하려면 HTTPS를 종단하는 역방향 프록시를 앞에 두고
그 공개 호스트 이름을 허용 호스트에 적습니다. 관리 웹은 루프백 주소에서 온 `X-Forwarded-For`와 `X-Forwarded-Proto`만 신뢰합니다.
서버는 드라이버 등록 파일과 비밀 디렉터리를 `spring.config.import`로 읽으며 이는 실행 명령에 포함됩니다(설치 참조). `ledger-cli`는 기본으로 읽습니다.

## 설치

설치기는 계획 중이며 이번 릴리스에 포함되지 않습니다. 아래는 수동 절차입니다. 빌드에는 JDK 25가 필요합니다.

1. jar를 빌드합니다: `./gradlew build`. jar는 `ledger-cli/build/libs/ledger-cli.jar`, `ledger-mcp-server/build/libs/ledger-mcp-server.jar`,
   `ledger-admin-web/build/libs/ledger-admin-web.jar`, `ledger-client-hook/build/libs/ledger-client-hook.jar`이며, 클라이언트별 플러그인 디렉터리는
   `ledger-client-hook/build/client-plugins/<client>`입니다. 이를 호스트에 배치하며, 그 호스트에는 Java 25 런타임이 필요합니다.
2. 데이터베이스를 준비합니다. 비어 있는 데이터베이스와 장부용 계정이 필요하며 세션 시간대는 UTC여야 합니다. 애플리케이션 계정용 최소 권한 부여 템플릿은
   선택 사항이며 `ledger-database/src/main/resources/db-templates/dml-grants.sql`에 있습니다.
3. 그 데이터베이스의 JDBC 드라이버는 직접 구해 드라이버 디렉터리에 넣습니다. 제품에 동봉되어 있지 않습니다. 데이터베이스 URL과 함께 등록합니다(드라이버 클래스가 그 URL을 받아들여야 합니다).

```sh
java -jar ledger-cli.jar driver-register --jar=/etc/pg-ledger-mcp/drivers/<driver>.jar --class=<driver-class> --ledger.jdbc.url=<jdbc-url>
```

4. 서비스를 실행할 사용자로 비밀을 각각 숨김 입력으로 저장합니다.

```sh
java -jar ledger-cli.jar secret-set ledger.jdbc.password
java -jar ledger-cli.jar secret-set ledger.mcp.token
java -jar ledger-cli.jar secret-set ledger.admin.token
java -jar ledger-cli.jar secret-set ledger.admin.agent-token
```

5. 스키마를 만듭니다. `migrate`는 파일 `V{n}__*.sql`을 순서대로 적용하고, 시도마다 파일의 SHA-256과 함께 `schema_history` 표에 기록합니다.
   적용한 뒤에 바뀐 파일이 있으면 모든 프로세스가 시작할 때 멈춥니다.

```sh
java -jar ledger-cli.jar migrate --ledger.jdbc.url=<jdbc-url> --ledger.jdbc.username=<account>
```

6. 소유자 쪽 설정을 합니다. `project-add`, `code-set`을 실행하고, 1.x에서 온 경우에는 `import-legacy`를 실행합니다(1.x에서 이전 참조).
7. MCP 서버와 관리 웹을 같은 설정(url, 계정, 허용 호스트)으로 시작하며, 드라이버 등록과 비밀을 설정 출처로 지정합니다.

```sh
java -Duser.timezone=UTC -jar ledger-mcp-server.jar --spring.config.import=optional:file:/etc/pg-ledger-mcp/jdbc-driver.yaml,optional:configtree:/etc/pg-ledger-mcp/secrets/ --ledger.jdbc.url=<jdbc-url> --ledger.jdbc.username=<account>
java -Duser.timezone=UTC -jar ledger-admin-web.jar --spring.config.import=optional:file:/etc/pg-ledger-mcp/jdbc-driver.yaml,optional:configtree:/etc/pg-ledger-mcp/secrets/ --ledger.jdbc.url=<jdbc-url> --ledger.jdbc.username=<account>
```

   등록된 드라이버가 없으면 MCP 서버는 이유를 밝히고 멈춥니다. 관리 웹은 로그인과 드라이버 화면만 제공하는 설정 모드로 시작합니다.
   `ledger-cli mcp-check`는 실행 중인 MCP 서버에 연결해 계약의 모든 도구가 목록에 있는지 확인합니다.
8. 클라이언트 훅을 설치하고(클라이언트 훅 참조), 세션마다 `key-register`로 키를 등록한 뒤 `grant`로 부여합니다.

## ledger-cli

`java -jar ledger-cli.jar <command> [arguments]`. 옵션은 `--name=value` 형태로 씁니다. Spring 속성(`--ledger.jdbc.url=...`처럼 점이 있는 이름)도 같은 방식으로 전달합니다.
이 도구는 비밀 디렉터리를 소유한 사용자로 실행하며, 모든 소유자 작업은 그 사용자 이름으로 기록됩니다.

| 명령 | 구문 | 내용 |
|---|---|---|
| `secret-set` | `secret-set <name>` | 숨김 입력으로 비밀을 저장합니다 |
| `driver-register` | `driver-register --jar=<path> --class=<driver-class>` | JDBC 드라이버를 등록합니다. 데이터베이스가 필요하지 않습니다 |
| `migrate` | `migrate` | 아직 적용하지 않은 스키마 파일을 적용합니다 |
| `import-legacy` | `import-legacy [--verify-only]` | 1.x 장부를 새 스키마로 복사하고 모든 표를 대조합니다 |
| `key-register` | `key-register <author> <public-key-or-block> <fingerprint> [--replace=<current-fingerprint>]` | 세션이 출력한 공개 키를 작성자에 묶습니다. 유효한 키는 `--replace`가 있어야 교체합니다 |
| `key-revoke` | `key-revoke <author> --fingerprint=<fingerprint>` | 작성자의 유효한 키를 닫습니다 |
| `project-add` | `project-add <id> <code> <title>` | 프로젝트를 추가합니다 |
| `code-set` | `code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>] [--inactive]` | 공통코드를 추가하거나 수정합니다 |
| `codes` | `codes [<group>]` | 공통코드를 나열합니다 |
| `grant` | `grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]` | 프로젝트의 범위를 부여합니다. 작성자에게 먼저 유효한 세션 키가 있어야 합니다 |
| `revoke` | `revoke <author> [<project> [<scope>]]` | 유효한 부여를 회수합니다 |
| `grants` | `grants [<author>]` | 유효한 부여를 나열합니다 |
| `mcp-check` | `mcp-check [--url=<url>] [--ledger.mcp.token=<token>]` | 실행 중인 MCP 서버에 연결해 도구를 확인합니다 |

## 관리 웹

관리 웹(`ledger-admin-web.jar`)은 소유자를 위한 서버 렌더링 콘솔이며 기본 주소는 `127.0.0.1:8865`입니다. 일반 사용자로 실행하며 root 권한이 필요하지 않습니다.

- **로그인.** 관리 토큰 `ledger.admin.token`, 또는 `ledger.admin.oidc.owners`에 적힌 사람의 OpenID Connect입니다. 에이전트의 토큰은 거절되므로 MCP 토큰을 가진 에이전트가
  스스로 부여를 줄 수 없습니다. 실패한 시도는 해당 클라이언트를 잠그고 모든 클라이언트를 늦춥니다(`ledger.admin.lockout.*`).
- **브라우저 보안.** Spring Security가 CSRF 토큰, 기본으로 `Secure`인 `SameSite=Strict` 세션 쿠키, `Host` 허용 목록, 콘텐츠 보안 정책을 제공합니다.
- **화면.** 개요(주의 항목 건수와 MCP 서버 상태), 프로젝트, 결함, 작업, 조건, 이력(읽기 전용), 공통코드, 세션 키가 있는 부여, JDBC 드라이버 화면입니다.
  시각은 UTC로 보내며 브라우저의 지역 시각으로 표시합니다.
- **부여.** 소유자가 세션이 출력한 공개 키를 붙여 넣고, 화면에 보이는 지문을 세션이 보여 준 지문과 대조한 뒤에야 키가 등록되고 부여가 발급됩니다.
  유효한 키는 바로 그 키를 명시적으로 확인해야 교체됩니다.

## 클라이언트 훅

실행 jar 하나 `ledger-client-hook.jar`가 모든 클라이언트를 처리하며, 첫 인자로 클라이언트를 고릅니다:
`java -jar ledger-client-hook.jar claude`, `codex`, `antigravity`. 플러그인 디렉터리는 `clients/claude`, `clients/codex`, `clients/antigravity`에 있습니다(각 README 참조).
훅에는 클라이언트 호스트의 `PATH`에 Java 25 런타임이 필요하며, 훅이 없거나 실패하면 쓰기는 `HOST_ATTESTATION_REQUIRED`로 거절됩니다.

훅은 쓰기 도구 10종에만 서명하며, 도구 이름은 `mcp__<server>__ledger_<op>` 또는 `mcp__<server>__<server>_ledger_<op>`입니다
(Codex Apps는 `mcp__codex_apps__app_<app>_ledger_<op>`). `_hostAttestation`의 값은
`pgla2.<author>.<tool>.<request id>.<arguments digest>.<issued at>.<signature>`이며, 작성자, 도구, 요청 ID, 인자의 RFC 8785 정규형(`_hostAttestation` 제외, 배치는 모든 연산과 그 내용, 순서, 요청 ID)의
SHA-256, 시각에 대한 Ed25519 서명입니다. 가로챈 토큰으로 다른 내용을 보낼 수 없습니다. `pgla1` 토큰은 `ATTESTATION_VERSION_UNSUPPORTED`로 거절됩니다.

- 훅은 MCP 서버 이름과 작성자마다 Ed25519 키 하나를 소유자 전용 폴더에 보관합니다. 기본은 `~/.pg-ledger-mcp/session-keys`이며, `LEDGER_HOOK_KEY_DIR`에 절대 경로를 주어 바꿀 수 있습니다.
  한 서버용 서명은 다른 서버에서 검증되지 않습니다. Codex Apps의 서버 이름은 `codex_apps.<app>`입니다.
- `java -jar ledger-client-hook.jar session-key <server> <author>`는 서버, 작성자, 공개 키, 지문을 출력합니다.
  소유자는 지문을 대조한 뒤 그 서버에서 `ledger-cli key-register` 또는 부여 화면으로 키를 등록합니다.
- 서버는 부여를 보기 전에 서명, 도구, 요청 ID, 인자, 시각(`ledger.attestation.window`)을 검증합니다. 키가 없는 경우와 서명이 틀린 경우는 같은 거절인 `ATTESTATION_SIGNATURE_INVALID`이고,
  내용이 다르면 `ATTESTATION_BINDING_MISMATCH`입니다.
- 한계: 같은 운영체제 사용자의 다른 프로세스는 키 파일을 읽을 수 있으므로 한 사용자 안의 격리에는 별도 계정이 필요합니다. 상승된 권한으로 실행한 훅(키 폴더의 소유자가 달라집니다)은 거절됩니다.

## 1.x에서 이전

1.x는 PostgreSQL 함수 위에서 동작하던 DBHub와 Node 구현이었고, 2.0.0은 그 전부를 대체합니다. 기존 데이터는 제자리에서 변환하지 않고 옮깁니다.
1.x 스키마는 그대로 남으므로 되돌릴 때 쓸 수 있습니다. 계약 변경은 [릴리스 노트](docs/release-notes/2.0.0.md)를 참조합니다.

1. 위와 같이 드라이버를 등록하고, 비밀을 저장하고, `migrate`를 실행합니다. 새 스키마를 1.x 장부와 같은 데이터베이스에 둘 때는 `ledger.schema.name`을
   1.x 스키마 `ledger`가 아닌 스키마로 지정합니다.
2. 1.x 데이터베이스의 읽기 전용 계정 비밀번호를 `secret-set ledger.import.source.password`로 저장하고,
   `import-legacy --ledger.import.source.url=<jdbc-url> --ledger.import.source.username=<account>`를 실행합니다.
3. 가져오기는 1.x 스키마를 읽기 전용으로 읽어 모든 행을 한 트랜잭션으로 복사하고, 하나라도 실패하면 되돌립니다. 이어서 모든 표의 행 수와 SHA-256 다이제스트를 대조합니다
   (`--verify-only`는 대조만 다시 합니다). 새 스키마에 이미 데이터가 있으면 거부하며, 옛 데이터 중 새 스키마가 담을 수 없는 값은 모두 나열하고 값을 지어내지 않습니다.
4. 옛 부여는 세션 키 없이 복사됩니다. 모든 작성자는 `key-register`로 세션 키를 등록해야 하며, 그 뒤에 부여가 동작합니다.
5. 클라이언트: `execute_sql_reader`와 `search_objects_reader` 도구는 제거되었으며 읽기 도구가 대체합니다. 쓰기 도구의 결과는 `rows[0].result` 감싸기 없이 키를 그대로 유지합니다.
   서버를 전환하기 전에 훅 jar(Java 25 런타임)를 설치합니다.

## 빌드와 시험

JDK 25와 Gradle 래퍼만 있으면 되며, 래퍼가 Gradle을 직접 내려받습니다.

```sh
./gradlew build
```

빌드는 경고를 오류로 보고 컴파일하며, 모든 모듈의 시험을 내장 데이터베이스에서 실행하고(외부 데이터베이스가 필요하지 않습니다), JDBC 드라이버, JPA, 마이그레이션 도구, Lombok이
런타임 클래스패스에 없는지 확인합니다. `ledger-cli`의 문서 시험(DOC-001부터 DOC-029까지, [docs/testing/TEST_CASES.md](docs/testing/TEST_CASES.md))은 이 README, 영문 README,
릴리스 노트를 코드와 대조합니다.

## 구성

- `ledger-domain`, `ledger-attestation`, `ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`,
  `ledger-cli`, `ledger-client-hook`: 위의 모듈이며 각각 `src/main`과 `src/test`를 가집니다.
- `clients/claude`, `clients/codex`, `clients/antigravity`: 클라이언트별 플러그인의 매니페스트와 README이며, 빌드가 훅 jar와 함께
  `ledger-client-hook/build/client-plugins`에 모읍니다.
- `docs/`: 시험 사례 목록, 릴리스 노트 형식, 릴리스 노트.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: 빌드.
- `bin/`, `web/`, `mcp/`, `sql/`, `tests/*.mjs`, `deploy/*.sh`: 1.x 구현(DBHub, Node, PostgreSQL 함수)이며 이후 단계에서 제거할 때까지 저장소에 남겨 둡니다. 2.x의 일부가 아닙니다.

## 라이선스

MIT
