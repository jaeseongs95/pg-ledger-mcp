package org.pgledger.application.history;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.pgledger.domain.value.UtcInstant;

/** The {@code changes} of a history row: field name to [before, after], in the order the fields were added. */
public record ChangeSet(@JsonValue Map<String, Change> entries) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ChangeSet {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public static ChangeSet empty() {
        return new ChangeSet(Map.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Adds a field whatever its values are. */
    public ChangeSet with(String field, String before, String after) {
        Map<String, Change> copy = new LinkedHashMap<>(entries);
        copy.put(field, new Change(before, after));
        return new ChangeSet(copy);
    }

    /** Adds a field only when the two values differ. */
    public ChangeSet withIfChanged(String field, String before, String after) {
        return Objects.equals(before, after) ? this : with(field, before, after);
    }

    public static String text(Instant instant) {
        return instant == null ? null : new UtcInstant(instant).toString();
    }

    public static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
