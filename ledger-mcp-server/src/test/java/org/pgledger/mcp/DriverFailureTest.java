package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgledger.mcp.support.McpTestSettings;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Without a registered JDBC driver the server does not start, and says why: the failure analyzer of the server
 * prints the token and what to do. The application is started for real, as in a deployment (no {@code embedded}
 * profile, no driver registration, only the token).
 */
@ExtendWith(OutputCaptureExtension.class)
class DriverFailureTest {

    @Test
    @DisplayName("DRV (server start): without a registered driver the start fails with DRIVER_NOT_CONFIGURED and the "
            + "hint to register one")
    void theStartFailsWithTheHint(CapturedOutput output) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(LedgerMcpServerApplication.class)
                .web(WebApplicationType.NONE).properties(McpTestSettings.TOKEN_PROPERTY,
                        McpTestSettings.QUIET_BANNER, McpTestSettings.NO_JMX);

        assertThatThrownBy(application::run).isInstanceOf(RuntimeException.class);

        assertThat(output.getAll()).contains("DRIVER_NOT_CONFIGURED")
                .contains("No JDBC driver is registered")
                .contains("Register a JDBC driver (the driver-register command of ledger-cli) and start the server again.");
    }
}
