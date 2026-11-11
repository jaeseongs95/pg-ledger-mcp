package org.pgledger.database.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.database.schema.GrantApplier.Profile;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * One new H2 database owned by the owner account (no account name, the one that may do DDL and grant) with the accounts of
 * the two applications: A, the MCP server, and B, the admin web and the owner commands of the CLI. The schema is built by the
 * real migrator; the grants are applied by the real {@link GrantApplier} from the real templates. Creating an account is
 * an H2 statement and exists in the tests only (the product never creates an account: that is a statement of the DBMS and
 * stays an operator step).
 */
public final class AccountDatabase implements AutoCloseable {

    /** H2: not enough rights for the statement. */
    public static final String PERMISSION_DENIED = "90096";
    /** H2 refuses to truncate a table that a foreign key refers to (history refers to itself) before it asks for rights. */
    public static final String H2_CANNOT_TRUNCATE = "90106";

    private static final String PASSWORD = "pw-for-the-test";

    private final String ownerUrl;
    private final String accountUrl;
    private final HikariDataSource owner;
    private final HikariDataSource mcp;
    private final HikariDataSource admin;
    private final String mcpAccount;
    private final String adminAccount;

    private AccountDatabase(String ownerUrl, HikariDataSource owner, String mcpAccount, String adminAccount) {
        this.ownerUrl = ownerUrl;
        // the database options of the URL (after the first ';') are the owner's
        this.accountUrl = ownerUrl.substring(0, ownerUrl.indexOf(';'));
        this.owner = owner;
        this.mcpAccount = mcpAccount;
        this.adminAccount = adminAccount;
        this.mcp = pool(accountUrl, mcpAccount, PASSWORD);
        this.admin = pool(accountUrl, adminAccount, PASSWORD);
    }

    /**
     * @param migrate whether the owner applies the schema
     * @param grant   whether the owner applies the two templates (needs the schema)
     */
    public static AccountDatabase create(Databases databases, boolean migrate, boolean grant) throws SQLException {
        String url = databases.newUrl();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String mcpAccount = "MCP_" + suffix;
        String adminAccount = "ADM_" + suffix;
        HikariDataSource owner = pool(url, null, null);
        SchemaProperties properties = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true,
                null, Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT));
        if (migrate) {
            new SchemaMigrator(owner, new PathMatchingResourcePatternResolver(), properties, Clock.systemUTC()).migrate();
        }
        try (Connection c = owner.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE USER " + mcpAccount + " PASSWORD '" + PASSWORD + "'");
            s.execute("CREATE USER " + adminAccount + " PASSWORD '" + PASSWORD + "'");
        }
        if (grant) {
            GrantApplier applier = new GrantApplier(owner, properties);
            applier.apply(Profile.MCP, mcpAccount);
            applier.apply(Profile.ADMIN, adminAccount);
        }
        return new AccountDatabase(url, owner, mcpAccount, adminAccount);
    }

    private static HikariDataSource pool(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        if (user != null) {
            config.setUsername(user);
            config.setPassword(password);
        }
        config.setMaximumPoolSize(4);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return new HikariDataSource(config);
    }

    public HikariDataSource owner() {
        return owner;
    }

    public HikariDataSource mcp() {
        return mcp;
    }

    public HikariDataSource admin() {
        return admin;
    }

    public String mcpAccount() {
        return mcpAccount;
    }

    public String adminAccount() {
        return adminAccount;
    }

    /** The repositories of the ledger as the owner (its own pool, closed with the returned context). */
    public LedgerTestContext ownerContext() {
        return LedgerTestContext.onMigrated(pool(ownerUrl, null, null));
    }

    /** The repositories of the ledger as account A (its own pool, closed with the returned context). */
    public LedgerTestContext mcpContext() {
        return LedgerTestContext.onMigrated(pool(accountUrl, mcpAccount, PASSWORD));
    }

    /** The repositories of the ledger as account B (its own pool, closed with the returned context). */
    public LedgerTestContext adminContext() {
        return LedgerTestContext.onMigrated(pool(accountUrl, adminAccount, PASSWORD));
    }

    /** The statement fails with the refusal of a missing right, not with a mistake in the statement. */
    public static void refused(DataSource account, String sql) {
        assertThatThrownBy(() -> run(account, sql)).as(sql).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).as("a refusal for a missing right, not a mistake in the statement")
                        .isIn(PERMISSION_DENIED, H2_CANNOT_TRUNCATE));
    }

    public static void allowed(DataSource account, String sql) throws SQLException {
        run(account, sql);
    }

    private static void run(DataSource account, String sql) throws SQLException {
        try (Connection c = account.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Override
    public void close() {
        admin.close();
        mcp.close();
        owner.close();
    }
}
