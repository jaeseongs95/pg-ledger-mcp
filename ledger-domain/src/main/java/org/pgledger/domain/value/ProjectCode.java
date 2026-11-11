package org.pgledger.domain.value;

import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/** Short upper-case project code that forms the prefix of defect and task ids. */
public record ProjectCode(String value) {

    /** The format the constructor enforces (the legacy import checks it without throwing). */
    public static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    public ProjectCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw ValidationException.invalid("project_code", ValidationReason.MUST_MATCH, FORMAT.pattern());
        }
    }

    public static ProjectCode of(String value) {
        return new ProjectCode(value);
    }
}
