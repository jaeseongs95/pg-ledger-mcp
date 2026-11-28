# Installation and configuration

[한국어](installation.ko.md) · [Guide index](guide.md)

## Configuration

Settings are Spring Boot properties: in a file, on the command line (`--name=value`) or from the secret store. Secrets are never
put in a file or in an environment variable; `ledger-cli secret-set <name>` reads a secret with hidden input and stores it as one file
of the secret directory, in the folder of the use of its name, which the processes read as a Spring configuration tree. A secret is
stored under the name of its property, and a name that is no secret of the product is refused (`UNKNOWN_SECRET_NAME`). There is no
command that prints a stored secret.

The secret directory (`<settings folder>/secrets`) has one folder per use, and a process imports only the folders it needs, so the
MCP server never has the secrets of the admin web in its settings and the admin web never has the token of the agents under that name:

| Secret (the file name is the property) | Read by | Folder in the secret directory |
|---|---|---|
| `ledger.jdbc.password` | MCP server, admin web, `ledger-cli`, `ledger-migrate-1x` | `common` |
| `ledger.admin.token`, `ledger.admin.agent-token`, `spring.security.oauth2.client.registration.<id>.client-secret` (OpenID Connect) | admin web | `admin` |
| `ledger.mcp.token` | MCP server, `ledger-cli mcp-check` | `mcp` |
| `ledger.import.source.password` | `ledger-migrate-1x` | `tool` |

The key files of the attachments are not in the secret directory: they are in `<settings folder>/attachment-keys`, below no
configuration tree (Spring Boot makes every file below a tree a property of the process).

