package org.pgledger.admin;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.MutableClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADM-050 (security audit D1, D5): the cap on the failures of all clients together does not depend on the client address.
 * Here three failures in all lock everybody, so a client with a new address, which has not failed once, is refused too.
 */
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, "ledger.admin.lockout.global-max-failures=3", AdminTestSettings.QUIET_BANNER,
    AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX})
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(AdminTestInfrastructure.class)
class GlobalLockoutTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    private ResultActions bearer(Browser browser, String token) throws Exception {
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Test
    @DisplayName("ADM-050: three failures from three addresses lock a fourth address that never failed; the lock ends after "
            + "the lock period")
    void adm050_theCapDoesNotDependOnTheAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());
        }

        bearer(new Browser(mvc), AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_AUTH_FAILURES"));
        clock.advance(Duration.ofSeconds(59));
        bearer(new Browser(mvc), AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(2));

        bearer(new Browser(mvc), AdminTestSettings.TOKEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-050: failures older than the window do not add up across clients")
    void adm050_theWindowAppliesToTheCapToo() throws Exception {
        clock.advance(Duration.ofSeconds(120));
        bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());
        bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());
        clock.advance(Duration.ofSeconds(61));
        bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());
        bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());

        bearer(new Browser(mvc), AdminTestSettings.TOKEN).andExpect(status().isOk());
    }
}
