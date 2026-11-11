package org.pgledger.application.legacy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.pgledger.database.repository.LegacyColumn;
import org.pgledger.database.repository.LegacyRow;
import org.pgledger.domain.value.Sha256;

/**
 * The canonical serialisation of a row that both sides of the comparison use, so equal rows give equal text and any
 * difference in any column gives different text. Every value is self-delimiting: {@code n;} for null, {@code s<length>:<text>;}
 * for text (the length is in UTF-16 code units, so no text can imitate a delimiter), {@code l<number>;} for a number and
 * {@code t<UTC time with six fractional digits and Z>;} for an instant (cut to microseconds, the precision of the columns).
 * The columns are written in the column order of the new table. The row digest is the SHA-256 of that text
 * ({@link Sha256#ofText}, the one digest helper of the domain).
 */
final class CanonicalRow {

    private static final DateTimeFormatter INSTANT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private CanonicalRow() {
    }

    /** The serialisation of the given columns of the row. */
    static String of(List<LegacyColumn> columns, LegacyRow row) {
        StringBuilder out = new StringBuilder();
        for (LegacyColumn column : columns) {
            append(out, row.get(column.name()));
        }
        return out.toString();
    }

    /** The digest of the serialisation of the given columns of the row. */
    static String digest(List<LegacyColumn> columns, LegacyRow row) {
        return Sha256.ofText(of(columns, row)).hex();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("n;");
            case String text -> out.append('s').append(text.length()).append(':').append(text).append(';');
            case Long number -> out.append('l').append(number).append(';');
            case Instant instant -> out.append('t').append(INSTANT.format(instant.truncatedTo(ChronoUnit.MICROS)))
                    .append(';');
            default -> throw new IllegalArgumentException("not a value of a legacy row: " + value.getClass().getName());
        }
    }
}
