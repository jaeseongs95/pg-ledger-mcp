package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.pgledger.application.write.BatchOpInput;

/**
 * One operation of {@code ledger_batch} as it arrives on the wire: {@code op} picks the variant, the other keys are
 * the keys of the single tool. The component names are the wire keys; {@link #toInput()} hands the operation to the
 * batch service.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BatchOp.Save.class, name = "save"),
    @JsonSubTypes.Type(value = BatchOp.Transition.class, name = "transition"),
    @JsonSubTypes.Type(value = BatchOp.RecordFix.class, name = "record_fix"),
    @JsonSubTypes.Type(value = BatchOp.RecordCase.class, name = "record_case"),
    @JsonSubTypes.Type(value = BatchOp.Link.class, name = "link"),
    @JsonSubTypes.Type(value = BatchOp.Correct.class, name = "correct")})
public sealed interface BatchOp {

    BatchOpInput toInput();

    record Save(@JsonProperty("entity") String entity, @JsonProperty("fields") SaveFields fields,
            @JsonProperty("reason") String reason, @JsonProperty("id") String id,
            @JsonProperty("expected_version") Long expectedVersion, @JsonProperty("request_id") String requestId)
            implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.Save(entity, fields == null ? null : fields.toInput(), reason, id, expectedVersion,
                    requestId);
        }
    }

    record Transition(@JsonProperty("entity") String entity, @JsonProperty("id") String id,
            @JsonProperty("expected_version") Long expectedVersion, @JsonProperty("to") String to,
            @JsonProperty("reason") String reason, @JsonProperty("evidence_uri") String evidenceUri,
            @JsonProperty("evidence_sha256") String evidenceSha256, @JsonProperty("merged_into") String mergedInto,
            @JsonProperty("request_id") String requestId) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.Transition(entity, id, expectedVersion, to, reason, evidenceUri, evidenceSha256,
                    mergedInto, requestId);
        }
    }

    record RecordFix(@JsonProperty("defect_id") String defectId, @JsonProperty("repo") String repo,
            @JsonProperty("commit_sha") String commitSha, @JsonProperty("reason") String reason,
            @JsonProperty("branch") String branch, @JsonProperty("ci_ref") String ciRef,
            @JsonProperty("ci_conclusion") String ciConclusion,
            @JsonProperty("ci_completed_at") String ciCompletedAt,
            @JsonProperty("mark_source_fixed") Boolean markSourceFixed,
            @JsonProperty("request_id") String requestId) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.RecordFix(defectId, repo, commitSha, reason, branch, ciRef, ciConclusion,
                    ciCompletedAt, markSourceFixed, requestId);
        }
    }

    record RecordCase(@JsonProperty("defect_id") String defectId, @JsonProperty("observation") String observation,
            @JsonProperty("reason") String reason, @JsonProperty("evidence") String evidence,
            @JsonProperty("user_quote") String userQuote, @JsonProperty("observed_at") String observedAt,
            @JsonProperty("request_id") String requestId) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.RecordCase(defectId, observation, reason, evidence, userQuote, observedAt,
                    requestId);
        }
    }

    record Link(@JsonProperty("kind") String kind, @JsonProperty("a") String a, @JsonProperty("b") String b,
            @JsonProperty("reason") String reason, @JsonProperty("remove") Boolean remove,
            @JsonProperty("request_id") String requestId) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.Link(kind, a, b, reason, remove, requestId);
        }
    }

    record Correct(@JsonProperty("history_id") Long historyId, @JsonProperty("reason") String reason,
            @JsonProperty("request_id") String requestId) implements BatchOp {

        @Override
        public BatchOpInput toInput() {
            return new BatchOpInput.Correct(historyId, reason, requestId);
        }
    }
}
