package org.pgledger.cli;

/**
 * The command stopped before it was done because a step that only the operator can do is missing, for example a privilege
 * that is a statement of the database system and no part of the product (exit code 3). The command says which step; what it
 * had done stays done and running it again continues where it stopped.
 */
public final class OperatorStepException extends CliException {

    private static final long serialVersionUID = 1L;

    public OperatorStepException(ErrorCode code, String... arguments) {
        super(code, null, arguments);
    }
}
