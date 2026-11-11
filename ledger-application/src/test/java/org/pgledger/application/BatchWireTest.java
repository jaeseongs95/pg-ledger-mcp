package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.domain.model.BatchOpKind;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** DOM-025 (the application's side): the wire records of a batch carry exactly the keys the domain lists per kind. */
class BatchWireTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private static BatchOpInput filled(BatchOpKind kind) {
        return switch (kind) {
            case SAVE -> new BatchOpInput.Save("defect", Map.of("title", "x"), "reason", "id", 1L, "request");
            case TRANSITION -> new BatchOpInput.Transition("defect", "id", 1L, "OPEN", "reason", "uri", "sha",
                    "target", "request");
            case RECORD_FIX -> new BatchOpInput.RecordFix("defect", "repo", "commit", "reason", "branch", "ci",
                    "success", "time", true, "request");
            case RECORD_CASE -> new BatchOpInput.RecordCase("defect", "seen", "reason", "evidence", "quote", "time",
                    "request");
            case LINK -> new BatchOpInput.Link("kind", "a", "b", "reason", false, "request");
            case CORRECT -> new BatchOpInput.Correct(1L, "reason", "request");
        };
    }

    @ParameterizedTest
    @EnumSource(BatchOpKind.class)
    @DisplayName("an operation record has the keys of its BatchOpKind and the op discriminator")
    void wireKeysEqualTheDomainKeys(BatchOpKind kind) {
        Map<?, ?> written = JSON.readValue(JSON.writeValueAsString(filled(kind)), Map.class);
        assertThat(written.keySet()).isEqualTo(kind.allowedKeys());
        assertThat(written.get("op")).isEqualTo(kind.token());
    }
}
