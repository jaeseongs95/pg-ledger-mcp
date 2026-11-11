package org.pgledger.application.read;

import java.util.List;
import org.pgledger.database.repository.AttentionQueryRepository;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.ProjectId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The defects and tasks that need a look: five kinds, of one project or of all, in pages ordered by kind and id. */
@Service
@Transactional(readOnly = true)
public class AttentionService {

    /** Optional filters. */
    public record AttentionQuery(String project, String kind, Number maxRows, String cursor) {
    }

    /** One item; {@code entity} is defect or task. */
    public record Item(String kind, String entity, String id, String projectId) {
    }

    private final AttentionQueryRepository attention;
    private final Cursors cursors;

    public AttentionService(AttentionQueryRepository attention, Cursors cursors) {
        this.attention = attention;
        this.cursors = cursors;
    }

    public Page<Item> attention(AttentionQuery q) {
        ProjectId project = q.project() == null || q.project().isBlank() ? null : ProjectId.of(q.project());
        AttentionKind kind = q.kind() == null || q.kind().isBlank() ? null
                : EnumParser.parse("kind", q.kind(), AttentionKind.values());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = List.of(String.valueOf(project), String.valueOf(kind));
        Cursors.Position position = cursors.decode(q.cursor(), PageCursor.Kind.ATTENTION, criteria);
        AttentionQueryRepository.Position after = position == null ? null : new AttentionQueryRepository.Position(
                EnumParser.parse("cursor", position.sortKey(), AttentionKind.values()), position.id());
        var page = attention.page(project, kind, maxRows, after);
        return cursors.page(page, PageCursor.Kind.ATTENTION, criteria,
                i -> new Item(i.kind().name(), entity(i.kind()), i.id(), i.projectId()),
                i -> i.kind().name(), AttentionQueryRepository.Item::id);
    }

    private static String entity(AttentionKind kind) {
        return switch (kind) {
            case OPEN_WITH_FIX, FIXED_WITHOUT_COMMIT, OPEN_NO_OWNER -> "defect";
            case TASK_UNKNOWN, DONE_NOT_VERIFIED -> "task";
        };
    }
}
