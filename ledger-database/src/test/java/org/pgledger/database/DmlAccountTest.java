package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.database.TestSchema;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
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
import org.junit.jupiter.params.ParameterizedTest;
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
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.support.Databases;
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

/**
 * REPO-023, REPO-024, REPO-038 and SCH-014: the application runs on a least-privilege account (the owner applies
 * the schema and the grants of {@code db-templates/dml-grants.sql} through {@code GrantApplier}, INS-001 .. INS-003); the history cannot be changed through it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LedgerDatabaseExtension.class)
class DmlAccountTest {

    /** PostgreSQL SQLState 42501: insufficient privilege. */
    private static final String PERMISSION_DENIED = "42501";

    private Env shared;

    /**
     * A schema of its own in the test database, owned by the owner account, with the application account of the tests
     * ({@code ledger.test.app-user}) as the restricted account. The owner account cannot create roles, so the account is the
     * login that the tests are configured with; what it may do exists only in this schema and goes with the drop of it.
     * The schema name is the product's {@code ledger.schema.name}.
     */
    private record Env(TestSchema schema, DataSource app) implements AutoCloseable {
        String name() {
            return schema.name();
        }

        @Override
        public void close() {
            schema.close();
        }
    }

    /** A new schema with the application account allowed to see it; the schema is applied and granted when asked. */
    private Env environment(boolean migrate, boolean grant) {
        TestDatabase database = TestDatabase.shared();
        TestSchema schema = database.newSchema();
        String account = database.settings().appUser();
        schema.allowUsage(account);
        SchemaProperties properties = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true,
                schema.name(), java.time.Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT));
        if (migrate) {
            new SchemaMigrator(database.pool(), new PathMatchingResourcePatternResolver(), properties,
                    Clock.systemUTC()).migrate();
        }
        if (grant) {
            // the real path of the installer: the grant template applied by GrantApplier through the owner (DDL) account
            new GrantApplier(database.pool(), properties).apply(account);
        }
        return new Env(schema, TestDatabase.scoped(database.appPool(), schema.name()));
    }

    private static void refused(DataSource app, String sql) {
        assertThatThrownBy(() -> {
            try (Connection c = app.getConnection(); Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }).as(sql).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).as("a refusal for a missing right, not a mistake in the statement")
                        .isEqualTo(PERMISSION_DENIED));
    }

    private static void allowed(DataSource app, String sql) throws SQLException {
        try (Connection c = app.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static Stream<String> statementsTheApplicationAccountMayRun() {
        return Stream.of("INSERT INTO history (id, occurred_at, author_id, login, actor_assurance, entity, entity_id, op, "
                + "changes, reason, request_id) VALUES (1, TIMESTAMP '2026-11-05 10:00:00', 'claude:a', "
                + "'ledger_author', 'DECLARED', 'DEFECT', 'X', 'CREATE', '{}', 'a reason of length', "
                + "'11111111-1111-1111-1111-111111111111')",
                "INSERT INTO owner_audit (id, occurred_at, actor, operation, subject) VALUES (1, TIMESTAMP '2026-11-05 10:00:00', "
                        + "'token:owner', 'GRANT_ISSUED', 'claude:a|p')", "SELECT COUNT(*) FROM owner_audit",
                "SELECT COUNT(*) FROM schema_history");
    }

    static Stream<String> statementsTheApplicationAccountMustNotRun() {
        return Stream.of(
                "UPDATE history SET reason = 'changed by the application'",
                "UPDATE history SET before_data = NULL",
                "UPDATE owner_audit SET actor = 'someone else'",
                "DELETE FROM owner_audit",
                "TRUNCATE TABLE owner_audit",
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
        assertThat(shared().schema()).isNotNull();
    }

    /** One environment for the statement tests of the class, built on first use. */
    private synchronized Env shared() throws SQLException, IOException {
        if (shared == null) {
            shared = environment(true, true);
        }
        return shared;
    }

    @AfterAll
    void closeSharedEnvironment() {
        if (shared != null) {
            shared.close();
        }
    }

    @Test
    void theWholeWorkflowRunsOnTheRestrictedAccount() throws Exception {
        try (Env env = environment(true, true); LedgerTestContext ctx = LedgerTestContext.onMigrated(env.app(), env.name())) {
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
            assertThat(List.of(Counter.values())).hasSize(6);
        }
    }

    @Test
    void sch014_theMigrationIsRefusedForTheApplicationAccountButItCanReadTheHistory() throws Exception {
        try (Env fresh = environment(false, false)) {
            assertThatThrownBy(() -> new SchemaMigrator(fresh.app(), new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true, null,
                            java.time.Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), Clock.systemUTC())
                    .migrate())
                    .isInstanceOf(InfrastructureException.class);
        }
        try (Env migrated = environment(true, true)) {
            allowed(migrated.app(), "SELECT version, outcome FROM schema_history");
        }
    }
}
