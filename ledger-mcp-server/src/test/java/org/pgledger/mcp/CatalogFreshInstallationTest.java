package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.support.McpFreshInstallationTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.support.ToolNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/** MCC-013: a fresh installation has no defect category yet; the tools are there and say how to add one. */
@McpFreshInstallationTest
class CatalogFreshInstallationTest {

    @Test
    @DisplayName("MCC-013: with no active category the tools are still listed and the category text says how to add one")
    void theEmptyCategoryGroupExplainsItself(McpSession session, @Autowired MessageSource messages) {
        SchemaView category = SchemaView.of(session.mapper(), session.tool("ledger_save").inputSchema())
                .at("fields.category").orElseThrow();
        String expected = messages.getMessage("catalog.empty", new Object[] {"DEFECT_CATEGORY"}, Locale.ENGLISH);

        assertThat(session.tools()).extracting(tool -> tool.name()).containsExactlyInAnyOrderElementsOf(ToolNames.all());
        assertThat(category.description()).contains(expected).contains("ledger-cli code-set DEFECT_CATEGORY");
        assertThat(category.allowed()).isEmpty();
    }
}
