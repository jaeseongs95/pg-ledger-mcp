package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

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
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.OwnerIdentity;

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
        Registered registered = keys.register(new RegisterCommand(author, publicKey(pair), "laptop"), TestOwners.OWNER);
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
        Registered first = keys.register(new RegisterCommand(author, publicKey(pair), null), TestOwners.OWNER);
        Registered second = keys.register(new RegisterCommand(author, " " + publicKey(pair) + "\n", "again"), TestOwners.OWNER);
        assertThat(second).isEqualTo(first);
        assertThat(keys.history(author)).hasSize(1);
        assertThat(keys.history(author).getFirst().note()).isNull();
    }

    @Test
    @DisplayName("SVC-KEY-03: a confirmed replacement (registerReplacing naming the live fingerprint) closes the live key; the closed row stays, the old key no longer verifies and the new one does")
    void key03_replace(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = s.actorWith("defect");
        KeyPair replacement = TestKeyRing.newPair();
        long old = keys.live(author).orElseThrow().id();
        s.ctx().clock().set(s.ctx().clock().instant().plusSeconds(60));
        Registered registered = keys.registerReplacing(new RegisterCommand(author, publicKey(replacement), "rotated"),
                TestOwners.OWNER, keys.live(author).orElseThrow().fingerprint());
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
        return ScopedValue.where(org.pgledger.domain.context.ToolScope.CURRENT, "ledger_save")
                .where(org.pgledger.domain.context.ToolScope.ARGUMENTS_DIGEST, TestKeyRing.DIRECT_DIGEST).call(
                () -> s.bean(SaveService.class).save(token, new org.pgledger.application.write.SaveCommand(request,
                        "defect", null, 0L, org.pgledger.application.support.Fields.of(s.defectFields("by key")), Scenario.REASON)));
    }

    @Test
    @DisplayName("SVC-KEY-04: a key that is live for one author cannot be registered for another (DUPLICATE_KEY); once the first author's key is revoked it can")
    void key04_oneAuthorPerKey(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String first = newAuthor();
        String second = newAuthor();
        String key = publicKey(TestKeyRing.newPair());
        keys.register(new RegisterCommand(first, key, null), TestOwners.OWNER);
        LedgerException failure = Expect.failure(() -> keys.register(new RegisterCommand(second, key, null), TestOwners.OWNER),
                ConflictCode.DUPLICATE_KEY);
        assertThat(failure.args().getFirst()).startsWith("public key ");
        assertThat(keys.live(second)).isEmpty();
        assertThat(keys.revoke(first, TestOwners.OWNER)).isTrue();
        assertThat(keys.register(new RegisterCommand(second, key, null), TestOwners.OWNER).author()).isEqualTo(second);
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
            LedgerException failure = Expect.failure(() -> keys.register(new RegisterCommand(author, bad, null), TestOwners.OWNER),
                    ValidationCode.VALIDATION_FAILED);
            assertThat(failure.args()).containsExactly("public_key");
            assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.MUST_BE_ED25519_PUBLIC_KEY);
        }
        String good = publicKey(TestKeyRing.newPair());
        Expect.failure(() -> keys.register(new RegisterCommand("claude", good, null), TestOwners.OWNER), TrustCode.AUTHOR_ID_FORMAT);
        Expect.failure(() -> keys.register(new RegisterCommand("user:someone", good, null), TestOwners.OWNER), TrustCode.AUTHOR_ID_FORMAT);
        LedgerException missing = Expect.failure(() -> keys.register(new RegisterCommand(null, good, null), TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
        assertThat(missing.args()).containsExactly("author");
        assertThat(keys.history(author)).isEmpty();
    }

    @Test
    @DisplayName("SVC-KEY-06: revoking closes the live key and keeps its row; the author cannot write until a key is registered again; revoking twice is false")
    void key06_revoke(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = s.actorWith("defect");
        assertThat(keys.revoke(author, TestOwners.OWNER)).isTrue();
        assertThat(keys.revoke(author, TestOwners.OWNER)).isFalse();
        assertThat(keys.revoke(newAuthor(), TestOwners.OWNER)).isFalse();
        assertThat(keys.live(author)).isEmpty();
        assertThat(keys.history(author)).hasSize(1).allSatisfy(row -> assertThat(row.revokedAt()).isNotNull());
        String request = UUID.randomUUID().toString();
        String token = s.ctx().keys().token(author, "ledger_save", request, s.ctx().clock().instant());
        Expect.failure(() -> asSave(s, token, request), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        keys.register(new RegisterCommand(author, publicKey(s.ctx().keys().pairOf(author)), null), TestOwners.OWNER);
        assertThat(asSave(s, token, request).author()).isEqualTo(author);
        assertThat(keys.history(author)).hasSize(2);
    }

    @Test
    @DisplayName("SVC-KEY-07: two registrations of one author with different keys at the same time leave exactly one live key; the other is refused KEY_REPLACEMENT_NOT_CONFIRMED")
    void key07_parallelRegistration(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        List<Callable<Object>> calls = IntStream.range(0, 2).<Callable<Object>>mapToObj(i -> () -> keys.register(
                new RegisterCommand(author, publicKey(TestKeyRing.newPair()), "n" + i), TestOwners.OWNER)).toList();
        List<Parallel.Outcome<Object>> outcomes = Parallel.all(calls);
        assertThat(outcomes.stream().filter(Parallel.Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded())).singleElement().satisfies(o -> assertThat(o.failure())
                .isInstanceOfSatisfying(LedgerException.class, failure -> assertThat(failure.code())
                        .isEqualTo(ConflictCode.KEY_REPLACEMENT_NOT_CONFIRMED)));
        assertThat(s.count("SELECT COUNT(*) FROM session_key WHERE author_id = ? AND revoked_at IS NULL", author))
                .isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM session_key WHERE author_id = ?", author)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-KEY-08: key ids come from the counter without gaps and the rows carry no secret: only the public key and its fingerprint")
    void key08_rows(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        keys.register(new RegisterCommand(author, publicKey(TestKeyRing.newPair()), null), TestOwners.OWNER);
        keys.revoke(author, TestOwners.OWNER);
        keys.register(new RegisterCommand(author, publicKey(TestKeyRing.newPair()), null), TestOwners.OWNER);
        List<Long> ids = keys.history(author).stream().map(SessionPublicKey::id).toList();
        assertThat(ids.get(1)).isEqualTo(ids.get(0) + 1);
        assertThat(keys.history(author)).allSatisfy(row -> {
            assertThat(row.publicKey()).hasSize(60);
            assertThat(row.fingerprint()).matches("[0-9a-f]{64}");
            assertThat(row.toString()).doesNotContain("PRIVATE");
        });
    }

    @Test
    @DisplayName("SVC-KEY-13 (audit2 S7): the retry advice wraps the transaction advice of SessionKeyService (retry outer, transaction inner), so every attempt has a transaction of its own; three registrations of the same key for the same author at the same moment all succeed with the one row")
    void key13_retryIsOutsideTheTransaction(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(keys)).isTrue();
        List<String> advice = java.util.Arrays.stream(((org.springframework.aop.framework.Advised) keys).getAdvisors())
                .map(advisor -> advisor.getAdvice().getClass().getName()).toList();
        int retry = indexContaining(advice, "Retry");
        int transaction = indexContaining(advice, "TransactionInterceptor");
        assertThat(retry).as("the retry advice is on the proxy: %s", advice).isNotNegative();
        assertThat(transaction).as("the transaction advice is on the proxy: %s", advice).isNotNegative();
        assertThat(retry).as("an advisor earlier in the list is the outer one: %s", advice).isLessThan(transaction);

        String author = newAuthor();
        String key = publicKey(TestKeyRing.newPair());
        List<Callable<Registered>> calls = IntStream.range(0, 3).<Callable<Registered>>mapToObj(i -> () -> keys.register(
                new RegisterCommand(author, key, "n" + i), TestOwners.OWNER)).toList();
        List<Parallel.Outcome<Registered>> outcomes = Parallel.all(calls);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).as("a conflict is retried, not thrown").isNull());
        assertThat(outcomes.stream().map(outcome -> outcome.value().id()).distinct()).hasSize(1);
        assertThat(s.count("SELECT COUNT(*) FROM session_key WHERE author_id = ?", author)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-KEY-17 a registration of a key that another registration of the same author made live between its two lookups (the key was not live when it looked at the fingerprint, and is the author's live key when it looks at the author) answers with that row like any repeated registration: no KEY_REPLACEMENT_NOT_CONFIRMED, no second row, no second audit row, nothing closed")
    void key17_theSameKeyBecomesLiveBetweenTheLookups(Scenario s) {
        SessionKeyService real = s.bean(SessionKeyService.class);
        String author = newAuthor();
        String key = publicKey(TestKeyRing.newPair());
        java.util.concurrent.atomic.AtomicBoolean raced = new java.util.concurrent.atomic.AtomicBoolean();
        // The state repository of the service under test: right after the lookup by fingerprint found nothing, the other
        // registration makes the same key live (the interleaving of SVC-KEY-13, here at a fixed point).
        org.pgledger.database.repository.SessionKeyStateRepository racing =
                new org.pgledger.database.repository.SessionKeyStateRepository(s.ctx().jdbc()) {
                    @Override
                    public java.util.Optional<SessionPublicKey> findLiveByFingerprint(String fingerprint) {
                        java.util.Optional<SessionPublicKey> seen = super.findLiveByFingerprint(fingerprint);
                        if (raced.compareAndSet(false, true)) {
                            real.register(new RegisterCommand(author, key, "the other registration"), TestOwners.OWNER);
                        }
                        return seen;
                    }
                };
        SessionKeyService service = new SessionKeyService(racing,
                s.bean(org.pgledger.database.repository.SessionKeyRepository.class),
                s.bean(org.pgledger.database.repository.IdAllocator.class),
                s.bean(org.pgledger.application.admin.OwnerAuditWriter.class), s.bean(java.time.Clock.class));

        Registered registered = s.ctx().tx(() -> service.register(new RegisterCommand(author, key, "this one"),
                TestOwners.OWNER));

        assertThat(raced).isTrue();
        assertThat(s.count("SELECT COUNT(*) FROM session_key WHERE author_id = ?", author)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM session_key WHERE author_id = ? AND revoked_at IS NULL", author)).isEqualTo(1);
        assertThat(registered.id()).isEqualTo(s.ctx().jdbc().sql("SELECT id FROM session_key WHERE author_id = ?").param(author)
                .query(Long.class).single());
        assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE subject = ?", author)).isEqualTo(1);
    }

    private static int indexContaining(List<String> names, String part) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(part)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("SVC-KEY-14 (audit2 S5): no key is registered for the owner role user:owner, also by replacement: VALIDATION_FAILED path author reason MUST_BE_A_SESSION_AUTHOR; nothing is stored or audited")
    void key14_theOwnerRoleHasNoKey(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String key = publicKey(TestKeyRing.newPair());
        long audited = s.count("SELECT COUNT(*) FROM owner_audit WHERE subject = ?", "user:owner");
        // the harness of the service tests may have put a row of its own in this context (see SigningAttestationVerifier)
        long rows = s.count("SELECT COUNT(*) FROM session_key WHERE author_id = ?", "user:owner");

        LedgerException failure = Expect.failure(
                () -> keys.register(new RegisterCommand("user:owner", key, null), TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
        assertThat(failure.args()).containsExactly("author");
        assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.MUST_BE_A_SESSION_AUTHOR);
        Expect.failure(() -> keys.registerReplacing(new RegisterCommand("user:owner", key, null), TestOwners.OWNER,
                "0".repeat(64)), ValidationCode.VALIDATION_FAILED);

        assertThat(s.count("SELECT COUNT(*) FROM session_key WHERE author_id = ?", "user:owner")).as("nothing was stored")
                .isEqualTo(rows);
        assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE subject = ?", "user:owner")).isEqualTo(audited);
    }

    @Test
    @DisplayName("SVC-KEY-15 (audit2 N3): revokeConfirmed closes the live key only when the fingerprint is that of the live key (any letter case): the row stays closed, KEY_REVOKED is audited with the actor; another fingerprint is VALIDATION_FAILED path fingerprint MUST_MATCH naming the live one; none or no live key is REQUIRED / NOT_FOUND; liveKeys lists the live keys")
    void key15_revokeConfirmed(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        KeyPair pair = TestKeyRing.newPair();
        String fingerprint = PublicKeys.fingerprint(pair.getPublic());
        keys.register(new RegisterCommand(author, publicKey(pair), null), TestOwners.OWNER);
        assertThat(keys.liveKeys()).extracting(row -> row.authorId().value()).contains(author);

        LedgerException wrong = Expect.failure(() -> keys.revokeConfirmed(author,
                PublicKeys.fingerprint(TestKeyRing.newPair().getPublic()), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
        assertThat(wrong.args()).containsExactly("fingerprint");
        assertThat(Expect.firstIssue(wrong).reason()).isEqualTo(ValidationReason.MUST_MATCH);
        assertThat(Expect.firstIssue(wrong).reasonArgs()).containsExactly(fingerprint);
        for (String none : new String[] {null, " "}) {
            Expect.failure(() -> keys.revokeConfirmed(author, none, TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
        }
        assertThat(keys.live(author)).as("nothing was closed").isPresent();

        keys.revokeConfirmed(author, " " + fingerprint.toUpperCase(java.util.Locale.ROOT) + " ", TestOwners.OWNER);

        assertThat(keys.live(author)).isEmpty();
        assertThat(keys.history(author)).singleElement().satisfies(row -> assertThat(row.revokedAt()).isNotNull());
        assertThat(keys.liveKeys()).extracting(row -> row.authorId().value()).doesNotContain(author);
        assertThat(s.ctx().jdbc().sql("SELECT operation || ' ' || actor || ' ' || detail FROM owner_audit WHERE subject = :a "
                + "ORDER BY id").param("a", author).query(String.class).list())
                .containsExactly("KEY_REGISTERED test:owner key=" + fingerprint, "KEY_REVOKED test:owner key=" + fingerprint);
        Expect.failure(() -> keys.revokeConfirmed(author, fingerprint, TestOwners.OWNER), LookupCode.NOT_FOUND);
        Expect.failure(() -> keys.revokeConfirmed(newAuthor(), fingerprint, TestOwners.OWNER), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-KEY-16 (audit2 S1): present reads the bare key or the four lines of the hook command: the canonical key, its fingerprint and the server of a block; a block for another author, with a fingerprint line that does not belong to its key, with a missing line or with a bad key is VALIDATION_FAILED (author / public_key); nothing is stored")
    void key16_present(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        KeyPair pair = TestKeyRing.newPair();
        String key = publicKey(pair);
        String fingerprint = PublicKeys.fingerprint(pair.getPublic());
        String block = "server: ledger\nauthor: " + author + "\npublic-key: " + key + "\nfingerprint: " + fingerprint + "\n";

        assertThat(keys.present(author, " " + key + "\n")).isEqualTo(new SessionKeyService.PresentedKey(key, fingerprint, null));
        assertThat(keys.present(author, block)).isEqualTo(new SessionKeyService.PresentedKey(key, fingerprint, "ledger"));

        LedgerException otherAuthor = Expect.failure(() -> keys.present(newAuthor(), block), ValidationCode.VALIDATION_FAILED);
        assertThat(otherAuthor.args()).containsExactly("author");
        assertThat(Expect.firstIssue(otherAuthor).reason()).isEqualTo(ValidationReason.KEY_BLOCK_AUTHOR_DIFFERS);
        assertThat(Expect.firstIssue(otherAuthor).reasonArgs()).containsExactly(author);
        LedgerException changed = Expect.failure(() -> keys.present(author, block.replace(fingerprint, "0".repeat(64))),
                ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(changed).reason()).isEqualTo(ValidationReason.KEY_BLOCK_FINGERPRINT_DIFFERS);
        LedgerException incomplete = Expect.failure(() -> keys.present(author, "author: " + author + "\npublic-key: " + key),
                ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(incomplete).reason()).isEqualTo(ValidationReason.MUST_BE_SESSION_KEY_BLOCK);
        LedgerException badKey = Expect.failure(() -> keys.present(author, block.replace(key, "AAAA")),
                ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(badKey).reason()).isEqualTo(ValidationReason.MUST_BE_ED25519_PUBLIC_KEY);
        Expect.failure(() -> keys.present(author, null), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> keys.present(author, "not a key"), ValidationCode.VALIDATION_FAILED);
        assertThat(keys.history(author)).isEmpty();
    }
}
