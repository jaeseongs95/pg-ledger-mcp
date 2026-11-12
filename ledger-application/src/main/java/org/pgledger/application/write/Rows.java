package org.pgledger.application.write;

import java.util.List;
import java.util.Optional;
import org.pgledger.application.gate.Resolution;
import org.pgledger.application.gate.Target;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.database.repository.RowLockRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Loads the rows a write works on. A locked load takes the row lock first (see {@link RowLockRepository}), reads
 * the row, and compares its version with the version the caller expects, so the row it returns is the row the
 * next update replaces.
 */
@Component
public class Rows {

    private final ProjectRepository projects;
    private final DefectRepository defects;
    private final TaskRepository tasks;
    private final ConditionRepository conditions;
    private final RowLockRepository locks;

    public Rows(ProjectRepository projects, DefectRepository defects, TaskRepository tasks,
            ConditionRepository conditions, RowLockRepository locks) {
        this.projects = projects;
        this.defects = defects;
        this.tasks = tasks;
        this.conditions = conditions;
        this.locks = locks;
    }

    public Optional<Defect> findDefect(DefectId id) {
        return defects.findById(id);
    }

    public Optional<Task> findTask(TaskId id) {
        return tasks.findById(id);
    }

    public Optional<Condition> findCondition(ConditionId id) {
        return conditions.findById(id);
    }

    /** The project of a defect as the one target of a write; a defect that is not there is the failure, as a value. */
    public Resolution defectTarget(DefectId id, Scope scope) {
        return defects.findById(id).map(row -> Resolution.of(new Target(row.projectId(), scope)))
                .orElseGet(() -> Resolution.unresolved(notFound(HistoryEntity.DEFECT.token(),id.value())));
    }

    public Resolution taskTarget(TaskId id, Scope scope) {
        return tasks.findById(id).map(row -> Resolution.of(new Target(row.projectId(), scope)))
                .orElseGet(() -> Resolution.unresolved(notFound(HistoryEntity.TASK.token(),id.value())));
    }

    public Resolution conditionTarget(ConditionId id, Scope scope) {
        return conditions.findById(id).map(row -> Resolution.of(new Target(row.projectId(), scope)))
                .orElseGet(() -> Resolution.unresolved(notFound(HistoryEntity.CONDITION.token(),id.value())));
    }

    public Project project(ProjectId id) {
        return projects.findById(id).orElseThrow(() -> notFound("project", id.value()));
    }

    public Defect defect(DefectId id) {
        return defects.findById(id).orElseThrow(() -> notFound(HistoryEntity.DEFECT.token(),id.value()));
    }

    public Task task(TaskId id) {
        return tasks.findById(id).orElseThrow(() -> notFound(HistoryEntity.TASK.token(),id.value()));
    }

    public Condition condition(ConditionId id) {
        return conditions.findById(id).orElseThrow(() -> notFound(HistoryEntity.CONDITION.token(),id.value()));
    }

    /** Locks the defect; with a non-null expected version a different current version is a VERSION_CONFLICT. */
    public Defect lockedDefect(DefectId id, Long expectedVersion) {
        if (!locks.lock(id)) {
            throw notFound(HistoryEntity.DEFECT.token(),id.value());
        }
        Defect row = defects.findById(id).orElseThrow(() -> notFound(HistoryEntity.DEFECT.token(),id.value()));
        requireVersion(HistoryEntity.DEFECT.token(),id.value(), expectedVersion, row.version());
        return row;
    }

    public Task lockedTask(TaskId id, Long expectedVersion) {
        if (!locks.lock(id)) {
            throw notFound(HistoryEntity.TASK.token(),id.value());
        }
        Task row = tasks.findById(id).orElseThrow(() -> notFound(HistoryEntity.TASK.token(),id.value()));
        requireVersion(HistoryEntity.TASK.token(),id.value(), expectedVersion, row.version());
        return row;
    }

    public Condition lockedCondition(ConditionId id, Long expectedVersion) {
        if (!locks.lock(id)) {
            throw notFound(HistoryEntity.CONDITION.token(),id.value());
        }
        Condition row = conditions.findById(id).orElseThrow(() -> notFound(HistoryEntity.CONDITION.token(),id.value()));
        requireVersion(HistoryEntity.CONDITION.token(),id.value(), expectedVersion, row.version());
        return row;
    }

    /**
     * Stores a changed defect. The update is conditional on the version the row carries, which is the version it was
     * read at; a lost race is a VERSION_CONFLICT that names that version and the one in the database now.
     */
    public Defect saveDefect(Defect after) {
        try {
            return defects.save(after);
        } catch (OptimisticLockingFailureException e) {
            throw versionConflict(HistoryEntity.DEFECT.token(), after.id().value(), after.version(), defects
                    .findById(after.id()).map(Defect::version)
                    .orElseThrow(() -> notFound(HistoryEntity.DEFECT.token(), after.id().value())), e);
        }
    }

    /** As {@link #saveDefect} for a task. */
    public Task saveTask(Task after) {
        try {
            return tasks.save(after);
        } catch (OptimisticLockingFailureException e) {
            throw versionConflict(HistoryEntity.TASK.token(), after.id().value(), after.version(), tasks
                    .findById(after.id()).map(Task::version)
                    .orElseThrow(() -> notFound(HistoryEntity.TASK.token(), after.id().value())), e);
        }
    }

    /** As {@link #saveDefect} for a condition. */
    public Condition saveCondition(Condition after) {
        try {
            return conditions.save(after);
        } catch (OptimisticLockingFailureException e) {
            throw versionConflict(HistoryEntity.CONDITION.token(), after.id().value(), after.version(), conditions
                    .findById(after.id()).map(Condition::version)
                    .orElseThrow(() -> notFound(HistoryEntity.CONDITION.token(), after.id().value())), e);
        }
    }

    /** The one place that makes the VERSION_CONFLICT of a save: the arguments are the real versions, in order. */
    private static ConflictException versionConflict(String entity, String id, long expected, long current,
            Throwable cause) {
        return new ConflictException(ConflictCode.VERSION_CONFLICT,
                List.of(entity, id, Long.toString(expected), Long.toString(current)), cause);
    }

    public static LookupException notFound(String what, String id) {
        return new LookupException(LookupCode.NOT_FOUND, List.of(what, id));
    }

    private static void requireVersion(String entity, String id, Long expected, long current) {
        if (expected != null && expected != current) {
            throw new ConflictException(ConflictCode.VERSION_CONFLICT,
                    List.of(entity, id, Long.toString(expected), Long.toString(current)));
        }
    }
}
