package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.ReadOutputs;
import org.pgledger.mcp.tool.WriteOutputs;

/** REH3-MCP-01: the tools and the ids of 1.x (a task with a lower-case id, the schemas of the tools). */
@McpServerTest
class IdsOfOneXTest {

    @Test
    @DisplayName("REH3-MCP-01 no input schema of any tool has a pattern for an id (the id arguments are plain strings, so the contract of the tools is unchanged)")
    void noSchemaHasAPattern(McpSession session) {
        for (Tool tool : session.tools()) {
            assertThat(session.mapper().writeValueAsString(tool.inputSchema())).as(tool.name()).doesNotContain("\"pattern\"");
        }
    }

    @Test
    @DisplayName("REH3-MCP-01 a task with the id AGS:A07-a is created, read and edited by that id through the tools; the same id in the other case is NOT_FOUND")
    void anIdOfOneXWorksThroughTheTools(McpSession session, Scenario scenario) {
        String id = scenario.code() + ":A07-a";
        String other = id.toLowerCase(Locale.ROOT);

        WriteOutputs.Saved created = session.ok("ledger_save",
                Calls.Save.create("task", Fields.task(scenario.projectId(), "an id of 1.x"), Scenario.AUTHOR).withId(id),
                WriteOutputs.Saved.class);

        assertThat(created.id()).isEqualTo(id);
        assertThat(session.ok("ledger_task", new Calls.IdOnly(id), ReadOutputs.Task.class).id()).isEqualTo(id);
        WriteOutputs.Saved edited = session.ok("ledger_save",
                Calls.Save.edit("task", id, 1, Fields.titled("edited"), Scenario.AUTHOR), WriteOutputs.Saved.class);
        assertThat(edited.version()).isEqualTo(2);
        ToolError missing = session.failed("ledger_task", new Calls.IdOnly(other));
        assertThat(missing.code()).isEqualTo("NOT_FOUND");
        assertThat(session.failed("ledger_save",
                Calls.Save.edit("task", other, 1, Fields.titled("edited"), Scenario.AUTHOR)).code()).isEqualTo("NOT_FOUND");
    }
}
