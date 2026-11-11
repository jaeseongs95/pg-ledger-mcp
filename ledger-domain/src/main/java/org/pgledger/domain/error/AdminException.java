package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#ADMIN}: access to the admin interface. */
public final class AdminException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public AdminException(AdminCode code, List<String> args) {
        super(code, args, Map.of(), null);
    }
}
