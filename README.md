# pg-ledger-mcp

[한국어](README.ko.md)

A ledger for work done by AI agents, served as one MCP endpoint. It records defects, tasks, fixes and their verification,
together with who did each one and why. Agents read and write through MCP tools; they never get a database login of their own.

Version 2.0.0 is a rewrite in Java 25 and Spring Boot. It keeps the contract that clients rely on (the names and input keys
of the write tools, the keys of the results, the `_hostAttestation` key) and changes what is stated in the
[release notes](docs/release-notes/2.0.0.md); the format of release notes is in [docs/RELEASE_NOTES_FORMAT.md](docs/RELEASE_NOTES_FORMAT.md).

## Contents

- Concepts
- MCP tools
- Modules
- Configuration
- Installation
- ledger-cli
- Admin web
- Client hooks
- Migrating from 1.x
- Build and test
- Layout

## Concepts

- **Author identity is proved, not claimed.** Every write and every `ledger_whoami` carries the reserved key `_hostAttestation`,
  which the host hook of the client fills in. Models must omit it. The author is `<vendor>:<session-id>`, for example
  `claude:1f2e3d4c` or `codex:9a8b7c6d`; the owner of the ledger is `user:owner`. A legacy `author_id` is accepted and ignored.
- **Writes need a live grant.** The owner grants an author a scope on a project with `ledger-cli grant` (or on the grants page of
  the admin web). The scopes are `defect`, `task`, `fix` and `verify`. A write without a matching grant is refused.
- **Verification is separate.** `VERIFIED`, `MET` and `NOT_MET` are set only through the reviewer tools `ledger_review` and
  `ledger_review_correct`, which need the scope `verify`. A defect is verified only with an evidence link and at least one
  recorded fix from another session than the verifier; the verification and rework requests go to the session that recorded
  the fix (`fixed_by`), not to the one that registered the defect (`created_by`). CI results are optional metadata.
- **History is append-only.** Every write adds a history row: when, author, channel, operation, state change and reason. The
  application has no path that updates or deletes a history row, and the optional grant template of the database module gives the
  application account no `UPDATE` or `DELETE` on it. A mistake is marked with a `CORRECT` row. An operation that changes an
  existing row also stores the full row as it was before the change (`before_data`, returned by `ledger_history` with
  `include_before`).
- **Retries are safe.** Pass `request_id`, a UUID. Repeating the same request returns the stored result and writes nothing twice.
  A receipt that was imported from the 1.x ledger answers `REQUEST_ID_LEGACY`: use a new request id.
- **Optimistic locking.** Edits and transitions carry `expected_version`; a stale version is `VERSION_CONFLICT`.
- **Projects and ids.** A defect belongs to a project and gets the id `<CODE>-D-0001`, `<CODE>-D-0002`, and so on. A task without
  an id gets `<CODE>-T-0001`, and so on. The server assigns them.
- **One defect, one root cause, many observed cases.** Each observation of a defect (symptom, log, user report) is a case under it
  (`ledger_record_case`). A merged defect (`MERGED`, `merged_into`) keeps its cases; `ledger_defect` shows them under the defect it
  was merged into, and a `MERGED` defect takes no new cases.
- **Common codes.** Defect category, defect severity and relation kind are the three groups `DEFECT_CATEGORY`, `DEFECT_SEVERITY` and
  `RELATION_KIND`, with Korean and English names. The active values are part of the tool definitions. A retired code stays on
  existing rows and is refused when newly assigned. A severity is required on every defect.
- **Creation provenance is immutable.** New rows receive `created_by` automatically; edits and reviewer transitions keep it.
- **Time is UTC.** Timestamps are stored and transmitted in UTC (ISO-8601 with `Z`); the server converts no time zone. The JVM and
  the database session must run in UTC, and a server that finds otherwise does not start (`ledger.schema.require-utc`).
- **Database neutral.** The product does not bundle a JDBC driver: the user obtains the JDBC driver of the database of their choice
  and registers it with `driver-register`. Only ANSI SQL is used and no DBMS-specific feature is used: the schema files have no
  triggers, procedures, views, sequences, identity columns or boolean columns, and the rules that the database used to enforce are
  enforced by the application services. No list of databases is published.

