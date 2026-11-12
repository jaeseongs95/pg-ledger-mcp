package org.pgledger.application.write;

import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Resolution;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryKey;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.CorrectResult;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.HistoryOp;
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

    @Override
    protected Lookup resolve(CorrectCommand c) {
        return Lookup.verifiable(() -> {
            if (c.historyId() == null) {
                return Resolution.unresolved(ValidationException.invalid("history_id", ValidationReason.REQUIRED));
            }
            return historyRows.findById(c.historyId()).map(this::target)
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("history", c.historyId().toString())));
        });
    }

    /** The project of the corrected entity and the scope that writes it (verify for a verification). */
    private Resolution target(HistoryEntry row) {
        // a verification (a transition into or out of VERIFIED, MET or NOT_MET) needs the verify scope on top
        Scope scope = row.involvesVerification() ? Scope.VERIFY : row.entity().writeScope();
        String ownerId = new HistoryKey(row.entity(), row.entityId()).ownerId();
        return switch (row.entity()) {
            case TASK -> rows.taskTarget(TaskId.of(ownerId), scope);
            case CONDITION, CONDITION_LINK -> rows.conditionTarget(ConditionId.of(ownerId), scope);
            case DEFECT, DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, DEFECT_CASE ->
                rows.defectTarget(DefectId.of(ownerId), scope);
        };
    }

    @Override
    protected Prepared<CorrectResult> prepare(CorrectCommand c) {
        long id = c.requireHistoryId();
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
