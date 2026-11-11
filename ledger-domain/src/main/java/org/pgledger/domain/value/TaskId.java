package org.pgledger.domain.value;

/** Id of a task: free upper-case text ({@link EntityId#PATTERN}); the server issues {@code <CODE>-T-NNNN}. */
public record TaskId(String value) {

    public TaskId {
        EntityId.requireValid("task_id", value);
    }

    public static TaskId of(String value) {
        return new TaskId(value);
    }

    /** The id the server issues for task number {@code number} of a project. */
    public static TaskId issue(ProjectCode code, long number) {
        return new TaskId(code.value() + "-T-" + DefectId.zeroPadded(number));
    }
}
