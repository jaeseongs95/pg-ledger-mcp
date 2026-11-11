package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.history.Change;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.support.Dbms;
import org.pgledger.application.support.DbmsAware;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.H2ServiceTest;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.TaskState;
import tools.jackson.databind.json.JsonMapper;

/** SVC-TRN-01 .. SVC-TRN-18: transitions and review. */
@H2ServiceTest
class TransitionTest implements DbmsAware {

    @Parameter
    Dbms dbms;

    @Override
    public Dbms dbms() {
        return dbms;
    }

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
        ValidationException e = (ValidationException) Expect.failure(() -> author(s, "ticket", s.defectId(1), 1,
                "OPEN", null), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).containsExactly("defect", "task", "condition");
    }

    @Test
    @DisplayName("SVC-TRN-01: an unknown state lists the states")
    void trn01_unknownState(Scenario s) {
        SaveResult d = s.newDefect("d");
        ValidationException e = (ValidationException) Expect.failure(() -> author(s, "defect", d.id(), 1, "FIXED",
                null), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).containsExactly("OPEN", "SOURCE_FIXED", "VERIFIED", "NOT_DEFECT",
                "DISPUTED", "MERGED");
    }

    @Test
    @DisplayName("SVC-TRN-02: SOURCE_FIXED needs a recorded fix")
    void trn02_fixRequired(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> author(s, "defect", d.id(), 1, "SOURCE_FIXED", null), TransitionCode.FIX_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-03: a stale expected version")
    void trn03_versionConflict(Scenario s) {
        SaveResult d = s.newDefect("d");
        author(s, "defect", d.id(), 1, "DISPUTED", Scenario.EVIDENCE);
        Expect.failure(() -> author(s, "defect", d.id(), 1, "OPEN", Scenario.EVIDENCE),
                ConflictCode.VERSION_CONFLICT);
    }

    @Test
    @DisplayName("SVC-TRN-04: a defect already in the target state")
    void trn04_sameState(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> author(s, "defect", d.id(), 1, "OPEN", null), TransitionCode.TRANSITION_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-05: VERIFIED without evidence is refused by the service itself")
    void trn05_evidenceGuard(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        Expect.failure(() -> review(s, Scenario.REVIEWER, "defect", d.id(), 2, "VERIFIED", null),
                TransitionCode.EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-05: VERIFIED without a recorded fix is refused by the service itself")
    void trn05_fixGuard(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.ctx().jdbc().sql("UPDATE defect SET status = 'SOURCE_FIXED' WHERE id = ?").param(d.id()).update();
        Expect.failure(() -> review(s, Scenario.REVIEWER, "defect", d.id(), 1, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.FIX_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-06: the session that fixed cannot verify")
    void trn06_sameSession(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        Expect.failure(() -> review(s, Scenario.AUTHOR, "defect", d.id(), 2, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.SAME_SESSION);
    }

    @Test
    @DisplayName("SVC-TRN-07: a session without grants cannot verify")
    void trn07_noGrant(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        Expect.failure(() -> review(s, Scenario.NOGRANT, "defect", d.id(), 2, "VERIFIED", Scenario.EVIDENCE),
                TrustCode.SESSION_NOT_GRANTED);
    }

    @Test
    @DisplayName("SVC-TRN-08: the reviewer verifies, the history keeps the evidence and the reviewer login")
    void trn08_verify(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        String requestId = Scenario.uuid();
        TransitionResult result = s.bean(ReviewService.class).review(Scenario.REVIEWER, new TransitionCommand(
                requestId, "defect", d.id(), 2L, "VERIFIED", Scenario.REASON, Scenario.EVIDENCE, null, null));
        assertThat(result.from()).isEqualTo("SOURCE_FIXED");
        assertThat(result.to()).isEqualTo("VERIFIED");
        assertThat(result.version()).isEqualTo(3);
        assertThat(result.author()).isEqualTo(Scenario.REVIEWER);
        assertThat(result.login()).isEqualTo("ledger_reviewer");
        HistoryEntry row = s.historyRow(result.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.TRANSITION);
        assertThat(row.fromState()).isEqualTo("SOURCE_FIXED");
        assertThat(row.toState()).isEqualTo("VERIFIED");
        assertThat(row.evidenceUri()).isEqualTo(Scenario.EVIDENCE);
        assertThat(row.requestId().toString()).isEqualTo(requestId);
        assertThat(row.login()).isEqualTo("ledger_reviewer");
        assertThat(s.defect(d.id()).status()).isEqualTo(DefectStatus.VERIFIED);
    }

    @Test
    @DisplayName("SVC-TRN-09: leaving VERIFIED needs evidence, with it the reviewer may do it")
    void trn09_leavingVerified(Scenario s) {
        SaveResult d = s.verifiedDefect("d");
        Expect.failure(() -> review(s, Scenario.REVIEWER, "defect", d.id(), 3, "OPEN", null),
                TransitionCode.EVIDENCE_REQUIRED);
        assertThat(review(s, Scenario.REVIEWER, "defect", d.id(), 3, "OPEN", Scenario.EVIDENCE).to())
                .isEqualTo("OPEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_DEFECT", "DISPUTED"})
    @DisplayName("SVC-TRN-10: NOT_DEFECT and DISPUTED need evidence")
    void trn10_evidence(String to, Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> author(s, "defect", d.id(), 1, to, null), TransitionCode.EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-11: merging into a live defect stores the target and records it")
    void trn11_merge(Scenario s) {
        SaveResult source = s.newDefect("source");
        SaveResult target = s.newDefect("target");
        TransitionResult result = MergeFixture.merge(s, source.id(), 1, target.id());
        assertThat(s.defect(source.id()).mergedInto().value()).isEqualTo(target.id());
        assertThat(s.changes(result.historyId()).entries()).containsExactly(Map.entry("merged_into",
                new Change(null, target.id())));
    }

    @Test
    @DisplayName("SVC-TRN-11: a defect cannot be merged into itself")
    void trn11_self(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> MergeFixture.merge(s, d.id(), 1, d.id()), TransitionCode.MERGE_TARGET_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-11: a merged defect is no merge target")
    void trn11_mergedTarget(Scenario s) {
        SaveResult a = s.newDefect("a");
        SaveResult b = s.newDefect("b");
        SaveResult c = s.newDefect("c");
        MergeFixture.merge(s, b.id(), 1, a.id());
        Expect.failure(() -> MergeFixture.merge(s, c.id(), 1, b.id()), TransitionCode.MERGE_TARGET_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-11: an unknown merge target")
    void trn11_unknownTarget(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> MergeFixture.merge(s, d.id(), 1, s.defectId(77)), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-TRN-11: MERGED without a target")
    void trn11_noTarget(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> author(s, "defect", d.id(), 1, "MERGED", null), TransitionCode.MERGE_TARGET_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-12: leaving MERGED clears the target")
    void trn12_unmerge(Scenario s) {
        SaveResult source = s.newDefect("source");
        SaveResult target = s.newDefect("target");
        MergeFixture.merge(s, source.id(), 1, target.id());
        author(s, "defect", source.id(), 2, "OPEN", null);
        assertThat(s.defect(source.id()).mergedInto()).isNull();
        assertThat(s.defect(source.id()).status()).isEqualTo(DefectStatus.OPEN);
    }

    @Test
    @DisplayName("SVC-TRN-13: the task flow of reporting, verifying and sending back")
    void trn13_taskFlow(Scenario s) {
        SaveResult t = s.newTask("t");
        TransitionResult reported = author(s, "task", t.id(), 1, "REPORTED_DONE", Scenario.EVIDENCE);
        assertThat(reported.version()).isEqualTo(2);
        Expect.failure(() -> review(s, Scenario.AUTHOR, "task", t.id(), 2, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, Scenario.REVIEWER, "task", t.id(), 2, "VERIFIED", Scenario.EVIDENCE).version())
                .isEqualTo(3);
        assertThat(review(s, Scenario.REVIEWER, "task", t.id(), 3, "REPORTED_DONE", Scenario.EVIDENCE).version())
                .isEqualTo(4);
        Expect.failure(() -> review(s, Scenario.AUTHOR, "task", t.id(), 4, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, Scenario.REVIEWER, "task", t.id(), 4, "VERIFIED", Scenario.EVIDENCE).version())
                .isEqualTo(5);
        assertThat(s.task(t.id()).state()).isEqualTo(TaskState.VERIFIED);
    }

    @Test
    @DisplayName("SVC-TRN-14: the task owner cannot verify")
    void trn14_ownerCannotVerify(Scenario s) {
        var fields = s.taskFields("t");
        fields.put("owner_actor_id", Scenario.REVIEWER);
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        author(s, "task", t.id(), 1, "REPORTED_DONE", Scenario.EVIDENCE);
        Expect.failure(() -> review(s, Scenario.REVIEWER, "task", t.id(), 2, "VERIFIED", Scenario.EVIDENCE),
                TransitionCode.SAME_SESSION);
    }

    @Test
    @DisplayName("SVC-TRN-15: a task cannot return to UNKNOWN")
    void trn15_toUnknown(Scenario s) {
        SaveResult t = s.newTask("t");
        Expect.failure(() -> author(s, "task", t.id(), 1, "UNKNOWN", null), TransitionCode.TRANSITION_INVALID);
    }

    @Test
    @DisplayName("SVC-TRN-15: REPORTED_DONE needs evidence")
    void trn15_doneNeedsEvidence(Scenario s) {
        SaveResult t = s.newTask("t");
        Expect.failure(() -> author(s, "task", t.id(), 1, "REPORTED_DONE", null), TransitionCode.EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-TRN-16: MET needs evidence and the reviewer channel")
    void trn16_condition(Scenario s) {
        SaveResult c = s.newCondition("1", "r", "c");
        Expect.failure(() -> review(s, Scenario.REVIEWER, "condition", c.id(), 1, "MET", null),
                TransitionCode.EVIDENCE_REQUIRED);
        assertThat(review(s, Scenario.REVIEWER, "condition", c.id(), 1, "MET", Scenario.EVIDENCE).to())
                .isEqualTo("MET");
    }

    @Test
    @DisplayName("SVC-TRN-16: MET back to UNKNOWN needs the verify scope but no evidence (unchanged by design)")
    void trn16_backToUnknownNeedsNoEvidence(Scenario s) {
        SaveResult c = s.newCondition("1", "r", "c");
        review(s, Scenario.REVIEWER, "condition", c.id(), 1, "MET", Scenario.EVIDENCE);
        assertThat(review(s, Scenario.REVIEWER, "condition", c.id(), 2, "UNKNOWN", null).to()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("SVC-TRN-17: the result carries exactly the contract keys")
    void trn17_resultKeys(Scenario s) {
        SaveResult d = s.newDefect("d");
        TransitionResult result = author(s, "defect", d.id(), 1, "DISPUTED", Scenario.EVIDENCE);
        Map<?, ?> keys = new JsonMapper().readValue(s.bean(LedgerJson.class).write(result).text(), Map.class);
        assertThat(keys.keySet()).isEqualTo(Set.of("status", "entity", "id", "from", "to", "version", "history_id",
                "author", "login"));
    }

    @Test
    @DisplayName("SVC-TRN-18: a malformed evidence_sha256")
    void trn18_sha(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(null,
                "defect", d.id(), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, "xyz", null)),
                ValidationCode.VALIDATION_FAILED);
    }
}
