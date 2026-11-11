package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.Console;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresNonPosixFileSystem;
import org.pgledger.cli.support.RequiresOwnerOnlyStorage;
import org.pgledger.cli.support.RequiresPosixPermissions;
import org.pgledger.cli.support.ScriptedSecretInput;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.ClassUtils;

@ExtendWith({ OutputCaptureExtension.class, CliExtension.class })
class SecretSetCommandTest {

    private static final String VALUE = "Zq9-unique-secret-value-é한";
    private static final String NAME = "cloudflare-api-token";
    private static final String[] COMMAND_NAMES = Arrays.stream(CliCommand.values()).map(CliCommand::cliName).toArray(String[]::new);

    /** One command line and what the standard path must make of it. */
    record Refusal(String label, String[] line, ExitStatus status, ErrorCode code) {

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Refusal> usageErrors() {
        return Stream.of(
                new Refusal("no argument", new String[0], ExitStatus.USAGE, ErrorCode.MISSING_COMMAND),
                new Refusal("secret-get", new String[] { "secret-get", NAME }, ExitStatus.USAGE, ErrorCode.UNKNOWN_COMMAND),
                new Refusal("secret-show", new String[] { "secret-show", NAME }, ExitStatus.USAGE, ErrorCode.UNKNOWN_COMMAND),
                new Refusal("secret-list", new String[] { "secret-list", NAME }, ExitStatus.USAGE, ErrorCode.UNKNOWN_COMMAND),
                new Refusal("category-add", new String[] { "category-add", NAME }, ExitStatus.USAGE, ErrorCode.UNKNOWN_COMMAND),
                new Refusal("SECRET-SET", new String[] { "SECRET-SET", NAME }, ExitStatus.USAGE, ErrorCode.UNKNOWN_COMMAND),
                new Refusal("secret-set without a name", new String[] { "secret-set" }, ExitStatus.USAGE, ErrorCode.MISSING_ARGUMENT),
                new Refusal("secret-set with a surplus argument", new String[] { "secret-set", NAME, "extra" },
                        ExitStatus.USAGE, ErrorCode.TOO_MANY_ARGUMENTS),
                new Refusal("secret-set with an unknown option (CLI-029)", new String[] { "secret-set", NAME, "--force" },
                        ExitStatus.USAGE, ErrorCode.UNKNOWN_OPTION));
    }

    @TempDir
    Path tmp;

    private Path directory() {
        return tmp.resolve("secrets");
    }

    private static boolean allZero(char[] chars) {
        for (char c : chars) {
            if (c != 0) {
                return false;
            }
        }
        return true;
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-001 secret-set stores the hidden input, prints only the name and path, and clears the char array")
    void sec001_secretSetStoresAndPrintsNameAndPathOnly(Cli cli) throws IOException {
        char[] typed = VALUE.toCharArray();
        ScriptedSecretInput input = new ScriptedSecretInput(typed);
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.run(directory(), input, output, "secret-set", NAME);

        Path file = directory().resolve(NAME);
        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(Files.readString(file)).isEqualTo(VALUE);
        assertThat(input.reads()).isEqualTo(1);
        assertThat(input.lastPrompt()).contains(NAME);
        assertThat(output.info()).contains(NAME).contains(file.toString()).doesNotContain(VALUE);
        assertThat(allZero(typed)).as("the char array is wiped after use").isTrue();
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-003 the value never reaches the console output or the log, on success")
    void sec003_valueIsNotEchoedOnSuccess(Cli cli, CapturedOutput captured) {
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());

        Outcome result = cli.run(directory(), input, null, "secret-set", NAME);

        assertThat(result.exitCode()).isZero();
        assertThat(captured.getAll()).contains(NAME).doesNotContain(VALUE);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-003 the value never reaches the console output or the log, on failure")
    void sec003_valueIsNotEchoedOnFailure(Cli cli, CapturedOutput captured) {
        char[] unencodable = (VALUE + '\ud800').toCharArray();
        ScriptedSecretInput input = new ScriptedSecretInput(unencodable);

        Outcome result = cli.run(directory(), input, null, "secret-set", NAME);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.INVALID_ENCODING);
        assertThat(captured.getAll()).doesNotContain(VALUE).doesNotContain("Zq9");
        assertThat(allZero(unencodable)).isTrue();
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-003 without a terminal the real Console input refuses (exit 1) and creates nothing")
    void sec003_refusesWithoutTerminal(Cli cli) {
        Console console = System.console();
        assumeFalse(console != null && console.isTerminal(), "test JVM is attached to a terminal");

        Outcome result = cli.run(directory(), null, new RecordingOutput(), "secret-set", NAME);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.NO_CONSOLE);
        assertThat(Files.exists(directory())).isFalse();
    }

    @ParameterizedTest(name = "[{index}] empty input")
    @ValueSource(ints = { 0, 1 })
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-003 an empty or missing input is refused (exit 1) and stores nothing")
    void sec003_refusesEmptyInput(int variant, Cli cli) {
        ScriptedSecretInput input = new ScriptedSecretInput(variant == 0 ? new char[0] : null);

        Outcome result = cli.run(directory(), input, new RecordingOutput(), "secret-set", NAME);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.EMPTY_SECRET);
        assertThat(input.reads()).isEqualTo(1);
        assertThat(Files.exists(directory().resolve(NAME))).isFalse();
    }

