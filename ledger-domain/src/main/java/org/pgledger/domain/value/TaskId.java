package org.pgledger.domain.value;

import java.util.Optional;
import org.pgledger.domain.error.ValidationException;

/** Id of a task: free upper-case text ({@link EntityId#PATTERN}); the server issues {@code <CODE>-T-NNNN}. */
public record TaskId(String value) {

    public TaskId {
        EntityId.requireValid("task_id", value);
    }

    /** The id of a text, or none when the text is not an id; nothing is thrown. */
    public static Optional<TaskId> parse(String value) {
        return EntityId.isValid(value) ? Optional.of(new TaskId(value)) : Optional.empty();
    }

    /** The failure of a text that is not a task id. */
    public static ValidationException malformed() {
        return EntityId.malformed("task_id");
    }

    public static TaskId of(String value) {
        return new TaskId(value);
    }

    /** The id the server issues for task number {@code number} of a project. */
    public static TaskId issue(ProjectCode code, long number) {
        return new TaskId(code.value() + "-T-" + DefectId.zeroPadded(number));
    }
}
