package org.pgledger.admin;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.pgledger.admin.support.Browser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.pgledger.testing.database.TestDatabaseScanFilter;
import org.pgledger.testing.database.TestDriverRegistration;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADM-071 (audit2 S4): the lock has an explicit state and compares times of the monotonic ticker by their difference.
 * {@code System.nanoTime} may be negative (its origin is arbitrary) and may wrap around, and 0 is a value like any
 * other, so no ticker value may stand for "not locked". The ticker of this context is one that the test sets: a
 * negative value, a value where the end of the lock is exactly 0, and a value that wraps around.
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.NO_GLOBAL_CAP, "admin.test.moving-ticker=false",
    AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX})
@AutoConfigureMockMvc
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import({AdminTestInfrastructure.class, NegativeTickerLockoutTest.SettableTickerConfiguration.class})
class NegativeTickerLockoutTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";
    private static final long SECOND = 1_000_000_000L;

    /** A ticker that the test sets. */
    static final class SettableTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        void set(long value) {
            nanos.set(value);
        }

        void advance(Duration by) {
            nanos.addAndGet(by.toNanos());
        }

        @Override
        public long read() {
            return nanos.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SettableTickerConfiguration {

        @Bean
        @Primary
        SettableTicker settableTicker() {
            return new SettableTicker();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SettableTicker ticker;

    private ResultActions bearer(Browser browser, String token) throws Exception {
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void fiveFailuresThenLocked(Browser browser) throws Exception {
        for (int i = 0; i < 5; i++) {
            bearer(browser, WRONG).andExpect(status().isUnauthorized());
        }
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("ADM-071: a negative ticker value: the five attempts are counted as attempts (401), the sixth is locked (429), the lock ends after the lock period")
    void adm071_aNegativeTicker() throws Exception {
        ticker.set(-1_000 * SECOND);
        Browser browser = new Browser(mvc);

        fiveFailuresThenLocked(browser);
        ticker.advance(Duration.ofSeconds(59));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());
        ticker.advance(Duration.ofSeconds(2));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-071: the lock that ends exactly at ticker value 0 is a lock")
    void adm071_aLockThatEndsAtZero() throws Exception {
        ticker.set(-60 * SECOND);
        Browser browser = new Browser(mvc);

        fiveFailuresThenLocked(browser);
        ticker.advance(Duration.ofSeconds(61));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-071: the ticker wraps around during the lock: the lock holds for the lock period and ends after it")
    void adm071_theTickerWrapsAround() throws Exception {
        ticker.set(Long.MAX_VALUE - 10 * SECOND);
        Browser browser = new Browser(mvc);

        fiveFailuresThenLocked(browser);
        ticker.advance(Duration.ofSeconds(30));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());
        ticker.advance(Duration.ofSeconds(31));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
    }
}
