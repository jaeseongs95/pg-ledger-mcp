package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pgledger.database.support.AccountDatabase.allowed;
import static org.pgledger.database.support.AccountDatabase.refused;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectCaseLockRepository;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.OwnerAuditRepository;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.database.repository.ReceiptCompletionRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.database.repository.RowLockRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.repository.SessionKeyRepository;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.database.schema.GrantApplier.Profile;
import org.pgledger.database.schema.SchemaMigrationException;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.support.AccountDatabase;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.LedgerDatabaseExtension;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectCase;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.DefectRelation;
import org.pgledger.domain.model.DefectTaskLink;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.OwnerAuditEntry;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * INS-001 .. INS-006 and INS-010: the two applications run on two least-privilege accounts. A (the MCP server) has exactly
 * the rights of the write paths and reads of the server and nothing on the owner-only tables; B (the admin web and the owner
 * commands of the CLI) has exactly the rights of the owner paths and none on the ledger content; neither can change the
 * history. The rights are those of {@code db-templates/mcp-grants.sql} and {@code admin-grants.sql}, applied by the real
 * {@link GrantApplier}. Which repository a path uses is derived from {@code ledger-application}: the write operations
 * ({@code SaveOperation}, {@code TransitionOperation}, {@code RecordFixOperation}, {@code RecordCaseOperation},
 * {@code LinkOperation}, {@code CorrectOperation}), the {@code WriteGate}, {@code WhoamiService}, {@code ReceiptService},
 * {@code HistoryWriter} and {@code IdAllocator} for A; {@code ProjectService}, {@code CommonCodeService},
 * {@code GrantService}, {@code SessionKeyService} and {@code OwnerAuditWriter} for B.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LedgerDatabaseExtension.class)
class AccountSeparationTest {

    private static final Instant NOW = LedgerTestContext.START;
    private static final AuthorId BY = Seed.ALICE;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private Databases databases;
    private AccountDatabase shared;

    @BeforeEach
    void setUp(Databases fresh) {
        databases = fresh;
    }

    /** One environment for the statement tests of the class, built on first use. */
    private synchronized AccountDatabase shared() throws SQLException {
        if (shared == null) {
            shared = AccountDatabase.create(databases, true, true);
        }
        return shared;
    }

    @AfterAll
    void closeSharedEnvironment() {
        if (shared != null) {
            shared.close();
        }
    }

    // ---- INS-001: account A runs every write path of the MCP server ----

