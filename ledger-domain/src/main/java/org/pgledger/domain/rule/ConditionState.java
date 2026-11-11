package org.pgledger.domain.rule;

import java.util.List;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.value.Evidence;

/** State of a release condition, with its transition policy. */
public enum ConditionState implements LifecycleState {
    UNKNOWN, MET, NOT_MET;

    @Override
    public boolean verification() {
        return this == MET || this == NOT_MET;
    }

    /**
     * Checks a move from this state to {@code to}. Moving to MET or NOT_MET needs evidence; moving back to UNKNOWN
     * needs none (unchanged from the old ledger by design).
     */
    public void requireTransitionTo(ConditionState to, Evidence evidence) {
        if (this == to) {
            throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
        }
        if (to.verification() && !evidence.provided()) {
            throw new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
        }
    }
}
