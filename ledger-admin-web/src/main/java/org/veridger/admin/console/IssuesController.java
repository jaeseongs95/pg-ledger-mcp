// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.veridger.admin.web.AdminHttpExceptionAdvice;
import org.veridger.application.admin.ProjectVersionService;
import org.veridger.application.read.DetailService;
import org.veridger.application.read.ListService;
import org.veridger.application.read.ListService.IssuesQuery;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.IssueStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The issues of a project (filtered, paged) and the detail of one with its deliverables, links, and recent history. */
@Controller
public class IssuesController {

    /** The filters and the paging position of the list, as the query string carries them. */
    public record IssueFilter(String project, String status, String severity, String category, String attention,
            String owner, String taskId, String projectVersion, Integer limit, String cursor, String back) {
    }

    private final Choices choices;
    private final ListService lists;
    private final DetailService details;
    private final Links links;
    private final ProjectVersionService versions;

    public IssuesController(Choices choices, ListService lists, DetailService details,
            Links links, ProjectVersionService versions) {
        this.choices = choices;
        this.lists = lists;
        this.details = details;
        this.links = links;
        this.versions = versions;
    }

    @GetMapping("/issues")
    String list(@ModelAttribute("filter") IssueFilter filter, @ModelAttribute("dates") ListDateFilter dates,
            Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("statuses", IssueStatus.values());
        model.addAttribute("attentions", new AttentionKind[] {AttentionKind.OPEN_WITH_DELIVERABLE,
            AttentionKind.RESOLVED_WITHOUT_DELIVERABLE, AttentionKind.OPEN_NO_OWNER});
        model.addAttribute("severities", choices.codes(CodeGroup.ISSUE_SEVERITY));
        model.addAttribute("categories", choices.codes(CodeGroup.ISSUE_CATEGORY));
        model.addAttribute("sizes", Paging.SIZES);
        boolean hasFilters = StringUtils.hasText(filter.status()) || StringUtils.hasText(filter.severity())
                || StringUtils.hasText(filter.category()) || StringUtils.hasText(filter.attention())
                || StringUtils.hasText(filter.owner()) || StringUtils.hasText(filter.taskId())
                || StringUtils.hasText(filter.projectVersion()) || dates.hasRange();
        model.addAttribute("hasFilters", hasFilters);
        model.addAttribute("resetHref", links.projectHref("/issues", project.selected()));
        if (project.selected() != null) {
            choices.versionFilter(model, project.selected(), filter.projectVersion());
            var page = lists.issues(new IssuesQuery(project.selected(), filter.status(), filter.severity(),
                    filter.category(), null, null, filter.owner(), filter.attention(), filter.taskId(), null,
                    Paging.limitOf(filter.limit()), filter.cursor(), filter.projectVersion()), dates.query(false), true);
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("status", filter.status());
            filters.put("severity", filter.severity());
            filters.put("category", filter.category());
            filters.put("attention", filter.attention());
            filters.put("owner", filter.owner());
            filters.put("taskId", filter.taskId());
            filters.put("projectVersion", filter.projectVersion());
            dates.populate(model, "/issues", filters, filter.limit(), "created");
            model.addAttribute("page", page);
            model.addAttribute("paging", Paging.of("/issues", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
        }
        return "issues";
    }

    @GetMapping("/issues/{id}")
    String detail(@PathVariable("id") String id, Model model) {
        model.addAllAttributes(issueModel(id));
        return "issue";
    }

    @PostMapping("/issues/{id}/versions")
    String version(@PathVariable("id") String id, @RequestParam String name,
            @RequestParam(defaultValue = "false") boolean remove, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "issue", () -> issueModel(id));
        versions.changeIssueLink(id, name, !remove);
        flash.addFlashAttribute("flashKey", "versions.issueSaved");
        return "redirect:/issues/{id}#project-versions";
    }

    private Map<String, Object> issueModel(String id) {
        var issue = details.issue(id);
        var projectVersions = versions.list(issue.projectId());
        return Map.of("issue", issue,
                "linkedVersions", projectVersions.stream().filter(v -> issue.projectVersions().contains(v.name())).toList(),
                "availableVersions", projectVersions.stream().filter(v -> !issue.projectVersions().contains(v.name())).toList());
    }
}
