package org.pgledger.database.time;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The single place that maps between {@link Instant} and a TIMESTAMP (without time zone) column that holds UTC.
 * It uses JDBC 4.2 {@link LocalDateTime}, which carries no zone, so neither the JVM default zone nor the database
 * session zone takes part in the conversion. Values are cut to microseconds, the precision of the columns, so a
 * value read back equals the value written.
 */
public final class UtcTime {

    private UtcTime() {
    }

    public static LocalDateTime toDb(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    public static Instant fromDb(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public static Instant read(ResultSet rs, String column) throws SQLException {
        return fromDb(rs.getObject(column, LocalDateTime.class));
    }
}
