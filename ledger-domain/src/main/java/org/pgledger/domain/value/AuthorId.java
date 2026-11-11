package org.pgledger.domain.value;

import java.util.List;
import java.util.regex.Pattern;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;

/**
 * A declared author: {@code <vendor>:<session-id>} (vendor lower case, 1..32 characters; session 1..128 of
 * {@code [A-Za-z0-9._-]}) or the owner {@code user:owner}. {@code null} means the host attached no session at all.
 */
public record AuthorId(String value) implements ActorId {

    public static final AuthorId OWNER = new AuthorId("user:owner");

    private static final Pattern SESSION_FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._-]{1,128}");

    public AuthorId {
        if (value == null) {
            throw new TrustException(TrustCode.HOST_ATTESTATION_REQUIRED, List.of());
        }
        if (!matches(value)) {
            throw new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of(value));
        }
    }

    public static AuthorId of(String value) {
        return new AuthorId(value);
    }

    /** True for {@code user:owner} and for session authors; {@code user:} is reserved for the owner. */
    public static boolean matches(String value) {
        return value != null && (value.equals("user:owner")
                || (SESSION_FORMAT.matcher(value).matches() && !value.startsWith("user:")));
    }
}
