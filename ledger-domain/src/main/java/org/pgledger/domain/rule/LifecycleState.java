package org.pgledger.domain.rule;

import java.util.Optional;
import org.pgledger.domain.value.Token;
import org.pgledger.domain.value.Tokens;

/** A state of a defect, task or condition. Verification states can only be entered or left by a reviewer. */
public sealed interface LifecycleState extends Token permits DefectStatus, TaskState, ConditionState {

    String name();

    /** True for VERIFIED, MET and NOT_MET: moving into or out of such a state is a verification. */
    boolean verification();

    @Override
    default String token() {
        return name();
    }

    /**
     * The state constant of a state text that a history row keeps, by the kind of entity the row is about; none for
     * a text that is none of that entity's states (an imported row may hold one) and for an entity without states.
     */
    static Optional<LifecycleState> of(HistoryEntity entity, String stored) {
        return switch (entity) {
            case DEFECT -> Tokens.find(stored, DefectStatus.values()).map(state -> state);
            case TASK -> Tokens.find(stored, TaskState.values()).map(state -> state);
            case CONDITION -> Tokens.find(stored, ConditionState.values()).map(state -> state);
            case DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, CONDITION_LINK, DEFECT_CASE -> Optional.empty();
        };
    }

    /** Whether the state text of a history row of {@code entity} is a verification state; false for any other text. */
    static boolean isVerification(HistoryEntity entity, String stored) {
        return of(entity, stored).map(LifecycleState::verification).orElse(false);
    }
}
