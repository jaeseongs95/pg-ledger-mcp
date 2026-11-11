package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.mcp.support.CodeChanges;
import org.pgledger.testing.TestOwners;
import org.pgledger.mcp.support.CodeListing;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Parallel;
import org.pgledger.mcp.support.Parallel.Outcome;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.support.ToolNames;
import org.pgledger.mcp.tool.ReadOutputs;
import org.pgledger.mcp.tool.WriteOutputs;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MCC cases about the tools following the common codes. Another process changes a code (here: the same service call
 * that the admin web and the command line make), the server notices it at its next poll (200 ms in the tests) and
 * announces list_changed. The server of this class is a context of its own because the cases change the codes for
 * good; every case works with codes of its own and checks with "contains".
 */
@McpServerTest
@TestPropertySource(properties = "ledger.test.context=catalog-changes")
class CatalogChangeTest {

    /** Long enough for several polls even on a busy machine; a notification is awaited, not slept for. */
    private static final Duration NOTIFICATION = Duration.ofSeconds(5);

    /** Three polls of 200 ms and a margin. */
    private static final Duration THREE_POLLS = Duration.ofMillis(1500);

    private static SchemaView saveField(McpSession session, String path) {
        return SchemaView.of(session.mapper(), session.tool("ledger_save").inputSchema()).at(path).orElseThrow();
    }

    private static CommonCodeService codes(ApplicationContext context) {
        return context.getBean(CommonCodeService.class);
    }

