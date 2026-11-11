package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.gate.AttestationProperties;
import org.pgledger.application.gate.SignedAttestationVerifier;
import org.pgledger.apptest.TestKeyRing;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.AuthorId;

/**
 * SVC-GATE-41 (audit2 S6): an author without a live key is answered after the same work as an author with a key and a
 * wrong signature (one Ed25519 check), so the time of the answer does not tell which authors have a key. The keys come
 * from a plain in-memory implementation of the key lookup (no database, no timing noise from it).
 */
class AttestationTimingTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final String TOOL = "ledger_save";
    private static final int WARM_UP = 300;
    private static final int ROUNDS = 600;

    /** The lookup of live keys held in memory. */
    private static final class MemoryKeys extends SessionKeyStateRepository {

        private final Map<String, SessionPublicKey> live = new HashMap<>();

        MemoryKeys() {
            super(null);
        }

        void put(String author, KeyPair pair) {
            String text = PublicKeys.encode(pair.getPublic());
            live.put(author, SessionPublicKey.register(1, AuthorId.of(author), text,
                    PublicKeys.fingerprint(pair.getPublic()), AuthorId.OWNER, NOW, null));
        }

        @Override
        public Optional<SessionPublicKey> findLive(AuthorId author) {
            return Optional.ofNullable(live.get(author.value()));
        }
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    @Test
    @DisplayName("SVC-GATE-41: no live key and a wrong signature are the same answer and take the same time (the median of the one is at least half of the other)")
    void svcGate41_noKeyCostsAsMuchAsAWrongSignature() {
        MemoryKeys keys = new MemoryKeys();
        keys.put("claude:haskey", TestKeyRing.newPair());
        SignedAttestationVerifier verifier = new SignedAttestationVerifier(keys,
                new AttestationProperties(Duration.ofMinutes(5)), Clock.fixed(NOW, ZoneOffset.UTC));
        KeyPair stranger = TestKeyRing.newPair();
        String wrongSignature = TestKeyRing.token(stranger, "claude:haskey", TOOL, "r1", TestKeyRing.DIRECT_DIGEST, NOW);
        String noKey = TestKeyRing.token(stranger, "claude:nokey", TOOL, "r1", TestKeyRing.DIRECT_DIGEST, NOW);

        List<Long> withKeyNanos = new ArrayList<>();
        List<Long> withoutKeyNanos = new ArrayList<>();
        List<Object> codes = new ArrayList<>();
        for (int i = 0; i < WARM_UP + ROUNDS; i++) {
            long wrongTook = answer(verifier, wrongSignature, codes);
            long noKeyTook = answer(verifier, noKey, codes);
            if (i >= WARM_UP) {
                withKeyNanos.add(wrongTook);
                withoutKeyNanos.add(noKeyTook);
            }
        }

        assertThat(codes).as("both are answered ATTESTATION_SIGNATURE_INVALID").containsOnly(TrustCode.ATTESTATION_SIGNATURE_INVALID);
        assertThat(median(withoutKeyNanos)).as("median nanoseconds without a key, with a key: %s", median(withKeyNanos))
                .isGreaterThan(median(withKeyNanos) / 2);
    }

    private static long answer(SignedAttestationVerifier verifier, String token, List<Object> codes) {
        long start = System.nanoTime();
        try {
            verifier.verify(token, TOOL, "r1", TestKeyRing.DIRECT_DIGEST);
        } catch (LedgerException refused) {
            long took = System.nanoTime() - start;
            codes.add(refused.code());
            return took;
        }
        throw new AssertionError("the attestation must be refused");
    }
}
