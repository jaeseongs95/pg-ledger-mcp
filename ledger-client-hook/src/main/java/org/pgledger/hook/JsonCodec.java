package org.pgledger.hook;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the host JSON with Jackson, set up so that the round trip keeps what the host sent.
 *
 * <p>The tool arguments are arbitrary JSON chosen by the model, so they are handled as a Jackson tree; no
 * decision logic is written against untyped maps. Integers beyond long range stay big integers, decimals are
 * kept as {@code BigDecimal} with their trailing zeros (never a double), strings and unknown fields are untouched.
 * A number is written back by value: {@code 1e999} becomes {@code 1E+999} and {@code -0} becomes {@code 0}.
 */
public final class JsonCodec {

    /** Deeper input is refused as malformed. Real tool calls are a handful of levels deep. */
    private static final int MAX_NESTING_DEPTH = 256;

    private final JsonMapper mapper;

    private JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public static JsonCodec create() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH).build())
                // the hook output wraps the input one or two levels deeper
                .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH + 8).build())
                .build();
        JsonMapper mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                // text after the first JSON value is an error, as with JSON.parse
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        return new JsonCodec(mapper);
    }

    /**
     * Parses UTF-8 (or UTF-16/32 with a byte order mark) JSON bytes into a tree.
     *
     * @throws JacksonException if the bytes are not exactly one valid JSON document
     */
    JsonNode read(byte[] json) throws JacksonException {
        return mapper.readTree(json);
    }

    /** UTF-8 bytes of the compact JSON text, independent of the JVM default charset. */
    byte[] write(JsonNode node) throws JacksonException {
        return mapper.writeValueAsBytes(node);
    }

    /** The compact JSON text, for a host that carries JSON inside a JSON string. */
    String writeText(JsonNode node) throws JacksonException {
        return mapper.writeValueAsString(node);
    }

    ObjectNode newObject() {
        return mapper.createObjectNode();
    }
}
