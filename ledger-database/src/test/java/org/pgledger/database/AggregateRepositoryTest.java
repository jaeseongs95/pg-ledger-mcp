package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.database.support.LedgerDataJdbcTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.RecordingDataSource;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectCase;
import org.pgledger.domain.model.DefectFields;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * REPO-006 .. REPO-008 and the round trip of every aggregate through the real schema, as a {@code @DataJdbcTest} slice (W7-SLICE-01,
 * principle 7.2): the repositories are beans of the slice, every test runs in a transaction that Spring rolls back, and a project
 * of its own ({@link Seed}) keeps the tests apart.
 */
@LedgerDataJdbcTest
class AggregateRepositoryTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RecordingDataSource spy;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private DefectRepository defects;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ConditionRepository conditions;

    @Autowired
    private DefectFixRepository fixes;

    @Autowired
    private DefectCaseRepository cases;

    @Autowired
    private HistoryRepository history;

    @Autowired
    private ReceiptRepository receipts;

    @Autowired
    private SessionGrantRepository grants;

    @Autowired
    private IdAllocator ids;

    private Seed seed;

    @BeforeEach
    void setUp() {
        seed = new Seed(context);
    }

    @Test
    void roundTripOfTheVersionedAggregates() {
        Project project = seed.project();
        assertThat(project.version()).isEqualTo(1);
        assertThat(project.createdBy()).isEqualTo(AuthorId.OWNER);
        assertThat(projects.findById(project.id())).hasValueSatisfying(p -> {
            assertThat(p.code()).isEqualTo(project.code());
            assertThat(p.nextDefectNo()).isZero();
            assertThat(p.version()).isEqualTo(1);
        });
        assertThat(projects.findByCode(project.code())).isPresent();

        Instant created = Instant.parse("2026-11-05T10:00:00.123456Z");
        Defect defect = seed.defect(project, 1, "first defect", created);
        assertThat(defect.version()).isEqualTo(1);
        Defect read = defects.findById(defect.id()).orElseThrow();
        assertThat(read).isEqualTo(defect);
        assertThat(read.createdAt()).isEqualTo(created);
        assertThat(read.createdBy()).isEqualTo(Seed.ALICE);
        assertThat(read.status()).isEqualTo(DefectStatus.OPEN);
        assertThat(read.caseSeq()).isZero();

        Task task = seed.task(project, 1, "first task");
        assertThat(tasks.findById(task.id())).hasValue(task);
        assertThat(task.state()).isEqualTo(TaskState.PLANNED);

        Condition condition = seed.condition(project, project.code().value() + "-C-1", "1.0", "C1");
        assertThat(conditions.findById(condition.id())).hasValue(condition);
        assertThat(condition.state()).isEqualTo(ConditionState.UNKNOWN);
        assertThat(conditions.findByProjectIdAndReleaseAndCode(project.id(), "1.0", "C1")).isPresent();
    }

    @Test
    void repo006_createdByIsStampedOnInsertAndNeverRewritten() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "stamped", LedgerTestContext.START);
        spy.clear();
        Defect edited = LedgerTestContext.as(Seed.BOB, () -> defects.save(defect.edited(
                DefectFields.of(Patch.absent(), Patch.set("edited title"), Patch.absent(), Patch.absent(),
                        Patch.absent(), Patch.absent(), Patch.absent()), LedgerTestContext.START.plusSeconds(5))));
        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.createdBy()).isEqualTo(Seed.ALICE);
        assertThat(defects.findById(defect.id()).orElseThrow().createdBy()).isEqualTo(Seed.ALICE);
        List<String> updates = spy.statements().stream().filter(s -> s.toUpperCase().startsWith("UPDATE")).toList();
        assertThat(updates).isNotEmpty().noneMatch(s -> s.toLowerCase().contains("created_by"));
        assertThat(updates).noneMatch(s -> s.toLowerCase().contains("case_seq"));
    }

    @Test
    void repo007_aWriteWithoutAnActorFailsLoudly() {
        Project project = seed.project();
        assertThatThrownBy(() -> defects.save(Defect.open(
                DefectId.of(project.code(), 1),
                DefectFields.of(Patch.set(project.id().value()), Patch.set("t"), Patch.set("ARC"), Patch.set("P1"),
                        Patch.set("b"), Patch.set("c"), Patch.absent()), LedgerTestContext.START)))
                .hasStackTraceContaining("no actor is bound");
    }

    @Test
    void repo008_staleUpdateFailsAndTheVersionGrowsByOne() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "versioned", LedgerTestContext.START);
        DefectFields change = DefectFields.of(Patch.absent(), Patch.set("v2"), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent());
        Defect first = LedgerTestContext.as(Seed.ALICE, () -> defects.save(defect.edited(change, LedgerTestContext.START)));
        assertThat(first.version()).isEqualTo(2);
        // a second save from the stale copy loses
        assertThatThrownBy(() -> LedgerTestContext.as(Seed.BOB,
                () -> defects.save(defect.edited(change, LedgerTestContext.START))))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(defects.findById(defect.id()).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void historyReceiptsFixesCasesAndGrantsRoundTrip() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "with children", LedgerTestContext.START);

        DefectFix fix = fixes.insert(DefectFix.record(
                ids.next(Counter.DEFECT_FIX), defect.id(), "repo", CommitSha.of("a".repeat(40)), "main", "run-1",
                CiConclusion.SUCCESS, Instant.parse("2026-11-05T10:01:00Z"), Seed.ALICE, LedgerTestContext.START));
        assertThat(fixes.findById(fix.id())).hasValue(fix);
        DefectFix updated = fixes.update(fix.withCi("run-2", CiConclusion.FAILURE, null));
        assertThat(fixes.findById(fix.id()).orElseThrow().ciConclusion()).isEqualTo(CiConclusion.FAILURE);
        assertThat(updated.ciRef()).isEqualTo("run-2");

        DefectCase observed = cases.insert(DefectCase.observe(
                ids.next(Counter.DEFECT_CASE), defect.id(), "seen in the log", null, "the user said so", null,
                Seed.ALICE, LedgerTestContext.START));
        assertThat(cases.findByDefectIdOrderById(defect.id())).containsExactly(observed);

        RequestId request = new RequestId(UUID.randomUUID());
        HistoryEntry entry = history.insert(new HistoryEntry(
                ids.next(Counter.HISTORY), LedgerTestContext.START, Seed.ALICE, Channel.AUTHOR.login(),
                ActorAssurance.DECLARED, HistoryEntity.DEFECT, defect.id().value(), HistoryOp.EDIT, null, null,
                JsonDocument.of("{\"title\":[\"a\",\"b\"]}"), JsonDocument.of("{\"version\":1}"),
                "a reason of enough length", "https://evidence", Sha256.of("c".repeat(64)), null, request, null));
        assertThat(history.findById(entry.id())).hasValue(entry);
        assertThat(history.findByEntityAndEntityIdOrderById(HistoryEntity.DEFECT, defect.id().value()))
                .containsExactly(entry);

        Receipt receipt = receipts.insert(Receipt.reserve(request, ReceiptOperation.SAVE, Sha256.of("d".repeat(64)),
                Seed.ALICE, Channel.AUTHOR, LedgerTestContext.START));
        assertThat(receipts.findById(request)).hasValue(receipt);
        assertThat(receipt.response()).isNull();

        SessionGrant grant = grants.insert(SessionGrant.issue(
                ids.next(Counter.SESSION_GRANT), Seed.ALICE, project.id(), Scope.DEFECT, AuthorId.OWNER,
                LedgerTestContext.START, null, "note"));
        assertThat(grants.findById(grant.id())).hasValue(grant);
        assertThat(grants.findByActiveKey(grant.activeKey())).hasValue(grant);
    }
}
