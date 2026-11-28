// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.application.history.Change;
import org.veridger.application.history.HistoryKey;
import org.veridger.application.json.LedgerJson;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.MergeFixture;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.WriteResult.RecordDeliverableResult;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.database.repository.DeliverableRepository;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.rule.CheckResult;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.CodeValue;
import tools.jackson.databind.json.JsonMapper;

/**
 * Universal deliverable recording and check amendments across issues, specifications, and tasks.
 */
@LedgerServiceTest
class DeliverableTest {

    private static RecordDeliverableCommand cmd(String targetEntity, String targetId, String ref, String checkRef,
            String checkResult, String checkedAt, Boolean mark) {
        return new RecordDeliverableCommand(Scenario.uuid(), targetEntity, targetId, "code_change", ref,
                Scenario.REASON, "repo/e2e", "Deliverable summary", checkRef, checkResult, checkedAt, mark);
    }

    private RecordDeliverableResult record(Scenario s, String actor, RecordDeliverableCommand command) {
        return s.bean(RecordDeliverableService.class).recordDeliverable(actor, command);
    }

    @Test
    @DisplayName("DLV-01: recording a deliverable on an OPEN issue moves it to RESOLVED and assigns owner")
    void dlv01_firstIssueDeliverable(Scenario s) {
        SaveResult i = s.newIssue("i");
        RecordDeliverableResult result = record(s, Scenario.AUTHOR,
                cmd("issue", i.id(), Scenario.ref('a'), null, null, null, null));
        assertThat(result.targetState()).isEqualTo("RESOLVED");
        assertThat(result.ownerActorId()).isEqualTo(Scenario.AUTHOR);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.transitionHistoryId()).isNotNull().isGreaterThan(result.historyId());
        assertThat(s.issue(i.id()).status()).isEqualTo(IssueStatus.RESOLVED);
        Map<?, ?> keys = new JsonMapper().readValue(s.bean(LedgerJson.class).write(result).text(), Map.class);
        assertThat(keys.keySet()).isEqualTo(Set.of("status", "target_entity", "target_id", "deliverable_id",
                "target_state", "owner_actor_id", "version", "history_id", "transition_history_id", "author",
                "login"));
    }

    @ParameterizedTest
    @EnumSource(DeliverableTarget.class)
    void amendingCheckMetadataDoesNotReportCompletion(DeliverableTarget kind, Scenario s) {
        SaveResult target = switch (kind) {
            case ISSUE -> s.newIssue("issue");
            case SPEC -> s.newSpec("spec");
            case TASK -> s.newTask("task");
        };
        String entity = kind.token();
        String creator = s.actorWith("deliverable_create");
        String updater = s.actorWith("deliverable_update");
        var reference = Scenario.ref('a');
        var added = record(s, creator, cmd(entity, target.id(), reference, "check", "failed", null, false));
        var amended = record(s, updater, cmd(entity, target.id(), reference, "check", "passed", null, true));
        assertThat(amended.deliverableId()).isEqualTo(added.deliverableId());
        assertThat(amended.targetState()).isEqualTo(added.targetState());
        assertThat(amended.transitionHistoryId()).isNull();
    }

    @Test
    @DisplayName("DLV-02: check_result must be passed, failed, or cancelled, and requires check_ref")
    void dlv02_checkResultValidation(Scenario s) {
        SaveResult i = s.newIssue("i");
        ValidationException e = (ValidationException) Expect.failure(() -> record(s, Scenario.AUTHOR,
                cmd("issue", i.id(), Scenario.ref('a'), "check-1", "green", null, null)),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).containsExactly("passed", "failed", "cancelled");

        for (String blank : new String[] {"", "   "}) {
            Expect.failure(() -> record(s, Scenario.AUTHOR,
                    cmd("issue", i.id(), Scenario.ref('a'), blank, "passed", null, null)),
                    ValidationCode.VALIDATION_FAILED);
        }
    }

    @Test
    @DisplayName("DLV-03: unknown DELIVERABLE_KIND code is refused with CODE_INVALID")
    void dlv03_unknownDeliverableKind(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> record(s, Scenario.AUTHOR, new RecordDeliverableCommand(Scenario.uuid(), "issue", i.id(),
                "UNKNOWN_KIND", "ref-1", Scenario.REASON, null, "Summary", null, null, null, null)),
                ValidationCode.CODE_INVALID);
    }

    @Test
    @DisplayName("DLV-04: second deliverable by another actor updates owner while keeping RESOLVED")
    void dlv04_ownerChangesOnSecondDeliverable(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        RecordDeliverableResult second = record(s, Scenario.REVIEWER,
                cmd("issue", i.id(), Scenario.ref('b'), null, null, null, null));
        assertThat(second.version()).isEqualTo(3);
        assertThat(second.targetState()).isEqualTo("RESOLVED");
        assertThat(second.ownerActorId()).isEqualTo(Scenario.REVIEWER);
        HistoryEntry edit = s.historyRow(second.transitionHistoryId());
        assertThat(edit.op()).isEqualTo(HistoryOp.EDIT);
        assertThat(s.changes(edit.id()).entries()).containsExactly(Map.entry("owner_actor_id",
                new Change(Scenario.AUTHOR, Scenario.REVIEWER)));
    }

    @Test
    @DisplayName("DLV-05: duplicate deliverable without check_ref is DELIVERABLE_ALREADY_RECORDED; amending check facts writes EDIT and blocks amender from self-verifying (D-0029)")
    void dlv05_amendAndSelfVerifyGuard(Scenario s) {
        SaveResult i = s.newIssue("i");
        record(s, Scenario.AUTHOR, cmd("issue", i.id(), Scenario.ref('a'), "chk-1", "failed", null, null));
        Expect.failure(() -> record(s, Scenario.AUTHOR,
                cmd("issue", i.id(), Scenario.ref('a'), null, null, null, null)),
                TransitionCode.DELIVERABLE_ALREADY_RECORDED);

        String amender = s.actorWith("deliverable", "verify");
        RecordDeliverableResult update = record(s, amender, cmd("issue", i.id(), Scenario.ref('a'), "chk-1",
                "passed", "2026-11-05T09:00:00Z", null));
        HistoryEntry row = s.historyRow(update.historyId());
        assertThat(row.entity()).isEqualTo(HistoryEntity.DELIVERABLE);
        assertThat(row.op()).isEqualTo(HistoryOp.EDIT);
        assertThat(s.changes(row.id()).entries()).containsOnlyKeys("check_result", "checked_at");
        assertThat(update.transitionHistoryId()).isNull();
        assertThat(update.version()).isEqualTo(3);

        // Identical check facts change nothing
        Expect.failure(() -> record(s, amender, cmd("issue", i.id(), Scenario.ref('a'), "chk-1", "passed",
                "2026-11-05T09:00:00Z", null)), TransitionCode.NOTHING_CHANGES);

        // D-0029: amender cannot self-verify
        Expect.failure(() -> s.bean(ReviewService.class).review(amender,
                s.move("issue", i.id(), 3, "VERIFIED", Scenario.EVIDENCE)), TransitionCode.SAME_SESSION);
    }

    @Test
    @DisplayName("DLV-06: mark_completed=false keeps OPEN status; task deliverable moves PLANNED task to COMPLETED")
    void dlv06_noMarkAndTaskDeliverable(Scenario s) {
        SaveResult i = s.newIssue("i");
        RecordDeliverableResult noMark = record(s, Scenario.AUTHOR,
                cmd("issue", i.id(), Scenario.ref('a'), null, null, null, false));
        assertThat(noMark.targetState()).isEqualTo("OPEN");
        assertThat(s.issue(i.id()).ownerActorId().value()).isEqualTo(Scenario.AUTHOR);

        SaveResult t = s.newTask("render reel");
        RecordDeliverableResult taskDeliv = record(s, Scenario.AUTHOR, new RecordDeliverableCommand(Scenario.uuid(),
                "task", t.id(), "media", "renders/reel-v1.mp4", Scenario.REASON, "s3://reels/v1", "Final cut", null,
                null, null, true));
        assertThat(taskDeliv.targetState()).isEqualTo("COMPLETED");
        assertThat(s.task(t.id()).state()).isEqualTo(TaskState.COMPLETED);
    }

    record CheckCase(String name, String result) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<CheckCase> checkCases() {
        return Stream.of(new CheckCase("failed", "failed"), new CheckCase("cancelled", "cancelled"),
                new CheckCase("none", null));
    }

    @ParameterizedTest
    @MethodSource("checkCases")
    @DisplayName("DLV-07: external check metadata is optional; independent reviewer can verify")
    void dlv07_checkIsOptional(CheckCase checkCase, Scenario s) {
        SaveResult i = s.newIssue("i");
        String res = checkCase.result();
        record(s, Scenario.AUTHOR, cmd("issue", i.id(), Scenario.ref('a'), res == null ? null : "chk-1", res, null,
                null));
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("issue", i.id(), 2, "VERIFIED",
                Scenario.EVIDENCE));
        assertThat(s.issue(i.id()).status()).isEqualTo(IssueStatus.VERIFIED);
        CheckResult stored = s.bean(DeliverableRepository.class)
                .findByTargetEntityAndTargetIdAndKindAndDeliverableRefAndLocationKey(DeliverableTarget.ISSUE, i.id(),
                        CodeValue.of("code_change"), Scenario.ref('a'),
                        org.veridger.domain.model.Deliverable.locationKeyOf("repo/e2e"))
                .orElseThrow().checkResult();
        assertThat(stored == null ? null : stored.token()).isEqualTo(res);
    }

    @Test
    @DisplayName("DLV-08: history, unknown target, missing grant, UTC offset normalization, and merged issue deliverable")
    void dlv08_historyAuthTimeAndMerged(Scenario s) {
        SaveResult i = s.newIssue("i");
        RecordDeliverableResult result = record(s, Scenario.AUTHOR, cmd("issue", i.id(), Scenario.ref('a'),
                "https://check.example/1", "passed", "2026-11-05T19:00:00+09:00", false));
        HistoryEntry row = s.historyRow(result.historyId());
        assertThat(row.entity()).isEqualTo(HistoryEntity.DELIVERABLE);
        assertThat(row.entityId()).isEqualTo(HistoryKey.deliverableEntityId("issue", i.id(), "code_change",
                Scenario.ref('a')));
        assertThat(row.evidenceUri()).isEqualTo("https://check.example/1");

        Expect.failure(() -> record(s, Scenario.AUTHOR, cmd("issue", s.issueId(99), Scenario.ref('a'), null, null,
                null, null)), LookupCode.NOT_FOUND);
        Expect.failure(() -> record(s, Scenario.ISSUE_ONLY, cmd("issue", i.id(), Scenario.ref('b'), null, null, null,
                null)), TrustCode.SESSION_NOT_GRANTED);

        for (String naive : new String[] {"2026-11-05T10:00:00", "2026-11-05"}) {
            Expect.failure(() -> record(s, Scenario.AUTHOR, cmd("issue", i.id(), Scenario.ref('c'), "chk", "passed",
                    naive, null)), ValidationCode.VALIDATION_FAILED);
        }

        SaveResult source = s.newIssue("source");
        SaveResult target = s.newIssue("target");
        MergeFixture.merge(s, source.id(), 1, target.id());
        RecordDeliverableResult onMerged = record(s, Scenario.AUTHOR,
                cmd("issue", source.id(), Scenario.ref('m'), null, null, null, null));
        assertThat(onMerged.targetState()).isEqualTo("MERGED");
    }

    @Test
    @DisplayName("DLV-09: same deliverable_ref at distinct locations records separate deliverables, while same location amends")
    void dlv09_distinctLocationsAreDistinctDeliverables(Scenario s) {
        SaveResult i = s.newIssue("multi-repo");
        String sha = Scenario.ref('d');
        RecordDeliverableResult first = record(s, Scenario.AUTHOR, new RecordDeliverableCommand(Scenario.uuid(),
                "issue", i.id(), "code_change", sha, Scenario.REASON, "org/repo-old", "First repo", null, null, null,
                true));
        RecordDeliverableResult second = record(s, Scenario.AUTHOR, new RecordDeliverableCommand(Scenario.uuid(),
                "issue", i.id(), "code_change", sha, Scenario.REASON, "org/repo-new", "Renamed repo", null, null, null,
                true));
        assertThat(second.deliverableId()).isNotEqualTo(first.deliverableId());

        RecordDeliverableResult amended = record(s, Scenario.AUTHOR, new RecordDeliverableCommand(Scenario.uuid(),
                "issue", i.id(), "code_change", sha, Scenario.REASON, "org/repo-new", "Renamed repo", "ci/1", "passed",
                null, true));
        assertThat(amended.deliverableId()).isEqualTo(second.deliverableId());
        assertThat(s.bean(DeliverableRepository.class).findByTargetEntityAndTargetIdOrderById(DeliverableTarget.ISSUE,
                i.id())).hasSize(2);
    }
}
