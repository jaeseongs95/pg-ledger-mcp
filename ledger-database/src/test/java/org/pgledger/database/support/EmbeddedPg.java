package org.pgledger.database.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

/** One embedded PostgreSQL server per test JVM; every caller gets its own empty database. */
public final class EmbeddedPg {

    private static EmbeddedPostgres server;

    private EmbeddedPg() {
    }

    private static synchronized EmbeddedPostgres server() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException("cannot start embedded PostgreSQL", e);
            }
        }
        return server;
    }

    /** Creates a new database and returns its JDBC URL (the superuser and the database name are in it). */
    public static String newDatabaseUrl() {
        EmbeddedPostgres pg = server();
        String name = "t_" + UUID.randomUUID().toString().replace("-", "");
        DataSource admin = pg.getPostgresDatabase();
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create database " + name, e);
        }
        return pg.getJdbcUrl("postgres", name);
    }
}