    @Test
    @DisplayName("MCC-003: a category added by another process is announced and shown in the ledger_save schema "
            + "without a restart")
    void aNewCategoryIsAnnounced(McpSession session, ApplicationContext context) {
        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "UI_UX"), TestOwners.OWNER);

        List<Tool> announced = session.awaitListChange(NOTIFICATION);

        assertThat(announced).extracting(Tool::name).containsExactlyInAnyOrderElementsOf(ToolNames.all());
        assertThat(saveField(session, "fields.category").allowed()).contains("UI_UX");
        assertThat(saveField(session, "fields.category").description()).contains("UI_UX name UI_UX (Name UI_UX)");
    }

    @Test
    @DisplayName("MCC-004: a code set INACTIVE is announced and removed from enumeration and description, and shown "
            + "again when reactivated")
    void aRetiredCodeLeavesAndComesBack(McpSession session, ApplicationContext context) {
        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "COMES_AND_GOES"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView shown = saveField(session, "fields.category");

        codes(context).set(CodeChanges.inactive(CodeChanges.CATEGORY, "COMES_AND_GOES"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView retired = saveField(session, "fields.category");

        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "COMES_AND_GOES"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView reactivated = saveField(session, "fields.category");

        assertThat(shown.allowed()).contains("COMES_AND_GOES");
        assertThat(retired.allowed()).doesNotContain("COMES_AND_GOES");
        assertThat(retired.description()).doesNotContain("COMES_AND_GOES");
        assertThat(reactivated.allowed()).contains("COMES_AND_GOES");
        assertThat(reactivated.description()).contains("COMES_AND_GOES");
    }

    @Test
    @DisplayName("MCC-005: a relation kind and a severity added by another process show in ledger_link.kind and in "
            + "the severity text")
    void aRelationKindAndASeverityAreShown(McpSession session, ApplicationContext context) {
        codes(context).set(CodeChanges.active(CodeChanges.RELATION, "BLOCKS_NEW"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        List<String> kinds = SchemaView.of(session.mapper(), session.tool("ledger_link").inputSchema())
                .at("kind").orElseThrow().allowed();

        codes(context).set(CodeChanges.active(CodeChanges.SEVERITY, "P7"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView severity = saveField(session, "fields.severity");

        assertThat(kinds).contains("defect_task", "condition_task", "condition_defect", "BLOCKS_NEW");
        assertThat(severity.allowed()).contains("P1", "P2", "P3", "P7");
        assertThat(severity.description()).contains("P7 name P7 (Name P7)");
    }

    @Test
    @DisplayName("MCC-006: without a change there is no notification for three polls, and a write does not cause one")
    void noChangeNoNotification(McpSession session, Scenario scenario) {
        session.awaitNoListChange(THREE_POLLS);

        session.ok("ledger_save", Calls.Save.create("defect", Fields.defect(scenario.projectId(), "a write"),
                Scenario.AUTHOR), WriteOutputs.Saved.class);

        session.awaitNoListChange(THREE_POLLS);
    }

    @Test
    @DisplayName("MCC-007: three open sessions are all notified, a fourth opened afterwards sees the new schema")
    void everySessionIsNotified(McpSession first, McpSession second, McpSession third, ApplicationContext context) {
        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "FOR_THREE"), TestOwners.OWNER);

        List<List<Tool>> announced = Stream.of(first, second, third)
                .map(session -> session.awaitListChange(NOTIFICATION)).toList();

        try (McpSession fourth = McpSession.open(context)) {
            assertThat(saveField(fourth, "fields.category").allowed()).contains("FOR_THREE");
        }
        assertThat(announced).hasSize(3).allSatisfy(tools -> assertThat(tools).hasSize(22));
    }

    @Test
    @DisplayName("MCC-008: a session with the old schema writes the new code, the service reads the database")
    void theOldSchemaDoesNotLimitTheWrite(McpSession session, Scenario scenario, ApplicationContext context) {
        List<String> before = saveField(session, "fields.category").allowed();
        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "NOT_LISTED_YET"), TestOwners.OWNER);

        WriteOutputs.Saved saved = session.ok("ledger_save", Calls.Save.create("defect",
                new Fields(scenario.projectId(), "new code", "NOT_LISTED_YET", "P2", "b", "c", null, null, null, null,
                        null, null, null, null, null), Scenario.AUTHOR), WriteOutputs.Saved.class);

        assertThat(before).doesNotContain("NOT_LISTED_YET");
        assertThat(session.ok("ledger_defect", new Calls.IdOnly(saved.id()), ReadOutputs.Defect.class).category())
                .isEqualTo("NOT_LISTED_YET");
    }

    @Test
    @DisplayName("MCC-009: a code change whose transaction rolls back is not announced")
    void aRolledBackChangeIsNotAnnounced(McpSession session, ApplicationContext context) {
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        transaction.executeWithoutResult(status -> {
            codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "NEVER_COMMITTED"));
            status.setRollbackOnly();
        });

        session.awaitNoListChange(THREE_POLLS);
        assertThat(saveField(session, "fields.category").allowed()).doesNotContain("NEVER_COMMITTED");
    }

    @Test
    @DisplayName("MCC-011: tools/list called 200 times while the catalog is rebuilt repeatedly is always a full list")
    void theListIsNeverPartial(McpSession lister, McpSession watcher, ApplicationContext context) {
        Callable<List<Integer>> listing = () -> IntStream.range(0, 200)
                .mapToObj(round -> lister.tools().size()).toList();
        List<CommonCodeService.CodeCommand> flips = List.of(CodeChanges.active(CodeChanges.CATEGORY, "FLIP"),
                CodeChanges.inactive(CodeChanges.CATEGORY, "FLIP"));
        Callable<List<Integer>> changing = () -> IntStream.range(0, 12).mapToObj(round -> {
            codes(context).set(flips.get(round % flips.size()), TestOwners.OWNER);
            return watcher.awaitListChange(NOTIFICATION).size();
        }).toList();

        List<Outcome<List<Integer>>> outcomes = Parallel.all(List.of(listing, changing));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.get(0).value()).hasSize(200).containsOnly(22);
        assertThat(outcomes.get(1).value()).hasSize(12).containsOnly(22);
    }

    @Test
    @DisplayName("MCC-012: codes with quotes, a newline, a backslash and a template marker are listed verbatim, the "
            + "inactive severity is omitted")
    void specialCharactersArePreserved(McpSession session, ApplicationContext context) {
        codes(context).set(CodeChanges.withTexts(CodeChanges.CATEGORY, "QUOTED", "따옴표 \"quoted\"",
                "say \"hi\"", "line one\nline two", "back\\slash"), TestOwners.OWNER);
        codes(context).set(CodeChanges.withTexts(CodeChanges.CATEGORY, "TEMPLATE", "@CODES:DEFECT_CATEGORY@",
                "@CODES:DEFECT_SEVERITY@", "@CODES:DEFECT_CATEGORY@", "@CODES:DEFECT_CATEGORY@"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView category = saveField(session, "fields.category");
        SchemaView severity = saveField(session, "fields.severity");
        List<ReadOutputs.CodeRow> active = CodeListing.active(session, CodeChanges.CATEGORY);

        assertThat(active).extracting(ReadOutputs.CodeRow::code).contains("QUOTED", "TEMPLATE");
        assertThat(category.description()).contains(active.stream().map(CodeListing::line).toList()
                .toArray(new String[0]));
        assertThat(category.description()).contains("QUOTED 따옴표 \"quoted\" (say \"hi\"): line one\nline "
                + "two / back\\slash");
        assertThat(category.description()).contains("TEMPLATE @CODES:DEFECT_CATEGORY@ (@CODES:DEFECT_SEVERITY@): "
                + "@CODES:DEFECT_CATEGORY@ / @CODES:DEFECT_CATEGORY@");
        assertThat(severity.allowed()).doesNotContain("UNRATED");
        assertThat(severity.description()).doesNotContain("UNRATED");
    }
}
