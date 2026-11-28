// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.application.history.Snapshot;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Fields;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchResult;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.SaveCommand;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.WriteResult.LinkResult;
import org.veridger.application.write.WriteResult.ProjectVersionResult;
import org.veridger.application.write.WriteResult.RecordCaseResult;
import org.veridger.application.write.WriteResult.RecordDeliverableResult;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ErrorCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.value.OperationKey;

/** SVC-BAT-01, -02, -04 .. -12: batch execution, derived keys, receipts, and per-operation gate checks. */
@LedgerServiceTest
class BatchTest {

    private BatchOpInput.Save saveIssue(Scenario s, String title) {
        return new BatchOpInput.Save("issue", Fields.of(s.issueFields(title)), Scenario.REASON, null, null);
    }

    private BatchResult batch(Scenario s, String actor, String operationKey, List<BatchOpInput> ops) {
        return s.bean(BatchService.class).batch(actor, operationKey, ops);
    }

    @Test
    void versionCreationMetadataEditsAndItemLinksUseTheirOwnPermissions(Scenario s) {
        String creator = s.actorWith("version_create");
        String editor = s.actorWith("version_update");
        String linker = s.actorWith("spec_update");
        String spec = s.newSpec("versioned specification").id();
        var create = new BatchOpInput.ProjectVersion(s.projectId(), "1.0.0", Scenario.REASON, true, null,
                "First version", null, null, null, null);
        var edit = new BatchOpInput.ProjectVersion(s.projectId(), "1.0.0", Scenario.REASON, false, null,
                "Updated description", null, null, null, null);
        var link = new BatchOpInput.ProjectVersion(s.projectId(), "1.0.0", Scenario.REASON, false, null, null,
                null, null, new BatchOpInput.VersionItems(null, List.of(spec), null, null), null);

        Expect.failure(() -> batch(s, editor, Scenario.uuid(), List.of(create)), TrustCode.SESSION_NOT_GRANTED);
        assertThat(batch(s, creator, Scenario.uuid(), List.of(create)).results().getFirst())
                .isInstanceOfSatisfying(ProjectVersionResult.class, result -> assertThat(result.created()).isTrue());
        Expect.failure(() -> batch(s, creator, Scenario.uuid(), List.of(edit)), TrustCode.SESSION_NOT_GRANTED);
        assertThat(batch(s, editor, Scenario.uuid(), List.of(edit)).count()).isEqualTo(1);
        Expect.failure(() -> batch(s, editor, Scenario.uuid(), List.of(link)), TrustCode.SESSION_NOT_GRANTED);
        assertThat(batch(s, linker, Scenario.uuid(), List.of(link)).results().getFirst())
                .isInstanceOfSatisfying(ProjectVersionResult.class, result -> assertThat(result.linked()).isEqualTo(1));
    }

    @Test
    @DisplayName("SVC-BAT-01/04 & OPK-026: sequence visibility, missing grant, and null op validation")
    void bat01_04_opk026_sequenceGrantAndNullOp(Scenario s) {
        s.newIssue("one");
        s.newIssue("two");
        BatchResult result = batch(s, Scenario.AUTHOR, Scenario.uuid(), List.of(saveIssue(s, "three"),
                saveIssue(s, "four"),
                new BatchOpInput.Link("same_family", s.issueId(3), s.issueId(4), Scenario.REASON, null)));
        assertThat(result.count()).isEqualTo(3);
        assertThat(((SaveResult) result.results().get(0)).id()).isEqualTo(s.issueId(3));
        assertThat(((SaveResult) result.results().get(1)).id()).isEqualTo(s.issueId(4));
        assertThat(((LinkResult) result.results().get(2)).entity()).isEqualTo("issue_relation");

        Expect.failure(() -> batch(s, Scenario.NOGRANT, Scenario.uuid(), List.of(saveIssue(s, "x"))),
                TrustCode.SESSION_NOT_GRANTED);

        for (List<BatchOpInput> ops : List.of(Arrays.asList((BatchOpInput) null),
                Arrays.<BatchOpInput>asList(saveIssue(s, "first"), null))) {
            String key = Scenario.uuid();
            long receipts = s.count("SELECT COUNT(*) FROM request_receipt");
            ValidationException refused = (ValidationException) Expect.failure(
                    () -> batch(s, Scenario.AUTHOR, key, ops), ValidationCode.VALIDATION_FAILED);
            assertThat(refused.issues().getFirst().path()).isEqualTo("ops[" + ops.indexOf(null) + "]");
            assertThat(refused.issues().getFirst().reason()).isEqualTo(ValidationReason.REQUIRED);
            assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
        }
    }

    @Test
    @DisplayName("SVC-BAT-06: missing required key in later operation rolls earlier operations back")
    void bat06_missingKeyRollback(Scenario s) {
        SaveResult i = s.newIssue("i");
        assertRefused(s, List.of(new BatchOpInput.Transition("issue", i.id(), null, "DISPUTED", Scenario.REASON, null,
                null, null, null)), "ops[0].expected_version");
        BatchOpInput noReason = new BatchOpInput.Save("issue", Fields.of(s.issueFields("x")), null, null, null);
        assertRefused(s, List.of(saveIssue(s, "x"), noReason), "ops[1].reason");
    }

