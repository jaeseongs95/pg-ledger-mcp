# Agent integration guide

[한국어](client-hooks.ko.md) · [Guide index](guide.md)

## Connect the MCP client

In the host's MCP settings, register a Streamable HTTP server with the instance's `/mcp` URL. On the same host, the default is `http://127.0.0.1:8765/mcp`. Use the instance's `ledger.mcp.token` as the Bearer credential. For a remote instance, use the HTTPS URL configured by the operator.

## Install or update the hook

Run the installer on the host where the agent runs, with Java 25 available on its `PATH`. Use `sh deploy/install-hook.sh --hosts=codex,claude,antigravity` on Linux or `deploy\install-hook.cmd --hosts=codex,claude,antigravity` on Windows. Keep only the host names you use. `--version=vX.Y.Z` selects a specific release.

| Host | Hook configuration and session identity |
|---|---|
| [Codex](../clients/codex/README.md) | Review and trust the installed hook in `/hooks`. Grants use `codex:<session_id>`. |
| [Claude Code](../clients/claude/README.md) | Load the hook in Claude Code's `PreToolUse` configuration. Grants use `claude:<session_id>`. |
| [Antigravity](../clients/antigravity/README.md) | Load the Antigravity hook. The installer uses `antigravity` and grants use `gemini:<conversationId>`. |

The vendor pages describe plugin and manual installation. Keep `ledger-client-hook.jar` and `ledger-hook-resident.jar` together when installing manually.

## Verify the connection

1. Start a new agent session and confirm the MCP tool list loads. Check the endpoint and Bearer credential if it cannot connect.
2. Call `ledger_whoami` from the host. Confirm the returned author identifies the intended session. `HOST_ATTESTATION_REQUIRED` means the call arrived without the required host attestation. Check hook loading, trust, Java, and the two JARs.
3. In **Session grants**, give that author the permissions it needs for the project. Call `ledger_whoami` again to confirm its live grants. If a write returns `SESSION_NOT_GRANTED`, check the project, permission, and expiry. See the [MCP reference](mcp.md) for the input and permission contracts.

## Client hooks

The hook is two programs that lie next to each other in the plugin folder. The hosts run the **light client**,
`ledger-client-hook.jar`, which serves all clients; the first argument selects the client:
`java -jar ledger-client-hook.jar claude`, `codex` or `antigravity`. It loads nothing but `java.base` (no Spring, no Jackson, no
logging library) and, for every call, carries the input of the host over the loopback to the **resident program**,
`ledger-hook-resident.jar` (a Spring Boot application), and prints what that answers. What the host sees did not change: the host
JSON on the standard input, only the answer on the standard output (no JVM line, no log line), the exit codes and what a failure
means. The plugin directories are in `clients/claude`, `clients/codex` and `clients/antigravity` (see the README of each). For guided installation or upgrades, run `deploy/install-hook.sh` (Linux) or `deploy\install-hook.cmd` (Windows), which download and verify `SHA256SUMS` for both hook JARs, safely merge Codex, Claude, and Antigravity hook configurations with backups, and run a live smoke test. The hook
needs a Java 25 runtime on the `PATH` of the client host; with no hook, or a failing hook, a write is refused with
`HOST_ATTESTATION_REQUIRED`.

The hook stamps only the thirteen write tools and five attachment tools, named `mcp__<server>__ledger_<op>` or `mcp__<server>__<server>_ledger_<op>`
(Codex Apps: `mcp__codex_apps__app_<app>_ledger_<op>`). The value of `_hostAttestation` is `<vendor>:<session-id>` (for example
`claude:1f2e3d4c` or `codex:9a8b7c6d`). If the model supplies `_hostAttestation` itself, the hook refuses the call (`RESERVED_FIELD`).

- The resident program is started by the first call and needs no installation step: when the address file is missing, names a process
  that is gone (the process id and the start time must both match, so a reused id is not taken for it) or does not answer, the client
  starts `java -jar` of a copy of the program, and only one starts even when several calls arrive together (a lock on the state
  folder; the program holds a second lock of its own). The first call takes about a second, the later ones about a tenth of a second
  (measurements in the release notes). The program keeps running after the call and ends by itself after 15 minutes without a call
  (`LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT`, an ISO-8601 duration such as `PT30M`), or with `java -jar ledger-client-hook.jar stop`.
- Its state folder, `~/.veridger/resident/<id>/`, holds the address file (the port,
  the process id and start time, the build, and a secret that is new for every start), the two lock files and `resident.log`, which
  has the output of the program and never a secret. The program listens on `127.0.0.1` only, at a port that the system chooses. Every
  request must carry the secret of the address file; it is compared in constant time and a request without it is refused with 401, so
  another account of the same PC cannot call the program. The folder and its files are owner-only (POSIX permissions, or an ACL for
  the owner, chosen by what the file system can do).
- The program runs from a copy in `~/.veridger/resident-runtime/<build>/`, so a running program never locks the jars of the
  plugin folder: the plugin can be updated or removed at any time. A client whose program file is another build (an updated plugin)
  asks the running program to end, ends it if it does not, and starts the new one.
- If the program cannot be reached or started, the call fails the way an unanswerable call always failed: exit code 2 and, on the
  standard error, `ledger: the hook program is not available` with the reason; the server then refuses the write with
  `HOST_ATTESTATION_REQUIRED`. Read `resident.log` in the state folder; check that `ledger-client-hook.jar` and
  `ledger-hook-resident.jar` are in the same folder and that `java` is JDK 25 or later. `java -jar ledger-client-hook.jar stop` ends a
  program that behaves oddly; the next call starts a new one. The folders `resident` and `resident-runtime` can be deleted while no
  program runs.
- The server validates the `_hostAttestation` author format (`HOST_ATTESTATION_REQUIRED`, `AUTHOR_ID_FORMAT`) before checking the
  session's live grants (`session_grant`).
- Limits: network-level authentication relies on `ledger.mcp.token` (plus `Host` and `Origin` allow lists), while the client hook
  prevents the model from spoofing `_hostAttestation` locally. Keep `ledger.mcp.token` restricted to trusted owner hosts.
