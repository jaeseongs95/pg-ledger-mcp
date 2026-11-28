// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import org.veridger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Keeps milestone visibility separate from the criteria and their history. */
@Repository
public class MilestoneArchiveRepository {

    static final String ARCHIVED = "EXISTS (SELECT 1 FROM milestone_archive ma "
            + "WHERE ma.project_id = c.project_id AND ma.milestone_name = c.milestone_name AND ma.archived = 1)";
    static final String ACTIVE = "NOT " + ARCHIVED;

    private final JdbcClient jdbc;

    public MilestoneArchiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasCriteria(ProjectId project, String milestone) {
        return forMilestone("SELECT COUNT(*) FROM milestone_criterion WHERE project_id = :project "
                + "AND milestone_name = :milestone", project, milestone).query(Long.class).single() > 0;
    }

    /** The caller holds a write pin on the project so that transitions and their audit entries are serialized. */
    public boolean change(ProjectId project, String milestone, boolean archived) {
        var previous = forMilestone("SELECT archived FROM milestone_archive WHERE project_id = :project "
                + "AND milestone_name = :milestone", project, milestone).query(Integer.class).optional();
        int next = archived ? 1 : 0;
        if (previous.orElse(0) == next) {
            return false;
        }
        String sql = previous.isPresent()
                ? "UPDATE milestone_archive SET archived = :archived WHERE project_id = :project AND milestone_name = :milestone"
                : "INSERT INTO milestone_archive (project_id, milestone_name, archived) VALUES (:project, :milestone, :archived)";
        forMilestone(sql, project, milestone).param("archived", next).update();
        return true;
    }

    private JdbcClient.StatementSpec forMilestone(String sql, ProjectId project, String milestone) {
        return jdbc.sql(sql).param("project", project.value()).param("milestone", milestone);
    }
}