A direct MCP caller is not authenticated as a host: the bearer token admits the caller, and the signed attestation proves the author.

## MCP tools

The server speaks MCP over Streamable HTTP at `/mcp` (default `127.0.0.1:8765`) and answers `/healthz`. Callers present
the bearer token `ledger.mcp.token`. The write tools keep their names and input keys. The results carry their keys at the top
level of the result; the wrapping `rows[0].result` of 1.x is gone. Every tool input is closed: an unknown argument is refused.

Write tools (a login, an attestation and a live grant are needed):

| Tool | Channel | What |
|---|---|---|
| `ledger_whoami` | author | is this author well formed, which grants does it hold (read only) |
| `ledger_save` | author | create or edit a defect, task or condition |
| `ledger_transition` | author | change state (`OPEN`, `SOURCE_FIXED`, `MERGED`, `ACTIVE`, `REPORTED_DONE`, and so on) |
| `ledger_record_fix` | author | record a fix commit and optional CI metadata; the fixing session becomes `fixed_by` and an `OPEN` defect becomes `SOURCE_FIXED` |
| `ledger_record_case` | author | add an observed case under a defect (a `MERGED` defect is refused and names its target) |
| `ledger_link` | author | link defects, tasks and conditions, or remove a link |
| `ledger_correct` | author | mark a history row as wrong |
| `ledger_batch` | author | up to 200 of the operations above in one transaction: all commit or none |
| `ledger_review` | reviewer | `VERIFIED`, `MET`, `NOT_MET`, and reopening them; the evidence link is required |
| `ledger_review_correct` | reviewer | mark a verification row as wrong |

Read tools (no attestation and no grant; structured queries that replace the raw SQL tools of 1.x):

| Tool | What |
|---|---|
| `ledger_get` | state and version of many ids at once (defects, tasks and conditions mixed); the check after a write and the source of `expected_version` |
| `ledger_defect` | one defect with fixes, tasks, conditions, cases (also those of merged defects), relations, `fixed_by` and recent changes |
| `ledger_task` | one task with purpose, success condition, origin, owner, the session that reported it done, defects and conditions |
| `ledger_history` | the history and the receipt of every write, by ids, id range, entity, project, operation, author or time range; `include_before` adds `before_data` |
| `ledger_search` | defects, tasks and conditions by keywords, case-insensitive, `match` is `ANY` (default) or `ALL` |
| `ledger_defects` | the defects of a project, filtered by status, severity, category, attention kind and owner |
| `ledger_tasks` | the tasks of a project, filtered by state, owner and attention kind |
| `ledger_conditions` | the release conditions of a project, filtered by state and release |
| `ledger_attention` | what needs a look: `OPEN_WITH_FIX`, `FIXED_WITHOUT_COMMIT`, `OPEN_NO_OWNER`, `TASK_UNKNOWN`, `DONE_NOT_VERIFIED` |
| `ledger_stats` | counts per state and severity of one project or of all; `bucket` = `day` adds the new items per UTC day |
| `ledger_projects` | the projects with id, code and title |
| `ledger_codes` | the common codes; retired codes with `include_inactive` |

The list read tools take `max_rows` (1 to 1000, default 50) and `cursor`; their answers carry `truncated` and `next_cursor`.
The tools `execute_sql_reader` and `search_objects_reader` of 1.x were removed: use the read tools above.

When a common code changes, the server sends `notifications/tools/list_changed`, so a connected client reloads the tool
definitions without a restart. A change made by another process (the admin web, `ledger-cli`) is noticed within
`ledger.mcp.code-poll-interval`. A new tool is registered when the server starts.

`ledger_record_case` takes `defect_id`, `observation` (1 to 4000 characters) and `reason`, and optionally `evidence`,
`user_quote` and `observed_at` (ISO-8601 with `Z` or an offset). In `ledger_batch` the same arguments go in an operation
`{"op": "record_case", ...}`:

```json
{"defect_id": "WEB-D-0007", "observation": "checkout returns 500 for a gift card",
 "evidence": "request 7f3a, api/checkout.ts:88", "user_quote": "I can't pay with my gift card",
 "observed_at": "2026-11-05T10:00:00Z", "reason": "second report of the same failure"}
```

