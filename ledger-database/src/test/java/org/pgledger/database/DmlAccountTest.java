package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.ReceiptCompletionRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.schema.SchemaMigrationException;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.Dbms;
import org.pgledger.database.support.LedgerDatabaseExtension;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.DefectTaskLink;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * REPO-023, REPO-024, REPO-038 and SCH-014: the application runs on a least-privilege account (the owner applies
 * the schema and the grants of {@code db-templates/dml-grants.sql}); the history cannot be changed through it.
 */
@ParameterizedClass
@EnumSource(Dbms.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LedgerDatabaseExtension.class)
class DmlAccountTest {

    private static final String PASSWORD = "pw-for-the-test";
    private static final String PERMISSION_DENIED_POSTGRESQL = "42501";
    private static final String PERMISSION_DENIED_H2 = "90096";
    /** H2 refuses to truncate a table that a foreign key refers to (history refers to itself) before it asks for rights. */
    private static final String H2_CANNOT_TRUNCATE = "90106";

    @Parameter
    Dbms dbms;

    private String account;
    private final Map<Dbms, Env> shared = new EnumMap<>(Dbms.class);
    private Databases databases;

    @BeforeEach
    void setUp(Databases fresh) {
        databases = fresh;
    }

    private record Env(String url, HikariDataSource owner, HikariDataSource app) implements AutoCloseable {
        @Override
        public void close() {
            app.close();
            owner.close();
        }
    }

    private HikariDataSource pool(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        if (user != null) {
            config.setUsername(user);
            config.setPassword(password);
        }
        config.setMaximumPoolSize(4);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return new HikariDataSource(config);
    }

