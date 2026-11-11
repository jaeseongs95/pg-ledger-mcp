package org.pgledger.domain.rule;

import org.pgledger.domain.value.ActorId;

/** One recorded task transition (from the history), oldest first in a {@link TaskReports}. */
public record TaskTransition(TaskState from, TaskState to, ActorId author) {
}
