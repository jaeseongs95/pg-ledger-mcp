package org.pgledger.domain.value;

import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;

/** A git commit id: 40 lower-case hex characters. */
public record CommitSha(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{40}");

    public CommitSha {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw ValidationException.invalid("commit_sha", "must be 40 lower-case hex characters");
        }
    }

    public static CommitSha of(String value) {
        return new CommitSha(value);
    }
}
