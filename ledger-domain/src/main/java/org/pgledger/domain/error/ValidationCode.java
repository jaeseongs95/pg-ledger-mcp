package org.pgledger.domain.error;

/** Error tokens of category VALIDATION. */
public enum ValidationCode implements ErrorCode {
    VALIDATION_FAILED,
    REASON_INVALID,
    CODE_REQUIRED,
    CODE_INVALID,
    ID_PREFIX_MISMATCH;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.VALIDATION;
    }
}
