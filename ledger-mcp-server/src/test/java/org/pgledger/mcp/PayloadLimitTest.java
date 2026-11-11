package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.McpTestSettings;
import org.pgledger.mcp.support.ProblemBody;
import org.pgledger.mcp.support.RawHttp;
import org.pgledger.mcp.support.RawHttp.Header;
import org.pgledger.mcp.support.RawHttp.Reply;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.WriteOutputs.Whoami;
import org.springframework.test.context.TestPropertySource;

/**
 * The request size limit. The server of this class has its own context because its limit is small (4 KB); the
 * calls of the other classes stay far below the default.
 */
@McpServerTest
@TestPropertySource(properties = "ledger.mcp.max-request-size=4KB")
class PayloadLimitTest {

    private static final int OVERSIZE = 10_000;

    private static Calls.Save oversizeSave(Scenario scenario) {
        return Calls.Save.create("defect", Fields.defect(scenario.projectId(), "x".repeat(OVERSIZE)),
                Scenario.AUTHOR);
    }

    @Test
    @DisplayName("MCW-022: a body above the configured limit is HTTP 413 PAYLOAD_TOO_LARGE, nothing is written, the "
            + "server keeps serving")
    void anOversizeBodyIsRefused(RawHttp http, McpSession session, Scenario scenario) {
        Reply reply = http.callToolWithoutSession("ledger_save", oversizeSave(scenario),
                Header.bearer(McpTestSettings.TOKEN));

        assertThat(reply.status()).isEqualTo(413);
        assertThat(session.mapper().readValue(reply.body(), ProblemBody.class))
                .isEqualTo(new ProblemBody(413, "The request body is too large.", "PAYLOAD_TOO_LARGE"));
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
        assertThat(session.ok("ledger_whoami", Calls.Whoami.as(Scenario.AUTHOR), Whoami.class).author())
                .isEqualTo(Scenario.AUTHOR);
    }

    @Test
    @DisplayName("MCC-019: an oversize frame inside a session is a client error, writes nothing and the session "
            + "keeps serving")
    void anOversizeFrameInASessionIsAnError(RawHttp http, McpSession session, Scenario scenario) {
        Header bearer = Header.bearer(McpTestSettings.TOKEN);
        String sessionId = http.openSession(bearer);

        Reply oversize = http.callTool(sessionId, "ledger_save", oversizeSave(scenario), bearer);
        Reply after = http.callTool(sessionId, "ledger_whoami", Calls.Whoami.as(Scenario.AUTHOR), bearer);

        assertThat(oversize.status()).isEqualTo(413);
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
        assertThat(after.status()).isEqualTo(200);
        assertThat(session.mapper().convertValue(http.toolResult(after).structuredContent(), Whoami.class).author())
                .isEqualTo(Scenario.AUTHOR);
        assertThat(http.response(after).error()).isNull();
    }
}
