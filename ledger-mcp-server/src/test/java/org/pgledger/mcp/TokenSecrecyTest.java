package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.mcp.config.LedgerMcpProperties;
import org.pgledger.mcp.security.AgentTokenProvider;
import org.pgledger.mcp.support.McpTestSettings;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.util.unit.DataSize;

/** MCC-015, the parts that need no server: the comparison of the token and what prints it. */
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

    @Test
    @DisplayName("MCC-015: the settings print without the token")
    void thePropertiesDoNotPrintTheToken() {
        LedgerMcpProperties properties = properties();

        assertThat(properties.toString()).doesNotContain(TOKEN).contains("token=<hidden>");
        assertThat(String.valueOf(properties)).doesNotContain(TOKEN);
    }
}
