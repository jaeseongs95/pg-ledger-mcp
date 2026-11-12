package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.pgledger.testing.TestOwners;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.GrantService.RevokeCommand;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.WriteCall;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.domain.context.ActorScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ProjectId;

/** SVC-GATE-01 .. SVC-GATE-19: the write gate, in the order the catalogue fixes. */
@LedgerServiceTest
class GateTest {

    // ------------------------------------------------------------------- 01 .. 03

    static Stream<WriteCall> everyWrite() {
        return WriteCall.all().stream();
    }

    @ParameterizedTest
    @MethodSource("everyWrite")
    @DisplayName("SVC-GATE-01: no attestation, nothing written")
    void gate01_noAttestationIsRefusedAndLeavesNothing(WriteCall call, Scenario s) {
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");
        long history = s.count("SELECT COUNT(*) FROM history");
        Expect.failure(() -> call.invoke().accept(s, null), TrustCode.HOST_ATTESTATION_REQUIRED);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"claude", "user:someone", "", "Claude:x", "claude:"})
    @DisplayName("SVC-GATE-02: a malformed author id")
    void gate02_badFormat(String actor, Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields("t"))),
                TrustCode.AUTHOR_ID_FORMAT);
    }

    @Test
    @DisplayName("SVC-GATE-03: no grant names the author, the scope and the project")
    void gate03_noGrant(Scenario s) {
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.NOGRANT,
                s.create("defect", null, s.defectFields("t"))), TrustCode.SESSION_NOT_GRANTED);
        assertThat(e.details()).containsEntry("author", List.of(Scenario.NOGRANT))
                .containsEntry("scope", List.of("defect")).containsEntry("project", List.of(s.projectId()));
    }

    // ---------------------------------------------------------------------- 04

    static Stream<Named<Consumer<Scenario>>> verificationsThroughTheAuthorChannel() {
        return Stream.of(
                Named.of("defect to VERIFIED", s -> {
                    SaveResult d = s.sourceFixedDefect("d");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("defect", d.id(), 2, "VERIFIED", Scenario.EVIDENCE));
                }),
                Named.of("condition to MET", s -> {
                    SaveResult c = s.newCondition("1", "r1", "c1");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("condition", c.id(), 1, "MET", Scenario.EVIDENCE));
                }),
                Named.of("condition to NOT_MET", s -> {
                    SaveResult c = s.newCondition("2", "r1", "c2");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("condition", c.id(), 1, "NOT_MET", Scenario.EVIDENCE));
                }),
                Named.of("out of VERIFIED", s -> {
                    SaveResult d = s.verifiedDefect("d");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("defect", d.id(), 3, "OPEN", Scenario.EVIDENCE));
                }),
                Named.of("a batch operation", s -> {
                    SaveResult d = s.sourceFixedDefect("d");
                    s.bean(BatchService.class).batch(Scenario.AUTHOR, null, List.of(new BatchOpInput.Transition(
                            "defect", d.id(), 2L, "VERIFIED", Scenario.REASON, Scenario.EVIDENCE, null, null,
                            null)));
                }));
    }

    @ParameterizedTest
    @MethodSource("verificationsThroughTheAuthorChannel")
    @DisplayName("SVC-GATE-04: a verification needs the reviewer channel even with a verify grant")
    void gate04_verificationNeedsTheReviewerChannel(Consumer<Scenario> attempt, Scenario s) {
        Expect.failure(() -> attempt.accept(s), TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
    }

    // ---------------------------------------------------------------------- 05

    @Test
    @DisplayName("SVC-GATE-05: the reviewer channel does non-verify transitions with a defect grant")
    void gate05_reviewerChannelNeedsOnlyTheScopeOfTheMove(Scenario s) {
        SaveResult d = s.newDefect("d");
        var result = s.bean(ReviewService.class).review(Scenario.DEFECT_ONLY,
                s.move("defect", d.id(), 1, "DISPUTED", Scenario.EVIDENCE));
        assertThat(result.login()).isEqualTo("ledger_reviewer");
        assertThat(s.historyRow(result.historyId()).login()).isEqualTo("ledger_reviewer");
    }

    @Test
    @DisplayName("SVC-GATE-05: a verification through the reviewer channel needs the verify grant")
    void gate05_verificationNeedsTheVerifyGrant(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.DEFECT_ONLY,
                s.move("defect", d.id(), 2, "VERIFIED", Scenario.EVIDENCE)), TrustCode.SESSION_NOT_GRANTED);
    }

    // ---------------------------------------------------------------------- 06

    /** One write and the scope it needs; {@code prepare} builds what it works on and returns the call for an actor. */
    record Op(String name, Scope scope, Function<Scenario, Function<String, Object>> prepare) {
        @Override
        public String toString() {
            return name + " needs " + scope.token();
        }
    }

    static List<Op> operations() {
        return List.of(
                new Op("save defect", Scope.DEFECT, s -> a -> s.bean(SaveService.class).save(a,
                        s.create("defect", null, s.defectFields("g")))),
                new Op("save task", Scope.TASK, s -> a -> s.bean(SaveService.class).save(a,
                        s.create("task", null, s.taskFields("g")))),
                new Op("save condition", Scope.TASK, s -> a -> s.bean(SaveService.class).save(a,
                        s.create("condition", s.conditionId("G"), s.conditionFields("r", "c")))),
                new Op("transition defect", Scope.DEFECT, s -> {
                    SaveResult d = s.newDefect("g");
                    return a -> s.bean(TransitionService.class).transition(a,
                            s.move("defect", d.id(), 1, "DISPUTED", Scenario.EVIDENCE));
                }),
                new Op("transition task", Scope.TASK, s -> {
                    SaveResult t = s.newTask("g");
                    return a -> s.bean(TransitionService.class).transition(a,
                            s.move("task", t.id(), 1, "ACTIVE", null));
                }),
                new Op("verify through review", Scope.VERIFY, s -> {
                    SaveResult d = s.sourceFixedDefect("g");
                    return a -> s.bean(ReviewService.class).review(a,
                            s.move("defect", d.id(), 2, "VERIFIED", Scenario.EVIDENCE));
                }),
                new Op("record_fix", Scope.FIX, s -> {
                    SaveResult d = s.newDefect("g");
                    return a -> s.fix(a, d.id(), Scenario.commit('b'));
                }),
                new Op("record_case", Scope.DEFECT, s -> {
                    SaveResult d = s.newDefect("g");
                    return a -> s.bean(RecordCaseService.class).recordCase(a,
                            new RecordCaseCommand(null, d.id(), "seen", Scenario.REASON, null, null, null));
                }),
                new Op("link defect_task", Scope.DEFECT, s -> {
                    SaveResult d = s.newDefect("g");
                    SaveResult t = s.newTask("g");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(null, "defect_task", d.id(), t.id(), Scenario.REASON, null));
                }),
                new Op("link relation", Scope.DEFECT, s -> {
                    SaveResult d1 = s.newDefect("g1");
                    SaveResult d2 = s.newDefect("g2");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(null, "same_site", d1.id(), d2.id(), Scenario.REASON, null));
                }),
                new Op("link condition_task", Scope.TASK, s -> {
                    SaveResult c = s.newCondition("L1", "r", "c");
                    SaveResult t = s.newTask("g");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(null, "condition_task", c.id(), t.id(), Scenario.REASON, null));
                }),
                new Op("link condition_defect", Scope.TASK, s -> {
                    SaveResult c = s.newCondition("L2", "r", "c");
                    SaveResult d = s.newDefect("g");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(null, "condition_defect", c.id(), d.id(), Scenario.REASON, null));
                }),
                new Op("correct a defect row", Scope.DEFECT, s -> {
                    SaveResult d = s.newDefect("g");
                    return a -> s.bean(CorrectService.class).correct(a,
                            new CorrectCommand(null, d.historyId(), Scenario.REASON));
                }),
                new Op("correct a fix row", Scope.FIX, s -> {
                    SaveResult d = s.newDefect("g");
                    long fixHistory = s.fix(Scenario.AUTHOR, d.id(), Scenario.commit('c')).historyId();
                    return a -> s.bean(CorrectService.class).correct(a,
                            new CorrectCommand(null, fixHistory, Scenario.REASON));
                }),
                new Op("correct a task row", Scope.TASK, s -> {
                    SaveResult t = s.newTask("g");
                    return a -> s.bean(CorrectService.class).correct(a,
                            new CorrectCommand(null, t.historyId(), Scenario.REASON));
                }),
                new Op("correct a verification row", Scope.VERIFY, s -> {
                    SaveResult d = s.verifiedDefect("g");
                    long verification = s.history(HistoryEntity.DEFECT, d.id()).stream()
                            .filter(h -> h.op() == HistoryOp.TRANSITION && "VERIFIED".equals(h.toState()))
                            .findFirst().orElseThrow().id();
                    return a -> s.bean(ReviewService.class).reviewCorrect(a,
                            new CorrectCommand(null, verification, Scenario.REASON));
                }));
    }

    @ParameterizedTest
    @MethodSource("operations")
    @DisplayName("SVC-GATE-06: an author with every scope but the needed one is refused, naming the scope")
    void gate06_wrongScopeIsRefused(Op op, Scenario s) {
        Function<String, Object> call = op.prepare().apply(s);
        String[] others = java.util.Arrays.stream(Scope.values()).filter(x -> x != op.scope()).map(Scope::token)
                .toArray(String[]::new);
        String actor = s.actorWith(others);
        LedgerException e = Expect.failure(() -> call.apply(actor), TrustCode.SESSION_NOT_GRANTED);
        assertThat(e.details()).containsEntry("scope", List.of(op.scope().token()));
    }

    @ParameterizedTest
    @MethodSource("operations")
    @DisplayName("SVC-GATE-06: an author with only the needed scope is accepted")
    void gate06_rightScopeIsAccepted(Op op, Scenario s) {
        Function<String, Object> call = op.prepare().apply(s);
        String actor = s.actorWith(op.scope().token());
        assertThat(Expect.outcome(() -> call.apply(actor))).isEmpty();
    }

    // ------------------------------------------------------------------ 07 .. 09

    @Test
    @DisplayName("SVC-GATE-07: a grant on one project does not allow writes to another")
    void gate07_grantsAreProjectScoped(Scenario s) {
        String other = s.otherProject();
        var fields = s.defectFields("t");
        fields.put("project_id", other);
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("defect", null, fields)), TrustCode.SESSION_NOT_GRANTED);
        assertThat(e.details()).containsEntry("project", List.of(other));
    }

    /** Which of the two projects ("source" = the scenario's, "target" = another) the actor holds a grant on. */
    record Grants(String name, List<String> on, ErrorCode expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Grants> movedTaskGrants() {
        return Stream.of(new Grants("only the source project", List.of("source"), TrustCode.SESSION_NOT_GRANTED),
                new Grants("only the target project", List.of("target"), TrustCode.SESSION_NOT_GRANTED),
                new Grants("both projects", List.of("source", "target"), null));
    }

    @ParameterizedTest
    @MethodSource("movedTaskGrants")
    @DisplayName("SVC-GATE-08: moving a task needs grants on the source and the target project")
    void gate08_aMoveNeedsBothProjects(Grants grants, Scenario s) {
        String other = s.otherProject();
        SaveResult t = s.newTask("t");
        String actor = "claude:mv" + Math.abs(grants.name().hashCode());
        var projects = java.util.Map.of("source", s.projectId(), "target", other);
        grants.on().forEach(label -> s.grant(actor, projects.get(label), "task"));
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(actor,
                s.edit("task", t.id(), 1, java.util.Map.of("project_id", other)))))
                .isEqualTo(Optional.ofNullable(grants.expected()));
    }

    static Stream<Grants> relationGrants() {
        return Stream.of(new Grants("only the first project", List.of("source"), TrustCode.SESSION_NOT_GRANTED),
                new Grants("both projects", List.of("source", "target"), null));
    }

    @ParameterizedTest
    @MethodSource("relationGrants")
    @DisplayName("SVC-GATE-09: a relation between defects of two projects needs the defect grant on both")
    void gate09_relationNeedsBothProjects(Grants grants, Scenario s) {
        String other = s.otherProject();
        SaveResult d1 = s.newDefect("one");
        var fields = s.defectFields("two");
        fields.put("project_id", other);
        s.grant(Scenario.AUTHOR, other, "defect");
        SaveResult d2 = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields));
        String actor = "claude:rel" + Math.abs(grants.name().hashCode());
        var projects = java.util.Map.of("source", s.projectId(), "target", other);
        grants.on().forEach(label -> s.grant(actor, projects.get(label), "defect"));
        assertThat(Expect.outcome(() -> s.bean(LinkService.class).link(actor,
                new LinkCommand(null, "same_site", d1.id(), d2.id(), Scenario.REASON, null))))
                .isEqualTo(Optional.ofNullable(grants.expected()));
    }

    // ---------------------------------------------------------------------- 10

    /** A grant that expires at 11:00:00Z, used when the clock reads {@code at}; the write is accepted or not. */
    record Expiry(String name, String expiresAt, String at, ErrorCode expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Expiry> expiries() {
        return Stream.of(
                new Expiry("one second before the expiry", "2026-11-05T11:00:00Z", "2026-11-05T10:59:59Z", null),
                new Expiry("exactly at the expiry", "2026-11-05T11:00:00Z", "2026-11-05T11:00:00Z",
                        TrustCode.SESSION_NOT_GRANTED),
                new Expiry("after the expiry", "2026-11-05T11:00:00Z", "2026-11-06T00:00:00Z",
                        TrustCode.SESSION_NOT_GRANTED),
                new Expiry("a grant that never expires", null, "2036-01-01T00:00:00Z", null));
    }

    @ParameterizedTest
    @MethodSource("expiries")
    @DisplayName("SVC-GATE-10: expiry is judged against the clock, equality is expired")
    void gate10_expiry(Expiry expiry, Scenario s) {
        String actor = "claude:exp" + Math.abs(expiry.name().hashCode());
        s.ctx().enroll(actor);
        s.bean(GrantService.class).grant(new GrantCommand(actor, s.projectId(), List.of("defect"),
                expiry.expiresAt(), null), TestOwners.OWNER);
        s.ctx().clock().set(java.time.Instant.parse(expiry.at()));
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(actor,
                s.create("defect", null, s.defectFields("t"))))).isEqualTo(Optional.ofNullable(expiry.expected()));
    }

    // ------------------------------------------------------------------ 11, 12

    @Test
    @DisplayName("SVC-GATE-11: refused while revoked, accepted after a new grant")
    void gate11_revokeAndRegrant(Scenario s) {
        String actor = s.actorWith("defect");
        s.bean(GrantService.class).revoke(new RevokeCommand(actor, s.projectId(), "defect"), TestOwners.OWNER);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields("t"))),
                TrustCode.SESSION_NOT_GRANTED);
        s.grant(actor, s.projectId(), "defect");
        assertThat(s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields("t"))).version())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-12: a revoked session cannot replay a stored receipt")
    void gate12_theGateRunsBeforeTheReceiptLookup(Scenario s) {
        String actor = s.actorWith("defect");
        SaveCommand command = new SaveCommand(Scenario.uuid(), "defect", null, 0L, Fields.of(s.defectFields("t")),
                Scenario.REASON);
        SaveResult first = s.bean(SaveService.class).save(actor, command);
        assertThat(s.bean(SaveService.class).save(actor, command)).isEqualTo(first);
        s.bean(GrantService.class).revoke(new RevokeCommand(actor, null, null), TestOwners.OWNER);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, command), TrustCode.SESSION_NOT_GRANTED);
    }

    // ------------------------------------------------------------------ 13, 19

    private SessionGrant defectGrant(Scenario s, String actor) {
        return s.bean(SessionGrantRepository.class).findByActiveKey(SessionGrant.liveKey(AuthorId.of(actor),
                ProjectId.of(s.projectId()), Scope.DEFECT)).orElseThrow();
    }

    @Test
    @DisplayName("SVC-GATE-13: a write stamps the grant, a failed write does not")
    void gate13_lastUsedAt(Scenario s) {
        String actor = s.actorWith("defect");
        assertThat(defectGrant(s, actor).lastUsedAt()).isNull();
        SaveCommand tooShort = new SaveCommand(null, "defect", null, 0L, Fields.of(s.defectFields("t")), "short");
        Expect.failure(() -> s.bean(SaveService.class).save(actor, tooShort), ValidationCode.REASON_INVALID);
        assertThat(defectGrant(s, actor).lastUsedAt()).as("rolled back with the failed write").isNull();
        s.ctx().clock().set(java.time.Instant.parse("2026-11-05T12:34:56Z"));
        s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields("t")));
        assertThat(defectGrant(s, actor).lastUsedAt()).isEqualTo(java.time.Instant.parse("2026-11-05T12:34:56Z"));
    }

    @Test
    @DisplayName("SVC-GATE-19: the grant check and the write share a transaction")
    void gate19_anExceptionAfterTheGateRollsBackTheStamp(Scenario s) {
        String actor = s.actorWith("defect");
        assertThatCode(() -> s.ctx().tx(() -> {
            s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields("t")));
            throw new IllegalStateException("after the gate");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(defectGrant(s, actor).lastUsedAt()).isNull();
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isZero();
    }

    // ------------------------------------------------------------------ 14, 16

    @Test
    @DisplayName("SVC-GATE-14: author ids are compared case-sensitively")
    void gate14_caseSensitiveAuthors(Scenario s) {
        s.grant("claude:ABC", s.projectId(), "defect");
        Expect.failure(() -> s.bean(SaveService.class).save("claude:abc", s.create("defect", null,
                s.defectFields("t"))), TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.bean(SaveService.class).save("claude:ABC", s.create("defect", null, s.defectFields("t")))
                .version()).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-16: user:owner is a valid author and still needs a grant")
    void gate16_ownerNeedsAGrantToo(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save("user:owner", s.create("defect", null,
                s.defectFields("t"))), TrustCode.SESSION_NOT_GRANTED);
        s.grant("user:owner", s.projectId(), "defect");
        assertThat(s.bean(SaveService.class).save("user:owner", s.create("defect", null, s.defectFields("t")))
                .author()).isEqualTo("user:owner");
    }

    // ---------------------------------------------------------------------- 15

    @Test
    @DisplayName("SVC-GATE-15: a save without project_id is a validation failure, an unknown id is not found")
    void gate15_theProjectMustBeDetermined(Scenario s) {
        var fields = s.defectFields("t");
        fields.remove("project_id");
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("defect", null, fields)), ValidationCode.VALIDATION_FAILED);
        assertThat(e.args()).first().isEqualTo("project_id");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("defect", s.defectId(99), 1, java.util.Map.of("title", "x"))), LookupCode.NOT_FOUND);
    }

    // ---------------------------------------------------------------------- 17

    @Test
    @DisplayName("SVC-GATE-17: no attestation beats an unknown project")
    void gate17_attestationBeforeProject(Scenario s) {
        var fields = s.defectFields("t");
        fields.put("project_id", "no-such-project");
        Expect.failure(() -> s.bean(SaveService.class).save(null, s.create("defect", null, fields)),
                TrustCode.HOST_ATTESTATION_REQUIRED);
        // a malformed id, entity or state of a transition or a correction is raised after the attestation, on both channels
        for (String malformed : List.of("no-such-id", "x", "")) {
            Expect.failure(() -> s.bean(TransitionService.class).transition(null, s.move("defect", malformed, 1, "DISPUTED",
                    Scenario.EVIDENCE)), TrustCode.HOST_ATTESTATION_REQUIRED);
            Expect.failure(() -> s.bean(ReviewService.class).review(null, s.move("task", malformed, 1, "ACTIVE", null)),
                    TrustCode.HOST_ATTESTATION_REQUIRED);
            Expect.failure(() -> s.bean(TransitionService.class).transition(null, s.move("condition", malformed, 1,
                    "MET", Scenario.EVIDENCE)), TrustCode.HOST_ATTESTATION_REQUIRED);
        }
        Expect.failure(() -> s.bean(TransitionService.class).transition(null, s.move("nothing", s.defectId(1), 1,
                "DISPUTED", Scenario.EVIDENCE)), TrustCode.HOST_ATTESTATION_REQUIRED);
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> s.bean(TransitionService.class).transition(null, s.move("defect", d.id(), 1, "NOWHERE",
                Scenario.EVIDENCE)), TrustCode.HOST_ATTESTATION_REQUIRED);
        Expect.failure(() -> s.bean(TransitionService.class).transition("claude", s.move("defect", "no-such-id", 1,
                "DISPUTED", Scenario.EVIDENCE)), TrustCode.AUTHOR_ID_FORMAT);
        Expect.failure(() -> s.bean(CorrectService.class).correct(null, new CorrectCommand(null, null,
                Scenario.REASON)), TrustCode.HOST_ATTESTATION_REQUIRED);
        Expect.failure(() -> s.bean(ReviewService.class).reviewCorrect(null, new CorrectCommand(null, null,
                Scenario.REASON)), TrustCode.HOST_ATTESTATION_REQUIRED);
        // with an attested actor the same inputs are refused for their format
        Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("defect",
                "no-such-id", 1, "DISPUTED", Scenario.EVIDENCE)), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-GATE-17: a bad format beats a missing grant")
    void gate17_formatBeforeGrant(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save("claude", s.create("defect", null,
                s.defectFields("t"))), TrustCode.AUTHOR_ID_FORMAT);
    }

    @Test
    @DisplayName("SVC-GATE-17: a missing grant beats a short reason")
    void gate17_grantBeforeReason(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.NOGRANT, new SaveCommand(null, "defect", null,
                0L, Fields.of(s.defectFields("t")), "short")), TrustCode.SESSION_NOT_GRANTED);
    }

    @Test
    @DisplayName("SVC-GATE-17: a short reason beats a stale version")
    void gate17_reasonBeforeVersion(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(null, "defect", d.id(),
                99L, Fields.of("title", "x"), "short")), ValidationCode.REASON_INVALID);
    }

    @Test
    @DisplayName("SVC-GATE-17: a stored receipt is replayed before the version is looked at")
    void gate17_receiptBeforeVersion(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveCommand edit = new SaveCommand(Scenario.uuid(), "defect", d.id(), 1L, Fields.of("title", "x"),
                Scenario.REASON);
        SaveResult first = s.bean(SaveService.class).save(Scenario.AUTHOR, edit);
        assertThat(first.version()).isEqualTo(2);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, edit)).isEqualTo(first);
        SaveCommand fresh = new SaveCommand(Scenario.uuid(), "defect", d.id(), 1L, Fields.of("title", "y"),
                Scenario.REASON);
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, fresh)))
                .contains(ConflictCode.VERSION_CONFLICT);
    }

    // ---------------------------------------------------------------------- 18

    @Test
    @DisplayName("SVC-GATE-18: the actor is bound per call, never leaks, and is unbound afterwards")
    void gate18_scopedValueActor(Scenario s) {
        List<String> actors = IntStream.range(0, 20).mapToObj(i -> "claude:par" + i).toList();
        actors.forEach(a -> s.grant(a, s.projectId(), "defect"));
        List<java.util.concurrent.Callable<SaveResult>> calls = new ArrayList<>();
        for (String actor : actors) {
            calls.add(() -> s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields(actor))));
        }
        List<Parallel.Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(outcomes.stream().map(o -> o.value().author())).containsExactlyElementsOf(actors);
        assertThat(outcomes).allSatisfy(o -> assertThat(s.defect(o.value().id()).createdBy().value())
                .isEqualTo(o.value().author()));
        assertThat(ActorScope.CURRENT.isBound()).isFalse();
    }
}
