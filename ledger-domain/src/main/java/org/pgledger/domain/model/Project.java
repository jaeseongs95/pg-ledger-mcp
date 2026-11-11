package org.pgledger.domain.model;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TextRule;

/**
 * A project. The two counters hold the last number issued for defects and tasks; they are advanced only by the
 * id allocator (a single UPDATE), never by saving this record, which is insert-only.
 */
@Table("project")
public record Project(@Id ProjectId id, ProjectCode code, String title, long nextDefectNo, long nextTaskNo,
        @CreatedBy @InsertOnlyProperty ActorId createdBy) {

    public Project {
        title = TextRule.PROJECT_TITLE.required(title);
    }

    /** A new project with both counters at 0. */
    public static Project open(ProjectId id, ProjectCode code, String title) {
        return new Project(id, code, title, 0, 0, null);
    }
}
