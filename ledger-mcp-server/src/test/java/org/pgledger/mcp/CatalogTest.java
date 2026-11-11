package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.ToolNames;

/** MCC-001: the tools of the server. (The server of this class never gets an extra tool.) */
@McpServerTest
class CatalogTest {

    @Test
    @DisplayName("MCC-001: tools/list holds exactly the 22 tools: 10 write and 12 read")
    void theServerHasTwentyTwoTools(McpSession session) {
        assertThat(session.tools()).extracting(Tool::name).hasSize(22)
                .containsExactlyInAnyOrderElementsOf(ToolNames.all());
    }
}
