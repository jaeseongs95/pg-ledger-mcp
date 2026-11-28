// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.GrantCommand;
import org.veridger.application.admin.ProjectService;
import org.veridger.application.admin.ProjectService.ProjectCommand;
import org.veridger.application.admin.ProjectService.RenameCommand;
import org.veridger.application.admin.ProjectVersionService;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.LookupException;
import org.veridger.mcp.error.ToolError;
import org.veridger.mcp.support.Args;
import org.veridger.mcp.support.Calls;
import org.veridger.mcp.support.Ledger;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.McpTestSettings;
import org.veridger.mcp.support.RawHttp;
import org.veridger.mcp.support.RawHttp.Header;
import org.veridger.mcp.support.ReadCase;
import org.veridger.mcp.support.Scenario;
import org.veridger.mcp.support.SchemaView;
import org.veridger.mcp.support.Seed;
import org.veridger.mcp.support.ToolNames;
import org.veridger.mcp.tool.ReadOutputs;
import org.veridger.testing.TestOwners;
import tools.jackson.databind.JsonNode;

/** MCR cases common to the 15 read tools: schemas, the removed SQL tools, the bearer. */
@McpServerTest
class ReadContractTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.veridger.mcp.support.ReadCase#all")
    @DisplayName("MCR-001: a read tool declares a closed input without attestation or author, an output schema that "
            + "its result validates against, and refuses an unknown argument")
    void aReadToolKeepsItsContract(ReadCase read, McpSession session, Scenario scenario) {
        Tool tool = session.tool(read.tool());
        SchemaView input = SchemaView.of(session.mapper(), tool.inputSchema());
        Seed seed = Seed.create(session, scenario);

        CallToolResult result = session.call(read.tool(), read.call(seed));
        ValidationResponse validation = McpJsonDefaults.getSchemaValidator().validate(tool.outputSchema(),
                result.structuredContent());
        CallToolResult failedResult = session.call(read.tool(),
                Args.from(session.mapper(), read.call(seed)).with("unknown_argument", 1));
        ValidationResponse failedValidation = McpJsonDefaults.getSchemaValidator().validate(tool.outputSchema(),
                failedResult.structuredContent());
        ToolError unknown = session.error(failedResult);

        assertThat(tool.outputSchema()).isNotNull();
        assertThat(input.propertyNames()).doesNotContain("_hostAttestation");
        assertThat(input.closed()).isTrue();
        assertThat(input.objects()).allMatch(SchemaView::closed);
        assertThat(result.isError()).isFalse();
        assertThat(validation.valid()).as("the ok result against the output schema: %s", validation.errorMessage())
                .isTrue();
        assertThat(failedResult.isError()).isTrue();
        assertThat(failedValidation.valid()).as("the error result against the output schema: %s",
                failedValidation.errorMessage()).isTrue();
        assertThat(unknown.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(unknown.issues()).extracting(ToolError.Issue::path).contains("unknown_argument");

        JsonNode fullData = session.mapper().valueToTree(result.structuredContent()).path("data");
        String field = SchemaView.of(session.mapper(), tool.outputSchema()).at("data").orElseThrow()
                .propertyNames().stream().filter(fullData::has).findFirst().orElseThrow();
        Args projectedArgs = Args.from(session.mapper(), read.call(seed)).with("fields", List.of(field))
                .with("structured_only", true);
        CallToolResult projected = session.call(read.tool(), projectedArgs);
        assertThat(projected.isError()).isFalse();
        assertThat(McpSession.text(projected)).isEqualTo("OK");
        assertThat(McpJsonDefaults.getSchemaValidator().validate(tool.outputSchema(), projected.structuredContent())
                .valid()).isTrue();
        JsonNode selected = session.mapper().valueToTree(projected.structuredContent()).path("data");
        assertThat(selected.get(field)).isEqualTo(fullData.get(field));
        int expectedFields = "items".equals(field) && fullData.has("next_cursor") ? 3 : 1;
        if (fullData.has("not_found") && !"not_found".equals(field)) {
            expectedFields++;
        }
        assertThat(selected.size()).isEqualTo(expectedFields);
    }

    @Test
    void relatedProjectVersionsSurviveRenamingAndAppearInOneBatch(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        var versions = scenario.bean(ProjectVersionService.class);
        String original = "검토본 A";
        String renamed = "운영안 2026년 2차";
        String otherProject = seed.project() + "-other";
        scenario.bean(ProjectService.class).add(new ProjectCommand(otherProject, scenario.code() + "O", "Other"),
                TestOwners.OWNER);
        versions.add(otherProject, original, null);
        assertThatThrownBy(() -> versions.changeIssueLink(seed.issue(), original, true))
                .isInstanceOf(LookupException.class);
        versions.add(seed.project(), original, null);
        assertThatThrownBy(() -> versions.add(seed.project(), original, null)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> versions.changeIssueLink(seed.issue(), "미등록", true))
                .isInstanceOf(LookupException.class);
        versions.changeIssueLink(seed.issue(), original, true);
        versions.changeIssueLink(seed.issue(), original, true);
        versions.update(seed.project(), original, renamed, "운영 검토에 사용");

        List<String> ids = List.of(seed.issue(), seed.spec(), "missing-record", seed.resolved());
        Args lookup = Args.of().with("ids", ids).with("include_details", true);
        CallToolResult response = session.call("ledger_get", lookup);
        assertThat(response.isError()).isFalse();
        ReadOutputs.Got batch = session.read(response, ReadOutputs.Got.class);
        assertThat(McpJsonDefaults.getSchemaValidator().validate(session.tool("ledger_get").outputSchema(),
                response.structuredContent()).valid()).isTrue();
        assertThat(batch.items()).extracting(ReadOutputs.GotItem::id)
                .containsExactly(seed.issue(), seed.spec(), seed.resolved());
        assertThat(batch.notFound()).containsExactly("missing-record");
        assertThat(batch.issueDetails()).extracting(ReadOutputs.Issue::id).containsExactly(seed.issue(), seed.resolved());
        ReadOutputs.Issue single = session.ok("ledger_issue", new Calls.IdOnly(seed.issue()), ReadOutputs.Issue.class);
        assertThat(batch.issueDetails().getFirst()).isEqualTo(single);
        assertThat(single.projectVersions()).containsExactly(renamed);
        assertThat(batch.issueDetails().getLast().projectVersions()).isEmpty();
        assertThat(session.ok("ledger_get", new Calls.Ids(ids), ReadOutputs.Got.class).issueDetails()).isNull();

        var filter = Args.of().with("project", seed.project()).with("project_version", renamed);
        var matching = session.readItems(session.call("ledger_issues", filter), ReadOutputs.IssueRow.class);
        assertThat(matching.items()).extracting(ReadOutputs.IssueRow::id).containsExactly(seed.issue());
        assertThat(matching.items().getFirst().projectVersions()).containsExactly(renamed);
        var projects = session.readItems(session.call("ledger_projects",
                Args.of().with("project", seed.project()).with("include_versions", true)), ReadOutputs.ProjectRow.class);
        assertThat(projects.items()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(seed.project());
            assertThat(p.versions())
                    .containsExactly(new ReadOutputs.ProjectVersion(renamed, "운영 검토에 사용"));
        });

        versions.changeIssueLink(seed.issue(), renamed, false);
        versions.changeIssueLink(seed.issue(), renamed, false);
        assertThat(session.readItems(session.call("ledger_issues", filter), ReadOutputs.IssueRow.class).items())
                .isEmpty();
    }

    @Test
    void theDetailBatchLimitLeavesTheMetadataLimitUnchanged(McpSession session) {
        List<String> ids = IntStream.rangeClosed(1, 11).mapToObj(i -> "missing-" + i).toList();
        Args lookup = Args.of().with("ids", ids);
        assertThat(session.ok("ledger_get", lookup, ReadOutputs.Got.class).notFound()).containsExactlyElementsOf(ids);
        assertThat(session.error(session.call("ledger_get", lookup.with("include_details", true))).code())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void projectCatalogShowsTheRenamedDisplayNameAndKeepsItsIdentifiers(McpSession session, Scenario scenario) {
        String id = scenario.projectId() + "-catalog";
        String code = scenario.code() + "C";
        var service = scenario.bean(ProjectService.class);
        var original = service.add(new ProjectCommand(id, code, "Project description", "Old name"), TestOwners.OWNER);
        service.rename(new RenameCommand(id, "Sennard", original.version()), TestOwners.OWNER);

        String otherId = scenario.projectId() + "-spare";
        service.add(new ProjectCommand(otherId, code + "X", "Another project", "Sennard documentation"),
                TestOwners.OWNER);
        var filter = Args.of().with("project", id).with("display_name", "sEnNaRd").with("include_versions", true);
        var response = session.call("ledger_projects", filter);
        assertThat(response.isError()).isFalse();
        assertThat(session.readItems(response, ReadOutputs.ProjectRow.class).items())
                .singleElement().satisfies(project -> {
                    assertThat(project.id()).isEqualTo(id);
                    assertThat(project.code()).isEqualTo(code);
                    assertThat(project.displayName()).isEqualTo("Sennard");
                    assertThat(project.title()).isEqualTo("Project description");
                    assertThat(project.versions()).isEmpty();
                });
        assertThat(session.readItems(session.call("ledger_projects", filter.with("display_name", "Old name")),
                ReadOutputs.ProjectRow.class).items()).isEmpty();
        var paged = Args.of().with("display_name", "sennard").with("max_rows", 1);
        var first = session.readItems(session.call("ledger_projects", paged), ReadOutputs.ProjectRow.class);
        assertThat(first.items()).extracting(ReadOutputs.ProjectRow::id).containsExactly(id);
        assertThat(first.truncated()).isTrue();
        assertThat(session.readItems(session.call("ledger_projects", paged.with("cursor", first.nextCursor())),
                ReadOutputs.ProjectRow.class).items()).extracting(ReadOutputs.ProjectRow::id).containsExactly(otherId);
        assertThat(session.failed("ledger_projects", paged.with("display_name", "different")
                .with("cursor", first.nextCursor())).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void anAuthorizedAgentCanManageProjectVersionsAndIssueLinksInOneBatch(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String name = "운영 검토본";
        var op = Args.of().with("op", "project_version").with("project_id", seed.project()).with("name", name)
                .with("create", true).with("description", "초기 검토에 쓰는 버전")
                .with("link_issue_ids", List.of(seed.issue(), seed.resolved()));
        var batch = Args.of().with("operationKey", Calls.freshKey()).with("default_reason", Scenario.REASON)
                .with("ops", List.of(op)).with("_hostAttestation", Scenario.AUTHOR);
        assertThat(session.failed("ledger_batch", batch.with("_hostAttestation", Scenario.REVIEWER)).code())
                .isEqualTo("SESSION_NOT_GRANTED");
        var response = session.call("ledger_batch", batch);
        JsonNode result = session.mapper().valueToTree(response.structuredContent()).path("data").path("results").get(0);
        assertThat(result.path("created").asBoolean()).isTrue();
        assertThat(result.path("linked").asInt()).isEqualTo(2);
        assertThat(session.call("ledger_batch", batch).structuredContent()).isEqualTo(response.structuredContent());
        assertThat(session.failed("ledger_batch", batch.with("ops", List.of(op.with("name", "다른 버전")))).code())
                .isEqualTo("OPERATION_CONFLICT");
        assertThat(Ledger.history(session, seed.project()))
                .filteredOn(h -> h.changes() instanceof Map<?, ?> changes && changes.containsKey("project_version"))
                .hasSize(2);

        String renamed = "운영 검토 확정본";
        var rename = Args.of().with("op", "project_version").with("project_id", seed.project()).with("name", renamed)
                .with("original_name", name).with("unlink_issue_ids", List.of(seed.resolved()));
        var edited = session.call("ledger_batch", batch.with("operationKey", Calls.freshKey()).with("ops", List.of(rename)));
        JsonNode changed = session.mapper().valueToTree(edited.structuredContent()).path("data").path("results").get(0);
        assertThat(changed.path("version_id")).isEqualTo(result.path("version_id"));
        assertThat(changed.path("unlinked").asInt()).isEqualTo(1);
        var data = session.ok("ledger_issue", new Calls.IdOnly(seed.issue()), ReadOutputs.Issue.class);
        assertThat(data.projectVersions()).containsExactly(renamed);
        assertThat(session.ok("ledger_issue", new Calls.IdOnly(seed.resolved()), ReadOutputs.Issue.class).projectVersions())
                .isEmpty();
    }

    @Test
    void anAgentCanLinkAndUnlinkExistingRecordsAcrossVersionItemKinds(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String name = "Existing records";
        scenario.bean(ProjectVersionService.class).add(seed.project(), name, null);
        Map<String, List<String>> items = Map.of("spec", List.of(seed.spec(), seed.spec()),
                "task", List.of(seed.task()), "criterion", List.of(seed.criterion()));
        var version = Args.of().with("op", "project_version").with("project_id", seed.project()).with("name", name);
        var base = Args.of().with("default_reason", Scenario.REASON);
        var link = base.with("operationKey", Calls.freshKey()).with("ops", List.of(version.with("link_items", items)));
        String actor = scenario.actorWith("version-links", "spec", "task");
        String taskOnly = scenario.actorWith("version-task-only", "task");
        assertThat(session.failed("ledger_batch", link.with("_hostAttestation", taskOnly)).code())
                .isEqualTo("SESSION_NOT_GRANTED");
        assertThat(session.failed("ledger_batch", link.with("_hostAttestation", Scenario.ISSUE_ONLY)).code())
                .isEqualTo("SESSION_NOT_GRANTED");
        assertThat(session.failed("ledger_batch", base.with("operationKey", Calls.freshKey())
                .with("_hostAttestation", actor).with("ops", List.of(version.with("description", "edit version")
                        .with("link_items", items)))).code()).isEqualTo("SESSION_NOT_GRANTED");

        var authorizedLink = link.with("_hostAttestation", actor);
        var response = session.call("ledger_batch", authorizedLink);
        assertThat(response.isError()).isFalse();
        var result = session.mapper().valueToTree(response.structuredContent()).path("data").path("results").get(0);
        assertThat(result.path("linked").asInt()).isEqualTo(3);
        assertThat(session.call("ledger_batch", authorizedLink).structuredContent()).isEqualTo(response.structuredContent());
        assertThat(session.failed("ledger_batch", authorizedLink.with("ops",
                List.of(version.with("unlink_items", items)))).code()).isEqualTo("OPERATION_CONFLICT");

        var details = Args.of().with("ids", List.of(seed.spec(), seed.task(), seed.criterion()))
                .with("include_details", true);
        var linked = session.ok("ledger_get", details, ReadOutputs.Got.class);
        assertThat(linked.specDetails().getFirst().projectVersions()).containsExactly(name);
        assertThat(linked.taskDetails().getFirst().projectVersions()).containsExactly(name);
        assertThat(linked.criterionDetails().getFirst().projectVersions()).containsExactly(name);

        var unlink = authorizedLink.with("operationKey", Calls.freshKey())
                .with("ops", List.of(version.with("unlink_items", items)));
        var detached = session.call("ledger_batch", unlink);
        assertThat(detached.isError()).isFalse();
        assertThat(session.mapper().valueToTree(detached.structuredContent())
                .path("data").path("results").get(0).path("unlinked").asInt()).isEqualTo(3);
        var noChange = session.call("ledger_batch", unlink.with("operationKey", Calls.freshKey()));
        assertThat(noChange.isError()).isFalse();
        assertThat(session.mapper().valueToTree(noChange.structuredContent())
                .path("data").path("results").get(0).path("unlinked").asInt()).isZero();
        var unlinked = session.ok("ledger_get", details, ReadOutputs.Got.class);
        assertThat(unlinked.specDetails().getFirst().projectVersions()).isEmpty();
        assertThat(unlinked.taskDetails().getFirst().projectVersions()).isEmpty();
        assertThat(unlinked.criterionDetails().getFirst().projectVersions()).isEmpty();

        var changes = Ledger.history(session, seed.project()).stream()
                .filter(row -> row.changes() instanceof Map<?, ?> data && data.containsKey("project_version")).toList();
        assertThat(changes).hasSize(6).allSatisfy(row -> assertThat(row.authorId()).isEqualTo(actor));
        for (String kind : items.keySet()) {
            assertThat(changes).filteredOn(row -> row.entity().equals(kind)).extracting(ReadOutputs.History::op)
                    .containsExactlyInAnyOrder("LINK", "UNLINK");
        }
    }

    @Test
    void aVersionBatchRejectsInvalidSelectionsAndRollsBackOnOutOfProjectItems(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String other = seed.project() + "-other";
        scenario.bean(ProjectService.class).add(new ProjectCommand(other, scenario.code() + "O", "Other"), TestOwners.OWNER);
        scenario.bean(GrantService.class).grant(new GrantCommand(Scenario.AUTHOR, other, List.of("issue", "task"),
                null, null), TestOwners.OWNER);
        String name = "Roll back";
        var version = Args.of().with("op", "project_version").with("project_id", seed.project()).with("name", name);
        var register = version.with("create", true).with("link_items", Map.of("spec", List.of(seed.spec())));
        var link = version.with("project_id", other).with("create", true)
                .with("link_items", Map.of("task", List.of(seed.task())));
        var base = Args.of().with("default_reason", Scenario.REASON).with("_hostAttestation", Scenario.AUTHOR);
        assertThat(session.failed("ledger_batch", base.with("operationKey", Calls.freshKey())
                .with("ops", List.of(register, link))).code()).isEqualTo("NOT_FOUND");
        var versions = scenario.bean(ProjectVersionService.class);
        assertThat(versions.list(seed.project())).isEmpty();
        assertThat(versions.list(other)).isEmpty();

        for (var invalid : List.of(
                register.with("unlink_items", Map.of("spec", List.of(seed.spec()))),
                register.with("link_items", Map.of("deliverable", List.of(seed.task()))),
                register.with("link_items", Map.of("spec", Collections.nCopies(201, seed.spec()))))) {
            assertThat(session.failed("ledger_batch", base.with("operationKey", Calls.freshKey())
                    .with("ops", List.of(invalid))).code()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(session.failed("ledger_batch", base.with("operationKey", Calls.freshKey())
                .with("ops", List.of(register.with("link_items", Map.of("criterion", List.of(seed.task()))))))
                .code()).isEqualTo("NOT_FOUND");
        assertThat(versions.list(seed.project())).isEmpty();
    }

    @Test
    void anAgentCanCreateAndReadFourKindsInOneProjectVersion(McpSession session, Scenario scenario) {
        String project = scenario.projectId();
        Seed.create(session, scenario);
        String name = "운영 검토본";
        var version = Args.of().with("op", "project_version").with("project_id", project).with("name", name)
                .with("create", true);
        var save = Args.of().with("op", "save").with("project_version", name);
        var batch = Args.of().with("operationKey", Calls.freshKey()).with("default_reason", Scenario.REASON)
                .with("_hostAttestation", Scenario.AUTHOR).with("ops", List.of(version,
                        save.with("entity", "spec").with("fields", Calls.Fields.spec(project, "version spec")),
                        save.with("entity", "issue").with("fields", Calls.Fields.issue(project, "version issue")),
                        save.with("entity", "task").with("fields", Calls.Fields.task(project, "version task")),
                        save.with("entity", "criterion").with("id", scenario.criterionId("VER-01"))
                                .with("fields", Calls.Fields.criterion(project, name, "VER-01"))));

        var response = session.call("ledger_batch", batch);
        assertThat(response.isError()).isFalse();
        assertThat(session.call("ledger_batch", batch).structuredContent()).isEqualTo(response.structuredContent());
        JsonNode results = session.mapper().valueToTree(response.structuredContent()).path("data").path("results");
        List<String> ids = IntStream.range(1, results.size()).mapToObj(i -> results.get(i).path("id").asString()).toList();
        ReadOutputs.Got details = session.ok("ledger_get", Args.of().with("ids", ids).with("include_details", true),
                ReadOutputs.Got.class);
        assertThat(details.specDetails().getFirst().projectVersions()).containsExactly(name);
        assertThat(details.issueDetails().getFirst().projectVersions()).containsExactly(name);
        assertThat(details.taskDetails().getFirst().projectVersions()).containsExactly(name);
        assertThat(details.criterionDetails().getFirst().projectVersions()).containsExactly(name);

        var filter = Args.of().with("project", project).with("project_version", name);
        assertThat(session.readItems(session.call("ledger_specs", filter), ReadOutputs.SpecRow.class).items())
                .extracting(ReadOutputs.SpecRow::id).containsExactly(ids.get(0));
        assertThat(session.readItems(session.call("ledger_issues", filter), ReadOutputs.IssueRow.class).items())
                .extracting(ReadOutputs.IssueRow::id).containsExactly(ids.get(1));
        assertThat(session.readItems(session.call("ledger_tasks", filter), ReadOutputs.TaskRow.class).items())
                .extracting(ReadOutputs.TaskRow::id).containsExactly(ids.get(2));
        assertThat(session.readItems(session.call("ledger_criteria", filter), ReadOutputs.CriterionRow.class).items())
                .extracting(ReadOutputs.CriterionRow::id).containsExactly(ids.get(3));

        var search = filter.with("max_rows", 2);
        var first = session.readItems(session.call("ledger_search", search), ReadOutputs.Hit.class);
        assertThat(first.truncated()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        var second = session.readItems(session.call("ledger_search", search.with("cursor", first.nextCursor())),
                ReadOutputs.Hit.class);
        assertThat(second.truncated()).isFalse();
        List<ReadOutputs.Hit> hits = new ArrayList<>(first.items());
        hits.addAll(second.items());
        assertThat(hits).extracting(ReadOutputs.Hit::id).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(session.readItems(session.call("ledger_search", filter.with("keywords", List.of("version task"))),
                ReadOutputs.Hit.class).items()).extracting(ReadOutputs.Hit::id).containsExactly(ids.get(2));
        assertThat(session.failed("ledger_search", search.with("cursor", first.nextCursor())
                .with("project_version", "Another")).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(session.failed("ledger_search", filter.without("project")).code()).isEqualTo("VALIDATION_FAILED");
        for (String tool : List.of("ledger_search", "ledger_specs", "ledger_issues", "ledger_tasks", "ledger_criteria")) {
            var unmatched = session.call(tool, filter.with("project_version", "Unregistered"));
            assertThat(unmatched.isError()).isFalse();
            var rows = session.mapper().valueToTree(unmatched.structuredContent()).path("data").path("items");
            assertThat(rows.isArray()).isTrue();
            assertThat(rows.isEmpty()).isTrue();
        }
    }

    @Test
    void aVersionBatchRollsBackOnAnIssueOutsideItsProject(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String other = seed.project() + "-other";
        scenario.bean(ProjectService.class).add(new ProjectCommand(other, scenario.code() + "O", "Other"), TestOwners.OWNER);
        var first = Args.of().with("op", "project_version").with("project_id", seed.project()).with("name", "A")
                .with("create", true);
        var outside = first.with("name", "B").with("project_id", other).with("link_issue_ids", List.of(seed.issue()));
        scenario.bean(GrantService.class).grant(new GrantCommand(Scenario.AUTHOR, other, List.of("issue"), null, null),
                TestOwners.OWNER);
        var batch = Args.of().with("operationKey", Calls.freshKey()).with("default_reason", Scenario.REASON)
                .with("ops", List.of(first, outside)).with("_hostAttestation", Scenario.AUTHOR);
        assertThat(session.failed("ledger_batch", batch).code()).isEqualTo("NOT_FOUND");
        assertThat(scenario.bean(ProjectVersionService.class).list(seed.project())).isEmpty();
        assertThat(scenario.bean(ProjectVersionService.class).list(other)).isEmpty();
    }

    @Test
    void rejectsVersionSelectionOnEditsAndRollsBackWhenTheSelectedVersionIsMissing(
            McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        var versions = scenario.bean(ProjectVersionService.class);
        String name = "Review scope";
        versions.add(seed.project(), name, null);

        var batch = Args.of().with("default_reason", Scenario.REASON).with("_hostAttestation", Scenario.AUTHOR);
        var edit = Args.of().with("op", "save").with("entity", "issue").with("id", seed.issue()).with("expected_version", 1)
                .with("fields", Args.of().with("title", "Should not replace title"))
                .with("project_version", name);
        assertThat(session.failed("ledger_batch", batch.with("operationKey", Calls.freshKey())
                .with("ops", List.of(edit))).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(session.ok("ledger_issue", new Calls.IdOnly(seed.issue()), ReadOutputs.Issue.class).projectVersions())
                .isEmpty();

        String id = scenario.criterionId("VERSION-ROLLBACK");
        String title = "Unregistered version";
        var version = Args.of().with("op", "project_version").with("project_id", seed.project()).with("name", "Transient")
                .with("create", true);
        var criterion = Args.of().with("op", "save").with("entity", "criterion").with("id", id)
                .with("fields", Calls.Fields.criterion(seed.project(), name, "RC-02")).with("project_version", title);
        assertThat(session.failed("ledger_batch", batch.with("operationKey", Calls.freshKey())
                .with("ops", List.of(version, criterion))).code()).isEqualTo("NOT_FOUND");
        assertThat(versions.list(seed.project())).extracting(ProjectVersionService.Version::name).containsExactly(name);
        assertThat(session.ok("ledger_get", new Calls.Ids(List.of(id)), ReadOutputs.Got.class).notFound()).containsExactly(id);
    }

    @Test
    void mixedDetailSelectionAndPaginationAvoidLargeResponses(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        var ids = List.of(seed.issue(), seed.spec(), seed.task(), seed.criterion());
        var all = Args.of().with("ids", ids).with("include_details", true);
        ReadOutputs.Got data = session.ok("ledger_get", all, ReadOutputs.Got.class);
        assertThat(data.issueDetails()).extracting(ReadOutputs.Issue::id).containsExactly(seed.issue());
        assertThat(data.specDetails()).extracting(ReadOutputs.Spec::id).containsExactly(seed.spec());
        assertThat(data.taskDetails()).extracting(ReadOutputs.Task::id).containsExactly(seed.task());
        assertThat(data.criterionDetails()).extracting(ReadOutputs.Criterion::id).containsExactly(seed.criterion());
        var full = session.call("ledger_get", all);
        var picked = session.call("ledger_get", all.with("fields",
                List.of("issue_details.title", "spec_details.title", "task_details.title", "criterion_details.code"))
                .with("structured_only", true));
        assertThat(picked.isError()).isFalse();
        assertThat(McpSession.text(picked)).isEqualTo("OK");
        assertThat(session.mapper().writeValueAsBytes(picked).length)
                .isLessThan(session.mapper().writeValueAsBytes(full).length / 2);

        var pageArgs = Args.of().with("project", seed.project()).with("max_rows", 1)
                .with("fields", List.of("items.id"));
        var page = session.call("ledger_issues", pageArgs);
        JsonNode rows = session.mapper().valueToTree(page.structuredContent()).path("data");
        assertThat(rows.path("truncated").asBoolean()).isTrue();
        assertThat(rows.path("next_cursor").asString()).isNotBlank();
        assertThat(rows.path("items").get(0).size()).isEqualTo(1);
        var next = session.call("ledger_issues", pageArgs.with("cursor", rows.path("next_cursor").asString()));
        JsonNode more = session.mapper().valueToTree(next.structuredContent()).path("data");
        assertThat(more.path("items").get(0).path("id")).isNotEqualTo(rows.path("items").get(0).path("id"));
    }

    @Test
    void malformedResponseOptionsReturnValidationErrors(McpSession session) {
        var badFields = List.of("id", List.of(), List.of(1), List.of("not a field"), List.of("x".repeat(121)),
                java.util.Collections.nCopies(17, "id"));
        for (Object fields : badFields) {
            var error = session.failed("ledger_projects", Args.of().with("fields", fields));
            assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactly("fields");
        }
        var badMode = session.failed("ledger_projects", Args.of().with("structured_only", "true"));
        assertThat(badMode.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(badMode.issues()).extracting(ToolError.Issue::path).containsExactly("structured_only");
    }

    @Test
    void everyToolOffersResponseOptionsAndBatchRetriesKeepTheSameReceipt(McpSession session, Scenario scenario) {
        assertThat(session.tools()).hasSize(33).allSatisfy(tool -> {
            SchemaView input = SchemaView.of(session.mapper(), tool.inputSchema());
            assertThat(input.properties().get("fields").type()).isEqualTo("array");
            assertThat(input.properties().get("structured_only").type()).isEqualTo("boolean");
            assertThat(input.required()).doesNotContain("fields", "structured_only");
        });

        Seed seed = Seed.create(session, scenario);
        var op = Map.of("op", "record_case", "issue_id", seed.issue(), "observation", "an observed event");
        var full = Calls.Batch.of(Scenario.AUTHOR, Calls.Op.recordCase(seed.issue(), "an observed event"));
        var args = Args.of().with("ops", List.of(op)).with("operationKey", full.operationKey())
                .with("_hostAttestation", Scenario.AUTHOR).with("default_reason", Scenario.REASON);
        var invalid = session.failed("ledger_batch", args.with("fields", List.of("unknown_field")));
        assertThat(invalid.issues()).extracting(ToolError.Issue::path).containsExactly("fields");
        var projected = session.call("ledger_batch", args.with("fields", List.of("count", "results.history_id")));
        assertThat(projected.isError()).isFalse();
        var data = session.mapper().valueToTree(projected.structuredContent()).path("data");
        assertThat(data.path("count").asInt()).isEqualTo(1);
        assertThat(data.path("results").get(0).size()).isEqualTo(1);
        var replay = session.call("ledger_batch", full);
        assertThat(replay.isError()).isFalse();
        var replayed = session.mapper().valueToTree(replay.structuredContent()).path("data").path("results").get(0);
        assertThat(replayed.path("history_id")).isEqualTo(data.path("results").get(0).path("history_id"));
        var issue = session.ok("ledger_issue", new Calls.IdOnly(seed.issue()), ReadOutputs.Issue.class);
        assertThat(issue.cases()).filteredOn(c -> "an observed event".equals(c.observation())).hasSize(1);
    }

    @Test
    void attentionAndPermissionQueriesCanReturnOnlyTheContextForTheCurrentAction(McpSession session,
            Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        var attention = Args.of().with("project", seed.project()).with("kind", "OPEN_NO_OWNER");
        var minimal = session.readItems(session.call("ledger_attention", attention), ReadOutputs.AttentionRow.class);
        var contextual = session.readItems(session.call("ledger_attention", attention.with("include_context", true)),
                ReadOutputs.AttentionRow.class);
        assertThat(minimal.items()).extracting(ReadOutputs.AttentionRow::title).containsExactly((String) null);
        assertThat(contextual.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(seed.issue());
            assertThat(item.title()).isEqualTo("open issue");
            assertThat(item.updatedAt()).isNotNull();
        });

        var whoami = Args.from(session.mapper(), Calls.Whoami.as(Scenario.REVIEWER));
        var project = session.ok("ledger_whoami", whoami.with("project", seed.project()),
                org.veridger.mcp.tool.WriteOutputs.Whoami.class);
        assertThat(project.grants()).extracting(org.veridger.mcp.tool.WriteOutputs.Grant::project)
                .containsOnly(seed.project());
        var elsewhere = session.ok("ledger_whoami", whoami.with("project", seed.project() + "-elsewhere"),
                org.veridger.mcp.tool.WriteOutputs.Whoami.class);
        assertThat(elsewhere.grants()).isEmpty();
        assertThat(elsewhere.canVerifyLogin()).isTrue();
    }

    @Test
    @DisplayName("MCR-001: author_id is declared by ledger_history only, as the filter of MCR-H05; no write tool and "
            + "no attachment tool declares it any more (it was the ignored deprecated key of the write tools)")
    void authorIdIsOnlyTheHistoryFilter(McpSession session) {
        List<String> declaring = ToolNames.all().stream()
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
    @DisplayName("MCR-011: ledger_codes exists and lists the active groups")
    void theCodesToolExists(McpSession session) {
        ReadOutputs.Items<ReadOutputs.CodeRow> severities = session.readItems(
                session.call("ledger_codes", Calls.Codes.of("ISSUE_SEVERITY")), ReadOutputs.CodeRow.class);
        ReadOutputs.Items<ReadOutputs.CodeRow> deliverableKinds = session.readItems(
                session.call("ledger_codes", Calls.Codes.of("DELIVERABLE_KIND")), ReadOutputs.CodeRow.class);

        assertThat(session.tools()).extracting(Tool::name).contains("ledger_codes");
        assertThat(severities.items()).extracting(ReadOutputs.CodeRow::code).contains("P1", "P2", "P3");
        assertThat(deliverableKinds.items()).extracting(ReadOutputs.CodeRow::code)
                .contains("code_change", "document", "media", "design", "dataset", "specification", "artifact");
    }

    @Test
    @DisplayName("MCR-K02: ledger_codes says how a relation kind keeps its two ends: caused_by and blocks are GIVEN (directed), the other kinds and every other group CANONICAL")
    void theCodesToolNamesTheDirection(McpSession session) {
        List<ReadOutputs.CodeRow> kinds = session.readItems(session.call("ledger_codes", Calls.Codes.of("RELATION_KIND")),
                ReadOutputs.CodeRow.class).items();
        List<ReadOutputs.CodeRow> severities = session.readItems(
                session.call("ledger_codes", Calls.Codes.of("ISSUE_SEVERITY")), ReadOutputs.CodeRow.class).items();

        assertThat(kinds).filteredOn(row -> "GIVEN".equals(row.direction())).extracting(ReadOutputs.CodeRow::code)
                .containsExactlyInAnyOrder("caused_by", "blocks");
        assertThat(kinds).filteredOn(row -> "CANONICAL".equals(row.direction())).extracting(ReadOutputs.CodeRow::code)
                .contains("same_site", "same_family", "same_work_unit", "regression_of");
        assertThat(severities).extracting(ReadOutputs.CodeRow::direction).containsOnly("CANONICAL");
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
