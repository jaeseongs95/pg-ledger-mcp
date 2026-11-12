package org.pgledger.domain.value;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * {@code <PROJECT_CODE>-D-<number>}: the number is zero padded to 4 digits, grows past 9999 and has at most
 * {@value #MAX_DIGITS} digits, so every number fits a {@code long} ({@code Long.MAX_VALUE} has 19 digits).
 */
public record DefectId(String value) {

    /** The most digits of a number: 18 digits always fit a {@code long}. */
    static final int MAX_DIGITS = 18;

    /** The highest number an id can hold. */
    static final long MAX_NUMBER = 999_999_999_999_999_999L;

    /** The format the constructor enforces (the legacy import checks it without throwing). */
    public static final Pattern FORMAT = Pattern.compile("([A-Z][A-Z0-9_]{0,31})-D-([0-9]{4," + MAX_DIGITS + "})");

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
        return ValidationException.invalid("defect_id", ValidationReason.DEFECT_ID_FORMAT);
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

    /** The number of an id the server issues, zero padded to 4 digits; a number outside 1..{@link #MAX_NUMBER} is refused. */
    static String zeroPadded(long number) {
        if (number < 1) {
            throw ValidationException.invalid("number", ValidationReason.MUST_BE_POSITIVE);
        }
        if (number > MAX_NUMBER) {
            throw ValidationException.invalid("number", ValidationReason.OUT_OF_RANGE, "1",
                    Long.toString(MAX_NUMBER));
        }
        String digits = Long.toString(number);
        return "0".repeat(Math.max(0, 4 - digits.length())) + digits;
    }
}
