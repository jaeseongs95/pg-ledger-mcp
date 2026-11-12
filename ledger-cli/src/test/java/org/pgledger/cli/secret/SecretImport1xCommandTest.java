package org.pgledger.cli.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresOwnerOnlyStorage;
import org.pgledger.cli.support.ScriptedSecretInput;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * SEC-027: {@code secret-import-1x (--env=<file> | --stdin)} moves the two secrets of a 1.x installation into the secret store
 * ({@code DBHUB_AUTH_TOKEN} to {@code ledger.mcp.token} and {@code ledger.admin.agent-token}, {@code PGLEDGER_ADMIN_TOKEN} to
 * {@code ledger.admin.token}). No test registers a driver or a database: the command does not use one. The values are made up.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class SecretImport1xCommandTest {

    private static final String AGENT = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String ADMIN = "0f9e8d7c6b5a49382716051a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e";
    private static final String DATABASE_PASSWORD = "db-password-of-the-1x-installation";

    @TempDir
    Path tmp;

    private Path directory() {
        return tmp.resolve("secrets");
    }

    private static String environment(String agent, String admin) {
        return "# written by pg-ledger-mcp deploy/install.sh\nPGLEDGER_DB=ledger\nPGLEDGER_AUTHOR_PASSWORD=" + DATABASE_PASSWORD
                + "\n" + (agent == null ? "" : "DBHUB_AUTH_TOKEN=" + agent + "\n")
                + (admin == null ? "" : "PGLEDGER_ADMIN_TOKEN=" + admin + "\n");
    }

    private Path envFile(String text) throws IOException {
        Path file = tmp.resolve("env");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private String stored(String name) throws IOException {
        return Files.readString(directory().resolve(name));
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 --env: the agent token becomes ledger.mcp.token and ledger.admin.agent-token, the admin token ledger.admin.token; the other settings are not carried over; only names are printed")
    void sec027_importsFromAFile(Cli cli, CapturedOutput captured) throws IOException {
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.run(directory(), null, output, "secret-import-1x", "--env=" + envFile(environment(AGENT, ADMIN)));

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(stored("ledger.mcp.token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.token")).isEqualTo(ADMIN);
        assertThat(directory().toFile().list()).containsExactlyInAnyOrder("ledger.mcp.token", "ledger.admin.agent-token",
                "ledger.admin.token");
        assertThat(output.info()).contains("ledger.mcp.token", "ledger.admin.agent-token", "ledger.admin.token",
                "DBHUB_AUTH_TOKEN", "PGLEDGER_ADMIN_TOKEN");
        assertThat(output.info() + captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN).doesNotContain(DATABASE_PASSWORD);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 --stdin: the same, from the text on standard input (the file of 1.x belongs to root, so the installer opens it and pipes it in)")
    void sec027_importsFromStandardInput(Cli cli, CapturedOutput captured) throws IOException {
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.withStandardInput(environment(AGENT, ADMIN)).run(directory(), null, output, "secret-import-1x",
                "--stdin");

        assertThat(result.exitCode()).isZero();
        assertThat(stored("ledger.mcp.token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.token")).isEqualTo(ADMIN);
        assertThat(output.info() + captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN).doesNotContain(DATABASE_PASSWORD);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 a secret that is stored already is kept and never replaced (the run that stopped half way is completed); the output says so")
    void sec027_keepsWhatExists(Cli cli, CapturedOutput captured) throws IOException {
        String chosen = "chosen-by-the-owner-0123456789";
        assertThat(cli.run(directory(), new ScriptedSecretInput(chosen.toCharArray()), new RecordingOutput(), "secret-set",
                "ledger.mcp.token").exitCode()).isZero();
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.withStandardInput(environment(AGENT, ADMIN)).run(directory(), null, output, "secret-import-1x",
                "--stdin");

        assertThat(result.exitCode()).isZero();
        assertThat(stored("ledger.mcp.token")).as("kept").isEqualTo(chosen);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.token")).isEqualTo(ADMIN);
        assertThat(output.info()).contains("ledger.mcp.token already exists");

        RecordingOutput again = new RecordingOutput();
        assertThat(cli.withStandardInput(environment("another-agent-token-0123456789", "another-admin-token-0123456789"))
                .run(directory(), null, again, "secret-import-1x", "--stdin").exitCode()).isZero();
        assertThat(stored("ledger.admin.token")).as("a second run replaces nothing").isEqualTo(ADMIN);
        assertThat(again.info().lines().filter(line -> line.contains("already exists"))).hasSize(3);
        assertThat(captured.getAll()).doesNotContain(chosen).doesNotContain(AGENT).doesNotContain("another-");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("W7-SEC-01 a 1.0.0 environment (DBHUB_AUTH_TOKEN, no PGLEDGER_ADMIN_TOKEN): the two secrets of the agents are stored, the admin one is not, one line says so (no value), exit 0, and a second run changes nothing")
    void w7sec01_aOneZeroEnvironmentHasNoAdminToken(Cli cli, CapturedOutput captured) throws IOException {
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.withStandardInput(environment(AGENT, null)).run(directory(), null, output, "secret-import-1x", "--stdin");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(stored("ledger.mcp.token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(directory().toFile().list()).containsExactlyInAnyOrder("ledger.mcp.token", "ledger.admin.agent-token");
        assertThat(output.info().lines().filter(line -> line.contains("PGLEDGER_ADMIN_TOKEN"))).singleElement().asString()
                .contains("1.0.0").contains("ledger.admin.token").contains("generates");
        assertThat(output.info() + captured.getAll()).doesNotContain(AGENT).doesNotContain(DATABASE_PASSWORD);

        RecordingOutput again = new RecordingOutput();
        assertThat(cli.withStandardInput(environment(AGENT, null)).run(directory(), null, again, "secret-import-1x", "--stdin")
                .exitCode()).isZero();
        assertThat(directory().toFile().list()).containsExactlyInAnyOrder("ledger.mcp.token", "ledger.admin.agent-token");
        assertThat(again.info()).contains("already exists");
    }

    static Stream<Arguments> refusedValues() {
        return Stream.of(
                Arguments.of("no agent token", environment(null, ADMIN), ErrorCode.ENV_KEYS_MISSING),
                Arguments.of("no token at all", environment(null, null), ErrorCode.ENV_KEYS_MISSING),
                Arguments.of("an empty agent token", environment("", ADMIN), ErrorCode.ENV_KEYS_MISSING),
                Arguments.of("a short agent token", environment("short-token", ADMIN), ErrorCode.INVALID_1X_VALUE),
                Arguments.of("a short admin token", environment(AGENT, "short-token"), ErrorCode.INVALID_1X_VALUE),
                Arguments.of("one value for both tokens", environment(AGENT, AGENT), ErrorCode.INVALID_1X_VALUE));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("refusedValues")
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 a missing or unusable value is refused (exit 1) naming the setting, and nothing is stored, not even the secret that was fine")
    void sec027_refusesBeforeWriting(String label, String text, ErrorCode code, Cli cli, CapturedOutput captured) {
        Outcome result = cli.withStandardInput(text).run(directory(), null, new RecordingOutput(), "secret-import-1x", "--stdin");

        assertThat(result.exitCode()).as(label).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).as(label).contains(code);
        assertThat(Files.exists(directory())).as(label + ": nothing is stored").isFalse();
        assertThat(captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN).doesNotContain(DATABASE_PASSWORD);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 the refusal names the missing settings and the unusable one, never a value")
    void sec027_refusalNamesTheSetting(Cli cli) {
        Outcome missing = cli.withStandardInput(environment(null, null)).run(directory(), null, new RecordingOutput(),
                "secret-import-1x", "--stdin");
        Outcome unusable = cli.withStandardInput(environment(AGENT, "short-token")).run(directory(), null, new RecordingOutput(),
                "secret-import-1x", "--stdin");

        // the admin token is optional (W7-SEC-01): only the one that is required is named
        assertThat(missing.refusal().orElseThrow().arguments()).containsExactly("DBHUB_AUTH_TOKEN");
        assertThat(unusable.refusal().orElseThrow().arguments()).containsExactly("PGLEDGER_ADMIN_TOKEN");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 a file that is missing, not UTF-8 or larger than 64 KiB is ENV_FILE_FAILED (exit 1) naming only the kind of failure; the same text on standard input is STDIN_FAILED")
    void sec027_unreadableInput(Cli cli, CapturedOutput captured) throws IOException {
        Path notUtf8 = tmp.resolve("not-utf8.env");
        Files.write(notUtf8, new byte[] { 'A', '=', (byte) 0xC3, (byte) 0x28, '\n' });
        Path tooLarge = envFile(environment(AGENT, ADMIN) + "# " + "x".repeat(64 * 1024) + "\n");

        for (String option : new String[] { "--env=" + tmp.resolve("missing.env"), "--env=" + notUtf8, "--env=" + tooLarge }) {
            Outcome result = cli.run(directory(), null, new RecordingOutput(), "secret-import-1x", option);

            assertThat(result.exitCode()).as(option).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).as(option).contains(ErrorCode.ENV_FILE_FAILED);
        }
        Outcome piped = cli.withStandardInput(environment(AGENT, ADMIN) + "# " + "x".repeat(64 * 1024) + "\n").run(directory(), null,
                new RecordingOutput(), "secret-import-1x", "--stdin");
        assertThat(piped.code()).contains(ErrorCode.STDIN_FAILED);
        assertThat(Files.exists(directory())).isFalse();
        assertThat(captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN);
    }

    static Stream<Arguments> usageRefusals() {
        return Stream.of(
                Arguments.of(new String[] { "secret-import-1x" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "secret-import-1x", "--env=/etc/x", "--stdin" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "secret-import-1x", "--env" }, ErrorCode.MISSING_OPTION_VALUE),
                Arguments.of(new String[] { "secret-import-1x", "--stdin=yes" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "secret-import-1x", "extra", "--stdin" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "secret-import-1x", "--stdin", "--force" }, ErrorCode.UNKNOWN_OPTION));
    }

    @ParameterizedTest
    @MethodSource("usageRefusals")
    @DisplayName("SEC-027 a wrong command line is refused (exit 2) and stores nothing")
    void sec027_usageRefusals(String[] line, ErrorCode code, Cli cli) {
        Outcome result = cli.withStandardInput(environment(AGENT, ADMIN)).run(directory(), null, new RecordingOutput(), line);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(code);
        assertThat(Files.exists(directory())).isFalse();
    }
}
