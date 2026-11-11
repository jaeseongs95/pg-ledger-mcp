package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.McpTestSettings;
import org.pgledger.mcp.support.RawHttp;
import org.pgledger.mcp.support.RawHttp.Header;
import org.pgledger.mcp.support.ReadCase;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.support.Seed;
import org.pgledger.mcp.support.ToolNames;
import org.pgledger.mcp.tool.ReadOutputs;

/** MCR cases common to the twelve read tools: schemas, the removed SQL tools, the bearer. */
@McpServerTest
class ReadContractTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.ReadCase#all")
    @DisplayName("MCR-001: a read tool declares a closed input without attestation or author, an output schema that "
            + "its result validates against, and refuses an unknown argument")
    void aReadToolKeepsItsContract(ReadCase read, McpSession session, Scenario scenario) {
        Tool tool = session.tool(read.tool());
        SchemaView input = SchemaView.of(session.mapper(), tool.inputSchema());
        Seed seed = Seed.create(session, scenario);

        CallToolResult result = session.call(read.tool(), read.call(seed));
        ValidationResponse validation = McpJsonDefaults.getSchemaValidator().validate(tool.outputSchema(),
                result.structuredContent());
        ToolError unknown = session.failed(read.tool(),
                Args.from(session.mapper(), read.call(seed)).with("unknown_argument", 1));

        assertThat(tool.outputSchema()).isNotNull();
        assertThat(input.propertyNames()).doesNotContain("_hostAttestation");
        assertThat(input.closed()).isTrue();
        assertThat(input.objects()).allMatch(SchemaView::closed);
        assertThat(result.isError()).isFalse();
        assertThat(validation.valid()).as("the result against the output schema: %s", validation.errorMessage())
                .isTrue();
        assertThat(unknown.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(unknown.issues()).extracting(ToolError.Issue::path).contains("unknown_argument");
    }

    @Test
    @DisplayName("MCR-001: author_id is declared by ledger_history only, as the filter of MCR-H05 and not as the "
            + "ignored legacy key of the write tools")
    void authorIdIsOnlyTheHistoryFilter(McpSession session) {
        List<String> declaring = ToolNames.READ.stream()
                .filter(tool -> SchemaView.of(session.mapper(), session.tool(tool).inputSchema()).properties()
                        .containsKey("author_id"))
                .toList();
        SchemaView history = SchemaView.of(session.mapper(), session.tool("ledger_history").inputSchema());

        assertThat(declaring).containsExactly("ledger_history");
        assertThat(history.properties().get("author_id").description()).isEqualTo("Author of the history entry.");
    }

    static Stream<String> removedSqlTools() {
        return Stream.of("execute_sql_reader", "search_objects_reader");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("removedSqlTools")
    @DisplayName("MCR-011: the SQL tools of the old server do not exist: the call is a protocol error and changes "
            + "nothing")
    void theSqlToolsAreGone(String tool, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        ReadOutputs.Stats before = session.ok("ledger_stats", new Calls.ProjectOnly(seed.project()),
                ReadOutputs.Stats.class);

        assertThatThrownBy(() -> session.call(tool, new Calls.Nothing())).isInstanceOf(McpError.class);

        assertThat(session.ok("ledger_stats", new Calls.ProjectOnly(seed.project()), ReadOutputs.Stats.class))
                .isEqualTo(before);
        assertThat(session.tools()).extracting(Tool::name).doesNotContain(tool);
    }

    @Test
    @DisplayName("MCR-011: ledger_codes exists and lists the codes")
    void theCodesToolExists(McpSession session) {
        ReadOutputs.Items<ReadOutputs.CodeRow> codes = session.readItems(
                session.call("ledger_codes", Calls.Codes.of("DEFECT_SEVERITY")), ReadOutputs.CodeRow.class);

        assertThat(session.tools()).extracting(Tool::name).contains("ledger_codes");
        assertThat(codes.items()).extracting(ReadOutputs.CodeRow::code).contains("P1", "P2", "P3");
    }

    /** A request to a read tool with a bearer that must not be accepted. */
    private record Unauthorized(String tool, String bearer, List<Header> headers) {

        @Override
        public String toString() {
            return tool + " with " + bearer;
        }
    }

    static Stream<Unauthorized> unauthorizedRequests() {
        return ToolNames.read().flatMap(tool -> Stream.of(
                new Unauthorized(tool, "no bearer", List.of()),
                new Unauthorized(tool, "a wrong bearer", List.of(Header.bearer(McpTestSettings.TOKEN + "x")))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unauthorizedRequests")
    @DisplayName("MCR-013: without a bearer or with a wrong one every read tool is HTTP 401")
    void aReadToolNeedsTheBearer(Unauthorized request, RawHttp http) {
        RawHttp.Reply reply = http.callToolWithoutSession(request.tool(), new Calls.Nothing(),
                request.headers().toArray(new Header[0]));

        assertThat(reply.status()).isEqualTo(401);
    }
}
