package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.RecordingOutput;

/**
 * SET-007, SET-009 and SET-018 (the part of the install defaults): {@code install-default <name>} prints one default of the
 * seed bundle and nothing else, needs no database and no driver; the classes that used to hold the defaults as constants ask for
 * them now.
 */
@ExtendWith(CliExtension.class)
class InstallDefaultCommandTest {

    @TempDir
    Path tmp;

    static Stream<Arguments> defaults() {
        return Stream.of(Arguments.of("instance-name", "pg-ledger-mcp"), Arguments.of("mcp-port", "8765"),
                Arguments.of("admin-port", "8865"));
    }

    @ParameterizedTest
    @MethodSource("defaults")
    @DisplayName("SET-007 install-default <name> prints the value of the bundle on one line and nothing else, without a driver and without a database bean")
    void printsTheDefault(String name, String value, Cli cli) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.run(tmp.resolve("secrets"), null, output, "install-default", name);

        assertThat(result.exitCode()).isZero();
        assertThat(output.info()).isEqualTo(value);
        assertThat(result.context().containsBean("dataSource")).isFalse();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(new String[] { "install-default", "no-such-default" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "install-default" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "install-default", "mcp-port", "admin-port" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "install-default", "mcp-port", "--mcp-port=1" }, ErrorCode.UNKNOWN_OPTION));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    @DisplayName("SET-007 an unknown name, no name, a second name and an option are usage errors (exit 2) and print nothing")
    void refusesAWrongCommandLine(String[] line, ErrorCode code, Cli cli) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.run(tmp.resolve("secrets"), null, output, line);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(code);
        assertThat(output.info()).isEmpty();
    }

    @Test
    @DisplayName("SET-009 DeployLayout, DeployParameters, DeployInputReader and McpCheckCommand hold neither the default name nor a default port: they ask InstallDefaults")
    void theClassesHoldNoDefaultOfTheirOwn() throws IOException {
        String root = System.getProperty("ledger.docs.root");
        List<Path> files = Stream.of("deploy/DeployLayout.java", "deploy/DeployParameters.java", "deploy/DeployInputReader.java",
                "mcp/McpCheckCommand.java").map(name -> Path.of(root, "ledger-cli", "src", "main", "java", "org", "pgledger", "cli")
                        .resolve(name)).toList();

        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(text).as(file.toString()).doesNotContain("\"pg-ledger-mcp\"", "8765", "8865");
        }
        assertThat(Files.readString(files.get(2), StandardCharsets.UTF_8)).contains("defaults.instanceName()", "defaults.mcpPort()",
                "defaults.adminPort()");
        assertThat(Files.readString(files.get(3), StandardCharsets.UTF_8)).contains("defaults.mcpPort()");
    }
}
