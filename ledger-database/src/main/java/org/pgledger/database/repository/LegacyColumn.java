package org.pgledger.database.repository;

import java.sql.Types;

/**
 * One column of a table of the legacy import, by its name and by what the import reads from or binds to it. The kinds are
 * the four JDBC values the import needs; everything else (a JSON column, a UUID, a name type) is read as text, which is how
 * the legacy values that are not plain text arrive (JDBC {@code getString}).
 */
public record LegacyColumn(String name, Kind kind) {

    /** The Java value a column holds in a {@link LegacyRow}. */
    public enum Kind {
        /** {@link String}. */
        TEXT(Types.VARCHAR),
        /** {@link Long}. */
        LONG(Types.BIGINT),
        /** {@link java.time.Instant}, read from a time zone aware column and stored as UTC. */
        INSTANT(Types.TIMESTAMP),
        /** {@link Boolean}; only the legacy side has boolean columns, the new schema has none. */
        BOOLEAN(Types.BOOLEAN);

        private final int sqlType;

        Kind(int sqlType) {
            this.sqlType = sqlType;
        }

        /** The {@link Types} constant that a null of this kind is bound as. */
        public int sqlType() {
            return sqlType;
        }
    }

    public static LegacyColumn text(String name) {
        return new LegacyColumn(name, Kind.TEXT);
    }

    public static LegacyColumn number(String name) {
        return new LegacyColumn(name, Kind.LONG);
    }

    public static LegacyColumn instant(String name) {
        return new LegacyColumn(name, Kind.INSTANT);
    }

    public static LegacyColumn flag(String name) {
        return new LegacyColumn(name, Kind.BOOLEAN);
    }
}
