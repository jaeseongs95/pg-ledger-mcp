package org.pgledger.admin.security;

import org.pgledger.domain.error.AdminCode;
import org.springframework.security.core.AuthenticationException;

/** An authentication failure that has a code of the external contract (the failure handlers turn it into a problem). */
public final class AdminAuthenticationException extends AuthenticationException {

    private static final long serialVersionUID = 1L;

    private final transient AdminCode code;

    public AdminAuthenticationException(AdminCode code) {
        super(code.token());
        this.code = code;
    }

    public AdminCode code() {
        return code;
    }
}
