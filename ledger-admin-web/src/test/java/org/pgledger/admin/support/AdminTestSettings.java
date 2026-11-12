package org.pgledger.admin.support;

/**
 * The configuration properties of the admin test contexts: the PostgreSQL of the test PC is brought by the test
 * configuration ({@code TestDataSourceConfiguration}); these are the secrets (generated test values, not real ones)
 * and the quiet logging. The embedded profile makes the application take that database from the context instead of
 * the registered JDBC driver.
 */
public final class AdminTestSettings {

    /** The admin token of the test application (a test value, not a secret). */
    public static final String TOKEN = "test-admin-token-0123456789-abcdef";

    /** The agent token that the test application knows (it must be refused on the admin). */
    public static final String AGENT_TOKEN = "test-agent-token-0123456789-abcdef";

    public static final String TOKEN_PROPERTY = "ledger.admin.token=" + TOKEN;
    public static final String AGENT_TOKEN_PROPERTY = "ledger.admin.agent-token=" + AGENT_TOKEN;
    public static final String QUIET_BANNER = "spring.main.banner-mode=off";
    public static final String QUIET_LOG = "logging.level.root=WARN";
    public static final String NO_JMX = "spring.jmx.enabled=false";
    /** The session cookie is Secure by default; the tests that talk plain HTTP to a real server say so explicitly. */
    public static final String INSECURE_COOKIE = "server.servlet.session.cookie.secure=false";
    /** The cap on failures of all clients together is out of the way of the many tests that share one context. */
    public static final String NO_GLOBAL_CAP = "ledger.admin.lockout.global-max-failures=1000000";
    public static final String OWNER_EMAIL = "owner@example.test";

    private AdminTestSettings() {
    }
}
