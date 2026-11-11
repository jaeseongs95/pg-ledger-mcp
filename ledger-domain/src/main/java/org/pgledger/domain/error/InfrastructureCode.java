package org.pgledger.domain.error;

/** Error tokens of category INFRASTRUCTURE. */
public enum InfrastructureCode implements ErrorCode {
    DRIVER_NOT_CONFIGURED,
    DRIVER_INVALID,
    INTERNAL_ERROR,
    INTEGRITY_VIOLATION;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.INFRASTRUCTURE;
    }
}
