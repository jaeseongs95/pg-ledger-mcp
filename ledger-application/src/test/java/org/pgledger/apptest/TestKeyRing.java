package org.pgledger.apptest;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;

/**
 * The Ed25519 key pairs of the test authors of one Spring context, made on first use, and the signing of attestations
 * with them. It plays the hook of the author: the one place of the service tests where a signature is made.
 */
public final class TestKeyRing {

    private final ConcurrentHashMap<String, KeyPair> pairs = new ConcurrentHashMap<>();

    /** A fresh key pair that belongs to nobody (for a signature that the server must refuse). */
    public static KeyPair newPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key pair generator", failure);
        }
    }

    /** The key pair of the author (made when it is asked for the first time). */
    public KeyPair pairOf(String author) {
        return pairs.computeIfAbsent(author, name -> newPair());
    }

    /** The public key of the author in the form the owner registers. */
    public String publicKeyOf(String author) {
        return PublicKeys.encode(pairOf(author).getPublic());
    }

    /** The fingerprint of the public key of the author. */
    public String fingerprintOf(String author) {
        return PublicKeys.fingerprint(pairOf(author).getPublic());
    }

    /** The text of an attestation signed with the key of the author. */
    public String token(String author, String tool, String requestId, Instant issuedAt) {
        return token(pairOf(author), author, tool, requestId, issuedAt);
    }

    /** The text of an attestation for {@code author} signed with any key pair (for example the pair of another author). */
    public static String token(KeyPair signer, String author, String tool, String requestId, Instant issuedAt) {
        return AttestationToken.sign(new AttestationClaims(author, tool, requestId, issuedAt.getEpochSecond()),
                signer.getPrivate()).text();
    }
}