    @Test
    @DisplayName("SEC-005 an invalid name is refused (exit 1) before any prompt and is not echoed")
    void sec005_invalidNameIsRefusedBeforePrompt(Cli cli, CapturedOutput captured) {
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());

        Outcome result = cli.run(directory(), input, new RecordingOutput(), "secret-set", "../x");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.INVALID_SECRET_NAME);
        assertThat(input.reads()).isZero();
        assertThat(captured.getAll()).doesNotContain("../x");
        assertThat(Files.exists(directory())).isFalse();
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("SEC-016 on a file system with ACLs and without POSIX permissions secret-set stores the secret under an owner-only ACL (changed by install: it was refused before)")
    void sec016_aclFileSystemStoresTheSecret(Cli cli, CapturedOutput captured) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("acl"), "the default file system has no ACLs");
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());

        Outcome result = cli.run(directory(), input, new RecordingOutput(), "secret-set", NAME);

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(input.reads()).isEqualTo(1);
        assertThat(Files.readString(directory().resolve(NAME))).isEqualTo(VALUE);
        assertThat(captured.getAll()).doesNotContain(VALUE);
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-006 secret-set refuses a loose existing directory (exit 1) naming path and mode, before any prompt")
    void sec006_looseDirectoryIsRefusedBeforePrompt(Cli cli, CapturedOutput captured) throws IOException {
        Files.createDirectory(directory());
        Files.setPosixFilePermissions(directory(), PosixFilePermissions.fromString("rwxr-xr-x"));
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());

        Outcome result = cli.run(directory(), input, new RecordingOutput(), "secret-set", NAME);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.LOOSE_PERMISSIONS);
        assertThat(input.reads()).isZero();
        assertThat(captured.getAll()).contains(directory().toString()).contains("rwxr-xr-x");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("usageErrors")
    @DisplayName("SEC-004 / CLI-001 read-back, unknown commands and wrong argument counts are usage errors (exit 2) with the usage text, before any prompt")
    void usageErrorsExitWithTwoAndPrintTheUsage(Refusal refusal, Cli cli, CapturedOutput captured) {
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.run(directory(), input, output, refusal.line());

        assertThat(result.exitCode()).isEqualTo(refusal.status().code());
        assertThat(result.code()).contains(refusal.code());
        assertThat(input.reads()).isZero();
        assertThat(output.info()).isEmpty();
        assertThat(captured.getAll()).contains(COMMAND_NAMES);
        assertThat(Files.exists(directory())).isFalse();
    }

    @Test
    @DisplayName("SEC-004 the command enum has no command that reads a secret back")
    void sec004_noCommandReadsASecretBack() {
        assertThat(Arrays.stream(CliCommand.values()).map(CliCommand::cliName))
                .doesNotContain("secret-get", "secret-show", "secret-list");
    }

    @Test
    @DisplayName("CLI-002 the application is web-less: no web application type, no servlet or reactive classes, context closed on exit")
    void cli002_applicationIsWebLess(Cli cli) {
        Outcome result = cli.run(directory(), new ScriptedSecretInput(null), new RecordingOutput(), "secret-get");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.context().getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(result.context().getClass().getName()).doesNotContain("Web");
        assertThat(ClassUtils.isPresent("jakarta.servlet.Servlet", null)).isFalse();
        assertThat(ClassUtils.isPresent("org.springframework.web.reactive.DispatcherHandler", null)).isFalse();
        assertThat(result.context().isActive()).isFalse();
    }
}
