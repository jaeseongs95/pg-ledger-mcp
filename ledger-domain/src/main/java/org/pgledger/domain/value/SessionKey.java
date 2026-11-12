package org.pgledger.domain.value;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What "same session" compares. Ids imported from older ledgers may carry an {@code agent:} prefix, which is
 * dropped; the vendor stays part of the key.
 */
public record SessionKey(String value) {

    private static final String LEGACY_PREFIX = "agent:";

    /** The vendor of a key: the characters before the first colon, when they are letters, digits, {@code _} or {@code -}. */
    private static final Pattern VENDOR = Pattern.compile("([A-Za-z0-9_-]+):.*");

    public SessionKey {
        if (value.startsWith(LEGACY_PREFIX)) {
            value = value.substring(LEGACY_PREFIX.length());
        }
    }

    /** The vendor of the session, or none when the key (of an imported actor) does not start with one. */
    public Optional<String> vendor() {
        Matcher matcher = VENDOR.matcher(value);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
