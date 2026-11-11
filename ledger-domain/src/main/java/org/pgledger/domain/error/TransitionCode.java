package org.pgledger.domain.error;

/** Error tokens of category TRANSITION. */
public enum TransitionCode implements ErrorCode {
    TRANSITION_INVALID,
    FIX_REQUIRED,
    FIX_ALREADY_RECORDED,
    EVIDENCE_REQUIRED,
    MERGE_TARGET_INVALID,
    MERGED_DEFECT,
    SAME_SESSION,
    PROJECT_MOVE_FORBIDDEN,
    NOTHING_CHANGES;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.TRANSITION;
    }
}
