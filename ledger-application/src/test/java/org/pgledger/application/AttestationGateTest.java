package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.gate.AttestationProperties;
import org.pgledger.application.gate.SignedAttestationVerifier;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.WhoamiService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.apptest.TestKeyRing;
import org.pgledger.domain.context.ToolScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.HistoryEntity;

/**
 * SVC-GATE-20 .. SVC-GATE-35: the signed attestation of the author at the write gate. The other service tests call the
 * services with a plain author and let {@code SigningAttestationVerifier} sign it; these tests hand the services the
 * signed text themselves, so they decide what is signed (which author, tool, request id, time, key).
 */
@LedgerServiceTest
class AttestationGateTest {

    private static final String SAVE = "ledger_save";

    /** The arguments digest of the calls of these tests unless a test binds another (see {@link TestKeyRing}). */
    private static final String DIGEST = TestKeyRing.DIRECT_DIGEST;

    /** The digest of other arguments: what a captured token meets when the call carries other content. */
    private static final String OTHER_DIGEST = org.pgledger.attestation.ArgumentsDigest.of(Map.of("other", "content"));

    /**
     * Runs the call as the tool binding of the MCP server does: with the name of the tool called and the digest of the
     * arguments that the binding computed from the raw call.
     */
    private static <T> T asTool(String tool, Supplier<T> call) {
        return asTool(tool, DIGEST, call);
    }