| Key | Used by | Meaning |
|---|---|---|
| `ledger.jdbc.url` | all | JDBC URL of the ledger database |
| `ledger.jdbc.username` | all | database account |
| `ledger.jdbc.password` | all | database password (secret) |
| `ledger.jdbc.pool-size` | all | connection pool size, default 10 |
| `ledger.jdbc.connection-timeout-ms` | all | maximum wait for a connection from the pool in milliseconds, default 30000 |
| `ledger.jdbc.idle-timeout-ms` | all | how long an idle connection stays in the pool in milliseconds, default 600000 |
| `ledger.jdbc.keepalive-time-ms` | all | how often idle pooled connections are validated in milliseconds (`0` disables, otherwise at least 30000), default 0 |
| `ledger.jdbc.driver` | all | the registered driver, written by `driver-register` (jar, class, SHA-256), read at start |
| `ledger.schema.name` | all | schema that holds the tables; created by `migrate` when missing; absent means the default schema of the account |
| `ledger.schema.require-utc` | servers, CLI | refuse to start when the JVM or the database session is not UTC, default true |
| `ledger.mcp.token` | MCP server, `mcp-check` | the bearer token of the agents, at least 16 characters (secret) |
| `ledger.mcp.allowed-hosts` | MCP server | accepted `Host` values, default `127.0.0.1` and `localhost` |
| `ledger.mcp.allowed-origins` | MCP server | accepted browser origins, default none |
| `ledger.mcp.max-request-size` | MCP server | largest request body, default 1MB |
| `ledger.mcp.code-poll-interval` | MCP server | how often a code change of another process is looked up, default 5s |
| `ledger.mcp.session-idle-timeout` | MCP server | how long an inactive Streamable HTTP MCP session stays in memory before eviction, default 30m |
| `ledger.mcp.max-sessions` | MCP server | maximum concurrent Streamable HTTP MCP sessions held in memory, default 1000 |
| `ledger.mcp.keep-alive-interval` | MCP server | Streamable HTTP SSE keep-alive ping interval (`0s` disables pings for POST-only clients), default 30s |
| `ledger.admin.token` | admin web | the admin token, at least 16 characters (secret): the bearer of the read-only JSON endpoints under `/api`; with `ledger.admin.sign-in=token` it is also the password of the login page |
| `ledger.admin.agent-token` | admin web | required: the agents' bearer token, equal to `ledger.mcp.token`, and distinct from `ledger.admin.token`; the admin web refuses it with `AGENT_TOKEN_FORBIDDEN` (secret) |
| `ledger.admin.allowed-hosts` | admin web | accepted `Host` values (DNS rebinding guard), default `127.0.0.1` and `localhost` |
| `ledger.admin.driver-file` | admin web | the driver registration file the driver page writes, default `/etc/veridger/jdbc-driver.yaml` |
| `ledger.admin.driver-directory` | admin web | the one directory a driver jar may be taken from, default `/etc/veridger/drivers` |
| `ledger.admin.sign-in` | admin web | how the owner signs in, exactly one of `token`, `oidc` or `passkey`; the login page offers only that one; absent means `passkey` for a new installation, and an installation with `ledger.admin.oidc.*` settings and no choice is refused at start (nothing is guessed) |
| `ledger.admin.oidc.owner-email` | admin web | with `ledger.admin.sign-in=oidc`: the one e-mail address of the owner, which the provider must have verified; the provider itself is `spring.security.oauth2.client.*` |
| `ledger.admin.oidc.issuer` | admin web | with `ledger.admin.sign-in=oidc`: the issuer (`iss`) the address counts for: an address is only meaningful at the issuer that verified it |
| `ledger.admin.passkey.rp-id` | admin web | the host name the passkeys are bound to; default: the one non-loopback host of `ledger.admin.allowed-hosts`, else `localhost` |
| `ledger.admin.passkey.origins` | admin web | the origins the browser shows (scheme, host and port): `https` except `http://localhost`; default `https://<rp-id>`, or `http://localhost:<server.port>` for `localhost` |
| `ledger.admin.lockout.window` | admin web | the period in which credential checks (admin token, passkey sign-in, one-time code) are counted, default 60s |
| `ledger.admin.lockout.global-max-failures` | admin web | credential checks of all callers together within the window before the delay starts, default 50 |
| `ledger.admin.lockout.global-delay` | admin web | the wait before a credential is compared once the budget is used, default 1s; it slows down and never locks or refuses: the right credential always signs in |
| `ledger.admin.mcp.health-url` | admin web | the `/healthz` URL of the MCP server shown on the overview; absent means not configured |
| `ledger.admin.mcp.timeout` | admin web | timeout of that check, default 2s |
| `ledger.admin.update.enabled` | admin web | enable the `/updates` release checker and staging button, default true |
| `ledger.admin.update.repository` | admin web | GitHub repository (`owner/repo`) queried for releases, default `jaeseongs95/veridger` |
| `ledger.admin.update.directory` | admin web | directory where verified update JARs and `request.properties` are staged for the host updater, default `/etc/veridger/update` |
| `ledger.admin.update.schema-migration-marker` | admin web | read-only host marker for automatic PostgreSQL schema migrations, set by the installer |
| `ledger.attachments.directory` | servers, CLI | where the files of the attachments are kept (the installer sets it to `<data folder>/attachments`); when it is not set the attachment tools are not registered |
| `ledger.attachments.key-directory` | servers, CLI | where the key files are kept, outside `directory` (the start fails when it is inside); the installer sets it to `<settings folder>/attachment-keys`, beside the secret directory and not inside it |
| `ledger.attachments.active-key-id` | servers, CLI | the key that new uploads are written with; `setup` writes it into `local.yaml`; the server does not start without the key file |
| `ledger.attachments.max-file-bytes` | servers, CLI | the largest file, default 64MB |
| `ledger.attachments.staging-budget-bytes` | servers, CLI | what all open uploads may declare together, default 512MB |
| `ledger.attachments.attachment-budget-bytes` | servers, CLI | what all kept attachments (and the open uploads) may hold together, default 10GB; over it a new `artifact_begin` is refused and nothing is deleted |
| `ledger.attachments.max-open-per-owner` | servers, CLI | how many uploads of one author may be open, default 4 |
| `ledger.attachments.disk-margin-bytes` | servers, CLI | the space that has to stay free on the disk of `directory` after a file is written, default 64MB (the size of the largest file): when `usable - size` is below it a begin or a chunk is refused with `ARTIFACT_STORAGE_FULL` and the owner has to free the disk |
| `ledger.attachments.upload-ttl` | servers, CLI | how long an upload lives after its last chunk, default 10m |
| `ledger.attachments.orphan-grace` | servers, CLI | how long a file without a row (or a replaced file) waits before `attachments-check` moves it to `orphans/`, default 24h |
| `ledger.attachments.reaper-interval` | MCP server | how often expired uploads are removed, default 1m |
| `ledger.secrets.home` | `ledger-cli` | the directory of the installation that holds the settings, the driver registration, the driver jars and the secret directory, default `/etc/veridger` on Linux and `C:/ProgramData/veridger/etc` on Windows, where the installer of the host puts it |
| `ledger.secrets.directory` | `ledger-cli` | the secret directory (it holds the folders `common`, `admin`, `mcp` and `tool` and is itself no configuration tree), default `secrets` below `ledger.secrets.home` (`/etc/veridger/secrets` on Linux) |
| `ledger.secrets.instance` | `ledger-cli` | the instance name that the default paths follow, default `veridger` |
| `ledger.cli.driver.directory` | `ledger-cli` | the directory `driver-register` takes a jar from, default `drivers` below `ledger.secrets.home` (`/etc/veridger/drivers` on Linux) |
| `ledger.cli.driver.file` | `ledger-cli` | the registration file `driver-register` writes, default `jdbc-driver.yaml` in `ledger.secrets.home` (`/etc/veridger/jdbc-driver.yaml` on Linux) |

