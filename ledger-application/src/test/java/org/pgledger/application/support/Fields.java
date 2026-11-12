package org.pgledger.application.support;

import java.util.Map;
import java.util.Set;
import org.pgledger.application.write.FieldsInput;
import org.pgledger.domain.value.Patch;

/**
 * Builds a {@link FieldsInput} from a map of wire keys, the way the tests write their fields: a key that is in the map
 * (even with a null value) is a patch set to the value, a key that is not in the map is left out.
 */
public final class Fields {

    private static final Set<String> KEYS = Set.of("project_id", "title", "category", "severity", "behavior",
            "root_cause", "owner_actor_id", "purpose", "success_condition", "origin_ref", "origin_quote", "release",
            "goal_label", "code", "condition_text");

    private Fields() {
    }

    /**
     * The fields of a save from a map of wire keys.
     *
     * @throws IllegalArgumentException when a key is none of the keys of {@link FieldsInput}
     */
    public static FieldsInput of(Map<String, String> raw) {
        for (String key : raw.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("not a key of FieldsInput: " + key);
            }
        }
        return new FieldsInput(
                patch(raw, "project_id"),
                patch(raw, "title"),
                patch(raw, "category"),
                patch(raw, "severity"),
                patch(raw, "behavior"),
                patch(raw, "root_cause"),
                patch(raw, "owner_actor_id"),
                patch(raw, "purpose"),
                patch(raw, "success_condition"),
                patch(raw, "origin_ref"),
                patch(raw, "origin_quote"),
                patch(raw, "release"),
                patch(raw, "goal_label"),
                patch(raw, "code"),
                patch(raw, "condition_text"));
    }

    /** The fields of a save with one key, the value may be null (a patch set to nothing). */
    public static FieldsInput of(String key, String value) {
        Map<String, String> one = new java.util.HashMap<>();
        one.put(key, value);
        return of(one);
    }

    private static Patch<String> patch(Map<String, String> raw, String key) {
        return raw.containsKey(key) ? Patch.set(raw.get(key)) : null;
    }
}
