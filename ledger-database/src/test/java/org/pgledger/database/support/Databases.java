package org.pgledger.database.support;

import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.database.TestSchema;

/**
 * The factory of fresh, empty "databases" for the tests: each is a schema of its own in the PostgreSQL of the test PC
 * (TEST_CASES.md 1.4a; the test database support is {@link TestDatabase}). The schemas this factory made are dropped
 * when it is closed, which the JUnit extension does at the end of the test that asked for it; whatever a failing test
 * leaves is dropped by the shutdown hook of the test JVM and by the sweep of the next run.
 */
public final class Databases implements AutoCloseable {

    /** The long text type of the schema files: the default of the product. */
    public static final String LONG_TEXT = SchemaProperties.DEFAULT_LONG_TEXT_TYPE;

    private final List<TestSchema> schemas = new ArrayList<>();
    private final List<String> reserved = new ArrayList<>();

    /** The test database of this JVM. */
    public TestDatabase database() {
        return TestDatabase.shared();
    }

    /** A new, empty schema, dropped by {@link #close()}. */
    public TestSchema newSchema() {
        TestSchema schema = database().newSchema();
        schemas.add(schema);
        return schema;
    }

    /**
     * A schema name that no other test uses, for a schema that the code under test creates (the product's
     * {@code ledger.schema.name}); the schema is dropped by {@link #close()} when it exists.
     */
    public String reserveSchemaName() {
        String name = database().reserveSchemaName();
        reserved.add(name);
        return name;
    }

    /** A data source on a new, empty schema (the shared pool of the owner account, every connection in the schema). */
    public DataSource newDataSource() {
        return newSchema().dataSource();
    }

    /** A pool whose sessions run in {@code zone}; the caller closes it. */
    public HikariDataSource newPool(String zone) {
        return database().newPool(zone);
    }

    @Override
    public void close() {
        for (TestSchema schema : schemas) {
            schema.close();
        }
        schemas.clear();
        for (String name : reserved) {
            database().drop(name);
        }
        reserved.clear();
    }
}
