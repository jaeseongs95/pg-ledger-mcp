package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * SEC-024: {@code secret-set <name> --stdin} stores the first line of standard input (the convention of
 * {@code docker login --password-stdin}), for an installation without a terminal; nothing else changes, and the value never
 * reaches an output, a log or a refusal.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class SecretSetStdinTest {

    private static final String NAME = "ledger.jdbc.password";
    private static final String VALUE = "Zq9-stdin-secret-value-é한";

    @TempDir
    Path tmp;

    private Path directory() {
        return tmp.resolve("secrets");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-024 the first line of standard input is stored without its line end; the console input is not asked; only the name and the path are printed")
    void sec024_storesTheFirstLine(Cli cli, CapturedOutput captured) throws IOException {
        ScriptedSecretInput console = new ScriptedSecretInput("typed-value".toCharArray());
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.withStandardInput(VALUE + "\r\nsecond line\n").run(directory(), console, output, "secret-set", NAME,
                "--stdin");

        Path file = directory().resolve(NAME);
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(file)).isEqualTo(VALUE);
        assertThat(console.reads()).as("the terminal is not asked").isZero();
        assertThat(output.info()).contains(NAME).contains(file.toString()).doesNotContain(VALUE);
        assertThat(captured.getAll()).doesNotContain(VALUE).doesNotContain("second line");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-024 without --stdin the input of standard input is never read: the console input answers")
    void sec024_withoutTheFlagStandardInputIsIgnored(Cli cli) throws IOException {
        ScriptedSecretInput console = new ScriptedSecretInput("typed-value".toCharArray());

        Outcome result = cli.withStandardInput("from-the-pipe\n").run(directory(), console, new RecordingOutput(), "secret-set",
                NAME);

        assertThat(result.exitCode()).isZero();
        assertThat(console.reads()).isEqualTo(1);
        assertThat(Files.readString(directory().resolve(NAME))).isEqualTo("typed-value");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-024 an empty first line or an empty input is EMPTY_SECRET (exit 1) and stores nothing")
    void sec024_emptyIsRefused(Cli cli) {
        for (String input : new String[] { "", "\n", "\r\nvalue\n" }) {
            Outcome result = cli.withStandardInput(input).run(directory(), null, new RecordingOutput(), "secret-set", NAME,
                    "--stdin");

            assertThat(result.exitCode()).as(input).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).as(input).contains(ErrorCode.EMPTY_SECRET);
            assertThat(Files.exists(directory().resolve(NAME))).as(input).isFalse();
        }
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-024 a line longer than 1024 bytes is SECRET_TOO_LONG (exit 1), stores nothing and is not echoed")
    void sec024_tooLongIsRefused(Cli cli, CapturedOutput captured) {
        String line = "Lq7" + "x".repeat(1100);

        Outcome result = cli.withStandardInput(line + "\n").run(directory(), null, new RecordingOutput(), "secret-set", NAME,
                "--stdin");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.SECRET_TOO_LONG);
        assertThat(Files.exists(directory().resolve(NAME))).isFalse();
        assertThat(captured.getAll()).doesNotContain("Lq7");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-028 --if-absent: a secret that exists is kept and nothing is read or asked (the installer is run again without typing the password again); an absent one is stored as without the flag")
    void sec028_ifAbsent(Cli cli, CapturedOutput captured) throws IOException {
        ScriptedSecretInput console = new ScriptedSecretInput("typed-first-value".toCharArray());
        RecordingOutput stored = new RecordingOutput();
        Outcome first = cli.run(directory(), console, stored, "secret-set", NAME, "--if-absent");
        ScriptedSecretInput other = new ScriptedSecretInput("typed-second-value".toCharArray());
        RecordingOutput kept = new RecordingOutput();

        Outcome second = cli.withStandardInput("from-the-pipe\n").run(directory(), other, kept, "secret-set", NAME, "--if-absent",
                "--stdin");

        assertThat(first.exitCode()).isZero();
        assertThat(console.reads()).isEqualTo(1);
        assertThat(second.exitCode()).isZero();
        assertThat(other.reads()).as("nothing is asked for a secret that exists").isZero();
        assertThat(Files.readString(directory().resolve(NAME))).as("the stored value is not replaced").isEqualTo("typed-first-value");
        assertThat(kept.info()).contains(NAME).contains("already exists").doesNotContain("typed-");
        assertThat(captured.getAll()).doesNotContain("typed-first-value").doesNotContain("from-the-pipe");
    }

    @Test
    @DisplayName("SEC-024 --stdin takes no value, and no other command declares it but the ones that take a secret")
    void sec024_theFlagIsAFlag(Cli cli) {
        Outcome withValue = cli.withStandardInput("v\n").run(directory(), null, new RecordingOutput(), "secret-set", NAME,
                "--stdin=yes");
        Outcome otherCommand = cli.withStandardInput("v\n").run(directory(), null, new RecordingOutput(), "token-generate", NAME,
                "--stdin");

        assertThat(withValue.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(withValue.code()).contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(otherCommand.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(otherCommand.code()).contains(ErrorCode.UNKNOWN_OPTION);
        assertThat(Files.exists(directory())).isFalse();
    }
}
