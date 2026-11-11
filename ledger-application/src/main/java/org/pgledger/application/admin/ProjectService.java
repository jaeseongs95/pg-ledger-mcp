package org.pgledger.application.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.ProjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** Projects, used by the admin web and the CLI. A new project is stamped {@code user:owner} and starts at 0. */
@Service
@Transactional
@Validated
public class ProjectService {

    /** A project to add: id, code (the prefix of its defect and task ids) and title. */
    public record ProjectCommand(@NotNull(message = "{reason.REQUIRED}") String id,
            @NotNull(message = "{reason.REQUIRED}") String code, String title) {
    }

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    public Project add(@Valid ProjectCommand command) {
        ProjectId id = ProjectId.of(command.id());
        ProjectCode code = ProjectCode.of(command.code());
        Project project = Project.open(id, code, command.title());
        if (projects.existsById(id) || projects.findByCode(code).isPresent()) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("project " + id.value()));
        }
        try {
            return ScopedValue.where(org.pgledger.domain.context.ActorScope.CURRENT, AuthorId.OWNER)
                    .call(() -> projects.insert(project));
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("project " + id.value()), e);
        }
    }

    @Transactional(readOnly = true)
    public List<Project> list() {
        return projects.findAllByOrderByCode();
    }
}
