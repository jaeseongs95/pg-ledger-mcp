package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.DefectRelation;
import org.pgledger.domain.model.DefectTaskLink;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.TaskId;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-021, REPO-022: normalised link keys, unique keys, checks and foreign keys. */
@DatabaseTest
class ConstraintTest {

    private LedgerTestContext ctx;
    private Seed seed;
    private LinkRepository links;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
        links = ctx.bean(LinkRepository.class);
        jdbc = JdbcClient.create(ctx.pool());
    }

    private static final AuthorId BY = Seed.ALICE;

    @Test
    void repo021_aTaskAndADefectWithTheSameTextAreDifferentTargetsOfOneCondition() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "linked", LedgerTestContext.START);
        Task sameText = seed.task(project, TaskId.of(defect.id().value()), "same text");
        Condition c = seed.condition(project, project.code().value() + "-C", "1.0", "C");
        ConditionLink toTask = ConditionLink.toTask(c.id(), sameText.id(), BY);
        ConditionLink toDefect = ConditionLink.toDefect(c.id(), defect.id(), BY);
        assertThat(ctx.tx(() -> links.add(toTask))).isTrue();
        assertThat(ctx.tx(() -> links.add(toTask))).as("a duplicate adds nothing").isFalse();
        assertThat(ctx.tx(() -> links.add(toDefect))).isTrue();
        assertThat(ctx.tx(() -> links.add(toDefect))).isFalse();
        assertThat(links.linksOf(c.id())).containsExactlyInAnyOrder(toTask, toDefect);
        assertThat(ctx.tx(() -> links.remove(toTask))).isTrue();
        assertThat(ctx.tx(() -> links.remove(toTask))).isFalse();
    }

    @Test
    void repo021_aPipeInAnIdNeverMakesTwoDifferentPairsCollide() {
        Project project = seed.project();
        Task pipeTask = seed.task(project, TaskId.of("A|T"), "pipe task");
        Task plainTask = seed.task(project, TaskId.of("T"), "plain task");
        Condition first = seed.condition(project, "OLD-PIPE-C", "1.0", "P1");
        Condition second = seed.condition(project, "OLD-PIPE-C|A", "1.0", "P2");
        assertThat(ctx.tx(() -> links.add(ConditionLink.toTask(first.id(), pipeTask.id(), BY)))).isTrue();
        assertThat(ctx.tx(() -> links.add(ConditionLink.toTask(second.id(), plainTask.id(), BY)))).isTrue();
    }

    @Test
    void repo021_theLinkKeyIsNeverNullAndAnSqlDuplicateIsRefusedByIt() {
        Project project = seed.project();
        Task task = seed.task(project, 1, "keyed");
        Condition c = seed.condition(project, project.code().value() + "-K", "1.0", "K");
        ConditionLink link = ConditionLink.toTask(c.id(), task.id(), BY);
        ctx.tx(() -> links.add(link));
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO condition_link (link_key, condition_id, task_id, created_by) "
                + "VALUES (:key, :c, :t, 'user:owner')").param("key", link.linkKey()).param("c", c.id().value())
                .param("t", task.id().value()).update()).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO condition_link (link_key, condition_id, created_by) "
                + "VALUES ('k', :c, 'user:owner')").param("c", c.id().value()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repo022_aFixIsUniquePerDefectRepoAndCommit() {
        Project project = seed.project();
        Defect d1 = seed.defect(project, 1, "one", LedgerTestContext.START);
        IdAllocator ids = ctx.bean(IdAllocator.class);
        DefectFixRepository fixes = ctx.bean(DefectFixRepository.class);
        CommitSha sha = CommitSha.of("c".repeat(40));
        ctx.tx(() -> fixes.insert(DefectFix.record(ids.next(Counter.DEFECT_FIX), d1.id(), "repo", sha, null, null,
                null, null, BY, LedgerTestContext.START)));
        assertThatThrownBy(() -> ctx.tx(() -> fixes.insert(DefectFix.record(ids.next(Counter.DEFECT_FIX), d1.id(),
                "repo", sha, null, null, null, null, BY, LedgerTestContext.START))))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(ctx.tx(() -> fixes.insert(DefectFix.record(ids.next(Counter.DEFECT_FIX), d1.id(), "other-repo",
                sha, null, "ref", CiConclusion.SUCCESS, null, BY, LedgerTestContext.START)))).isNotNull();
    }

    @Test
    void repo022_aConditionIsUniquePerProjectReleaseAndCode() {
        Project project = seed.project();
        seed.condition(project, project.code().value() + "-C1", "1.0", "X");
        assertThatThrownBy(() -> seed.condition(project, project.code().value() + "-C2", "1.0", "X"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(ctx.bean(ConditionRepository.class).findByProjectIdAndReleaseAndCode(project.id(), "1.0", "X"))
                .isPresent();
    }

    @Test
    void repo022_relationsAreCanonicalAndUniquePerKind() {
        Project project = seed.project();
        Defect d1 = seed.defect(project, 1, "one", LedgerTestContext.START);
        Defect d2 = seed.defect(project, 2, "two", LedgerTestContext.START);
        Task task = seed.task(project, 1, "task");
        assertThat(ctx.tx(() -> links.add(new DefectRelation(d2.id(), d1.id(), CodeValue.of("same_site"), BY))))
                .isTrue();
        assertThat(ctx.tx(() -> links.add(new DefectRelation(d1.id(), d2.id(), CodeValue.of("same_site"), BY))))
                .as("the pair is the same relation in either order").isFalse();
        assertThat(ctx.tx(() -> links.add(new DefectRelation(d1.id(), d2.id(), CodeValue.of("blocks"), BY))))
                .as("another kind is another relation").isTrue();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO defect_relation (a, b, kind, created_by) VALUES (:a, :b, "
                + "'x', 'user:owner')").param("a", d2.id().value()).param("b", d1.id().value()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(ctx.tx(() -> links.add(new DefectTaskLink(d1.id(), task.id(), BY)))).isTrue();
        assertThat(ctx.tx(() -> links.add(new DefectTaskLink(d1.id(), task.id(), BY)))).isFalse();
        assertThat(links.tasksOf(d1.id())).hasSize(1);
    }

    /** Statements that break a foreign key or a check; {@code :defect} is a real defect of the test. */
    static Stream<String> orphansAndBrokenChecks() {
        return Stream.of(
                "INSERT INTO task (id, project_id, title, purpose, success_condition, state, version, created_at, "
                        + "updated_at, created_by) VALUES ('ORPHAN-T', 'nope', 't', 'p', 's', 'PLANNED', 1, "
                        + "TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 10:00:00', 'user:owner')",
                "INSERT INTO defect (id, project_id, title, category, severity, status, behavior, root_cause, "
                        + "version, created_at, updated_at, created_by) VALUES ('ORPHAN-D-0001', 'nope', 't', 'A', "
                        + "'P1', 'OPEN', 'b', 'c', 1, TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 "
                        + "10:00:00', 'user:owner')",
                "UPDATE defect SET status = 'MERGED', merged_into = 'NOSUCH-D-0001' WHERE id = :defect",
                "INSERT INTO defect_case (id, defect_id, observation, recorded_by, recorded_at) VALUES (990001, "
                        + "'NOSUCH-D-0001', 'o', 'claude:a', TIMESTAMP '2026-11-05 10:00:00')",
                "INSERT INTO defect_task (defect_id, task_id, created_by) VALUES ('NOSUCH-D-0001', 'X', 'user:owner')",
                "INSERT INTO defect_relation (a, b, kind, created_by) VALUES ('AAA-D-0001', 'AAA-D-0002', 'k', "
                        + "'user:owner')",
                "INSERT INTO release_condition (id, project_id, release_name, code, condition_text, state, version, "
                        + "created_by) VALUES ('ORPHAN-C', 'nope', '1', 'c', 't', 'UNKNOWN', 1, 'user:owner')",
                "INSERT INTO condition_link (link_key, condition_id, task_id, created_by) VALUES ('k', 'NOSUCH', "
                        + "'X', 'user:owner')",
                "INSERT INTO session_grant (id, author_id, project_id, grant_scope, granted_by, granted_at, active_key) "
                        + "VALUES (990002, 'claude:a', 'nope', 'DEFECT', 'user:owner', TIMESTAMP '2026-11-05 "
                        + "10:00:00', 'orphan-key')",
                "INSERT INTO history (id, occurred_at, author_id, login, actor_assurance, entity, entity_id, op, changes, "
                        + "reason, corrects_id, request_id) VALUES (990003, TIMESTAMP '2026-11-05 10:00:00', "
                        + "'claude:a', 'ledger_author', 'DECLARED', 'DEFECT', 'X', 'CORRECT', '{}', "
                        + "'a reason of length', 424242, '11111111-1111-1111-1111-111111111111')",
                "UPDATE defect SET status = 'MERGED' WHERE id = :defect",
                "UPDATE defect SET merged_into = id WHERE id = :defect");
    }

    @ParameterizedTest
    @MethodSource("orphansAndBrokenChecks")
    void repo022_aChildWithoutItsParentOrAnImpossibleRowIsRefusedWithClass23(String sql) {
        Defect defect = seed.defect(seed.project(), 1, "host", LedgerTestContext.START);
        Throwable thrown = catchThrowable(() -> jdbc.sql(sql).param("defect", defect.id().value()).update());
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(thrown)).getSQLState())
                .startsWith("23");
    }
}
