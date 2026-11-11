package org.pgledger.mcp.support;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The arguments of a tool call as the wire carries them: a flat object. Tests that need keys the contract does not
 * know (an unknown argument, a legacy key) or values of a wrong type build the call from these pairs; the calls
 * that follow the contract use the typed records of {@link Calls} instead.
 */
public record Args(@JsonAnyGetter Map<String, Object> values) {

    public static Args of() {
        return new Args(new LinkedHashMap<>());
    }

    /** The pairs of a valid call record, to be changed into an invalid call. */
    public static Args from(JsonMapper mapper, Object call) {
        return new Args(mapper.convertValue(call, new TypeReference<LinkedHashMap<String, Object>>() {
        }));
    }

    public Args with(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(key, value);
        return new Args(copy);
    }

    public Args without(String key) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.remove(key);
        return new Args(copy);
    }
}
