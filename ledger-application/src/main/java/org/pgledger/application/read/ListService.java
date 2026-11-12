package org.pgledger.application.read;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.pgledger.database.repository.ConditionQueryRepository;
import org.pgledger.database.repository.DefectQueryRepository;
import org.pgledger.database.repository.DefectSummary;
import org.pgledger.database.repository.TaskQueryRepository;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.Tokens;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** The project lists of defects, tasks and conditions, newest first, in pages. */
@Service
@Transactional(readOnly = true)
@Validated
public class ListService {

    private static final String REQUIRED = "{reason.REQUIRED}";
    private static final int MAX_OWNER = 200;
    private static final String OWNER_LENGTH = "{reason.MUST_BE_AT_MOST_CHARS}";

    /** Filters of the defect list; the project is required, everything else narrows (AND). */
    public record DefectsQuery(@NotBlank(message = REQUIRED) String project, String status, String severity,
            String category, String attention, @Size(max = MAX_OWNER, message = OWNER_LENGTH) String owner,
            Number maxRows, String cursor) {

        /** A blank filter is a filter that is not given. */
        public DefectsQuery {
            status = Texts.nullIfBlank(status);
            severity = Texts.nullIfBlank(severity);
            category = Texts.nullIfBlank(category);
            attention = Texts.nullIfBlank(attention);
        }
    }

    /** Filters of the task list. */
    public record TasksQuery(@NotBlank(message = REQUIRED) String project, String state,
            @Size(max = MAX_OWNER, message = OWNER_LENGTH) String owner, String attention, Number maxRows,
            String cursor) {

        /** A blank filter is a filter that is not given. */
        public TasksQuery {
            state = Texts.nullIfBlank(state);
            attention = Texts.nullIfBlank(attention);
        }
    }

    /** Filters of the condition list. */
    public record ConditionsQuery(@NotBlank(message = REQUIRED) String project, String state, String release,
            Number maxRows, String cursor) {

        /** A blank filter is a filter that is not given. */
        public ConditionsQuery {
            state = Texts.nullIfBlank(state);
            release = Texts.nullIfBlank(release);
        }
    }

    public record DefectItem(String id, String title, String category, String severity, String status,
            String mergedInto, String owner, long version, Instant createdAt, Instant updatedAt) {
    }

    public record TaskItem(String id, String title, String state, String ownerActorId, long version,
            Instant createdAt, Instant updatedAt) {
    }

    public record ConditionItem(String id, String release, String goalLabel, String code, String conditionText,
            String state, long version) {
    }

    /** The attention kinds of defects and of tasks: each kind says which of the two it is about. */
    private static final AttentionKind[] DEFECT_KINDS = AttentionKind.of(HistoryEntity.DEFECT)
            .toArray(AttentionKind[]::new);
    private static final AttentionKind[] TASK_KINDS = AttentionKind.of(HistoryEntity.TASK)
            .toArray(AttentionKind[]::new);

    private final DefectQueryRepository defects;
    private final TaskQueryRepository tasks;
    private final ConditionQueryRepository conditions;
    private final Cursors cursors;

    public ListService(DefectQueryRepository defects, TaskQueryRepository tasks,
            ConditionQueryRepository conditions, Cursors cursors) {
        this.defects = defects;
        this.tasks = tasks;
        this.conditions = conditions;
        this.cursors = cursors;
    }

    public Page<DefectItem> defects(@Valid DefectsQuery q) {
        ProjectId project = ProjectId.of(q.project());
        DefectQueryRepository.Filter filter = new DefectQueryRepository.Filter(
                q.status() == null ? null : Tokens.parse("status", q.status(), DefectStatus.values()),
                q.severity() == null ? null : CodeValue.of(q.severity()),
                q.category() == null ? null : CodeValue.of(q.category()), null, null, Texts.nullIfBlank(q.owner()),
                q.attention() == null ? null : Tokens.parseName("attention", q.attention(), DEFECT_KINDS));
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = List.of(project, filter);
        // the cursor holds the position of the last row: its creation time and its id
        DefectQueryRepository.Position after = cursors.decode(q.cursor(), PageCursor.Kind.DEFECTS, criteria)
                .map(position -> new DefectQueryRepository.Position(
                        UtcInstant.parse("cursor", position.sortKey()).value(), DefectId.of(position.id())))
                .orElse(null);
        var page = defects.page(project, filter, maxRows, DefectQueryRepository.Order.NEWEST_FIRST, after);
        return cursors.page(page, PageCursor.Kind.DEFECTS, criteria, ListService::item,
                s -> new UtcInstant(s.createdAt()).toString(), s -> s.id().value());
    }

    private static DefectItem item(DefectSummary s) {
        return new DefectItem(s.id().value(), s.title(), s.category().value(), s.severity().value(),
                s.status().name(), s.mergedInto() == null ? null : s.mergedInto().value(), s.owner(), s.version(),
                s.createdAt(), s.updatedAt());
    }

    public Page<TaskItem> tasks(@Valid TasksQuery q) {
        ProjectId project = ProjectId.of(q.project());
        TaskQueryRepository.Filter filter = new TaskQueryRepository.Filter(
                q.state() == null ? null : Tokens.parse("state", q.state(), TaskState.values()),
                Texts.nullIfBlank(q.owner()),
                q.attention() == null ? null : Tokens.parseName("attention", q.attention(), TASK_KINDS));
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = List.of(project, filter);
        TaskQueryRepository.Position after = cursors.decode(q.cursor(), PageCursor.Kind.TASKS, criteria)
                .map(position -> new TaskQueryRepository.Position(
                        UtcInstant.parse("cursor", position.sortKey()).value(), TaskId.of(position.id())))
                .orElse(null);
        var page = tasks.page(project, filter, maxRows, after);
        return cursors.page(page, PageCursor.Kind.TASKS, criteria,
                t -> new TaskItem(t.id().value(), t.title(), t.state().name(), t.ownerActorId(), t.version(),
                        t.createdAt(), t.updatedAt()),
                t -> new UtcInstant(t.createdAt()).toString(), t -> t.id().value());
    }

    public Page<ConditionItem> conditions(@Valid ConditionsQuery q) {
        ProjectId project = ProjectId.of(q.project());
        ConditionQueryRepository.Filter filter = new ConditionQueryRepository.Filter(
                q.state() == null ? null : Tokens.parse("state", q.state(), ConditionState.values()),
                q.release());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        Object criteria = List.of(project, filter);
        ConditionId after = cursors.decode(q.cursor(), PageCursor.Kind.CONDITIONS, criteria)
                .map(position -> ConditionId.of(position.id())).orElse(null);
        var page = conditions.page(project, filter, maxRows, after);
        return cursors.page(page, PageCursor.Kind.CONDITIONS, criteria,
                c -> new ConditionItem(c.id().value(), c.release(), c.goalLabel(), c.code(), c.conditionText(),
                        c.state().name(), c.version()),
                c -> null, c -> c.id().value());
    }
}
