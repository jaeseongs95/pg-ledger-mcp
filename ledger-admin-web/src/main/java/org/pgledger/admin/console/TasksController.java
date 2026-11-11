package org.pgledger.admin.console;

import java.util.LinkedHashMap;
import java.util.Map;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.ListService.ConditionsQuery;
import org.pgledger.application.read.ListService.TasksQuery;
import org.pgledger.application.read.StatsService;
import org.pgledger.application.read.StatsService.StatsQuery;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.TaskState;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

/** The tasks and the release conditions of a project (filtered, paged) and the detail of a task. */
@Controller
public class TasksController {

    /** The filters and the paging position of the task list. */
    public record TaskFilter(String project, String state, String owner, String attention, Integer limit,
            String cursor, String back) {
    }

    /** The filters and the paging position of the condition list. */
    public record ConditionFilter(String project, String state, String release, Integer limit, String cursor,
            String back) {
    }

    private final Choices choices;
    private final ListService lists;
    private final DetailService details;
    private final StatsService stats;

    public TasksController(Choices choices, ListService lists, DetailService details, StatsService stats) {
        this.choices = choices;
        this.lists = lists;
        this.details = details;
        this.stats = stats;
    }

    @GetMapping("/tasks")
    String tasks(@ModelAttribute("filter") TaskFilter filter, Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("states", TaskState.values());
        model.addAttribute("attentions", new AttentionKind[] {AttentionKind.TASK_UNKNOWN,
            AttentionKind.DONE_NOT_VERIFIED});
        model.addAttribute("sizes", Paging.SIZES);
        if (project.selected() != null) {
            var page = lists.tasks(new TasksQuery(project.selected(), filter.state(), filter.owner(),
                    filter.attention(), Paging.limitOf(filter.limit()), filter.cursor()));
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("state", filter.state());
            filters.put("owner", filter.owner());
            filters.put("attention", filter.attention());
            model.addAttribute("page", page);
            model.addAttribute("paging", Paging.of("/tasks", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
            model.addAttribute("total", stats.stats(new StatsQuery(project.selected(), null)).tasks());
        }
        return "tasks";
    }

    @GetMapping("/tasks/{id}")
    String task(@PathVariable("id") String id, Model model) {
        model.addAttribute("task", details.task(id));
        return "task";
    }

    @GetMapping("/conditions")
    String conditions(@ModelAttribute("filter") ConditionFilter filter, Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("states", ConditionState.values());
        model.addAttribute("sizes", Paging.SIZES);
        if (project.selected() != null) {
            var page = lists.conditions(new ConditionsQuery(project.selected(), filter.state(), filter.release(),
                    Paging.limitOf(filter.limit()), filter.cursor()));
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("state", filter.state());
            filters.put("release", filter.release());
            model.addAttribute("page", page);
            model.addAttribute("paging", Paging.of("/conditions", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
            model.addAttribute("total", stats.stats(new StatsQuery(project.selected(), null)).conditions());
        }
        return "conditions";
    }
}
