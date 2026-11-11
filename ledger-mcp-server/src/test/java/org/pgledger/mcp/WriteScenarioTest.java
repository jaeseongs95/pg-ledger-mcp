package org.pgledger.mcp;

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
import org.pgledger.application.write.WriteStatus;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.BatchResults;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.ReadOutputs;
import org.pgledger.mcp.tool.WriteOutputs.CaseRecorded;
import org.pgledger.mcp.tool.WriteOutputs.Corrected;
import org.pgledger.mcp.tool.WriteOutputs.FixRecorded;
import org.pgledger.mcp.tool.WriteOutputs.Linked;
import org.pgledger.mcp.tool.WriteOutputs.Saved;
import org.pgledger.mcp.tool.WriteOutputs.Transitioned;

/**
 * MCW-025: the main path of the old end-to-end run, condensed, through the tools in the order of the old run: a
 * defect is created, replayed, edited, fixed, verified by the reviewer; a task goes through its states and the
 * review; a condition is met; the rows are linked; a batch writes two rows; a case is recorded; a history row is
 * corrected. The steps are test methods in a fixed order on one test instance (one scenario, the state that a step
 * hands to the next is held in the fields), so no loop runs inside a test method and every step compares its result
 * structurally. The history ids differ from run to run (one database serves all tests), so they are not compared
 * but must grow from step to step.
 */
@McpServerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WriteScenarioTest {

    private static final String AUTHOR_LOGIN = "ledger_author";
    private static final String REVIEWER_LOGIN = "ledger_reviewer";

    private McpSession session;
    private Scenario scenario;
    private String requestId;
    private long lastHistoryId;
    private String defect;
    private String task;
    private String condition;
    private String secondDefect;
    private long firstDefectHistoryId;

    @BeforeAll
    void open(McpSession openedSession, Scenario openedScenario) {
        this.session = openedSession;
        this.scenario = openedScenario;
        this.defect = scenario.defectId(1);
        this.task = scenario.taskId(1);
        this.condition = scenario.code() + "-C-REL1-A";
        this.secondDefect = scenario.defectId(2);
        this.requestId = UUID.randomUUID().toString();
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
    @DisplayName("MCW-025 step 01 (E-07): the author creates a defect and gets the next id")
    void createDefect() {
        Saved result = session.ok("ledger_save", Calls.Save.create("defect",
                Fields.defect(scenario.projectId(), "first defect"), Scenario.AUTHOR).withRequestId(requestId),
                Saved.class);

        assertThat(result).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("defect", defect, 1, Scenario.AUTHOR));
        advance(result.historyId());
        this.firstDefectHistoryId = result.historyId();
    }

    @Test
    @Order(2)
    @DisplayName("MCW-025 step 02 (E-08): the same request id again is a replay of the same result")
    void replayTheCreation() {
        Saved replay = session.ok("ledger_save", Calls.Save.create("defect",
                Fields.defect(scenario.projectId(), "first defect"), Scenario.AUTHOR).withRequestId(requestId),
                Saved.class);

        assertThat(replay.historyId()).isEqualTo(firstDefectHistoryId);
        assertThat(replay).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("defect", defect, 1, Scenario.AUTHOR));
    }

    @Test
    @Order(3)
    @DisplayName("MCW-025 step 03 (E-12, E-13): a stale version is refused, the current version edits")
    void editWithTheCurrentVersion() {
        ToolError stale = session.failed("ledger_save",
                Calls.Save.edit("defect", defect, 7, Fields.titled("edited"), Scenario.AUTHOR));
        Saved edited = session.ok("ledger_save",
                Calls.Save.edit("defect", defect, 1, Fields.titled("edited"), Scenario.AUTHOR), Saved.class);

        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(edited).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("defect", defect, 2, Scenario.AUTHOR));
        advance(edited.historyId());
    }

    @Test
    @Order(4)
    @DisplayName("MCW-025 step 04 (E-15, record_fix): SOURCE_FIXED needs a fix; recording the fix moves the defect")
    void recordTheFix() {
        ToolError withoutFix = session.failed("ledger_transition",
                Calls.Transition.of("defect", defect, 2, "SOURCE_FIXED", Scenario.AUTHOR));
        FixRecorded fix = session.ok("ledger_record_fix", Calls.RecordFix.of(defect, "a".repeat(40), Scenario.AUTHOR),
                FixRecorded.class);

        assertThat(withoutFix.code()).isEqualTo("FIX_REQUIRED");
        assertThat(fix).usingRecursiveComparison().ignoringFields("historyId", "fixId", "transitionHistoryId")
                .isEqualTo(new FixRecorded(WriteStatus.COMMITTED, defect, 0, "SOURCE_FIXED", Scenario.AUTHOR, 3, 0,
                        0L, Scenario.AUTHOR, AUTHOR_LOGIN));
        advance(fix.historyId());
    }

    @Test
    @Order(5)
    @DisplayName("MCW-025 step 05 (E-17, E-18): the fixing session cannot verify, a session without the grant cannot")
    void theFixerAndTheUngrantedCannotVerify() {
        ToolError fixer = session.failed("ledger_review",
                Calls.Review.of("defect", defect, 3, "VERIFIED", Scenario.AUTHOR));
        ToolError ungranted = session.failed("ledger_review",
                Calls.Review.of("defect", defect, 3, "VERIFIED", Scenario.NOGRANT));

        assertThat(fixer.code()).isEqualTo("SAME_SESSION");
        assertThat(ungranted.code()).isEqualTo("SESSION_NOT_GRANTED");
    }

    @Test
    @Order(6)
    @DisplayName("MCW-025 step 06 (V1): the reviewer verifies the defect through ledger_review, without CI")
    void theReviewerVerifies() {
        Transitioned verified = session.ok("ledger_review",
                Calls.Review.of("defect", defect, 3, "VERIFIED", Scenario.REVIEWER), Transitioned.class);

        assertThat(verified).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new Transitioned(WriteStatus.COMMITTED, "defect", defect, "SOURCE_FIXED", "VERIFIED", 4, 0,
                        Scenario.REVIEWER, REVIEWER_LOGIN));
        advance(verified.historyId());
    }

    @Test
    @Order(7)
    @DisplayName("MCW-025 step 07 (P2): a task is created, moved to ACTIVE and REPORTED_DONE, and verified by the "
            + "reviewer")
    void theTaskRunsThroughItsStates() {
        Saved created = session.ok("ledger_save",
                Calls.Save.create("task", Fields.task(scenario.projectId(), "first task"), Scenario.AUTHOR),
                Saved.class);
        Transitioned active = session.ok("ledger_transition",
                Calls.Transition.of("task", task, 1, "ACTIVE", Scenario.AUTHOR), Transitioned.class);
        Transitioned done = session.ok("ledger_transition",
                Calls.Transition.of("task", task, 2, "REPORTED_DONE", Scenario.AUTHOR)
                        .withEvidence(Scenario.EVIDENCE), Transitioned.class);
        Transitioned verified = session.ok("ledger_review",
                Calls.Review.of("task", task, 3, "VERIFIED", Scenario.REVIEWER), Transitioned.class);

        assertThat(created).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("task", task, 1, Scenario.AUTHOR));
        assertThat(List.of(active, done, verified)).usingRecursiveFieldByFieldElementComparatorIgnoringFields(
                "historyId").containsExactly(
                        new Transitioned(WriteStatus.COMMITTED, "task", task, "PLANNED", "ACTIVE", 2, 0,
                                Scenario.AUTHOR, AUTHOR_LOGIN),
                        new Transitioned(WriteStatus.COMMITTED, "task", task, "ACTIVE", "REPORTED_DONE", 3, 0,
                                Scenario.AUTHOR, AUTHOR_LOGIN),
                        new Transitioned(WriteStatus.COMMITTED, "task", task, "REPORTED_DONE", "VERIFIED", 4, 0,
                                Scenario.REVIEWER, REVIEWER_LOGIN));
        advance(verified.historyId());
    }

    @Test
    @Order(8)
    @DisplayName("MCW-025 step 08 (P2): a release condition is created and met")
    void aConditionIsMet() {
        Saved created = session.ok("ledger_save", Calls.Save
                .create("condition", Fields.condition(scenario.projectId(), "REL1", "A"), Scenario.AUTHOR)
                .withId(condition), Saved.class);
        Transitioned met = session.ok("ledger_review",
                Calls.Review.of("condition", condition, 1, "MET", Scenario.REVIEWER), Transitioned.class);

        assertThat(created).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(saved("condition", condition, 1, Scenario.AUTHOR));
        assertThat(met).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new Transitioned(WriteStatus.COMMITTED, "condition", condition, "UNKNOWN", "MET", 2, 0,
                        Scenario.REVIEWER, REVIEWER_LOGIN));
        advance(met.historyId());
    }

    @Test
    @Order(9)
    @DisplayName("MCW-025 step 09 (E-19): the defect and the task are linked, a second link is refused")
    void theDefectAndTheTaskAreLinked() {
        Linked link = session.ok("ledger_link", Calls.Link.of("defect_task", defect, task, Scenario.AUTHOR),
                Linked.class);
        ToolError again = session.failed("ledger_link", Calls.Link.of("defect_task", defect, task, Scenario.AUTHOR));

        assertThat(link).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new Linked(WriteStatus.COMMITTED, "defect_task", defect + "|" + task, false, 0,
                        Scenario.AUTHOR, AUTHOR_LOGIN));
        assertThat(again.code()).isEqualTo("NOTHING_CHANGES");
        advance(link.historyId());
    }

    @Test
    @Order(10)
    @DisplayName("MCW-025 step 10 (E-21, P2): a batch saves a defect and links it to the task in one transaction")
    void aBatchWritesTwoRows() {
        BatchResults batch = session.ok("ledger_batch", Calls.Batch.of(Scenario.AUTHOR,
                Calls.Op.save("defect", Fields.defect(scenario.projectId(), "second defect")),
                Calls.Op.link("defect_task", secondDefect, task)), BatchResults.class);

        assertThat(batch.status()).isEqualTo(WriteStatus.COMMITTED);
        assertThat(batch.count()).isEqualTo(2);
        assertThat(batch.results()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("historyId")
                .containsExactly(
                        new BatchResults.Entry(WriteStatus.COMMITTED, "defect", secondDefect, 1L, 0L,
                                Scenario.AUTHOR, AUTHOR_LOGIN, null, null, null, null, null, null, null, null, null,
                                null),
                        new BatchResults.Entry(WriteStatus.COMMITTED, "defect_task", secondDefect + "|" + task, null, 0L,
                                Scenario.AUTHOR, AUTHOR_LOGIN, null, null, null, null, null, null, null, null,
                                false, null));
        advance(batch.results().getLast().historyId());
    }

    @Test
    @Order(11)
    @DisplayName("MCW-025 step 11 (C1): an observed case is recorded under the defect")
    void aCaseIsRecorded() {
        CaseRecorded recorded = session.ok("ledger_record_case",
                Calls.RecordCase.of(secondDefect, "seen once more", Scenario.AUTHOR), CaseRecorded.class);

        assertThat(recorded).usingRecursiveComparison().ignoringFields("historyId", "caseId")
                .isEqualTo(new CaseRecorded(WriteStatus.COMMITTED, "defect_case", secondDefect + "|" + recorded.caseId(),
                        0, secondDefect, 0, Scenario.AUTHOR, AUTHOR_LOGIN));
        advance(recorded.historyId());
    }

    @Test
    @Order(12)
    @DisplayName("MCW-025 step 12 (E-20): a history row is marked wrong with a CORRECT row")
    void aHistoryRowIsCorrected() {
        Corrected corrected = session.ok("ledger_correct", Calls.Correct.of(firstDefectHistoryId, Scenario.AUTHOR),
                Corrected.class);

        assertThat(corrected).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new Corrected(WriteStatus.COMMITTED, firstDefectHistoryId, 0, Scenario.AUTHOR,
                        AUTHOR_LOGIN));
        advance(corrected.historyId());
    }

    @Test
    @Order(13)
    @DisplayName("MCW-025 step 13 (E-29, E-30, P5): the reader sees the verified defect, its fixer and the history")
    void theReaderSeesTheResult() {
        ReadOutputs.Defect read = session.ok("ledger_defect", new Calls.IdOnly(defect), ReadOutputs.Defect.class);
        ReadOutputs.Got got = Ledger.get(session, defect, task, condition);

        assertThat(read).usingRecursiveComparison().comparingOnlyFields("id", "status", "version", "createdBy",
                "fixedBy", "title").isEqualTo(new ReadOutputs.Defect(defect, scenario.projectId(), "edited", "ARC",
                        "P2", "VERIFIED", null, null, null, null, 4, null, Scenario.AUTHOR, Scenario.AUTHOR, null, null,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        assertThat(got.items()).extracting(ReadOutputs.GotItem::state)
                .containsExactlyInAnyOrder("VERIFIED", "VERIFIED", "MET");
        assertThat(Ledger.history(session, scenario.projectId())).extracting(ReadOutputs.History::authorId)
                .doesNotContain(Scenario.NOGRANT);
    }
}
