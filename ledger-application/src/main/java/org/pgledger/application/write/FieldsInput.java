package org.pgledger.application.write;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import org.pgledger.application.json.PatchText;
import org.pgledger.domain.value.Patch;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The {@code fields} of a save as they arrive: one patch per editable key of a defect, a task or a condition (the kind of
 * the save decides which of them are valid). The component names are the wire keys. Reading with Jackson gives the three
 * states of a key: left out is {@link Patch#absent}, written with a JSON {@code null} is a patch set to nothing (clears an
 * optional field), written with a text is a patch set to it; {@code PatchText} does that. A record that is made in code
 * and given {@code null} for a key means the key is left out.
 */
public record FieldsInput(
        @JsonProperty("project_id") @JsonDeserialize(using = PatchText.class) Patch<String> projectId,
        @JsonProperty("title") @JsonDeserialize(using = PatchText.class) Patch<String> title,
        @JsonProperty("category") @JsonDeserialize(using = PatchText.class) Patch<String> category,
        @JsonProperty("severity") @JsonDeserialize(using = PatchText.class) Patch<String> severity,
        @JsonProperty("behavior") @JsonDeserialize(using = PatchText.class) Patch<String> behavior,
        @JsonProperty("root_cause") @JsonDeserialize(using = PatchText.class) Patch<String> rootCause,
        @JsonProperty("owner_actor_id") @JsonDeserialize(using = PatchText.class) Patch<String> ownerActorId,
        @JsonProperty("purpose") @JsonDeserialize(using = PatchText.class) Patch<String> purpose,
        @JsonProperty("success_condition") @JsonDeserialize(using = PatchText.class) Patch<String> successCondition,
        @JsonProperty("origin_ref") @JsonDeserialize(using = PatchText.class) Patch<String> originRef,
        @JsonProperty("origin_quote") @JsonDeserialize(using = PatchText.class) Patch<String> originQuote,
        @JsonProperty("release") @JsonDeserialize(using = PatchText.class) Patch<String> release,
        @JsonProperty("goal_label") @JsonDeserialize(using = PatchText.class) Patch<String> goalLabel,
        @JsonProperty("code") @JsonDeserialize(using = PatchText.class) Patch<String> code,
        @JsonProperty("condition_text") @JsonDeserialize(using = PatchText.class) Patch<String> conditionText) {

    public FieldsInput {
        projectId = orAbsent(projectId);
        title = orAbsent(title);
        category = orAbsent(category);
        severity = orAbsent(severity);
        behavior = orAbsent(behavior);
        rootCause = orAbsent(rootCause);
        ownerActorId = orAbsent(ownerActorId);
        purpose = orAbsent(purpose);
        successCondition = orAbsent(successCondition);
        originRef = orAbsent(originRef);
        originQuote = orAbsent(originQuote);
        release = orAbsent(release);
        goalLabel = orAbsent(goalLabel);
        code = orAbsent(code);
        conditionText = orAbsent(conditionText);
    }

    private static Patch<String> orAbsent(Patch<String> patch) {
        return patch == null ? Patch.absent() : patch;
    }

    /** The keys the caller wrote, by wire key and in the order of the contract. */
    Map<String, Patch<String>> mentioned() {
        Map<String, Patch<String>> keys = new LinkedHashMap<>();
        put(keys, "project_id", projectId);
        put(keys, "title", title);
        put(keys, "category", category);
        put(keys, "severity", severity);
        put(keys, "behavior", behavior);
        put(keys, "root_cause", rootCause);
        put(keys, "owner_actor_id", ownerActorId);
        put(keys, "purpose", purpose);
        put(keys, "success_condition", successCondition);
        put(keys, "origin_ref", originRef);
        put(keys, "origin_quote", originQuote);
        put(keys, "release", release);
        put(keys, "goal_label", goalLabel);
        put(keys, "code", code);
        put(keys, "condition_text", conditionText);
        return keys;
    }

    private static void put(Map<String, Patch<String>> keys, String key, Patch<String> patch) {
        if (patch.mentioned()) {
            keys.put(key, patch);
        }
    }
}
