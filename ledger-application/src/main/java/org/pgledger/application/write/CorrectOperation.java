package org.pgledger.application.write;

import java.util.List;
import java.util.Set;
import org.pgledger.application.gate.Resolution;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.CorrectResult;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.stereotype.Component;

/**
 * Points at a wrong history row with a CORRECT row of the same entity. The wrong row itself is never changed.
 * The scope is the one that writes the corrected entity, and verify when the corrected row is a verification.
 * Correcting a CORRECT row is allowed, as the old function allowed it.
 */
@Component
public class CorrectOperation extends WriteOperation<CorrectCommand, CorrectResult> {

    private static final Set<String> VERIFICATION_STATES = Set.of("VERIFIED", "MET", "NOT_MET");

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
    protected Resolution resolve(CorrectCommand c) {
        return Resolution.of(() -> {
            HistoryEntry row = historyRows.findById(historyId(c))
                    .orElseThrow(() -> Rows.notFound("history", Long.toString(historyId(c))));
            ProjectId project = switch (row.entity()) {
                case TASK -> rows.task(TaskId.of(row.entityId())).projectId();
                case CONDITION -> rows.condition(ConditionId.of(row.entityId())).projectId();
                case CONDITION_LINK -> rows.condition(ConditionId.of(before(row.entityId(), '|'))).projectId();
                case DEFECT -> rows.defect(DefectId.of(row.entityId())).projectId();
                case DEFECT_FIX -> rows.defect(DefectId.of(before(row.entityId(), '@'))).projectId();
                case DEFECT_TASK, DEFECT_RELATION, DEFECT_CASE ->
                    rows.defect(DefectId.of(before(row.entityId(), '|'))).projectId();
            };
            Scope scope = switch (row.entity()) {
                case TASK, CONDITION, CONDITION_LINK -> Scope.TASK;
                case DEFECT_FIX -> Scope.FIX;
                case DEFECT, DEFECT_TASK, DEFECT_RELATION, DEFECT_CASE -> Scope.DEFECT;
            };
            boolean verification = row.op() == HistoryOp.TRANSITION
                    && (VERIFICATION_STATES.contains(row.toState())
                            || (row.fromState() != null && VERIFICATION_STATES.contains(row.fromState())));
            return List.of(new Target(project, verification ? Scope.VERIFY : scope));
        });
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
