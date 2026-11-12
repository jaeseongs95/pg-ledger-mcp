package org.pgledger.testing.database;

import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.pgledger.database.schema.GrantApplier;

/**
 * One schema of the test database that a test (or a test class) owns: created empty, dropped by {@link #close()}.
 * {@link #dataSource()} reaches it through the shared pool of the owner account.
 */
public final class TestSchema implements AutoCloseable {

    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,29}");

    private final TestDatabase database;
    private final String name;

    TestSchema(TestDatabase database, String name) {
        this.database = database;
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** The owner account's connections, all using this schema. */
    public DataSource dataSource() {
        return TestDatabase.scoped(database.pool(), name);
    }

    /**
     * Lets {@code account} see this schema ({@code USAGE}): the step of the DBMS that the grant template leaves to the
     * operator. The table rights come from the template ({@link GrantApplier}) or from {@link #grantSelect}.
     */
    public TestSchema allowUsage(String account) {
        database.execute("GRANT USAGE ON SCHEMA " + name + " TO " + requireAccount(account));
        return this;
    }

    /** Gives {@code account} {@code SELECT} on one table of this schema. */
    public TestSchema grantSelect(String account, String table) {
        database.execute("GRANT SELECT ON " + name + "." + table + " TO " + requireAccount(account));
        return this;
    }

    private static String requireAccount(String account) {
        if (!ACCOUNT.matcher(account).matches()) {
            throw new IllegalArgumentException("not a plain account name: " + account);
        }
        return account;
    }

    /** Drops the schema with its tables and with every right that was granted on them. */
    @Override
    public void close() {
        database.drop(name);
    }
}
