package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.GrantService.RevokeCommand;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.RegisterCommand;
import org.pgledger.application.admin.SessionKeyService.Registered;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.apptest.TestKeyRing;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.value.OwnerIdentity;

/**
 * SVC-GRT-07, SVC-GRT-08, SVC-CC-11, SVC-KEY-09 .. SVC-KEY-11 (security audit D8, F4): the owner operations record who
 * did them, and a live key of an author is never replaced without the explicit confirmation naming it.
 */
@LedgerServiceTest
class OwnerAuditTest {

    private static final OwnerIdentity OWNER = OwnerIdentity.of("token:owner");

    private static String newAuthor() {
        return "claude:audit" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String publicKey(KeyPair pair) {
        return PublicKeys.encode(pair.getPublic());
    }

    private static List<String> audit(Scenario s, String subject) {
        return s.ctx().jdbc().sql("SELECT operation || ' ' || actor || ' ' || COALESCE(detail, '') FROM owner_audit "
                + "WHERE subject = :subject ORDER BY id").param("subject", subject).query(String.class).list();
    }

    @Test
    @DisplayName("SVC-GRT-07: grant and revoke with an identity record one audit row each, in the transaction of the call")
    void grt07_grantAndRevokeAreAudited(Scenario s) {
        GrantService grants = s.bean(GrantService.class);
        String author = newAuthor();
        List<Long> ids = grants.grant(new GrantCommand(author, s.projectId(), List.of("defect", "task"), null, null), OWNER);

        assertThat(audit(s, author + "|" + s.projectId())).singleElement().satisfies(row -> assertThat(row)
                .startsWith("GRANT_ISSUED token:owner scopes=defect,task expires=never grants=" + ids.get(0) + ","
                        + ids.get(1)));
        assertThat(grants.revoke(new RevokeCommand(author, s.projectId(), "task"), OWNER)).isEqualTo(1);
        assertThat(grants.revoke(new RevokeCommand(author, null, null), OWNER)).isEqualTo(1);
        assertThat(audit(s, author + "|" + s.projectId())).hasSize(2).last().isEqualTo(
                "GRANT_REVOKED token:owner scope=task revoked=1");
        assertThat(audit(s, author)).singleElement().isEqualTo("GRANT_REVOKED token:owner scope=all revoked=1");
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ? AND granted_by = 'user:owner'", author))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-GRT-07: the calls without an identity record system; a refused grant leaves no audit row")
    void grt07_systemAndRefused(Scenario s) {
        GrantService grants = s.bean(GrantService.class);
        String author = newAuthor();
        grants.grant(new GrantCommand(author, s.projectId(), List.of("fix"), null, null));
        Expect.failure(() -> grants.grant(new GrantCommand(author, "nowhere", List.of("fix"), null, null), OWNER),
                LookupCode.NOT_FOUND);

        assertThat(audit(s, author + "|" + s.projectId())).singleElement().satisfies(row -> assertThat(row)
                .startsWith("GRANT_ISSUED system "));
        assertThat(audit(s, author + "|nowhere")).isEmpty();
    }

    @Test
    @DisplayName("SVC-GRT-08: an owner identity is 1 to 300 characters without a control character")
    void grt08_identity() {
        assertThat(OwnerIdentity.SYSTEM.value()).isEqualTo("system");
        assertThat(OwnerIdentity.of("x".repeat(300)).value()).hasSize(300);
        for (String bad : new String[] {null, "", "   ", "x".repeat(301), "line\nbreak", "bell\u0007"}) {
            assertThatThrownBy(() -> OwnerIdentity.of(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("SVC-CC-11: a code that is added or changed is audited with the identity; an identical set and the call "
            + "without identity behave as specified")
    void cc11_codeChangesAreAudited(Scenario s) {
        CommonCodeService codes = s.bean(CommonCodeService.class);
        String name = "AUD_" + s.code();
        String subject = "RELATION_KIND/" + name;
        CodeCommand added = new CodeCommand("RELATION_KIND", name, "감사", "Audit", null, null, 70, "ACTIVE");
        codes.set(added, OWNER);
        codes.set(added, OWNER);
        codes.set(new CodeCommand("RELATION_KIND", name, "감사", "Audit", null, null, 70, "INACTIVE"), OWNER);
        codes.set(new CodeCommand("RELATION_KIND", name, "감사", "Audit", null, null, 70, "ACTIVE"));

        assertThat(audit(s, subject)).containsExactly("CODE_SET token:owner added state=ACTIVE",
                "CODE_SET token:owner changed state=INACTIVE", "CODE_SET system changed state=ACTIVE");
    }

    @Test
    @DisplayName("SVC-KEY-09: another key for an author that has one is refused with the live key's fingerprint and time; "
            + "only the confirmation naming that fingerprint replaces it")
    void key09_noSilentReplacement(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        KeyPair first = TestKeyRing.newPair();
        KeyPair second = TestKeyRing.newPair();
        RegisterCommand firstKey = new RegisterCommand(author, publicKey(first), null);
        RegisterCommand secondKey = new RegisterCommand(author, publicKey(second), null);
        Registered live = keys.register(firstKey, OWNER);
        String registeredAt = s.ctx().clock().instant().toString();

        LedgerException refused = Expect.failure(() -> keys.register(secondKey, OWNER),
                ConflictCode.KEY_REPLACEMENT_NOT_CONFIRMED);
        assertThat(refused.args()).containsExactly(author, live.fingerprint(), registeredAt);
        for (String missing : new String[] {null, "  "}) {
            LedgerException invalid = Expect.failure(() -> keys.registerReplacing(secondKey, OWNER, missing),
                    ValidationCode.VALIDATION_FAILED);
            assertThat(invalid.args()).containsExactly("replace");
        }
        Expect.failure(() -> keys.registerReplacing(secondKey, OWNER, PublicKeys.fingerprint(second.getPublic())),
                ConflictCode.KEY_REPLACEMENT_NOT_CONFIRMED);
        assertThat(keys.history(author)).hasSize(1);
        assertThat(keys.live(author).orElseThrow().fingerprint()).isEqualTo(live.fingerprint());

        assertThat(keys.register(firstKey, OWNER)).isEqualTo(live);
        assertThat(keys.registerReplacing(firstKey, OWNER, live.fingerprint())).isEqualTo(live);
        Registered replaced = keys.registerReplacing(secondKey, OWNER, live.fingerprint().toUpperCase(Locale.ROOT));
        assertThat(keys.live(author).orElseThrow().id()).isEqualTo(replaced.id());
        assertThat(keys.history(author)).hasSize(2);
    }

    @Test
    @DisplayName("SVC-KEY-10: register, replace and revoke are audited with the identity; the repeat of the live key and the "
            + "second revoke are not")
    void key10_keyOperationsAreAudited(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String author = newAuthor();
        KeyPair first = TestKeyRing.newPair();
        KeyPair second = TestKeyRing.newPair();
        RegisterCommand firstKey = new RegisterCommand(author, publicKey(first), null);
        String firstPrint = PublicKeys.fingerprint(first.getPublic());
        String secondPrint = PublicKeys.fingerprint(second.getPublic());

        keys.register(firstKey, OWNER);
        keys.register(firstKey, OWNER);
        keys.registerReplacing(new RegisterCommand(author, publicKey(second), null), OWNER, firstPrint);
        assertThat(keys.revoke(author, OWNER)).isTrue();
        assertThat(keys.revoke(author, OWNER)).isFalse();
        keys.register(firstKey);

        assertThat(audit(s, author)).containsExactly("KEY_REGISTERED token:owner key=" + firstPrint,
                "KEY_REPLACED token:owner key=" + secondPrint + " replaced=" + firstPrint,
                "KEY_REVOKED token:owner key=" + secondPrint, "KEY_REGISTERED system key=" + firstPrint);
    }

    @Test
    @DisplayName("SVC-KEY-11: fingerprintOf gives the fingerprint the owner compares and stores nothing; a bad key is VALIDATION_FAILED")
    void key11_fingerprintOf(Scenario s) {
        SessionKeyService keys = s.bean(SessionKeyService.class);
        KeyPair pair = TestKeyRing.newPair();

        assertThat(keys.fingerprintOf(publicKey(pair))).isEqualTo(PublicKeys.fingerprint(pair.getPublic()));
        LedgerException invalid = Expect.failure(() -> keys.fingerprintOf("this is not a key"),
                ValidationCode.VALIDATION_FAILED);
        assertThat(invalid.args()).containsExactly("public_key");
        assertThat(s.count("SELECT COUNT(*) FROM session_key WHERE public_key = ?", publicKey(pair))).isZero();
    }
}
