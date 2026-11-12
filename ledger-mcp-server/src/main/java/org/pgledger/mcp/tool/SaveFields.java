package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import org.pgledger.application.json.PatchText;
import org.pgledger.application.write.FieldsInput;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.value.Patch;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The {@code fields} argument of {@code ledger_save} and of a {@code save} operation of {@code ledger_batch}: the editable
 * fields of a defect, a task or a condition (the kind decides which of them are valid). The component names are the wire
 * keys; the descriptions are message keys that the catalog resolves.
 *
 * <p>Every field is a {@link Patch}, so the binding tells "left out" (absent: not touched), "written as null" (set to
 * nothing: an optional field is cleared) and "written with a text" apart without remembering the keys of the call
 * ({@link PatchText}). The schema of the tool still shows each of them as a plain string: {@code @Schema} names the type
 * the model sees.
 */
public record SaveFields(
        @JsonProperty("project_id") @JsonPropertyDescription("field.project_id") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> projectId,
        @JsonProperty("title") @JsonPropertyDescription("field.title") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> title,
        @JsonProperty("category") @JsonPropertyDescription("field.category") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) @CommonCode(group = CodeGroup.DEFECT_CATEGORY) Patch<String> category,
        @JsonProperty("severity") @JsonPropertyDescription("field.severity") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) @CommonCode(group = CodeGroup.DEFECT_SEVERITY) Patch<String> severity,
        @JsonProperty("behavior") @JsonPropertyDescription("field.behavior") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> behavior,
        @JsonProperty("root_cause") @JsonPropertyDescription("field.root_cause") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> rootCause,
        @JsonProperty("owner_actor_id") @JsonPropertyDescription("field.owner_actor_id")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> ownerActorId,
        @JsonProperty("purpose") @JsonPropertyDescription("field.purpose") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> purpose,
        @JsonProperty("success_condition") @JsonPropertyDescription("field.success_condition")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> successCondition,
        @JsonProperty("origin_ref") @JsonPropertyDescription("field.origin_ref") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> originRef,
        @JsonProperty("origin_quote") @JsonPropertyDescription("field.origin_quote")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> originQuote,
        @JsonProperty("release") @JsonPropertyDescription("field.release") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> release,
        @JsonProperty("goal_label") @JsonPropertyDescription("field.goal_label") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> goalLabel,
        @JsonProperty("code") @JsonPropertyDescription("field.code") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> code,
        @JsonProperty("condition_text") @JsonPropertyDescription("field.condition_text")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> conditionText) {

    /** The input of the save service: the same patches, without the annotations that only the schema of the tool needs. */
    public FieldsInput toInput() {
        return new FieldsInput(projectId, title, category, severity, behavior, rootCause, ownerActorId, purpose,
                successCondition, originRef, originQuote, release, goalLabel, code, conditionText);
    }
}
