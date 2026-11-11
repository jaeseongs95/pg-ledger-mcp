package org.pgledger.database.repository;

import java.util.Optional;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.ProjectId;
import org.springframework.data.repository.Repository;

/** Release conditions: save (insert when the version is 0, update with the version check otherwise) and read. */
public interface ConditionRepository extends Repository<Condition, ConditionId> {

    <S extends Condition> S save(S condition);

    Optional<Condition> findById(ConditionId id);

    boolean existsById(ConditionId id);

    Optional<Condition> findByProjectIdAndReleaseAndCode(ProjectId projectId, String release, String code);
}
