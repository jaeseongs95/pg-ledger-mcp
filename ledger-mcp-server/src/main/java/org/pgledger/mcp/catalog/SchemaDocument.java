package org.pgledger.mcp.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * A JSON schema of a tool as the MCP SDK holds it (a nested map of keywords; the SDK's {@code Tool} offers no other
 * form) with the few edits the catalog makes: close every object, restrict a property to a list of values, set a
 * description, resolve the descriptions, allow null in an output. The map form is confined to this class.
 *
 * <p>A property is addressed by a path of property names separated by dots; an array item is the name followed by
 * {@code []}. A property that is a {@code $ref} into {@code $defs} is followed to its definition.
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
        for (String segment : path.split("\\.")) {
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
