package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.security.OwnerActorResolver;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * ADM-PRN-05: the resolver of the owner identity that the controllers take as an argument. It is made from the identity
 * the owner signed in with, and it refuses one that the audit trail cannot hold instead of cutting it.
 */
class OwnerActorResolverTest {

    private static final OwnerActorResolver RESOLVER = new OwnerActorResolver();
    private static final Instant ISSUED = Instant.parse("2026-11-05T10:00:00Z");

    private static Authentication token() {
        return UsernamePasswordAuthenticationToken.authenticated("owner", null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    private static Authentication oidc(String issuer, String subject) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("not-a-secret").issuer(issuer).subject(subject)
                .issuedAt(ISSUED).expiresAt(ISSUED.plusSeconds(60)).build();
        return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_OWNER")),
                idToken), List.of(new SimpleGrantedAuthority("ROLE_OWNER")), "registration");
    }

    private static OwnerIdentity resolve(Authentication authentication) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(authentication);
        MethodParameter parameter = new MethodParameter(
                OwnerActorResolverTest.class.getDeclaredMethod("anyController", OwnerIdentity.class), 0);
        return RESOLVER.resolveArgument(parameter, null, new ServletWebRequest(request), null);
    }

    /** What a controller method with the argument looks like. */
    @SuppressWarnings("unused")
    private static void anyController(OwnerIdentity actor) {
    }

    @Test
    @DisplayName("ADM-PRN-05: the resolver serves the argument of the type OwnerIdentity and no other")
    void itServesTheOwnerIdentityOnly() throws Exception {
        MethodParameter owner = new MethodParameter(
                OwnerActorResolverTest.class.getDeclaredMethod("anyController", OwnerIdentity.class), 0);
        MethodParameter text = new MethodParameter(String.class.getDeclaredMethod("length"), -1);

        assertThat(RESOLVER.supportsParameter(owner)).isTrue();
        assertThat(RESOLVER.supportsParameter(text)).isFalse();
    }

    @Test
    @DisplayName("ADM-PRN-05: the token sign-in is token:owner, an OpenID Connect sign-in is oidc:<issuer>#<subject>")
    void theIdentityIsHowTheOwnerSignedIn() throws Exception {
        assertThat(resolve(token()).value()).isEqualTo("token:owner");
        assertThat(resolve(oidc("https://issuer.example", "subject-1")).value())
                .isEqualTo("oidc:https://issuer.example#subject-1");
    }

    @Test
    @DisplayName("ADM-PRN-05: an identity of 300 characters is accepted; a longer one is refused (UNAUTHORIZED), never "
            + "cut, so two that differ only after the 300th character never become one audit actor")
    void aTooLongIdentityIsRefusedNotCut() throws Exception {
        String prefix = "oidc:https://issuer.example#";
        String exact = "s".repeat(OwnerIdentity.MAX_LENGTH - prefix.length());

        assertThat(resolve(oidc("https://issuer.example", exact)).value()).hasSize(OwnerIdentity.MAX_LENGTH);
        for (String longer : List.of(exact + "a", exact + "b")) {
            assertThatThrownBy(() -> resolve(oidc("https://issuer.example", longer)))
                    .isInstanceOfSatisfying(AdminException.class,
                            failure -> assertThat(failure.code()).isEqualTo(AdminCode.UNAUTHORIZED));
        }
    }

    @Test
    @DisplayName("ADM-PRN-05: a request without a signed-in principal is UNAUTHORIZED")
    void noPrincipalIsUnauthorized() throws Exception {
        MethodParameter parameter = new MethodParameter(
                OwnerActorResolverTest.class.getDeclaredMethod("anyController", OwnerIdentity.class), 0);

        assertThatThrownBy(() -> RESOLVER.resolveArgument(parameter, null,
                new ServletWebRequest(new MockHttpServletRequest()), null))
                .isInstanceOfSatisfying(AdminException.class,
                        failure -> assertThat(failure.code()).isEqualTo(AdminCode.UNAUTHORIZED));
    }
}
