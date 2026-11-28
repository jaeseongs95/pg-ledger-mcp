// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.read;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.veridger.database.repository.CriterionQueryRepository;
import org.veridger.database.repository.DateQuery;
import org.veridger.database.repository.IssueQueryRepository;
import org.veridger.database.repository.IssueSummary;
import org.veridger.database.repository.ProjectVersionRepository;
import org.veridger.database.repository.SpecQueryRepository;
import org.veridger.database.repository.SpecSummary;
import org.veridger.database.repository.TaskQueryRepository;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.PageCursor;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.veridger.domain.value.TextRule;
import org.veridger.domain.value.Tokens;
import org.veridger.domain.value.UtcInstant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project-scoped keyset lists of issues, specifications, tasks, and milestone criteria.
 */
@Service
@Transactional(readOnly = true)
public class ListService {

    public record IssuesQuery(String project, String status, String severity, String category, String createdFrom,
            String createdTo, String owner, String attention, String taskId, String order, Number maxRows,
            String cursor, String projectVersion) {

        public IssuesQuery(String project, String status, String severity, String category, String createdFrom,
                String createdTo, String owner, String attention, String taskId, String order, Number maxRows,
                String cursor) {
            this(project, status, severity, category, createdFrom, createdTo, owner, attention, taskId, order,
                    maxRows, cursor, null);
        }

        public IssuesQuery(String project, String status, String severity, String category, String createdFrom,
                String createdTo, String owner, String attention, String order, Number maxRows, String cursor) {
            this(project, status, severity, category, createdFrom, createdTo, owner, attention, null, order, maxRows,
                    cursor);
        }

        public IssuesQuery(String project, String status, String severity, String category, String owner,
                String attention, Number maxRows, String cursor) {
            this(project, status, severity, category, null, null, owner, attention, null, null, maxRows, cursor);
        }

        public IssuesQuery {
            project = Texts.nullIfBlank(project);
            status = Texts.nullIfBlank(status);
            severity = Texts.nullIfBlank(severity);
            category = Texts.nullIfBlank(category);
            createdFrom = Texts.nullIfBlank(createdFrom);
            createdTo = Texts.nullIfBlank(createdTo);
            owner = Texts.nullIfBlank(owner);
            attention = Texts.nullIfBlank(attention);
            taskId = Texts.nullIfBlank(taskId);
            order = Texts.nullIfBlank(order);
            projectVersion = TextRule.PROJECT_VERSION_NAME.optional(projectVersion);
        }
    }

    public record SpecsQuery(String project, String status, String parentId, String priority, String owner,
            String attention, Number maxRows, String cursor, String projectVersion) {

        public SpecsQuery(String project, String status, String parentId, String priority, String owner,
                String attention, Number maxRows, String cursor) {
            this(project, status, parentId, priority, owner, attention, maxRows, cursor, null);
        }

        public SpecsQuery {
            project = Texts.nullIfBlank(project);
            status = Texts.nullIfBlank(status);
            parentId = Texts.nullIfBlank(parentId);
            priority = Texts.nullIfBlank(priority);
            owner = Texts.nullIfBlank(owner);
            attention = Texts.nullIfBlank(attention);
            projectVersion = TextRule.PROJECT_VERSION_NAME.optional(projectVersion);
        }
    }

    public record TasksQuery(String project, String state, String owner, String attention, Number maxRows,
            String cursor, String projectVersion) {

        public TasksQuery(String project, String state, String owner, String attention, Number maxRows, String cursor) {
            this(project, state, owner, attention, maxRows, cursor, null);
        }

        public TasksQuery {
            project = Texts.nullIfBlank(project);
            state = Texts.nullIfBlank(state);
            owner = Texts.nullIfBlank(owner);
            attention = Texts.nullIfBlank(attention);
            projectVersion = TextRule.PROJECT_VERSION_NAME.optional(projectVersion);
        }
    }

