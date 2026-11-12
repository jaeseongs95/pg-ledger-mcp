package org.pgledger.database.schema;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.database.repository.SessionClockRepository;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

/**
 * Startup guard: the JVM and the database session must run in UTC. The database check compares the ANSI
 * {@code LOCALTIMESTAMP} of the session with the UTC clock. A failure is an {@link InfrastructureException}
 * (JVM_ZONE_NOT_UTC, SESSION_ZONE_NOT_UTC, SESSION_CLOCK_UNREADABLE, SCHEMA_DATABASE_UNREACHABLE).
 */
@Component
public class UtcSessionCheck {

    private static final Logger LOG = LoggerFactory.getLogger(UtcSessionCheck.class);
    /** Real zones differ by at least 15 minutes, so one minute separates "UTC" from "not UTC" safely. */
    static final Duration TOLERANCE = Duration.ofMinutes(1);

    private final DataSource dataSource;
    private final Clock clock;
    private final SchemaProperties properties;

    public UtcSessionCheck(DataSource dataSource, Clock clock, SchemaProperties properties) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.properties = properties;
    }

    public void verify() {
        verifyJvmZone();
        verifySessionZone();
    }

    /**
     * The JVM default zone must be UTC: Spring Data JDBC reads TIMESTAMP columns through {@link Timestamp}, which
     * drivers build in the JVM or session zone. {@code Timestamp.valueOf(LocalDateTime)} reads the wall clock in
     * the default zone, so no zone API is called to find out.
     *
     * @throws InfrastructureException JVM_ZONE_NOT_UTC
     */
    public static void verifyJvmZone() {
        for (LocalDateTime wallClock : new LocalDateTime[] {LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 7, 1, 0, 0)}) {
            if (Timestamp.valueOf(wallClock).getTime() != wallClock.toInstant(ZoneOffset.UTC).toEpochMilli()) {
                throw new InfrastructureException(InfrastructureCode.JVM_ZONE_NOT_UTC, List.of());
            }
        }
    }

    /** @throws InfrastructureException SESSION_ZONE_NOT_UTC, SESSION_CLOCK_UNREADABLE */
    public void verifySessionZone() {
        LocalDateTime session = readSessionClock();
        LocalDateTime utc = UtcTime.toDb(clock.instant());
        Duration skew = Duration.between(utc, session).abs();
        if (skew.compareTo(TOLERANCE) > 0) {
            throw new InfrastructureException(InfrastructureCode.SESSION_ZONE_NOT_UTC, List.of(skew.toString()));
        }
        LOG.info("database session zone verified as UTC (skew {})", skew);
    }

    /**
     * The session clock is the ANSI {@code LOCALTIMESTAMP} (the wall clock of the session), not a function of one DBMS.
     * It is the point of the check: the session zone is what {@code LOCALTIMESTAMP} shows, and the guard compares that with
     * the UTC clock. The application itself never reads a time from the database (the {@code Clock} is the only "now").
     */
    private LocalDateTime readSessionClock() {
        try {
            return new SessionClockRepository(SchemaDataSource.of(dataSource, properties.name())).sessionTimestamp()
                    .orElseThrow(() -> new InfrastructureException(InfrastructureCode.SESSION_CLOCK_UNREADABLE,
                            List.of()));
        } catch (CannotGetJdbcConnectionException e) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_DATABASE_UNREACHABLE, List.of(), e);
        }
    }
}
