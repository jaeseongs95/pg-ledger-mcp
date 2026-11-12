package org.pgledger.seed;

/** The seed bundle cannot be built or read (a bad source file, a missing or damaged bundle); the message says which and why. */
public final class SeedUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SeedUnavailableException(String message) {
        super(message);
    }

    public SeedUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
