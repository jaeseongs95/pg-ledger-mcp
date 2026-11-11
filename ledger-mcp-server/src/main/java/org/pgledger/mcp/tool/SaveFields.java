package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.pgledger.domain.rule.CodeGroup;

/**
 * The {@code fields} argument of {@code ledger_save}: the editable fields of a defect, a task or a condition (the
 * kind decides which of them are valid). The component names are the wire keys; the descriptions are message
 * keys that the catalog resolves.
 */
public record SaveFields(
        @JsonProperty("project_id") @JsonPropertyDescription("field.project_id") String projectId,
        @JsonProperty("title") @JsonPropertyDescription("field.title") String title,
        @JsonProperty("category") @JsonPropertyDescription("field.category")
        @CommonCode(group = CodeGroup.DEFECT_CATEGORY) String category,
        @JsonProperty("severity") @JsonPropertyDescription("field.severity")
        @CommonCode(group = CodeGroup.DEFECT_SEVERITY) String severity,
        @JsonProperty("behavior") @JsonPropertyDescription("field.behavior") String behavior,
        @JsonProperty("root_cause") @JsonPropertyDescription("field.root_cause") String rootCause,
        @JsonProperty("owner_actor_id") @JsonPropertyDescription("field.owner_actor_id") String ownerActorId,
        @JsonProperty("purpose") @JsonPropertyDescription("field.purpose") String purpose,
        @JsonProperty("success_condition") @JsonPropertyDescription("field.success_condition")
        String successCondition,
        @JsonProperty("origin_ref") @JsonPropertyDescription("field.origin_ref") String originRef,
        @JsonProperty("origin_quote") @JsonPropertyDescription("field.origin_quote") String originQuote,
        @JsonProperty("release") @JsonPropertyDescription("field.release") String release,
        @JsonProperty("goal_label") @JsonPropertyDescription("field.goal_label") String goalLabel,
        @JsonProperty("code") @JsonPropertyDescription("field.code") String code,
        @JsonProperty("condition_text") @JsonPropertyDescription("field.condition_text") String conditionText,
        @JsonIgnore Set<String> mentioned) implements PresenceAware<SaveFields> {

    public SaveFields {
        mentioned = mentioned == null ? Set.of() : Set.copyOf(mentioned);
    }

    @Override
    public SaveFields withMentioned(Set<String> keys) {
        return new SaveFields(projectId, title, category, severity, behavior, rootCause, ownerActorId, purpose,
                successCondition, originRef, originQuote, release, goalLabel, code, conditionText, keys);
    }

    /** The keys the caller wrote with their values (null for a key written without value), in the contract order. */
    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "project_id", projectId);
        put(out, "title", title);
        put(out, "category", category);
        put(out, "severity", severity);
        put(out, "behavior", behavior);
        put(out, "root_cause", rootCause);
        put(out, "owner_actor_id", ownerActorId);
        put(out, "purpose", purpose);
        put(out, "success_condition", successCondition);
        put(out, "origin_ref", originRef);
        put(out, "origin_quote", originQuote);
        put(out, "release", release);
        put(out, "goal_label", goalLabel);
        put(out, "code", code);
        put(out, "condition_text", conditionText);
        return out;
    }

    private void put(Map<String, String> out, String key, String value) {
        if (mentioned.contains(key)) {
            out.put(key, value);
        }
    }
}
