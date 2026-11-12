package org.pgledger.admin;

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
 * ADM-059 (security audit U6): the lock counts on the monotonic ticker of the application, not on the UTC clock. This
 * context has no test ticker, so the application uses its own ticker ({@code System.nanoTime}); setting the wall clock
 * forward by two hours must not end the lock.
 */
@ContextConfiguration(initializers = TestSchemaInitializer.class)
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.NO_GLOBAL_CAP, "admin.test.moving-ticker=false",
    AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX})
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(AdminTestInfrastructure.class)
class MonotonicLockoutTest {

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
    @DisplayName("ADM-059: a jump of the wall clock does not end a lock (the lock counts on the monotonic ticker)")
    void adm059_theWallClockDoesNotEndTheLock() throws Exception {
        Browser browser = new Browser(mvc);
        for (int i = 0; i < 5; i++) {
            bearer(browser, WRONG).andExpect(status().isUnauthorized());
        }
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());

        clock.jumpWallClock(Duration.ofHours(2));

        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());
    }
}
