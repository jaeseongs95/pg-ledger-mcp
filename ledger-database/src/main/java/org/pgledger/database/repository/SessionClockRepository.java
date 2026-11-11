package org.pgledger.database.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the wall clock of the database session (ANSI {@code LOCALTIMESTAMP}) for the UTC guard. */
public final class SessionClockRepository {

    private final JdbcClient jdbc;

    public SessionClockRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
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
