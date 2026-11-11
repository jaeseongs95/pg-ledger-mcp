package org.pgledger.cli;

/** The command line itself is wrong (exit code 2). */
public final class UsageException extends CliException {

    private static final long serialVersionUID = 1L;

    public UsageException(ErrorCode code, String... arguments) {
        super(code, null, arguments);
    }
}
