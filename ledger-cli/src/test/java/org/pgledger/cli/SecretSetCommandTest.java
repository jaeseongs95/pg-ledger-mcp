package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.cli.support.CliHarness;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.ScriptedSecretInput;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.ClassUtils;

@ExtendWith(OutputCaptureExtension.class)
class SecretSetCommandTest {

    private static final String VALUE = "Zq9-unique-secret-value-é한";

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
    @EnabledOnOs(LINUX)
    @DisplayName("SEC-001 secret-set stores the hidden input, prints only the name and path, and clears the char array")
    void sec001_secretSetStoresAndPrintsNameAndPathOnly() throws IOException {
        char[] typed = VALUE.toCharArray();
        ScriptedSecretInput input = new ScriptedSecretInput(typed);
        RecordingOutput output = new RecordingOutput();

        CliHarness.Result result = CliHarness.run(directory(), input, output, "secret-set", "cloudflare-api-token");

        Path file = directory().resolve("cloudflare-api-token");
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(file)).isEqualTo(VALUE);
        assertThat(input.reads()).isEqualTo(1);
        assertThat(input.lastPrompt()).contains("cloudflare-api-token");
        assertThat(output.info()).contains("cloudflare-api-token").contains(file.toString()).doesNotContain(VALUE);
        assertThat(output.error()).isEmpty();
        assertThat(allZero(typed)).as("the char array is wiped after use").isTrue();
    }

    @Test
    @EnabledOnOs(LINUX)
    @DisplayName("SEC-003 the value never reaches the console output or the log, on success")
    void sec003_valueIsNotEchoedOnSuccess(CapturedOutput captured) {
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());

        CliHarness.Result result = CliHarness.run(directory(), input, null, "secret-set", "cloudflare-api-token");

        assertThat(result.exitCode()).isZero();
        assertThat(captured.getAll()).contains("cloudflare-api-token").doesNotContain(VALUE);
    }

    @Test
    @EnabledOnOs(LINUX)
    @DisplayName("SEC-003 the value never reaches the console output or the log, on failure")
    void sec003_valueIsNotEchoedOnFailure(CapturedOutput captured) {
        char[] unencodable = (VALUE + '\ud800').toCharArray();
        ScriptedSecretInput input = new ScriptedSecretInput(unencodable);

        CliHarness.Result result = CliHarness.run(directory(), input, null, "secret-set", "cloudflare-api-token");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(captured.getAll()).doesNotContain(VALUE).doesNotContain("Zq9");
        assertThat(allZero(unencodable)).isTrue();
    }

    @Test
    @DisplayName("SEC-003 without a terminal the real Console input refuses (exit 1) and creates nothing")
    void sec003_refusesWithoutTerminal() {
        Console console = System.console();
        assumeFalse(console != null && console.isTerminal(), "test JVM is attached to a terminal");
        RecordingOutput output = new RecordingOutput();

        CliHarness.Result result = CliHarness.run(directory(), null, output, "secret-set", "cloudflare-api-token");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(output.error()).isNotBlank();
        assertThat(Files.exists(directory())).isFalse();
    }

    @ParameterizedTest(name = "[{index}] empty input")
    @ValueSource(ints = {0, 1})
    @EnabledOnOs(LINUX)
    @DisplayName("SEC-003 an empty or missing input is refused (exit 1) and stores nothing")
    void sec003_refusesEmptyInput(int variant) {
        ScriptedSecretInput input = new ScriptedSecretInput(variant == 0 ? new char[0] : null);
        RecordingOutput output = new RecordingOutput();

        CliHarness.Result result = CliHarness.run(directory(), input, output, "secret-set", "cloudflare-api-token");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(input.reads()).isEqualTo(1);
        assertThat(Files.exists(directory().resolve("cloudflare-api-token"))).isFalse();
    }

    @Test
    @DisplayName("SEC-005 an invalid name is refused (exit 1) before any prompt and is not echoed")
    void sec005_invalidNameIsRefusedBeforePrompt() {
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());
        RecordingOutput output = new RecordingOutput();

        CliHarness.Result result = CliHarness.run(directory(), input, output, "secret-set", "../x");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(input.reads()).isZero();
        assertThat(output.error()).doesNotContain("../x");
        assertThat(Files.exists(directory())).isFalse();
    }

    @Test
    @EnabledOnOs(WINDOWS)
    @DisplayName("SEC-012 on Windows secret-set fails with an explicit message before reading any input")
    void sec012_windowsRefusesBeforePrompt() {
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());
        RecordingOutput output = new RecordingOutput();

        CliHarness.Result result = CliHarness.run(directory(), input, output, "secret-set", "cloudflare-api-token");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(input.reads()).isZero();
        assertThat(output.error()).isNotBlank();
        assertThat(Files.exists(directory())).isFalse();
    }

    @Test
    @EnabledOnOs(LINUX)
    @DisplayName("SEC-006 secret-set refuses a loose existing directory (exit 1) naming path and mode, before any prompt")
    void sec006_looseDirectoryIsRefusedBeforePrompt() throws IOException {
        Files.createDirectory(directory());
        Files.setPosixFilePermissions(directory(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());
        RecordingOutput output = new RecordingOutput();

        CliHarness.Result result = CliHarness.run(directory(), input, output, "secret-set", "cloudflare-api-token");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(input.reads()).isZero();
        assertThat(output.error()).contains(directory().toString()).contains("rwxr-xr-x");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"secret-get", "secret-show", "secret-list", "category-add", "SECRET-SET"})
    @DisplayName("SEC-004 / CLI-001 read-back and unknown commands are rejected (exit 2) with the usage text")
    void sec004_unknownCommandIsAUsageError(String command) {
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());
        RecordingOutput output = new RecordingOutput();

        CliHarness.Result result = CliHarness.run(directory(), input, output, command, "cloudflare-api-token");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(input.reads()).isZero();
        assertThat(output.info()).isEmpty();
        for (CliCommand known : CliCommand.values()) {
            assertThat(output.error()).contains(known.cliName());
        }
        assertThat(Files.exists(directory())).isFalse();
    }

    @Test
    @DisplayName("SEC-004 the command enum has no command that reads a secret back")
    void sec004_noCommandReadsASecretBack() {
        assertThat(Arrays.stream(CliCommand.values()).map(CliCommand::cliName))
                .doesNotContain("secret-get", "secret-show", "secret-list");
    }

    @Test
    @DisplayName("CLI-001 no argument is a usage error (exit 2) and the usage lists every command")
    void cli001_noArgumentIsAUsageError() {
        RecordingOutput output = new RecordingOutput();

        CliHarness.Result result = CliHarness.run(directory(), new ScriptedSecretInput(null), output);

        assertThat(result.exitCode()).isEqualTo(2);
        for (CliCommand known : CliCommand.values()) {
            assertThat(output.error()).contains(known.cliName());
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"secret-set", "secret-set cloudflare-api-token extra"})
    @DisplayName("CLI-001 a missing or surplus argument is a usage error (exit 2) before any prompt")
    void cli001_wrongArgumentCountIsAUsageError(String line) {
        ScriptedSecretInput input = new ScriptedSecretInput(VALUE.toCharArray());
        RecordingOutput output = new RecordingOutput();

        CliHarness.Result result = CliHarness.run(directory(), input, output, line.split(" "));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(input.reads()).isZero();
        assertThat(output.error()).contains("secret-set");
    }

    @Test
    @DisplayName("CLI-002 the application is web-less: no web application type, no servlet or reactive classes, context closed on exit")
    void cli002_applicationIsWebLess() {
        CliHarness.Result result = CliHarness.run(directory(), new ScriptedSecretInput(null), new RecordingOutput(), "secret-get");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.closedContext().getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(result.closedContext().getClass().getName()).doesNotContain("Web");
        assertThat(ClassUtils.isPresent("jakarta.servlet.Servlet", null)).isFalse();
        assertThat(ClassUtils.isPresent("org.springframework.web.reactive.DispatcherHandler", null)).isFalse();
        assertThat(result.closedContext().isActive()).isFalse();
    }
}
