package org.pgledger.application.read;

import java.util.List;
import org.pgledger.database.repository.AttentionQueryRepository;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.Tokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The defects and tasks that need a look: five kinds, of one project or of all, in pages ordered by kind and id. */
@Service
@Transactional(readOnly = true)
public class AttentionService {

    /** Optional filters. */
    public record AttentionQuery(String project, String kind, Number maxRows, String cursor) {

        /** A blank filter is a filter that is not given. */
        public AttentionQuery {
            project = Texts.nullIfBlank(project);
            kind = Texts.nullIfBlank(kind);
        }
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
        ProjectId project = q.project() == null ? null : ProjectId.of(q.project());
        AttentionKind kind = q.kind() == null ? null : Tokens.parseName("kind", q.kind(), AttentionKind.values());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = List.of(String.valueOf(project), String.valueOf(kind));
        AttentionQueryRepository.Position after = cursors.decode(q.cursor(), PageCursor.Kind.ATTENTION, criteria)
                .map(position -> new AttentionQueryRepository.Position(
                        Tokens.parseName("cursor", position.sortKey(), AttentionKind.values()), position.id()))
                .orElse(null);
        var page = attention.page(project, kind, maxRows, after);
        // the kind of item says which kind of row it is about; its token is the name of that kind in the responses
        return cursors.page(page, PageCursor.Kind.ATTENTION, criteria,
                i -> new Item(i.kind().name(), i.kind().entity().token(), i.id(), i.projectId()),
                i -> i.kind().name(), AttentionQueryRepository.Item::id);
    }
}
