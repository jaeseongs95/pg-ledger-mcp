package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/**
 * Root of every business failure. The message text is not carried here: it is resolved from the message bundle
 * through {@code code().messageKey()} and {@link #args()}; {@link #getMessage()} is a technical summary for logs.
 */
public abstract sealed class LedgerException extends RuntimeException
        permits TrustException, ValidationException, TransitionException, ConflictException, LookupException,
        AdminException, InfrastructureException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorCode code;
    private final transient List<String> args;
    private final transient Map<String, List<String>> details;

    protected LedgerException(ErrorCode code, List<String> args, Map<String, List<String>> details, Throwable cause) {
        super(code.token() + " " + args, cause);
        this.code = code;
        this.args = List.copyOf(args);
        this.details = Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public ErrorCategory category() {
        return code.category();
    }

    /** Positional arguments of the message pattern. */
    public List<String> args() {
        return args;
    }

    /** Machine readable context (for example valid codes); values are always lists. */
    public Map<String, List<String>> details() {
        return details;
    }
}
