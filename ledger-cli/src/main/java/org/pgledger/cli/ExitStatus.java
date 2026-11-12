package org.pgledger.cli;

/** Process exit codes of every command. */
public enum ExitStatus {

    SUCCESS(0),
    FAILURE(1),
    USAGE(2),
    /** The command stopped because a step that only the operator can do is missing (a rerun continues where it stopped). */
    OPERATOR_STEP(3);

    private final int code;

    ExitStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
