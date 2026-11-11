package org.pgledger.application.write;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.ConditionFields;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFields;
import org.pgledger.domain.model.EntityFields;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.Patch;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code fields} of a save into the typed field records of the domain, and shows an entity (or the
 * fields of a request) as {@code field name -> text} for the history diff and the receipt digest. The key sets are
 * the editable sets of the contract; anything else is refused.
 */
@Component
public class EntityEditors {

    private static final List<String> DEFECT_KEYS = List.of("project_id", "title", "category", "severity",
            "behavior", "root_cause", "owner_actor_id");
    private static final List<String> TASK_KEYS = List.of("project_id", "title", "purpose", "success_condition",
            "owner_actor_id", "origin_ref", "origin_quote");
    private static final List<String> CONDITION_KEYS = List.of("project_id", "release", "goal_label", "code",
            "condition_text");

    /** The editable keys of an entity kind. */
    public List<String> keys(HistoryEntity entity) {
        return switch (entity) {
            case DEFECT -> DEFECT_KEYS;
            case TASK -> TASK_KEYS;
            case CONDITION -> CONDITION_KEYS;
            case DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, CONDITION_LINK, DEFECT_CASE ->
                throw ValidationException.invalid("entity", "cannot be saved: " + entity.token(),
                        List.of("defect", "task", "condition"));
        };
    }

    /** Refuses unknown keys and builds the typed fields; a missing map is a failure. */
    public EntityFields parse(HistoryEntity entity, Map<String, String> raw) {
        if (raw == null) {
            throw ValidationException.invalid("fields", "is required");
        }
        List<String> allowed = keys(entity);
        for (String key : raw.keySet()) {
            if (!allowed.contains(key)) {
                throw ValidationException.invalid("fields." + key, "is not an editable field of "
                        + entity.token() + " (state changes use transition)", allowed);
            }
        }
        return switch (entity) {
            case DEFECT -> DefectFields.of(patch(raw, "project_id"), patch(raw, "title"), patch(raw, "category"),
                    patch(raw, "severity"), patch(raw, "behavior"), patch(raw, "root_cause"),
                    patch(raw, "owner_actor_id"));
            case TASK -> TaskFields.of(patch(raw, "project_id"), patch(raw, "title"), patch(raw, "purpose"),
                    patch(raw, "success_condition"), patch(raw, "owner_actor_id"), patch(raw, "origin_ref"),
                    patch(raw, "origin_quote"));
            case CONDITION -> ConditionFields.of(patch(raw, "project_id"), patch(raw, "release"),
                    patch(raw, "goal_label"), patch(raw, "code"), patch(raw, "condition_text"));
            case DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, CONDITION_LINK, DEFECT_CASE ->
                throw new IllegalStateException("keys() refused it");
        };
    }

    private static Patch<String> patch(Map<String, String> raw, String key) {
        return raw.containsKey(key) ? Patch.set(raw.get(key)) : Patch.absent();
    }

    /** The mentioned fields of a request as text (null for a field that is cleared). */
    public Map<String, String> mentioned(EntityFields fields) {
        Map<String, String> out = new LinkedHashMap<>();
        switch (fields) {
            case DefectFields f -> {
                put(out, "project_id", f.projectId());
                put(out, "title", f.title());
                put(out, "category", f.category());
                put(out, "severity", f.severity());
                put(out, "behavior", f.behavior());
                put(out, "root_cause", f.rootCause());
                put(out, "owner_actor_id", f.ownerActorId());
            }
            case TaskFields f -> {
                put(out, "project_id", f.projectId());
                put(out, "title", f.title());
                put(out, "purpose", f.purpose());
                put(out, "success_condition", f.successCondition());
                put(out, "owner_actor_id", f.ownerActorId());
                put(out, "origin_ref", f.originRef());
                put(out, "origin_quote", f.originQuote());
            }
            case ConditionFields f -> {
                put(out, "project_id", f.projectId());
                put(out, "release", f.release());
                put(out, "goal_label", f.goalLabel());
                put(out, "code", f.code());
                put(out, "condition_text", f.conditionText());
            }
        }
        return out;
    }

    private static void put(Map<String, String> out, String key, Patch<?> patch) {
        if (patch.mentioned()) {
            Object value = patch.orElse(null);
            out.put(key, value == null ? null : valueText(value));
        }
    }

    private static String valueText(Object value) {
        return switch (value) {
            case org.pgledger.domain.value.ProjectId v -> v.value();
            case org.pgledger.domain.value.CodeValue v -> v.value();
            case org.pgledger.domain.value.AuthorId v -> v.value();
            case String v -> v;
            default -> throw new IllegalStateException("unexpected field value " + value.getClass());
        };
    }

    public Map<String, String> columns(Defect d) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("project_id", d.projectId().value());
        out.put("title", d.title());
        out.put("category", d.category().value());
        out.put("severity", d.severity().value());
        out.put("behavior", d.behavior());
        out.put("root_cause", d.rootCause());
        out.put("owner_actor_id", d.ownerActorId() == null ? null : d.ownerActorId().value());
        return out;
    }

    public Map<String, String> columns(Task t) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("project_id", t.projectId().value());
        out.put("title", t.title());
        out.put("purpose", t.purpose());
        out.put("success_condition", t.successCondition());
        out.put("owner_actor_id", t.ownerActorId() == null ? null : t.ownerActorId().value());
        out.put("origin_ref", t.originRef());
        out.put("origin_quote", t.originQuote());
        return out;
    }

    public Map<String, String> columns(Condition c) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("project_id", c.projectId().value());
        out.put("release", c.release());
        out.put("goal_label", c.goalLabel());
        out.put("code", c.code());
        out.put("condition_text", c.conditionText());
        return out;
    }

    /** The keys of the editable set, as a set. */
    public Set<String> keySet(HistoryEntity entity) {
        return Set.copyOf(keys(entity));
    }
}
