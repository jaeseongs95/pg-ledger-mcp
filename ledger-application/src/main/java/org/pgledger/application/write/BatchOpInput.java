package org.pgledger.application.write;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

/**
 * One operation of a batch as it arrives: the {@code op} key picks the record, the other keys are the keys of
 * {@code BatchOpKind}. Reading is strict: an unknown key or an unknown {@code op} is refused. Whether the
 * required keys are present is checked by {@code BatchService} in the order of the operations.
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

    /** The operation's own request id, or null (the batch derives one). */
    String requestId();

    record Save(String entity, Map<String, String> fields, String reason, String id, Long expectedVersion,
            String requestId) implements BatchOpInput {
    }

    record Transition(String entity, String id, Long expectedVersion, String to, String reason, String evidenceUri,
            String evidenceSha256, String mergedInto, String requestId) implements BatchOpInput {
    }

    record RecordFix(String defectId, String repo, String commitSha, String reason, String branch, String ciRef,
            String ciConclusion, String ciCompletedAt, Boolean markSourceFixed, String requestId)
            implements BatchOpInput {
    }

    record RecordCase(String defectId, String observation, String reason, String evidence, String userQuote,
            String observedAt, String requestId) implements BatchOpInput {
    }

    record Link(String kind, String a, String b, String reason, Boolean remove, String requestId)
            implements BatchOpInput {
    }

    record Correct(Long historyId, String reason, String requestId) implements BatchOpInput {
    }
}
