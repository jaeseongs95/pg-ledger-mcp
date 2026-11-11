# pg-ledger-mcp

A PostgreSQL ledger for work done by AI agents, served as one MCP endpoint.
It records defects, tasks, fixes and their verification, along with who did each one and why.

Agents read and write through MCP tools. They never get a database login of their own.

- **Role logins, not per-agent accounts.** There are three logins: `ledger_author`, `ledger_reviewer` and `ledger_reader`.
  Each MCP tool is bound to the login it needs, and the caller never chooses one.
- **The host hook signs every write and whoami.** Reserved `_hostAttestation` is a signed token
  (`pgla2.<author>.<tool>.<request id>.<arguments digest>.<issued at>.<signature>`; `pgla1` tokens are refused)
  for the author `<vendor>:<session-id>`, for example `claude:1f2e3d4c` or `codex:9a8b7c6d`. Models must omit this field.
  The signature covers the exact arguments of the call (SHA-256 of their RFC 8785 canonical form, without `_hostAttestation`;
  a batch: every operation, its content, its order and its request id), so a captured token cannot carry other content.
  The hook keeps one Ed25519 key pair per MCP server name of the host and author in an owner-only folder (an absolute path:
  `LEDGER_HOOK_KEY_DIR`, default `~/.pg-ledger-mcp/session-keys`; POSIX mode or, on Windows, an ACL that grants the owner alone);
  it signs only `mcp__<server>__ledger_<op>` (or `mcp__<server>__<server>_ledger_<op>`) with a known operation, so a signature made
  for one server (the test server, a look-alike server) never verifies on another. Codex Apps (`mcp__codex_apps__app_<app>_ledger_<op>`)
  is keyed by the app: the server name for `session-key` is `codex_apps.<app>`, for example `codex_apps.ledger`.
  `java -jar ledger-client-hook.jar session-key <server> <author>` prints the public key and its fingerprint. The owner registers each
  server's key separately (`ledger-cli key-register <author> <public-key> <fingerprint>` on that server, after comparing the fingerprint);
  the server verifies the signature (a missing key and a wrong signature are the same refusal, `ATTESTATION_SIGNATURE_INVALID`), the tool,
  the request id, the arguments and the time (default window 5 minutes) before it looks at any grant. A bare `<vendor>:<session-id>` is refused.
  Existing project/scope grants still apply. The ledger owner is `user:owner`. Legacy model `author_id` is ignored by the server.
  Limit: another process of the same OS user can read the key file; separate OS accounts are needed for isolation inside one user.
- **Writes need a live grant.** The owner grants an author a scope on a project with `bin/ledger-admin grant`.
  The scopes are `defect`, `task`, `fix` and `verify`. Writes without a matching grant are refused.
- **Verification is separate.** `VERIFIED`, `MET` and `NOT_MET` can only be set through the reviewer login.
  A fix must be verified by a session other than the one that recorded it.
  `VERIFIED` needs an evidence link and at least one recorded fix from another session. CI results are optional metadata.
  The developer supplies the source SHA, original failure reproduction and direct execution steps; the reviewer runs the actual program.
  When a defect is registered by one session (`created_by`) and fixed by another, `ledger_record_fix` updates `owner_actor_id` to the fixing session and `ledger.v_defect` exposes `fixed_by` / `fixed_at`; verification feedback and rework requests go to the session that fixed the defect (`fixed_by`), not `created_by`.
- **History is append-only.** Every write adds a `ledger.history` row: when, author, login, operation, state change and reason.
  Mistakes are marked with a `CORRECT` row. Triggers refuse `UPDATE`, `DELETE` and `TRUNCATE` on history, even for the owner.
- **Retries are safe.** Pass `request_id`, a UUID. Repeating the same request returns the stored result and does not write twice.
- **Optimistic locking.** Edits and transitions carry `expected_version`.
- **Projects.** Each defect belongs to a project and gets the id `<CODE>-D-0001`, `<CODE>-D-0002`, … automatically.
- **One defect, one root cause; many observed cases.** Each observation of a defect (symptom, log, user report) is a row of
  `ledger.defect_case` under it. Merging a defect (`MERGED`, `merged_into`) keeps its cases where they are; `ledger.v_defect`
  shows them under the defect it was merged into, so nothing has to be copied. A MERGED defect takes no new cases.
