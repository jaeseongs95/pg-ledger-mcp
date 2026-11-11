package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.FixHistory;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.MergeTarget;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.TaskReports;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.rule.TaskTransition;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.SessionKey;

/** DOM-009 .. DOM-019: the transition policies are pure functions of their facts. */
class TransitionPolicyTest {

    private static final Evidence EVIDENCE = Evidence.of("https://ci.example/run/1", null);
    private static final AuthorId ME = AuthorId.of("claude:me");
    private static final AuthorId OTHER = AuthorId.of("codex:other");
    private static final DefectId SELF = DefectId.of("E2E-D-0001");
    private static final DefectId LIVE = DefectId.of("E2E-D-0002");
    private static final FixHistory OTHER_FIX = new FixHistory(List.of(OTHER.sessionKey()));
    private static final FixHistory OWN_FIX = new FixHistory(List.of(ME.sessionKey()));

    private static void defect(DefectStatus from, DefectStatus to, Evidence evidence, FixHistory fixes,
            MergeTarget target) {
        from.requireTransitionTo(to, evidence, fixes, ME.sessionKey(), SELF, target);
    }

    @Test
    void dom009_everyPairFollowsTheRules() {
        MergeTarget live = new MergeTarget(LIVE, DefectStatus.OPEN);
        for (DefectStatus from : DefectStatus.values()) {
            for (DefectStatus to : DefectStatus.values()) {
                boolean denied = from == to || (to == DefectStatus.VERIFIED && from != DefectStatus.SOURCE_FIXED);
                if (denied) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> defect(from, to, EVIDENCE, OTHER_FIX, live))
                            .as("%s -> %s", from, to)
                            .isInstanceOf(org.pgledger.domain.error.TransitionException.class);
                } else {
                    assertThatCode(() -> defect(from, to, EVIDENCE, OTHER_FIX, live)).as("%s -> %s", from, to)
                            .doesNotThrowAnyException();
                }
            }
        }
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> defect(DefectStatus.OPEN, DefectStatus.OPEN, EVIDENCE, OTHER_FIX, live));
    }

    @Test
    void dom010_sourceFixedNeedsAFix() {
        Expect.failsWith(TransitionCode.FIX_REQUIRED,
                () -> defect(DefectStatus.OPEN, DefectStatus.SOURCE_FIXED, Evidence.NONE, FixHistory.NONE, null));
        assertThatCode(() -> defect(DefectStatus.OPEN, DefectStatus.SOURCE_FIXED, Evidence.NONE, OWN_FIX, null))
                .doesNotThrowAnyException();
    }

    @Test
    void dom011_verifiedOnlyFromSourceFixed() {
        for (DefectStatus from : List.of(DefectStatus.OPEN, DefectStatus.NOT_DEFECT, DefectStatus.DISPUTED,
                DefectStatus.MERGED)) {
            Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                    () -> defect(from, DefectStatus.VERIFIED, Evidence.NONE, FixHistory.NONE, null));
        }
    }

    @Test
    void dom012_verifiedChecksEvidenceThenFixThenSession() {
        DefectStatus sf = DefectStatus.SOURCE_FIXED;
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> defect(sf, DefectStatus.VERIFIED, Evidence.NONE, FixHistory.NONE, null));
        Expect.failsWith(TransitionCode.FIX_REQUIRED,
                () -> defect(sf, DefectStatus.VERIFIED, EVIDENCE, FixHistory.NONE, null));
        Expect.failsWith(TransitionCode.SAME_SESSION, () -> defect(sf, DefectStatus.VERIFIED, EVIDENCE, OWN_FIX, null));
        assertThatCode(() -> defect(sf, DefectStatus.VERIFIED, EVIDENCE, OTHER_FIX, null)).doesNotThrowAnyException();
        // a mix of own and independent fixes is enough
        FixHistory mixed = new FixHistory(List.of(ME.sessionKey(), OTHER.sessionKey()));
        assertThatCode(() -> defect(sf, DefectStatus.VERIFIED, EVIDENCE, mixed, null)).doesNotThrowAnyException();
    }

    @Test
    void dom013_leavingVerifiedNeedsEvidenceAfterTheFixCheck() {
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> defect(DefectStatus.VERIFIED, DefectStatus.OPEN, Evidence.NONE, OTHER_FIX, null));
        assertThatCode(() -> defect(DefectStatus.VERIFIED, DefectStatus.OPEN, EVIDENCE, OTHER_FIX, null))
                .doesNotThrowAnyException();
        // the SOURCE_FIXED-needs-fix check comes first
        Expect.failsWith(TransitionCode.FIX_REQUIRED,
                () -> defect(DefectStatus.VERIFIED, DefectStatus.SOURCE_FIXED, Evidence.NONE, FixHistory.NONE, null));
    }

    @Test
    void dom014_notDefectAndDisputedNeedEvidence() {
        for (DefectStatus to : List.of(DefectStatus.NOT_DEFECT, DefectStatus.DISPUTED)) {
            Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                    () -> defect(DefectStatus.OPEN, to, Evidence.NONE, FixHistory.NONE, null));
        }
    }

    @Test
    void dom015_mergeNeedsALiveTargetAndMergedIntoFollowsTheStatus() {
        for (MergeTarget bad : new MergeTarget[] {null, new MergeTarget(SELF, DefectStatus.OPEN),
                new MergeTarget(LIVE, DefectStatus.MERGED)}) {
            Expect.failsWith(TransitionCode.MERGE_TARGET_INVALID,
                    () -> defect(DefectStatus.OPEN, DefectStatus.MERGED, Evidence.NONE, FixHistory.NONE, bad));
        }
        assertThatCode(() -> defect(DefectStatus.OPEN, DefectStatus.MERGED, Evidence.NONE, FixHistory.NONE,
                new MergeTarget(LIVE, DefectStatus.SOURCE_FIXED))).doesNotThrowAnyException();

        Instant now = Instant.parse("2026-11-05T10:00:00Z");
        Defect open = DomainFixtures.defect(DefectStatus.OPEN, null);
        Defect merged = open.transitioned(DefectStatus.MERGED, LIVE, now);
        assertThat(merged.mergedInto()).isEqualTo(LIVE);
        assertThat(merged.transitioned(DefectStatus.OPEN, null, now).mergedInto()).isNull();
        Expect.invalid(() -> open.transitioned(DefectStatus.MERGED, null, now));
        Expect.invalid(() -> merged.transitioned(DefectStatus.OPEN, LIVE, now));
    }

    @Test
    void dom016_scopeOfATransition() {
        assertThat(Scope.forTransition(HistoryEntity.DEFECT, DefectStatus.OPEN, DefectStatus.VERIFIED))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.DEFECT, DefectStatus.VERIFIED, DefectStatus.OPEN))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.DEFECT, DefectStatus.OPEN, DefectStatus.DISPUTED))
                .isEqualTo(Scope.DEFECT);
        assertThat(Scope.forTransition(HistoryEntity.TASK, TaskState.ACTIVE, TaskState.REPORTED_DONE))
                .isEqualTo(Scope.TASK);
        assertThat(Scope.forTransition(HistoryEntity.TASK, TaskState.REPORTED_DONE, TaskState.VERIFIED))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.CONDITION, ConditionState.UNKNOWN, ConditionState.MET))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.CONDITION, ConditionState.NOT_MET, ConditionState.UNKNOWN))
                .isEqualTo(Scope.VERIFY);
        Expect.invalid(() -> Scope.forTransition(HistoryEntity.DEFECT_FIX, DefectStatus.OPEN, DefectStatus.DISPUTED));
    }

    private static void task(TaskState from, TaskState to, Evidence evidence, AuthorId actor, AuthorId owner,
            TaskReports reports) {
        from.requireTransitionTo(to, evidence, actor.sessionKey(), Optional.ofNullable(owner)
                .map(AuthorId::sessionKey), reports);
    }

    @Test
    void dom017_taskPolicy() {
        TaskReports none = new TaskReports(List.of());
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> task(TaskState.PLANNED, TaskState.UNKNOWN, EVIDENCE, ME, null, none));
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> task(TaskState.ACTIVE, TaskState.ACTIVE, EVIDENCE, ME, null, none));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> task(TaskState.ACTIVE, TaskState.REPORTED_DONE, Evidence.NONE, ME, null, none));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> task(TaskState.VERIFIED, TaskState.ACTIVE, Evidence.NONE, OTHER, null, none));
        assertThatCode(() -> task(TaskState.VERIFIED, TaskState.ACTIVE, EVIDENCE, OTHER, null, none))
                .doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> task(TaskState.ACTIVE, TaskState.VERIFIED, EVIDENCE, OTHER, null, none));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> task(TaskState.REPORTED_DONE, TaskState.VERIFIED, Evidence.NONE, OTHER, null, none));
        // the owner cannot verify
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> task(TaskState.REPORTED_DONE, TaskState.VERIFIED, EVIDENCE, OTHER, OTHER, none));
        // the reporter cannot verify
        TaskReports reportedByMe = new TaskReports(
                List.of(new TaskTransition(TaskState.ACTIVE, TaskState.REPORTED_DONE, ME)));
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> task(TaskState.REPORTED_DONE, TaskState.VERIFIED, EVIDENCE, ME, null, reportedByMe));
        assertThatCode(() -> task(TaskState.REPORTED_DONE, TaskState.VERIFIED, EVIDENCE, OTHER, null, reportedByMe))
                .doesNotThrowAnyException();
    }

    @Test
    void dom018_reporterIsTheLatestReportThatDidNotComeFromVerified() {
        TaskReports history = new TaskReports(List.of(
                new TaskTransition(TaskState.ACTIVE, TaskState.REPORTED_DONE, ME),
                new TaskTransition(TaskState.REPORTED_DONE, TaskState.VERIFIED, OTHER),
                new TaskTransition(TaskState.VERIFIED, TaskState.REPORTED_DONE, OTHER)));
        assertThat(history.latestReporter()).contains(new SessionKey("claude:me"));
        assertThat(new TaskReports(List.of()).latestReporter()).isEmpty();
    }

    @Test
    void dom019_conditionPolicy() {
        for (ConditionState to : List.of(ConditionState.MET, ConditionState.NOT_MET)) {
            Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                    () -> ConditionState.UNKNOWN.requireTransitionTo(to, Evidence.NONE));
            assertThatCode(() -> ConditionState.UNKNOWN.requireTransitionTo(to, EVIDENCE)).doesNotThrowAnyException();
        }
        // leaving MET or NOT_MET for UNKNOWN needs no evidence (unchanged from the old ledger by design)
        assertThatCode(() -> ConditionState.MET.requireTransitionTo(ConditionState.UNKNOWN, Evidence.NONE))
                .doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> ConditionState.MET.requireTransitionTo(ConditionState.MET, EVIDENCE));
    }
}
