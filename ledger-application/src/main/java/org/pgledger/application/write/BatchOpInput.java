package org.pgledger.application.write;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;

/**
 * One operation of a batch as it arrives: the {@code op} key picks the record, the other keys are the keys of
 * {@code BatchOpKind}. Reading is strict: an unknown key or an unknown {@code op} is refused. The required keys are
 * the constraints of the records (a missing key and a JSON {@code null} are the same refusal, REQUIRED, with the path
 * {@code ops[i].<key>}); {@code BatchService} validates an operation just before it runs it, in the order of the
 * operations.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BatchOpInput.Save.class, name = "save"),
    @JsonSubTypes.Type(value = BatchOpInput.Transition.class, name = "transition"),
    @JsonSubTypes.Type(value = BatchOpInput.RecordFix.class, name = "record_fix"),
    @JsonSubTypes.Type(value = BatchOpInput.RecordCase.class, name = "record_case"),
    @JsonSubTypes.Type(value = BatchOpInput.Link.class, name = "link"),
    @JsonSubTypes.Type(value = BatchOpInput.Correct.class, name = "correct")})
public sealed interface BatchOpInput {

    /** The message template of every required key: the reason REQUIRED. */
    String REQUIRED = "{reason.REQUIRED}";

    /** The operation's own request id, or null (the batch derives one). */
    String requestId();

    record Save(@NotNull(message = REQUIRED) String entity, @NotNull(message = REQUIRED) FieldsInput fields,
            @NotNull(message = REQUIRED) String reason, String id, Long expectedVersion, String requestId)
            implements BatchOpInput {
    }

    record Transition(@NotNull(message = REQUIRED) String entity, @NotNull(message = REQUIRED) String id,
            @NotNull(message = REQUIRED) Long expectedVersion, @NotNull(message = REQUIRED) String to,
            @NotNull(message = REQUIRED) String reason, String evidenceUri, String evidenceSha256, String mergedInto,
            String requestId) implements BatchOpInput {
    }

    record RecordFix(@NotNull(message = REQUIRED) String defectId, @NotNull(message = REQUIRED) String repo,
            @NotNull(message = REQUIRED) String commitSha, @NotNull(message = REQUIRED) String reason, String branch,
            String ciRef, String ciConclusion, String ciCompletedAt, Boolean markSourceFixed, String requestId)
            implements BatchOpInput {
    }

    record RecordCase(@NotNull(message = REQUIRED) String defectId, @NotNull(message = REQUIRED) String observation,
            @NotNull(message = REQUIRED) String reason, String evidence, String userQuote, String observedAt,
            String requestId) implements BatchOpInput {
    }

    record Link(@NotNull(message = REQUIRED) String kind, @NotNull(message = REQUIRED) String a,
            @NotNull(message = REQUIRED) String b, @NotNull(message = REQUIRED) String reason, Boolean remove,
            String requestId) implements BatchOpInput {
    }

    record Correct(@NotNull(message = REQUIRED) Long historyId, @NotNull(message = REQUIRED) String reason,
            String requestId) implements BatchOpInput {
    }
}
