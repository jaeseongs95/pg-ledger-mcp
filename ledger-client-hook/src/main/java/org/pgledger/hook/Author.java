package org.pgledger.hook;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An author as the ledger writes it: {@code <vendor>:<session id>} with a vendor of {@code [a-z][a-z0-9-]{0,31}} and a
 * session id of {@code [A-Za-z0-9._-]{1,128}} ({@link HostId}). The vendor {@code user} is reserved for the owner: only
 * the literal {@code user:owner} is accepted with it.
 *
 * <p>The server defines this format too ({@code AuthorId} of the domain module): the hook is a separate program that
 * depends on the attestation module only, so the definition exists twice. Both are tested against the same vectors
 * ({@code contract/author-vectors.json} of the test fixture of {@code ledger-attestation}).
 *
 * @param vendor  the text before the colon: a vendor of the format, or {@code user}
 * @param session the text after the colon
 */
record Author(String vendor, HostId session) {

    private static final Pattern VENDOR_FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final String OWNER_VENDOR = "user";
    private static final String OWNER_SESSION = "owner";
    private static final char SEPARATOR = ':';

    Author {
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(session, "session");
        if (!isAuthor(vendor, session)) {
            throw new IllegalArgumentException("not an author of the ledger");
        }
    }

    /** The author of a host: the vendor of the client and the host id of the call. */
    static Author of(Vendor vendor, HostId session) {
        return new Author(vendor.prefix(), session);
    }

    /** The author that the whole text spells (no trimming), or empty if it is not one. */
    static Optional<Author> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int separator = text.indexOf(SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        String vendor = text.substring(0, separator);
        if (HostIdentity.of(text.substring(separator + 1)) instanceof HostId session && isAuthor(vendor, session)) {
            return Optional.of(new Author(vendor, session));
        }
        return Optional.empty();
    }

    /** {@code <vendor>:<session id>}: what the attestation and the key file carry. */
    String value() {
        return vendor + SEPARATOR + session.value();
    }

    private static boolean isAuthor(String vendor, HostId session) {
        if (OWNER_VENDOR.equals(vendor)) {
            return OWNER_SESSION.equals(session.value());
        }
        return VENDOR_FORMAT.matcher(vendor).matches();
    }
}
