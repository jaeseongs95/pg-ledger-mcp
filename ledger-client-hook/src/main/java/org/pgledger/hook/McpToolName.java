package org.pgledger.hook;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The MCP server (as the host names it) and the ledger write tool that a host tool name stands for. The hook signs only
 * for a name of exactly these shapes, and the server name is the audience of the signature: the key of an author is
 * kept per server, so a signature made for one server is of no use on another.
 *
 * <ul>
 * <li>Claude Code and Codex: {@code mcp__<server>__ledger_<op>}, or {@code mcp__<server>__<server>_ledger_<op>} when the
 *     tool reaches the host through a gateway that prefixes the tool names with the name of its upstream (the portal of
 *     the deployment names the upstream like the server, {@code mcp__ledger__ledger_ledger_save}). The prefix before
 *     {@code ledger_} must be exactly the server segment: a look-alike server that names its tool
 *     {@code other_ledger_save} is not signed for.
 * <li>Antigravity, direct: {@code mcp:<server><sep>ledger_<op>} or {@code mcp:<server><sep><server>_ledger_<op>} with a
 *     separator of {@code :}, {@code /} or {@code _}; a name without a server ({@code mcp:ledger_save}) names no
 *     audience and is not signed.
 * <li>Antigravity, {@code call_mcp_tool}: the {@code ServerName} of the envelope, and a {@code ToolName} of
 *     {@code ledger_<op>} or {@code <ServerName>_ledger_<op>}.
 * </ul>
 * The operation is one of the ten known ones, exactly. The Codex Apps namespace
 * {@code mcp__codex_apps__app_ledger_ledger_<op>} is none of these shapes (its prefix is not the server segment) and is
 * not signed for.
 *
 * @param server the server name, 1 to 100 characters of {@code A-Za-z0-9._-} (for the {@code mcp__} names of Claude
 *               Code and Codex, whose server names are made of {@code A-Za-z0-9_-}, without the dot)
 * @param tool   the write tool; the attestation is issued for its canonical name {@code ledger_<operation>}
 */
record McpToolName(String server, WriteTool tool) {

    private static final String OPERATIONS = WriteTool.alternation();
    private static final String SERVER_CHARACTERS = "A-Za-z0-9._-";
    private static final int MAX_SERVER_LENGTH = 100;
    /** A server name as the owner may give it to {@code session-key} and as the hook accepts it from a host. */
    private static final Pattern SERVER = Pattern.compile("[" + SERVER_CHARACTERS + "]{1," + MAX_SERVER_LENGTH + "}");
    private static final Pattern DOUBLE_UNDERSCORE = Pattern.compile(
            "^mcp__([A-Za-z0-9_-]{1," + MAX_SERVER_LENGTH + "}?)__(?:\\1_)?ledger_" + OPERATIONS + "$");
    private static final Pattern ANTIGRAVITY_DIRECT = Pattern.compile(
            "^mcp:([" + SERVER_CHARACTERS + "]{1," + MAX_SERVER_LENGTH + "}?)[:/_](?:\\1_)?ledger_" + OPERATIONS + "$");

    /** Whether the text is a server name: the same rule for a name from a host and one given to {@code session-key}. */
    static boolean isServerName(String text) {
        return text != null && SERVER.matcher(text).matches();
    }

    /** Claude Code and Codex: {@code mcp__<server>__[<server>_]ledger_<op>}. */
    static Optional<McpToolName> ofDoubleUnderscore(String hostToolName) {
        return of(DOUBLE_UNDERSCORE.matcher(hostToolName));
    }

    /** Antigravity, a direct MCP call: {@code mcp:<server><sep>[<server>_]ledger_<op>}. */
    static Optional<McpToolName> ofAntigravityDirect(String hostToolName) {
        return of(ANTIGRAVITY_DIRECT.matcher(hostToolName));
    }

    /** Antigravity, {@code call_mcp_tool}: the {@code ServerName} and the {@code ToolName} of the envelope. */
    static Optional<McpToolName> ofEnvelope(String serverName, String toolName) {
        if (!isServerName(serverName) || toolName == null) {
            return Optional.empty();
        }
        Pattern tool = Pattern.compile("^(?:" + Pattern.quote(serverName) + "_)?ledger_" + OPERATIONS + "$");
        Matcher matcher = tool.matcher(toolName);
        return matcher.matches() ? WriteTool.ofOperation(matcher.group(1)).map(op -> new McpToolName(serverName, op))
                : Optional.empty();
    }

    private static Optional<McpToolName> of(Matcher matcher) {
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return WriteTool.ofOperation(matcher.group(2)).map(op -> new McpToolName(matcher.group(1), op));
    }
}