    public record CriteriaQuery(String project, String state, String milestone, Number maxRows, String cursor,
            String projectVersion, boolean archived) {

        public CriteriaQuery(String project, String state, String milestone, Number maxRows, String cursor,
                String projectVersion) {
            this(project, state, milestone, maxRows, cursor, projectVersion, false);
        }

        public CriteriaQuery(String project, String state, String milestone, Number maxRows, String cursor) {
            this(project, state, milestone, maxRows, cursor, null);
        }

        public CriteriaQuery {
            project = Texts.nullIfBlank(project);
            state = Texts.nullIfBlank(state);
            milestone = Texts.nullIfBlank(milestone);
            projectVersion = TextRule.PROJECT_VERSION_NAME.optional(projectVersion);
        }
    }

    public record IssueItem(String id, String title, String category, String severity, String status,
            String mergedInto, String owner, long version, Instant createdAt, Instant updatedAt,
            List<String> projectVersions) {
    }

    public record SpecItem(String id, String parentId, String title, String status, String priority,
            String ownerActorId, long version, Instant createdAt, Instant updatedAt) {
    }

    public record TaskItem(String id, String title, String state, String ownerActorId, long version,
            Instant createdAt, Instant updatedAt) {
    }

    public record CriterionItem(String id, String milestoneName, String goalLabel, String code, String criterionText,
            String state, long version, Instant createdAt, Instant updatedAt) {
    }

    /** Per-milestone criterion readiness summary. */
    public record MilestoneRollup(String milestone, long total, long met, long completed, long active, long planned,
            long notMet, long unknown) {

        public int metPercent() {
            return total <= 0L ? 0 : (int) Math.round((met * 100.0) / total);
        }
    }

    /** Linked work item counts for one milestone criterion. */
    public record CriterionLinkCounts(long issues, long specs, long tasks) {
        public static final CriterionLinkCounts ZERO = new CriterionLinkCounts(0L, 0L, 0L);

        public long total() {
            return issues + specs + tasks;
        }
    }

    private static final int MAX_OWNER_CHARS = 200;

    private final IssueQueryRepository issues;
    private final SpecQueryRepository specs;
    private final TaskQueryRepository tasks;
    private final CriterionQueryRepository criteria;
    private final Cursors cursors;
    private final ProjectVersionRepository versions;

    public ListService(IssueQueryRepository issues, SpecQueryRepository specs, TaskQueryRepository tasks,
            CriterionQueryRepository criteria, Cursors cursors, ProjectVersionRepository versions) {
        this.issues = issues;
        this.specs = specs;
        this.tasks = tasks;
        this.criteria = criteria;
        this.cursors = cursors;
        this.versions = versions;
    }

    public Page<IssueItem> issues(IssuesQuery q) {
        return issues(q, DateQuery.NONE);
    }

    public Page<IssueItem> issues(IssuesQuery q, DateQuery dates) {
        return issues(q, dates, false);
    }

