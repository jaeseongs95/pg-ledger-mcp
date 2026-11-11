package org.pgledger.admin.console;

import java.util.LinkedHashMap;
import java.util.Map;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.HistoryService.HistoryQuery;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

/** The append-only history, newest rows last (ordered by history id), filtered and paged. */
@Controller
public class HistoryController {

    /** The filters and the paging position of the history list; an absent project means every project. */
    public record HistoryFilter(String project, String entity, String entityId, String op, String authorId,
            Integer limit, String cursor, String back) {
    }

    private final Choices choices;
    private final HistoryService history;

    public HistoryController(Choices choices, HistoryService history) {
        this.choices = choices;
        this.history = history;
    }

    @GetMapping("/history")
    String list(@ModelAttribute("filter") HistoryFilter filter, Model model) {
        var page = history.history(new HistoryQuery(null, null, null, filter.entity(), filter.entityId(),
                filter.project(), filter.op(), filter.authorId(), null, null, null, Paging.limitOf(filter.limit()),
                filter.cursor()));
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("project", filter.project());
        filters.put("entity", filter.entity());
        filters.put("entityId", filter.entityId());
        filters.put("op", filter.op());
        filters.put("authorId", filter.authorId());
        model.addAttribute("choice", choices.project(null));
        model.addAttribute("entities", HistoryEntity.values());
        model.addAttribute("ops", HistoryOp.values());
        model.addAttribute("sizes", Paging.SIZES);
        model.addAttribute("page", page);
        model.addAttribute("paging", Paging.of("/history", filters, filter.limit(), filter.cursor(), filter.back(),
                page.nextCursor()));
        return "history";
    }
}
