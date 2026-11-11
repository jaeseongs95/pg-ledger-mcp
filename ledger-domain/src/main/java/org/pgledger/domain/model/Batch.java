package org.pgledger.domain.model;

import java.util.List;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.value.RequestId;

/** 1..200 operations that commit together or not at all. */
public record Batch(RequestId requestId, List<BatchOp> ops) {

    public static final int MAX_OPS = 200;

    public Batch {
        if (ops == null || ops.isEmpty() || ops.size() > MAX_OPS) {
            throw ValidationException.invalid("ops", "must hold 1.." + MAX_OPS + " operations");
        }
        ops = List.copyOf(ops);
    }

    /** The request id of operation {@code index} (0-based): its own, or one derived from the batch id. */
    public RequestId requestIdOf(int index) {
        BatchOp op = ops.get(index);
        return op.requestId() != null ? op.requestId() : requestId.derive(index);
    }
}
