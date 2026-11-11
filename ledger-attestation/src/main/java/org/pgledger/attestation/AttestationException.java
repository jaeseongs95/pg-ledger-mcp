package org.pgledger.attestation;

/**
 * An attestation or a public key that cannot be used. The reason says which check failed; the message never carries
 * a token, a signature or any key material.
 */
public final class AttestationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which check failed. */
    public enum Reason {
        /** The text is not a token of this format (wrong shape, bad encoding, wrong sizes). */
        MALFORMED,
        /** The text has the prefix of a retired token format (version 1, which did not cover the arguments). */
        VERSION_UNSUPPORTED,
        /** The signature does not belong to the claims and the key. */
        SIGNATURE_INVALID,
        /** The text is not an Ed25519 public key in X.509 form. */
        KEY_INVALID
    }

    private final Reason reason;

    public AttestationException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public AttestationException(Reason reason) {
        this(reason, null);
    }

    public Reason reason() {
        return reason;
    }
}
