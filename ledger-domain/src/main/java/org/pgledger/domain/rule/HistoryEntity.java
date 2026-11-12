package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/** The kind of row a history entry is about. */
public enum HistoryEntity implements Token {
    DEFECT("defect"),
    TASK("task"),
    CONDITION("condition"),
    DEFECT_FIX("defect_fix"),
    DEFECT_TASK("defect_task"),
    DEFECT_RELATION("defect_relation"),
    CONDITION_LINK("condition_link"),
    DEFECT_CASE("defect_case");

    private final String token;

    HistoryEntity(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * The grant scope that writes this kind of row: the scope a session needs on the project of the row (a link or a
     * case is written with the scope of the defect or the task it hangs on; a verification needs {@link Scope#VERIFY}
     * on top of this, see {@link Scope#forTransition}).
     */
    public Scope writeScope() {
        return switch (this) {
            case DEFECT, DEFECT_TASK, DEFECT_RELATION, DEFECT_CASE -> Scope.DEFECT;
            case TASK, CONDITION, CONDITION_LINK -> Scope.TASK;
            case DEFECT_FIX -> Scope.FIX;
        };
    }
}