## Modules

The build is a Gradle multi-module project.

| Module | What |
|---|---|
| `ledger-domain` | aggregates, value types, state rules, error codes and `ToolContract`, the list of tool names |
| `ledger-attestation` | the `_hostAttestation` token, RFC 8785 canonical JSON and the Ed25519 key forms, shared by the hook and the server (JDK only) |
| `ledger-database` | Spring Data JDBC repositories, the schema files `V{n}__*.sql` and their runner, the loader of the registered JDBC driver |
| `ledger-application` | the application services: write gate, receipts, history, the write and read operations, grants, session keys, the legacy import |
| `ledger-mcp-server` | the Spring Boot MCP server (`ledger-mcp-server.jar`) |
| `ledger-admin-web` | the Spring Boot admin web for the owner (`ledger-admin-web.jar`) |
| `ledger-cli` | the owner command line tool (`ledger-cli.jar`) |
| `ledger-client-hook` | the one hook jar of all clients (`ledger-client-hook.jar`) |

## Configuration

Settings are Spring Boot properties: in a file, on the command line (`--name=value`) or from the secret store. Secrets are never
put in a file or in an environment variable; `ledger-cli secret-set <name>` reads a secret with hidden input and stores it as one file
of the secret directory, which the processes read as a Spring configuration tree. A secret is stored under the name of its property.
There is no command that prints a stored secret.

| Key | Used by | Meaning |
|---|---|---|
| `ledger.jdbc.url` | all | JDBC URL of the ledger database |
| `ledger.jdbc.username` | all | database account |
| `ledger.jdbc.password` | all | database password (secret) |
| `ledger.jdbc.pool-size` | all | connection pool size, default 10 |
| `ledger.jdbc.driver` | all | the registered driver, written by `driver-register` (jar, class, SHA-256), read at start |
| `ledger.schema.name` | all | schema that holds the tables; created by `migrate` when missing; absent means the default schema of the account |
| `ledger.schema.long-text-type` | all | the long text type of the schema files, the only per-database part of the DDL, default `TEXT` |
| `ledger.schema.require-utc` | servers, CLI | refuse to start when the JVM or the database session is not UTC, default true |
| `ledger.attestation.window` | servers | how far the time of an attestation may be from the server time, default `PT5M` |
| `ledger.mcp.token` | MCP server, `mcp-check` | the bearer token of the agents, at least 16 characters (secret) |
| `ledger.mcp.allowed-hosts` | MCP server | accepted `Host` values, default `127.0.0.1` and `localhost` |
| `ledger.mcp.allowed-origins` | MCP server | accepted browser origins, default none |
| `ledger.mcp.max-request-size` | MCP server | largest request body, default 1MB |
| `ledger.mcp.code-poll-interval` | MCP server | how often a code change of another process is looked up, default 5s |
| `ledger.admin.token` | admin web | the owner's admin token, at least 16 characters (secret) |
| `ledger.admin.agent-token` | admin web | required: the agents' bearer token, equal to `ledger.mcp.token`, and distinct from `ledger.admin.token`; the admin web refuses it with `AGENT_TOKEN_FORBIDDEN` (secret) |
| `ledger.admin.allowed-hosts` | admin web | accepted `Host` values (DNS rebinding guard), default `127.0.0.1` and `localhost` |
| `ledger.admin.driver-file` | admin web | the driver registration file the driver page writes, default `/etc/pg-ledger-mcp/jdbc-driver.yaml` |
| `ledger.admin.driver-directory` | admin web | the one directory a driver jar may be taken from, default `/etc/pg-ledger-mcp/drivers` |
| `ledger.admin.oidc.owners` | admin web | who may sign in with OpenID Connect: a list of issuer with subject or e-mail; the provider itself is `spring.security.oauth2.client.*` |
| `ledger.admin.lockout.max-failures` | admin web | failed token attempts of one client before it is locked, default 5 |
| `ledger.admin.lockout.window` | admin web | the period in which failures are counted, default 60s |
| `ledger.admin.lockout.lock-period` | admin web | how long a client stays locked, default 60s |
| `ledger.admin.lockout.global-max-failures` | admin web | failed attempts of all clients together within the window before the global delay starts, default 50 |
| `ledger.admin.lockout.global-delay` | admin web | the wait before a token is compared once the global budget is used, default 1s; it slows down and never locks |
| `ledger.admin.mcp.health-url` | admin web | the `/healthz` URL of the MCP server shown on the overview; absent means not configured |
| `ledger.admin.mcp.timeout` | admin web | timeout of that check, default 2s |
| `ledger.import.source.url` | `import-legacy` | JDBC URL of the database that holds the 1.x ledger |
| `ledger.import.source.username` | `import-legacy` | a read-only account of that database |
| `ledger.import.source.password` | `import-legacy` | its password (secret) |
| `ledger.import.source.schema` | `import-legacy` | the 1.x schema, default `ledger` |
| `ledger.secrets.directory` | `ledger-cli` | the secret directory, default `/etc/pg-ledger-mcp/secrets` |
| `ledger.secrets.instance` | `ledger-cli` | the instance name that the default paths follow, default `pg-ledger-mcp` |
| `ledger.cli.driver.directory` | `ledger-cli` | the directory `driver-register` takes a jar from, default `/etc/pg-ledger-mcp/drivers` |
| `ledger.cli.driver.file` | `ledger-cli` | the registration file `driver-register` writes, default `/etc/pg-ledger-mcp/jdbc-driver.yaml` |

