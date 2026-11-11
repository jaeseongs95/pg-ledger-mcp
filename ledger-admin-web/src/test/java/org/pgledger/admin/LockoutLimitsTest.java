package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * ADM-053 and ADM-054 (security audit D4, D5): the limit of failed attempts holds when the attempts come at the same
 * time, and a client cannot get more attempts by changing the address inside its IPv6 prefix.
 */
@AdminTest
class LockoutLimitsTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";
    private static final int THREADS = 64;

    @Autowired
    private MockMvc mvc;

    private ResultActions bearer(Browser browser, String token) throws Exception {
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private int attempt(String address) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRONG).with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })).andReturn().getResponse().getStatus();
    }

    @RepeatedTest(3)
    @DisplayName("ADM-053: 64 failed attempts of one address at the same moment: exactly five are answered 401, the rest 429")
    void adm053_concurrentFailuresCannotExceedTheLimit(org.junit.jupiter.api.RepetitionInfo repetition) throws Exception {
        String address = "10.200." + repetition.getCurrentRepetition() + ".1";
        CyclicBarrier together = new CyclicBarrier(THREADS);
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                answers.add(pool.submit(() -> {
                    together.await();
                    return attempt(address);
                }));
            }
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get());
            }
        }

        assertThat(statuses.stream().filter(status -> status == 401).count()).as("answered as a wrong token").isEqualTo(5);
        assertThat(statuses.stream().filter(status -> status == 429).count()).as("refused by the lock").isEqualTo(THREADS - 5);
    }

    @Test
    @DisplayName("ADM-054: five failures from five addresses of one IPv6 /64 lock the prefix; another /64 is not affected")
    void adm054_anIpv6PrefixIsOneClient() throws Exception {
        for (int i = 1; i <= 5; i++) {
            bearer(new Browser(mvc, "2001:db8:a:1::" + i), WRONG).andExpect(status().isUnauthorized());
        }

        bearer(new Browser(mvc, "2001:db8:a:1:ffff::6"), AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_AUTH_FAILURES"));
        bearer(new Browser(mvc, "2001:db8:a:2::1"), AdminTestSettings.TOKEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-054: an IPv4-mapped IPv6 address is the IPv4 address it carries")
    void adm054_aMappedAddressIsTheIpv4Address() throws Exception {
        for (int i = 0; i < 5; i++) {
            bearer(new Browser(mvc, "10.201.1.1"), WRONG).andExpect(status().isUnauthorized());
        }

        bearer(new Browser(mvc, "::ffff:10.201.1.1"), AdminTestSettings.TOKEN).andExpect(status().isTooManyRequests());
        bearer(new Browser(mvc, "10.201.1.2"), AdminTestSettings.TOKEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-054: addresses of different IPv6 prefixes are different clients")
    void adm054_differentPrefixesAreDifferentClients() throws Exception {
        for (int i = 1; i <= 5; i++) {
            bearer(new Browser(mvc, "2001:db8:b:" + i + "::1"), WRONG).andExpect(status().isUnauthorized());
        }

        bearer(new Browser(mvc, "2001:db8:b:9::1"), WRONG).andExpect(status().isUnauthorized());
    }
}
