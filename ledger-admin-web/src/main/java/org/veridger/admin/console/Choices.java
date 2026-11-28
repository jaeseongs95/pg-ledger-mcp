// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.veridger.application.admin.ProjectVersionService;
import org.veridger.application.read.Page;
import org.veridger.application.read.CatalogService;
import org.veridger.application.read.CatalogService.CodeView;
import org.veridger.application.read.CatalogService.CodesQuery;
import org.veridger.application.read.CatalogService.ProjectView;
import org.veridger.application.read.CatalogService.ProjectsQuery;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.ProjectId;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;

/**
 * The lists the pages and the filter forms offer whole: the projects, the codes of every group, and the active codes of a group.
 * The services page them (at most {@link #ALL} rows a page), so each list follows the cursors to the last page: a list that
 * stopped at the first page would drop the rest without a word.
 */
@Component
public class Choices {

    /** The most rows one page of a list asks for. */
    public static final int ALL = 1000;

    private final CatalogService catalog;
    private final ProjectVersionService versions;

    public Choices(CatalogService catalog, ProjectVersionService versions) {
        this.catalog = catalog;
        this.versions = versions;
    }

    /** The projects, and the one the page works on: the one asked for, else the first (absent when there is none). */
    public record ProjectChoice(List<ProjectView> projects, String selected) {
        public boolean listed() {
            return projects.stream().anyMatch(project -> Objects.equals(project.id(), selected));
        }
    }

    public ProjectChoice project(String requested) {
        List<ProjectView> projects = projects();
        String selected = StringUtils.hasText(requested) ? requested
                : projects.isEmpty() ? null : projects.getFirst().id();
        return new ProjectChoice(projects, selected);
    }

    /** Every project. */
    public List<ProjectView> projects() {
        return all(cursor -> catalog.projects(new ProjectsQuery(ALL, cursor)));
    }

    void versionFilter(Model model, String project, String selected) {
        var names = StringUtils.hasText(project) ? versionNames(project) : List.<String>of();
        model.addAttribute("versionOptions", names);
        model.addAttribute("versionListed", !StringUtils.hasText(selected) || names.contains(selected));
    }

    List<String> versionNames(String project) {
        return versions.list(project).stream().map(ProjectVersionService.Version::name).toList();
    }

    <T> void itemVersions(Model model, String project, HistoryEntity kind, List<T> rows, Function<T, String> id) {
        model.addAttribute("itemVersions", versions.namesForItems(project, kind, rows.stream().map(id).toList()));
    }

    /** Display names for a page that already loaded its project choices. */
    static Map<String, String> projectNames(List<ProjectView> projects) {
        Map<String, String> names = new LinkedHashMap<>();
        projects.forEach(project -> names.put(project.id(), project.displayName()));
        return names;
    }

    /** The display name for a detail page linked to a single project. */
    public String projectName(String id) {
        return catalog.projectName(ProjectId.of(id));
    }

    /** The active codes of a group. */
    public List<CodeView> codes(CodeGroup group) {
        return all(cursor -> catalog.codes(new CodesQuery(group.name(), false, ALL, cursor)));
    }

    /** Every code of every group, the retired ones too. */
    public List<CodeView> allCodes() {
        return all(cursor -> catalog.codes(new CodesQuery(null, true, ALL, cursor)));
    }

    /** The items of every page of a list, from the first to the page that has no next cursor. */
    private static <T> List<T> all(Function<String, Page<T>> pages) {
        List<T> items = new ArrayList<>();
        String cursor = null;
        do {
            Page<T> page = pages.apply(cursor);
            items.addAll(page.items());
            cursor = page.truncated() ? page.nextCursor() : null;
        } while (cursor != null);
        return items;
    }
}
