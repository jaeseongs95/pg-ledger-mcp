// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.veridger.testing.TestOwners;

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
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.GrantCommand;
import org.veridger.application.admin.GrantService.RevokeCommand;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Fields;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Parallel;
import org.veridger.application.support.Scenario;
import org.veridger.application.support.WriteCall;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.CorrectCommand;
import org.veridger.application.write.CorrectService;
import org.veridger.application.write.LinkCommand;
import org.veridger.application.write.LinkService;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.SaveCommand;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.database.repository.SessionGrantRepository;
import org.veridger.domain.context.ActorScope;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ErrorCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.model.SessionGrant;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.ProjectId;

/** SVC-GATE-01 .. SVC-GATE-19, 43 .. 47: the write gate, in the order the catalogue fixes. */
@LedgerServiceTest
class GateTest {

    static Stream<WriteCall> everyWrite() {
        return WriteCall.all().stream();
    }

    @ParameterizedTest
    @MethodSource("everyWrite")
    @DisplayName("SVC-GATE-01: no or blank attestation, nothing written")
    void gate01_noAttestationIsRefusedAndLeavesNothing(WriteCall call, Scenario s) {
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");
        long history = s.count("SELECT COUNT(*) FROM history");
        Expect.failure(() -> call.invoke().accept(s, null), TrustCode.HOST_ATTESTATION_REQUIRED);
        Expect.failure(() -> call.invoke().accept(s, ""), TrustCode.HOST_ATTESTATION_REQUIRED);
        Expect.failure(() -> call.invoke().accept(s, "   "), TrustCode.HOST_ATTESTATION_REQUIRED);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"claude", "user:someone", "claude:bad id", "Claude:x", "claude:"})
    @DisplayName("SVC-GATE-02: a malformed author id")
    void gate02_badFormat(String actor, Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("t"))),
                TrustCode.AUTHOR_ID_FORMAT);
    }

    @Test
    @DisplayName("SVC-GATE-03: no grant names the author, the scope and the project")
    void gate03_noGrant(Scenario s) {
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.NOGRANT,
                s.create("issue", null, s.issueFields("t"))), TrustCode.SESSION_NOT_GRANTED);
        assertThat(e.details()).containsEntry("author", List.of(Scenario.NOGRANT))
                .containsEntry("scope", List.of("issue_create")).containsEntry("project", List.of(s.projectId()));
    }

    static Stream<Named<Consumer<Scenario>>> verificationsThroughTheAuthorChannel() {
        return Stream.of(
                Named.of("issue to VERIFIED", s -> {
                    SaveResult i = s.resolvedIssue("i");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("issue", i.id(), 2, "VERIFIED", Scenario.EVIDENCE));
                }),
                Named.of("spec to VERIFIED", s -> {
                    SaveResult sp = s.newSpec("sp");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("spec", sp.id(), 1, "COMPLETED", Scenario.EVIDENCE));
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("spec", sp.id(), 2, "VERIFIED", Scenario.EVIDENCE));
                }),
                Named.of("criterion to MET", s -> {
                    SaveResult c = s.newCriterion("1", "r1", "c1");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("criterion", c.id(), 1, "MET", Scenario.EVIDENCE));
                }),
                Named.of("criterion to NOT_MET", s -> {
                    SaveResult c = s.newCriterion("2", "r1", "c2");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("criterion", c.id(), 1, "NOT_MET", Scenario.EVIDENCE));
                }),
                Named.of("out of VERIFIED", s -> {
                    SaveResult i = s.verifiedIssue("i");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            s.move("issue", i.id(), 3, "OPEN", Scenario.EVIDENCE));
                }),
                Named.of("a batch operation", s -> {
                    SaveResult i = s.resolvedIssue("i");
                    s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(new BatchOpInput.Transition(
                            "issue", i.id(), 2L, "VERIFIED", Scenario.REASON, Scenario.EVIDENCE, null, null,
                            null)));
                }));
    }

    @ParameterizedTest
    @MethodSource("verificationsThroughTheAuthorChannel")
    @DisplayName("SVC-GATE-04: a verification needs the reviewer channel even with a verify grant")
    void gate04_verificationNeedsTheReviewerChannel(Consumer<Scenario> attempt, Scenario s) {
        Expect.failure(() -> attempt.accept(s), TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
    }

    @Test
    @DisplayName("SVC-GATE-05: the reviewer channel does non-verify transitions with an issue grant")
    void gate05_reviewerChannelNeedsOnlyTheScopeOfTheMove(Scenario s) {
        SaveResult i = s.newIssue("i");
        var result = s.bean(ReviewService.class).review(Scenario.ISSUE_ONLY,
                s.move("issue", i.id(), 1, "DISPUTED", Scenario.EVIDENCE));
        assertThat(result.login()).isEqualTo("ledger_reviewer");
        assertThat(s.historyRow(result.historyId()).login()).isEqualTo("ledger_reviewer");
    }

    @Test
    @DisplayName("SVC-GATE-05: a verification through the reviewer channel needs the verify grant")
    void gate05_verificationNeedsTheVerifyGrant(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.ISSUE_ONLY,
                s.move("issue", i.id(), 2, "VERIFIED", Scenario.EVIDENCE)), TrustCode.SESSION_NOT_GRANTED);
    }

    /** One write and the scope it needs; {@code prepare} builds what it works on and returns the call for an actor. */
    record Op(String name, Scope scope, Function<Scenario, Function<String, Object>> prepare) {
        @Override
        public String toString() {
            return name + " needs " + scope.token();
        }
    }

    static List<Op> operations() {
        return List.of(
                new Op("save issue", Scope.ISSUE_CREATE, s -> a -> s.bean(SaveService.class).save(a,
                        s.create("issue", null, s.issueFields("g")))),
                new Op("save spec", Scope.SPEC_CREATE, s -> a -> s.bean(SaveService.class).save(a,
                        s.create("spec", null, s.specFields("g")))),
                new Op("save task", Scope.TASK_CREATE, s -> a -> s.bean(SaveService.class).save(a,
                        s.create("task", null, s.taskFields("g")))),
                new Op("save criterion", Scope.CRITERION_CREATE, s -> a -> s.bean(SaveService.class).save(a,
                        s.create("criterion", s.criterionId("G"), s.criterionFields("r", "c")))),
                new Op("edit issue", Scope.ISSUE_UPDATE, s -> {
                    SaveResult issue = s.newIssue("before");
                    return a -> s.bean(SaveService.class).save(a,
                            s.edit("issue", issue.id(), 1, java.util.Map.of("title", "after")));
                }),
                new Op("edit spec", Scope.SPEC_UPDATE, s -> {
                    SaveResult spec = s.newSpec("before");
                    return a -> s.bean(SaveService.class).save(a,
                            s.edit("spec", spec.id(), 1, java.util.Map.of("title", "after")));
                }),
                new Op("edit task", Scope.TASK_UPDATE, s -> {
                    SaveResult task = s.newTask("before");
                    return a -> s.bean(SaveService.class).save(a,
                            s.edit("task", task.id(), 1, java.util.Map.of("title", "after")));
                }),
                new Op("edit criterion", Scope.CRITERION_UPDATE, s -> {
                    SaveResult criterion = s.newCriterion("E", "r", "before");
                    return a -> s.bean(SaveService.class).save(a,
                            s.edit("criterion", criterion.id(), 1, java.util.Map.of("criterion_text", "after")));
                }),
                new Op("transition issue", Scope.ISSUE_UPDATE, s -> {
                    SaveResult i = s.newIssue("g");
                    return a -> s.bean(TransitionService.class).transition(a,
                            s.move("issue", i.id(), 1, "DISPUTED", Scenario.EVIDENCE));
                }),
                new Op("transition spec", Scope.SPEC_UPDATE, s -> {
                    SaveResult sp = s.newSpec("g");
                    return a -> s.bean(TransitionService.class).transition(a,
                            s.move("spec", sp.id(), 1, "ACTIVE", null));
                }),
                new Op("transition task", Scope.TASK_UPDATE, s -> {
                    SaveResult t = s.newTask("g");
                    return a -> s.bean(TransitionService.class).transition(a,
                            s.move("task", t.id(), 1, "ACTIVE", null));
                }),
                new Op("verify through review", Scope.VERIFY, s -> {
                    SaveResult i = s.resolvedIssue("g");
                    return a -> s.bean(ReviewService.class).review(a,
                            s.move("issue", i.id(), 2, "VERIFIED", Scenario.EVIDENCE));
                }),
                new Op("record_deliverable", Scope.DELIVERABLE_ADD, s -> {
                    SaveResult i = s.newIssue("g");
                    return a -> s.deliver(a, i.id(), Scenario.ref('b'));
                }),
                new Op("record_case", Scope.ISSUE_UPDATE, s -> {
                    SaveResult i = s.newIssue("g");
                    return a -> s.bean(RecordCaseService.class).recordCase(a,
                            new RecordCaseCommand(Scenario.uuid(), i.id(), "seen", Scenario.REASON, null, null, null));
                }),
                new Op("link issue_task", Scope.ISSUE_UPDATE, s -> {
                    SaveResult i = s.newIssue("g");
                    SaveResult t = s.newTask("g");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(Scenario.uuid(), "issue_task", i.id(), t.id(), Scenario.REASON, null));
                }),
                new Op("link issue_spec", Scope.SPEC_UPDATE, s -> {
                    SaveResult i = s.newIssue("g");
                    SaveResult sp = s.newSpec("g");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(Scenario.uuid(), "issue_spec", i.id(), sp.id(), Scenario.REASON, null));
                }),
                new Op("link spec_task", Scope.SPEC_UPDATE, s -> {
                    SaveResult sp = s.newSpec("g");
                    SaveResult t = s.newTask("g");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(Scenario.uuid(), "spec_task", sp.id(), t.id(), Scenario.REASON, null));
                }),
                new Op("link relation", Scope.ISSUE_UPDATE, s -> {
                    SaveResult i1 = s.newIssue("g1");
                    SaveResult i2 = s.newIssue("g2");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(Scenario.uuid(), "same_site", i1.id(), i2.id(), Scenario.REASON, null));
                }),
                new Op("link criterion_task", Scope.CRITERION_UPDATE, s -> {
                    SaveResult c = s.newCriterion("L1", "r", "c");
                    SaveResult t = s.newTask("g");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(Scenario.uuid(), "criterion_task", c.id(), t.id(), Scenario.REASON, null));
                }),
                new Op("link criterion_issue", Scope.CRITERION_UPDATE, s -> {
                    SaveResult c = s.newCriterion("L2", "r", "c");
                    SaveResult i = s.newIssue("g");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(Scenario.uuid(), "criterion_issue", c.id(), i.id(), Scenario.REASON, null));
                }),
                new Op("link criterion_spec", Scope.CRITERION_UPDATE, s -> {
                    SaveResult c = s.newCriterion("L3", "r", "c");
                    SaveResult sp = s.newSpec("g");
                    return a -> s.bean(LinkService.class).link(a,
                            new LinkCommand(Scenario.uuid(), "criterion_spec", c.id(), sp.id(), Scenario.REASON, null));
                }),
                new Op("correct an issue row", Scope.ISSUE_UPDATE, s -> {
                    SaveResult i = s.newIssue("g");
                    return a -> s.bean(CorrectService.class).correct(a,
                            new CorrectCommand(Scenario.uuid(), i.historyId(), Scenario.REASON));
                }),
                new Op("correct a spec row", Scope.SPEC_UPDATE, s -> {
                    SaveResult sp = s.newSpec("g");
                    return a -> s.bean(CorrectService.class).correct(a,
                            new CorrectCommand(Scenario.uuid(), sp.historyId(), Scenario.REASON));
                }),
                new Op("correct a deliverable row", Scope.DELIVERABLE_EDIT, s -> {
                    SaveResult i = s.newIssue("g");
                    long deliverableHistory = s.deliver(Scenario.AUTHOR, i.id(), Scenario.ref('c')).historyId();
                    return a -> s.bean(CorrectService.class).correct(a,
                            new CorrectCommand(Scenario.uuid(), deliverableHistory, Scenario.REASON));
                }),
                new Op("correct a task row", Scope.TASK_UPDATE, s -> {
                    SaveResult t = s.newTask("g");
                    return a -> s.bean(CorrectService.class).correct(a,
                            new CorrectCommand(Scenario.uuid(), t.historyId(), Scenario.REASON));
                }),
                new Op("correct a verification row", Scope.VERIFY, s -> {
                    SaveResult i = s.verifiedIssue("g");
                    long verification = s.history(HistoryEntity.ISSUE, i.id()).stream()
                            .filter(h -> h.op() == HistoryOp.TRANSITION && "VERIFIED".equals(h.toState()))
                            .findFirst().orElseThrow().id();
                    return a -> s.bean(ReviewService.class).reviewCorrect(a,
                            new CorrectCommand(Scenario.uuid(), verification, Scenario.REASON));
                }));
    }

    @ParameterizedTest
    @MethodSource("operations")
    @DisplayName("SVC-GATE-06: an author with every scope but the needed one is refused, naming the scope")
    void gate06_wrongScopeIsRefused(Op op, Scenario s) {
        Function<String, Object> call = op.prepare().apply(s);
        String[] others = Scope.assignable().stream().filter(x -> x != op.scope()).map(Scope::token)
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

    @Test
    @DisplayName("SVC-GATE-07: a grant on one project does not allow writes to another")
    void gate07_grantsAreProjectScoped(Scenario s) {
        String other = s.otherProject();
        var fields = s.issueFields("t");
        fields.put("project_id", other);
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("issue", null, fields)), TrustCode.SESSION_NOT_GRANTED);
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
    @DisplayName("SVC-GATE-08: moving a free-text task needs grants on the source and the target project")
    void gate08_aMoveNeedsBothProjects(Grants grants, Scenario s) {
        String other = s.otherProject();
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("task", "FREE-MV-" + Scenario.uuid().substring(0, 8), s.taskFields("t")));
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
    @DisplayName("SVC-GATE-09: a relation between issues of two projects needs the issue grant on both")
    void gate09_relationNeedsBothProjects(Grants grants, Scenario s) {
        String other = s.otherProject();
        SaveResult i1 = s.newIssue("one");
        var fields = s.issueFields("two");
        fields.put("project_id", other);
        s.grant(Scenario.AUTHOR, other, "issue");
        SaveResult i2 = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields));
        String actor = "claude:rel" + Math.abs(grants.name().hashCode());
        var projects = java.util.Map.of("source", s.projectId(), "target", other);
        grants.on().forEach(label -> s.grant(actor, projects.get(label), "issue"));
        assertThat(Expect.outcome(() -> s.bean(LinkService.class).link(actor,
                new LinkCommand(Scenario.uuid(), "same_site", i1.id(), i2.id(), Scenario.REASON, null))))
                .isEqualTo(Optional.ofNullable(grants.expected()));
    }

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
        s.bean(GrantService.class).grant(new GrantCommand(actor, s.projectId(), List.of("issue"),
                expiry.expiresAt(), null), TestOwners.OWNER);
        s.ctx().clock().set(java.time.Instant.parse(expiry.at()));
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(actor,
                s.create("issue", null, s.issueFields("t"))))).isEqualTo(Optional.ofNullable(expiry.expected()));
    }

    @Test
    @DisplayName("SVC-GATE-11: refused while revoked, accepted after a new grant")
    void gate11_revokeAndRegrant(Scenario s) {
        String actor = s.actorWith("issue");
        s.bean(GrantService.class).revoke(new RevokeCommand(actor, s.projectId(), "issue"), TestOwners.OWNER);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("t"))),
                TrustCode.SESSION_NOT_GRANTED);
        s.grant(actor, s.projectId(), "issue");
        assertThat(s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("t"))).version())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-12: a revoked session cannot replay a stored receipt")
    void gate12_theGateRunsBeforeTheReceiptLookup(Scenario s) {
        String actor = s.actorWith("issue");
        SaveCommand command = new SaveCommand(Scenario.uuid(), "issue", null, 0L, Fields.of(s.issueFields("t")),
                Scenario.REASON);
        SaveResult first = s.bean(SaveService.class).save(actor, command);
        assertThat(s.bean(SaveService.class).save(actor, command)).isEqualTo(first);
        s.bean(GrantService.class).revoke(new RevokeCommand(actor, null, null), TestOwners.OWNER);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, command), TrustCode.SESSION_NOT_GRANTED);
    }

    @Test
    void deliverableCreationAndReplayNeedCreateWhileAmendmentNeedsUpdate(Scenario s) {
        String creator = s.actorWith("deliverable_create");
        String editor = s.actorWith("deliverable_update");
        String issue = s.newIssue("deliverable permission test").id();
        String reference = Scenario.ref('a');
        RecordDeliverableService service = s.bean(RecordDeliverableService.class);
        RecordDeliverableCommand create = s.deliverableCommand(issue, reference);
        Expect.failure(() -> service.recordDeliverable(editor, create), TrustCode.SESSION_NOT_GRANTED);
        var created = service.recordDeliverable(creator, create);
        assertThat(service.recordDeliverable(creator, create)).isEqualTo(created);

        var update = new RecordDeliverableCommand(Scenario.uuid(), "issue", issue, "code_change", reference,
                Scenario.REASON, "repo/e2e", "Deliverable for " + issue, "check/result", "passed", null, null);
        Expect.failure(() -> service.recordDeliverable(creator, update), TrustCode.SESSION_NOT_GRANTED);
        var amended = service.recordDeliverable(editor, update);
        assertThat(service.recordDeliverable(editor, update)).isEqualTo(amended);
    }

    @Test
    void revokingOnePermissionKeepsTheEffectiveExpiryOfTheRemainingGrant(Scenario s) {
        String actor = s.actorWith("issue");
        var service = s.bean(GrantService.class);
        service.grant(new GrantCommand(actor, s.projectId(), List.of("issue_update"),
                "2026-11-05T11:00:00Z", null), TestOwners.OWNER);
        assertThat(service.revoke(new RevokeCommand(actor, s.projectId(), "issue_create"), TestOwners.OWNER))
                .isEqualTo(2);
        var grants = service.live(actor);
        assertThat(grants).extracting(SessionGrant::scope).containsExactlyInAnyOrder(Scope.ISSUE_UPDATE,
                Scope.VERSION_CREATE, Scope.VERSION_UPDATE);
        assertThat(grants).allMatch(grant -> grant.expiresAt() == null);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("forbidden"))),
                TrustCode.SESSION_NOT_GRANTED);
    }

    private SessionGrant issueGrant(Scenario s, String actor) {
        return s.bean(SessionGrantRepository.class).findByActiveKey(SessionGrant.liveKey(AuthorId.of(actor),
                ProjectId.of(s.projectId()), Scope.ISSUE)).orElseThrow();
    }

    @Test
    @DisplayName("SVC-GATE-13: a write stamps the grant, a failed write does not")
    void gate13_lastUsedAt(Scenario s) {
        String actor = s.actorWith("issue");
        assertThat(issueGrant(s, actor).lastUsedAt()).isNull();
        SaveCommand tooShort = new SaveCommand(Scenario.uuid(), "issue", null, 0L, Fields.of(s.issueFields("t")), "short");
        Expect.failure(() -> s.bean(SaveService.class).save(actor, tooShort), ValidationCode.REASON_INVALID);
        assertThat(issueGrant(s, actor).lastUsedAt()).as("rolled back with the failed write").isNull();
        s.ctx().clock().set(java.time.Instant.parse("2026-11-05T12:34:56Z"));
        s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("t")));
        assertThat(issueGrant(s, actor).lastUsedAt()).isEqualTo(java.time.Instant.parse("2026-11-05T12:34:56Z"));
    }

    @Test
    @DisplayName("SVC-GATE-19: the grant check and the write share a transaction")
    void gate19_anExceptionAfterTheGateRollsBackTheStamp(Scenario s) {
        String actor = s.actorWith("issue");
        assertThatCode(() -> s.ctx().tx(() -> {
            s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("t")));
            throw new IllegalStateException("after the gate");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(issueGrant(s, actor).lastUsedAt()).isNull();
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isZero();
    }

    @Test
    @DisplayName("SVC-GATE-14: author ids are compared case-sensitively")
    void gate14_caseSensitiveAuthors(Scenario s) {
        s.grant("claude:ABC", s.projectId(), "issue");
        Expect.failure(() -> s.bean(SaveService.class).save("claude:abc", s.create("issue", null,
                s.issueFields("t"))), TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.bean(SaveService.class).save("claude:ABC", s.create("issue", null, s.issueFields("t")))
                .version()).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-16: user:owner is reserved for direct owner operations and refused as an agent session author")
    void gate16_ownerNeedsAGrantToo(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save("user:owner", s.create("issue", null,
                s.issueFields("t"))), TrustCode.AUTHOR_ID_FORMAT);
        Expect.failure(() -> s.grant("user:owner", s.projectId(), "issue"), TrustCode.AUTHOR_ID_FORMAT);
    }

    @Test
    @DisplayName("SVC-GATE-15: a save without project_id is a validation failure, an unknown id is not found")
    void gate15_theProjectMustBeDetermined(Scenario s) {
        var fields = s.issueFields("t");
        fields.remove("project_id");
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("issue", null, fields)), ValidationCode.VALIDATION_FAILED);
        assertThat(e.args()).first().isEqualTo("project_id");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("issue", s.issueId(99), 1, java.util.Map.of("title", "x"))), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-GATE-17: no attestation beats an unknown project")
    void gate17_attestationBeforeProject(Scenario s) {
        var fields = s.issueFields("t");
        fields.put("project_id", "no-such-project");
        Expect.failure(() -> s.bean(SaveService.class).save(null, s.create("issue", null, fields)),
                TrustCode.HOST_ATTESTATION_REQUIRED);
        for (String malformed : List.of("no-such-id", "x", "")) {
            Expect.failure(() -> s.bean(TransitionService.class).transition(null, s.move("issue", malformed, 1,
                    "DISPUTED", Scenario.EVIDENCE)), TrustCode.HOST_ATTESTATION_REQUIRED);
            Expect.failure(() -> s.bean(ReviewService.class).review(null, s.move("task", malformed, 1, "ACTIVE", null)),
                    TrustCode.HOST_ATTESTATION_REQUIRED);
            Expect.failure(() -> s.bean(TransitionService.class).transition(null, s.move("criterion", malformed, 1,
                    "MET", Scenario.EVIDENCE)), TrustCode.HOST_ATTESTATION_REQUIRED);
        }
        Expect.failure(() -> s.bean(TransitionService.class).transition(null, s.move("nothing", s.issueId(1), 1,
                "DISPUTED", Scenario.EVIDENCE)), TrustCode.HOST_ATTESTATION_REQUIRED);
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> s.bean(TransitionService.class).transition(null, s.move("issue", i.id(), 1, "NOWHERE",
                Scenario.EVIDENCE)), TrustCode.HOST_ATTESTATION_REQUIRED);
        Expect.failure(() -> s.bean(TransitionService.class).transition("claude", s.move("issue", "no-such-id", 1,
                "DISPUTED", Scenario.EVIDENCE)), TrustCode.AUTHOR_ID_FORMAT);
        Expect.failure(() -> s.bean(CorrectService.class).correct(null, new CorrectCommand(Scenario.uuid(), null,
                Scenario.REASON)), TrustCode.HOST_ATTESTATION_REQUIRED);
        Expect.failure(() -> s.bean(ReviewService.class).reviewCorrect(null, new CorrectCommand(Scenario.uuid(), null,
                Scenario.REASON)), TrustCode.HOST_ATTESTATION_REQUIRED);
        Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("issue",
                "no-such-id", 1, "DISPUTED", Scenario.EVIDENCE)), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-GATE-17: a bad format beats a missing grant")
    void gate17_formatBeforeGrant(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save("claude", s.create("issue", null,
                s.issueFields("t"))), TrustCode.AUTHOR_ID_FORMAT);
    }

    @Test
    @DisplayName("SVC-GATE-17: a missing grant beats a short reason")
    void gate17_grantBeforeReason(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.NOGRANT, new SaveCommand(Scenario.uuid(), "issue", null,
                0L, Fields.of(s.issueFields("t")), "short")), TrustCode.SESSION_NOT_GRANTED);
    }

    @Test
    @DisplayName("SVC-GATE-17: a short reason beats a stale version")
    void gate17_reasonBeforeVersion(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(Scenario.uuid(), "issue", i.id(),
                99L, Fields.of("title", "x"), "short")), ValidationCode.REASON_INVALID);
    }

    @Test
    @DisplayName("SVC-GATE-17: a stored receipt is replayed before the version is looked at")
    void gate17_receiptBeforeVersion(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveCommand edit = new SaveCommand(Scenario.uuid(), "issue", i.id(), 1L, Fields.of("title", "x"),
                Scenario.REASON);
        SaveResult first = s.bean(SaveService.class).save(Scenario.AUTHOR, edit);
        assertThat(first.version()).isEqualTo(2);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, edit)).isEqualTo(first);
        SaveCommand fresh = new SaveCommand(Scenario.uuid(), "issue", i.id(), 1L, Fields.of("title", "y"),
                Scenario.REASON);
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, fresh)))
                .contains(ConflictCode.VERSION_CONFLICT);
    }

    @Test
    @DisplayName("SVC-GATE-18: the actor is bound per call, never leaks, and is unbound afterwards")
    void gate18_scopedValueActor(Scenario s) {
        List<String> actors = IntStream.range(0, 20).mapToObj(i -> "claude:par" + i).toList();
        actors.forEach(a -> s.grant(a, s.projectId(), "issue"));
        List<java.util.concurrent.Callable<SaveResult>> calls = new ArrayList<>();
        for (String actor : actors) {
            calls.add(() -> s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields(actor))));
        }
        List<Parallel.Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(outcomes.stream().map(o -> o.value().author())).containsExactlyElementsOf(actors);
        assertThat(outcomes).allSatisfy(o -> assertThat(s.issue(o.value().id()).createdBy().value())
                .isEqualTo(o.value().author()));
        assertThat(ActorScope.CURRENT.isBound()).isFalse();
    }

    /** A kind of link that is not a relation, with the scope that it needs on every project it touches. */
    record LinkKindCase(String kind, Scope scope) {
        @Override
        public String toString() {
            return kind;
        }
    }

    static Stream<LinkKindCase> linkKinds() {
        return Stream.of(
                new LinkKindCase("issue_task", Scope.ISSUE_UPDATE),
                new LinkKindCase("issue_spec", Scope.SPEC_UPDATE),
                new LinkKindCase("spec_task", Scope.SPEC_UPDATE),
                new LinkKindCase("criterion_task", Scope.CRITERION_UPDATE),
                new LinkKindCase("criterion_issue", Scope.CRITERION_UPDATE),
                new LinkKindCase("criterion_spec", Scope.CRITERION_UPDATE));
    }

    /** The two ends of a link. */
    record Ends(String a, String b) {
    }

    private static SaveResult createIn(Scenario s, String entity, String project) {
        var fields = "task".equals(entity) ? s.taskFields("other")
                : "spec".equals(entity) ? s.specFields("other") : s.issueFields("other");
        fields.put("project_id", project);
        return s.bean(SaveService.class).save(Scenario.AUTHOR, s.create(entity, null, fields));
    }

    /** The ends of a link of this kind: the first in the project of the scenario, the second in {@code second}. */
    private static Ends ends(Scenario s, String kind, String second) {
        s.grant(Scenario.AUTHOR, second, "issue", "spec", "task");
        if ("issue_task".equals(kind)) {
            return new Ends(s.newIssue("first").id(), createIn(s, "task", second).id());
        }
        if ("issue_spec".equals(kind)) {
            return new Ends(s.newIssue("first").id(), createIn(s, "spec", second).id());
        }
        if ("spec_task".equals(kind)) {
            return new Ends(s.newSpec("first").id(), createIn(s, "task", second).id());
        }
        if ("criterion_task".equals(kind)) {
            return new Ends(s.newCriterion("1", "r1", "c1").id(), createIn(s, "task", second).id());
        }
        if ("criterion_issue".equals(kind)) {
            return new Ends(s.newCriterion("1", "r1", "c1").id(), createIn(s, "issue", second).id());
        }
        return new Ends(s.newCriterion("1", "r1", "c1").id(), createIn(s, "spec", second).id());
    }

    private static String newActor(String label) {
        return "claude:" + label + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static LinkCommand link(String kind, Ends ends, Boolean remove) {
        return new LinkCommand(Scenario.uuid(), kind, ends.a(), ends.b(), Scenario.REASON, remove);
    }

    @ParameterizedTest
    @MethodSource("linkKinds")
    @DisplayName("SVC-GATE-43 (VRF-030, VRF-031): a link between rows of two projects needs the scope of the kind on both projects")
    void gate43_linkNeedsBothProjects(LinkKindCase kind, Scenario s) {
        String other = s.otherProject();
        Ends ends = ends(s, kind.kind(), other);
        var cases = List.of(new Grants("the first project only", List.of("source"), TrustCode.SESSION_NOT_GRANTED),
                new Grants("the second project only", List.of("target"), TrustCode.SESSION_NOT_GRANTED),
                new Grants("both projects", List.of("source", "target"), null));
        var projects = java.util.Map.of("source", s.projectId(), "target", other);
        var lacking = java.util.Map.of("the first project only", other, "the second project only", s.projectId());
        for (Grants grants : cases) {
            String actor = newActor("lk");
            grants.on().forEach(label -> s.grant(actor, projects.get(label), kind.scope().token()));
            Optional<ErrorCode> outcome = Expect.outcome(() -> s.bean(LinkService.class).link(actor,
                    link(kind.kind(), ends, null)));
            assertThat(outcome).as(grants.name()).isEqualTo(Optional.ofNullable(grants.expected()));
            if (grants.expected() != null) {
                LedgerException e = Expect.failure(() -> s.bean(LinkService.class).link(actor,
                        link(kind.kind(), ends, null)), TrustCode.SESSION_NOT_GRANTED);
                assertThat(e.details()).containsEntry("project", List.of(lacking.get(grants.name())))
                        .containsEntry("scope", List.of(kind.scope().token()));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("linkKinds")
    @DisplayName("SVC-GATE-44 (VRF-032): both ends in one project need the scope there once, to link and to unlink")
    void gate44_oneProjectIsOneCheck(LinkKindCase kind, Scenario s) {
        Ends ends;
        if ("issue_task".equals(kind.kind())) {
            ends = new Ends(s.newIssue("one").id(), s.newTask("one").id());
        } else if ("issue_spec".equals(kind.kind())) {
            ends = new Ends(s.newIssue("one").id(), s.newSpec("one").id());
        } else if ("spec_task".equals(kind.kind())) {
            ends = new Ends(s.newSpec("one").id(), s.newTask("one").id());
        } else if ("criterion_task".equals(kind.kind())) {
            ends = new Ends(s.newCriterion("1", "r1", "c1").id(), s.newTask("one").id());
        } else if ("criterion_issue".equals(kind.kind())) {
            ends = new Ends(s.newCriterion("1", "r1", "c1").id(), s.newIssue("one").id());
        } else {
            ends = new Ends(s.newCriterion("1", "r1", "c1").id(), s.newSpec("one").id());
        }
        String actor = newActor("lk");
        s.grant(actor, s.projectId(), kind.scope().token());
        assertThat(s.bean(LinkService.class).link(actor, link(kind.kind(), ends, null)).removed()).isFalse();
        assertThat(s.bean(LinkService.class).link(actor, link(kind.kind(), ends, true)).removed()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("linkKinds")
    @DisplayName("SVC-GATE-45 (VRF-033): the unlink of a link across two projects passes the same check as the link")
    void gate45_unlinkNeedsBothProjects(LinkKindCase kind, Scenario s) {
        String other = s.otherProject();
        Ends ends = ends(s, kind.kind(), other);
        var linked = s.bean(LinkService.class).link(Scenario.AUTHOR, link(kind.kind(), ends, null));
        String firstOnly = newActor("ul");
        s.grant(firstOnly, s.projectId(), kind.scope().token());
        LedgerException e = Expect.failure(() -> s.bean(LinkService.class).link(firstOnly,
                link(kind.kind(), ends, true)), TrustCode.SESSION_NOT_GRANTED);
        assertThat(e.details()).containsEntry("project", List.of(other)).containsEntry("scope",
                List.of(kind.scope().token()));
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE op = 'UNLINK' AND entity_id = ?", linked.id())).isZero();
        String both = newActor("ul");
        s.grant(both, s.projectId(), kind.scope().token());
        s.grant(both, other, kind.scope().token());
        assertThat(s.bean(LinkService.class).link(both, link(kind.kind(), ends, true)).removed()).isTrue();
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE op = 'UNLINK' AND entity_id = ?", linked.id()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-46 (VRF-034): a merge into an issue of another project needs the issue scope on both projects")
    void gate46_mergeNeedsBothProjects(Scenario s) {
        String other = s.otherProject();
        s.grant(Scenario.AUTHOR, other, "issue");
        SaveResult source = s.newIssue("source");
        SaveResult sameProject = s.newIssue("same project");
        SaveResult target = createIn(s, "issue", other);
        java.util.function.BiFunction<String, String, TransitionCommand> merge =
                (id, into) -> new TransitionCommand(Scenario.uuid(), "issue", id, 1L, "MERGED", Scenario.REASON, null,
                        null, into);
        String firstOnly = newActor("mg");
        s.grant(firstOnly, s.projectId(), "issue");
        LedgerException e = Expect.failure(() -> s.bean(TransitionService.class).transition(firstOnly,
                merge.apply(source.id(), target.id())), TrustCode.SESSION_NOT_GRANTED);
        assertThat(e.details()).containsEntry("project", List.of(other)).containsEntry("scope", List.of("issue_update"));
        String secondOnly = newActor("mg");
        s.grant(secondOnly, other, "issue");
        e = Expect.failure(() -> s.bean(TransitionService.class).transition(secondOnly,
                merge.apply(source.id(), target.id())), TrustCode.SESSION_NOT_GRANTED);
        assertThat(e.details()).containsEntry("project", List.of(s.projectId()));
        assertThat(s.issue(source.id()).status()).isEqualTo(IssueStatus.OPEN);
        s.bean(TransitionService.class).transition(firstOnly, merge.apply(sameProject.id(), source.id()));
        assertThat(s.issue(sameProject.id()).mergedInto().value()).isEqualTo(source.id());
        String both = newActor("mg");
        s.grant(both, s.projectId(), "issue");
        s.grant(both, other, "issue");
        Expect.failure(() -> s.bean(TransitionService.class).transition(both, merge.apply(source.id(), s.issueId(99))),
                LookupCode.NOT_FOUND);
        s.bean(TransitionService.class).transition(both, merge.apply(source.id(), target.id()));
        assertThat(s.issue(source.id()).mergedInto().value()).isEqualTo(target.id());
    }

    @Test
    @DisplayName("SVC-GATE-47 (VRF-035): the other end of a link that does not exist is NOT_FOUND and writes nothing")
    void gate47_unknownOtherEnd(Scenario s) {
        SaveResult issue = s.newIssue("i");
        SaveResult spec = s.newSpec("sp");
        SaveResult criterion = s.newCriterion("1", "r1", "c1");
        long history = s.count("SELECT COUNT(*) FROM history");
        record Missing(String kind, String a, String b, String what) {
        }
        for (Missing missing : List.of(
                new Missing("issue_task", issue.id(), s.taskId(99), "task"),
                new Missing("issue_spec", issue.id(), s.specId(99), "spec"),
                new Missing("spec_task", spec.id(), s.taskId(99), "task"),
                new Missing("criterion_task", criterion.id(), s.taskId(98), "task"),
                new Missing("criterion_issue", criterion.id(), s.issueId(97), "issue"),
                new Missing("criterion_spec", criterion.id(), s.specId(96), "spec"))) {
            LedgerException e = Expect.failure(() -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                    link(missing.kind(), new Ends(missing.a(), missing.b()), null)), LookupCode.NOT_FOUND);
            assertThat(e.args()).containsExactly(missing.what(), missing.b());
        }
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
    }
}
