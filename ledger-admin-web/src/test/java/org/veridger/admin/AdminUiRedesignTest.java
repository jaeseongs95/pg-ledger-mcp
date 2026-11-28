// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.admin.console.Paging;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Browser.Form;
import org.veridger.admin.support.Seeds;
import org.veridger.admin.support.Seeds.Seeded;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.GrantCommand;
import org.veridger.application.write.FieldsInput;
import org.veridger.application.write.LinkCommand;
import org.veridger.application.write.LinkService;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.SaveCommand;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.domain.value.Patch;
import org.veridger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the v4.3.0 Admin Web UI/UX redesign: Issues taskId filter, KO/EN localized labels for all enums and common
 * codes, Judge Verdict O3 Focused Governance Cockpit (preserving /api/overview JSON), Judge Verdict M2 Release Gates
 * hub + /criteria/detail, responsive deliverable cards, and the native dialog History JSON viewer.
 */
@AdminTest
class AdminUiRedesignTest {

    private static final String REVIEWER = "codex:admin-ui-reviewer";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    @Autowired
    private LinkService linkService;

    @Autowired
    private RecordDeliverableService deliverables;

    @Autowired
    private SaveService saves;

    @Autowired
    private TransitionService transitions;

    @Autowired
    private ReviewService reviews;

    @Autowired
    private GrantService grants;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void dateRangesAndSortingUseTheSameCalendarAndContinueAcrossPages() throws Exception {
        Seeded project = seeds.project();
        String late = seeds.issue(project, "Date filter late");
        String early = seeds.issue(project, "Date filter early");
        String nextDay = seeds.issue(project, "Date filter next day");
        setIssueTime(late, "2026-09-01T14:59:59");
        setIssueTime(early, "2026-08-31T15:00:00");
        setIssueTime(nextDay, "2026-09-01T15:00:00");
        Browser browser = Browser.signedIn(mvc);

        var first = browser.perform(browser.get("/issues").param("project", project.project())
                .param("dateField", "updated").param("dateFrom", "2026-09-01").param("dateTo", "2026-09-01")
                .param("dateZone", "Asia/Seoul").param("sort", "updated").param("direction", "asc").param("limit", "1"))
                .andExpect(status().isOk()).andReturn();
        assertThat(first.getResponse().getContentAsString()).contains(early, "aria-sort=\"ascending\"")
                .doesNotContain(late, nextDay);
        Paging paging = (Paging) first.getModelAndView().getModel().get("paging");
        assertThat(paging.nextHref()).contains("dateField=updated", "dateFrom=2026-09-01", "dateTo=2026-09-01",
                "sort=updated", "direction=asc", "dateZone=Asia/Seoul");
        var second = browser.perform(browser.get(paging.nextHref())).andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString()).contains(late).doesNotContain(early, nextDay);
        assertThat(((Paging) second.getModelAndView().getModel().get("paging")).hasNext()).isFalse();

        var history = browser.perform(browser.get("/history").param("project", project.project())
                .param("dateFrom", "2026-09-01").param("dateTo", "2026-09-01").param("dateZone", "Asia/Seoul")
                .param("sort", "created").param("direction", "asc").param("limit", "1"))
                .andExpect(status().isOk()).andReturn();
        assertThat(history.getResponse().getContentAsString()).contains(early).doesNotContain(late, nextDay);
        Paging historyPaging = (Paging) history.getModelAndView().getModel().get("paging");
        String nextHistory = browser.perform(browser.get(historyPaging.nextHref())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(nextHistory).contains(late).doesNotContain(early, nextDay);
    }

    private void setIssueTime(String id, String timestamp) {
        LocalDateTime time = LocalDateTime.parse(timestamp);
        jdbc.sql("UPDATE issue SET updated_at = :time WHERE id = :id").param("time", time).param("id", id).update();
        setHistoryTime("ISSUE", id, time);
    }

    private void setHistoryTime(String entity, String id, LocalDateTime time) {
        jdbc.sql("UPDATE history SET occurred_at = :time WHERE entity = :entity AND entity_id = :id")
                .param("time", time).param("entity", entity).param("id", id).update();
    }