The servers listen on loopback only (MCP `127.0.0.1:8765`, admin web `127.0.0.1:8865`), fixed in the code of both: there is no setting
for the address. To publish them, the operator puts a reverse proxy of their choice, which terminates HTTPS, on the same host and
lists the one host name that it passes in the allowed hosts of the service (`--mcp-host`, `--admin-host`). The admin web trusts
`X-Forwarded-For` and `X-Forwarded-Proto` from the loopback address only. The servers read the driver registration file and the secret directory
through `spring.config.import`, which the installers put into the service definition (see Installation); `ledger-cli` reads them by default.

## Installation

Two installers cover three targets: a personal PC and a Windows server run `deploy\install.cmd`, a Linux server runs
`sudo deploy/install.sh`. The targets differ by their options only, and on every one of them both servers listen on the loopback
address only (see Reaching the servers from outside). macOS is not supported; a Linux desktop that is a personal PC follows the
Linux procedure. The build needs JDK 25 and the host needs a Java 25 runtime at a path that every account can execute (not
under `/home`: the service account runs it too).

What every target has in common:

- **One service account.** The MCP server and the admin web run as one operating system account that belongs to no person: on
  Linux the system user `veridger` (no login, not root), on Windows the predefined account LocalService (no password). Its purpose
  is to keep the secrets of the ledger, the admin token and the database password, away from the account of the person who works on
  the machine and from the agents that run under it: the secret directory is readable by the service account only (on Windows: by the installing user and, read only, by LocalService, and by nobody else). The secrets are
  kept in one folder per use and each service imports only its own and the common one, so the process of the MCP server no longer holds
  the admin token or the OpenID Connect client secret in its settings (and no key file of the attachments is a property of either
  service). What this does not do: the two services run as one operating system user, so the file permissions do not keep one service from
  reading the folder of the other; the personal edition trusts the whole installation target, and the change is that no process
  carries a secret of another use in its environment. The two
  services of an installation, called an instance, are named after it: the instance is `veridger` unless `--name=<name>` gives
  another name, and its services are `<name>` and `<name>-admin` (`veridger` and `veridger-admin` by default). The Linux user, the
  directories (`/opt/<name>`, `/etc/<name>`, on Windows `C:\ProgramData\<name>`) and the WinSW ids follow the name, so a second instance, or an
  installation next to a host that still runs 1.x under the default names, needs only its own `--name=<name>` and its own ports.
