package org.pgledger.hook;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the host JSON with Jackson data binding: the host events and the answers are records.
 *
 * <p>Only the tool arguments are arbitrary JSON chosen by the model. They are the one part that stays a Jackson
 * tree, so the round trip keeps what the host sent: integers beyond long range stay big integers, decimals are
 * kept as {@code BigDecimal} with their trailing zeros (never a double), strings and unknown fields are untouched.
 * A number is written back by value: {@code 1e999} becomes {@code 1E+999} and {@code -0} becomes {@code 0}.
 *
 * <p>The one place that builds a {@code JsonMapper}: the hook jar runs as a plain {@code java -jar} process without a
 * Spring context (HK-018), so there is no Boot {@code JsonMapper} bean to inject, and the settings above differ from
 * the Boot defaults.
 */
public final class JsonCodec {

    /** Deeper input is refused as malformed. Real tool calls are a handful of levels deep. */
    private static final int MAX_NESTING_DEPTH = 256;

    private static final Logger LOG = Logger.getLogger(JsonCodec.class.getName());

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
                // the host events carry far more than the hook reads
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // the answer {} is a record without components
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .build();
        return new JsonCodec(mapper);
    }

    /**
     * Binds one host event. A JSON document that is valid but not an object of the host (null, an array, a number,
     * a string) is not an event: the result is {@code null}.
     *
     * @throws JacksonException if the bytes are not exactly one valid JSON document
     */
    <T> T readEvent(byte[] json, Class<T> type) throws JacksonException {
        // Anything that is not exactly one valid JSON document fails here and is the caller's malformed input.
        mapper.readTree(json);
        try {
            return mapper.readValue(json, type);
        } catch (DatabindException notThisShape) {
            // valid JSON, but not an event of this host: not an error, the caller treats it as "not a ledger call"
            LOG.log(Level.FINE, "the JSON document is not an event of the host", notThisShape);
            return null;
        }
    }

    /** Binds a JSON object that is already a tree (the arguments of a wrapped call); empty if it is not an object. */
    <T> Optional<T> bindObject(JsonNode node, Class<T> type) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.treeToValue(node, type));
    }

    /** Parses UTF-8 (or UTF-16/32 with a byte order mark) JSON text of the tool arguments into a tree. */
    JsonNode read(byte[] json) throws JacksonException {
        return mapper.readTree(json);
    }

    /** UTF-8 bytes of the compact JSON text, independent of the JVM default charset. */
    byte[] write(Object value) throws JacksonException {
        return mapper.writeValueAsBytes(value);
    }

    /** The compact JSON text, for a host that carries JSON inside a JSON string. */
    String writeText(JsonNode node) throws JacksonException {
        return mapper.writeValueAsString(node);
    }
}
