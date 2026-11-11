package org.pgledger.domain.value;

/**
 * What "same session" compares. Ids imported from older ledgers may carry an {@code agent:} prefix, which is
 * dropped; the vendor stays part of the key.
 */
public record SessionKey(String value) {

    private static final String LEGACY_PREFIX = "agent:";

    public SessionKey {
        if (value.startsWith(LEGACY_PREFIX)) {
            value = value.substring(LEGACY_PREFIX.length());
        }
    }
}
