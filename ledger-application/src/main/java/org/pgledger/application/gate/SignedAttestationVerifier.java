package org.pgledger.application.gate;

import java.security.PublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.AuthorId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Ed25519 check of the author attestation. The checks run in a fixed order and the first one that fails is the
 * answer, so a caller that cannot prove who it is learns nothing about the later checks: parse and author format, the
 * signature under the live registered key of the author (a missing key and a wrong signature are one and the same
 * answer, {@link TrustCode#ATTESTATION_SIGNATURE_INVALID} without any argument, so a caller that cannot sign cannot find
 * out which authors have a key; the precise reason is logged here and nowhere else), the binding to the tool, the
 * request id and the arguments of the call, the time window. The time is the injected {@link Clock}.
 */
@Component
public class SignedAttestationVerifier implements AttestationVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(SignedAttestationVerifier.class);

    private final SessionKeyStateRepository keys;
    private final AttestationProperties properties;
    private final Clock clock;

    public SignedAttestationVerifier(SessionKeyStateRepository keys, AttestationProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public AuthorId verify(String presented, String tool, String requestId, String argumentsDigest) {
        return check(presented, tool, requestId, argumentsDigest, false);
    }

    @Override
    public AuthorId verifyForWrite(String presented, String tool, String requestId, String argumentsDigest) {
        return check(presented, tool, requestId, argumentsDigest, true);
    }

    private AuthorId check(String presented, String tool, String requestId, String argumentsDigest, boolean hold) {
        AttestationToken token = parse(presented);
        AttestationClaims claims = token.claims();
        AuthorId author = AuthorId.of(claims.author());
        SessionPublicKey registered = provenKey(token, author);
        if (hold && !keys.holdLive(registered.id())) {
            // the key was closed after it was read, before this transaction could lock it: the same answer
            LOG.info("attestation of {} refused: its key was closed meanwhile", author.value());
            throw signatureInvalid();
        }
        verifyBinding(claims, tool, requestId, argumentsDigest);
        verifyWindow(claims);
        return author;
    }

    /**
     * A value that is not a token is told apart: a bare {@code <vendor>:<session-id>} is the unsigned old format
     * (ATTESTATION_UNSIGNED), anything else is a malformed author as before (AUTHOR_ID_FORMAT); the prefix of the
     * retired version 1 is its own answer.
     */
    private static AttestationToken parse(String presented) {
        if (presented == null) {
            throw new TrustException(TrustCode.HOST_ATTESTATION_REQUIRED, List.of());
        }
        if (presented.startsWith(AttestationToken.RETIRED_PREFIX + ".")) {
            throw new TrustException(TrustCode.ATTESTATION_VERSION_UNSUPPORTED, List.of());
        }
        if (!presented.startsWith(AttestationToken.PREFIX + ".")) {
            if (AuthorId.matches(presented)) {
                throw new TrustException(TrustCode.ATTESTATION_UNSIGNED, List.of());
            }
            throw new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of(presented));
        }
        try {
            return AttestationToken.parse(presented);
        } catch (AttestationException failure) {
            throw new TrustException(TrustCode.ATTESTATION_MALFORMED, List.of(), Map.of(), failure);
        }
    }

    /** The live key of the author, once the signature is proven under it; every other outcome is one answer. */
    private SessionPublicKey provenKey(AttestationToken token, AuthorId author) {
        Optional<SessionPublicKey> registered = keys.findLive(author);
        if (registered.isEmpty()) {
            LOG.info("attestation of {} refused: no live session key is registered", author.value());
            throw signatureInvalid();
        }
        try {
            PublicKey key = PublicKeys.decode(registered.get().publicKey());
            token.verify(key);
        } catch (AttestationException failure) {
            LOG.info("attestation of {} refused: the signature does not match its live session key", author.value());
            throw new TrustException(TrustCode.ATTESTATION_SIGNATURE_INVALID, List.of(), Map.of(), failure);
        }
        return registered.get();
    }

    private static TrustException signatureInvalid() {
        return new TrustException(TrustCode.ATTESTATION_SIGNATURE_INVALID, List.of());
    }

    private static void verifyBinding(AttestationClaims claims, String tool, String requestId, String argumentsDigest) {
        if (!Objects.equals(tool, claims.tool())) {
            throw mismatch("tool");
        }
        if (!Objects.equals(requestId == null ? "" : requestId, claims.requestId())) {
            throw mismatch("request_id");
        }
        if (!Objects.equals(argumentsDigest, claims.argumentsDigest())) {
            throw mismatch("arguments");
        }
    }

    private static TrustException mismatch(String field) {
        return new TrustException(TrustCode.ATTESTATION_BINDING_MISMATCH, List.of(field),
                Map.of("field", List.of(field)));
    }

    private void verifyWindow(AttestationClaims claims) {
        long window = properties.window().toSeconds();
        if (Math.abs(clock.instant().getEpochSecond() - claims.issuedAt()) > window) {
            throw new TrustException(TrustCode.ATTESTATION_TIME_WINDOW, List.of(Long.toString(window)),
                    Map.of("window_seconds", List.of(Long.toString(window))));
        }
    }
}
