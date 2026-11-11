package org.pgledger.hook.keys;

import java.security.PrivateKey;
import java.security.PublicKey;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;

/**
 * The key pair of one author. The private key stays inside: it can sign an attestation and nothing else (no getter, and
 * {@link #toString()} names only the author), so it cannot reach a log, a message or an output by accident.
 */
public final class SessionKey {

    private final String author;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    SessionKey(String author, PrivateKey privateKey, PublicKey publicKey) {
        this.author = author;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public String author() {
        return author;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    /** Public key text and fingerprint for the owner. */
    public KeyInfo info() {
        return new KeyInfo(author, PublicKeys.encode(publicKey), PublicKeys.fingerprint(publicKey));
    }

    /** Signs the claims with the private key of the author. */
    public AttestationToken sign(AttestationClaims claims) {
        return AttestationToken.sign(claims, privateKey);
    }

    @Override
    public String toString() {
        return "SessionKey[author=" + author + "]";
    }
}
