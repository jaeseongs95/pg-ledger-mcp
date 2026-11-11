package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#CONFLICT}: versions, request ids, duplicate keys. */
public final class ConflictException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public ConflictException(ConflictCode code, List<String> args) {
        super(code, args, Map.of(), null);
    }

    public ConflictException(ConflictCode code, List<String> args, Throwable cause) {
        super(code, args, Map.of(), cause);
    }
}
