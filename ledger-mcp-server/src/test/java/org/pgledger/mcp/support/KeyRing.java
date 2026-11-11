package org.pgledger.mcp.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.testing.TestOwners;

/**
 * The key pairs of the test authors of one server and the signing with them: what the client hook of an author does,
 * and what the owner does when it registers the public key the hook printed. A bean of the test context, so every
 * server under test has its own.
 */
public final class KeyRing {

    private final ConcurrentHashMap<String, KeyPair> pairs = new ConcurrentHashMap<>();
    private final Set<String> enrolled = ConcurrentHashMap.newKeySet();
    private final SessionKeyService owner;
    private final Clock clock;

    public KeyRing(SessionKeyService owner, Clock clock) {
        this.owner = owner;
        this.clock = clock;
    }

    /** A fresh key pair that belongs to nobody. */
    public static KeyPair newPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key pair generator", failure);
        }
    }

    /** The key pair of the author, made when it is asked for the first time. */
    public KeyPair pairOf(String author) {
        return pairs.computeIfAbsent(author, name -> newPair());
    }

    /** The public key of the author in the form the owner registers. */
    public String publicKeyOf(String author) {
        return PublicKeys.encode(pairOf(author).getPublic());
    }

    /** Registers the key of the author as the owner does (once per author and server). */
    public void enroll(String author) {
        if (!enrolled.contains(author)) {
            owner.register(new SessionKeyService.RegisterCommand(author, publicKeyOf(author), null), TestOwners.OWNER);
            enrolled.add(author);
        }
    }

    /** The attestation for the call, signed now with the key of the author. */
    public String token(String author, String tool, String requestId) {
        return token(pairOf(author), author, tool, requestId, clock.instant());
    }

    /** The attestation for {@code author} signed with any key pair at the given time. */
    public static String token(KeyPair signer, String author, String tool, String requestId, Instant issuedAt) {
        return AttestationToken.sign(new AttestationClaims(author, tool, requestId, issuedAt.getEpochSecond()),
                signer.getPrivate()).text();
    }

    public Clock clock() {
        return clock;
    }
}
