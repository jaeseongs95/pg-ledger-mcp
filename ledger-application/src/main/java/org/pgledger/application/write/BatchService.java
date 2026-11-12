package org.pgledger.application.write;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.validation.ViolationTranslator;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.BatchOpKind;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.value.RequestId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Up to 200 write operations in one transaction: they all commit or none does. Every operation runs through the
 * same steps as its single tool (gate, grants, reason, receipt, business rules) through the author channel, so a
 * verification cannot be part of a batch. An operation without its own request id gets one derived from the batch
 * request id and its position, so sending the same batch again returns the stored results instead of writing
 * twice. A failure names the operation (details {@code op_index}, 1-based, and {@code op}) and rolls everything back.
 */
@Service
@Transactional
public class BatchService {

    private final SaveOperation save;
    private final TransitionOperation transition;
    private final RecordFixOperation recordFix;
    private final RecordCaseOperation recordCase;
    private final LinkOperation link;
    private final CorrectOperation correct;
    private final LedgerJson json;
    private final Validator validator;

    public BatchService(SaveOperation save, TransitionOperation transition, RecordFixOperation recordFix,
            RecordCaseOperation recordCase, LinkOperation link, CorrectOperation correct, LedgerJson json,
            Validator validator) {
        this.save = save;
        this.transition = transition;
        this.recordFix = recordFix;
        this.recordCase = recordCase;
        this.link = link;
        this.correct = correct;
        this.json = json;
        this.validator = validator;
    }

    /** Reads the {@code ops} array of a batch from JSON text, then runs {@link #batch}. */
    public BatchResult batch(String actor, String requestId, String opsJson) {
        return batch(actor, requestId, json.readInputList("ops", opsJson, BatchOpInput.class));
    }

    public BatchResult batch(String actor, String requestId, List<BatchOpInput> ops) {
        if (ops == null || ops.isEmpty() || ops.size() > BatchOpKind.MAX_OPS_PER_BATCH) {
            throw ValidationException.invalid("ops", ValidationReason.MUST_HOLD_ITEMS, "1", String.valueOf(BatchOpKind.MAX_OPS_PER_BATCH));
        }
        RequestId batchId = WriteOperation.requestIdOrFresh(requestId);
        List<WriteResult> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            BatchOpInput op = ops.get(i);
            String opRequestId = op.requestId() != null ? op.requestId() : batchId.derive(i).toString();
            try {
                requireKeys(op, i);
                results.add(run(actor, requestId, op, opRequestId));
            } catch (LedgerException failure) {
                throw failure.withDetails(Map.of("op_index", List.of(Integer.toString(i + 1)),
                        "op", List.of(kind(op).token())));
            }
        }
        return new BatchResult(WriteStatus.COMMITTED, results.size(), results);
    }

    /**
     * The required keys of an operation are the constraints of its record; a violation is named by the path of the key in
     * the request ({@code ops[1].reason}). The operation is validated just before it runs, so the operations before it
     * have run (and are rolled back with the batch) exactly as when the keys were checked by hand.
     */
    private void requireKeys(BatchOpInput op, int index) {
        Set<ConstraintViolation<BatchOpInput>> violations = validator.validate(op);
        if (!violations.isEmpty()) {
            throw new ViolationTranslator("ops[" + index + "]").translate(violations);
        }
    }

    private static BatchOpKind kind(BatchOpInput op) {
        return switch (op) {
            case BatchOpInput.Save s -> BatchOpKind.SAVE;
            case BatchOpInput.Transition t -> BatchOpKind.TRANSITION;
            case BatchOpInput.RecordFix f -> BatchOpKind.RECORD_FIX;
            case BatchOpInput.RecordCase c -> BatchOpKind.RECORD_CASE;
            case BatchOpInput.Link l -> BatchOpKind.LINK;
            case BatchOpInput.Correct c -> BatchOpKind.CORRECT;
        };
    }

    /** {@code boundRequestId} is the request id of the whole batch as the caller sent it: what the attestation is bound to. */
    private WriteResult run(String actor, String boundRequestId, BatchOpInput op, String id) {
        return switch (op) {
            case BatchOpInput.Save s -> save.run(actor, boundRequestId, Channel.AUTHOR,
                    new SaveCommand(id, s.entity(), s.id(), s.expectedVersion(), s.fields(), s.reason()));
            case BatchOpInput.Transition t -> transition.run(actor, boundRequestId, Channel.AUTHOR,
                    new TransitionCommand(id, t.entity(), t.id(), t.expectedVersion(), t.to(), t.reason(),
                            t.evidenceUri(), t.evidenceSha256(), t.mergedInto()));
            case BatchOpInput.RecordFix f -> recordFix.run(actor, boundRequestId, Channel.AUTHOR,
                    new RecordFixCommand(id, f.defectId(), f.repo(), f.commitSha(), f.reason(), f.branch(),
                            f.ciRef(), f.ciConclusion(), f.ciCompletedAt(), f.markSourceFixed()));
            case BatchOpInput.RecordCase c -> recordCase.run(actor, boundRequestId, Channel.AUTHOR,
                    new RecordCaseCommand(id, c.defectId(), c.observation(), c.reason(), c.evidence(),
                            c.userQuote(), c.observedAt()));
            case BatchOpInput.Link l -> link.run(actor, boundRequestId, Channel.AUTHOR,
                    new LinkCommand(id, l.kind(), l.a(), l.b(), l.reason(), l.remove()));
            case BatchOpInput.Correct c -> correct.run(actor, boundRequestId, Channel.AUTHOR,
                    new CorrectCommand(id, c.historyId(), c.reason()));
        };
    }
}
