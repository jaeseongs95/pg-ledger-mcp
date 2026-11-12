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
     *
     * <p>This is the one place that asks the database for the time, and on purpose (principle 4.5 forbids DB clock
     * functions for the data): the ANSI {@code LOCALTIMESTAMP} shows the wall clock of the database session, and that is what
     * the UTC guard ({@code UtcSessionCheck}) compares with the UTC {@code Clock} to find out whether the session zone is
     * UTC. No row is ever written with this value.
     */
    public Optional<LocalDateTime> sessionTimestamp() {
        return jdbc.sql("SELECT MAX(LOCALTIMESTAMP) FROM schema_history")
                .query((rs, n) -> rs.getObject(1, LocalDateTime.class))
                .optional();
    }
}
