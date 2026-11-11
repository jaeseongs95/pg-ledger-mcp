package org.pgledger.database.repository;

import java.util.Optional;
import org.pgledger.domain.model.SessionGrant;
import org.springframework.data.repository.Repository;

/** Session grants: insert and read. The state changes (use, revoke) are statements of {@link GrantStateRepository}. */
public interface SessionGrantRepository extends Repository<SessionGrant, Long>, Insertable<SessionGrant> {

    Optional<SessionGrant> findById(Long id);

    Optional<SessionGrant> findByActiveKey(String activeKey);
}
