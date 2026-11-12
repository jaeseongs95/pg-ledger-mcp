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
  application has no path that updates or deletes a history row, and the grant template of the database module (`migrate` applies it to
  the application account) gives that account no `UPDATE` or `DELETE` on it. A mistake is marked with a `CORRECT` row. An operation that changes an
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
| `ledger-seed` | the seed bundle of a new installation, the default defect categories and the install defaults in one H2 file, and its reader; only `ledger-cli` uses it |
| `ledger-client-hook` | the one hook jar of all clients (`ledger-client-hook.jar`) |
| `ledger-system-test` | test code only: the system test of the packaged product, which starts the built jars against the local PostgreSQL |

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
| `ledger.secrets.home` | `ledger-cli` | the directory of the installation that holds the settings, the driver registration, the driver jars and the secret directory, default `/etc/pg-ledger-mcp` (the Windows installer gives its own) |
| `ledger.secrets.directory` | `ledger-cli` | the secret directory, default `/etc/pg-ledger-mcp/secrets` |
| `ledger.secrets.instance` | `ledger-cli` | the instance name that the default paths follow, default `pg-ledger-mcp` |
| `ledger.cli.driver.directory` | `ledger-cli` | the directory `driver-register` takes a jar from, default `/etc/pg-ledger-mcp/drivers` |
| `ledger.cli.driver.file` | `ledger-cli` | the registration file `driver-register` writes, default `/etc/pg-ledger-mcp/jdbc-driver.yaml` |

The servers listen on loopback only (MCP `127.0.0.1:8765`, admin web `127.0.0.1:8865`); publish them through a reverse proxy
that terminates HTTPS and list its public host name in the allowed hosts. The admin web trusts `X-Forwarded-For` and
`X-Forwarded-Proto` from the loopback address only. The servers read the driver registration file and the secret directory
through `spring.config.import`, which the installers put into the service definition (see Installation); `ledger-cli` reads them by default.

## Installation

Two installers cover three targets. A **personal PC** (Windows) runs `deploy\install.cmd` with its defaults: both servers listen on
the loopback address only, and there is no reverse proxy and no TLS. A **Windows server** runs the same script and, when it is
reached from outside, names its public host names and puts a reverse proxy that the operator has prepared in front of it. A **Linux
server** runs `sudo deploy/install.sh`, optionally with an nginx site. macOS is not supported; a Linux desktop that is a personal PC
follows the Linux procedure. The build needs JDK 25 and the host needs a Java 25 runtime at a path that every account can execute (not
under `/home`: the service account runs it too).

What every target has in common:

- **One service account.** The MCP server and the admin web run as one operating system account that belongs to no person: on
  Linux the system user `pg-ledger-mcp` (no login, not root), on Windows the predefined account LocalService (no password). Its purpose
  is to keep the secrets of the ledger, the admin token and the database password, away from the account of the person who works on
  the machine and from the agents that run under it: the secret directory is readable by the service account only. The two
  services of an installation, called an instance, are named after it: the instance is `pg-ledger-mcp` unless `--name=<name>` gives
  another name, and its services are `<name>` and `<name>-admin` (`pg-ledger-mcp` and `pg-ledger-mcp-admin` by default). The Linux user, the
  directories (`/opt/<name>`, `/etc/<name>`, on Windows `C:\ProgramData\<name>`) and the WinSW ids follow the name, so a second instance, or an
  installation next to a host that still runs 1.x under the default names, needs only its own `--name=<name>` and its own ports.
- **Two database accounts**, created beforehand with the tools of your DBMS (creating an account is not ANSI SQL and is not part of the
  product). The application account is used by both servers and by the owner commands of `ledger-cli`; `migrate` gives it the least
  privilege of `ledger-database/src/main/resources/db-templates/dml-grants.sql` (no DDL, no `UPDATE` or `DELETE` on the history). The
  DDL account is used only by `migrate`, `setup` and `import-legacy`; its password is typed with hidden input and is never stored. The
  session time zone of the database must be UTC, and the application account must be allowed to use the schema, a step of your DBMS
  that is not part of the product SQL: `setup` checks it and says so when it is missing (see First setup).
- **Jars and the driver.** Build the jars with `./gradlew assemble` (the jars only; `./gradlew build` also runs the tests, which need a PostgreSQL of their own, see Build and test) and collect the three jars of the host in one directory. The
  JDBC driver of your database is obtained by you, and you name its class; it is not bundled.

