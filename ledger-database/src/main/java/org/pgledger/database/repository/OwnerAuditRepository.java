package org.pgledger.database.repository;

import java.util.List;
import org.pgledger.domain.model.OwnerAuditEntry;
import org.springframework.data.repository.Repository;

/** The owner's audit trail: insert and read only (no save, update or delete, and the account has no right to them). */
public interface OwnerAuditRepository extends Repository<OwnerAuditEntry, Long>, Insertable<OwnerAuditEntry> {

    List<OwnerAuditEntry> findBySubjectOrderById(String subject);

    List<OwnerAuditEntry> findByActorOrderById(String actor);
}
