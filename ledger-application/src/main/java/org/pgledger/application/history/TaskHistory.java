package org.pgledger.application.history;

import java.util.List;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.TaskReports;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.rule.TaskTransition;
import org.pgledger.domain.value.Tokens;

/**
 * The history rows of one task, oldest first, as the transitions the rules of the task look at. A history row keeps
 * its states as text, and an imported (legacy) row may hold a state that is none of ours or none at all; such a
 * transition is not a report, like {@link org.pgledger.domain.rule.LifecycleState#of} says of an unknown state, so it
 * is left out here at the boundary and never reaches the rules.
 */
public record TaskHistory(List<HistoryEntry> entries) {

    public TaskHistory {
        entries = List.copyOf(entries);
    }

    /** The transitions whose two states are task states, in the order of the rows. */
    public TaskReports reports() {
        List<TaskTransition> transitions = entries.stream().filter(entry -> entry.op() == HistoryOp.TRANSITION)
                .flatMap(entry -> Tokens.find(entry.fromState(), TaskState.values())
                        .flatMap(from -> Tokens.find(entry.toState(), TaskState.values())
                                .map(to -> new TaskTransition(from, to, entry.authorId())))
                        .stream())
                .toList();
        return new TaskReports(transitions);
    }
}
