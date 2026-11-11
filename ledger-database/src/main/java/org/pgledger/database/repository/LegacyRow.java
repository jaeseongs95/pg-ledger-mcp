package org.pgledger.database.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pgledger.database.time.UtcTime;

/**
 * One row of a legacy import table, by column name, in column order. A value is a {@link String}, a {@link Long}, an
 * {@link Instant}, a {@link Boolean} or {@code null}, whatever the column of the source or the target holds, so the same
 * row type carries a row as it was read from the legacy schema, as it is to be written to the new schema, and as it is read
 * back from there.
 *
 * @param values the values by column name (insertion ordered; a value may be null)
 */
public record LegacyRow(Map<String, Object> values) {

    public LegacyRow {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Reads the current row of a legacy query whose select list is {@code columns}. A time value is read as a JDBC 4.2
     * {@link OffsetDateTime}, the instant of a zone aware column, so no session or JVM time zone takes part.
     */
    public static LegacyRow ofSource(ResultSet rs, List<LegacyColumn> columns) throws SQLException {
        return read(rs, columns, false);
    }

    /** Reads the current row of a query of the new schema, whose time columns hold UTC without a zone. */
    public static LegacyRow ofTarget(ResultSet rs, List<LegacyColumn> columns) throws SQLException {
        return read(rs, columns, true);
    }

    private static LegacyRow read(ResultSet rs, List<LegacyColumn> columns, boolean utcColumns) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            int index = i + 1;
            LegacyColumn column = columns.get(i);
            Object value = switch (column.kind()) {
                case TEXT -> rs.getString(index);
                case LONG -> {
                    long number = rs.getLong(index);
                    yield rs.wasNull() ? null : Long.valueOf(number);
                }
                case INSTANT -> utcColumns ? UtcTime.fromDb(rs.getObject(index, LocalDateTime.class))
                        : instantOf(rs.getObject(index, OffsetDateTime.class));
                case BOOLEAN -> {
                    boolean flag = rs.getBoolean(index);
                    yield rs.wasNull() ? null : Boolean.valueOf(flag);
                }
            };
            values.put(column.name(), value);
        }
        return new LegacyRow(values);
    }

    private static Instant instantOf(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    /** The value of a column; naming a column the row does not have is a programming error. */
    public Object get(String column) {
        if (!values.containsKey(column)) {
            throw new IllegalArgumentException("the row has no column " + column + ": " + values.keySet());
        }
        return values.get(column);
    }

    public String text(String column) {
        return (String) get(column);
    }

    public Long number(String column) {
        return (Long) get(column);
    }

    public Instant instant(String column) {
        return (Instant) get(column);
    }

    /** True when the (boolean) column is true; null counts as false. */
    public boolean flag(String column) {
        return Boolean.TRUE.equals(get(column));
    }
}
