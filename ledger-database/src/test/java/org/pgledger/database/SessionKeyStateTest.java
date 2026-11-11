package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.SessionKeyRepository;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.AuthorId;
import org.springframework.dao.DuplicateKeyException;

/** REPO-050 .. REPO-053: the session key table (one live key per author, one author per live key, rows never deleted). */
@DatabaseTest
class SessionKeyStateTest {

    private static final Instant REGISTERED = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-11-05T12:00:00Z");

    private LedgerTestContext ctx;
    private SessionKeyRepository store;
    private SessionKeyStateRepository state;
    private IdAllocator ids;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        store = ctx.bean(SessionKeyRepository.class);
        state = ctx.bean(SessionKeyStateRepository.class);
        ids = ctx.bean(IdAllocator.class);
    }

    private static AuthorId newAuthor() {
        return AuthorId.of("claude:k" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static String newFingerprint() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private SessionPublicKey register(AuthorId author, String fingerprint) {
        return ctx.tx(() -> store.insert(SessionPublicKey.register(ids.next(Counter.SESSION_KEY), author,
                "MCow" + fingerprint.substring(0, 40), fingerprint, AuthorId.OWNER, REGISTERED, "note")));
    }

    @Test
    @DisplayName("REPO-050 a registered key is found as the live key of its author and by its fingerprint; a closed key is found by neither")
    void liveLookups() {
        AuthorId author = newAuthor();
        String fingerprint = newFingerprint();
        SessionPublicKey key = register(author, fingerprint);
        assertThat(state.findLive(author)).hasValueSatisfying(found -> {
            assertThat(found.id()).isEqualTo(key.id());
            assertThat(found.fingerprint()).isEqualTo(fingerprint);
            assertThat(found.registeredBy()).isEqualTo(AuthorId.OWNER);
            assertThat(found.registeredAt()).isEqualTo(REGISTERED);
            assertThat(found.revokedAt()).isNull();
        });
        assertThat(state.findLiveByFingerprint(fingerprint)).hasValueSatisfying(found ->
                assertThat(found.authorId()).isEqualTo(author));
        assertThat(state.findLive(newAuthor())).isEmpty();
        assertThat(ctx.tx(() -> state.close(key.id(), CLOSED))).isTrue();
        assertThat(state.findLive(author)).isEmpty();
        assertThat(state.findLiveByFingerprint(fingerprint)).isEmpty();
    }

    @Test
    @DisplayName("REPO-051 a second live key of one author, and the same key for a second author, are refused by the database")
    void uniqueLiveKeys() {
        AuthorId author = newAuthor();
        String fingerprint = newFingerprint();
        register(author, fingerprint);
        assertThatThrownBy(() -> register(author, newFingerprint())).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> register(newAuthor(), fingerprint)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("REPO-052 closing frees both keys: a new key for the author and the old key for another author work, closed rows pile up and are never deleted")
    void closingFreesTheKeys() {
        AuthorId author = newAuthor();
        String first = newFingerprint();
        SessionPublicKey one = register(author, first);
        assertThat(ctx.tx(() -> state.close(one.id(), CLOSED))).isTrue();
        assertThat(ctx.tx(() -> state.close(one.id(), CLOSED))).as("a closed key cannot be closed again").isFalse();
        SessionPublicKey two = register(author, newFingerprint());
        assertThat(ctx.tx(() -> state.close(two.id(), CLOSED))).isTrue();
        SessionPublicKey three = register(author, newFingerprint());
        AuthorId other = newAuthor();
        SessionPublicKey reused = register(other, first);
        assertThat(state.findHistory(author)).extracting(SessionPublicKey::id)
                .containsExactly(one.id(), two.id(), three.id());
        assertThat(state.findHistory(author)).extracting(SessionPublicKey::revokedAt)
                .containsExactly(CLOSED, CLOSED, null);
        assertThat(state.findHistory(author).getFirst().activeKey()).isEqualTo("revoked:" + one.id());
        assertThat(state.findHistory(author).getFirst().fingerprintKey()).isEqualTo("revoked:" + one.id());
        assertThat(state.findLiveByFingerprint(first)).hasValueSatisfying(found ->
                assertThat(found.id()).isEqualTo(reused.id()));
        assertThat(store.findById(one.id())).isPresent();
    }

    @Test
    @DisplayName("REPO-053 the author of a key can be called revoked: and the live key text of one author never equals the closed key text of a row")
    void liveAndClosedKeysCannotCollide() {
        AuthorId strange = AuthorId.of("revoked:1");
        SessionPublicKey key = register(strange, newFingerprint());
        assertThat(key.activeKey()).isEqualTo("revoked:1|live");
        assertThat(SessionPublicKey.closedKey(1)).isEqualTo("revoked:1").isNotEqualTo(key.activeKey());
        assertThat(state.findLive(strange)).isPresent();
    }
}
