package org.pgledger.domain.rule;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.value.Token;

/** What a session grant allows on a project. */
public enum Scope implements Token {
    DEFECT("defect"), TASK("task"), FIX("fix"), VERIFY("verify");

    private final String token;

    Scope(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * The scope a state change needs: VERIFY when it enters or leaves a verification state, otherwise the scope
     * that writes the entity.
     */
    public static Scope forTransition(HistoryEntity entity, LifecycleState from, LifecycleState to) {
        if (from.verification() || to.verification()) {
            return VERIFY;
        }
        return switch (entity) {
            case DEFECT, TASK, CONDITION -> entity.writeScope();
            case DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, CONDITION_LINK, DEFECT_CASE ->
                throw ValidationException.invalid("entity", ValidationReason.HAS_NO_STATE, entity.token());
        };
    }
}
