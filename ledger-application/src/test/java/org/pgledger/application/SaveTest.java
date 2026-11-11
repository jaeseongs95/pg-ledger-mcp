package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.history.Change;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.TaskState;

/** SVC-SAV-01 .. SVC-SAV-19: save (create and edit of a defect, task or condition). */
@LedgerServiceTest
class SaveTest {

    private static Map<String, String> with(Map<String, String> fields, String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.put(key, value);
        return copy;
    }

    private static Map<String, String> without(Map<String, String> fields, String key) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.remove(key);
        return copy;
    }

    @Test
    @DisplayName("SVC-SAV-01: creating a defect writes the row and a CREATE history row with every given field")
    void sav01_createDefect(Scenario s) {
        var fields = s.defectFields("login bug");
        SaveResult result = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields));
        assertThat(result.status().name()).isEqualTo("COMMITTED");
        assertThat(result.entity()).isEqualTo("defect");
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.author()).isEqualTo(Scenario.AUTHOR);
        assertThat(result.login()).isEqualTo("ledger_author");
        HistoryEntry row = s.historyRow(result.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.CREATE);
        assertThat(row.entity()).isEqualTo(HistoryEntity.DEFECT);
        assertThat(row.entityId()).isEqualTo(result.id());
        assertThat(row.beforeData()).isNull();
        assertThat(row.actorAssurance()).isEqualTo(ActorAssurance.DECLARED);
        Map<String, Change> expected = new LinkedHashMap<>();
        fields.forEach((k, v) -> expected.put(k, new Change(null, v)));
        assertThat(s.changes(result.historyId()).entries()).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    @DisplayName("SVC-SAV-02: a defect without severity needs one of the valid codes")
    void sav02_severityRequired(Scenario s) {
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("defect", null, without(s.defectFields("d"), "severity"))), ValidationCode.CODE_REQUIRED);
        assertThat(e.details()).containsEntry("valid", List.of("P1", "P2", "P3"));
        assertThat(e.args()).containsExactly("defect.severity", "P1, P2, P3", "DEFECT_SEVERITY");
    }

    @Test
    @DisplayName("SVC-SAV-02: a defect without category needs one of the active categories")
    void sav02_categoryRequired(Scenario s) {
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("defect", null, without(s.defectFields("d"), "category"))), ValidationCode.CODE_REQUIRED);
        assertThat(e.details().get("valid")).startsWith("ARC");
    }

    @Test
    @DisplayName("SVC-SAV-03: UNRATED is not allowed on a new defect")
    void sav03_unrated(Scenario s) {
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("defect", null, with(s.defectFields("d"), "severity", "UNRATED"))),
                ValidationCode.CODE_INVALID);
        assertThat(e.details()).containsEntry("valid", List.of("P1", "P2", "P3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BGU", "RETIRED"})
    @DisplayName("SVC-SAV-04: an unknown or an inactive category on a new defect")
    void sav04_categoryIsChecked(String category, Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("defect", null, with(s.defectFields("d"), "category", category))),
                ValidationCode.CODE_INVALID);
    }

    @Test
    @DisplayName("SVC-SAV-04: an edit may not change a category to an inactive one")
    void sav04_editToInactive(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                Map.of("category", "RETIRED"))), ValidationCode.CODE_INVALID);
    }

    @Test
    @DisplayName("SVC-SAV-04: an unrelated edit of a row that holds a retired category is allowed")
    void sav04_retiredCategoryStaysOnUnrelatedEdits(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.ctx().jdbc().sql("UPDATE defect SET category = 'RETIRED' WHERE id = ?").param(d.id()).update();
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                Map.of("title", "new title"))).version()).isEqualTo(2);
        assertThat(s.defect(d.id()).category().value()).isEqualTo("RETIRED");
    }

    @Test
    @DisplayName("SVC-SAV-05: an edit bumps the version and the time and records only the changed fields")
    void sav05_edit(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.ctx().clock().set(Instant.parse("2026-11-05T12:00:00Z"));
        var fields = new LinkedHashMap<String, String>();
        fields.put("title", "new title");
        fields.put("behavior", s.defect(d.id()).behavior());
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1, fields));
        assertThat(edited.version()).isEqualTo(2);
        assertThat(s.defect(d.id()).updatedAt()).isEqualTo(Instant.parse("2026-11-05T12:00:00Z"));
        assertThat(s.historyRow(edited.historyId()).op()).isEqualTo(HistoryOp.EDIT);
        assertThat(s.changes(edited.historyId()).entries()).containsExactly(Map.entry("title",
                new Change("d", "new title")));
    }

    @Test
    @DisplayName("SVC-SAV-06: a stale expected version names both versions")
    void sav06_versionConflict(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1, Map.of("title", "two")));
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect",
                d.id(), 7, Map.of("title", "three"))), ConflictCode.VERSION_CONFLICT);
        assertThat(e.args()).containsExactly("defect", d.id(), "7", "2");
    }

    @Test
    @DisplayName("SVC-SAV-07: an edit needs an id")
    void sav07_idRequired(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", null, 1,
                Map.of("title", "x"))), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-SAV-07: an unknown id")
    void sav07_unknownId(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", s.defectId(5), 1,
                Map.of("title", "x"))), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-SAV-07: the id of another kind of entity")
    void sav07_idOfAnotherEntity(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", d.id(), 1,
                Map.of("title", "x"))), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-SAV-08: an edit with identical values changes nothing and writes no history")
    void sav08_nothingChanges(Scenario s) {
        SaveResult d = s.newDefect("d");
        long history = s.count("SELECT COUNT(*) FROM history");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                Map.of("title", "d"))), TransitionCode.NOTHING_CHANGES);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.defect(d.id()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-SAV-09: a defect cannot move to another project")
    void sav09_projectMove(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                Map.of("project_id", s.projectId()))), TransitionCode.PROJECT_MOVE_FORBIDDEN);
    }

    static Stream<String> keysThatAreNotEditable() {
        return Stream.of("status", "version", "created_by", "id", "merged_into", "state", "updated_at", "titel");
    }

    @ParameterizedTest
    @MethodSource("keysThatAreNotEditable")
    @DisplayName("SVC-SAV-10: a key that is not an editable field is refused and named")
    void sav10_unknownKey(String key, Scenario s) {
        ValidationException e = (ValidationException) Expect.failure(() -> s.bean(SaveService.class).save(
                Scenario.AUTHOR, s.create("defect", null, with(s.defectFields("d"), key, "x"))),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).isEqualTo("fields." + key);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isZero();
    }

    @Test
    @DisplayName("SVC-SAV-11: owner_actor_id must be an author id")
    void sav11_owner(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null,
                with(s.defectFields("d"), "owner_actor_id", "someone"))), TrustCode.AUTHOR_ID_FORMAT);
        SaveResult ok = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null,
                with(s.defectFields("d"), "owner_actor_id", "codex:owner1")));
        assertThat(s.defect(ok.id()).ownerActorId().value()).isEqualTo("codex:owner1");
    }

    @Test
    @DisplayName("SVC-SAV-12: a task starts PLANNED at version 1, an edit moves updated_at")
    void sav12_task(Scenario s) {
        var fields = with(with(s.taskFields("t"), "owner_actor_id", "codex:owner1"), "origin_ref", "ref-1");
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        assertThat(s.task(t.id()).state()).isEqualTo(TaskState.PLANNED);
        assertThat(s.task(t.id()).version()).isEqualTo(1);
        assertThat(s.task(t.id()).originRef()).isEqualTo("ref-1");
        assertThat(s.task(t.id()).ownerActorId().value()).isEqualTo("codex:owner1");
        s.ctx().clock().set(Instant.parse("2026-11-05T13:00:00Z"));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", t.id(), 1, Map.of("title", "t2")));
        assertThat(s.task(t.id()).updatedAt()).isEqualTo(Instant.parse("2026-11-05T13:00:00Z"));
    }

    @Test
    @DisplayName("SVC-SAV-13: a condition starts UNKNOWN at version 1, an edit bumps the version only")
    void sav13_condition(Scenario s) {
        SaveResult c = s.newCondition("1", "r1", "c1");
        var conditions = s.bean(org.pgledger.database.repository.ConditionRepository.class);
        var row = conditions.findById(org.pgledger.domain.value.ConditionId.of(c.id())).orElseThrow();
        assertThat(row.state()).isEqualTo(ConditionState.UNKNOWN);
        assertThat(row.version()).isEqualTo(1);
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("condition", c.id(), 1,
                Map.of("condition_text", "changed")));
        assertThat(edited.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-SAV-14: a condition key (project, release, code) is unique")
    void sav14_duplicateCondition(Scenario s) {
        s.newCondition("1", "r1", "c1");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("condition",
                s.conditionId("2"), s.conditionFields("r1", "c1"))), ConflictCode.DUPLICATE_KEY);
    }

    @Test
    @DisplayName("SVC-SAV-15: an unknown project on create")
    void sav15_unknownProject(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null,
                with(s.defectFields("d"), "project_id", "nowhere"))), LookupCode.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 1001})
    @DisplayName("SVC-SAV-16: a reason outside 10..1000 characters leaves no receipt")
    void sav16_reasonLength(int length, Scenario s) {
        String id = Scenario.uuid();
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(id, "defect", null, 0L,
                s.defectFields("d"), "r".repeat(length))), ValidationCode.REASON_INVALID);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", id)).isZero();
    }

    /** A create with one field too long. */
    record TooLong(String name, Consumer<Scenario> create) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<TooLong> tooLongFields() {
        return Stream.of(
                new TooLong("title", s -> create(s, "defect", with(s.defectFields("d"), "title", "t".repeat(301)))),
                new TooLong("behavior", s -> create(s, "defect",
                        with(s.defectFields("d"), "behavior", "b".repeat(4001)))),
                new TooLong("root_cause", s -> create(s, "defect",
                        with(s.defectFields("d"), "root_cause", "c".repeat(4001)))),
                new TooLong("purpose", s -> create(s, "task", with(s.taskFields("t"), "purpose", "p".repeat(4001)))),
                new TooLong("success_condition", s -> create(s, "task",
                        with(s.taskFields("t"), "success_condition", "s".repeat(4001)))),
                new TooLong("origin_quote", s -> create(s, "task",
                        with(s.taskFields("t"), "origin_quote", "q".repeat(2001)))),
                new TooLong("condition_text", s -> create(s, "condition",
                        with(s.conditionFields("r", "c"), "condition_text", "x".repeat(4001)))),
                new TooLong("release", s -> create(s, "condition", with(s.conditionFields("r", "c"), "release",
                        "r".repeat(101)))),
                new TooLong("goal_label", s -> create(s, "condition", with(s.conditionFields("r", "c"), "goal_label",
                        "g".repeat(201)))));
    }

    private static void create(Scenario s, String entity, Map<String, String> fields) {
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create(entity,
                "condition".equals(entity) ? s.conditionId("1") : null, fields));
    }

    @ParameterizedTest
    @MethodSource("tooLongFields")
    @DisplayName("SVC-SAV-17: a field over its limit is refused before any SQL write")
    void sav17_limits(TooLong tooLong, Scenario s) {
        s.ctx().spy().clear();
        Expect.failure(() -> tooLong.create().accept(s), ValidationCode.VALIDATION_FAILED);
        assertThat(s.ctx().spy().statements()).noneMatch(sql -> sql.stripLeading().toUpperCase().startsWith("INSERT"));
    }

    @Test
    @DisplayName("SVC-SAV-18: ids of different kinds of entity do not collide")
    void sav18_separateKinds(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", d.id(), s.taskFields("t")));
        assertThat(t.id()).isEqualTo(d.id());
        assertThat(s.task(d.id()).title()).isEqualTo("t");
        assertThat(s.defect(d.id()).title()).isEqualTo("d");
    }

    @Test
    @DisplayName("SVC-SAV-19: an edit by another author keeps created_by and records the editor")
    void sav19_createdByIsKept(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult edited = s.bean(SaveService.class).save(Scenario.DEFECT_ONLY, s.edit("defect", d.id(), 1,
                Map.of("title", "by someone else")));
        assertThat(s.defect(d.id()).createdBy().value()).isEqualTo(Scenario.AUTHOR);
        assertThat(s.historyRow(edited.historyId()).authorId().value()).isEqualTo(Scenario.DEFECT_ONLY);
    }
}
