package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * CLI-003 and CLI-004 (the schema runner through the command; the runner itself is SCH-001, SCH-002), CLI-038 (a command that
 * is not {@code migrate} on a database that is not up to date) and CLI-042 (a migration that stops). The database is a schema of
 * its own in the PostgreSQL of the test PC; the application context is the real one with that database as its DataSource.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class MigrateCommandTest {

    private static final String USER = "alice";
    private static final Pattern FILE_NAME = Pattern.compile("V([0-9]+)__.*\\.sql");
    private static final String HISTORY = "SELECT version, file_name, sha256, applied_at, outcome FROM schema_history "
            + "ORDER BY version, applied_at";

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
        return cli.withDatabase(database.dataSource()).asProcessUser(USER)
                .withArguments("--ledger.schema.long-text-type=" + TestLedgerDatabase.longTextType())
                .run(tmp.resolve("secrets"), null, output, args);
    }

    /** The files of the schema as an independent reader sees them: name and SHA-256 of the text with LF line ends. */
    private static Map<String, String> schemaFiles() throws Exception {
        Map<String, String> digests = new java.util.TreeMap<>(Comparator.comparingInt(MigrateCommandTest::version));
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/V*.sql")) {
            String text = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            digests.put(resource.getFilename(), HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8))));
        }
        return digests;
    }

    private static int version(String fileName) {
        Matcher matcher = FILE_NAME.matcher(fileName);
        assertThat(matcher.matches()).as(fileName).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static byte[] sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private String dataDigest() throws NoSuchAlgorithmException {
        StringBuilder all = new StringBuilder();
        for (String table : List.of("common_code", "code_revision", "id_counter", "project", "owner_audit")) {
            all.append(table).append(database.rows("SELECT * FROM " + table));
        }
        return HexFormat.of().formatHex(sha256(all.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("CLI-003 migrate on an empty database applies every V file in numeric order: one schema_history row per file, outcome SUCCESS, the file name and the SHA-256 of an independent reader, applied_at in UTC; exit 0")
    void migratesAnEmptyDatabase(Cli cli) throws Exception {
        RecordingOutput output = new RecordingOutput();
        Instant before = Instant.now().minus(Duration.ofMinutes(1));

        Outcome result = run(cli, output, "migrate");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        Map<String, String> files = schemaFiles();
        assertThat(files).hasSizeGreaterThanOrEqualTo(3);
        List<Map<String, Object>> history = database.rows(HISTORY);
        assertThat(history).hasSize(files.size());
        List<String> names = new ArrayList<>(files.keySet());
        for (int i = 0; i < history.size(); i++) {
            Map<String, Object> row = history.get(i);
            assertThat(((Number) row.get("version")).intValue()).isEqualTo(i + 1);
            assertThat(row.get("file_name")).isEqualTo(names.get(i));
            assertThat(row.get("sha256")).isEqualTo(files.get(names.get(i)));
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");
            Instant appliedAt = ((Timestamp) row.get("applied_at")).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
            assertThat(appliedAt).isBetween(before, Instant.now().plus(Duration.ofMinutes(1)));
        }
        assertThat(output.info()).contains(String.valueOf(files.size())).contains("V1").contains("V" + files.size());
    }

    @Test
    @DisplayName("CLI-004 migrate again changes nothing: exit 0, the output says the schema is up to date, the history rows and the data are exactly as they were")
    void migratesAnUpToDateDatabaseAgain(Cli cli) throws Exception {
        assertThat(run(cli, new RecordingOutput(), "migrate").exitCode()).isZero();
        List<Map<String, Object>> historyBefore = database.rows(HISTORY);
        String dataBefore = dataDigest();
        RecordingOutput output = new RecordingOutput();

        Outcome again = run(cli, output, "migrate");

        assertThat(again.exitCode()).isZero();
        assertThat(again.failure()).isNull();
        assertThat(database.rows(HISTORY)).isEqualTo(historyBefore);
        assertThat(dataDigest()).isEqualTo(dataBefore);
        assertThat(output.info()).contains("up to date");
    }

    @Test
    @DisplayName("CLI-038 a command that needs the schema on a database without it is SCHEMA_NOT_READY (exit 1) naming the migrate command; nothing is created")
    void otherCommandsRefuseAnUnmigratedDatabase(Cli cli, CapturedOutput captured) {
        Outcome result = run(cli, new RecordingOutput(), "project-add", "e2e", "E2E", "End-to-end test project");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.SCHEMA_NOT_READY);
        assertThat(captured.getAll()).contains("migrate");
        assertThat(database.rows("SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name = 'schema_history'", database.schemaName()).getFirst().get("n")).isEqualTo(0L);
    }

    @Test
    @DisplayName("CLI-038 the same command after migrate works")
    void afterMigrateTheCommandWorks(Cli cli) {
        assertThat(run(cli, new RecordingOutput(), "migrate").exitCode()).isZero();

        Outcome result = run(cli, new RecordingOutput(), "project-add", "e2e", "E2E", "End-to-end test project");

        assertThat(result.exitCode()).isZero();
        assertThat(database.rows("SELECT id FROM project")).hasSize(1);
    }

    @Test
    @DisplayName("CLI-042 a schema file whose digest differs from the applied one stops migrate: MIGRATION_FAILED (exit 1) naming the file, and the attempt is recorded as FAILED")
    void aChangedFileStopsTheMigration(Cli cli, CapturedOutput captured) {
        assertThat(run(cli, new RecordingOutput(), "migrate").exitCode()).isZero();
        assertThat(database.update("UPDATE schema_history SET sha256 = ? WHERE version = 1", "f".repeat(64))).isEqualTo(1);

        Outcome result = run(cli, new RecordingOutput(), "migrate");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.MIGRATION_FAILED);
        // the failure of the database module is an InfrastructureException with a code; its summary names the code and the file
        assertThat(captured.getAll()).contains("SCHEMA_DIGEST_MISMATCH").contains("V1");
        assertThat(database.rows("SELECT outcome FROM schema_history WHERE version = 1 ORDER BY applied_at")
                .getLast().get("outcome")).isEqualTo("FAILED");
    }
}
