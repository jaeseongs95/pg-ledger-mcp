package org.pgledger.domain.error;

/** Error tokens of category CONFLICT. */
public enum ConflictCode implements ErrorCode {
    VERSION_CONFLICT,
    REQUEST_CONFLICT,
    REQUEST_ID_LEGACY,
    DUPLICATE_KEY,
    KEY_REPLACEMENT_NOT_CONFIRMED,
    /** A grant for an author that has no live session key: no write of this author could be accepted. Argument: the author. */
    SESSION_KEY_REQUIRED;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.CONFLICT;
    }
}
