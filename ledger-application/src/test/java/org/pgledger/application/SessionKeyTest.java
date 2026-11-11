package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.RegisterCommand;
import org.pgledger.application.admin.SessionKeyService.Registered;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.SaveService;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.apptest.TestKeyRing;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.SessionPublicKey;

/** SVC-KEY-01 .. SVC-KEY-08: the owner binds public keys to authors (admin web and CLI, never a tool). */
@LedgerServiceTest
class SessionKeyTest {

    private static String newAuthor() {
        return "claude:key" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String publicKey(KeyPair pair) {
        return PublicKeys.encode(pair.getPublic());
    }

    @Test
    @DisplayName("SVC-KEY-01: registering binds the key to the author, answers with the fingerprint the owner compares, and records the owner and the time")
    void key01_register(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        KeyPair pair = TestKeyRing.newPair();
        Registered registered = keys.register(new RegisterCommand(author, publicKey(pair), "laptop"));
        assertThat(registered.author()).isEqualTo(author);
        assertThat(registered.fingerprint()).isEqualTo(PublicKeys.fingerprint(pair.getPublic())).matches("[0-9a-f]{64}");
        SessionPublicKey live = keys.live(author).orElseThrow();
        assertThat(live.id()).isEqualTo(registered.id());
        assertThat(live.publicKey()).isEqualTo(publicKey(pair));
        assertThat(live.registeredBy().value()).isEqualTo("user:owner");
        assertThat(live.registeredAt()).isEqualTo(s.ctx().clock().instant());
        assertThat(live.revokedAt()).isNull();
        assertThat(live.note()).isEqualTo("laptop");
        assertThat(keys.live(newAuthor())).isEmpty();
    }

    @Test
    @DisplayName("SVC-KEY-02: registering the key that is already live for the author changes nothing; spaces around the text are ignored")
    void key02_registerTwice(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        KeyPair pair = TestKeyRing.newPair();
        Registered first = keys.register(new RegisterCommand(author, publicKey(pair), null));
        Registered second = keys.register(new RegisterCommand(author, " " + publicKey(pair) + "\n", "again"));
        assertThat(second).isEqualTo(first);
        assertThat(keys.history(author)).hasSize(1);
        assertThat(keys.history(author).getFirst().note()).isNull();
    }

    @Test
    @DisplayName("SVC-KEY-03: a new key replaces the live one by closing it; the closed row stays, the old key no longer verifies and the new one does")
    void key03_replace(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = s.actorWith("defect");
        KeyPair replacement = TestKeyRing.newPair();
        long old = keys.live(author).orElseThrow().id();
        s.ctx().clock().set(s.ctx().clock().instant().plusSeconds(60));
        Registered registered = keys.register(new RegisterCommand(author, publicKey(replacement), "rotated"));
        assertThat(registered.id()).isNotEqualTo(old);
        List<SessionPublicKey> history = keys.history(author);
        assertThat(history).extracting(SessionPublicKey::id).containsExactly(old, registered.id());
        assertThat(history.getFirst().revokedAt()).isEqualTo(s.ctx().clock().instant());
        assertThat(history.getFirst().activeKey()).isEqualTo(SessionPublicKey.closedKey(old));
        assertThat(history.getLast().revokedAt()).isNull();

        String request = UUID.randomUUID().toString();
        String oldToken = s.ctx().keys().token(author, "ledger_save", request, s.ctx().clock().instant());
        Expect.failure(() -> asSave(s, oldToken, request), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        String newToken = TestKeyRing.token(replacement, author, "ledger_save", request, s.ctx().clock().instant());
        assertThat(asSave(s, newToken, request).author()).isEqualTo(author);
    }

    private static org.pgledger.application.write.WriteResult.SaveResult asSave(Scenario s, String token, String request) {
        return ScopedValue.where(org.pgledger.domain.context.ToolScope.CURRENT, "ledger_save").call(
                () -> s.bean(SaveService.class).save(token, new org.pgledger.application.write.SaveCommand(request,
                        "defect", null, 0L, s.defectFields("by key"), Scenario.REASON)));
    }

    @Test
    @DisplayName("SVC-KEY-04: a key that is live for one author cannot be registered for another (DUPLICATE_KEY); once the first author's key is revoked it can")
    void key04_oneAuthorPerKey(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String first = newAuthor();
        String second = newAuthor();
        String key = publicKey(TestKeyRing.newPair());
        keys.register(new RegisterCommand(first, key, null));
        LedgerException failure = Expect.failure(() -> keys.register(new RegisterCommand(second, key, null)),
                ConflictCode.DUPLICATE_KEY);
        assertThat(failure.args().getFirst()).startsWith("public key ");
        assertThat(keys.live(second)).isEmpty();
        assertThat(keys.revoke(first)).isTrue();
        assertThat(keys.register(new RegisterCommand(second, key, null)).author()).isEqualTo(second);
    }

    @Test
    @DisplayName("SVC-KEY-05: a text that is not an Ed25519 X.509 public key, and an author without the ledger format, are refused and register nothing")
    void key05_validation(Scenario s) throws NoSuchAlgorithmException {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        String rsa = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("RSA").generateKeyPair()
                .getPublic().getEncoded());
        String privateKey = Base64.getEncoder().encodeToString(TestKeyRing.newPair().getPrivate().getEncoded());
        for (String bad : new String[] {null, "", "   ", "not base64!", rsa, privateKey, "AAAA"}) {
            LedgerException failure = Expect.failure(() -> keys.register(new RegisterCommand(author, bad, null)),
                    ValidationCode.VALIDATION_FAILED);
            assertThat(failure.args()).containsExactly("public_key");
            assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.MUST_BE_ED25519_PUBLIC_KEY);
        }
        String good = publicKey(TestKeyRing.newPair());
        Expect.failure(() -> keys.register(new RegisterCommand("claude", good, null)), TrustCode.AUTHOR_ID_FORMAT);
        Expect.failure(() -> keys.register(new RegisterCommand("user:someone", good, null)), TrustCode.AUTHOR_ID_FORMAT);
        LedgerException missing = Expect.failure(() -> keys.register(new RegisterCommand(null, good, null)),
                ValidationCode.VALIDATION_FAILED);
        assertThat(missing.args()).containsExactly("author");
        assertThat(keys.history(author)).isEmpty();
    }