```sh
mkdir dist
cp ledger-cli/build/libs/ledger-cli.jar ledger-mcp-server/build/libs/ledger-mcp-server.jar ledger-admin-web/build/libs/ledger-admin-web.jar dist/
```

- **What an installer does.** The installers are thin: they start only what Java cannot do, and everything else is a command of
  `ledger-cli`. Java does the work on the files of the host: `render-deploy --check` validates every option first, and
  `prepare-host` creates the folders with their owners, modes and ACLs, copies the jars and the driver jar and writes the service
  definitions and the settings (`application.yaml` and one file per service in one directory; your own settings belong in
  `local.yaml` there, which is read last and never replaced). The installer then has the secrets stored by `ledger-cli`: the
  database password of the application account (hidden input), `ledger.admin.token` and `ledger.mcp.token` (generated, shown once,
  at the end of their step) and the copy `ledger.admin.agent-token`; it registers the driver, runs `setup` with the DDL account (the
  schema, the grants and the seed codes, see First setup) and starts the two services. What stays in the scripts, because Java cannot do it, is creating the operating system user (`useradd`),
  starting a command as that user (`runuser`), the service managers (`systemctl`, WinSW and `sc.exe`) and `nginx -t` with the reload.
  The Java 25 check is the JVM's own: the jars are compiled for Java 25, and no other runtime loads them. Nothing is downloaded or
  installed. Running it again with newer jars is the upgrade: files and services are replaced, the schema and the grants are applied
  again, and secrets, tokens and data are kept. A run that stopped half way is repaired the same way: run it again with the same
  options (the commands that store secrets do not need the database). The settings directory is `ledger.secrets.home` of the tool; the
  services read the same sources through `spring.config.import`.

The options of `deploy/install.sh` are given as `--name=value`; the ones it does not read itself go on to `render-deploy`:

| Option | Meaning |
|---|---|
| `--name=<name>` | the name of the instance, default `pg-ledger-mcp`: lower-case letters, digits and hyphens, at most 23 characters, not ending with `-admin`; Java validates it before anything is created |
| `--java=<path>` | the Java 25 runtime, default the `java` on the `PATH` of root; the service user runs it too, so it is at a path every account can execute (not under `/home`), which the script checks before it creates anything |
| `--jars=<dir>` | the directory that holds the three jars |
| `--driver-jar=<file>` | the JDBC driver jar |
| `--driver-class=<class>` | the driver class, which must accept the JDBC URL |
| `--owner-account=<account>` | the DDL account (its password is asked for) |
| `--app-account=<account>` | the application account |
| `--jdbc-url=<url>` | the JDBC URL of the ledger database (no password in it) |
| `--schema=<name>` | `ledger.schema.name`, optional |
| `--long-text-type=<type>` | `ledger.schema.long-text-type`, optional |
| `--mcp-port=<n>` | the loopback port of the MCP server, default 8765 |
| `--admin-port=<n>` | the loopback port of the admin web, default 8865 |
| `--mcp-host=<host>` | the public host name of the MCP server, allowed in its `Host` check |
| `--admin-host=<host>` | the public host name of the admin web |
| `--nginx-conf=<path>` | Linux: write the nginx site to this file; needs both host names and either `--tls-certificate=<path>` and `--tls-key=<path>` or `--tls-acme` |
| `--tls-certificate=<path>` | the TLS certificate of the operator; nothing issues or renews one |
| `--tls-key=<path>` | the TLS key of the operator |
| `--tls-acme` | a flag, instead of the certificate and the key of the operator: the certificate is made and renewed by `ledger-cli` (see Automatic certificate) |
| `--acme-directory=<url>` | with `--tls-acme`, optional: the ACME directory URL of the certificate authority; the default is the production directory of Let's Encrypt, `https://acme-v02.api.letsencrypt.org/directory` |
| `--acme-email=<address>` | with `--tls-acme`, optional: the contact address of the ACME account |
| `--agree-tos` | with `--tls-acme`: you have read the terms of service of the certificate authority and agree to them; the first order needs it |
| `--from-1x-env=<file>` | migration from 1.x: the 1.x environment file; its two tokens become secrets before any token is generated (see Migrating from 1.x) |
| `--stdin` | read the passwords from standard input instead of the terminal: first the one of the application account (only while it is not stored), then the DDL one; the convention of `docker login --password-stdin` |

