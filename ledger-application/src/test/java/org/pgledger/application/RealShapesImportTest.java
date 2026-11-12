package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.legacy.ImportReport;
import org.pgledger.application.legacy.LegacyImportService;
import org.pgledger.application.legacy.TableCheck;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.ListService.ConditionsQuery;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.apptest.LedgerTestRunners;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.testing.TestOwners;
import org.pgledger.testing.legacy.LegacyFixtures;
import org.pgledger.testing.legacy.LegacyLedger;

/**
 * REH3-IMP-01 .. 04, REH3-ID-03, REH3-TXT-05 (and the import side of REH3-ID-08): the legacy ledger with the shapes of the
 * real 1.x data (rehearsal 3) through the import service in the real application context, then the tools by the ids that
 * 1.x used.
 */
class RealShapesImportTest {

    private static void withRig(LegacyLedger legacy, Consumer<LedgerAppContext> body) {
        try (legacy) {
            LedgerTestRunners.importing(legacy, legacy.reader(), body);
        }
    }

    private static long count(LedgerAppContext ctx, String sql, Object... arguments) {
        return ctx.jdbc().sql(sql).params(Arrays.asList(arguments)).query(Long.class).single();
    }

    private static LegacyImportService importer(LedgerAppContext ctx) {
        return ctx.bean(LegacyImportService.class);
    }

