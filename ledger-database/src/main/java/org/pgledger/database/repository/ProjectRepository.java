package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.ProjectId;
import org.springframework.data.repository.Repository;

/** Projects: insert and read. The counters are advanced by {@link IdAllocator}. */
public interface ProjectRepository extends Repository<Project, ProjectId>, Insertable<Project> {

    Optional<Project> findById(ProjectId id);

    Optional<Project> findByCode(ProjectCode code);

    boolean existsById(ProjectId id);

    List<Project> findAllByOrderByCode();
}