    private void assertRefused(Scenario s, List<BatchOpInput> ops, String path) {
        long count = s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId());
        String batchKey = Scenario.uuid();
        ValidationException e = (ValidationException) Expect.failure(() -> s.bean(BatchService.class)
                .batch(Scenario.AUTHOR, batchKey, ops), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).startsWith(path);
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isEqualTo(count);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", batchKey)).isZero();
    }

    record Size(int count, ErrorCode expected) {
        @Override
        public String toString() {
            return count + " operations";
        }
    }

    static Stream<Size> sizes() {
        return Stream.of(new Size(0, ValidationCode.VALIDATION_FAILED), new Size(1, null), new Size(200, null),
                new Size(201, ValidationCode.VALIDATION_FAILED));
    }

    @ParameterizedTest
    @MethodSource("sizes")
    @DisplayName("SVC-BAT-07: 1 to 200 operations")
    void bat07_sizes(Size size, Scenario s) {
        List<BatchOpInput> ops = IntStream.range(0, size.count()).<BatchOpInput>mapToObj(i -> saveIssue(s, "x"))
                .toList();
        assertThat(Expect.outcome(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), ops)))
                .isEqualTo(Optional.ofNullable(size.expected()));
    }

    @Test
    @DisplayName("SVC-BAT-08 & OPK-005/019: derived operation keys, batch replay, and operation conflicts")
    void bat08_opk005_019_derivedKeysReplayAndConflicts(Scenario s) {
        String batchId = Scenario.uuid();
        OperationKey batchKey = OperationKey.parse(batchId);
        List<BatchOpInput> ops = List.of(saveIssue(s, "zero"), saveIssue(s, "one"));
        BatchResult first = batch(s, Scenario.AUTHOR, batchId, ops);

        assertThat(batch(s, Scenario.AUTHOR, batchId, ops)).isEqualTo(first);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ? AND operation = 'BATCH'",
                batchId)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?)",
                batchKey.derive(0).toString(), batchKey.derive(1).toString())).isEqualTo(2);

        for (int i = 0; i < 2; i++) {
            String title = i == 0 ? "zero" : "one";
            SaveResult byDerivedKey = s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(
                    batchKey.derive(i).toString(), "issue", null, 0L, Fields.of(s.issueFields(title)),
                    Scenario.REASON));
            assertThat(byDerivedKey).isEqualTo(first.results().get(i));
        }

        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(
                batchKey.derive(0).toString(), "issue", null, 0L, Fields.of(s.issueFields("other")),
                Scenario.REASON)), ConflictCode.OPERATION_CONFLICT);
        Expect.failure(() -> batch(s, Scenario.AUTHOR, batchId, List.of(saveIssue(s, "zero"))),
                ConflictCode.OPERATION_CONFLICT);

        String saveKey = Scenario.uuid();
        s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(saveKey, "issue", null, 0L,
                Fields.of(s.issueFields("a")), Scenario.REASON));
        Expect.failure(() -> batch(s, Scenario.AUTHOR, saveKey, List.of(saveIssue(s, "a"))),
                ConflictCode.OPERATION_CONFLICT);
    }

    @Test
    void aBatchRollsBackCreationIfASeparateEditScopeIsMissing(Scenario s) {
        String creator = s.actorWith("spec_create");
        String specId = s.specId(1);
        var create = new BatchOpInput.Save("spec", Fields.of(s.specFields("Draft")), Scenario.REASON, specId, null);
        var edit = new BatchOpInput.Save("spec", Fields.of(java.util.Map.of("title", "Edited")),
                Scenario.REASON, specId, 1L);

        LedgerException error = Expect.failure(() -> batch(s, creator, Scenario.uuid(), List.of(create, edit)),
                TrustCode.SESSION_NOT_GRANTED);
        assertThat(error.details()).containsEntry("op_index", List.of("2"))
                .containsEntry("scope", List.of("spec_update"));
        assertThat(s.count("SELECT COUNT(*) FROM spec WHERE id = ?", specId)).isZero();
        assertThat(batch(s, creator, Scenario.uuid(), List.of(create)).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-BAT-09/10/12: multi-kind batch, per-operation gate checks, and chained edits of one row")
    void bat09_10_12_multiKindGatesAndChainedEdits(Scenario s) {
        SaveResult i = s.newIssue("i");
        String sp1 = s.specId(1);
        String t1 = s.taskId(1);
        String c1 = s.criterionId("1");
        BatchResult mixed = batch(s, Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Save("spec", Fields.of(s.specFields("spec one")), Scenario.REASON, sp1, null),
                new BatchOpInput.Save("task", Fields.of(s.taskFields("task one")), Scenario.REASON, t1, null),
                new BatchOpInput.Save("criterion", Fields.of(s.criterionFields("m1", "one")), Scenario.REASON, c1,
                        null),
                new BatchOpInput.Link("issue_task", i.id(), t1, Scenario.REASON, null),
                new BatchOpInput.Link("issue_spec", i.id(), sp1, Scenario.REASON, null),
                new BatchOpInput.Link("spec_task", sp1, t1, Scenario.REASON, null),
                new BatchOpInput.Link("criterion_task", c1, t1, Scenario.REASON, null),
                new BatchOpInput.Link("criterion_issue", c1, i.id(), Scenario.REASON, null)));
        assertThat(mixed.count()).isEqualTo(8);

        List<BatchOpInput> gatedOps = List.of(
                new BatchOpInput.RecordDeliverable("issue", i.id(), "code_change", Scenario.ref('a'), Scenario.REASON,
                        "repo/e2e", "Deliverable summary", null, null, null, null, null),
                new BatchOpInput.RecordCase(i.id(), "seen", Scenario.REASON, null, null, null, null),
                new BatchOpInput.Correct(i.historyId(), Scenario.REASON, null));
        String delivOnly = s.actorWith("deliverable");
        LedgerException gateErr = Expect.failure(() -> batch(s, delivOnly, Scenario.uuid(), gatedOps),
                TrustCode.SESSION_NOT_GRANTED);
        assertThat(gateErr.details()).containsEntry("op_index", List.of("2")).containsEntry("scope", List.of("issue_update"));
        assertThat(s.count("SELECT COUNT(*) FROM deliverable WHERE target_entity = 'ISSUE' AND target_id = ?", i.id()))
                .isZero();
        String enough = s.actorWith("deliverable", "issue");
        assertThat(batch(s, enough, Scenario.uuid(), gatedOps).count()).isEqualTo(3);

        SaveResult d2 = s.newIssue("d2");
        BatchResult chained = batch(s, Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Save("issue", Fields.of("title", "x1"), Scenario.REASON, d2.id(), 1L),
                new BatchOpInput.Save("issue", Fields.of("title", "x2"), Scenario.REASON, d2.id(), 2L)));
        SaveResult second = (SaveResult) chained.results().get(1);
        assertThat(second.version()).isEqualTo(3);
        Snapshot.IssueRow before = s.before(second.historyId(), Snapshot.IssueRow.class);
        assertThat(before.title()).isEqualTo("x1");
        assertThat(before.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("R6-F1: deferred batch case/history flush resolves positive surrogate IDs, preserves unlink before_data, and replays identically")
    void r6F1_deferredFlushResolvesIdsAndReplaysIdentically(Scenario s) {
        SaveResult issue = s.newIssue("batch-flush");
        SaveResult spec = s.newSpec("batch-spec");
        String batchId = Scenario.uuid();
        OperationKey batchKey = OperationKey.parse(batchId);
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.RecordCase(issue.id(), "first case", Scenario.REASON, "ev-1", null, null, null),
                new BatchOpInput.RecordDeliverable("issue", issue.id(), "code_change", Scenario.ref('b'),
                        Scenario.REASON, "repo/batch", "batch deliverable", null, null, null, false, null),
                new BatchOpInput.Link("issue_spec", issue.id(), spec.id(), Scenario.REASON, false),
                new BatchOpInput.Link("issue_spec", issue.id(), spec.id(), Scenario.REASON, true));

        BatchResult first = batch(s, Scenario.AUTHOR, batchId, ops);
        assertThat(first.count()).isEqualTo(4);

        RecordCaseResult caseRes = (RecordCaseResult) first.results().get(0);
        RecordDeliverableResult delivRes = (RecordDeliverableResult) first.results().get(1);
        LinkResult linkRes = (LinkResult) first.results().get(2);
        LinkResult unlinkRes = (LinkResult) first.results().get(3);

        assertThat(caseRes.caseId()).isPositive();
        assertThat(caseRes.id()).isEqualTo(issue.id() + "|" + caseRes.caseId());
        assertThat(caseRes.historyId()).isPositive();
        assertThat(delivRes.historyId()).isPositive();
        assertThat(linkRes.historyId()).isPositive();
        assertThat(unlinkRes.historyId()).isPositive();
        assertThat(caseRes.historyId()).isLessThan(delivRes.historyId());
        assertThat(delivRes.historyId()).isLessThan(linkRes.historyId());
        assertThat(linkRes.historyId()).isLessThan(unlinkRes.historyId());

        Snapshot.IssueSpecRow unlinkBefore = s.before(unlinkRes.historyId(), Snapshot.IssueSpecRow.class);
        assertThat(unlinkBefore.createdBy()).isEqualTo(Scenario.AUTHOR);

        // Batch replay and derived-key single replay both return the exact resolved IDs
        assertThat(batch(s, Scenario.AUTHOR, batchId, ops)).isEqualTo(first);
        RecordCaseResult byDerivedKey = s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR,
                new RecordCaseCommand(batchKey.derive(0).toString(), issue.id(), "first case", Scenario.REASON, "ev-1",
                        null, null, null));
        assertThat(byDerivedKey).isEqualTo(caseRes);
    }
}
