package org.pgledger.application.read;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.database.repository.HistoryQueryRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SessionKey;
import org.pgledger.domain.value.Tokens;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The history, the receipt of every write: by id list, id range, entity, project, operation, author and time,
 * always ascending by id. {@code before_data} comes back only when asked for and only for the rows that have it.
 */
@Service
@Transactional(readOnly = true)
public class HistoryService {

    /** The criteria, all optional and combined with AND. */
    public record HistoryQuery(List<Long> ids, Long idFrom, Long idTo, String entity, String entityId,
            String project, String op, String authorId, String atFrom, String atTo, Boolean includeBefore,
            Number maxRows, String cursor) {
    }

    /** One history row as the tools show it. {@code beforeData} is null unless asked for and present. */
    public record HistoryView(long id, Instant at, String authorId, String vendor, String login,
            String actorAssurance, String entity, String entityId, String op, String fromState, String toState,
            ChangeSet changes, Snapshot beforeData, String reason, String evidenceUri, Long correctsId,
            String requestId, String legacyRef) {
    }

    private static final Pattern VENDOR = Pattern.compile("^([A-Za-z0-9_-]+):.*");

    private final HistoryQueryRepository queries;
    private final HistoryRepository rows;
    private final LedgerJson json;
    private final Cursors cursors;

    public HistoryService(HistoryQueryRepository queries, HistoryRepository rows, LedgerJson json,
            Cursors cursors) {
        this.queries = queries;
        this.rows = rows;
        this.json = json;
        this.cursors = cursors;
    }

    public Page<HistoryView> history(HistoryQuery q) {
        HistoryQueryRepository.Filter filter = new HistoryQueryRepository.Filter(q.ids(), q.idFrom(), q.idTo(),
                blank(q.entity()) ? null : Tokens.parse("entity", q.entity(), HistoryEntity.values()),
                blank(q.entityId()) ? null : q.entityId(),
                blank(q.project()) ? null : ProjectId.of(q.project()),
                blank(q.op()) ? null : Tokens.parse("op", q.op(), HistoryOp.values()),
                blank(q.authorId()) ? null : q.authorId(),
                blank(q.atFrom()) ? null : UtcInstant.parse("at_from", q.atFrom()).value(),
                blank(q.atTo()) ? null : UtcInstant.parse("at_to", q.atTo()).value());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Cursors.Position position = cursors.decode(q.cursor(), PageCursor.Kind.HISTORY, filter);
        long after = position == null ? 0 : Long.parseLong(position.id());
        var ids = queries.page(filter, after, maxRows);
        boolean withBefore = q.includeBefore() != null && q.includeBefore();
        List<HistoryView> views = ids.rows().isEmpty() ? List.of() : rows.findByIdIn(ids.rows()).stream()
                .sorted(Comparator.comparing(HistoryEntry::id)).map(row -> view(row, withBefore)).toList();
        String next = ids.truncated() && !ids.rows().isEmpty()
                ? cursors.encode(PageCursor.Kind.HISTORY, filter, null, Long.toString(ids.rows().getLast())) : null;
        return new Page<>(views, ids.truncated(), next);
    }

    /** One row as a view, optionally with its {@code before_data}. */
    public HistoryView view(HistoryEntry row, boolean withBefore) {
        Snapshot before = withBefore && row.beforeData() != null
                ? json.read(row.beforeData(), Snapshot.typeOf(row.entity())) : null;
        Matcher vendor = VENDOR.matcher(new SessionKey(row.authorId().value()).value());
        return new HistoryView(row.id(), row.at(), row.authorId().value(), vendor.matches() ? vendor.group(1) : null,
                row.login(), row.actorAssurance().name(), row.entity().token(), row.entityId(), row.op().name(),
                row.fromState(), row.toState(), json.read(row.changes(), ChangeSet.class), before, row.reason(),
                row.evidenceUri(), row.correctsId(), row.requestId().toString(), row.legacyRef());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
