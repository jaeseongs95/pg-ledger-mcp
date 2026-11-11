package org.pgledger.cli;

/** The command line is fine but the work was refused or failed (exit code 1). */
public final class FailureException extends CliException {

    private static final long serialVersionUID = 1L;

    public FailureException(ErrorCode code, String... arguments) {
        super(code, null, arguments);
    }

    public FailureException(ErrorCode code, Throwable cause, String... arguments) {
        super(code, cause, arguments);
    }
}
