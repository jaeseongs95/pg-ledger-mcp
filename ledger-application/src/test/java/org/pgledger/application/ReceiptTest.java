package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchResult;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.apptest.LedgerTestRunners;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.ReceiptOrigin;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;

/** SVC-RCP-01 .. SVC-RCP-08, -12 .. -15: idempotency of the write services. */
@LedgerServiceTest
class ReceiptTest {

    private static SaveCommand create(Scenario s, String requestId, String title) {
        return new SaveCommand(requestId, "defect", null, 0L, s.defectFields(title), Scenario.REASON);
    }

    private static long receipts(Scenario s, String requestId) {
        return s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", requestId);
    }

    private static long defects(Scenario s) {
        return s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId());
    }

    @Test
    @DisplayName("SVC-RCP-01: the same request again returns the stored response and writes nothing")
    void rcp01_replay(Scenario s) {
        String id = Scenario.uuid();
        SaveResult first = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "d"));
        long history = s.count("SELECT COUNT(*) FROM history");
        SaveResult second = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "d"));
        assertThat(second).isEqualTo(first);
        assertThat(second.historyId()).isEqualTo(first.historyId());
        assertThat(defects(s)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(receipts(s, id)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------- 02

    static Stream<Named<BiFunction<Scenario, SaveCommand, SaveCommand>>> changedSaves() {
        return Stream.of(
                Named.of("another reason", (s, c) -> new SaveCommand(c.requestId(), c.entity(), c.id(),
                        c.expectedVersion(), c.fields(), "another reason, long enough")),
                Named.of("another id", (s, c) -> new SaveCommand(c.requestId(), c.entity(), s.defectId(2),
                        c.expectedVersion(), c.fields(), c.reason())),
                Named.of("another expected version", (s, c) -> new SaveCommand(c.requestId(), c.entity(), c.id(), 5L,
                        c.fields(), c.reason())),
                Named.of("another field value", (s, c) -> new SaveCommand(c.requestId(), c.entity(), c.id(),
                        c.expectedVersion(), Map.of("title", "other title"), c.reason())));
    }

    @ParameterizedTest
    @MethodSource("changedSaves")
    @DisplayName("SVC-RCP-02: the same request id with another payload is a conflict")
    void rcp02_conflict(BiFunction<Scenario, SaveCommand, SaveCommand> change, Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        String id = Scenario.uuid();
        SaveCommand base = new SaveCommand(id, "defect", s.defectId(1), 1L, Map.of("title", "edited"),
                Scenario.REASON);
        s.bean(SaveService.class).save(Scenario.AUTHOR, base);
        long history = s.count("SELECT COUNT(*) FROM history");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, change.apply(s, base)),
                ConflictCode.REQUEST_CONFLICT);
        assertThat(s.defect(s.defectId(1)).title()).isEqualTo("edited");
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
    }

    @Test
    @DisplayName("SVC-RCP-03: the same request id for another operation is a conflict")
    void rcp03_anotherOperation(Scenario s) {
        String id = Scenario.uuid();
        SaveResult d = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "d"));
        Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(id,
                "defect", d.id(), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, null, null)),
                ConflictCode.REQUEST_CONFLICT);
    }

    @Test
    @DisplayName("SVC-RCP-04: the same request id from another author is a conflict")
    void rcp04_anotherAuthor(Scenario s) {
        String id = Scenario.uuid();
        SaveResult d = s.newDefect("d");
        TransitionCommand dispute = new TransitionCommand(id, "defect", d.id(), 1L, "DISPUTED", Scenario.REASON,
                Scenario.EVIDENCE, null, null);
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, dispute);
        Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.DEFECT_ONLY, dispute),
                ConflictCode.REQUEST_CONFLICT);
    }

    @Test
    @DisplayName("SVC-RCP-04: the same request id through the other channel is a conflict")
    void rcp04_anotherChannel(Scenario s) {
        String id = Scenario.uuid();
        SaveResult d = s.newDefect("d");
        TransitionCommand dispute = new TransitionCommand(id, "defect", d.id(), 1L, "DISPUTED", Scenario.REASON,
                Scenario.EVIDENCE, null, null);
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, dispute);
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.AUTHOR, dispute),
                ConflictCode.REQUEST_CONFLICT);
    }

    // ---------------------------------------------------------------------- 05

    static Stream<Named<UnaryOperator<SaveCommand>>> samePayloadSpelledDifferently() {
        return Stream.of(
                Named.of("key order changed", c -> {
                    List<String> keys = new java.util.ArrayList<>(c.fields().keySet());
                    java.util.Collections.reverse(keys);
                    Map<String, String> reversed = new LinkedHashMap<>();
                    keys.forEach(k -> reversed.put(k, c.fields().get(k)));
                    return new SaveCommand(c.requestId(), c.entity(), c.id(), c.expectedVersion(), reversed,
                            c.reason());
                }),
                Named.of("reason padded with spaces", c -> new SaveCommand(c.requestId(), c.entity(), c.id(),
                        c.expectedVersion(), c.fields(), "   " + c.reason() + "   ")),
                Named.of("expected version absent instead of 0", c -> new SaveCommand(c.requestId(), c.entity(),
                        c.id(), null, c.fields(), c.reason())),
                Named.of("blank id instead of absent", c -> new SaveCommand(c.requestId(), c.entity(), "  ",
                        c.expectedVersion(), c.fields(), c.reason())),
                Named.of("an optional field blank instead of absent", c -> {
                    Map<String, String> fields = new LinkedHashMap<>(c.fields());
                    fields.put("owner_actor_id", "   ");
                    return new SaveCommand(c.requestId(), c.entity(), c.id(), c.expectedVersion(), fields,
                            c.reason());
                }),
                Named.of("an optional field null instead of absent", c -> {
                    Map<String, String> fields = new LinkedHashMap<>(c.fields());
                    fields.put("owner_actor_id", null);
                    return new SaveCommand(c.requestId(), c.entity(), c.id(), c.expectedVersion(), fields,
                            c.reason());
                }));
    }

    @ParameterizedTest
    @MethodSource("samePayloadSpelledDifferently")
    @DisplayName("SVC-RCP-05 / SVC-NORM-04: the same payload spelled differently is a replay")
    void rcp05_normalisedDigest(UnaryOperator<SaveCommand> spell, Scenario s) {
        String id = Scenario.uuid();
        SaveResult first = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "d"));
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, spell.apply(create(s, id, "d")))).isEqualTo(first);
        assertThat(defects(s)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------- 06

    @Test
    @DisplayName("SVC-RCP-06: a failed version check leaves no receipt, the corrected retry works")
    void rcp06_versionConflict(Scenario s) {
        SaveResult d = s.newDefect("d");
        String id = Scenario.uuid();
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(id, "defect", d.id(), 9L,
                Map.of("title", "x"), Scenario.REASON)), ConflictCode.VERSION_CONFLICT);
        assertThat(receipts(s, id)).isZero();
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(id, "defect", d.id(), 1L,
                Map.of("title", "x"), Scenario.REASON)).version()).isEqualTo(2);
        assertThat(receipts(s, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-06: a not-found failure leaves no receipt, the corrected retry works")
    void rcp06_notFound(Scenario s) {
        SaveResult d = s.newDefect("d");
        String id = Scenario.uuid();
        Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(id,
                "defect", s.defectId(77), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, null, null)),
                LookupCode.NOT_FOUND);
        assertThat(receipts(s, id)).isZero();
        assertThat(s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(id, "defect",
                d.id(), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, null, null)).to()).isEqualTo("DISPUTED");
    }

    @Test
    @DisplayName("SVC-RCP-06: a grant failure leaves no receipt, the retry after the grant works")
    void rcp06_grant(Scenario s) {
        String id = Scenario.uuid();
        String actor = "claude:late-grant";
        Expect.failure(() -> s.bean(SaveService.class).save(actor, create(s, id, "d")),
                TrustCode.SESSION_NOT_GRANTED);
        assertThat(receipts(s, id)).isZero();
        s.grant(actor, s.projectId(), "defect");
        assertThat(s.bean(SaveService.class).save(actor, create(s, id, "d")).version()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------- 07

    @Test
    @DisplayName("SVC-RCP-07: a transition is replayed after the version moved on")
    void rcp07_transition(Scenario s) {
        SaveResult d = s.newDefect("d");
        TransitionCommand dispute = new TransitionCommand(Scenario.uuid(), "defect", d.id(), 1L, "DISPUTED",
                Scenario.REASON, Scenario.EVIDENCE, null, null);
        TransitionResult first = s.bean(TransitionService.class).transition(Scenario.AUTHOR, dispute);
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(null, "defect", d.id(), 2L,
                "OPEN", Scenario.REASON, Scenario.EVIDENCE, null, null));
        assertThat(s.bean(TransitionService.class).transition(Scenario.AUTHOR, dispute)).isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-RCP-07: a case is replayed after its defect was merged")
    void rcp07_caseAfterMerge(Scenario s) {
        SaveResult source = s.newDefect("source");
        SaveResult target = s.newDefect("target");
        RecordCaseCommand observed = new RecordCaseCommand(Scenario.uuid(), source.id(), "seen", Scenario.REASON,
                null, null, null);
        var first = s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, observed);
        MergeFixture.merge(s, source.id(), 1, target.id());
        assertThat(s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, observed)).isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-RCP-07: a fix is replayed after the defect was verified")
    void rcp07_fix(Scenario s) {
        SaveResult d = s.newDefect("d");
        RecordFixCommand fix = new RecordFixCommand(Scenario.uuid(), d.id(), "repo/e2e", Scenario.commit('a'),
                Scenario.REASON, null, null, null, null, null);
        var first = s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, fix);
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("defect", d.id(), 2, "VERIFIED",
                Scenario.EVIDENCE));
        assertThat(s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, fix)).isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-RCP-07: a link is replayed after it was removed")
    void rcp07_link(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult t = s.newTask("t");
        LinkCommand link = new LinkCommand(Scenario.uuid(), "defect_task", d.id(), t.id(), Scenario.REASON, null);
        var first = s.bean(LinkService.class).link(Scenario.AUTHOR, link);
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task", d.id(), t.id(),
                Scenario.REASON, true));
        assertThat(s.bean(LinkService.class).link(Scenario.AUTHOR, link)).isEqualTo(first);
    }

    // ---------------------------------------------------------------------- 08

    @Test
    @DisplayName("SVC-RCP-08: without a request id two identical calls are two writes")
    void rcp08_blindRetry(Scenario s) {
        long before = s.count("SELECT COUNT(*) FROM request_receipt");
        SaveResult one = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, null, "d"));
        SaveResult two = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, null, "d"));
        assertThat(two.id()).isNotEqualTo(one.id());
        assertThat(defects(s)).isEqualTo(2);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(before + 2);
    }

    // ---------------------------------------------------------------------- 12

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"not-a-uuid", "12345", "0123456789012345678901234567890123456"})
    @DisplayName("SVC-RCP-12: a request id that is not a UUID")
    void rcp12_requestIdFormat(String requestId, Scenario s) {
        var failure = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                create(s, requestId, "d")), ValidationCode.VALIDATION_FAILED);
        assertThat(failure.args()).first().isEqualTo("request_id");
    }

    // ---------------------------------------------------------------------- 13

    @Test
    @DisplayName("SVC-RCP-13: the stored response survives a restart of the application")
    void rcp13_restart(Scenario s) {
        String id = Scenario.uuid();
        SaveResult first = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "d"));
        LedgerTestRunners.restartOf(s.ctx(), restarted -> assertThat(restarted.getBean(SaveService.class).save(
                Scenario.AUTHOR, create(s, id, "d"))).isEqualTo(first));
        assertThat(defects(s)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------- 15

    private void legacyReceipt(Scenario s, String id) {
        s.bean(org.springframework.transaction.support.TransactionTemplate.class).executeWithoutResult(
                status -> s.bean(ReceiptRepository.class).insert(new Receipt(RequestId.parse(id),
                        ReceiptOperation.SAVE, Sha256.ofText("legacy " + id), AuthorId.of(Scenario.AUTHOR),
                        Channel.AUTHOR.login(), JsonDocument.of("{\"status\":\"COMMITTED\"}"),
                        ReceiptOrigin.LEGACY, Instant.parse("2025-01-01T00:00:00Z"))));
    }

    @Test
    @DisplayName("SVC-RCP-15: a legacy request id is refused, even with a payload that hashes the same")
    void rcp15_legacyWithTheSamePayload(Scenario s) {
        String id = Scenario.uuid();
        s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "d"));
        s.ctx().jdbc().sql("UPDATE request_receipt SET origin = 'LEGACY' WHERE request_id = ?").param(id).update();
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "d")),
                ConflictCode.REQUEST_ID_LEGACY);
        assertThat(defects(s)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-15: a legacy request id is refused for another payload, and a new id works")
    void rcp15_legacyWithAnotherPayload(Scenario s) {
        String id = Scenario.uuid();
        legacyReceipt(s, id);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "another")),
                ConflictCode.REQUEST_ID_LEGACY);
        assertThat(defects(s)).isZero();
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, Scenario.uuid(), "another")).version())
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------- 14

    @Test
    @DisplayName("SVC-RCP-14: a batch replay returns the stored results, and every operation replays on its own")
    void rcp14_batch(Scenario s) {
        String id = Scenario.uuid();
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.Save("defect", s.defectFields("one"), Scenario.REASON, null, null, null),
                new BatchOpInput.Save("task", s.taskFields("two"), Scenario.REASON, null, null, null));
        BatchResult first = s.bean(BatchService.class).batch(Scenario.AUTHOR, id, ops);
        assertThat(s.bean(BatchService.class).batch(Scenario.AUTHOR, id, ops)).isEqualTo(first);
        RequestId derived = RequestId.parse(id).derive(1);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(derived.toString(), "task", null,
                0L, s.taskFields("two"), Scenario.REASON))).isEqualTo(first.results().get(1));
        assertThat(defects(s)).isEqualTo(1);
    }
}
