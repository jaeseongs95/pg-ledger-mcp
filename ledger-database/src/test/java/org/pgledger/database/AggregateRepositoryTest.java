package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.Dbms;
import org.pgledger.database.support.DbmsAware;
import org.pgledger.database.support.LedgerTestContext;
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
import org.springframework.dao.OptimisticLockingFailureException;

/** REPO-006 .. REPO-008 and the round trip of every aggregate through the real schema. */
@DatabaseTest
class AggregateRepositoryTest implements DbmsAware {

    @Parameter
    Dbms dbms;

    private LedgerTestContext ctx;
    private Seed seed;

    @Override
    public Dbms dbms() {
        return dbms;
    }

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
    }

    @Test
    void roundTripOfTheVersionedAggregates() {
        Project project = seed.project();
        assertThat(project.version()).isEqualTo(1);
        assertThat(project.createdBy()).isEqualTo(AuthorId.OWNER);
        assertThat(ctx.bean(ProjectRepository.class).findById(project.id())).hasValueSatisfying(p -> {
            assertThat(p.code()).isEqualTo(project.code());
            assertThat(p.nextDefectNo()).isZero();
            assertThat(p.version()).isEqualTo(1);
        });
        assertThat(ctx.bean(ProjectRepository.class).findByCode(project.code())).isPresent();

        Instant created = Instant.parse("2026-11-05T10:00:00.123456Z");
        Defect defect = seed.defect(project, 1, "first defect", created);
        assertThat(defect.version()).isEqualTo(1);
        Defect read = ctx.bean(DefectRepository.class).findById(defect.id()).orElseThrow();
        assertThat(read).isEqualTo(defect);
        assertThat(read.createdAt()).isEqualTo(created);
        assertThat(read.createdBy()).isEqualTo(Seed.ALICE);
        assertThat(read.status()).isEqualTo(DefectStatus.OPEN);
        assertThat(read.caseSeq()).isZero();

        Task task = seed.task(project, 1, "first task");
        assertThat(ctx.bean(TaskRepository.class).findById(task.id())).hasValue(task);
        assertThat(task.state()).isEqualTo(TaskState.PLANNED);

        Condition condition = seed.condition(project, project.code().value() + "-C-1", "1.0", "C1");
        assertThat(ctx.bean(ConditionRepository.class).findById(condition.id())).hasValue(condition);
        assertThat(condition.state()).isEqualTo(ConditionState.UNKNOWN);
        assertThat(ctx.bean(ConditionRepository.class).findByProjectIdAndReleaseAndCode(project.id(), "1.0", "C1"))
                .isPresent();
    }

    @Test
    void repo006_createdByIsStampedOnInsertAndNeverRewritten() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "stamped", LedgerTestContext.START);
        DefectRepository repository = ctx.bean(DefectRepository.class);
        ctx.spy().clear();
        Defect edited = LedgerTestContext.as(Seed.BOB, () -> ctx.tx(() -> repository.save(defect.edited(
                DefectFields.of(Patch.absent(), Patch.set("edited title"), Patch.absent(), Patch.absent(),
                        Patch.absent(), Patch.absent(), Patch.absent()), LedgerTestContext.START.plusSeconds(5)))));
        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.createdBy()).isEqualTo(Seed.ALICE);
        assertThat(repository.findById(defect.id()).orElseThrow().createdBy()).isEqualTo(Seed.ALICE);
        List<String> updates = ctx.spy().statements().stream().filter(s -> s.toUpperCase().startsWith("UPDATE"))
                .toList();
        assertThat(updates).isNotEmpty().noneMatch(s -> s.toLowerCase().contains("created_by"));
        assertThat(updates).noneMatch(s -> s.toLowerCase().contains("case_seq"));
    }

    @Test
    void repo007_aWriteWithoutAnActorFailsLoudly() {
        Project project = seed.project();
        assertThatThrownBy(() -> ctx.tx(() -> ctx.bean(DefectRepository.class).save(Defect.open(
                DefectId.of(project.code(), 1),
                DefectFields.of(Patch.set(project.id().value()), Patch.set("t"), Patch.set("ARC"), Patch.set("P1"),
                        Patch.set("b"), Patch.set("c"), Patch.absent()), LedgerTestContext.START))))
                .hasStackTraceContaining("no actor is bound");
    }

    @Test
    void repo008_staleUpdateFailsAndTheVersionGrowsByOne() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "versioned", LedgerTestContext.START);
        DefectRepository repository = ctx.bean(DefectRepository.class);
        DefectFields change = DefectFields.of(Patch.absent(), Patch.set("v2"), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent());
        Defect first = LedgerTestContext.as(Seed.ALICE,
                () -> ctx.tx(() -> repository.save(defect.edited(change, LedgerTestContext.START))));
        assertThat(first.version()).isEqualTo(2);
        // a second save from the stale copy loses
        assertThatThrownBy(() -> LedgerTestContext.as(Seed.BOB,
                () -> ctx.tx(() -> repository.save(defect.edited(change, LedgerTestContext.START)))))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.findById(defect.id()).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void historyReceiptsFixesCasesAndGrantsRoundTrip() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "with children", LedgerTestContext.START);
        IdAllocator ids = ctx.bean(IdAllocator.class);

        DefectFix fix = ctx.tx(() -> ctx.bean(DefectFixRepository.class).insert(DefectFix.record(
                ids.next(Counter.DEFECT_FIX), defect.id(), "repo", CommitSha.of("a".repeat(40)), "main", "run-1",
                CiConclusion.SUCCESS, Instant.parse("2026-11-05T10:01:00Z"), Seed.ALICE, LedgerTestContext.START)));
        assertThat(ctx.bean(DefectFixRepository.class).findById(fix.id())).hasValue(fix);
        DefectFix updated = ctx.tx(() -> ctx.bean(DefectFixRepository.class).update(fix.withCi("run-2",
                CiConclusion.FAILURE, null)));
        assertThat(ctx.bean(DefectFixRepository.class).findById(fix.id()).orElseThrow().ciConclusion())
                .isEqualTo(CiConclusion.FAILURE);
        assertThat(updated.ciRef()).isEqualTo("run-2");

        DefectCase observed = ctx.tx(() -> ctx.bean(DefectCaseRepository.class).insert(DefectCase.observe(
                ids.next(Counter.DEFECT_CASE), defect.id(), "seen in the log", null, "the user said so", null,
                Seed.ALICE, LedgerTestContext.START)));
        assertThat(ctx.bean(DefectCaseRepository.class).findByDefectIdOrderById(defect.id())).containsExactly(observed);

        RequestId request = new RequestId(UUID.randomUUID());
        HistoryEntry entry = ctx.tx(() -> ctx.bean(HistoryRepository.class).insert(new HistoryEntry(
                ids.next(Counter.HISTORY), LedgerTestContext.START, Seed.ALICE, Channel.AUTHOR.login(),
                ActorAssurance.DECLARED, HistoryEntity.DEFECT, defect.id().value(), HistoryOp.EDIT, null, null,
                JsonDocument.of("{\"title\":[\"a\",\"b\"]}"), JsonDocument.of("{\"version\":1}"),
                "a reason of enough length", "https://evidence", Sha256.of("c".repeat(64)), null, request, null)));
        assertThat(ctx.bean(HistoryRepository.class).findById(entry.id())).hasValue(entry);
        assertThat(ctx.bean(HistoryRepository.class).findByEntityAndEntityIdOrderById(HistoryEntity.DEFECT,
                defect.id().value())).containsExactly(entry);

        Receipt receipt = ctx.tx(() -> ctx.bean(ReceiptRepository.class).insert(Receipt.reserve(request,
                ReceiptOperation.SAVE, Sha256.of("d".repeat(64)), Seed.ALICE, Channel.AUTHOR,
                LedgerTestContext.START)));
        assertThat(ctx.bean(ReceiptRepository.class).findById(request)).hasValue(receipt);
        assertThat(receipt.response()).isNull();

        SessionGrant grant = ctx.tx(() -> ctx.bean(SessionGrantRepository.class).insert(SessionGrant.issue(
                ids.next(Counter.SESSION_GRANT), Seed.ALICE, project.id(), Scope.DEFECT, AuthorId.OWNER,
                LedgerTestContext.START, null, "note")));
        assertThat(ctx.bean(SessionGrantRepository.class).findById(grant.id())).hasValue(grant);
        assertThat(ctx.bean(SessionGrantRepository.class).findByActiveKey(grant.activeKey())).hasValue(grant);
    }
}
