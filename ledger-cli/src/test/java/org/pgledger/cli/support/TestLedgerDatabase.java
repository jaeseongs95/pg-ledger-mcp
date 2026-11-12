package org.pgledger.cli.support;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.database.TestSchema;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The PostgreSQL of the test PC as the ledger database of a CLI test (TEST_CASES.md 1.4a): a schema of its own in the test
 * database ({@link TestSchema}), with the schema files migrated by the real {@code SchemaMigrator} as the admin web tests
 * do. Its DataSource is the shared pool of the test JVM, every connection using that schema ({@link TestDatabase#scoped}),
 * so the connections of the JVM stay few. The test reads the rows the CLI wrote through {@link #rows}; column names are the
 * lower-case names of PostgreSQL. {@link #close()} drops the schema.
 */
public final class TestLedgerDatabase implements AutoCloseable {

    private final TestSchema schema;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    private TestLedgerDatabase(TestSchema schema) {
        this.schema = schema;
        this.dataSource = schema.dataSource();
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** A new schema with the whole ledger schema migrated. */
    public static TestLedgerDatabase migrated() {
        TestLedgerDatabase database = empty();
        // No catch: a schema that could not be migrated is dropped again, and the failure goes on to the test.
        boolean ready = false;
        try {
            new SchemaMigrator(database.dataSource, new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, longTextType(), true, database.schemaName(),
                            Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), Clock.systemUTC()).migrate();
            ready = true;
        } finally {
            if (!ready) {
                database.close();
            }
        }
        return database;
    }

    /** The long text type of the schema files: the default of the product, the setting {@code ledger.schema.long-text-type}. */
    public static String longTextType() {
        return SchemaProperties.DEFAULT_LONG_TEXT_TYPE;
    }

    /** A new, empty schema (no table; the schema files are not migrated). */
    public static TestLedgerDatabase empty() {
        return new TestLedgerDatabase(TestDatabase.shared().newSchema());
    }

    /** The shared pool of the owner account, every connection using the schema of this database. */
    public DataSource dataSource() {
        return dataSource;
    }

    /** The name of the schema of this database in the test database. */
    public String schemaName() {
        return schema.name();
    }

    /** The application account of the tests ({@code ledger.test.app-user}): the account the CLI grants rights to. */
    public String appAccount() {
        return TestDatabase.shared().settings().appUser();
    }

    /**
     * A JDBC template that connects to this schema as the application account (the least-privilege login of the tests;
     * the operator makes that account with the tools of the DBMS, the tests do not create one: the owner account may not).
     * The account is let into the schema ({@code USAGE}, the step the grant template leaves to the operator); what it may do
     * in the tables is only what the grant template gave it ({@code migrate --app-account}). The rights vanish with the
     * schema.
     */
    public JdbcTemplate asAppAccount() {
        schema.allowUsage(appAccount());
        return new JdbcTemplate(TestDatabase.scoped(TestDatabase.shared().appPool(), schema.name()));
    }

    /** The rows of an ANSI query (the column names are the lower-case names of PostgreSQL). */
    public List<Map<String, Object>> rows(String sql, Object... arguments) {
        return jdbc.queryForList(sql, arguments);
    }

    /** Runs an ANSI DML statement (the test changes a row the product would never change) and returns the row count. */
    public int update(String sql, Object... arguments) {
        return jdbc.update(sql, arguments);
    }

    /** Drops the schema with everything in it; the shared pool stays open. */
    @Override
    public void close() {
        schema.close();
    }
}
