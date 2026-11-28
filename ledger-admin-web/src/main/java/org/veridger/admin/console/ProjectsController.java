// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.veridger.admin.web.AdminHttpExceptionAdvice;
import org.veridger.application.admin.ProjectService;
import org.veridger.application.admin.ProjectService.EditCommand;
import org.veridger.application.admin.ProjectService.ProjectCommand;
import org.veridger.application.admin.ProjectService.RenameCommand;
import org.veridger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The projects: the list and the form that adds one (stamped {@code user:owner} by the service, recorded with the signed-in owner). */
@Controller
public class ProjectsController {

    private final Choices choices;
    private final ProjectService projects;

    public ProjectsController(Choices choices, ProjectService projects) {
        this.choices = choices;
        this.projects = projects;
    }

    @GetMapping("/projects")
    String page(Model model) {
        model.addAttribute("projects", choices.projects());
        return "projects";
    }

    @PostMapping("/projects")
    String add(@ModelAttribute ProjectCommand command, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "projects", () -> recovery("projectForm", command));
        projects.add(command, actor);
        flash.addFlashAttribute("flashKey", "flash.projectAdded");
        return "redirect:/projects";
    }

    @PostMapping("/projects/rename")
    String rename(@ModelAttribute RenameCommand command, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "projects", () -> recovery("editForm", new EditCommand(command.id(), command.displayName(),
                choices.projects().stream().filter(p -> Objects.equals(p.id(), command.id())).map(p -> p.title())
                        .findFirst().orElse(""), command.version())));
        projects.rename(command, actor);
        flash.addFlashAttribute("flashKey", "flash.projectRenamed");
        return "redirect:/projects";
    }

    @PostMapping("/projects/edit")
    String edit(@ModelAttribute EditCommand command, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "projects", () -> recovery("editForm", command));
        projects.edit(command, actor);
        flash.addFlashAttribute("flashKey", "flash.projectEdited");
        return "redirect:/projects";
    }

    private Map<String, Object> recovery(String key, Object form) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("projects", choices.projects());
        model.put(key, form);
        return model;
    }
}
