package org.pgledger.hook;

/** The vendor part of {@code _hostAttestation}: {@code <vendor>:<host id>}. */
public enum Vendor {
    CLAUDE("claude"),
    CODEX("codex"),
    GEMINI("gemini");

    private final String prefix;

    Vendor(String prefix) {
        this.prefix = prefix;
    }

    /** The attestation value for a validated host id. */
    String attest(HostId id) {
        return prefix + ":" + id.value();
    }
}
