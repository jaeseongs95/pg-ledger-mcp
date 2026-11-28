// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.application.history.Change;
import org.veridger.application.json.LedgerJson;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.MergeFixture;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.CorrectCommand;
import org.veridger.application.write.CorrectService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.application.write.WriteResult.TransitionResult;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskState;
import tools.jackson.databind.json.JsonMapper;

/** SVC-TRN-01 .. SVC-TRN-18: transitions and review across issues, specs, tasks and criteria. */
@LedgerServiceTest
class TransitionTest {

    private TransitionResult author(Scenario s, String entity, String id, long version, String to, String evidence) {
        return s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move(entity, id, version, to, evidence));
    }

    private TransitionResult review(Scenario s, String actor, String entity, String id, long version, String to,
            String evidence) {
        return s.bean(ReviewService.class).review(actor, s.move(entity, id, version, to, evidence));
    }

    @Test
    @DisplayName("SVC-TRN-01: an unknown entity lists the entities")
    void trn01_unknownEntity(Scenario s) {
        ValidationException e = (ValidationException) Expect.failure(() -> author(s, "ticket", s.issueId(1), 1,
                "OPEN", null), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).containsExactly("issue", "spec", "task", "criterion");
    }

    @Test
    @DisplayName("SVC-TRN-01: an unknown state lists the states")
    void trn01_unknownState(Scenario s) {
        SaveResult i = s.newIssue("i");
        ValidationException e = (ValidationException) Expect.failure(() -> author(s, "issue", i.id(), 1, "FIXED",
                null), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).containsExactly("OPEN", "RESOLVED", "VERIFIED", "NOT_AN_ISSUE",
                "DISPUTED", "MERGED");
    }

    @Test
    @DisplayName("SVC-TRN-02: RESOLVED needs a recorded deliverable")
    void trn02_deliverableRequired(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> author(s, "issue", i.id(), 1, "RESOLVED", null), TransitionCode.DELIVERABLE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-03: a stale expected version")
    void trn03_versionConflict(Scenario s) {
        SaveResult i = s.newIssue("i");
        author(s, "issue", i.id(), 1, "DISPUTED", Scenario.EVIDENCE);
        Expect.failure(() -> author(s, "issue", i.id(), 1, "OPEN", Scenario.EVIDENCE),
                ConflictCode.VERSION_CONFLICT);
    }

    @Test
    @DisplayName("SVC-TRN-04: an issue already in the target state")
    void trn04_sameState(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> author(s, "issue", i.id(), 1, "OPEN", null), TransitionCode.TRANSITION_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-05: VERIFIED without evidence is refused by the service itself")
    void trn05_evidenceGuard(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        Expect.failure(() -> review(s, Scenario.REVIEWER, "issue", i.id(), 2, "VERIFIED", null),
                TransitionCode.EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-05: VERIFIED without a recorded deliverable is refused by the service itself")
    void trn05_deliverableGuard(Scenario s) {
        SaveResult i = s.newIssue("i");
        s.ctx().jdbc().sql("UPDATE issue SET status = 'RESOLVED' WHERE id = ?").param(i.id()).update();
        Expect.failure(() -> review(s, Scenario.REVIEWER, "issue", i.id(), 1, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.DELIVERABLE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-06: the session that recorded the deliverable cannot verify")
    void trn06_sameSession(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        Expect.failure(() -> review(s, Scenario.AUTHOR, "issue", i.id(), 2, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.SAME_SESSION);
    }

    @Test
    @DisplayName("SVC-TRN-07: a session without grants cannot verify")
    void trn07_noGrant(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        Expect.failure(() -> review(s, Scenario.NOGRANT, "issue", i.id(), 2, "VERIFIED", Scenario.EVIDENCE),
                TrustCode.SESSION_NOT_GRANTED);
    }

    @Test
    @DisplayName("SVC-TRN-08: the reviewer verifies, the history keeps the evidence and the reviewer login")
    void trn08_verify(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        String operationKey = Scenario.uuid();
        TransitionResult result = s.bean(ReviewService.class).review(Scenario.REVIEWER, new TransitionCommand(
                operationKey, "issue", i.id(), 2L, "VERIFIED", Scenario.REASON, Scenario.EVIDENCE, null, null));
        assertThat(result.from()).isEqualTo("RESOLVED");
        assertThat(result.to()).isEqualTo("VERIFIED");
        assertThat(result.version()).isEqualTo(3);
        assertThat(result.author()).isEqualTo(Scenario.REVIEWER);
        assertThat(result.login()).isEqualTo("ledger_reviewer");
        HistoryEntry row = s.historyRow(result.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.TRANSITION);
        assertThat(row.fromState()).isEqualTo("RESOLVED");
        assertThat(row.toState()).isEqualTo("VERIFIED");
        assertThat(row.evidenceUri()).isEqualTo(Scenario.EVIDENCE);
        assertThat(row.operationKey().toString()).isEqualTo(operationKey);
        assertThat(row.login()).isEqualTo("ledger_reviewer");
        assertThat(s.issue(i.id()).status()).isEqualTo(IssueStatus.VERIFIED);
    }

    @Test
    @DisplayName("SVC-TRN-09: leaving VERIFIED needs evidence, with it the reviewer may do it")
    void trn09_leavingVerified(Scenario s) {
        SaveResult i = s.verifiedIssue("i");
        Expect.failure(() -> review(s, Scenario.REVIEWER, "issue", i.id(), 3, "OPEN", null),
                TransitionCode.EVIDENCE_REQUIRED);
        assertThat(review(s, Scenario.REVIEWER, "issue", i.id(), 3, "OPEN", Scenario.EVIDENCE).to())
                .isEqualTo("OPEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_AN_ISSUE", "DISPUTED"})
    @DisplayName("SVC-TRN-10: NOT_AN_ISSUE and DISPUTED need evidence")
    void trn10_evidence(String to, Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> author(s, "issue", i.id(), 1, to, null), TransitionCode.EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-11: merging into a live issue stores the target and records it")
    void trn11_merge(Scenario s) {
        SaveResult source = s.newIssue("source");
        SaveResult target = s.newIssue("target");
        TransitionResult result = MergeFixture.merge(s, source.id(), 1, target.id());
        assertThat(s.issue(source.id()).mergedInto().value()).isEqualTo(target.id());
        assertThat(s.changes(result.historyId()).entries()).containsExactly(Map.entry("merged_into",
                new Change(null, target.id())));
    }

    @Test
    @DisplayName("SVC-TRN-11: an issue cannot be merged into itself")
    void trn11_self(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> MergeFixture.merge(s, i.id(), 1, i.id()), TransitionCode.MERGE_TARGET_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-11: a merged issue is no merge target")
    void trn11_mergedTarget(Scenario s) {
        SaveResult a = s.newIssue("a");
        SaveResult b = s.newIssue("b");
        SaveResult c = s.newIssue("c");
        MergeFixture.merge(s, b.id(), 1, a.id());
        Expect.failure(() -> MergeFixture.merge(s, c.id(), 1, b.id()), TransitionCode.MERGE_TARGET_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-11: an unknown merge target")
    void trn11_unknownTarget(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> MergeFixture.merge(s, i.id(), 1, s.issueId(77)), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-TRN-11: MERGED without a target")
    void trn11_noTarget(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> author(s, "issue", i.id(), 1, "MERGED", null), TransitionCode.MERGE_TARGET_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-12: leaving MERGED clears the target")
    void trn12_unmerge(Scenario s) {
        SaveResult source = s.newIssue("source");
        SaveResult target = s.newIssue("target");
        MergeFixture.merge(s, source.id(), 1, target.id());
        author(s, "issue", source.id(), 2, "OPEN", null);
        assertThat(s.issue(source.id()).mergedInto()).isNull();
        assertThat(s.issue(source.id()).status()).isEqualTo(IssueStatus.OPEN);
    }

    @Test
    @DisplayName("SVC-TRN-13: the task and spec flows of completing, verifying and sending back")
    void trn13_taskAndSpecFlows(Scenario s) {
        SaveResult t = s.newTask("t");
        TransitionResult completed = author(s, "task", t.id(), 1, "COMPLETED", Scenario.EVIDENCE);
        assertThat(completed.version()).isEqualTo(2);
        Expect.failure(() -> review(s, Scenario.AUTHOR, "task", t.id(), 2, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, Scenario.REVIEWER, "task", t.id(), 2, "VERIFIED", Scenario.EVIDENCE).version())
                .isEqualTo(3);
        assertThat(review(s, Scenario.REVIEWER, "task", t.id(), 3, "COMPLETED", Scenario.EVIDENCE).version())
                .isEqualTo(4);
        Expect.failure(() -> review(s, Scenario.AUTHOR, "task", t.id(), 4, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, Scenario.REVIEWER, "task", t.id(), 4, "VERIFIED", Scenario.EVIDENCE).version())
                .isEqualTo(5);
        assertThat(s.task(t.id()).state()).isEqualTo(TaskState.VERIFIED);

        SaveResult sp = s.newSpec("sp");
        author(s, "spec", sp.id(), 1, "COMPLETED", Scenario.EVIDENCE);
        Expect.failure(() -> review(s, Scenario.AUTHOR, "spec", sp.id(), 2, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, Scenario.REVIEWER, "spec", sp.id(), 2, "VERIFIED", Scenario.EVIDENCE).to())
                .isEqualTo("VERIFIED");
        assertThat(s.spec(sp.id()).status()).isEqualTo(SpecStatus.VERIFIED);
    }

    @Test
    @DisplayName("SVC-TRN-14: the task owner cannot verify")
    void trn14_ownerCannotVerify(Scenario s) {
        var fields = s.taskFields("t");
        fields.put("owner_actor_id", Scenario.REVIEWER);
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        author(s, "task", t.id(), 1, "COMPLETED", Scenario.EVIDENCE);
        Expect.failure(() -> review(s, Scenario.REVIEWER, "task", t.id(), 2, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.SAME_SESSION);
    }

    @ParameterizedTest
    @EnumSource(value = HistoryEntity.class, names = {"ISSUE", "SPEC", "TASK", "CRITERION"})
    void independentReviewerCanReturnReportedWork(HistoryEntity kind, Scenario s) {
        SaveResult reported = switch (kind) {
            case ISSUE -> s.resolvedIssue("issue");
            case SPEC -> s.newSpec("spec");
            case TASK -> s.newTask("task");
            case CRITERION -> s.newCriterion("1", "milestone", "criterion");
            default -> throw new IllegalArgumentException(kind.name());
        };
        String entity = kind.token();
        boolean issue = kind == HistoryEntity.ISSUE;
        if (!issue) {
            author(s, entity, reported.id(), 1, "COMPLETED", Scenario.EVIDENCE);
        }
        String reviewer = s.actorWith("verify");
        String target = issue ? "OPEN" : "ACTIVE";
        String from = issue ? "RESOLVED" : "COMPLETED";
        var command = s.move(entity, reported.id(), 2, target, Scenario.EVIDENCE);
        var reviewService = s.bean(ReviewService.class);
        Expect.failure(() -> s.bean(TransitionService.class).transition(reviewer, command),
                TrustCode.SESSION_NOT_GRANTED);
        Expect.failure(() -> review(s, reviewer, entity, reported.id(), 2, target, null),
                TransitionCode.EVIDENCE_REQUIRED);
        s.grant(Scenario.AUTHOR, s.projectId(), "verify");
        Expect.failure(() -> reviewService.review(Scenario.AUTHOR, command), TransitionCode.SAME_SESSION);

        TransitionResult returned = reviewService.review(reviewer, command);
        assertThat(returned.from()).isEqualTo(from);
        assertThat(returned.to()).isEqualTo(target);
        assertThat(returned.version()).isEqualTo(3);
        assertThat(s.historyRow(returned.historyId()).involvesVerification()).isTrue();
        assertThat(reviewService.review(reviewer, command)).isEqualTo(returned);
        var correction = new CorrectCommand(Scenario.uuid(), returned.historyId(), Scenario.REASON);
        Expect.failure(() -> s.bean(CorrectService.class).correct(Scenario.AUTHOR, correction),
                TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
        assertThat(reviewService.reviewCorrect(reviewer, correction).corrects()).isEqualTo(returned.historyId());
    }

    @Test
    void authorMayWithdrawCompletionWithoutReviewPrivileges(Scenario s) {
        SaveResult task = s.newTask("task");
        author(s, "task", task.id(), 1, "COMPLETED", Scenario.EVIDENCE);
        var command = s.move("task", task.id(), 2, "ACTIVE", null);
        String editOnly = s.actorWith("task_update");
        Expect.failure(() -> s.bean(ReviewService.class).review(editOnly, command), TrustCode.SESSION_NOT_GRANTED);
        var result = s.bean(TransitionService.class).transition(editOnly, command);
        assertThat(result.to()).isEqualTo("ACTIVE");
        assertThat(s.historyRow(result.historyId()).involvesVerification()).isFalse();
        String verifyOnly = s.actorWith("verify");
        Expect.failure(() -> review(s, verifyOnly, "task", task.id(), 3, "BLOCKED", Scenario.EVIDENCE),
                TrustCode.SESSION_NOT_GRANTED);
    }

    @Test
    void mergingVerifiedIssueStillRequiresUpdatePermissionOnBothProjects(Scenario s) {
        SaveResult source = s.verifiedIssue("source");
        String destinationProject = s.otherProject();
        s.grant(Scenario.AUTHOR, destinationProject, "issue_create");
        var fields = s.issueFields("destination");
        fields.put("project_id", destinationProject);
        SaveResult destination = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields));
        String reviewer = s.actorWith("verify");
        s.grant(reviewer, destinationProject, "issue_update");
        var merge = new TransitionCommand(Scenario.uuid(), "issue", source.id(), 3L, "MERGED", Scenario.REASON,
                Scenario.EVIDENCE, null, destination.id());
        var reviews = s.bean(ReviewService.class);
        var failure = Expect.failure(() -> reviews.review(reviewer, merge), TrustCode.SESSION_NOT_GRANTED);
        assertThat(failure.args()).containsExactly(reviewer, "issue_update", s.projectId());
        s.grant(reviewer, s.projectId(), "issue_update");
        assertThat(reviews.review(reviewer, merge).to()).isEqualTo("MERGED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"spec", "task"})
    void withdrawingVerdictAndCancellingRequiresRecordUpdate(String entity, Scenario s) {
        boolean spec = "spec".equals(entity);
        SaveResult record = spec ? s.newSpec(entity) : s.newTask(entity);
        author(s, entity, record.id(), 1, "COMPLETED", Scenario.EVIDENCE);
        String reviewer = s.actorWith("verify");
        review(s, reviewer, entity, record.id(), 2, "VERIFIED", Scenario.EVIDENCE);
        var cancel = s.move(entity, record.id(), 3, "CANCELLED", Scenario.EVIDENCE);
        var reviews = s.bean(ReviewService.class);
        var refused = Expect.failure(() -> reviews.review(reviewer, cancel), TrustCode.SESSION_NOT_GRANTED);
        assertThat(refused.args()).containsExactly(reviewer, entity + "_update", s.projectId());
        s.grant(reviewer, s.projectId(), entity + "_update");
        assertThat(reviews.review(reviewer, cancel).to()).isEqualTo("CANCELLED");
    }

    @EnumSource(value = IssueStatus.class, names = {"NOT_AN_ISSUE", "DISPUTED"})
    @ParameterizedTest
    void withdrawingIssueVerdictAndChangingDispositionRequiresIssueUpdate(IssueStatus to, Scenario s) {
        SaveResult issue = s.verifiedIssue("issue");
        String reviewer = s.actorWith("verify");
        var disposition = s.move("issue", issue.id(), 3, to.name(), Scenario.EVIDENCE);
        var reviews = s.bean(ReviewService.class);
        var refused = Expect.failure(() -> reviews.review(reviewer, disposition), TrustCode.SESSION_NOT_GRANTED);
        assertThat(refused.args()).containsExactly(reviewer, "issue_update", s.projectId());
        s.grant(reviewer, s.projectId(), "issue_update");
        assertThat(reviews.review(reviewer, disposition).to()).isEqualTo(to.name());
    }

    @Test
    @DisplayName("SVC-TRN-15: a task cannot return to UNKNOWN")
    void trn15_toUnknown(Scenario s) {
        SaveResult t = s.newTask("t");
        Expect.failure(() -> author(s, "task", t.id(), 1, "UNKNOWN", null), TransitionCode.TRANSITION_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-15: COMPLETED needs evidence")
    void trn15_doneNeedsEvidence(Scenario s) {
        SaveResult t = s.newTask("t");
        Expect.failure(() -> author(s, "task", t.id(), 1, "COMPLETED", null), TransitionCode.EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-16: criterion completion and MET/NOT_MET verification")
    void trn16_criterion(Scenario s) {
        SaveResult c = s.newCriterion("1", "r", "c");
        Expect.failure(() -> author(s, "criterion", c.id(), 1, "COMPLETED", null), TransitionCode.EVIDENCE_REQUIRED);
        assertThat(author(s, "criterion", c.id(), 1, "COMPLETED", Scenario.EVIDENCE).to()).isEqualTo("COMPLETED");
        Expect.failure(() -> review(s, Scenario.REVIEWER, "criterion", c.id(), 2, "MET", null),
                TransitionCode.EVIDENCE_REQUIRED);
        assertThat(review(s, Scenario.REVIEWER, "criterion", c.id(), 2, "MET", Scenario.EVIDENCE).to())
                .isEqualTo("MET");
    }

    @Test
    @DisplayName("SVC-TRN-16 / R3-F6: MET back to UNKNOWN needs the verify scope and evidence")
    void trn16_backToUnknownNeedsEvidence(Scenario s) {
        SaveResult c = s.newCriterion("1", "r", "c");
        review(s, Scenario.REVIEWER, "criterion", c.id(), 1, "MET", Scenario.EVIDENCE);
        Expect.failure(() -> review(s, Scenario.REVIEWER, "criterion", c.id(), 2, "UNKNOWN", null),
                TransitionCode.EVIDENCE_REQUIRED);
        assertThat(review(s, Scenario.REVIEWER, "criterion", c.id(), 2, "UNKNOWN", Scenario.EVIDENCE).to())
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("SVC-TRN-17: the result carries exactly the contract keys")
    void trn17_resultKeys(Scenario s) {
        SaveResult i = s.newIssue("i");
        TransitionResult result = author(s, "issue", i.id(), 1, "DISPUTED", Scenario.EVIDENCE);
        Map<?, ?> keys = new JsonMapper().readValue(s.bean(LedgerJson.class).write(result).text(), Map.class);
        assertThat(keys.keySet()).isEqualTo(Set.of("status", "entity", "id", "from", "to", "version", "history_id",
                "author", "login"));
    }

    @Test
    @DisplayName("SVC-TRN-18: a malformed evidence_sha256")
    void trn18_sha(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(
                Scenario.uuid(), "issue", i.id(), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, "xyz", null)),
                ValidationCode.VALIDATION_FAILED);
    }
}
