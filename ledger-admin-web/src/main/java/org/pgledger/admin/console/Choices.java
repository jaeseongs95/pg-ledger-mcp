package org.pgledger.admin.console;

import java.util.List;
import org.pgledger.application.read.CatalogService;
import org.pgledger.application.read.CatalogService.CodeView;
import org.pgledger.application.read.CatalogService.CodesQuery;
import org.pgledger.application.read.CatalogService.ProjectView;
import org.pgledger.application.read.CatalogService.ProjectsQuery;
import org.pgledger.domain.rule.CodeGroup;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** The lists the filter forms offer: the projects, and the active codes of a group. */
@Component
public class Choices {

    /** The most rows one list asks for: every project and every code of the lists the pages offer. */
    public static final int ALL = 1000;

    private final CatalogService catalog;

    public Choices(CatalogService catalog) {
        this.catalog = catalog;
    }

    /** The projects, and the one the page works on: the one asked for, else the first (absent when there is none). */
    public record ProjectChoice(List<ProjectView> projects, String selected) {
    }

    public ProjectChoice project(String requested) {
        List<ProjectView> projects = catalog.projects(new ProjectsQuery(ALL, null)).items();
        String selected = StringUtils.hasText(requested) ? requested
                : projects.isEmpty() ? null : projects.getFirst().id();
        return new ProjectChoice(projects, selected);
    }

    public List<CodeView> codes(CodeGroup group) {
        return catalog.codes(new CodesQuery(group.name(), false, ALL, null)).items();
    }
}
