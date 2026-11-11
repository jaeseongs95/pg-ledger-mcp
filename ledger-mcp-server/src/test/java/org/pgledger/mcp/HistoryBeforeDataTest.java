package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.WriteOutputs.Saved;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The {@code before_data} of {@code ledger_history} through the MCP tools: its keys are snake_case. */
@McpServerTest
class HistoryBeforeDataTest {

    /** The defect row before the edit, read with its snake_case keys: a key that is not on the wire stays null. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record BeforeDefect(String id, String projectId, String title, String category, String severity, String status,
            String mergedInto, String behavior, String rootCause, String ownerActorId, Long caseSeq, Long version,
            Instant createdAt, Instant updatedAt, String createdBy) {
    }

    /** The part of a history row this case looks at. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Row(long id, String op, BeforeDefect beforeData) {
    }

    /** The same row read with the camelCase keys: they must not be on the wire. */
    record CamelBefore(@JsonProperty("projectId") String projectId, @JsonProperty("rootCause") String rootCause,
            @JsonProperty("caseSeq") Long caseSeq, @JsonProperty("createdBy") String createdBy) {
    }

    /** A history row of which only the camelCase keys of before_data are read. */
    record CamelRow(String op, @JsonProperty("before_data") CamelBefore beforeData) {
    }

    @Test
    @DisplayName("MCR-H13: the before_data of an EDIT row has snake_case keys and holds the row before the edit; "
            + "the CREATE row has none")
    void beforeDataIsWrittenWithTheKeysOfTheContract(McpSession session, Scenario scenario) {
        Saved created = session.ok("ledger_save", Calls.Save.create("defect",
                Fields.defect(scenario.projectId(), "first title"), Scenario.AUTHOR), Saved.class);
        session.ok("ledger_save", Calls.Save.edit("defect", created.id(), created.version(),
                Fields.titled("second title"), Scenario.AUTHOR), Saved.class);

        CallToolResult result = session.call("ledger_history", new Calls.History(scenario.projectId(), true, null));
        List<Row> rows = session.readItems(result, Row.class).items();
        List<CamelRow> camel = session.readItems(result, CamelRow.class).items();

        BeforeDefect expected = new BeforeDefect(created.id(), scenario.projectId(), "first title", "ARC", "P2",
                "OPEN", null, "behavior of first title", "cause of first title", null, 0L, 1L, null, null,
                Scenario.AUTHOR);
        assertThat(rows).extracting(Row::op).containsExactly("CREATE", "EDIT");
        assertThat(rows.getFirst().beforeData()).isNull();
        assertThat(rows.getLast().beforeData()).usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt").isEqualTo(expected);
        assertThat(rows.getLast().beforeData().createdAt()).isNotNull();
        assertThat(rows.getLast().beforeData().updatedAt()).isNotNull();
        assertThat(camel.getLast().beforeData()).isEqualTo(new CamelBefore(null, null, null, null));
    }

    /** The part of a history row this case looks at: the changes as the wire shows them, field to [before, after]. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ChangeRow(String op, Map<String, List<String>> changes) {
    }

    @Test
    @DisplayName("MCR-H14: the changes of a history row are written with the snake_case field names and as "
            + "[before, after] pairs")
    void changesAreWrittenWithTheKeysOfTheContract(McpSession session, Scenario scenario) {
        Saved created = session.ok("ledger_save", Calls.Save.create("defect",
                Fields.defect(scenario.projectId(), "first title"), Scenario.AUTHOR), Saved.class);
        Fields edit = new Fields(null, "second title", null, null, null, "second cause", null, null, null, null,
                null, null, null, null, null);
        session.ok("ledger_save", Calls.Save.edit("defect", created.id(), created.version(), edit,
                Scenario.AUTHOR), Saved.class);

        CallToolResult result = session.call("ledger_history", new Calls.History(scenario.projectId(), false, null));
        List<ChangeRow> rows = session.readItems(result, ChangeRow.class).items();

        assertThat(rows).extracting(ChangeRow::op).containsExactly("CREATE", "EDIT");
        assertThat(rows.getFirst().changes()).containsKeys("title", "root_cause", "project_id")
                .doesNotContainKeys("rootCause", "projectId");
        assertThat(rows.getLast().changes()).containsOnlyKeys("title", "root_cause");
        assertThat(rows.getLast().changes().get("title")).containsExactly("first title", "second title");
        assertThat(rows.getLast().changes().get("root_cause")).containsExactly("cause of first title",
                "second cause");
    }
}
