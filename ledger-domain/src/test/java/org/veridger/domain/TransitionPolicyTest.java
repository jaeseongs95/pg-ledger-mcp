// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.Spec;
import org.veridger.domain.rule.CriterionReports;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.CriterionTransition;
import org.veridger.domain.rule.DeliverableHistory;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.MergeTarget;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.rule.SpecReports;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.SpecTransition;
import org.veridger.domain.rule.TaskReports;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.rule.TaskTransition;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.Evidence;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.SessionKey;

/** DOM-009 .. DOM-019: transition policies for issues, specs, tasks, and milestone criteria. */
class TransitionPolicyTest {

    private static final Evidence EVIDENCE = Evidence.of("https://ci.example/run/1", null);
    private static final AuthorId ME = AuthorId.of("claude:me");
    private static final AuthorId OTHER = AuthorId.of("codex:other");
    private static final IssueId SELF = IssueId.of("E2E-I-0001");
    private static final IssueId LIVE = IssueId.of("E2E-I-0002");
    private static final DeliverableHistory OTHER_DELIV = new DeliverableHistory(List.of(OTHER.sessionKey()), List.of());
    private static final DeliverableHistory OWN_DELIV = new DeliverableHistory(List.of(ME.sessionKey()), List.of());

    private static void issue(IssueStatus from, IssueStatus to, Evidence evidence, DeliverableHistory deliverables,
            MergeTarget target) {
        from.requireTransitionTo(to, evidence, deliverables, ME.sessionKey(), SELF, target);
    }

