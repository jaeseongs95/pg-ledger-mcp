package org.pgledger.database.support;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.context.ApplicationContext;
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

    /** Where the repositories come from. */
    private interface Beans {
        <T> T get(Class<T> type);
    }

    /** How a write is run: in a transaction of its own (committed), or in the one that the test already has. */
    private interface Transactions {
        <T> T run(Supplier<T> work);
    }

    private final Beans beans;
    private final Transactions transactions;

    /** A seed that commits every row in a transaction of its own (the shared database of {@link LedgerTestContext}). */
    public Seed(LedgerTestContext ctx) {
        this.beans = new Beans() {
            @Override
            public <T> T get(Class<T> type) {
                return ctx.bean(type);
            }
        };
        this.transactions = new Transactions() {
            @Override
            public <T> T run(Supplier<T> work) {
                return ctx.tx(work);
            }
        };
    }

    /** A seed for a slice test: the repositories are beans of the context, and the rows go into the transaction of the test. */
    public Seed(ApplicationContext context) {
        this.beans = context::getBean;
        this.transactions = new Transactions() {
            @Override
            public <T> T run(Supplier<T> work) {
                return work.get();
            }
        };
    }

    /** A project with a code no other test uses; counters at 0. */
    public Project project() {
        int n = SEQUENCE.incrementAndGet();
        Project project = Project.open(ProjectId.of("p" + n), ProjectCode.of("P" + n), "Project " + n);
        return LedgerTestContext.as(AuthorId.OWNER, () -> transactions.run(() -> beans.get(ProjectRepository.class)
                .insert(project)));
    }

    public Defect defect(Project project, long number, String title, Instant createdAt) {
        DefectFields fields = DefectFields.of(Patch.set(project.id().value()), Patch.set(title), Patch.set("ARC"),
                Patch.set("P2"), Patch.set("behavior of " + title), Patch.set("cause of " + title), Patch.absent());
        Defect defect = Defect.open(DefectId.of(project.code(), number), fields, createdAt);
        return LedgerTestContext.as(ALICE, () -> transactions.run(() -> beans.get(DefectRepository.class).save(defect)));
    }

    public Task task(Project project, long number, String title) {
        return task(project, TaskId.issue(project.code(), number), title);
    }

    public Task task(Project project, TaskId id, String title) {
        TaskFields fields = TaskFields.of(Patch.set(project.id().value()), Patch.set(title), Patch.set("purpose"),
                Patch.set("success"), Patch.absent(), Patch.absent(), Patch.absent());
        Task task = Task.plan(id, fields, LedgerTestContext.START);
        return LedgerTestContext.as(ALICE, () -> transactions.run(() -> beans.get(TaskRepository.class).save(task)));
    }

    public Condition condition(Project project, String id, String release, String code) {
        ConditionFields fields = ConditionFields.of(Patch.set(project.id().value()), Patch.set(release),
                Patch.absent(), Patch.set(code), Patch.set("condition text"));
        Condition condition = Condition.open(ConditionId.of(id), fields);
        return LedgerTestContext.as(ALICE, () -> transactions.run(() -> beans.get(ConditionRepository.class).save(condition)));
    }
}
