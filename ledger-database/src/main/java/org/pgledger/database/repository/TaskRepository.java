package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.data.repository.Repository;

/** Tasks: save (insert when the version is 0, update with the version check otherwise) and read. No delete. */
public interface TaskRepository extends Repository<Task, TaskId> {

    <S extends Task> S save(S task);

    Optional<Task> findById(TaskId id);

    boolean existsById(TaskId id);

    List<Task> findByProjectIdAndState(ProjectId projectId, TaskState state);
}
