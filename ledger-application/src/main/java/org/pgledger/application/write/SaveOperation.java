package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.ConditionFields;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFields;
import org.pgledger.domain.model.EntityFields;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.Tokens;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** Creates (expected version 0) or edits a defect, task or condition. */
@Component
public class SaveOperation extends WriteOperation<SaveCommand, SaveResult> {

    /** Marks a field that an edit clears; it differs from a field that is not mentioned. */
    private static final String CLEARED = "\u0000";

    private final EntityEditors editors;
    private final Rows rows;
    private final CodeRules codeRules;
    private final DefectRepository defects;
    private final TaskRepository tasks;
    private final ConditionRepository conditions;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final Clock clock;

    public SaveOperation(WriteGate gate, ReceiptService receipts, EntityEditors editors, Rows rows,
            CodeRules codeRules, DefectRepository defects, TaskRepository tasks, ConditionRepository conditions,
            IdAllocator ids, HistoryWriter history, Clock clock) {
        super(gate, receipts, ReceiptOperation.SAVE, SaveResult.class);
        this.editors = editors;
        this.rows = rows;
        this.codeRules = codeRules;
        this.defects = defects;
        this.tasks = tasks;
        this.conditions = conditions;
        this.ids = ids;
        this.history = history;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(SaveCommand c) {
        return Lookup.afterActor(() -> targets(c));
    }

    private List<Target> targets(SaveCommand c) {
        HistoryEntity entity = entity(c.entity());
        Scope scope = entity.writeScope();
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
            case DEFECT -> rows.defect(DefectId.of(id)).projectId();
            case TASK -> rows.task(TaskId.of(id)).projectId();
            case CONDITION -> rows.condition(ConditionId.of(id)).projectId();
            case DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, CONDITION_LINK, DEFECT_CASE ->
                throw new IllegalStateException("entity() refused it");
        };
        String moved = c.mentionedProject();
        if (entity == HistoryEntity.DEFECT || moved == null || moved.isBlank() || Objects.equals(moved, current.value())) {
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
        EntityFields fields = editors.parse(entity, c.fields());
        if (create) {
            fields.requireCreatable();
        } else {
            fields.requireEditable();
            if (fields instanceof DefectFields d && d.projectId().mentioned()) {
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
                .with("fields", payloadFields);
        return new Prepared<>(payload, ctx -> create ? create(ctx, entity, fields, c.id(), mentioned)
                : edit(ctx, entity, fields, c.requireId(), c.expectedVersion(), mentioned));
    }

    // ---------------------------------------------------------------- create

    private SaveResult create(WriteContext ctx, HistoryEntity entity, EntityFields fields, String explicitId,
            Map<String, String> mentioned) {
        Instant now = clock.instant();
        Project project = rows.project(fields.projectId().orNull());
        String id;
        long version;
        switch (fields) {
            case DefectFields f -> {
                DefectId defectId = defectId(project, explicitId);
                codeRules.checkDefect(null, f.category().orNull(), f.severity().orNull());
                Defect saved = inserted(() -> defects.save(Defect.open(defectId, f, now)), HistoryEntity.DEFECT,
                        defectId.value());
                id = saved.id().value();
                version = saved.version();
            }
            case TaskFields f -> {
                TaskId taskId = taskId(project, explicitId);
                Task saved = inserted(() -> tasks.save(Task.plan(taskId, f, now)), HistoryEntity.TASK,
                        taskId.value());
                id = saved.id().value();
                version = saved.version();
            }
            case ConditionFields f -> {
                if (explicitId == null || explicitId.isBlank()) {
                    throw ValidationException.invalid("id", ValidationReason.REQUIRED_FOR_CONDITION);
                }
                ConditionId conditionId = ConditionId.of(explicitId);
                if (conditions.existsById(conditionId) || conditions.findByProjectIdAndReleaseAndCode(
                        project.id(), f.release().orNull(), f.code().orNull()).isPresent()) {
                    throw duplicate(HistoryEntity.CONDITION, conditionId.value());
                }
                Condition saved = inserted(() -> conditions.save(Condition.open(conditionId, f)), HistoryEntity.CONDITION,
                        conditionId.value());
                id = saved.id().value();
                version = saved.version();
            }
        }
        ChangeSet changes = ChangeSet.empty();
        for (Map.Entry<String, String> field : mentioned.entrySet()) {
            changes = changes.with(field.getKey(), null, field.getValue());
        }
        long historyId = history.append(ctx, HistoryWriter.Entry.of(entity, id, HistoryOp.CREATE, changes));
        return new SaveResult(WriteStatus.COMMITTED, entity.token(), id, version, historyId, ctx.author().value(),
                ctx.login());
    }

    private DefectId defectId(Project project, String explicit) {
        if (explicit == null || explicit.isBlank()) {
            DefectId candidate;
            do {
                candidate = DefectId.of(project.code(), ids.nextDefectNo(project.code()));
            } while (defects.existsById(candidate));
            return candidate;
        }
        DefectId candidate = DefectId.of(explicit);
        if (!Objects.equals(candidate.projectCode(), project.code())) {
            String prefix = project.code().value() + "-D-";
            throw new ValidationException(ValidationCode.ID_PREFIX_MISMATCH, List.of(explicit, prefix),
                    List.of(new ValidationIssue("id", ValidationReason.MUST_START_WITH, List.of(prefix))), Map.of());
        }
        if (defects.existsById(candidate)) {
            throw duplicate(HistoryEntity.DEFECT, explicit);
        }
        ids.raiseDefectNo(project.code(), candidate.number());
        return candidate;
    }

    private TaskId taskId(Project project, String explicit) {
        if (explicit == null || explicit.isBlank()) {
            TaskId candidate;
            do {
                candidate = TaskId.issue(project.code(), ids.nextTaskNo(project.code()));
            } while (tasks.existsById(candidate));
            return candidate;
        }
        TaskId candidate = TaskId.of(explicit);
        if (tasks.existsById(candidate)) {
            throw duplicate(HistoryEntity.TASK, explicit);
        }
        // a free id has a number only in the form the server issues (at most 18 digits); any other id raises nothing
        candidate.numberIn(project.code()).ifPresent(number -> ids.raiseTaskNo(project.code(), number));
        return candidate;
    }

    // ------------------------------------------------------------------ edit

    private SaveResult edit(WriteContext ctx, HistoryEntity entity, EntityFields fields, String id,
            Long expectedVersion, Map<String, String> mentioned) {
        Instant now = clock.instant();
        Map<String, String> beforeColumns;
        Map<String, String> afterColumns;
        Snapshot before;
        long version;
        switch (fields) {
            case DefectFields f -> {
                Defect row = rows.lockedDefect(DefectId.of(id), expectedVersion);
                Defect after = row.edited(f, now);
                if (after.category() == null || after.severity() == null) {
                    codeRules.checkDefect(row, after.category(), after.severity());
                }
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                codeRules.checkDefect(row, after.category(), after.severity());
                before = Snapshot.DefectRow.of(row);
                version = rows.saveDefect(after).version();
            }
            case TaskFields f -> {
                Task row = rows.lockedTask(TaskId.of(id), expectedVersion);
                Task after = row.edited(f, now);
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                before = Snapshot.TaskRow.of(row);
                version = rows.saveTask(after).version();
            }
            case ConditionFields f -> {
                Condition row = rows.lockedCondition(ConditionId.of(id), expectedVersion);
                Condition after = row.edited(f);
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                before = Snapshot.ConditionRow.of(row);
                version = rows.saveCondition(after).version();
            }
        }
        ChangeSet changes = ChangeSet.empty();
        for (String key : mentioned.keySet()) {
            changes = changes.withIfChanged(key, beforeColumns.get(key), afterColumns.get(key));
        }
        long historyId = history.append(ctx,
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

    /**
     * Runs an insert; a duplicate key becomes the contract's error, never a raw one. (A lost version race of an update
     * is the VERSION_CONFLICT of {@link Rows#saveDefect}.)
     */
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
