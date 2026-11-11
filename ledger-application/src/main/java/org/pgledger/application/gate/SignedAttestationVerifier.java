package org.pgledger.application.gate;

import java.security.PublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.AuthorId;
import org.springframework.stereotype.Component;

/**
 * The Ed25519 check of the author attestation. The checks run in a fixed order and the first one that fails is the
 * answer, so a caller that cannot prove who it is learns nothing about the later checks: parse and author format, the
 * live registered key of the author, the signature, the binding to the tool and the request id of the call, the time
 * window. The time is the injected {@link Clock}.
 */
@Component
public class SignedAttestationVerifier implements AttestationVerifier {

    private final SessionKeyStateRepository keys;
    private final AttestationProperties properties;
    private final Clock clock;

    public SignedAttestationVerifier(SessionKeyStateRepository keys, AttestationProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public AuthorId verify(String presented, String tool, String requestId) {
        AttestationToken token = parse(presented);
        AttestationClaims claims = token.claims();
        AuthorId author = AuthorId.of(claims.author());
        SessionPublicKey registered = keys.findLive(author).orElseThrow(() -> new TrustException(
                TrustCode.SESSION_KEY_NOT_REGISTERED, List.of(author.value()),
                Map.of("author", List.of(author.value()))));
        verifySignature(token, registered, author);
        verifyBinding(claims, tool, requestId);
        verifyWindow(claims);
        return author;
    }

    /**
     * A value that is not a token is told apart: a bare {@code <vendor>:<session-id>} is the unsigned old format
     * (ATTESTATION_UNSIGNED), anything else is a malformed author as before (AUTHOR_ID_FORMAT).
     */
    private static AttestationToken parse(String presented) {
        if (presented == null) {
            throw new TrustException(TrustCode.HOST_ATTESTATION_REQUIRED, List.of());
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

    private static void verifySignature(AttestationToken token, SessionPublicKey registered, AuthorId author) {
        try {
            PublicKey key = PublicKeys.decode(registered.publicKey());
            token.verify(key);
        } catch (AttestationException failure) {
            throw new TrustException(TrustCode.ATTESTATION_SIGNATURE_INVALID, List.of(author.value()),
                    Map.of("author", List.of(author.value())), failure);
        }
    }

    private static void verifyBinding(AttestationClaims claims, String tool, String requestId) {
        if (!Objects.equals(tool, claims.tool())) {
            throw mismatch("tool");
        }
        if (!Objects.equals(requestId == null ? "" : requestId, claims.requestId())) {
            throw mismatch("request_id");
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
