package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#VALIDATION}; carries the issues behind the code. */
public final class ValidationException extends LedgerException {

    private static final long serialVersionUID = 1L;

    private final transient List<ValidationIssue> issues;

    public ValidationException(ValidationCode code, List<String> args, List<ValidationIssue> issues,
            Map<String, List<String>> details) {
        super(code, args, details, null);
        this.issues = List.copyOf(issues);
    }

    /** VALIDATION_FAILED for one path. */
    public static ValidationException invalid(String path, String reason) {
        return invalid(path, reason, List.of());
    }

    /** VALIDATION_FAILED for one path of a closed value set. */
    public static ValidationException invalid(String path, String reason, List<String> allowed) {
        return new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(path, reason),
                List.of(new ValidationIssue(path, reason, allowed)), Map.of());
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
