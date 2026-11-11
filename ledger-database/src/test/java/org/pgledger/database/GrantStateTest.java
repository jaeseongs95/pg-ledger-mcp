package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.Dbms;
import org.pgledger.database.support.DbmsAware;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Parallel;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** REPO-015, REPO-016, REPO-020 and the grant part of SVC-TZ-03. */
@DatabaseTest
class GrantStateTest implements DbmsAware {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant GRANTED = Instant.parse("2026-11-01T00:00:00Z");

    @Parameter
    Dbms dbms;

    private LedgerTestContext ctx;
    private Seed seed;
    private GrantStateRepository grants;
    private SessionGrantRepository store;
    private IdAllocator ids;

    @Override
    public Dbms dbms() {
        return dbms;
    }

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
        grants = ctx.bean(GrantStateRepository.class);
        store = ctx.bean(SessionGrantRepository.class);
        ids = ctx.bean(IdAllocator.class);
    }

    private SessionGrant grant(Project project, AuthorId author, Scope scope, Instant expiresAt) {
        return ctx.tx(() -> store.insert(SessionGrant.issue(ids.next(Counter.SESSION_GRANT), author, project.id(),
                scope, AuthorId.OWNER, GRANTED, expiresAt, null)));
    }

    /** One grant, one question: does the check admit the asker. */
    private record Admission(String name, Instant expiresAt, Scope asked, AuthorId asker,
            boolean otherProject, boolean admitted) {
        /** The last-use stamp the grant must carry afterwards: the clock value when admitted, otherwise none. */
        Instant stamp() {
            return admitted ? NOW : null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Admission> admissions() {
        return Stream.of(
                new Admission("live", NOW.plusSeconds(3600), Scope.DEFECT, Seed.ALICE, false, true),
                new Admission("never expires", null, Scope.DEFECT, Seed.ALICE, false, true),
                new Admission("expired", NOW.minusSeconds(1), Scope.DEFECT, Seed.ALICE, false, false),
                new Admission("expires exactly now", NOW, Scope.DEFECT, Seed.ALICE, false, false),
                new Admission("other scope", null, Scope.VERIFY, Seed.ALICE, false, false),
                new Admission("other project", null, Scope.DEFECT, Seed.ALICE, true, false),
                new Admission("other author", null, Scope.DEFECT, Seed.BOB, false, false),
                new Admission("author ids are case sensitive", null, Scope.DEFECT, AuthorId.of("claude:ALICE"),
                        false, false));
    }

    @ParameterizedTest
    @MethodSource("admissions")
    void repo015_theConditionalUpdateAdmitsOnlyLiveGrants(Admission admission) {
        Project project = seed.project();
        Project other = seed.project();
        SessionGrant granted = grant(project, Seed.ALICE, Scope.DEFECT, admission.expiresAt());
        Project asked = admission.otherProject() ? other : project;
        assertThat(ctx.tx(() -> grants.markUsed(admission.asker(), asked.id(), admission.asked(), NOW)))
                .isEqualTo(admission.admitted());
        assertThat(store.findById(granted.id()).orElseThrow().lastUsedAt())
                .isEqualTo(admission.stamp());
    }

    @Test
    void repo015_liveGrantsOfAnAuthorAreListedByProjectAndScope() {
        Project project = seed.project();
        grant(project, Seed.ALICE, Scope.TASK, null);
        grant(project, Seed.ALICE, Scope.DEFECT, NOW.plusSeconds(60));
        grant(project, Seed.ALICE, Scope.FIX, NOW);
        assertThat(grants.findLive(Seed.ALICE, NOW)).filteredOn(g -> g.projectId().equals(project.id()))
                .extracting(SessionGrant::scope).containsExactly(Scope.DEFECT, Scope.TASK);
    }

    /** A grant that expires at {@code expiresAt}, asked at {@code now}. */
    private record Expiry(Instant expiresAt, Instant now, boolean admitted) {
    }

    static Stream<Expiry> expiries() {
        // 2026-11-05T15:00:00Z is midnight in Seoul; only the UTC instants count
        return Stream.of(
                new Expiry(Instant.parse("2026-11-05T15:00:00Z"), Instant.parse("2026-11-05T14:59:59Z"), true),
                new Expiry(Instant.parse("2026-11-05T15:00:00Z"), Instant.parse("2026-11-05T15:00:00Z"), false),
                new Expiry(Instant.parse("2026-11-06T00:00:00Z"), Instant.parse("2026-11-05T23:59:59Z"), true),
                new Expiry(Instant.parse("2026-11-06T00:00:00Z"), Instant.parse("2026-11-06T00:00:00Z"), false));
    }

    @ParameterizedTest
    @MethodSource("expiries")
    void svcTz03_expiryDependsOnUtcInstantsOnly(Expiry expiry) {
        Project project = seed.project();
        grant(project, Seed.ALICE, Scope.DEFECT, expiry.expiresAt());
        assertThat(ctx.tx(() -> grants.markUsed(Seed.ALICE, project.id(), Scope.DEFECT, expiry.now())))
                .isEqualTo(expiry.admitted());
    }

    @Test
    void repo016_aRevocationWaitsForTheWriterThatUsedTheGrant() throws Exception {
        Project project = seed.project();
        SessionGrant grant = grant(project, Seed.ALICE, Scope.DEFECT, null);
        TransactionTemplate tx = ctx.bean(TransactionTemplate.class);
        CountDownLatch used = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> writer = Parallel.start(() -> tx.execute(status -> {
            boolean ok = grants.markUsed(Seed.ALICE, project.id(), Scope.DEFECT, NOW);
            used.countDown();
            await(release);
            return ok;
        }));
        assertThat(used.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> revoker = Parallel.start(() -> tx.execute(status -> grants.revoke(grant.id(), NOW)));
        assertThatThrownBy(() -> revoker.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(writer.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(revoker.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(ctx.tx(() -> grants.markUsed(Seed.ALICE, project.id(), Scope.DEFECT, NOW))).isFalse();
    }

    @Test
    void repo016_aCommittedRevocationMakesTheCheckFail() {
        Project project = seed.project();
        SessionGrant grant = grant(project, Seed.ALICE, Scope.DEFECT, null);
        assertThat(ctx.tx(() -> grants.revoke(grant.id(), NOW))).isTrue();
        assertThat(ctx.tx(() -> grants.revoke(grant.id(), NOW))).as("already revoked").isFalse();
        assertThat(ctx.tx(() -> grants.markUsed(Seed.ALICE, project.id(), Scope.DEFECT, NOW))).isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void repo020_aLiveGrantExistsOncePerKeyAndRevokedOnesPileUp() {
        Project project = seed.project();
        AuthorId me = AuthorId.of("claude:own" + project.id().value());
        SessionGrant first = grant(project, me, Scope.DEFECT, null);
        assertThatThrownBy(() -> grant(project, me, Scope.DEFECT, null)).isInstanceOf(DuplicateKeyException.class);
        assertThat(ctx.tx(() -> grants.revoke(first.id(), NOW))).isTrue();
        assertThat(store.findById(first.id()).orElseThrow().activeKey()).isEqualTo("revoked:" + first.id());
        SessionGrant second = grant(project, me, Scope.DEFECT, null);
        assertThat(ctx.tx(() -> grants.revoke(second.id(), NOW))).isTrue();
        grant(project, me, Scope.DEFECT, null);
        assertThat(JdbcClient.create(ctx.pool()).sql("SELECT COUNT(*) FROM session_grant WHERE project_id = :p AND "
                + "revoked_at IS NOT NULL").param("p", project.id().value()).query(Integer.class).single())
                .isEqualTo(2);
        grant(project, me, Scope.TASK, null);
        assertThat(ctx.tx(() -> grants.revokeLive(me, project.id(), Scope.TASK, NOW))).isEqualTo(1);
        assertThat(ctx.tx(() -> grants.revokeLive(me, project.id(), null, NOW))).isEqualTo(1);
        assertThat(ctx.tx(() -> grants.revokeLive(me, null, null, NOW))).isZero();
    }
}
