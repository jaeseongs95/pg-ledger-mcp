package org.pgledger.domain.value;

import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;

/** Primary key of a project (the {@code project_id} of the tool contract); not the same as its code. */
public record ProjectId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public ProjectId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw ValidationException.invalid("project_id", "must match [A-Za-z0-9_-]{1,64}");
        }
    }

    public static ProjectId of(String value) {
        return new ProjectId(value);
    }
}
