package org.pgledger.application.read;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.SearchQueryRepository;
import org.pgledger.database.repository.SearchQueryRepository.Kind;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
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

    /** The most keywords one search may carry (each one is a clause of the SQL). */
    public static final int MAX_KEYWORDS = 20;

    private static final String KEYWORD_BLANK = "{reason.MUST_NOT_BE_BLANK}";
    private static final String KEYWORD_LENGTH = "{reason.MUST_BE_AT_MOST_CHARS}";
    private static final String KEYWORD_COUNT = "{reason.MUST_HOLD_ITEMS}";

    /**
     * The criteria; everything but the keywords is optional. There are at most {@link #MAX_KEYWORDS} keywords and
     * every keyword is 1..{@link #MAX_KEYWORD} characters.
     */
    public record SearchQuery(String project, List<String> kinds,
            @Size(max = MAX_KEYWORDS, message = KEYWORD_COUNT) List<@NotBlank(message = KEYWORD_BLANK)
                    @Size(max = MAX_KEYWORD, message = KEYWORD_LENGTH) String> keywords,
            String match, List<String> excludeStatus, String createdFrom, String createdTo, Number maxRows,
            String cursor) {

        /** A blank criterion is a criterion that is not given. */
        public SearchQuery {
            project = Texts.nullIfBlank(project);
            createdFrom = Texts.nullIfBlank(createdFrom);
            createdTo = Texts.nullIfBlank(createdTo);
        }
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
                : Tokens.parseName("match", q.match(), KeywordMatch.values());
        Set<String> excluded = new LinkedHashSet<>();
        if (q.excludeStatus() != null) {
            q.excludeStatus().forEach(raw -> excluded.add(status(raw).name()));
        }
        ProjectId project = q.project() == null ? null : ProjectId.of(q.project());
        SearchQueryRepository.Filter filter = new SearchQueryRepository.Filter(project, kinds, keywords, match,
                excluded, time("created_from", q.createdFrom()), time("created_to", q.createdTo()));
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = List.of(String.valueOf(project), kinds.stream().sorted().toList(), keywords, match,
                excluded.stream().sorted().toList(), String.valueOf(filter.createdFrom()),
                String.valueOf(filter.createdTo()));
        SearchQueryRepository.Position after = cursors.decode(q.cursor(), PageCursor.Kind.SEARCH, criteria)
                .map(SearchService::position).orElse(null);
        var page = search.page(filter, maxRows, after);
        return cursors.page(page, PageCursor.Kind.SEARCH, criteria,
                h -> new Hit(KindToken.of(h.kind()).token(), h.id(), h.projectId(), h.title(), h.state(),
                        h.createdAt()),
                h -> h.createdAt() == null ? null : new UtcInstant(h.createdAt()).toString(),
                h -> h.kind().name() + ":" + h.id());
    }

    /** The position a cursor holds: the creation time of the last hit, its kind and its id ({@code KIND:id}). */
    private static SearchQueryRepository.Position position(Cursors.Position position) {
        int colon = position.id().indexOf(':');
        if (colon < 0) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
        }
        return new SearchQueryRepository.Position(position.sortKey() == null ? Instant.EPOCH
                : UtcInstant.parse("cursor", position.sortKey()).value(), Tokens.parseName("cursor",
                position.id().substring(0, colon), Kind.values()), position.id().substring(colon + 1));
    }

    /** The state constant of a name in the exclusion list; the name is that of a state of any of the three kinds. */
    private static LifecycleState status(String raw) {
        return STATES.stream().filter(state -> Objects.equals(state.token(), raw)).findFirst()
                .orElseThrow(() -> ValidationException.invalid("exclude_status", ValidationReason.UNKNOWN_VALUE,
                        STATES.stream().map(LifecycleState::name).distinct().toList(), String.valueOf(raw)));
    }

    private static Instant time(String path, String raw) {
        return raw == null ? null : UtcInstant.parse(path, raw).value();
    }
}
