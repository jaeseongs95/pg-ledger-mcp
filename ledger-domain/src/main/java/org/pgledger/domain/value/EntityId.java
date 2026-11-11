package org.pgledger.domain.value;

import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;

/**
 * An id of a defect, task or condition. Ids are accepted only in upper case, so equality does not depend on a
 * database collation. This record is the plain form; {@link DefectId}, {@link TaskId} and {@link ConditionId}
 * add their own rules on top of {@link #PATTERN}.
 */
public record EntityId(String value) {

    /** Upper-case letters, digits, {@code _}, {@code -} and {@code |} (legacy ids contain a pipe), 1..120 characters. */
    public static final Pattern PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9_|-]{0,119}");

    public EntityId {
        requireValid("id", value);
    }

    public static EntityId of(String value) {
        return new EntityId(value);
    }

    static void requireValid(String path, String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw ValidationException.invalid(path, "must be an upper-case id matching " + PATTERN.pattern());
        }
    }
}
