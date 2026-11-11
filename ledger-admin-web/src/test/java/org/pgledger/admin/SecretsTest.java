package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.admin.security.AdminTokenProvider;
import org.pgledger.admin.security.LoginAttempts;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ADM-037 and the admin side of MCC-027: the tokens never appear in a page, a header, an error or a log, and a token that
 * is missing, blank or too short stops the start with a text that names the key and never the value.
 */
@AdminTest
@ExtendWith(OutputCaptureExtension.class)
class SecretsTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("ADM-037: neither token appears in any page, header, error body or log line of a session that signs in, "
            + "fails to sign in and meets errors")
    void adm037_tokensNeverAppear(CapturedOutput output) throws Exception {
        List<MvcResult> results = new ArrayList<>();
        Browser owner = new Browser(mvc);
        results.add(owner.perform(owner.get("/login")).andReturn());
        results.add(owner.signIn(AdminTestSettings.TOKEN).andReturn());
        for (String page : List.of("/", "/projects", "/defects", "/tasks", "/conditions", "/history", "/grants", "/codes",
                "/driver", "/defects/NOP-D-0001")) {
            results.add(owner.perform(owner.get(page)).andReturn());
        }
        Browser attacker = new Browser(mvc);
        results.add(attacker.perform(attacker.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + AdminTestSettings.AGENT_TOKEN)).andReturn());
        results.add(attacker.perform(attacker.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + AdminTestSettings.TOKEN.substring(1))).andReturn());
        results.add(attacker.signIn(AdminTestSettings.AGENT_TOKEN).andReturn());
        results.add(owner.perform(owner.get("/probe/fail").accept(MediaType.APPLICATION_JSON)).andExpect(status()
                .isInternalServerError()).andReturn());

        for (MvcResult result : results) {
            String shown = result.getResponse().getContentAsString() + result.getResponse().getHeaderNames().stream()
                    .map(name -> name + ": " + result.getResponse().getHeaders(name)).toList();
            assertThat(shown).doesNotContain(AdminTestSettings.TOKEN).doesNotContain(AdminTestSettings.AGENT_TOKEN);
        }
        assertThat(output.getAll()).doesNotContain(AdminTestSettings.TOKEN).doesNotContain(AdminTestSettings.AGENT_TOKEN)
                .doesNotContain("generated security password");
    }

    @Test
    @DisplayName("ADM-037: the settings print without the tokens")
    void adm037_propertiesDoNotPrintTheTokens(@Autowired LedgerAdminProperties properties) {
        assertThat(properties.toString()).doesNotContain(AdminTestSettings.TOKEN).doesNotContain(AdminTestSettings.AGENT_TOKEN)
                .contains("token=<hidden>");
    }

    /** The settings of the admin bound from the properties, and the provider that is made from them. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LedgerAdminProperties.class)
    static class TokenConfiguration {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        Ticker ticker() {
            return Ticker.systemTicker();
        }

        @Bean
        LoginAttempts loginAttempts(LedgerAdminProperties properties, Ticker ticker, Clock clock) {
            return new LoginAttempts(properties, ticker, clock);
        }

        @Bean
        AdminTokenProvider adminTokenProvider(LedgerAdminProperties properties, LoginAttempts attempts) {
            return new AdminTokenProvider(properties, attempts);
        }
    }

    static Stream<String> unusableTokens() {
        return Stream.of(AdminTestSettings.TOKEN.substring(0, 15), "abc123xyz", " ".repeat(20));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableTokens")
    @DisplayName("MCC-027 (admin): a token that is too short or blank stops the start, names the key and prints no token")
    void anUnusableTokenStopsTheStartWithoutPrintingIt(String token, CapturedOutput output) {
        new ApplicationContextRunner().withUserConfiguration(TokenConfiguration.class)
                .withPropertyValues("ledger.admin.token=" + token).run(context -> {
                    assertThat(context).hasFailed();
                    StringBuilder shown = new StringBuilder();
                    for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
                        shown.append(cause.getMessage()).append('\n');
                    }
                    assertThat(shown.toString()).contains("ledger.admin.token").doesNotContain("Value: ");
                    if (!token.isBlank()) {
                        assertThat(shown.toString()).doesNotContain(token);
                        assertThat(output.getAll()).doesNotContain(token);
                    }
                });
    }

    @Test
    @DisplayName("MCC-027 (admin): a missing token stops the start naming the key; a token equal to the agent token too")
    void missingOrSharedTokenStopsTheStart() {
        new ApplicationContextRunner().withUserConfiguration(TokenConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(String.valueOf(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()).getMessage()))
                    .contains("ledger.admin.token");
        });
        new ApplicationContextRunner().withUserConfiguration(TokenConfiguration.class)
                .withPropertyValues("ledger.admin.token=" + AdminTestSettings.TOKEN,
                        "ledger.admin.agent-token=" + AdminTestSettings.TOKEN)
                .run(context -> {
                    assertThat(context).hasFailed();
                    String shown = NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()).getMessage();
                    assertThat(shown).contains("ledger.admin.agent-token").doesNotContain(AdminTestSettings.TOKEN);
                });
    }

    @Test
    @DisplayName("MCC-027 (admin): a token of 16 characters starts")
    void aTokenOfTheLeastLengthStarts() {
        new ApplicationContextRunner().withUserConfiguration(TokenConfiguration.class)
                .withPropertyValues("ledger.admin.token=" + AdminTestSettings.TOKEN.substring(0, 16))
                .run(context -> assertThat(context).hasNotFailed());
    }
}