    @Test
    @DisplayName("SVC-KEY-06: revoking closes the live key and keeps its row; the author cannot write until a key is registered again; revoking twice is false")
    void key06_revoke(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = s.actorWith("defect");
        assertThat(keys.revoke(author)).isTrue();
        assertThat(keys.revoke(author)).isFalse();
        assertThat(keys.revoke(newAuthor())).isFalse();
        assertThat(keys.live(author)).isEmpty();
        assertThat(keys.history(author)).hasSize(1).allSatisfy(row -> assertThat(row.revokedAt()).isNotNull());
        String request = UUID.randomUUID().toString();
        String token = s.ctx().keys().token(author, "ledger_save", request, s.ctx().clock().instant());
        Expect.failure(() -> asSave(s, token, request), TrustCode.SESSION_KEY_NOT_REGISTERED);
        keys.register(new RegisterCommand(author, publicKey(s.ctx().keys().pairOf(author)), null));
        assertThat(asSave(s, token, request).author()).isEqualTo(author);
        assertThat(keys.history(author)).hasSize(2);
    }

    @Test
    @DisplayName("SVC-KEY-07: two registrations of one author at the same time leave exactly one live key and keep both rows")
    void key07_parallelRegistration(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        List<Callable<Object>> calls = IntStream.range(0, 2).<Callable<Object>>mapToObj(i -> () -> keys.register(
                new RegisterCommand(author, publicKey(TestKeyRing.newPair()), "n" + i))).toList();
        assertThat(Parallel.all(calls)).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(s.count("SELECT COUNT(*) FROM session_key WHERE author_id = ? AND revoked_at IS NULL", author))
                .isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM session_key WHERE author_id = ?", author)).isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-KEY-08: key ids come from the counter without gaps and the rows carry no secret: only the public key and its fingerprint")
    void key08_rows(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        keys.register(new RegisterCommand(author, publicKey(TestKeyRing.newPair()), null));
        keys.revoke(author);
        keys.register(new RegisterCommand(author, publicKey(TestKeyRing.newPair()), null));
        List<Long> ids = keys.history(author).stream().map(SessionPublicKey::id).toList();
        assertThat(ids.get(1)).isEqualTo(ids.get(0) + 1);
        assertThat(keys.history(author)).allSatisfy(row -> {
            assertThat(row.publicKey()).hasSize(60);
            assertThat(row.fingerprint()).matches("[0-9a-f]{64}");
            assertThat(row.toString()).doesNotContain("PRIVATE");
        });
    }
}
