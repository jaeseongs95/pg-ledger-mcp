package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.pgledger.application.write.BatchResult;
import org.pgledger.application.write.WhoamiService;
import org.pgledger.application.write.WriteResult;
import org.pgledger.application.write.WriteStatus;

/**
 * The results of the write tools as they go on the wire. The component names are the keys of the old function
 * results ({@code status}, {@code entity}, {@code id}, {@code version}, {@code history_id}, ...), so a client that
 * read those keeps working; there is no wrapper around them.
 */
public final class WriteOutputs {

    private WriteOutputs() {
    }

    /** One result of a write; a batch lists one per operation. */
    public sealed interface Result permits Saved, Transitioned, FixRecorded, CaseRecorded, Linked, Corrected {
    }

    public record Saved(@JsonProperty("status") WriteStatus status, @JsonProperty("entity") String entity,
            @JsonProperty("id") String id, @JsonProperty("version") long version,
            @JsonProperty("history_id") long historyId, @JsonProperty("author") String author,
            @JsonProperty("login") String login) implements Result {
    }

    public record Transitioned(@JsonProperty("status") WriteStatus status, @JsonProperty("entity") String entity,
            @JsonProperty("id") String id, @JsonProperty("from") String from, @JsonProperty("to") String to,
            @JsonProperty("version") long version, @JsonProperty("history_id") long historyId,
            @JsonProperty("author") String author, @JsonProperty("login") String login) implements Result {
    }

    public record FixRecorded(@JsonProperty("status") WriteStatus status, @JsonProperty("defect") String defect,
            @JsonProperty("fix_id") long fixId, @JsonProperty("defect_status") String defectStatus,
            @JsonProperty("owner_actor_id") String ownerActorId, @JsonProperty("version") long version,
            @JsonProperty("history_id") long historyId,
            @JsonProperty("transition_history_id") Long transitionHistoryId,
            @JsonProperty("author") String author, @JsonProperty("login") String login) implements Result {
    }

    public record CaseRecorded(@JsonProperty("status") WriteStatus status, @JsonProperty("entity") String entity,
            @JsonProperty("id") String id, @JsonProperty("case_id") long caseId,
            @JsonProperty("defect") String defect, @JsonProperty("history_id") long historyId,
            @JsonProperty("author") String author, @JsonProperty("login") String login) implements Result {
    }

    public record Linked(@JsonProperty("status") WriteStatus status, @JsonProperty("entity") String entity,
            @JsonProperty("id") String id, @JsonProperty("removed") boolean removed,
            @JsonProperty("history_id") long historyId, @JsonProperty("author") String author,
            @JsonProperty("login") String login) implements Result {
    }

    public record Corrected(@JsonProperty("status") WriteStatus status, @JsonProperty("corrects") long corrects,
            @JsonProperty("history_id") long historyId, @JsonProperty("author") String author,
            @JsonProperty("login") String login) implements Result {
    }

    public record Batched(@JsonProperty("status") WriteStatus status, @JsonProperty("count") int count,
            @JsonProperty("results") List<Result> results) {

        public Batched {
            results = List.copyOf(results);
        }
    }

    /** One live grant of {@code ledger_whoami}. */
    public record Grant(@JsonProperty("project") String project, @JsonProperty("scope") String scope,
            @JsonProperty("expires_at") Instant expiresAt) {
    }

    /** The answer of {@code ledger_whoami}; {@code session_user} is the login of the channel. */
    public record Whoami(@JsonProperty("session_user") String sessionUser, @JsonProperty("author") String author,
            @JsonProperty("author_format_ok") boolean authorFormatOk,
            @JsonProperty("can_verify_login") boolean canVerifyLogin, @JsonProperty("grants") List<Grant> grants) {

        public Whoami {
            grants = List.copyOf(grants);
        }
    }

    public static Saved saved(WriteResult.SaveResult r) {
        return new Saved(r.status(), r.entity(), r.id(), r.version(), r.historyId(), r.author(), r.login());
    }

    public static Transitioned transitioned(WriteResult.TransitionResult r) {
        return new Transitioned(r.status(), r.entity(), r.id(), r.from(), r.to(), r.version(), r.historyId(),
                r.author(), r.login());
    }

    public static FixRecorded fixRecorded(WriteResult.RecordFixResult r) {
        return new FixRecorded(r.status(), r.defect(), r.fixId(), r.defectStatus(), r.ownerActorId(), r.version(),
                r.historyId(), r.transitionHistoryId(), r.author(), r.login());
    }

    public static CaseRecorded caseRecorded(WriteResult.RecordCaseResult r) {
        return new CaseRecorded(r.status(), r.entity(), r.id(), r.caseId(), r.defect(), r.historyId(), r.author(),
                r.login());
    }

    public static Linked linked(WriteResult.LinkResult r) {
        return new Linked(r.status(), r.entity(), r.id(), r.removed(), r.historyId(), r.author(), r.login());
    }

    public static Corrected corrected(WriteResult.CorrectResult r) {
        return new Corrected(r.status(), r.corrects(), r.historyId(), r.author(), r.login());
    }

    /** The wire form of any write result (the results of a batch). */
    public static Result of(WriteResult result) {
        return switch (result) {
            case WriteResult.SaveResult r -> saved(r);
            case WriteResult.TransitionResult r -> transitioned(r);
            case WriteResult.RecordFixResult r -> fixRecorded(r);
            case WriteResult.RecordCaseResult r -> caseRecorded(r);
            case WriteResult.LinkResult r -> linked(r);
            case WriteResult.CorrectResult r -> corrected(r);
        };
    }

    public static Batched of(BatchResult result) {
        return new Batched(result.status(), result.count(), result.results().stream().map(WriteOutputs::of).toList());
    }

    public static Whoami of(WhoamiService.Whoami whoami) {
        return new Whoami(whoami.login(), whoami.author(), whoami.authorFormatOk(), whoami.canVerifyLogin(),
                whoami.grants().stream().map(g -> new Grant(g.project(), g.scope(), g.expiresAt())).toList());
    }
}
