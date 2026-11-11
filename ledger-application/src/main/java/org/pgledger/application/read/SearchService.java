package org.pgledger.application.read;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.SearchQueryRepository;
import org.pgledger.database.repository.SearchQueryRepository.Kind;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.LifecycleState;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.Token;
import org.pgledger.domain.value.Tokens;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * Finds defects, tasks and conditions by keywords (a keyword matches in title, purpose, behavior, root cause or
 * condition text, ignoring case; {@code %}, {@code _} and {@code \} are literal), newest first. {@code match} is
 * ANY (default) or ALL. Time limits are inclusive UTC instants. A condition has no creation time: it is found when
 * no time limit is given and comes after everything that has one.
 */
@Service
@Transactional(readOnly = true)
@Validated
public class SearchService {

    /** Longest keyword. */
    public static final int MAX_KEYWORD = 200;

    private static final String KEYWORD_LENGTH = "each keyword must be 1..200 characters";

    /** The criteria; everything but the keywords is optional. Every keyword is 1..{@link #MAX_KEYWORD} characters. */
    public record SearchQuery(String project, List<String> kinds,
            List<@NotBlank(message = KEYWORD_LENGTH) @Size(max = MAX_KEYWORD, message = KEYWORD_LENGTH) String> keywords,
            String match, List<String> excludeStatus, String createdFrom, String createdTo, Number maxRows,
            String cursor) {
    }

    /** One found item; {@code createdAt} is null for a condition. */
    public record Hit(String kind, String id, String projectId, String title, String state, Instant createdAt) {
    }

    /** The kinds as the callers name them (lower case); the repository's {@link Kind} is the same set. */
    private enum KindToken implements Token {
        DEFECT(Kind.DEFECT), TASK(Kind.TASK), CONDITION(Kind.CONDITION);

        private final Kind kind;

        KindToken(Kind kind) {
            this.kind = kind;
        }

        @Override
        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        static KindToken of(Kind kind) {
            return switch (kind) {
                case DEFECT -> DEFECT;
                case TASK -> TASK;
                case CONDITION -> CONDITION;
            };
        }
    }

    /** Every state constant of the three kinds, for the exclusion list. */
    private static final List<LifecycleState> STATES = Stream.<LifecycleState[]>of(DefectStatus.values(),
            TaskState.values(), ConditionState.values()).flatMap(Arrays::stream).toList();

    private final SearchQueryRepository search;
    private final Cursors cursors;

    public SearchService(SearchQueryRepository search, Cursors cursors) {
        this.search = search;
        this.cursors = cursors;
    }

    public Page<Hit> search(@Valid SearchQuery q) {
        Set<Kind> kinds = new LinkedHashSet<>();
        if (q.kinds() == null || q.kinds().isEmpty()) {
            kinds.addAll(List.of(Kind.values()));
        } else {
            q.kinds().forEach(raw -> kinds.add(Tokens.parse("kinds", raw, KindToken.values()).kind));
        }
        List<String> keywords = q.keywords() == null ? List.of() : q.keywords();
        KeywordMatch match = q.match() == null ? KeywordMatch.ANY
                : EnumParser.parse("match", q.match(), KeywordMatch.values());
        Set<String> excluded = new LinkedHashSet<>();
        if (q.excludeStatus() != null) {
            q.excludeStatus().forEach(raw -> excluded.add(status(raw).name()));
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
                h -> new Hit(KindToken.of(h.kind()).token(), h.id(), h.projectId(), h.title(), h.state(),
                        h.createdAt()),
                h -> h.createdAt() == null ? null : new UtcInstant(h.createdAt()).toString(),
                h -> h.kind().name() + ":" + h.id());
    }

    /** The state constant of a name in the exclusion list; the name is that of a state of any of the three kinds. */
    private static LifecycleState status(String raw) {
        return STATES.stream().filter(state -> state.token().equals(raw)).findFirst()
                .orElseThrow(() -> ValidationException.invalid("exclude_status", "unknown value " + raw,
                        STATES.stream().map(LifecycleState::name).distinct().toList()));
    }

    private static Instant time(String path, String raw) {
        return raw == null || raw.isBlank() ? null : UtcInstant.parse(path, raw).value();
    }
}
