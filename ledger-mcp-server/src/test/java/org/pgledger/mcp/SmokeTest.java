package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;

@McpServerTest
class SmokeTest {

    @Test
    void probes(McpSession session, Scenario scenario) throws IOException {
        Args save = Args.of().with("_hostAttestation", Scenario.AUTHOR).with("entity", "defect")
                .with("reason", Scenario.REASON)
                .with("fields", Args.of().with("project_id", scenario.projectId()).with("title", "t1")
                        .with("category", "ARC").with("severity", "P2").with("behavior", "b").with("root_cause", "r"));
        List<CallToolResult> results = List.of(
                session.call("ledger_whoami", Args.of().with("_hostAttestation", Scenario.AUTHOR).with("author_id", "x")),
                session.call("ledger_whoami", Args.of()),
                session.call("ledger_whoami", Args.of().with("bogus", 1).with("_hostAttestation", Scenario.AUTHOR)),
                session.call("ledger_save", save),
                session.call("ledger_save", Args.of().with("_hostAttestation", Scenario.AUTHOR).with("entity", "defect")
                        .with("reason", "short").with("fields", "{\"project_id\":\"" + scenario.projectId() + "\",\"titel\":1}")),
                session.call("ledger_get", Args.of().with("ids", List.of(scenario.defectId(1)))),
                session.call("ledger_defect", Args.of().with("id", scenario.defectId(1))),
                session.call("ledger_history", Args.of().with("project", scenario.projectId()).with("include_before", true)),
                session.call("ledger_history", Args.of().with("max_rows", "a")),
                session.call("ledger_stats", Args.of()));
        Files.writeString(Path.of("build", "probe.txt"), results.stream().map(r -> r.toString()).reduce("", (a, b) -> a + "\n\n" + b));
        assertThat(session.tools()).hasSize(22);
    }
}
