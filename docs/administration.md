# Administrator guide

[한국어](administration.ko.md) · [Guide index](guide.md)

## Owner checklist

1. Choose the owner sign-in method and confirm that the owner can sign in. Keep the owner credential separate from the MCP Bearer credential. See [Admin web](#admin-web).
2. Create the project and identify the host and session that needs access. Confirm the identity with `ledger_whoami` before granting permissions. Revisit **Session grants** to edit or revoke access as work changes.
3. Use **Attachments** to inspect stored evidence. Monitor the instance and plan backups, upgrades, and recovery with the [operations guide](operations.md).

For navigating and maintaining project records, use the [user guide](user-guide.md).

## ledger-cli

`java -jar ledger-cli.jar <command> [arguments]`. An option is written `--name=value`. A Spring property (a name with a dot, such as
`--ledger.jdbc.url=...`) is passed the same way. The tool runs as the user that owns the secret directory (on Linux the
service user), and every owner operation is recorded with that user.

| Command | Syntax | What |
|---|---|---|
| `secret-set` | `secret-set <name> [--stdin] [--if-absent]` | store a secret from hidden input, or with `--stdin` from the first line of standard input (never from an argument or the environment); `--if-absent` keeps an existing secret and asks for nothing; the line read with `--stdin` is UTF-8 and a line that is not is refused (`INVALID_ENCODING`): Windows PowerShell 5.1 encodes what it pipes to a program with `$OutputEncoding`, which is ASCII unless it is changed, so set `$OutputEncoding = [System.Text.UTF8Encoding]::new($false)` (UTF-8 without a byte order mark) before piping a secret that has other characters |
| `driver-register` | `driver-register --jar=<path> --class=<driver-class>` | register the JDBC driver; needs no database |
| `token-generate` | `token-generate <name> [--print] [--copy-to=<name>]` | store a random token (32 bytes from `SecureRandom`) as a secret; an existing secret is kept; `--print` shows a token that was just created, once; `--copy-to` stores the same token under a second name, once |
| `migrate` | `migrate [--owner-account=<account>] [--app-account=<account>] [--stdin]` | apply the pending schema files, as the DDL account when `--owner-account` is given (its password is typed, or with `--stdin` read from standard input, and not stored), and then the grants of `dml-grants.sql` to the application account |
| `setup` | `setup [--owner-account=<account>] [--app-account=<account>] [--stdin]` | the first setup: migrate as `migrate` does, check that the application account can use the schema (exit code `3` and the missing step when it cannot), add the seed codes that are missing (a code that exists is never changed) and compare the seed with the database by counts and digest; running it again changes nothing |
| `passkey-code` | `passkey-code [--remove-existing]` | issue the one-time code that enrols a passkey of the owner in the admin web (shown once, valid 15 minutes, stored only as its hash; a new code ends the earlier unused one); `--remove-existing` closes every live passkey first, for an owner who lost them all |
| `project-add` | `project-add <id> <code> <title>` | add a project |
| `code-set` | `code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>] [--inactive] [--direction=<direction>]` | add or update a common code; `--direction` (`GIVEN` or `CANONICAL`) is for the group `RELATION_KIND` (see the `ledger_link` tool) |
| `codes` | `codes [<group>]` | list the common codes |
| `grant` | `grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]` | grant scopes on a project directly to an author (`<vendor>:<session-id>`) |
| `revoke` | `revoke <author> [<project> [<scope>]]` | revoke live grants |
| `grants` | `grants [<author>]` | list live grants |
| `attachments-check` | `attachments-check [--purge-orphans]` | check the attachments against their files and keys: a file that no row names, and a file that `attachments-rekey` replaced, is moved to `orphans/` and never deleted; a file in `orphans/` whose row exists again is moved back; the report gives the counts and the ids (never a storage key or a path) of the attachments that are missing, damaged or pointed to by no history row, and the exit code is `1` when one is missing or damaged; a key file that is missing or a disk that cannot be read stops the check with that error and is never reported as a damaged file; `--purge-orphans` first deletes the files in `orphans/` that no row names (a file whose row exists again is not deleted, the check moves it back) and records the number in the audit trail |
| `attachments-rekey` | `attachments-rekey --from=<keyId>` | write the attachment files of the key `<keyId>` again under the active key `ledger.attachments.active-key-id` (a different key); the old files are moved to `orphans/` by `attachments-check` after `ledger.attachments.orphan-grace` |
| `attachments-key-create` | `attachments-key-create` | create a new 256-bit key file in the key directory (readable by the service account only; on Windows by the installing user and, read only, by LocalService) and print its key id, never the key; set `ledger.attachments.active-key-id` to it to write new uploads with it |
| `attachments-prepare` | `attachments-prepare <file> [--name=<name>]` | measure one regular file in a single streaming pass and print JSON (`fileName`, `size`, `sha256`, `mediaType`) for `artifact_begin`; connects to nothing |
| `attachments-send` | `attachments-send <file> --upload-id=<id> [--url=<url>] [--ledger.mcp.token=<token>]` | send the chunks of one file directly to the running MCP server using `artifact_status` and `artifact_chunk` (`uploadId` capability without session attestation); resumes from server progress if interrupted and needs no database |
| `mcp-check` | `mcp-check [--url=<url>] [--ledger.mcp.token=<token>]` | connect to a running MCP server and check its tools |
| `render-deploy` | `render-deploy --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>] [--admin-host=<host>] [--root=<directory>] [--check]` | write the service definitions (systemd units, or WinSW configurations on Windows) and the settings; the installers call it |
| `prepare-host` | `prepare-host --jars=<dir> --driver-jar=<file> [--winsw=<file>] --java=<path> --jdbc-url=<url> --app-account=<account> [--name=<name>] [--platform=<platform>] [--home=<path>] [--schema=<name>] [--mcp-port=<n>] [--admin-port=<n>] [--mcp-host=<host>] [--admin-host=<host>] [--root=<directory>]` | prepare the files of the host (run as root, or elevated on Windows): the folders with owner and mode, or their ACLs (on Windows the access of LocalService and the secret folders of the installing user), the copies of the jars and the driver (on Windows also of WinSW), and what `render-deploy` writes; the installers call it |
| `install-default` | `install-default <name>` | print one default of the installation from the seed bundle (`instance-name`, `mcp-port` or `admin-port`); the installers ask for their defaults with it |

## Admin web

The overview shows items awaiting verification, blocked work, and release-criterion progress for each version of the selected project.
Records show both the number matching the filters and how many are on the page. Project names and descriptions can be edited together.
Issues, specifications, tasks, release gates, and history show the linked project versions and can be filtered by a version. History shows the target’s current links, and related records follow the versions of their parent.
The release gates page can archive a canceled milestone and restore it from the archived view, preserving its criteria, links, and history.
The session grants page accepts more than one permission in the filter. Projects with any selected permission are shown.

The admin web (`ledger-admin-web.jar`) is a server-rendered console for the owner, on `127.0.0.1:8865` by default. It runs as
the service account of the installation and never as root.

- **Sign-in, one of three** (`ledger.admin.sign-in`). With `passkey` (the default of a new installation) the owner signs in with a
  passkey. With `token` (the personal edition, one person on their own computer or server) the owner types the admin token
  `ledger.admin.token` into the login page of the application. With `oidc` the owner signs in with OpenID Connect, and the owner is **one e-mail address**
  (`ledger.admin.oidc.owner-email`, verified by the provider at `ledger.admin.oidc.issuer`); another address, or one the provider did not
  verify, is refused. Only one of the three is offered: with `oidc` or `passkey` the token form is absent and a posted token
  finds no sign-in. The admin token `ledger.admin.token` also stays the bearer of the read-only JSON endpoints. Once signed in, owner
  changes apply directly under session and CSRF protection without per-action passkey re-approval. The agents' token is refused, so an agent that holds the MCP token cannot grant itself
  anything. Credential checks (passkey, one-time code, token) are slowed down once more than a budget of them arrive within a window
  (`ledger.admin.lockout.*`); nothing is ever locked, because a lock that anybody can trigger with wrong credentials locks the owner out as
  well. There is no limit per client either: the public reaches the services through the operator's reverse proxy on the same host, and
  the address that proxy forwards is a record of the request and not an identity (depending on the proxy, every user arrives as one
  address), so a lock by address would lock the owner with the attacker, and the personal edition, one person, has nobody to tell apart. A token
  made by `token-generate` (256 bits from `SecureRandom`) and the one-time code (100 bits) cannot be guessed, so a delay is all that is needed.
- **First passkey, another one, or a reset.** Run `ledger-cli passkey-code` on the server as the service account: it prints a
  one-time code (shown once, valid 15 minutes, stored only as its hash). On the login page type it under "Register a passkey"; that
  signs in an enrolment session (a quarter of an hour, it may register a passkey and nothing else), where "Register" creates the
  passkey. `passkey-code --remove-existing` closes every live passkey first, for an owner who lost them all; without the flag no
  passkey is touched. Each code and each reset is recorded in the audit trail of the owner. The passkey is bound to the host name
  of the admin web (`ledger.admin.passkey.rp-id`), HTTPS is required except on `localhost`, and a wrong host or origin is refused with
  `PASSKEY_HOST_MISMATCH`.
- **How it is built.** The ceremonies are Spring Security's WebAuthn support (`spring-security-webauthn` with WebAuthn4J, both
  Apache-2.0); the passkeys are kept in the ledger database (V7: base64url text, no BLOB, a credential is never changed or deleted: closing it
  is a row of its own).