    private static ValidationException refused(LedgerAppContext ctx) {
        return (ValidationException) Expect.failure(() -> importer(ctx).importLegacy(TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
    }

    private static void assertNothingWritten(LedgerAppContext ctx) {
        for (String table : List.of("project", "task", "defect", "release_condition", "condition_link", "history",
                "owner_audit")) {
            assertThat(count(ctx, "SELECT COUNT(*) FROM " + table)).as(table).isZero();
        }
    }

    @Test
    @DisplayName("REH3-IMP-01 the legacy ledger of the real shapes (ids in any case with # / . : _ -, 120 characters, a code of 79, a CI reference of 533, '' in five long texts, links to tasks and defects) imports and verifies: every id byte for byte, '' as NULL, the link keys as ConditionLink derives them, the id_key in upper case")
    void imp01_realShapes() {
        withRig(LegacyFixtures.withRealShapes(LegacyFixtures.rich()), ctx -> {
            ImportReport report = importer(ctx).importLegacy(TestOwners.OWNER);

            assertThat(report.tables()).allMatch(TableCheck::matches);
            importer(ctx).verify();
            for (String id : List.of(LegacyFixtures.REAL_TASK_A01, LegacyFixtures.REAL_TASK_LOWER_SUFFIX,
                    LegacyFixtures.REAL_TASK_LONG, LegacyFixtures.REAL_TASK_CHARS, LegacyFixtures.REAL_TASK_120,
                    LegacyFixtures.REAL_TASK_EMPTY_BOTH, LegacyFixtures.REAL_TASK_EMPTY_ONE)) {
                assertThat(count(ctx, "SELECT COUNT(*) FROM task WHERE id = ?", id)).as(id).isEqualTo(1);
            }
            assertThat(LegacyFixtures.REAL_TASK_120).hasSize(120);
            for (String id : List.of(LegacyFixtures.REAL_CONDITION_LONG, LegacyFixtures.REAL_CONDITION_LOWER)) {
                assertThat(count(ctx, "SELECT COUNT(*) FROM release_condition WHERE id = ?", id)).as(id).isEqualTo(1);
            }
            assertThat(count(ctx, "SELECT COUNT(*) FROM release_condition WHERE code = ?", LegacyFixtures.REAL_CODE_79))
                    .isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM defect_fix WHERE ci_ref = ?", LegacyFixtures.REAL_CI_REF_533))
                    .isEqualTo(1);
            // '' is NULL
            assertThat(count(ctx, "SELECT COUNT(*) FROM task WHERE id = ? AND purpose IS NULL AND success_condition IS NULL",
                    LegacyFixtures.REAL_TASK_EMPTY_BOTH)).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM task WHERE id = ? AND purpose IS NULL AND success_condition = ?",
                    LegacyFixtures.REAL_TASK_EMPTY_ONE, "it is done")).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM defect WHERE id = ? AND behavior IS NULL AND root_cause IS NULL",
                    LegacyFixtures.REAL_DEFECT)).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM release_condition WHERE id = ? AND condition_text IS NULL",
                    LegacyFixtures.REAL_CONDITION_LOWER)).isEqualTo(1);
            // the link keys are the ones of a new link
            ConditionId condition = ConditionId.of(LegacyFixtures.REAL_CONDITION_LONG);
            List<String> keys = ctx.jdbc().sql("SELECT link_key FROM condition_link WHERE condition_id = ? ORDER BY link_key")
                    .param(condition.value()).query(String.class).list();
            assertThat(keys).containsExactlyInAnyOrder(
                    ConditionLink.toTask(condition, TaskId.of(LegacyFixtures.REAL_TASK_A01), AuthorId.OWNER).linkKey(),
                    ConditionLink.toTask(condition, TaskId.of(LegacyFixtures.REAL_TASK_CHARS), AuthorId.OWNER).linkKey(),
                    ConditionLink.toDefect(condition, org.pgledger.domain.value.DefectId.of(LegacyFixtures.REAL_DEFECT),
                            AuthorId.OWNER).linkKey());
            // the normalised id the unique key rests on
            for (Map<String, Object> row : ctx.jdbc().sql("SELECT id, id_key FROM task").query().listOfRows()) {
                assertThat(row.get("id_key")).isEqualTo(String.valueOf(row.get("id")).toUpperCase(Locale.ROOT));
            }
            for (Map<String, Object> row : ctx.jdbc().sql("SELECT id, id_key FROM release_condition").query().listOfRows()) {
                assertThat(row.get("id_key")).isEqualTo(String.valueOf(row.get("id")).toUpperCase(Locale.ROOT));
            }
        });
    }

    @Test
    @DisplayName("REH3-IMP-02 ids of one kind that differ only in case are reported (the later one of each pair, naming the other) and nothing is written; a space, 121 characters and a tab are reported as malformed")
    void imp02_caseOnlyAndMalformedIds() {
        withRig(LegacyFixtures.withCaseDuplicates(LegacyFixtures.rich()), ctx -> {
            ValidationException refused = refused(ctx);

            assertThat(refused.issues()).hasSize(2);
            assertThat(refused.issues()).allSatisfy(issue -> assertThat(issue.reason().name())
                    .isEqualTo("ID_DIFFERS_ONLY_IN_CASE"));
            List<String> paths = refused.issues().stream().map(ValidationIssue::path).toList();
            assertThat(paths).anyMatch(path -> path.equals("task[OLD-T-a].id") || path.equals("task[OLD-T-A].id"));
            assertThat(paths).anyMatch(path -> path.equals("release_condition[old-c].id")
                    || path.equals("release_condition[OLD-C].id"));
            ValidationIssue task = refused.issues().stream().filter(issue -> issue.path().startsWith("task[")).findFirst()
                    .orElseThrow();
            assertThat(task.reasonArgs()).hasSize(1);
            assertThat(task.reasonArgs().getFirst()).isIn("OLD-T-a", "OLD-T-A").isNotEqualTo(
                    task.path().substring("task[".length(), task.path().length() - "].id".length()));
            assertNothingWritten(ctx);
        });
        withRig(LegacyFixtures.withMalformedIds(LegacyFixtures.rich()), ctx -> {
            ValidationException refused = refused(ctx);

            assertThat(refused.issues()).hasSize(3);
            assertThat(refused.issues()).allSatisfy(issue -> assertThat(issue.reason().name()).isEqualTo("MUST_BE_ID"));
            assertThat(refused.issues().stream().map(ValidationIssue::path).toList()).contains("task[OLD T SPACE].id",
                    "task[" + LegacyFixtures.TASK_ID_121 + "].id", "release_condition[OLD\tC].id");
            assertNothingWritten(ctx);
        });
    }

    private static void insertCondition(LegacyLedger legacy, String id, String code) {
        legacy.insert("condition", "id", id, "project_id", "old", "release", "old", "code", code, "condition_text", "text",
                "state", "UNKNOWN", "version", 1, "created_by", LegacyFixtures.SEED);
    }

    private static void insertFix(LegacyLedger legacy, long id, String commit, String ciRef) {
        legacy.insert("defect_fix", "id", id, "defect_id", "E2E-D-0007", "repo", "repo/len", "commit_sha", commit.repeat(40),
                "ci_ref", ciRef, "ci_conclusion", "success", "recorded_by", LegacyFixtures.SEED, "recorded_at",
                LegacyLedger.at("2026-11-06T02:00:00Z"), "imported", false);
    }

    @Test
    @DisplayName("REH3-IMP-03 a condition code of 200 and a CI reference of 2000 characters import; 201, 2001 and an origin_ref of 501 are reported with the limit (never cut)")
    void imp03_lengths() {
        LegacyLedger fits = LegacyFixtures.rich();
        insertCondition(fits, "OLD-C-200", "c".repeat(200));
        insertFix(fits, 31, "1", "r".repeat(2000));
        withRig(fits, ctx -> {
            assertThat(importer(ctx).importLegacy(TestOwners.OWNER).tables()).allMatch(TableCheck::matches);
            assertThat(count(ctx, "SELECT COUNT(*) FROM release_condition WHERE id = 'OLD-C-200' AND code = ?",
                    "c".repeat(200))).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM defect_fix WHERE id = 31 AND ci_ref = ?", "r".repeat(2000)))
                    .isEqualTo(1);
        });
        LegacyLedger tooLong = LegacyFixtures.rich();
        insertCondition(tooLong, "OLD-C-201", "c".repeat(201));
        insertFix(tooLong, 32, "2", "r".repeat(2001));
        tooLong.update("UPDATE " + tooLong.schema() + ".task SET origin_ref = ? WHERE id = 'OLD-T-1'", "x".repeat(501));
        withRig(tooLong, ctx -> {
            ValidationException refused = refused(ctx);

            assertThat(refused.issues()).extracting(ValidationIssue::path).containsExactlyInAnyOrder(
                    "release_condition[OLD-C-201].code", "defect_fix[32].ci_ref", "task[OLD-T-1].origin_ref");
            assertThat(refused.issues()).allSatisfy(issue -> assertThat(issue.reason().name())
                    .isEqualTo("MUST_BE_AT_MOST_CHARS"));
            assertThat(refused.issues().stream().map(issue -> issue.reasonArgs().getFirst()).toList())
                    .containsExactlyInAnyOrder("200", "2000", "500");
            assertNothingWritten(ctx);
        });
    }

    @Test
    @DisplayName("REH3-IMP-04 a NOT NULL text that identifies a row stays refused when it is '' in 1.x (release, repo): reported, nothing written, no value invented")
    void imp04_emptyIdentifyingTexts() {
        withRig(LegacyFixtures.withEmptyIdentifyingTexts(LegacyFixtures.rich()), ctx -> {
            ValidationException refused = refused(ctx);

            assertThat(refused.issues()).extracting(ValidationIssue::path).containsExactlyInAnyOrder(
                    "release_condition[OLD-C-NORELEASE].release_name", "defect_fix[30].repo");
            assertThat(refused.issues()).allSatisfy(issue -> assertThat(issue.reason().name()).isEqualTo("REQUIRED"));
            assertNothingWritten(ctx);
        });
    }

    @Test
    @DisplayName("REH3-ID-03 an imported task is read, edited, moved and linked by the id 1.x gave it (AGS:A07-a); the id in the other case is NOT_FOUND and nothing is written")
    void id03_exactIdsAfterTheImport() {
        withRig(LegacyFixtures.withRealShapes(LegacyFixtures.rich()), ctx -> {
            importer(ctx).importLegacy(TestOwners.OWNER);
            String author = "claude:reh3-author";
            ctx.enroll(author);
            ctx.bean(GrantService.class).grant(new GrantCommand(author, "ags", List.of("task"), null, null),
                    TestOwners.OWNER);
            String id = LegacyFixtures.REAL_TASK_LOWER_SUFFIX;
            String other = id.toLowerCase(Locale.ROOT);
            DetailService detail = ctx.bean(DetailService.class);
            SaveService save = ctx.bean(SaveService.class);

            assertThat(detail.task(id).id()).isEqualTo(id);
            Expect.failure(() -> detail.task(other), LookupCode.NOT_FOUND);
            SaveResult edited = save.save(author, new SaveCommand(null, "task", id, 1L, Fields.of("title", "edited"),
                    Scenario.REASON));
            assertThat(edited.id()).isEqualTo(id);
            assertThat(edited.version()).isEqualTo(2);
            Expect.failure(() -> save.save(author, new SaveCommand(null, "task", other, 1L,
                    Fields.of("title", "edited again"), Scenario.REASON)), LookupCode.NOT_FOUND);
            assertThat(ctx.bean(TransitionService.class).transition(author, new TransitionCommand(null, "task", id, 2L,
                    "ACTIVE", Scenario.REASON, null, null, null)).version()).isEqualTo(3);
            Expect.failure(() -> ctx.bean(TransitionService.class).transition(author, new TransitionCommand(null, "task",
                    other, 3L, "BLOCKED", Scenario.REASON, null, null, null)), LookupCode.NOT_FOUND);
            LinkService links = ctx.bean(LinkService.class);
            assertThat(links.link(author, new LinkCommand(null, "condition_task", LegacyFixtures.REAL_CONDITION_LONG, id,
                    Scenario.REASON, null)).id()).isEqualTo(LegacyFixtures.REAL_CONDITION_LONG + "|" + id);
            Expect.failure(() -> links.link(author, new LinkCommand(null, "condition_task",
                    LegacyFixtures.REAL_CONDITION_LONG, other, Scenario.REASON, null)), LookupCode.NOT_FOUND);

            assertThat(count(ctx, "SELECT COUNT(*) FROM task WHERE id = ? AND title = 'edited' AND state = 'ACTIVE'", id))
                    .isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM history WHERE entity = 'TASK' AND entity_id = ? AND op <> 'LEGACY_NOTE'",
                    other)).isZero();
        });
    }

    @Test
    @DisplayName("REH3-TXT-05 a task, a defect and a condition that had '' in 1.x read as \"\" (as 1.x returned them)")
    void txt05_emptyReadsAsEmpty() {
        withRig(LegacyFixtures.withRealShapes(LegacyFixtures.rich()), ctx -> {
            importer(ctx).importLegacy(TestOwners.OWNER);
            DetailService detail = ctx.bean(DetailService.class);

            DetailService.TaskDetail task = detail.task(LegacyFixtures.REAL_TASK_EMPTY_BOTH);
            assertThat(task.purpose()).isEmpty();
            assertThat(task.successCondition()).isEmpty();
            assertThat(detail.task(LegacyFixtures.REAL_TASK_EMPTY_ONE).purpose()).isEmpty();
            assertThat(detail.task(LegacyFixtures.REAL_TASK_EMPTY_ONE).successCondition()).isEqualTo("it is done");
            DetailService.DefectDetail defect = detail.defect(LegacyFixtures.REAL_DEFECT);
            assertThat(defect.behavior()).isEmpty();
            assertThat(defect.rootCause()).isEmpty();
            var conditions = ctx.bean(ListService.class).conditions(new ConditionsQuery("ags", null, null, 50, null));
            assertThat(conditions.items()).filteredOn(item -> item.id().equals(LegacyFixtures.REAL_CONDITION_LOWER))
                    .singleElement().satisfies(item -> assertThat(item.conditionText()).isEmpty());
        });
    }

    @Test
    @DisplayName("REH3-ID-08 / REH3-LEN-01 the schema: id 120, id_key 360 NOT NULL and unique in task and release_condition, code 200, ci_ref 2000, and the five long texts are nullable")
    void id08_theColumns() {
        withRig(LegacyFixtures.rich(), ctx -> {
            assertThat(size(ctx, "task", "id")).isEqualTo(120);
            assertThat(size(ctx, "task", "id_key")).isEqualTo(360);
            assertThat(size(ctx, "release_condition", "id")).isEqualTo(120);
            assertThat(size(ctx, "release_condition", "id_key")).isEqualTo(360);
            assertThat(size(ctx, "release_condition", "code")).isEqualTo(200);
            assertThat(size(ctx, "defect_fix", "ci_ref")).isEqualTo(2000);
            for (String[] column : new String[][] {{"task", "purpose"}, {"task", "success_condition"},
                    {"defect", "behavior"}, {"defect", "root_cause"}, {"release_condition", "condition_text"}}) {
                assertThat(nullable(ctx, column[0], column[1])).as(column[0] + "." + column[1]).isTrue();
            }
            assertThat(nullable(ctx, "task", "id_key")).isFalse();
            assertThat(nullable(ctx, "release_condition", "id_key")).isFalse();
        });
    }

    private static int size(LedgerAppContext ctx, String table, String column) {
        return ctx.jdbc().sql("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema = "
                + "current_schema() AND table_name = ? AND column_name = ?").param(table).param(column)
                .query(Integer.class).single();
    }

    private static boolean nullable(LedgerAppContext ctx, String table, String column) {
        return "YES".equals(ctx.jdbc().sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema = "
                + "current_schema() AND table_name = ? AND column_name = ?").param(table).param(column)
                .query(String.class).single());
    }
}
