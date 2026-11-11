package org.pgledger.mcp.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The arguments of the tools as the tests send them: one record per tool, its components the wire keys (snake case,
 * {@code _hostAttestation} as written), a key left null is left out of the call. Jackson writes them; nothing here
 * is a JSON text. The server declares the same keys as the parameters of its tool methods.
 */
public final class Calls {

    private Calls() {
    }

    /** The {@code fields} object of {@code ledger_save}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fields(String projectId, String title, String category, String severity, String behavior,
            String rootCause, String ownerActorId, String purpose, String successCondition, String originRef,
            String originQuote, String release, String goalLabel, String code, String conditionText) {

        public static Fields defect(String projectId, String title) {
            return new Fields(projectId, title, "ARC", "P2", "behavior of " + title, "cause of " + title, null, null,
                    null, null, null, null, null, null, null);
        }

        public static Fields task(String projectId, String title) {
            return new Fields(projectId, title, null, null, null, null, null, "purpose of " + title,
                    "success of " + title, null, null, null, null, null, null);
        }

        public static Fields condition(String projectId, String release, String code) {
            return new Fields(projectId, null, null, null, null, null, null, null, null, null, null, release, null,
                    code, "condition " + code);
        }

        /** An edit of the title only (an edit does not repeat the project). */
        public static Fields titled(String title) {
            return new Fields(null, title, null, null, null, null, null, null, null, null, null, null, null, null,
                    null);
        }

