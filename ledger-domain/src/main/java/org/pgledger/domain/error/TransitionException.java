package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#TRANSITION}: state rules, evidence, fixes, merges, no-ops. */
public final class TransitionException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public TransitionException(TransitionCode code, List<String> args) {
        this(code, args, Map.of());
    }

    public TransitionException(TransitionCode code, List<String> args, Map<String, List<String>> details) {
        super(code, args, details, null);
    }

    @Override
    protected LedgerException copyWith(Map<String, List<String>> newDetails) {
        return new TransitionException((TransitionCode) code(), args(), newDetails);
    }
}
