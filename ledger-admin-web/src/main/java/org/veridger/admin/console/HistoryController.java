// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.util.LinkedHashMap;
import java.util.Map;
import org.veridger.application.read.HistoryService;
import org.veridger.application.read.HistoryService.HistoryQuery;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

/** The append-only history, filtered and paged with an optional date order. */
@Controller
public class HistoryController {

    /** The filters and the paging position of the history list; an absent project means every project. */
    public record HistoryFilter(String project, String entity, String entityId, String op, String authorId,
            String projectVersion, Integer limit, String cursor, String back) {
    }

    private final Choices choices;
    private final HistoryService history;
    private final AttachmentPanels attachments;

    public HistoryController(Choices choices, HistoryService history, AttachmentPanels attachments) {
        this.choices = choices;
        this.history = history;
        this.attachments = attachments;
    }

    @GetMapping("/history")
    String list(@ModelAttribute("filter") HistoryFilter filter, @ModelAttribute("dates") ListDateFilter dates,
            Model model) {
        var page = history.history(new HistoryQuery(null, null, null, filter.entity(), filter.entityId(),
                filter.project(), filter.op(), filter.authorId(), null, null, null, Paging.limitOf(filter.limit()),
                filter.cursor()), dates.query(true), true, filter.projectVersion());
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("project", filter.project());
        filters.put("projectVersion", filter.projectVersion());
        filters.put("entity", filter.entity());
        filters.put("entityId", filter.entityId());
        filters.put("op", filter.op());
        filters.put("authorId", filter.authorId());
        dates.populate(model, "/history", filters, filter.limit(), null);
        model.addAttribute("choice", choices.project(null));
        choices.versionFilter(model, filter.project(), filter.projectVersion());
        model.addAttribute("entities", HistoryEntity.values());
        model.addAttribute("ops", HistoryOp.values());
        model.addAttribute("sizes", Paging.SIZES);
        model.addAttribute("page", page);
        model.addAttribute("itemVersions", history.projectVersions(page.items()));
        boolean hasFilters = StringUtils.hasText(filter.project()) || StringUtils.hasText(filter.entity())
                || StringUtils.hasText(filter.entityId()) || StringUtils.hasText(filter.op())
                || StringUtils.hasText(filter.authorId()) || StringUtils.hasText(filter.projectVersion()) || dates.hasRange();
        model.addAttribute("hasFilters", hasFilters);
        model.addAttribute("resetHref", "/history");
        Map<Long, String> pointing = new LinkedHashMap<>();
        for (var row : page.items()) {
            if (row.attachmentId() != null) {
                pointing.put(row.id(), row.attachmentId());
            }
        }
        model.addAttribute("attachments", attachments.panelsOf(pointing));
        model.addAttribute("paging", Paging.of("/history", filters, filter.limit(), filter.cursor(), filter.back(),
                page.nextCursor()));
        return "history";
    }
}
