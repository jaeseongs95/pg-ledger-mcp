package org.pgledger.application.write;

import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Resolution;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.CorrectResult;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.LifecycleState;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.stereotype.Component;

/**
 * Points at a wrong history row with a CORRECT row of the same entity. The wrong row itself is never changed.
 * The scope is the one that writes the corrected entity, and verify when the corrected row is a verification.
 * Correcting a CORRECT row is allowed, as the old function allowed it.
 */
@Component
public class CorrectOperation extends WriteOperation<CorrectCommand, CorrectResult> {

    private final Rows rows;
    private final HistoryRepository historyRows;
    private final HistoryWriter history;

    public CorrectOperation(WriteGate gate, ReceiptService receipts, Rows rows, HistoryRepository historyRows,
            HistoryWriter history) {
        super(gate, receipts, ReceiptOperation.CORRECT, CorrectResult.class);
        this.rows = rows;
        this.historyRows = historyRows;
        this.history = history;
    }

    private static long historyId(CorrectCommand c) {
        if (c.historyId() == null) {
            throw ValidationException.invalid("history_id", "is required");
        }
        return c.historyId();
    }

    @Override
    protected Lookup resolve(CorrectCommand c) {
        return Lookup.verifiable(() -> {
            if (c.historyId() == null) {
                return Resolution.unresolved(ValidationException.invalid("history_id", "is required"));
            }
            return historyRows.findById(c.historyId()).map(this::target)
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("history", c.historyId().toString())));
        });
    }

    /** The project of the corrected entity and the scope that writes it (verify for a verification). */
    private Resolution target(HistoryEntry row) {
        Scope scope = verification(row) ? Scope.VERIFY : switch (row.entity()) {
            case TASK, CONDITION, CONDITION_LINK -> Scope.TASK;
            case DEFECT_FIX -> Scope.FIX;
            case DEFECT, DEFECT_TASK, DEFECT_RELATION, DEFECT_CASE -> Scope.DEFECT;
        };
        return switch (row.entity()) {
            case TASK -> rows.taskTarget(TaskId.of(row.entityId()), scope);
            case CONDITION -> rows.conditionTarget(ConditionId.of(row.entityId()), scope);
            case CONDITION_LINK -> rows.conditionTarget(ConditionId.of(before(row.entityId(), '|')), scope);
            case DEFECT -> rows.defectTarget(DefectId.of(row.entityId()), scope);
            case DEFECT_FIX -> rows.defectTarget(DefectId.of(before(row.entityId(), '@')), scope);
            case DEFECT_TASK, DEFECT_RELATION, DEFECT_CASE ->
                rows.defectTarget(DefectId.of(before(row.entityId(), '|')), scope);
        };
    }

    /**
     * Whether the corrected row is a verification: a transition into or out of VERIFIED, MET or NOT_MET. A history
     * row keeps its states as text (an imported row may hold one that is none of ours, which is no verification);
     * they become the state constants of the entity here, at the boundary, and are compared as constants.
     */
    private static boolean verification(HistoryEntry row) {
        if (row.op() != HistoryOp.TRANSITION) {
            return false;
        }
        return isVerification(row.entity(), row.toState()) || isVerification(row.entity(), row.fromState());
    }

    private static boolean isVerification(HistoryEntity entity, String stored) {
        return LifecycleState.of(entity, stored).filter(LifecycleState::verification).isPresent();
    }

    private static String before(String id, char separator) {
        int at = id.indexOf(separator);
        return at < 0 ? id : id.substring(0, at);
    }

    @Override
    protected Prepared<CorrectResult> prepare(CorrectCommand c) {
        long id = historyId(c);
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("history_id", Long.toString(id));
        return new Prepared<>(payload, ctx -> {
            HistoryEntry row = historyRows.findById(id)
                    .orElseThrow(() -> Rows.notFound("history", Long.toString(id)));
            long newId = history.append(ctx, HistoryWriter.Entry.of(row.entity(), row.entityId(),
                    HistoryOp.CORRECT, ChangeSet.empty()).correcting(id));
            return new CorrectResult(WriteStatus.COMMITTED, id, newId, ctx.author().value(), ctx.login());
        });
    }
}
