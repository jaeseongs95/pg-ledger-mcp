package org.pgledger.cli.secret;

/** Boundary to the hidden terminal input; the product implementation is {@link ConsoleSecretInput} only. */
public interface SecretInput {

    /**
     * Reads one secret without echo. The caller owns the returned array and wipes it.
     *
     * @return the typed characters, or null at end of input
     */
    char[] read(String prompt);
}
