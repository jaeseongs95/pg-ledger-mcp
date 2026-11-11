package org.pgledger.cli.key;

/**
 * Where a session key is stored for an author: the door of the command {@code key-register} to the ledger. The
 * implementation belongs to the part of the tool that has the database connection (the application service
 * {@code SessionKeyService} behind it); a refusal of the ledger is thrown as a {@code CliException}.
 */
public interface SessionKeyRegistry {

    /**
     * Binds the key to the author; the owner already compared the fingerprint.
     *
     * @param publicKey the key in its canonical form (X.509 DER, standard base64)
     */
    void register(String author, String publicKey);
}
