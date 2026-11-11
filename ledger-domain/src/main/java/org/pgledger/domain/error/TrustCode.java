package org.pgledger.domain.error;

/** Error tokens of category TRUST. */
public enum TrustCode implements ErrorCode {
    HOST_ATTESTATION_REQUIRED,
    AUTHOR_ID_FORMAT,
    SESSION_NOT_GRANTED,
    VERIFY_NEEDS_REVIEWER_LOGIN;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.TRUST;
    }
}