`render-deploy`, `prepare-host` and `seal-host` take five more options that the installers set themselves: `--platform=<platform>`
(`linux`, the default, or `windows`), `--home=<path>` (Windows: the installation directory), `--java=<path>` (the Java executable),
`--root=<directory>` (put the files below a directory instead of at their paths, for a look before they are put in place) and, for
`render-deploy` only, the flag `--check` (validate every option and change nothing; the installers run it first, so that a bad value,
the name included, stops them while nothing exists yet). `prepare-host` also takes `--jars=<dir>`, `--driver-jar=<file>` and, on
Windows, `--winsw=<file>`. Without `--root` the platform has to be the platform of the host that runs the command: the paths of the
other one are no paths of this host and would end up below the working directory, so the commands refuse them (naming `--platform`)
before anything is read or written.

### Linux server

Run as root; the user `pg-ledger-mcp` is created when it is missing, `prepare-host` (Java) creates `/opt/<name>` (root, 0755),
`/etc/<name>` (the service user, 0750), its `drivers` folder (root, 0755) and its `secrets` folder (the service user, 0700), copies the
jars and the driver and renders the units into `/etc/systemd/system`, which are then enabled; the secrets are stored by the service
user. A unit file of 1.x that a rendered unit replaces is kept as `<file>.1x-backup`.

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url>
```

With a public name and TLS, add the host names and the paths of your certificate and key; the nginx site overwrites
`X-Forwarded-For` with the address of the connection, which the admin web trusts from the loopback proxy only:

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url> --mcp-host=<mcp-host> --admin-host=<admin-host> --nginx-conf=/etc/nginx/conf.d/pg-ledger-mcp.conf --tls-certificate=<certificate> --tls-key=<key>
```

### Automatic certificate

With `--tls-acme` in place of `--tls-certificate` and `--tls-key`, `ledger-cli` makes and renews the certificate itself. It is a
standard ACME client (RFC 8555) built on the library acme4j (Apache-2.0; it brings jose4j, Apache-2.0, and Bouncy Castle, under the
Bouncy Castle license, an MIT-style license, which acme4j uses for the certificate request). The certificate authority is any ACME
directory: `--acme-directory=<url>`, by default the production directory of Let's Encrypt. The proof is HTTP-01 only, so no
interface of a DNS or CDN vendor is involved. The result is `fullchain.pem` and `privkey.pem` in `/etc/<name>/tls`, next to the
account key `account.key`: standard PEM files that any other proxy can use as well. The keys are made with the JDK (EC P-256),
stored so that only the service user can read them, never printed, and the files are replaced atomically, so a failed renewal
leaves the old certificate in place.

What the operator does, in this order:

1. Create the DNS records (`A` and `AAAA`) of the two host names, `--mcp-host` and `--admin-host`, that point to the server.
2. Allow the ports `80` and `443` in the firewall. Port `80` must reach nginx: the certificate authority fetches the proof from
   there over HTTP.
3. If a CDN or another proxy stands in front of the server, it has to pass the path `/.well-known/acme-challenge/` through to the
   server on port `80` and must neither cache it nor redirect it. Long-lived MCP connections (Streamable HTTP) need a timeout of
   the proxy that is long enough; the nginx site itself sets `proxy_buffering off` and `proxy_read_timeout 3600s` for the MCP host.
