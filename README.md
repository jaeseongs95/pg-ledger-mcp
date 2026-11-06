# pg-ledger-mcp

A PostgreSQL ledger for work done by AI agents, served as one MCP endpoint.
It records defects, tasks, fixes and their verification, along with who did each one and why.

Agents read and write through MCP tools. They never get a database login of their own.

- **Role logins, not per-agent accounts.** There are three logins: `ledger_author`, `ledger_reviewer` and `ledger_reader`.
  Each MCP tool is bound to the login it needs, and the caller never chooses one.
- **Every write names its author.** `author_id` is `<vendor>:<session-id>`, for example `claude:1f2e3d4c` or `codex:9a8b7c6d`.
  The ledger owner is `user:owner`. Any lowercase vendor name is accepted; there is no vendor list to maintain.
- **Writes need a live grant.** The owner grants an author a scope on a project with `bin/ledger-admin grant`.
  The scopes are `defect`, `task`, `fix` and `verify`. Writes without a matching grant are refused.
- **Verification is separate.** `VERIFIED`, `MET` and `NOT_MET` can only be set through the reviewer login.
  A fix must be verified by a session other than the one that recorded it.
  `VERIFIED` also needs an evidence link and a fix whose CI concluded `success`.
- **History is append-only.** Every write adds a `ledger.history` row: when, author, login, operation, state change and reason.
  Mistakes are marked with a `CORRECT` row. Triggers refuse `UPDATE`, `DELETE` and `TRUNCATE` on history, even for the owner.
- **Retries are safe.** Pass `request_id`, a UUID. Repeating the same request returns the stored result and does not write twice.
- **Optimistic locking.** Edits and transitions carry `expected_version`.
- **Projects.** Each defect belongs to a project and gets the id `<CODE>-D-0001`, `<CODE>-D-0002`, … automatically.

Every rule above is enforced inside PostgreSQL by constraints, `SECURITY DEFINER` functions, grants and triggers.
The MCP layer is a thin configuration of [DBHub](https://github.com/bytebase/dbhub).

## MCP tools

| tool | login | what |
|---|---|---|
| `execute_sql_reader` | reader | any read-only SQL (read-only transaction, at most 1000 rows) |
| `search_objects_reader` | reader | list schemas, tables, columns |
| `ledger_whoami` | author | is this author id well formed, which grants does it hold |
| `ledger_save` | author | create or edit a defect, task or condition |
| `ledger_transition` | author | change state (OPEN, SOURCE_FIXED, MERGED, ACTIVE, REPORTED_DONE, …) |
| `ledger_record_fix` | author | record a fix commit and its CI result; OPEN → SOURCE_FIXED |
| `ledger_link` | author | link defects, tasks and conditions |
| `ledger_correct` | author | mark a history row as wrong |
| `ledger_batch` | author | up to 200 of the operations above in one transaction: all commit or none |
| `ledger_review` | reviewer | VERIFIED / MET / NOT_MET, and reopening them |
| `ledger_review_correct` | reviewer | mark a verification row as wrong |

Useful views: `ledger.v_defect` (state, fixes, tasks, last change), `ledger.v_history`, `ledger.v_attention`
(rows that need a look, such as an OPEN defect that already has a fix or a VERIFIED defect without CI).

## Install

Requirements: Linux with systemd, PostgreSQL 15+ (and its psql) on the same host, Node.js 22.5+ and npm, run as root.
The ledger logins connect to `127.0.0.1` with a password, so `pg_hba.conf` must allow `host … 127.0.0.1/32 scram-sha-256`.
The Debian and Ubuntu defaults already allow it.

```sh
sudo -u postgres createdb ledger
sudo deploy/install.sh --db ledger --mcp-port 8765 --allowed-hosts ledger-mcp.example.com
sudo bin/ledger-admin project-add web WEB "Web shop"
sudo bin/ledger-admin category-add BUG Bug "Behaviour differs from the specification"
sudo bin/ledger-admin grant claude:1f2e3d4c web defect fix --note "fix sprint"
sudo bin/ledger-admin grant codex:9a8b7c6d web verify
```

`install.sh` does the following:

1. Creates the roles, the `ledger` schema and the functions.
2. Generates passwords and a bearer token into `/etc/pg-ledger-mcp/env` (mode 0600, root).
3. Installs DBHub 1.5.1 under `/opt/pg-ledger-mcp`.
4. Starts `pg-ledger-mcp.service` on `127.0.0.1:<mcp-port>`.
5. Checks the tool list over MCP.

Re-running it re-applies functions, views and grants after a `pg_dump` of the schema to `/var/backups/pg-ledger-mcp/`.
It keeps data, passwords and the token. `--schema-only` stops before DBHub.

The service listens on loopback only. To publish it, put an HTTPS reverse proxy or tunnel in front and list the public
hostname in `--allowed-hosts`. Clients send `Authorization: Bearer <DBHUB_AUTH_TOKEN>`.

The `ledger_*` roles are cluster-wide. Run one instance per PostgreSQL cluster. If the logins already have passwords,
`install.sh` stops and asks for `--reset-passwords`.

### Client configuration (example)

```json
{ "mcpServers": { "ledger": { "type": "http", "url": "https://ledger-mcp.example.com/mcp",
  "headers": { "Authorization": "Bearer <token>" } } } }
```

Tell your agents their author id. Claude Code, for example, can use `claude:<session id>`.

## Test

On a host where a throwaway PostgreSQL cluster may be created:

```sh
sudo pg_createcluster 16 e2e --port 5544 --start
sudo tests/e2e.sh --pg-port 5544 --disposable-cluster
```

The test drives the MCP tools through these cases:

- grants, author format and request replay
- id assignment and version conflicts
- fix, then verify, including the same-session refusal
- links, corrections and read-only access
- batches: replay, rollback of the whole batch on one failure, the same grant and login rules

It also checks the database rules directly through the logins, then re-runs the installer.

## Layout

```
sql/10_tables.sql     roles, schema, tables, history triggers (fresh install)
sql/20_api.sql        functions, views, grants (re-applied on every install)
mcp/dbhub.toml        DBHub sources (one per login) and tool definitions
deploy/install.sh     install / re-apply, passwords, DBHub, systemd unit, smoke check
bin/ledger-admin      projects, categories, grants (owner operations)
bin/mcp-call.mjs      tiny MCP client: check, list, call
tests/                end-to-end test
```

## License

MIT