    @Test
    void criterionRegistrationDateComesFromItsCreationHistory() throws Exception {
        Seeded project = seeds.project();
        String first = seeds.criterion(project, "1", "date test", "first-date");
        String second = seeds.criterion(project, "2", "date test", "second-date");
        setHistoryTime("CRITERION", first, LocalDateTime.parse("2026-09-01T09:00:00"));
        setHistoryTime("CRITERION", second, LocalDateTime.parse("2026-09-02T09:00:00"));
        Browser browser = Browser.signedIn(mvc);
        String html = browser.perform(browser.get("/criteria").param("project", project.project())
                .param("dateField", "created").param("dateFrom", "2026-09-01").param("dateTo", "2026-09-01")
                .param("sort", "created").param("direction", "desc"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(first, "aria-sort=\"descending\"", "datetime=\"2026-09-01T09:00:00Z\"")
                .doesNotContain(second);
    }

    @Test
    void aProjectCanBeRenamedWithoutChangingItsIdentifierOrGrants() throws Exception {
        Seeded project = seeds.project();
        String originalId = seeds.issue(project, "Before rename");
        Browser browser = Browser.signedIn(mvc);
        String version = jdbc.sql("SELECT version FROM project WHERE id = :id").param("id", project.project())
                .query(Long.class).single().toString();
        String csrf = browser.csrfOf("/projects");
        browser.perform(browser.form("/projects/rename",
                Form.of("id", project.project(), "displayName", "새 프로젝트명", "version", version), csrf))
                .andExpect(redirectedUrl("/projects"));
        browser.perform(browser.form("/projects/rename",
                Form.of("id", project.project(), "displayName", "Stale edit", "version", version), csrf))
                .andExpect(status().isConflict());

        String projectPage = browser.perform(browser.get("/projects").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(projectPage).contains("새 프로젝트명", "프로젝트 설명").doesNotContain("href=\"/codes\"");
        String grantsPage = browser.perform(browser.get("/grants")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(grantsPage).contains("grant-project-filter", "session-search", "grant-host-filter", "grant-scope-filter",
                "data-project-name=\"새 프로젝트명\"", "value=\"" + project.project() + "\">새 프로젝트명</option>");
        String taskId = seeds.task(project, "Task after rename");
        String specId = seeds.spec(project, "Specification after rename");
        String criterionId = seeds.criterion(project, "1", "rename test", "renamed");
        List<String> details = List.of("/issues/" + originalId, "/tasks/detail?id=" + taskId,
                "/specs/detail?id=" + specId, "/criteria/detail?id=" + criterionId);
        for (String detail : details) {
            String html = browser.perform(browser.get(detail)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).contains("href=\"/?project=" + project.project() + "\">새 프로젝트명</a>");
        }
        String issuesPage = browser.perform(browser.get("/issues").param("project", project.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(issuesPage).contains("value=\"" + project.project() + "\" selected=\"selected\">새 프로젝트명</option>");
        String overviewPage = browser.perform(browser.get("/").param("project", project.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(overviewPage).contains(">새 프로젝트명</option>");
        assertThat(originalId).isEqualTo(project.issueId(1));
        assertThat(seeds.issue(project, "After rename")).isEqualTo(project.issueId(2));
    }

    @Test
    void projectEditSavesBothFieldsAndRejectsStalePayloads() throws Exception {
        Seeded project = seeds.project();
        Browser browser = Browser.signedIn(mvc);
        String version = jdbc.sql("SELECT version FROM project WHERE id = :id").param("id", project.project())
                .query(Long.class).single().toString();
        String csrf = browser.csrfOf("/projects");
        var edit = Form.of("id", project.project(), "displayName", "Updated name", "title", "Updated description",
                "version", version);
        browser.perform(browser.form("/projects/edit", edit, csrf)).andExpect(redirectedUrl("/projects"));
        var stale = Form.of("id", project.project(), "displayName", "Uncommitted form name",
                "title", "Uncommitted form description", "version", version);
        String conflict = browser.perform(browser.form("/projects/edit", stale, csrf)).andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(conflict).contains("Uncommitted form name", "Uncommitted form description",
                "data-open-on-load=\"true\"");
        assertThat(browser.perform(browser.get("/projects")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("Updated name", "Updated description");
    }

    @Test
    void milestoneArchiveKeepsCriteriaAndHistoryAndOnlyHidesTheSelectedProjectGroup() throws Exception {
        Seeded project = seeds.project();
        Seeded other = seeds.project();
        String milestone = "Recovery plan #2";
        String target = seeds.criterion(project, "1", milestone, "ARCHIVE-1");
        String remaining = seeds.criterion(project, "2", "Current plan", "CURRENT-1");
        String otherTarget = seeds.criterion(other, "1", milestone, "OTHER-1");
        Browser browser = Browser.signedIn(mvc);
        String csrf = browser.csrfOf("/criteria?project=" + project.project());
        var archive = Form.of("project", project.project(), "milestone", milestone, "archive", "true");
        browser.perform(browser.form("/criteria/milestone/archive", archive, csrf))
                .andExpect(redirectedUrl("/criteria?project=" + project.project()));
        browser.perform(browser.form("/criteria/milestone/archive", archive, csrf))
                .andExpect(status().is3xxRedirection());
        assertThat(browser.perform(browser.get("/criteria").param("project", project.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains(remaining, "1 shown · 1 matching").doesNotContain(target);
        assertThat(browser.perform(browser.get("/criteria").param("project", project.project()).param("archived", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains(target, "Restore milestone").doesNotContain(remaining);
        assertThat(browser.perform(browser.get("/criteria").param("project", other.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains(otherTarget);
        browser.perform(browser.get("/criteria/detail").param("id", target)).andExpect(status().isOk());
        assertThat(browser.perform(browser.get("/history").param("project", project.project()).param("entityId", target))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains(target);
        var restore = Form.of("project", project.project(), "milestone", milestone, "archive", "false");
        browser.perform(browser.form("/criteria/milestone/archive", restore, csrf))
                .andExpect(redirectedUrl("/criteria?project=" + project.project() + "&archived=true"));
        assertThat(browser.perform(browser.get("/criteria").param("project", project.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains(target, remaining);
        var missing = Form.of("project", project.project(), "milestone", "missing", "archive", "true");
        browser.perform(browser.form("/criteria/milestone/archive", missing, csrf)).andExpect(status().isNotFound());
    }

    @Test
    void pagedListsShowMatchingTotalsEvenWhenNoRowsMatch() throws Exception {
        Seeded project = seeds.project();
        seeds.issue(project, "Issue");
        seeds.task(project, "Task");
        seeds.spec(project, "Specification");
        seeds.criterion(project, "1", "Original milestone", "A");
        Browser browser = Browser.signedIn(mvc);
        for (String route : List.of("/issues", "/tasks", "/specs", "/criteria")) {
            String rendered = browser.perform(browser.get(route).param("project", project.project()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(rendered).contains("1 shown · 1 matching");
        }
        assertThat(browser.perform(browser.get("/issues").param("project", project.project()).param("status", "RESOLVED"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("0 shown · 0 matching");
        assertThat(browser.perform(browser.get("/history").param("project", project.project()).param("entity", "issue"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("1 shown · 1 matching");
    }

    @Test
    @DisplayName("UI-430-01: /issues filters by taskId, preserves the filter value and paging, and reset clears it")
    void v430_01_issuesFilterByTaskId() throws Exception {
        Seeded project = seeds.project();
        String issueOne = seeds.issue(project, "Issue linked to task A");
        String issueTwo = seeds.issue(project, "Issue linked to task B");
        String taskA = seeds.task(project, "Task A");
        String taskB = seeds.task(project, "Task B");
        linkService.link(Seeds.AUTHOR, new LinkCommand(UUID.randomUUID().toString(), "issue_task", issueOne, taskA,
                Seeds.REASON, false));
        linkService.link(Seeds.AUTHOR, new LinkCommand(UUID.randomUUID().toString(), "issue_task", issueTwo, taskB,
                Seeds.REASON, false));
        Browser browser = Browser.signedIn(mvc);

        String filtered = browser.perform(browser.get("/issues")
                .param("project", project.project())
                .param("taskId", taskA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(filtered)
                .contains("id=\"taskId\"")
                .contains("value=\"" + taskA + "\"")
                .contains(issueOne, "Issue linked to task A")
                .doesNotContain(issueTwo, "Issue linked to task B")
                .doesNotContain("No rows.")
                .contains("Reset filters");
    }

    @Test
    @DisplayName("UI-430-02: filter dropdowns, badges, operations, entities, and scopes render localized KO/EN names while keeping canonical values")
    void v430_02_localizedLabelsInKoreanAndEnglish() throws Exception {
        Seeded project = seeds.project();
        String issue = seeds.issue(project, "Localized issue");
        String spec = seeds.spec(project, "Localized spec");
        String task = seeds.task(project, "Localized task");
        seeds.criterion(project, "L1", "v4.3.0", "G1");
        Browser browser = Browser.signedIn(mvc);

        String issuesKo = browser.perform(browser.get("/issues?project=" + project.project())
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ko")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(issuesKo)
                .contains("<option value=\"OPEN\">열림</option>")
                .contains("<option value=\"VERIFIED\">검증됨</option>")
                .contains("열림")
                .contains("작업 ID");

        String issuesEn = browser.perform(browser.get("/issues?project=" + project.project())
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(issuesEn)
                .contains("<option value=\"OPEN\">Open</option>")
                .contains("<option value=\"VERIFIED\">Verified</option>")
                .contains("Task ID");

        String historyKo = browser.perform(browser.get("/history?project=" + project.project())
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ko")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(historyKo)
                .contains("<option value=\"issue\">이슈</option>")
                .contains("<option value=\"CREATE\">생성</option>")
                .contains(issue, spec, task);

        String codesKo = browser.perform(browser.get("/codes").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(codesKo)
                .contains("data-filter-group=\"ISSUE_CATEGORY\"")
                .contains("data-group-label=\"이슈 분류\"")
                .contains("data-display-name=")
                .contains("이슈 분류")
                .contains("활성");

        String codesEn = browser.perform(browser.get("/codes").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(codesEn)
                .contains("data-group-label=\"Issue category\"")
                .contains("data-display-name=");

        String grantsKo = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(grantsKo)
                .contains("<option value=\"verify\">검증</option>")
                .contains("<option value=\"deliverable\">산출물</option>");
    }

    @Test
    @DisplayName("UI-430-03: Overview renders Judge Verdict O3 Focused Governance Cockpit while keeping /api/overview JSON untouched")
    void v430_03_overviewCockpitAndUnchangedApi() throws Exception {
        Seeded emptyProj = seeds.project();
        Browser browser = Browser.signedIn(mvc);

        String emptyHtml = browser.perform(browser.get("/?project=" + emptyProj.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(emptyHtml)
                .contains("No ledger items yet")
                .contains("Manage session grants")
                .doesNotContain("cockpit-tiles");

        Seeded project = seeds.project();
        String issueId = seeds.issue(project, "Cockpit attention issue title");
        seeds.spec(project, "Cockpit spec");
        seeds.task(project, "Cockpit task");

        String noCriteriaHtml = browser.perform(browser.get("/?project=" + project.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(noCriteriaHtml)
                .contains("cockpit-tiles")
                .contains("Needs verification")
                .contains("Hygiene &amp; anomalies")
                .contains("Active work")
                .contains("Blocked &amp; disputed")
                .contains("Cockpit attention issue title")
                .contains("mcp-pill")
                .doesNotContain("overview.totals");

        // /api/overview JSON contract remains completely unchanged
        browser.perform(browser.get("/api/overview?project=" + project.project()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.issues").value(1))
                .andExpect(jsonPath("$.stats.specs").value(1))
                .andExpect(jsonPath("$.stats.tasks").value(1))
                .andExpect(jsonPath("$.attention[0].id").value(issueId))
                .andExpect(jsonPath("$.attention[0].title").doesNotExist());
    }

    @Test
    @DisplayName("UI-430-04: /criteria renders Judge Verdict M2 Release Gates rollups, link counts, onboarding, and /criteria/detail?id=...")
    void v430_04_releaseGatesRollupsLinksAndDetailPage() throws Exception {
        Seeded emptyProj = seeds.project();
        Browser browser = Browser.signedIn(mvc);

        String emptyCriteria = browser.perform(browser.get("/criteria?project=" + emptyProj.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(emptyCriteria)
                .contains("Release gates")
                .contains("No release gates defined yet")
                .contains("onboarding-steps");

        Seeded project = seeds.project();
        grants.grant(new GrantCommand(REVIEWER, project.project(), List.of("verify"), null, null), TestOwners.OWNER);
        String issueId = seeds.issue(project, "Linked gate issue");
        String taskId = seeds.task(project, "Linked gate task");
        String oddCriterionId = project.code() + "-C/a#b?c:1";
        saves.save(Seeds.AUTHOR, new SaveCommand(UUID.randomUUID().toString(), "criterion", oddCriterionId, 0L,
                new FieldsInput(Patch.set(project.project()), null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, Patch.set("v4.3.0"), Patch.set("UX Polish"), Patch.set("G1"),
                        Patch.set("All admin pages pass Browser CLI inspection")),
                Seeds.REASON));
        transitions.transition(Seeds.AUTHOR, new TransitionCommand(UUID.randomUUID().toString(), "criterion",
                oddCriterionId, 1L, "COMPLETED", Seeds.REASON, "https://ci.example/1", null, null));
        reviews.review(REVIEWER, new TransitionCommand(UUID.randomUUID().toString(), "criterion",
                oddCriterionId, 2L, "MET", Seeds.REASON, "https://ci.example/review", null, null));
        String plannedCriterionId = project.code() + "-C-PLANNED";
        saves.save(Seeds.AUTHOR, new SaveCommand(UUID.randomUUID().toString(), "criterion", plannedCriterionId, 0L,
                new FieldsInput(Patch.set(project.project()), null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, Patch.set("v4.3.0"), Patch.set("Rollout"), Patch.set("G2"),
                        Patch.set("Planned rollout criterion included in milestone total")),
                Seeds.REASON));
        transitions.transition(Seeds.AUTHOR, new TransitionCommand(UUID.randomUUID().toString(), "criterion",
                plannedCriterionId, 1L, "PLANNED", Seeds.REASON, null, null, null));
        linkService.link(Seeds.AUTHOR, new LinkCommand(UUID.randomUUID().toString(), "criterion_issue",
                oddCriterionId, issueId, Seeds.REASON, false));
        linkService.link(Seeds.AUTHOR, new LinkCommand(UUID.randomUUID().toString(), "criterion_task",
                oddCriterionId, taskId, Seeds.REASON, false));

        String listHtml = browser.perform(browser.get("/criteria?project=" + project.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listHtml)
                .contains("<progress class=\"gate-progress\" value=\"1\" max=\"2\"")
                .contains("1 / 2 met (50%)")
                .contains("1 planned")
                .contains("<select id=\"milestone\" name=\"milestone\">")
                .contains("UX Polish")
                .contains("Issue 1")
                .contains("Task 1")
                .contains("/criteria/detail?id=")
                .doesNotContain("No rows.");

        String detailHtml = browser.perform(browser.get("/criteria/detail").param("id", oddCriterionId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detailHtml)
                .contains("All admin pages pass Browser CLI inspection")
                .contains(issueId)
                .contains(taskId)
                .contains("Met");

        // Issue detail links to /criteria/detail
        String issueDetail = browser.perform(browser.get("/issues/" + issueId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(issueDetail).contains("/criteria/detail?id=");

        // Unknown criterion returns 404 NOT_FOUND
        browser.perform(browser.get("/criteria/detail").param("id", "NOSUCH-C-999")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("UI-430-05: deliverables use responsive cards and /history uses native dialog JSON viewer without inline style/details")
    void v430_05_deliverablesCardsAndHistoryJsonModal() throws Exception {
        Seeded project = seeds.project();
        String issueId = seeds.issue(project, "Issue with long deliverable");
        String longSha = "5ebe8183e407ec0fa1f3ff2ea89ff001aa2f7b07";
        deliverables.recordDeliverable(Seeds.AUTHOR, new RecordDeliverableCommand(
                UUID.randomUUID().toString(), "issue", issueId, "code_change", longSha, Seeds.REASON,
                "jaeseongs95/veridger@feature/v4.3.0-admin-ui-ux",
                "Signed integration commit with full review summary text that must not wrap into vertical columns.",
                "https://ci.example/runs/42", "passed", "2026-11-26T00:00:00Z", false));
        Browser browser = Browser.signedIn(mvc);

        String issueHtml = browser.perform(browser.get("/issues/" + issueId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(issueHtml)
                .contains("deliverable-card")
                .contains(longSha)
                .contains("badge-passed")
                .contains("Passed")
                .contains("href=\"https://ci.example/runs/42\"")
                .doesNotContain("style=");

        String historyHtml = browser.perform(browser.get("/history?project=" + project.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(historyHtml)
                .contains("id=\"modal-history-json\"")
                .contains("id=\"history-json-pre\"")
                .contains("id=\"history-json-copy\"")
                .contains("data-changes-json")
                .contains("data-row-target=")
                .doesNotContain("style=");
    }
}
