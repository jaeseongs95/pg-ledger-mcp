package org.pgledger.mcp.reflection;

import java.util.List;
import org.pgledger.domain.rule.CodeGroup;

/**
 * A place of a tool's input schema that holds a common code: the schema path ({@code fields.category}, an array
 * item ends in {@code []}), the group whose active codes fill it, and the values that are allowed besides them.
 */
public record CodeSlot(String path, CodeGroup group, List<String> fixed) {

    public CodeSlot {
        fixed = List.copyOf(fixed);
    }
}
