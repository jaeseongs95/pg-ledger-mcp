// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.veridger.application.gate.Lookup;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.history.ChangeSet;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.history.Snapshot;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.database.repository.CriterionRepository;
import org.veridger.database.repository.IdAllocator;
import org.veridger.database.repository.IssueRepository;
import org.veridger.database.repository.ProjectVersionRepository;
import org.veridger.database.repository.SpecRepository;
import org.veridger.database.repository.TaskRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TransitionException;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationIssue;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.Criterion;
import org.veridger.domain.model.CriterionFields;
import org.veridger.domain.model.EntityFields;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.IssueFields;
import org.veridger.domain.model.Project;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.model.Spec;
import org.veridger.domain.model.SpecFields;
import org.veridger.domain.model.Task;
import org.veridger.domain.model.TaskFields;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.LockOrder;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.CanonicalPayload;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.veridger.domain.value.TextRule;
import org.veridger.domain.value.Tokens;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** Creates (expected version 0) or edits an issue, specification, task, or milestone criterion. */
@Component
public class SaveOperation extends WriteOperation<SaveCommand, SaveResult> {

    private static final String CLEARED = "\u0000";

    private final WriteGate gate;
    private final EntityEditors editors;
    private final Rows rows;
    private final CodeRules codeRules;
    private final IssueRepository issues;
    private final SpecRepository specs;
    private final TaskRepository tasks;
    private final CriterionRepository criteria;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final ProjectVersionRepository versions;
    private final Clock clock;