4. Read the terms of service of the certificate authority and give `--agree-tos`; `--acme-email=<address>` is an optional contact.

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url> --mcp-host=<mcp-host> --admin-host=<admin-host> --nginx-conf=/etc/nginx/conf.d/pg-ledger-mcp.conf --tls-acme --agree-tos
```

The nginx site holds only the `server` blocks of its own two host names. It never takes `default_server` and changes no other
site, so it can live next to other services of the host. For each host name a port 80 block serves `/.well-known/acme-challenge/`
from the web root `/var/www/<name>-acme` and redirects everything else to https; the 443 blocks use the PEM files.

First issuance: nginx cannot load a 443 block before its certificate files exist, so `render-deploy` writes the 443 blocks only
when `fullchain.pem` and `privkey.pem` exist. The installer therefore writes the site with the port 80 blocks, lets nginx load it,
runs `tls-issue` as the service user, runs `render-deploy` again (now with the 443 blocks), lets nginx load the site again and
enables the timer. Running the installer again asks `tls-renew` instead, which orders only what is due.

Renewal: `render-deploy --tls-acme` writes the systemd service `<name>-tls-renew.service` and the timer
`<name>-tls-renew.timer`. The timer runs twice a day, at a random time. `tls-renew` orders a new certificate only when the present
one expires within 30 days (`--renew-before-days=<n>`) or does not name exactly the two host names, and otherwise does nothing and
says so. The service runs as the service user without any capability, may write only the TLS directory and the web root, and
reloads nginx afterwards with `ExecStartPost=+/usr/bin/systemctl reload nginx`. That is one fixed command line that systemd runs with
full privileges outside the sandbox (the `+` prefix of systemd.service(5)), because only root may reload nginx. Nothing from the
service user reaches it, and no `sudo` rule and no polkit rule has to be kept; the process that talks to the network and writes the
keys keeps no privilege.

Automatic certificates are for Linux only, because the nginx site is: the installer for Windows has no web server of its own (a
Windows server is reached through a reverse proxy that you prepare). The commands themselves run on any host: with `--webroot=<dir>`
and `--tls-dir=<dir>` they write the proof where the web server of your choice serves it and the PEM files where your proxy reads
them, and `--name=<name>` selects the instance whose directories are the defaults.

### Windows: a personal PC or a server

Run in an elevated prompt. The script takes its arguments in this order, and the services are registered with WinSW, the way
Spring Boot's documentation describes for Windows services; you obtain the WinSW 2.x executable yourself, as you do the JDBC driver,
and the installer neither bundles nor downloads it.

| Argument | Meaning |
|---|---|
| `<jars-dir>` | the directory that holds the three jars |
| `<winsw-exe>` | the WinSW 2.x executable |
| `<driver-jar>` | the JDBC driver jar |
| `<driver-class>` | the driver class |
| `<ddl-account>` | the DDL account |
| `<app-account>` | the application account |
| `"<jdbc-url>"` | the JDBC URL, in quotes |
| `"<name>"` | optional, in quotes: the name of the instance, default `pg-ledger-mcp` |
| `"<more options>"` | optional, in quotes, after the name: more options of `render-deploy`, for example the host names of a server |

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>"
```

A Windows server that is reached from outside adds its host names and puts a reverse proxy that you have prepared in front of the two
loopback ports (the installer writes no proxy site on Windows):

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>" "pg-ledger-mcp" "--mcp-host=<mcp-host> --admin-host=<admin-host>"
```

Everything lives in `C:\ProgramData\<name>`. Both services run as LocalService, restart after a failure (the WinSW
configuration), and write their logs to the `logs` folder. `prepare-host` closes the folders to every account but SYSTEM and
Administrators; `seal-host` hands the secrets over to LocalService at the end of the installation (Java, with ACLs, no `icacls`): the
installing user can only see that a file exists, and `prepare-host` takes them back for the duration of a re-run. So the
owner commands of `ledger-cli` cannot read them afterwards: do the owner work (projects, common codes, grants, keys) on the pages of
the admin web, which sign in with `ledger.admin.token`. On a personal PC open the admin web at `http://localhost:8865` (the port is
`--admin-port=<n>`): the admin web sets a `Secure` session cookie, which the major browsers accept from `localhost` because they treat it
as a secure context.

### First setup

The installers call `setup`, and you run it yourself after an upgrade or against a database that you prepared by hand. A new ledger
has to start with some data, so `ledger-cli` carries a seed bundle: the file `ledger-seed.mv.db`, which the build writes with a Java
program from the text files `src/seed/defect-categories.tsv` and `src/seed/install-defaults.properties` of the module `ledger-cli`
and packs into `ledger-cli.jar`. It is never committed. The bundle holds the 14 default defect categories (code, Korean and English
name and description) and the defaults of an installation (the instance name and the two ports); it holds no secret. The file has the
format of the H2 database, and that is all H2 is here: the ledger is always kept by the database system that you install, and the
registration of an H2 driver, or of a JDBC URL of H2, is refused by `driver-register` and by the driver page of the admin web.

`setup [--owner-account=<account>] [--app-account=<account>] [--stdin]` takes the options of `migrate` and does four things in this
order:

1. It migrates the schema exactly as `migrate` does, and applies the grants of the application account.
2. It checks, over a connection of the application account, that this account can use the schema. The privilege to use a schema is a
   statement of your database system and is not part of the product SQL, so when it is missing `setup` stops with exit code `3`, names
   the account and says that an operator has to give it that privilege with the tools of the database system. Nothing else has been
   changed at that point; run `setup` again after that step and it continues where it stopped.
3. It adds the codes of the seed that the ledger does not have yet, through the service of `code-set` and of the admin web, so that
   the code revision and the owner's audit trail are kept. A code that exists is never changed: a code that you renamed or retired
   stays as you left it.
4. It compares the seed with the database by the number of codes and by a SHA-256 digest, and prints the report. Running `setup`
   again changes nothing and prints the same report; a code of yours that differs from the seed is counted as changed and is kept.

The defaults of an installation are written in one place, the seed bundle. `render-deploy`, `prepare-host` and `seal-host` take the name
and the ports from it when an option is not given, and the installers ask for them with `install-default <name>` (`instance-name`,
`mcp-port` or `admin-port`), so no script holds a second copy.

### After the installation

1. Check the MCP server with `ledger-cli mcp-check`; without a registered driver the MCP server stops and says why, and the admin web
   starts in a setup mode that offers only the sign-in and the driver page.
2. Set up the owner side: `project-add`, `code-set` and, when you come from 1.x, `import-legacy` (see Migrating from 1.x). On Linux run
   `ledger-cli` as the service user, for example `sudo -u <name> java -jar /opt/<name>/ledger-cli.jar --ledger.secrets.instance=<name> project-add ...`.
3. Install the client hooks (see Client hooks), register the key of each session with `key-register` and issue the grants with `grant`.

`migrate` applies the files `V{n}__*.sql` in order and records every attempt, with the SHA-256 of the file, in the table
`schema_history`. A file that was changed after it was applied stops every process at start.

## ledger-cli

`java -jar ledger-cli.jar <command> [arguments]`. An option is written `--name=value`. A Spring property (a name with a dot, such as
`--ledger.jdbc.url=...`) is passed the same way. The tool runs as the user that owns the secret directory (on Linux the
service user), and every owner operation is recorded with that user.