- **Two database accounts**, created beforehand with the tools of your DBMS (creating an account is not ANSI SQL and is not part of the
  product). The application account is used by both servers and by the owner commands of `ledger-cli`; `migrate` gives it the least
  privilege of `ledger-database/src/main/resources/db-templates/dml-grants.sql` (no DDL, no `UPDATE` or `DELETE` on the history). The
  DDL account is used only by `migrate` and `setup`; its password is typed with hidden input and is never stored. The
  session time zone of the database must be UTC, and the application account must be allowed to use the schema, a step of your DBMS
  that is not part of the product SQL: `setup` checks it and says so when it is missing (see First setup).
- **Jars and the driver.** Build the jars with `./gradlew assemble` (the jars only; `./gradlew build` also runs the tests, which need a PostgreSQL of their own, see Build and test) and collect the jars in one directory: the three jars of the host, and
  `ledger-migrate-1x.jar`, the tool that moves a 1.x installation (see Upgrading from 1.x). The installers use the three jars of the host only; they do not know the
  tool and do not copy it, because it is no part of the services. The JDBC driver of your database is obtained by you, and you name its class; it is not bundled.

```sh
mkdir dist
cp ledger-cli/build/libs/ledger-cli.jar ledger-mcp-server/build/libs/ledger-mcp-server.jar ledger-admin-web/build/libs/ledger-admin-web.jar ledger-migrate-1x/build/libs/ledger-migrate-1x.jar dist/
```

- **What an installer does.** The installers are thin: they start only what Java cannot do, and everything else is a command of
  `ledger-cli`. Java does the work on the files of the host: `render-deploy --check` validates every option first, and
  `prepare-host` creates the folders with their owners, modes and ACLs, copies the jars and the driver jar and writes the service
  definitions and the settings (`application.yaml` and one file per service in one directory; your own settings belong in
  `local.yaml` there, which is read last and never replaced). The installer then has the secrets stored by `ledger-cli`: the
  database password of the application account (hidden input), `ledger.admin.token` and `ledger.mcp.token` (generated, shown once,
  at the end of their step) and the copy `ledger.admin.agent-token`; it registers the driver, runs `setup` with the DDL account (the
  schema, the grants and the seed codes, see First setup) and starts the two services. What stays in the scripts, because Java cannot do it, is creating the operating system user (`useradd`),
  starting a command as that user (`runuser`) and the service managers (`systemctl`, WinSW and `sc.exe`).
  The Java 25 check is the JVM's own: the jars are compiled for Java 25, and no other runtime loads them. Nothing is downloaded or
  installed. Running it again with newer jars is the upgrade: files and services are replaced, the schema and the grants are applied
  again, and secrets, tokens and data are kept. A run that stopped half way is repaired the same way: run it again with the same
  options (the commands that store secrets do not need the database). The settings directory is `ledger.secrets.home` of the tool; the
  services read the same sources through `spring.config.import`.

The options of `deploy/install.sh` are given as `--name=value`; the ones it does not read itself go on to `render-deploy`:

