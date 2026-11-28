# 개발과 시험

[English](development.md) · [가이드 목차](guide.ko.md)

## 빌드와 시험

JDK 25와 Gradle 래퍼만 있으면 되며, 래퍼가 Gradle을 직접 내려받습니다.

```sh
./gradlew build
```

빌드는 경고를 오류로 보고 컴파일하며, 모든 모듈의 시험을 그 컴퓨터에 설치된 PostgreSQL에서 실행하고(아래 시험 데이터베이스 참조, 내장 데이터베이스는 없으며 빌드가 데이터베이스를
띄우지도 않습니다), JDBC 드라이버, JPA, 마이그레이션 도구, Lombok이
런타임 클래스패스에 없는지 확인합니다. `ledger-cli`의 문서 시험(DOC-001부터 DOC-029까지, [docs/testing/TEST_CASES.md](testing/TEST_CASES.md))은 이 README, 영문 README,
릴리스 노트를 코드와 대조합니다.

`ledger-system-test`의 시스템 시험(SYS-001부터 SYS-012까지)에도 같은 로컬 PostgreSQL과 빌드된 jar가 필요합니다. 이 시험은 설치 명령, `ledger-cli.jar`,
`ledger-mcp-server.jar`, 훅(가벼운 클라이언트와 상주 프로그램)을 각각 별도의 Java 프로세스로 시험 데이터베이스에 대해 실행하므로, 빌드가 시험 전에 jar를 먼저 만듭니다.
`./gradlew build`에 포함되며, `./gradlew :ledger-system-test:test`로 단독 실행할 수 있습니다.

### 시험 데이터베이스

모듈 시험에는 이미 설치되어 실행 중인 PostgreSQL 18 이상이 필요합니다. 기본값으로 `127.0.0.1:55432`의 `ledger_test` 데이터베이스에 그 데이터베이스의 소유자 계정으로 접속하며,
두 번째 로그인인 최소 권한 응용 계정은 계정 권한을 다루는 시험이 사용합니다. 각 시험은 자기만의 스키마를 만들어 쓰고 끝나면 삭제하므로 그 데이터베이스 밖에는 아무것도 남지 않으며,
시험 JVM 하나는 연결을 최대 10개까지만 유지합니다. 데이터베이스에 접속할 수 없는 시험은 접속 오류로 실패하며, 건너뛰지도 않고 다른 데이터베이스로 대체하지도 않습니다.

접속 정보는 명령줄 또는 `gradle.properties`의 Gradle 속성으로 지정합니다. 아래 JDBC URL과 계정 이름은 기본값이며, 두 비밀번호 파일 속성에는 저장소 기본값이 없습니다(`build.gradle.kts`에 특정 기기 경로를 하드코딩하지 않습니다).

```sh
./gradlew test \
  -Pledger.test.jdbc-url=jdbc:postgresql://127.0.0.1:55432/ledger_test \
  -Pledger.test.jdbc-user=ledger_test \
  -Pledger.test.app-user=ledger_test_app \
  -Pledger.test.jdbc-password-file=<owner-password-file> \
  -Pledger.test.app-password-file=<app-password-file>
```

비밀번호는 속성 값으로 주지 않습니다. 두 비밀번호 파일 속성은 첫 줄이 비밀번호인 파일의 경로이며 시험 JVM만 그 파일을 읽습니다. 속성을 지정하지 않으면 드라이버에 비밀번호를 주지 않으므로
PostgreSQL JDBC 드라이버가 사용자의 비밀번호 파일(Windows는 `pgpass.conf`, Linux는 `.pgpass`)을 읽습니다. 비밀번호는 코드, 설정, 로그, 커밋 메시지에 적지 않습니다.

### 자원이 작은 기계에서 시험 JVM을 제한하는 선택 속성

시험 JVM은 기본적으로 제한하지 않습니다. 자원이 작은 기계에서는 다음 Gradle 선택 속성으로 동시에 실행하는 시험 JVM의 수, JVM별 최대 힙 크기,
JVM이 인식하는 프로세서 수를 제한할 수 있습니다. 속성은 명령줄로만 넘기며 저장소에는 설정하지 않으므로, 이 저장소를 쓰는 다른 사용자에게 영향이 없습니다.
숫자가 아니거나 1보다 작은 값을 주면 빌드가 설정 단계에서 실패합니다.

```sh
./gradlew test -Pledger.test.forks=1 -Pledger.test.maxHeapSize=1g -Pledger.test.activeProcessorCount=1
```

### 빌드 속도

`gradle.properties`는 Gradle 구성 캐시, 빌드 캐시, 병렬 실행을 켭니다(`org.gradle.configuration-cache`, `org.gradle.caching`,
`org.gradle.parallel`). Gradle 데몬과 파일 감시는 기본값을 그대로 씁니다. 아무것도 바꾸지 않고 `./gradlew build`를 두 번째로 실행하면
구성을 다시 쓰고, 입력이 바뀐 태스크만 실행합니다. 시험 데이터베이스를 쓰는 모듈(`ledger-database`, `ledger-application`, `ledger-mcp-server`,
`ledger-admin-web`, `ledger-cli`, `ledger-system-test`)의 시험은 예외입니다. 데이터베이스의 상태는 Gradle이 볼 수 없는 입력이므로 이 시험은
매번 실행하며, 빌드 캐시에서 가져오지도 않고 캐시에 저장하지도 않습니다. 병렬 실행에서도 이 시험은 하나씩 차례로 실행합니다. 시험 데이터베이스 역할에는 연결 수 제한이 있어,
시험 JVM 여러 개가 동시에 연결하면 그 제한을 넘기 때문입니다. 다른 모듈의 시험은 캐시에서 가져올 수 있습니다. 기계의 제한(워커 수,
시험 JVM, 힙, 프로세서)은 저장소 설정이 아니므로, 위 속성과 함께 예를 들어 `--max-workers=1`처럼 명령 인자로 줍니다.

## 구성

- `ledger-domain`, `ledger-attestation`, `ledger-files`, `ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`,
  `ledger-cli`, `ledger-client-hook`, `ledger-migrate-1x`: 위의 모듈이며 각각 `src/main`과 `src/test`를 가집니다. `ledger-system-test`와 `ledger-arch-checks`는 `src/test`만 가집니다.
- `clients/claude`, `clients/codex`, `clients/antigravity`: 클라이언트별 플러그인의 매니페스트와 README이며, 빌드가 훅의 두 jar와 함께
  `ledger-client-hook/build/client-plugins`에 모읍니다.
- `docs/`: 시험 사례 목록, 릴리스 노트 형식, 릴리스 노트.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: 빌드.
- `deploy/`: 설치기 두 개, `install.sh`(Linux)와 `install.cmd`(Windows)입니다.
- 1.x 구현(DBHub, Node, PostgreSQL 함수)은 2.x 이상의 일부가 아니며 더 이상 저장소에 없습니다. 소스는 태그 `v1.1.0`에서 볼 수 있습니다.
