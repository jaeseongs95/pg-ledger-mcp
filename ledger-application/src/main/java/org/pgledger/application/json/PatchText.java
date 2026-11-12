package org.pgledger.application.json;

import org.pgledger.domain.value.Patch;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a field of an edit as a {@link Patch} of text, which tells three things apart that a plain {@code String} cannot:
 * a text is {@link Patch#set set} to that text, a JSON {@code null} is set to nothing (the field is to be cleared), and a
 * key that is missing stays {@link Patch#absent absent} (the field is not touched). Jackson asks a deserializer for each
 * case separately ({@link #deserialize}, {@link #getNullValue}, {@link #getAbsentValue}), so the difference needs no
 * bookkeeping of which keys the caller wrote.
 */
public final class PatchText extends ValueDeserializer<Patch<String>> {

    @Override
    public Patch<String> deserialize(JsonParser parser, DeserializationContext context) {
        return Patch.set(context.readValue(parser, String.class));
    }

    /** A JSON {@code null} is a value: the field is cleared. */
    @Override
    public Object getNullValue(DeserializationContext context) {
        return Patch.set(null);
    }

    /** A key that is not in the input is no value: the field is left as it is. */
    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return Patch.absent();
    }
}
