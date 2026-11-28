// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.RevokeCommand;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchResult;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.application.write.WriteResult.TransitionResult;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.TrustCode;
import org.veridger.testing.TestOwners;

/**
 * R9-D0056: a replay of a write that was answered returns the stored response when only the world has changed (its
 * state, its version), and is refused when the author lost the grant that the answered call needed. The scope a
 * transition needs depends on the state (a verification needs {@code verify} and the reviewer channel); the scope of a
 * replay is the one of the call that was answered, not the one of the state the entity has now.
 */
@LedgerServiceTest
class ReplayAfterChangeTest {

    /** A task that an author moved to ACTIVE; then another author completes it and the reviewer verifies it. */
    private record Verified(String taskId, TransitionCommand started, TransitionResult answered) {
    }

    private static Verified startedThenVerified(Scenario s, String starter, boolean viaReview) {
        SaveResult task = s.newTask("t");
        TransitionCommand started = s.move("task", task.id(), 1, "ACTIVE", null);
        TransitionResult answered = viaReview ? s.bean(ReviewService.class).review(starter, started)
                : s.bean(TransitionService.class).transition(starter, started);
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("task", task.id(), 2, "COMPLETED", Scenario.EVIDENCE));
        s.bean(ReviewService.class).review(Scenario.REVIEWER,
                s.move("task", task.id(), 3, "VERIFIED", Scenario.EVIDENCE));
        assertThat(s.task(task.id()).state().name()).isEqualTo("VERIFIED");
        return new Verified(task.id(), started, answered);
    }

    private static long historyRows(Scenario s, String taskId) {
        return s.count("SELECT COUNT(*) FROM history WHERE entity = 'TASK' AND entity_id = ?", taskId);
    }

    @Test
    @DisplayName("R9-D0056-a: the author channel replays the stored answer of a transition although the task has entered VERIFIED")
    void replayOnTheAuthorChannelAfterTheTaskWasVerified(Scenario s) {
        Verified v = startedThenVerified(s, Scenario.AUTHOR, false);
        long rows = historyRows(s, v.taskId());
        assertThat(s.bean(TransitionService.class).transition(Scenario.AUTHOR, v.started())).isEqualTo(v.answered());
        assertThat(historyRows(s, v.taskId())).as("a replay writes nothing").isEqualTo(rows);
    }

    @Test
    @DisplayName("R9-D0056-b: an author with the task scope only replays the stored answer on the reviewer channel after the task was verified")
    void replayWithoutTheVerifyScopeAfterTheTaskWasVerified(Scenario s) {
        String actor = s.actorWith("task");
        Verified v = startedThenVerified(s, actor, true);
        long rows = historyRows(s, v.taskId());
        assertThat(s.bean(ReviewService.class).review(actor, v.started())).isEqualTo(v.answered());
        assertThat(historyRows(s, v.taskId())).as("a replay writes nothing").isEqualTo(rows);
    }

    @Test
    @DisplayName("R9-D0056-c: a replay is refused when the grant of the answered call was revoked, and the scope it names is the one of the answered call")
    void replayIsRefusedWhenTheAnsweredGrantWasRevoked(Scenario s) {
        String actor = s.actorWith("task");
        Verified v = startedThenVerified(s, actor, true);
        s.bean(GrantService.class).revoke(new RevokeCommand(actor, null, null), TestOwners.OWNER);
        LedgerException refused = Expect.failure(() -> s.bean(ReviewService.class).review(actor, v.started()),
                TrustCode.SESSION_NOT_GRANTED);
        assertThat(refused.args()).as("the answered move from PLANNED to ACTIVE needs task_update")
                .containsExactly(actor, "task_update", s.projectId());
    }

    @Test
    @DisplayName("R9-D0056-d: a replay of a batch answers every operation from its receipt after the task was verified")
    void replayOfABatchAfterTheTaskWasVerified(Scenario s) {
        SaveResult task = s.newTask("t");
        String batchKey = Scenario.uuid();
        List<BatchOpInput> ops = List.of(new BatchOpInput.Transition("task", task.id(), 1L, "ACTIVE", Scenario.REASON,
                null, null, null, null));
        BatchResult answered = s.bean(BatchService.class).batch(Scenario.AUTHOR, batchKey, ops);
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("task", task.id(), 2, "COMPLETED", Scenario.EVIDENCE));
        s.bean(ReviewService.class).review(Scenario.REVIEWER,
                s.move("task", task.id(), 3, "VERIFIED", Scenario.EVIDENCE));
        long rows = historyRows(s, task.id());
        assertThat(s.bean(BatchService.class).batch(Scenario.AUTHOR, batchKey, ops)).isEqualTo(answered);
        assertThat(historyRows(s, task.id())).as("a replay writes nothing").isEqualTo(rows);
    }
}