| Option | Meaning |
|---|---|
| `--name=<name>` | the name of the instance, default `veridger`: lower-case letters, digits and hyphens, at most 23 characters, not ending with `-admin`; Java validates it before anything is created |
| `--java=<path>` | the Java 25 runtime, default the `java` on the `PATH` of root; the service user runs it too, so it is at a path every account can execute (not under `/home`), which the script checks right after it creates the service user and before it makes anything else |
| `--jars=<dir>` | optional: the directory that holds the three jars; when omitted, the installer downloads the release jars and verifies `SHA256SUMS` automatically |
| `--version=<tag>` | optional: release tag to download when `--jars` is omitted (`latest` or `vX.Y.Z`), default `latest` |
| `--repo=<owner/name>` | optional: GitHub repository to download releases from when `--jars` is omitted, default `jaeseongs95/veridger` |
| `--pg-dump=<path>` | optional: enable automatic PostgreSQL schema updates using this absolute path to `pg_dump` |
| `--driver-jar=<file>` | the JDBC driver jar |
| `--driver-class=<class>` | the driver class, which must accept the JDBC URL (for the PostgreSQL JDBC driver, `org.postgresql.Driver`) |
| `--owner-account=<account>` | the DDL account (its password is asked for) |
| `--app-account=<account>` | the application account |
| `--jdbc-url=<url>` | the JDBC URL of the ledger database (no password in it) |
| `--schema=<name>` | `ledger.schema.name`, optional |
| `--mcp-port=<n>` | the loopback port of the MCP server, default 8765 |
| `--admin-port=<n>` | the loopback port of the admin web, default 8865 |
| `--mcp-host=<host>` | optional: the one host name (lower case, no port) that the reverse proxy of the operator passes in the `Host` header for the MCP server; it is allowed in the `Host` check of the MCP server besides `127.0.0.1` and `localhost`, which are always allowed. Without it the server is reached on loopback only |
| `--admin-host=<host>` | optional: the same for the admin web, and a name other than `--mcp-host`. With it, the installer also writes `ledger.admin.passkey.rp-id` (the name) and `ledger.admin.passkey.origins` (`https://<name>`) into `admin.yaml`, so the passkeys of a remote installation work without a hand edit (set `rp-id` and `origins` in `local.yaml` for another arrangement) |
| `--stdin` | read the passwords from standard input instead of the terminal: first the one of the application account (only while it is not stored), then the DDL one; the convention of `docker login --password-stdin` |

`render-deploy` and `prepare-host` take five more options that the installers set themselves: `--platform=<platform>`
(`linux`, the default, or `windows`), `--home=<path>` (Windows: the installation directory), `--java=<path>` (the Java executable),
`--root=<directory>` (put the files below a directory instead of at their paths, for a look before they are put in place) and, for
`render-deploy` only, the flag `--check` (validate every option and change nothing; the installers run it first, so that a bad value,
the name included, stops them while nothing exists yet). `prepare-host` also takes `--jars=<dir>`, `--driver-jar=<file>` and, on
Windows, `--winsw=<file>`. Without `--root` the platform has to be the platform of the host that runs the command: the paths of the
other one are no paths of this host and would end up below the working directory, so the commands refuse them (naming `--platform`)
before anything is read or written.

### Linux server

Run as root; the user `veridger` is created when it is missing, `prepare-host` (Java) creates `/opt/<name>` (root, 0755),
`/etc/<name>` (the service user, 0750), its `drivers` folder (root, 0755), its `secrets` folder with the folders `common`, `admin`, `mcp` and `tool` and the folder `attachment-keys` (the service user, 0700), copies the
jars and the driver and renders the units into `/etc/systemd/system`, which are then enabled; the secrets are stored by the service
user. A file that the installer did not render and that a rendered file replaces is kept as `<file>.backup`; to move a 1.x installation,
see Upgrading from 1.x.

```sh
sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url>
```

The installer enables and restarts the two services (`<name>` and `<name>-admin`, `veridger` and `veridger-admin` by default) and installs the least-privilege host updater (`veridger-updater.sh` triggered by a systemd `.path` unit); check them with `systemctl status veridger veridger-admin`. When `--jars` and required options are omitted in an interactive terminal, `deploy/install.sh` downloads and verifies `SHA256SUMS` and prompts for missing values.

### Reaching the servers from outside

