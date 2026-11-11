package org.pgledger.domain.error;

/** Error tokens of category LOOKUP. */
public enum LookupCode implements ErrorCode {
    NOT_FOUND;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.LOOKUP;
    }
}
