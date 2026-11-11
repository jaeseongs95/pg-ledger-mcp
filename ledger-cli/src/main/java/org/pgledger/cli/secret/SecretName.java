package org.pgledger.cli.secret;

import java.util.regex.Pattern;

import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/** A validated secret name; it becomes a file name, so path separators and a leading dot are impossible. */
public record SecretName(String value) {

    private static final Pattern ALLOWED = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");

    public SecretName {
        // The refusal carries no argument: the rejected text is never echoed.
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new FailureException(ErrorCode.INVALID_SECRET_NAME);
        }
    }
}