    public SaveOperation(WriteGate gate, ReceiptService receipts, EntityEditors editors, Rows rows,
            CodeRules codeRules, IssueRepository issues, SpecRepository specs, TaskRepository tasks,
            CriterionRepository criteria, IdAllocator ids, HistoryWriter history, Clock clock,
            ProjectVersionRepository versions) {
        super(gate, receipts, ReceiptOperation.SAVE, SaveResult.class);
        this.gate = gate;
        this.editors = editors;
        this.rows = rows;
        this.codeRules = codeRules;
        this.issues = issues;
        this.specs = specs;
        this.tasks = tasks;
        this.criteria = criteria;
        this.ids = ids;
        this.history = history;
        this.versions = versions;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(SaveCommand c) {
        return Lookup.afterActor(() -> targets(c));
    }

    private List<Target> targets(SaveCommand c) {
        HistoryEntity entity = entity(c.entity());
        Scope scope = c.isCreate() ? entity.createScope() : entity.updateScope();
        if (c.isCreate()) {
            String project = c.mentionedProject();
            if (project == null || project.isBlank()) {
                throw ValidationException.invalid("project_id", ValidationReason.REQUIRED);
            }
            ProjectId projectId = ProjectId.of(project);
            rows.project(projectId);
            return List.of(new Target(projectId, scope));
        }
        String id = c.requireId();
        ProjectId current = switch (entity) {
            case ISSUE -> rows.issue(IssueId.of(id)).projectId();
            case SPEC -> rows.spec(SpecId.of(id)).projectId();
            case TASK -> rows.task(TaskId.of(id)).projectId();
            case CRITERION -> rows.criterion(CriterionId.of(id)).projectId();
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                throw new IllegalStateException("entity() refused it");
        };
        String moved = c.mentionedProject();
        if (entity == HistoryEntity.ISSUE || moved == null || moved.isBlank() || Objects.equals(moved, current.value())) {
            return List.of(new Target(current, scope));
        }
        ProjectId target = ProjectId.of(moved);
        rows.project(target);
        return List.of(new Target(current, scope), new Target(target, scope));
    }

    private static HistoryEntity entity(String token) {
        return Tokens.parse("entity", token, EntityEditors.SAVEABLE);
    }

    @Override
    protected Prepared<SaveResult> prepare(SaveCommand c) {
        HistoryEntity entity = entity(c.entity());
        boolean create = c.isCreate();
        String projectVersion = TextRule.PROJECT_VERSION_NAME.optional(c.projectVersion());
        if (projectVersion != null && !create) {
            throw ValidationException.invalid("project_version", ValidationReason.EXISTS_ONLY_FOR, "create");
        }
        EntityFields fields = editors.parse(entity, c.fields());
        if (create) {
            fields.requireCreatable();
        } else {
            fields.requireEditable();
            if (fields instanceof IssueFields i && i.projectId().mentioned()) {
                throw new TransitionException(TransitionCode.PROJECT_MOVE_FORBIDDEN, List.of());
            }
        }
        Map<String, String> mentioned = editors.mentioned(fields);
        CanonicalPayload.Fields payloadFields = CanonicalPayload.Fields.empty();
        for (Map.Entry<String, String> field : mentioned.entrySet()) {
            String value = field.getValue() == null ? (create ? null : CLEARED) : field.getValue();
            payloadFields = payloadFields.text(field.getKey(), value);
        }
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("entity", entity.token())
                .text("id", c.id() == null || c.id().isBlank() ? null : c.id())
                .text("v", Long.toString(c.expectedVersion() == null ? 0 : c.expectedVersion()))
                .with("fields", payloadFields).text("project_version", projectVersion);
        return new Prepared<>(payload, ctx -> create ? create(ctx, entity, fields, c.id(), mentioned, projectVersion)
                : edit(ctx, entity, fields, c.requireId(), c.expectedVersion(), mentioned));
    }

    private SaveResult create(WriteContext ctx, HistoryEntity entity, EntityFields fields, String explicitId,
            Map<String, String> mentioned, String projectVersion) {
        Instant now = clock.instant();
        Project project = rows.project(fields.projectId().orNull());
        if (ctx.isBatched() || projectVersion != null) {
            ctx.pinProject(rows, project.id());
        }
        var versionRef = projectVersion == null ? null : (ctx.session().channel() == Channel.ADMIN
                ? versions.findById(project.id().value(), projectVersion) : versions.find(project.id().value(), projectVersion))
                .orElseThrow(() -> new org.veridger.domain.error.LookupException(
                        org.veridger.domain.error.LookupCode.NOT_FOUND, List.of("project_version", projectVersion)));
        String id;
        long version;
        switch (fields) {
            case IssueFields f -> {
                IssueId issueId = issueId(project, explicitId);
                codeRules.checkIssue(null, f.category().orNull(), f.severity().orNull());
                Issue saved = inserted(() -> issues.save(Issue.open(issueId, f, now)), HistoryEntity.ISSUE,
                        issueId.value());
                ctx.markCreatedIssue(saved.id());
                id = saved.id().value();
                version = saved.version();
            }
            case SpecFields f -> {
                SpecId specId = specId(project, explicitId);
                if (f.parentId().orNull() != null) {
                    ctx.pinProject(rows, project.id());
                }
                validateSpecParent(specId, project.id(), f.parentId().orNull());
                Spec saved = inserted(() -> specs.save(Spec.create(specId, f, now)), HistoryEntity.SPEC,
                        specId.value());
                ctx.markCreatedSpec(saved.id());
                id = saved.id().value();
                version = saved.version();
            }
            case TaskFields f -> {
                TaskId taskId = taskId(project, explicitId);
                Task saved = inserted(() -> tasks.save(Task.plan(taskId, f, now)), HistoryEntity.TASK,
                        taskId.value());
                ctx.markCreatedTask(saved.id());
                id = saved.id().value();
                version = saved.version();
            }
            case CriterionFields f -> {
                if (explicitId == null || explicitId.isBlank()) {
                    throw ValidationException.invalid("id", ValidationReason.REQUIRED_FOR_CRITERION);
                }
                CriterionId criterionId = CriterionId.of(explicitId);
                criteria.findByIdKey(criterionId.key()).ifPresent(existing -> {
                    throw duplicate(HistoryEntity.CRITERION, existing.id().value());
                });
                if (criteria.findByProjectIdAndMilestoneAndCode(project.id(), f.milestone().orNull(),
                        f.code().orNull()).isPresent()) {
                    throw duplicate(HistoryEntity.CRITERION, criterionId.value());
                }
                Criterion saved = inserted(() -> criteria.save(Criterion.open(criterionId, f)),
                        HistoryEntity.CRITERION, criterionId.value());
                ctx.markCreatedCriterion(saved.id());
                id = saved.id().value();
                version = saved.version();
            }
        }
        if (ctx.session().channel() != Channel.ADMIN) {
            ctx.authorize(gate, List.of(new Target(project.id(), entity.createScope())));
        }
        ChangeSet changes = ChangeSet.empty();
        for (Map.Entry<String, String> field : mentioned.entrySet()) {
            if (!"".equals(field.getValue())) {
                changes = changes.with(field.getKey(), null, field.getValue());
            }
        }
        if (versionRef != null) {
            versions.linkItem(project.id().value(), entity, id, versionRef.id());
            changes = changes.with("project_version", null, versionRef.name());
        }
        long historyId = ctx.appendHistory(history, HistoryWriter.Entry.of(entity, id, HistoryOp.CREATE, changes));
        return new SaveResult(WriteStatus.COMMITTED, entity.token(), id, version, historyId, ctx.author().value(),
                ctx.login());
    }

    private IssueId issueId(Project project, String explicit) {
        if (explicit == null || explicit.isBlank()) {
            IssueId candidate;
            do {
                candidate = IssueId.of(project.code(), ids.nextIssueNo(project.code()));
            } while (issues.existsById(candidate));
            return candidate;
        }
        IssueId candidate = IssueId.of(explicit);
        if (!Objects.equals(candidate.projectCode(), project.code())) {
            String prefix = project.code().value() + "-I-";
            throw new ValidationException(ValidationCode.ID_PREFIX_MISMATCH, List.of(explicit, prefix),
                    List.of(new ValidationIssue("id", ValidationReason.MUST_START_WITH, List.of(prefix))), Map.of());
        }
        requireCanonical(explicit, candidate.number(), number -> IssueId.of(project.code(), number).value());
        if (issues.existsById(candidate)) {
            throw duplicate(HistoryEntity.ISSUE, explicit);
        }
        ids.takeIssueNo(project.code(), candidate.number()).ifPresent(next -> {
            throw idAhead(explicit, IssueId.of(project.code(), next).value());
        });
        return candidate;
    }

    private SpecId specId(Project project, String explicit) {
        if (explicit == null || explicit.isBlank()) {
            SpecId candidate;
            do {
                candidate = SpecId.issue(project.code(), ids.nextSpecNo(project.code()));
            } while (specs.findByIdKey(candidate.key()).isPresent());
            return candidate;
        }
        SpecId candidate = SpecId.of(explicit);
        if (candidate.number().isPresent() && candidate.numberIn(project.code()).isEmpty()) {
            String prefix = project.code().value() + "-S-";
            throw new ValidationException(ValidationCode.ID_PREFIX_MISMATCH, List.of(explicit, prefix),
                    List.of(new ValidationIssue("id", ValidationReason.MUST_START_WITH, List.of(prefix))), Map.of());
        }
        candidate.numberIn(project.code()).ifPresent(number -> requireCanonical(explicit, number,
                numberOfId -> SpecId.issue(project.code(), numberOfId).value()));
        specs.findByIdKey(candidate.key()).ifPresent(existing -> {
            throw duplicate(HistoryEntity.SPEC, existing.id().value());
        });
        candidate.numberIn(project.code()).ifPresent(number -> ids.takeSpecNo(project.code(), number).ifPresent(next -> {
            throw idAhead(explicit, SpecId.issue(project.code(), next).value());
        }));
        return candidate;
    }

    private TaskId taskId(Project project, String explicit) {
        if (explicit == null || explicit.isBlank()) {
            TaskId candidate;
            do {
                candidate = TaskId.issue(project.code(), ids.nextTaskNo(project.code()));
            } while (tasks.findByIdKey(candidate.key()).isPresent());
            return candidate;
        }
        TaskId candidate = TaskId.of(explicit);
        if (candidate.number().isPresent() && candidate.numberIn(project.code()).isEmpty()) {
            String prefix = project.code().value() + "-T-";
            throw new ValidationException(ValidationCode.ID_PREFIX_MISMATCH, List.of(explicit, prefix),
                    List.of(new ValidationIssue("id", ValidationReason.MUST_START_WITH, List.of(prefix))), Map.of());
        }
        candidate.numberIn(project.code()).ifPresent(number -> requireCanonical(explicit, number,
                numberOfId -> TaskId.issue(project.code(), numberOfId).value()));
        tasks.findByIdKey(candidate.key()).ifPresent(existing -> {
            throw duplicate(HistoryEntity.TASK, existing.id().value());
        });
        candidate.numberIn(project.code()).ifPresent(number -> ids.takeTaskNo(project.code(), number).ifPresent(next -> {
            throw idAhead(explicit, TaskId.issue(project.code(), next).value());
        }));
        return candidate;
    }

    private void validateSpecParent(SpecId selfId, ProjectId projectId, SpecId parentId) {
        if (parentId == null) {
            return;
        }
        if (Objects.equals(selfId.key(), parentId.key())) {
            throw ValidationException.invalid("parent_id", ValidationReason.MUST_DIFFER_FROM, "id");
        }
        Spec parent = rows.spec(parentId);
        if (!Objects.equals(parent.projectId(), projectId)) {
            throw ValidationException.invalid("parent_id", ValidationReason.MUST_MATCH, projectId.value());
        }
        Set<String> seen = new HashSet<>();
        seen.add(selfId.key());
        Spec cursor = parent;
        while (true) {
            if (!seen.add(cursor.id().key())) {
                throw ValidationException.invalid("parent_id", ValidationReason.MUST_DIFFER_FROM, "id");
            }
            if (cursor.parentId() == null) {
                break;
            }
            Optional<Spec> next = specs.findById(cursor.parentId());
            if (next.isEmpty()) {
                break;
            }
            cursor = next.orElseThrow();
        }
    }

    private record SpecPinResult(ProjectId projectId, boolean pinned) {
    }

    // ceiling: project-level ANSI row lock serializes spec parent/move mutations (lock order: project asc -> spec -> session_grant).
    private SpecPinResult pinSpecHierarchy(SpecId spec, ProjectId targetProject) {
        ProjectId current = rows.spec(spec).projectId();
        ProjectId maxPinned;
        if (targetProject != null && !Objects.equals(targetProject, current)) {
            if (LockOrder.PROJECT.compare(current, targetProject) <= 0) {
                rows.pinProject(current);
                rows.pinProject(targetProject);
                maxPinned = targetProject;
            } else {
                rows.pinProject(targetProject);
                rows.pinProject(current);
                maxPinned = current;
            }
        } else {
            rows.pinProject(current);
            maxPinned = current;
        }
        if (rows.pinSpecInProject(spec, current)) {
            return new SpecPinResult(current, true);
        }
        ProjectId movedProject = rows.spec(spec).projectId();
        boolean alreadyPinned = Objects.equals(movedProject, current) || Objects.equals(movedProject, targetProject);
        if (alreadyPinned && rows.pinSpecInProject(spec, movedProject)) {
            return new SpecPinResult(movedProject, true);
        }
        if (!alreadyPinned && LockOrder.PROJECT.compare(movedProject, maxPinned) > 0) {
            rows.pinProject(movedProject);
            if (rows.pinSpecInProject(spec, movedProject)) {
                return new SpecPinResult(movedProject, true);
            }
            movedProject = rows.spec(spec).projectId();
        }
        return new SpecPinResult(movedProject, false);
    }

    private static List<Target> moveTargets(ProjectId current, ProjectId target, Scope scope) {
        List<Target> targets = new ArrayList<>(2);
        targets.add(new Target(current, scope));
        if (target != null && !Objects.equals(target, current)) {
            targets.add(new Target(target, scope));
        }
        return targets;
    }

    private static void requireCanonical(String explicit, long number, LongFunction<String> canonical) {
        if (number < 1) {
            throw ValidationException.invalid("id", ValidationReason.MUST_BE_POSITIVE);
        }
        String written = canonical.apply(number);
        if (!Objects.equals(written, explicit)) {
            throw ValidationException.invalid("id", ValidationReason.ID_NOT_CANONICAL, written);
        }
    }

    private static ValidationException idAhead(String explicit, String nextId) {
        return new ValidationException(ValidationCode.ID_NUMBER_AHEAD, List.of(explicit, nextId),
                List.of(new ValidationIssue("id", ValidationReason.NUMBER_ABOVE_NEXT, List.of(nextId))), Map.of());
    }

    private SaveResult edit(WriteContext ctx, HistoryEntity entity, EntityFields fields, String id,
            Long expectedVersion, Map<String, String> mentioned) {
        Instant now = clock.instant();
        Map<String, String> beforeColumns;
        Map<String, String> afterColumns;
        Snapshot before;
        long version;
        switch (fields) {
            case IssueFields f -> {
                IssueId issueId = IssueId.of(id);
                ctx.requirePinnedIssue(issueId);
                Issue row = rows.issueForWrite(issueId, expectedVersion);
                row.requireNotVerified();
                Issue after = row.edited(f, now);
                if (after.category() == null || after.severity() == null) {
                    codeRules.checkIssue(row, after.category(), after.severity());
                }
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                codeRules.checkIssue(row, after.category(), after.severity());
                before = Snapshot.IssueRow.of(row);
                version = rows.saveIssue(after).version();
                ctx.authorize(gate, List.of(new Target(row.projectId(), HistoryEntity.ISSUE.updateScope())));
            }
            case SpecFields f -> {
                SpecId specId = SpecId.of(id);
                ProjectId targetProject = f.projectId().orNull();
                boolean hierarchyEdit = f.projectId().mentioned() || f.parentId().mentioned();
                ProjectId currentProject;
                if (ctx.isBatched()) {
                    currentProject = ctx.pinSpec(rows, specId);
                    if (hierarchyEdit) {
                        ctx.pinProject(rows, currentProject);
                        if (targetProject != null) {
                            ctx.pinProject(rows, targetProject);
                        }
                    }
                } else if (hierarchyEdit) {
                    SpecPinResult result = pinSpecHierarchy(specId, targetProject);
                    if (!result.pinned()) {
                        throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                                List.of(HistoryEntity.SPEC.token(), specId.value()));
                    }
                    currentProject = result.projectId();
                } else {
                    currentProject = rows.pinSpec(specId);
                }
                ctx.authorize(gate, moveTargets(currentProject, targetProject, HistoryEntity.SPEC.updateScope()));
                Spec row = rows.specForWrite(specId, expectedVersion);
                row.requireNotVerified();
                Spec after = row.edited(f, now);
                if (!Objects.equals(row.projectId(), after.projectId())
                        && (row.id().number().isPresent() || !specs.findByParentId(row.id()).isEmpty())) {
                    throw new TransitionException(TransitionCode.PROJECT_MOVE_FORBIDDEN, List.of());
                }
                validateSpecParent(row.id(), after.projectId(), after.parentId());
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                before = Snapshot.SpecRow.of(row);
                version = rows.saveSpec(after).version();
            }
            case TaskFields f -> {
                ProjectId currentProject = ctx.pinTask(rows, TaskId.of(id));
                ctx.authorize(gate, moveTargets(currentProject, f.projectId().orNull(), HistoryEntity.TASK.updateScope()));
                Task row = rows.taskForWrite(TaskId.of(id), expectedVersion);
                row.requireNotVerified();
                Task after = row.edited(f, now);
                if (!Objects.equals(row.projectId(), after.projectId()) && row.id().number().isPresent()) {
                    throw new TransitionException(TransitionCode.PROJECT_MOVE_FORBIDDEN, List.of());
                }
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                before = Snapshot.TaskRow.of(row);
                version = rows.saveTask(after).version();
            }
            case CriterionFields f -> {
                ProjectId currentProject = ctx.pinCriterion(rows, CriterionId.of(id));
                ctx.authorize(gate,
                        moveTargets(currentProject, f.projectId().orNull(), HistoryEntity.CRITERION.updateScope()));
                Criterion row = rows.criterionForWrite(CriterionId.of(id), expectedVersion);
                row.requireOpenForEdit();
                Criterion after = row.edited(f);
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                if (!Objects.equals(row.projectId(), after.projectId())
                        || !Objects.equals(row.milestone(), after.milestone())
                        || !Objects.equals(row.code(), after.code())) {
                    criteria.findByProjectIdAndMilestoneAndCode(after.projectId(), after.milestone(), after.code())
                            .filter(existing -> !Objects.equals(existing.id(), row.id()))
                            .ifPresent(existing -> {
                                throw duplicate(HistoryEntity.CRITERION, after.id().value());
                            });
                }
                before = Snapshot.CriterionRow.of(row);
                version = rows.saveCriterion(after).version();
            }
        }
        ChangeSet changes = ChangeSet.empty();
        for (String key : mentioned.keySet()) {
            changes = changes.withIfChanged(key, beforeColumns.get(key), afterColumns.get(key));
        }
        long historyId = ctx.appendHistory(history,
                HistoryWriter.Entry.of(entity, id, HistoryOp.EDIT, changes).withBefore(before));
        return new SaveResult(WriteStatus.COMMITTED, entity.token(), id, version, historyId, ctx.author().value(),
                ctx.login());
    }

    private static void requireChange(Map<String, String> mentioned, Map<String, String> before,
            Map<String, String> after) {
        boolean changed = mentioned.keySet().stream()
                .anyMatch(k -> !Objects.equals(before.get(k), after.get(k)));
        if (!changed) {
            throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
        }
    }

    private static <T> T inserted(Supplier<T> insert, HistoryEntity entity, String id) {
        try {
            return insert.get();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of(entity.token() + " " + id), e);
        }
    }

    private static ConflictException duplicate(HistoryEntity entity, String id) {
        return new ConflictException(ConflictCode.DUPLICATE_KEY, List.of(entity.token() + " " + id));
    }
}
