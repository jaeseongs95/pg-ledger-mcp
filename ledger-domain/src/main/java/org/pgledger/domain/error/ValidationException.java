package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#VALIDATION}; carries the issues behind the code. */
public final class ValidationException extends LedgerException {

    private static final long serialVersionUID = 1L;

    private final transient List<ValidationIssue> issues;

    public ValidationException(ValidationCode code, List<String> args, List<ValidationIssue> issues,
            Map<String, List<String>> details) {
        this(code, args, issues, details, null);
    }

    public ValidationException(ValidationCode code, List<String> args, List<ValidationIssue> issues,
            Map<String, List<String>> details, Throwable cause) {
        super(code, args, details, cause);
        this.issues = List.copyOf(issues);
    }

    /** VALIDATION_FAILED for one path; {@code reasonArgs} fill the placeholders of the reason's pattern. */
    public static ValidationException invalid(String path, ValidationReason reason, String... reasonArgs) {
        return invalid(path, reason, List.of(), reasonArgs);
    }

    /**
     * VALIDATION_FAILED for one path of a closed value set. The code's arguments are the path only: the reason lives
     * in the issue, and its sentence comes from the reason bundles.
     */
    public static ValidationException invalid(String path, ValidationReason reason, List<String> allowed,
            String... reasonArgs) {
        return new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(path),
                List.of(new ValidationIssue(path, reason, List.of(reasonArgs), allowed)), Map.of());
    }

    /** VALIDATION_FAILED for one path whose input failed in a parser: {@code cause} is that failure. */
    public static ValidationException invalid(String path, ValidationReason reason, Throwable cause,
            String... reasonArgs) {
        return new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(path),
                List.of(new ValidationIssue(path, reason, List.of(reasonArgs), List.of())), Map.of(), cause);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }

    @Override
    protected LedgerException copyWith(Map<String, List<String>> newDetails) {
        return new ValidationException((ValidationCode) code(), args(), issues, newDetails, getCause());
    }
}
