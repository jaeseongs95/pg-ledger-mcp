package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.CodeListing;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.support.Seed;
import org.pgledger.mcp.support.ToolNames;
import org.pgledger.mcp.tool.ReadOutputs.CodeRow;

/** MCW cases about what the write tools declare: names, schemas, enumerations, descriptions. */
@McpServerTest
class WriteSchemaTest {

    private static final List<String> FIXED_LINK_KINDS = List.of("defect_task", "condition_task", "condition_defect");

    private static SchemaView input(McpSession session, String tool) {
        return SchemaView.of(session.mapper(), session.tool(tool).inputSchema());
    }

    @Test
    @DisplayName("MCW-001: tools/list holds the ten write tools and no tool that executes SQL or searches objects")
    void listHoldsTheWriteToolsAndNoSqlTool(McpSession session) {
        List<String> names = session.tools().stream().map(Tool::name).toList();

        assertThat(names).containsAll(ToolNames.WRITE)
                .noneMatch(name -> name.startsWith("execute_sql") || name.startsWith("search_objects"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.ToolNames#write")
    @DisplayName("MCW-002: _hostAttestation is an optional string, author_id is a declared legacy key that is ignored, "
            + "every object of the schema is closed")
    void attestationIsOptionalAndTheSchemaIsClosed(String tool, McpSession session) {
        SchemaView schema = input(session, tool);

        assertThat(schema.properties().get("_hostAttestation").type()).isEqualTo("string");
        assertThat(schema.required()).doesNotContain("_hostAttestation", "author_id");
        assertThat(schema.properties().get("author_id").description()).contains("ignored");
        assertThat(schema.closed()).isTrue();
        assertThat(schema.objects()).allMatch(SchemaView::closed);
    }

    @Test
    @DisplayName("MCW-002 / MCW-PRN-01: no object of a write schema is a map of free keys: since the batch binds the fields "
            + "of a save as the same record as ledger_save, the fields of a batch operation list their keys too")
    void noObjectIsAMapOfFreeKeys(McpSession session) {
        List<String> withOpenObject = ToolNames.WRITE.stream()
                .filter(tool -> input(session, tool).nodes().anyMatch(node -> "object".equals(node.type())
                        && node.properties().isEmpty()))
                .toList();

        assertThat(withOpenObject).isEmpty();
    }

    @Test
    @DisplayName("MCW-014: ledger_transition offers the author-channel states only, ledger_review all of them")
    void transitionOffersNoVerificationState(McpSession session) {
        List<String> author = input(session, "ledger_transition").properties().get("to").allowed();
        List<String> reviewer = input(session, "ledger_review").properties().get("to").allowed();

        assertThat(author).contains("OPEN", "SOURCE_FIXED", "PLANNED", "REPORTED_DONE")
                .doesNotContain("VERIFIED", "MET", "NOT_MET");
        assertThat(reviewer).contains("VERIFIED", "MET", "NOT_MET");
    }

    @Test
    @DisplayName("MCW-014: sending VERIFIED to ledger_transition is VALIDATION_FAILED naming the allowed states")
    void transitionRefusesVerified(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        List<String> allowed = input(session, "ledger_transition").properties().get("to").allowed();

        ToolError error = session.failed("ledger_transition",
                Calls.Transition.of("defect", seed.fixed(), 2, "VERIFIED", Scenario.REVIEWER));

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("to", "unknown value VERIFIED", allowed));
    }

    @Test
    @DisplayName("MCW-016: the enumerations of entity, ci_conclusion and link kind")
    void enumerationsOfTheClosedSets(McpSession session) {
        List<String> activeRelationKinds = CodeListing.codes(CodeListing.active(session, "RELATION_KIND"));

        assertThat(input(session, "ledger_save").properties().get("entity").allowed())
                .containsExactly("defect", "task", "condition");
        assertThat(input(session, "ledger_transition").properties().get("entity").allowed())
                .containsExactly("defect", "task", "condition");
        assertThat(input(session, "ledger_review").properties().get("entity").allowed())
                .containsExactly("defect", "task", "condition");
        assertThat(input(session, "ledger_record_fix").properties().get("ci_conclusion").allowed())
                .containsExactly("success", "failure", "cancelled");
        assertThat(input(session, "ledger_link").properties().get("kind").allowed())
                .containsExactlyElementsOf(java.util.stream.Stream.concat(FIXED_LINK_KINDS.stream(),
                        activeRelationKinds.stream()).toList());
    }

    @Test
    @DisplayName("MCW-017: the description of ledger_review says CI is optional metadata, that the reviewer runs the "
            + "program and where to find the fixing session")
    void reviewDescriptionStatesTheReviewRules(McpSession session) {
        String description = session.tool("ledger_review").description();

        assertThat(description).contains("CI is optional metadata").contains("the reviewer runs the actual program")
                .contains("fixed_by of ledger_defect");
    }

    @Test
    @DisplayName("MCW-023: the fields of ledger_save are typed per kind; category and severity carry the active codes "
            + "as enumeration and description")
    void saveFieldsCarryTheActiveCodes(McpSession session) {
        SchemaView fields = input(session, "ledger_save").at("fields").orElseThrow();
        List<CodeRow> categories = CodeListing.active(session, "DEFECT_CATEGORY");
        List<CodeRow> severities = CodeListing.active(session, "DEFECT_SEVERITY");

        assertThat(fields.propertyNames()).containsExactly("behavior", "category", "code", "condition_text",
                "goal_label", "origin_quote", "origin_ref", "owner_actor_id", "project_id", "purpose", "release",
                "root_cause", "severity", "success_condition", "title");
        assertThat(fields.properties().values()).allMatch(property -> "string".equals(property.type()));
        assertThat(fields.at("category").orElseThrow().allowed()).isEqualTo(CodeListing.codes(categories))
                .containsExactly("ARC");
        assertThat(fields.at("severity").orElseThrow().allowed()).isEqualTo(CodeListing.codes(severities))
                .doesNotContain("UNRATED");
        assertThat(fields.at("category").orElseThrow().description())
                .contains(categories.stream().map(CodeListing::line).toList().toArray(new String[0]));
        assertThat(fields.at("severity").orElseThrow().description())
                .contains(severities.stream().map(CodeListing::line).toList().toArray(new String[0]));
    }
}
