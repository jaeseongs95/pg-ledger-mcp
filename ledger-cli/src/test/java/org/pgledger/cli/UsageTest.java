package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;

/**
 * CLI-001, CLI-002 and CLI-039: the command line itself. No database is involved: the usage and the refusals of the
 * arguments come before any database work.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class })
class UsageTest {

    @TempDir
    Path tmp;

    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (var stream = new ClassPathResource(name).getInputStream()) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private Outcome run(Cli cli, String... args) {
        return cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), args);
    }

    @Test
    @DisplayName("CLI-001 no argument is MISSING_COMMAND, an unknown command (also the dropped category-add) is UNKNOWN_COMMAND: exit 2 and the usage text lists every command, in English")
    void usageListsEveryCommandInEnglish(Cli cli, CapturedOutput captured) throws IOException {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            Outcome none = run(cli);
            assertThat(none.exitCode()).isEqualTo(ExitStatus.USAGE.code());
            assertThat(none.code()).contains(ErrorCode.MISSING_COMMAND);
            String usage = captured.getAll();
            assertThat(usage).contains(bundle("messages.properties").getProperty("usage.header"));
            for (CliCommand command : CliCommand.values()) {
                assertThat(usage).as("usage line of %s", command.cliName())
                        .contains(bundle("messages.properties").getProperty(command.usageKey()));
            }

            Outcome unknown = run(cli, "category-add", "DEFECT_CATEGORY", "X");
            assertThat(unknown.exitCode()).isEqualTo(ExitStatus.USAGE.code());
            assertThat(unknown.code()).contains(ErrorCode.UNKNOWN_COMMAND);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    @DisplayName("CLI-001 the usage text of the Korean bundle lists every command too")
    void usageListsEveryCommandInKorean(Cli cli, CapturedOutput captured) throws IOException {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        try {
            Outcome none = run(cli);

            assertThat(none.exitCode()).isEqualTo(ExitStatus.USAGE.code());
            String usage = captured.getAll();
            Properties korean = bundle("messages_ko.properties");
            assertThat(usage).contains(korean.getProperty("usage.header"));
            for (CliCommand command : CliCommand.values()) {
                assertThat(usage).as("usage line of %s", command.cliName()).contains(korean.getProperty(command.usageKey()));
            }
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    @DisplayName("CLI-001 the usage text names the commands of the owner tool (changed by import: import-legacy is listed after migrate; changed by install, INS-029: token-generate follows secret-set and render-deploy ends the list; SEC-027: secret-import-1x follows token-generate; INS-044: prepare-host and seal-host end the list)")
    void theCommandSetIsTheOneOfThePlan() {
        assertThat(Stream.of(CliCommand.values()).map(CliCommand::cliName)).containsExactly("secret-set",
                "token-generate", "secret-import-1x", "driver-register", "migrate", "import-legacy", "key-register", "key-revoke", "project-add", "code-set",
                "codes", "grant", "revoke", "grants", "mcp-check", "render-deploy", "prepare-host", "seal-host");
    }

    @Test
    @DisplayName("CLI-002 a command starts no web server: the application is not a web application and has no server port")
    void noServerPort(Cli cli) {
        Outcome result = run(cli);

        assertThat(result.context()).isNotNull();
        assertThat(result.context().getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(result.context().getEnvironment().getProperty("local.server.port")).isNull();
        assertThat(result.context().getClass().getName()).doesNotContain("Web");
    }

    static Stream<Arguments> refusedCommandLines() {
        return Stream.of(
                Arguments.of(new String[] { "migrate", "extra" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "project-add", "e2e", "E2E" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "project-add", "e2e", "E2E", "title", "more" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "code-set", "DEFECT_CATEGORY", "BUG" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "code-set", "G", "C", "n", "d", "extra" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "code-set", "G", "C", "n", "--sort=abc" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "code-set", "G", "C", "n", "--sort" }, ErrorCode.MISSING_OPTION_VALUE),
                Arguments.of(new String[] { "code-set", "G", "C", "n", "--inactive=yes" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "code-set", "G", "C", "n", "--bogus=1" }, ErrorCode.UNKNOWN_OPTION),
                Arguments.of(new String[] { "codes", "A", "B" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "grant", "claude:a", "p" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "grant", "claude:a", "p", "defect", "--note" }, ErrorCode.MISSING_OPTION_VALUE),
                Arguments.of(new String[] { "grant", "claude:a", "p", "defect", "--expires=a", "--expires=b" },
                        ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "revoke" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "revoke", "a", "b", "c", "d" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "grants", "a", "b" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "driver-register" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "driver-register", "--jar=x" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "driver-register", "--jar=x", "--class=y", "extra" },
                        ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "mcp-check", "extra" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "mcp-check", "--url=ftp://host/mcp" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "mcp-check", "--url=http://user:password@host/mcp" },
                        ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "mcp-check", "--url=http://host/mcp?token=abc" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "mcp-check", "--force" }, ErrorCode.UNKNOWN_OPTION));
    }

    @ParameterizedTest(name = "{0} is {1}")
    @MethodSource("refusedCommandLines")
    @DisplayName("CLI-039 a wrong command line of the new commands is a usage error (exit 2) before any database or network work")
    void wrongCommandLinesAreUsageErrors(String[] line, ErrorCode expected, Cli cli, CapturedOutput captured) {
        Outcome result = run(cli, line);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(expected);
        assertThat(captured.getAll()).doesNotContain("password").doesNotContain("token=abc");
    }
}
