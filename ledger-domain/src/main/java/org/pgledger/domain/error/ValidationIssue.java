package org.pgledger.domain.error;

import java.util.List;

/**
 * One reason of a validation failure: where ({@code path}), why ({@code reason} with its {@code reasonArgs} for the
 * placeholders of the message pattern), and the allowed values when the set is closed. The lists are copies and
 * never null.
 */
public record ValidationIssue(String path, ValidationReason reason, List<String> reasonArgs, List<String> allowed) {

    public ValidationIssue {
        reasonArgs = List.copyOf(reasonArgs);
        allowed = List.copyOf(allowed);
    }

    /** An issue without allowed values. */
    public ValidationIssue(String path, ValidationReason reason, List<String> reasonArgs) {
        this(path, reason, reasonArgs, List.of());
    }
}
