package org.pgledger.cli.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
import org.pgledger.cli.support.RequiresPosixPermissions;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** INS-014: {@code token-generate <name> [--print]} stores a random token once, keeps it on a re-run and shows it only when asked, once. */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class TokenGenerateCommandTest {

    @TempDir
    Path tmp;

    private Path directory() {
        return tmp.resolve("secrets");
    }

    private Outcome run(Cli cli, RecordingOutput output, String... args) {
        return cli.run(directory(), null, output, args);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("INS-014 a new token is stored as a secret file and is not printed; a second run keeps the stored value and says so")
    void ins014_storesOnceAndKeeps(Cli cli, CapturedOutput captured) throws IOException {
        RecordingOutput first = new RecordingOutput();
        Outcome created = run(cli, first, "token-generate", "ledger.mcp.token");

        assertThat(created.exitCode()).isZero();
        Path file = directory().resolve("ledger.mcp.token");
        String token = Files.readString(file);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(first.info()).contains("ledger.mcp.token").contains(file.toString()).doesNotContain(token);
        assertThat(captured.getAll()).doesNotContain(token);

        RecordingOutput second = new RecordingOutput();
        Outcome kept = run(cli, second, "token-generate", "ledger.mcp.token");

        assertThat(kept.exitCode()).isZero();
        assertThat(Files.readString(file)).as("an existing token is never replaced").isEqualTo(token);
        assertThat(second.info()).contains("already exists").doesNotContain(token);
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("INS-014 the file is owner-only: the directory 0700 and the file 0600, like secret-set")
    void ins014_ownerOnlyModes(Cli cli) throws IOException {
        run(cli, new RecordingOutput(), "token-generate", "ledger.mcp.token");

        assertThat(Files.getPosixFilePermissions(directory())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(directory().resolve("ledger.mcp.token")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("INS-014 --print shows a token that this run created, once and only then; a kept token is never shown; two tokens differ")
    void ins014_printShowsAnewTokenOnce(Cli cli) throws IOException {
        RecordingOutput created = new RecordingOutput();
        run(cli, created, "token-generate", "ledger.admin.token", "--print");
        String admin = Files.readString(directory().resolve("ledger.admin.token"));
        assertThat(created.info()).contains(admin);

        RecordingOutput kept = new RecordingOutput();
        run(cli, kept, "token-generate", "ledger.admin.token", "--print");
        assertThat(kept.info()).doesNotContain(admin);

        RecordingOutput agent = new RecordingOutput();
        run(cli, agent, "token-generate", "ledger.mcp.token", "--print");
        String mcp = Files.readString(directory().resolve("ledger.mcp.token"));
        assertThat(agent.info()).contains(mcp);
        assertThat(mcp).as("the agent token differs from the admin token").isNotEqualTo(admin);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("INS-047 --copy-to: the same token is stored under a second name once (the admin web has to know the token of the agents); a copy that exists is never replaced, a missing copy of a kept token is made; the value is never printed")
    void ins047_copyTo(Cli cli, CapturedOutput captured) throws IOException {
        RecordingOutput first = new RecordingOutput();
        Outcome created = run(cli, first, "token-generate", "ledger.mcp.token", "--copy-to=ledger.admin.agent-token");

        assertThat(created.exitCode()).isZero();
        String token = Files.readString(directory().resolve("ledger.mcp.token"));
        assertThat(Files.readString(directory().resolve("ledger.admin.agent-token"))).isEqualTo(token);
        assertThat(first.info()).contains("ledger.admin.agent-token").doesNotContain(token);

        Files.delete(directory().resolve("ledger.admin.agent-token"));
        RecordingOutput second = new RecordingOutput();
        Outcome repaired = run(cli, second, "token-generate", "ledger.mcp.token", "--copy-to=ledger.admin.agent-token");

        assertThat(repaired.exitCode()).isZero();
        assertThat(second.info()).contains("already exists");
        assertThat(Files.readString(directory().resolve("ledger.mcp.token"))).as("the token is kept").isEqualTo(token);
        assertThat(Files.readString(directory().resolve("ledger.admin.agent-token"))).as("the copy is made again").isEqualTo(token);

        Files.writeString(directory().resolve("ledger.admin.agent-token"), "a-different-token-0123456789");
        run(cli, new RecordingOutput(), "token-generate", "ledger.mcp.token", "--copy-to=ledger.admin.agent-token");
        assertThat(Files.readString(directory().resolve("ledger.admin.agent-token"))).as("a copy that exists is not replaced")
                .isEqualTo("a-different-token-0123456789");
        assertThat(captured.getAll()).doesNotContain(token);
    }

    @Test
    @DisplayName("INS-047 --copy-to needs a name that is not the token itself, and a value: a usage error that stores nothing")
    void ins047_copyToRefusals(Cli cli) {
        Outcome same = run(cli, new RecordingOutput(), "token-generate", "ledger.mcp.token", "--copy-to=ledger.mcp.token");
        Outcome blank = run(cli, new RecordingOutput(), "token-generate", "ledger.mcp.token", "--copy-to");
        Outcome bad = run(cli, new RecordingOutput(), "token-generate", "ledger.mcp.token", "--copy-to=../x");

        assertThat(same.code()).contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(same.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(blank.code()).contains(ErrorCode.MISSING_OPTION_VALUE);
        assertThat(bad.code()).contains(ErrorCode.INVALID_SECRET_NAME);
        assertThat(Files.exists(directory())).isFalse();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(new String[] { "token-generate" }, ErrorCode.MISSING_ARGUMENT, ExitStatus.USAGE),
                Arguments.of(new String[] { "token-generate", "a", "b" }, ErrorCode.TOO_MANY_ARGUMENTS, ExitStatus.USAGE),
                Arguments.of(new String[] { "token-generate", "a", "--force" }, ErrorCode.UNKNOWN_OPTION, ExitStatus.USAGE),
                Arguments.of(new String[] { "token-generate", "a", "--print=yes" }, ErrorCode.INVALID_OPTION_VALUE, ExitStatus.USAGE),
                Arguments.of(new String[] { "token-generate", "../x" }, ErrorCode.INVALID_SECRET_NAME, ExitStatus.FAILURE),
                Arguments.of(new String[] { "token-generate", "UPPER" }, ErrorCode.INVALID_SECRET_NAME, ExitStatus.FAILURE));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    @DisplayName("INS-014 a wrong command line is refused and stores nothing")
    void ins014_refusals(String[] line, ErrorCode code, ExitStatus status, Cli cli) {
        Outcome result = run(cli, new RecordingOutput(), line);

        assertThat(result.exitCode()).isEqualTo(status.code());
        assertThat(result.code()).contains(code);
        assertThat(Files.exists(directory())).isFalse();
    }
}
