package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-012: no command prints or logs a secret. The database password, the password inside a JDBC URL and the bearer token are
 * given to the commands (as the settings they are), the commands run in the ways that print the most (refusals, the debug
 * log of Spring Boot), and the captured standard streams, the logs and what the commands printed are searched for them.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class })
class OutputSecrecyTest {

    private static final String PASSWORD = "Db-Password-4711-unique";
    private static final String DSN_PASSWORD = "Dsn-Password-0815-unique";
    private static final String TOKEN = "Bearer-Token-0123456789-unique-value";
    private static final String SOURCE_PASSWORD = "Legacy-Source-Password-2468-unique";
    private static final List<String> SECRETS = List.of(PASSWORD, DSN_PASSWORD, TOKEN, SOURCE_PASSWORD);

    @TempDir
    Path tmp;

    private Cli withSecrets(Cli cli) {
        return cli.withArguments("--ledger.jdbc.password=" + PASSWORD, "--ledger.mcp.token=" + TOKEN,
                "--ledger.import.source.password=" + SOURCE_PASSWORD, "--ledger.import.source.url=jdbc:postgresql://old.example/ledger?password=" + SOURCE_PASSWORD,
                "--ledger.jdbc.url=jdbc:postgresql://db.example/ledger?user=ledger&password=" + DSN_PASSWORD);
    }

    private static void assertNoSecret(CapturedOutput captured, RecordingOutput output) {
        for (String secret : SECRETS) {
            assertThat(captured.getAll()).as("the log and the streams hold %s", secret).doesNotContain(secret);
            assertThat(output.info()).as("the output holds %s", secret).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("CLI-012 a command that is refused (no driver, a usage error, an unreachable MCP server) prints no password, no DSN password and no token, with and without --debug")
    void refusalsAndDebugLogsHoldNoSecret(Cli cli, CapturedOutput captured) {
        RecordingOutput output = new RecordingOutput();
        List<String[]> lines = List.of(new String[] { "migrate" }, new String[] { "grants" }, new String[] { "import-legacy" },
                new String[] { "import-legacy", "--verify-only", "--debug" },
                new String[] { "project-add", "only-one-operand" },
                new String[] { "mcp-check", "--url=http://127.0.0.1:1/mcp" },
                new String[] { "mcp-check", "--url=http://127.0.0.1:1/mcp", "--debug" },
                new String[] { "migrate", "--debug" }, new String[] { "driver-register", "--jar=" + tmp.resolve("none.jar"),
                    "--class=org.h2.Driver", "--ledger.cli.driver.directory=" + tmp, "--debug" });

        for (String[] line : lines) {
            Outcome result = withSecrets(cli).run(tmp.resolve("secrets"), null, output, line);

            assertThat(result.exitCode()).as(String.join(" ", line)).isNotZero();
        }

        assertNoSecret(captured, output);
    }

    @Test
    @DisplayName("CLI-012 commands that succeed on a database (project-add, grants, codes) print no secret either")
    void successfulCommandsHoldNoSecret(Cli cli, CapturedOutput captured) {
        RecordingOutput output = new RecordingOutput();
        try (TestLedgerDatabase database = TestLedgerDatabase.migrated()) {
            for (String[] line : List.of(new String[] { "project-add", "e2e", "E2E", "End-to-end test project" },
                    new String[] { "codes" }, new String[] { "grants" })) {
                Outcome result = withSecrets(cli).withDatabase(database.dataSource()).asProcessUser("alice")
                        .run(tmp.resolve("secrets"), null, output, line);

                assertThat(result.exitCode()).as(String.join(" ", line)).isZero();
            }
        }

        assertNoSecret(captured, output);
    }
}
