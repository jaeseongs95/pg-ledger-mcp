# Migrating from 1.x

[한국어](migration.ko.md) · [Guide index](guide.md)

## Upgrading from 1.x

1.x was a DBHub and Node implementation on PostgreSQL functions; 2.0.0 replaces all of it. Existing data is moved, not converted in
place. The 1.x schema stays and can be used to go back. See the [release notes](release-notes/2.0.0.md) for the contract changes.

A caller of 1.x has to make these changes to its tool calls; 2.0 accepts no old form next to the new one:

- `request_id` and `batch_request_id` are replaced by the required `operationKey` (1 to 128 characters) on every write tool; the old
  keys are refused as unknown arguments, a call without `operationKey` is refused, and the server never makes a key up. An operation inside
  `ledger_batch` carries no key of its own.
- The `author_id` argument is gone; the author is the host attestation.
- 1.x `fields` maps are replaced by flat tool parameters, and an object or array argument (`ops`, `files`) must be sent as a JSON object or array, not as a JSON document in a string.
- The text `null` is a text, not a missing argument; omit an argument that has no value.

Version 2.0 itself knows nothing of 1.x: its services, its installers and `ledger-cli` read no 1.x setting, file or table. The move is
done by a separate tool, the module `ledger-migrate-1x`. The build makes it as `ledger-migrate-1x/build/libs/ledger-migrate-1x.jar`, and
it is kept in the distribution folder `dist` next to the other jars (the `cp` step of Installation). The installers do not know it and
do not copy it, because it is no part of the services. It is run from there with the Java 25 runtime of the installation, as
`java -jar dist/ledger-migrate-1x.jar <command> [options]`. The folder `dist` has to be readable by the service user (not under `/home`), because the
commands that use the secret store run as that user. The tool reads the settings of the installation the way the services do (the settings
directory `ledger.secrets.home`, with `application.yaml`, `local.yaml`, the driver registration and the secret directory); for an
instance with another name give `--ledger.secrets.instance=<name>`. A command that needs the ledger database needs the registered JDBC
driver; `stop-1x` and `import-secrets-1x` need none.

| Migration command | Syntax | What |
|---|---|---|
| `stop-1x` | `stop-1x [--name=<instance>] [--platform=<platform>] [--unit-dir=<dir>] [--env=<file> [--mcp-host=<host>] [--admin-host=<host>]]` | first step: stop and disable the two services of 1.x, `<name>` and `<name>-admin`, and keep each definition as a backup next to it (`<file>.1x-backup`); with `--env` it first checks the public host names of 1.x |
| `import-secrets-1x` | `import-secrets-1x --env=<file> [--replace]` or `import-secrets-1x --stdin [--replace]` | after the installer: move the secrets of 1.x into the secret store of 2.0; with `--replace` they replace the tokens that the installer generated |
| `import-1x` | `import-1x [--verify-only] [--owner-account=<account>] [--stdin]` | copy the 1.x ledger into the new schema in one transaction and compare every table by row count and SHA-256; `--verify-only` repeats only the comparison |
| `retire-1x` | `retire-1x [--confirm=<sha256> --backup-tested]` | after you accept 2.0, remove what the 1.x installer made in the database; without options it lists it and changes nothing |

The order of an upgrade is: back up, `stop-1x`, prepare the database accounts, install 2.0 with the installer, `import-secrets-1x`, restart
the services, `import-1x`, check, and, only after you accept 2.0, `retire-1x`. The 1.x service stays on the 1.x database until it is stopped, and
the installer of 2.0 stops nothing of 1.x. Do the steps in this order.

1. **Back up.** Dump the ledger database with the dump tool of your DBMS, and copy the 1.x settings directory `/etc/pg-ledger-mcp`
   (the file `/etc/pg-ledger-mcp/env` holds the 1.x tokens and passwords) and the 1.x unit files in `/etc/systemd/system`.
