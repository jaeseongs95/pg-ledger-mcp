package org.pgledger.domain.value;

import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;

/** Short upper-case project code that forms the prefix of defect and task ids. */
public record ProjectCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    public ProjectCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw ValidationException.invalid("project_code", "must match [A-Z][A-Z0-9_]{0,31}");
        }
    }

    public static ProjectCode of(String value) {
        return new ProjectCode(value);
    }
}
