package org.pgledger.mcp.reflection;

import java.lang.reflect.Type;

/**
 * One parameter of a tool method: its wire key (the parameter name), whether the call must carry it, its type and
 * whether the type is an object or an array (then a text value is the old JSON-document form).
 */
record ToolParameter(String name, boolean required, Type type, boolean structured) {
}