2. **Stop the 1.x services with `stop-1x`.** Run it first, as root, on the host of 1.x. Given the 1.x environment file and the host options
   (the same ones that you will give the installer of 2.0 in step 4), it checks the public host names of 1.x before it stops anything:

   ```sh
   sudo java -jar dist/ledger-migrate-1x.jar stop-1x --env=/etc/pg-ledger-mcp/env --mcp-host=<mcp-host> --admin-host=<admin-host>
   ```

   - **What it changes.** It stops and disables the two services of 1.x, `<name>` (the MCP server) and `<name>-admin` (the admin web, which
     1.0.0 did not have); `<name>` is `--name=<instance>`, default `pg-ledger-mcp`. It keeps the definition of each next to it as a backup: the
     unit file `<unit-dir>/<service>.service` is moved to `<service>.service.1x-backup`; the directory is `--unit-dir=<dir>`, default
     `/etc/systemd/system`. 1.x had Linux (systemd) installations only, so there is nothing to stop on Windows: `--platform=windows` is refused
     with `NO_1X_SERVICE_WINDOWS`, and a Windows service of that name is a service of 2.0 or of the system, which the tool never touches.
     The 1.x database and the 1.x environment file are not touched.
   - **What it reads.** The definitions of the two services and, with `--env=<file>`, the host names that the 1.x environment file allows.
   - **Why it starts a process.** Java has no API for the service manager of the host, so the command runs `systemctl`
     with fixed arguments (`stop`, `disable` and `daemon-reload`) and the validated name, in one place of its code.
     It touches only a service called `<name>` or `<name>-admin` whose definition is the one of 1.x: a unit of 1.x has an `EnvironmentFile=`
     line that names `/etc/<name>/env`.
     Every service is checked before the first change; otherwise the command stops with `NOT_1X_SERVICE` and has changed nothing.
   - **The public host names of 1.x.** The clients reach the 1.x server through the host names that the 1.x setting `DBHUB_ALLOWED_HOSTS`
     allows (the admin web of 1.1.0: `PGLEDGER_ADMIN_ALLOWED_HOSTS`), for example the name of your reverse proxy. The `Host` check of 2.0 allows
     `127.0.0.1`, `localhost` and the one name of `--mcp-host` and the one name of `--admin-host` only (the installer takes one host name for each
     service), so a name of the file that the clients still use and that is not carried would be refused with HTTP 403 right after the migration.
     With `--env=<file>` the command therefore tells, before it stops anything, each public host name of the file that the option does not carry
     as a name that 2.0 no longer accepts, naming the host names and the option; it refuses nothing for them and goes on. Give the one name that
     your proxy passes to the option (`--mcp-host=<name>`, one host name of the installer's form: lower case, no port, no list; `--mcp-host` can be given
     without `--admin-host`), and give the same options to the installer. The loopback names need no option, and nothing else of the file
     is printed. The two host options without `--env` are refused.
   - **Run it again** when it stopped half way: a service that was stopped before is reported as such, a backup file that holds other bytes
     than the definition is never replaced (`BACKUP_DIFFERS`), and a failed call of the service manager (`SERVICE_COMMAND_FAILED`) leaves what
     was done and the next run continues. The clients of 1.x are refused from now on, until the server of 2.0 runs. To keep 1.x running
     next to the new installation instead, do not run `stop-1x`, and give the new one its own `--name=<name>` and its own ports.
3. **Create the two database accounts** of the new installation with the tools of your DBMS (see Installation: the application account and
   the DDL account); the 1.x accounts stay as they are. When the new schema lives in the database of the 1.x ledger, set
   `ledger.schema.name` to a schema other than the 1.x schema `ledger`.
4. **Install 2.0** with the installer of your host (see Installation). The installer knows nothing of 1.x; give it the host names that
   you gave `stop-1x`:

   ```sh
   sudo deploy/install.sh --jars=dist --driver-jar=<driver-jar> --driver-class=<driver-class> --owner-account=<ddl-account> --app-account=<app-account> --jdbc-url=<jdbc-url> --mcp-host=<mcp-host> --admin-host=<admin-host>
   ```

   The installer generates tokens of its own (each shown once) and starts the two services with them.