| Command | Syntax | What |
|---|---|---|
| `secret-set` | `secret-set <name> [--stdin] [--if-absent]` | store a secret from hidden input, or with `--stdin` from the first line of standard input (never from an argument or the environment); `--if-absent` keeps an existing secret and asks for nothing |
| `driver-register` | `driver-register --jar=<path> --class=<driver-class>` | register the JDBC driver; needs no database |
| `token-generate` | `token-generate <name> [--print] [--copy-to=<name>]` | store a random token (32 bytes from `SecureRandom`) as a secret; an existing secret is kept; `--print` shows a token that was just created, once; `--copy-to` stores the same token under a second name, once |
| `secret-import-1x` | `secret-import-1x [--env=<file>] [--stdin]` | move the two tokens of a 1.x installation (`DBHUB_AUTH_TOKEN` to `ledger.mcp.token` and `ledger.admin.agent-token`, `PGLEDGER_ADMIN_TOKEN` to `ledger.admin.token`) from its environment file, given as a file or on standard input, into the secret store; an existing secret is kept, no value is printed |
| `migrate` | `migrate [--owner-account=<account>] [--app-account=<account>] [--stdin]` | apply the pending schema files, as the DDL account when `--owner-account` is given (its password is typed, or with `--stdin` read from standard input, and not stored), and then the grants of `dml-grants.sql` to the application account |
| `setup` | `setup [--owner-account=<account>] [--app-account=<account>] [--stdin]` | the first setup: migrate as `migrate` does, check that the application account can use the schema (exit code `3` and the missing step when it cannot), add the seed codes that are missing (a code that exists is never changed) and compare the seed with the database by counts and digest; running it again changes nothing |
| `import-legacy` | `import-legacy [--verify-only] [--owner-account=<account>] [--stdin]` | copy the 1.x ledger into the new schema and compare every table; it writes the whole ledger, so it runs as the DDL account |
| `key-register` | `key-register <author> <public-key-or-block> <fingerprint> [--replace=<current-fingerprint>]` | bind the public key a session printed to its author; a live key is replaced only with `--replace` |
| `key-revoke` | `key-revoke <author> --fingerprint=<fingerprint>` | close the live key of an author |
| `project-add` | `project-add <id> <code> <title>` | add a project |
| `code-set` | `code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>] [--inactive]` | add or update a common code |
| `codes` | `codes [<group>]` | list the common codes |
| `grant` | `grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]` | grant scopes on a project; the author needs a live session key first |
| `revoke` | `revoke <author> [<project> [<scope>]]` | revoke live grants |
| `grants` | `grants [<author>]` | list live grants |
| `mcp-check` | `mcp-check [--url=<url>] [--ledger.mcp.token=<token>]` | connect to a running MCP server and check its tools |
| `render-deploy` | `render-deploy --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--long-text-type=<type>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host> --admin-host=<host>] [--nginx-conf=<path> (--tls-certificate=<path> --tls-key=<path> | --tls-acme [--acme-directory=<url>])] [--root=<directory>] [--check]` | write the service definitions (systemd units, or WinSW configurations on Windows), the settings and the optional nginx site; the installers call it |
| `prepare-host` | `prepare-host --jars=<dir> --driver-jar=<file> [--winsw=<file>] --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--long-text-type=<type>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host> --admin-host=<host>] [--nginx-conf=<path> (--tls-certificate=<path> --tls-key=<path> | --tls-acme [--acme-directory=<url>])] [--root=<directory>]` | prepare the files of the host (run as root, or elevated on Windows): the folders with owner and mode, or their ACLs, the copies of the jars and the driver (on Windows also of WinSW), and what `render-deploy` writes; the installers call it |
| `seal-host` | `seal-host --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--long-text-type=<type>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host> --admin-host=<host>] [--nginx-conf=<path> (--tls-certificate=<path> --tls-key=<path> | --tls-acme [--acme-directory=<url>])] [--root=<directory>]` | Windows, last: give LocalService its access and hand the secrets over to it; the installer calls it |
| `install-default` | `install-default <name>` | print one default of the installation from the seed bundle (`instance-name`, `mcp-port` or `admin-port`); the installers ask for their defaults with it |
| `tls-issue` | `tls-issue --mcp-host=<host> --admin-host=<host> [--name=<name>] [--webroot=<dir>] [--tls-dir=<dir>] [--acme-directory=<url>] [--acme-email=<address>] [--agree-tos]` | order a certificate for the two host names from a certificate authority that speaks ACME and write `fullchain.pem` and `privkey.pem`; the first run creates the account and needs `--agree-tos`; see Automatic certificate |
| `tls-renew` | `tls-renew --mcp-host=<host> --admin-host=<host> [--name=<name>] [--webroot=<dir>] [--tls-dir=<dir>] [--acme-directory=<url>] [--renew-before-days=<n>]` | order a new certificate when the present one expires within `--renew-before-days` (default 30) or does not name exactly the two host names, and otherwise do nothing; the timer of `--tls-acme` runs it |

## Admin web

The admin web (`ledger-admin-web.jar`) is a server-rendered console for the owner, on `127.0.0.1:8865` by default. It runs as
the service account of the installation and never as root.

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

The installation replaces the 1.x service on the same host, which stays on the 1.x database until it is switched: stop it, install,
import, check. Do the steps in this order; the installer stops nothing of 1.x by itself.

1. **Back up.** Dump the ledger database with the dump tool of your DBMS, and copy the 1.x settings directory `/etc/pg-ledger-mcp`
   (the file `/etc/pg-ledger-mcp/env` holds the 1.x tokens and passwords) and the 1.x unit files in `/etc/systemd/system`.
2. **Stop the 1.x services.** On a host that runs them under the default names:
   `sudo systemctl stop pg-ledger-mcp pg-ledger-mcp-admin`. Their clients are refused until the new server runs. To keep 1.x running
   next to the new installation, give the new one its own `--name=<name>` and its own ports instead.
3. **Create the two database accounts** of the new installation with the tools of your DBMS (see above: the application account and
   the DDL account); the 1.x accounts stay as they are. When the new schema lives in the database of the 1.x ledger, set
   `ledger.schema.name` to a schema other than the 1.x schema `ledger`.
