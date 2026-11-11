package org.pgledger.domain.error;

import java.util.List;

/** One reason of a validation failure: where, why, and the allowed values when the set is closed. */
public record ValidationIssue(String path, String reason, List<String> allowed) {

    public ValidationIssue {
        allowed = List.copyOf(allowed);
    }
}
