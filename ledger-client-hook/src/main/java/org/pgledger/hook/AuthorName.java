package org.pgledger.hook;

import java.util.regex.Pattern;

/**
 * An author as the ledger writes it: {@code <vendor>:<session id>} with a vendor of {@code [a-z][a-z0-9-]{0,31}} and a
 * session id of {@code [A-Za-z0-9._-]{1,128}}. The vendor {@code user} is reserved for the owner: only the literal
 * {@code user:owner} is accepted with it.
 */
final class AuthorName {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._-]{1,128}");
    private static final String OWNER = "user:owner";
    private static final String USER_PREFIX = "user:";

    private AuthorName() {
    }

    /** Whether the text is an author of the ledger (the whole text, no trimming). */
    static boolean isValid(String text) {
        if (text == null) {
            return false;
        }
        if (text.startsWith(USER_PREFIX)) {
            return OWNER.equals(text);
        }
        return FORMAT.matcher(text).matches();
    }
}
