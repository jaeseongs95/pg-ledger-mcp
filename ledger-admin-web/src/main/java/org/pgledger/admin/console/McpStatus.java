package org.pgledger.admin.console;

/** The MCP server as the overview shows it: up, down (and why) or not configured. */
public record McpStatus(State state, Reason reason) {

    public enum State {
        UP, DOWN, NOT_CONFIGURED
    }

    /** Why the server counts as down; the sentence for each is in the message bundle. */
    public enum Reason {
        NONE, REPORTS_DOWN, UNREACHABLE, TIMEOUT, HTTP_ERROR, INVALID_ANSWER
    }
}
