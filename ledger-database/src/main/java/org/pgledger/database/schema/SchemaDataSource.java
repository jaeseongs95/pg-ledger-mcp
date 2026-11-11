package org.pgledger.database.schema;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * A {@link DataSource} whose connections all use one schema. The schema is chosen with JDBC
 * {@code Connection.setSchema}, the one portable way (ANSI {@code SET SCHEMA} is not understood by every DBMS), and
 * this class is the only place that calls it. The services get and release their connections through Spring
 * ({@code JdbcClient}, {@code JdbcUtils}, transactions), never directly.
 */
final class SchemaDataSource extends DelegatingDataSource {

    private final String schema;

    private SchemaDataSource(DataSource target, String schema) {
        super(target);
        this.schema = schema;
    }

    /** {@code target} itself when {@code schema} is {@code null}: the default schema of the account. */
    static DataSource of(DataSource target, String schema) {
        return schema == null ? target : new SchemaDataSource(target, schema);
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
            connection.setSchema(SchemaHistoryRepository.fold(connection.getMetaData(), schema));
            ready = true;
            return connection;
        } finally {
            if (!ready) {
                connection.close();
            }
        }
    }

}