    @Test
    void dom009_everyPairFollowsTheRules() {
        MergeTarget live = new MergeTarget(LIVE, IssueStatus.OPEN);
        for (IssueStatus from : IssueStatus.values()) {
            for (IssueStatus to : IssueStatus.values()) {
                boolean denied = from == to || (to == IssueStatus.VERIFIED && from != IssueStatus.RESOLVED);
                if (denied) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> issue(from, to, EVIDENCE, OTHER_DELIV, live))
                            .as("%s -> %s", from, to)
                            .isInstanceOf(org.veridger.domain.error.TransitionException.class);
                } else {
                    assertThatCode(() -> issue(from, to, EVIDENCE, OTHER_DELIV, live)).as("%s -> %s", from, to)
                            .doesNotThrowAnyException();
                }
            }
        }
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> issue(IssueStatus.OPEN, IssueStatus.OPEN, EVIDENCE, OTHER_DELIV, live));
    }

    @Test
    void dom010_resolvedNeedsADeliverable() {
        Expect.failsWith(TransitionCode.DELIVERABLE_REQUIRED,
                () -> issue(IssueStatus.OPEN, IssueStatus.RESOLVED, Evidence.NONE, DeliverableHistory.NONE, null));
        assertThatCode(() -> issue(IssueStatus.OPEN, IssueStatus.RESOLVED, Evidence.NONE, OWN_DELIV, null))
                .doesNotThrowAnyException();
    }

    @Test
    void dom011_verifiedOnlyFromResolved() {
        for (IssueStatus from : List.of(IssueStatus.OPEN, IssueStatus.NOT_AN_ISSUE, IssueStatus.DISPUTED,
                IssueStatus.MERGED)) {
            Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                    () -> issue(from, IssueStatus.VERIFIED, Evidence.NONE, DeliverableHistory.NONE, null));
        }
    }

    @Test
    void dom012_verifiedChecksEvidenceThenDeliverableThenSession() {
        IssueStatus resolved = IssueStatus.RESOLVED;
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> issue(resolved, IssueStatus.VERIFIED, Evidence.NONE, DeliverableHistory.NONE, null));
        Expect.failsWith(TransitionCode.DELIVERABLE_REQUIRED,
                () -> issue(resolved, IssueStatus.VERIFIED, EVIDENCE, DeliverableHistory.NONE, null));
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> issue(resolved, IssueStatus.VERIFIED, EVIDENCE, OWN_DELIV, null));
        assertThatCode(() -> issue(resolved, IssueStatus.VERIFIED, EVIDENCE, OTHER_DELIV, null))
                .doesNotThrowAnyException();
        DeliverableHistory mixed = new DeliverableHistory(List.of(ME.sessionKey(), OTHER.sessionKey()), List.of());
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> issue(resolved, IssueStatus.VERIFIED, EVIDENCE, mixed, null));
        DeliverableHistory mixedReversed = new DeliverableHistory(List.of(OTHER.sessionKey(), ME.sessionKey()), List.of());
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> issue(resolved, IssueStatus.VERIFIED, EVIDENCE, mixedReversed, null));
    }

    @Test
    void vrf011_theVerifierIsNoReporterEither() {
        IssueStatus resolved = IssueStatus.RESOLVED;
        AuthorId registrar = AuthorId.of("claude:registrar");
        AuthorId observer = AuthorId.of("codex:observer");
        AuthorId contributor = AuthorId.of("codex:contributor");
        AuthorId independent = AuthorId.of("claude:independent");
        DeliverableHistory facts = new DeliverableHistory(List.of(contributor.sessionKey()),
                List.of(registrar.sessionKey(), observer.sessionKey()));
        for (AuthorId participant : List.of(registrar, observer, contributor)) {
            Expect.failsWith(TransitionCode.SAME_SESSION, () -> resolved.requireTransitionTo(IssueStatus.VERIFIED,
                    EVIDENCE, facts, participant.sessionKey(), SELF, null));
        }
        assertThatCode(() -> resolved.requireTransitionTo(IssueStatus.VERIFIED, EVIDENCE, facts,
                independent.sessionKey(), SELF, null)).doesNotThrowAnyException();
        DeliverableHistory noReporter = new DeliverableHistory(List.of(contributor.sessionKey()), List.of());
        assertThatCode(() -> resolved.requireTransitionTo(IssueStatus.VERIFIED, EVIDENCE, noReporter,
                registrar.sessionKey(), SELF, null)).doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED, () -> resolved.requireTransitionTo(IssueStatus.VERIFIED,
                Evidence.NONE, facts, registrar.sessionKey(), SELF, null));
        Expect.failsWith(TransitionCode.DELIVERABLE_REQUIRED, () -> resolved.requireTransitionTo(IssueStatus.VERIFIED,
                EVIDENCE, new DeliverableHistory(List.of(), List.of(registrar.sessionKey())),
                registrar.sessionKey(), SELF, null));
        assertThat(DeliverableHistory.NONE.hasDeliverable()).isFalse();
        assertThat(DeliverableHistory.NONE.involves(registrar.sessionKey())).isFalse();
        assertThat(facts.involves(observer.sessionKey())).isTrue();
    }

    @Test
    void vrf001_onlyAVerifiedIssueOrSpecRefusesAContentChange() {
        for (IssueStatus status : IssueStatus.values()) {
            Issue issue = DomainFixtures.issue(status, status == IssueStatus.MERGED ? LIVE : null);
            if (status == IssueStatus.VERIFIED) {
                assertThat(Expect.failsWith(TransitionCode.VERIFIED_ENTITY, issue::requireNotVerified).args())
                        .containsExactly(issue.id().value());
            } else {
                assertThatCode(issue::requireNotVerified).as(status.name()).doesNotThrowAnyException();
            }
        }
        for (SpecStatus status : SpecStatus.values()) {
            Spec spec = DomainFixtures.spec(status);
            if (status == SpecStatus.VERIFIED) {
                assertThat(Expect.failsWith(TransitionCode.VERIFIED_ENTITY, spec::requireNotVerified).args())
                        .containsExactly(spec.id().value());
            } else {
                assertThatCode(spec::requireNotVerified).as(status.name()).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void dom013_leavingVerifiedNeedsEvidenceAfterTheDeliverableCheck() {
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> issue(IssueStatus.VERIFIED, IssueStatus.OPEN, Evidence.NONE, OTHER_DELIV, null));
        assertThatCode(() -> issue(IssueStatus.VERIFIED, IssueStatus.OPEN, EVIDENCE, OTHER_DELIV, null))
                .doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.DELIVERABLE_REQUIRED,
                () -> issue(IssueStatus.VERIFIED, IssueStatus.RESOLVED, Evidence.NONE, DeliverableHistory.NONE, null));
    }

    @Test
    void dom014_notAnIssueAndDisputedNeedEvidence() {
        for (IssueStatus to : List.of(IssueStatus.NOT_AN_ISSUE, IssueStatus.DISPUTED)) {
            Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                    () -> issue(IssueStatus.OPEN, to, Evidence.NONE, DeliverableHistory.NONE, null));
        }
    }

    @Test
    void dom015_mergeNeedsALiveTargetAndMergedIntoFollowsTheStatus() {
        for (MergeTarget bad : new MergeTarget[] {null, new MergeTarget(SELF, IssueStatus.OPEN),
                new MergeTarget(LIVE, IssueStatus.MERGED)}) {
            Expect.failsWith(TransitionCode.MERGE_TARGET_INVALID,
                    () -> issue(IssueStatus.OPEN, IssueStatus.MERGED, Evidence.NONE, DeliverableHistory.NONE, bad));
        }
        assertThatCode(() -> issue(IssueStatus.OPEN, IssueStatus.MERGED, Evidence.NONE, DeliverableHistory.NONE,
                new MergeTarget(LIVE, IssueStatus.RESOLVED))).doesNotThrowAnyException();

        Instant now = Instant.parse("2026-11-05T10:00:00Z");
        Issue open = DomainFixtures.issue(IssueStatus.OPEN, null);
        Issue merged = open.transitioned(IssueStatus.MERGED, LIVE, now);
        assertThat(merged.mergedInto()).isEqualTo(LIVE);
        assertThat(merged.transitioned(IssueStatus.OPEN, null, now).mergedInto()).isNull();
        Expect.invalid(() -> open.transitioned(IssueStatus.MERGED, null, now));
        Expect.invalid(() -> merged.transitioned(IssueStatus.OPEN, LIVE, now));
    }

    @Test
    void dom016_scopeOfATransition() {
        assertThat(Scope.forTransition(HistoryEntity.ISSUE, IssueStatus.OPEN, IssueStatus.VERIFIED))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.ISSUE, IssueStatus.VERIFIED, IssueStatus.OPEN))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.ISSUE, IssueStatus.OPEN, IssueStatus.DISPUTED))
                .isEqualTo(Scope.ISSUE_UPDATE);
        assertThat(Scope.forTransition(HistoryEntity.SPEC, SpecStatus.ACTIVE, SpecStatus.COMPLETED))
                .isEqualTo(Scope.SPEC_UPDATE);
        assertThat(Scope.forTransition(HistoryEntity.SPEC, SpecStatus.COMPLETED, SpecStatus.VERIFIED))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.TASK, TaskState.ACTIVE, TaskState.COMPLETED))
                .isEqualTo(Scope.TASK_UPDATE);
        assertThat(Scope.forTransition(HistoryEntity.TASK, TaskState.COMPLETED, TaskState.VERIFIED))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.CRITERION, CriterionState.UNKNOWN, CriterionState.MET))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.CRITERION, CriterionState.NOT_MET, CriterionState.UNKNOWN))
                .isEqualTo(Scope.VERIFY);
        Expect.invalid(() -> Scope.forTransition(HistoryEntity.DELIVERABLE, IssueStatus.OPEN, IssueStatus.DISPUTED));
    }

    @Test
    void spec20_specLifecycleEnforcesCompletionEvidenceAndIndependentReview() {
        AuthorId owner = AuthorId.of("claude:owner");
        AuthorId worker = AuthorId.of("codex:worker");
        AuthorId reviewer = AuthorId.of("claude:reviewer");
        SpecReports reports = new SpecReports(List.of(
                new SpecTransition(SpecStatus.ACTIVE, SpecStatus.COMPLETED, worker),
                new SpecTransition(SpecStatus.COMPLETED, SpecStatus.VERIFIED, reviewer),
                new SpecTransition(SpecStatus.VERIFIED, SpecStatus.COMPLETED, reviewer)));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED, () -> SpecStatus.ACTIVE.requireTransitionTo(
                SpecStatus.COMPLETED, Evidence.NONE, worker.sessionKey(), List.of(), new SpecReports(List.of())));
        Expect.failsWith(TransitionCode.TRANSITION_INVALID, () -> SpecStatus.ACTIVE.requireTransitionTo(
                SpecStatus.VERIFIED, EVIDENCE, reviewer.sessionKey(), List.of(), reports));
        Expect.failsWith(TransitionCode.SAME_SESSION, () -> SpecStatus.COMPLETED.requireTransitionTo(
                SpecStatus.VERIFIED, EVIDENCE, owner.sessionKey(), List.of(owner.sessionKey()), reports));
        Expect.failsWith(TransitionCode.SAME_SESSION, () -> SpecStatus.COMPLETED.requireTransitionTo(
                SpecStatus.VERIFIED, EVIDENCE, worker.sessionKey(), List.of(owner.sessionKey()), reports));
        assertThatCode(() -> SpecStatus.COMPLETED.requireTransitionTo(
                SpecStatus.VERIFIED, EVIDENCE, reviewer.sessionKey(), List.of(owner.sessionKey()), reports))
                .doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED, () -> SpecStatus.VERIFIED.requireTransitionTo(
                SpecStatus.ACTIVE, Evidence.NONE, reviewer.sessionKey(), List.of(), reports));
    }

    private static void task(TaskState from, TaskState to, Evidence evidence, AuthorId actor, AuthorId owner,
            TaskReports reports) {
        if (owner == null) {
            from.requireTransitionTo(to, evidence, actor.sessionKey(), reports);
        } else {
            from.requireTransitionTo(to, evidence, actor.sessionKey(), List.of(owner.sessionKey()), reports);
        }
    }

    @Test
    void dom017_aTaskWithoutOwnerAndATaskOwnedByAnotherSessionAgree() {
        TaskReports none = new TaskReports(List.of());
        assertThatCode(() -> TaskState.COMPLETED.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                OTHER.sessionKey(), List.of(ME.sessionKey()), none)).doesNotThrowAnyException();
        assertThatCode(() -> TaskState.COMPLETED.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                OTHER.sessionKey(), none)).doesNotThrowAnyException();
        assertThatCode(() -> TaskState.COMPLETED.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                OTHER.sessionKey(), List.of(), none)).doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.SAME_SESSION, () -> TaskState.COMPLETED.requireTransitionTo(
                TaskState.VERIFIED, EVIDENCE, OTHER.sessionKey(), List.of(OTHER.sessionKey()), none));
        Expect.failsWith(TransitionCode.SAME_SESSION, () -> TaskState.COMPLETED.requireTransitionTo(
                TaskState.VERIFIED, EVIDENCE, OTHER.sessionKey(), List.of(new SessionKey(OTHER.value())), none));
    }

    @Test
    void vrf014_theVerifierIsNoRegistrantAndNoReporterOfTheTaskAtAnyTime() {
        AuthorId creator = AuthorId.of("claude:creator");
        AuthorId owner = AuthorId.of("codex:owner");
        AuthorId first = AuthorId.of("claude:first");
        AuthorId second = AuthorId.of("codex:second");
        AuthorId reviewer = AuthorId.of("codex:reviewer");
        AuthorId independent = AuthorId.of("claude:independent");
        TaskReports history = new TaskReports(List.of(
                new TaskTransition(TaskState.ACTIVE, TaskState.COMPLETED, first),
                new TaskTransition(TaskState.COMPLETED, TaskState.VERIFIED, reviewer),
                new TaskTransition(TaskState.VERIFIED, TaskState.COMPLETED, reviewer),
                new TaskTransition(TaskState.ACTIVE, TaskState.COMPLETED, second)));
        List<SessionKey> registrants = List.of(creator.sessionKey(), owner.sessionKey());
        for (AuthorId refused : List.of(creator, owner, first, second)) {
            Expect.failsWith(TransitionCode.SAME_SESSION, () -> TaskState.COMPLETED.requireTransitionTo(
                    TaskState.VERIFIED, EVIDENCE, refused.sessionKey(), registrants, history));
        }
        for (AuthorId allowed : List.of(reviewer, independent)) {
            assertThatCode(() -> TaskState.COMPLETED.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                    allowed.sessionKey(), registrants, history)).doesNotThrowAnyException();
        }
        assertThatCode(() -> TaskState.COMPLETED.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                creator.sessionKey(), List.of(), new TaskReports(List.of()))).doesNotThrowAnyException();
        assertThat(history.reportedBy(first.sessionKey())).isTrue();
        assertThat(history.reportedBy(second.sessionKey())).isTrue();
        assertThat(history.reportedBy(reviewer.sessionKey())).isFalse();
        assertThat(history.latestReporter()).contains(second.sessionKey());
    }

    @Test
    void dom018_reportsAnswerWhetherASessionReportedTheTask() {
        TaskReports history = new TaskReports(List.of(
                new TaskTransition(TaskState.ACTIVE, TaskState.COMPLETED, ME),
                new TaskTransition(TaskState.COMPLETED, TaskState.VERIFIED, OTHER)));
        assertThat(history.reportedBy(ME.sessionKey())).isTrue();
        assertThat(history.reportedBy(OTHER.sessionKey())).isFalse();
        assertThat(new TaskReports(List.of()).reportedBy(ME.sessionKey())).isFalse();
    }

    @Test
    void dom017_taskPolicy() {
        TaskReports none = new TaskReports(List.of());
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> task(TaskState.PLANNED, TaskState.UNKNOWN, EVIDENCE, ME, null, none));
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> task(TaskState.ACTIVE, TaskState.ACTIVE, EVIDENCE, ME, null, none));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> task(TaskState.ACTIVE, TaskState.COMPLETED, Evidence.NONE, ME, null, none));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> task(TaskState.VERIFIED, TaskState.ACTIVE, Evidence.NONE, OTHER, null, none));
        assertThatCode(() -> task(TaskState.VERIFIED, TaskState.ACTIVE, EVIDENCE, OTHER, null, none))
                .doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> task(TaskState.ACTIVE, TaskState.VERIFIED, EVIDENCE, OTHER, null, none));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> task(TaskState.COMPLETED, TaskState.VERIFIED, Evidence.NONE, OTHER, null, none));
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> task(TaskState.COMPLETED, TaskState.VERIFIED, EVIDENCE, OTHER, OTHER, none));
        TaskReports reportedByMe = new TaskReports(
                List.of(new TaskTransition(TaskState.ACTIVE, TaskState.COMPLETED, ME)));
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> task(TaskState.COMPLETED, TaskState.VERIFIED, EVIDENCE, ME, null, reportedByMe));
        assertThatCode(() -> task(TaskState.COMPLETED, TaskState.VERIFIED, EVIDENCE, OTHER, null, reportedByMe))
                .doesNotThrowAnyException();
    }

    @Test
    void dom018_reporterIsTheLatestReportThatDidNotComeFromVerified() {
        TaskReports history = new TaskReports(List.of(
                new TaskTransition(TaskState.ACTIVE, TaskState.COMPLETED, ME),
                new TaskTransition(TaskState.COMPLETED, TaskState.VERIFIED, OTHER),
                new TaskTransition(TaskState.VERIFIED, TaskState.COMPLETED, OTHER)));
        assertThat(history.latestReporter()).contains(new SessionKey("claude:me"));
        assertThat(new TaskReports(List.of()).latestReporter()).isEmpty();
    }

    @Test
    void dom019_criterionPolicy() {
        CriterionReports none = new CriterionReports(List.of());
        for (CriterionState to : List.of(CriterionState.COMPLETED, CriterionState.MET, CriterionState.NOT_MET)) {
            Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED, () -> CriterionState.UNKNOWN.requireTransitionTo(to,
                    Evidence.NONE, ME.sessionKey(), List.of(), none));
            assertThatCode(() -> CriterionState.UNKNOWN.requireTransitionTo(to, EVIDENCE, ME.sessionKey(), List.of(),
                    none)).doesNotThrowAnyException();
        }
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED, () -> CriterionState.MET.requireTransitionTo(
                CriterionState.UNKNOWN, Evidence.NONE, ME.sessionKey(), List.of(), none));
        assertThatCode(() -> CriterionState.MET.requireTransitionTo(CriterionState.UNKNOWN, EVIDENCE,
                ME.sessionKey(), List.of(), none)).doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> CriterionState.MET.requireTransitionTo(CriterionState.MET, EVIDENCE, ME.sessionKey(), List.of(),
                        none));
    }

    @Test
    void vrf017_theSessionThatRegisteredOrCompletedACriterionDoesNotJudgeIt() {
        List<SessionKey> registrants = List.of(ME.sessionKey());
        CriterionReports completedByOther = new CriterionReports(
                List.of(new CriterionTransition(CriterionState.ACTIVE, CriterionState.COMPLETED, OTHER)));
        for (CriterionState to : List.of(CriterionState.MET, CriterionState.NOT_MET)) {
            Expect.failsWith(TransitionCode.SAME_SESSION, () -> CriterionState.UNKNOWN.requireTransitionTo(to,
                    EVIDENCE, ME.sessionKey(), registrants, completedByOther));
            Expect.failsWith(TransitionCode.SAME_SESSION, () -> CriterionState.COMPLETED.requireTransitionTo(to,
                    EVIDENCE, OTHER.sessionKey(), registrants, completedByOther));
            assertThatCode(() -> CriterionState.UNKNOWN.requireTransitionTo(to, EVIDENCE,
                    AuthorId.of("claude:independent").sessionKey(), registrants, completedByOther))
                    .doesNotThrowAnyException();
        }
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED, () -> CriterionState.MET.requireTransitionTo(
                CriterionState.UNKNOWN, Evidence.NONE, ME.sessionKey(), registrants, completedByOther));
        assertThatCode(() -> CriterionState.MET.requireTransitionTo(CriterionState.UNKNOWN, EVIDENCE,
                ME.sessionKey(), registrants, completedByOther)).doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED, () -> CriterionState.UNKNOWN.requireTransitionTo(
                CriterionState.MET, Evidence.NONE, ME.sessionKey(), registrants, completedByOther));
    }
}
