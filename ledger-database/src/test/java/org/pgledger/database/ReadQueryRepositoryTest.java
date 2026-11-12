package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.AttentionQueryRepository;
import org.pgledger.database.repository.ConditionQueryRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectQueryRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.EntityLookupRepository;
import org.pgledger.database.repository.HistoryQueryRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.KeysetPage;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.RowLockRepository;
import org.pgledger.database.repository.SearchQueryRepository;
import org.pgledger.database.repository.StatsQueryRepository;
import org.pgledger.database.repository.TaskQueryRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Parallel;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.TaskId;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The query repositories behind the read tools (REPO-025 .. REPO-028 for the new lists): lookups, filters, keyset
 * pages in the OR form, search, history filters, counts, attention and the row lock, on the PostgreSQL of the test PC.
 */
@DatabaseTest
class ReadQueryRepositoryTest {

    private LedgerTestContext ctx;
    private Seed seed;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
    }

    private static final Instant T0 = LedgerTestContext.START;

    private HistoryEntry history(HistoryEntity entity, String entityId, HistoryOp op, String author, Instant at) {
        IdAllocator ids = ctx.bean(IdAllocator.class);
        return ctx.tx(() -> ctx.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY), at,
                org.pgledger.domain.value.AuthorId.of(author), "ledger_author", ActorAssurance.DECLARED, entity,
                entityId, op, null, null, JsonDocument.EMPTY_OBJECT, null, "a reason of enough length", null, null,
                null, new RequestId(UUID.randomUUID()), null)));
    }

    // ------------------------------------------------------------------ lookup

    @Test
    void lookupFindsTheIdsOfEachKindAndIgnoresTheRest() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "d", T0);
        Task task = seed.task(project, 1, "t");
        var condition = seed.condition(project, project.code().value() + "-C-1", "r", "c");
        EntityLookupRepository lookup = ctx.bean(EntityLookupRepository.class);
        List<String> ids = List.of(defect.id().value(), task.id().value(), condition.id().value(), "NOPE-D-0001",
                defect.id().value().toLowerCase());
        assertThat(lookup.defects(ids)).extracting(EntityLookupRepository.Found::id)
                .containsExactly(defect.id().value());
        assertThat(lookup.tasks(ids)).extracting(EntityLookupRepository.Found::state).containsExactly("PLANNED");
        assertThat(lookup.conditions(ids)).extracting(EntityLookupRepository.Found::version).containsExactly(1L);
        assertThat(lookup.defects(ids).getFirst().projectId()).isEqualTo(project.id().value());
    }

    // ------------------------------------------------------------ defect lists

    @Test
    void defectListFiltersByOwnerWithTheFixerAsFallbackAndByAttentionKind() {
        Project project = seed.project();
        Defect owned = seed.defect(project, 1, "owned", T0);
        Defect fixed = seed.defect(project, 2, "fixed", T0.plusSeconds(1));
        Defect bare = seed.defect(project, 3, "bare", T0.plusSeconds(2));
        LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(DefectRepository.class)
                .save(owned.ownedBy(Seed.BOB, T0))));
        IdAllocator ids = ctx.bean(IdAllocator.class);
        ctx.tx(() -> ctx.bean(DefectFixRepository.class).insert(DefectFix.record(ids.next(Counter.DEFECT_FIX),
                fixed.id(), "repo", CommitSha.of("a".repeat(40)), null, null, null, null, Seed.ALICE, T0)));
        DefectQueryRepository queries = ctx.bean(DefectQueryRepository.class);
        MaxRows all = MaxRows.DEFAULT;
        assertThat(queries.page(project.id(), new DefectQueryRepository.Filter(null, null, null, null, null,
                Seed.BOB.value(), null), all, DefectQueryRepository.Order.NEWEST_FIRST, null).rows())
                .extracting(s -> s.id().value()).containsExactly(owned.id().value());
        var byFixer = queries.page(project.id(), new DefectQueryRepository.Filter(null, null, null, null, null,
                Seed.ALICE.value(), null), all, DefectQueryRepository.Order.NEWEST_FIRST, null).rows();
        assertThat(byFixer).extracting(s -> s.id().value()).containsExactly(fixed.id().value());
        assertThat(byFixer.getFirst().owner()).isEqualTo(Seed.ALICE.value());
        assertThat(queries.page(project.id(), new DefectQueryRepository.Filter(null, null, null, null, null, null,
                AttentionKind.OPEN_WITH_FIX), all, DefectQueryRepository.Order.NEWEST_FIRST, null).rows())
                .extracting(s -> s.id().value()).containsExactly(fixed.id().value());
        assertThat(queries.page(project.id(), new DefectQueryRepository.Filter(null, null, null, null, null, null,
                AttentionKind.OPEN_NO_OWNER), all, DefectQueryRepository.Order.NEWEST_FIRST, null).rows())
                .extracting(s -> s.id().value()).containsExactly(bare.id().value(), fixed.id().value());
        assertThatThrownBy(() -> queries.page(project.id(), new DefectQueryRepository.Filter(null, null, null, null,
                null, null, AttentionKind.TASK_UNKNOWN), all, DefectQueryRepository.Order.NEWEST_FIRST, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------- task lists

    @Test
    void taskListPagesNewestFirstThroughEqualTimesAndFilters() {
        Project project = seed.project();
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            tasks.add(seed.task(project, i, "task " + i));
        }
        TaskQueryRepository queries = ctx.bean(TaskQueryRepository.class);
        List<String> seen = new ArrayList<>();
        TaskQueryRepository.Position after = null;
        KeysetPage<TaskQueryRepository.TaskSummary> page;
        do {
            page = queries.page(project.id(), TaskQueryRepository.Filter.NONE, new MaxRows(2), after);
            page.rows().forEach(r -> seen.add(r.id().value()));
            after = page.rows().isEmpty() ? null : new TaskQueryRepository.Position(page.rows().getLast()
                    .createdAt(), page.rows().getLast().id());
        } while (page.truncated());
        assertThat(seen).containsExactlyElementsOf(tasks.reversed().stream().map(t -> t.id().value()).toList());
        assertThat(queries.page(project.id(), new TaskQueryRepository.Filter(TaskState.ACTIVE, null, null),
                MaxRows.DEFAULT, null).rows()).isEmpty();
        assertThat(queries.page(project.id(), new TaskQueryRepository.Filter(null, null,
                AttentionKind.TASK_UNKNOWN), MaxRows.DEFAULT, null).rows()).isEmpty();
        assertThat(queries.page(project.id(), new TaskQueryRepository.Filter(TaskState.PLANNED, null, null),
                MaxRows.DEFAULT, null).rows()).hasSize(7);
    }

    @Test
    void conditionListPagesByIdDescendingWithFilters() {
        Project project = seed.project();
        String prefix = project.code().value() + "-C-";
        for (int i = 1; i <= 5; i++) {
            seed.condition(project, prefix + i, i % 2 == 0 ? "r2" : "r1", "c" + i);
        }
        ConditionQueryRepository queries = ctx.bean(ConditionQueryRepository.class);
        var first = queries.page(project.id(), ConditionQueryRepository.Filter.NONE, new MaxRows(2), null);
        assertThat(first.rows()).extracting(c -> c.id().value()).containsExactly(prefix + 5, prefix + 4);
        assertThat(first.truncated()).isTrue();
        var next = queries.page(project.id(), ConditionQueryRepository.Filter.NONE, new MaxRows(2),
                first.rows().getLast().id());
        assertThat(next.rows()).extracting(c -> c.id().value()).containsExactly(prefix + 3, prefix + 2);
        assertThat(queries.page(project.id(), new ConditionQueryRepository.Filter(ConditionState.UNKNOWN, "r2"),
                MaxRows.DEFAULT, null).rows()).extracting(c -> c.id().value()).containsExactly(prefix + 4,
                prefix + 2);
    }

    // ------------------------------------------------------------------ search

    private SearchQueryRepository.Filter search(Project project, List<String> keywords, KeywordMatch match,
            Set<SearchQueryRepository.Kind> kinds) {
        return new SearchQueryRepository.Filter(project.id(), kinds, keywords, match, Set.of(), null, null);
    }

    private static final Set<SearchQueryRepository.Kind> EVERY_KIND = Set.of(SearchQueryRepository.Kind.values());

    @Test
    void searchFindsKeywordsInEveryKindIgnoringCaseAndTreatingWildcardsAsText() {
        Project project = seed.project();
        seed.defect(project, 1, "Login Bug", T0);
        seed.defect(project, 2, "100%_done", T0.plusSeconds(1));
        seed.task(project, 1, "login task");
        seed.condition(project, project.code().value() + "-C-1", "r", "c1");
        SearchQueryRepository repository = ctx.bean(SearchQueryRepository.class);
        var login = repository.page(search(project, List.of("LOGIN"), KeywordMatch.ANY, EVERY_KIND),
                MaxRows.DEFAULT, null);
        assertThat(login.rows()).extracting(SearchQueryRepository.Hit::kind).containsExactly(
                SearchQueryRepository.Kind.TASK, SearchQueryRepository.Kind.DEFECT);
        var conditionHits = repository.page(search(project, List.of("CONDITION te"), KeywordMatch.ANY, EVERY_KIND),
                MaxRows.DEFAULT, null);
        assertThat(conditionHits.rows()).extracting(SearchQueryRepository.Hit::kind)
                .containsExactly(SearchQueryRepository.Kind.CONDITION);
        assertThat(conditionHits.rows().getFirst().createdAt()).isNull();
        assertThat(repository.page(search(project, List.of("100%"), KeywordMatch.ANY, EVERY_KIND), MaxRows.DEFAULT,
                null).rows()).hasSize(1);
        assertThat(repository.page(search(project, List.of("%"), KeywordMatch.ANY, EVERY_KIND), MaxRows.DEFAULT,
                null).rows()).as("a percent sign is text").hasSize(1);
        assertThat(repository.page(search(project, List.of("'; DROP TABLE project; --"), KeywordMatch.ANY,
                EVERY_KIND), MaxRows.DEFAULT, null).rows()).isEmpty();
    }

    @Test
    void searchAnyAllExcludeKindsAndTimeRange() {
        Project project = seed.project();
        seed.defect(project, 1, "alpha beta", T0);
        seed.defect(project, 2, "alpha", T0.plusSeconds(60));
        seed.defect(project, 3, "beta", T0.plusSeconds(120));
        seed.task(project, 1, "alpha beta task");
        SearchQueryRepository repository = ctx.bean(SearchQueryRepository.class);
        Set<SearchQueryRepository.Kind> defects = Set.of(SearchQueryRepository.Kind.DEFECT);
        assertThat(repository.page(search(project, List.of("alpha", "beta"), KeywordMatch.ANY, defects),
                MaxRows.DEFAULT, null).rows()).hasSize(3);
        assertThat(repository.page(search(project, List.of("alpha", "beta"), KeywordMatch.ALL, defects),
                MaxRows.DEFAULT, null).rows()).extracting(SearchQueryRepository.Hit::title)
                .containsExactly("alpha beta");
        assertThat(repository.page(new SearchQueryRepository.Filter(project.id(), defects, List.of("alpha"),
                KeywordMatch.ANY, Set.of("OPEN"), null, null), MaxRows.DEFAULT, null).rows()).isEmpty();
        assertThat(repository.page(new SearchQueryRepository.Filter(project.id(), EVERY_KIND, List.of(),
                KeywordMatch.ANY, Set.of(), T0.plusSeconds(60), T0.plusSeconds(120)), MaxRows.DEFAULT, null).rows())
                .extracting(SearchQueryRepository.Hit::title).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void searchPagesWithoutDuplicatesThroughEqualTimes() {
        Project project = seed.project();
        for (int i = 1; i <= 5; i++) {
            seed.defect(project, i, "same time " + i, T0);
        }
        for (int i = 1; i <= 3; i++) {
            seed.task(project, i, "same time task " + i);
        }
        SearchQueryRepository repository = ctx.bean(SearchQueryRepository.class);
        List<String> seen = new ArrayList<>();
        SearchQueryRepository.Position after = null;
        KeysetPage<SearchQueryRepository.Hit> page;
        do {
            page = repository.page(search(project, List.of("same time"), KeywordMatch.ANY, EVERY_KIND),
                    new MaxRows(3), after);
            page.rows().forEach(h -> seen.add(h.kind() + h.id()));
            var last = page.rows().getLast();
            after = new SearchQueryRepository.Position(last.createdAt(), last.kind(), last.id());
        } while (page.truncated());
        assertThat(seen).hasSize(8).doesNotHaveDuplicates();
    }

    // ----------------------------------------------------------------- history

    @Test
    void historyFiltersAreCombinedAndPagedAscending() {
        Project project = seed.project();
        Project other = seed.project();
        Defect defect = seed.defect(project, 1, "d", T0);
        Defect elsewhere = seed.defect(other, 1, "e", T0);
        var condition = seed.condition(project, project.code().value() + "-C-1", "r", "c");
        IdAllocator ids = ctx.bean(IdAllocator.class);
        ctx.tx(() -> ctx.bean(DefectFixRepository.class).insert(DefectFix.record(ids.next(Counter.DEFECT_FIX),
                defect.id(), "repo", CommitSha.of("b".repeat(40)), null, null, null, null, Seed.ALICE, T0)));
        ctx.tx(() -> ctx.bean(LinkRepository.class).add(ConditionLink.toDefect(condition.id(), defect.id(),
                Seed.ALICE)));
        HistoryEntry h1 = history(HistoryEntity.DEFECT, defect.id().value(), HistoryOp.CREATE, "claude:alice", T0);
        HistoryEntry h2 = history(HistoryEntity.DEFECT_FIX, defect.id().value() + "@" + "b".repeat(40),
                HistoryOp.CREATE, "claude:alice", T0.plusSeconds(10));
        HistoryEntry h3 = history(HistoryEntity.CONDITION_LINK, condition.id().value() + "|" + defect.id().value(),
                HistoryOp.LINK, "codex:bob", T0.plusSeconds(20));
        HistoryEntry h4 = history(HistoryEntity.DEFECT, elsewhere.id().value(), HistoryOp.CREATE, "claude:alice",
                T0.plusSeconds(30));
        HistoryEntry h5 = history(HistoryEntity.DEFECT, defect.id().value(), HistoryOp.EDIT, "codex:bob",
                T0.plusSeconds(40));
        HistoryQueryRepository queries = ctx.bean(HistoryQueryRepository.class);
        HistoryQueryRepository.Filter none = HistoryQueryRepository.Filter.NONE;
        assertThat(queries.page(withProject(none, project), 0, MaxRows.DEFAULT).rows())
                .containsExactly(h1.id(), h2.id(), h3.id(), h5.id());
        assertThat(queries.page(withProject(none, other), 0, MaxRows.DEFAULT).rows()).containsExactly(h4.id());
        var byIds = new HistoryQueryRepository.Filter(List.of(h5.id(), h1.id()), null, null, null, null, null, null,
                null, null, null);
        assertThat(queries.page(byIds, 0, MaxRows.DEFAULT).rows()).containsExactly(h1.id(), h5.id());
        var range = new HistoryQueryRepository.Filter(List.of(), h2.id(), h4.id(), null, null, null, null, null, null,
                null);
        assertThat(queries.page(range, 0, MaxRows.DEFAULT).rows()).containsExactly(h2.id(), h3.id(), h4.id());
        var edits = new HistoryQueryRepository.Filter(List.of(), null, null, null, null, null, HistoryOp.EDIT,
                "codex:bob", null, null);
        assertThat(queries.page(edits, 0, MaxRows.DEFAULT).rows()).containsExactly(h5.id());
        var timed = new HistoryQueryRepository.Filter(List.of(), null, null, null, null, null, null, null,
                T0.plusSeconds(10), T0.plusSeconds(30));
        assertThat(queries.page(timed, 0, MaxRows.DEFAULT).rows()).containsExactly(h2.id(), h3.id(), h4.id());
        var entity = new HistoryQueryRepository.Filter(List.of(), null, null, HistoryEntity.DEFECT,
                defect.id().value(), null, null, null, null, null);
        var first = queries.page(entity, 0, new MaxRows(1));
        assertThat(first.rows()).containsExactly(h1.id());
        assertThat(first.truncated()).isTrue();
        assertThat(queries.page(entity, h1.id(), new MaxRows(1)).rows()).containsExactly(h5.id());
        assertThat(queries.newestOfEntity(HistoryEntity.DEFECT, defect.id().value(), true, 1))
                .containsExactly(h5.id());
    }

    private static HistoryQueryRepository.Filter withProject(HistoryQueryRepository.Filter base, Project project) {
        return new HistoryQueryRepository.Filter(base.ids(), base.idFrom(), base.idTo(), base.entity(),
                base.entityId(), project.id(), base.op(), base.author(), base.atFrom(), base.atTo());
    }

    // ---------------------------------------------------------- counts, attention

    @Test
    void countsPerStateAndPerUtcDayForOneProjectAndForAll() {
        Project project = seed.project();
        seed.defect(project, 1, "a", Instant.parse("2026-11-05T14:59:59Z"));
        seed.defect(project, 2, "b", Instant.parse("2026-11-05T15:00:00Z"));
        seed.defect(project, 3, "c", Instant.parse("2026-11-06T00:00:00Z"));
        seed.task(project, 1, "t");
        seed.condition(project, project.code().value() + "-C-1", "r", "c");
        StatsQueryRepository stats = ctx.bean(StatsQueryRepository.class);
        assertThat(stats.defectsByStatus(project.id())).containsExactly(Map.entry("OPEN", 3L));
        assertThat(stats.defectsBySeverity(project.id())).containsExactly(Map.entry("P2", 3L));
        assertThat(stats.tasksByState(project.id())).containsExactly(Map.entry("PLANNED", 1L));
        assertThat(stats.conditionsByState(project.id())).containsExactly(Map.entry("UNKNOWN", 1L));
        assertThat(stats.defectsCreatedPerDay(project.id())).containsExactly(
                Map.entry(LocalDate.of(2026, 11, 5), 2L), Map.entry(LocalDate.of(2026, 11, 6), 1L));
        assertThat(stats.defectsByStatus(null).get("OPEN")).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void attentionPagesByKindAndIdWithFilters() {
        Project project = seed.project();
        seed.defect(project, 1, "no owner one", T0);
        seed.defect(project, 2, "no owner two", T0);
        seed.task(project, 1, "planned");
        AttentionQueryRepository attention = ctx.bean(AttentionQueryRepository.class);
        var page = attention.page(project.id(), null, new MaxRows(1), null);
        assertThat(page.rows()).extracting(AttentionQueryRepository.Item::id).containsExactly(
                project.code().value() + "-D-0001");
        assertThat(page.truncated()).isTrue();
        var next = attention.page(project.id(), null, new MaxRows(5), new AttentionQueryRepository.Position(
                page.rows().getFirst().kind(), page.rows().getFirst().id()));
        assertThat(next.rows()).extracting(AttentionQueryRepository.Item::id).containsExactly(
                project.code().value() + "-D-0002");
        assertThat(attention.page(project.id(), AttentionKind.OPEN_WITH_FIX, MaxRows.DEFAULT, null).rows()).isEmpty();
    }

    @Test
    void linkListsFindTheOtherSide() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "d", T0);
        Task task = seed.task(project, 1, "t");
        var condition = seed.condition(project, project.code().value() + "-C-1", "r", "c");
        LinkRepository links = ctx.bean(LinkRepository.class);
        ctx.tx(() -> links.add(new org.pgledger.domain.model.DefectTaskLink(defect.id(), task.id(), Seed.ALICE)));
        ctx.tx(() -> links.add(ConditionLink.toDefect(condition.id(), defect.id(), Seed.ALICE)));
        ctx.tx(() -> links.add(ConditionLink.toTask(condition.id(), task.id(), Seed.ALICE)));
        assertThat(links.defectsOfTask(task.id())).containsExactly(defect.id().value());
        assertThat(links.conditionsOfDefect(defect.id())).containsExactly(condition.id().value());
        assertThat(links.conditionsOfTask(task.id())).containsExactly(condition.id().value());
    }

    // ---------------------------------------------------------------- row lock

    @Test
    void theRowLockExistsOnlyForExistingRowsAndMakesAWriterWaitUntilCommit() throws Exception {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "d", T0);
        Task task = seed.task(project, 1, "t");
        var condition = seed.condition(project, project.code().value() + "-C-1", "r", "c");
        RowLockRepository locks = ctx.bean(RowLockRepository.class);
        assertThat(ctx.tx(() -> locks.lock(defect.id()))).isTrue();
        assertThat(ctx.tx(() -> locks.lock(task.id()))).isTrue();
        assertThat(ctx.tx(() -> locks.lock(condition.id()))).isTrue();
        assertThat(ctx.tx(() -> locks.lock(DefectId.of(project.code().value() + "-D-9999")))).isFalse();
        assertThat(ctx.tx(() -> locks.lock(TaskId.of("NOBODY-T-1")))).isFalse();
        assertThat(ctx.tx(() -> locks.lock(ConditionId.of("NOBODY-C-1")))).isFalse();
        TransactionTemplate tx = ctx.bean(TransactionTemplate.class);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> holder = Parallel.start(() -> tx.execute(status -> {
            boolean ok = locks.lock(defect.id());
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> waiter = Parallel.start(() -> tx.execute(status -> locks.lock(defect.id())));
        assertThatThrownBy(() -> waiter.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(ctx.bean(DefectRepository.class).findById(defect.id()).orElseThrow().version()).isEqualTo(1);
    }
}