- **Browser hardening.** Spring Security provides CSRF tokens, a `SameSite=Strict` session cookie that is `Secure` by default, the
  `Host` allow list and a Content Security Policy.
- **Pages.** Use the keyboard-accessible sidebar to browse records, manage projects and session grants, and open attachments, JDBC settings, passkeys, or updates. Tables and forms support both Korean and English. Times use UTC in transit and the browser time zone for display.
- **Grants.** Sessions are grouped into per-author cards showing host badges and session IDs separately while preserving the stored `<vendor>:<session-id>` identifier. The owner selects the host (`codex`, `claude`, `gemini`, or custom) and enters the session ID in separate fields (pasting `<vendor>:<session-id>` auto-splits the fields) with no key registration or per-session token issuance, adjusts all scopes of a project in one atomic sync, or revokes grants through confirmation modals.
- **Project version workspace.** Open a project version to browse, create, link, or unlink specs, issues, tasks, and release gates. In `ledger_batch`, a `save` creation can specify `project_version` by name to create and link the record in the same transaction. `ledger_get` with `include_details=true` includes `project_versions` for all four kinds.
- **Updates (`/updates`).** The owner can check GitHub Releases and stage a verified update. The host updater owns service restarts and PostgreSQL migrations. See [automatic PostgreSQL schema updates](installation.md#automatic-postgresql-schema-updates).
