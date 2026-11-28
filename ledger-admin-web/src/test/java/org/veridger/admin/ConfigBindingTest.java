// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.admin.app.LedgerAdminProperties;
import org.veridger.admin.support.AdminTestSettings;
import org.veridger.admin.support.FailureText;
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

    @Test
    @DisplayName("LGN-011: the settings of the limit are the window, the global budget and the global delay; the removed keys max-failures and lock-period are ignored like any unknown key")
    void lgn011_theLockSettingsAreGone() {
        assertThat(Stream.of(LedgerAdminProperties.Lockout.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).containsExactly("window", "globalMaxFailures",
                        "globalDelay");
        runner().withPropertyValues(AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY,
                "ledger.admin.lockout.max-failures=1", "ledger.admin.lockout.lock-period=1h",
                "ledger.admin.lockout.global-max-failures=7", "ledger.admin.lockout.global-delay=2s").run(context -> {
                    assertThat(context).hasNotFailed();
                    LedgerAdminProperties.Lockout lockout = context.getBean(LedgerAdminProperties.class).lockout();
                    assertThat(lockout.globalMaxFailures()).isEqualTo(7);
                    assertThat(lockout.globalDelay()).isEqualTo(java.time.Duration.ofSeconds(2));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ledger.admin.lockout.global-max-failures=0", "ledger.admin.lockout.global-delay=-1s"})
    @DisplayName("LGN-011: a global budget below 1 or a negative delay stops the start and names the setting")
    void lgn011_unusableLimitStopsTheStart(String property) {
        // the failure of a constraint on a component names the property as the record does (globalMaxFailures), a failure of the
        // message of an assertion as the key (global-delay)
        String key = property.substring(property.lastIndexOf('.') + 1, property.indexOf('='));
        String camel = org.springframework.util.StringUtils.uncapitalize(
                java.util.Arrays.stream(key.split("-")).map(org.springframework.util.StringUtils::capitalize)
                        .collect(java.util.stream.Collectors.joining()));
        runner().withPropertyValues(AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY, property)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(FailureText.of(context.getStartupFailure())).containsAnyOf(key, camel);
                });
    }

    private static ApplicationContextRunner secrets() {
        return runner().withPropertyValues(AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY);
    }

    @Test
    @DisplayName("PSK-CFG-01: a new installation (nothing chosen, no OpenID Connect setting) signs in with a passkey")
    void psk_defaultIsPasskey() {
        secrets().run(context -> assertThat(context.getBean(LedgerAdminProperties.class).effectiveSignIn())
                .isEqualTo(org.veridger.admin.app.SignInMethod.PASSKEY));
    }

    @Test
    @DisplayName("PSK-CFG-02: OpenID Connect settings without a sign-in choice stop the start and name ledger.admin.sign-in (nothing is guessed)")
    void psk_oidcSettingsNeedAChoice() {
        secrets().withPropertyValues("ledger.admin.oidc.issuer=https://issuer.example",
                "ledger.admin.oidc.owner-email=owner@example.test").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(FailureText.of(context.getStartupFailure())).contains("ledger.admin.sign-in");
                });
    }

    @Test
    @DisplayName("PSK-CFG-03: sign-in=oidc needs the owner e-mail and the issuer, and binds with both")
    void psk_oidcNeedsTheOwner() {
        secrets().withPropertyValues("ledger.admin.sign-in=oidc").run(context -> {
            assertThat(context).hasFailed();
            assertThat(FailureText.of(context.getStartupFailure())).contains("ledger.admin.oidc.owner-email");
        });
        secrets().withPropertyValues("ledger.admin.sign-in=oidc", "ledger.admin.oidc.issuer=https://issuer.example",
                "ledger.admin.oidc.owner-email=owner@example.test").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("PCS-042: a list ledger.admin.oidc.owners is no setting of the product (no release had it): it is ignored like any unknown setting, the one owner e-mail binds")
    void pcs_noRefusalOfAnOwnersList() {
        secrets().withPropertyValues("ledger.admin.sign-in=oidc", "ledger.admin.oidc.issuer=https://issuer.example",
                "ledger.admin.oidc.owner-email=owner@example.test", "ledger.admin.oidc.owners[0].issuer=https://issuer.example",
                "ledger.admin.oidc.owners[0].email=a@example.test", "ledger.admin.oidc.owners[1].issuer=https://issuer.example",
                "ledger.admin.oidc.owners[1].email=b@example.test").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LedgerAdminProperties.class).oidc().ownerEmail())
                            .isEqualTo("owner@example.test");
                });
    }

    @Test
    @DisplayName("PSK-CFG-05: a passkey sign-in with an explicit choice binds; the passkey settings default to nothing (the host is derived at start)")
    void psk_explicitPasskeyBinds() {
        secrets().withPropertyValues("ledger.admin.sign-in=passkey").run(context -> {
            LedgerAdminProperties properties = context.getBean(LedgerAdminProperties.class);
            assertThat(properties.effectiveSignIn()).isEqualTo(org.veridger.admin.app.SignInMethod.PASSKEY);
            assertThat(properties.passkey().rpId()).isNull();
            assertThat(properties.passkey().origins()).isEmpty();
        });
    }

    @Test
    @DisplayName("UPD-CFG-01: ledger.admin.update binds defaults and rejects invalid repository or API URL with userinfo")
    void upd_settingsValidation() {
        secrets().run(context -> {
            LedgerAdminProperties.Update update = context.getBean(LedgerAdminProperties.class).update();
            assertThat(update.enabled()).isTrue();
            assertThat(update.repository()).isEqualTo("jaeseongs95/veridger");
            assertThat(update.currentVersion()).isEqualTo("7.0.0");
            assertThat(update.currentSchemaVersion()).isEqualTo(17);
        });
        secrets().withPropertyValues("ledger.admin.update.repository=bad repo name").run(context -> {
            assertThat(context).hasFailed();
            assertThat(FailureText.of(context.getStartupFailure())).contains("ledger.admin.update.repository");
        });
        secrets().withPropertyValues("ledger.admin.update.api-base-url=https://user:pass@api.github.com").run(context -> {
            assertThat(context).hasFailed();
            assertThat(FailureText.of(context.getStartupFailure())).contains("ledger.admin.update.api-base-url")
                    .doesNotContain("pass");
        });
    }
}
