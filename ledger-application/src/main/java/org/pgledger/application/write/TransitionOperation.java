package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Resolution;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.history.TaskHistory;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.FixHistory;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.LifecycleState;
import org.pgledger.domain.rule.MergeTarget;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.TaskReports;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.EntityId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.Tokens;
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
    private final DefectFixRepository fixes;
    private final HistoryRepository historyRows;
    private final HistoryWriter history;
    private final Clock clock;

    public TransitionOperation(WriteGate gate, ReceiptService receipts, Rows rows, CodeRules codeRules,
            DefectFixRepository fixes, HistoryRepository historyRows, HistoryWriter history, Clock clock) {
        super(gate, receipts, ReceiptOperation.TRANSITION, TransitionResult.class);
        this.gate = gate;
        this.rows = rows;
        this.codeRules = codeRules;
        this.fixes = fixes;
        this.historyRows = historyRows;
        this.history = history;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(TransitionCommand c) {
        return Lookup.verifiable(() -> Tokens.find(c.entity(), STATEFUL).map(entity -> switch (entity) {
            case DEFECT -> DefectId.parse(c.id()).map(id -> rows.findDefect(id)
                    .map(row -> target(entity, row.projectId(), row.status(), c, DefectStatus.values()))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("defect", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(DefectId.malformed()));
            case TASK -> TaskId.parse(c.id()).map(id -> rows.findTask(id)
                    .map(row -> target(entity, row.projectId(), row.state(), c, TaskState.values()))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("task", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(TaskId.malformed()));
            case CONDITION -> ConditionId.parse(c.id()).map(id -> rows.findCondition(id)
                    .map(row -> target(entity, row.projectId(), row.state(), c, ConditionState.values()))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("condition", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(ConditionId.malformed()));
            case DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, CONDITION_LINK, DEFECT_CASE ->
                throw new IllegalStateException("STATEFUL has no such entity");
        }).orElseGet(() -> Resolution.unresolved(Tokens.unknown("entity", c.entity(), STATEFUL))));
    }

    /** The target of a move of a row that exists: its project and the scope of the move, or the unknown state. */
    private static <S extends Enum<S> & LifecycleState> Resolution target(HistoryEntity entity, ProjectId project,
            S from, TransitionCommand c, S[] states) {
        return Tokens.find(c.to(), states)
                .map(to -> Resolution.of(new Target(project, Scope.forTransition(entity, from, to))))
                .orElseGet(() -> Resolution.unresolved(Tokens.unknown("to", c.to(), states)));
    }

    @Override
    protected Prepared<TransitionResult> prepare(TransitionCommand c) {
        HistoryEntity entity = Tokens.parse("entity", c.entity(), STATEFUL);
        if (c.expectedVersion() == null) {
            throw ValidationException.invalid("expected_version", ValidationReason.REQUIRED);
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
    private void recheck(WriteContext ctx, HistoryEntity entity, ProjectId project, LifecycleState from,
            LifecycleState to) {
        Scope scope = Scope.forTransition(entity, from, to);
        if (scope == Scope.VERIFY && !ctx.session().channel().mayVerify()) {
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
        Defect saved = rows.saveDefect(after);
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
        TaskReports reports = new TaskHistory(historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.TASK,
                id.value())).reports();
        if (row.ownerActorId() == null) {
            row.state().requireTransitionTo(to, evidence, ctx.author().sessionKey(), reports);
        } else {
            row.state().requireTransitionTo(to, evidence, ctx.author().sessionKey(), row.ownerActorId().sessionKey(),
                    reports);
        }
        Task saved = rows.saveTask(row.transitioned(to, now));
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
        Condition saved = rows.saveCondition(row.transitioned(to));
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
}
