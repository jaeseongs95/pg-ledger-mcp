package org.pgledger.database.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;

/**
 * The factory of fresh, empty databases for the tests: embedded H2 databases in memory, in the default mode (no
 * compatibility mode). The ledger runs on any DBMS the user attaches a JDBC driver for; the tests use H2 only, and
 * the real DBMS is verified at the test server stage.
 */
public final class Databases {

    /** The long text type of H2 that replaces the placeholder of the schema files. */
    public static final String LONG_TEXT = "CLOB";

    /** The URL of a new empty database. */
    public String newUrl() {
        return "jdbc:h2:mem:t" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000";
    }

    /** A pool on a new, empty database whose sessions run in {@code zone} (a test setup statement of H2). */
    public HikariDataSource newDataSource(String zone, String schema) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(newUrl());
        config.setMaximumPoolSize(12);
        config.setConnectionInitSql("SET TIME ZONE '" + zone + "'");
        config.setSchema(schema);
        return new HikariDataSource(config);
    }

    public HikariDataSource newDataSource() {
        return newDataSource("UTC", null);
    }
}
