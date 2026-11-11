package org.pgledger.domain.value;

/** Evidence that comes with a transition: a uri (a blank one equals none) and an optional digest of the content. */
public record Evidence(String uri, Sha256 sha256) {

    public static final Evidence NONE = new Evidence(null, null);

    public Evidence {
        uri = TextRule.EVIDENCE_URI.optional(uri);
    }

    public static Evidence of(String uri, String sha256) {
        return new Evidence(uri, sha256 == null || sha256.isBlank() ? null : Sha256.of(sha256));
    }

    public boolean provided() {
        return uri != null;
    }
}
