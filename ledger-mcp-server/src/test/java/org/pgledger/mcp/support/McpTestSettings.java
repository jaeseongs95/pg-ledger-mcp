package org.pgledger.mcp.support;

/**
 * The configuration properties of the MCP test contexts: a quiet log, the test token of the agents and a fast poll of
 * the common-code revision. The database is not set here: the contexts use the PostgreSQL of the test PC through the
 * shared pool of the test database support (a {@code TestSchemaInitializer} gives each context a schema of its own).
 */
public final class McpTestSettings {

    /** The bearer token of the test server (a test value, not a secret). */
    public static final String TOKEN = "test-token-0123456789-abcdefghij";

    /** The poll interval of the common-code revision in the tests, as a Boot duration. */
    public static final String POLL = "200ms";

    public static final String QUIET_BANNER = "spring.main.banner-mode=off";
    public static final String QUIET_LOG = "logging.level.root=WARN";
    public static final String NO_JMX = "spring.jmx.enabled=false";
    public static final String TOKEN_PROPERTY = "ledger.mcp.token=" + TOKEN;
    public static final String POLL_PROPERTY = "ledger.mcp.code-poll-interval=" + POLL;

    /** The one browser origin the test server allows (an Origin of any other page is refused). */
    public static final String ALLOWED_ORIGIN = "http://allowed.example";
    public static final String ORIGIN_PROPERTY = "ledger.mcp.allowed-origins=" + ALLOWED_ORIGIN;

    /** The host names the test server allows in the Host header (the defaults of the server). */
    public static final String ALLOWED_HOST = "localhost";

    private McpTestSettings() {
    }
}
