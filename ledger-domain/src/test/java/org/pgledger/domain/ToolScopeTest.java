package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.context.ToolScope;

/** W7-TS-01: what the current request called is an {@link Optional}, not a {@code null}. */
class ToolScopeTest {

    private static final String DIGEST = "a".repeat(64);

    @Test
    @DisplayName("W7-TS-01 outside a tool call the tool and the digest are empty")
    void outsideACall() {
        assertThat(ToolScope.current()).isEmpty();
        assertThat(ToolScope.argumentsDigest()).isEmpty();
    }

    @Test
    @DisplayName("W7-TS-01 inside a tool call with a tool and a digest both are present")
    void insideACall() {
        ScopedValue.where(ToolScope.CURRENT, "ledger_save").where(ToolScope.ARGUMENTS_DIGEST, DIGEST).run(() -> {
            assertThat(ToolScope.current()).contains("ledger_save");
            assertThat(ToolScope.argumentsDigest()).contains(DIGEST);
        });
    }

    @Test
    @DisplayName("W7-TS-01 inside a tool call whose arguments have no digest (a call without attestation) the tool is present and the digest is empty")
    void aCallWithoutADigest() {
        ScopedValue.where(ToolScope.CURRENT, "ledger_save").where(ToolScope.ARGUMENTS_DIGEST, (String) null).run(() -> {
            assertThat(ToolScope.current()).contains("ledger_save");
            assertThat(ToolScope.argumentsDigest()).isEmpty();
        });
    }
}
