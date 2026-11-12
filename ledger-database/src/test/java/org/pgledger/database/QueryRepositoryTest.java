package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.AttentionQueryRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectQueryRepository;
import org.pgledger.database.repository.DefectQueryRepository.Filter;
import org.pgledger.database.repository.DefectQueryRepository.Order;
import org.pgledger.database.repository.DefectQueryRepository.Position;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.DefectSummary;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.KeysetPage;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.MergeChainRepository;
import org.pgledger.database.repository.StatsQueryRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.database.support.LedgerDataJdbcTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.RecordingDataSource;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.MaxRows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * REPO-025 .. REPO-028, REPO-034, REPO-036: row limits, keyset pages, search, day ranges, counts, merge chains, as a
 * {@code @DataJdbcTest} slice (W7-SLICE-01, principle 7.2): every test runs in a transaction that Spring rolls back, with a
 * project of its own.
 */
@LedgerDataJdbcTest
class QueryRepositoryTest {

    private static final java.util.regex.Pattern ROW_LIMIT_KEYWORD = java.util.regex.Pattern.compile(
            "\\b(LIMIT|FETCH|TOP|ROWNUM|OFFSET)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RecordingDataSource spy;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DefectQueryRepository queries;

    @Autowired
    private DefectRepository defects;

    @Autowired
    private DefectFixRepository fixes;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private IdAllocator ids;

    @Autowired
    private StatsQueryRepository stats;

    @Autowired
    private AttentionQueryRepository attentionQueries;

    @Autowired
    private MergeChainRepository mergeChains;

    private Seed seed;

    @BeforeEach
    void setUp() {
        seed = new Seed(context);
    }

    private Project projectWithDefects(int count, boolean distinctTimes) {
        Project project = seed.project();
        IntStream.rangeClosed(1, count).forEach(i -> seed.defect(project, i, "row " + i,
                LedgerTestContext.START.plusSeconds(distinctTimes ? i : 0)));
        return project;
    }

    /** A page size and what the 30 stored rows make of it. */
    private record PageSize(int max, int rows, boolean truncated) {
    }

    static Stream<PageSize> pageSizes() {
        return Stream.of(new PageSize(1, 1, true), new PageSize(10, 10, true), new PageSize(29, 29, true),
                new PageSize(30, 30, false), new PageSize(1000, 30, false));
    }

    @ParameterizedTest
    @MethodSource("pageSizes")
    void repo025_oneRowMoreThanTheLimitTellsTheListContinuesWithoutVendorSql(PageSize size) {
        Project project = projectWithDefects(30, true);
        spy.clear();
        KeysetPage<DefectSummary> page = queries.page(project.id(), Filter.NONE, new MaxRows(size.max()),
                Order.NEWEST_FIRST, null);
        assertThat(page.rows()).hasSize(size.rows());
        assertThat(page.truncated()).isEqualTo(size.truncated());
        assertThat(spy.statements()).isNotEmpty().noneMatch(s -> ROW_LIMIT_KEYWORD.matcher(s).find());
    }

    /** All ids of a list, two rows per page, following the position of each page's last row. */
    private List<DefectId> walk(Project project, Order order, Position after) {
        KeysetPage<DefectSummary> page = queries.page(project.id(), Filter.NONE, new MaxRows(2), order, after);
        Stream<DefectId> here = page.rows().stream().map(DefectSummary::id);
        DefectSummary last = page.rows().getLast();
        return page.truncated()
                ? Stream.concat(here, walk(project, order, new Position(last.createdAt(), last.id())).stream())
                        .toList()
                : here.toList();
    }

    @ParameterizedTest
    @EnumSource(Order.class)
    void repo026_sevenRowsWithTheSameTimeAreWalkedExactlyOnceInEveryOrder(Order order) {
        Project project = projectWithDefects(7, false);
        List<DefectId> expected = IntStream.rangeClosed(1, 7).mapToObj(i -> DefectId.of(project.code(), i))
                .sorted(order == Order.NEWEST_FIRST ? Comparator.comparing(DefectId::value).reversed()
                        : Comparator.comparing(DefectId::value)).toList();
        assertThat(walk(project, order, null)).isEqualTo(expected);
    }

    /** Keywords, how they combine, and the titles found (newest first) among the six defects of the fixture. */
    private record Search(List<String> keywords, KeywordMatch match, List<String> titles) {
        @Override
        public String toString() {
            return keywords + " " + match;
        }
    }

    private static final List<String> SEARCH_TITLES = List.of("Login Bug", "login bug", "LOGIN BUG", "100%_done",
            "a\\b", "1000 done");
    private static final List<String> LOGIN_TITLES = List.of("LOGIN BUG", "login bug", "Login Bug");

    static Stream<Search> searches() {
        return Stream.of(
                new Search(List.of("login"), KeywordMatch.ANY, LOGIN_TITLES),
                new Search(List.of("LOGIN"), KeywordMatch.ANY, LOGIN_TITLES),
                new Search(List.of("100%"), KeywordMatch.ANY, List.of("100%_done")),
                new Search(List.of("_done"), KeywordMatch.ANY, List.of("100%_done")),
                new Search(List.of("a\\b"), KeywordMatch.ANY, List.of("a\\b")),
                new Search(List.of("login", "nothing"), KeywordMatch.ANY, LOGIN_TITLES),
                new Search(List.of("login", "nothing"), KeywordMatch.ALL, List.of()),
                new Search(List.of("behavior of login"), KeywordMatch.ANY, LOGIN_TITLES),
                new Search(List.of("'; DROP TABLE project; --"), KeywordMatch.ANY, List.of()));
    }

    @ParameterizedTest
    @MethodSource("searches")
    void repo027_searchIsCaseInsensitiveAndEscapesLikeCharacters(Search search) {
        Project project = seed.project();
        IntStream.range(0, SEARCH_TITLES.size()).forEach(i -> seed.defect(project, i + 1, SEARCH_TITLES.get(i),
                LedgerTestContext.START.plusSeconds(i + 1)));
        assertThat(queries.search(project.id(), search.keywords(), search.match(), MaxRows.DEFAULT).rows())
                .extracting(DefectSummary::title).containsExactlyElementsOf(search.titles());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM project").query(Integer.class).single())
                .as("injection text is data: the table survives").isPositive();
    }

    @Test
    void repo027_aSearchWithoutKeywordsIsRefused() {
        assertThatThrownBy(() -> queries.search(seed.project().id(), List.of(), KeywordMatch.ANY, MaxRows.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A creation-time range (inclusive; an open end is null) and the titles it finds, oldest first. */
    private record Range(String name, Instant from, Instant to, List<String> titles) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Instant> RANGE_TIMES = List.of(Instant.parse("2026-11-04T23:59:59.999999Z"),
            Instant.parse("2026-11-05T00:00:00Z"), Instant.parse("2026-11-05T14:59:59Z"),
            Instant.parse("2026-11-05T15:00:00Z"));

    static Stream<Range> ranges() {
        return Stream.of(
                new Range("the UTC day 2026-11-05", Instant.parse("2026-11-05T00:00:00Z"),
                        Instant.parse("2026-11-05T23:59:59.999999Z"), List.of("t1", "t2", "t3")),
                new Range("the same day in Seoul (KST)", Instant.parse("2026-11-04T15:00:00Z"),
                        Instant.parse("2026-11-05T14:59:59.999999Z"), List.of("t0", "t1", "t2")),
                new Range("both ends are inclusive", Instant.parse("2026-11-05T14:59:59Z"),
                        Instant.parse("2026-11-05T15:00:00Z"), List.of("t2", "t3")),
                new Range("open end", Instant.parse("2026-11-05T15:00:00Z"), null, List.of("t3")));
    }

    @ParameterizedTest
    @MethodSource("ranges")
    void repo028_dayRangesAreInclusiveAtBothEnds(Range range) {
        Project project = seed.project();
        IntStream.range(0, RANGE_TIMES.size()).forEach(i -> seed.defect(project, i + 1, "t" + i, RANGE_TIMES.get(i)));
        assertThat(queries.page(project.id(), new Filter(null, null, null, range.from(), range.to()),
                MaxRows.DEFAULT, Order.OLDEST_FIRST, null).rows()).extracting(DefectSummary::title)
                .containsExactlyElementsOf(range.titles());
    }

    @Test
    void repo034_countsAndTheFiveAttentionKinds() {
        Project project = seed.project();
        Defect openWithFix = seed.defect(project, 1, "open with fix", LedgerTestContext.START);
        Defect openNoOwner = seed.defect(project, 2, "open no owner", LedgerTestContext.START);
        Defect fixedNoCommit = LedgerTestContext.as(Seed.ALICE, () -> defects.save(
                seed.defect(project, 3, "fixed", LedgerTestContext.START)
                        .transitioned(DefectStatus.SOURCE_FIXED, null, LedgerTestContext.START)));
        Defect verifiedWithFix = LedgerTestContext.as(Seed.ALICE, () -> defects.save(
                seed.defect(project, 4, "verified", LedgerTestContext.START)
                        .transitioned(DefectStatus.VERIFIED, null, LedgerTestContext.START)));
        fixes.insert(DefectFix.record(ids.next(Counter.DEFECT_FIX), openWithFix.id(), "repo",
                CommitSha.of("d".repeat(40)), null, null, null, null, Seed.ALICE, LedgerTestContext.START));
        fixes.insert(DefectFix.record(ids.next(Counter.DEFECT_FIX), verifiedWithFix.id(), "repo",
                CommitSha.of("e".repeat(40)), null, null, null, null, Seed.ALICE, LedgerTestContext.START));
        var unknownTask = seed.task(project, 1, "unknown task");
        LedgerTestContext.as(Seed.ALICE, () -> tasks.save(unknownTask.transitioned(TaskState.UNKNOWN,
                LedgerTestContext.START)));
        var doneTask = seed.task(project, 2, "done task");
        LedgerTestContext.as(Seed.ALICE, () -> tasks.save(doneTask.transitioned(TaskState.REPORTED_DONE,
                LedgerTestContext.START)));

        assertThat(stats.defectsByStatus(project.id())).containsEntry(DefectStatus.OPEN.name(), 2L)
                .containsEntry(DefectStatus.SOURCE_FIXED.name(), 1L).containsEntry(DefectStatus.VERIFIED.name(), 1L);
        assertThat(stats.defectsBySeverity(project.id())).containsEntry("P2", 4L);
        List<AttentionQueryRepository.Item> attention = attentionQueries
                .page(project.id(), null, MaxRows.DEFAULT, null).rows();
        assertThat(attention).extracting(AttentionQueryRepository.Item::kind, AttentionQueryRepository.Item::id)
                .contains(tuple(AttentionKind.OPEN_WITH_FIX, openWithFix.id().value()),
                        tuple(AttentionKind.OPEN_NO_OWNER, openNoOwner.id().value()),
                        tuple(AttentionKind.OPEN_NO_OWNER, openWithFix.id().value()),
                        tuple(AttentionKind.FIXED_WITHOUT_COMMIT, fixedNoCommit.id().value()),
                        tuple(AttentionKind.TASK_UNKNOWN, unknownTask.id().value()),
                        tuple(AttentionKind.DONE_NOT_VERIFIED, doneTask.id().value()));
        assertThat(attention).extracting(AttentionQueryRepository.Item::id).doesNotContain(verifiedWithFix.id().value());
        assertThat(attention).hasSize(6);
        // the same meaning in the filter of the list: a kind selects the rows the attention list names for it
        assertThat(queries.page(project.id(), new Filter(null, null, null, null, null,
                null, AttentionKind.FIXED_WITHOUT_COMMIT), MaxRows.DEFAULT, Order.OLDEST_FIRST, null).rows())
                .extracting(DefectSummary::id).containsExactly(fixedNoCommit.id());
        // a kind of the other entity is a mistake of the caller
        assertThatThrownBy(() -> queries.page(project.id(), new Filter(null, null, null,
                null, null, null, AttentionKind.TASK_UNKNOWN), MaxRows.DEFAULT, Order.OLDEST_FIRST, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("defect attention kind");
    }

    @Test
    void repo036_aMergeChainOfDepth50IsWalkedWithoutRecursiveSql() {
        Project project = seed.project();
        Defect root = seed.defect(project, 1, "root", LedgerTestContext.START);
        // the chain runs root <- 2 <- 3 ... <- 51: every defect is merged into the one before it
        List<DefectId> chain = IntStream.rangeClosed(2, 51).mapToObj(i -> DefectId.of(project.code(), i)).toList();
        chain.stream().reduce(root.id(), (target, id) -> {
            Defect child = seed.defect(project, DefectId.of(id.value()).number(), "link " + id.value(),
                    LedgerTestContext.START);
            LedgerTestContext.as(Seed.ALICE, () -> defects.save(
                    child.transitioned(DefectStatus.MERGED, target, LedgerTestContext.START)));
            return id;
        });
        spy.clear();
        assertThat(mergeChains.mergedInto(root.id())).containsExactlyInAnyOrderElementsOf(chain);
        assertThat(spy.statements()).noneMatch(s -> s.toUpperCase().contains("RECURSIVE"));
        assertThat(mergeChains.mergedInto(chain.getLast())).isEmpty();
    }
}
