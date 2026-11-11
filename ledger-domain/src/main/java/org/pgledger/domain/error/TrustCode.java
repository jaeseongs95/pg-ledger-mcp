package org.pgledger.domain.error;

/** Error tokens of category TRUST. */
public enum TrustCode implements ErrorCode {
    HOST_ATTESTATION_REQUIRED,
    AUTHOR_ID_FORMAT,
    SESSION_NOT_GRANTED,
    VERIFY_NEEDS_REVIEWER_LOGIN,
    /** The attestation is a bare {@code <vendor>:<session-id>} (the format before signatures): nothing proves it. */
    ATTESTATION_UNSIGNED,
    /** The attestation has the prefix of a signed token but is not a canonical token. */
    ATTESTATION_MALFORMED,
    /** The author has no live registered session key (never registered, or closed without a replacement). */
    SESSION_KEY_NOT_REGISTERED,
    /** The signature does not match the claims under the live key of the author. */
    ATTESTATION_SIGNATURE_INVALID,
    /** The token was signed for another tool or another request id than the call it came with. */
    ATTESTATION_BINDING_MISMATCH,
    /** The issue time of the token is further from the server clock than the accepted window. */
    ATTESTATION_TIME_WINDOW;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.TRUST;
    }
}
