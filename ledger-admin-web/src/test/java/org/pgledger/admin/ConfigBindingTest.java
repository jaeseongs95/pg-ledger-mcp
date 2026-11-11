package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.FailureText;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * ADM-052 and ADM-055 (security audit D3, D9): settings that must stop the start. The properties are bound exactly as the
 * application binds them, with Bean Validation; the failure names the key and never prints a secret.
 */
class ConfigBindingTest {

    private static final String SECRET_IN_URL = "secret-pw-0123";

    @EnableConfigurationProperties(LedgerAdminProperties.class)
    static class PropertiesOnly {
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesOnly.class);
    }

    private record Bad(String name, String... properties) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Bad> unusableAgentTokens() {
        return Stream.of(
                new Bad("absent", AdminTestSettings.TOKEN_PROPERTY),
                new Bad("blank", AdminTestSettings.TOKEN_PROPERTY, "ledger.admin.agent-token= "),
                new Bad("15 characters", AdminTestSettings.TOKEN_PROPERTY,
                        "ledger.admin.agent-token=" + AdminTestSettings.AGENT_TOKEN.substring(0, 15)),
                new Bad("equal to the admin token", AdminTestSettings.TOKEN_PROPERTY,
                        "ledger.admin.agent-token=" + AdminTestSettings.TOKEN));
    }

    @ParameterizedTest
    @MethodSource("unusableAgentTokens")
    @DisplayName("ADM-052: no agent token, a blank, a short one or the admin token stops the start, names the key and "
            + "prints no token")
    void adm052_agentTokenIsRequired(Bad bad) {
        runner().withPropertyValues(bad.properties()).run(context -> {
            assertThat(context).hasFailed();
            String shown = FailureText.of(context.getStartupFailure());
            assertThat(shown).contains("ledger.admin.agent-token").doesNotContain(AdminTestSettings.TOKEN)
                    .doesNotContain(AdminTestSettings.AGENT_TOKEN).doesNotContain("Value: ");
        });
    }

    @Test
    @DisplayName("ADM-052: an agent token that differs from the admin token and has 16 characters binds")
    void adm052_aUsableAgentTokenBinds() {
        runner().withPropertyValues(AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    static Stream<String> unusableHealthUrls() {
        return Stream.of("http://user:" + SECRET_IN_URL + "@127.0.0.1:1/health",
                "https://:" + SECRET_IN_URL + "@example.test/healthz", "ftp://127.0.0.1/health", "127.0.0.1:1/health",
                "http:///health");
    }

    @ParameterizedTest
    @MethodSource("unusableHealthUrls")
    @DisplayName("ADM-055: a health URL with user information, without a host or that is not http(s) stops the start; the "
            + "failure names the key and prints no password")
    void adm055_healthUrlMustBeUsable(String url) {
        runner().withPropertyValues(AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY,
                "ledger.admin.mcp.health-url=" + url).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(FailureText.of(context.getStartupFailure())).contains("ledger.admin.mcp.health-url")
                            .doesNotContain(SECRET_IN_URL);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8765/healthz", "https://example.test/healthz",
        "http://localhost:1/other?probe=1"})
    @DisplayName("ADM-055: an http(s) health URL without user information binds")
    void adm055_aPlainHealthUrlBinds(String url) {
        runner().withPropertyValues(AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY,
                "ledger.admin.mcp.health-url=" + url).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("ADM-055: without a health URL the settings bind")
    void adm055_noHealthUrlBinds() {
        runner().withPropertyValues(AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY)
                .run(context -> assertThat(context.getBean(LedgerAdminProperties.class).mcp().healthUrl()).isNull());
    }
}
