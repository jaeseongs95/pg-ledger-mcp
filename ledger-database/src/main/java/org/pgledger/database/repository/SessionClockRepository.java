package org.pgledger.database.repository;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Reads the wall clock of the database session (ANSI {@code LOCALTIMESTAMP}) for the UTC guard. */
public final class SessionClockRepository {

    private final JdbcClient jdbc;

    public SessionClockRepository(Connection connection) {
        this.jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    /**
     * The session clock, read through an aggregate over our own table so that no vendor-specific "select without
     * table" is needed. Empty when the table has no row yet.
     */
    public Optional<LocalDateTime> sessionTimestamp() {
        return jdbc.sql("SELECT MAX(LOCALTIMESTAMP) FROM schema_history")
                .query((rs, n) -> rs.getObject(1, LocalDateTime.class))
                .optional();
    }
}
