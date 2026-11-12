package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.pgledger.cli.support.TestLedgerDatabase;
import org.pgledger.database.repository.LegacySourceRepository;
import org.pgledger.database.repository.LegacyTable;
import org.pgledger.testing.legacy.LegacyFixtures;
import org.pgledger.testing.legacy.LegacyLedger;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * REH2-MIG-01 and REH2-MIG-06: the order the README prescribes, on the real wiring over the test PostgreSQL. The installer runs
 * {@code setup} (the schema is migrated by the command itself and the 14 seed codes are added through the code service, which
 * writes {@code owner_audit} rows), and then the owner runs {@code import-legacy}. The import has to accept what {@code migrate}
 * and {@code setup} made, and still refuse ledger data, a session key and an earlier import.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class ImportAfterSetupTest {

    private static final String USER = "alice";
    private static final String AUDIT = "SELECT actor, operation, subject FROM owner_audit ORDER BY id";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;
    private LegacyLedger legacy;

    @BeforeEach
    void open() {
        database = TestLedgerDatabase.empty();
        legacy = LegacyFixtures.rich();
    }

    @AfterEach
    void close() {
        legacy.close();
        database.close();
    }

    private Outcome run(Cli cli, RecordingOutput output, String... args) {
        return cli.withDatabase(database.dataSource()).withApplicationAccount(database.applicationDataSource(true))
                .withLegacySource(new LegacySourceRepository(legacy.reader(), legacy.schema())).asProcessUser(USER)
                .withArguments("--ledger.schema.long-text-type=" + TestLedgerDatabase.longTextType())
                .run(tmp.resolve("secrets"), null, output, args);
    }

    /** What the installer does before the import. */
    private void setup(Cli cli) {
        Outcome setup = run(cli, new RecordingOutput(), "setup", "--app-account=" + database.appAccount());
        assertThat(setup.exitCode()).isZero();
        assertThat(count("owner_audit WHERE operation = 'CODE_SET'")).isEqualTo(14);
    }

    private long count(String tableAndCondition) {
        return ((Number) database.rows("SELECT COUNT(*) AS n FROM " + tableAndCondition).getFirst().get("n")).longValue();
    }

    @Test
    @DisplayName("REH2-MIG-01 setup (14 seed codes through the code service), then import-legacy: exit 0, every table has the rows of the legacy table, owner_audit has the 14 CODE_SET rows and one IMPORT row, and --verify-only afterwards exits 0 and writes nothing")
    void importsAfterSetup(Cli cli) {
        setup(cli);
        RecordingOutput output = new RecordingOutput();

        Outcome imported = run(cli, output, "import-legacy");

        assertThat(imported.failure()).isNull();
        assertThat(imported.exitCode()).isZero();
        for (LegacyTable table : LegacyTable.values()) {
            if (table != LegacyTable.COMMON_CODE) {
                assertThat(count(table.targetName())).as(table.name()).isEqualTo(legacy.count(table.sourceName()));
            }
        }
        // the seed has the same 14 categories as the legacy ledger, which has the category OLDBUG besides
        assertThat(count("common_code WHERE code_group = 'DEFECT_CATEGORY'")).isEqualTo(15);
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit.stream().filter(row -> "CODE_SET".equals(row.get("operation")))).hasSize(14);
        assertThat(audit.stream().filter(row -> "IMPORT".equals(row.get("operation")))).hasSize(1);
        assertThat(audit.getLast()).containsEntry("operation", "IMPORT").containsEntry("actor", "cli:" + USER);
        long auditRows = count("owner_audit");

        RecordingOutput verified = new RecordingOutput();
        Outcome verify = run(cli, verified, "import-legacy", "--verify-only");

        assertThat(verify.exitCode()).isZero();
        assertThat(verified.info()).contains("Verified").contains("Nothing was changed");
        assertThat(count("owner_audit")).isEqualTo(auditRows);
    }

    @Test
    @DisplayName("REH2-MIG-06 after setup and import a second import is IMPORT_TARGET_NOT_EMPTY (exit 1) naming owner_audit and project; after setup a row of project is IMPORT_TARGET_NOT_EMPTY naming project and not owner_audit; nothing is added either time")
    void stillRefusesDataAndASecondRun(Cli cli, CapturedOutput captured) {
        setup(cli);
        assertThat(run(cli, new RecordingOutput(), "import-legacy").exitCode()).isZero();
        long history = count("history");
        long audit = count("owner_audit");

        Outcome second = run(cli, new RecordingOutput(), "import-legacy");

        assertThat(second.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(second.code()).contains(ErrorCode.IMPORT_TARGET_NOT_EMPTY);
        assertThat(second.refusal().orElseThrow().arguments().getFirst()).contains("owner_audit").contains("project");
        assertThat(captured.getAll()).contains("already holds ledger data");
        assertThat(count("history")).isEqualTo(history);
        assertThat(count("owner_audit")).isEqualTo(audit);

        try (TestLedgerDatabase other = TestLedgerDatabase.empty()) {
            Outcome ready = cli.withDatabase(other.dataSource()).withApplicationAccount(other.applicationDataSource(true))
                    .asProcessUser(USER).withArguments("--ledger.schema.long-text-type=" + TestLedgerDatabase.longTextType())
                    .run(tmp.resolve("secrets"), null, new RecordingOutput(), "setup", "--app-account=" + other.appAccount());
            assertThat(ready.exitCode()).isZero();
            other.update("INSERT INTO project (id, code, title, version, created_by) VALUES ('own', 'OWN', 'a project of its own', 1, 'owner')");
            Outcome refused = cli.withDatabase(other.dataSource())
                    .withLegacySource(new LegacySourceRepository(legacy.reader(), legacy.schema())).asProcessUser(USER)
                    .run(tmp.resolve("secrets"), null, new RecordingOutput(), "import-legacy");

            assertThat(refused.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(refused.code()).contains(ErrorCode.IMPORT_TARGET_NOT_EMPTY);
            assertThat(refused.refusal().orElseThrow().arguments().getFirst()).contains("project").doesNotContain("owner_audit");
            assertThat(((Number) other.rows("SELECT COUNT(*) AS n FROM history").getFirst().get("n")).longValue()).isZero();
        }
    }
}
