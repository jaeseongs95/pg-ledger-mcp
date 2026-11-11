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
    /** The attestation has the prefix of the retired token format (version 1, which did not cover the arguments). */
    ATTESTATION_VERSION_UNSUPPORTED,
    /**
     * The attestation cannot be verified: the signature does not match the claims under the live key of the author,
     * or the author has no live key on this server. One code for both, with no author in it: until a signature is
     * proven, a caller must not learn which authors have a key.
     */
    ATTESTATION_SIGNATURE_INVALID,
    /** The token was signed for another tool, request id or arguments than the call it came with. */
    ATTESTATION_BINDING_MISMATCH,
    /** The issue time of the token is further from the server clock than the accepted window. */
    ATTESTATION_TIME_WINDOW;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.TRUST;
    }
}
