package org.pgledger.database.support;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.ConditionFields;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFields;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;

/** Creates unique projects and rows for a test, so tests of one database do not collide. */
public final class Seed {

    public static final AuthorId ALICE = AuthorId.of("claude:alice");
    public static final AuthorId BOB = AuthorId.of("codex:bob");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final LedgerTestContext ctx;

    public Seed(LedgerTestContext ctx) {
        this.ctx = ctx;
    }

    /** A project with a code no other test uses; counters at 0. */
    public Project project() {
        int n = SEQUENCE.incrementAndGet();
        Project project = Project.open(ProjectId.of("p" + n), ProjectCode.of("P" + n), "Project " + n);
        return LedgerTestContext.as(AuthorId.OWNER, () -> ctx.tx(() -> ctx.bean(ProjectRepository.class)
                .insert(project)));
    }

    public Defect defect(Project project, long number, String title, Instant createdAt) {
        DefectFields fields = DefectFields.of(Patch.set(project.id().value()), Patch.set(title), Patch.set("ARC"),
                Patch.set("P2"), Patch.set("behavior of " + title), Patch.set("cause of " + title), Patch.absent());
        Defect defect = Defect.open(DefectId.of(project.code(), number), fields, createdAt);
        return LedgerTestContext.as(ALICE, () -> ctx.tx(() -> ctx.bean(DefectRepository.class).save(defect)));
    }

    public Task task(Project project, long number, String title) {
        return task(project, TaskId.issue(project.code(), number), title);
    }

    public Task task(Project project, TaskId id, String title) {
        TaskFields fields = TaskFields.of(Patch.set(project.id().value()), Patch.set(title), Patch.set("purpose"),
                Patch.set("success"), Patch.absent(), Patch.absent(), Patch.absent());
        Task task = Task.plan(id, fields, LedgerTestContext.START);
        return LedgerTestContext.as(ALICE, () -> ctx.tx(() -> ctx.bean(TaskRepository.class).save(task)));
    }

    public Condition condition(Project project, String id, String release, String code) {
        ConditionFields fields = ConditionFields.of(Patch.set(project.id().value()), Patch.set(release),
                Patch.absent(), Patch.set(code), Patch.set("condition text"));
        Condition condition = Condition.open(ConditionId.of(id), fields);
        return LedgerTestContext.as(ALICE, () -> ctx.tx(() -> ctx.bean(ConditionRepository.class).save(condition)));
    }
}
