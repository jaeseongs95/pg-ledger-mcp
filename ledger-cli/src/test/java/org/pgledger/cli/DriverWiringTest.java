package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresPosixPermissions;
import org.pgledger.cli.support.ScriptedSecretInput;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-027, CLI-028, CLI-032: the database beans exist only when a usable JDBC driver is registered, and the settings come
 * from the registration file and the secret store through {@code spring.config.import}.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class })
class DriverWiringTest {

    private static final String PASSWORD_NAME = "ledger.jdbc.password";
    private static final String PASSWORD = "Pw-9f3-unique-database-password-é";

    @TempDir
    Path tmp;

    private Path secrets() {
        return tmp.resolve("secrets");
    }

    private static String slashed(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    @Test
    @DisplayName("CLI-027 a registration whose jar does not exist (read through spring.config.import) is not a usable driver: key-register is DRIVER_NOT_CONFIGURED (exit 1) naming driver-register, the reason is logged, no database bean is created")
    void unusableRegistrationIsRefused(Cli cli, CapturedOutput captured) throws IOException {
        Path registration = tmp.resolve("jdbc-driver.yaml");
        Path missingJar = tmp.resolve("missing-driver.jar");
        Files.writeString(registration, """
                ledger:
                  jdbc:
                    driver:
                      jar: '%s'
                      class-name: 'org.h2.Driver'
                      sha256: '%s'
                      registered-at: '2026-11-11T00:00:00Z'
                """.formatted(slashed(missingJar), "0".repeat(64)));
        KeyPair pair = pair();

        Outcome result = cli.withArguments("--spring.config.import=optional:file:" + slashed(registration))
                .run(secrets(), null, new RecordingOutput(), "key-register", "claude:session-1",
                        PublicKeys.encode(pair.getPublic()), PublicKeys.fingerprint(pair.getPublic()));

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.DRIVER_NOT_CONFIGURED);
        assertThat(captured.getAll()).contains("driver-register").contains("missing-driver.jar");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("CLI-028 secret-set still works when no driver is registered: exit 0, the secret is stored")
    void secretSetWorksWithoutADriver(Cli cli) throws IOException {
        ScriptedSecretInput input = new ScriptedSecretInput(PASSWORD.toCharArray());

        Outcome result = cli.run(secrets(), input, new RecordingOutput(), "secret-set", PASSWORD_NAME);

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(Files.readString(secrets().resolve(PASSWORD_NAME))).isEqualTo(PASSWORD);
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("CLI-032 with the default spring.config.import the secret store is read as a configtree: ledger.jdbc.password is in the environment and no output shows it")
    void theSecretStoreIsPartOfTheEnvironment(Cli cli, CapturedOutput captured) {
        assertThat(cli.run(secrets(), new ScriptedSecretInput(PASSWORD.toCharArray()), new RecordingOutput(),
                "secret-set", PASSWORD_NAME).exitCode()).isZero();

        Outcome result = cli.run(secrets(), null, new RecordingOutput(), "key-register");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.context().getEnvironment().getProperty(PASSWORD_NAME)).isEqualTo(PASSWORD);
        assertThat(captured.getAll()).doesNotContain(PASSWORD);
    }

    private static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
