package org.pgledger.application.history;

import java.time.Clock;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.write.WriteContext;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.Evidence;
import org.springframework.stereotype.Component;

/**
 * The only writer of history rows, and the only place that turns a replaced row into {@code before_data}. A row is
 * inserted in the transaction of the write; there is no update and no delete anywhere.
 */
@Component
public class HistoryWriter {

    /** What one history row says; the author, login, time, reason and request id come from the write context. */
    public record Entry(HistoryEntity entity, String entityId, HistoryOp op, String fromState, String toState,
            ChangeSet changes, Snapshot before, Evidence evidence, Long correctsId) {

        public static Entry of(HistoryEntity entity, String entityId, HistoryOp op, ChangeSet changes) {
            return new Entry(entity, entityId, op, null, null, changes, null, Evidence.NONE, null);
        }

        public Entry withBefore(Snapshot snapshot) {
            return new Entry(entity, entityId, op, fromState, toState, changes, snapshot, evidence, correctsId);
        }

        public Entry withEvidence(Evidence value) {
            return new Entry(entity, entityId, op, fromState, toState, changes, before, value, correctsId);
        }

        public Entry withStates(String from, String to) {
            return new Entry(entity, entityId, op, from, to, changes, before, evidence, correctsId);
        }

        public Entry correcting(long historyId) {
            return new Entry(entity, entityId, op, fromState, toState, changes, before, evidence, historyId);
        }
    }

    private final HistoryRepository history;
    private final IdAllocator ids;
    private final LedgerJson json;
    private final Clock clock;

    public HistoryWriter(HistoryRepository history, IdAllocator ids, LedgerJson json, Clock clock) {
        this.history = history;
        this.ids = ids;
        this.json = json;
        this.clock = clock;
    }

    /** Appends the row and returns its history id. */
    public long append(WriteContext context, Entry entry) {
        long id = ids.next(Counter.HISTORY);
        HistoryEntry row = new HistoryEntry(id, clock.instant(), context.author(), context.login(),
                ActorAssurance.DECLARED, entry.entity(), entry.entityId(), entry.op(), entry.fromState(),
                entry.toState(), json.write(entry.changes()),
                entry.before() == null ? null : json.write(entry.before()), context.reason().text(),
                entry.evidence().uri(), entry.evidence().sha256(), entry.correctsId(), context.requestId(), null);
        return history.insert(row).id();
    }
}
