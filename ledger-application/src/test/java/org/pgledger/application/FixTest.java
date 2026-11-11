package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.history.Change;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.WriteResult.RecordFixResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import tools.jackson.databind.json.JsonMapper;

/** SVC-FIX-01 .. SVC-FIX-16 (SVC-FIX-11 and -12 read the defect, they are in the read test). */
@LedgerServiceTest
class FixTest {

    private static RecordFixCommand fix(String defect, String commit, String ciRef, String conclusion,
            String completedAt, Boolean mark) {
        return new RecordFixCommand(null, defect, "repo/e2e", commit, Scenario.REASON, "main", ciRef, conclusion,
                completedAt, mark);
    }

    private RecordFixResult record(Scenario s, String actor, RecordFixCommand command) {
        return s.bean(RecordFixService.class).recordFix(actor, command);
    }

    @Test
    @DisplayName("SVC-FIX-01: a fix moves an OPEN defect to SOURCE_FIXED and makes the author the owner")
    void fix01_firstFix(Scenario s) {
        SaveResult d = s.newDefect("d");
        RecordFixResult result = record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), null, null, null, null));
        assertThat(result.defectStatus()).isEqualTo("SOURCE_FIXED");
        assertThat(result.ownerActorId()).isEqualTo(Scenario.AUTHOR);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.transitionHistoryId()).isNotNull().isGreaterThan(result.historyId());
        assertThat(s.defect(d.id()).status()).isEqualTo(DefectStatus.SOURCE_FIXED);
        Map<?, ?> keys = new JsonMapper().readValue(s.bean(LedgerJson.class).write(result).text(), Map.class);
        assertThat(keys.keySet()).isEqualTo(Set.of("status", "defect", "fix_id", "defect_status", "owner_actor_id",
                "version", "history_id", "transition_history_id", "author", "login"));
    }

    @Test
    @DisplayName("SVC-FIX-02: ci_conclusion must be success, failure or cancelled")
    void fix02_conclusion(Scenario s) {
        SaveResult d = s.newDefect("d");
        ValidationException e = (ValidationException) Expect.failure(() -> record(s, Scenario.AUTHOR,
                fix(d.id(), Scenario.commit('a'), "ci", "passed", null, null)), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).containsExactly("success", "failure", "cancelled");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("SVC-FIX-03: a conclusion needs a CI reference")
    void fix03_conclusionNeedsReference(String ciRef, Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), ciRef, "success", null,
                null)), ValidationCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    @DisplayName("SVC-FIX-04: a commit id is 40 lower-case hex characters")
    void fix04_commitFormat(String commit, Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> record(s, Scenario.AUTHOR, fix(d.id(), commit, null, null, null, null)),
                ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-FIX-05: a new commit by another session makes it the owner, the status stays")
    void fix05_ownerChanges(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        RecordFixResult second = record(s, Scenario.REVIEWER, fix(d.id(), Scenario.commit('b'), null, null, null,
                null));
        assertThat(second.version()).isEqualTo(3);
        assertThat(second.defectStatus()).isEqualTo("SOURCE_FIXED");
        assertThat(second.ownerActorId()).isEqualTo(Scenario.REVIEWER);
        HistoryEntry edit = s.historyRow(second.transitionHistoryId());
        assertThat(edit.op()).isEqualTo(HistoryOp.EDIT);
        assertThat(s.changes(edit.id()).entries()).containsExactly(Map.entry("owner_actor_id",
                new Change(Scenario.AUTHOR, Scenario.REVIEWER)));
    }

    @Test
    @DisplayName("SVC-FIX-06: the same commit again without CI data")
    void fix06_alreadyRecorded(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        Expect.failure(() -> record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), null, null, null, null)),
                TransitionCode.FIX_ALREADY_RECORDED);
    }

    @Test
    @DisplayName("SVC-FIX-07: the same commit with changed CI data records an EDIT of the fix only")
    void fix07_ciUpdate(Scenario s) {
        SaveResult d = s.newDefect("d");
        record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), "ci-1", "failure", null, null));
        RecordFixResult update = record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), "ci-1", "success",
                "2026-11-05T09:00:00Z", null));
        HistoryEntry row = s.historyRow(update.historyId());
        assertThat(row.entity()).isEqualTo(HistoryEntity.DEFECT_FIX);
        assertThat(row.op()).isEqualTo(HistoryOp.EDIT);
        assertThat(s.changes(row.id()).entries()).containsOnlyKeys("ci_conclusion", "ci_completed_at");
        assertThat(update.transitionHistoryId()).isNull();
        assertThat(update.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-FIX-08: identical CI data changes nothing")
    void fix08_nothingChanges(Scenario s) {
        SaveResult d = s.newDefect("d");
        record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), "ci-1", "success", null, null));
        Expect.failure(() -> record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), "ci-1", "success", null,
                null)), TransitionCode.NOTHING_CHANGES);
    }

    @Test
    @DisplayName("SVC-FIX-09: mark_source_fixed=false keeps the defect OPEN and sets the owner")
    void fix09_noMark(Scenario s) {
        SaveResult d = s.newDefect("d");
        RecordFixResult result = record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), null, null, null,
                false));
        assertThat(result.defectStatus()).isEqualTo("OPEN");
        assertThat(result.version()).isEqualTo(2);
        assertThat(s.historyRow(result.transitionHistoryId()).op()).isEqualTo(HistoryOp.EDIT);
        assertThat(s.defect(d.id()).ownerActorId().value()).isEqualTo(Scenario.AUTHOR);
    }

    /** The CI run behind a fix; no conclusion means no CI data at all. */
    record CiRun(String name, String conclusion) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<CiRun> ciRuns() {
        return Stream.of(new CiRun("failure", "failure"), new CiRun("cancelled", "cancelled"),
                new CiRun("no CI data", null));
    }

    @ParameterizedTest
    @MethodSource("ciRuns")
    @DisplayName("SVC-FIX-10: CI is optional metadata: a fix with failed or no CI can be verified by an independent session")
    void fix10_ciIsOptional(CiRun run, Scenario s) {
        SaveResult d = s.newDefect("d");
        String conclusion = run.conclusion();
        record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), conclusion == null ? null : "ci-1", conclusion,
                null, null));
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("defect", d.id(), 2, "VERIFIED",
                Scenario.EVIDENCE));
        assertThat(s.defect(d.id()).status()).isEqualTo(DefectStatus.VERIFIED);
        CiConclusion stored = s.bean(DefectFixRepository.class).findByDefectIdAndRepoAndCommitSha(
                DefectId.of(d.id()), "repo/e2e", CommitSha.of(Scenario.commit('a'))).orElseThrow().ciConclusion();
        assertThat(stored == null ? null : stored.token()).isEqualTo(conclusion);
    }

    @Test
    @DisplayName("SVC-FIX-10: the author of the only fix cannot verify it, even with a successful CI run")
    void fix10_ownFixCannotBeVerified(Scenario s) {
        SaveResult d = s.newDefect("d");
        record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), "ci-1", "success", null, null));
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.AUTHOR, s.move("defect", d.id(), 2,
                "VERIFIED", Scenario.EVIDENCE)), TransitionCode.SAME_SESSION);
    }

    @Test
    @DisplayName("SVC-FIX-13: the history row of a fix names defect and commit and carries the CI reference")
    void fix13_history(Scenario s) {
        SaveResult d = s.newDefect("d");
        RecordFixResult result = record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), "https://ci.example/1",
                "success", null, null));
        HistoryEntry row = s.historyRow(result.historyId());
        assertThat(row.entity()).isEqualTo(HistoryEntity.DEFECT_FIX);
        assertThat(row.entityId()).isEqualTo(d.id() + "@" + Scenario.commit('a'));
        assertThat(row.evidenceUri()).isEqualTo("https://ci.example/1");
        assertThat(s.bean(DefectFixRepository.class).findById(result.fixId()).orElseThrow().recordedBy().value())
                .isEqualTo(Scenario.AUTHOR);
    }

    @Test
    @DisplayName("SVC-FIX-14: an unknown defect")
    void fix14_unknownDefect(Scenario s) {
        Expect.failure(() -> record(s, Scenario.AUTHOR, fix(s.defectId(9), Scenario.commit('a'), null, null, null,
                null)), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-FIX-14: an actor without the fix grant")
    void fix14_noFixGrant(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> record(s, Scenario.DEFECT_ONLY, fix(d.id(), Scenario.commit('a'), null, null, null,
                null)), TrustCode.SESSION_NOT_GRANTED);
    }

    @Test
    @DisplayName("SVC-FIX-15: an offset time and the same time in UTC are one instant")
    void fix15_instants(Scenario s) {
        SaveResult d = s.newDefect("d");
        record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), "ci", "success", "2026-11-05T19:00:00+09:00",
                false));
        record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('b'), "ci", "success", "2026-11-05T10:00:00Z",
                false));
        Instant first = s.bean(DefectFixRepository.class).findByDefectIdAndRepoAndCommitSha(DefectId.of(d.id()),
                "repo/e2e", CommitSha.of(Scenario.commit('a'))).orElseThrow().ciCompletedAt();
        Instant second = s.bean(DefectFixRepository.class).findByDefectIdAndRepoAndCommitSha(DefectId.of(d.id()),
                "repo/e2e", CommitSha.of(Scenario.commit('b'))).orElseThrow().ciCompletedAt();
        assertThat(first).isEqualTo(second).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-05T10:00:00", "2026-11-05"})
    @DisplayName("SVC-FIX-15: a time without a zone is refused")
    void fix15_naive(String time, Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> record(s, Scenario.AUTHOR, fix(d.id(), Scenario.commit('a'), "ci", "success", time,
                null)), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-FIX-16: a fix on a MERGED defect is allowed (unchanged by design)")
    void fix16_onMerged(Scenario s) {
        SaveResult source = s.newDefect("source");
        SaveResult target = s.newDefect("target");
        MergeFixture.merge(s, source.id(), 1, target.id());
        RecordFixResult result = record(s, Scenario.AUTHOR, fix(source.id(), Scenario.commit('a'), null, null, null,
                null));
        assertThat(result.defectStatus()).isEqualTo("MERGED");
        assertThat(s.defect(source.id()).status()).isEqualTo(DefectStatus.MERGED);
    }
}
