package org.pgledger.mcp.reflection;

import java.util.List;

/**
 * An argument of a tool that holds a value of a closed set: the parameter, the schema path of the value (an array
 * item ends in {@code []}) and the allowed values.
 */
public record ChoiceSlot(String parameter, String path, List<String> values) {

    public ChoiceSlot {
        values = List.copyOf(values);
    }
}
