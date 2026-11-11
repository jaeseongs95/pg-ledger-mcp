package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#ADMIN}: access to the admin interface. */
public final class AdminException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public AdminException(AdminCode code, List<String> args) {
        this(code, args, Map.of());
    }

    private AdminException(AdminCode code, List<String> args, Map<String, List<String>> details) {
        super(code, args, details, null);
    }

    @Override
    protected LedgerException copyWith(Map<String, List<String>> newDetails) {
        return new AdminException((AdminCode) code(), args(), newDetails);
    }
}