Both servers listen on `127.0.0.1` only, and no setting changes that. To reach them from another machine, the operator runs a
reverse proxy and a TLS certificate on the same host, with a tool of the operator's choice; the installers write no proxy
configuration, order no certificate and install no renewal. The proxy connects to `127.0.0.1:8765` (MCP server) and
`127.0.0.1:8865` (admin web) and passes the `Host` header of the client on. Give each service the one host name that the proxy
passes for it, `--mcp-host=<host>` and `--admin-host=<host>`: the name is allowed in the `Host` check of that service, besides
`127.0.0.1` and `localhost`, which are always allowed. With a name for the admin web, the installer also writes the passkey settings
for it (see the options above). The admin web trusts `X-Forwarded-For` and `X-Forwarded-Proto` from the loopback address only, so the
proxy has to overwrite them with the address and the scheme of the connection it received (for redirect and WebAuthn origin checks and
request logging; credential throttling uses `ledger.admin.lockout.global-delay` and never locks per client address). Long-lived MCP connections (Streamable
HTTP) need a timeout of the proxy that is long enough. The certificate, its renewal and the firewall are the operator's too.

### Windows: a personal PC or a server

Run in an elevated prompt. The script takes its arguments in this order, and the services are registered with WinSW, the way
Spring Boot's documentation describes for Windows services; you obtain the WinSW 2.x executable yourself, as you do the JDBC driver,
and the installer neither bundles nor downloads it.

| Argument | Meaning |
|---|---|
| `[--stdin]` | optional, and only as the first argument: read the passwords from standard input instead of the terminal (first the application account, only while its password is not stored, then the DDL account), as `install.sh --stdin` does; the other arguments keep their order |
| `<jars-dir>` | the directory that holds the three jars |
| `<winsw-exe>` | the WinSW 2.x executable |
| `<driver-jar>` | the JDBC driver jar |
| `<driver-class>` | the driver class |
| `<ddl-account>` | the DDL account |
| `<app-account>` | the application account |
| `"<jdbc-url>"` | the JDBC URL, in quotes |
| `"<name>"` | optional, in quotes: the name of the instance, default `veridger` |
| `"<more options>"` | optional, in quotes, after the name: more options of `render-deploy`, for example the host names of a server |

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>"
```

A server that is reached from outside adds the host name of each service and puts a reverse proxy that you have prepared in front of the
two loopback ports (see Reaching the servers from outside):

```bat
deploy\install.cmd dist <winsw-exe> <driver-jar> <driver-class> <ddl-account> <app-account> "<jdbc-url>" "veridger" "--mcp-host=<mcp-host> --admin-host=<admin-host>"
```

For an interactive installation that downloads and verifies `SHA256SUMS` automatically, run `deploy\install.cmd --download`. The installer also registers the host updater script (`veridger-updater.cmd`) as a Scheduled Task for Admin Web updates.

#### Automatic PostgreSQL schema updates

Run the current installer once with `--pg-dump=/absolute/path/to/pg_dump` on Linux or set `VERIDGER_PG_DUMP` to the absolute path of `pg_dump.exe` before running the Windows installer. Use a client from the database server major version and a single-host PostgreSQL JDBC URL with a database name. The URL can include `sslmode`. The installer uses the supplied database owner credential to set up the database and stores it in an updater-only directory. Existing installations also need this one-time setup. Re-run it after changing the endpoint, schema, or owner credentials.

For an update within the current major version, the host compares the migrations packaged in the installed and verified release JARs. It stops both services for a schema change, makes a custom-format database backup, applies the new migrations and application grants, and starts and checks both services. Backups are kept in `/opt/<name>/backup-*` on Linux and `C:\ProgramData\<name>\lib\backup-*` on Windows.

If an update fails after migration begins, the recovery marker `.schema-update-in-progress` in the installation's JAR directory points to the backup and the target version. Examine `backup.log` and `migration.log` there. Recover either by completing the target migration and verifying both services, or by restoring the dump to a compatible database and restoring the matching JARs. Remove the marker only after the database and applications are running a consistent version. Major-version upgrades still use the documented installation procedure.

Everything lives in `C:\ProgramData\<name>`. Both services run as LocalService, restart after a failure (the WinSW
configuration), and write their logs to the `logs` folder. `prepare-host` closes the folders to every account but SYSTEM and
Administrators and gives LocalService its access in the same step (Java, with ACLs, no `icacls`): it reads and runs the jars and
changes the settings, the logs and the data. The secrets are the installing user's: the folder of the secrets, its folders of the uses, the folder `attachment-keys` and each secret made in
them are created with an ACL for the installing user (everything) and for LocalService (read only), and for nobody else, so the
services read them and the accounts the agents run as cannot. Nothing is handed over to LocalService (the installing user stays the owner and
no privilege to take ownership is used), so the owner commands of `ledger-cli` keep working for the installing user, and a re-run of the
installer finds everything as it was; run it again as the same user (the personal edition has one user). The admin web is where the
owner works too (see Admin web: sign in with a passkey or OpenID Connect, and register the first passkey with `passkey-code`). On a
personal PC open the admin web at `http://localhost:8865` (the port is `--admin-port=<n>`): the admin web sets a `Secure` session
cookie, which the major browsers accept from `localhost` because they treat it as a secure context, and a passkey works on `localhost`
without HTTPS.

