package org.pgledger.application.read;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.SearchQueryRepository;
import org.pgledger.database.repository.SearchQueryRepository.Kind;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds defects, tasks and conditions by keywords (a keyword matches in title, purpose, behavior, root cause or
 * condition text, ignoring case; {@code %}, {@code _} and {@code \} are literal), newest first. {@code match} is
 * ANY (default) or ALL. Time limits are inclusive UTC instants. A condition has no creation time: it is found when
 * no time limit is given and comes after everything that has one.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    /** Longest keyword. */
    public static final int MAX_KEYWORD = 200;

    /** The criteria; everything but the keywords is optional. */
    public record SearchQuery(String project, List<String> kinds, List<String> keywords, String match,
            List<String> excludeStatus, String createdFrom, String createdTo, Number maxRows, String cursor) {
    }

    /** One found item; {@code createdAt} is null for a condition. */
    public record Hit(String kind, String id, String projectId, String title, String state, Instant createdAt) {
    }

    private final SearchQueryRepository search;
    private final Cursors cursors;

    public SearchService(SearchQueryRepository search, Cursors cursors) {
        this.search = search;
        this.cursors = cursors;
    }

    public Page<Hit> search(SearchQuery q) {
        Set<Kind> kinds = new LinkedHashSet<>();
        if (q.kinds() == null || q.kinds().isEmpty()) {
            kinds.addAll(List.of(Kind.values()));
        } else {
            q.kinds().forEach(raw -> kinds.add(kind(raw)));
        }
        List<String> keywords = new ArrayList<>();
        if (q.keywords() != null) {
            for (String keyword : q.keywords()) {
                if (keyword == null || keyword.isBlank() || keyword.length() > MAX_KEYWORD) {
                    throw ValidationException.invalid("keywords", "each keyword must be 1.." + MAX_KEYWORD
                            + " characters");
                }
                keywords.add(keyword);
            }
        }
        KeywordMatch match = q.match() == null ? KeywordMatch.ANY
                : EnumParser.parse("match", q.match(), KeywordMatch.values());
        Set<String> excluded = new LinkedHashSet<>();
        if (q.excludeStatus() != null) {
            q.excludeStatus().forEach(raw -> excluded.add(status(raw)));
        }
        ProjectId project = q.project() == null || q.project().isBlank() ? null : ProjectId.of(q.project());
        SearchQueryRepository.Filter filter = new SearchQueryRepository.Filter(project, kinds, keywords, match,
                excluded, time("created_from", q.createdFrom()), time("created_to", q.createdTo()));
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = List.of(String.valueOf(project), kinds.stream().sorted().toList(), keywords, match,
                excluded.stream().sorted().toList(), String.valueOf(filter.createdFrom()),
                String.valueOf(filter.createdTo()));
        Cursors.Position position = cursors.decode(q.cursor(), PageCursor.Kind.SEARCH, criteria);
        SearchQueryRepository.Position after = null;
        if (position != null) {
            int colon = position.id().indexOf(':');
            if (colon < 0) {
                throw ValidationException.invalid("cursor", "is damaged");
            }
            after = new SearchQueryRepository.Position(position.sortKey() == null ? Instant.EPOCH
                    : UtcInstant.parse("cursor", position.sortKey()).value(), EnumParser.parse("cursor",
                    position.id().substring(0, colon), Kind.values()), position.id().substring(colon + 1));
        }
        var page = search.page(filter, maxRows, after);
        return cursors.page(page, PageCursor.Kind.SEARCH, criteria,
                h -> new Hit(h.kind().name().toLowerCase(java.util.Locale.ROOT), h.id(), h.projectId(), h.title(),
                        h.state(), h.createdAt()),
                h -> h.createdAt() == null ? null : new UtcInstant(h.createdAt()).toString(),
                h -> h.kind().name() + ":" + h.id());
    }

    private static Kind kind(String raw) {
        for (Kind kind : Kind.values()) {
            if (kind.name().toLowerCase(java.util.Locale.ROOT).equals(raw)) {
                return kind;
            }
        }
        throw ValidationException.invalid("kinds", "unknown value " + raw, Arrays.stream(Kind.values())
                .map(k -> k.name().toLowerCase(java.util.Locale.ROOT)).toList());
    }

    private static String status(String raw) {
        List<String> known = new ArrayList<>();
        Arrays.stream(DefectStatus.values()).forEach(s -> known.add(s.name()));
        Arrays.stream(TaskState.values()).forEach(s -> known.add(s.name()));
        Arrays.stream(ConditionState.values()).forEach(s -> known.add(s.name()));
        if (raw == null || !known.contains(raw)) {
            throw ValidationException.invalid("exclude_status", "unknown value " + raw,
                    known.stream().distinct().toList());
        }
        return raw;
    }

    private static Instant time(String path, String raw) {
        return raw == null || raw.isBlank() ? null : UtcInstant.parse(path, raw).value();
    }
}
