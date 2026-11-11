package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.pgledger.testing.TestOwners;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.GrantService.RevokeCommand;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.DefectId;

/** SVC-RACE-01 .. SVC-RACE-09, SVC-GRT-06: real threads and latches, committed on the embedded H2 (no transaction wraps a test). */
@LedgerServiceTest
class RaceTest {

    private static Optional<ErrorCode> code(Outcome<?> outcome) {
        return Optional.ofNullable(outcome.failure()).map(e -> ((LedgerException) e).code());
    }

    private static List<Optional<ErrorCode>> codes(List<? extends Outcome<?>> outcomes) {
        return outcomes.stream().map(RaceTest::code).toList();
    }

    /**
     * Runs {@code work} inside a transaction that stays open after the work is done: {@code finished} is counted
     * down when the work is done, the transaction commits when {@code release} is counted down.
     */
    private <T> Future<T> holdingTransaction(Scenario s, Supplier<T> work, CountDownLatch finished,
            CountDownLatch release) {
        return Parallel.start(() -> s.ctx().tx(() -> {
            try {
                T result = work.get();
                finished.countDown();
                release.await(60, TimeUnit.SECONDS);
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the transaction was held open", e);
            } finally {
                finished.countDown();
            }
        }));
    }

    // ------------------------------------------------------------------- 01

