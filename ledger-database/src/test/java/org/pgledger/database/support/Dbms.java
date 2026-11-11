package org.pgledger.database.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;

/** The two databases the repository tests run on: H2 in default mode and an embedded PostgreSQL. */
public enum Dbms {
    H2("CLOB", false), POSTGRESQL("TEXT", true);

    private final String longText;
    private final boolean transactionalDdl;

    Dbms(String longText, boolean transactionalDdl) {
        this.longText = longText;
        this.transactionalDdl = transactionalDdl;
    }

    /** The long text type that replaces the placeholder of the schema files. */
    public String longText() {
        return longText;
    }

    /** True when a failed schema file is rolled back as a whole (H2 commits every DDL statement at once). */
    public boolean transactionalDdl() {
        return transactionalDdl;
    }

    /** The URL of a new empty database. */
    public String newUrl() {
        return this == H2 ? "jdbc:h2:mem:t" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1"
                : EmbeddedPg.newDatabaseUrl();
    }

    /** A pool on a new, empty database whose sessions run in {@code zone} (an ANSI-free test setup statement). */
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
