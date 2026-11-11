package org.pgledger.application.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.Batch;
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

    public BatchService(SaveOperation save, TransitionOperation transition, RecordFixOperation recordFix,
            RecordCaseOperation recordCase, LinkOperation link, CorrectOperation correct, LedgerJson json) {
        this.save = save;
        this.transition = transition;
        this.recordFix = recordFix;
        this.recordCase = recordCase;
        this.link = link;
        this.correct = correct;
        this.json = json;
    }

    /** Reads the {@code ops} array of a batch from JSON text, then runs {@link #batch}. */
    public BatchResult batch(String actor, String requestId, String opsJson) {
        return batch(actor, requestId, json.readInputList("ops", opsJson, BatchOpInput.class));
    }

    public BatchResult batch(String actor, String requestId, List<BatchOpInput> ops) {
        if (ops == null || ops.isEmpty() || ops.size() > Batch.MAX_OPS) {
            throw ValidationException.invalid("ops", "must hold 1.." + Batch.MAX_OPS + " operations");
        }
        RequestId batchId = requestId == null || requestId.isBlank() ? new RequestId(UUID.randomUUID())
                : RequestId.parse(requestId);
        List<WriteResult> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            BatchOpInput op = ops.get(i);
            String opRequestId = op.requestId() != null ? op.requestId() : batchId.derive(i).toString();
            try {
                results.add(run(actor, op, opRequestId, i));
            } catch (LedgerException failure) {
                throw failure.withDetails(Map.of("op_index", List.of(Integer.toString(i + 1)),
                        "op", List.of(kind(op).token())));
            }
        }
        return new BatchResult(WriteStatus.COMMITTED, results.size(), results);
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

    private WriteResult run(String actor, BatchOpInput op, String id, int index) {
        return switch (op) {
            case BatchOpInput.Save s -> {
                require(index, "entity", s.entity());
                require(index, "fields", s.fields());
                require(index, "reason", s.reason());
                yield save.run(actor, Channel.AUTHOR,
                        new SaveCommand(id, s.entity(), s.id(), s.expectedVersion(), s.fields(), s.reason()));
            }
            case BatchOpInput.Transition t -> {
                require(index, "entity", t.entity());
                require(index, "id", t.id());
                require(index, "expected_version", t.expectedVersion());
                require(index, "to", t.to());
                require(index, "reason", t.reason());
                yield transition.run(actor, Channel.AUTHOR, new TransitionCommand(id, t.entity(), t.id(),
                        t.expectedVersion(), t.to(), t.reason(), t.evidenceUri(), t.evidenceSha256(),
                        t.mergedInto()));
            }
            case BatchOpInput.RecordFix f -> {
                require(index, "defect_id", f.defectId());
                require(index, "repo", f.repo());
                require(index, "commit_sha", f.commitSha());
                require(index, "reason", f.reason());
                yield recordFix.run(actor, Channel.AUTHOR, new RecordFixCommand(id, f.defectId(), f.repo(),
                        f.commitSha(), f.reason(), f.branch(), f.ciRef(), f.ciConclusion(), f.ciCompletedAt(),
                        f.markSourceFixed()));
            }
            case BatchOpInput.RecordCase c -> {
                require(index, "defect_id", c.defectId());
                require(index, "observation", c.observation());
                require(index, "reason", c.reason());
                yield recordCase.run(actor, Channel.AUTHOR, new RecordCaseCommand(id, c.defectId(),
                        c.observation(), c.reason(), c.evidence(), c.userQuote(), c.observedAt()));
            }
            case BatchOpInput.Link l -> {
                require(index, "kind", l.kind());
                require(index, "a", l.a());
                require(index, "b", l.b());
                require(index, "reason", l.reason());
                yield link.run(actor, Channel.AUTHOR, new LinkCommand(id, l.kind(), l.a(), l.b(), l.reason(),
                        l.remove()));
            }
            case BatchOpInput.Correct c -> {
                require(index, "history_id", c.historyId());
                require(index, "reason", c.reason());
                yield correct.run(actor, Channel.AUTHOR, new CorrectCommand(id, c.historyId(), c.reason()));
            }
        };
    }

    private static void require(int index, String key, Object value) {
        if (value == null) {
            throw ValidationException.invalid("ops[" + index + "]." + key, "is required");
        }
    }
}
