package org.pgledger.hook.keys;

/**
 * The session key of an author cannot be used. The reason says which check failed; the message is the reason name and
 * never carries key material or a file name.
 */
public final class SessionKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which check failed. */
    public enum Reason {
        /** The author text is empty or holds a line break (the key file keeps it on its own line). */
        AUTHOR_INVALID,
        /** The key folder is not configured, is not a usable path or is not a directory. */
        FOLDER_UNAVAILABLE,
        /** Group or others have access to the folder or the key file (POSIX); it is refused, never repaired. */
        TOO_OPEN,
        /** The key file is a link, a directory or another special file. */
        NOT_A_REGULAR_FILE,
        /** The key file is not in the stored format (wrong lines, text that is not UTF-8 or not a key). */
        CORRUPT,
        /** The key file belongs to another author. */
        AUTHOR_MISMATCH,
        /** The private and the public key of the file are not a pair. */
        KEY_MISMATCH,
        /** Reading or creating the folder or the file failed. */
        IO_FAILED
    }

    private final Reason reason;

    public SessionKeyException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public SessionKeyException(Reason reason) {
        this(reason, null);
    }

    public Reason reason() {
        return reason;
    }
}
