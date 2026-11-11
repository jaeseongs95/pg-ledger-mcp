package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.repository.DefectCaseLockRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Parallel;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.rule.DefectStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** REPO-039, REPO-040: recording a case locks the defect row and serialises with a merge of the same row. */
@DatabaseTest
class CaseSequenceTest {

    private LedgerTestContext ctx;
    private Seed seed;
    private DefectCaseLockRepository lock;
    private DefectRepository defects;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
        lock = ctx.bean(DefectCaseLockRepository.class);
        defects = ctx.bean(DefectRepository.class);
        jdbc = JdbcClient.create(ctx.pool());
    }

    private long caseSeq(Defect defect) {
        return jdbc.sql("SELECT case_seq FROM defect WHERE id = :id").param("id", defect.id().value())
                .query(Long.class).single();
    }

    private Instant updatedAt(Defect defect) {
        return defects.findById(defect.id()).orElseThrow().updatedAt();
    }

    private Defect merged(Defect defect, Defect target) {
        return LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> defects.save(
                defects.findById(defect.id()).orElseThrow().transitioned(DefectStatus.MERGED, target.id(),
                        LedgerTestContext.START.plusSeconds(60)))));
    }

    @Test
    void repo039_theLockUpdateWorksOnLiveDefectsAndLeavesVersionAndTimeAlone() {
        Project project = seed.project();
        Defect live = seed.defect(project, 1, "live", LedgerTestContext.START);
        Defect target = seed.defect(project, 2, "target", LedgerTestContext.START);
        Defect gone = merged(seed.defect(project, 3, "merged", LedgerTestContext.START), target);
        assertThat(ctx.tx(() -> lock.lockForCase(live.id()))).isTrue();
        assertThat(ctx.tx(() -> lock.lockForCase(live.id()))).isTrue();
        assertThat(ctx.tx(() -> lock.lockForCase(gone.id()))).as("a merged defect takes no case").isFalse();
        assertThat(caseSeq(live)).isEqualTo(2);
        assertThat(caseSeq(gone)).isZero();
        Defect reread = defects.findById(live.id()).orElseThrow();
        assertThat(reread.version()).isEqualTo(1);
        assertThat(reread.updatedAt()).isEqualTo(live.updatedAt());
        assertThat(reread.caseSeq()).isEqualTo(2);
        // saving a stale copy does not write the counter back
        LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> defects.save(live)));
        assertThat(caseSeq(live)).as("the read-only counter is not overwritten by a save").isEqualTo(2);
    }

    @Test
    void repo040_aMergeWaitsForTheCaseThatLockedTheRow() throws Exception {
        Project project = seed.project();
        Defect source = seed.defect(project, 1, "source", LedgerTestContext.START);
        Defect target = seed.defect(project, 2, "target", LedgerTestContext.START);
        TransactionTemplate tx = ctx.bean(TransactionTemplate.class);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> recorder = Parallel.start(() -> tx.execute(status -> {
            boolean ok = lock.lockForCase(source.id());
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Defect> merger = Parallel.start(() -> ScopedValue.where(org.pgledger.domain.context.ActorScope.CURRENT,
                Seed.ALICE).call(() -> tx.execute(status -> defects.save(source.transitioned(DefectStatus.MERGED,
                        target.id(), LedgerTestContext.START.plusSeconds(1))))));
        assertThatThrownBy(() -> merger.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(recorder.get(10, TimeUnit.SECONDS)).isTrue();
        Defect result = merger.get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(DefectStatus.MERGED);
        assertThat(caseSeq(source)).as("the case's bump survives the merge").isEqualTo(1);
        assertThat(defects.findById(source.id()).orElseThrow().status()).isEqualTo(DefectStatus.MERGED);
        // the other order: after the merge committed, the case is refused
        assertThat(ctx.tx(() -> lock.lockForCase(source.id()))).isFalse();
    }
}
