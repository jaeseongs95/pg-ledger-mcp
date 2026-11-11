package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#CONFLICT}: versions, request ids, duplicate keys. */
public final class ConflictException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public ConflictException(ConflictCode code, List<String> args) {
        this(code, args, Map.of(), null);
    }

    public ConflictException(ConflictCode code, List<String> args, Throwable cause) {
        this(code, args, Map.of(), cause);
    }

    private ConflictException(ConflictCode code, List<String> args, Map<String, List<String>> details,
            Throwable cause) {
        super(code, args, details, cause);
    }

    @Override
    protected LedgerException copyWith(Map<String, List<String>> newDetails) {
        return new ConflictException((ConflictCode) code(), args(), newDetails, getCause());
    }
}
