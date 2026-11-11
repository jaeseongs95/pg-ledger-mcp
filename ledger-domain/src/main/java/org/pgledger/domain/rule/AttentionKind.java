package org.pgledger.domain.rule;

/** The kinds of item that need a look. */
public enum AttentionKind {
    OPEN_WITH_FIX, FIXED_WITHOUT_COMMIT, OPEN_NO_OWNER, TASK_UNKNOWN, DONE_NOT_VERIFIED
}
