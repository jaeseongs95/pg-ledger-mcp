package org.pgledger.mcp.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * The part of a JSON schema (an input or an output schema of a tool, which the SDK hands over as a map) that the
 * tests look at, as a record: the type, the properties, what is required, whether the object is closed, the
 * description, the enumeration, the items and the alternatives. A property is addressed by a path of names
 * separated by dots; an array item is the name followed by {@code []}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchemaView(Object type, Map<String, SchemaView> properties, List<String> required,
        Object additionalProperties, String description, @JsonProperty("enum") List<String> allowed,
        SchemaView items, List<SchemaView> anyOf, @JsonProperty("$defs") Map<String, SchemaView> defs,
        @JsonProperty("$ref") String ref) {

    public SchemaView {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        required = required == null ? List.of() : List.copyOf(required);
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
        defs = defs == null ? Map.of() : Map.copyOf(defs);
    }

    /** Reads the schema the SDK holds as a map. */
    public static SchemaView of(JsonMapper mapper, Map<String, Object> schema) {
        return mapper.convertValue(schema, SchemaView.class);
    }

    /** This schema and every schema below it, definitions included. */
    public Stream<SchemaView> nodes() {
        return Stream.of(
                Stream.of(this),
                properties.values().stream().flatMap(SchemaView::nodes),
                Stream.ofNullable(items).flatMap(SchemaView::nodes),
                anyOf.stream().flatMap(SchemaView::nodes),
                defs.values().stream().flatMap(SchemaView::nodes)).flatMap(stream -> stream);
    }

    /** The nodes that declare properties: the objects of the schema. */
    public Stream<SchemaView> objects() {
        return nodes().filter(node -> !node.properties().isEmpty());
    }

    /** The schema at the path, or empty. */
    public Optional<SchemaView> at(String path) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        boolean item = head.endsWith("[]");
        String name = item ? head.substring(0, head.length() - 2) : head;
        Optional<SchemaView> next = Optional.ofNullable(properties.get(name))
                .map(property -> item ? property.items() : property);
        return dot < 0 ? next : next.flatMap(node -> node.at(path.substring(dot + 1)));
    }

    /** The object does not accept a key that it does not list. */
    public boolean closed() {
        return Boolean.FALSE.equals(additionalProperties);
    }

    /** The names of the properties this schema lists. */
    public List<String> propertyNames() {
        return properties.keySet().stream().sorted().toList();
    }
}
