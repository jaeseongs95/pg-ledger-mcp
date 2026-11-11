package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.ProbeController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** ADM-001, ADM-002, ADM-036: the security headers on every class of response, and what a server error shows. */
@AdminTest
class SecurityHeadersTest {

    /** One class of response: how to ask for it. */
    private record Response(String name, Function<Browser, MockHttpServletRequestBuilder> ask,
            boolean signedIn) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Response> classes() {
        return Stream.of(
                new Response("page", b -> b.get("/login"), false),
                new Response("static asset", b -> b.get("/css/admin.css"), false),
                new Response("health", b -> b.get("/healthz").accept(MediaType.APPLICATION_JSON), false),
                new Response("redirect", b -> b.get("/"), false),
                new Response("401", b -> b.get("/api/session").accept(MediaType.APPLICATION_JSON), false),
                new Response("404", b -> b.get("/no-such-page").accept(MediaType.APPLICATION_JSON), true),
                new Response("5xx", b -> b.get("/probe/fail").accept(MediaType.APPLICATION_JSON), true));
    }

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @MethodSource("classes")
    @DisplayName("ADM-002: CSP and the security headers are on every class of response; no inline script or style")
    void adm002_everyClassOfResponseCarriesTheHeaders(Response response) throws Exception {
        Browser browser = response.signedIn() ? Browser.signedIn(mvc) : new Browser(mvc);

        MvcResult result = browser.perform(response.ask().apply(browser)).andReturn();

        String policy = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(policy).contains("default-src 'self'", "frame-ancestors 'none'", "form-action 'self'")
                .doesNotContain("'unsafe-inline'", "'unsafe-eval'");
        assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    @DisplayName("ADM-001: /healthz is public, 200, and carries the headers; no other actuator endpoint is exposed")
    void adm001_health() throws Exception {
        Browser browser = new Browser(mvc);

        browser.perform(browser.get("/healthz").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Frame-Options", "DENY"));
        browser.perform(browser.get("/env").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        Browser owner = Browser.signedIn(mvc);
        owner.perform(owner.get("/env").accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ADM-036: a server error is a problem detail with code and trace id, without the text of the failure")
    void adm036_serverErrorShowsNoInternalDetail() throws Exception {
        Browser owner = Browser.signedIn(mvc);

        String body = owner.perform(owner.get("/probe/fail").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(ProbeController.INTERNAL_DETAIL).doesNotContain("Exception")
                .doesNotContain("at org.");

        owner.perform(owner.get("/probe/fail").accept(MediaType.TEXT_HTML)).andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("INTERNAL_ERROR")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(ProbeController.INTERNAL_DETAIL))));
    }
}
