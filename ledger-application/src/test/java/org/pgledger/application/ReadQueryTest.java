package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.application.read.AttentionService;
import org.pgledger.application.read.AttentionService.AttentionQuery;
import org.pgledger.application.read.CatalogService;
import org.pgledger.application.read.CatalogService.CodesQuery;
import org.pgledger.application.read.CatalogService.ProjectsQuery;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.GetService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.HistoryService.HistoryQuery;
import org.pgledger.application.read.HistoryService.HistoryView;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.ListService.ConditionsQuery;
import org.pgledger.application.read.ListService.DefectsQuery;
import org.pgledger.application.read.ListService.TasksQuery;
import org.pgledger.application.read.Page;
import org.pgledger.application.read.SearchService;
import org.pgledger.application.read.SearchService.SearchQuery;
import org.pgledger.application.read.StatsService;
import org.pgledger.application.read.StatsService.StatsQuery;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/** MCR-H01 .. H12, MCR-S01 .. S07, MCR-L01 .. L05, MCR-ST01, ST02, MCR-P01, MCR-K01, MCR-010, MCR-012 and SVC-BEF-12. */
@LedgerServiceTest
class ReadQueryTest {

    private HistoryService history(Scenario s) {
        return s.bean(HistoryService.class);
    }

    private static HistoryQuery history(String project, Boolean includeBefore) {
        return new HistoryQuery(null, null, null, null, null, project, null, null, null, null, includeBefore, 1000,
                null);
    }

    private List<HistoryView> rows(Scenario s) {
        return history(s).history(history(s.projectId(), null)).items();
    }

    // ================================================================ history

    @Test
    @DisplayName("MCR-H01: a list of ids returns exactly those rows, ascending")
    void h01_ids(Scenario s) {
        IntStream.range(0, 10).forEach(i -> s.newDefect("d" + i));
        List<Long> all = rows(s).stream().map(HistoryView::id).toList();
        List<Long> pick = List.of(all.get(8), all.get(2), all.get(6));
        var page = history(s).history(new HistoryQuery(pick, null, null, null, null, null, null, null, null, null,
                null, null, null));
        assertThat(page.items()).extracting(HistoryView::id).containsExactly(all.get(2), all.get(6), all.get(8));
    }

    @Test
    @DisplayName("MCR-H02: an id range is inclusive at both ends")
    void h02_range(Scenario s) {
        IntStream.range(0, 10).forEach(i -> s.newDefect("d" + i));
        List<Long> all = rows(s).stream().map(HistoryView::id).toList();
        var page = history(s).history(new HistoryQuery(null, all.get(3), all.get(7), null, null, null, null, null,
                null, null, null, null, null));
        assertThat(page.items()).extracting(HistoryView::id).containsExactlyElementsOf(all.subList(3, 8));
    }

