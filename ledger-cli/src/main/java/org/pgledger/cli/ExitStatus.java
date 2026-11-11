package org.pgledger.cli;

/** Process exit codes of every command. */
public enum ExitStatus {

    SUCCESS(0),
    FAILURE(1),
    USAGE(2);

    private final int code;

    ExitStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
