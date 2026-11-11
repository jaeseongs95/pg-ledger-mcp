package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#INFRASTRUCTURE}: driver, internal and integrity failures. */
public final class InfrastructureException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public InfrastructureException(InfrastructureCode code, List<String> args) {
        super(code, args, Map.of(), null);
    }

    public InfrastructureException(InfrastructureCode code, List<String> args, Throwable cause) {
        super(code, args, Map.of(), cause);
    }
}