    private static <T> T asTool(String tool, String argumentsDigest, Supplier<T> call) {
        return ScopedValue.where(ToolScope.CURRENT, tool).where(ToolScope.ARGUMENTS_DIGEST, argumentsDigest)
                .call(call::get);
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private static SaveCommand create(Scenario s, String requestId, String title) {
        return new SaveCommand(requestId, "defect", null, 0L, s.defectFields(title), Scenario.REASON);
    }

    private static String now(Scenario s, String author, String tool, String requestId) {
        return s.ctx().keys().token(author, tool, requestId, s.ctx().clock().instant());
    }

    private static SaveResult save(Scenario s, String token, SaveCommand command) {
        return asTool(SAVE, () -> s.bean(SaveService.class).save(token, command));
    }

    private static LedgerException refused(Scenario s, String token, SaveCommand command, TrustCode code) {
        LedgerException failure = Expect.failure(() -> save(s, token, command), code);
        // nothing of the attestation may be in what the failure says
        assertThat(failure.getMessage()).doesNotContain(token);
        assertThat(failure.args()).noneMatch(arg -> arg.contains(token));
        return failure;
    }

    @Test
    @DisplayName("SVC-GATE-20: a token signed for the tool and the request id of the call, inside the window, writes as its author")
    void gate20_signedCallWrites(Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        SaveResult result = save(s, now(s, author, SAVE, requestId), create(s, requestId, "signed"));
        assertThat(result.author()).isEqualTo(author);
        assertThat(s.history(HistoryEntity.DEFECT, result.id())).hasSize(1);
    }

    @Test
    @DisplayName("SVC-GATE-21: the old format, a bare <vendor>:<session-id>, is ATTESTATION_UNSIGNED; text that is no author at all is AUTHOR_ID_FORMAT")
    void gate21_unsignedOldFormat(Scenario s) {
        SignedAttestationVerifier verifier = s.bean(SignedAttestationVerifier.class);
        String author = s.actorWith("defect");
        assertThat(Expect.failure(() -> verifier.verify(author, SAVE, "", DIGEST), TrustCode.ATTESTATION_UNSIGNED).args())
                .isEmpty();
        assertThat(Expect.failure(() -> verifier.verify("user:owner", SAVE, "", DIGEST), TrustCode.ATTESTATION_UNSIGNED)
                .args()).isEmpty();
        for (String text : List.of("", "claude", "user:someone", "claude:bad id", "PGLA2.x")) {
            Expect.failure(() -> verifier.verify(text, SAVE, "", DIGEST), TrustCode.AUTHOR_ID_FORMAT);
        }
        Expect.failure(() -> verifier.verify(null, SAVE, "", DIGEST), TrustCode.HOST_ATTESTATION_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pgla2.", "pgla2.a.b.c.d.e", "pgla2.a.b.c.d.e.f.g",
            "pgla2.!.b.c.44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a.1.AAAA",
            "pgla2.YQ.Yg.Yw.44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a.01.AAAA",
            "pgla2.YQ.Yg.Yw.44136FA355B3678A1146AD16F7E8649E94FB4FC21FE77E8310C060F61CAAFF8A.1.AAAA"})
    @DisplayName("SVC-GATE-22: text with the prefix of a token that is not a canonical token is ATTESTATION_MALFORMED")
    void gate22_malformedToken(String text, Scenario s) {
        SignedAttestationVerifier verifier = s.bean(SignedAttestationVerifier.class);
        Expect.failure(() -> verifier.verify(text, SAVE, "", DIGEST), TrustCode.ATTESTATION_MALFORMED);
    }

    @Test
    @DisplayName("SVC-GATE-40: a token of the retired version 1 (pgla1, no arguments digest) is ATTESTATION_VERSION_UNSUPPORTED for save, batch and whoami, and writes nothing")
    void gate40_versionOneIsRefused(Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        String signature = "A".repeat(86);
        String encodedAuthor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(author.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String versionOne = String.join(".", "pgla1", encodedAuthor, "bGVkZ2VyX3NhdmU", "", "1700000000", signature);
        Expect.failure(() -> save(s, versionOne, create(s, requestId, "v1")), TrustCode.ATTESTATION_VERSION_UNSUPPORTED);
        Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class).batch(versionOne, requestId,
                List.of(new BatchOpInput.Save("defect", s.defectFields("v1"), Scenario.REASON, null, null, null)))),
                TrustCode.ATTESTATION_VERSION_UNSUPPORTED);
        Expect.failure(() -> asTool("ledger_whoami", () -> s.bean(WhoamiService.class).whoami(versionOne,
                Channel.AUTHOR)), TrustCode.ATTESTATION_VERSION_UNSUPPORTED);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", requestId)).isZero();
    }

    @Test
    @DisplayName("SVC-GATE-23: a forged signature (any other key pair signing the claims of the author) is ATTESTATION_SIGNATURE_INVALID and writes nothing")
    void gate23_forgedSignature(Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        String forged = TestKeyRing.token(TestKeyRing.newPair(), author, SAVE, requestId, s.ctx().clock().instant());
        LedgerException failure = refused(s, forged, create(s, requestId, "forged"),
                TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // the author is not in the failure: a caller that cannot sign learns nothing about the author (F5)
        assertThat(failure.args()).isEmpty();
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", requestId)).isZero();
    }

    @Test
    @DisplayName("SVC-GATE-24: a signature made with the key of another registered author is ATTESTATION_SIGNATURE_INVALID")
    void gate24_signatureOfAnotherAuthor(Scenario s) {
        String victim = s.actorWith("defect");
        String attacker = s.actorWith("defect");
        String requestId = newRequestId();
        KeyPair attackerKey = s.ctx().keys().pairOf(attacker);
        String token = TestKeyRing.token(attackerKey, victim, SAVE, requestId, s.ctx().clock().instant());
        refused(s, token, create(s, requestId, "impersonation"), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // the attacker's own token still works for the attacker
        String own = newRequestId();
        assertThat(save(s, now(s, attacker, SAVE, own), create(s, own, "own")).author()).isEqualTo(attacker);
    }

    @Test
    @DisplayName("SVC-GATE-25: a token signed for another tool, or used outside any tool call, is ATTESTATION_BINDING_MISMATCH (tool)")
    void gate25_toolMismatch(Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        String forLink = now(s, author, "ledger_link", requestId);
        LedgerException failure = refused(s, forLink, create(s, requestId, "wrong tool"),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(failure.args()).containsExactly("tool");
        String forSave = now(s, author, SAVE, requestId);
        Expect.failure(() -> s.bean(SaveService.class).save(forSave, create(s, requestId, "no tool call")),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", requestId)).isZero();
    }

    @Test
    @DisplayName("SVC-GATE-26: a token signed for another request id, with none, or used with none, is ATTESTATION_BINDING_MISMATCH (request_id)")
    void gate26_requestIdMismatch(Scenario s) {
        String author = s.actorWith("defect");
        String one = newRequestId();
        String other = newRequestId();
        LedgerException failure = refused(s, now(s, author, SAVE, one), create(s, other, "other id"),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(failure.args()).containsExactly("request_id");
        refused(s, now(s, author, SAVE, ""), create(s, one, "token without id"), TrustCode.ATTESTATION_BINDING_MISMATCH);
        refused(s, now(s, author, SAVE, one), create(s, null, "call without id"), TrustCode.ATTESTATION_BINDING_MISMATCH);
        refused(s, now(s, author, SAVE, one.toUpperCase()), create(s, one, "other spelling"),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id IN (?, ?)", one, other)).isZero();
        // a call without a request id has the empty id: a token for the empty id is the token of that call
        assertThat(save(s, now(s, author, SAVE, ""), create(s, null, "empty id")).author()).isEqualTo(author);
    }

    @ParameterizedTest
    @ValueSource(longs = {301, 3600, -301, -86400})
    @DisplayName("SVC-GATE-27: an issue time more than the window (5 minutes) from the server clock, in the past or in the future, is ATTESTATION_TIME_WINDOW")
    void gate27_outsideTheWindow(long offsetSeconds, Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        Instant issued = s.ctx().clock().instant().minusSeconds(offsetSeconds);
        String token = s.ctx().keys().token(author, SAVE, requestId, issued);
        LedgerException failure = refused(s, token, create(s, requestId, "outside"), TrustCode.ATTESTATION_TIME_WINDOW);
        assertThat(failure.args()).containsExactly("300");
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", requestId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 299, 300, -299, -300})
    @DisplayName("SVC-GATE-27: an issue time exactly on the edge of the window or inside it is accepted")
    void gate27_insideTheWindow(long offsetSeconds, Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        Instant issued = s.ctx().clock().instant().minusSeconds(offsetSeconds);
        String token = s.ctx().keys().token(author, SAVE, requestId, issued);
        assertThat(save(s, token, create(s, requestId, "inside")).author()).isEqualTo(author);
    }

    @Test
    @DisplayName("SVC-GATE-27: the server clock decides: the same token is inside the window and, after the clock moved, outside")
    void gate27_theClockIsTheServers(Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        String token = now(s, author, SAVE, requestId);
        s.ctx().clock().set(s.ctx().clock().instant().plus(Duration.ofMinutes(5)));
        assertThat(save(s, token, create(s, requestId, "still inside")).author()).isEqualTo(author);
        s.ctx().clock().set(s.ctx().clock().instant().plusSeconds(1));
        Expect.failure(() -> save(s, token, create(s, requestId, "now outside")), TrustCode.ATTESTATION_TIME_WINDOW);
    }

    @Test
    @DisplayName("SVC-GATE-28: an author whose key was never registered is ATTESTATION_SIGNATURE_INVALID (F5: not told apart from a wrong signature), whatever grants exist; the message names the session-key command and not the author")
    void gate28_keyNotRegistered(Scenario s) {
        String author = "claude:nokey" + UUID.randomUUID().toString().substring(0, 8);
        s.bean(org.pgledger.application.admin.GrantService.class).grant(new org.pgledger.application.admin.GrantService
                .GrantCommand(author, s.projectId(), List.of("defect"), null, null));
        String requestId = newRequestId();
        KeyPair unregistered = TestKeyRing.newPair();
        String token = TestKeyRing.token(unregistered, author, SAVE, requestId, s.ctx().clock().instant());
        LedgerException failure = refused(s, token, create(s, requestId, "no key"),
                TrustCode.ATTESTATION_SIGNATURE_INVALID);
        assertThat(failure.args()).isEmpty();
        assertThat(Expect.message(failure)).doesNotContain(author).contains("session-key");
    }

    @Test
    @DisplayName("SVC-GATE-29: a revoked key is ATTESTATION_SIGNATURE_INVALID; after the owner registers a new key the old key still is, and the new one writes")
    void gate29_revokedKey(Scenario s) {
        String author = s.actorWith("defect");
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String before = newRequestId();
        assertThat(save(s, now(s, author, SAVE, before), create(s, before, "before")).author()).isEqualTo(author);

        assertThat(keys.revoke(author)).isTrue();
        String revoked = newRequestId();
        refused(s, now(s, author, SAVE, revoked), create(s, revoked, "revoked"), TrustCode.ATTESTATION_SIGNATURE_INVALID);

        KeyPair replacement = TestKeyRing.newPair();
        keys.register(new SessionKeyService.RegisterCommand(author,
                org.pgledger.attestation.PublicKeys.encode(replacement.getPublic()), "replaced"));
        String old = newRequestId();
        refused(s, now(s, author, SAVE, old), create(s, old, "old key"), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        String fresh = newRequestId();
        String token = TestKeyRing.token(replacement, author, SAVE, fresh, s.ctx().clock().instant());
        assertThat(save(s, token, create(s, fresh, "new key")).author()).isEqualTo(author);
    }

    @Test
    @DisplayName("SVC-GATE-30: the same signed call sent again returns the stored response; the same request id with other content is REQUEST_CONFLICT; the same token for another request id is ATTESTATION_BINDING_MISMATCH")
    void gate30_reuseOfASignature(Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        String token = now(s, author, SAVE, requestId);
        SaveResult first = save(s, token, create(s, requestId, "original"));
        assertThat(save(s, token, create(s, requestId, "original"))).isEqualTo(first);
        Expect.failure(() -> save(s, token, create(s, requestId, "different content")), ConflictCode.REQUEST_CONFLICT);
        Expect.failure(() -> save(s, token, create(s, newRequestId(), "different content")),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-31: the order of the checks: signature before project and grant, key before signature, binding and window after the signature, grant last")
    void gate31_orderOfTheChecks(Scenario s) {
        String granted = s.actorWith("defect");
        String ungranted = s.actorWithoutGrants();
        String requestId = newRequestId();
        Instant at = s.ctx().clock().instant();
        Map<String, String> fields = s.defectFields("t");
        fields.put("project_id", "no-such-project");
        SaveCommand unknownProject = new SaveCommand(requestId, "defect", null, 0L, fields, Scenario.REASON);

        // a forged signature wins over an unknown project and over a missing grant
        String forged = TestKeyRing.token(TestKeyRing.newPair(), ungranted, SAVE, requestId, at);
        Expect.failure(() -> save(s, forged, unknownProject), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // an unregistered key wins over everything after it
        String stranger = "claude:order" + UUID.randomUUID().toString().substring(0, 8);
        String strangerToken = TestKeyRing.token(TestKeyRing.newPair(), stranger, "ledger_link", requestId,
                at.minusSeconds(86400));
        Expect.failure(() -> save(s, strangerToken, unknownProject), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // a valid signature with the wrong tool and a stale time: the binding comes before the window
        String wrongBoth = s.ctx().keys().token(granted, "ledger_link", requestId, at.minusSeconds(86400));
        Expect.failure(() -> save(s, wrongBoth, unknownProject), TrustCode.ATTESTATION_BINDING_MISMATCH);
        // the arguments digest is part of the binding: it comes after the tool and the request id, before the window
        String wrongDigest = s.ctx().keys().token(granted, SAVE, requestId, OTHER_DIGEST, at.minusSeconds(86400));
        Expect.failure(() -> save(s, wrongDigest, unknownProject), TrustCode.ATTESTATION_BINDING_MISMATCH);
        // a valid binding with a stale time: the window comes before the project
        String stale = s.ctx().keys().token(granted, SAVE, requestId, at.minusSeconds(86400));
        Expect.failure(() -> save(s, stale, unknownProject), TrustCode.ATTESTATION_TIME_WINDOW);
        // everything about the attestation is right: now the project is looked at, then the grant
        String valid = s.ctx().keys().token(granted, SAVE, requestId, at);
        Expect.failure(() -> save(s, valid, unknownProject), LookupCode.NOT_FOUND);
        String noGrant = s.ctx().keys().token(ungranted, SAVE, requestId, at);
        Expect.failure(() -> save(s, noGrant, create(s, requestId, "no grant")), TrustCode.SESSION_NOT_GRANTED);
        // verification through the author channel is refused before the attestation is looked at
        TransitionCommand verify = s.move("defect", s.newDefect("to verify").id(), 1, "VERIFIED", Scenario.EVIDENCE);
        Expect.failure(() -> asTool("ledger_transition", () -> s.bean(org.pgledger.application.write.TransitionService.class)
                .transition("garbage", verify)), TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
    }

    @Test
    @DisplayName("SVC-GATE-32: a batch is attested once, with the request id of the batch; its operations keep request ids of their own")
    void gate32_batch(Scenario s) {
        String author = s.actorWith("defect");
        String batchId = newRequestId();
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.Save("defect", s.defectFields("one"), Scenario.REASON, null, null, null),
                new BatchOpInput.Save("defect", s.defectFields("two"), Scenario.REASON, null, null, newRequestId()));
        assertThat(asTool("ledger_batch", () -> s.bean(BatchService.class).batch(now(s, author, "ledger_batch", batchId),
                batchId, ops)).results()).hasSize(2);

        String other = newRequestId();
        LedgerException failure = Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class)
                .batch(now(s, author, "ledger_batch", other), batchId, ops)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(failure.details()).containsEntry("op_index", List.of("1"));
        Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class)
                .batch(now(s, author, SAVE, batchId), batchId, ops)), TrustCode.ATTESTATION_BINDING_MISMATCH);
    }

    @Test
    @DisplayName("SVC-GATE-33: whoami is attested with the empty request id and its own tool; the reviewer channel is the tool ledger_review")
    void gate33_whoamiAndReview(Scenario s) {
        String author = s.actorWith("defect");
        WhoamiService whoami = s.bean(WhoamiService.class);
        assertThat(asTool("ledger_whoami", () -> whoami.whoami(now(s, author, "ledger_whoami", ""), Channel.AUTHOR))
                .grants()).hasSize(1);
        assertThat(asTool("ledger_whoami", () -> whoami.whoami(now(s, author, "ledger_whoami", ""), Channel.AUTHOR))
                .author()).isEqualTo(author);
        Expect.failure(() -> asTool("ledger_whoami", () -> whoami.whoami(now(s, author, "ledger_whoami",
                newRequestId()), Channel.AUTHOR)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        Expect.failure(() -> asTool("ledger_save", () -> whoami.whoami(now(s, author, "ledger_whoami", ""),
                Channel.AUTHOR)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        Expect.failure(() -> whoami.whoami(now(s, author, "ledger_whoami", ""), Channel.AUTHOR),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        Expect.failure(() -> asTool("ledger_whoami", () -> whoami.whoami(TestKeyRing.token(TestKeyRing.newPair(),
                author, "ledger_whoami", "", s.ctx().clock().instant()), Channel.AUTHOR)),
                TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // a value without a valid author is still answered, not refused (as before signatures)
        assertThat(asTool("ledger_whoami", () -> whoami.whoami("claude", Channel.AUTHOR)).authorFormatOk()).isFalse();
        Expect.failure(() -> asTool("ledger_whoami", () -> whoami.whoami(null, Channel.AUTHOR)),
                TrustCode.HOST_ATTESTATION_REQUIRED);

        String reviewer = s.actorWith("defect");
        String requestId = newRequestId();
        SaveResult defect = s.newDefect("to review");
        TransitionCommand move = new TransitionCommand(requestId, "defect", defect.id(), 1L, "DISPUTED", Scenario.REASON,
                Scenario.EVIDENCE, null, null);
        Expect.failure(() -> asTool("ledger_transition", () -> s.bean(ReviewService.class)
                .review(now(s, reviewer, "ledger_review", requestId), move)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(asTool("ledger_review", () -> s.bean(ReviewService.class)
                .review(now(s, reviewer, "ledger_review", requestId), move)).author()).isEqualTo(reviewer);
    }

    @Test
    @DisplayName("SVC-GATE-34: the window is a Duration setting, five minutes by default, of at least one second")
    void gate34_windowSetting(Scenario s) {
        assertThat(s.bean(AttestationProperties.class).window()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new AttestationProperties(Duration.ofSeconds(1)).window()).isEqualTo(Duration.ofSeconds(1));
        for (Duration bad : List.of(Duration.ZERO, Duration.ofSeconds(-5), Duration.ofMillis(999))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AttestationProperties(bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ledger.attestation.window");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AttestationProperties(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SVC-GATE-36: the token of a batch is bound to the digest of the arguments of the whole batch: another digest is ATTESTATION_BINDING_MISMATCH (arguments) at operation 1 and writes nothing")
    void gate36_batchTokenIsBoundToTheArguments(Scenario s) {
        String author = s.actorWith("defect");
        String batchId = newRequestId();
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.Save("defect", s.defectFields("one"), Scenario.REASON, null, null, null));
        String token = s.ctx().keys().token(author, "ledger_batch", batchId, OTHER_DIGEST, s.ctx().clock().instant());

        LedgerException failure = Expect.failure(() -> asTool("ledger_batch", DIGEST,
                () -> s.bean(BatchService.class).batch(token, batchId, ops)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(failure.args()).containsExactly("arguments");
        assertThat(failure.details()).containsEntry("op_index", List.of("1")).containsEntry("field",
                List.of("arguments"));
        // a call outside a tool call has no digest at all
        Expect.failure(() -> s.bean(BatchService.class).batch(token, batchId, ops),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", batchId)).isZero();

        assertThat(asTool("ledger_batch", OTHER_DIGEST, () -> s.bean(BatchService.class).batch(token, batchId, ops))
                .results()).hasSize(1);
        assertThat(asTool("ledger_batch", OTHER_DIGEST, () -> s.bean(BatchService.class).batch(token, batchId, ops))
                .results()).hasSize(1);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-37: the token of a save cannot be used for other arguments, before or after the legitimate call; the legitimate call is not blocked")
    void gate37_saveTokenIsBoundToTheArguments(Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        String token = s.ctx().keys().token(author, SAVE, requestId, OTHER_DIGEST, s.ctx().clock().instant());

        LedgerException raced = Expect.failure(() -> asTool(SAVE, DIGEST,
                () -> s.bean(SaveService.class).save(token, create(s, requestId, "attacker content"))),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(raced.args()).containsExactly("arguments");
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", requestId)).isZero();

        SaveResult written = asTool(SAVE, OTHER_DIGEST,
                () -> s.bean(SaveService.class).save(token, create(s, requestId, "signed content")));
        assertThat(written.author()).isEqualTo(author);
        Expect.failure(() -> asTool(SAVE, DIGEST,
                () -> s.bean(SaveService.class).save(token, create(s, requestId, "attacker content"))),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-38: after the legitimate call failed (its receipt rolled back) the captured token is still bound to its arguments")
    void gate38_tokenAfterAFailedCall(Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        String token = s.ctx().keys().token(author, SAVE, requestId, OTHER_DIGEST, s.ctx().clock().instant());
        Map<String, String> fields = s.defectFields("fails");
        fields.put("project_id", "no-such-project");
        Expect.failure(() -> asTool(SAVE, OTHER_DIGEST, () -> s.bean(SaveService.class).save(token,
                new SaveCommand(requestId, "defect", null, 0L, fields, Scenario.REASON))), LookupCode.NOT_FOUND);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", requestId)).isZero();

        Expect.failure(() -> asTool(SAVE, DIGEST, () -> s.bean(SaveService.class).save(token,
                create(s, requestId, "captured token, other content"))), TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isZero();
    }

    /** The token text with the 64 signature bytes replaced by zeros: well formed, never valid. */
    private static String withZeroSignature(String token) {
        return token.substring(0, token.lastIndexOf('.') + 1) + "A".repeat(86);
    }

    @Test
    @DisplayName("SVC-GATE-39: an author with a key and an author without one cannot be told apart by a caller that cannot sign: one code, no argument, no author, the same message")
    void gate39_noOracleForRegisteredKeys(Scenario s) {
        String keyed = s.actorWith("defect");
        String keyless = "claude:nokey" + UUID.randomUUID().toString().substring(0, 8);
        s.bean(org.pgledger.application.admin.GrantService.class).grant(new org.pgledger.application.admin.GrantService
                .GrantCommand(keyless, s.projectId(), List.of("defect"), null, null));
        Instant at = s.ctx().clock().instant();
        List<LedgerException> failures = new java.util.ArrayList<>();
        for (String author : List.of(keyed, keyless)) {
            String requestId = newRequestId();
            String zero = withZeroSignature(TestKeyRing.token(TestKeyRing.newPair(), author, SAVE, requestId, at));
            failures.add(Expect.failure(() -> save(s, zero, create(s, requestId, "oracle")),
                    TrustCode.ATTESTATION_SIGNATURE_INVALID));
            String foreign = TestKeyRing.token(TestKeyRing.newPair(), author, SAVE, requestId, at);
            failures.add(Expect.failure(() -> save(s, foreign, create(s, requestId, "oracle")),
                    TrustCode.ATTESTATION_SIGNATURE_INVALID));
            failures.add(Expect.failure(() -> asTool("ledger_whoami", () -> s.bean(WhoamiService.class).whoami(
                    TestKeyRing.token(TestKeyRing.newPair(), author, "ledger_whoami", "", at), Channel.AUTHOR)),
                    TrustCode.ATTESTATION_SIGNATURE_INVALID));
        }
        for (LedgerException failure : failures) {
            assertThat(failure.args()).isEmpty();
            assertThat(failure.details()).isEmpty();
            assertThat(Expect.message(failure)).doesNotContain(keyed).doesNotContain(keyless)
                    .isEqualTo(Expect.message(failures.getFirst()));
        }
    }

    @Test
    @DisplayName("SVC-GATE-35: no failure of the attestation carries the token, its signature or a key in its message, arguments or details")
    void gate35_noSecretInFailures(Scenario s) {
        String author = s.actorWith("defect");
        String requestId = newRequestId();
        String stale = s.ctx().keys().token(author, SAVE, requestId, s.ctx().clock().instant().minusSeconds(86400));
        String forged = TestKeyRing.token(TestKeyRing.newPair(), author, SAVE, requestId, s.ctx().clock().instant());
        String privateKeyText = java.util.Base64.getEncoder()
                .encodeToString(s.ctx().keys().pairOf(author).getPrivate().getEncoded());
        record Case(String token, TrustCode code) {
        }
        for (Case failing : List.of(new Case(stale, TrustCode.ATTESTATION_TIME_WINDOW),
                new Case(forged, TrustCode.ATTESTATION_SIGNATURE_INVALID))) {
            LedgerException failure = Expect.failure(() -> save(s, failing.token(), create(s, requestId, "secret")),
                    failing.code());
            String signature = failing.token().substring(failing.token().lastIndexOf('.') + 1);
            assertThat(String.valueOf(failure) + failure.args() + failure.details() + Expect.message(failure))
                    .doesNotContain(failing.token()).doesNotContain(signature).doesNotContain(privateKeyText);
        }
    }
}