    @Test
    @DisplayName("INS-001 the account of the MCP server runs every statement of every write path and read of the server")
    void ins001_theMcpAccountRunsEveryWritePath() throws Exception {
        try (AccountDatabase db = AccountDatabase.create(databases, true, true);
                LedgerTestContext owner = db.ownerContext();
                LedgerTestContext mcp = db.mcpContext()) {
            // what the owner prepared: the project, a live grant and a live key of the author
            Project project = new Seed(owner).project();
            IdAllocator ownerIds = owner.bean(IdAllocator.class);
            owner.tx(() -> owner.bean(SessionGrantRepository.class).insert(SessionGrant.issue(
                    ownerIds.next(Counter.SESSION_GRANT), BY, project.id(), Scope.DEFECT, AuthorId.OWNER, NOW, null,
                    null)));
            String fingerprint = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            owner.tx(() -> owner.bean(SessionKeyRepository.class).insert(SessionPublicKey.register(
                    ownerIds.next(Counter.SESSION_KEY), BY, "MCow" + fingerprint.substring(0, 40), fingerprint,
                    AuthorId.OWNER, NOW, "note")));

            // the write gate: the live key is read, the live grant is stamped (a conditional UPDATE of session_grant)
            assertThat(mcp.bean(SessionKeyStateRepository.class).findLive(BY)).isPresent();
            assertThat(mcp.tx(() -> mcp.bean(GrantStateRepository.class).markUsed(BY, project.id(), Scope.DEFECT, NOW)))
                    .isTrue();
            assertThat(mcp.bean(GrantStateRepository.class).findLive(BY, NOW)).hasSize(1);

            // ids: the counters of the project and the rows of id_counter
            IdAllocator ids = mcp.bean(IdAllocator.class);
            assertThat(mcp.tx(() -> ids.nextDefectNo(project.code()))).isPositive();
            assertThat(mcp.tx(() -> ids.nextTaskNo(project.code()))).isPositive();

            // ledger_save: insert and update of defects, tasks, conditions; the row locks
            Seed seed = new Seed(mcp);
            Defect defect = seed.defect(project, 1, "as the MCP account", NOW);
            Defect other = seed.defect(project, 2, "the other defect", NOW);
            Task task = seed.task(project, 1, "a task");
            Condition condition = seed.condition(project, project.code().value() + "-C", "1.0", "C");
            assertThat(mcp.tx(() -> mcp.bean(RowLockRepository.class).lock(defect.id()))).isTrue();
            assertThat(mcp.tx(() -> mcp.bean(RowLockRepository.class).lock(task.id()))).isTrue();
            assertThat(mcp.tx(() -> mcp.bean(RowLockRepository.class).lock(condition.id()))).isTrue();
            LedgerTestContext.as(BY, () -> mcp.tx(() -> mcp.bean(DefectRepository.class)
                    .save(defect.transitioned(DefectStatus.DISPUTED, null, NOW))));

            // ledger_record_fix: insert and update of a fix
            DefectFix fix = mcp.tx(() -> mcp.bean(DefectFixRepository.class).insert(DefectFix.record(
                    ids.next(Counter.DEFECT_FIX), other.id(), "repo", CommitSha.of("a".repeat(40)), null, null, null, null,
                    BY, NOW)));
            mcp.tx(() -> mcp.bean(DefectFixRepository.class).update(fix.withCi("ref", null, null)));

            // ledger_record_case: the case lock (an UPDATE of the defect) and the insert of the case
            assertThat(mcp.tx(() -> mcp.bean(DefectCaseLockRepository.class).lockForCase(other.id()))).isTrue();
            mcp.tx(() -> mcp.bean(DefectCaseRepository.class).insert(DefectCase.observe(ids.next(Counter.DEFECT_CASE),
                    other.id(), "seen in the log", null, "the user said so", null, BY, NOW)));

            // ledger_link: insert and delete in the three link tables
            LinkRepository links = mcp.bean(LinkRepository.class);
            DefectTaskLink taskLink = new DefectTaskLink(defect.id(), task.id(), BY);
            DefectRelation relation = new DefectRelation(defect.id(), other.id(), CodeValue.of("same_site"), BY);
            ConditionLink conditionLink = ConditionLink.toTask(condition.id(), task.id(), BY);
            assertThat(mcp.tx(() -> links.add(taskLink))).isTrue();
            assertThat(mcp.tx(() -> links.add(relation))).isTrue();
            assertThat(mcp.tx(() -> links.add(conditionLink))).isTrue();
            assertThat(mcp.tx(() -> links.remove(taskLink.defectId(), taskLink.taskId()))).isTrue();
            assertThat(mcp.tx(() -> links.remove(relation))).isTrue();
            assertThat(mcp.tx(() -> links.remove(conditionLink))).isTrue();

            // every write: the receipt (insert, completion) and the history row (insert only)
            RequestId request = new RequestId(UUID.randomUUID());
            mcp.tx(() -> {
                mcp.bean(ReceiptRepository.class).insert(Receipt.reserve(request, ReceiptOperation.SAVE,
                        Sha256.of("b".repeat(64)), BY, Channel.AUTHOR, NOW));
                mcp.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY), NOW, BY,
                        Channel.AUTHOR.login(), ActorAssurance.DECLARED, HistoryEntity.DEFECT, defect.id().value(),
                        HistoryOp.CREATE, null, null, JsonDocument.EMPTY_OBJECT, null, "a reason of enough length", null,
                        null, null, request, null));
                return mcp.bean(ReceiptCompletionRepository.class).finish(request, JsonDocument.EMPTY_OBJECT);
            });

            // the reads of the server: codes, code revision, projects, schema history
            assertThat(mcp.bean(CommonCodeRepository.class).catalog(CodeGroup.DEFECT_SEVERITY)).isNotNull();
            assertThat(mcp.bean(CodeRevisionRepository.class).current()).isNotNegative();
            assertThat(mcp.bean(ProjectRepository.class).findById(project.id())).isPresent();
            allowed(db.mcp(), "SELECT COUNT(*) FROM schema_history");
            allowed(db.mcp(), "SELECT COUNT(*) FROM history");
        }
    }

    // ---- INS-002 .. INS-003: what account A must not do ----

    static Stream<String> theMcpAccountAndTheSessionKeys() {
        return Stream.of(
                "INSERT INTO session_key DEFAULT VALUES",
                // the statement of the write gate that keeps a live key locked (SessionKeyStateRepository.holdLive):
                // OPEN CONFLICT, see the case INS-002 in docs/testing/TEST_CASES.md
                "UPDATE session_key SET note = note WHERE id = 1 AND revoked_at IS NULL",
                "UPDATE session_key SET revoked_at = TIMESTAMP '2026-11-05 10:00:00'",
                "UPDATE session_key SET public_key = 'replaced'",
                "DELETE FROM session_key");
    }

    @ParameterizedTest
    @MethodSource("theMcpAccountAndTheSessionKeys")
    @DisplayName("INS-002 the MCP account may read the session keys and is refused every write on them")
    void ins002_theMcpAccountCannotWriteTheSessionKeys(String sql) throws Exception {
        allowed(shared().mcp(), "SELECT COUNT(*) FROM session_key");
        refused(shared().mcp(), sql);
    }

    static Stream<String> theMcpAccountAndTheOwnerOnlyTables() {
        return Stream.of(
                "SELECT COUNT(*) FROM owner_audit",
                "INSERT INTO owner_audit DEFAULT VALUES",
                "UPDATE owner_audit SET actor = 'someone else'",
                "DELETE FROM owner_audit",
                "INSERT INTO project DEFAULT VALUES",
                "DELETE FROM project",
                "INSERT INTO common_code DEFAULT VALUES",
                "UPDATE common_code SET name_ko = 'changed'",
                "DELETE FROM common_code",
                "UPDATE code_revision SET revision = revision + 1",
                "DELETE FROM code_revision",
                "INSERT INTO session_grant DEFAULT VALUES",
                "DELETE FROM session_grant",
                "DELETE FROM id_counter",
                "DELETE FROM defect",
                "DELETE FROM task",
                "DELETE FROM defect_fix",
                "DELETE FROM defect_case",
                "DELETE FROM release_condition",
                "DELETE FROM request_receipt");
    }

    @ParameterizedTest
    @MethodSource("theMcpAccountAndTheOwnerOnlyTables")
    @DisplayName("INS-003 the MCP account has no access to the owner audit and no write on the owner-only tables")
    void ins003_theMcpAccountCannotWriteTheOwnerOnlyTables(String sql) throws Exception {
        refused(shared().mcp(), sql);
    }

    // ---- INS-004 .. INS-005: account B ----

    @Test
    @DisplayName("INS-004 the account of the admin web and the owner commands runs the owner paths and reads what the console shows")
    void ins004_theAdminAccountRunsTheOwnerPaths() throws Exception {
        try (AccountDatabase db = AccountDatabase.create(databases, true, true);
                LedgerTestContext owner = db.ownerContext();
                LedgerTestContext admin = db.adminContext()) {
            IdAllocator ids = admin.bean(IdAllocator.class);
            // project-add (ProjectService) and the audit row of it (OwnerAuditWriter)
            int n = SEQUENCE.incrementAndGet();
            Project project = LedgerTestContext.as(AuthorId.OWNER, () -> admin.tx(() -> admin.bean(ProjectRepository.class)
                    .insert(Project.open(ProjectId.of("q" + n), ProjectCode.of("Q" + n), "Project of the admin"))));
            admin.tx(() -> admin.bean(OwnerAuditRepository.class).insert(new OwnerAuditEntry(
                    ids.next(Counter.OWNER_AUDIT), NOW, "cli:alice", OwnerAction.PROJECT_ADDED, project.id().value(),
                    null)));
            // code-set (CommonCodeService): insert, update and the revision bump
            CommonCode code = new CommonCode(CodeGroup.DEFECT_CATEGORY, CodeValue.of("UI_" + project.code().value()),
                    "화면", null, null, null, 25, CodeState.ACTIVE, AuthorId.OWNER);
            admin.tx(() -> {
                admin.bean(CommonCodeRepository.class).insert(code);
                admin.bean(CommonCodeRepository.class).update(new CommonCode(code.group(), code.code(), "화면2", null,
                        null, null, 26, CodeState.INACTIVE, AuthorId.OWNER));
                admin.bean(CodeRevisionRepository.class).bump();
                return null;
            });
            // key-register / key-revoke (SessionKeyService)
            String fingerprint = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            SessionPublicKey key = admin.tx(() -> admin.bean(SessionKeyRepository.class).insert(
                    SessionPublicKey.register(ids.next(Counter.SESSION_KEY), BY, "MCow" + fingerprint.substring(0, 40),
                            fingerprint, AuthorId.OWNER, NOW, "note")));
            assertThat(admin.tx(() -> admin.bean(SessionKeyStateRepository.class).close(key.id(), NOW))).isTrue();
            // grant / revoke (GrantService): insert and revoke
            SessionGrant grant = admin.tx(() -> admin.bean(SessionGrantRepository.class).insert(SessionGrant.issue(
                    ids.next(Counter.SESSION_GRANT), BY, project.id(), Scope.DEFECT, AuthorId.OWNER, NOW, null, null)));
            assertThat(admin.tx(() -> admin.bean(GrantStateRepository.class).revoke(grant.id(), NOW))).isTrue();
            // the reads of the console: every table the pages show (content seeded by the owner)
            Seed seed = new Seed(owner);
            Project seeded = seed.project();
            seed.defect(seeded, 1, "shown in the console", NOW);
            seed.task(seeded, 1, "a task shown");
            for (String table : new String[] { "project", "common_code", "code_revision", "id_counter", "task", "defect",
                    "defect_fix", "defect_case", "defect_task", "defect_relation", "release_condition", "condition_link",
                    "session_grant", "session_key", "history", "owner_audit", "schema_history" }) {
                allowed(db.admin(), "SELECT COUNT(*) FROM " + table);
            }
        }
    }

    static Stream<String> theAdminAccountAndTheLedgerContent() {
        return Stream.of(
                "INSERT INTO defect DEFAULT VALUES",
                "UPDATE defect SET version = version",
                "DELETE FROM defect",
                "INSERT INTO task DEFAULT VALUES",
                "UPDATE task SET version = version",
                "DELETE FROM task",
                "INSERT INTO defect_fix DEFAULT VALUES",
                "UPDATE defect_fix SET ci_ref = ci_ref",
                "DELETE FROM defect_fix",
                "INSERT INTO defect_case DEFAULT VALUES",
                "DELETE FROM defect_case",
                "INSERT INTO defect_task DEFAULT VALUES",
                "DELETE FROM defect_task",
                "INSERT INTO defect_relation DEFAULT VALUES",
                "DELETE FROM defect_relation",
                "INSERT INTO release_condition DEFAULT VALUES",
                "UPDATE release_condition SET version = version",
                "INSERT INTO condition_link DEFAULT VALUES",
                "DELETE FROM condition_link",
                "INSERT INTO history DEFAULT VALUES",
                "SELECT COUNT(*) FROM request_receipt",
                "INSERT INTO request_receipt DEFAULT VALUES",
                "UPDATE request_receipt SET response = NULL",
                "UPDATE project SET next_defect_no = next_defect_no + 1",
                "DELETE FROM project",
                "DELETE FROM session_key",
                "DELETE FROM session_grant",
                "DELETE FROM common_code",
                "UPDATE owner_audit SET actor = 'someone else'",
                "DELETE FROM owner_audit");
    }

    @ParameterizedTest
    @MethodSource("theAdminAccountAndTheLedgerContent")
    @DisplayName("INS-005 the admin account has no write on the ledger content and no access to the receipts")
    void ins005_theAdminAccountCannotWriteTheLedgerContent(String sql) throws Exception {
        refused(shared().admin(), sql);
    }

    // ---- INS-006: neither account can change the history or the schema ----

    static Stream<String> statementsNoApplicationAccountMayRun() {
        return Stream.of(
                "UPDATE history SET reason = 'changed by the application'",
                "UPDATE history SET before_data = NULL",
                "DELETE FROM history",
                "TRUNCATE TABLE history",
                "INSERT INTO schema_history DEFAULT VALUES",
                "UPDATE schema_history SET outcome = 'FAILED'",
                "DELETE FROM schema_history",
                "CREATE TABLE stolen (id INTEGER)",
                "DROP TABLE project",
                "ALTER TABLE project ADD COLUMN extra INTEGER");
    }

    @ParameterizedTest
    @MethodSource("statementsNoApplicationAccountMayRun")
    @DisplayName("INS-006 (REPO-023, REPO-024, REPO-038) neither application account can change the history, the schema history or the schema")
    void ins006_neitherAccountCanChangeTheHistoryOrTheSchema(String sql) throws Exception {
        refused(shared().mcp(), sql);
        refused(shared().admin(), sql);
        assertThat(shared().owner()).isNotNull();
    }

    @Test
    @DisplayName("INS-006 (SCH-014) the migration is refused for both accounts, which can still read the schema history")
    void ins006_theMigrationIsRefusedForBothAccounts() throws Exception {
        try (AccountDatabase fresh = AccountDatabase.create(databases, false, false)) {
            for (var account : new javax.sql.DataSource[] { fresh.mcp(), fresh.admin() }) {
                assertThatThrownBy(() -> new org.pgledger.database.schema.SchemaMigrator(account,
                        new PathMatchingResourcePatternResolver(), properties(), Clock.systemUTC()).migrate())
                        .isInstanceOf(SchemaMigrationException.class);
            }
        }
        try (AccountDatabase migrated = AccountDatabase.create(databases, true, true)) {
            allowed(migrated.mcp(), "SELECT version, outcome FROM schema_history");
            allowed(migrated.admin(), "SELECT version, outcome FROM schema_history");
        }
    }

    private static SchemaProperties properties() {
        return new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true, null,
                Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT));
    }

    // ---- INS-010: applying the grants again ----

    @Test
    @DisplayName("INS-010 applying the grants a second time changes nothing; an account that does not exist is a SchemaMigrationException")
    void ins010_theGrantsCanBeAppliedAgain() throws Exception {
        try (AccountDatabase db = AccountDatabase.create(databases, true, true)) {
            GrantApplier applier = new GrantApplier(db.owner(), properties());
            applier.apply(Profile.MCP, db.mcpAccount());
            applier.apply(Profile.ADMIN, db.adminAccount());
            allowed(db.mcp(), "SELECT COUNT(*) FROM defect");
            refused(db.mcp(), "DELETE FROM owner_audit");
            allowed(db.admin(), "SELECT COUNT(*) FROM owner_audit");
            refused(db.admin(), "DELETE FROM defect");

            assertThatThrownBy(() -> applier.apply(Profile.MCP, "NO_SUCH_ACCOUNT"))
                    .isInstanceOf(SchemaMigrationException.class)
                    .hasMessageNotContaining("GRANT")
                    .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
}
