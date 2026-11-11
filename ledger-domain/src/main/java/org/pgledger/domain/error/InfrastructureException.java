package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#INFRASTRUCTURE}: driver, internal and integrity failures. */
public final class InfrastructureException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public InfrastructureException(InfrastructureCode code, List<String> args) {
        this(code, args, Map.of(), null);
    }

    public InfrastructureException(InfrastructureCode code, List<String> args, Throwable cause) {
        this(code, args, Map.of(), cause);
    }

    private InfrastructureException(InfrastructureCode code, List<String> args, Map<String, List<String>> details,
            Throwable cause) {
        super(code, args, details, cause);
    }

    @Override
    protected LedgerException copyWith(Map<String, List<String>> newDetails) {
        return new InfrastructureException((InfrastructureCode) code(), args(), newDetails, getCause());
    }
}
