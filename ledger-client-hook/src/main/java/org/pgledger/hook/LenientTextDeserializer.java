package org.pgledger.hook;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a JSON string as text and anything else (number, boolean, object, array) as {@code null}, without failing.
 * A host field of the wrong type then counts as absent, which is how the hook has always treated it: such an event
 * is not a ledger call, or its host id is invalid. A JSON {@code null} never reaches here; Jackson binds it to
 * {@code null} itself.
 */
final class LenientTextDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            return parser.getString();
        }
        parser.skipChildren();
        return null;
    }
}
