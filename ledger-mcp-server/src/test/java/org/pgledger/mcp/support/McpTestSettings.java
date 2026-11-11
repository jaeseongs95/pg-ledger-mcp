package org.pgledger.mcp.support;

/**
 * The configuration properties of the MCP test contexts: an embedded H2 database in its default mode (no
 * compatibility mode), a pool that holds the threads of the parallel tests, a database session in UTC, the test
 * token of the agents and a fast poll of the common-code revision.
 */
public final class McpTestSettings {

    /** The bearer token of the test server (a test value, not a secret). */
    public static final String TOKEN = "test-token-0123456789-abcdefghij";

    /** The poll interval of the common-code revision in the tests, as a Boot duration. */
    public static final String POLL = "200ms";

    public static final String URL = "spring.datasource.url=jdbc:h2:mem:${random.uuid};DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000";
    public static final String POOL = "spring.datasource.hikari.maximum-pool-size=40";
    public static final String UTC_SESSION = "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'";
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
