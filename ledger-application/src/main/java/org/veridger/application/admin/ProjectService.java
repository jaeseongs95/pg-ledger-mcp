// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Objects;
import org.veridger.database.repository.ProjectRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.LookupException;
import org.veridger.domain.model.Project;
import org.veridger.domain.rule.OwnerAction;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.OwnerIdentity;
import org.veridger.domain.value.ProjectCode;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TextRule;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * Projects, used by the admin web and the CLI. A new project is stamped {@code user:owner} and starts at 0; who added it
 * is recorded in the audit trail of the owner (an audit row is written in the transaction of the insert).
 */
@Service
@Transactional
@Validated
public class ProjectService {

    /** A project to add: id, code (the prefix of its issue, spec, and task ids) and title. */
    public record ProjectCommand(@NotNull(message = "{reason.REQUIRED}") String id,
            @NotNull(message = "{reason.REQUIRED}") String code, String title, String displayName) {

        public ProjectCommand(String id, String code, String title) {
            this(id, code, title, null);
        }
    }

    public record RenameCommand(String id, String displayName, @NotNull(message = "{reason.REQUIRED}") Long version) {
    }

    public record EditCommand(String id, String displayName, String title,
            @NotNull(message = "{reason.REQUIRED}") Long version) {
    }

    private final ProjectRepository projects;
    private final OwnerAuditWriter audit;

    public ProjectService(ProjectRepository projects, OwnerAuditWriter audit) {
        this.projects = projects;
        this.audit = audit;
    }

    public Project add(@Valid ProjectCommand command, OwnerIdentity actor) {
        ProjectId id = ProjectId.of(command.id());
        ProjectCode code = ProjectCode.of(command.code());
        Project project = Project.open(id, code, command.title(), command.displayName());
        if (projects.existsById(id) || projects.findByCode(code).isPresent()) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("project " + id.value()));
        }
        Project added;
        try {
            added = ScopedValue.where(org.veridger.domain.context.ActorScope.CURRENT, AuthorId.OWNER)
                    .call(() -> projects.insert(project));
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("project " + id.value()), e);
        }
        audit.record(actor, OwnerAction.PROJECT_ADDED, id.value(), "code=" + code.value());
        return added;
    }

    public void rename(@Valid RenameCommand command, OwnerIdentity actor) {
        ProjectId id = ProjectId.of(command.id());
        String name = TextRule.PROJECT_NAME.required(command.displayName());
        Project current = projects.findById(id)
                .filter(project -> Objects.equals(project.id(), id))
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("project", id.value())));
        update(current, name, current.title(), command.version(), actor, OwnerAction.PROJECT_RENAMED);
    }

    public void edit(@Valid EditCommand command, OwnerIdentity actor) {
        ProjectId id = ProjectId.of(command.id());
        String name = TextRule.PROJECT_NAME.required(command.displayName());
        String title = TextRule.PROJECT_TITLE.required(command.title());
        Project current = projects.findById(id)
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("project", id.value())));
        update(current, name, title, command.version(), actor, OwnerAction.PROJECT_EDITED);
    }

    private void update(Project current, String name, String title, long version, OwnerIdentity actor,
            OwnerAction action) {
        ProjectId id = current.id();
        if (Objects.equals(name, current.displayName()) && Objects.equals(title, current.title())) {
            return;
        }
        if (!projects.edit(id, name, title, version)) {
            throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION, List.of("project", id.value()));
        }
        audit.record(actor, action, id.value(), action == OwnerAction.PROJECT_RENAMED ? name
                : "name=" + name + ", description=" + title);
    }

    @Transactional(readOnly = true)
    public List<Project> list() {
        return projects.findAllByOrderByCode();
    }
}
