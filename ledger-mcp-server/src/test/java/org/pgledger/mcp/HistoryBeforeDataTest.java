package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
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

    /** The defect row before the edit, read with snake_case keys: a camelCase key in the response leaves these null. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record BeforeDefect(String id, String projectId, String title, String category, String severity, String status,
            String mergedInto, String behavior, String rootCause, String ownerActorId, long caseSeq, long version,
            Instant createdAt, Instant updatedAt, String createdBy) {
    }

    /** The part of a history row this case looks at. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Row(long id, String op, BeforeDefect beforeData) {
    }

    @Test
    @DisplayName("MCR-H13: the before_data of an EDIT row has snake_case keys and holds the row before the edit; "
            + "the CREATE row has none")
    void beforeDataIsWrittenWithTheKeysOfTheContract(McpSession session, Scenario scenario) {
        Saved created = session.ok("ledger_save", Calls.Save.create("defect",
                Fields.defect(scenario.projectId(), "first title"), Scenario.AUTHOR), Saved.class);
        session.ok("ledger_save", Calls.Save.edit("defect", created.id(), created.version(),
                Fields.titled("second title"), Scenario.AUTHOR), Saved.class);

        List<Row> rows = session.readItems(session.call("ledger_history",
                new Calls.History(scenario.projectId(), true, null)), Row.class).items();

        BeforeDefect expected = new BeforeDefect(created.id(), scenario.projectId(), "first title", "ARC", "P2",
                "OPEN", null, "behavior of first title", "cause of first title", null, 0L, 1L, null, null,
                Scenario.AUTHOR);
        assertThat(rows).extracting(Row::op).containsExactly("CREATE", "EDIT");
        assertThat(rows.getFirst().beforeData()).isNull();
        assertThat(rows.getLast().beforeData()).usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt").isEqualTo(expected);
        assertThat(rows.getLast().beforeData().createdAt()).isNotNull();
        assertThat(rows.getLast().beforeData().updatedAt()).isNotNull();
    }
}
