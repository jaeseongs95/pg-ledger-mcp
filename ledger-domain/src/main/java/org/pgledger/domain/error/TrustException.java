package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#TRUST}. */
public final class TrustException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public TrustException(TrustCode code, List<String> args) {
        this(code, args, Map.of());
    }

    public TrustException(TrustCode code, List<String> args, Map<String, List<String>> details) {
        super(code, args, details, null);
    }

    /** With the failure that made the check fail (kept for the log; it never reaches the caller of a tool). */
    public TrustException(TrustCode code, List<String> args, Map<String, List<String>> details, Throwable cause) {
        super(code, args, details, cause);
    }

    @Override
    protected LedgerException copyWith(Map<String, List<String>> newDetails) {
        return new TrustException((TrustCode) code(), args(), newDetails);
    }
}
