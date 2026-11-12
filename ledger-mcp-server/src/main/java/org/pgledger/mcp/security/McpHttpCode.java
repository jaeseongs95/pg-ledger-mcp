package org.pgledger.mcp.security;

/**
 * The {@code code} of a problem the server answers with outside a tool call (an HTTP failure; a failed tool is a tool
 * result with its own error tokens). The constant name is the token of the external contract and the key of the sentence
 * in the message bundle is {@code error.<token>}.
 */
enum McpHttpCode {
    PAYLOAD_TOO_LARGE, INTERNAL_ERROR;

    String messageKey() {
        return "error." + name();
    }
}
