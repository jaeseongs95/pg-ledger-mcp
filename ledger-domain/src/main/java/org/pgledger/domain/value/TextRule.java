package org.pgledger.domain.value;

import java.util.Locale;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * Length and normalisation rule of a text field. Lengths count characters (code points), not bytes. An optional
 * field that is blank becomes {@code null}; a required field that is blank is rejected. A column never holds
 * an empty string. Fields marked trimmed are stored stripped.
 */
public enum TextRule {
    TITLE(300, false),
    BEHAVIOR(4000, false),
    ROOT_CAUSE(4000, false),
    PURPOSE(4000, false),
    SUCCESS_CONDITION(4000, false),
    ORIGIN_REF(500, false),
    ORIGIN_QUOTE(2000, false),
    RELEASE(100, false),
    GOAL_LABEL(200, false),
    /** 200: the longest code of the 1.x ledger is 79 characters (the column held 64 in earlier 2.0 builds). */
    CONDITION_CODE(200, false),
    CONDITION_TEXT(4000, false),
    OBSERVATION(4000, true),
    EVIDENCE(2000, true),
    USER_QUOTE(2000, false),
    GRANT_NOTE(500, false),
    REPO(300, false),
    BRANCH(300, false),
    /** 2000: the longest CI reference of the 1.x ledger is 533 characters (the column held 500 in earlier 2.0 builds). */
    CI_REF(2000, true),
    EVIDENCE_URI(2000, true),
    PROJECT_TITLE(200, true),
    CODE_NAME(200, true),
    CODE_DESCRIPTION(500, false);

    private final int max;
    private final boolean trimmed;

    TextRule(int max, boolean trimmed) {
        this.max = max;
        this.trimmed = trimmed;
    }

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    public int max() {
        return max;
    }

    /** The value, or a validation failure when it is blank or too long. */
    public String required(String raw) {
        String value = optional(raw);
        if (value == null) {
            throw ValidationException.invalid(path(), ValidationReason.REQUIRED);
        }
        return value;
    }

    /**
     * The value of a field that has to be given and may be empty (the NOT NULL columns of 1.x that allowed {@code ''}: purpose,
     * success condition, behavior, root cause, condition text): {@code null} is a validation failure (REQUIRED), a blank text is
     * the empty text {@code ""}, which the row stores as NULL (see {@link #emptyToNull}); too long is a validation failure.
     */
    public String givenOrEmpty(String raw) {
        if (raw == null) {
            throw ValidationException.invalid(path(), ValidationReason.REQUIRED);
        }
        return raw.isBlank() ? "" : optional(raw);
    }

    /**
     * The text a row stores: NULL for an empty text (no column holds {@code ''}: a DBMS of the Oracle kind cannot, and the
     * two would be different values otherwise). The tools show such a field as {@code ""} again, as 1.x did.
     */
    public static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    /** The value, or {@code null} when it is absent or blank; too long is a validation failure. */
    public String optional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = trimmed ? raw.strip() : raw;
        if (value.codePointCount(0, value.length()) > max) {
            throw ValidationException.invalid(path(), ValidationReason.MUST_BE_AT_MOST_CHARS, String.valueOf(max));
        }
        return value;
    }
}
