package org.pgledger.database.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.data.repository.Repository;

/**
 * Tasks: save (insert when the version is 0, update with the version check otherwise) and read. No delete.
 *
 * <p>The id of a task is compared exactly. A DBMS whose collation ignores case answers {@code WHERE id = 'ags:a07-a'} with the
 * row {@code AGS:A07-A}, so {@link #findById} and {@link #existsById} compare the id of the row they fetched with the id they
 * were asked for, in Java, and answer nothing for another case. {@link #findOneById} is the fetch itself: use it for nothing
 * else. Ids that differ only in case cannot be created ({@link #findByIdKey} finds the id a new one would equal, and the key
 * column is UNIQUE), so there is never a sibling row to choose between.
 */
public interface TaskRepository extends Repository<Task, TaskId> {

    <S extends Task> S save(S task);

    /** The fetch of {@link #findById}: it may return the row of another case on a DBMS that ignores case. */
    Optional<Task> findOneById(TaskId id);

    default Optional<Task> findById(TaskId id) {
        return findOneById(id).filter(row -> Objects.equals(row.id(), id));
    }

    default boolean existsById(TaskId id) {
        return findById(id).isPresent();
    }

    /** The task whose id is {@code key} in upper case ({@link TaskId#key()}), that is the task an id of any case would equal. */
    Optional<Task> findByIdKey(String key);

    List<Task> findByProjectIdAndState(ProjectId projectId, TaskState state);
}
