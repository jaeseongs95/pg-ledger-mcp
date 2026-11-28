# Development and testing

[한국어](development.ko.md) · [Guide index](guide.md)

## Build and test

JDK 25 and the Gradle wrapper are all that is needed; the wrapper downloads Gradle itself.

```sh
./gradlew build
```

The build compiles with warnings as errors, runs every module test against the PostgreSQL that is installed on the machine
(see the test database below; there is no embedded database and the build does not start one) and checks that no JDBC driver, JPA,
migration tool or Lombok is on a runtime classpath. The documentation tests of
`ledger-cli` (DOC-001 to DOC-029 in [docs/testing/TEST_CASES.md](testing/TEST_CASES.md)) check this README, the Korean one and the
release notes against the code.

The system test of `ledger-system-test` (SYS-001 to SYS-012) needs that same local PostgreSQL and the built jars: it runs the install
commands, `ledger-cli.jar`, `ledger-mcp-server.jar` and the hook jar as separate Java processes against the test database, so the build
makes the jars before it runs the test. It is part of `./gradlew build`; `./gradlew :ledger-system-test:test` runs it alone.

### The test database

The module tests need a PostgreSQL 18 or later that is already installed and running. By default they connect to
`127.0.0.1:55432`, database `ledger_test`, as the owner of that database; a second login, the least-privilege application account,
is used by the tests of the account rights. Each test works in a schema of its own that it creates and drops, so nothing remains
except inside that database, and one test JVM holds at most 10 connections. A test that cannot reach the database fails with a
connection error; it is not skipped and does not fall back to another database.

The connection is set with Gradle properties, on the command line or in `gradle.properties`. The JDBC URL and account names below are
the defaults; the two password file properties have no repository default (no machine path is hardcoded in `build.gradle.kts`):

```sh
./gradlew test \
  -Pledger.test.jdbc-url=jdbc:postgresql://127.0.0.1:55432/ledger_test \
  -Pledger.test.jdbc-user=ledger_test \
  -Pledger.test.app-user=ledger_test_app \
  -Pledger.test.jdbc-password-file=<owner-password-file> \
  -Pledger.test.app-password-file=<app-password-file>
```

A password is never a property value: the two password file properties name a file whose first line is the password, and only the
test JVM reads it. When a property is not set, no password is given to the driver and the PostgreSQL JDBC driver reads the pass
file of the user (`pgpass.conf` on Windows, `.pgpass` on Linux). A password is never written to code, configuration, logs or
commit messages.

### Optional properties for machines with few resources

The test JVMs are not limited by default. On a machine with few resources, the following optional Gradle properties limit them:
the number of test JVMs run at the same time, the maximum heap size of each, and the number of processors each reports. They are
passed on the command line and are not set in the repository, so they do not affect other users of it. A value that is not a
number, or is smaller than 1, fails the build at configuration time.

```sh
./gradlew test -Pledger.test.forks=1 -Pledger.test.maxHeapSize=1g -Pledger.test.activeProcessorCount=1
```

### Build speed

`gradle.properties` switches on the Gradle configuration cache, the build cache and parallel execution (`org.gradle.configuration-cache`,
`org.gradle.caching`, `org.gradle.parallel`); the Gradle daemon and file watching keep their defaults. A second `./gradlew build` with
nothing changed reuses the configuration and runs only the tasks whose inputs changed. The tests of the modules that use the test
database (`ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`, `ledger-cli` and `ledger-system-test`) are the
exception: the state of the database is an input that Gradle cannot see, so they run every time and are never taken from the build
cache or stored in it. They also run one after the other, even with parallel execution: the test database role has a connection
limit that several test JVMs at once would exceed. The tests of the other modules can be taken from the cache. The limits of a machine (the number of workers, the
test JVMs, the heap, the processors) are not repository settings: give them as command arguments, for example `--max-workers=1`
together with the properties above.

## Layout

- `ledger-domain`, `ledger-attestation`, `ledger-files`, `ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`,
  `ledger-cli`, `ledger-client-hook`, `ledger-migrate-1x`: the modules above, each with `src/main` and `src/test`; `ledger-system-test` and `ledger-arch-checks` have `src/test` only.
- `clients/claude`, `clients/codex`, `clients/antigravity`: the manifests and README of each client plugin; the build stages them with
  the hook jar into `ledger-client-hook/build/client-plugins`, with both jars of the hook.
- `docs/`: the test case catalogue, the release note format and the release notes.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: the build.
- `deploy/`: the two installers, `install.sh` (Linux) and `install.cmd` (Windows).
- The 1.x implementation (DBHub, Node and PostgreSQL functions) is not part of 2.x or later and is no longer in the tree. Its source is available at the tag `v1.1.0`.
