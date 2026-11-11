package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import java.util.Comparator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.DbmsAware;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectQueryRepository;
import org.pgledger.database.repository.DefectQueryRepository.Filter;
import org.pgledger.database.repository.DefectQueryRepository.Order;
import org.pgledger.database.repository.DefectQueryRepository.Position;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.DefectSummary;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.KeysetPage;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.MergeChainRepository;
import org.pgledger.database.repository.StatsRepository;
import org.pgledger.database.repository.StatsRepository.AttentionItem;
import org.pgledger.database.support.Dbms;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.MaxRows;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-025 .. REPO-028, REPO-034, REPO-036: row limits, keyset pages, search, day ranges, counts, merge chains. */
@DatabaseTest
class QueryRepositoryTest implements DbmsAware {

    @Parameter
    Dbms dbms;

    private LedgerTestContext ctx;
    private Seed seed;
    private DefectQueryRepository queries;

    @Override
    public Dbms dbms() {
        return dbms;
    }

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
        queries = ctx.bean(DefectQueryRepository.class);
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
        ctx.spy().clear();
        KeysetPage<DefectSummary> page = queries.page(project.id(), Filter.NONE, new MaxRows(size.max()),
                Order.NEWEST_FIRST, null);
        assertThat(page.rows()).hasSize(size.rows());
        assertThat(page.truncated()).isEqualTo(size.truncated());
        assertThat(ctx.spy().statements()).isNotEmpty().noneMatch(s -> s.toUpperCase().matches(
                "(?s).*\\b(LIMIT|FETCH|TOP|ROWNUM|OFFSET)\\b.*"));
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

