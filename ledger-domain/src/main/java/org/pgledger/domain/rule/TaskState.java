package org.pgledger.domain.rule;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.SessionKey;

/** State of a task, with its transition policy. */
public enum TaskState implements LifecycleState {
    UNKNOWN, PLANNED, ACTIVE, BLOCKED, REPORTED_DONE, VERIFIED, CANCELLED;

    @Override
    public boolean verification() {
        return this == VERIFIED;
    }

    /**
     * Checks a move from this state to {@code to}.
     *
     * @param owner the session that owns the task, if any
     */
    public void requireTransitionTo(TaskState to, Evidence evidence, SessionKey actor, Optional<SessionKey> owner,
            TaskReports reports) {
        if (this == to || to == UNKNOWN) {
            throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
        }
        if (to == REPORTED_DONE && !evidence.provided()) {
            throw evidenceRequired(to);
        }
        if (this == VERIFIED && !evidence.provided()) {
            throw evidenceRequired(to);
        }
        if (to == VERIFIED) {
            if (this != REPORTED_DONE) {
                throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
            }
            if (!evidence.provided()) {
                throw evidenceRequired(to);
            }
            if (owner.filter(actor::equals).isPresent() || reports.latestReporter().filter(actor::equals).isPresent()) {
                throw new TransitionException(TransitionCode.SAME_SESSION, List.of());
            }
        }
    }

    private static TransitionException evidenceRequired(TaskState to) {
        return new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
    }
}
