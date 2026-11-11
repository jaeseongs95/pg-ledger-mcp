package org.pgledger.admin.security;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Who may sign in with OpenID Connect. Spring Security has already validated the ID token (issuer, audience, nonce,
 * expiry, signature) and the {@code state} when this is called; this service only decides who the owner is: the person
 * must be on the configured list ({@code ledger.admin.oidc.owners}) <em>for the issuer of the ID token</em>, by the
 * subject, or by the e-mail address when the provider verified it. A subject or an address is only meaningful inside one
 * issuer (another provider can hand out the same address or subject to somebody else), so an entry counts for its own
 * issuer and no other. Anybody else is refused, so no authenticated session exists. The provider is configuration
 * ({@code spring.security.oauth2.client.*}); no provider is named here. E-mail addresses are compared in lower case
 * with {@link Locale#ROOT}, so the default locale of the JVM cannot change the comparison.
 */
@Component
public final class OwnerOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private enum Kind {
        SUBJECT, EMAIL
    }

    /** One entry of the allow list: the issuer it counts for, what kind of identifier it is, and the identifier. */
    private record Allowed(String issuer, Kind kind, String value) {
    }

    private final Set<Allowed> owners = new HashSet<>();

    public OwnerOidcUserService(LedgerAdminProperties properties) {
        for (LedgerAdminProperties.Oidc.Owner owner : properties.oidc().owners()) {
            if (StringUtils.hasText(owner.subject())) {
                owners.add(new Allowed(owner.issuer().strip(), Kind.SUBJECT, owner.subject().strip()));
            }
            if (StringUtils.hasText(owner.email())) {
                owners.add(new Allowed(owner.issuer().strip(), Kind.EMAIL,
                        owner.email().strip().toLowerCase(Locale.ROOT)));
            }
        }
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcIdToken token = request.getIdToken();
        String issuer = String.valueOf(token.getIssuer());
        boolean bySubject = owners.contains(new Allowed(issuer, Kind.SUBJECT, token.getSubject()));
        String email = token.getEmail();
        boolean byEmail = Boolean.TRUE.equals(token.getEmailVerified()) && email != null
                && owners.contains(new Allowed(issuer, Kind.EMAIL, email.toLowerCase(Locale.ROOT)));
        if (!bySubject && !byEmail) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED));
        }
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_OWNER")), token);
    }
}
