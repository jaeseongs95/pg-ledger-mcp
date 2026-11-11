package org.pgledger.cli.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.Console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

class ConsoleSecretInputTest {

    @Test
    @DisplayName("SEC-003 without an interactive terminal (piped stdin or no Console) the input is refused instead of read")
    void sec003_refusesWhenNotATerminal() {
        Console console = System.console();
        assumeFalse(console != null && console.isTerminal(), "test JVM is attached to a terminal");

        assertThatThrownBy(() -> new ConsoleSecretInput().read("prompt"))
                .isInstanceOfSatisfying(FailureException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_CONSOLE));
    }
}
