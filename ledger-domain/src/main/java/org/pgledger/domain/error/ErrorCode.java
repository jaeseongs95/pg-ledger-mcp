package org.pgledger.domain.error;

/**
 * An error token of the external contract (MCP tool error code, admin ProblemDetail code). Every code enum
 * belongs to exactly one {@link ErrorCategory}; the constant name is the token.
 */
public sealed interface ErrorCode
        permits TrustCode, ValidationCode, TransitionCode, ConflictCode, LookupCode, AdminCode, InfrastructureCode {

    /** The constant name, which is the contract token. */
    String name();

    ErrorCategory category();

    default String token() {
        return name();
    }

    /** Message bundle key of this code. */
    default String messageKey() {
        return "error." + name();
    }
}
