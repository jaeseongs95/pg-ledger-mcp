package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.mcp.catalog.ToolCatalog;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.CodeChanges;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.ProbeTools;
import org.pgledger.mcp.support.ProbeTools.Pong;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.tool.ToolAdapter;
import org.pgledger.mcp.tool.WriteOutputs.Whoami;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

/**
 * MCC cases that change the running server: a tool added at run time, the database that is gone for a poll, tools
 * that fail. The server of this class is a context of its own because it keeps the tools that the cases add.
 */
@McpServerTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "ledger.test.context=runtime-tools")
class RuntimeToolTest {

    private static final Duration NOTIFICATION = Duration.ofSeconds(5);

    @Test
    @DisplayName("MCC-002: a tool added at run time is announced with list_changed, listed and callable")
    void aToolAddedAtRunTimeIsAnnounced(McpSession session, ApplicationContext context) {
        int before = session.tools().size();

        context.getBean(ToolCatalog.class).register(new ProbeTools.Working());
        List<Tool> announced = session.awaitListChange(NOTIFICATION);

        assertThat(announced).hasSize(before + 1);
        assertThat(session.tools()).hasSize(before + 1).extracting(Tool::name).contains("probe_ping");
        assertThat(session.ok("probe_ping", new Calls.Nothing(), Pong.class)).isEqualTo(new Pong("pong"));
        assertThat(SchemaView.of(session.mapper(), session.tool("probe_ping").inputSchema()).closed()).isTrue();
    }

    /** A failing tool, the adapter that holds it, the token of the error and what must not reach the model. */
    private record Failing(String tool, ToolAdapter adapter, String token, List<String> secrets) {

        @Override
        public String toString() {
            return token;
        }
    }

    static Stream<Failing> failingTools() {
        return Stream.of(
                new Failing("probe_defect", new ProbeTools.Defective(), "INTERNAL_ERROR",
                        List.of("boom", "SELECT", "vault", "IllegalStateException")),
                new Failing("probe_integrity", new ProbeTools.Violating(), "INTEGRITY_VIOLATION",
                        List.of("insert into", "HISTORY_PK", "DataIntegrityViolationException", "statement")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingTools")
    @DisplayName("MCC-020: an unexpected exception and an integrity error are INTERNAL_ERROR and "
            + "INTEGRITY_VIOLATION without SQL or class names, and are logged once")
    void anUnexpectedFailureIsConvertedOnce(Failing failing, McpSession session, ApplicationContext context,
            CapturedOutput output) {
        context.getBean(ToolCatalog.class).register(failing.adapter());
        session.awaitListChange(NOTIFICATION);

        CallToolResult result = session.call(failing.tool(), new Calls.Nothing());
        ToolError error = session.error(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.code()).isEqualTo(failing.token());
        assertThat(McpSession.text(result)).isEqualTo(failing.token() + ": " + error.message());
        failing.secrets().forEach(secret -> assertThat(McpSession.text(result)).doesNotContain(secret));
        assertThat(StringUtils.countOccurrencesOf(output.getAll(), "a tool call failed with " + failing.token()))
                .isEqualTo(1);
        assertThat(StringUtils.countOccurrencesOf(output.getAll(), failing.secrets().getFirst())).isEqualTo(1);
    }

    @Test
    @DisplayName("MCC-010: the database is gone for a poll, then back: the error is logged, the server keeps "
            + "serving, a later change is still announced")
    void aPollWithoutDatabaseIsSurvived(McpSession session, ApplicationContext context, CapturedOutput output) {
        JdbcClient database = JdbcClient.create(context.getBean(DataSource.class));

        database.sql("ALTER TABLE code_revision RENAME TO code_revision_away").update();
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(output.getAll()).contains("a scheduled task failed"));
        Whoami during = session.ok("ledger_whoami", Calls.Whoami.as("claude:nobody"), Whoami.class);
        database.sql("ALTER TABLE code_revision_away RENAME TO code_revision").update();
        context.getBean(CommonCodeService.class).set(CodeChanges.active(CodeChanges.CATEGORY, "AFTER_OUTAGE"));
        List<Tool> announced = session.awaitListChange(NOTIFICATION);

        assertThat(during.author()).isEqualTo("claude:nobody");
        assertThat(announced).extracting(Tool::name).contains("ledger_save");
        assertThat(SchemaView.of(session.mapper(), session.tool("ledger_save").inputSchema())
                .at("fields.category").orElseThrow().allowed()).contains("AFTER_OUTAGE");
    }
}
