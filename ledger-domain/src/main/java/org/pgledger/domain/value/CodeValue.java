package org.pgledger.domain.value;

import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/** A common-code value (category, severity, relation kind): a letter then letters, digits or {@code _}, up to 32. */
public record CodeValue(String value) {

    /** The format the constructor enforces (the legacy import checks it without throwing). */
    public static final Pattern FORMAT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");

    public CodeValue {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw ValidationException.invalid("code", ValidationReason.MUST_MATCH, FORMAT.pattern());
        }
    }

    public static CodeValue of(String value) {
        return new CodeValue(value);
    }
}
