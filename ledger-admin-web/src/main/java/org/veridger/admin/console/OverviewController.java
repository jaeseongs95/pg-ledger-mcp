// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.util.List;
import org.veridger.application.admin.ProjectVersionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The home page: the MCP server, the counts per state and what needs attention. */
@Controller
public class OverviewController {

    private final OverviewService overview;

    private final ProjectVersionService versions;

    public OverviewController(OverviewService overview, ProjectVersionService versions) {
        this.overview = overview;
        this.versions = versions;
    }

    @GetMapping("/")
    String page(@RequestParam(name = "project", required = false) String project, Model model) {
        OverviewService.ConsoleOverview console = overview.consoleOverview(project);
        model.addAttribute("console", console);
        model.addAttribute("overview", console.base());
        model.addAttribute("projectNames", Choices.projectNames(console.base().projects()));
        String scope = console.base().project();
        model.addAttribute("versionReadiness", scope == null ? List.of() : versions.readiness(scope));
        return "overview";
    }
}
