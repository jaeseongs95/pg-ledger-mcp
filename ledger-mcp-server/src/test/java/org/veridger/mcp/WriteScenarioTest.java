// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.veridger.application.write.WriteStatus;
import org.veridger.mcp.error.ToolError;
import org.veridger.mcp.support.BatchResults;
import org.veridger.mcp.support.Calls;
import org.veridger.mcp.support.Calls.Fields;
import org.veridger.mcp.support.Ledger;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.Scenario;
import org.veridger.mcp.tool.ReadOutputs;
import org.veridger.mcp.tool.WriteOutputs.CaseRecorded;
import org.veridger.mcp.tool.WriteOutputs.Corrected;
import org.veridger.mcp.tool.WriteOutputs.DeliverableRecorded;
import org.veridger.mcp.tool.WriteOutputs.Linked;
import org.veridger.mcp.tool.WriteOutputs.Saved;
import org.veridger.mcp.tool.WriteOutputs.Transitioned;

/**
 * MCW-025: end-to-end 2.0 universal ledger lifecycle through the MCP tools: an issue is created, replayed, edited,
 * given a deliverable (moving to RESOLVED), and independently verified; a specification is created, completed with a
 * deliverable, and independently verified; a task moves through ACTIVE, REPORTED_DONE, and VERIFIED; a milestone
 * criterion is created and evaluated MET; links, batch writes, observed cases, and history corrections are exercised.
 */
@McpServerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WriteScenarioTest {

    private static final String AUTHOR_LOGIN = "ledger_author";
    private static final String REVIEWER_LOGIN = "ledger_reviewer";

    private McpSession session;
    private Scenario scenario;
    private String operationKey;
    private long lastHistoryId;
    private String issue;
    private String spec;
    private String task;
    private String criterion;
    private String secondIssue;
    private long firstIssueHistoryId;

    @BeforeAll
    void open(McpSession openedSession, Scenario openedScenario) {
        this.session = openedSession;
        this.scenario = openedScenario;
        this.issue = scenario.issueId(1);
        this.spec = scenario.specId(1);
        this.task = scenario.taskId(1);
        this.criterion = scenario.criterionId("M1-A");
        this.secondIssue = scenario.issueId(2);
        this.operationKey = UUID.randomUUID().toString();
    }

    /** The history id of a step must be newer than that of the step before. */
    private void advance(long historyId) {
        assertThat(historyId).isGreaterThan(lastHistoryId);
        this.lastHistoryId = historyId;
    }

    private static Saved saved(String entity, String id, long version, String author) {
        return new Saved(WriteStatus.COMMITTED, entity, id, version, 0, author, AUTHOR_LOGIN);
    }

    @Test
    @Order(1)
    @DisplayName("MCW-025 step 01: the author creates an issue and gets the next <CODE>-I-NNNN id")
    void createIssue() {
        Saved result = session.ok("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "first issue", Scenario.AUTHOR)
                        .withOperationKey(operationKey),
                Saved.class);

        assertThat(result).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("issue", issue, 1, Scenario.AUTHOR));
        advance(result.historyId());
        this.firstIssueHistoryId = result.historyId();
    }

    @Test
    @Order(2)
    @DisplayName("MCW-025 step 02: the same operation key again is a replay of the same result")
    void replayTheCreation() {
        Saved replay = session.ok("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "first issue", Scenario.AUTHOR)
                        .withOperationKey(operationKey),
                Saved.class);

        assertThat(replay.historyId()).isEqualTo(firstIssueHistoryId);
        assertThat(replay).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("issue", issue, 1, Scenario.AUTHOR));
    }

    @Test
    @Order(3)
    @DisplayName("MCW-025 step 03: a stale version is refused, the current version edits")
    void editWithTheCurrentVersion() {
        ToolError stale = session.failed("ledger_issue_save",
                Calls.IssueSave.editTitle(issue, 7, "edited", Scenario.AUTHOR));
        Saved edited = session.ok("ledger_issue_save",
                Calls.IssueSave.editTitle(issue, 1, "edited", Scenario.AUTHOR), Saved.class);

        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(edited).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("issue", issue, 2, Scenario.AUTHOR));
        advance(edited.historyId());
    }

    @Test
    @Order(4)
    @DisplayName("MCW-025 step 04: RESOLVED needs a deliverable; recording the deliverable moves the issue to RESOLVED")
    void recordTheDeliverable() {
        ToolError withoutDeliverable = session.failed("ledger_transition",
                Calls.Transition.of("issue", issue, 2, "RESOLVED", Scenario.AUTHOR));
        DeliverableRecorded deliverable = session.ok("ledger_record_deliverable",
                Calls.RecordDeliverable.ofIssue(issue, "rev-doc-01", Scenario.AUTHOR),
                DeliverableRecorded.class);

        assertThat(withoutDeliverable.code()).isEqualTo("DELIVERABLE_REQUIRED");
        assertThat(deliverable).usingRecursiveComparison().ignoringFields("historyId", "deliverableId", "transitionHistoryId")
                .isEqualTo(new DeliverableRecorded(WriteStatus.COMMITTED, "issue", issue, 0, "RESOLVED",
                        Scenario.AUTHOR, 3, 0, 0L, Scenario.AUTHOR, AUTHOR_LOGIN));
        advance(deliverable.historyId());
    }

    @Test
    @Order(5)
    @DisplayName("MCW-025 step 05: the author session cannot self-verify, and a session without the grant cannot verify")
    void theAuthorAndTheUngrantedCannotVerify() {
        ToolError author = session.failed("ledger_review",
                Calls.Review.of("issue", issue, 3, "VERIFIED", Scenario.AUTHOR));
        ToolError ungranted = session.failed("ledger_review",
                Calls.Review.of("issue", issue, 3, "VERIFIED", Scenario.NOGRANT));

        assertThat(author.code()).isEqualTo("SAME_SESSION");
        assertThat(ungranted.code()).isEqualTo("SESSION_NOT_GRANTED");
    }

    @Test
    @Order(6)
    @DisplayName("MCW-025 step 06: the reviewer verifies the issue through ledger_review")
    void theReviewerVerifiesTheIssue() {
        Transitioned verified = session.ok("ledger_review",
                Calls.Review.of("issue", issue, 3, "VERIFIED", Scenario.REVIEWER), Transitioned.class);

        assertThat(verified).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new Transitioned(WriteStatus.COMMITTED, "issue", issue, "RESOLVED", "VERIFIED", 4, 0,
                        Scenario.REVIEWER, REVIEWER_LOGIN));
        advance(verified.historyId());
    }

    @Test
    @Order(7)
    @DisplayName("MCW-025 step 07: a specification is created, completed with a deliverable, refused self-review, and verified by an independent reviewer")
    void theSpecRunsThroughCompletionAndReview() {
        Saved created = session.ok("ledger_spec_save",
                Calls.SpecSave.create(scenario.projectId(), "scene 03 lighting spec", Scenario.AUTHOR),
                Saved.class);
        ToolError withoutEvidence = session.failed("ledger_transition",
                Calls.Transition.of("spec", spec, 1, "COMPLETED", Scenario.AUTHOR));
        DeliverableRecorded completed = session.ok("ledger_record_deliverable",
                Calls.RecordDeliverable.ofSpec(spec, "cut-s03-v2", Scenario.AUTHOR),
                DeliverableRecorded.class);
        ToolError selfReview = session.failed("ledger_review",
                Calls.Review.of("spec", spec, 2, "VERIFIED", Scenario.AUTHOR));
        Transitioned verified = session.ok("ledger_review",
                Calls.Review.of("spec", spec, 2, "VERIFIED", Scenario.REVIEWER), Transitioned.class);

        assertThat(created).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("spec", spec, 1, Scenario.AUTHOR));
        assertThat(withoutEvidence.code()).isEqualTo("EVIDENCE_REQUIRED");
        assertThat(completed.targetState()).isEqualTo("COMPLETED");
        assertThat(selfReview.code()).isEqualTo("SAME_SESSION");
        assertThat(verified).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new Transitioned(WriteStatus.COMMITTED, "spec", spec, "COMPLETED", "VERIFIED", 3, 0,
                        Scenario.REVIEWER, REVIEWER_LOGIN));
        advance(verified.historyId());
    }

    @Test
    @Order(8)
    @DisplayName("MCW-025 step 08: a task is created, moved to ACTIVE and COMPLETED, and verified by the reviewer")
    void theTaskRunsThroughItsStates() {
        Saved created = session.ok("ledger_task_save",
                Calls.TaskSave.create(scenario.projectId(), "first task", Scenario.AUTHOR),
                Saved.class);
        Transitioned active = session.ok("ledger_transition",
                Calls.Transition.of("task", task, 1, "ACTIVE", Scenario.AUTHOR), Transitioned.class);
        Transitioned done = session.ok("ledger_transition",
                Calls.Transition.of("task", task, 2, "COMPLETED", Scenario.AUTHOR)
                        .withEvidence(Scenario.EVIDENCE), Transitioned.class);
        Transitioned verified = session.ok("ledger_review",
                Calls.Review.of("task", task, 3, "VERIFIED", Scenario.REVIEWER), Transitioned.class);

        assertThat(created).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("task", task, 1, Scenario.AUTHOR));
        assertThat(List.of(active, done, verified)).usingRecursiveFieldByFieldElementComparatorIgnoringFields(
                "historyId").containsExactly(
                        new Transitioned(WriteStatus.COMMITTED, "task", task, "PLANNED", "ACTIVE", 2, 0,
                                Scenario.AUTHOR, AUTHOR_LOGIN),
                        new Transitioned(WriteStatus.COMMITTED, "task", task, "ACTIVE", "COMPLETED", 3, 0,
                                Scenario.AUTHOR, AUTHOR_LOGIN),
                        new Transitioned(WriteStatus.COMMITTED, "task", task, "COMPLETED", "VERIFIED", 4, 0,
                                Scenario.REVIEWER, REVIEWER_LOGIN));
        advance(verified.historyId());
    }

    @Test
    @Order(9)
    @DisplayName("MCW-025 step 09: a milestone criterion is created and evaluated MET")
    void aCriterionIsMet() {
        Saved created = session.ok("ledger_criterion_save",
                Calls.CriterionSave.create(scenario.projectId(), criterion, "M1", "A", Scenario.AUTHOR),
                Saved.class);
        Transitioned met = session.ok("ledger_review",
                Calls.Review.of("criterion", criterion, 1, "MET", Scenario.REVIEWER), Transitioned.class);

        assertThat(created).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("criterion", criterion, 1, Scenario.AUTHOR));
        assertThat(met).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new Transitioned(WriteStatus.COMMITTED, "criterion", criterion, "UNKNOWN", "MET", 2, 0,
                        Scenario.REVIEWER, REVIEWER_LOGIN));
        advance(met.historyId());
    }

    @Test
    @Order(10)
    @DisplayName("MCW-025 step 10: the issue, spec, and task are linked, and a duplicate link is refused")
    void entitiesAreLinked() {
        Linked issueTask = session.ok("ledger_link", Calls.Link.of("issue_task", issue, task, Scenario.AUTHOR),
                Linked.class);
        Linked specTask = session.ok("ledger_link", Calls.Link.of("spec_task", spec, task, Scenario.AUTHOR),
                Linked.class);
        ToolError again = session.failed("ledger_link", Calls.Link.of("issue_task", issue, task, Scenario.AUTHOR));

        assertThat(issueTask).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new Linked(WriteStatus.COMMITTED, "issue_task", issue + "|" + task, false, 0,
                        Scenario.AUTHOR, AUTHOR_LOGIN));
        assertThat(specTask.entity()).isEqualTo("spec_task");
        assertThat(again.code()).isEqualTo("NOTHING_CHANGES");
        advance(specTask.historyId());
    }

    @Test
    @Order(11)
    @DisplayName("MCW-025 step 11: a batch saves an issue and links it to the task in one transaction")
    void aBatchWritesTwoRows() {
        BatchResults batch = session.ok("ledger_batch", Calls.Batch.of(Scenario.AUTHOR,
                Calls.Op.save("issue", Fields.issue(scenario.projectId(), "second issue")),
                Calls.Op.link("issue_task", secondIssue, task)), BatchResults.class);

        assertThat(batch.status()).isEqualTo(WriteStatus.COMMITTED);
        assertThat(batch.count()).isEqualTo(2);
        assertThat(batch.results()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("historyId")
                .containsExactly(
                        new BatchResults.Entry(WriteStatus.COMMITTED, "issue", secondIssue, 1L, 0L,
                                Scenario.AUTHOR, AUTHOR_LOGIN, null, null, null, null, null, null, null, null, null,
                                null, null, null),
                        new BatchResults.Entry(WriteStatus.COMMITTED, "issue_task", secondIssue + "|" + task, null, 0L,
                                Scenario.AUTHOR, AUTHOR_LOGIN, null, null, null, null, null, null, null, null, null,
                                null, false, null));
        advance(batch.results().getLast().historyId());
    }

    @Test
    @Order(12)
    @DisplayName("MCW-025 step 12: an observed case is recorded under the issue")
    void aCaseIsRecorded() {
        CaseRecorded recorded = session.ok("ledger_record_case",
                Calls.RecordCase.of(secondIssue, "seen once more", Scenario.AUTHOR), CaseRecorded.class);

        assertThat(recorded).usingRecursiveComparison().ignoringFields("historyId", "caseId")
                .isEqualTo(new CaseRecorded(WriteStatus.COMMITTED, "issue_case", secondIssue + "|" + recorded.caseId(),
                        0, secondIssue, 0, Scenario.AUTHOR, AUTHOR_LOGIN));
        advance(recorded.historyId());
    }

    @Test
    @Order(13)
    @DisplayName("MCW-025 step 13: a history row is marked wrong with a CORRECT row")
    void aHistoryRowIsCorrected() {
        Corrected corrected = session.ok("ledger_correct", Calls.Correct.of(firstIssueHistoryId, Scenario.AUTHOR),
                Corrected.class);

        assertThat(corrected).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new Corrected(WriteStatus.COMMITTED, firstIssueHistoryId, 0, Scenario.AUTHOR,
                        AUTHOR_LOGIN));
        advance(corrected.historyId());
    }

    @Test
    @Order(14)
    @DisplayName("MCW-025 step 14: the reader sees the verified issue, verified spec, verified task, met criterion, and history")
    void theReaderSeesTheResult() {
        ReadOutputs.Issue readIssue = session.ok("ledger_issue", new Calls.IdOnly(issue), ReadOutputs.Issue.class);
        ReadOutputs.Spec readSpec = session.ok("ledger_spec", new Calls.IdOnly(spec), ReadOutputs.Spec.class);
        ReadOutputs.Got got = Ledger.get(session, issue, spec, task, criterion);

        assertThat(readIssue.id()).isEqualTo(issue);
        assertThat(readIssue.status()).isEqualTo("VERIFIED");
        assertThat(readIssue.version()).isEqualTo(4);
        assertThat(readIssue.createdBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(readIssue.resolvedBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(readIssue.title()).isEqualTo("edited");
        assertThat(readSpec.id()).isEqualTo(spec);
        assertThat(readSpec.status()).isEqualTo("VERIFIED");
        assertThat(readSpec.version()).isEqualTo(3);
        assertThat(readSpec.completedBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(got.items()).extracting(ReadOutputs.GotItem::state)
                .containsExactlyInAnyOrder("VERIFIED", "VERIFIED", "VERIFIED", "MET");
        assertThat(Ledger.history(session, scenario.projectId())).extracting(ReadOutputs.History::authorId)
                .doesNotContain(Scenario.NOGRANT);
    }
    @Test
    @Order(15)
    void mcpSeparatelyEnforcesIssueAndSpecificationCreationAndEditing() {
        String creator = scenario.actorWith("creator", "issue_create", "spec_create");
        String editor = scenario.actorWith("editor", "issue_update", "spec_update");
        Saved createdIssue = session.ok("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "new issue", creator), Saved.class);
        Saved createdSpec = session.ok("ledger_spec_save",
                Calls.SpecSave.create(scenario.projectId(), "new spec", creator), Saved.class);

        assertThat(session.failed("ledger_issue_save",
                Calls.IssueSave.editTitle(createdIssue.id(), 1, "blocked issue edit", creator)).code())
                .isEqualTo("SESSION_NOT_GRANTED");
        assertThat(session.failed("ledger_spec_save",
                Calls.SpecSave.editTitle(createdSpec.id(), 1, "blocked spec edit", creator)).code())
                .isEqualTo("SESSION_NOT_GRANTED");
        assertThat(session.failed("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "blocked issue creation", editor)).code())
                .isEqualTo("SESSION_NOT_GRANTED");
        assertThat(session.failed("ledger_spec_save",
                Calls.SpecSave.create(scenario.projectId(), "blocked spec creation", editor)).code())
                .isEqualTo("SESSION_NOT_GRANTED");

        assertThat(session.ok("ledger_issue_save",
                Calls.IssueSave.editTitle(createdIssue.id(), 1, "edited issue", editor), Saved.class).version())
                .isEqualTo(2);
        assertThat(session.ok("ledger_spec_save",
                Calls.SpecSave.editTitle(createdSpec.id(), 1, "edited spec", editor), Saved.class).version())
                .isEqualTo(2);
    }

}
