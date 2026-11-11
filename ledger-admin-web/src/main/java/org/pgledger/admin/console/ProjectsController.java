package org.pgledger.admin.console;

import org.pgledger.admin.security.OwnerActors;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.read.CatalogService;
import org.pgledger.application.read.CatalogService.ProjectsQuery;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** The projects: the list and the form that adds one (stamped {@code user:owner} by the service, recorded with the signed-in owner). */
@Controller
public class ProjectsController {

    private static final int ALL = 1000;

    private final CatalogService catalog;
    private final ProjectService projects;

    public ProjectsController(CatalogService catalog, ProjectService projects) {
        this.catalog = catalog;
        this.projects = projects;
    }

    @GetMapping("/projects")
    String page(Model model) {
        model.addAttribute("projects", catalog.projects(new ProjectsQuery(ALL, null)).items());
        return "projects";
    }

    @PostMapping("/projects")
    String add(@ModelAttribute ProjectCommand command, Authentication authentication) {
        projects.add(command, OwnerActors.of(authentication));
        return "redirect:/projects";
    }
}
