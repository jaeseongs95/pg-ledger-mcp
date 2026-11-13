# pg-ledger-mcp plugin for Claude Code

[한국어](README.ko.md)

This directory is a Claude Code plugin. It proves the author of the ledger write calls that a Claude Code session makes. The three clients (Claude Code, Codex and Antigravity) use the same executable jar, `ledger-client-hook.jar`; the first argument selects the client.

## How it works

The `PreToolUse` hook of `hooks/hooks.json` runs `java -jar "${CLAUDE_PLUGIN_ROOT}/lib/ledger-client-hook.jar" claude` before each call of a ledger write tool. The hook handles only the ten write tools (`save`, `transition`, `record_fix`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct` and `whoami`), named `mcp__<server>__ledger_<op>` or `mcp__<server>__<server>_ledger_<op>`. The second form is used by a gateway that puts the name of the upstream server in front of the tool; the prefix must equal the server name exactly. The read tools and every other tool pass unchanged.

For a write call the hook adds the reserved argument `_hostAttestation` and returns the changed input as `updatedInput`. Its value is a signature, made with the Ed25519 session key of the author `claude:<session_id>` for the MCP server of the call. The signature covers the tool, the request id, the SHA-256 digest of the canonical form (RFC 8785) of all arguments, and the time. If the call has no `request_id`, the hook generates one. The hook gives no permission decision, so the permission policy of Claude Code applies unchanged. The call is denied when the model supplies `_hostAttestation` itself or when the `session_id` of the host is not valid; malformed JSON blocks the call with exit code 2.

## Requirements

The hook command looks up `java` on the `PATH`, so the host that runs Claude Code needs JDK 25 or later on its `PATH`. Without `java` the command fails, and with an older JDK the JVM refuses the jar with `UnsupportedClassVersionError`. In both cases no `_hostAttestation` is added, and the server refuses the write with `HOST_ATTESTATION_REQUIRED`. A failing hook therefore never lets a write without a proven author be stored.

## Installation

The jar is not committed to the repository. `./gradlew :ledger-client-hook:assemble` creates `ledger-client-hook/build/client-plugins/claude/`, a plugin directory that holds the jar as `lib/ledger-client-hook.jar`. Copy this directory to the plugin location of Claude Code.

## Registering the key of a session

1. The first write call of an author for a server creates the key pair of that author and server. The keys are kept in an owner-only folder: `~/.pg-ledger-mcp/session-keys`, or the absolute path in `LEDGER_HOOK_KEY_DIR`. On Windows the folder gets an ACL for its owner only, and a folder or file that grants access to another principal is refused. A hook that runs elevated is refused, because the key folder then belongs to another owner.
2. `java -jar lib/ledger-client-hook.jar session-key <server> <author>` prints the server, the author, the public key and its fingerprint. `<server>` is the name of the MCP server as the host shows it in the tool name, for example `ledger` for `mcp__ledger__ledger_save`.
3. The owner compares the fingerprint with the one the session showed and registers the public key with `key-register` of `ledger-cli`, or on the grants page of the admin web. Only then do the grants of the author work.

The key belongs to one server and one author. A signature made for a test server, or for a server with a similar name, never verifies on the production server; each server gets its own registration. The server checks the signature, the tool, the request id, the arguments and the time before it looks at any grant.

## Limits

Another process of the same operating system user can read the key file, so isolation inside one user needs separate accounts.

## References

- [PreToolUse contract of Claude Code](https://code.claude.com/docs/en/hooks#pretooluse)
- [Client hooks in the main README](../../README.md)
