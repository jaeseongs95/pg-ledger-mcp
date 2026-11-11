package org.pgledger.mcp.support;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The arguments of a tool call as the wire carries them: a flat object. Tests that need keys the contract does not
 * know (an unknown argument, a legacy key) or values of a wrong type build the call from these pairs; the calls
 * that follow the contract use the typed records of the server instead.
 */
public record Args(@JsonAnyGetter Map<String, Object> values) {

    public static Args of() {
        return new Args(new LinkedHashMap<>());
    }

    public Args with(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(key, value);
        return new Args(copy);
    }
}