    @Test
    void repo027_searchIsCaseInsensitiveAndEscapesLikeCharacters() {
        Project project = seed.project();
        seed.defect(project, 1, "Login Bug", LedgerTestContext.START.plusSeconds(1));
        seed.defect(project, 2, "login bug", LedgerTestContext.START.plusSeconds(2));
        seed.defect(project, 3, "LOGIN BUG", LedgerTestContext.START.plusSeconds(3));
        seed.defect(project, 4, "100%_done", LedgerTestContext.START.plusSeconds(4));
        seed.defect(project, 5, "a\\b", LedgerTestContext.START.plusSeconds(5));
        seed.defect(project, 6, "1000 done", LedgerTestContext.START.plusSeconds(6));
        assertThat(search(project, "login")).hasSize(3);
        assertThat(search(project, "LOGIN")).hasSize(3);
        assertThat(search(project, "100%")).extracting(DefectSummary::title).containsExactly("100%_done");
        assertThat(search(project, "_done")).extracting(DefectSummary::title).containsExactly("100%_done");
        assertThat(search(project, "a\\b")).extracting(DefectSummary::title).containsExactly("a\\b");
        // the percent sign and underscore are literal: "1000 done" matches neither "100%" nor "_done"
        assertThat(search(project, "100%")).extracting(DefectSummary::title).doesNotContain("1000 done");
        // any versus all keywords
        assertThat(queries.search(project.id(), List.of("login", "nothing"), KeywordMatch.ANY, MaxRows.DEFAULT).rows())
                .hasSize(3);
        assertThat(queries.search(project.id(), List.of("login", "nothing"), KeywordMatch.ALL, MaxRows.DEFAULT).rows())
                .isEmpty();
        // the long text columns are searched too
        assertThat(search(project, "behavior of login")).hasSize(3);
        // injection text is data: the table survives and nothing matches
        assertThat(search(project, "'; DROP TABLE project; --")).isEmpty();
        assertThat(JdbcClient.create(ctx.pool()).sql("SELECT COUNT(*) FROM project").query(Integer.class).single())
                .isPositive();
        assertThatThrownBy(() -> queries.search(project.id(), List.of(), KeywordMatch.ANY, MaxRows.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<DefectSummary> search(Project project, String keyword) {
        return queries.search(project.id(), List.of(keyword), KeywordMatch.ANY, MaxRows.DEFAULT).rows();
    }

    @Test
    void repo028_dayRangesAreInclusiveAtBothEnds() {
        Project project = seed.project();
        Instant[] times = {Instant.parse("2026-11-04T23:59:59.999999Z"), Instant.parse("2026-11-05T00:00:00Z"),
                Instant.parse("2026-11-05T14:59:59Z"), Instant.parse("2026-11-05T15:00:00Z")};
        for (int i = 0; i < times.length; i++) {
            seed.defect(project, i + 1, "t" + i, times[i]);
        }
        // the UTC day 2026-11-05
        assertThat(titles(project, Instant.parse("2026-11-05T00:00:00Z"), Instant.parse("2026-11-05T23:59:59.999999Z")))
                .containsExactlyInAnyOrder("t1", "t2", "t3");
        // the same day in Seoul (KST) is 2026-11-04T15:00:00Z .. 2026-11-05T14:59:59.999999Z
        assertThat(titles(project, Instant.parse("2026-11-04T15:00:00Z"), Instant.parse("2026-11-05T14:59:59.999999Z")))
                .containsExactlyInAnyOrder("t0", "t1", "t2");
        assertThat(titles(project, Instant.parse("2026-11-05T14:59:59Z"), Instant.parse("2026-11-05T15:00:00Z")))
                .containsExactlyInAnyOrder("t2", "t3");
        assertThat(queries.page(project.id(), new Filter(null, null, null, Instant.parse("2026-11-05T15:00:00Z"), null),
                MaxRows.DEFAULT, Order.OLDEST_FIRST, null).rows()).extracting(DefectSummary::title)
                .containsExactly("t3");
    }

    private List<String> titles(Project project, Instant from, Instant to) {
        return queries.page(project.id(), new Filter(null, null, null, from, to), MaxRows.DEFAULT,
                Order.OLDEST_FIRST, null).rows().stream().map(DefectSummary::title).toList();
    }

    @Test
    void repo034_countsAndTheFiveAttentionKinds() {
        Project project = seed.project();
        DefectRepository defects = ctx.bean(DefectRepository.class);
        IdAllocator ids = ctx.bean(IdAllocator.class);
        Defect openWithFix = seed.defect(project, 1, "open with fix", LedgerTestContext.START);
        Defect openNoOwner = seed.defect(project, 2, "open no owner", LedgerTestContext.START);
        Defect fixedNoCommit = LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> defects.save(
                seed.defect(project, 3, "fixed", LedgerTestContext.START)
                        .transitioned(DefectStatus.SOURCE_FIXED, null, LedgerTestContext.START))));
        Defect verifiedWithFix = LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> defects.save(
                seed.defect(project, 4, "verified", LedgerTestContext.START)
                        .transitioned(DefectStatus.VERIFIED, null, LedgerTestContext.START))));
        ctx.tx(() -> ctx.bean(DefectFixRepository.class).insert(DefectFix.record(ids.next(Counter.DEFECT_FIX),
                openWithFix.id(), "repo", CommitSha.of("d".repeat(40)), null, null, null, null, Seed.ALICE,
                LedgerTestContext.START)));
        ctx.tx(() -> ctx.bean(DefectFixRepository.class).insert(DefectFix.record(ids.next(Counter.DEFECT_FIX),
                verifiedWithFix.id(), "repo", CommitSha.of("e".repeat(40)), null, null, null, null, Seed.ALICE,
                LedgerTestContext.START)));
        var unknownTask = seed.task(project, 1, "unknown task");
        LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(org.pgledger.database.repository.TaskRepository.class)
                .save(unknownTask.transitioned(org.pgledger.domain.rule.TaskState.UNKNOWN, LedgerTestContext.START))));
        var doneTask = seed.task(project, 2, "done task");
        LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(org.pgledger.database.repository.TaskRepository.class)
                .save(doneTask.transitioned(org.pgledger.domain.rule.TaskState.REPORTED_DONE, LedgerTestContext.START))));

        StatsRepository stats = ctx.bean(StatsRepository.class);
        assertThat(stats.defectsByStatus(project.id())).containsEntry(DefectStatus.OPEN, 2L)
                .containsEntry(DefectStatus.SOURCE_FIXED, 1L).containsEntry(DefectStatus.VERIFIED, 1L);
        assertThat(stats.defectsBySeverity(project.id())).containsEntry("P2", 4L);
        List<AttentionItem> attention = stats.attention(project.id());
        assertThat(attention).contains(new AttentionItem(AttentionKind.OPEN_WITH_FIX, openWithFix.id().value()),
                new AttentionItem(AttentionKind.OPEN_NO_OWNER, openNoOwner.id().value()),
                new AttentionItem(AttentionKind.OPEN_NO_OWNER, openWithFix.id().value()),
                new AttentionItem(AttentionKind.FIXED_WITHOUT_COMMIT, fixedNoCommit.id().value()),
                new AttentionItem(AttentionKind.TASK_UNKNOWN, unknownTask.id().value()),
                new AttentionItem(AttentionKind.DONE_NOT_VERIFIED, doneTask.id().value()));
        assertThat(attention).extracting(AttentionItem::id).doesNotContain(verifiedWithFix.id().value());
        assertThat(attention).hasSize(6);
    }

    @Test
    void repo036_aMergeChainOfDepth50IsWalkedWithoutRecursiveSql() {
        Project project = seed.project();
        DefectRepository defects = ctx.bean(DefectRepository.class);
        Defect root = seed.defect(project, 1, "root", LedgerTestContext.START);
        // the chain runs root <- 2 <- 3 ... <- 51: every defect is merged into the one before it
        List<DefectId> chain = IntStream.rangeClosed(2, 51).mapToObj(i -> DefectId.of(project.code(), i)).toList();
        chain.stream().reduce(root.id(), (target, id) -> {
            Defect child = seed.defect(project, DefectId.of(id.value()).number(), "link " + id.value(),
                    LedgerTestContext.START);
            LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> defects.save(
                    child.transitioned(DefectStatus.MERGED, target, LedgerTestContext.START))));
            return id;
        });
        ctx.spy().clear();
        assertThat(ctx.bean(MergeChainRepository.class).mergedInto(root.id()))
                .containsExactlyInAnyOrderElementsOf(chain);
        assertThat(ctx.spy().statements()).noneMatch(s -> s.toUpperCase().contains("RECURSIVE"));
        assertThat(ctx.bean(MergeChainRepository.class).mergedInto(chain.getLast())).isEmpty();
    }
}
