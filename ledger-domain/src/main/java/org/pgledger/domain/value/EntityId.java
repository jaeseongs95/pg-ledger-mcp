package org.pgledger.domain.value;

import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

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

    static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    static ValidationException malformed(String path) {
        return ValidationException.invalid(path, ValidationReason.MUST_BE_UPPER_CASE_ID_MATCHING, PATTERN.pattern());
    }

    static void requireValid(String path, String value) {
        if (!isValid(value)) {
            throw malformed(path);
        }
    }
}
