package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#LOOKUP}. */
public final class LookupException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public LookupException(LookupCode code, List<String> args) {
        super(code, args, Map.of(), null);
    }
}
