// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.veridger.admin.web.AdminHttpExceptionAdvice;
import org.veridger.application.admin.ProjectVersionService;

@Controller
public class ProjectVersionsController {

    public record VersionForm(String project, String originalName, String name, String description) {}

    private final Choices choices;
    private final ProjectVersionService versions;

    public ProjectVersionsController(Choices choices, ProjectVersionService versions) {
        this.choices = choices;
        this.versions = versions;
    }

    @GetMapping("/projects/versions")
    String page(@RequestParam(required = false) String project, Model model) {
        model.addAllAttributes(pageModel(project));
        return "versions";
    }

    @GetMapping("/projects/versions/options")
    @ResponseBody
    List<String> options(@RequestParam("project") String project) {
        return choices.versionNames(project);
    }

    @PostMapping("/projects/versions")
    String add(@ModelAttribute VersionForm form, HttpServletRequest request, RedirectAttributes flash) {
        recover(request, form, true);
        versions.add(form.project(), form.name(), form.description());
        return success(form, flash);
    }

    @PostMapping("/projects/versions/edit")
    String edit(@ModelAttribute VersionForm form, HttpServletRequest request, RedirectAttributes flash) {
        recover(request, form, false);
        versions.update(form.project(), form.originalName(), form.name(), form.description());
        return success(form, flash);
    }

    private static String success(VersionForm form, RedirectAttributes flash) {
        flash.addAttribute("project", form.project());
        flash.addFlashAttribute("flashKey", "versions.saved");
        return "redirect:/projects/versions";
    }

    private void recover(HttpServletRequest request, VersionForm form, boolean adding) {
        AdminHttpExceptionAdvice.recoverHtml(request, "versions", () -> {
            Map<String, Object> model = pageModel(form.project());
            model.put(adding ? "addForm" : "editForm", form);
            return model;
        });
    }

    private Map<String, Object> pageModel(String requested) {
        Choices.ProjectChoice choice = choices.project(requested);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("choice", choice);
        if (choice.selected() != null) {
            model.put("versions", versions.list(choice.selected()));
        }
        return model;
    }
}
