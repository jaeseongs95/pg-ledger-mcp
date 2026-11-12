package org.pgledger.database.schema;

import javax.sql.DataSource;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.dao.DataAccessException;

/**
 * Whether the application account can use the ledger schema: the one account of the MCP server, the admin web and the owner
 * commands. {@code setup} runs as the owner account (the DDL account) and makes the schema, so it cannot tell by its own
 * connection whether the application account, which the servers connect as, may use it; this asks the application account
 * itself, over a connection of its own, the question the servers ask at their start ({@link SchemaVerifier}: the history table
 * must be visible and up to date). The right to use a schema is not part of the product SQL (it is not ANSI SQL, see
 * {@code db-templates/dml-grants.sql}) and is a step of the operator with the tools of the DBMS, so a "no" is the operator's
 * to fix and the answer is a plain boolean; the reason is in the log, never the password.
 */
public final class ApplicationAccountAccess {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationAccountAccess.class);
    /** What is shown for an account that is not configured. */
    private static final String NOT_CONFIGURED = "(not configured)";

    private final String account;
    private final SchemaVerifier verifier;

    /**
     * @param account     the name of the application account, or null when none is configured (the answer is then no)
     * @param application connections of the application account, not those of the run
     */
    public ApplicationAccountAccess(String account, DataSource application, ResourcePatternResolver resolver,
            SchemaProperties properties) {
        this.account = account == null || account.isBlank() ? null : account;
        this.verifier = new SchemaVerifier(application, resolver, properties);
    }

    /** The answer for a run in which no application account is configured: always no. */
    public static ApplicationAccountAccess notConfigured(ResourcePatternResolver resolver, SchemaProperties properties) {
        return new ApplicationAccountAccess(null, null, resolver, properties);
    }

    /** The name of the application account, for a message. */
    public String account() {
        return account == null ? NOT_CONFIGURED : account;
    }

    /** True when the application account can connect and see the schema history table the way the servers do at their start. */
    public boolean canUseSchema() {
        if (account == null) {
            LOG.warn("no application account is configured (ledger.jdbc.username), so it cannot be asked about the schema");
            return false;
        }
        try {
            verifier.verify();
            return true;
        } catch (InfrastructureException | DataAccessException cannot) {
            LOG.warn("the application account {} cannot use the ledger schema: {}", account, cannot.toString(), cannot);
            return false;
        }
    }
}
