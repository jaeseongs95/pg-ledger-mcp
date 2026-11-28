// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.mcp.error.ToolError;
import org.veridger.mcp.support.CodeListing;
import org.veridger.mcp.support.Calls;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.Scenario;
import org.veridger.mcp.support.SchemaView;
import org.veridger.mcp.support.Seed;
import org.veridger.mcp.support.ToolNames;
import org.veridger.mcp.tool.ReadOutputs.CodeRow;

/** MCW cases about what the write tools declare: names, schemas, enumerations, descriptions. */
@McpServerTest
class WriteSchemaTest {

    private static final List<String> FIXED_LINK_KINDS = List.of("issue_task", "issue_spec", "spec_task",
            "criterion_task", "criterion_issue", "criterion_spec");

    private static SchemaView input(McpSession session, String tool) {
        return SchemaView.of(session.mapper(), session.tool(tool).inputSchema());
    }

    @Test
    @DisplayName("MCW-001: tools/list holds the 13 write tools and no tool that executes SQL or searches objects")
    void listHoldsTheWriteToolsAndNoSqlTool(McpSession session) {
        List<String> names = session.tools().stream().map(Tool::name).toList();

        assertThat(names).containsAll(ToolNames.WRITE)
                .noneMatch(name -> name.startsWith("execute_sql") || name.startsWith("search_objects"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.veridger.mcp.support.ToolNames#write")
    @DisplayName("MCW-002: _hostAttestation is an optional string, author_id, request_id and batch_request_id are not "
            + "declared (removed, was: author_id a declared deprecated key), every object of the schema is closed")
    void attestationIsOptionalAndTheSchemaIsClosed(String tool, McpSession session) {
        SchemaView schema = input(session, tool);

        assertThat(schema.properties().get("_hostAttestation").type()).isEqualTo("string");
        assertThat(schema.required()).doesNotContain("_hostAttestation");
        assertThat(schema.properties()).doesNotContainKeys("author_id", "request_id", "batch_request_id");
        assertThat(schema.closed()).isTrue();
        assertThat(schema.objects()).allMatch(SchemaView::closed);
    }

    static Stream<String> keyedWriteTools() {
        return ToolNames.write().filter(tool -> !"ledger_whoami".equals(tool));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keyedWriteTools")
    @DisplayName("MCW-002: the nine write tools declare operationKey as a required string, 1..128 characters (ledger_batch: "
            + "one key for the whole batch, the operations carry none; the others: the server never makes a key up)")
    void everyKeyedWriteToolRequiresAnOperationKey(String tool, McpSession session) {
        SchemaView schema = input(session, tool);
        SchemaView key = schema.properties().get("operationKey");

        assertThat(key).as("operationKey of %s", tool).isNotNull();
        assertThat(key.type()).isEqualTo("string");
        assertThat(schema.required()).contains("operationKey");
        assertThat(key.description()).startsWith("Required.").contains("1..128 characters");
        if ("ledger_batch".equals(tool)) {
            assertThat(key.description()).contains("whole batch").contains("carry no key of their own");
        } else {
            assertThat(key.description()).contains("never makes a key up");
        }
    }

    @Test
    @DisplayName("MCW-002: ledger_whoami can scope the reported grants and supports the shared response options")
    void whoamiDeclaresAttestationAndResponseOptions(McpSession session) {
        SchemaView schema = input(session, "ledger_whoami");

        assertThat(schema.propertyNames()).containsExactlyInAnyOrder("_hostAttestation", "project", "fields",
                "structured_only");
        assertThat(schema.required()).isEmpty();
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
    void projectVersionLinksDeclareTheFourSupportedRecordKinds(McpSession session) {
        var batch = input(session, "ledger_batch");
        var versions = batch.nodes()
                .filter(node -> node.properties().containsKey("link_items")).findFirst().orElseThrow();
        for (String field : List.of("link_items", "unlink_items")) {
            var items = versions.properties().get(field);
            if (items.ref() != null) {
                items = batch.defs().get(items.ref().substring("#/$defs/".length()));
            }
            assertThat(items.closed()).isTrue();
            assertThat(items.propertyNames()).containsExactly("criterion", "issue", "spec", "task");
            assertThat(items.properties().values()).allSatisfy(ids -> {
                assertThat(ids.type()).isEqualTo("array");
                assertThat(ids.items().type()).isEqualTo("string");
            });
        }
    }

    @Test
    @DisplayName("MCW-014: ledger_transition offers the author-channel states only, ledger_review all of them")
    void transitionOffersNoVerificationState(McpSession session) {
        List<String> author = input(session, "ledger_transition").properties().get("to").allowed();
        List<String> reviewer = input(session, "ledger_review").properties().get("to").allowed();

        assertThat(author).contains("OPEN", "RESOLVED", "PLANNED", "ACTIVE", "COMPLETED")
                .doesNotContain("VERIFIED", "MET", "NOT_MET");
        assertThat(reviewer).contains("VERIFIED", "MET", "NOT_MET");
    }

    @Test
    @DisplayName("MCW-014: sending VERIFIED to ledger_transition is VALIDATION_FAILED naming the allowed states")
    void transitionRefusesVerified(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        List<String> allowed = input(session, "ledger_transition").properties().get("to").allowed();

        ToolError error = session.failed("ledger_transition",
                Calls.Transition.of("issue", seed.resolved(), 2, "VERIFIED", Scenario.REVIEWER));

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("to", "unknown value VERIFIED", allowed));
    }

    @Test
    @DisplayName("MCW-016: the enumerations of entity, target_entity, deliverable kind, check_result and link kind")
    void enumerationsOfTheClosedSets(McpSession session) {
        List<String> activeRelationKinds = CodeListing.codes(CodeListing.active(session, "RELATION_KIND"));
        List<String> activeDeliverableKinds = CodeListing.codes(CodeListing.active(session, "DELIVERABLE_KIND"));

        assertThat(input(session, "ledger_transition").properties().get("entity").allowed())
                .containsExactly("issue", "spec", "task", "criterion");
        assertThat(input(session, "ledger_review").properties().get("entity").allowed())
                .containsExactly("issue", "spec", "task", "criterion");
        assertThat(input(session, "ledger_record_deliverable").properties().get("target_entity").allowed())
                .containsExactly("issue", "spec", "task");
        assertThat(input(session, "ledger_record_deliverable").properties().get("kind").allowed())
                .isEqualTo(activeDeliverableKinds);
        assertThat(input(session, "ledger_record_deliverable").properties().get("check_result").allowed())
                .containsExactly("passed", "failed", "cancelled");
        assertThat(input(session, "ledger_link").properties().get("kind").allowed())
                .containsExactlyElementsOf(java.util.stream.Stream.concat(FIXED_LINK_KINDS.stream(),
                        activeRelationKinds.stream()).toList());
    }

    @Test
    @DisplayName("MCW-DIR-01: ledger_link says which relation kinds are directed: its description, the descriptions of a and b, and the line of every relation kind in the list of kinds (GIVEN: a is the subject; otherwise no direction)")
    void linkStatesWhichKindsAreDirected(McpSession session) {
        String description = session.tool("ledger_link").description();
        SchemaView properties = input(session, "ledger_link");
        String kinds = properties.properties().get("kind").description();

        assertThat(description).contains("directed").contains("caused_by = a is caused by b")
                .contains("blocks = a blocks b").contains("(a, b) and (b, a) are two different relations")
                .contains("one relation in either order").contains("ledger_batch with op=project_version");
        assertThat(properties.properties().get("a").description()).contains("subject").contains("caused_by")
                .contains("blocks");
        assertThat(properties.properties().get("b").description()).contains("object");
        for (CodeRow row : CodeListing.active(session, "RELATION_KIND")) {
            boolean directed = "caused_by".equals(row.code()) || "blocks".equals(row.code());
            assertThat(kinds).as(row.code()).contains(row.code() + " ").contains(CodeListing.line(row));
            assertThat(CodeListing.line(row)).as(row.code())
                    .endsWith(directed ? "[directed: a is the subject, b the object]"
                            : "[no direction: a and b are interchangeable]");
        }
        // the six fixed kinds are no relation kind and carry no direction
        assertThat(kinds).doesNotContain("issue_task [").doesNotContain("criterion_task [");
    }

    @Test
    @DisplayName("MCW-017: the description of ledger_review states domain-neutral review rules: automated checks are "
            + "optional metadata, the reviewer inspects the actual deliverable, and rework goes to resolved_by / completed_by")
    void reviewDescriptionStatesTheReviewRules(McpSession session) {
        String description = session.tool("ledger_review").description();

        assertThat(description).contains("optional metadata").contains("actual deliverable")
                .contains("resolved_by of ledger_issue").contains("completed_by of ledger_spec")
                .contains("project verify", "RESOLVED issue to OPEN", "COMPLETED spec/task/criterion to ACTIVE",
                        "ledger_transition for author-side changes");
    }

    @Test
    @DisplayName("MCW-023: flat ledger_issue_save and batch SaveFields carry active ISSUE_CATEGORY and ISSUE_SEVERITY codes "
            + "as enumeration and description, and no write tool takes a raw query parameter")
    void saveParametersAndBatchFieldsCarryTheActiveCodes(McpSession session) {
        SchemaView issueSave = input(session, "ledger_issue_save");
        List<CodeRow> categories = CodeListing.active(session, "ISSUE_CATEGORY");
        List<CodeRow> severities = CodeListing.active(session, "ISSUE_SEVERITY");

        assertThat(ToolNames.WRITE).allSatisfy(tool ->
                assertThat(input(session, tool).propertyNames()).doesNotContain("query", "sql"));
        assertThat(issueSave.at("category").orElseThrow().allowed()).isEqualTo(CodeListing.codes(categories))
                .containsExactly("ARC");
        assertThat(issueSave.at("severity").orElseThrow().allowed()).isEqualTo(CodeListing.codes(severities))
                .doesNotContain("UNRATED");
        assertThat(issueSave.at("category").orElseThrow().description())
                .contains(categories.stream().map(CodeListing::line).toList().toArray(new String[0]));
        assertThat(issueSave.at("severity").orElseThrow().description())
                .contains(severities.stream().map(CodeListing::line).toList().toArray(new String[0]));
    }
}
