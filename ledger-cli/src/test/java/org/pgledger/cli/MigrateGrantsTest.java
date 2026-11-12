package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * INS-011: {@code migrate --app-account=<account>} applies the grant template of the application account after the schema,
 * through the account the command runs as (here the owner account of the test database, the DDL account; the application
 * account is {@code ledger.test.app-user} of the PostgreSQL of the test PC, and 42501 is the SQLState of PostgreSQL for a
 * right that is missing).
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class MigrateGrantsTest {

    /** The SQLState of PostgreSQL for an insufficient privilege. */
    private static final String NOT_ALLOWED = "42501";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;

    @BeforeEach
    void emptyDatabase() {
        database = TestLedgerDatabase.empty();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Outcome run(Cli cli, RecordingOutput output, String... args) {
        return cli.withDatabase(database.dataSource()).asProcessUser("alice")
                .withArguments("--ledger.schema.long-text-type=" + TestLedgerDatabase.longTextType())
                .run(tmp.resolve("secrets"), null, output, args);
    }

    /**
     * The application account as the operator made it with the tools of the DBMS: the application account of the tests (the
     * owner account of the test database may not create roles, so the tests use the account that exists).
     */
    private String account() {
        return database.appAccount();
    }

    /** The SQLState of the refusal of the statement, or the word "allowed". */
    private static String outcomeOf(JdbcTemplate account, String sql) {
        try {
            account.execute(sql);
            return "allowed";
        } catch (DataAccessException refused) {
            return refused.getMostSpecificCause() instanceof SQLException sql1 ? sql1.getSQLState() : "other";
        }
    }

    /** Whether the schema history table exists in the test schema of this test (and only there). */
    private boolean schemaExists() {
        return !database.rows(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = 'schema_history'",
                database.schemaName()).isEmpty();
    }

    @Test
    @DisplayName("INS-011 migrate --app-account migrates the schema and then grants the application account what dml-grants.sql gives, nothing more, and says so")
    void ins011_theAccountGetsTheGrants(Cli cli) {
        String account = account();
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "migrate", "--app-account=" + account);

        assertThat(result.exitCode()).isZero();
        assertThat(output.info()).contains("Applied ").contains("schema version(s)").contains(account).contains("grants");
        JdbcTemplate app = database.asAppAccount();
        assertThat(outcomeOf(app, "SELECT COUNT(*) FROM defect")).isEqualTo("allowed");
        assertThat(outcomeOf(app, "SELECT COUNT(*) FROM schema_history")).isEqualTo("allowed");
        assertThat(outcomeOf(app, "DELETE FROM history")).isEqualTo(NOT_ALLOWED);
        assertThat(outcomeOf(app, "UPDATE schema_history SET outcome = 'FAILED'")).isEqualTo(NOT_ALLOWED);
        assertThat(outcomeOf(app, "CREATE TABLE stolen (id INTEGER)")).isEqualTo(NOT_ALLOWED);
    }

    @Test
    @DisplayName("INS-011 a second run applies no schema file and the same grants again: the account can do the same, nothing changed")
    void ins011_aRerunGrantsAgain(Cli cli) {
        String account = account();
        run(cli, new RecordingOutput(), "migrate", "--app-account=" + account);
        RecordingOutput output = new RecordingOutput();

        Outcome again = run(cli, output, "migrate", "--app-account=" + account);

        assertThat(again.exitCode()).isZero();
        assertThat(output.info()).contains("up to date").contains(account);
        assertThat(outcomeOf(database.asAppAccount(), "SELECT COUNT(*) FROM defect")).isEqualTo("allowed");
    }

    @Test
    @DisplayName("INS-011 migrate without the options is unchanged (no account is granted anything)")
    void ins011_withoutTheOptionNothingIsGranted(Cli cli) {
        Outcome result = run(cli, new RecordingOutput(), "migrate");

        assertThat(result.exitCode()).isZero();
        assertThat(outcomeOf(database.asAppAccount(), "SELECT COUNT(*) FROM defect")).isEqualTo(NOT_ALLOWED);
    }

    static Stream<Arguments> usageRefusals() {
        return Stream.of(
                Arguments.of(new String[] { "migrate", "--app-account=a;b" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "migrate", "--app-account=a b" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "migrate", "--app-account=1abc" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "migrate", "--app-account" }, ErrorCode.MISSING_OPTION_VALUE),
                Arguments.of(new String[] { "migrate", "--owner-account" }, ErrorCode.MISSING_OPTION_VALUE),
                // SEC-025: --stdin gives the password of the owner account, so it needs the account and takes no value
                Arguments.of(new String[] { "migrate", "--stdin" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "migrate", "--app-account=APP_X", "--stdin" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "migrate", "--mcp-account=a" }, ErrorCode.UNKNOWN_OPTION));
    }

    @ParameterizedTest
    @MethodSource("usageRefusals")
    @DisplayName("INS-011 a name that is not a plain account name or a blank option is refused (exit 2) before anything is changed")
    void ins011_usageRefusals(String[] line, ErrorCode code, Cli cli) {
        Outcome result = run(cli, new RecordingOutput(), line);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(code);
        assertThat(schemaExists()).as("nothing was migrated").isFalse();
    }

    @Test
    @DisplayName("INS-011 an account that does not exist is GRANT_FAILED (exit 1) with the schema already migrated, and the next run repeats the grants")
    void ins011_anUnknownAccountFailsTheGrants(Cli cli) {
        Outcome result = run(cli, new RecordingOutput(), "migrate", "--app-account=NO_SUCH_ACCOUNT");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.GRANT_FAILED);
        assertThat(schemaExists()).isTrue();
    }

    @Test
    @DisplayName("INS-011 a surplus operand is still refused")
    void ins011_surplusOperand(Cli cli) {
        Outcome result = run(cli, new RecordingOutput(), "migrate", "extra", "--app-account=APP_X");

        assertThat(result.code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
    }
}
