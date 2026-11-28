// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.veridger.application.admin.MilestoneAdminService;
import org.veridger.application.read.DetailService;
import org.veridger.application.read.ListService;
import org.veridger.application.read.ListService.CriteriaQuery;
import org.veridger.application.read.ListService.TasksQuery;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The tasks and the milestone criteria of a project (filtered, paged) and the detail of a task. */
@Controller
public class TasksController {

    /** The filters and the paging position of the task list. */
    public record TaskFilter(String project, String state, String owner, String attention, String projectVersion, Integer limit,
            String cursor, String back) {
    }

    /** The filters and the paging position of the milestone criteria list. */
    public record CriterionFilter(String project, String state, String milestone, String projectVersion, Integer limit, String cursor,
            String back, Boolean archived) {
        public boolean showsArchived() {
            return Boolean.TRUE.equals(archived);
        }
    }

    private final Choices choices;
    private final ListService lists;
    private final DetailService details;
    private final Links links;
    private final MilestoneAdminService milestones;

    public TasksController(Choices choices, ListService lists, DetailService details, Links links,
            MilestoneAdminService milestones) {
        this.choices = choices;
        this.lists = lists;
        this.details = details;
        this.links = links;
        this.milestones = milestones;
    }

    @GetMapping("/tasks")
    String tasks(@ModelAttribute("filter") TaskFilter filter, @ModelAttribute("dates") ListDateFilter dates,
            Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("states", TaskState.values());
        model.addAttribute("attentions", new AttentionKind[] {AttentionKind.TASK_UNKNOWN,
            AttentionKind.DONE_NOT_VERIFIED});
        model.addAttribute("sizes", Paging.SIZES);
        boolean hasFilters = StringUtils.hasText(filter.state()) || StringUtils.hasText(filter.owner())
                || StringUtils.hasText(filter.attention()) || StringUtils.hasText(filter.projectVersion()) || dates.hasRange();
        model.addAttribute("hasFilters", hasFilters);
        model.addAttribute("resetHref", links.projectHref("/tasks", project.selected()));
        if (project.selected() != null) {
            choices.versionFilter(model, project.selected(), filter.projectVersion());
            var page = lists.tasks(new TasksQuery(project.selected(), filter.state(), filter.owner(),
                    filter.attention(), Paging.limitOf(filter.limit()), filter.cursor(), filter.projectVersion()),
                    dates.query(false), true);
            choices.itemVersions(model, project.selected(), HistoryEntity.TASK, page.items(), ListService.TaskItem::id);
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("projectVersion", filter.projectVersion());
            filters.put("state", filter.state());
            filters.put("owner", filter.owner());
            filters.put("attention", filter.attention());
            dates.populate(model, "/tasks", filters, filter.limit(), "created");
            model.addAttribute("page", page);
            model.addAttribute("paging", Paging.of("/tasks", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
        }
        return "tasks";
    }

    /**
     * The page of one task. The id is a query parameter and not a path segment: ids are free text and may contain
     * characters (a slash, a number sign, a question mark) that a path segment cannot carry, and the web container
     * refuses an encoded slash.
     */
    @GetMapping("/tasks/detail")
    String task(@RequestParam("id") String id, Model model) {
        var task = details.task(id);
        model.addAttribute("task", task);
        model.addAttribute("relatedVersions", task.relatedVersions());
        return "task";
    }

    @GetMapping("/criteria")
    String criteria(@ModelAttribute("filter") CriterionFilter filter, @ModelAttribute("dates") ListDateFilter dates,
            Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("states", CriterionState.values());
        model.addAttribute("sizes", Paging.SIZES);
        boolean hasFilters = StringUtils.hasText(filter.state()) || StringUtils.hasText(filter.milestone())
                || StringUtils.hasText(filter.projectVersion()) || dates.hasRange();
        model.addAttribute("hasFilters", hasFilters);
        model.addAttribute("resetHref", links.statHref(project.selected(), "/criteria", "archived",
                filter.showsArchived() ? "true" : null));
        if (project.selected() != null) {
            choices.versionFilter(model, project.selected(), filter.projectVersion());
            var page = lists.criteria(new CriteriaQuery(project.selected(), filter.state(), filter.milestone(),
                    Paging.limitOf(filter.limit()), filter.cursor(), filter.projectVersion(), filter.showsArchived()),
                    dates.query(false), true);
            choices.itemVersions(model, project.selected(), HistoryEntity.CRITERION, page.items(), ListService.CriterionItem::id);
            var rollups = lists.milestoneRollups(project.selected(), filter.showsArchived(), filter.projectVersion());
            var rawLinkCounts = lists.criterionLinkCounts(
                    page.items().stream().map(ListService.CriterionItem::id).toList());
            Map<String, ListService.CriterionLinkCounts> linkCounts = new LinkedHashMap<>();
            for (ListService.CriterionItem item : page.items()) {
                linkCounts.put(item.id(), rawLinkCounts.getOrDefault(item.id(), ListService.CriterionLinkCounts.ZERO));
            }
            boolean customMilestone = StringUtils.hasText(filter.milestone())
                    && rollups.stream().noneMatch(r -> Objects.equals(r.milestone(), filter.milestone()));
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("projectVersion", filter.projectVersion());
            filters.put("state", filter.state());
            filters.put("milestone", filter.milestone());
            filters.put("archived", filter.showsArchived() ? "true" : null);
            dates.populate(model, "/criteria", filters, filter.limit(), null);
            model.addAttribute("page", page);
            model.addAttribute("rollups", rollups);
            model.addAttribute("customMilestone", customMilestone);
            model.addAttribute("linkCounts", linkCounts);
            model.addAttribute("paging", Paging.of("/criteria", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
        }
        return "criteria";
    }

    @PostMapping("/criteria/milestone/archive")
    String changeMilestone(@RequestParam("project") String project, @RequestParam("milestone") String milestone,
            @RequestParam("archive") boolean archive, @RequestParam(required = false) String projectVersion, OwnerIdentity actor,
            RedirectAttributes flash) {
        milestones.setArchived(project, milestone, archive, actor);
        flash.addFlashAttribute("flashKey", archive ? "flash.milestoneArchived" : "flash.milestoneRestored");
        flash.addAttribute("project", project);
        if (StringUtils.hasText(projectVersion)) {
            flash.addAttribute("projectVersion", projectVersion);
        }
        if (!archive) {
            flash.addAttribute("archived", true);
        }
        return "redirect:/criteria";
    }

    /**
     * The page of one release gate (milestone criterion). Uses a query parameter so free-text ids containing
     * {@code /}, {@code #}, {@code ?}, or {@code :} round-trip cleanly.
     */
    @GetMapping("/criteria/detail")
    String criterion(@RequestParam("id") String id, Model model) {
        var criterion = details.criterion(id);
        model.addAttribute("criterion", criterion);
        model.addAttribute("relatedVersions", criterion.relatedVersions());
        return "criterion";
    }
}
