package org.pgledger.admin;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.MutableClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADM-028: five failed token attempts of one client address within a minute lock that address for a minute. The clock is
 * the one of the application (the tests move it), the counters expire by themselves.
 */
@AdminTest
class LockoutTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    private ResultActions bearer(Browser browser, String token) throws Exception {
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void fail(Browser browser, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            bearer(browser, WRONG).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    @DisplayName("ADM-028: after five failures the sixth attempt, even with the right token, is 429 TOO_MANY_AUTH_FAILURES")
    void adm028_lockAfterFiveFailures() throws Exception {
        Browser browser = new Browser(mvc);
        fail(browser, 5);

        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_AUTH_FAILURES"));
        bearer(browser, WRONG).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("ADM-028: the lock ends after the lock period; then the right token works")
    void adm028_lockEnds() throws Exception {
        Browser browser = new Browser(mvc);
        fail(browser, 5);
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofSeconds(59));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(2));

        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-028: a success before the fifth failure resets the count")
    void adm028_successResets() throws Exception {
        Browser browser = new Browser(mvc);
        fail(browser, 4);
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());

        fail(browser, 4);
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-028: failures are counted per client address; failures of the login form count like those of the bearer")
    void adm028_perAddressAndForm() throws Exception {
        Browser attacker = new Browser(mvc);
        for (int i = 0; i < 5; i++) {
            String csrf = attacker.csrfOf("/login");
            attacker.perform(attacker.form("/login", Browser.Form.of("username", "owner", "token", WRONG), csrf)
                    .accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        }

        bearer(attacker, AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());
        bearer(new Browser(mvc), AdminTestSettings.TOKEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-028: failures older than the window do not add up")
    void adm028_windowExpires() throws Exception {
        Browser browser = new Browser(mvc);
        fail(browser, 4);
        clock.advance(Duration.ofSeconds(61));

        fail(browser, 4);
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
    }
}