    @Test
    @DisplayName("MCR-H03: the history of one defect: create, edit, fix, verification, ascending")
    void h03_entityHistory(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1, Map.of("title", "two")));
        s.fix(Scenario.AUTHOR, d.id(), Scenario.commit('a'));
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("defect", d.id(), 3, "VERIFIED",
                Scenario.EVIDENCE));
        var page = history(s).history(new HistoryQuery(null, null, null, "defect", d.id(), null, null, null, null,
                null, null, null, null));
        assertThat(page.items()).extracting(HistoryView::op).containsExactly("CREATE", "EDIT", "TRANSITION",
                "TRANSITION");
        assertThat(page.items().get(2).fromState()).isEqualTo("OPEN");
        assertThat(page.items().get(2).toState()).isEqualTo("SOURCE_FIXED");
        assertThat(page.items().getLast().authorId()).isEqualTo(Scenario.REVIEWER);
        assertThat(page.items().getLast().login()).isEqualTo("ledger_reviewer");
    }

    @Test
    @DisplayName("MCR-H04: the project filter; an unknown project is an empty list, not an error")
    void h04_project(Scenario s) {
        s.newDefect("d");
        String other = s.otherProject();
        assertThat(history(s).history(history(other, null)).items()).isEmpty();
        assertThat(history(s).history(history("nowhere", null)).items()).isEmpty();
        assertThat(rows(s)).isNotEmpty();
    }

    @Test
    @DisplayName("MCR-H05: op and author are combined with AND")
    void h05_combined(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.bean(SaveService.class).save(Scenario.DEFECT_ONLY, s.edit("defect", d.id(), 1, Map.of("title", "two")));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 2, Map.of("title", "three")));
        var page = history(s).history(new HistoryQuery(null, null, null, null, null, s.projectId(), "EDIT",
                Scenario.DEFECT_ONLY, null, null, null, null, null));
        assertThat(page.items()).hasSize(1).allSatisfy(h -> assertThat(h.authorId()).isEqualTo(Scenario.DEFECT_ONLY));
    }

    static Stream<Arguments> timeRanges() {
        return Stream.of(
                Arguments.of("2026-11-05T00:00:00Z", "2026-11-05T14:59:59Z", List.of("b", "c")),
                Arguments.of("2026-11-05T15:00:00Z", "2026-11-05T15:00:00Z", List.of("d")),
                Arguments.of("2026-11-04T23:59:59Z", "2026-11-04T23:59:59Z", List.of("a")),
                Arguments.of("2026-11-05T14:59:59Z", "2026-11-05T15:00:00Z", List.of("c", "d")));
    }

    @ParameterizedTest
    @MethodSource("timeRanges")
    @DisplayName("MCR-H06: a time range is inclusive and judged in UTC")
    void h06_timeRange(String from, String to, List<String> titles, Scenario s) {
        List<String> stamps = List.of("2026-11-04T23:59:59Z", "2026-11-05T00:00:00Z", "2026-11-05T14:59:59Z",
                "2026-11-05T15:00:00Z");
        List<String> ids = IntStream.range(0, 4).mapToObj(i -> {
            s.ctx().clock().set(Instant.parse(stamps.get(i)));
            return s.newDefect("" + (char) ('a' + i)).id();
        }).toList();
        var page = history(s).history(new HistoryQuery(null, null, null, null, null, s.projectId(), null, null, from,
                to, null, null, null));
        List<String> found = page.items().stream().map(HistoryView::entityId).toList();
        assertThat(found).containsExactlyElementsOf(titles.stream().map(t -> ids.get(t.charAt(0) - 'a')).toList());
    }

    @Test
    @DisplayName("MCR-H08: changes are pairs, corrections show corrects_id, vendor comes from the author")
    void h08_shape(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                Map.of("title", "two")));
        var correction = s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(null,
                edited.historyId(), Scenario.REASON));
        List<HistoryView> rows = rows(s);
        HistoryView edit = rows.stream().filter(h -> h.id() == edited.historyId()).findFirst().orElseThrow();
        HistoryView corrected = rows.stream().filter(h -> h.id() == correction.historyId()).findFirst().orElseThrow();
        assertThat(s.bean(LedgerJson.class).read(edit.changes(), ChangeSet.class).entries())
                .containsOnlyKeys("title");
        assertThat(edit.vendor()).isEqualTo("claude");
        assertThat(edit.requestId()).isNotBlank();
        assertThat(edit.legacyRef()).isNull();
        assertThat(corrected.correctsId()).isEqualTo(edited.historyId());
    }

    @Test
    @DisplayName("MCR-H09: the history row of a write has the request id and author of the call")
    void h09_historyIsTheReceipt(Scenario s) {
        String requestId = Scenario.uuid();
        SaveResult d = s.bean(SaveService.class).save(Scenario.AUTHOR, new org.pgledger.application.write
                .SaveCommand(requestId, "defect", null, 0L, s.defectFields("d"), Scenario.REASON));
        var page = history(s).history(new HistoryQuery(List.of(d.historyId()), null, null, null, null, null, null,
                null, null, null, null, null, null));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().requestId()).isEqualTo(requestId);
        assertThat(page.items().getFirst().authorId()).isEqualTo(Scenario.AUTHOR);
    }

    @Test
    @DisplayName("MCR-H10: imported rows are visible with their legacy reference and assurance")
    void h10_importedRows(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.ctx().jdbc().sql("INSERT INTO history (id, at, author_id, login, actor_assurance, entity, entity_id, op, "
                + "changes, reason, request_id, legacy_ref) VALUES (?, TIMESTAMP '2025-01-01 00:00:00', "
                + "'agent:claude:old', 'ledger_author', 'LEGACY_LOGIN', 'DEFECT', ?, 'IMPORT', '{}', "
                + "'imported from the old ledger', ?, 'legacy-7')")
                .param(s.count("SELECT MAX(id) FROM history") + 1).param(d.id()).param(Scenario.uuid()).update();
        s.ctx().jdbc().sql("UPDATE id_counter SET issued_no = issued_no + 1 WHERE name = 'HISTORY'").update();
        HistoryView imported = rows(s).stream().filter(h -> "IMPORT".equals(h.op())).findFirst().orElseThrow();
        assertThat(imported.legacyRef()).isEqualTo("legacy-7");
        assertThat(imported.actorAssurance()).isEqualTo("LEGACY_LOGIN");
        assertThat(imported.vendor()).isEqualTo("claude");
        assertThat(imported.beforeData()).isNull();
    }

    @Test
    @DisplayName("MCR-H11 / SVC-BEF-12: before_data comes only with include_before, and only where a row has it")
    void h11_includeBefore(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult t = s.newTask("t");
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                Map.of("title", "two")));
        var transitioned = s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("defect", d.id(), 2,
                "DISPUTED", Scenario.EVIDENCE));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task", d.id(), t.id(),
                Scenario.REASON, null));
        var unlinked = s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, "defect_task", d.id(),
                t.id(), Scenario.REASON, true));
        var kept = s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d.id(),
                "seen", Scenario.REASON, null, null, null));
        assertThat(history(s).history(history(s.projectId(), null)).items()).allSatisfy(h -> assertThat(
                h.beforeData()).isNull());
        assertThat(history(s).history(history(s.projectId(), false)).items()).allSatisfy(h -> assertThat(
                h.beforeData()).isNull());
        List<HistoryView> with = history(s).history(history(s.projectId(), true)).items();
        LedgerJson json = s.bean(LedgerJson.class);
        assertThat(json.read(beforeOf(with, edited.historyId()), Snapshot.DefectRow.class))
                .isInstanceOf(Snapshot.DefectRow.class);
        assertThat(json.read(beforeOf(with, transitioned.historyId()), Snapshot.DefectRow.class))
                .isInstanceOf(Snapshot.DefectRow.class);
        assertThat(json.read(beforeOf(with, unlinked.historyId()), Snapshot.DefectTaskRow.class))
                .isInstanceOf(Snapshot.DefectTaskRow.class);
        assertThat(with.stream().filter(h -> h.id() == d.historyId() || h.id() == kept.historyId()))
                .allSatisfy(h -> assertThat(h.beforeData()).isNull());
    }

    /** The stored before_data text of the history row with the id. */
    private static JsonDocument beforeOf(List<HistoryView> rows, long id) {
        return rows.stream().filter(h -> h.id() == id).findFirst().orElseThrow().beforeData();
    }

    @Test
    @DisplayName("MCR-H12: a full page of before_data rows is complete or truncated by max_rows, never silently cut")
    void h12_includeBeforeAtTheLimit(Scenario s) {
        SaveResult d = s.newDefect("d");
        IntStream.range(1, 31).forEach(i -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(),
                i, Map.of("title", "t" + i))));
        var page = history(s).history(new HistoryQuery(null, null, null, "defect", d.id(), null, null, null, null,
                null, true, 20, null));
        assertThat(page.items()).hasSize(20);
        assertThat(page.truncated()).isTrue();
        assertThat(page.items().stream().skip(1)).allSatisfy(h -> assertThat(h.beforeData()).isNotNull());
        var all = history(s).history(new HistoryQuery(null, null, null, "defect", d.id(), null, null, null, null,
                null, true, 1000, null));
        assertThat(all.items()).hasSize(31);
        assertThat(all.truncated()).isFalse();
    }

    // ================================================================= search

    private Page<SearchService.Hit> search(Scenario s, List<String> keywords, String match) {
        return s.bean(SearchService.class).search(new SearchQuery(s.projectId(), null, keywords, match, null, null,
                null, null, null));
    }

    private static List<String> keywords(int count) {
        return IntStream.range(0, count).mapToObj(i -> "k" + i).toList();
    }

    @Test
    @DisplayName("MCR-S08: 20 keywords are accepted, 21 are refused before any SQL runs")
    void s08_keywordCount(Scenario s) {
        assertThat(search(s, keywords(20), null).items()).isEmpty();

        s.ctx().spy().clear();
        LedgerException e = Expect.failure(() -> search(s, keywords(21), null), ValidationCode.VALIDATION_FAILED);

        assertThat(e.args()).containsExactly("keywords");
        assertThat(Expect.firstIssue(e).reason()).isEqualTo(ValidationReason.MUST_HOLD_ITEMS);
        assertThat(Expect.firstIssue(e).reasonArgs()).containsExactly("0", "20");
        assertThat(s.ctx().spy().statements()).isEmpty();
    }

    @Test
    @DisplayName("MCR-H15: 200 ids are accepted, 201 are refused before any SQL runs")
    void h15_idCount(Scenario s) {
        List<Long> accepted = LongStream.rangeClosed(1, 200).boxed().toList();
        assertThat(history(s).history(new HistoryQuery(accepted, null, null, null, null, null, null, null, null,
                null, null, 1000, null)).items()).hasSizeLessThanOrEqualTo(200);

        s.ctx().spy().clear();
        List<Long> tooMany = LongStream.rangeClosed(1, 201).boxed().toList();
        LedgerException e = Expect.failure(() -> history(s).history(new HistoryQuery(tooMany, null, null, null,
                null, null, null, null, null, null, null, 1000, null)), ValidationCode.VALIDATION_FAILED);

        assertThat(e.args()).containsExactly("ids");
        assertThat(Expect.firstIssue(e).reason()).isEqualTo(ValidationReason.MUST_HOLD_ITEMS);
        assertThat(Expect.firstIssue(e).reasonArgs()).containsExactly("0", "200");
        assertThat(s.ctx().spy().statements()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "LOGIN", "LoGiN"})
    @DisplayName("MCR-S01: the keyword is found in every text field of defects, tasks and conditions, in any case")
    void s01_everyField(String keyword, Scenario s) {
        var title = s.defectFields("Login in the title");
        var behavior = s.defectFields("x1");
        behavior.put("behavior", "a Login problem");
        var cause = s.defectFields("x2");
        cause.put("root_cause", "the LOGIN form");
        var purpose = s.taskFields("x3");
        purpose.put("purpose", "fix login");
        var condition = s.conditionFields("r", "c");
        condition.put("condition_text", "Login works");
        List<String> ids = List.of(
                s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, title)).id(),
                s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, behavior)).id(),
                s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, cause)).id(),
                s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, purpose)).id(),
                s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("condition", s.conditionId("1"),
                        condition)).id());
        s.newDefect("nothing about it");
        assertThat(search(s, List.of(keyword), null).items()).extracting(SearchService.Hit::id)
                .containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    @DisplayName("MCR-S02: ANY by default, ALL on request, a wrong spelling of match is refused")
    void s02_match(Scenario s) {
        s.newDefect("alpha beta");
        s.newDefect("alpha");
        s.newDefect("beta");
        s.newDefect("gamma");
        assertThat(search(s, List.of("alpha", "beta"), null).items()).hasSize(3);
        assertThat(search(s, List.of("alpha", "beta"), "ANY").items()).hasSize(3);
        assertThat(search(s, List.of("alpha", "beta"), "ALL").items()).extracting(SearchService.Hit::title)
                .containsExactly("alpha beta");
        assertThat(search(s, List.of("alpha"), "ALL").items()).hasSameSizeAs(search(s, List.of("alpha"),
                "ANY").items());
        ValidationException e = (ValidationException) Expect.failure(() -> search(s, List.of("alpha"), "any"),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).containsExactly("ANY", "ALL");
        Expect.failure(() -> search(s, List.of("alpha"), "AND"), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("MCR-S03: the creation range is inclusive and judged in UTC")
    void s03_createdRange(Scenario s) {
        List<String> stamps = List.of("2026-11-04T23:59:59Z", "2026-11-05T00:00:00Z", "2026-11-05T14:59:59Z",
                "2026-11-05T15:00:00Z");
        List<String> ids = IntStream.range(0, 4).mapToObj(i -> {
            s.ctx().clock().set(Instant.parse(stamps.get(i)));
            return s.newDefect("ranged " + i).id();
        }).toList();
        var page = s.bean(SearchService.class).search(new SearchQuery(s.projectId(), null, List.of("ranged"), null,
                null, "2026-11-05T00:00:00Z", "2026-11-05T14:59:59Z", null, null));
        assertThat(page.items()).extracting(SearchService.Hit::id).containsExactlyInAnyOrder(ids.get(1), ids.get(2));
    }

    @Test
    @DisplayName("MCR-S04: kinds, project and excluded states narrow the result")
    void s04_filters(Scenario s) {
        SaveResult d = s.newDefect("common word");
        SaveResult merged = s.newDefect("common word two");
        s.newTask("common word task");
        MergeFixture.merge(s, merged.id(), 1, d.id());
        String other = s.otherProject();
        s.grant(Scenario.AUTHOR, other, "defect");
        var elsewhere = s.defectFields("common word elsewhere");
        elsewhere.put("project_id", other);
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, elsewhere));
        var onlyDefects = s.bean(SearchService.class).search(new SearchQuery(s.projectId(), List.of("defect"),
                List.of("common"), null, null, null, null, null, null));
        assertThat(onlyDefects.items()).hasSize(2);
        var withoutMerged = s.bean(SearchService.class).search(new SearchQuery(s.projectId(), null, List.of("common"),
                null, List.of("MERGED", "NOT_DEFECT"), null, null, null, null));
        assertThat(withoutMerged.items()).extracting(SearchService.Hit::id).doesNotContain(merged.id());
        assertThat(withoutMerged.items()).hasSize(2);
        assertThat(s.bean(SearchService.class).search(new SearchQuery(null, null, List.of("common word elsewhere"),
                null, null, null, null, null, null)).items()).hasSize(1);
    }

    @Test
    @DisplayName("MCR-S05: newest first")
    void s05_newestFirst(Scenario s) {
        IntStream.range(0, 4).forEach(i -> {
            s.ctx().clock().set(Instant.parse("2026-11-05T10:00:00Z").plusSeconds(i * 10L));
            s.newDefect("ordered " + i);
        });
        assertThat(search(s, List.of("ordered"), null).items()).extracting(SearchService.Hit::title)
                .containsExactly("ordered 3", "ordered 2", "ordered 1", "ordered 0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"100%", "_x", "a\\b", "'; DROP TABLE project; --"})
    @DisplayName("MCR-S06: percent, underscore and backslash are text, injection text is data")
    void s06_literals(String keyword, Scenario s) {
        SaveResult hit = s.newDefect("has " + keyword + " inside");
        s.newDefect("1000 and axb");
        assertThat(search(s, List.of(keyword), null).items()).extracting(SearchService.Hit::id)
                .containsExactly(hit.id());
        assertThat(s.count("SELECT COUNT(*) FROM project WHERE id = ?", s.projectId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("MCR-S06: a blank keyword is refused")
    void s06_blank(String keyword, Scenario s) {
        LedgerException e = Expect.failure(() -> search(s, List.of(keyword), null), ValidationCode.VALIDATION_FAILED);
        assertThat(e.args()).containsExactly("keywords");
        assertThat(Expect.firstIssue(e).reason()).isEqualTo(ValidationReason.MUST_NOT_BE_BLANK);
        assertThat(Expect.firstIssue(e).reasonArgs()).isEmpty();
    }

    @Test
    @DisplayName("MCR-S06: a keyword of 1000 characters is refused")
    void s06_tooLong(Scenario s) {
        LedgerException e = Expect.failure(() -> search(s, List.of("k".repeat(1000)), null),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.args()).containsExactly("keywords");
        assertThat(Expect.firstIssue(e).reason()).isEqualTo(ValidationReason.MUST_BE_AT_MOST_CHARS);
        assertThat(Expect.firstIssue(e).reasonArgs()).containsExactly("200");
    }

    @Test
    @DisplayName("MCR-S07: ASCII ignores case, Korean text is matched as written")
    void s07_korean(Scenario s) {
        SaveResult d = s.newDefect("Login 로그인 오류");
        assertThat(search(s, List.of("login"), null).items()).extracting(SearchService.Hit::id).containsExactly(d.id());
        assertThat(search(s, List.of("로그인"), null).items()).hasSize(1);
        assertThat(search(s, List.of("로그"), null).items()).hasSize(1);
        assertThat(search(s, List.of("로그아웃"), null).items()).isEmpty();
    }

    // ============================================================ lists, etc.

    @Test
    @DisplayName("MCR-L01: only the project's defects, and the project is required")
    void l01_projectRequired(Scenario s) {
        s.newDefect("mine");
        String other = s.otherProject();
        s.grant(Scenario.AUTHOR, other, "defect");
        var elsewhere = s.defectFields("theirs");
        elsewhere.put("project_id", other);
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, elsewhere));
        var mine = s.bean(ListService.class).defects(new DefectsQuery(s.projectId(), null, null, null, null, null,
                null, null));
        assertThat(mine.items()).extracting(ListService.DefectItem::title).containsExactly("mine");
        LedgerException missing = Expect.failure(() -> s.bean(ListService.class).defects(new DefectsQuery(null, null, null,
                null, null, null, null, null)), ValidationCode.VALIDATION_FAILED);
        assertThat(missing.args()).containsExactly("project");
        assertThat(Expect.firstIssue(missing).reason()).isEqualTo(ValidationReason.REQUIRED);
        assertThat(Expect.firstIssue(missing).reasonArgs()).isEmpty();
        LedgerException longOwner = Expect.failure(() -> s.bean(ListService.class).defects(new DefectsQuery(
                s.projectId(), null, null, null, null, "o".repeat(201), null, null)),
                ValidationCode.VALIDATION_FAILED);
        assertThat(longOwner.args()).containsExactly("owner");
        assertThat(Expect.firstIssue(longOwner).reason()).isEqualTo(ValidationReason.MUST_BE_AT_MOST_CHARS);
        assertThat(Expect.firstIssue(longOwner).reasonArgs()).containsExactly("200");
    }

    @Test
    @DisplayName("MCR-L02: status, severity, category, attention and owner narrow with AND; owner falls back to the fixer")
    void l02_filters(Scenario s) {
        SaveResult open = s.newDefect("open");
        SaveResult fixed = s.sourceFixedDefect("fixed");
        var p1 = s.defectFields("urgent");
        p1.put("severity", "P1");
        SaveResult urgent = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, p1));
        s.ctx().jdbc().sql("UPDATE defect SET severity = 'UNRATED' WHERE id = ?").param(open.id()).update();
        ListService lists = s.bean(ListService.class);
        assertThat(lists.defects(new DefectsQuery(s.projectId(), "SOURCE_FIXED", null, null, null, null, null, null))
                .items()).extracting(ListService.DefectItem::id).containsExactly(fixed.id());
        assertThat(lists.defects(new DefectsQuery(s.projectId(), null, "P1", null, null, null, null, null)).items())
                .extracting(ListService.DefectItem::id).containsExactly(urgent.id());
        assertThat(lists.defects(new DefectsQuery(s.projectId(), null, "UNRATED", null, null, null, null, null))
                .items()).extracting(ListService.DefectItem::id).containsExactly(open.id());
        assertThat(lists.defects(new DefectsQuery(s.projectId(), null, null, "ARC", null, Scenario.AUTHOR, null, null))
                .items()).extracting(ListService.DefectItem::id).containsExactly(fixed.id());
        assertThat(lists.defects(new DefectsQuery(s.projectId(), "OPEN", "P1", null, "OPEN_NO_OWNER", null, null,
                null)).items()).extracting(ListService.DefectItem::id).containsExactly(urgent.id());
        ValidationException e = (ValidationException) Expect.failure(() -> lists.defects(new DefectsQuery(
                s.projectId(), "FIXED", null, null, null, null, null, null)), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).contains("OPEN", "SOURCE_FIXED");
    }

    @Test
    @DisplayName("MCR-L03: tasks by state and owner, conditions by state and release")
    void l03_tasksAndConditions(Scenario s) {
        var owned = s.taskFields("owned");
        owned.put("owner_actor_id", "codex:owner1");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, owned));
        SaveResult plain = s.newTask("plain");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("task", plain.id(), 1, "ACTIVE", null));
        s.newCondition("1", "r1", "c1");
        s.newCondition("2", "r2", "c2");
        ListService lists = s.bean(ListService.class);
        assertThat(lists.tasks(new TasksQuery(s.projectId(), "ACTIVE", null, null, null, null)).items())
                .extracting(ListService.TaskItem::id).containsExactly(plain.id());
        assertThat(lists.tasks(new TasksQuery(s.projectId(), null, "codex:owner1", null, null, null)).items())
                .extracting(ListService.TaskItem::title).containsExactly("owned");
        assertThat(lists.conditions(new ConditionsQuery(s.projectId(), "UNKNOWN", "r2", null, null)).items())
                .extracting(ListService.ConditionItem::id).containsExactly(s.conditionId("2"));
        assertThat(lists.tasks(new TasksQuery(s.projectId(), null, null, null, null, null)).items()
                .getFirst().title()).as("newest first").isEqualTo("plain");
    }

    @Test
    @DisplayName("MCR-L04: the five attention kinds appear when triggered and disappear when resolved")
    void l04_attentionKinds(Scenario s) {
        SaveResult withFix = s.newDefect("with fix");
        s.bean(org.pgledger.application.write.RecordFixService.class).recordFix(Scenario.AUTHOR,
                new org.pgledger.application.write.RecordFixCommand(null, withFix.id(), "repo/e2e",
                        Scenario.commit('a'), Scenario.REASON, null, null, null, null, false));
        SaveResult noCommit = s.newDefect("no commit");
        s.ctx().jdbc().sql("UPDATE defect SET status = 'SOURCE_FIXED' WHERE id = ?").param(noCommit.id()).update();
        SaveResult unknownTask = s.newTask("unknown");
        s.ctx().jdbc().sql("UPDATE task SET state = 'UNKNOWN' WHERE id = ?").param(unknownTask.id()).update();
        SaveResult done = s.newTask("done");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("task", done.id(), 1, "REPORTED_DONE",
                Scenario.EVIDENCE));
        AttentionService attention = s.bean(AttentionService.class);
        List<String> found = attention.attention(new AttentionQuery(s.projectId(), null, 1000, null)).items().stream()
                .map(i -> i.kind() + ":" + i.id()).toList();
        SaveResult bare = s.newDefect("bare");
        found = attention.attention(new AttentionQuery(s.projectId(), null, 1000, null)).items().stream()
                .map(i -> i.kind() + ":" + i.id()).toList();
        assertThat(found).contains("OPEN_WITH_FIX:" + withFix.id(), "FIXED_WITHOUT_COMMIT:" + noCommit.id(),
                "TASK_UNKNOWN:" + unknownTask.id(), "DONE_NOT_VERIFIED:" + done.id(),
                "OPEN_NO_OWNER:" + bare.id());
        assertThat(found).doesNotContain("OPEN_NO_OWNER:" + withFix.id());
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("task", done.id(), 2, "VERIFIED",
                Scenario.EVIDENCE));
        var after = attention.attention(new AttentionQuery(s.projectId(), "DONE_NOT_VERIFIED", 1000, null));
        assertThat(after.items()).isEmpty();
    }

    @Test
    @DisplayName("MCR-L05: attention can be narrowed by project and kind")
    void l05_attentionFilters(Scenario s) {
        s.newDefect("one");
        s.newTask("two");
        AttentionService attention = s.bean(AttentionService.class);
        assertThat(attention.attention(new AttentionQuery(s.projectId(), "OPEN_NO_OWNER", null, null)).items())
                .hasSize(1);
        assertThat(attention.attention(new AttentionQuery(s.otherProject(), null, null, null)).items()).isEmpty();
        assertThat(attention.attention(new AttentionQuery(null, "TASK_UNKNOWN", null, null)).items())
                .allSatisfy(i -> assertThat(i.entity()).isEqualTo("task"));
    }

    // ================================================================= stats

    @Test
    @DisplayName("MCR-ST01: every state is counted, zero when empty, and the totals equal the row counts")
    void st01_counts(Scenario s) {
        SaveResult open = s.newDefect("open");
        SaveResult source = s.newDefect("source");
        s.newDefect("rated later");
        s.ctx().jdbc().sql("UPDATE defect SET severity = 'UNRATED' WHERE id = ?").param(open.id()).update();
        s.newTask("t1");
        s.newCondition("1", "r", "c");
        MergeFixture.merge(s, source.id(), 1, open.id());
        StatsService.Stats stats = s.bean(StatsService.class).stats(new StatsQuery(s.projectId(), null));
        assertThat(stats.defectsByStatus()).containsEntry("OPEN", 2L).containsEntry("MERGED", 1L)
                .containsEntry("VERIFIED", 0L).hasSize(6);
        assertThat(stats.defectsBySeverity()).containsEntry("UNRATED", 1L).containsEntry("P2", 2L)
                .containsEntry("P1", 0L);
        assertThat(stats.tasksByState()).containsEntry("PLANNED", 1L).hasSize(7);
        assertThat(stats.conditionsByState()).containsEntry("UNKNOWN", 1L).hasSize(3);
        assertThat(stats.defects()).isEqualTo(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?",
                s.projectId()));
        assertThat(stats.tasks()).isEqualTo(1);
        assertThat(stats.conditions()).isEqualTo(1);
        assertThat(s.bean(StatsService.class).stats(new StatsQuery(null, null)).defects())
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("MCR-ST02: the day bucket is a UTC day, a local midnight changes nothing")
    void st02_utcDays(Scenario s) {
        List<String> stamps = List.of("2026-11-05T14:59:59Z", "2026-11-05T15:00:00Z", "2026-11-05T23:59:59Z",
                "2026-11-06T00:00:00Z");
        stamps.forEach(stamp -> {
            s.ctx().clock().set(Instant.parse(stamp));
            s.newDefect("at " + stamp);
        });
        var days = s.bean(StatsService.class).stats(new StatsQuery(s.projectId(), "day")).createdPerDay();
        assertThat(days).extracting(StatsService.DayCount::day).containsExactly(LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 6));
        assertThat(days).extracting(StatsService.DayCount::defects).containsExactly(3L, 1L);
        Expect.failure(() -> s.bean(StatsService.class).stats(new StatsQuery(s.projectId(), "hour")),
                ValidationCode.VALIDATION_FAILED);
    }

    // ===================================================== projects and codes

    @Test
    @DisplayName("MCR-P01: projects with id, code and title, ordered by code")
    void p01_projects(Scenario s) {
        Page<CatalogService.ProjectView> page = s.bean(CatalogService.class).projects(new ProjectsQuery(1000, null));
        assertThat(page.items()).extracting(CatalogService.ProjectView::id).contains(s.projectId());
        assertThat(page.items().stream().map(CatalogService.ProjectView::code).toList()).isSorted();
        CatalogService.ProjectView mine = page.items().stream().filter(p -> Objects.equals(p.id(), s.projectId())).findFirst()
                .orElseThrow();
        assertThat(mine.code()).isEqualTo(s.code());
        assertThat(mine.title()).startsWith("Project");
    }

    @Test
    @DisplayName("MCR-K01: codes of the three groups, retired ones only on request, a change is visible at once")
    void k01_codes(Scenario s) {
        String added = "UI_" + s.code();
        String retired = "OLD_" + s.code();
        CommonCodeService codes = s.bean(CommonCodeService.class);
        codes.set(new CodeCommand("DEFECT_CATEGORY", added, "화면", "UI", "설명", "description", 33, null));
        codes.set(new CodeCommand("DEFECT_CATEGORY", retired, "옛", "Old", null, null, 34, "INACTIVE"));
        CatalogService catalog = s.bean(CatalogService.class);
        var active = catalog.codes(new CodesQuery("DEFECT_CATEGORY", null, 1000, null)).items();
        assertThat(active).extracting(CatalogService.CodeView::code).contains(added).doesNotContain(retired);
        var everything = catalog.codes(new CodesQuery("DEFECT_CATEGORY", true, 1000, null)).items();
        assertThat(everything).extracting(CatalogService.CodeView::code).contains(added, retired);
        CatalogService.CodeView view = everything.stream().filter(c -> Objects.equals(c.code(), added)).findFirst()
                .orElseThrow();
        assertThat(view.nameKo()).isEqualTo("화면");
        assertThat(view.nameEn()).isEqualTo("UI");
        assertThat(view.descriptionKo()).isEqualTo("설명");
        assertThat(view.sortOrder()).isEqualTo(33);
        assertThat(view.state()).isEqualTo("ACTIVE");
        assertThat(catalog.codes(new CodesQuery(null, true, 1000, null)).items()).extracting(
                CatalogService.CodeView::group).containsOnly("DEFECT_CATEGORY", "DEFECT_SEVERITY", "RELATION_KIND");
        Expect.failure(() -> catalog.codes(new CodesQuery("X", null, null, null)), ValidationCode.VALIDATION_FAILED);
    }

    // ======================================================= read behaviour

    @Test
    @DisplayName("MCR-010: reading changes nothing, needs no attestation and no grant")
    void mcr010_readsAreReadOnly(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.newTask("t");
        String state = "SELECT COUNT(*) + SUM(version) FROM defect WHERE project_id = ?";
        long before = s.count(state, s.projectId());
        long history = s.count("SELECT COUNT(*) FROM history");
        IntStream.range(0, 100).forEach(i -> {
            s.bean(GetService.class).get(List.of(d.id()));
            s.bean(DetailService.class).defect(d.id());
            rows(s);
            search(s, List.of("d"), null);
        });
        assertThat(s.count(state, s.projectId())).isEqualTo(before);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
    }

    private static final Pattern INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z)?");

    @Test
    @DisplayName("MCR-012: every instant of an output ends with Z")
    void mcr012_instantsEndWithZ(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d.id(), "seen",
                Scenario.REASON, null, null, "2026-11-05T09:00:00+01:00"));
        LedgerJson json = s.bean(LedgerJson.class);
        List<String> outputs = List.of(json.write(s.bean(DetailService.class).defect(d.id())).text(),
                json.write(rows(s)).text(), json.write(s.bean(ListService.class).defects(new DefectsQuery(
                        s.projectId(), null, null, null, null, null, null, null)).items()).text());
        assertThat(outputs).allSatisfy(text -> {
            var matcher = INSTANT.matcher(text);
            int found = 0;
            while (matcher.find()) {
                found++;
                assertThat(matcher.group(2)).as(matcher.group()).isEqualTo("Z");
            }
            assertThat(found).isPositive();
        });
    }
}
