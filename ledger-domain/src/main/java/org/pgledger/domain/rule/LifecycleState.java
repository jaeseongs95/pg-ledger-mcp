package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/** A state of a defect, task or condition. Verification states can only be entered or left by a reviewer. */
public sealed interface LifecycleState extends Token permits DefectStatus, TaskState, ConditionState {

    String name();

    /** True for VERIFIED, MET and NOT_MET: moving into or out of such a state is a verification. */
    boolean verification();

    @Override
    default String token() {
        return name();
    }
}