### First setup

The installers call `setup`, and you run it yourself after an upgrade or against a database that you prepared by hand. A new ledger
has to start with some data, so `ledger-cli` carries a seed bundle: the file `ledger-seed.mv.db`, which the build writes with a Java
program from the text files `src/seed/issue-categories.tsv` and `src/seed/install-defaults.properties` of the module `ledger-cli`
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

The defaults of an installation are written in one place, the seed bundle. `render-deploy` and `prepare-host` take the name
and the ports from it when an option is not given, and the installers ask for them with `install-default <name>` (`instance-name`,
`mcp-port` or `admin-port`), so no script holds a second copy.

### After the installation

1. Check the MCP server with `ledger-cli mcp-check`; without a registered driver the MCP server stops and says why, and the admin web
   starts in a setup mode that offers only the login page, which tells you to register the driver with `ledger-cli driver-register`
   (without a database there is no passkey to sign in with, so the driver page of the admin web is a console page).
2. Set up the owner side: `project-add` and `code-set`; when you come from 1.x, see Upgrading from 1.x instead of creating projects by hand. On Linux run
   `ledger-cli` as the service user, for example `sudo -u <name> java -jar /opt/<name>/ledger-cli.jar --ledger.secrets.instance=<name> project-add ...`.
3. Install the client hooks (see Client hooks) and issue session grants directly with `grant` or on the grants page of the admin web.

`migrate` applies the files `V{n}__*.sql` in order and records every attempt, with the SHA-256 of the file, in the table
`schema_history`: one row for each attempt of a version, with the columns `version`, `attempt`, `script`, `digest`, `applied_at` and
`outcome` (`SUCCEEDED` or `FAILED`). An attempt is one transaction. It starts by inserting its own row as `FAILED` and ends by turning that
one row to `SUCCEEDED`; if the file fails, the attempt is rolled back, the row stays `FAILED`, and the next run tries the version again as
the next attempt without changing or deleting the earlier rows. Two `migrate` runs at the same time are safe: the second waits in the
database for the first and then skips what the first has applied. A file that was changed after it was applied stops `migrate` before it
applies anything, and every process at start; no row is added for it. The database must use the encoding UTF-8, which `migrate` proves with a probe query of the dialect before it changes anything, and the long text type of
the schema files is decided by the database dialect of the ledger, which `migrate` takes from the product name of the connection (there is
no setting for it). Each declaration is taken from the official documentation of the database it is for, and where a database sets a small
default limit when the length is left out, the declaration carries a length. The type information of the JDBC driver is not used, because a
driver may report it in a form that cannot be judged.

The ledger does not use Flyway: a current Flyway release needs a module for each DBMS and refuses a database it has none for, while the
ledger asks for ANSI SQL on any database whose driver you register (PostgreSQL is the database that is tested; other products are not tested). And the ledger migrates by this explicit command and not when a server
starts, because the servers never hold the password of the DDL account: a server only checks the schema at start and, when files are
pending, refuses to start and names `ledger-cli migrate`, so a server that is taken over cannot change the schema.
