// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.database.repository.DatabaseEncodingRepository;
import org.veridger.database.repository.DatabaseEncodingRepository.EncodingProbe;
import org.veridger.database.repository.SchemaHistoryRepository;
import org.veridger.database.repository.SchemaHistoryRepository.Outcome;
import org.veridger.database.repository.SchemaHistoryRepository.Row;
import org.veridger.database.repository.SqlBuilder;
import org.veridger.database.schema.LedgerSchemaGuardConfiguration;
import org.veridger.database.schema.DatabaseEncoding;
import org.veridger.database.schema.MigrationFile;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.database.schema.SchemaMigrator;
import org.veridger.database.schema.SchemaProperties;
import org.veridger.database.schema.SchemaVerifier;
import org.veridger.database.support.Databases;
import org.veridger.database.support.LedgerDatabaseExtension;
import org.veridger.database.support.Parallel;
import org.veridger.database.support.TestClock;
import org.veridger.domain.value.Sha256;
import org.veridger.testing.database.TestDatabase;
import org.veridger.testing.database.TestSchema;
import org.veridger.testing.database.TestSchemaServices;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** SCH-001 .. SCH-011, SCH-015, SCH-020, SCH-021, SCH-034: the schema runner on the PostgreSQL of the test PC, one schema per test. */
@ExtendWith(LedgerDatabaseExtension.class)
class SchemaMigratorTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final String ONE = "CREATE TABLE t_one (id INTEGER NOT NULL, note VARCHAR(50), "
            + "CONSTRAINT pk_t_one PRIMARY KEY (id));\n";
    private static final String TWO = "CREATE TABLE t_two (id INTEGER NOT NULL, CONSTRAINT pk_t_two PRIMARY KEY (id));\n";
    private static final String THREE = "CREATE TABLE t_three (id INTEGER NOT NULL, "
            + "CONSTRAINT pk_t_three PRIMARY KEY (id));\n";
    private static final String FOUR = "CREATE TABLE t_four (id INTEGER NOT NULL, "
            + "CONSTRAINT pk_t_four PRIMARY KEY (id));\n";
    private static final String BROKEN_THREE = "CREATE TABLE t_half (id INTEGER NOT NULL);\n"
            + "INSERT INTO no_such_table (id) VALUES (1);\n";

    @TempDir
    Path temp;

    /** The data source of the schema of this test (every connection uses it); the schema is dropped with {@link Databases}. */
    private DataSource pool;
    private String schema;
    private Databases databases;
    private final TestClock clock = new TestClock(T0);
    private int directories;

    @BeforeEach
    void setUp(Databases fresh) {
        databases = fresh;
        TestSchema created = databases.newSchema();
        schema = created.name();
        pool = created.dataSource();
    }

    /** A directory of schema files, written as UTF-8 exactly as given. */
    private Path files(Map<String, String> content) {
        try {
            Path dir = Files.createDirectories(temp.resolve("d" + directories++));
            for (var entry : content.entrySet()) {
                Files.writeString(dir.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SchemaProperties props(Path dir, String schema) {
        return new SchemaProperties(dir.toUri().toString(), true, schema);
    }

    private SchemaMigrator migrator(Path dir) {
        return TestSchemaServices.migrator(pool, props(dir, null), clock);
    }

    private SchemaVerifier verifier(Path dir) {
        return TestSchemaServices.verifier(pool, props(dir, null));
    }

    private List<Row> history() {
        return TestSchemaServices.history(pool).rows();
    }

    private boolean tableExists(String name) {
        return JdbcClient.create(pool).sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = "
                + ":schema AND table_name = :name").param("schema", schema).param("name", name)
                .query(Integer.class).single() > 0;
    }

    private static Sha256 digest(String text) {
        return Sha256.ofText(text.replace("\r\n", "\n"));
    }

    /** The code of the failure is the contract: the call must fail with an InfrastructureException of exactly this code. */
    private static InfrastructureException refusedWith(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            InfrastructureCode code) {
        InfrastructureException refused = catchThrowableOfType(InfrastructureException.class, call);
        assertThat(refused).as("an InfrastructureException is thrown").isNotNull();
        assertThat(refused.code()).isEqualTo(code);
        return refused;
    }

    private static Row succeeded(int version, int attempt, String file, String text, Instant at) {
        return new Row(version, attempt, file, digest(text), at, Outcome.SUCCEEDED);
    }

    private static Row failed(int version, int attempt, String file, String text, Instant at) {
        return new Row(version, attempt, file, digest(text), at, Outcome.FAILED);
    }

    private static String resource(String name) {
        try {
            return new ClassPathResource(name, SchemaMigratorTest.class.getClassLoader())
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void sch001_aFreshDatabaseGetsEveryRealFileInOrderWithItsDigest() {
        SchemaMigrator real = TestSchemaServices.migrator(pool,
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true, null), clock);
        assertThat(real.migrate()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);
        assertThat(history()).isEqualTo(List.of(
                succeeded(1, 1, "V1__core_tables.sql", resource("db/V1__core_tables.sql"), T0),
                succeeded(2, 1, "V2__history_and_receipts.sql", resource("db/V2__history_and_receipts.sql"), T0),
                succeeded(3, 1, "V3__seed_codes.sql", resource("db/V3__seed_codes.sql"), T0),
                succeeded(4, 1, "V4__relation_direction.sql", resource("db/V4__relation_direction.sql"), T0),
                succeeded(5, 1, "V5__common_code_version.sql", resource("db/V5__common_code_version.sql"), T0),
                succeeded(6, 1, "V6__driver_audit_key.sql", resource("db/V6__driver_audit_key.sql"), T0),
                succeeded(7, 1, "V7__owner_passkey.sql", resource("db/V7__owner_passkey.sql"), T0),
                succeeded(8, 1, "V8__attachments.sql", resource("db/V8__attachments.sql"), T0),
                succeeded(9, 1, "V9__spec_parent_and_upload_gate.sql",
                        resource("db/V9__spec_parent_and_upload_gate.sql"), T0),
                succeeded(10, 1, "V10__deliverable_location_key.sql",
                        resource("db/V10__deliverable_location_key.sql"), T0),
                succeeded(11, 1, "V11__deliverable_location_key_non_empty.sql",
                        resource("db/V11__deliverable_location_key_non_empty.sql"), T0),
                succeeded(12, 1, "V12__performance_indexes.sql",
                        resource("db/V12__performance_indexes.sql"), T0),
                succeeded(13, 1, "V13__owner_passkey_counter.sql",
                        resource("db/V13__owner_passkey_counter.sql"), T0),
                succeeded(14, 1, "V14__project_display_name_and_date_indexes.sql",
                        resource("db/V14__project_display_name_and_date_indexes.sql"), T0),
                succeeded(15, 1, "V15__project_versions.sql", resource("db/V15__project_versions.sql"), T0),
                succeeded(16, 1, "V16__project_version_items.sql", resource("db/V16__project_version_items.sql"), T0),
                succeeded(17, 1, "V17__milestone_archive.sql", resource("db/V17__milestone_archive.sql"), T0),
                succeeded(18, 1, "V18__granular_session_grants.sql", resource("db/V18__granular_session_grants.sql"), T0)));
        assertThat(tableExists("issue")).isTrue();
        assertThat(tableExists("spec")).isTrue();
        assertThat(tableExists("project_version")).isTrue();
        assertThat(tableExists("issue_project_version")).isTrue();
        assertThat(tableExists("project_version_item")).isTrue();
        assertThat(tableExists("milestone_archive")).isTrue();
        JdbcClient jdbc = JdbcClient.create(pool);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = :schema AND table_name IN ('project_version', 'issue_project_version', 'project_version_item')
                """).param("schema", schema).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = :schema AND table_name IN ('project_version', 'issue_project_version', 'project_version_item')
                  AND is_nullable = 'NO'
                """).param("schema", schema).query(Long.class).single()).isZero();
    }

    @Test
    void v14BackfillsProjectNamesFromCodesWithoutChangingExistingFields() throws IOException {
        var shipped = MigrationFile.load(new PathMatchingResourcePatternResolver(), SchemaProperties.DEFAULT_LOCATION);
        Map<String, String> throughV13 = new LinkedHashMap<>();
        for (var file : shipped) {
            if (file.version() <= 13) {
                throughV13.put(file.fileName(), file.text());
            }
        }
        Path dir = files(throughV13);
        migrator(dir).migrate();
        JdbcClient jdbc = JdbcClient.create(pool);
        jdbc.sql("INSERT INTO project (id, code, title, next_issue_no, version, created_by) "
                + "VALUES ('old', 'OLD', 'Original description', 7, 2, 'user:owner')").update();
        String v14 = "V14__project_display_name_and_date_indexes.sql";
        Files.writeString(dir.resolve(v14), resource("db/" + v14));
        assertThat(migrator(dir).migrate()).containsExactly(14);
        assertThat(jdbc.sql("SELECT code, title, display_name, next_issue_no, version FROM project WHERE id = 'old'")
                .query().singleRow()).containsEntry("code", "OLD").containsEntry("title", "Original description")
                .containsEntry("display_name", "OLD").containsEntry("next_issue_no", 7L).containsEntry("version", 2L);
    }

    /** RUN-004: the declaration that the dialect of SqlBuilder gives for the placeholder on the test PostgreSQL holds a long text. */
    @Test
    void run004_theLongTextTypeOfTheDialectHoldsALongText() {
        String type = new SqlBuilder(pool).longTextType();
        JdbcClient jdbc = JdbcClient.create(pool);
        jdbc.sql("CREATE TABLE t_long (id INTEGER NOT NULL, body " + type + ", CONSTRAINT pk_t_long PRIMARY KEY (id))").update();
        // 40000 characters of mixed Korean and ASCII text
        String text = "긴 text 글 ".repeat(5_715).substring(0, 40_000);
        assertThat(text).hasSize(40_000);
        assertThat(type).as("the declaration of the PostgreSQL dialect").isEqualTo("TEXT");

        jdbc.sql("INSERT INTO t_long (id, body) VALUES (1, :body)").param("body", text).update();

        assertThat(jdbc.sql("SELECT body FROM t_long WHERE id = 1").query(String.class).single()).isEqualTo(text);
        jdbc.sql("DROP TABLE t_long").update();
        // and the files are applied with it: the placeholder is gone from the DDL that ran, and a long text fits the real columns
        Path dir = files(Map.of("V1__long.sql", "CREATE TABLE t_files (id INTEGER NOT NULL, body ${long_text}, "
                + "CONSTRAINT pk_t_files PRIMARY KEY (id));\n"));
        assertThat(migrator(dir).migrate()).containsExactly(1);
        jdbc.sql("INSERT INTO t_files (id, body) VALUES (1, :body)").param("body", text).update();
        assertThat(jdbc.sql("SELECT LENGTH(body) FROM t_files WHERE id = 1").query(Integer.class).single()).isEqualTo(40_000);
        jdbc.sql("INSERT INTO t_files (id, body) VALUES (2, :body)").param("body", text).update();
        assertThat(jdbc.sql("SELECT body FROM t_files WHERE id = 2").query(String.class).single()).isEqualTo(text);
    }

    /**
     * RUN-022: the dialect is asked before any statement that changes the database. The builder of the migrator reports a product
     * that has no dialect (the database itself is the test PostgreSQL), and the migrator names a schema to create: it is refused with
     * DATABASE_PRODUCT_UNSUPPORTED and neither that schema nor the history table exists afterwards.
     */
    @Test
    void run022_aProductWithoutADialectIsRefusedBeforeAnySchemaOrTableIsMade() throws SQLException {
        DataSource derby = org.mockito.Mockito.mock(DataSource.class);
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        java.sql.DatabaseMetaData metaData = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        org.mockito.Mockito.when(derby.getConnection()).thenReturn(connection);
        org.mockito.Mockito.when(connection.getMetaData()).thenReturn(metaData);
        org.mockito.Mockito.when(metaData.getDatabaseProductName()).thenReturn("Apache Derby");
        Path dir = files(Map.of("V1__one.sql", ONE));
        String named = "lt4_no_dialect";
        SchemaMigrator refusing = TestSchemaServices.migrator(pool, props(dir, named), clock, new SqlBuilder(derby));

        refusedWith(refusing::migrate, InfrastructureCode.DATABASE_PRODUCT_UNSUPPORTED);

        SchemaHistoryRepository repository = TestSchemaServices.history(pool);
        assertThat(repository.schemaExists(named)).as("the named schema is not created").isFalse();
        assertThat(repository.tableExists()).as("the history table is not created").isFalse();
    }

    @Test
    void sch002_aSecondRunDoesNothing() {
        Path dir = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO));
        assertThat(migrator(dir).migrate()).containsExactly(1, 2);
        List<Row> before = history();
        assertThat(migrator(dir).migrate()).isEmpty();
        assertThat(history()).isEqualTo(before);
        verifier(dir).verify();
    }

    /**
     * RUN-011: one attempt is one transaction that starts with its own row (FAILED, the claim) and ends by turning that one row to
     * SUCCEEDED. The file reads the history from inside its own transaction: it sees its claim as FAILED, and the version before it
     * already SUCCEEDED; after the commit both are SUCCEEDED, and no other row was touched.
     */
    @Test
    void run011_theFirstStatementClaimsTheAttemptAndTheLastOneTurnsThatRowToSucceeded() {
        String seeing = "CREATE TABLE t_seen (version BIGINT NOT NULL, attempt INTEGER NOT NULL, outcome VARCHAR(10) NOT NULL, "
                + "rows_now INTEGER NOT NULL);\n"
                + "INSERT INTO t_seen (version, attempt, outcome, rows_now) SELECT version, attempt, outcome, "
                + "(SELECT COUNT(*) FROM schema_history) FROM schema_history WHERE version = 2;\n";
        Path dir = files(Map.of("V1__one.sql", ONE, "V2__seeing.sql", seeing));

        assertThat(migrator(dir).migrate()).containsExactly(1, 2);

        assertThat(JdbcClient.create(pool).sql("SELECT version || ':' || attempt || ':' || outcome || ':' || rows_now FROM t_seen")
                .query(String.class).list()).as("seen by V2 itself: its own row FAILED, two rows in all").containsExactly("2:1:FAILED:2");
        assertThat(history()).isEqualTo(List.of(succeeded(1, 1, "V1__one.sql", ONE, T0),
                succeeded(2, 1, "V2__seeing.sql", seeing, T0)));
    }

    /**
     * RUN-012 (SCH-009): the same version by two runners at once. The first runner is held inside its file (its claim is taken and
     * not committed) by a lock the test holds; the second then claims the same (version, attempt) and waits for the first in the
     * database. Both waits are seen in the lock-wait view of the database ({@code pg_blocking_pids}, which names the holder's
     * backend), with a deadline that fails the test; nothing sleeps. When the lock is released the first commits, the second gets
     * the duplicate key, reads the history once, finds the version SUCCEEDED with its digest and skips it.
     */
    @Test
    void run012_twoRunnersOnTheSameVersionTheSecondWaitsInTheDatabaseAndSkips() throws Exception {
        JdbcClient jdbc = JdbcClient.create(pool);
        jdbc.sql("CREATE TABLE t_gate (id INTEGER NOT NULL)").update();
        String gated = "SELECT COUNT(*) FROM t_gate;\n" + ONE;
        Path dir = files(Map.of("V1__one.sql", gated));
        try (Connection holder = pool.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("LOCK TABLE t_gate IN ACCESS EXCLUSIVE MODE");
            }
            int holderBackend = backendOf(holder);
            try {
                Future<List<Integer>> first = Parallel.start(() -> migrator(dir).migrate());
                int firstBackend = awaitBackendBlockedBy(jdbc, holderBackend);
                Future<List<Integer>> second = Parallel.start(() -> migrator(dir).migrate());
                awaitBackendBlockedBy(jdbc, firstBackend);

                holder.commit();

                assertThat(first.get(60, TimeUnit.SECONDS)).as("the first runner applied V1").containsExactly(1);
                assertThat(second.get(60, TimeUnit.SECONDS)).as("the second runner applied nothing").isEmpty();
            } finally {
                holder.rollback();
            }
        }
        assertThat(history()).as("exactly one row, SUCCEEDED").isEqualTo(List.of(succeeded(1, 1, "V1__one.sql", gated, T0)));
        assertThat(tableExists("t_one")).isTrue();
    }

    private static int backendOf(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** The backends that wait for a lock that the backend {@code holder} holds (the lock-wait view of PostgreSQL). */
    private static List<Integer> backendsBlockedBy(JdbcClient jdbc, int holder) {
        return jdbc.sql("SELECT pid FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND :holder = ANY (pg_blocking_pids(pid))")
                .param("holder", holder).query(Integer.class).list();
    }

    /** Returns the backend that waits for {@code holder}; fails when none does by the deadline. */
    private static int awaitBackendBlockedBy(JdbcClient jdbc, int holder) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(10))
                .until(() -> !backendsBlockedBy(jdbc, holder).isEmpty());
        return backendsBlockedBy(jdbc, holder).getFirst();
    }

    /**
     * RUN-013 (SCH-003, SCH-021): a changed applied file stops the run before anything is applied, and adds no row to the history (the
     * change of a file is not an attempt). The history stays as it was, so the original files are still intact.
     */
    @Test
    void sch003_021_run013_aChangedFileStopsBeforeAnythingIsAppliedAndAddsNoRow() {
        Path original = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", THREE));
        String editedTwo = TWO + "-- edited\n";
        Path edited = files(Map.of("V1__one.sql", ONE, "V2__two.sql", editedTwo, "V3__three.sql", THREE, "V4__four.sql", FOUR));
        migrator(original).migrate();
        List<Row> before = history();

        InfrastructureException mismatch = refusedWith(() -> migrator(edited).migrate(),
                InfrastructureCode.SCHEMA_DIGEST_MISMATCH);

        assertThat(mismatch.args()).containsExactly("2", "V2__two.sql", digest(TWO).hex(), digest(editedTwo).hex());
        assertThat(history()).as("no row is added").isEqualTo(before);
        assertThat(tableExists("t_four")).as("the pending V4 was not applied").isFalse();
        refusedWith(() -> verifier(edited).verify(), InfrastructureCode.SCHEMA_DIGEST_MISMATCH);
        verifier(original).verify();
        // and again: still refused, still no row
        refusedWith(() -> migrator(edited).migrate(), InfrastructureCode.SCHEMA_DIGEST_MISMATCH);
        assertThat(history()).isEqualTo(before);
        assertThat(tableExists("t_two")).isTrue();
    }

    /**
     * RUN-015 (SCH-004, SCH-005, SCH-020): a failed file leaves a FAILED row of its attempt; the next run uses the next attempt for
     * the same version and keeps the earlier rows: attempt 1 and 2 stay FAILED, attempt 3 is SUCCEEDED.
     */
    @Test
    void sch004_005_020_run015_aFailedFileIsRetriedWithTheNextAttemptAndEveryAttemptIsKept() {
        Path broken = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", BROKEN_THREE,
                "V4__four.sql", FOUR));
        InfrastructureException failure = refusedWith(() -> migrator(broken).migrate(),
                InfrastructureCode.SCHEMA_MIGRATION_FAILED);
        assertThat(failure.args()).containsExactly("3", "V3__three.sql");
        assertThat(failure.getCause()).as("the database failure is the cause").isNotNull();
        assertThat(history()).isEqualTo(List.of(succeeded(1, 1, "V1__one.sql", ONE, T0),
                succeeded(2, 1, "V2__two.sql", TWO, T0), failed(3, 1, "V3__three.sql", BROKEN_THREE, T0)));
        assertThat(tableExists("t_four")).as("V4 waits for V3").isFalse();
        // PostgreSQL has transactional DDL: the whole file is rolled back, the statement that ran before the failure is gone
        assertThat(tableExists("t_half")).as("no partial V3 objects (transactional DDL)").isFalse();
        refusedWith(() -> verifier(broken).verify(), InfrastructureCode.SCHEMA_RECORDED_FAILED);
        // a second try without a fix is attempt 2: a new row, the first one is kept
        refusedWith(() -> migrator(broken).migrate(), InfrastructureCode.SCHEMA_MIGRATION_FAILED);
        assertThat(history().stream().filter(r -> r.version() == 3).toList()).isEqualTo(List.of(
                failed(3, 1, "V3__three.sql", BROKEN_THREE, T0), failed(3, 2, "V3__three.sql", BROKEN_THREE, T0)));
        // after the fix V3 and V4 apply: attempt 3
        Path fixed = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", THREE, "V4__four.sql",
                FOUR));
        clock.set(T0.plusSeconds(60));
        assertThat(migrator(fixed).migrate()).containsExactly(3, 4);
        assertThat(history().stream().filter(r -> r.version() == 3).toList()).isEqualTo(List.of(
                failed(3, 1, "V3__three.sql", BROKEN_THREE, T0), failed(3, 2, "V3__three.sql", BROKEN_THREE, T0),
                succeeded(3, 3, "V3__three.sql", THREE, T0.plusSeconds(60))));
        assertThat(history().stream().filter(r -> r.version() == 4).toList())
                .containsExactly(succeeded(4, 1, "V4__four.sql", FOUR, T0.plusSeconds(60)));
        verifier(fixed).verify();
        assertThat(tableExists("t_four")).isTrue();
    }

    /** A data source that refuses every connection once a connection of it was rolled back (the database is gone after the failure). */
    private static DataSource refusingAfterRollback(DataSource target) {
        AtomicBoolean rolledBack = new AtomicBoolean();
        return new DelegatingDataSource(target) {
            @Override
            public Connection getConnection() throws SQLException {
                if (rolledBack.get()) {
                    throw new SQLException("the database is not reachable any more (the test took it away)");
                }
                Connection real = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            if ("rollback".equals(method.getName())) {
                                rolledBack.set(true);
                            }
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
    }

    /** SCH-034: a failure while the failure is recorded does not hide the failure of the file. */
    @Test
    void sch034_theCauseSurvivesAFailureToRecordTheFailure() {
        // the statement of the file fails, the attempt is rolled back, and then the database cannot be reached any more, so the FAILED
        // row cannot be written: the failure of the file is what the caller learns, the failure of recording it is attached to it
        String failing = "INSERT INTO no_such_table (id) VALUES (1);\n";
        migrator(files(Map.of("V1__one.sql", ONE))).migrate();
        Path dir = files(Map.of("V1__one.sql", ONE, "V2__two.sql", failing));

        InfrastructureException failure = refusedWith(
                () -> TestSchemaServices.migrator(refusingAfterRollback(pool), props(dir, null), clock).migrate(),
                InfrastructureCode.SCHEMA_MIGRATION_FAILED);

        assertThat(failure.args()).containsExactly("2", "V2__two.sql");
        assertThat(failure.getCause()).as("the original failure of the file").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("no_such_table");
        assertThat(failure.getSuppressed()).as("the failure of recording it").hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(DataAccessException.class);
        assertThat(history().stream().filter(r -> r.version() == 2)).as("no row of the failed attempt was written").isEmpty();
    }

    /** RUN-014 (SCH-006): a gap in the files is refused before the database is touched. */
    @Test
    void sch006_run014_aGapInTheFilesIsRefused() {
        Path gap = files(Map.of("V1__one.sql", ONE, "V3__three.sql", THREE));
        assertThat(refusedWith(() -> migrator(gap).migrate(), InfrastructureCode.SCHEMA_VERSION_GAP).args())
                .containsExactly("2", "3");
        assertThat(tableExists("schema_history")).as("nothing was created").isFalse();
        assertThat(tableExists("t_one")).isFalse();
    }

    @Test
    void sch006_run014_aLateFileBelowAnAppliedVersionIsRefused() {
        Path all = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", THREE));
        migrator(files(Map.of("V1__one.sql", ONE))).migrate();
        TestSchemaServices.history(pool).append(succeeded(3, 1, "V3__three.sql", THREE, T0));
        List<Row> before = history();
        assertThat(refusedWith(() -> migrator(all).migrate(), InfrastructureCode.SCHEMA_OUT_OF_ORDER).args())
                .containsExactly("2", "V2__two.sql", "3");
        assertThat(history()).isEqualTo(before);
        assertThat(tableExists("t_two")).isFalse();
    }

    /**
     * RUN-029: the encoding of the test database is proved by the probe query of the dialect of its product: the text of three code
     * points comes back whole, which the judgement accepts, and PostgreSQL has no probe of the schema character set. The real
     * database and the real {@code SqlBuilder} (the product name from the connection) answer; nothing is replaced.
     */
    @Test
    void run029_theTestDatabaseAnswersTheEncodingProbeWithTheTextWhole() {
        SqlBuilder dialect = new SqlBuilder(pool);
        DatabaseEncodingRepository encodings = TestSchemaServices.encodings(pool, dialect);

        EncodingProbe probe = encodings.encodingProbe();

        assertThat(probe.value()).as("the probe text, whole").isEqualTo("한글😀").isEqualTo(SqlBuilder.ENCODING_PROBE_TEXT);
        assertThat(probe.length()).as("code points").isEqualTo(3);
        assertThat(dialect.countsCodePoints()).isTrue();
        DatabaseEncoding.requireUtf8(dialect.countsCodePoints(), probe.length(), probe.value());
        assertThat(encodings.schemaCharsets()).as("PostgreSQL has no schema character set probe").isEmpty();
    }

    /**
     * RUN-033: {@code migrate} on the test PostgreSQL with the real migrator and the real repository, and a {@link SqlBuilder} of the test
     * that changes the text of one probe query only (nothing of the product is replaced); the encoding is refused before any DDL: the
     * schema that the properties name is not made and neither is the history table nor a table of the file.
     */
    private InfrastructureException encodingRefusedBeforeAnyDdl(SqlBuilder dialect, String named) {
        Path dir = files(Map.of("V1__one.sql", ONE));
        SchemaMigrator refusing = TestSchemaServices.migrator(pool, props(dir, named), clock, dialect);

        InfrastructureException refused = refusedWith(refusing::migrate, InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8);

        SchemaHistoryRepository repository = TestSchemaServices.history(pool);
        assertThat(repository.schemaExists(named)).as("the named schema is not created").isFalse();
        assertThat(repository.tableExists()).as("the history table is not created").isFalse();
        assertThat(tableExists("t_one")).as("no file is applied").isFalse();
        return refused;
    }

    /** RUN-033 (a): a database that answers with the right length and a text that lost the Korean letters. */
    @Test
    void run033_aLossyAnswerIsRefusedBeforeAnyDdl() {
        SqlBuilder lossy = new SqlBuilder(pool) {
            @Override
            public String generateEncodingProbe() {
                return "SELECT CHAR_LENGTH(CAST(? AS VARCHAR(40))), REPLACE(CAST(? AS VARCHAR(40)), '한글', '??')";
            }
        };

        InfrastructureException refused = encodingRefusedBeforeAnyDdl(lossy, "ep_lossy_value");

        assertThat(refused.args()).containsExactly("length=3, value=??😀");
    }

    /** RUN-033 (b): a probe that fails on the real database with a data exception (22P05: the text has no equivalent in LATIN1). */
    @Test
    void run033_aDataExceptionOfTheProbeIsRefusedBeforeAnyDdl() {
        SqlBuilder failing = new SqlBuilder(pool) {
            @Override
            public String generateEncodingProbe() {
                return "SELECT LENGTH(CONVERT_TO(CAST(? AS VARCHAR(40)), 'LATIN1')), CAST(? AS VARCHAR(40))";
            }
        };

        InfrastructureException refused = encodingRefusedBeforeAnyDdl(failing, "ep_data_exception");

        assertThat(refused.args()).containsExactly("SQLSTATE=22P05");
        assertThat(refused.getCause()).as("the failure of the database is the cause").isInstanceOf(DataAccessException.class);
    }

    /**
     * RUN-033 (c): the schema probe gives no row. The dialect of the test stays PostgreSQL, because the query of MariaDB and MySQL
     * ({@code @@character_set_server}, {@code DATABASE()}) does not run on it; the test gives the text of the schema probe only, a query of
     * the same shape (three columns) that finds no row, so the real repository (the list) and the real judgement run.
     */
    @Test
    void run033_noSchemaRowIsRefusedBeforeAnyDdl() {
        SqlBuilder noRow = new SqlBuilder(pool) {
            @Override
            public Optional<String> generateSchemaCharsetProbe() {
                return Optional.of("SELECT schema_name, schema_name, 1 FROM information_schema.schemata "
                        + "WHERE schema_name = 'ep_no_such_schema_anywhere'");
            }
        };

        InfrastructureException refused = encodingRefusedBeforeAnyDdl(noRow, "ep_no_schema_row");

        assertThat(refused.args()).containsExactly("schema character set=none");
    }

    /** RUN-029: {@code migrate} runs the probe before anything is changed and applies the files on the test database. */
    @Test
    void run029_migrateProvesTheEncodingAndApplies() {
        Path dir = files(Map.of("V1__one.sql", ONE));

        assertThat(migrator(dir).migrate()).containsExactly(1);
        assertThat(tableExists("t_one")).isTrue();
    }


    @ParameterizedTest
    @ValueSource(strings = {"v1__x.sql", "V1_x.sql", "V01__x.sql", "V1__x.txt", "V1__X.sql"})
    void sch007_namesThatDoNotMatchAreRejectedNotSkipped(String name) {
        Path dir = files(Map.of(name, ONE));
        assertThat(refusedWith(() -> migrator(dir).migrate(), InfrastructureCode.SCHEMA_FILE_NAME_INVALID).args())
                .containsExactly(name);
    }

    @Test
    void sch008_commentsBlankLinesAndSemicolonsInLiteralsAreSplitCorrectly() {
        String text = "-- a comment with a semicolon; inside\n\nCREATE TABLE t_text (id INTEGER NOT NULL, "
                + "note VARCHAR(50), CONSTRAINT pk_t_text PRIMARY KEY (id));\n\n"
                + "/* block; comment */\nINSERT INTO t_text (id, note) VALUES (1, 'one; two');\n"
                + "INSERT INTO t_text (id, note) VALUES (2, 'it''s');\n";
        migrator(files(Map.of("V1__text.sql", text))).migrate();
        assertThat(JdbcClient.create(pool).sql("SELECT note FROM t_text ORDER BY id").query(String.class).list())
                .containsExactly("one; two", "it's");
    }

    /** Two runners over all the files of the product at the same time (no control of the overlap: RUN-012 makes it certain). */
    private List<List<Integer>> twoMigrationsAtOnce() {
        SchemaProperties real = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true, null);
        Callable<List<Integer>> migrate = () -> TestSchemaServices.migrator(pool, real, clock).migrate();
        return Parallel.all(List.of(migrate, migrate));
    }

    @Test
    void sch009_twoMigrationsAtOnceLeaveTheSchemaWholeWithOneSuccessPerVersion() {
        List<List<Integer>> applied = twoMigrationsAtOnce();
        // the loser of a version gets the duplicate key, finds it SUCCEEDED and goes on: it is never refused, and each version is
        // applied by exactly one of the two
        assertThat(applied.stream().flatMap(List::stream).sorted().toList())
                .containsExactlyElementsOf(shippedFiles().stream().map(MigrationFile::version).toList());
        assertThat(history()).as("one row per version: attempt 1, SUCCEEDED")
                .allMatch(r -> r.attempt() == 1 && r.outcome() == Outcome.SUCCEEDED);
        assertThat(history().stream().map(Row::version))
                .containsExactlyElementsOf(shippedFiles().stream().map(MigrationFile::version).toList());
        assertThat(tableExists("request_receipt")).isTrue();
        assertThat(tableExists("issue")).isTrue();
        assertThat(tableExists("spec")).isTrue();
        assertThat(JdbcClient.create(pool).sql("SELECT COUNT(*) FROM common_code").query(Integer.class).single())
                .isEqualTo(18);
    }

    @Test
    void sch010_anApplicationStartsOnlyOnAnUpToDateSchema() {
        Path two = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO));
        migrator(two).migrate();
        Path three = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", THREE));
        assertThat(refusedWith(() -> verifier(three).verify(), InfrastructureCode.SCHEMA_PENDING).args())
                .containsExactly("3", "V3__three.sql");
        verifier(two).verify();
        Path one = files(Map.of("V1__one.sql", ONE));
        assertThat(refusedWith(() -> verifier(one).verify(), InfrastructureCode.SCHEMA_APPLIED_FILE_MISSING).args())
                .containsExactly("2", "V2__two.sql");
    }

    /** A database in some state, the files the starting application carries, and what the refusal must name. */
    private record Refusal(String name, Map<String, String> applied, Map<String, String> running, String message) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Refusal> refusals() {
        Map<String, String> two = Map.of("V1__one.sql", ONE, "V2__two.sql", TWO);
        return Stream.of(
                new Refusal("a version is pending", two, Map.of("V1__one.sql", ONE, "V2__two.sql", TWO,
                        "V3__three.sql", THREE), "SCHEMA_PENDING [3, V3__three.sql]"),
                new Refusal("a version failed", Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql",
                        BROKEN_THREE), Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", BROKEN_THREE),
                        "SCHEMA_RECORDED_FAILED [3, V3__three.sql]"),
                new Refusal("an applied file was edited", two, Map.of("V1__one.sql", ONE, "V2__two.sql",
                        TWO + "-- edited\n"), "SCHEMA_DIGEST_MISMATCH [2, V2__two.sql,"),
                new Refusal("an applied version has no file", two, Map.of("V1__one.sql", ONE),
                        "SCHEMA_APPLIED_FILE_MISSING [2, V2__two.sql]"));
    }

    private ConfigurableApplicationContext startApplication(Path dir) {
        return new SpringApplicationBuilder(LedgerSchemaGuardConfiguration.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=WARN",
                        "ledger.schema.location=" + dir.toUri(), "ledger.schema.require-utc=false")
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("dataSource", pool);
                    context.getBeanFactory().registerSingleton("jdbcClient", JdbcClient.create(pool));
                    context.getBeanFactory().registerSingleton("clock", clock);
                }).run();
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void sch003_010_theApplicationRefusesToStartOnASchemaThatIsNotUpToDate(Refusal refusal) {
        catchThrowable(() -> migrator(files(refusal.applied())).migrate());
        Path running = files(refusal.running());
        assertThatThrownBy(() -> startApplication(running)).isInstanceOf(BeanCreationException.class)
                .hasStackTraceContaining(refusal.message());
    }

    @Test
    void sch010_theApplicationStartsOnAnUpToDateSchema() {
        Path two = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO));
        migrator(two).migrate();
        try (var context = startApplication(two)) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void sch010_aDatabaseThatWasNeverMigratedIsRefused() {
        Path two = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO));
        DataSource empty = databases.newDataSource();
        refusedWith(() -> TestSchemaServices.verifier(empty, props(two, null))
                .verify(), InfrastructureCode.SCHEMA_HISTORY_MISSING);
    }

    @Test
    void sch011_lineEndingsDoNotChangeTheDigest() {
        Path lf = files(Map.of("V1__one.sql", ONE));
        Path crlf = files(Map.of("V1__one.sql", ONE.replace("\n", "\r\n")));
        migrator(lf).migrate();
        assertThat(migrator(crlf).migrate()).isEmpty();
        verifier(crlf).verify();
    }

    @Test
    void sch015_aNeighbourSchemaIsLeftUntouched() {
        // the neighbour schema is another program's schema with a table of its own; the schema of the product is the one the
        // migrator creates
        DataSource database = databases.database().pool();
        String neighbour = databases.newSchema().name();
        String named = databases.reserveSchemaName();
        JdbcClient jdbc = JdbcClient.create(database);
        jdbc.sql("CREATE TABLE " + neighbour + ".defect (id VARCHAR(20) NOT NULL, title VARCHAR(50), "
                + "CONSTRAINT pk_neighbour_defect PRIMARY KEY (id))").update();
        jdbc.sql("INSERT INTO " + neighbour + ".defect (id, title) VALUES ('OLD-D-1', 'old')").update();
        SchemaProperties properties = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true, named);
        // the data source of the product names its schema itself: here the schema that the migrator creates
        DataSource product = TestDatabase.scoped(database, named);
        TestSchemaServices.migrator(product, properties, clock).migrate();
        TestSchemaServices.verifier(product, properties).verify();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = :schema")
                .param("schema", neighbour).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT title FROM " + neighbour + ".defect WHERE id = 'OLD-D-1'").query(String.class).single())
                .isEqualTo("old");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = :schema")
                .param("schema", named).query(Integer.class).single()).isEqualTo(tablesOfTheShippedFiles());
    }

    /** The migration files that the product ships (V1 .. Vn of {@code classpath:db/}), as the migrator reads them. */
    private static List<MigrationFile> shippedFiles() {
        return MigrationFile.load(new PathMatchingResourcePatternResolver(SchemaMigratorTest.class.getClassLoader()),
                SchemaProperties.DEFAULT_LOCATION);
    }

    /** The tables of a migrated schema: one per CREATE TABLE of the shipped files, and the schema_history the migrator makes. */
    private static int tablesOfTheShippedFiles() {
        return 1 + shippedFiles().stream().mapToInt(file -> file.text().split("CREATE TABLE", -1).length - 1).sum();
    }
}
