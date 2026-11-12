package org.pgledger.cli.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.Console;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.secret.ConsoleSecretInput;
import org.pgledger.cli.secret.StdinSecretInput;
import org.pgledger.cli.support.ScriptedSecretInput;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.util.Map;

/**
 * INS-012: {@code --owner-account=<DDL account>} puts the account and its typed password in front of the other settings for
 * one run; the password is asked for when the DataSource asks for it, once, and is never stored.
 */
class OwnerAccountPropertySourceTest {

    private static boolean allZero(char[] chars) {
        for (char c : chars) {
            if (c != 0) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("INS-012 the username needs no input; the password is read from the hidden input once, however often it is asked for, and the typed characters are wiped")
    void ins012_thePasswordIsAskedForOnce() {
        char[] typed = "typed-ddl-password".toCharArray();
        ScriptedSecretInput input = new ScriptedSecretInput(typed);
        OwnerAccountPropertySource source = new OwnerAccountPropertySource("ledger_ddl", "prompt text", input);

        assertThat(source.getProperty("ledger.jdbc.username")).isEqualTo("ledger_ddl");
        assertThat(source.getProperty("ledger.schema.name")).as("no other key is answered").isNull();
        assertThat(input.reads()).as("nothing is asked until the password is").isZero();

        assertThat(source.getProperty("ledger.jdbc.password")).isEqualTo("typed-ddl-password");
        assertThat(source.getProperty("ledger.jdbc.password")).isEqualTo("typed-ddl-password");
        assertThat(input.reads()).isEqualTo(1);
        assertThat(input.lastPrompt()).isEqualTo("prompt text");
        assertThat(allZero(typed)).isTrue();
        assertThat(source.toString()).doesNotContain("typed-ddl-password");
    }

    @Test
    @DisplayName("INS-012 an empty or missing input is refused (EMPTY_SECRET) and the refusal repeats at the next question")
    void ins012_anEmptyInputIsRefused() {
        for (char[] answer : new char[][] { new char[0], null }) {
            OwnerAccountPropertySource source = new OwnerAccountPropertySource("a", "p", new ScriptedSecretInput(answer));

            assertThatThrownBy(() -> source.getProperty("ledger.jdbc.password")).isInstanceOfSatisfying(FailureException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.EMPTY_SECRET));
        }
    }

    @Test
    @DisplayName("INS-012 without a terminal the real console input refuses (NO_CONSOLE)")
    void ins012_noConsole() {
        Console console = System.console();
        assumeFalse(console != null && console.isTerminal(), "test JVM is attached to a terminal");
        OwnerAccountPropertySource source = new OwnerAccountPropertySource("a", "p", new ConsoleSecretInput());

        assertThatThrownBy(() -> source.getProperty("ledger.jdbc.password")).isInstanceOfSatisfying(FailureException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_CONSOLE));
    }

    @Test
    @DisplayName("INS-012 the connection settings bind from it: account and password of the owner, the URL from the settings file, one question")
    void ins012_theConnectionSettingsBind() {
        ScriptedSecretInput input = new ScriptedSecretInput("typed-ddl-password".toCharArray());
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("settings-file",
                Map.of("ledger.jdbc.url", "jdbc:h2:mem:x", "ledger.jdbc.username", "ledger_app")));
        environment.getPropertySources().addFirst(new OwnerAccountPropertySource("ledger_ddl", "p", input));

        LedgerJdbcProperties bound = Binder.get(environment).bind("ledger.jdbc", LedgerJdbcProperties.class).get();

        assertThat(bound.username()).as("the owner account wins over the account of the settings file").isEqualTo("ledger_ddl");
        assertThat(bound.password()).isEqualTo("typed-ddl-password");
        assertThat(bound.url()).isEqualTo("jdbc:h2:mem:x");
        assertThat(input.reads()).isEqualTo(1);
    }

    @Test
    @DisplayName("INS-012 the post-processor adds the source only for --owner-account=<account> of migrate or import-legacy on the command line, never from the environment, and not for a blank value")
    void ins012_thePostProcessor() {
        OwnerAccountEnvironmentPostProcessor processor = new OwnerAccountEnvironmentPostProcessor();

        StandardEnvironment with = new StandardEnvironment();
        with.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--owner-account=ledger_ddl", "migrate"));
        processor.postProcessEnvironment(with, null);
        assertThat(with.getPropertySources().iterator().next()).isInstanceOf(OwnerAccountPropertySource.class);
        assertThat(with.getProperty("ledger.jdbc.username")).isEqualTo("ledger_ddl");

        StandardEnvironment otherCommand = new StandardEnvironment();
        otherCommand.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--owner-account=ledger_ddl", "secret-set"));
        processor.postProcessEnvironment(otherCommand, null);
        assertThat(otherCommand.getPropertySources().stream()).as("only migrate and import-legacy ask for the password")
                .noneMatch(OwnerAccountPropertySource.class::isInstance);

        StandardEnvironment without = new StandardEnvironment();
        without.getPropertySources().addFirst(new SimpleCommandLinePropertySource("migrate"));
        processor.postProcessEnvironment(without, null);
        assertThat(without.getPropertySources().stream()).noneMatch(OwnerAccountPropertySource.class::isInstance);

        StandardEnvironment blank = new StandardEnvironment();
        blank.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--owner-account=", "migrate"));
        processor.postProcessEnvironment(blank, null);
        assertThat(blank.getPropertySources().stream()).noneMatch(OwnerAccountPropertySource.class::isInstance);

        StandardEnvironment fromEnvironment = new StandardEnvironment();
        fromEnvironment.getPropertySources().addLast(new MapPropertySource("systemEnvironment", Map.of("OWNER_ACCOUNT", "x")));
        processor.postProcessEnvironment(fromEnvironment, null);
        assertThat(fromEnvironment.getPropertySources().stream()).noneMatch(OwnerAccountPropertySource.class::isInstance);
    }

    @Test
    @DisplayName("SEC-025 --stdin on the command line makes standard input the source of the password, without --stdin it is the console; nothing is read while the source is made")
    void sec025_theInputOfThePassword() {
        OwnerAccountEnvironmentPostProcessor processor = new OwnerAccountEnvironmentPostProcessor();

        for (String command : new String[] { "migrate", "import-legacy" }) {
            StandardEnvironment piped = new StandardEnvironment();
            piped.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--owner-account=ledger_ddl", "--stdin", command));
            processor.postProcessEnvironment(piped, null);
            assertThat(piped.getPropertySources().get(OwnerAccountPropertySource.NAME).getSource())
                    .as(command + " --stdin").isInstanceOf(StdinSecretInput.class);

            StandardEnvironment typed = new StandardEnvironment();
            typed.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--owner-account=ledger_ddl", command));
            processor.postProcessEnvironment(typed, null);
            assertThat(typed.getPropertySources().get(OwnerAccountPropertySource.NAME).getSource())
                    .as(command + " without --stdin").isInstanceOf(ConsoleSecretInput.class);
        }
    }

    @Test
    @DisplayName("SEC-025 --stdin alone does not make a source: the password is of an account, and a command that does not declare the option refuses it")
    void sec025_noAccountNoSource() {
        OwnerAccountEnvironmentPostProcessor processor = new OwnerAccountEnvironmentPostProcessor();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--stdin", "migrate"));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().stream()).noneMatch(OwnerAccountPropertySource.class::isInstance);
    }
}
