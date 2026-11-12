package org.pgledger.domain.rule;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.value.SessionKey;

/** The transitions of one task, oldest first; tells who reported it done last. */
public record TaskReports(List<TaskTransition> transitions) {

    public TaskReports {
        transitions = List.copyOf(transitions);
    }

    /**
     * The session of the latest REPORTED_DONE transition that did not come from VERIFIED (sending a verified task
     * back is not a report).
     */
    public Optional<SessionKey> latestReporter() {
        return latestReport().map(t -> t.author().sessionKey());
    }

    /** The latest REPORTED_DONE transition that did not come from VERIFIED: who made it, as the history recorded it. */
    public Optional<TaskTransition> latestReport() {
        return transitions.reversed().stream()
                .filter(t -> t.to() == TaskState.REPORTED_DONE && t.from() != TaskState.VERIFIED)
                .findFirst();
    }

    /** Whether {@code session} is the latest reporter of the task. */
    public boolean reportedBy(SessionKey session) {
        return latestReporter().filter(session::equals).isPresent();
    }
}
