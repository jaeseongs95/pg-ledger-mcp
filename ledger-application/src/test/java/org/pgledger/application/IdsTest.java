package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchResult;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.context.ActorScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.TaskId;

/** SVC-IDS-01 .. SVC-IDS-13: server-assigned ids (PostgreSQL of the test PC). */
@LedgerServiceTest
class IdsTest {

    private static SaveCommand defect(Scenario s, String id, String title) {
        return s.create("defect", id, s.defectFields(title));
    }

    @Test
    @DisplayName("SVC-IDS-01: the project code, not the project id, forms the prefix; ids count from 1")
    void ids01_firstDefects(Scenario s) {
        SaveResult one = s.newDefect("one");
        SaveResult two = s.newDefect("two");
        assertThat(one.id()).isEqualTo(s.code() + "-D-0001");
        assertThat(two.id()).isEqualTo(s.code() + "-D-0002");
        assertThat(one.version()).isEqualTo(1);
        assertThat(s.defect(one.id()).status()).isEqualTo(DefectStatus.OPEN);
    }

    @Test
    @DisplayName("SVC-IDS-02: an explicit defect id of another project code")
    void ids02_prefixMismatch(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, "OTHER-D-0009", "x")),
                ValidationCode.ID_PREFIX_MISMATCH);
    }

    @Test
    @DisplayName("SVC-IDS-03: an explicit id raises the counter, the next id is never a duplicate")
    void ids03_explicitRaisesTheCounter(Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        s.newDefect("three");
        SaveResult explicit = s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, s.defectId(100), "x"));
        assertThat(explicit.id()).isEqualTo(s.defectId(100));
        assertThat(s.newDefect("next").id()).isEqualTo(s.defectId(101));
    }

    @Test
    @DisplayName("SVC-IDS-04: an explicit id that exists")
    void ids04_duplicate(Scenario s) {
        SaveResult one = s.newDefect("one");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, one.id(), "again")),
                ConflictCode.DUPLICATE_KEY);
    }

    @Test
    @DisplayName("SVC-IDS-05: failed requests consume no number")
    void ids05_failuresConsumeNothing(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(null, "defect", null, 0L,
                Fields.of(s.defectFields(" ")), Scenario.REASON)), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.NOGRANT, defect(s, null, "x")),
                org.pgledger.domain.error.TrustCode.SESSION_NOT_GRANTED);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(null, "defect", null, 0L,
                Fields.of(s.defectFields("x")), "short")), ValidationCode.REASON_INVALID);
        assertThat(s.newDefect("good").id()).isEqualTo(s.defectId(1));
    }

    @Test
    @DisplayName("SVC-IDS-06: tasks are numbered like defects, explicit free-text ids are accepted")
    void ids06_tasks(Scenario s) {
        assertThat(s.newTask("auto").id()).isEqualTo(s.taskId(1));
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("task", s.code() + "-T-1", s.taskFields("explicit"))).id()).isEqualTo(s.code() + "-T-1");
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("task", s.code() + "-T|PIPE", s.taskFields("pipe"))).id()).isEqualTo(s.code() + "-T|PIPE");
    }

    @Test
    @DisplayName("SVC-IDS-07: an issued task id is never a duplicate of a task that exists")
    void ids07_issuedTaskIdSkipsExisting(Scenario s) {
        s.newTask("first");
        TaskFields fields = TaskFields.of(Patch.set(s.projectId()), Patch.set("planted"), Patch.set("p"),
                Patch.set("s"), Patch.absent(), Patch.absent(), Patch.absent());
        Task planted = Task.plan(TaskId.of(s.taskId(2)), fields, s.ctx().clock().instant());
        s.ctx().tx(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.OWNER)
                .call(() -> s.bean(TaskRepository.class).save(planted)));
        assertThat(s.newTask("next").id()).isEqualTo(s.taskId(3));
    }

    @Test
    @DisplayName("SVC-IDS-08: two projects number independently")
    void ids08_independentProjects(Scenario s) {
        String other = s.otherProject();
        s.grant(Scenario.AUTHOR, other, "defect", "task");
        var fields = s.defectFields("other");
        fields.put("project_id", other);
        SaveResult otherDefect = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields));
        assertThat(otherDefect.id()).endsWith("-D-0001").doesNotStartWith(s.code());
        assertThat(s.newDefect("mine").id()).isEqualTo(s.defectId(1));
        assertThat(s.newTask("mine").id()).isEqualTo(s.taskId(1));
    }

    @Test
    @DisplayName("SVC-IDS-09: a condition needs an explicit id")
    void ids09_conditionNeedsAnId(Scenario s) {
        var failure = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("condition", null, s.conditionFields("r", "c"))), ValidationCode.VALIDATION_FAILED);
        assertThat(failure.args()).first().isEqualTo("id");
    }

    @Test
    @DisplayName("SVC-IDS-10: 50 parallel defects and 50 parallel tasks get distinct sequential ids")
    void ids10_parallelCreates(Scenario s) {
        String actor = s.actorWith("defect", "task");
        List<Callable<SaveResult>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int n = i;
            calls.add(() -> s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields("d" + n))));
            calls.add(() -> s.bean(SaveService.class).save(actor, s.create("task", null, s.taskFields("t" + n))));
        }
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls, 20);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(outcomes.stream().map(o -> o.value().id()).filter(id -> id.contains("-D-")).toList())
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 50).mapToObj(s::defectId).toList());
        assertThat(outcomes.stream().map(o -> o.value().id()).filter(id -> id.contains("-T-")).toList())
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 50).mapToObj(s::taskId).toList());
    }

    @Test
    @DisplayName("SVC-IDS-11: the number grows past 9999")
    void ids11_pastNineThousand(Scenario s) {
        s.ctx().jdbc().sql("UPDATE project SET next_defect_no = 9999 WHERE id = ?").param(s.projectId()).update();
        assertThat(s.newDefect("big").id()).isEqualTo(s.code() + "-D-10000");
    }

    @Test
    @DisplayName("SVC-IDS-12: a batch of creates is consecutive, and a failing batch gives its numbers back")
    void ids12_batches(Scenario s) {
        BatchOpInput create = new BatchOpInput.Save("defect", Fields.of(s.defectFields("b")), Scenario.REASON, null, null, null);
        BatchOpInput failing = new BatchOpInput.Transition("defect", s.defectId(99), 1L, "DISPUTED", Scenario.REASON,
                Scenario.EVIDENCE, null, null, null);
        Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, null, List.of(create, create, failing)),
                org.pgledger.domain.error.LookupCode.NOT_FOUND);
        BatchResult ok = s.bean(BatchService.class).batch(Scenario.AUTHOR, null, List.of(create, create));
        assertThat(ok.results().stream().map(r -> ((SaveResult) r).id()))
                .containsExactly(s.defectId(1), s.defectId(2));
    }

    @Test
    @DisplayName("SVC-IDS-13: history, fix, case and grant ids are distinct and gapless after 64 concurrent writes")
    void ids13_surrogateKeys(Scenario s) {
        List<SaveResult> defects = IntStream.range(0, 32).mapToObj(i -> s.newDefect("d" + i)).toList();
        List<Callable<WriteResult>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String defect = defects.get(i).id();
            String commit = Scenario.commit((char) ('a' + i % 6));
            calls.add(() -> s.fix(Scenario.AUTHOR, defect, commit));
            String caseOn = defects.get(16 + i).id();
            calls.add(() -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null,
                    caseOn, "seen", Scenario.REASON, null, null, null)));
            calls.add(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null,
                    s.taskFields("t"))));
            String author = "claude:grant" + i;
            s.ctx().enroll(author);
            calls.add(() -> {
                s.bean(GrantService.class).grant(new GrantService.GrantCommand(author, s.projectId(),
                        List.of("defect"), null, null), TestOwners.OWNER);
                return null;
            });
        }
        assertThat(Parallel.all(calls, 16)).allSatisfy(o -> assertThat(o.failure()).isNull());
        Stream.of("history", "defect_fix", "defect_case", "session_grant").forEach(table -> {
            assertThat(s.count("SELECT COUNT(DISTINCT id) FROM " + table)).as(table).isEqualTo(
                    s.count("SELECT COUNT(*) FROM " + table));
            assertThat(s.count("SELECT MAX(id) FROM " + table)).as(table + " has no gaps").isEqualTo(
                    s.count("SELECT COUNT(*) FROM " + table));
        });
    }
}
