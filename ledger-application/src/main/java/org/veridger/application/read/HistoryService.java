// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.read;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.veridger.database.repository.DateQuery;
import org.veridger.database.repository.HistoryQueryRepository;
import org.veridger.database.repository.HistoryRepository;
import org.veridger.database.repository.ProjectVersionRepository;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.value.JsonDocument;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.PageCursor;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TextRule;
import org.veridger.domain.value.Tokens;
import org.veridger.domain.value.UtcInstant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * The history, the receipt of every write: by id list, id range, entity, project, operation, author and time,
 * ascending by id by default. {@code before_data} comes back only when asked for and present.
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
     * {@code changes}, the stored JSON object of field to [before, after]. {@code attachmentId} is the id of the attachment
     * the row points to, or null.
     */
    public record HistoryView(long id, Instant at, String authorId, String vendor, String login,
            String actorAssurance, String entity, String entityId, String op, String fromState, String toState,
            JsonDocument changes, JsonDocument beforeData, String reason, String evidenceUri, Long correctsId,
            String operationKey, String sourceRef, String attachmentId) {
    }

    private final HistoryQueryRepository queries;
    private final HistoryRepository rows;
    private final Cursors cursors;
    private final ProjectVersionRepository versions;

    public HistoryService(HistoryQueryRepository queries, HistoryRepository rows, Cursors cursors,
            ProjectVersionRepository versions) {
        this.queries = queries;
        this.rows = rows;
        this.cursors = cursors;
        this.versions = versions;
    }

    public Page<HistoryView> history(@Valid HistoryQuery q) {
        return history(q, DateQuery.NONE);
    }

    public Page<HistoryView> history(@Valid HistoryQuery q, DateQuery dates) {
        return history(q, dates, false, null);
    }

    public Page<HistoryView> history(@Valid HistoryQuery q, DateQuery dates, boolean countMatches, String projectVersion) {
        // @Size counts the list and does not look at its items: an item that is null is refused here, as a blank keyword of a
        // search is, and does not reach the filter (whose copy of the list refuses a null with a NullPointerException)
        if (q.ids() != null && q.ids().stream().anyMatch(Objects::isNull)) {
            throw ValidationException.invalid("ids", ValidationReason.REQUIRED);
        }
        HistoryQueryRepository.Filter filter = new HistoryQueryRepository.Filter(q.ids(), q.idFrom(), q.idTo(),
                q.entity() == null ? null : Tokens.parse("entity", q.entity(), HistoryEntity.values()),
                q.entityId(),
                q.project() == null ? null : ProjectId.of(q.project()),
                q.op() == null ? null : Tokens.parse("op", q.op(), HistoryOp.values()),
                q.authorId(),
                q.atFrom() == null ? null : UtcInstant.parse("at_from", q.atFrom()).value(),
                q.atTo() == null ? null : UtcInstant.parse("at_to", q.atTo()).value());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        String name = TextRule.PROJECT_VERSION_NAME.optional(projectVersion);
        if (name != null && filter.project() == null) {
            throw ValidationException.invalid("project", ValidationReason.REQUIRED);
        }
        Object criteriaKey = dates.cursorCriteria(name == null ? filter : List.of(filter, name));
        var after = cursors.decode(q.cursor(), PageCursor.Kind.HISTORY, criteriaKey).map(pos -> historyPosition(pos, dates));
        String versionId = null;
        if (name != null) {
            var found = versions.find(filter.project().value(), name);
            if (found.isEmpty()) {
                return new Page<>(List.of(), false, null, countMatches ? 0L : null);
            }
            versionId = found.orElseThrow().id();
        }
        String selectedVersionId = versionId;
        var positions = after.map(pos -> queries.positions(filter, maxRows, dates, pos, selectedVersionId))
                .orElseGet(() -> queries.positions(filter, maxRows, dates, null, selectedVersionId));
        Long matchingCount = countMatches ? queries.count(filter, dates, versionId) : null;
        if (positions.rows().isEmpty()) {
            return new Page<>(List.of(), false, null, matchingCount);
        }
        List<Long> ids = positions.rows().stream().map(HistoryQueryRepository.Position::id).toList();
        Map<Long, HistoryEntry> entries = rows.findByIdIn(ids).stream()
                .collect(Collectors.toMap(HistoryEntry::id, Function.identity()));
        boolean withBefore = q.includeBefore() != null && q.includeBefore();
        // the id of an entity is compared exactly: a DBMS that ignores case in = also returns the rows of the id in another case
        List<HistoryView> views = ids.stream().map(entries::get).filter(Objects::nonNull)
                .filter(row -> q.entityId() == null || Objects.equals(row.entityId(), q.entityId()))
                .map(row -> view(row, withBefore)).toList();
        var last = positions.rows().getLast();
        String next = positions.truncated() ? cursors.encode(PageCursor.Kind.HISTORY, criteriaKey,
                dates.sort() == null ? null : last.at().toString(), Long.toString(last.id())) : null;
        return new Page<>(views, positions.truncated(), next, matchingCount);
    }

    public Map<Long, List<String>> projectVersions(List<HistoryView> page) {
        return queries.projectVersions(page.stream().map(HistoryView::id).toList());
    }

    private static HistoryQueryRepository.Position historyPosition(Cursors.Position pos, DateQuery dates) {
        if ((dates.sort() == null) != (pos.sortKey() == null)) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
        }
        try {
            Instant time = pos.sortKey() == null ? null : UtcInstant.parse("cursor", pos.sortKey()).value();
            return new HistoryQueryRepository.Position(pos.number(), time);
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
    }

    /** One row as a view, optionally with its {@code before_data}. */
    public HistoryView view(HistoryEntry row, boolean withBefore) {
        JsonDocument before = withBefore ? row.beforeData() : null;
        // the vendor is the one the session key of the author names; a row of an imported actor may have none (the
        // component of the view is nullable on the wire)
        return row.authorId().sessionKey().vendor()
                .map(vendor -> view(row, vendor, before))
                .orElseGet(() -> view(row, null, before));
    }

    private static HistoryView view(HistoryEntry row, String vendor, JsonDocument before) {
        return new HistoryView(row.id(), row.at(), row.authorId().value(), vendor,
                row.login(), row.actorAssurance().name(), row.entity().token(), row.entityId(), row.op().name(),
                row.fromState(), row.toState(), row.changes(), before, row.reason(),
                row.evidenceUri(), row.correctsId(), row.operationKey().toString(), row.sourceRef(),
                row.attachmentId());
    }
}
