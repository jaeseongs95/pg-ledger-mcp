package org.pgledger.hook;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tests' own JSON reader and writer, independent of the hook's codec: exact numbers, trailing zeros kept,
 * and strict about unknown fields so that an extra key in a hook answer fails the binding.
 */
final class Json {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private Json() {}

    /** A JSON value from its text; for the host values a test needs as they are (null, 3, {}, [] ...). */
    static JsonNode of(String text) {
        return MAPPER.readTree(text);
    }

    static JsonNode text(String value) {
        return MAPPER.getNodeFactory().stringNode(value);
    }
}