    public Page<IssueItem> issues(IssuesQuery q, DateQuery dates, boolean countMatches) {
        ProjectId project = requireProject(q.project());
        IssueStatus status = q.status() == null ? null : Tokens.parseName("status", q.status(), IssueStatus.values());
        CodeValue severity = q.severity() == null ? null : CodeValue.of(q.severity());
        CodeValue category = q.category() == null ? null : CodeValue.of(q.category());
        Instant from = q.createdFrom() == null ? null : UtcInstant.parse("created_from", q.createdFrom()).value();
        Instant to = q.createdTo() == null ? null : UtcInstant.parse("created_to", q.createdTo()).value();
        String owner = checkOwner(q.owner());
        AttentionKind attention = parseAttention(q.attention(), HistoryEntity.ISSUE);
        TaskId taskId = q.taskId() == null ? null : TaskId.of(q.taskId());
        IssueQueryRepository.Order order = q.order() == null ? IssueQueryRepository.Order.NEWEST_FIRST
                : Tokens.parseName("order", q.order(), IssueQueryRepository.Order.values());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        IssueQueryRepository.Filter filter = new IssueQueryRepository.Filter(status, severity, category, from, to,
                owner, attention, taskId);
        List<String> key = q.projectVersion() == null
                ? List.of(project.value(), filter.toString(), order.name())
                : List.of(project.value(), filter.toString(), order.name(), q.projectVersion());
        Object criteriaKey = dates.cursorCriteria(key);
        var after = cursors.decode(q.cursor(), PageCursor.Kind.ISSUES, criteriaKey)
                .map(ListService::issuePosition);
        String versionId = versionId(project, q.projectVersion());
        if (q.projectVersion() != null && versionId == null) {
            return new Page<>(List.of(), false, null, countMatches ? 0L : null);
        }
        var page = after.map(position -> issues.page(project, filter, maxRows, order, dates, position, versionId))
                .orElseGet(() -> issues.page(project, filter, maxRows, order, dates, null, versionId));
        Map<String, List<String>> related = versions.namesForIssues(project.value(),
                page.rows().stream().map(row -> row.id().value()).toList());
        var result = cursors.page(page, PageCursor.Kind.ISSUES, criteriaKey,
                r -> toIssueItem(r, related.getOrDefault(r.id().value(), List.of())),
                r -> dates.sortKey(r.createdAt(), r.updatedAt()).toString(),
                r -> r.id().value());
        return countMatches ? result.withMatchingCount(issues.count(project, filter, dates, versionId)) : result;
    }

    public Page<SpecItem> specs(SpecsQuery q) {
        return specs(q, DateQuery.NONE);
    }

    public Page<SpecItem> specs(SpecsQuery q, DateQuery dates) {
        return specs(q, dates, false);
    }

    public Page<SpecItem> specs(SpecsQuery q, DateQuery dates, boolean countMatches) {
        ProjectId project = requireProject(q.project());
        SpecStatus status = q.status() == null ? null : Tokens.parseName("status", q.status(), SpecStatus.values());
        SpecId parentId = q.parentId() == null ? null : SpecId.of(q.parentId());
        String priority = q.priority() == null ? null : TextRule.PRIORITY.required(q.priority());
        String owner = checkOwner(q.owner());
        AttentionKind attention = parseAttention(q.attention(), HistoryEntity.SPEC);
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        SpecQueryRepository.Filter filter = new SpecQueryRepository.Filter(status, parentId, priority, owner,
                attention);
        Object criteriaKey = dates.cursorCriteria(versionKey(project, filter, q.projectVersion()));
        var after = cursors.decode(q.cursor(), PageCursor.Kind.SPECS, criteriaKey).map(ListService::specPosition);
        String versionId = versionId(project, q.projectVersion());
        if (q.projectVersion() != null && versionId == null) {
            return new Page<>(List.of(), false, null, countMatches ? 0L : null);
        }
        var page = after.map(position -> specs.page(project, filter, maxRows, dates, position, versionId))
                .orElseGet(() -> specs.page(project, filter, maxRows, dates, null, versionId));
        var result = cursors.page(page, PageCursor.Kind.SPECS, criteriaKey,
                ListService::toSpecItem,
                r -> dates.sortKey(r.createdAt(), r.updatedAt()).toString(),
                r -> r.id().value());
        return countMatches ? result.withMatchingCount(specs.count(project, filter, dates, versionId)) : result;
    }

    public Page<TaskItem> tasks(TasksQuery q) {
        return tasks(q, DateQuery.NONE);
    }

    public Page<TaskItem> tasks(TasksQuery q, DateQuery dates) {
        return tasks(q, dates, false);
    }

