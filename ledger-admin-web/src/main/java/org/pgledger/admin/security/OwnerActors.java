package org.pgledger.admin.security;

import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The identity that an owner operation is recorded under: who is signed in. The admin token (login form, bearer) is the
 * owner as {@code token:owner}; an OpenID Connect sign-in is {@code oidc:<issuer>#<subject>} (a subject is only unique
 * inside its issuer), so the audit trail tells the people on the allow list apart. Never the fixed role name.
 */
public final class OwnerActors {

    private OwnerActors() {
    }

    /** The identity of the signed-in owner, cut to the longest identity the audit trail holds. */
    public static OwnerIdentity of(Authentication authentication) {
        String identity = switch (authentication.getPrincipal()) {
            case OidcUser oidc -> "oidc:" + oidc.getIssuer() + "#" + oidc.getSubject();
            default -> "token:" + authentication.getName();
        };
        return OwnerIdentity.of(identity.substring(0, Math.min(identity.length(), OwnerIdentity.MAX_LENGTH)));
    }
}
