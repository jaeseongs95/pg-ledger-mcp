package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.admin.app.LedgerAdminProperties.Oidc.Owner;
import org.pgledger.admin.security.OwnerOidcUserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.unit.DataSize;

/**
 * ADM-058 (security audit U4): the allow list of the OpenID Connect sign-in is bound to the issuer. An entry counts for
 * its own issuer only, whatever the other issuer says about its users, and the e-mail comparison does not depend on the
 * default locale of the JVM.
 */
class OwnerOidcUserServiceTest {

    private static final String ISSUER_A = "https://issuer-a.example";
    private static final String ISSUER_B = "https://issuer-b.example";

    private static LedgerAdminProperties properties(Owner... owners) {
        return new LedgerAdminProperties("a-token-of-sixteen-chars", "an-agent-token-of-sixteen", List.of("localhost"),
                DataSize.ofKilobytes(64), java.nio.file.Path.of("/unused"), java.nio.file.Path.of("/unused-drivers"),
                new LedgerAdminProperties.Lockout(5, Duration.ofSeconds(60), Duration.ofSeconds(60), 50),
                new LedgerAdminProperties.Oidc(List.of(owners)), new LedgerAdminProperties.Mcp(null, Duration.ofSeconds(2)));
    }

    private static OidcUserRequest request(String issuer, String subject, Consumer<OidcIdToken.Builder> tweak) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("mock").clientId("client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://admin.example/login/oauth2/code/mock").authorizationUri(issuer + "/authorize")
                .tokenUri(issuer + "/token").build();
        Instant now = Instant.now();
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token", now,
                now.plusSeconds(60));
        OidcIdToken.Builder id = OidcIdToken.withTokenValue("id-token").issuer(issuer).subject(subject)
                .issuedAt(now).expiresAt(now.plusSeconds(60)).claim("email_verified", true);
        tweak.accept(id);
        return new OidcUserRequest(registration, access, id.build());
    }

    private static void email(OidcIdToken.Builder id, String address) {
        id.claim("email", address);
    }

    @Test
    @DisplayName("ADM-058: a verified e-mail of the list signs in at its own issuer, whatever its letter case")
    void adm058_emailAtItsIssuer() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(new Owner(ISSUER_A, null, "Owner@Example.Test")));

        OidcUser user = service.loadUser(request(ISSUER_A, "any-subject", id -> email(id, "OWNER@example.TEST")));

        assertThat(user.getSubject()).isEqualTo("any-subject");
        assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OWNER");
    }

    @Test
    @DisplayName("ADM-058: the same verified e-mail from another issuer is refused")
    void adm058_emailFromAnotherIssuer() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(new Owner(ISSUER_A, null, "owner@example.test")));

        assertThatThrownBy(() -> service.loadUser(request(ISSUER_B, "any", id -> email(id, "owner@example.test"))))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("ADM-058: a subject of the list signs in at its own issuer only, with or without an e-mail")
    void adm058_subjectBoundToItsIssuer() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(new Owner(ISSUER_A, "subject-1", null)));

        assertThat(service.loadUser(request(ISSUER_A, "subject-1", id -> { })).getSubject()).isEqualTo("subject-1");
        assertThatThrownBy(() -> service.loadUser(request(ISSUER_B, "subject-1", id -> { })))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> service.loadUser(request(ISSUER_A, "subject-2", id -> { })))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("ADM-058: each entry counts for its own issuer: two issuers, two people")
    void adm058_twoIssuers() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(new Owner(ISSUER_A, null, "a@example.test"),
                new Owner(ISSUER_B, null, "b@example.test")));

        assertThat(service.loadUser(request(ISSUER_A, "x", id -> email(id, "a@example.test")))).isNotNull();
        assertThat(service.loadUser(request(ISSUER_B, "y", id -> email(id, "b@example.test")))).isNotNull();
        assertThatThrownBy(() -> service.loadUser(request(ISSUER_A, "x", id -> email(id, "b@example.test"))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> service.loadUser(request(ISSUER_B, "y", id -> email(id, "a@example.test"))))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("ADM-058: an e-mail that the provider did not verify, or none, is refused")
    void adm058_unverifiedEmail() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(new Owner(ISSUER_A, null, "owner@example.test")));

        assertThatThrownBy(() -> service.loadUser(request(ISSUER_A, "s", id -> {
            email(id, "owner@example.test");
            id.claim("email_verified", false);
        }))).isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> service.loadUser(request(ISSUER_A, "s", id -> { })))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("ADM-058: the e-mail comparison does not depend on the default locale (Turkish: I and i)")
    void adm058_localeRoot() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            OwnerOidcUserService service = new OwnerOidcUserService(properties(new Owner(ISSUER_A, null, "ILKER@Example.Test")));

            assertThat(service.loadUser(request(ISSUER_A, "s", id -> email(id, "ilker@example.test")))).isNotNull();
            assertThat(service.loadUser(request(ISSUER_A, "s", id -> email(id, "ILKER@EXAMPLE.TEST")))).isNotNull();
        } finally {
            Locale.setDefault(before);
        }
    }
}
