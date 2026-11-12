package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.history.Change;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.HistoryService.HistoryQuery;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;

/**
 * REH3-ID-04, REH3-ID-05, REH3-ID-07, REH3-TXT-01 .. 04 and REH3-LEN-01 (PostgreSQL of the test PC): the ids of 1.x in
 * any case, the refusal of ids that differ only in case, the texts that may be empty, and the longer columns.
 */
@LedgerServiceTest
class OneXIdsTest {

    private static String create(Scenario s, String entity, String id, Map<String, String> fields) {
        return s.bean(SaveService.class).save(Scenario.AUTHOR, s.create(entity, id, fields)).id();
    }

    private static Map<String, String> inProject(Map<String, String> fields, String project) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.put("project_id", project);
        return copy;
    }

    @Test
    @DisplayName("REH3-ID-04 an id of a task or condition that equals an existing one ignoring case is DUPLICATE_KEY naming the existing id; nothing is written; an unrelated id is created")
    void id04_caseCollisionsAreRefused(Scenario s) {
        String task = s.code() + ":A01";
        String condition = s.code() + ":C1";
        create(s, "task", task, s.taskFields("first"));
        create(s, "condition", condition, s.conditionFields("r", "c1"));
        long history = s.count("SELECT COUNT(*) FROM history WHERE entity_id IN (?, ?)", task.toLowerCase(Locale.ROOT),
                condition.toLowerCase(Locale.ROOT));

        LedgerException lowerTask = Expect.failure(() -> create(s, "task", task.toLowerCase(Locale.ROOT),
                s.taskFields("sibling")), ConflictCode.DUPLICATE_KEY);
        LedgerException sameTask = Expect.failure(() -> create(s, "task", task, s.taskFields("again")),
                ConflictCode.DUPLICATE_KEY);
        LedgerException lowerCondition = Expect.failure(() -> create(s, "condition", condition.toLowerCase(Locale.ROOT),
                s.conditionFields("r2", "c2")), ConflictCode.DUPLICATE_KEY);
        LedgerException sameCondition = Expect.failure(() -> create(s, "condition", condition,
                s.conditionFields("r3", "c3")), ConflictCode.DUPLICATE_KEY);

        assertThat(lowerTask.args()).containsExactly("task " + task);
        assertThat(sameTask.args()).containsExactly("task " + task);
        assertThat(lowerCondition.args()).containsExactly("condition " + condition);
        assertThat(sameCondition.args()).containsExactly("condition " + condition);
        assertThat(s.count("SELECT COUNT(*) FROM task WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM release_condition WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE entity_id IN (?, ?)", task.toLowerCase(Locale.ROOT),
                condition.toLowerCase(Locale.ROOT))).isEqualTo(history);
        assertThat(create(s, "task", s.code() + ":A02", s.taskFields("second"))).isEqualTo(s.code() + ":A02");
        assertThat(s.count("SELECT COUNT(*) FROM task WHERE id_key = ?", task.toUpperCase(Locale.ROOT))).isEqualTo(1);
    }

    @Test
    @DisplayName("REH3-ID-05 two creates at the same moment that differ only in case (in two projects, so the write gate does not serialise them): exactly one succeeds, the other is DUPLICATE_KEY, one row stays, no raw exception reaches the caller")
    void id05_race(Scenario s) {
        String other = s.otherProject();
        String second = "claude:reh3-" + Scenario.uuid().substring(0, 8);
        s.grant(second, other, "task");
        SaveService save = s.bean(SaveService.class);
        for (int round = 0; round < 20; round++) {
            int i = round;
            String task = s.code() + ":R" + i;
            String condition = s.code() + ":RC" + i;
            String exactTwice = s.code() + ":X" + i;
            List<Callable<SaveResult>> calls = new ArrayList<>();
            calls.add(() -> save.save(Scenario.AUTHOR, s.create("task", task, s.taskFields("one"))));
            calls.add(() -> save.save(second, s.create("task", task.toLowerCase(Locale.ROOT),
                    inProject(s.taskFields("two"), other))));
            calls.add(() -> save.save(Scenario.AUTHOR, s.create("condition", condition, s.conditionFields("r", "a" + i))));
            calls.add(() -> save.save(second, s.create("condition", condition.toLowerCase(Locale.ROOT),
                    inProject(s.conditionFields("r", "b" + i), other))));
            calls.add(() -> save.save(Scenario.AUTHOR, s.create("task", exactTwice, s.taskFields("x1"))));
            calls.add(() -> save.save(second, s.create("task", exactTwice, inProject(s.taskFields("x2"), other))));

            List<Outcome<SaveResult>> outcomes = Parallel.all(calls);

            for (int pair = 0; pair < 6; pair += 2) {
                Outcome<SaveResult> a = outcomes.get(pair);
                Outcome<SaveResult> b = outcomes.get(pair + 1);
                assertThat(a.succeeded() ^ b.succeeded()).as("exactly one of pair %s of round %s", pair / 2, i).isTrue();
                Throwable failure = a.succeeded() ? b.failure() : a.failure();
                assertThat(failure).isInstanceOf(LedgerException.class);
                assertThat(((LedgerException) failure).code()).isEqualTo(ConflictCode.DUPLICATE_KEY);
            }
            assertThat(s.count("SELECT COUNT(*) FROM task WHERE id_key = ?", task.toUpperCase(Locale.ROOT))).isEqualTo(1);
            assertThat(s.count("SELECT COUNT(*) FROM release_condition WHERE id_key = ?",
                    condition.toUpperCase(Locale.ROOT))).isEqualTo(1);
            assertThat(s.count("SELECT COUNT(*) FROM task WHERE id_key = ?", exactTwice.toUpperCase(Locale.ROOT)))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("REH3-ID-07 the history of a task is listed by the id as it was written and not by the same id in another case")
    void id07_historyByExactId(Scenario s) {
        String task = s.code() + ":Hi-a";
        create(s, "task", task, s.taskFields("t"));
        HistoryService history = s.bean(HistoryService.class);

        var exact = history.history(new HistoryQuery(null, null, null, "task", task, null, null, null, null, null,
                false, 50, null));
        var other = history.history(new HistoryQuery(null, null, null, "task", task.toLowerCase(Locale.ROOT), null, null,
                null, null, null, false, 50, null));

        assertThat(exact.items()).hasSize(1);
        assertThat(exact.items().getFirst().entityId()).isEqualTo(task);
        assertThat(other.items()).isEmpty();
    }

    @Test
    @DisplayName("REH3-TXT-01 a task created with purpose \"\" and success_condition \"   \" is accepted: both columns are NULL, the detail returns \"\", the history of the create holds no ''")
    void txt01_emptyTextsOfATask(Scenario s) {
        Map<String, String> fields = s.taskFields("t");
        fields.put("purpose", "");
        fields.put("success_condition", "   ");

        SaveResult created = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));

        assertThat(s.count("SELECT COUNT(*) FROM task WHERE id = ? AND purpose IS NULL AND success_condition IS NULL",
                created.id())).isEqualTo(1);
        DetailService.TaskDetail detail = s.bean(DetailService.class).task(created.id());
        assertThat(detail.purpose()).isEmpty();
        assertThat(detail.successCondition()).isEmpty();
        assertThat(s.changes(created.historyId()).entries().values()).noneMatch(change -> "".equals(change.after()));
    }

    @Test
    @DisplayName("REH3-TXT-02 purpose, behavior and condition_text have to be given on a create: absent or null is REQUIRED with the path of the field")
    void txt02_theFieldHasToBeGiven(Scenario s) {
        Map<String, String> noPurpose = s.taskFields("t");
        noPurpose.remove("purpose");
        Map<String, String> nullPurpose = s.taskFields("t");
        nullPurpose.put("purpose", null);
        Map<String, String> noBehavior = s.defectFields("d");
        noBehavior.remove("behavior");
        Map<String, String> noText = s.conditionFields("r", "c");
        noText.remove("condition_text");

        for (Object[] attempt : new Object[][] {{"task", null, noPurpose, "purpose"}, {"task", null, nullPurpose, "purpose"},
                {"defect", null, noBehavior, "behavior"}, {"condition", s.conditionId("1"), noText, "condition_text"}}) {
            LedgerException refused = Expect.failure(() -> create(s, (String) attempt[0], (String) attempt[1],
                    cast(attempt[2])), ValidationCode.VALIDATION_FAILED);
            assertThat(((ValidationException) refused).issues().getFirst().path()).isEqualTo(attempt[3]);
            assertThat(((ValidationException) refused).issues().getFirst().reason().name()).isEqualTo("REQUIRED");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> cast(Object fields) {
        return (Map<String, String>) fields;
    }

    @Test
    @DisplayName("REH3-TXT-03 an edit that sets purpose to \"\" stores NULL, the history records [old, null] and before_data holds the old text; the same edit again changes nothing; the read returns \"\"")
    void txt03_editToEmpty(Scenario s) {
        SaveResult task = s.newTask("t");

        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", task.id(), 1,
                Map.of("purpose", "")));

        assertThat(s.count("SELECT COUNT(*) FROM task WHERE id = ? AND purpose IS NULL", task.id())).isEqualTo(1);
        assertThat(s.changes(edited.historyId()).entries()).containsExactly(Map.entry("purpose",
                new Change("purpose of t", null)));
        assertThat(s.before(edited.historyId(), Snapshot.TaskRow.class).purpose()).isEqualTo("purpose of t");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", task.id(), 2,
                Map.of("purpose", ""))), TransitionCode.NOTHING_CHANGES);
        assertThat(s.bean(DetailService.class).task(task.id()).purpose()).isEmpty();
    }

    @Test
    @DisplayName("REH3-TXT-04 behavior and root_cause of a defect and condition_text of a condition may be set to \"\": NULL is stored, the history records the change, the read returns \"\"")
    void txt04_defectAndConditionEmptyTexts(Scenario s) {
        SaveResult defect = s.newDefect("d");
        SaveResult condition = s.newCondition("1", "r", "c");

        SaveResult editedDefect = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", defect.id(), 1,
                Map.of("behavior", "", "root_cause", " ")));
        SaveResult editedCondition = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("condition", condition.id(), 1,
                Map.of("condition_text", "")));

        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE id = ? AND behavior IS NULL AND root_cause IS NULL",
                defect.id())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM release_condition WHERE id = ? AND condition_text IS NULL",
                condition.id())).isEqualTo(1);
        assertThat(s.changes(editedDefect.historyId()).entries()).containsOnlyKeys("behavior", "root_cause");
        assertThat(s.changes(editedCondition.historyId()).entries()).containsExactly(Map.entry("condition_text",
                new Change("condition c", null)));
        DetailService.DefectDetail detail = s.bean(DetailService.class).defect(defect.id());
        assertThat(detail.behavior()).isEmpty();
        assertThat(detail.rootCause()).isEmpty();
    }

    @Test
    @DisplayName("REH3-LEN-01 a condition code of 200 and a CI reference of 2000 characters are stored whole; 201 and 2001 are MUST_BE_AT_MOST_CHARS (200, 2000)")
    void len01_longerColumns(Scenario s) {
        String code200 = "c".repeat(200);
        Map<String, String> fields = s.conditionFields("r", code200);
        assertThat(create(s, "condition", s.conditionId("L"), fields)).isEqualTo(s.conditionId("L"));
        assertThat(s.count("SELECT COUNT(*) FROM release_condition WHERE id = ? AND code = ?", s.conditionId("L"), code200))
                .isEqualTo(1);
        LedgerException tooLong = Expect.failure(() -> create(s, "condition", s.conditionId("M"),
                s.conditionFields("r", "c".repeat(201))), ValidationCode.VALIDATION_FAILED);
        assertThat(((ValidationException) tooLong).issues().getFirst().reasonArgs()).containsExactly("200");

        String defect = s.newDefect("d").id();
        String ci2000 = "r".repeat(2000);
        s.bean(org.pgledger.application.write.RecordFixService.class).recordFix(Scenario.AUTHOR,
                new org.pgledger.application.write.RecordFixCommand(null, defect, "repo/e2e", Scenario.commit('a'),
                        Scenario.REASON, null, ci2000, "success", null, null));
        assertThat(s.count("SELECT COUNT(*) FROM defect_fix WHERE defect_id = ? AND ci_ref = ?", defect, ci2000))
                .isEqualTo(1);
        LedgerException ciTooLong = Expect.failure(() -> s.bean(org.pgledger.application.write.RecordFixService.class)
                .recordFix(Scenario.AUTHOR, new org.pgledger.application.write.RecordFixCommand(null, defect, "repo/e2e",
                        Scenario.commit('b'), Scenario.REASON, null, "r".repeat(2001), "success", null, null)),
                ValidationCode.VALIDATION_FAILED);
        assertThat(((ValidationException) ciTooLong).issues().getFirst().reasonArgs()).containsExactly("2000");
    }
}
