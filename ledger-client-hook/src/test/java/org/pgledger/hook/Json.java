package org.pgledger.hook;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.util.RawValue;

/**
 * The tests' own JSON reader and writer, independent of the hook's codec: exact numbers, trailing zeros kept.
 * A Java {@code null} component is left out of the written document; a JSON {@code null} is a {@code NullNode}.
 */
final class Json {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    static final String RESERVED_KEY = "_hostAttestation";

    private Json() {}

    /** A JSON value from its text; for host values a test needs as they are (null, 3, {}, [] ...). */
    static JsonNode of(String text) {
        return MAPPER.readTree(text);
    }

    static JsonNode text(String value) {
        return MAPPER.getNodeFactory().stringNode(value);
    }

    /** JSON text that is written to the event exactly as given (duplicate keys, lone surrogates ...). */
    static RawValue raw(String text) {
        return new RawValue(text);
    }

    /** The arguments as the hook must forward them: the model's arguments plus the attestation, nothing else. */
    static ObjectNode stamped(JsonNode arguments, String attestation) {
        ObjectNode copy = ((ObjectNode) arguments).deepCopy();
        copy.put(RESERVED_KEY, attestation);
        return copy;
    }

    /** A Java value (record, map, list ...) turned into the JSON tree the model would have sent. */
    static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    static byte[] bytes(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }
}
