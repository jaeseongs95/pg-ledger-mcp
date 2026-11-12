package org.pgledger.application.read;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.pgledger.database.repository.HistoryQueryRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.Tokens;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * The history, the receipt of every write: by id list, id range, entity, project, operation, author and time,
 * always ascending by id. {@code before_data} comes back only when asked for and only for the rows that have it.
 */
@Service
@Transactional(readOnly = true)
@Validated
public class HistoryService {

    /** The most ids one history call may carry (they become the {@code IN} list of the SQL). */
    public static final int MAX_IDS = 200;

    /** The criteria, all optional and combined with AND; a blank text is a criterion that is not given. */
    public record HistoryQuery(@Size(max = MAX_IDS, message = "{reason.MUST_HOLD_ITEMS}") List<Long> ids, Long idFrom, Long idTo, String entity, String entityId,
            String project, String op, String authorId, String atFrom, String atTo, Boolean includeBefore,
            Number maxRows, String cursor) {

        public HistoryQuery {
            entity = Texts.nullIfBlank(entity);
            entityId = Texts.nullIfBlank(entityId);
            project = Texts.nullIfBlank(project);
            op = Texts.nullIfBlank(op);
            authorId = Texts.nullIfBlank(authorId);
            atFrom = Texts.nullIfBlank(atFrom);
            atTo = Texts.nullIfBlank(atTo);
        }
    }

    /**
     * One history row as the tools show it. {@code beforeData} is null unless asked for and present; it is the JSON
     * text as the row stores it (written by the ledger mapper, snake_case), which is passed on and not parsed; so is
     * {@code changes}, the stored JSON object of field to [before, after].
     */
    public record HistoryView(long id, Instant at, String authorId, String vendor, String login,
            String actorAssurance, String entity, String entityId, String op, String fromState, String toState,
            JsonDocument changes, JsonDocument beforeData, String reason, String evidenceUri, Long correctsId,
            String requestId, String legacyRef) {
    }

    private final HistoryQueryRepository queries;
    private final HistoryRepository rows;
    private final Cursors cursors;

    public HistoryService(HistoryQueryRepository queries, HistoryRepository rows, Cursors cursors) {
        this.queries = queries;
        this.rows = rows;
        this.cursors = cursors;
    }

    public Page<HistoryView> history(@Valid HistoryQuery q) {
        HistoryQueryRepository.Filter filter = new HistoryQueryRepository.Filter(q.ids(), q.idFrom(), q.idTo(),
                q.entity() == null ? null : Tokens.parse("entity", q.entity(), HistoryEntity.values()),
                q.entityId(),
                q.project() == null ? null : ProjectId.of(q.project()),
                q.op() == null ? null : Tokens.parse("op", q.op(), HistoryOp.values()),
                q.authorId(),
                q.atFrom() == null ? null : UtcInstant.parse("at_from", q.atFrom()).value(),
                q.atTo() == null ? null : UtcInstant.parse("at_to", q.atTo()).value());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        long after = cursors.decode(q.cursor(), PageCursor.Kind.HISTORY, filter).map(Cursors.Position::number)
                .orElse(0L);
        var ids = queries.page(filter, after, maxRows);
        boolean withBefore = q.includeBefore() != null && q.includeBefore();
        // the id of an entity is compared exactly: a DBMS that ignores case in = also returns the rows of the id in another case
        List<HistoryView> views = ids.rows().isEmpty() ? List.of() : rows.findByIdIn(ids.rows()).stream()
                .sorted(Comparator.comparing(HistoryEntry::id))
                .filter(row -> q.entityId() == null || Objects.equals(row.entityId(), q.entityId()))
                .map(row -> view(row, withBefore)).toList();
        String next = ids.truncated() && !ids.rows().isEmpty()
                ? cursors.encode(PageCursor.Kind.HISTORY, filter, null, Long.toString(ids.rows().getLast())) : null;
        return new Page<>(views, ids.truncated(), next);
    }

    /** One row as a view, optionally with its {@code before_data}. */
    public HistoryView view(HistoryEntry row, boolean withBefore) {
        JsonDocument before = withBefore ? row.beforeData() : null;
        // the vendor is the one the session key of the author names; a row of an imported actor may have none
        return new HistoryView(row.id(), row.at(), row.authorId().value(),
                row.authorId().sessionKey().vendor().orElse(null),
                row.login(), row.actorAssurance().name(), row.entity().token(), row.entityId(), row.op().name(),
                row.fromState(), row.toState(), row.changes(), before, row.reason(),
                row.evidenceUri(), row.correctsId(), row.requestId().toString(), row.legacyRef());
    }
}
