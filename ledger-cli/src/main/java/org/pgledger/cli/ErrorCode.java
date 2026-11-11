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
    IO_FAILED,
    INVALID_PUBLIC_KEY,
    FINGERPRINT_MISMATCH,
    DRIVER_NOT_CONFIGURED,
    KEY_REPLACEMENT_NOT_CONFIRMED,
    REPLACE_FINGERPRINT_STALE,
    KEY_IN_USE,
    INVALID_AUTHOR,
    LEDGER_REFUSED,
    DATABASE_FAILED,
    MISSING_OPTION_VALUE,
    UNKNOWN_OPTION;

    public String messageKey() {
        return "error." + name();
    }
}
