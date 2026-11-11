package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.MockOidcProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * ADM-030, 031, 032: the OpenID Connect sign-in, end to end against a provider inside the test JVM: the authorization
 * request (state, nonce, PKCE, the configured redirect address), the callback with the ID token validation of Spring
 * Security, and the e-mail rule of the admin. No real provider is contacted; the provider is configuration only.
 */
@AdminTest
class OidcTest {

    private static final String REDIRECT = "https://admin.example/login/oauth2/code/mock";
    private static final MockOidcProvider PROVIDER = MockOidcProvider.start();

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        PROVIDER.registrationProperties(REDIRECT).forEach((name, value) -> registry.add(name, () -> value));
        registry.add("ledger.admin.oidc.owners[0].issuer", PROVIDER::issuer);
        registry.add("ledger.admin.oidc.owners[0].email", () -> AdminTestSettings.OWNER_EMAIL);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.close();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /** The authorization request of the browser: where it goes, with what. */
    private record Authorization(MultiValueMap<String, String> query, String state, String nonce) {
    }

    private Authorization start(Browser browser) throws Exception {
        String location = browser.perform(browser.get("/oauth2/authorization/mock")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(location).startsWith(PROVIDER.issuer() + "/authorize");
        return new Authorization(query, decoded(query.getFirst("state")), decoded(query.getFirst("nonce")));
    }

    private static String decoded(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.ResultActions callback(Browser browser, String state,
            Consumer<JWTClaimsSet.Builder> tweak, Authorization authorization) throws Exception {
        PROVIDER.issue(authorization.nonce(), tweak);
        return browser.perform(browser.get("/login/oauth2/code/mock").param("code", "a-code").param("state", state));
    }

    @Test
    @DisplayName("ADM-030: the login page offers the provider; its authorization request has state, nonce and PKCE, and the "
            + "configured external redirect address")
    void adm030_authorizationRequest() throws Exception {
        Browser browser = new Browser(mvc);

        assertThat(browser.perform(browser.get("/login")).andReturn().getResponse().getContentAsString())
                .contains("/oauth2/authorization/mock").contains("Mock provider");
        Authorization authorization = start(browser);

        assertThat(authorization.state()).isNotBlank();
        assertThat(authorization.nonce()).isNotBlank();
        assertThat(authorization.query().getFirst("code_challenge")).isNotBlank();
        assertThat(authorization.query().getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(authorization.query().getFirst("client_id")).isEqualTo(MockOidcProvider.CLIENT_ID);
        assertThat(java.net.URLDecoder.decode(authorization.query().getFirst("redirect_uri"),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(REDIRECT);
    }

    @Test
    @DisplayName("ADM-031: a valid ID token of an allowed, verified e-mail signs the owner in")
    void adm031_allowedEmailSignsIn() throws Exception {
        Browser browser = new Browser(mvc);
        Authorization authorization = start(browser);

        callback(browser, authorization.state(), claims -> { }, authorization).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.endsWith("/")));

        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.signIn").value("OIDC"));
    }

    /** One way for a sign-in to go wrong. */
    private record Refusal(String name, String state, Consumer<JWTClaimsSet.Builder> tweak) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Refusal> refusals() {
        return Stream.of(
                new Refusal("other e-mail", null, c -> c.claim("email", "intruder@example.test")),
                new Refusal("e-mail not verified", null, c -> c.claim("email_verified", false)),
                new Refusal("no e-mail verification claim", null, c -> c.claim("email_verified", null)),
                new Refusal("wrong audience", null, c -> c.audience("another-client")),
                new Refusal("wrong issuer", null, c -> c.issuer("https://another-issuer.example")),
                new Refusal("wrong nonce", null, c -> c.claim("nonce", "not-the-nonce")),
                new Refusal("expired token", null, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                        .issueTime(Date.from(Instant.now().minusSeconds(7200)))),
                new Refusal("wrong state", "not-the-state", c -> { }));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    @DisplayName("ADM-031: any other e-mail, an unverified one, a wrong audience, issuer, nonce or state, or an expired token "
            + "is 403 and opens no authenticated session")
    void adm031_refusals(Refusal refusal) throws Exception {
        Browser browser = new Browser(mvc);
        Authorization authorization = start(browser);

        callback(browser, refusal.state() == null ? authorization.state() : refusal.state(), refusal.tweak(), authorization)
                .andExpect(status().isForbidden());

        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADM-067: an owner operation of an OIDC session is recorded with the issuer and the subject of the sign-in")
    void adm067_auditActorOfAnOidcSession() throws Exception {
        Browser browser = new Browser(mvc);
        Authorization authorization = start(browser);
        callback(browser, authorization.state(), claims -> { }, authorization).andExpect(status().is3xxRedirection());

        browser.perform(browser.form("/codes", Browser.Form.of("group", "DEFECT_CATEGORY", "code", "OIDCAUD", "nameKo",
                "감사", "nameEn", "Audit", "state", "ACTIVE"), browser.csrfOf("/codes")))
                .andExpect(status().is3xxRedirection());

        assertThat(jdbc.sql("SELECT actor FROM owner_audit WHERE operation = 'CODE_SET' AND subject = :subject")
                .param("subject", "DEFECT_CATEGORY/OIDCAUD").query(String.class).list())
                .containsExactly("oidc:" + PROVIDER.issuer() + "#" + MockOidcProvider.SUBJECT);
    }

    @Test
    @DisplayName("ADM-032: an OIDC session needs the CSRF token and ends with the logout, like a token session")
    void adm032_sameRulesAsTokenSessions() throws Exception {
        Browser browser = new Browser(mvc);
        Authorization authorization = start(browser);
        callback(browser, authorization.state(), claims -> { }, authorization).andExpect(status().is3xxRedirection());

        browser.perform(browser.form("/projects", Browser.Form.of("id", "oidcp", "code", "OIDCP", "title", "t"), null)
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        browser.perform(browser.form("/projects", Browser.Form.of("id", "oidcp", "code", "OIDCP", "title", "t"),
                browser.csrfOf("/projects"))).andExpect(status().is3xxRedirection());

        browser.perform(browser.form("/logout", Browser.Form.of(), browser.csrfOf("/projects")))
                .andExpect(status().is3xxRedirection());
        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }
}
