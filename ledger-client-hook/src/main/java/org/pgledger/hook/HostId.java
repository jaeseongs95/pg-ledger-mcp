package org.pgledger.hook;

import java.util.Optional;

/** A host session or conversation id: 1 to 128 characters of {@code [A-Za-z0-9._-]}, the session part format of the ledger. */
record HostId(String value) {

    private static final int MAX_LENGTH = 128;

    /** The id if {@code text} is of the allowed form; null (absent or not a JSON string) and other text give empty. */
    static Optional<HostId> of(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            if (!allowed(text.charAt(i))) {
                return Optional.empty();
            }
        }
        return Optional.of(new HostId(text));
    }

    private static boolean allowed(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
    }
}
