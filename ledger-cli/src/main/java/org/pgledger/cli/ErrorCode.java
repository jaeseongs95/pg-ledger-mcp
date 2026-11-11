package org.pgledger.cli;

/** Every reason a command can be refused; each code has a message under {@link #messageKey()} in the message bundles. */
public enum ErrorCode {

    MISSING_COMMAND,
    UNKNOWN_COMMAND,
    MISSING_ARGUMENT,
    TOO_MANY_ARGUMENTS,
    INVALID_SECRET_NAME,
    NO_CONSOLE,
    EMPTY_SECRET,
    INVALID_ENCODING,
    NON_POSIX_FILESYSTEM,
    LOOSE_PERMISSIONS,
    UNEXPECTED_FILE_TYPE,
    FOREIGN_OWNER,
    UNKNOWN_PROCESS_USER,
    PARENT_MISSING,
    IO_FAILED;

    public String messageKey() {
        return "error." + name();
    }
}
