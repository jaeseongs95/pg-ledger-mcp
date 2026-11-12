package org.pgledger.hook;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The MCP server (as the host names it) and the ledger write tool that a host tool name stands for. The hook signs only
 * for a name that its host dialect recognises, and the server name is the audience of the signature: the key of an
 * author is kept per server, so a signature made for one server is of no use on another.
 *
 * <p>This is the value and the check of the server name only. How a host spells a tool name (which shapes, which
 * separators, which pseudo servers) is the business of that host and lives in its dialect, so that a new host or a
 * changed spelling is one class and no shared code changes (see {@link HostDialect}). The operation is one of the ten
 * known ones, exactly ({@link WriteTool}).
 *
 * @param server the server name, 1 to 100 characters of {@code A-Za-z0-9._-} (a dialect may allow fewer characters in its
 *               own spelling)
 * @param tool   the write tool; the attestation is issued for its canonical name {@code ledger_<operation>}
 */
record McpToolName(String server, WriteTool tool) {

    /** The characters of a server name, as the body of a regular expression character class. */
    static final String SERVER_CHARACTERS = "A-Za-z0-9._-";
    /** The longest server name. */
    static final int MAX_SERVER_LENGTH = 100;
    /** A server name as the owner may give it to {@code session-key} and as the hook accepts it from a host. */
    private static final Pattern SERVER = Pattern.compile("[" + SERVER_CHARACTERS + "]{1," + MAX_SERVER_LENGTH + "}");

    /** Whether the text is a server name: the same rule for a name from a host and one given to {@code session-key}. */
    static boolean isServerName(String text) {
        return text != null && SERVER.matcher(text).matches();
    }

    /** The tool of the server for an operation name that a dialect read from a host name; empty unless it is one of the ten. */
    static Optional<McpToolName> ofOperation(String server, String operation) {
        return WriteTool.ofOperation(operation).map(tool -> new McpToolName(server, tool));
    }
}
