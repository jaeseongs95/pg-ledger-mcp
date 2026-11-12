package org.pgledger.testing.database;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * A {@link DataSource} whose connections all use one schema of the test database, over a pool that many schemas share
 * (the connections of a test JVM are few). The schema is chosen with JDBC {@code Connection.setSchema}, as the product
 * does ({@code SchemaDataSource}). When {@code owned} is set, {@link #close()} closes that pool as well (a pool that
 * one context opened for itself); the shared pool is never closed here.
 */
final class ScopedDataSource extends DelegatingDataSource implements AutoCloseable {

    private final String schema;
    private final HikariDataSource owned;

    ScopedDataSource(DataSource target, String schema) {
        this(target, schema, null);
    }

    ScopedDataSource(DataSource target, String schema, HikariDataSource owned) {
        super(target);
        this.schema = schema;
        this.owned = owned;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withSchema(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withSchema(super.getConnection(username, password));
    }

    private Connection withSchema(Connection connection) throws SQLException {
        // No catch: the connection is closed again unless it is handed out (JDBC methods declare SQLException).
        boolean ready = false;
        try {
            connection.setSchema(schema);
            ready = true;
            return connection;
        } finally {
            if (!ready) {
                connection.close();
            }
        }
    }

    @Override
    public void close() {
        if (owned != null) {
            owned.close();
        }
    }
}
