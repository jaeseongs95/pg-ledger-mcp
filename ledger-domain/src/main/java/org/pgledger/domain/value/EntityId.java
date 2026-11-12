package org.pgledger.domain.value;

import java.util.Locale;
import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * An id of a task or condition: the domain of 1.x, 1 to {@value #MAX_LENGTH} characters with no whitespace (any Unicode
 * separator) and no control or format character, in any case. The ids of 1.x are kept as they are, so this is all the rule
 * there is; the id of a defect has its own format ({@link DefectId}). This record is the plain form; {@link TaskId} and
 * {@link ConditionId} add their own rules on top of {@link #PATTERN}.
 *
 * <p>Case does not depend on the DBMS collation: ids are compared exactly in Java ({@code equals}), an id that equals an
 * existing one when the case is ignored is refused when it is created, and the database keeps the upper-case {@link #key}
 * of every id under a UNIQUE constraint (so two creates that differ only in case collide on every DBMS).
 */
public record EntityId(String value) {

    /** The longest id, in characters (code points). */
    public static final int MAX_LENGTH = 120;

    /** No separator (spaces, no-break space, line and paragraph separators) and no control or format character. */
    public static final Pattern PATTERN = Pattern.compile("[^\\p{Z}\\p{C}]{1," + MAX_LENGTH + "}");

    /**
     * The key that makes the id unique whatever the collation of the database is: the id in upper case ({@link Locale#ROOT},
     * so the language of the machine does not matter). A case mapping can lengthen a text (the German sharp s is two letters), at
     * most three times, so the column of the key is three times as long as the id.
     */
    public static final int MAX_KEY_LENGTH = 3 * MAX_LENGTH;

    public EntityId {
        requireValid("id", value);
    }

    public static EntityId of(String value) {
        return new EntityId(value);
    }

    /** The upper-case key of an id (the id is not checked here). */
    public static String key(String id) {
        return id.toUpperCase(Locale.ROOT);
    }

    static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    static ValidationException malformed(String path) {
        return ValidationException.invalid(path, ValidationReason.MUST_BE_ID, Integer.toString(MAX_LENGTH));
    }

    static void requireValid(String path, String value) {
        if (!isValid(value)) {
            throw malformed(path);
        }
    }
}
