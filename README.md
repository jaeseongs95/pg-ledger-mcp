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
- **One defect, one root cause; many observed cases.** Each observation of a defect (symptom, log, user report) is a row of
  `ledger.defect_case` under it. Merging a defect (`MERGED`, `merged_into`) keeps its cases where they are; `ledger.v_defect`
  shows them under the defect it was merged into, so nothing has to be copied. A MERGED defect takes no new cases.

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
| `ledger_record_case` | author | add an observed case under a defect (refused on a MERGED defect, naming its target) |
| `ledger_link` | author | link defects, tasks and conditions |
| `ledger_correct` | author | mark a history row as wrong |
| `ledger_batch` | author | up to 200 of the operations above in one transaction: all commit or none |
| `ledger_review` | reviewer | VERIFIED / MET / NOT_MET, and reopening them |
| `ledger_review_correct` | reviewer | mark a verification row as wrong |

Useful views: `ledger.v_defect` (state, fixes, tasks, cases, last change), `ledger.v_history`, `ledger.v_attention`
(rows that need a look, such as an OPEN defect that already has a fix or a VERIFIED defect without CI).

`ledger_record_case` takes `defect_id`, `observation` (1..4000 characters) and `reason`, and optionally `evidence`
(request id, `file:line`, log location), `user_quote` (verbatim) and `observed_at` (ISO 8601; omitted = not known).
In `ledger_batch` the same arguments go in `{"op": "record_case", ...}`.
`v_defect.cases` is a JSON array, oldest first, of the cases of the defect and of every defect merged into it, also
through chains of merges. Each entry names its `defect`; `source` is `case` for a recorded case and `merged_behavior` for
the `behavior` text of a merged defect, which counts as one more observation.

```json
{"author_id": "claude:1f2e3d4c", "defect_id": "WEB-D-0007", "observation": "checkout returns 500 for a gift card",
 "evidence": "request 7f3a, api/checkout.ts:88", "user_quote": "I can't pay with my gift card",
 "observed_at": "2026-11-05T10:00:00Z", "reason": "second report of the same failure"}
```

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
It keeps data, passwords and the token. Tables added after the first release (`sql/15_defect_case.sql`) are created by the
re-run on an existing database; each runs once, recorded in `ledger._migration`, and existing rows are not changed. `--schema-only` stops before DBHub.

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
- observed cases: replay, refusal on a MERGED defect (also when the merge commits while the case waits), the view over
  merged defects and chains of merges

It also checks the database rules directly through the logins, then re-runs the installer.

`--upgrade-from <git-ref>` (for example `--upgrade-from a829947`) installs that commit first, writes data with it,
installs the checkout over it, checks that every existing row is unchanged, and then runs the same scenario.

## Layout

```
sql/10_tables.sql     roles, schema, tables, history triggers (fresh install)
sql/15_defect_case.sql  observed cases table (run on every install; does nothing once applied)
sql/20_api.sql        functions, views, grants (re-applied on every install)
mcp/dbhub.toml        DBHub sources (one per login) and tool definitions
deploy/install.sh     install / re-apply, passwords, DBHub, systemd unit, smoke check
bin/ledger-admin      projects, categories, grants (owner operations)
bin/mcp-call.mjs      tiny MCP client: check, list, call
tests/                end-to-end test
```

## License

MIT