        public Fields withTitle(String newTitle) {
            return new Fields(projectId, newTitle, category, severity, behavior, rootCause, ownerActorId, purpose,
                    successCondition, originRef, originQuote, release, goalLabel, code, conditionText);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Save(String entity, Fields fields, String reason, String id, Long expectedVersion,
            String requestId, @JsonProperty("_hostAttestation") String hostAttestation, String authorId) {

        public static Save create(String entity, Fields fields, String author) {
            return new Save(entity, fields, Scenario.REASON, null, null, null, author, null);
        }

        public static Save edit(String entity, String id, long version, Fields fields, String author) {
            return new Save(entity, fields, Scenario.REASON, id, version, null, author, null);
        }

        public Save withRequestId(String newRequestId) {
            return new Save(entity, fields, reason, id, expectedVersion, newRequestId, hostAttestation, authorId);
        }

        public Save withAttestation(String author) {
            return new Save(entity, fields, reason, id, expectedVersion, requestId, author, authorId);
        }

        public Save withAuthorId(String legacy) {
            return new Save(entity, fields, reason, id, expectedVersion, requestId, hostAttestation, legacy);
        }

        public Save withId(String newId) {
            return new Save(entity, fields, reason, newId, expectedVersion, requestId, hostAttestation, authorId);
        }
    }

    /** {@code ledger_save} with {@code fields} written as a JSON text, the old form of the contract. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LegacySave(String entity, String fields, String reason, String requestId,
            @JsonProperty("_hostAttestation") String hostAttestation) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Transition(String entity, String id, Long expectedVersion, String to, String reason,
            String evidenceUri, String evidenceSha256, String mergedInto, String requestId,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Transition of(String entity, String id, long version, String to, String author) {
            return new Transition(entity, id, version, to, Scenario.REASON, null, null, null, null, author);
        }

        public Transition withEvidence(String uri) {
            return new Transition(entity, id, expectedVersion, to, reason, uri, evidenceSha256, mergedInto,
                    requestId, hostAttestation);
        }

        public Transition withAttestation(String author) {
            return new Transition(entity, id, expectedVersion, to, reason, evidenceUri, evidenceSha256, mergedInto,
                    requestId, author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecordFix(String defectId, String repo, String commitSha, String reason, String branch,
            String ciRef, String ciConclusion, String ciCompletedAt, Boolean markSourceFixed, String requestId,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static RecordFix of(String defectId, String commitSha, String author) {
            return new RecordFix(defectId, "repo/e2e", commitSha, Scenario.REASON, null, null, null, null, null,
                    null, author);
        }

        public RecordFix withAttestation(String author) {
            return new RecordFix(defectId, repo, commitSha, reason, branch, ciRef, ciConclusion, ciCompletedAt,
                    markSourceFixed, requestId, author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RecordCase(String defectId, String observation, String reason, String evidence, String userQuote,
            String observedAt, String requestId, @JsonProperty("_hostAttestation") String hostAttestation) {

        public static RecordCase of(String defectId, String observation, String author) {
            return new RecordCase(defectId, observation, Scenario.REASON, null, null, null, null, author);
        }

        public RecordCase withAttestation(String author) {
            return new RecordCase(defectId, observation, reason, evidence, userQuote, observedAt, requestId, author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Link(String kind, String a, String b, String reason, Boolean remove, String requestId,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Link of(String kind, String a, String b, String author) {
            return new Link(kind, a, b, Scenario.REASON, null, null, author);
        }

        public Link withAttestation(String author) {
            return new Link(kind, a, b, reason, remove, requestId, author);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Correct(Long historyId, String reason, String requestId,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Correct of(long historyId, String author) {
            return new Correct(historyId, Scenario.REASON, null, author);
        }

        public Correct withAttestation(String author) {
            return new Correct(historyId, reason, requestId, author);
        }
    }

    /** One operation of {@code ledger_batch}: {@code op} names the single tool, the other keys are its keys. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Op(String op, String entity, String id, Long expectedVersion, String to, String reason,
            Fields fields, String evidenceUri, String defectId, String repo, String commitSha, String observation,
            String kind, String a, String b, Long historyId, String requestId) {

        public static Op save(String entity, Fields fields) {
            return new Op("save", entity, null, null, null, Scenario.REASON, fields, null, null, null, null, null,
                    null, null, null, null, null);
        }

        public static Op saveWithId(String entity, String id, Fields fields) {
            return new Op("save", entity, id, null, null, Scenario.REASON, fields, null, null, null, null, null,
                    null, null, null, null, null);
        }

        public static Op transition(String entity, String id, long version, String to) {
            return new Op("transition", entity, id, version, to, Scenario.REASON, null, null, null, null, null,
                    null, null, null, null, null, null);
        }

        public static Op recordFix(String defectId, String commitSha) {
            return new Op("record_fix", null, null, null, null, Scenario.REASON, null, null, defectId, "repo/e2e",
                    commitSha, null, null, null, null, null, null);
        }

        public static Op recordCase(String defectId, String observation) {
            return new Op("record_case", null, null, null, null, Scenario.REASON, null, null, defectId, null, null,
                    observation, null, null, null, null, null);
        }

        public static Op link(String kind, String a, String b) {
            return new Op("link", null, null, null, null, Scenario.REASON, null, null, null, null, null, null, kind,
                    a, b, null, null);
        }

        public static Op correct(long historyId) {
            return new Op("correct", null, null, null, null, Scenario.REASON, null, null, null, null, null, null,
                    null, null, null, historyId, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Batch(List<Op> ops, String requestId, @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Batch of(String author, Op... ops) {
            return new Batch(List.of(ops), null, author);
        }

        public Batch withRequestId(String newRequestId) {
            return new Batch(ops, newRequestId, hostAttestation);
        }
    }

    /** {@code ledger_batch} with {@code ops} written as a JSON text, the old form of the contract. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LegacyBatch(String ops, String requestId,
            @JsonProperty("_hostAttestation") String hostAttestation) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Review(String entity, String id, Long expectedVersion, String to, String reason,
            String evidenceUri, String evidenceSha256, String requestId,
            @JsonProperty("_hostAttestation") String hostAttestation) {

        public static Review of(String entity, String id, long version, String to, String author) {
            return new Review(entity, id, version, to, Scenario.REASON, Scenario.EVIDENCE, null, null, author);
        }

        public Review withoutEvidence() {
            return new Review(entity, id, expectedVersion, to, reason, null, evidenceSha256, requestId,
                    hostAttestation);
        }

        public Review withAttestation(String author) {
            return new Review(entity, id, expectedVersion, to, reason, evidenceUri, evidenceSha256, requestId,
                    author);
        }
    }

    /** {@code ledger_whoami}: the only key besides the legacy author id is the attestation. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Whoami(@JsonProperty("_hostAttestation") String hostAttestation, String authorId) {

        public static Whoami as(String author) {
            return new Whoami(author, null);
        }

        public static Whoami withoutAttestation() {
            return new Whoami(null, null);
        }
    }

    // ---------------------------------------------------------------------------------------------------- reads

    /** A call without arguments. */
    public record Nothing() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ids(List<String> ids) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IdOnly(String id) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectOnly(String project) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Codes(String group, Boolean includeInactive, Integer maxRows) {

        public static Codes of(String group) {
            return new Codes(group, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record History(String project, Boolean includeBefore, Integer maxRows) {

        public static History of(String project) {
            return new History(project, null, null);
        }
    }
}
