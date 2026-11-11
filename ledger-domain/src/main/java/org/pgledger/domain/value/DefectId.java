package org.pgledger.domain.value;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;

/** {@code <PROJECT_CODE>-D-<number>}: the number is zero padded to 4 digits and grows past 9999. */
public record DefectId(String value) {

    private static final Pattern FORMAT = Pattern.compile("([A-Z][A-Z0-9_]{0,31})-D-([0-9]{4,})");

    public DefectId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw malformed();
        }
    }

    /** The id of a text, or none when the text is not a defect id; nothing is thrown. */
    public static Optional<DefectId> parse(String value) {
        return value != null && FORMAT.matcher(value).matches() ? Optional.of(new DefectId(value)) : Optional.empty();
    }

    /** The failure of a text that is not a defect id (the constructor throws it; a gate may carry it as a value). */
    public static ValidationException malformed() {
        return ValidationException.invalid("defect_id", "must match <PROJECT_CODE>-D-<number of 4 or more digits>");
    }

    public static DefectId of(String value) {
        return new DefectId(value);
    }

    public static DefectId of(ProjectCode code, long number) {
        return new DefectId(code.value() + "-D-" + zeroPadded(number));
    }

    public ProjectCode projectCode() {
        return new ProjectCode(matcher().group(1));
    }

    public long number() {
        return Long.parseLong(matcher().group(2));
    }

    private Matcher matcher() {
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) {
            throw new IllegalStateException("validated in the constructor");
        }
        return m;
    }

    static String zeroPadded(long number) {
        if (number < 1) {
            throw ValidationException.invalid("number", "must be positive");
        }
        String digits = Long.toString(number);
        return "0".repeat(Math.max(0, 4 - digits.length())) + digits;
    }
}
