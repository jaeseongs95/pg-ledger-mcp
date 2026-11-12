package org.pgledger.database.repository;

import java.util.Objects;
import java.util.Optional;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.ProjectId;
import org.springframework.data.repository.Repository;

/**
 * Release conditions: save (insert when the version is 0, update with the version check otherwise) and read.
 *
 * <p>The id of a condition is compared exactly, as the id of a task is ({@link TaskRepository}): {@link #findById} and
 * {@link #existsById} compare the id of the fetched row with the one asked for, in Java, so a DBMS that ignores case never
 * returns the sibling. {@link #findOneById} is the fetch itself.
 */
public interface ConditionRepository extends Repository<Condition, ConditionId> {

    <S extends Condition> S save(S condition);

    /** The fetch of {@link #findById}: it may return the row of another case on a DBMS that ignores case. */
    Optional<Condition> findOneById(ConditionId id);

    default Optional<Condition> findById(ConditionId id) {
        return findOneById(id).filter(row -> Objects.equals(row.id(), id));
    }

    default boolean existsById(ConditionId id) {
        return findById(id).isPresent();
    }

    /** The condition whose id is {@code key} in upper case ({@link ConditionId#key()}). */
    Optional<Condition> findByIdKey(String key);

    Optional<Condition> findByProjectIdAndReleaseAndCode(ProjectId projectId, String release, String code);
}
