package org.pgledger.hook;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A host session or conversation id: 1 to 128 characters of {@code [A-Za-z0-9._-]}, the session part of the author
 * format of the ledger (see {@link Author}). The compact constructor refuses anything else; {@link HostIdentity#of}
 * is the way in for text that is not known to be an id.
 */
record HostId(String value) implements HostIdentity {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    HostId {
        Objects.requireNonNull(value, "value");
        if (!accepts(value)) {
            throw new IllegalArgumentException("not a host session id");
        }
    }

    /** Whether the text is of the allowed form; null (absent or not a JSON string) is not. */
    static boolean accepts(String text) {
        return text != null && FORMAT.matcher(text).matches();
    }
}
