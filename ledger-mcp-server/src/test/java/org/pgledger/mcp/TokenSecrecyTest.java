package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.mcp.config.LedgerMcpProperties;
import org.pgledger.mcp.security.AgentTokenProvider;
import org.pgledger.mcp.support.McpTestSettings;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.util.unit.DataSize;

/** MCC-015 and MCC-027, the parts that need no server: the comparison of the token and what prints it. */
@ExtendWith(OutputCaptureExtension.class)
class TokenSecrecyTest {

    private static final String TOKEN = McpTestSettings.TOKEN;

    private static LedgerMcpProperties properties() {
        return new LedgerMcpProperties(TOKEN, List.of("localhost"), List.of(), DataSize.ofKilobytes(1),
                Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("MCC-015: the right token is accepted")
    void theRightTokenIsAccepted() {
        Authentication result = new AgentTokenProvider(properties())
                .authenticate(new BearerTokenAuthenticationToken(TOKEN));

        assertThat(result.isAuthenticated()).isTrue();
    }

    /** A token that differs from the right one, in the way that the name says. */
    private record Wrong(String name, String token) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Wrong> wrongTokens() {
        int last = TOKEN.length() - 1;
        return Stream.of(
                new Wrong("first character differs", "X" + TOKEN.substring(1)),
                new Wrong("last character differs", TOKEN.substring(0, last) + "X"),
                new Wrong("middle character differs", TOKEN.substring(0, 16) + "X" + TOKEN.substring(17)),
                new Wrong("every character differs", "z".repeat(TOKEN.length())),
                new Wrong("one character shorter", TOKEN.substring(0, last)),
                new Wrong("one character longer", TOKEN + "X"),
                new Wrong("only the right token as a prefix", TOKEN + TOKEN));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongTokens")
    @DisplayName("MCC-015: a token that differs, also in one character of the same length, is refused without "
            + "telling any token")
    void aDifferingTokenIsRefused(Wrong wrong) {
        AgentTokenProvider provider = new AgentTokenProvider(properties());

        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(wrong.token())))
                .isInstanceOf(BadCredentialsException.class).hasMessageNotContaining(wrong.token())
                .hasMessageNotContaining(TOKEN).hasNoCause();
    }

    /** The settings of the server bound from the property, and the provider that is made from them. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LedgerMcpProperties.class)
    static class TokenConfiguration {

        @Bean
        AgentTokenProvider agentTokenProvider(LedgerMcpProperties properties) {
            return new AgentTokenProvider(properties);
        }
    }

    /** Every text the failure of a start can show: the messages of its causes and what the failure analyzers say. */
    private static String everythingShownAbout(Throwable failure) {
        StringBuilder shown = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            shown.append(cause.getMessage()).append('\n');
        }
        List<FailureAnalyzer> analyzers = SpringFactoriesLoader.forDefaultResourceLocation(
                TokenSecrecyTest.class.getClassLoader()).load(FailureAnalyzer.class,
                        ArgumentResolver.of(BeanFactory.class, new DefaultListableBeanFactory())
                                .and(Environment.class, new StandardEnvironment()));
        for (FailureAnalyzer analyzer : analyzers) {
            FailureAnalysis analysis = analyzer.analyze(failure);
            if (analysis != null) {
                shown.append(analysis.getDescription()).append('\n').append(analysis.getAction()).append('\n');
            }
        }
        return shown.toString();
    }

    static Stream<String> unusableTokens() {
        return Stream.of(TOKEN.substring(0, 15), "abc123xyz"," ".repeat(20));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableTokens")
    @DisplayName("MCC-027: a token that is too short or blank stops the start, names the key and prints no token")
    void anUnusableTokenStopsTheStartWithoutPrintingIt(String token, CapturedOutput output) {
        new ApplicationContextRunner().withUserConfiguration(TokenConfiguration.class)
                .withPropertyValues("ledger.mcp.token=" + token).run(context -> {
                    assertThat(context).hasFailed();
                    String shown = everythingShownAbout(context.getStartupFailure());
                    assertThat(shown).contains("ledger.mcp.token");
                    assertThat(shown).doesNotContain("Value: ").doesNotContain("\"" + token + "\"");
                    if (!token.isBlank()) {
                        assertThat(shown).doesNotContain(token);
                        assertThat(output.getAll()).doesNotContain(token);
                    }
                });
    }

    @Test
    @DisplayName("MCC-027: a token of 16 characters starts")
    void aTokenOfTheLeastLengthStarts() {
        new ApplicationContextRunner().withUserConfiguration(TokenConfiguration.class)
                .withPropertyValues("ledger.mcp.token=" + TOKEN.substring(0, 16))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("MCC-015: the settings print without the token")
    void thePropertiesDoNotPrintTheToken() {
        LedgerMcpProperties properties = properties();

        assertThat(properties.toString()).doesNotContain(TOKEN).contains("token=<hidden>");
        assertThat(String.valueOf(properties)).doesNotContain(TOKEN);
    }
}
