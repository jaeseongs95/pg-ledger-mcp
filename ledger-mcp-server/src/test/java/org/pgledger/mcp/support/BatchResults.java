package org.pgledger.mcp.support;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.pgledger.application.write.WriteStatus;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The result of {@code ledger_batch} read back. The server writes the result of each operation as the record of its
 * single tool ({@code WriteOutputs.Saved}, {@code Linked}, ...) without a type tag, and a sealed interface cannot be
 * read back from JSON without one, so the tests read an entry as the union of the keys of those records: a key the
 * operation does not have is null.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BatchResults(@JsonProperty("status") WriteStatus status, @JsonProperty("count") int count,
        @JsonProperty("results") List<Entry> results) {

    public BatchResults {
        results = List.copyOf(results);
    }

    /** One operation's result. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Entry(WriteStatus status, String entity, String id, Long version, Long historyId, String author,
            String login, String from, String to, String defect, Long fixId, String defectStatus,
            String ownerActorId, Long transitionHistoryId, Long caseId, Boolean removed, Long corrects) {
    }
}