4. **Install with the tokens of 1.x.** Add `--from-1x-env=<file>` to the Linux installation:

   ```sh
   sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url> --from-1x-env=/etc/pg-ledger-mcp/env
   ```

   The installer opens the 1.x file as root and `secret-import-1x` moves `DBHUB_AUTH_TOKEN` to `ledger.mcp.token` and
   `ledger.admin.agent-token` and `PGLEDGER_ADMIN_TOKEN` to `ledger.admin.token`, before any token is generated: the clients and the admin
   sign-in of 1.x keep working, and no value is printed. A 1.0.0 installation had no admin web and so no `PGLEDGER_ADMIN_TOKEN`: then only
   the two tokens of the agents are moved, the command says so, and the installer generates `ledger.admin.token` and shows it once.
   A secret that exists is kept. The unit files of 1.x that the new units replace are
   kept as `<file>.1x-backup`. Without a terminal, add `--stdin` and give the passwords on standard input.
5. As the service user, store the password of a read-only account of the 1.x database: `secret-set ledger.import.source.password`, and run
   `import-legacy --owner-account=<account> --ledger.import.source.url=<jdbc-url> --ledger.import.source.username=<account>`.
6. The import reads the 1.x schema read only, copies everything in one transaction and rolls back on any failure; then it compares the
   number of rows and the SHA-256 digest of every table (`--verify-only` repeats the comparison). It refuses when the new schema
   already holds data, and it lists every value of the old data that the new schema cannot hold; it never invents a value.
7. The old grants are copied without a session key. Every author registers a session key with `key-register`; then its grants work.
8. **Check.** `ledger-cli mcp-check --url=<its public URL>` and `import-legacy --verify-only`; open the admin web and sign in with the
   admin token of 1.x.
9. **Roll back** if the check fails: stop the new services, put the `<file>.1x-backup` unit files back in place of the new ones, run
   `sudo systemctl daemon-reload` and start the 1.x services. The 1.x schema and data were only read, so 1.x continues as it was.
10. Clients: the tools `execute_sql_reader` and `search_objects_reader` were removed; the read tools replace them. The results of the write
   tools keep their keys without the `rows[0].result` wrapping. Install the hook jar (a Java 25 runtime) before the server is switched.

## Build and test

JDK 25 and the Gradle wrapper are all that is needed; the wrapper downloads Gradle itself.

```sh
./gradlew build
```

The build compiles with warnings as errors, runs every module test against the PostgreSQL that is installed on the machine
(see the test database below; there is no embedded database and the build does not start one) and checks that no JDBC driver, JPA,
migration tool or Lombok is on a runtime classpath. The documentation tests of
`ledger-cli` (DOC-001 to DOC-029 in [docs/testing/TEST_CASES.md](docs/testing/TEST_CASES.md)) check this README, the Korean one and the
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

The connection is set with Gradle properties, on the command line or in `gradle.properties`. The values below are the defaults,
except the two password file properties, whose defaults are the files of the test machine that are named in the root
`build.gradle.kts`:

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

## Layout

- `ledger-domain`, `ledger-attestation`, `ledger-database`, `ledger-application`, `ledger-mcp-server`, `ledger-admin-web`,
  `ledger-cli`, `ledger-client-hook`: the modules above, each with `src/main` and `src/test`; `ledger-system-test` has `src/test` only.
- `clients/claude`, `clients/codex`, `clients/antigravity`: the manifests and README of each client plugin; the build stages them with
  the hook jar into `ledger-client-hook/build/client-plugins`.
- `docs/`: the test case catalogue, the release note format and the release notes.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: the build.
- `deploy/`: the two installers, `install.sh` (Linux) and `install.cmd` (Windows).
- `bin/`, `web/`, `mcp/`, `sql/`, `tests/*.mjs`: the 1.x implementation (DBHub, Node and PostgreSQL functions), kept in the tree until a later stage removes it. It is not part of 2.x.

## License

From version 2.0, pg-ledger-mcp is licensed under the GNU Affero General Public License v3.0 or (at your option) any later version (`AGPL-3.0-or-later`); see [LICENSE](LICENSE).

Copyright (C) 2026 Jaeseong Song

`AGPL-3.0-or-later` applies to 2.0.0 and to every release published after it, including any later maintenance release of the 1.x line. The MIT License continues to apply only to the copies already published under it, that is v1.0.0 and v1.1.0.

Under section 13 of the license, whoever runs a modified version as a network service must offer the users of that service the corresponding source code.
The admin web shows a link to the source code in the footer of its pages; its address is the setting `ledger.source.url`, by default the
public repository of the project. Whoever runs a modified version sets `ledger.source.url` to the place where the corresponding source of that
modified version is offered. The setting takes an `http` or `https` address; the answers of the MCP server do not carry it.
