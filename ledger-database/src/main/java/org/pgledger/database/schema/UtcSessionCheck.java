package org.pgledger.database.schema;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.pgledger.database.repository.SessionClockRepository;
import org.pgledger.database.time.UtcTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Startup guard: the JVM and the database session must run in UTC. The database check compares the ANSI
 * {@code LOCALTIMESTAMP} of the session with the UTC clock.
 */
public class UtcSessionCheck {

    private static final Logger LOG = LoggerFactory.getLogger(UtcSessionCheck.class);
    /** Real zones differ by at least 15 minutes, so one minute separates "UTC" from "not UTC" safely. */
    static final Duration TOLERANCE = Duration.ofMinutes(1);

    private final DataSource dataSource;
    private final Clock clock;
    private final String schemaName;

    public UtcSessionCheck(DataSource dataSource, Clock clock, String schemaName) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.schemaName = schemaName;
    }

    public void verify() {
        verifyJvmZone();
        verifySessionZone();
    }

    /**
     * The JVM default zone must be UTC: Spring Data JDBC reads TIMESTAMP columns through {@link Timestamp}, which
     * drivers build in the JVM or session zone. {@code Timestamp.valueOf(LocalDateTime)} reads the wall clock in
     * the default zone, so no zone API is called to find out.
     */
    public static void verifyJvmZone() {
        for (LocalDateTime wallClock : new LocalDateTime[] {LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 7, 1, 0, 0)}) {
            if (Timestamp.valueOf(wallClock).getTime() != wallClock.toInstant(ZoneOffset.UTC).toEpochMilli()) {
                throw new SchemaMigrationException("JVM default time zone is not UTC (start the JVM with "
                        + "-Duser.timezone=UTC)");
            }
        }
    }

    public void verifySessionZone() {
        LocalDateTime session = readSessionClock();
        LocalDateTime utc = UtcTime.toDb(clock.instant());
        Duration skew = Duration.between(utc, session).abs();
        if (skew.compareTo(TOLERANCE) > 0) {
            throw new SchemaMigrationException("database session time zone is not UTC (the session clock differs "
                    + "from the UTC clock by " + skew + ")");
        }
        LOG.info("database session zone verified as UTC (skew {})", skew);
    }

    private LocalDateTime readSessionClock() {
        try {
            return new SessionClockRepository(SchemaDataSource.of(dataSource, schemaName)).sessionTimestamp()
                    .orElseThrow(() -> new SchemaMigrationException(
                            "the session clock cannot be read: schema_history is empty"));
        } catch (CannotGetJdbcConnectionException e) {
            throw new SchemaMigrationException("cannot read the database session clock", e);
        }
    }
}
