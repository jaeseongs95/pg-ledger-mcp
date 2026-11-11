package org.pgledger.database.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The factory of fresh, empty databases for one test run: H2 databases in memory and databases on one embedded
 * PostgreSQL server that is started on first use. {@link LedgerDatabaseExtension} keeps one instance in the root
 * store of the run; JUnit closes it (and so stops the server) when the run ends.
 */
public final class Databases implements AutoCloseable {

    private EmbeddedPostgres postgres;

    private synchronized EmbeddedPostgres server() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException("cannot start embedded PostgreSQL", e);
            }
        }
        return postgres;
    }

    /** The URL of a new empty database (for PostgreSQL the superuser and the database name are in it). */
    public String newUrl(Dbms dbms) {
        return switch (dbms) {
            case H2 -> "jdbc:h2:mem:t" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
            case POSTGRESQL -> newPostgresUrl();
        };
    }

    private String newPostgresUrl() {
        EmbeddedPostgres pg = server();
        // a generated name cannot be bound: DDL takes an identifier, not a value
        String name = "t_" + UUID.randomUUID().toString().replace("-", "");
        DataSource admin = pg.getPostgresDatabase();
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create database " + name, e);
        }
        return pg.getJdbcUrl("postgres", name);
    }

    /** A pool on a new, empty database whose sessions run in {@code zone} (an ANSI-free test setup statement). */
    public HikariDataSource newDataSource(Dbms dbms, String zone, String schema) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(newUrl(dbms));
        config.setMaximumPoolSize(12);
        config.setConnectionInitSql("SET TIME ZONE '" + zone + "'");
        config.setSchema(schema);
        return new HikariDataSource(config);
    }

    public HikariDataSource newDataSource(Dbms dbms) {
        return newDataSource(dbms, "UTC", null);
    }

    @Override
    public synchronized void close() throws IOException {
        if (postgres != null) {
            postgres.close();
            postgres = null;
        }
    }
}
