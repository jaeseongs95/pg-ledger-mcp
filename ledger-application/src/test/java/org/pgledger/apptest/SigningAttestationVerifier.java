package org.pgledger.apptest;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.gate.AttestationVerifier;
import org.pgledger.application.gate.SignedAttestationVerifier;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.domain.value.AuthorId;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.pgledger.testing.TestOwners;

/**
 * The service tests call the services with the plain author of a test ({@code claude:e2e-author}), as the services were
 * called before attestations were signed. This class stands in for the hook of that author: a plain value is
 * signed with the key of the author (registered as the owner does, in a transaction of its own, the first time the
 * author is used) and then checked by the real {@link SignedAttestationVerifier}, so every call of a service test runs
 * the whole verification: key lookup, signature, binding, window. A value that already is a token (a test of the
 * verification itself) is passed to the real verifier as it is.
 *
 * <p>A plain value that has no author format is signed with a key that nobody registered: the format check of the
 * verifier comes first and refuses it, as it did before.
 */
public final class SigningAttestationVerifier implements AttestationVerifier {

    /** The tool of a call that is not made through a tool binding (the service tests call the services directly). */
    static final String DIRECT_TOOL = "ledger_service_test";

    private final SignedAttestationVerifier real;
    private final TestKeyRing keys;
    private final Clock clock;
    private final SessionKeyService sessionKeys;
    private final TransactionTemplate ownTransaction;
    private final Set<String> enrolled = ConcurrentHashMap.newKeySet();

    SigningAttestationVerifier(SignedAttestationVerifier real, TestKeyRing keys, Clock clock,
            SessionKeyService sessionKeys, PlatformTransactionManager transactions) {
        this.real = real;
        this.keys = keys;
        this.clock = clock;
        this.sessionKeys = sessionKeys;
        this.ownTransaction = new TransactionTemplate(transactions,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Override
    public AuthorId verify(String presented, String tool, String requestId, String argumentsDigest) {
        return check(presented, tool, requestId, argumentsDigest, false);
    }

    @Override
    public AuthorId verifyForWrite(String presented, String tool, String requestId, String argumentsDigest) {
        return check(presented, tool, requestId, argumentsDigest, true);
    }

    private AuthorId check(String presented, String tool, String requestId, String argumentsDigest, boolean write) {
        if (presented == null || presented.startsWith(AttestationToken.PREFIX + ".")
                || presented.startsWith(AttestationToken.RETIRED_PREFIX + ".")) {
            return call(presented, tool, requestId, argumentsDigest, write);
        }
        if (AuthorId.matches(presented)) {
            enroll(presented);
        }
        String signedTool = tool == null ? DIRECT_TOOL : tool;
        String signedDigest = argumentsDigest == null ? TestKeyRing.DIRECT_DIGEST : argumentsDigest;
        String token = keys.token(presented, signedTool, requestId == null ? "" : requestId, signedDigest,
                clock.instant());
        return call(token, signedTool, requestId, signedDigest, write);
    }

    private AuthorId call(String token, String tool, String requestId, String argumentsDigest, boolean write) {
        return write ? real.verifyForWrite(token, tool, requestId, argumentsDigest)
                : real.verify(token, tool, requestId, argumentsDigest);
    }

    /**
     * Registers the key of the author as the owner would (once per context): the transaction of its own commits at
     * once, so a rollback of the service call that needs the key does not take the key with it.
     */
    public void enroll(String author) {
        if (!enrolled.contains(author)) {
            ownTransaction.executeWithoutResult(status -> sessionKeys.register(
                    new SessionKeyService.RegisterCommand(author, keys.publicKeyOf(author), null), TestOwners.OWNER));
            enrolled.add(author);
        }
    }
}
