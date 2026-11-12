package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresOwnerOnlyStorage;
import org.pgledger.cli.support.ScriptedSecretInput;
import org.pgledger.testing.OwnerOnlyFiles;

/**
 * INS-039: an installation that stopped after the driver was registered (the registration file exists, the database password
 * is not stored yet, the database may even be down) is repaired by running the installer again: the commands that do not use
 * the ledger database ({@code secret-set}, {@code token-generate}, {@code render-deploy}, {@code mcp-check}) start whatever is
 * registered, and only {@code migrate} and the other ledger commands connect.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class InterruptedInstallTest {

    /** A database that nobody listens at: any connection of a command that makes the database beans fails. */
    private static final String DOWN = "jdbc:h2:tcp://localhost:1/none";

    @TempDir
    Path directory;

    private Path root;
    private Path file;
    private Path drivers;

    @BeforeEach
    void layout(Cli cli) throws Exception {
        root = directory.toRealPath();
        drivers = OwnerOnlyFiles.restrict(Files.createDirectories(root.resolve("drivers"))).toRealPath();
        file = root.resolve("config").resolve("jdbc-driver.yaml");
        Path jar = OwnerOnlyFiles.copy(h2Jar(), drivers.resolve("h2.jar")).toRealPath();
        Outcome registered = configured(cli).asProcessUser("alice").run(root.resolve("secrets"), null, new RecordingOutput(),
                "driver-register", "--jar=" + jar, "--class=org.h2.Driver");
        assertThat(registered.exitCode()).as("the driver is registered").isZero();
    }

    private static Path h2Jar() throws URISyntaxException {
        return Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String slashed(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** The state of the interrupted installation: the registration is read as it is at the next start, the database is down, no password. */
    private Cli configured(Cli cli) {
        // the file does not exist yet when the driver is registered: the import is optional
        return cli.withArguments("--ledger.cli.driver.file=" + slashed(file), "--ledger.cli.driver.directory=" + slashed(drivers),
                "--ledger.jdbc.url=" + DOWN, "--spring.config.import=optional:file:" + slashed(file));
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("INS-039 with a registered driver, no stored password and a database that is down, secret-set and token-generate still run and store (a re-run of the installer repairs the installation)")
    void ins039_secretCommandsDoNotNeedTheDatabase(Cli cli) throws Exception {
        Path secrets = root.resolve("secrets");
        ScriptedSecretInput input = new ScriptedSecretInput("the-database-password".toCharArray());

        Outcome password = configured(cli).run(secrets, input, new RecordingOutput(), "secret-set", "ledger.jdbc.password");
        Outcome token = configured(cli).run(secrets, null, new RecordingOutput(), "token-generate", "ledger.mcp.token");

        assertThat(password.exitCode()).isZero();
        assertThat(password.failure()).isNull();
        assertThat(token.exitCode()).isZero();
        assertThat(Files.readString(secrets.resolve("ledger.jdbc.password"))).isEqualTo("the-database-password");
        assertThat(Files.readString(secrets.resolve("ledger.mcp.token"))).hasSize(43);
    }

    @Test
    @DisplayName("INS-039 a command of the ledger (migrate) still needs the database: with it down the run is refused (exit 1), never half done")
    void ins039_aLedgerCommandStillConnects(Cli cli) {
        Outcome result = configured(cli).run(root.resolve("secrets"), null, new RecordingOutput(), "migrate");

        assertThat(result.failure()).as("the application does not start: the database is not reachable").isNotNull();
    }

    @Test
    @DisplayName("INS-039 usesDatabase: secret-set, token-generate, secret-import-1x, mcp-check, render-deploy, prepare-host and seal-host never use the ledger database, every other command does")
    void ins039_whichCommandsUseTheDatabase() {
        for (CliCommand command : CliCommand.values()) {
            boolean without = command == CliCommand.SECRET_SET || command == CliCommand.TOKEN_GENERATE
                    || command == CliCommand.SECRET_IMPORT_1X || command == CliCommand.MCP_CHECK
                    || command == CliCommand.RENDER_DEPLOY || command == CliCommand.PREPARE_HOST
                    || command == CliCommand.SEAL_HOST;
            assertThat(command.usesDatabase()).as(command.cliName()).isEqualTo(!without);
        }
    }
}