    @Test
    @DisplayName("SVC-RACE-01 (a): a merge that is not committed yet makes the case wait, then the case is refused")
    void race01_mergeFirst(Scenario s) throws Exception {
        SaveResult source = s.newDefect("source");
        SaveResult target = s.newDefect("target");
        String caseAuthor = s.actorWith("defect");
        CountDownLatch merged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> merge = holdingTransaction(s, () -> MergeFixture.merge(s, source.id(), 1, target.id()), merged,
                release);
        assertThat(merged.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Optional<ErrorCode>> recording = Parallel.start(() -> Expect.outcome(() -> s.bean(
                RecordCaseService.class).recordCase(caseAuthor, new RecordCaseCommand(null, source.id(), "seen",
                Scenario.REASON, null, null, null))));
        assertThatThrownBy(() -> recording.get(400, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        merge.get(60, TimeUnit.SECONDS);
        assertThat(recording.get(60, TimeUnit.SECONDS)).contains(TransitionCode.MERGED_DEFECT);
        assertThat(s.bean(DefectCaseRepository.class).findByDefectIdOrderById(DefectId.of(source.id()))).isEmpty();
        assertThat(s.defect(source.id()).status()).isEqualTo(DefectStatus.MERGED);
    }

    @Test
    @DisplayName("SVC-RACE-01 (b): a case that is not committed yet makes the merge wait, the case then belongs to the target")
    void race01_caseFirst(Scenario s) throws Exception {
        SaveResult source = s.newDefect("source");
        SaveResult target = s.newDefect("target");
        String caseAuthor = s.actorWith("defect");
        CountDownLatch recorded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<WriteResult> recording = holdingTransaction(s, () -> s.bean(RecordCaseService.class).recordCase(
                caseAuthor, new RecordCaseCommand(null, source.id(), "seen", Scenario.REASON, null, null, null)),
                recorded, release);
        assertThat(recorded.await(60, TimeUnit.SECONDS)).isTrue();
        Future<WriteResult> merging = Parallel.start(() -> MergeFixture.merge(s, source.id(), 1, target.id()));
        assertThatThrownBy(() -> merging.get(400, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        var caseResult = (WriteResult.RecordCaseResult) recording.get(60, TimeUnit.SECONDS);
        var mergeResult = (WriteResult.TransitionResult) merging.get(60, TimeUnit.SECONDS);
        assertThat(s.defect(source.id()).status()).isEqualTo(DefectStatus.MERGED);
        assertThat(s.bean(DefectCaseRepository.class).findByDefectIdOrderById(DefectId.of(source.id()))).hasSize(1);
        assertThat(caseResult.historyId()).as("the case was recorded before the merge").isLessThan(
                mergeResult.historyId());
        assertThat(s.history(HistoryEntity.DEFECT, source.id()).stream().filter(h -> h.op() == HistoryOp.TRANSITION)
                .mapToLong(h -> h.id()).max().orElseThrow()).isEqualTo(mergeResult.historyId());
    }

    // ------------------------------------------------------------------- 09

    @Test
    @DisplayName("SVC-RACE-09: 20 sequential and 20 parallel cases do not touch the version, a save still succeeds")
    void race09_caseCounterDoesNotBumpTheVersion(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1, Map.of("title", "two")));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 2, Map.of("title", "three")));
        long defectHistory = s.history(HistoryEntity.DEFECT, d.id()).size();
        String actor = s.actorWith("defect");
        IntStream.range(0, 20).forEach(i -> s.bean(RecordCaseService.class).recordCase(actor, new RecordCaseCommand(
                null, d.id(), "seq " + i, Scenario.REASON, null, null, null)));
        assertThat(s.defect(d.id()).caseSeq()).isEqualTo(20);
        assertThat(s.defect(d.id()).version()).isEqualTo(3);
        List<Callable<WriteResult>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            calls.add(() -> s.bean(RecordCaseService.class).recordCase(actor, new RecordCaseCommand(null, d.id(),
                    "par " + n, Scenario.REASON, null, null, null)));
        }
        calls.add(() -> s.bean(SaveService.class).save(Scenario.DEFECT_ONLY, s.edit("defect", d.id(), 3,
                Map.of("title", "four"))));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls, 21);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(s.defect(d.id()).caseSeq()).isEqualTo(40);
        assertThat(s.defect(d.id()).version()).as("only the save bumped the version").isEqualTo(4);
        assertThat(s.bean(DefectCaseRepository.class).findByDefectIdOrderById(DefectId.of(d.id()))).hasSize(40);
        assertThat(s.history(HistoryEntity.DEFECT, d.id())).hasSize((int) defectHistory + 1);
    }

    // --------------------------------------------------------------- 02 .. 05

    @Test
    @DisplayName("SVC-RACE-02: two transitions with the same expected version: one commits, one is a version conflict")
    void race02_transitions(Scenario s) {
        SaveResult d = s.newDefect("d");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("defect", d.id(), 1,
                        "DISPUTED", Scenario.EVIDENCE)),
                () -> s.bean(TransitionService.class).transition(Scenario.DEFECT_ONLY, s.move("defect", d.id(), 1,
                        "NOT_DEFECT", Scenario.EVIDENCE)));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(codes(outcomes)).containsExactlyInAnyOrder(Optional.empty(),
                Optional.of(ConflictCode.VERSION_CONFLICT));
        assertThat(s.defect(d.id()).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-RACE-03: two edits with the same expected version: one commits, one is a version conflict")
    void race03_edits(Scenario s) {
        SaveResult d = s.newDefect("d");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                        Map.of("title", "a"))),
                () -> s.bean(SaveService.class).save(Scenario.DEFECT_ONLY, s.edit("defect", d.id(), 1,
                        Map.of("title", "b"))));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(codes(outcomes)).containsExactlyInAnyOrder(Optional.empty(),
                Optional.of(ConflictCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("SVC-RACE-04: two fixes of one OPEN defect: one SOURCE_FIXED transition, a fix row for each success")
    void race04_twoFixes(Scenario s) {
        SaveResult d = s.newDefect("d");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.fix(Scenario.AUTHOR, d.id(), Scenario.commit('a')),
                () -> s.fix(Scenario.REVIEWER, d.id(), Scenario.commit('b')));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(codes(outcomes)).allSatisfy(c -> assertThat(c).isIn(Optional.empty(),
                Optional.of(ConflictCode.VERSION_CONFLICT)));
        assertThat(s.defect(d.id()).status()).isEqualTo(DefectStatus.SOURCE_FIXED);
        assertThat(s.history(HistoryEntity.DEFECT, d.id()).stream().filter(h -> h.op() == HistoryOp.TRANSITION
                && "SOURCE_FIXED".equals(h.toState()) && "OPEN".equals(h.fromState()))).hasSize(1);
        assertThat(s.count("SELECT COUNT(*) FROM defect_fix WHERE defect_id = ?", d.id()))
                .isEqualTo(outcomes.stream().filter(Outcome::succeeded).count());
    }

    @Test
    @DisplayName("SVC-RACE-05: two reviewers verify the same defect: exactly one VERIFIED")
    void race05_twoVerifications(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        String one = s.actorWith("verify");
        String two = s.actorWith("verify");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.bean(ReviewService.class).review(one, s.move("defect", d.id(), 2, "VERIFIED",
                        Scenario.EVIDENCE)),
                () -> s.bean(ReviewService.class).review(two, s.move("defect", d.id(), 2, "VERIFIED",
                        Scenario.EVIDENCE)));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(o -> ((LedgerException) o.failure()).code()))
                .allSatisfy(c -> assertThat(c).isIn(ConflictCode.VERSION_CONFLICT, TransitionCode.TRANSITION_INVALID));
        assertThat(s.defect(d.id()).status()).isEqualTo(DefectStatus.VERIFIED);
    }

    @Test
    @DisplayName("SVC-RACE-06: two identical links: one is made, the other changes nothing, no duplicate-key error")
    void race06_identicalLinks(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult t = s.newTask("t");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task", d.id(),
                        t.id(), Scenario.REASON, null)),
                () -> s.bean(LinkService.class).link(Scenario.DEFECT_ONLY, new LinkCommand(null, "defect_task",
                        d.id(), t.id(), Scenario.REASON, null)));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(codes(outcomes)).containsExactlyInAnyOrder(Optional.empty(),
                Optional.of(TransitionCode.NOTHING_CHANGES));
        assertThat(s.count("SELECT COUNT(*) FROM defect_task WHERE defect_id = ?", d.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 07

    @Test
    @DisplayName("SVC-RACE-07: a revocation waits for a write that holds the grant, afterwards nothing is accepted")
    void race07_revokeWaitsForTheWrite(Scenario s) throws Exception {
        String actor = s.actorWith("defect");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<SaveResult> writing = holdingTransaction(s, () -> s.bean(SaveService.class).save(actor,
                s.create("defect", null, s.defectFields("held"))), written, release);
        assertThat(written.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Integer> revoking = Parallel.start(() -> s.bean(GrantService.class).revoke(new RevokeCommand(actor,
                null, null), TestOwners.OWNER));
        assertThatThrownBy(() -> revoking.get(400, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(writing.get(60, TimeUnit.SECONDS).version()).isEqualTo(1);
        assertThat(revoking.get(60, TimeUnit.SECONDS)).isEqualTo(1);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields("late"))),
                TrustCode.SESSION_NOT_GRANTED);
    }

    @RepeatedTest(200)
    @DisplayName("SVC-RACE-07: a write against a revocation: a refused write left nothing, none after the revoke")
    void race07_writeAgainstRevoke(Scenario s) {
        String actor = s.actorWith("defect");
        List<Callable<Object>> calls = List.of(
                () -> s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields("round"))),
                () -> s.bean(GrantService.class).revoke(new RevokeCommand(actor, null, null), TestOwners.OWNER));
        List<Outcome<Object>> outcomes = Parallel.all(calls);
        assertThat(outcomes.get(1).failure()).isNull();
        Optional<ErrorCode> write = code(outcomes.get(0));
        long written = s.count("SELECT COUNT(*) FROM history WHERE author_id = ? AND op = 'CREATE'", actor);
        assertThat(write.map(ErrorCode::token).orElse("written") + ":" + written)
                .isIn("written:1", "SESSION_NOT_GRANTED:0");
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(actor, s.create("defect", null,
                s.defectFields("after"))))).contains(TrustCode.SESSION_NOT_GRANTED);
    }

    // ------------------------------------------------------------------ 08

    @Test
    @DisplayName("SVC-RACE-08: a code added while saves use it: each save sees the old or the new set, nothing else")
    void race08_codeSetVersusSave(Scenario s) {
        String category = "RACE_" + s.code();
        List<Callable<Object>> calls = new ArrayList<>();
        calls.add(() -> s.bean(CommonCodeService.class).set(new CodeCommand("DEFECT_CATEGORY", category, "경쟁",
                "Race", null, null, 80, null), TestOwners.OWNER));
        for (int i = 0; i < 20; i++) {
            calls.add(() -> {
                var fields = s.defectFields("race");
                fields.put("category", category);
                return s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields));
            });
        }
        List<Outcome<Object>> outcomes = Parallel.all(calls, 21);
        assertThat(codes(outcomes)).allSatisfy(c -> assertThat(c).isIn(Optional.empty(),
                Optional.of(ValidationCode.CODE_INVALID)));
        assertThat(outcomes.get(0).failure()).isNull();
    }

    // -------------------------------------------------------------- SVC-GRT-06

    @Test
    @DisplayName("SVC-GRT-06: two grants of the same key at the same time leave exactly one live grant")
    void grt06_parallelGrants(Scenario s) {
        String author = "claude:grt06";
        List<Callable<Object>> calls = IntStream.range(0, 2).<Callable<Object>>mapToObj(i -> () -> s.bean(
                GrantService.class).grant(new GrantCommand(author, s.projectId(), List.of("defect"), null, "n" + i), TestOwners.OWNER))
                .toList();
        assertThat(Parallel.all(calls)).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ? AND revoked_at IS NULL",
                author)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ?", author)).isEqualTo(2);
    }
}