- **Common codes.** Category, severity and relation kind are the three groups in `ledger.common_code`, with Korean and English names and descriptions.
  Active values are rendered into the MCP tool definitions. States, CI results and grant scopes keep their fixed CHECK constraints.
  A retired code stays on existing rows and is refused when newly assigned.
  **Severity is required:** P1/P2/P3 are active; an existing UNRATED defect must be rated on its next write.
- **Creation provenance is immutable.** New project, task, defect, condition, link and common-code rows receive `created_by` automatically.
  Existing `recorded_by`, history/receipt `author_id` and grant `granted_by` columns retain their meanings.
  Existing rows are backfilled from first CREATE/IMPORT history (LINK for links), with `unknown:legacy` when no source exists; link creators come from the first LINK after the latest UNLINK;
  projects and common codes use `user:owner`. Edits and reviewer transitions preserve the creator.
  Parents remain responsible for child results, records and mistakes and must verify/correct their evidence directly.

PostgreSQL enforces grants, state rules, provenance and history through constraints, `SECURITY DEFINER` functions and triggers.
Client hooks supply host identity; a direct MCP caller is not cryptographically authenticated as that host.
The MCP layer is a thin configuration of [DBHub](https://github.com/bytebase/dbhub).

## MCP tools

| tool | login | what |
|---|---|---|
| `execute_sql_reader` | reader | any read-only SQL (read-only transaction, at most 1000 rows) |
| `search_objects_reader` | reader | list schemas, tables, columns |
| `ledger_whoami` | author | is this author id well formed, which grants does it hold |
| `ledger_save` | author | create or edit a defect, task or condition |
| `ledger_transition` | author | change state (OPEN, SOURCE_FIXED, MERGED, ACTIVE, REPORTED_DONE, …) |
| `ledger_record_fix` | author | record a fix commit and optional CI metadata; assigns `owner_actor_id` (`v_defect.fixed_by`) and moves OPEN → SOURCE_FIXED |
| `ledger_record_case` | author | add an observed case under a defect (refused on a MERGED defect, naming its target) |
| `ledger_link` | author | link defects, tasks and conditions |
| `ledger_correct` | author | mark a history row as wrong |
| `ledger_batch` | author | up to 200 of the operations above in one transaction: all commit or none |
| `ledger_review` | reviewer | VERIFIED / MET / NOT_MET, and reopening them |
| `ledger_review_correct` | reviewer | mark a verification row as wrong |

Useful views: `ledger.v_defect` (state, fixes, tasks, cases, last change), `ledger.v_history`, `ledger.v_attention`
(rows that need a look, such as an OPEN defect that already has a fix or a fixed defect without a recorded commit).

`ledger_record_case` takes `defect_id`, `observation` (1..4000 characters) and `reason`, and optionally `evidence`
(request id, `file:line`, log location), `user_quote` (verbatim) and `observed_at` (ISO 8601; omitted = not known).
In `ledger_batch` the same arguments go in `{"op": "record_case", ...}`.
`v_defect.cases` is a JSON array, oldest first, of the cases of the defect and of every defect merged into it, also
through chains of merges. Each entry names its `defect`; `source` is `case` for a recorded case and `merged_behavior` for
the `behavior` text of a merged defect, which counts as one more observation.

```json
{"defect_id": "WEB-D-0007", "observation": "checkout returns 500 for a gift card",
 "evidence": "request 7f3a, api/checkout.ts:88", "user_quote": "I can't pay with my gift card",
 "observed_at": "2026-11-05T10:00:00Z", "reason": "second report of the same failure"}
```

## Install

Requirements: Linux with systemd, PostgreSQL 15+ (and its psql) on the same host, Node.js 22.5+ and npm, run as root.
The ledger logins connect to `127.0.0.1` with a password, so `pg_hba.conf` must allow `host … 127.0.0.1/32 scram-sha-256`.
The Debian and Ubuntu defaults already allow it.

### One-Click Install

To provision the database (if missing), install the schema, start the MCP service (`127.0.0.1:8765`), and start the Admin Web Console (`127.0.0.1:8865`) in one step:

```sh
sudo deploy/one-click-install.sh
```

Or directly via `curl` on a target Linux host (automatically clones into `/opt/<name>/repo` when run outside a checkout):

```sh
curl -fsSL https://raw.githubusercontent.com/jaeseongs95/pg-ledger-mcp/main/deploy/one-click-install.sh | sudo bash
```

### Custom Domain & Cloudflare Automation (e.g. `jaeseong.dev`)

If you own a Cloudflare-managed domain (for example `jaeseong.dev`), `deploy/setup-domain.sh` (or `deploy/one-click-install.sh --domain <domain>`) automates `DBHUB_ALLOWED_HOSTS` and `PGLEDGER_ADMIN_ALLOWED_HOSTS`, generates a **10-year self-signed ECDSA P-256 Origin TLS certificate** (`/etc/<name>/tls/cert.pem` & `key.pem`) for Cloudflare `Full` SSL, and optionally configures **Google OAuth 2.0** login alongside token / `/?token=` URL login:

```sh
# 1. One-click install + Cloudflare Origin TLS + Google OAuth 2.0 (recommended for jaeseong.dev)
curl -fsSL https://raw.githubusercontent.com/jaeseongs95/pg-ledger-mcp/main/deploy/one-click-install.sh | sudo \
  PGLEDGER_DOMAIN="jaeseong.dev" \
  PGLEDGER_ADMIN_GOOGLE_CLIENT_ID="<google-oauth-client-id>" \
  PGLEDGER_ADMIN_GOOGLE_CLIENT_SECRET="<google-oauth-client-secret>" \
  PGLEDGER_ADMIN_ALLOWED_EMAIL="you@example.com" \
  bash

# Or from a local checkout:
sudo deploy/one-click-install.sh \
  --domain jaeseong.dev \
  --domain-mode origin-tls \
  --google-client-id "<google-oauth-client-id>" \
  --google-client-secret "<google-oauth-client-secret>" \
  --allowed-email "you@example.com"

# 2. Cloudflare Tunnel API automation (updates remote tunnel ingress + creates proxied CNAME records)
sudo CF_API_TOKEN="<cloudflare-api-token>" deploy/setup-domain.sh \
  --name pg-ledger-mcp \
  --domain jaeseong.dev \
  --mode cloudflare-api \
  --cf-tunnel-id "<tunnel-uuid>"

# 3. Local cloudflared config.yml or Nginx reverse proxy automation
sudo deploy/setup-domain.sh --name pg-ledger-mcp --domain jaeseong.dev --mode cloudflared-config
sudo deploy/setup-domain.sh --name pg-ledger-mcp --domain jaeseong.dev --mode nginx --certbot-email admin@jaeseong.dev
```

When using `--domain-mode origin-tls` (the default when `cloudflared` is not active), finish in the Cloudflare Dashboard (`jaeseong.dev`):

1. **DNS -> Records**: Add an `A` record for `ledger-admin` pointing to the server public IP with **Proxy status: Proxied (Orange Cloud ON)**.
2. **SSL/TLS -> Overview**: Set encryption mode to **Full** (pairs with the generated 10-year ECDSA P-256 certificate in `/etc/<name>/tls/`).
3. **Rules -> Origin Rules**: Match `Hostname equals ledger-admin.jaeseong.dev` -> **Rewrite Destination Port** to `8865` (so `https://ledger-admin.jaeseong.dev` connects without `:8865` in the URL or Nginx on port 80/443).

By default, `--domain jaeseong.dev` maps `ledger-mcp.jaeseong.dev` and `ledger-admin.jaeseong.dev` (override with `--mcp-host` and `--admin-host`).

### Standard Install & CLI Administration

```sh
sudo -u postgres createdb ledger
sudo deploy/install.sh --db ledger --mcp-port 8765 --admin-port 8865 --allowed-hosts ledger-mcp.example.com --admin-allowed-hosts ledger-admin.example.com
sudo bin/ledger-admin project-add web WEB "Web shop"
sudo bin/ledger-admin category-add BUG "결함" "명세와 다른 동작" --name-en Bug --description-en "Behaviour differs from the specification"
sudo bin/ledger-admin code-set RELATION_KIND duplicates "중복" "a가 b를 반복" --name-en Duplicates --description-en "a repeats b"
sudo bin/ledger-admin grant claude:1f2e3d4c web defect fix --note "fix sprint"
sudo bin/ledger-admin grant codex:9a8b7c6d web verify
```

`install.sh` does the following:

1. Creates the roles, the `ledger` schema and the functions.
2. Generates passwords and two distinct bearer tokens (`DBHUB_AUTH_TOKEN` for MCP agents, `PGLEDGER_ADMIN_TOKEN` for the human owner) into `/etc/pg-ledger-mcp/env` (mode 0600, root).
3. Installs DBHub 1.5.1 under `/opt/pg-ledger-mcp`.
4. Starts `pg-ledger-mcp.service` on `127.0.0.1:<mcp-port>` and `pg-ledger-mcp-admin.service` on `127.0.0.1:<admin-port>` (unless `--skip-admin` is passed).
5. Checks the tool list over MCP and verifies `/healthz` on the Admin Web Console.

## Admin Web Console

The Admin Web Console (`pg-ledger-mcp-admin.service`, or `sudo bin/ledger-admin serve`) runs on `127.0.0.1:8766` by default and provides a zero-dependency web interface for human ledger owners:

- **Strict Privilege Separation**: Authenticates exclusively with `PGLEDGER_ADMIN_TOKEN` (`HttpOnly; SameSite=Strict` cookie or `Authorization: Bearer`) and explicitly rejects `DBHUB_AUTH_TOKEN` (`401 AGENT_TOKEN_FORBIDDEN`) so an AI agent holding the MCP token cannot self-grant permissions.
- **CSRF & Browser Hardening**: Enforces loopback binding (`127.0.0.1`), `Host`/`Origin`/`Sec-Fetch-Site` validation, `X-Requested-With: pg-ledger-admin` on all mutations, strict Content Security Policy (`default-src 'self'`), and DOM `textContent` rendering.
- **Owner Governance (Tier 1) & Read-Only Explorer (Tier 3)**:
  - **Triage Overview**: Live `ledger.v_attention` anomaly cards with deep-link filtering and project health counters.
  - **Session Grants**: 4-scope matrix (`defect`, `task`, `fix`, `verify`), expiration presets, and 2-step confirmation before revoking grants.
  - **Projects & Common Codes**: Register projects and manage `DEFECT_CATEGORY`, `SEVERITY`, and `RELATION_KIND` with active/retire toggles (respecting `ledger._code_keep` no-delete rule) and a 3-stage DBHub synchronization indicator.
  - **Ledger Explorer**: Read-only search and inspection across `ledger.v_defect` (with slide-out detail drawer), `ledger.task`, `ledger.condition`, and `ledger.v_history` (agent workflow mutations remain strictly in MCP tools with `_hostAttestation`).

Re-running it applies tables, backfill, replacement constraints, functions, views and grants in one PostgreSQL transaction after a `pg_dump` of the schema to `/var/backups/pg-ledger-mcp/`.
Existing ledger tables remain locked until the replacement constraints and triggers commit together. A schema-phase failure rolls the entire schema unit back; old concurrent writers see either the original constraints or their complete replacements.
Filesystem/password/service operations occur after that schema transaction; a later install failure still leaves the complete DB constraints in force.
It keeps data, passwords and the token. Tables added after the first release are created by the
re-run on an existing database (`sql/15_defect_case.sql`, `sql/16_common_code.sql`, `sql/17_created_by.sql`); each runs once, recorded in `ledger._migration`.
Existing semantic values are preserved; `created_by` is backfilled. Code changes through `category-add` / `code-set` rerender the tool definitions, restart the instance and wait up to 30 seconds for healthz.
The English options default to the supplied Korean text when omitted. Only the three extensible groups are accepted. `--schema-only` stops before DBHub.

The service listens on loopback only. To publish it, put an HTTPS reverse proxy or tunnel in front and list the public
hostname in `--allowed-hosts`. Clients send `Authorization: Bearer <DBHUB_AUTH_TOKEN>`.

The `ledger_*` roles are cluster-wide. Run one instance per PostgreSQL cluster. If the logins already have passwords,
`install.sh` stops and asks for `--reset-passwords`.

### Client configuration (example)

```json
{ "mcpServers": { "ledger": { "type": "http", "url": "https://ledger-mcp.example.com/mcp",
  "headers": { "Authorization": "Bearer <token>" } } } }
```

Install a host hook before moving a server to this contract. Claude Code:

```sh
claude plugin marketplace add jaeseongs95/pg-ledger-mcp
claude plugin install pg-ledger-mcp@pg-ledger-mcp
```

For a checkout, add its absolute repository path as the marketplace instead. For a temporary, session-scoped load:

```sh
claude --plugin-dir /absolute/path/to/pg-ledger-mcp/clients/claude
```

The Claude plugin matches the ten ledger tools, rejects any model-supplied `_hostAttestation`, preserves legacy `author_id` for cached connector schemas,
then returns the full `updatedInput` with `claude:<session_id>`. Missing/invalid host ids are refused. It reads only hook stdin.
It omits `permissionDecision`, retaining Claude's permission policy. In auto mode Claude may deny a rewritten call;
setting `allow` would skip permission prompts and is deliberately not part of this plugin.
Official contract: [Claude hooks](https://code.claude.com/docs/en/hooks#pretooluse-decision-control).

Codex: load the [Codex plugin](clients/codex/README.md) through the host's plugin mechanism, or merge its hook config into
`<repo>/.codex/hooks.json`, set the script's absolute path and review/trust it with `/hooks`.
Antigravity: load the [Antigravity plugin](clients/antigravity/README.md), or merge its named group into
`<workspace>/.agents/hooks.json` with the script's absolute path. These are project configuration alternatives; preserve existing hooks.
Codex and Claude use host `session_id`; Antigravity uses `conversationId`. All three preserve an existing legacy `author_id`
for cached schemas, without adding an absent value. Only the host `_hostAttestation` determines the author on the server;
the legacy field has no authority. No child-specific identity, permission or blocking layer is added.

Roll out the three host hooks first, then migrate the server under its existing operator approval; otherwise writes without
hooks fail with `HOST_ATTESTATION_REQUIRED`.

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
- common codes: bilingual tools/list content, dynamic relation enum, escaped text, missing/retired severity and unknown category
- fixed state, CI and grant CHECKs; host-attestation schemas and missing values; retained legacy `author_id` has no authority
- immutable creators after save, link, batch, fix and review; forged fields/UPDATE refusal; synthetic Claude hook contracts

It also checks the database rules directly through the logins, then re-runs the installer.

`--upgrade-from <git-ref>` (for example `--upgrade-from 6d2d0e1`) installs that commit first, writes data with it,
installs the checkout over it, checks that existing semantic values are unchanged (excluding newly added `created_by`) (categories move into `ledger.common_code`
unchanged, and an `UNRATED` defect must be rated on its next write), and then runs the same scenario.

## Layout

```
sql/10_tables.sql     roles, schema, tables, history triggers (fresh install)
sql/15_defect_case.sql  observed cases table (run on every install; does nothing once applied)
sql/16_common_code.sql  common codes table and the columns using them (same)
sql/17_created_by.sql  creation provenance backfill (same)
sql/20_api.sql        functions, views, grants (re-applied on every install)
mcp/dbhub.toml        DBHub sources (one per login) and tool definitions
deploy/apply-schema.sh  complete schema transaction and table locks
deploy/render-tools.sh  active bilingual codes → DBHub tool definitions
clients/claude/      Claude Code marketplace plugin (PreToolUse)
deploy/install.sh     install / re-apply, passwords, DBHub, systemd units, smoke checks
deploy/one-click-install.sh  zero-config one-click installer for PostgreSQL + MCP + Admin Console
deploy/setup-domain.sh  custom domain allowlist + Cloudflare Tunnel / Nginx automation
deploy/pg-ledger-mcp-admin.service  systemd unit for the Admin Web Console
bin/ledger-admin      projects, common codes, grants, and 'serve' subcommand (owner operations)
bin/ledger-admin-server.mjs  zero-dependency HTTP server for the Admin Web Console
web/                  Admin Web Console static frontend (index.html, styles.css, app.js)
bin/mcp-call.mjs      tiny MCP client: check, list, call
tests/                end-to-end MCP, upgrade, and Admin Web Console tests
```

## License

MIT
