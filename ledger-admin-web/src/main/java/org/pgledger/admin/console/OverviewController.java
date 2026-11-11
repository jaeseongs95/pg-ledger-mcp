package org.pgledger.admin.console;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The home page: the MCP server, the counts per state and what needs attention. */
@Controller
public class OverviewController {

    private final OverviewService overview;

    public OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/")
    String page(@RequestParam(name = "project", required = false) String project, Model model) {
        model.addAttribute("overview", overview.overview(project));
        return "overview";
    }
}
