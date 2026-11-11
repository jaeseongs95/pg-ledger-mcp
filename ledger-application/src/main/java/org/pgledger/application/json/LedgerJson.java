package org.pgledger.application.json;

import java.util.List;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.JsonDocument;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON writer and reader of the application: records become snake_case JSON text for the long text
 * columns (history changes, before_data, receipt responses) and are read back into records. Nothing here builds
 * JSON by hand and no generic tree is exposed.
 */
@Component
public final class LedgerJson {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    /** For input from callers: a key that no record knows is refused. */
    private final JsonMapper strict = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public JsonDocument write(Object value) {
        try {
            return JsonDocument.of(mapper.writeValueAsString(value));
        } catch (JacksonException e) {
            throw new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), e);
        }
    }

    public <T> T read(JsonDocument document, Class<T> type) {
        return read(document.text(), type);
    }

    public <T> T read(String text, Class<T> type) {
        try {
            return mapper.readValue(text, type);
        } catch (JacksonException e) {
            throw new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), e);
        }
    }

    /**
     * Reads a JSON array that a caller sent (not a stored document). Anything wrong with it is a validation failure
     * whose path starts with the root path and follows the position in the input, for example {@code ops[1].titel}.
     */
    public <T> List<T> readInputList(String rootPath, String text, Class<T> element) {
        if (text == null || text.isBlank()) {
            throw ValidationException.invalid(rootPath, "is required");
        }
        try {
            return strict.readValue(text, strict.getTypeFactory().constructCollectionType(List.class, element));
        } catch (JacksonException e) {
            StringBuilder path = new StringBuilder(rootPath);
            for (JacksonException.Reference step : e.getPath()) {
                if (step.getIndex() >= 0) {
                    path.append('[').append(step.getIndex()).append(']');
                } else if (step.getPropertyName() != null) {
                    path.append('.').append(step.getPropertyName());
                }
            }
            throw ValidationException.invalid(path.toString(), e.getOriginalMessage());
        }
    }
}