5. **Move the secrets of 1.x with `import-secrets-1x`.** The secret store belongs to the service account, which the installer creates, so
   this runs after the installer. The 1.x file is owned by root and the secret store by the service user, so root opens the file and pipes
   it to the tool, which runs as the service user:

   ```sh
   sudo sh -c 'runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar import-secrets-1x --replace --stdin < /etc/pg-ledger-mcp/env'
   ```

   - **What it reads.** The 1.x environment file, given with `--stdin` (as above) or with `--env=<file>` for an operator who can read the file as the
     service user; exactly one of the two. Only the token of the agents is read, and its value is checked before anything is written (at least
     16 characters). It uses no database.
   - **What it changes.** The installer has generated tokens of its own already, so without `--replace` a secret that exists is kept. With `--replace`
     the two secrets of the agents take the 1.x value: `DBHUB_AUTH_TOKEN` of 1.x becomes `ledger.mcp.token` and `ledger.admin.agent-token`.
     The clients of 1.x keep working. Only the names of the secrets are printed, never a value.
   - **The admin token is not imported.** `PGLEDGER_ADMIN_TOKEN` of 1.x is not carried over (when the 1.x environment has it, the command says so): the
     admin token of 2.0 is the `ledger.admin.token` that the installer made with `token-generate` (256 bits from `SecureRandom`, shown once), because
     a token chosen by hand may be weaker and the limit on credential checks of 2.0 is a delay that counts on a token that cannot be guessed. It stays
     the bearer of the read-only endpoints (it signs a browser in only with `ledger.admin.sign-in=token`).
   - For an instance with another name the service user is that name (`runuser -u <name>`), and the tool takes `--ledger.secrets.instance=<name>`.

   The services read their secrets at start, so restart the two services of 2.0:

   ```sh
   sudo systemctl restart veridger veridger-admin
   ```

6. **Import the ledger with `import-1x`.** Store the password of a read-only account of the 1.x database as the service user, then run the import
   as the DDL account; the settings of the 1.x source are described in the table below:

   ```sh
   sudo runuser -u veridger -- java -jar dist/ledger-cli.jar secret-set ledger.import.source.password
   sudo runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar import-1x --owner-account=<ddl-account> --ledger.import.source.url=<jdbc-url> --ledger.import.source.username=<account>
   ```

   The import writes the whole ledger; `--owner-account=<account>` runs it as the DDL account, whose
   password is typed with hidden input and is not stored (`--stdin` takes it from the first line of standard input instead and needs `--owner-account`).
   It reads the 1.x schema read only and writes the new schema in one transaction; see What the import does below.
7. **Review imported grants.** The grants of 1.x are copied directly and work as soon as the client hook stamps the session author id.
8. **Check.** Run `ledger-cli mcp-check --url=<its public URL>` and `import-1x --verify-only` (it needs the settings of the 1.x source and
   changes nothing), and run `ledger-cli passkey-code` and register your first passkey in the admin web (the admin token of the installer signs a
   browser in only with `ledger.admin.sign-in=token`; the default is `passkey`). Clients: the tools `execute_sql_reader` and `search_objects_reader` were removed; the read tools replace them. The results of the write
   tools keep their keys without the `rows[0].result` wrapping. Install the hook (the two jars of the plugin folder and a Java 25 runtime) on every client host before the server is switched.
   When the checks have passed, the read-only account of the 1.x database that was made for the import may be dropped.
9. **Roll back** if the check fails: stop the services of 2.0, put each `<unit>.1x-backup` back under its own name in place of the unit of 2.0
   (copy it, so that the backup stays), run `sudo systemctl daemon-reload`, and run `sudo systemctl enable --now <name>` and the same for
   `<name>-admin` to start the 1.x services with their previous configuration. `stop-1x` prints these steps. The 1.x database
   was only read, so 1.x continues as it was.
10. **Retire the 1.x database objects with `retire-1x`, after you accept 2.0.** The kept units (`<unit>.1x-backup`) and the 1.x database are the way
    back until then, and nothing deletes them before. The 1.x installer made functions, triggers, views, tables, a schema and
    four database roles in your database, and `import-1x` only reads them. Make a `pg_dump` backup of the 1.x database and test the restore of that 1.x backup: after the next step it is the
    only way back. Stop the 1.x service (`stop-1x`), store the password of an account that owns the 1.x objects (the owner role of the 1.x installer
    or a role that has its rights) with `secret-set ledger.import.source.password`, set `ledger.import.source.username` to that account
    and run `retire-1x` without options:

    ```sh
    sudo runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account>
    ```

    It checks that the ledger of 2.0 still holds what 1.x held (2.0 may have been used since: the rows it added and the counters it
    moved on are not looked at, a row it edited is accepted, and history, cases and every row still at its imported version must be as
    imported; `import-1x --verify-only` is stricter and reports what 2.0 added or changed as a difference), that no other session runs as a 1.x
    role or holds a lock in the 1.x schema and that the account owns the objects; it prints every object in the order it is dropped and
    the SHA-256 of that list, and changes nothing. Read the list, then run `retire-1x --confirm=<sha256> --backup-tested`:

    ```sh
    sudo runuser -u veridger -- java -jar dist/ledger-migrate-1x.jar retire-1x --ledger.import.source.username=<owner-account> --confirm=<sha256> --backup-tested
    ```

    The command cannot check the backup, so `--backup-tested` is your statement that the 1.x backup was made and its restore was tested. It drops
    the triggers, views, tables, functions, sequences and the schema in one transaction, and then the roles of
    `ledger.import.source.roles`; a role that is the account of the run or of the 2.0 settings, has other members or owns objects
    elsewhere stays and is listed as `SKIPPED`. The ledger never runs this by itself.

