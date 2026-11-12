package org.pgledger.hook;

/** The vendor part of the author that the attestation is signed for: {@code <vendor>:<host id>} (see {@link Author}). */
public enum Vendor {
    CLAUDE("claude"),
    CODEX("codex"),
    GEMINI("gemini");

    private final String prefix;

    Vendor(String prefix) {
        this.prefix = prefix;
    }

    /** The text before the colon of the author. */
    String prefix() {
        return prefix;
    }
}
