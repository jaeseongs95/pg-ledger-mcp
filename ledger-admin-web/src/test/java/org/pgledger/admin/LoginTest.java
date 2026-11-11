package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** ADM-007 .. ADM-011: who gets in, how, and what the session does. */
@AdminTest
class LoginTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("ADM-007: a page of an anonymous browser redirects to the login; a JSON endpoint answers 401 UNAUTHORIZED")
    void adm007_anonymous() throws Exception {
        Browser browser = new Browser(mvc);

        browser.perform(browser.get("/defects")).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
        browser.perform(browser.get("/api/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("problem+json")));
    }

    @Test
    @DisplayName("ADM-008: the agent token is refused with AGENT_TOKEN_FORBIDDEN as bearer and as login password; no session")
    void adm008_agentTokenIsRefused() throws Exception {
        Browser bearer = new Browser(mvc);
        bearer.perform(bearer.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + AdminTestSettings.AGENT_TOKEN))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AGENT_TOKEN_FORBIDDEN"));

        Browser form = new Browser(mvc);
        String csrf = form.csrfOf("/login");
        form.perform(form.form("/login", Form.of("username", "owner", "token", AdminTestSettings.AGENT_TOKEN), csrf)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AGENT_TOKEN_FORBIDDEN"));
        form.perform(form.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADM-009: the admin token signs in through the form and opens a session (the cookie and the session id: RealServerTest)")
    void adm009_loginOpensASession() throws Exception {
        Browser browser = new Browser(mvc);
        String csrf = browser.csrfOf("/login");

        browser.perform(browser.form("/login", Form.of("username", "owner", "token", AdminTestSettings.TOKEN), csrf))
                .andExpect(status().is3xxRedirection()).andExpect(header().string(HttpHeaders.LOCATION, endsWith("/")));

        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.signIn").value("TOKEN"));
    }

    @Test
    @DisplayName("ADM-009: a wrong token sends a browser back to the login with the code; no session")
    void adm009_wrongTokenFromABrowser() throws Exception {
        Browser browser = new Browser(mvc);
        String csrf = browser.csrfOf("/login");

        browser.perform(browser.form("/login", Form.of("username", "owner", "token", "wrong-wrong-wrong-wrong"), csrf)
                .accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/login?error=UNAUTHORIZED")));
        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADM-010: the admin token as bearer reads an endpoint; a wrong or an empty bearer is 401")
    void adm010_bearer() throws Exception {
        Browser browser = new Browser(mvc);

        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + AdminTestSettings.TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("owner"));
        browser.perform(browser.get("/api/overview").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + AdminTestSettings.TOKEN))
                .andExpect(status().isOk());
        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-wrong-wrong-wrong")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ")).andExpect(status().isUnauthorized());
        // a bearer request does not open a session
        assertThat(browser.session().getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    @Test
    @DisplayName("ADM-038: the token in the query string (/?token=...) opens no session and is not honoured")
    void adm038_urlTokenIsNotHonoured() throws Exception {
        Browser browser = new Browser(mvc);

        browser.perform(browser.get("/?token=" + AdminTestSettings.TOKEN)).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
        browser.perform(browser.get("/api/session?token=" + AdminTestSettings.TOKEN).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        assertThat(browser.session().getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    @Test
    @DisplayName("ADM-029: without a configured OIDC provider the login page offers none and the authorization URL is 404")
    void adm029_noOidcWithoutConfiguration() throws Exception {
        Browser browser = new Browser(mvc);

        assertThat(browser.perform(browser.get("/login")).andReturn().getResponse().getContentAsString())
                .doesNotContain("oauth2/authorization");
        browser.perform(browser.get("/oauth2/authorization/google").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        browser.perform(browser.get("/login/oauth2/code/google?code=x&state=y").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ADM-011: logout (POST with the CSRF token) ends the session on the server and clears the cookie")
    void adm011_logout() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        String csrf = browser.csrfOf("/projects");

        browser.perform(browser.form("/logout", Form.of(), csrf)).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("LEDGER_ADMIN_SESSION=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }
}
