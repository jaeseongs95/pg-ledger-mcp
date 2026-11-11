package org.pgledger.mcp.catalog;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The input schema of a registered tool as the MCP SDK holds it (a map), which can be switched to another schema in
 * one step. The SDK has no way to replace the definition of a tool without a gap: its {@code addTool} removes the
 * tool of the same name and adds the new one as two steps, and a {@code tools/list} that runs in between misses the
 * tool. The catalog therefore registers a tool once with this map and, when the codes change, switches the map; every
 * reader (the serialization of a {@code tools/list}) sees the old or the new schema, never neither.
 *
 * <p>The schema that is switched in must not be changed afterwards (the catalog builds a new one each time).
 */
final class LiveSchema extends AbstractMap<String, Object> {

    private final AtomicReference<Map<String, Object>> current;

    LiveSchema(Map<String, Object> schema) {
        this.current = new AtomicReference<>(schema);
    }

    /** From now on readers see this schema. */
    void switchTo(Map<String, Object> schema) {
        current.set(schema);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return current.get().entrySet();
    }

    @Override
    public Object get(Object key) {
        return current.get().get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return current.get().containsKey(key);
    }

    @Override
    public int size() {
        return current.get().size();
    }
}
