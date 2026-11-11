package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresPosixPermissions;
import org.pgledger.cli.support.ScriptedSecretInput;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.pgledger.database.repository.LegacySourceRepository;
import org.pgledger.database.repository.LegacyTable;
import org.pgledger.testing.legacy.LegacyFixtures;
import org.pgledger.testing.legacy.LegacyLedger;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-013 and CLI-044 .. CLI-050: the command {@code import-legacy} on the real wiring (the real application context, the
 * real services and repositories, an embedded H2 that the real migrator built as the new schema, and a second embedded H2
 * that {@link LegacyFixtures} filled as the legacy ledger, read through its read-only account). The import itself is
 * MIG-001 .. MIG-032 in the application module; these tests are about the command: its exit codes, what it prints, the
 * audit actor and that no secret is printed.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class ImportLegacyCommandTest {

    private static final String USER = "alice";
    private static final String AUDIT = "SELECT actor, operation, subject, detail FROM owner_audit ORDER BY id";
    private static final String SOURCE_PASSWORD_NAME = "ledger.import.source.password";
    private static final String SOURCE_PASSWORD = "Src-Pw-7c1-unique-legacy-password-é";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;
    private LegacyLedger legacy;

    @BeforeEach
    void open() {
        database = TestLedgerDatabase.migrated();
        legacy = LegacyFixtures.rich();
    }

    @AfterEach
    void close() {
        legacy.close();
        database.close();
    }

    private Outcome run(Cli cli, RecordingOutput output, String... args) {
        return cli.withDatabase(database.dataSource()).withLegacySource(new LegacySourceRepository(legacy.reader(),
                LegacyLedger.SCHEMA)).asProcessUser(USER).run(tmp.resolve("secrets"), null, output, args);
    }

    private long count(String table) {
        return ((Number) database.rows("SELECT COUNT(*) AS n FROM " + table).getFirst().get("n")).longValue();
    }

    @Test
    @DisplayName("CLI-044 import-legacy copies the legacy ledger: exit 0, one line per table with its rows and sha256, the summary, one owner_audit row IMPORT with the actor cli:<user>; the grants have no session key")
    void importsTheLegacyLedger(Cli cli) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "import-legacy");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        long rows = 0;
        for (LegacyTable table : LegacyTable.values()) {
            long legacyRows = legacy.count(table.sourceName());
            rows += legacyRows;
            assertThat(output.info()).containsPattern(table.targetName() + ": " + legacyRows + " rows, sha256 [0-9a-f]{64}");
            assertThat(count(table.targetName())).as(table.name()).isEqualTo(legacyRows);
        }
        assertThat(output.info()).contains("Imported " + rows + " rows of " + LegacyTable.values().length
                + " tables from the legacy schema ledger").contains("key-register");
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.getFirst().get("operation")).isEqualTo("IMPORT");
        assertThat(audit.getFirst().get("subject")).isEqualTo("legacy schema ledger");
        assertThat(count("session_key")).isZero();
    }

    @Test
    @DisplayName("CLI-045 a second import is IMPORT_TARGET_NOT_EMPTY (exit 1) naming the tables; nothing is added, there is still one audit row")
    void refusesASecondRun(Cli cli, CapturedOutput captured) {
        assertThat(run(cli, new RecordingOutput(), "import-legacy").exitCode()).isZero();
        long history = count("history");

        Outcome second = run(cli, new RecordingOutput(), "import-legacy");

        assertThat(second.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(second.code()).contains(ErrorCode.IMPORT_TARGET_NOT_EMPTY);
        assertThat(second.refusal().orElseThrow().arguments().getFirst()).contains("project").contains("history");
        assertThat(captured.getAll()).contains("already holds ledger data");
        assertThat(count("history")).isEqualTo(history);
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-046 a legacy value that cannot be imported is IMPORT_DATA_REFUSED (exit 1): every offending value is printed as table[key].column with its reason, nothing is written; the printed reason of an id is the format it must match")
    void listsWhatCannotBeImported(Cli cli, CapturedOutput captured) {
        LegacyFixtures.withEmptyPurpose(legacy);
        LegacyFixtures.withLowerCaseIds(legacy);
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "import-legacy");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.IMPORT_DATA_REFUSED);
        assertThat(output.info()).contains("task[OLD-T-UNKNOWN].purpose: REQUIRED")
                .contains("task[OLD-T-UNKNOWN].success_condition: REQUIRED")
                .contains("task[old-t-lower].id: MUST_BE_UPPER_CASE_ID_MATCHING [A-Z0-9][A-Z0-9_|-]{0,119}")
                .contains("release_condition[old-c-lower].id: MUST_BE_UPPER_CASE_ID_MATCHING");
        assertThat(result.refusal().orElseThrow().arguments()).containsExactly("4");
        assertThat(captured.getAll()).contains("cannot be imported as they are");
        assertThat(count("project")).isZero();
        assertThat(count("history")).isZero();
        assertThat(database.rows(AUDIT)).isEmpty();
    }

    @Test
    @DisplayName("CLI-047 a legacy database without installer steps is IMPORT_SOURCE_INCOMPLETE (exit 1) naming them, a command without the legacy connection is IMPORT_SOURCE_NOT_CONFIGURED (exit 1); nothing is written")
    void refusesAnIncompleteOrMissingSource(Cli cli) {
        try (LegacyLedger older = LegacyLedger.create(Set.of("schema"))) {
            Outcome incomplete = cli.withDatabase(database.dataSource()).withLegacySource(new LegacySourceRepository(older.reader(),
                    LegacyLedger.SCHEMA)).asProcessUser(USER).run(tmp.resolve("secrets"), null, new RecordingOutput(),
                    "import-legacy");

            assertThat(incomplete.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(incomplete.code()).contains(ErrorCode.IMPORT_SOURCE_INCOMPLETE);
            assertThat(incomplete.refusal().orElseThrow().arguments().getFirst()).contains("defect_case")
                    .contains("common_code").contains("created_by");
        }
        Outcome unconfigured = cli.withDatabase(database.dataSource()).asProcessUser(USER).run(tmp.resolve("secrets"), null,
                new RecordingOutput(), "import-legacy");

        assertThat(unconfigured.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(unconfigured.code()).contains(ErrorCode.IMPORT_SOURCE_NOT_CONFIGURED);
        assertThat(count("project")).isZero();
        assertThat(database.rows(AUDIT)).isEmpty();
    }

    @Test
    @DisplayName("CLI-048 --verify-only repeats the comparison and writes nothing: exit 0 after an import; after a row of the new schema was changed it is IMPORT_VERIFY_FAILED (exit 1) naming the table, both row counts and both digests")
    void verifiesAnImport(Cli cli, CapturedOutput captured) {
        assertThat(run(cli, new RecordingOutput(), "import-legacy").exitCode()).isZero();
        RecordingOutput verified = new RecordingOutput();

        Outcome ok = run(cli, verified, "import-legacy", "--verify-only");

        assertThat(ok.exitCode()).isZero();
        assertThat(verified.info()).contains("Verified").contains("Nothing was changed");
        assertThat(database.rows(AUDIT)).hasSize(1);

        database.update("UPDATE defect SET title = 'tampered' WHERE id = 'OLD-D-0001'");
        RecordingOutput failed = new RecordingOutput();
        Outcome tampered = run(cli, failed, "import-legacy", "--verify-only");

        assertThat(tampered.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(tampered.code()).contains(ErrorCode.IMPORT_VERIFY_FAILED);
        List<String> arguments = tampered.refusal().orElseThrow().arguments();
        assertThat(arguments).hasSize(5);
        assertThat(arguments.get(0)).isEqualTo("defect");
        assertThat(arguments.get(1)).isEqualTo(arguments.get(2));
        assertThat(arguments.get(3)).hasSize(64).isNotEqualTo(arguments.get(4));
        assertThat(failed.info()).contains("Table defect: " + arguments.get(1) + " legacy rows but " + arguments.get(2)
                + " rows in the new schema; sha256 " + arguments.get(3) + " against " + arguments.get(4));
        assertThat(captured.getAll()).contains("differ in the table defect");
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-049 import-legacy takes no operand and only the flag --verify-only: a surplus operand is TOO_MANY_ARGUMENTS, --verify-only=<value> is INVALID_OPTION_VALUE, another option is UNKNOWN_OPTION (exit 2 each); nothing is written")
    void refusesWrongCommandLines(Cli cli) {
        assertThat(run(cli, new RecordingOutput(), "import-legacy", "extra").code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        assertThat(run(cli, new RecordingOutput(), "import-legacy", "--verify-only=yes").code())
                .contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(cli, new RecordingOutput(), "import-legacy", "--force").code()).contains(ErrorCode.UNKNOWN_OPTION);
        for (String line : List.of("extra", "--verify-only=yes", "--force")) {
            assertThat(run(cli, new RecordingOutput(), "import-legacy", line).exitCode()).isEqualTo(ExitStatus.USAGE.code());
        }
        assertThat(count("project")).isZero();
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("CLI-050 (MIG-020) the password of the legacy connection is the secret ledger.import.source.password: the secret store is part of the environment, and neither the output nor the log shows it, in a successful import, in a refusal and with --debug")
    void theSourcePasswordIsASecret(Cli cli, CapturedOutput captured) {
        assertThat(cli.run(tmp.resolve("secrets"), new ScriptedSecretInput(SOURCE_PASSWORD.toCharArray()),
                new RecordingOutput(), "secret-set", SOURCE_PASSWORD_NAME).exitCode()).isZero();
        RecordingOutput output = new RecordingOutput();

        Outcome imported = run(cli, output, "import-legacy");
        Outcome refused = run(cli, output, "import-legacy");
        Outcome debug = run(cli, output, "import-legacy", "--verify-only", "--debug");

        assertThat(imported.exitCode()).isZero();
        assertThat(refused.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(debug.exitCode()).isZero();
        assertThat(imported.context().getEnvironment().getProperty(SOURCE_PASSWORD_NAME)).isEqualTo(SOURCE_PASSWORD);
        assertThat(captured.getAll()).doesNotContain(SOURCE_PASSWORD);
        assertThat(output.info()).doesNotContain(SOURCE_PASSWORD);
    }
}
