# pg-ledger-mcp plugin for Codex

[한국어](README.ko.md)

This directory is a Codex plugin. It proves the author of the ledger write calls that a Codex session makes. The three clients (Claude Code, Codex and Antigravity) use the same executable jar, `ledger-client-hook.jar`; the first argument selects the client. The plugin contains no MCP connection settings and no credentials.

## How it works

`extensions.com.openai.hooks` of `plugin.json` points to `hooks/hooks.json`. Its `PreToolUse` hook runs `java -jar "${PLUGIN_ROOT}/lib/ledger-client-hook.jar" codex` before each call of a ledger write tool (on Windows: `cmd.exe /d /c java -jar "%PLUGIN_ROOT%/lib/ledger-client-hook.jar" codex`). The hook handles only the ten write tools (`save`, `transition`, `record_fix`, `record_case`, `link`, `correct`, `batch`, `review`, `review_correct` and `whoami`). It signs a call whose tool name is one of these forms:

- `mcp__<server>__ledger_<op>`
- `mcp__<server>__<server>_ledger_<op>`, where the prefix must equal the server name exactly
- `mcp__codex_apps__app_<app>_ledger_<op>`, the namespace of Codex Apps

Read tools, unknown operations and names of any other shape pass unchanged.

For a write call the hook adds the reserved argument `_hostAttestation`. Its value is a signature, made with the Ed25519 session key of the author `codex:<session_id>` for the MCP server of the call. The signature covers the tool, the request id, the SHA-256 digest of the canonical form (RFC 8785) of all arguments, and the time. If the call has no `request_id`, the hook generates one. The other arguments are kept, and an existing `author_id` of an older connector is neither used nor copied. The hook answers `allow` together with `updatedInput`; a refusal answers `deny`. The call is denied when the model supplies `_hostAttestation` itself or when the `session_id` of the host is not valid. Malformed JSON is also denied explicitly, with exit code 0, because a failing hook process is not a reliable block. The hook gives no other permission decision.

All apps of Codex Apps share the virtual server `codex_apps`, so the app is the audience of the signature and the server identity is `codex_apps.<app>`. For `mcp__codex_apps__app_ledger_ledger_save` it is `codex_apps.ledger`, and the owner uses that name when registering the key: `java -jar lib/ledger-client-hook.jar session-key codex_apps.ledger <author>`. A look-alike app such as `app_evil_ledger_save` gets another identity and another key, so its signature never verifies under the key of `codex_apps.ledger`.

Codex supplies the `session_id` of the parent session to the hooks of subagents. The hook does not look for another identifier and adds no layer of its own for the subagent.

## Requirements

The hook command looks up `java` on the `PATH`, so the host that runs Codex needs JDK 25 or later on its `PATH`. Without `java` the command fails, and with an older JDK the JVM refuses the jar with `UnsupportedClassVersionError`. In both cases no `_hostAttestation` is added, and the server refuses the write with `HOST_ATTESTATION_REQUIRED`. A failing hook therefore never lets a write without a proven author be stored.

## Installation

The jar is not committed to the repository. `./gradlew :ledger-client-hook:assemble` creates `ledger-client-hook/build/client-plugins/codex/`, a plugin directory that holds the jar as `lib/ledger-client-hook.jar`. Copy this directory to the plugin location of Codex. After Codex has loaded the plugin, review and trust the exact definition of the hook in `/hooks`.

To use the hook as a project hook, merge the same entry into `<repo>/.codex/hooks.json` and replace the path of the jar with its absolute path. Do not overwrite an existing hook and do not install the hook twice.

## Registering the key of a session

1. The first write call of an author for a server creates the key pair of that author and server. The keys are kept in an owner-only folder: `~/.pg-ledger-mcp/session-keys`, or the absolute path in `LEDGER_HOOK_KEY_DIR`. On Windows the folder gets an ACL for its owner only, and a folder or file that grants access to another principal is refused. A hook that runs elevated is refused, because the key folder then belongs to another owner.
2. `java -jar lib/ledger-client-hook.jar session-key <server> <author>` prints the server, the author, the public key and its fingerprint. `<server>` is the name of the MCP server as Codex shows it in the tool name, for example `ledger` for `mcp__ledger__ledger_save`; for Codex Apps it is `codex_apps.<app>`.
3. The owner compares the fingerprint with the one the session showed and registers the public key with `key-register` of `ledger-cli`, or on the grants page of the admin web. Only then do the grants of the author work.

The key belongs to one server and one author. A signature made for a test server, or for a server with a similar name, never verifies on the production server; each server gets its own registration. The server checks the signature, the tool, the request id, the arguments and the time before it looks at any grant.

## Limits

Another process of the same operating system user can read the key file, so isolation inside one user needs separate accounts. The hook answers with exit code 0 for an allowance and for a denial. It cannot block a call when the hook process fails or when a call path avoids the hook; the refusal `HOST_ATTESTATION_REQUIRED` of the server is the defence in those cases. Delivery by the real host, execution on Windows and a security review before production use need their own evidence.

## References

- [Hooks: search paths, trust, input and output](https://learn.chatgpt.com/docs/hooks)
- [Plugin manifest and hook paths](https://developers.openai.com/plugins/build/plugins)
- [Client hooks in the main README](../../README.md)
