package org.pgledger.cli;

import java.util.Optional;

/**
 * The command line itself is wrong (exit code 2). Once the command is known, the exception carries it ({@link #command()}),
 * so the report shows the usage line of that command only; before that (no word, or a word that is no command) it has none
 * and the report lists every command.
 */
public final class UsageException extends CliException {

    private static final long serialVersionUID = 1L;

    private final CliCommand command;

    public UsageException(ErrorCode code, String... arguments) {
        super(code, null, arguments);
        this.command = null;
    }

    public UsageException(ErrorCode code, Throwable cause, String... arguments) {
        super(code, cause, arguments);
        this.command = null;
    }

    private UsageException(UsageException refusal, CliCommand command) {
        super(refusal.code(), refusal.getCause(), refusal.arguments().toArray(String[]::new));
        this.command = command;
        setStackTrace(refusal.getStackTrace());
    }

    /** The command whose command line was refused; empty when no command was known. */
    public Optional<CliCommand> command() {
        return Optional.ofNullable(command);
    }

    /** The same refusal, known to belong to this command. */
    public UsageException forCommand(CliCommand refused) {
        return new UsageException(this, refused);
    }
}
