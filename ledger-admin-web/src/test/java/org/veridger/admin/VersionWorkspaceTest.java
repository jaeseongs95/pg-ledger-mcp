// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Browser.Form;
import org.veridger.admin.support.Seeds;
import org.veridger.application.admin.ProjectVersionService;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.database.repository.HistoryQueryRepository;
import org.veridger.domain.rule.HistoryEntity;

@AdminTest
class VersionWorkspaceTest {
    private static final String VERSION = "6.0.0";
    private static final String TITLE = "Version workspace evidence";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private Seeds seeds;
    @Autowired
    private ProjectVersionService versions;
    @Autowired
    private RecordDeliverableService deliverables;
    @Autowired
    private HistoryQueryRepository history;

    static Stream<Arguments> itemForms() {
        return Stream.of(
                Arguments.of(HistoryEntity.SPEC, TITLE, new String[] {
                    "title", TITLE, "summary", "Observe the published state", "acceptanceCriteria", "Confirm the result"
                }),
                Arguments.of(HistoryEntity.ISSUE, TITLE, new String[] {
                    "title", TITLE, "category", "ARC", "severity", "P2", "symptom", "The state is unavailable",
                    "rootCause", "The source record cannot be found"
                }),
                Arguments.of(HistoryEntity.TASK, TITLE, new String[] {
                    "title", TITLE, "purpose", "Verify the state", "successCondition", "The record is available"
                }),
                Arguments.of(HistoryEntity.CRITERION, "RC-01", new String[] {
                    "id", "workspace-release-criterion", "milestone", VERSION, "goal", "Readiness", "code", "RC-01",
                    "criterionText", "The approved evidence is published"
                }));
    }

    @ParameterizedTest
    @MethodSource("itemForms")
    void createsAnItemWithAVisibleVersionLink(HistoryEntity kind, String title, String[] fields) throws Exception {
        var project = seeds.project();
        var version = version(project);
        var browser = Browser.signedIn(mvc);
        String path = "/projects/versions/" + version.id();
        String page = path + "?project=" + project.project() + "&kind=" + kind.token();
        var form = Form.of(fields);
        form.add("project", project.project());
        form.add("kind", kind.token());
        form.add("operationKey", UUID.randomUUID().toString());
        form.add("reason", Seeds.REASON);

        browser.perform(browser.form(path + "/items", form, browser.csrfOf(page)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(page))
                .andExpect(flash().attribute("flashKey", "versions.itemSaved"));

        var items = versions.items(project.project(), version.id(), kind, null, 25).items();
        assertThat(items).singleElement().satisfies(item -> assertThat(item.title()).isEqualTo(title));
        assertThat(html(browser, page)).contains(items.getFirst().id(), title, "Release review evidence");
        String id = items.getFirst().id();
        String details = switch (kind) {
            case SPEC -> "/specs/detail?id=" + id;
            case ISSUE -> "/issues/" + id;
            case TASK -> "/tasks/detail?id=" + id;
            case CRITERION -> "/criteria/detail?id=" + id;
            default -> throw new AssertionError("Unexpected version item kind");
        };
        assertThat(html(browser, details)).contains("href=\"" + path + "?project=" + project.project());

        String listing = switch (kind) {
            case SPEC -> "/specs";
            case ISSUE -> "/issues";
            case TASK -> "/tasks";
            case CRITERION -> "/criteria";
            default -> throw new AssertionError("Unexpected version item kind");
        };
        versions.add(project.project(), "6.1.0 pilot", "A second linked version");
        var second = versions.list(project.project()).getLast();
        versions.changeItemLink(project.project(), second.id(), kind, id, true);
        String filter = "?project=" + project.project() + "&projectVersion=" + VERSION;
        assertThat(html(browser, listing + filter)).contains("class=\"version-col\"", "name=\"projectVersion\"",
                ">" + VERSION + "</", ">6.1.0 pilot</",
                id, "1 shown · 1 matching");
        assertThat(html(browser, "/history" + filter + "&entity=" + kind.token())).contains(id,
                "<span class=\"version-tag\">" + VERSION + "</span>", "<span class=\"version-tag\">6.1.0 pilot</span>");
    }

    @Test
    void linksOnlyAnItemOfTheSelectedKindAndProjectAndCanUnlinkIt() throws Exception {
        var project = seeds.project();
        var version = version(project);
        String task = seeds.task(project, TITLE);
        String outsideTask = seeds.task(seeds.project(), "Task from another project");
        String spec = seeds.spec(project, "A specification");
        var browser = Browser.signedIn(mvc);
        String path = "/projects/versions/" + version.id();
        String page = path + "?project=" + project.project() + "&kind=task";
        String csrf = browser.csrfOf(page);
        var form = Form.of("project", project.project(), "kind", "task", "itemId", outsideTask);

        for (String unavailable : new String[] {outsideTask, spec}) {
            form.set("itemId", unavailable);
            String refused = browser.perform(browser.form(path + "/relationships", form, csrf))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(refused).contains("value=\"" + unavailable + "\"");
        }

        form.set("itemId", " ");
        browser.perform(browser.form(path + "/relationships", form, csrf)).andExpect(status().isBadRequest());
        assertThat(html(browser, page)).doesNotContain(TITLE);

        form.set("itemId", task);
        for (int attempt = 0; attempt < 2; attempt++) {
            browser.perform(browser.form(path + "/relationships", form, csrf))
                    .andExpect(redirectedUrl(page)).andExpect(flash().attribute("flashKey", "versions.relationshipSaved"));
        }
        assertThat(versions.items(project.project(), version.id(), HistoryEntity.TASK, null, 25).items())
                .singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(task));
        assertThat(html(browser, page)).contains(task, TITLE);

        form.add("remove", "true");
        browser.perform(browser.form(path + "/relationships", form, csrf)).andExpect(redirectedUrl(page));
        assertThat(html(browser, page)).doesNotContain(TITLE);
        assertThat(html(browser, "/tasks/detail?id=" + task)).contains(TITLE);
        browser.perform(browser.get(path).param("project", seeds.project().project())).andExpect(status().isNotFound());
        browser.perform(browser.get(page.replace("kind=task", "kind=deliverable"))).andExpect(status().isBadRequest());
    }

