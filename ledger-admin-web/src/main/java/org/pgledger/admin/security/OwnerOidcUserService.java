package org.pgledger.admin.security;

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

/**
 * Who may sign in with OpenID Connect. Spring Security has already validated the ID token (issuer, audience, nonce,
 * expiry, signature) and the {@code state} when this is called; this service only decides who the owner is: the
 * e-mail of the ID token must be verified by the provider and be on the configured list. Anybody else is refused, so no
 * authenticated session exists. The provider is configuration ({@code spring.security.oauth2.client.*}); no provider is
 * named here.
 */
@Component
public final class OwnerOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final Set<String> owners;

    public OwnerOidcUserService(LedgerAdminProperties properties) {
        this.owners = Set.copyOf(properties.oidc().allowedEmails().stream()
                .map(email -> email.toLowerCase(Locale.ROOT)).toList());
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcIdToken token = request.getIdToken();
        String email = token.getEmail();
        if (!Boolean.TRUE.equals(token.getEmailVerified()) || email == null
                || !owners.contains(email.toLowerCase(Locale.ROOT))) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED));
        }
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_OWNER")), token);
    }
}