The servers listen on loopback only (MCP `127.0.0.1:8765`, admin web `127.0.0.1:8865`); publish them through a reverse proxy
that terminates HTTPS and list its public host name in the allowed hosts. The admin web trusts `X-Forwarded-For` and
`X-Forwarded-Proto` from the loopback address only. The servers read the driver registration file and the secret directory
through `spring.config.import`, which is part of the launch line (see Installation); `ledger-cli` reads them by default.

## Installation

An installer is planned and is not part of this release; the steps below are the manual ones. The build needs JDK 25.

1. Build the jars: `./gradlew build`. The jars are `ledger-cli/build/libs/ledger-cli.jar`, `ledger-mcp-server/build/libs/ledger-mcp-server.jar`,
   `ledger-admin-web/build/libs/ledger-admin-web.jar` and `ledger-client-hook/build/libs/ledger-client-hook.jar`; the plugin directories of
   the clients are `ledger-client-hook/build/client-plugins/<client>`. Place them on the host; a Java 25 runtime is needed there.
2. Prepare the database: an empty database and an account for the ledger, with the session time zone UTC. An optional template of
   least-privilege grants for the application account is `ledger-database/src/main/resources/db-templates/dml-grants.sql`.
3. Obtain the JDBC driver of that database yourself and put its jar into the driver directory; it is not bundled. Register it with the
   URL of the database (the driver class must accept it):

```sh
java -jar ledger-cli.jar driver-register --jar=/etc/pg-ledger-mcp/drivers/<driver>.jar --class=<driver-class> --ledger.jdbc.url=<jdbc-url>
```

4. Store the secrets, each with hidden input, as the user that runs the services:

```sh
java -jar ledger-cli.jar secret-set ledger.jdbc.password
java -jar ledger-cli.jar secret-set ledger.mcp.token
java -jar ledger-cli.jar secret-set ledger.admin.token
java -jar ledger-cli.jar secret-set ledger.admin.agent-token
```

5. Create the schema: `migrate` applies the files `V{n}__*.sql` in order and records every attempt, with the SHA-256 of the file, in the
   table `schema_history`. A file that was changed after it was applied stops every process at start.

```sh
java -jar ledger-cli.jar migrate --ledger.jdbc.url=<jdbc-url> --ledger.jdbc.username=<account>
```

6. Set up the owner side: `project-add`, `code-set`, and, when you come from 1.x, `import-legacy` (see Migrating from 1.x).
7. Start the MCP server and the admin web with the same settings (the url, the account, the allowed hosts) and with the driver
   registration and the secrets as configuration sources:

