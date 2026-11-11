package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Resolution;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.FixHistory;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.MergeTarget;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.TaskReports;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.rule.TaskTransition;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.EntityId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.SessionKey;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.Tokens;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * One state change of a defect, task or condition with one TRANSITION history row. Moving into or out of a
 * verification state needs the verify scope and the reviewer channel. The policy of each state is the domain's.
 */
@Component
public class TransitionOperation extends WriteOperation<TransitionCommand, TransitionResult> {

    private static final HistoryEntity[] STATEFUL = {HistoryEntity.DEFECT, HistoryEntity.TASK,
            HistoryEntity.CONDITION};

    private final WriteGate gate;
    private final Rows rows;
    private final CodeRules codeRules;
    private final DefectRepository defects;
    private final DefectFixRepository fixes;
    private final TaskRepository tasks;
    private final ConditionRepository conditions;
    private final HistoryRepository historyRows;
    private final HistoryWriter history;
    private final Clock clock;

    public TransitionOperation(WriteGate gate, ReceiptService receipts, Rows rows, CodeRules codeRules,
            DefectRepository defects, DefectFixRepository fixes, TaskRepository tasks,
            ConditionRepository conditions, HistoryRepository historyRows, HistoryWriter history, Clock clock) {
        super(gate, receipts, ReceiptOperation.TRANSITION, TransitionResult.class);
        this.gate = gate;
        this.rows = rows;
        this.codeRules = codeRules;
        this.defects = defects;
        this.fixes = fixes;
        this.tasks = tasks;
        this.conditions = conditions;
        this.historyRows = historyRows;
        this.history = history;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(TransitionCommand c) {
        return Lookup.verifiable(() -> {
            HistoryEntity entity = Tokens.parse("entity", c.entity(), STATEFUL);
            return switch (entity) {
                case DEFECT -> {
                    DefectId id = DefectId.of(c.id());
                    yield rows.findDefect(id).map(row -> Resolution.of(new Target(row.projectId(), Scope
                            .forTransition(entity, row.status(), Tokens.parse("to", c.to(), DefectStatus.values())))))
                            .orElseGet(() -> Resolution.unresolved(Rows.notFound("defect", id.value())));
                }
                case TASK -> {
                    TaskId id = TaskId.of(c.id());
                    yield rows.findTask(id).map(row -> Resolution.of(new Target(row.projectId(), Scope
                            .forTransition(entity, row.state(), Tokens.parse("to", c.to(), TaskState.values())))))
                            .orElseGet(() -> Resolution.unresolved(Rows.notFound("task", id.value())));
                }
                case CONDITION -> {
                    ConditionId id = ConditionId.of(c.id());
                    yield rows.findCondition(id).map(row -> Resolution.of(new Target(row.projectId(), Scope
                            .forTransition(entity, row.state(), Tokens.parse("to", c.to(),
                                    ConditionState.values())))))
                            .orElseGet(() -> Resolution.unresolved(Rows.notFound("condition", id.value())));
                }
                case DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, CONDITION_LINK, DEFECT_CASE ->
                    throw new IllegalStateException("Tokens.parse refused it");
            };
        });
    }

    @Override
    protected Prepared<TransitionResult> prepare(TransitionCommand c) {
        HistoryEntity entity = Tokens.parse("entity", c.entity(), STATEFUL);
        if (c.expectedVersion() == null) {
            throw ValidationException.invalid("expected_version", "is required");
        }
        EntityId.of(c.id());
        Evidence evidence = Evidence.of(c.evidenceUri(), c.evidenceSha256());
        DefectId mergedInto = c.mergedInto() == null || c.mergedInto().isBlank() ? null
                : DefectId.of(c.mergedInto());
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("entity", entity.token())
                .text("id", c.id()).text("v", Long.toString(c.expectedVersion())).text("to", c.to())
                .text("evidence_uri", evidence.uri())
                .text("evidence_sha256", evidence.sha256() == null ? null : evidence.sha256().hex())
                .text("merged_into", mergedInto == null ? null : mergedInto.value());
        long version = c.expectedVersion();
        return new Prepared<>(payload, ctx -> switch (entity) {
            case DEFECT -> defect(ctx, DefectId.of(c.id()), version,
                    Tokens.parse("to", c.to(), DefectStatus.values()), evidence, mergedInto);
            case TASK -> task(ctx, TaskId.of(c.id()), version, Tokens.parse("to", c.to(), TaskState.values()),
                    evidence);
            case CONDITION -> condition(ctx, ConditionId.of(c.id()), version,
                    Tokens.parse("to", c.to(), ConditionState.values()), evidence);
            case DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, CONDITION_LINK, DEFECT_CASE ->
                throw new IllegalStateException("Tokens.parse refused it");
        });
    }

    /** The scope may differ from the one found before the row was locked; the grant and channel follow the row. */
    private void recheck(WriteContext ctx, HistoryEntity entity, org.pgledger.domain.value.ProjectId project,
            org.pgledger.domain.rule.LifecycleState from, org.pgledger.domain.rule.LifecycleState to) {
        Scope scope = Scope.forTransition(entity, from, to);
        if (scope == Scope.VERIFY && ctx.session().channel() != Channel.REVIEWER) {
            throw new TrustException(TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN, List.of());
        }
        gate.authorize(ctx.author(), new Target(project, scope));
    }

    private TransitionResult defect(WriteContext ctx, DefectId id, long expected, DefectStatus to,
            Evidence evidence, DefectId mergedInto) {
        Instant now = clock.instant();
        Defect row = rows.lockedDefect(id, expected);
        recheck(ctx, HistoryEntity.DEFECT, row.projectId(), row.status(), to);
        FixHistory fixHistory = new FixHistory(fixes.findByDefectIdOrderById(id).stream()
                .map(f -> f.recordedBy().sessionKey()).toList());
        MergeTarget target = null;
        if (to == DefectStatus.MERGED && mergedInto != null) {
            Defect targetRow = rows.defect(mergedInto);
            target = new MergeTarget(targetRow.id(), targetRow.status());
        }
        row.status().requireTransitionTo(to, evidence, fixHistory, ctx.author().sessionKey(), id, target);
        Defect after = row.transitioned(to, to == DefectStatus.MERGED ? mergedInto : null, now);
        codeRules.checkDefect(row, after.category(), after.severity());
        Defect saved = guarded(() -> defects.save(after), "defect", id.value());
        ChangeSet changes = to == DefectStatus.MERGED
                ? ChangeSet.empty().with("merged_into", null, mergedInto.value()) : ChangeSet.empty();
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT, id.value(),
                HistoryOp.TRANSITION, changes).withStates(row.status().name(), to.name()).withEvidence(evidence)
                .withBefore(Snapshot.DefectRow.of(row)));
        return result(ctx, HistoryEntity.DEFECT, id.value(), row.status().name(), to.name(), saved.version(),
                historyId);
    }

    private TransitionResult task(WriteContext ctx, TaskId id, long expected, TaskState to, Evidence evidence) {
        Instant now = clock.instant();
        Task row = rows.lockedTask(id, expected);
        recheck(ctx, HistoryEntity.TASK, row.projectId(), row.state(), to);
        Optional<SessionKey> owner = Optional.ofNullable(row.ownerActorId()).map(a -> a.sessionKey());
        List<TaskTransition> transitions = historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.TASK,
                id.value()).stream().filter(h -> h.op() == HistoryOp.TRANSITION)
                .map(h -> new TaskTransition(TaskState.valueOf(h.fromState()), TaskState.valueOf(h.toState()),
                        h.authorId())).toList();
        row.state().requireTransitionTo(to, evidence, ctx.author().sessionKey(), owner,
                new TaskReports(transitions));
        Task saved = guarded(() -> tasks.save(row.transitioned(to, now)), "task", id.value());
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.TASK, id.value(),
                HistoryOp.TRANSITION, ChangeSet.empty()).withStates(row.state().name(), to.name())
                .withEvidence(evidence).withBefore(Snapshot.TaskRow.of(row)));
        return result(ctx, HistoryEntity.TASK, id.value(), row.state().name(), to.name(), saved.version(),
                historyId);
    }

    private TransitionResult condition(WriteContext ctx, ConditionId id, long expected, ConditionState to,
            Evidence evidence) {
        Condition row = rows.lockedCondition(id, expected);
        recheck(ctx, HistoryEntity.CONDITION, row.projectId(), row.state(), to);
        row.state().requireTransitionTo(to, evidence);
        Condition saved = guarded(() -> conditions.save(row.transitioned(to)), "condition", id.value());
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.CONDITION, id.value(),
                HistoryOp.TRANSITION, ChangeSet.empty()).withStates(row.state().name(), to.name())
                .withEvidence(evidence).withBefore(Snapshot.ConditionRow.of(row)));
        return result(ctx, HistoryEntity.CONDITION, id.value(), row.state().name(), to.name(), saved.version(),
                historyId);
    }

    private static TransitionResult result(WriteContext ctx, HistoryEntity entity, String id, String from,
            String to, long version, long historyId) {
        return new TransitionResult(WriteStatus.COMMITTED, entity.token(), id, from, to, version, historyId,
                ctx.author().value(), ctx.login());
    }

    private static <T> T guarded(java.util.function.Supplier<T> save, String entity, String id) {
        try {
            return save.get();
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException(ConflictCode.VERSION_CONFLICT, List.of(entity, id, "?", "?"), e);
        }
    }
}
