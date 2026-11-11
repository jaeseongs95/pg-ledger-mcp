package org.pgledger.domain.error;

/** Error tokens of category CONFLICT. */
public enum ConflictCode implements ErrorCode {
    VERSION_CONFLICT,
    REQUEST_CONFLICT,
    REQUEST_ID_LEGACY,
    DUPLICATE_KEY;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.CONFLICT;
    }
}