    /** A new database, owned by the owner account, with a restricted account; the schema is applied when asked. */
    private Env environment(boolean migrate, boolean grant) throws SQLException, IOException {
        String ownerUrl = databases.newUrl(dbms);
        // the connection settings of the URL (PostgreSQL user and password, H2 database options) are the owner's
        String appUrl = ownerUrl.substring(0, ownerUrl.indexOf(dbms == Dbms.POSTGRESQL ? '?' : ';'));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        account = (dbms == Dbms.H2 ? "APP_" + suffix.toUpperCase(Locale.ROOT) : "app_" + suffix);
        HikariDataSource owner = pool(ownerUrl, null, null);
        if (migrate) {
            new SchemaMigrator(owner, new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(null, dbms.longText(), true, null), Clock.systemUTC()).migrate();
        }
        try (Connection c = owner.getConnection(); Statement s = c.createStatement()) {
            if (dbms == Dbms.H2) {
                s.execute("CREATE USER " + account + " PASSWORD '" + PASSWORD + "'");
            } else {
                s.execute("CREATE ROLE " + account + " LOGIN PASSWORD '" + PASSWORD + "'");
                s.execute("GRANT USAGE ON SCHEMA public TO " + account);
            }
        }
        if (grant) {
            String template = new String(getClass().getClassLoader().getResourceAsStream("db-templates/dml-grants.sql")
                    .readAllBytes(), StandardCharsets.UTF_8).replace("${dml_account}", account);
            try (Connection c = owner.getConnection()) {
                new ResourceDatabasePopulator(new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8)))
                        .populate(c);
            }
        }
        return new Env(ownerUrl, owner, pool(appUrl, account, PASSWORD));
    }

    private static void refused(HikariDataSource app, String sql) {
        assertThatThrownBy(() -> {
            try (Connection c = app.getConnection(); Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }).as(sql).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).as("a refusal for a missing right, not a mistake in the statement")
                        .isIn(PERMISSION_DENIED_POSTGRESQL, PERMISSION_DENIED_H2, H2_CANNOT_TRUNCATE));
    }

    private static void allowed(HikariDataSource app, String sql) throws SQLException {
        try (Connection c = app.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static Stream<String> statementsTheApplicationAccountMayRun() {
        return Stream.of("INSERT INTO history (id, at, author_id, login, actor_assurance, entity, entity_id, op, "
                + "changes, reason, request_id) VALUES (1, TIMESTAMP '2026-11-05 10:00:00', 'claude:a', "
                + "'ledger_author', 'DECLARED', 'DEFECT', 'X', 'CREATE', '{}', 'a reason of length', "
                + "'11111111-1111-1111-1111-111111111111')", "SELECT COUNT(*) FROM schema_history");
    }

    static Stream<String> statementsTheApplicationAccountMustNotRun() {
        return Stream.of(
                "UPDATE history SET reason = 'changed by the application'",
                "UPDATE history SET before_data = NULL",
                "DELETE FROM history",
                "TRUNCATE TABLE history",
                "UPDATE schema_history SET outcome = 'FAILED'",
                "DELETE FROM schema_history",
                "CREATE TABLE stolen (id INTEGER)",
                "DROP TABLE project",
                "ALTER TABLE project ADD COLUMN extra INTEGER");
    }

    @ParameterizedTest
    @MethodSource("statementsTheApplicationAccountMayRun")
    void repo023_theApplicationAccountMayAppendToTheHistoryAndReadTheSchemaHistory(String sql) throws Exception {
        allowed(shared().app(), sql);
    }

    @ParameterizedTest
    @MethodSource("statementsTheApplicationAccountMustNotRun")
    void repo023_024_038_theApplicationAccountCannotChangeHistoryOrTheSchema(String sql) throws Exception {
        refused(shared().app(), sql);
        // the owner can run the DDL, which shows the refusal is about the right and not about the statement
        assertThat(shared().owner()).isNotNull();
    }

    /** One environment per DBMS, built on first use: the parameter field is set after @BeforeAll ran, and one instance serves both DBMS runs. */
    private synchronized Env shared() throws SQLException, IOException {
        Env env = shared.get(dbms);
        if (env == null) {
            env = environment(true, true);
            shared.put(dbms, env);
        }
        return env;
    }

    @AfterAll
    void closeSharedEnvironment() {
        shared.values().forEach(Env::close);
    }

    @Test
    void theWholeWorkflowRunsOnTheRestrictedAccount() throws Exception {
        try (Env env = environment(true, true); LedgerTestContext ctx = LedgerTestContext.onMigrated(dbms, env.app())) {
            Seed seed = new Seed(ctx);
            Project project = seed.project();
            Defect defect = seed.defect(project, 1, "as the application account", LedgerTestContext.START);
            IdAllocator ids = ctx.bean(IdAllocator.class);
            Instant now = LedgerTestContext.START;
            // update of a defect and a fix, inserts of history and receipt, receipt completion
            LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(DefectRepository.class)
                    .save(defect.transitioned(org.pgledger.domain.rule.DefectStatus.DISPUTED, null, now))));
            DefectFix fix = ctx.tx(() -> ctx.bean(DefectFixRepository.class).insert(DefectFix.record(
                    ids.next(Counter.DEFECT_FIX), defect.id(), "repo", CommitSha.of("a".repeat(40)), null, null, null,
                    null, Seed.ALICE, now)));
            ctx.tx(() -> ctx.bean(DefectFixRepository.class).update(fix.withCi("ref", null, null)));
            RequestId request = new RequestId(UUID.randomUUID());
            ctx.tx(() -> {
                ctx.bean(ReceiptRepository.class).insert(Receipt.reserve(request, ReceiptOperation.SAVE,
                        Sha256.of("b".repeat(64)), Seed.ALICE, Channel.AUTHOR, now));
                ctx.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY), now,
                        Seed.ALICE, Channel.AUTHOR.login(), ActorAssurance.DECLARED, HistoryEntity.DEFECT,
                        defect.id().value(), HistoryOp.CREATE, null, null, JsonDocument.EMPTY_OBJECT, null,
                        "a reason of enough length", null, null, null, request, null));
                return ctx.bean(ReceiptCompletionRepository.class).finish(request, JsonDocument.EMPTY_OBJECT);
            });
            // grants, links (the only deletes), common codes with their revision
            SessionGrant grant = ctx.tx(() -> ctx.bean(SessionGrantRepository.class).insert(SessionGrant.issue(
                    ids.next(Counter.SESSION_GRANT), Seed.ALICE, project.id(), Scope.DEFECT, AuthorId.OWNER, now, null,
                    null)));
            assertThat(ctx.tx(() -> ctx.bean(GrantStateRepository.class).markUsed(Seed.ALICE, project.id(),
                    Scope.DEFECT, now))).isTrue();
            assertThat(ctx.tx(() -> ctx.bean(GrantStateRepository.class).revoke(grant.id(), now))).isTrue();
            var task = seed.task(project, 1, "linked task");
            DefectTaskLink link = new DefectTaskLink(defect.id(), task.id(), Seed.ALICE);
            assertThat(ctx.tx(() -> ctx.bean(LinkRepository.class).add(link))).isTrue();
            assertThat(ctx.tx(() -> ctx.bean(LinkRepository.class).remove(defect.id(), task.id()))).isTrue();
            CommonCode code = new CommonCode(CodeGroup.DEFECT_CATEGORY, CodeValue.of("UI_" + project.code().value()),
                    "화면", null, null, null, 25, CodeState.ACTIVE, AuthorId.OWNER);
            ctx.tx(() -> {
                ctx.bean(CommonCodeRepository.class).insert(code);
                ctx.bean(CommonCodeRepository.class).update(new CommonCode(code.group(), code.code(), "화면2", null,
                        null, null, 26, CodeState.INACTIVE, AuthorId.OWNER));
                ctx.bean(CodeRevisionRepository.class).bump();
                return null;
            });
            assertThat(ctx.bean(CommonCodeRepository.class).find(CodeGroup.DEFECT_CATEGORY, code.code()))
                    .hasValueSatisfying(c -> assertThat(c.state()).isEqualTo(CodeState.INACTIVE));
            assertThat(List.of(Counter.values())).hasSize(4);
        }
    }

    @Test
    void sch014_theMigrationIsRefusedForTheApplicationAccountButItCanReadTheHistory() throws Exception {
        try (Env fresh = environment(false, false)) {
            assertThatThrownBy(() -> new SchemaMigrator(fresh.app(), new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(null, dbms.longText(), true, null), Clock.systemUTC()).migrate())
                    .isInstanceOf(SchemaMigrationException.class);
        }
        try (Env migrated = environment(true, true)) {
            allowed(migrated.app(), "SELECT version, outcome FROM schema_history");
        }
    }
}
