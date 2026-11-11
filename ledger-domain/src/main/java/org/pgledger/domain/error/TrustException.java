package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#TRUST}. */
public final class TrustException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public TrustException(TrustCode code, List<String> args) {
        super(code, args, Map.of(), null);
    }

    public TrustException(TrustCode code, List<String> args, Map<String, List<String>> details) {
        super(code, args, details, null);
    }
}
