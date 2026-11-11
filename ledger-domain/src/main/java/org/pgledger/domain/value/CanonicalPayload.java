package org.pgledger.domain.value;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The normalised content of a request, in a form that does not depend on key order, on absent versus null
 * optional fields, or on how the caller spelled the input. Its digest is what a receipt compares to decide
 * "same request again" (replay) or "same id, other content" (conflict).
 */
public sealed interface CanonicalPayload {

    /** A text value. */
    record Text(String value) implements CanonicalPayload {
    }

    /** An ordered list of values. */
    record Items(List<CanonicalPayload> items) implements CanonicalPayload {

        public Items {
            items = List.copyOf(items);
        }
    }

    /** Named values; keys are sorted when written, {@code null} values are left out. */
    record Fields(Map<String, CanonicalPayload> fields) implements CanonicalPayload {

        public Fields {
            fields = Map.copyOf(fields);
        }

        public static Fields empty() {
            return new Fields(Map.of());
        }

        public Fields with(String key, CanonicalPayload value) {
            if (value == null) {
                return this;
            }
            Map<String, CanonicalPayload> copy = new TreeMap<>(fields);
            copy.put(key, value);
            return new Fields(copy);
        }

        public Fields text(String key, String value) {
            return value == null ? this : with(key, new Text(value));
        }
    }

    /** Digest of the operation name and its content. */
    static Sha256 digest(String operation, Fields content) {
        StringBuilder out = new StringBuilder();
        append(out, new Text(operation));
        append(out, content);
        return Sha256.ofText(out.toString());
    }

    private static void append(StringBuilder out, CanonicalPayload node) {
        switch (node) {
            case Text t -> out.append('t').append(t.value().length()).append(':').append(t.value());
            case Items i -> {
                out.append('[');
                i.items().forEach(item -> append(out, item));
                out.append(']');
            }
            case Fields f -> {
                out.append('{');
                new TreeMap<>(f.fields()).forEach((key, value) -> {
                    out.append('k').append(key.length()).append(':').append(key);
                    append(out, value);
                });
                out.append('}');
            }
        }
    }
}
