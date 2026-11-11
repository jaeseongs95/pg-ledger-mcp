package org.pgledger.cli.key;

/**
 * Where a session key is stored for an author: the door of the command {@code key-register} to the ledger. The
 * implementation ({@link LedgerSessionKeyRegistry}) exists only when a usable JDBC driver is registered; it calls the
 * application service {@code SessionKeyService}, and a refusal of the ledger is thrown as a {@code CliException}.
 */
public interface SessionKeyRegistry {

    /**
     * Binds the key to an author that has no live key; the owner already compared the fingerprint. Registering the key
     * the author already has changes nothing. An author that has another live key is refused (the key is never
     * replaced silently).
     *
     * @param publicKey the key in its canonical form (X.509 DER, standard base64)
     */
    void register(String author, String publicKey);

    /**
     * As {@link #register}, and replaces the live key of the author when its fingerprint is
     * {@code replacedFingerprint} (the owner read it from the refusal of {@link #register}); any other live key is
     * refused.
     *
     * @param publicKey the key in its canonical form (X.509 DER, standard base64)
     * @param replacedFingerprint the fingerprint of the live key that may be replaced
     */
    void registerReplacing(String author, String publicKey, String replacedFingerprint);

    /**
     * Closes the live key of the author when its fingerprint is {@code fingerprint} (the owner's confirmation); the
     * author keeps its grants and cannot sign until another key is registered.
     *
     * @param fingerprint the fingerprint of the live key, any letter case
     */
    void revoke(String author, String fingerprint);
}
