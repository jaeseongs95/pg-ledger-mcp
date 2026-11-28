// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.util.LinkedHashMap;
import java.util.Map;
import org.veridger.application.read.DetailService;
import org.veridger.application.read.ListService;
import org.veridger.application.read.ListService.SpecsQuery;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.SpecStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

/** The specifications of a project (filtered, paged) and the detail of one with its deliverables and recent history. */
@Controller
public class SpecsController {

    /** The filters and the paging position of the specification list. */
    public record SpecFilter(String project, String status, String priority, String owner, String attention,
            String projectVersion, Integer limit, String cursor, String back) {
    }

    private final Choices choices;
    private final ListService lists;
    private final DetailService details;
    private final Links links;

    public SpecsController(Choices choices, ListService lists, DetailService details, Links links) {
        this.choices = choices;
        this.lists = lists;
        this.details = details;
        this.links = links;
    }

    @GetMapping("/specs")
    String list(@ModelAttribute("filter") SpecFilter filter, @ModelAttribute("dates") ListDateFilter dates,
            Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("statuses", SpecStatus.values());
        model.addAttribute("attentions", new AttentionKind[] {AttentionKind.SPEC_COMPLETED_NOT_VERIFIED});
        model.addAttribute("sizes", Paging.SIZES);
        boolean hasFilters = StringUtils.hasText(filter.status()) || StringUtils.hasText(filter.priority())
                || StringUtils.hasText(filter.owner()) || StringUtils.hasText(filter.attention())
                || StringUtils.hasText(filter.projectVersion()) || dates.hasRange();
        model.addAttribute("hasFilters", hasFilters);
        model.addAttribute("resetHref", links.projectHref("/specs", project.selected()));
        if (project.selected() != null) {
            choices.versionFilter(model, project.selected(), filter.projectVersion());
            var page = lists.specs(new SpecsQuery(project.selected(), filter.status(), null, filter.priority(),
                    filter.owner(), filter.attention(), Paging.limitOf(filter.limit()), filter.cursor(),
                    filter.projectVersion()), dates.query(false), true);
            choices.itemVersions(model, project.selected(), HistoryEntity.SPEC, page.items(), ListService.SpecItem::id);
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("projectVersion", filter.projectVersion());
            filters.put("status", filter.status());
            filters.put("priority", filter.priority());
            filters.put("owner", filter.owner());
            filters.put("attention", filter.attention());
            dates.populate(model, "/specs", filters, filter.limit(), "created");
            model.addAttribute("page", page);
            model.addAttribute("paging", Paging.of("/specs", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
        }
        return "specs";
    }

    /**
     * The page of one specification. The id is a query parameter and not a path segment: specification ids may be free
     * text containing characters (a slash, a number sign, a question mark) that a path segment cannot carry.
     */
    @GetMapping("/specs/detail")
    String detail(@RequestParam("id") String id, Model model) {
        var spec = details.spec(id);
        model.addAttribute("spec", spec);
        model.addAttribute("relatedVersions", spec.relatedVersions());
        return "spec";
    }
}