```sh
java -Duser.timezone=UTC -jar ledger-mcp-server.jar --spring.config.import=optional:file:/etc/pg-ledger-mcp/jdbc-driver.yaml,optional:configtree:/etc/pg-ledger-mcp/secrets/ --ledger.jdbc.url=<jdbc-url> --ledger.jdbc.username=<account>
java -Duser.timezone=UTC -jar ledger-admin-web.jar --spring.config.import=optional:file:/etc/pg-ledger-mcp/jdbc-driver.yaml,optional:configtree:/etc/pg-ledger-mcp/secrets/ --ledger.jdbc.url=<jdbc-url> --ledger.jdbc.username=<account>
```

   Without a registered driver the MCP server stops and says why; the admin web starts in a setup mode that offers only the sign-in
   and the driver page. `ledger-cli mcp-check` connects to the running MCP server and checks that every tool of the contract is listed.
8. Install the client hooks (see Client hooks), register the key of each session with `key-register` and issue the grants with `grant`.

## ledger-cli

`java -jar ledger-cli.jar <command> [arguments]`. An option is written `--name=value`. A Spring property (a name with a dot, such as
`--ledger.jdbc.url=...`) is passed the same way. The tool runs as the user that owns the secret directory, and every owner
operation is recorded with that user.

| Command | Syntax | What |
|---|---|---|
| `secret-set` | `secret-set <name>` | store a secret from hidden input |
| `driver-register` | `driver-register --jar=<path> --class=<driver-class>` | register the JDBC driver; needs no database |
| `migrate` | `migrate` | apply the pending schema files |
| `import-legacy` | `import-legacy [--verify-only]` | copy the 1.x ledger into the new schema and compare every table |
| `key-register` | `key-register <author> <public-key-or-block> <fingerprint> [--replace=<current-fingerprint>]` | bind the public key a session printed to its author; a live key is replaced only with `--replace` |
| `key-revoke` | `key-revoke <author> --fingerprint=<fingerprint>` | close the live key of an author |
| `project-add` | `project-add <id> <code> <title>` | add a project |
| `code-set` | `code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>] [--inactive]` | add or update a common code |
| `codes` | `codes [<group>]` | list the common codes |
| `grant` | `grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]` | grant scopes on a project; the author needs a live session key first |
| `revoke` | `revoke <author> [<project> [<scope>]]` | revoke live grants |
| `grants` | `grants [<author>]` | list live grants |
| `mcp-check` | `mcp-check [--url=<url>] [--ledger.mcp.token=<token>]` | connect to a running MCP server and check its tools |

## Admin web

The admin web (`ledger-admin-web.jar`) is a server-rendered console for the owner, on `127.0.0.1:8865` by default. It runs as
an ordinary user and needs no root rights.

- **Sign-in.** The admin token `ledger.admin.token`, or OpenID Connect for the people listed in `ledger.admin.oidc.owners`. The
  agents' token is refused, so an agent that holds the MCP token cannot grant itself anything. Failed attempts lock the client and
  slow down all clients (`ledger.admin.lockout.*`).
- **Browser hardening.** Spring Security provides CSRF tokens, a `SameSite=Strict` session cookie that is `Secure` by default, the
  `Host` allow list and a Content Security Policy.
- **Pages.** Overview (attention counts and the state of the MCP server), projects, defects, tasks, conditions and history
  (read only), common codes, grants with their session keys, and the JDBC driver page. Times are sent as UTC and shown in the local
  time of the browser.
- **Grants.** The owner pastes the public key a session printed, compares the fingerprint shown with the one the session showed, and
  only then the key is registered and the grants are issued. A live key is replaced only on an explicit confirmation of that exact key.

## Client hooks

One executable jar, `ledger-client-hook.jar`, serves all clients; the first argument selects the client:
`java -jar ledger-client-hook.jar claude`, `codex` or `antigravity`. The plugin directories are in `clients/claude`,
`clients/codex` and `clients/antigravity` (see the README of each). The hook needs a Java 25 runtime on the `PATH` of the client
host; with no hook, or a failing hook, a write is refused with `HOST_ATTESTATION_REQUIRED`.

