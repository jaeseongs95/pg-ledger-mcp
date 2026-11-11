package org.pgledger.cli.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

class SecretNameTest {

    @ParameterizedTest(name = "[{index}] refused")
    @DisplayName("SEC-005 names that are not ^[a-z][a-z0-9._-]{0,63}$ are refused")
    @ValueSource(strings = {"../x", "a/b", "a\\b", "", "Upper", "A", ".hidden", "-lead", "9lead", "a b", "a\n", "nameé"})
    @NullSource
    void sec005_refusesInvalidNames(String name) {
        assertThatThrownBy(() -> new SecretName(name))
                .isInstanceOfSatisfying(FailureException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_SECRET_NAME));
    }

    @Test
    @DisplayName("SEC-005 a 200 character name is refused and the error carries no echo of it")
    void sec005_refusesLongName() {
        String name = "a".repeat(200);
        assertThatThrownBy(() -> new SecretName(name))
                .isInstanceOfSatisfying(FailureException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_SECRET_NAME);
                    assertThat(e.arguments()).isEmpty();
                });
    }

    @ParameterizedTest(name = "[{index}] accepted")
    @DisplayName("SEC-005 accepted names include cloudflare-api-token")
    @ValueSource(strings = {"cloudflare-api-token", "a", "ledger.mcp.bearer-token", "db_password", "x9.-_"})
    void sec005_acceptsValidNames(String name) {
        assertThatCode(() -> new SecretName(name)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-005 the length limit is 64 characters")
    void sec005_lengthBoundary() {
        assertThatCode(() -> new SecretName("a".repeat(64))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new SecretName("a".repeat(65))).isInstanceOf(FailureException.class);
    }
}