    public Page<TaskItem> tasks(TasksQuery q, DateQuery dates, boolean countMatches) {
        ProjectId project = requireProject(q.project());
        TaskState state = q.state() == null ? null : Tokens.parseName("state", q.state(), TaskState.values());
        String owner = checkOwner(q.owner());
        AttentionKind attention = parseAttention(q.attention(), HistoryEntity.TASK);
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        TaskQueryRepository.Filter filter = new TaskQueryRepository.Filter(state, owner, attention);
        Object criteriaKey = dates.cursorCriteria(versionKey(project, filter, q.projectVersion()));
        var after = cursors.decode(q.cursor(), PageCursor.Kind.TASKS, criteriaKey).map(ListService::taskPosition);
        String versionId = versionId(project, q.projectVersion());
        if (q.projectVersion() != null && versionId == null) {
            return new Page<>(List.of(), false, null, countMatches ? 0L : null);
        }
        var page = after.map(position -> tasks.page(project, filter, maxRows, dates, position, versionId))
                .orElseGet(() -> tasks.page(project, filter, maxRows, dates, null, versionId));
        var result = cursors.page(page, PageCursor.Kind.TASKS, criteriaKey,
                r -> new TaskItem(r.id().value(), r.title(), r.state().name(), r.ownerActorId(), r.version(),
                        r.createdAt(), r.updatedAt()),
                r -> dates.sortKey(r.createdAt(), r.updatedAt()).toString(),
                r -> r.id().value());
        return countMatches ? result.withMatchingCount(tasks.count(project, filter, dates, versionId)) : result;
    }

    public Page<CriterionItem> criteria(CriteriaQuery q) {
        return criteria(q, DateQuery.NONE);
    }

    public Page<CriterionItem> criteria(CriteriaQuery q, DateQuery dates) {
        return criteria(q, dates, false);
    }

    public Page<CriterionItem> criteria(CriteriaQuery q, DateQuery dates, boolean countMatches) {
        ProjectId project = requireProject(q.project());
        CriterionState state = q.state() == null ? null
                : Tokens.parseName("state", q.state(), CriterionState.values());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        CriterionQueryRepository.Filter filter = new CriterionQueryRepository.Filter(state, q.milestone(), q.archived());
        Object criteriaKey = dates.cursorCriteria(versionKey(project, filter, q.projectVersion()));
        var after = cursors.decode(q.cursor(), PageCursor.Kind.CRITERIA, criteriaKey)
                .map(pos -> criterionPosition(pos, dates));
        String versionId = versionId(project, q.projectVersion());
        if (q.projectVersion() != null && versionId == null) {
            return new Page<>(List.of(), false, null, countMatches ? 0L : null);
        }
        var page = after.map(position -> criteria.page(project, filter, maxRows, dates, position, versionId))
                .orElseGet(() -> criteria.page(project, filter, maxRows, dates, null, versionId));
        var result = cursors.page(page, PageCursor.Kind.CRITERIA, criteriaKey,
                r -> new CriterionItem(r.id().value(), r.milestone(), r.goalLabel(), r.code(),
                        Texts.orEmpty(r.criterionText()), r.state().name(), r.version(), r.createdAt(), r.updatedAt()),
                r -> criterionSortKey(r, dates),
                r -> r.id().value());
        return countMatches ? result.withMatchingCount(criteria.count(project, filter, dates, versionId)) : result;
    }

    /** Per-milestone readiness rollups for a project, ordered by milestone name. */
    public List<MilestoneRollup> milestoneRollups(String project, boolean archived, String projectVersion) {
        ProjectId projectId = requireProject(project);
        String name = TextRule.PROJECT_VERSION_NAME.optional(projectVersion);
        String versionId = versionId(projectId, name);
        if (name != null && versionId == null) {
            return List.of();
        }
        return criteria.milestoneRollups(projectId, archived, versionId).stream()
                .map(r -> new MilestoneRollup(r.milestone(), r.total(), r.met(), r.completed(), r.active(),
                        r.planned(), r.notMet(), r.unknown()))
                .toList();
    }

    /** Batched counts of linked issues, specs, and tasks for a page of criterion ids. */
    public Map<String, CriterionLinkCounts> criterionLinkCounts(Collection<String> criterionIds) {
        if (criterionIds == null || criterionIds.isEmpty()) {
            return Map.of();
        }
        Map<String, CriterionQueryRepository.LinkCounts> raw = criteria.linkCounts(criterionIds);
        Map<String, CriterionLinkCounts> out = new HashMap<>(raw.size());
        for (Map.Entry<String, CriterionQueryRepository.LinkCounts> e : raw.entrySet()) {
            CriterionQueryRepository.LinkCounts c = e.getValue();
            out.put(e.getKey(), new CriterionLinkCounts(c.issues(), c.specs(), c.tasks()));
        }
        return out;
    }

