package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SchemaHistoryRepository.Outcome;
import org.pgledger.database.repository.SchemaHistoryRepository.Row;
import org.pgledger.database.schema.LedgerSchemaGuardConfiguration;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.schema.SchemaVerifier;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.LedgerDatabaseExtension;
import org.pgledger.database.support.Parallel;
import org.pgledger.database.support.TestClock;
import org.pgledger.domain.value.Sha256;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SCH-001 .. SCH-011, SCH-015, SCH-020, SCH-021: the schema runner on H2. */
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

    private HikariDataSource pool;
    private Databases databases;
    private final TestClock clock = new TestClock(T0);
    private int directories;

    @BeforeEach
    void setUp(Databases fresh) {
        databases = fresh;
        pool = databases.newDataSource();
    }

    @AfterEach
    void tearDown() {
        pool.close();
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
        return new SchemaProperties(dir.toUri().toString(), Databases.LONG_TEXT, true, schema,
                Duration.ofMillis(200));
    }

    private SchemaMigrator migrator(Path dir) {
        return new SchemaMigrator(pool, new PathMatchingResourcePatternResolver(), props(dir, null), clock);
    }

    private SchemaVerifier verifier(Path dir) {
        return new SchemaVerifier(pool, new PathMatchingResourcePatternResolver(), props(dir, null));
    }

    private List<Row> history() {
        return new SchemaHistoryRepository(pool).rows();
    }

    private boolean tableExists(String name) {
        return JdbcClient.create(pool).sql("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) "
                + "= :name").param("name", name).query(Integer.class).single() > 0;
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

    private static Row success(int version, String file, String text, Instant at) {
        return new Row(version, file, digest(text), at, Outcome.SUCCESS);
    }

    private static Row failed(int version, String file, String text, Instant at) {
        return new Row(version, file, digest(text), at, Outcome.FAILED);
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
        SchemaMigrator real = new SchemaMigrator(pool, new PathMatchingResourcePatternResolver(),
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true, null,
                        Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), clock);
        assertThat(real.migrate()).containsExactly(1, 2, 3);
        assertThat(history()).isEqualTo(List.of(
                success(1, "V1__core_tables.sql", resource("db/V1__core_tables.sql"), T0),
                success(2, "V2__history_and_receipts.sql", resource("db/V2__history_and_receipts.sql"), T0),
                success(3, "V3__seed_codes.sql", resource("db/V3__seed_codes.sql"), T0)));
        assertThat(tableExists("defect")).isTrue();
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

    @Test
    void sch003_021_aChangedFileIsRecordedAsFailedAndStopsEverything() {
        Path original = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", THREE));
        String editedTwo = TWO + "-- edited\n";
        Path edited = files(Map.of("V1__one.sql", ONE, "V2__two.sql", editedTwo, "V3__three.sql", THREE));
        migrator(original).migrate();
        InfrastructureException mismatch = refusedWith(() -> migrator(edited).migrate(),
                InfrastructureCode.SCHEMA_DIGEST_MISMATCH);
        assertThat(mismatch.args()).containsExactly("2", "V2__two.sql", digest(TWO).hex(), digest(editedTwo).hex());
        assertThat(history()).contains(success(2, "V2__two.sql", TWO, T0),
                failed(2, "V2__two.sql", editedTwo, T0.plusNanos(1000)));
        assertThat(history().stream().filter(r -> r.version() == 2)).hasSize(2);
        // the guard uses the latest outcome, not any success row
        refusedWith(() -> verifier(edited).verify(), InfrastructureCode.SCHEMA_DIGEST_MISMATCH);
        refusedWith(() -> verifier(original).verify(), InfrastructureCode.SCHEMA_RECORDED_FAILED);
        // a digest mismatch of a successful version is never retried and appends nothing more
        refusedWith(() -> migrator(edited).migrate(), InfrastructureCode.SCHEMA_DIGEST_MISMATCH);
        assertThat(history().stream().filter(r -> r.version() == 2)).hasSize(2);
        assertThat(tableExists("t_two")).isTrue();
    }

    @Test
    void sch004_005_020_aFailedFileIsRetriedAndEveryAttemptIsKept() {
        Path broken = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", BROKEN_THREE,
                "V4__four.sql", FOUR));
        InfrastructureException failure = refusedWith(() -> migrator(broken).migrate(),
                InfrastructureCode.SCHEMA_MIGRATION_FAILED);
        assertThat(failure.args()).containsExactly("3", "V3__three.sql");
        assertThat(failure.getCause()).as("the database failure is the cause").isNotNull();
        assertThat(history()).isEqualTo(List.of(success(1, "V1__one.sql", ONE, T0), success(2, "V2__two.sql", TWO, T0),
                failed(3, "V3__three.sql", BROKEN_THREE, T0)));
        assertThat(tableExists("t_four")).as("V4 waits for V3").isFalse();
        // H2 commits every DDL statement at once, so the statement that ran before the failure stays (a DBMS with
        // transactional DDL rolls the whole file back: checked at the test server stage)
        assertThat(tableExists("t_half")).isTrue();
        refusedWith(() -> verifier(broken).verify(), InfrastructureCode.SCHEMA_RECORDED_FAILED);
        // a second try without a fix appends a second failed row, one microsecond later
        refusedWith(() -> migrator(broken).migrate(), InfrastructureCode.SCHEMA_MIGRATION_FAILED);
        assertThat(history().stream().filter(r -> r.version() == 3).map(Row::appliedAt).toList())
                .containsExactly(T0, T0.plusNanos(1000));
        // after the fix V3 and V4 apply
        Path fixed = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", THREE, "V4__four.sql",
                FOUR));
        clock.set(T0.plusSeconds(60));
        assertThat(migrator(fixed).migrate()).containsExactly(3, 4);
        assertThat(history().stream().filter(r -> r.version() == 3).toList()).isEqualTo(List.of(
                failed(3, "V3__three.sql", BROKEN_THREE, T0), failed(3, "V3__three.sql", BROKEN_THREE,
                        T0.plusNanos(1000)), success(3, "V3__three.sql", THREE, T0.plusSeconds(60))));
        verifier(fixed).verify();
        assertThat(tableExists("t_four")).isTrue();
    }

    @Test
    void sch006_aGapInTheFilesIsRefused() {
        Path gap = files(Map.of("V1__one.sql", ONE, "V3__three.sql", THREE));
        assertThat(refusedWith(() -> migrator(gap).migrate(), InfrastructureCode.SCHEMA_VERSION_GAP).args())
                .containsExactly("2", "3");
    }

    @Test
    void sch006_aLateFileBelowAnAppliedVersionIsRefused() {
        Path all = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO, "V3__three.sql", THREE));
        migrator(files(Map.of("V1__one.sql", ONE))).migrate();
        new SchemaHistoryRepository(pool).append(success(3, "V3__three.sql", THREE, T0));
        assertThat(refusedWith(() -> migrator(all).migrate(), InfrastructureCode.SCHEMA_OUT_OF_ORDER).args())
                .containsExactly("2", "V2__two.sql", "3");
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

    private List<String> twoMigrationsAtOnce() {
        SchemaProperties real = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true,
                null, Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT));
        Callable<String> migrate = () -> {
            Throwable refused = catchThrowable(
                    () -> new SchemaMigrator(pool, new PathMatchingResourcePatternResolver(), real, clock).migrate());
            if (refused == null) {
                return "done";
            }
            assertThat(refused).isInstanceOf(InfrastructureException.class);
            return "refused";
        };
        return Parallel.all(List.of(migrate, migrate));
    }

    @Test
    void sch009_twoMigrationsAtOnceLeaveTheSchemaWholeWithOneSuccessPerVersion() {
        assertThat(twoMigrationsAtOnce()).as("the loser waits or is refused cleanly").contains("done")
                .allMatch(outcome -> "done".equals(outcome) || "refused".equals(outcome));
        assertThat(history().stream().filter(r -> r.outcome() == Outcome.SUCCESS).map(Row::version))
                .containsExactly(1, 2, 3);
        assertThat(tableExists("request_receipt")).isTrue();
        assertThat(tableExists("defect")).isTrue();
        assertThat(JdbcClient.create(pool).sql("SELECT COUNT(*) FROM common_code").query(Integer.class).single())
                .isEqualTo(10);
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
                        "ledger.schema.location=" + dir.toUri(), "ledger.schema.long-text-type=" + Databases.LONG_TEXT,
                        "ledger.schema.require-utc=false")
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("dataSource", pool);
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
        startApplication(two).close();
    }

    @Test
    void sch010_aDatabaseThatWasNeverMigratedIsRefused() {
        Path two = files(Map.of("V1__one.sql", ONE, "V2__two.sql", TWO));
        try (HikariDataSource empty = databases.newDataSource()) {
            refusedWith(() -> new SchemaVerifier(empty, new PathMatchingResourcePatternResolver(), props(two, null))
                    .verify(), InfrastructureCode.SCHEMA_HISTORY_MISSING);
        }
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
    void sch015_aLegacySchemaIsLeftUntouched() throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA ledger");
            s.execute("CREATE TABLE ledger.defect (id VARCHAR(20) NOT NULL, title VARCHAR(50), "
                    + "CONSTRAINT pk_legacy_defect PRIMARY KEY (id))");
            s.execute("INSERT INTO ledger.defect (id, title) VALUES ('OLD-D-1', 'old')");
        }
        SchemaProperties named = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true,
                "pgledger", Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT));
        new SchemaMigrator(pool, new PathMatchingResourcePatternResolver(), named, clock).migrate();
        new SchemaVerifier(pool, new PathMatchingResourcePatternResolver(), named).verify();
        JdbcClient jdbc = JdbcClient.create(pool);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema) = 'ledger'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT title FROM ledger.defect WHERE id = 'OLD-D-1'").query(String.class).single())
                .isEqualTo("old");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema) = "
                + "'pgledger'").query(Integer.class).single()).isEqualTo(18);
    }
}
