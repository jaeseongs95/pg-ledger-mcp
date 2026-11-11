package org.pgledger.mcp.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.util.StringUtils;

/**
 * A JSON schema of a tool as the MCP SDK holds it (a nested map of keywords; the SDK's {@code Tool} offers no other
 * form) with the few edits the catalog makes: close every object, restrict a property to a list of values, set a
 * description, resolve the descriptions, allow null in an output. The map form is confined to this class.
 *
 * <p>A property is addressed by a path of property names separated by dots; an array item is the name followed by
 * {@code []}. A property that is a {@code $ref} into {@code $defs} is followed to its definition.
 *
 * <p><b>Why the schema is edited here</b> (principle 2.1: the answer to "does Spring offer it?" is recorded here).
 * Spring AI makes the input schema of an {@code @McpTool} method once, with a fixed generator
 * ({@code McpJsonSchemaGenerator}, victools with the Jackson and Swagger modules), and offers no hook to change the
 * result: the schema has no {@code additionalProperties} (spike report c8), it cannot list values that live in the
 * database and change while the server runs, and its descriptions are the annotation texts, not message-bundle
 * texts. The MCP SDK (2.1.0) takes a tool definition as given; {@code McpSyncServerCustomizer} changes the server
 * specification, and the list filter ({@code McpSyncListFilter}) only decides whether a tool is visible. Evidence:
 * the bytecode of spring-ai-mcp-annotations 2.0.2, spring-ai-autoconfigure-mcp-server-common 2.0.2 and mcp-core
 * 2.1.0 ({@code javap}), and the spike report (SPIKE_REPORT.md, b and c).
 *
 * <p>Document (read): Spring AI reference "MCP Annotations - Server"
 * (https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html). It does not describe a
 * customization of the input schema ({@code additionalProperties}, enumerations); the attributes of
 * {@code @McpToolParam} are only {@code description} and {@code required}: the documentation has no such feature.
 * Its section on dynamic schemas says "Tools can accept {@code CallToolRequest} for runtime schema handling", that is
 * the tool takes {@code request.arguments()} (a {@code Map<String, Object>}) and handles the arguments itself; a
 * schema that changes while the server runs, like the common codes here, is handled at the arguments in the same way
 * the official documentation shows. The MCP Java SDK javadoc was not opened; the SDK statements rest on {@code javap}.
 * The map form is the SDK's own type for a schema ({@code Tool.inputSchema()}); this class and {@code LiveSchema} are
 * the only places that hold it, and the edits are the minimum the catalog needs.
 */
final class SchemaDocument {

    private static final String PROPERTIES = "properties";
    private static final String DESCRIPTION = "description";
    private static final String DEFS = "$defs";

    private final Map<String, Object> root;

    private SchemaDocument(Map<String, Object> root) {
        this.root = root;
    }

    /** A deep copy of the schema of the SDK, so the original (the base of later builds) is never edited. */
    static SchemaDocument copyOf(Map<String, Object> schema) {
        return new SchemaDocument(copy(schema));
    }

    Map<String, Object> toSdk() {
        return root;
    }

    /** No object accepts a key that its schema does not list; an object that already says what it accepts keeps it. */
    void closeObjects() {
        close(root);
    }

    /** Restricts the property to the values; no values leaves it as it is. */
    void restrict(String path, List<String> values) {
        if (!values.isEmpty()) {
            node(path).ifPresent(node -> node.put("enum", new ArrayList<>(values)));
        }
    }

    Optional<String> description(String path) {
        return node(path).map(node -> node.get(DESCRIPTION)).filter(String.class::isInstance)
                .map(String.class::cast);
    }

    void describe(String path, String text) {
        node(path).ifPresent(node -> node.put(DESCRIPTION, text));
    }

    /** Replaces every description with the result of the operator (the message keys become their texts). */
    void rewriteDescriptions(UnaryOperator<String> resolve) {
        rewrite(root, resolve);
    }

    /** Lets every property of an output hold null, which the output records produce for an absent value. */
    void allowNullProperties() {
        nullable(root);
    }

    private Optional<Map<String, Object>> node(String path) {
        Map<String, Object> current = root;
        for (String segment : StringUtils.delimitedListToStringArray(path, ".")) {
            boolean item = segment.endsWith("[]");
            String name = item ? segment.substring(0, segment.length() - 2) : segment;
            Map<String, Object> properties = map(resolve(current).get(PROPERTIES));
            Map<String, Object> next = map(properties == null ? null : properties.get(name));
            if (next == null) {
                return Optional.empty();
            }
            current = item ? map(resolve(next).get("items")) : next;
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(resolve(current));
    }

    /** The definition a {@code $ref} points to, or the node itself. */
    private Map<String, Object> resolve(Map<String, Object> node) {
        Object ref = node.get("$ref");
        if (ref instanceof String text && text.startsWith("#/" + DEFS + "/")) {
            Map<String, Object> defs = map(root.get(DEFS));
            Map<String, Object> target = defs == null ? null : map(defs.get(text.substring(("#/" + DEFS + "/").length())));
            return target == null ? node : target;
        }
        return node;
    }

    private static void close(Map<String, Object> node) {
        // an object without listed properties is a map of free keys (a Map argument); it stays open
        if ("object".equals(node.get("type")) && node.containsKey(PROPERTIES)
                && !node.containsKey("additionalProperties")) {
            node.put("additionalProperties", Boolean.FALSE);
        }
        for (Object value : List.copyOf(node.values())) {
            visit(value, SchemaDocument::close);
        }
    }

    private static void rewrite(Map<String, Object> node, UnaryOperator<String> resolve) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (DESCRIPTION.equals(entry.getKey()) && entry.getValue() instanceof String text) {
                entry.setValue(resolve.apply(text));
            } else {
                visit(entry.getValue(), child -> rewrite(child, resolve));
            }
        }
    }

    private static void nullable(Map<String, Object> node) {
        Map<String, Object> properties = map(node.get(PROPERTIES));
        if (properties != null) {
            properties.values().forEach(property -> {
                Map<String, Object> schema = map(property);
                if (schema != null && schema.get("type") instanceof String type && !"null".equals(type)) {
                    schema.put("type", List.of(type, "null"));
                }
            });
        }
        for (Object value : List.copyOf(node.values())) {
            visit(value, SchemaDocument::nullable);
        }
    }

    private static void visit(Object value, java.util.function.Consumer<Map<String, Object>> action) {
        Map<String, Object> map = map(value);
        if (map != null) {
            action.accept(map);
        } else if (value instanceof List<?> list) {
            list.forEach(element -> visit(element, action));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(key, copyValue(value)));
        return out;
    }

    private static Object copyValue(Object value) {
        Map<String, Object> map = map(value);
        if (map != null) {
            return copy(map);
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list.stream().map(SchemaDocument::copyValue).toList());
        }
        return value;
    }
}
