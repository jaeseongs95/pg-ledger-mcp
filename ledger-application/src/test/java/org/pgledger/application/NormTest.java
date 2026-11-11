package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.history.Change;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;

/** SVC-NORM-01 .. SVC-NORM-05: an empty string is NULL or refused, never stored (SVC-NORM-04 is in the receipt test). */
@LedgerServiceTest
class NormTest {

    /** A write that gives optional fields as blank; the SQL counts the rows that hold NULL in all of them. */
    record Blank(String name, Function<Scenario, String> write, String sql) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Blank> blankOptionalFields() {
        return Stream.of(
                new Blank("defect owner_actor_id", s -> {
                    var fields = s.defectFields("d");
                    fields.put("owner_actor_id", "  ");
                    s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields));
                    return s.projectId();
                }, "SELECT COUNT(*) FROM defect WHERE project_id = ? AND owner_actor_id IS NULL"),
                new Blank("task owner, origin_ref, origin_quote", s -> {
                    var fields = s.taskFields("t");
                    fields.put("owner_actor_id", "");
                    fields.put("origin_ref", "   ");
                    fields.put("origin_quote", "");
                    s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
                    return s.projectId();
                }, "SELECT COUNT(*) FROM task WHERE project_id = ? AND owner_actor_id IS NULL "
                        + "AND origin_ref IS NULL AND origin_quote IS NULL"),
                new Blank("condition goal_label", s -> {
                    var fields = s.conditionFields("r", "c");
                    fields.put("goal_label", " ");
                    s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("condition", s.conditionId("1"), fields));
                    return s.projectId();
                }, "SELECT COUNT(*) FROM release_condition WHERE project_id = ? AND goal_label IS NULL"),
                new Blank("case evidence and user_quote", s -> {
                    SaveResult d = s.newDefect("d");
                    s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d.id(),
                            "seen", Scenario.REASON, " ", "", null));
                    return d.id();
                }, "SELECT COUNT(*) FROM defect_case WHERE defect_id = ? AND evidence IS NULL AND user_quote IS NULL"),
                new Blank("fix branch and ci_ref", s -> {
                    SaveResult d = s.newDefect("d");
                    s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, new RecordFixCommand(null, d.id(),
                            "repo/e2e", Scenario.commit('a'), Scenario.REASON, "  ", "", "", "", null));
                    return d.id();
                }, "SELECT COUNT(*) FROM defect_fix WHERE defect_id = ? AND branch IS NULL AND ci_ref IS NULL "
                        + "AND ci_conclusion IS NULL"),
                new Blank("transition evidence_sha256 and merged_into", s -> {
                    SaveResult d = s.newDefect("d");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(null, "defect",
                            d.id(), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, " ", " "));
                    return d.id();
                }, "SELECT COUNT(*) FROM history WHERE entity = 'DEFECT' AND entity_id = ? AND op = 'TRANSITION' "
                        + "AND evidence_sha256 IS NULL"),
                new Blank("grant note", s -> {
                    s.bean(GrantService.class).grant(new GrantService.GrantCommand("claude:note", s.projectId(),
                            List.of("defect"), null, "   "), TestOwners.OWNER);
                    return "claude:note";
                }, "SELECT COUNT(*) FROM session_grant WHERE author_id = ? AND note IS NULL"));
    }

    @ParameterizedTest
    @MethodSource("blankOptionalFields")
    @DisplayName("SVC-NORM-01: a blank optional field is stored as NULL")
    void norm01_blankOptionalsBecomeNull(Blank blank, Scenario s) {
        String parameter = blank.write().apply(s);
        assertThat(s.count(blank.sql(), parameter)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-NORM-01: a blank ci_ref with a ci_conclusion is a validation failure")
    void norm01_conclusionNeedsAReference(Scenario s) {
        SaveResult d = s.newDefect("d");
        ValidationException e = (ValidationException) Expect.failure(() -> s.bean(RecordFixService.class).recordFix(
                Scenario.AUTHOR, new RecordFixCommand(null, d.id(), "repo/e2e", Scenario.commit('a'), Scenario.REASON,
                        null, "   ", "success", null, null)), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).isEqualTo("ci_ref");
    }

    /** A required field given blank: the write fails with this code and names this path. */
    record Required(String name, Consumer<Scenario> write, ErrorCode code, String path) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Map<String, String> with(Map<String, String> fields, String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.put(key, value);
        return copy;
    }

    static Stream<Required> blankRequiredFields() {
        return Stream.of(
                new Required("defect title", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("defect", null, with(s.defectFields("d"), "title", "  "))),
                        ValidationCode.VALIDATION_FAILED, "title"),
                new Required("defect behavior", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("defect", null, with(s.defectFields("d"), "behavior", ""))),
                        ValidationCode.VALIDATION_FAILED, "behavior"),
                new Required("task purpose", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("task", null, with(s.taskFields("t"), "purpose", " "))),
                        ValidationCode.VALIDATION_FAILED, "purpose"),
                new Required("task success_condition", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("task", null, with(s.taskFields("t"), "success_condition", ""))),
                        ValidationCode.VALIDATION_FAILED, "success_condition"),
                new Required("condition condition_text", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("condition", s.conditionId("1"), with(s.conditionFields("r", "c"), "condition_text",
                                " "))), ValidationCode.VALIDATION_FAILED, "condition_text"),
                new Required("condition release", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("condition", s.conditionId("1"), with(s.conditionFields("r", "c"), "release", ""))),
                        ValidationCode.VALIDATION_FAILED, "release"),
                new Required("condition code", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("condition", s.conditionId("1"), with(s.conditionFields("r", "c"), "code", " "))),
                        ValidationCode.VALIDATION_FAILED, "condition_code"),
                new Required("case observation", s -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR,
                        new RecordCaseCommand(null, s.newDefect("d").id(), "   ", Scenario.REASON, null, null, null)),
                        ValidationCode.VALIDATION_FAILED, "observation"),
                new Required("fix repo", s -> s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR,
                        new RecordFixCommand(null, s.newDefect("d").id(), " ", Scenario.commit('a'), Scenario.REASON,
                                null, null, null, null, null)), ValidationCode.VALIDATION_FAILED, "repo"),
                new Required("reason", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        new SaveCommand(null, "defect", null, 0L, s.defectFields("d"), "          ")),
                        ValidationCode.REASON_INVALID, "reason"));
    }

    @ParameterizedTest
    @MethodSource("blankRequiredFields")
    @DisplayName("SVC-NORM-02: a blank required field is refused and named")
    void norm02_blankRequiredFields(Required required, Scenario s) {
        LedgerException e = Expect.failure(() -> required.write().accept(s), required.code());
        assertThat(((ValidationException) e).issues().get(0).path()).isEqualTo(required.path());
        assertThat(s.count("SELECT COUNT(*) FROM defect_case WHERE defect_id IN (SELECT id FROM defect "
                + "WHERE project_id = ?)", s.projectId())).isZero();
    }

    @Test
    @DisplayName("SVC-NORM-03: a blank evidence_uri equals none")
    void norm03_blankEvidence(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.REVIEWER, new TransitionCommand(null,
                "defect", d.id(), 2L, "VERIFIED", Scenario.REASON, "   ", null, null)),
                TransitionCode.EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-NORM-05: setting an optional field to blank clears it and the history records [old, null]")
    void norm05_clearing(Scenario s) {
        var fields = s.taskFields("t");
        fields.put("origin_ref", "https://origin.example/1");
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", t.id(), 1,
                Map.of("origin_ref", "")));
        assertThat(s.task(t.id()).originRef()).isNull();
        ChangeSet changes = s.bean(LedgerJson.class).read(s.historyRow(edited.historyId()).changes(),
                ChangeSet.class);
        assertThat(changes.entries()).containsExactly(Map.entry("origin_ref",
                new Change("https://origin.example/1", null)));
        assertThat(s.history(HistoryEntity.TASK, t.id()).get(1).op()).isEqualTo(HistoryOp.EDIT);
    }
}
