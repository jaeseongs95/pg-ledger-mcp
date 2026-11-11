package org.pgledger.hook;

/** The vendor part of the author that the attestation is signed for: {@code <vendor>:<host id>}. */
public enum Vendor {
    CLAUDE("claude"),
    CODEX("codex"),
    GEMINI("gemini");

    private final String prefix;

    Vendor(String prefix) {
        this.prefix = prefix;
    }

    /** The author {@code <vendor>:<host id>} for a validated host id. */
    String attest(HostId id) {
        return prefix + ":" + id.value();
    }
}
