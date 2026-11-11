package org.pgledger.domain.value;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * A point in time as the contract sees it: input must carry {@code Z} or an offset (a time without a zone is
 * ambiguous and rejected) and becomes a UTC {@link Instant}; output always ends in {@code Z}. Nothing here
 * converts between zones for display: that is the client's job.
 */
public record UtcInstant(Instant value) {

    public UtcInstant {
        if (value == null) {
            throw ValidationException.invalid("instant", ValidationReason.REQUIRED);
        }
    }

    public static UtcInstant parse(String path, String text) {
        if (text == null) {
            throw ValidationException.invalid(path, ValidationReason.REQUIRED);
        }
        try {
            return new UtcInstant(OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            throw ValidationException.invalid(path, ValidationReason.MUST_BE_ISO_8601_INSTANT, e);
        }
    }

    /** ISO-8601 in UTC, ending in {@code Z}. */
    @Override
    public String toString() {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }
}
