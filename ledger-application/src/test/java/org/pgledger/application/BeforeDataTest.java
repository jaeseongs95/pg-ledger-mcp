package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.RecordFixResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;

/** SVC-BEF-01 .. SVC-BEF-11: {@code before_data}, the full row as it was replaced (SVC-BEF-12 is in the read test). */
@LedgerServiceTest
class BeforeDataTest {

    private HistoryEntry row(Scenario s, long historyId) {
        return s.historyRow(historyId);
    }

    @Test
    @DisplayName("SVC-BEF-01: a created row, a case, a link and a new fix have no before_data")
    void bef01_createsHaveNone(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult t = s.newTask("t");
        SaveResult c = s.newCondition("1", "r", "c");
        long caseRow = s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d.id(),
                "seen", Scenario.REASON, null, null, null)).historyId();
        long linkRow = s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task", d.id(),
                t.id(), Scenario.REASON, null)).historyId();
        long fixRow = s.fix(Scenario.AUTHOR, d.id(), Scenario.commit('a')).historyId();
        List<Long> all = List.of(d.historyId(), t.historyId(), c.historyId(), caseRow, linkRow, fixRow);
        assertThat(all).allSatisfy(id -> assertThat(row(s, id).beforeData()).isNull());
    }

    @Test
    @DisplayName("SVC-BEF-02: a defect edit keeps the whole previous row")
    void bef02_defectEdit(Scenario s) {
        SaveResult d = s.newDefect("d");
        Snapshot.DefectRow expected = Snapshot.DefectRow.of(s.defect(d.id()));
        s.ctx().clock().set(java.time.Instant.parse("2026-11-05T15:00:00Z"));
        SaveResult edited = s.bean(SaveService.class).save(Scenario.DEFECT_ONLY, s.edit("defect", d.id(), 1,
                Map.of("title", "new title")));
        Snapshot.DefectRow before = s.before(edited.historyId(), Snapshot.DefectRow.class);
        assertThat(before).isEqualTo(expected);
        assertThat(before.version()).isEqualTo(1);
        assertThat(before.createdBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(s.changes(edited.historyId()).entries()).containsKey("title");
        assertThat(Snapshot.DefectRow.of(s.defect(d.id()))).isNotEqualTo(before);
    }

    @Test
    @DisplayName("SVC-BEF-03: task and condition edits keep the previous row")
    void bef03_taskAndConditionEdits(Scenario s) {
        SaveResult t = s.newTask("t");
        SaveResult c = s.newCondition("1", "r", "c");
        Snapshot.TaskRow task = Snapshot.TaskRow.of(s.task(t.id()));
        Snapshot.ConditionRow condition = Snapshot.ConditionRow.of(s.bean(ConditionRepository.class)
                .findById(ConditionId.of(c.id())).orElseThrow());
        SaveResult taskEdit = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", t.id(), 1,
                Map.of("title", "t2")));
        SaveResult conditionEdit = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("condition", c.id(), 1,
                Map.of("condition_text", "changed")));
        assertThat(s.before(taskEdit.historyId(), Snapshot.TaskRow.class)).isEqualTo(task);
        assertThat(s.before(conditionEdit.historyId(), Snapshot.ConditionRow.class)).isEqualTo(condition);
    }

    @Test
    @DisplayName("SVC-BEF-04: transitions keep the previous state and version, leaving MERGED keeps merged_into")
    void bef04_transitions(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult target = s.newDefect("target");
        var merged = MergeFixture.merge(s, d.id(), 1, target.id());
        Snapshot.DefectRow beforeMerge = s.before(merged.historyId(), Snapshot.DefectRow.class);
        assertThat(beforeMerge.status()).isEqualTo("OPEN");
        assertThat(beforeMerge.version()).isEqualTo(1);
        var reopened = s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("defect", d.id(), 2, "OPEN",
                null));
        Snapshot.DefectRow beforeReopen = s.before(reopened.historyId(), Snapshot.DefectRow.class);
        assertThat(beforeReopen.status()).isEqualTo("MERGED");
        assertThat(beforeReopen.mergedInto()).isEqualTo(target.id());
        SaveResult t = s.newTask("t");
        var active = s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("task", t.id(), 1, "ACTIVE",
                null));
        assertThat(s.before(active.historyId(), Snapshot.TaskRow.class).state()).isEqualTo("PLANNED");
        SaveResult c = s.newCondition("1", "r", "c");
        var met = s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("condition", c.id(), 1, "MET",
                Scenario.EVIDENCE));
        assertThat(s.before(met.historyId(), Snapshot.ConditionRow.class).state()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("SVC-BEF-05: a first fix: the fix row has none, the defect transition keeps the OPEN row")
    void bef05_firstFix(Scenario s) {
        SaveResult d = s.newDefect("d");
        Snapshot.DefectRow open = Snapshot.DefectRow.of(s.defect(d.id()));
        RecordFixResult fix = s.fix(Scenario.AUTHOR, d.id(), Scenario.commit('a'));
        assertThat(row(s, fix.historyId()).beforeData()).isNull();
        assertThat(s.before(fix.transitionHistoryId(), Snapshot.DefectRow.class)).isEqualTo(open);
    }

    @Test
    @DisplayName("SVC-BEF-05: a new commit by another session: the defect EDIT keeps the defect row")
    void bef05_anotherSession(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        Snapshot.DefectRow fixed = Snapshot.DefectRow.of(s.defect(d.id()));
        RecordFixResult second = s.fix(Scenario.REVIEWER, d.id(), Scenario.commit('b'));
        assertThat(row(s, second.transitionHistoryId()).op()).isEqualTo(HistoryOp.EDIT);
        assertThat(s.before(second.transitionHistoryId(), Snapshot.DefectRow.class)).isEqualTo(fixed);
    }

    @Test
    @DisplayName("SVC-BEF-05: a CI update keeps the old fix row")
    void bef05_ciUpdate(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, new RecordFixCommand(null, d.id(), "repo/e2e",
                Scenario.commit('a'), Scenario.REASON, null, "ci-1", "failure", null, null));
        Snapshot.FixRow old = Snapshot.FixRow.of(s.bean(DefectFixRepository.class).findByDefectIdAndRepoAndCommitSha(
                DefectId.of(d.id()), "repo/e2e", CommitSha.of(Scenario.commit('a'))).orElseThrow());
        RecordFixResult update = s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, new RecordFixCommand(null,
                d.id(), "repo/e2e", Scenario.commit('a'), Scenario.REASON, null, "ci-1", "success", null, null));
        assertThat(s.before(update.historyId(), Snapshot.FixRow.class)).isEqualTo(old);
    }

    @Test
    @DisplayName("SVC-BEF-05: mark_source_fixed=false: the defect EDIT keeps the defect row")
    void bef05_noMark(Scenario s) {
        SaveResult d = s.newDefect("d");
        Snapshot.DefectRow open = Snapshot.DefectRow.of(s.defect(d.id()));
        RecordFixResult fix = s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, new RecordFixCommand(null,
                d.id(), "repo/e2e", Scenario.commit('a'), Scenario.REASON, null, null, null, null, false));
        assertThat(s.before(fix.transitionHistoryId(), Snapshot.DefectRow.class)).isEqualTo(open);
    }

    @Test
    @DisplayName("SVC-BEF-06: an unlink keeps the removed link row")
    void bef06_unlink(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult t = s.newTask("t");
        var linked = s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task", d.id(),
                t.id(), Scenario.REASON, null));
        var unlinked = s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task", d.id(),
                t.id(), Scenario.REASON, true));
        assertThat(row(s, linked.historyId()).beforeData()).isNull();
        assertThat(s.before(unlinked.historyId(), Snapshot.DefectTaskRow.class)).isEqualTo(
                new Snapshot.DefectTaskRow(d.id(), t.id(), Scenario.AUTHOR));
    }

    @Test
    @DisplayName("SVC-BEF-07: a case or a correction has none, and the case counter is no modification")
    void bef07_caseCounterIsNoModification(Scenario s) {
        SaveResult d = s.newDefect("d");
        var defect = s.defect(d.id());
        long defectHistory = s.history(HistoryEntity.DEFECT, d.id()).size();
        var recorded = s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null,
                d.id(), "seen", Scenario.REASON, null, null, null));
        var corrected = s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(null,
                recorded.historyId(), Scenario.REASON));
        assertThat(row(s, recorded.historyId()).beforeData()).isNull();
        assertThat(row(s, corrected.historyId()).beforeData()).isNull();
        var after = s.defect(d.id());
        assertThat(after.caseSeq()).isEqualTo(defect.caseSeq() + 1);
        assertThat(after.version()).isEqualTo(defect.version());
        assertThat(after.updatedAt()).isEqualTo(defect.updatedAt());
        assertThat(s.history(HistoryEntity.DEFECT, d.id())).hasSize((int) defectHistory);
    }

    @Test
    @DisplayName("SVC-BEF-08: a write that fails after the row update leaves no history, no receipt, no change")
    void bef08_rollback(Scenario s) {
        SaveResult d = s.newDefect("d");
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");
        long counter = s.count("SELECT issued_no FROM id_counter WHERE name = 'HISTORY'");
        String requestId = Scenario.uuid();
        assertThatThrownBy(() -> s.ctx().tx(() -> {
            s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(requestId, "defect", d.id(), 1L,
                    Fields.of("title", "rolled back"), Scenario.REASON));
            throw new IllegalStateException("after the row update");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
        assertThat(s.count("SELECT issued_no FROM id_counter WHERE name = 'HISTORY'")).isEqualTo(counter);
        assertThat(s.defect(d.id()).title()).isEqualTo("d");
        assertThat(s.defect(d.id()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-BEF-09: two edits with the same expected version: one wins with the old row, one conflicts")
    void bef09_race(Scenario s) {
        SaveResult d = s.newDefect("d");
        List<Callable<SaveResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                        Map.of("title", "from author"))),
                () -> s.bean(SaveService.class).save(Scenario.DEFECT_ONLY, s.edit("defect", d.id(), 1,
                        Map.of("title", "from the other"))));
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        Outcome<SaveResult> winner = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow();
        assertThat(outcomes.stream().filter(o -> !o.succeeded())).hasSize(1).allSatisfy(o -> assertThat(
                ((LedgerException) o.failure()).code()).isEqualTo(ConflictCode.VERSION_CONFLICT));
        assertThat(s.before(winner.value().historyId(), Snapshot.DefectRow.class).version()).isEqualTo(1);
        assertThat(s.history(HistoryEntity.DEFECT, d.id()).stream().filter(h -> h.op() == HistoryOp.EDIT)).hasSize(1);
        assertThat(s.defect(d.id()).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-BEF-10: a replay of an edit writes no second history row")
    void bef10_replay(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveCommand edit = new SaveCommand(Scenario.uuid(), "defect", d.id(), 1L, Fields.of("title", "x"),
                Scenario.REASON);
        s.bean(SaveService.class).save(Scenario.AUTHOR, edit);
        long rows = s.history(HistoryEntity.DEFECT, d.id()).size();
        s.bean(SaveService.class).save(Scenario.AUTHOR, edit);
        assertThat(s.history(HistoryEntity.DEFECT, d.id())).hasSize((int) rows);
    }

    @Test
    @DisplayName("SVC-BEF-11: 4000 Korean characters are stored in before_data exactly")
    void bef11_koreanText(Scenario s) {
        String behavior = "가나다라".repeat(1000);
        var fields = s.defectFields("d");
        fields.put("behavior", behavior);
        SaveResult d = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields));
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                Map.of("title", "new")));
        assertThat(s.before(edited.historyId(), Snapshot.DefectRow.class).behavior()).isEqualTo(behavior);
    }
}
