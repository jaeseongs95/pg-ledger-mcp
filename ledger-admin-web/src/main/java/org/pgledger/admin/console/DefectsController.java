package org.pgledger.admin.console;

import java.util.LinkedHashMap;
import java.util.Map;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.ListService.DefectsQuery;
import org.pgledger.application.read.StatsService;
import org.pgledger.application.read.StatsService.StatsQuery;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.DefectStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

/** The defects of a project (filtered, paged) and the detail of one with its recent history. */
@Controller
public class DefectsController {

    /** The filters and the paging position of the list, as the query string carries them. */
    public record DefectFilter(String project, String status, String severity, String category, String attention,
            String owner, Integer limit, String cursor, String back) {
    }

    private final Choices choices;
    private final ListService lists;
    private final DetailService details;
    private final StatsService stats;

    public DefectsController(Choices choices, ListService lists, DetailService details, StatsService stats) {
        this.choices = choices;
        this.lists = lists;
        this.details = details;
        this.stats = stats;
    }

    @GetMapping("/defects")
    String list(@ModelAttribute("filter") DefectFilter filter, Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("statuses", DefectStatus.values());
        model.addAttribute("attentions", new AttentionKind[] {AttentionKind.OPEN_WITH_FIX,
            AttentionKind.FIXED_WITHOUT_COMMIT, AttentionKind.OPEN_NO_OWNER});
        model.addAttribute("severities", choices.codes(CodeGroup.DEFECT_SEVERITY));
        model.addAttribute("categories", choices.codes(CodeGroup.DEFECT_CATEGORY));
        model.addAttribute("sizes", Paging.SIZES);
        if (project.selected() != null) {
            var page = lists.defects(new DefectsQuery(project.selected(), filter.status(), filter.severity(),
                    filter.category(), filter.attention(), filter.owner(), Paging.limitOf(filter.limit()),
                    filter.cursor()));
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("status", filter.status());
            filters.put("severity", filter.severity());
            filters.put("category", filter.category());
            filters.put("attention", filter.attention());
            filters.put("owner", filter.owner());
            model.addAttribute("page", page);
            model.addAttribute("paging", Paging.of("/defects", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
            model.addAttribute("total", stats.stats(new StatsQuery(project.selected(), null)).defects());
        }
        return "defects";
    }

    @GetMapping("/defects/{id}")
    String detail(@PathVariable("id") String id, Model model) {
        model.addAttribute("defect", details.defect(id));
        return "defect";
    }
}
