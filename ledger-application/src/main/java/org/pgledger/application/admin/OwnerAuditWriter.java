package org.pgledger.application.admin;

import java.time.Clock;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.OwnerAuditRepository;
import org.pgledger.domain.model.OwnerAuditEntry;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Component;

/**
 * The only writer of the owner's audit trail. A row is inserted in the transaction of the owner operation it records
 * (every caller is a {@code @Transactional} service), so an operation and its audit row commit or roll back together.
 */
@Component
public class OwnerAuditWriter {

    private static final int DETAIL_MAX = 1000;

    private final OwnerAuditRepository audit;
    private final IdAllocator ids;
    private final Clock clock;

    public OwnerAuditWriter(OwnerAuditRepository audit, IdAllocator ids, Clock clock) {
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /** Appends one row; {@code detail} is cut to the length of its column. */
    public void record(OwnerIdentity actor, OwnerAction operation, String subject, String detail) {
        String text = detail == null || detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX);
        audit.insert(new OwnerAuditEntry(ids.next(Counter.OWNER_AUDIT), clock.instant(), actor.value(), operation,
                subject, text));
    }
}
