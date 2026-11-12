package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.GetService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.HistoryService.HistoryQuery;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.Rows;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.apptest.LedgerTestRunners;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.TaskId;

/**
 * REH3-ID-06: the DBMS that ignores case. The test makes the PostgreSQL of the test PC one for the columns of the ids
 * (a nondeterministic ICU collation of the tests only, {@code CREATE COLLATION}, which is no part of the product): there
 * {@code WHERE id = 'ags:a07-a'} finds {@code AGS:A07-A}. The lookups of the ledger compare the id exactly in Java after the
 * fetch, so the other case is not found, on a DBMS of either kind.
 */
class CaseInsensitiveDbmsTest {

    private static void ignoreCaseInTheIdColumns(LedgerAppContext ctx) {
        var jdbc = ctx.jdbc();
        jdbc.sql("CREATE COLLATION reh3_ci (provider = icu, locale = 'und-u-ks-level2', deterministic = false)").update();
        jdbc.sql("ALTER TABLE defect_task DROP CONSTRAINT fk_defect_task_task").update();
        jdbc.sql("ALTER TABLE condition_link DROP CONSTRAINT fk_condition_link_task").update();
        jdbc.sql("ALTER TABLE condition_link DROP CONSTRAINT fk_condition_link_cond").update();
        jdbc.sql("ALTER TABLE task ALTER COLUMN id TYPE VARCHAR(120) COLLATE reh3_ci").update();
        jdbc.sql("ALTER TABLE release_condition ALTER COLUMN id TYPE VARCHAR(120) COLLATE reh3_ci").update();
        jdbc.sql("ALTER TABLE history ALTER COLUMN entity_id TYPE VARCHAR(300) COLLATE reh3_ci").update();
    }

    @Test
    @DisplayName("REH3-ID-06 on a DBMS that ignores case in = the lookups of a task and a condition still compare the id exactly: repositories, Rows, the detail, ledger_get, the history, a transition and a link answer not found for the other case, and a create of it is DUPLICATE_KEY")
    void id06_exactComparisonInJava() {
        LedgerTestRunners.withoutCategories(ctx -> {
            ignoreCaseInTheIdColumns(ctx);
            Scenario s = new Scenario(ctx);
            String task = s.code() + ":A07-A";
            String condition = s.code() + ":C1";
            s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", task, s.taskFields("t")));
            s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("condition", condition, s.conditionFields("r", "c")));
            String lowerTask = task.toLowerCase(Locale.ROOT);
            String lowerCondition = condition.toLowerCase(Locale.ROOT);

            // the premise: this database does return the row of another case
            assertThat(s.count("SELECT COUNT(*) FROM task WHERE id = ?", lowerTask)).isEqualTo(1);
            assertThat(s.count("SELECT COUNT(*) FROM release_condition WHERE id = ?", lowerCondition)).isEqualTo(1);

            TaskRepository tasks = ctx.bean(TaskRepository.class);
            ConditionRepository conditions = ctx.bean(ConditionRepository.class);
            assertThat(tasks.findById(TaskId.of(task))).isPresent();
            assertThat(tasks.findById(TaskId.of(lowerTask))).isEmpty();
            assertThat(tasks.existsById(TaskId.of(lowerTask))).isFalse();
            assertThat(conditions.findById(ConditionId.of(condition))).isPresent();
            assertThat(conditions.findById(ConditionId.of(lowerCondition))).isEmpty();
            assertThat(conditions.existsById(ConditionId.of(lowerCondition))).isFalse();

            Rows rows = ctx.bean(Rows.class);
            Expect.failure(() -> rows.task(TaskId.of(lowerTask)), LookupCode.NOT_FOUND);
            Expect.failure(() -> rows.lockedTask(TaskId.of(lowerTask), null), LookupCode.NOT_FOUND);
            Expect.failure(() -> rows.lockedCondition(ConditionId.of(lowerCondition), null), LookupCode.NOT_FOUND);
            assertThat(rows.lockedTask(TaskId.of(task), null).id().value()).isEqualTo(task);

            Expect.failure(() -> ctx.bean(DetailService.class).task(lowerTask), LookupCode.NOT_FOUND);
            assertThat(ctx.bean(DetailService.class).task(task).id()).isEqualTo(task);
            GetService.Result got = ctx.bean(GetService.class).get(List.of(lowerTask, task, lowerCondition));
            assertThat(got.items()).extracting(GetService.Item::id).containsExactly(task);
            assertThat(got.notFound()).containsExactlyInAnyOrder(lowerTask, lowerCondition);

            HistoryService history = ctx.bean(HistoryService.class);
            assertThat(history.history(new HistoryQuery(null, null, null, "task", lowerTask, null, null, null, null, null,
                    false, 50, null)).items()).isEmpty();
            assertThat(history.history(new HistoryQuery(null, null, null, "task", task, null, null, null, null, null,
                    false, 50, null)).items()).hasSize(1);

            Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(null,
                    "task", lowerTask, 1L, "ACTIVE", Scenario.REASON, null, null, null)), LookupCode.NOT_FOUND);
            Expect.failure(() -> s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "condition_task",
                    condition, lowerTask, Scenario.REASON, null)), LookupCode.NOT_FOUND);
            Expect.failure(() -> s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task",
                    s.newDefect("d").id(), lowerTask, Scenario.REASON, null)), LookupCode.NOT_FOUND);
            Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", lowerTask,
                    s.taskFields("sibling"))), ConflictCode.DUPLICATE_KEY);
            assertThat(s.count("SELECT COUNT(*) FROM task WHERE project_id = ?", s.projectId())).isEqualTo(1);
        });
    }
}