The hook signs only the ten write tools, named `mcp__<server>__ledger_<op>` or `mcp__<server>__<server>_ledger_<op>`
(Codex Apps: `mcp__codex_apps__app_<app>_ledger_<op>`). The value of `_hostAttestation` is
`pgla2.<author>.<tool>.<request id>.<arguments digest>.<issued at>.<signature>`: an Ed25519 signature over the author, the tool, the
request id, the SHA-256 of the RFC 8785 canonical form of the arguments (without `_hostAttestation`; a batch: every operation, its
content, its order and its request id) and the time. A captured token cannot carry other content. `pgla1` tokens are refused with
`ATTESTATION_VERSION_UNSUPPORTED`.

- The hook keeps one Ed25519 key per MCP server name and author in an owner-only folder: `~/.pg-ledger-mcp/session-keys`, or the
  absolute path in `LEDGER_HOOK_KEY_DIR`. A signature made for one server never verifies on another. For Codex Apps the server name
  is `codex_apps.<app>`.
- `java -jar ledger-client-hook.jar session-key <server> <author>` prints the server, the author, the public key and its fingerprint.
  The owner compares the fingerprint and registers the key on that server with `ledger-cli key-register` or on the grants page.
- The server verifies the signature, the tool, the request id, the arguments and the time (`ledger.attestation.window`) before it
  looks at any grant. A missing key and a wrong signature are the same refusal, `ATTESTATION_SIGNATURE_INVALID`; a different
  content is `ATTESTATION_BINDING_MISMATCH`.
- Limits: another process of the same operating system user can read the key file, so isolation inside one user needs separate
  accounts; a hook that runs elevated (the key folder then belongs to another owner) is refused.

## Migrating from 1.x

1.x was a DBHub and Node implementation on PostgreSQL functions; 2.0.0 replaces all of it. Existing data is moved, not converted in
place. The 1.x schema stays and can be used to go back. See the [release notes](docs/release-notes/2.0.0.md) for the contract changes.

1. Register the driver, store the secrets and run `migrate` as above. When the new schema lives in the database of the 1.x ledger,
   set `ledger.schema.name` to a schema other than the 1.x schema `ledger`.
2. Store the password of a read-only account of the 1.x database: `secret-set ledger.import.source.password`, and run
   `import-legacy --ledger.import.source.url=<jdbc-url> --ledger.import.source.username=<account>`.
3. The import reads the 1.x schema read only, copies everything in one transaction and rolls back on any failure; then it compares the
   number of rows and the SHA-256 digest of every table (`--verify-only` repeats the comparison). It refuses when the new schema
   already holds data, and it lists every value of the old data that the new schema cannot hold; it never invents a value.
4. The old grants are copied without a session key. Every author registers a session key with `key-register`; then its grants work.
5. Clients: the tools `execute_sql_reader` and `search_objects_reader` were removed; the read tools replace them. The results of the write
   tools keep their keys without the `rows[0].result` wrapping. Install the hook jar (a Java 25 runtime) before the server is switched.

## Build and test

JDK 25 and the Gradle wrapper are all that is needed; the wrapper downloads Gradle itself.

```sh
./gradlew build
```

The build compiles with warnings as errors, runs every module test against an embedded database (no external database
is needed) and checks that no JDBC driver, JPA, migration tool or Lombok is on a runtime classpath. The documentation tests of
`ledger-cli` (DOC-001 to DOC-029 in [docs/testing/TEST_CASES.md](docs/testing/TEST_CASES.md)) check this README, the Korean one and the
release notes against the code.

## Layout

- `ledger-domain`, `ledger-attestation`, `ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`,
  `ledger-cli`, `ledger-client-hook`: the modules above, each with `src/main` and `src/test`.
- `clients/claude`, `clients/codex`, `clients/antigravity`: the manifests and README of each client plugin; the build stages them with
  the hook jar into `ledger-client-hook/build/client-plugins`.
- `docs/`: the test case catalogue, the release note format and the release notes.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: the build.
- `bin/`, `web/`, `mcp/`, `sql/`, `tests/*.mjs`, `deploy/*.sh`: the 1.x implementation (DBHub, Node and PostgreSQL functions), kept in the tree until a later stage removes it. It is not part of 2.x.

## License

MIT
