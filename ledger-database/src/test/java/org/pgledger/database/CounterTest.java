package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectCaseLockRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Parallel;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.RequestId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-009 .. REPO-014 and REPO-035: counters hand out unique numbers without gaps and without deadlocks. */
@DatabaseTest
class CounterTest {

    private LedgerTestContext ctx;
    private Seed seed;
    private IdAllocator ids;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
        ids = ctx.bean(IdAllocator.class);
    }

    private static List<Long> oneTo(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(Long::valueOf).toList();
    }

    private static <T> List<Callable<T>> times(int n, Callable<T> work) {
        return Stream.generate(() -> work).limit(n).toList();
    }

    @Test
    void repo009_theNextNumberIsPreviousPlusOneAndAnUnknownProjectUpdatesNothing() {
        Project project = seed.project();
        assertThat(ctx.tx(() -> ids.nextDefectNo(project.code()))).isEqualTo(1);
        assertThat(ctx.tx(() -> ids.nextDefectNo(project.code()))).isEqualTo(2);
        assertThat(ctx.tx(() -> ids.nextTaskNo(project.code()))).isEqualTo(1);
        ctx.tx(() -> {
            ids.raiseDefectNo(project.code(), 100);
            ids.raiseDefectNo(project.code(), 50);
            return null;
        });
        assertThat(ctx.tx(() -> ids.nextDefectNo(project.code()))).isEqualTo(101);
        LookupException unknown = catchThrowableOfType(LookupException.class,
                () -> ctx.tx(() -> ids.nextDefectNo(ProjectCode.of("NOSUCH"))));
        assertThat(unknown.code()).isEqualTo(LookupCode.NOT_FOUND);
        assertThatThrownBy(() -> ids.nextDefectNo(project.code())).isInstanceOf(IllegalStateException.class)
                .as("ids are only issued inside a transaction");
    }

    @Test
    void repo010_640ConcurrentAllocationsGiveEverySingleNumber() {
        Project project = seed.project();
        Callable<List<Long>> twentyAllocations = () -> IntStream.range(0, 20)
                .mapToObj(i -> ctx.tx(() -> ids.nextDefectNo(project.code()))).toList();
        List<Long> all = Parallel.all(times(32, twentyAllocations)).stream().flatMap(List::stream).sorted().toList();
        assertThat(all).isEqualTo(oneTo(640));
    }

    @Test
    void repo011_aRolledBackNumberIsIssuedAgain() {
        Project project = seed.project();
        assertThat(ctx.tx(() -> ids.nextDefectNo(project.code()))).isEqualTo(1);
        assertThatThrownBy(() -> ctx.tx(() -> {
            ids.nextDefectNo(project.code());
            throw new IllegalStateException("fail after allocating");
        })).hasMessageContaining("fail after allocating");
        assertThat(ctx.tx(() -> ids.nextDefectNo(project.code()))).isEqualTo(2);
    }

    private record Issued(String kind, long number) {
    }

    @Test
    void repo012_countersAreIndependentPerProjectAndKind() {
        Project a = seed.project();
        Project b = seed.project();
        List<Callable<Issued>> work = IntStream.range(0, 20).boxed().flatMap(i -> Stream.<Callable<Issued>>of(
                () -> new Issued("A-D", ctx.tx(() -> ids.nextDefectNo(a.code()))),
                () -> new Issued("A-T", ctx.tx(() -> ids.nextTaskNo(a.code()))),
                () -> new Issued("B-D", ctx.tx(() -> ids.nextDefectNo(b.code()))),
                () -> new Issued("B-T", ctx.tx(() -> ids.nextTaskNo(b.code()))))).toList();
        var byKind = Parallel.all(work).stream().collect(Collectors.groupingBy(Issued::kind,
                Collectors.mapping(Issued::number, Collectors.toList())));
        assertThat(byKind.keySet()).containsExactlyInAnyOrder("A-D", "A-T", "B-D", "B-T");
        assertThat(byKind.values()).allSatisfy(numbers -> assertThat(numbers).containsExactlyInAnyOrderElementsOf(
                oneTo(20)));
    }

    private static HistoryEntry historyRow(long id, Defect defect, int n) {
        return new HistoryEntry(id, LedgerTestContext.START, Seed.ALICE, Channel.AUTHOR.login(),
                ActorAssurance.DECLARED, HistoryEntity.DEFECT, defect.id().value(), HistoryOp.CREATE, null, null,
                JsonDocument.EMPTY_OBJECT, null, "writer " + n + " wrote this", null, null, null,
                new RequestId(UUID.randomUUID()), null);
    }

    @Test
    void repo013_surrogateKeysAreUniqueGaplessAndOrderedByInsertion() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "counter target", LedgerTestContext.START);
        long before = JdbcClient.create(ctx.pool()).sql("SELECT issued_no FROM id_counter WHERE name = 'HISTORY'")
                .query(Long.class).single();
        List<Callable<Long>> writers = IntStream.rangeClosed(1, 64).<Callable<Long>>mapToObj(n -> () -> {
            try {
                return ctx.tx(() -> {
                    long id = ids.next(Counter.HISTORY);
                    ctx.bean(HistoryRepository.class).insert(historyRow(id, defect, n));
                    if (n % 8 == 0) {
                        throw new IllegalStateException("roll back writer " + n);
                    }
                    return id;
                });
            } catch (IllegalStateException rolledBack) {
                return -1L;
            }
        }).toList();
        List<Long> committed = Parallel.all(writers).stream().filter(id -> id > 0).sorted().toList();
        assertThat(committed).hasSize(56);
        assertThat(committed).isEqualTo(LongStream.rangeClosed(before + 1, before + 56).boxed().toList());
        List<Long> inOrder = ctx.tx(() -> Stream.generate(() -> ids.next(Counter.DEFECT_FIX)).limit(3).toList());
        assertThat(inOrder.get(2) - inOrder.get(0)).isEqualTo(2);
        assertThat(inOrder).isSorted();
    }

    /** 64 writers take a number of the counter at the same time; every eighth rolls back and gives its number back. */
    @ParameterizedTest
    @EnumSource(Counter.class)
    void repo013_everyCounterIssuesDistinctNumbersWithoutGapsAfterRollbacks(Counter counter) {
        long before = ctx.tx(() -> ids.next(counter));
        List<Callable<Long>> writers = IntStream.rangeClosed(1, 64).<Callable<Long>>mapToObj(n -> () -> {
            try {
                return ctx.tx(() -> {
                    long id = ids.next(counter);
                    if (n % 8 == 0) {
                        throw new IllegalStateException("roll back writer " + n);
                    }
                    return id;
                });
            } catch (IllegalStateException rolledBack) {
                return -1L;
            }
        }).toList();
        List<Long> committed = Parallel.all(writers).stream().filter(id -> id > 0).sorted().toList();
        assertThat(committed).isEqualTo(LongStream.rangeClosed(before + 1, before + 56).boxed().toList());
        assertThat(ctx.tx(() -> ids.next(counter))).isEqualTo(before + 57);
    }

    @Test
    void repo014_mixedWorkloadInTheFixedLockOrderNeverDeadlocks() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "lock order", LedgerTestContext.START);
        DefectCaseLockRepository lock = ctx.bean(DefectCaseLockRepository.class);
        // the order is always: project counter, then the row lock, then the history counter
        List<Callable<Integer>> work = IntStream.range(0, 100).<Callable<Integer>>mapToObj(i -> () -> ctx.tx(() -> {
            Runnable first = switch (i % 4) {
                case 0, 3 -> () -> ids.nextDefectNo(project.code());
                case 1 -> () -> ids.nextTaskNo(project.code());
                default -> () -> lock.lockForCase(defect.id());
            };
            first.run();
            ctx.bean(HistoryRepository.class).insert(historyRow(ids.next(Counter.HISTORY), defect, i));
            return i;
        })).toList();
        assertThat(Parallel.all(work)).hasSize(100);
    }

    @Test
    void repo035_codeRevisionGrowsByOnePerBump() {
        CodeRevisionRepository revision = ctx.bean(CodeRevisionRepository.class);
        long before = revision.current();
        Parallel.all(times(16, () -> ctx.tx(() -> {
            revision.bump();
            return revision.current();
        })));
        assertThat(revision.current()).isEqualTo(before + 16);
    }
}
