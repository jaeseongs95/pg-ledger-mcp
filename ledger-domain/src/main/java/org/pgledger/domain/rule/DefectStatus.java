package org.pgledger.domain.rule;

import java.util.List;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.SessionKey;

/** Status of a defect, with the transition policy as a pure function of the facts handed in. */
public enum DefectStatus implements LifecycleState {
    OPEN, SOURCE_FIXED, VERIFIED, NOT_DEFECT, DISPUTED, MERGED;

    @Override
    public boolean verification() {
        return this == VERIFIED;
    }

    /**
     * Checks a move from this status to {@code to}; the order of the checks is part of the contract: same state,
     * fix for SOURCE_FIXED, evidence when leaving VERIFIED, then the rules of the target status.
     *
     * @param self   the defect being moved
     * @param target the merge target, or {@code null}
     */
    public void requireTransitionTo(DefectStatus to, Evidence evidence, FixHistory fixes, SessionKey actor,
            DefectId self, MergeTarget target) {
        if (this == to) {
            throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
        }
        if (to == SOURCE_FIXED && !fixes.hasFix()) {
            throw new TransitionException(TransitionCode.FIX_REQUIRED, List.of(to.name()));
        }
        if (this == VERIFIED && !evidence.provided()) {
            throw evidenceRequired(to);
        }
        switch (to) {
            case VERIFIED -> {
                if (this != SOURCE_FIXED) {
                    throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
                }
                if (!evidence.provided()) {
                    throw evidenceRequired(to);
                }
                if (!fixes.hasFix()) {
                    throw new TransitionException(TransitionCode.FIX_REQUIRED, List.of(to.name()));
                }
                if (!fixes.hasFixOtherThan(actor)) {
                    throw new TransitionException(TransitionCode.SAME_SESSION, List.of());
                }
            }
            case NOT_DEFECT, DISPUTED -> {
                if (!evidence.provided()) {
                    throw evidenceRequired(to);
                }
            }
            case MERGED -> {
                if (target == null || target.id().equals(self) || target.status() == MERGED) {
                    throw new TransitionException(TransitionCode.MERGE_TARGET_INVALID, List.of());
                }
            }
            case OPEN, SOURCE_FIXED -> {
                // no further rule: SOURCE_FIXED needed a fix above, OPEN is allowed from every other state
            }
        }
    }

    private static TransitionException evidenceRequired(DefectStatus to) {
        return new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
    }
}