### What the import does

- It reads the 1.x schema read only, copies everything in one transaction and rolls back on any failure; then it compares the
  number of rows and the SHA-256 digest of every table (`--verify-only` repeats the comparison). It runs once, on the schema that
  `migrate` and `setup` made, which is the state after the installer (the installer runs `setup`: the seed codes and the audit rows of
  that are accepted, and a seed code that 1.x also has takes the 1.x values). It refuses when a table of ledger data, a session key or
  an earlier import is already there, and it lists every value of the old data that the new schema cannot hold; it never invents a value.
- The ids of 1.x are kept as they are: an id of a task or a condition is 1 to 120 characters without whitespace or control
  characters, in any case and with any characters (`:`, `/`, `#`, `.`, `_`, `-` ...); defect ids keep their form (`<CODE>-D-0001`). Ids of one
  kind that differ only in case are one id to the new schema (a lookup compares the id exactly, and the key of the id in upper case is
  unique on every DBMS), so the import refuses such a pair and lists it.
- An empty text of 1.x (`purpose`, `success_condition`, `behavior`, `root_cause` and `condition_text` allowed `''`) is stored as NULL and
  the tools return an empty string for it, as 1.x did. The `code` of a condition (200 characters) and the `ci_ref` (2000 characters) are
  longer than in the earlier 2.0 builds, and the `origin_ref` of a task is a long text (up to 20000 characters when written), so every value of 1.x fits.
- 1.x stored every relation with its smaller id first and kept nowhere which defect the caller named first, so the import copies the
  relations of the directed kinds (`caused_by`, `blocks`) as they are stored and never guesses; it lists them (the count and the ids,
  also in the audit row and with `--verify-only`). Check each one and, where it is the wrong way round, remove it and link it again with
  `ledger_link` with the right defect first.
- The grants of 1.x are copied without a session key (step 7). The request receipts of 1.x are not carried over: every write of 2.0 needs an
  `operationKey` and refuses the old `request_id` as an unknown argument, so a 1.x answer that was lost cannot cause a second write
  in 2.0. The `request_id` of a 1.x history row is kept as its `operation_key`. The history rows that come from 1.x
  carry the neutral names `IMPORT`, `IMPORTED_NOTE`, `IMPORTED_CHANGE`, `IMPORTED_LOGIN`, `IMPORTED_SHARED` and `IMPORTED_UNKNOWN`, and the field `legacy_ref` of 1.x is
  `source_ref` in the read tools.

### Settings of the 1.x source

The rows `ledger.import.source.*` belong to the commands `import-1x` and `retire-1x` and to no other part of 2.0. Put them in `local.yaml` of the settings
directory or give them on the command line as `--name=value`; the password is a secret and is never put in a file.

| Key | Used by | Meaning |
|---|---|---|
| `ledger.import.source.url` | `import-1x`, `retire-1x` | JDBC URL of the database that holds the 1.x ledger |
| `ledger.import.source.username` | `import-1x`, `retire-1x` | a read-only account of that database (`retire-1x` needs an account that owns the 1.x objects) |
| `ledger.import.source.password` | `import-1x`, `retire-1x` | its password (secret) |
| `ledger.import.source.schema` | `import-1x`, `retire-1x` | the 1.x schema, default `ledger` |
| `ledger.import.source.roles` | `retire-1x` | the database roles of the 1.x installer that `retire-1x` removes after the schema, default `ledger_owner`, `ledger_author`, `ledger_reviewer`, `ledger_reader` |

**Upgrade note: the admin web sign-in.** If you signed in to the admin web with OpenID Connect, say so: set `ledger.admin.sign-in=oidc`
(an installation with `ledger.admin.oidc.*` settings and no `ledger.admin.sign-in` is refused at start with a message that names it),
and give the one e-mail address of the owner, `ledger.admin.oidc.owner-email`, with the issuer it counts for,
`ledger.admin.oidc.issuer`. If you sign in with a passkey (`ledger.admin.sign-in=passkey`, the default), enroll it once with
`passkey-code`; once signed in, owner mutations are protected by the session and CSRF token without per-action passkey re-approval.
