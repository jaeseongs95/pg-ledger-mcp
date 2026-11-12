package org.pgledger.application.read;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.Tokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The projects and the common codes, in pages. Both lists are short; the page is cut from the ordered list. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    public record ProjectsQuery(Number maxRows, String cursor) {
    }

    /** Filters of the code list: the group, and whether retired codes are listed too (by default they are not). */
    public record CodesQuery(String group, Boolean includeInactive, Number maxRows, String cursor) {
    }

    public record ProjectView(String id, String code, String title) {
    }

    public record CodeView(String group, String code, String nameKo, String nameEn, String descriptionKo,
            String descriptionEn, int sortOrder, String state) {
    }

    private final ProjectRepository projects;
    private final CommonCodeRepository codes;
    private final Cursors cursors;

    public CatalogService(ProjectRepository projects, CommonCodeRepository codes, Cursors cursors) {
        this.projects = projects;
        this.codes = codes;
        this.cursors = cursors;
    }

    public Page<ProjectView> projects(ProjectsQuery q) {
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = "projects";
        Optional<Cursors.Position> position = cursors.decode(q.cursor(), PageCursor.Kind.PROJECTS, criteria);
        List<Project> all = projects.findAllByOrderByCode();
        return slice(all, maxRows, position, PageCursor.Kind.PROJECTS, criteria, p -> p.code().value(),
                p -> new ProjectView(p.id().value(), p.code().value(), p.title()));
    }

    public Page<CodeView> codes(CodesQuery q) {
        CodeGroup group = q.group() == null || q.group().isBlank() ? null
                : Tokens.parse("group", q.group(), CodeGroup.values());
        boolean inactive = q.includeInactive() != null && q.includeInactive();
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = List.of(String.valueOf(group), inactive);
        Optional<Cursors.Position> position = cursors.decode(q.cursor(), PageCursor.Kind.CODES, criteria);
        List<CommonCode> all = new ArrayList<>();
        for (CodeGroup g : CodeGroup.values()) {
            if (group == null || group == g) {
                all.addAll(codes.catalog(g).entries());
            }
        }
        Predicate<CommonCode> shown = c -> inactive || c.state() == CodeState.ACTIVE;
        return slice(all.stream().filter(shown).toList(), maxRows, position, PageCursor.Kind.CODES, criteria,
                c -> c.group().name() + ":" + c.code().value(),
                c -> new CodeView(c.group().name(), c.code().value(), c.nameKo(), c.nameEn(), c.descriptionKo(),
                        c.descriptionEn(), c.sortOrder(), c.state().name()));
    }

    /** The items after the position (by key, in list order), at most max rows, with the cursor when more follow. */
    private <T, R> Page<R> slice(List<T> ordered, MaxRows maxRows, Optional<Cursors.Position> position,
            PageCursor.Kind kind, Object criteria, Function<T, String> key, Function<T, R> view) {
        // the index after the row the cursor names (the end of the list when it names none), or the first row
        int start = position.map(after -> {
            int at = 0;
            while (at < ordered.size() && !Objects.equals(key.apply(ordered.get(at)), after.id())) {
                at++;
            }
            return Math.min(at + 1, ordered.size());
        }).orElse(0);
        int end = Math.min(start + maxRows.value(), ordered.size());
        List<R> items = ordered.subList(start, end).stream().map(view).toList();
        boolean more = end < ordered.size();
        String next = more ? cursors.encode(kind, criteria, null, key.apply(ordered.get(end - 1))) : null;
        return new Page<>(items, more, next);
    }
}