    @Test
    void preservesInvalidCreateInputAndPagesThroughLinkedItems() throws Exception {
        var project = seeds.project();
        var version = version(project);
        var browser = Browser.signedIn(mvc);
        String path = "/projects/versions/" + version.id();
        String page = path + "?project=" + project.project() + "&kind=issue";
        var form = Form.of("project", project.project(), "kind", "issue", "operationKey", UUID.randomUUID().toString(),
                "title", TITLE, "category", "ARC", "severity", "P2", "reason", Seeds.REASON);
        String result = browser.perform(browser.form(path + "/items", form, browser.csrfOf(page)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(result).contains("Could not save the form", "value=\"" + TITLE + "\"");

        for (int i = 1; i <= 26; i++) {
            String id = seeds.issue(project, "Issue " + i);
            versions.changeItemLink(project.project(), version.id(), HistoryEntity.ISSUE, id, true);
        }
        String cursor = project.issueId(25);
        String last = project.issueId(26);
        assertThat(html(browser, page)).contains("rel=\"next\"", "after=" + cursor).doesNotContain(last);
        assertThat(html(browser, page + "&after=" + cursor)).contains(last).doesNotContain("rel=\"next\"", cursor);
    }

    @Test
    void keepsAnUnavailableIssueVersionFilterVisibleAndShowsTotalsForAnUnfilteredProject() throws Exception {
        var project = seeds.project();
        version(project);
        seeds.issue(project, TITLE);
        var browser = Browser.signedIn(mvc);
        String issues = "/issues?project=" + project.project();

        assertThat(html(browser, issues)).contains("1 shown · 1 matching");
        browser.perform(browser.get(issues).param("projectVersion", "Unavailable"))
                .andExpect(status().isOk()).andExpect(model().attribute("versionListed", false));
        assertThat(html(browser, issues + "&projectVersion=Unavailable")).contains("value=\"Unavailable\"", "0 shown · 0 matching")
                .doesNotContain(TITLE);
        assertThat(html(browser, "/issues?project=unavailable-project"))
                .containsPattern("value=\"unavailable-project\"\\s+selected>unavailable-project</option>");
    }

    @Test
    void overviewShowsVersionReadinessForTheSelectedProject() throws Exception {
        var project = seeds.project();
        var version = version(project);
        String issue = seeds.issue(project, TITLE);
        String task = seeds.task(project, "Validate recovery");
        String criterion = seeds.criterion(project, "1", "Pilot", "RC-01");
        versions.changeItemLink(project.project(), version.id(), HistoryEntity.ISSUE, issue, true);
        versions.changeItemLink(project.project(), version.id(), HistoryEntity.TASK, task, true);
        versions.changeItemLink(project.project(), version.id(), HistoryEntity.CRITERION, criterion, true);
        var summary = versions.readiness(project.project());
        assertThat(summary).singleElement().satisfies(row -> {
            assertThat(row.issues()).isEqualTo(1);
            assertThat(row.specs()).isZero();
            assertThat(row.tasks()).isEqualTo(1);
            assertThat(row.gates()).isEqualTo(1);
            assertThat(row.met()).isZero();
        });
        var browser = Browser.signedIn(mvc);
        assertThat(html(browser, "/?project=" + project.project()))
                .contains("Version readiness", "0 of 1 criteria met", version.id());
    }

    @Test
    void versionHistoryIncludesRelatedRecordsAndExcludesUnlinkedOrOutOfProjectRecords() throws Exception {
        var project = seeds.project();
        var external = seeds.project();
        var version = version(project);
        var otherVersion = version(external);
        String linked = seeds.issue(project, TITLE);
        String unlinked = seeds.issue(project, "An unlinked issue");
        String outside = seeds.issue(external, "An outside issue");
        for (String id : new String[] {linked, unlinked, outside}) {
            seeds.observe(id, "Case for " + id, "https://example.org/evidence");
        }
        versions.changeItemLink(project.project(), version.id(), HistoryEntity.ISSUE, linked, true);
        versions.changeItemLink(external.project(), otherVersion.id(), HistoryEntity.ISSUE, outside, true);
        var browser = Browser.signedIn(mvc);
        String path = "/history?project=" + project.project() + "&entity=issue_case&projectVersion=" + VERSION;
        assertThat(html(browser, path)).contains(linked + "|", "1 shown · 1 matching")
                .doesNotContain(unlinked + "|", outside + "|");
        browser.perform(browser.get("/projects/versions/options").param("project", project.project())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(content().json("[\"" + VERSION + "\"]"));
        versions.changeItemLink(project.project(), version.id(), HistoryEntity.ISSUE, linked, false);
        assertThat(html(browser, path)).contains("0 shown · 0 matching").doesNotContain(linked + "|");
    }

    @ParameterizedTest
    @EnumSource(value = HistoryEntity.class, names = {"SPEC", "TASK"})
    void compositeHistoryUsesTheLongestOwnerAcrossProjects(HistoryEntity kind) throws Exception {
        var project = seeds.project();
        var external = seeds.project();
        var version = version(project);
        var externalVersion = version(external);
        String shorter = project.code() + "-" + kind.token() + "-prefix";
        String longer = shorter + "@extension";
        if (kind == HistoryEntity.SPEC) {
            seeds.spec(project, shorter, "Short id");
            seeds.spec(external, longer, "Long id");
        } else {
            seeds.task(project, shorter, "Short id");
            seeds.task(external, longer, "Long id");
        }
        versions.changeItemLink(project.project(), version.id(), kind, shorter, true);
        var result = deliverables.recordDeliverable(Seeds.AUTHOR, new RecordDeliverableCommand(
                UUID.randomUUID().toString(), kind.token(), longer, "code_change", "evidence-ref", Seeds.REASON,
                "repo/evidence", "Release evidence", null, null, null, false));
        var historyIds = List.of(result.historyId());

        assertThat(history.projectVersions(historyIds)).isEmpty();
        var browser = Browser.signedIn(mvc);
        assertThat(html(browser, "/history?project=" + project.project() + "&entity=deliverable&projectVersion=" + VERSION))
                .contains("0 shown · 0 matching");

        versions.changeItemLink(external.project(), externalVersion.id(), kind, longer, true);
        assertThat(history.projectVersions(historyIds)).containsEntry(result.historyId(), List.of(VERSION));
        assertThat(html(browser, "/history?project=" + external.project() + "&entity=deliverable&projectVersion=" + VERSION))
                .contains("1 shown · 1 matching", longer);
    }

    private ProjectVersionService.Version version(Seeds.Seeded project) {
        versions.add(project.project(), VERSION, "Release review evidence");
        return versions.list(project.project()).getFirst();
    }

    private static String html(Browser browser, String path) throws Exception {
        return browser.perform(browser.get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
