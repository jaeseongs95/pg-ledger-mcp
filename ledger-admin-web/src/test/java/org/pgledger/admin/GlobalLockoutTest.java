package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.test.context.ContextConfiguration;
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
 * ADM-050 (security audit D1, D5; changed by audit2 N1): the budget of token checks of all clients together does not
 * depend on the client address, and it slows the checks down instead of locking everybody out. Here three checks in
 * all are the budget and every check beyond it waits 300 ms: a client with a new address that never failed, the owner
 * with the right token, still logs in (a global lock would be a denial of service of the only login).
 */
@ContextConfiguration(initializers = TestSchemaInitializer.class)
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, "ledger.admin.lockout.global-max-failures=3",
    "ledger.admin.lockout.global-delay=300ms", AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
    AdminTestSettings.NO_JMX})
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(AdminTestInfrastructure.class)
class GlobalLockoutTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";
    /** Less than the delay by a margin for timer granularity: a check that was delayed took at least this long. */
    private static final Duration DELAYED_AT_LEAST = Duration.ofMillis(250);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    private ResultActions bearer(Browser browser, String token) throws Exception {
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private Duration timed(String token, int expectedStatus) throws Exception {
        long start = System.nanoTime();
        bearer(new Browser(mvc), token).andExpect(status().is(expectedStatus));
        return Duration.ofNanos(System.nanoTime() - start);
    }

    @Test
    @DisplayName("ADM-050 (changed by audit2 N1): after the budget of three checks from three addresses, the right token of a fourth address still logs in, delayed; it is never 429; the delay ends with the window")
    void adm050_theBudgetSlowsDownAndNeverLocks() throws Exception {
        for (int i = 0; i < 3; i++) {
            bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());
        }

        assertThat(timed(AdminTestSettings.TOKEN, 200)).as("the right token is delayed, not refused")
                .isGreaterThanOrEqualTo(DELAYED_AT_LEAST);
        assertThat(timed(WRONG, 401)).as("a wrong token is answered as one, after the delay too")
                .isGreaterThanOrEqualTo(DELAYED_AT_LEAST);
        // the owner's own success gave its count back to the budget; the wrong one did not: still over the budget
        clock.advance(Duration.ofSeconds(59));
        assertThat(timed(AdminTestSettings.TOKEN, 200)).isGreaterThanOrEqualTo(DELAYED_AT_LEAST);
        clock.advance(Duration.ofSeconds(2));
        timed(AdminTestSettings.TOKEN, 200);
    }

    @Test
    @DisplayName("ADM-050: checks older than the window do not add up across clients: the budget is not used up")
    void adm050_theWindowAppliesToTheBudgetToo() throws Exception {
        clock.advance(Duration.ofSeconds(120));
        bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());
        bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());
        clock.advance(Duration.ofSeconds(61));
        bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());
        bearer(new Browser(mvc), WRONG).andExpect(status().isUnauthorized());

        bearer(new Browser(mvc), AdminTestSettings.TOKEN).andExpect(status().isOk());
    }
}
