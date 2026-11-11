package org.pgledger.domain.error;

/** Error tokens of category ADMIN. */
public enum AdminCode implements ErrorCode {
    AGENT_TOKEN_FORBIDDEN,
    UNAUTHORIZED,
    DNS_REBINDING_BLOCKED,
    TOO_MANY_AUTH_FAILURES,
    COMMON_CODE_DELETE_FORBIDDEN,
    PAYLOAD_TOO_LARGE,
    CSRF_REJECTED;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.ADMIN;
    }
}