    private static ProjectId requireProject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.invalid("project", ValidationReason.REQUIRED);
        }
        return ProjectId.of(raw);
    }

    private String versionId(ProjectId project, String name) {
        if (name == null) {
            return null;
        }
        var found = versions.find(project.value(), name);
        return found.isEmpty() ? null : found.orElseThrow().id();
    }

    private static List<String> versionKey(ProjectId project, Object filter, String name) {
        return name == null ? List.of(project.value(), filter.toString())
                : List.of(project.value(), filter.toString(), name);
    }

    private static String checkOwner(String owner) {
        if (owner != null && owner.codePointCount(0, owner.length()) > MAX_OWNER_CHARS) {
            throw ValidationException.invalid("owner", ValidationReason.MUST_BE_AT_MOST_CHARS,
                    Integer.toString(MAX_OWNER_CHARS));
        }
        return owner;
    }

    private static AttentionKind parseAttention(String raw, HistoryEntity entity) {
        if (raw == null) {
            return null;
        }
        List<AttentionKind> allowedKinds = java.util.Arrays.stream(AttentionKind.values())
                .filter(k -> k.entity() == entity)
                .toList();
        AttentionKind parsed = Tokens.parseName("attention", raw, AttentionKind.values());
        if (parsed.entity() != entity) {
            throw ValidationException.invalid("attention", ValidationReason.UNKNOWN_VALUE,
                    allowedKinds.stream().map(Enum::name).toList(), raw);
        }
        return parsed;
    }

    private static IssueItem toIssueItem(IssueSummary r, List<String> projectVersions) {
        return new IssueItem(r.id().value(), r.title(), r.category().value(), r.severity().value(), r.status().name(),
                r.mergedInto() == null ? null : r.mergedInto().value(), r.owner(), r.version(), r.createdAt(),
                r.updatedAt(), projectVersions);
    }

    private static SpecItem toSpecItem(SpecSummary r) {
        return new SpecItem(r.id().value(), r.parentId() == null ? null : r.parentId().value(), r.title(),
                r.status().name(), r.priority(), r.ownerActorId(), r.version(), r.createdAt(), r.updatedAt());
    }

    private static IssueQueryRepository.Position issuePosition(Cursors.Position pos) {
        try {
            if (pos.sortKey() == null) {
                throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
            }
            return new IssueQueryRepository.Position(UtcInstant.parse("cursor", pos.sortKey()).value(),
                    IssueId.of(pos.id()));
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
    }

    private static SpecQueryRepository.Position specPosition(Cursors.Position pos) {
        try {
            if (pos.sortKey() == null) {
                throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
            }
            return new SpecQueryRepository.Position(UtcInstant.parse("cursor", pos.sortKey()).value(),
                    SpecId.of(pos.id()));
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
    }

    private static TaskQueryRepository.Position taskPosition(Cursors.Position pos) {
        try {
            if (pos.sortKey() == null) {
                throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
            }
            return new TaskQueryRepository.Position(UtcInstant.parse("cursor", pos.sortKey()).value(),
                    TaskId.of(pos.id()));
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
    }

    private static String criterionSortKey(CriterionQueryRepository.CriterionSummary row, DateQuery dates) {
        Instant time = dates.sortKey(row.createdAt(), row.updatedAt());
        return dates.sort() == null || time == null ? null : time.toString();
    }

    private static CriterionQueryRepository.Position criterionPosition(Cursors.Position pos, DateQuery dates) {
        if (dates.sort() == null && pos.sortKey() != null) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
        }
        try {
            Instant time = pos.sortKey() == null ? null : UtcInstant.parse("cursor", pos.sortKey()).value();
            return new CriterionQueryRepository.Position(time, CriterionId.of(pos.id()));
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
    }
}
