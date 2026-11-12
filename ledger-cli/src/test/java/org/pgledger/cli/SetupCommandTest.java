package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.pgledger.seed.SeedBundle;
import org.pgledger.seed.SeedCode;
import org.springframework.core.io.ClassPathResource;

/**
 * SET-010 .. SET-014, SET-016, SET-019, SET-020: {@code setup}, the first setup of the ledger database, over the test
 * PostgreSQL (a schema of its own, migrated by the command itself). The connection of the run is the owner account of the test
 * database (the DDL account); the application account of the tests ({@code ledger.test.app-user}) is the account whose right to
 * use the schema the command checks over a connection of its own. The seed is the bundle that the build made.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class SetupCommandTest {

    private static final Pattern DIGESTS = Pattern
            .compile("digest of the seed ([0-9a-f]{64}), digest of the same codes in the database ([0-9a-f]{64})\\.");
    private static final String CODES = "SELECT code_group, code, name_ko, name_en, description_ko, description_en, sort_order, state "
            + "FROM common_code WHERE code_group = 'DEFECT_CATEGORY' ORDER BY sort_order, code";

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

    /** @param accountMayUseTheSchema whether the operator has done the step that lets the application account into the schema */
    private Outcome run(Cli cli, RecordingOutput output, boolean accountMayUseTheSchema, String... args) {
        return cli.withDatabase(database.dataSource()).withApplicationAccount(database.applicationDataSource(accountMayUseTheSchema))
                .asProcessUser("alice").withArguments("--ledger.schema.long-text-type=" + TestLedgerDatabase.longTextType())
                .run(tmp.resolve("secrets"), null, output, args);
    }

    private long number(String sql) {
        return ((Number) database.rows(sql).getFirst().values().iterator().next()).longValue();
    }

    private List<SeedCode> codesOfTheDatabase() {
        return database.rows(CODES).stream().map(row -> new SeedCode((String) row.get("code_group"), (String) row.get("code"),
                (String) row.get("name_ko"), (String) row.get("name_en"), (String) row.get("description_ko"),
                (String) row.get("description_en"), ((Number) row.get("sort_order")).intValue(), (String) row.get("state")))
                .toList();
    }

    /** Everything a run of setup could change: the codes, their revision, the audit trail and the history. */
    private List<Object> state() {
        return List.of(database.rows("SELECT * FROM common_code ORDER BY code_group, code"),
                number("SELECT revision FROM code_revision WHERE id = 1"), number("SELECT COUNT(*) FROM owner_audit"),
                number("SELECT COUNT(*) FROM history"));
    }

    private static String report(RecordingOutput output) {
        return output.info().lines().filter(line -> line.startsWith("Seed check:")).findFirst().orElseThrow();
    }

    private static String account(TestLedgerDatabase database) {
        return database.appAccount();
    }

    @Test
    @DisplayName("SET-010 setup on a migrated-by-itself schema: schema, grants, the 14 codes of the seed through the code service (revision and audit trail kept), and a report with equal counts and digests")
    void set010_theFirstSetup(Cli cli) {
        RecordingOutput output = new RecordingOutput();
        List<SeedCode> seed = SeedBundle.packaged().codes();

        Outcome result = run(cli, output, true, "setup", "--app-account=" + account(database));

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(seed).hasSize(14);
        assertThat(codesOfTheDatabase()).isEqualTo(seed);
        assertThat(number("SELECT revision FROM code_revision WHERE id = 1")).isEqualTo(14);
        List<Map<String, Object>> audit = database.rows("SELECT actor, subject FROM owner_audit WHERE operation = 'CODE_SET' ORDER BY id");
        assertThat(audit).hasSize(14).allSatisfy(row -> assertThat(row.get("actor")).isEqualTo("cli:alice"));
        assertThat(audit.stream().map(row -> (String) row.get("subject"))).containsExactlyInAnyOrderElementsOf(
                seed.stream().map(code -> "DEFECT_CATEGORY/" + code.code()).toList());
        assertThat(output.info()).contains("Applied ").contains("grants").contains("Added 14 seed code(s)");
        String report = report(output);
        assertThat(report).startsWith("Seed check: 14 codes in the seed, 14 present in the database, 14 equal to the seed, 0 changed");
        Matcher digests = DIGESTS.matcher(report);
        assertThat(digests.find()).isTrue();
        assertThat(digests.group(1)).isEqualTo(digests.group(2)).isEqualTo(SeedBundle.packaged().digest());
    }

    @Test
    @DisplayName("SET-011 a second setup changes nothing (codes, revision, audit trail, history) and prints the same report")
    void set011_aSecondRunChangesNothing(Cli cli) {
        RecordingOutput first = new RecordingOutput();
        run(cli, first, true, "setup", "--app-account=" + account(database));
        List<Object> before = state();
        RecordingOutput second = new RecordingOutput();

        Outcome again = run(cli, second, true, "setup", "--app-account=" + account(database));

        assertThat(again.exitCode()).isZero();
        assertThat(state()).isEqualTo(before);
        assertThat(report(second)).isEqualTo(report(first));
        assertThat(second.info()).contains("Added 0 seed code(s)").contains("up to date");
    }

    @Test
    @DisplayName("SET-012 a code the owner changed or retired is kept as he left it, his own code is untouched, nothing is audited, and the report says 12 equal and 2 changed")
    void set012_aChangedCodeIsKept(Cli cli) {
        run(cli, new RecordingOutput(), true, "setup", "--app-account=" + account(database));
        run(cli, new RecordingOutput(), true, "code-set", "DEFECT_CATEGORY", "ARC", "내 이름");
        run(cli, new RecordingOutput(), true, "code-set", "DEFECT_CATEGORY", "CON", "동시성·원자성", "--inactive");
        run(cli, new RecordingOutput(), true, "code-set", "DEFECT_CATEGORY", "MINE", "내 분류");
        List<Object> before = state();
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, true, "setup", "--app-account=" + account(database));

        assertThat(result.exitCode()).isZero();
        assertThat(state()).as("not a row, the revision or the audit trail changed").isEqualTo(before);
        assertThat(database.rows("SELECT name_ko, state FROM common_code WHERE code = 'ARC'").getFirst())
                .containsEntry("name_ko", "내 이름").containsEntry("state", "ACTIVE");
        assertThat(database.rows("SELECT state FROM common_code WHERE code = 'CON'").getFirst()).containsEntry("state", "INACTIVE");
        assertThat(report(output)).startsWith("Seed check: 14 codes in the seed, 14 present in the database, 12 equal to the seed, 2 changed");
        Matcher digests = DIGESTS.matcher(report(output));
        assertThat(digests.find()).isTrue();
        assertThat(digests.group(2)).isNotEqualTo(digests.group(1));
    }

    private static Properties bundle() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource("messages.properties").getInputStream()) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    @DisplayName("SET-013 the application account may not use the schema yet: exit 3, the account is named, the guidance is generic (no statement of a DBMS), the schema is migrated and no code is added")
    void set013_theOperatorStepIsMissing(Cli cli) throws IOException {
        Outcome result = run(cli, new RecordingOutput(), false, "setup", "--app-account=" + account(database));

        assertThat(result.exitCode()).isEqualTo(ExitStatus.OPERATOR_STEP.code());
        assertThat(result.code()).contains(ErrorCode.APP_ACCOUNT_NO_ACCESS);
        assertThat(result.refusal().orElseThrow().arguments()).containsExactly(CliExtension.APPLICATION_ACCOUNT_NAME);
        assertThat(number("SELECT COUNT(*) FROM schema_history")).isPositive();
        assertThat(number("SELECT COUNT(*) FROM common_code WHERE code_group = 'DEFECT_CATEGORY'")).isZero();
        assertThat(number("SELECT revision FROM code_revision WHERE id = 1")).isZero();
        assertThat(number("SELECT COUNT(*) FROM owner_audit")).isZero();
        String guidance = bundle().getProperty(ErrorCode.APP_ACCOUNT_NO_ACCESS.messageKey());
        assertThat(guidance).contains("let this account use the schema").contains("run setup again")
                .doesNotContain("GRANT").doesNotContain("USAGE").doesNotContainIgnoringCase("postgres");
    }

    @Test
    @DisplayName("SET-014 after the operator did the step, the next setup finishes what the one before stopped: the result of SET-010")
    void set014_aRerunFinishesTheStoppedRun(Cli cli) {
        run(cli, new RecordingOutput(), false, "setup", "--app-account=" + account(database));
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, true, "setup", "--app-account=" + account(database));

        assertThat(result.exitCode()).isZero();
        assertThat(codesOfTheDatabase()).isEqualTo(SeedBundle.packaged().codes());
        assertThat(report(output)).startsWith("Seed check: 14 codes in the seed, 14 present in the database, 14 equal to the seed, 0 changed");
    }

    static Stream<Arguments> usageRefusals() {
        return Stream.of(
                Arguments.of(new String[] { "setup", "extra" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "setup", "--app-account=a;b" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "setup", "--app-account" }, ErrorCode.MISSING_OPTION_VALUE),
                Arguments.of(new String[] { "setup", "--owner-account" }, ErrorCode.MISSING_OPTION_VALUE),
                Arguments.of(new String[] { "setup", "--stdin" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "setup", "--mcp-account=a" }, ErrorCode.UNKNOWN_OPTION));
    }

    @ParameterizedTest
    @MethodSource("usageRefusals")
    @DisplayName("SET-016 setup takes the options of migrate and refuses a wrong command line (exit 2) before anything is changed")
    void set016_usageRefusals(String[] line, ErrorCode code, Cli cli) {
        Outcome result = run(cli, new RecordingOutput(), true, line);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(code);
        assertThat(database.rows("SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = 'schema_history'",
                database.schemaName())).as("nothing was migrated").isEmpty();
    }

    @Test
    @DisplayName("SET-019 an application account the DBMS does not know is GRANT_FAILED (exit 1) after the schema was migrated, no code is added, and setup with the right account finishes")
    void set019_anUnknownAccount(Cli cli) {
        Outcome refused = run(cli, new RecordingOutput(), true, "setup", "--app-account=NO_SUCH_ACCOUNT");

        assertThat(refused.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(refused.code()).contains(ErrorCode.GRANT_FAILED);
        assertThat(number("SELECT COUNT(*) FROM common_code WHERE code_group = 'DEFECT_CATEGORY'")).isZero();

        Outcome finished = run(cli, new RecordingOutput(), true, "setup", "--app-account=" + account(database));

        assertThat(finished.exitCode()).isZero();
        assertThat(codesOfTheDatabase()).isEqualTo(SeedBundle.packaged().codes());
    }

    @Test
    @DisplayName("SET-020 setup without options after a migrate --app-account: the same check and the same complete seed")
    void set020_withoutOptions(Cli cli) {
        run(cli, new RecordingOutput(), true, "migrate", "--app-account=" + account(database));
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, true, "setup");

        assertThat(result.exitCode()).isZero();
        assertThat(codesOfTheDatabase()).isEqualTo(SeedBundle.packaged().codes());
        assertThat(report(output)).contains("14 equal to the seed");
    }
}
