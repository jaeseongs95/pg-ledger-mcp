package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.rule.OwnerAction;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of the owner's audit trail: who ({@code actor}, the owner identity of the admin web sign-in or of the command
 * line tool), when, what ({@code operation}) and which thing it concerned ({@code subject}, for example the author and
 * project of a grant). The trail is append-only like the history: the application inserts, the operator's GRANT gives
 * the application account no UPDATE or DELETE on it.
 */
@Table("owner_audit")
public record OwnerAuditEntry(@Id Long id, @Column("occurred_at") Instant at, String actor, OwnerAction operation, String subject,
        String detail) {
}
