package org.pgledger.domain.model;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TextRule;

/**
 * A project. The two counters hold the last number issued for defects and tasks; they are advanced only by the
 * id allocator (a single UPDATE) and are never written by saving this record (they start at 0 in the database).
 * {@code version} is 0 until the first save, which stores 1.
 */
@Table("project")
public record Project(@Id ProjectId id, ProjectCode code, String title, @ReadOnlyProperty long nextDefectNo,
        @ReadOnlyProperty long nextTaskNo, @Version long version, @CreatedBy @InsertOnlyProperty ActorId createdBy) {

    public Project {
        title = TextRule.PROJECT_TITLE.required(title);
    }

    /** A new project; the counters start at 0 in the database. */
    public static Project open(ProjectId id, ProjectCode code, String title) {
        return new Project(id, code, title, 0, 0, 0, null);
    }
}
