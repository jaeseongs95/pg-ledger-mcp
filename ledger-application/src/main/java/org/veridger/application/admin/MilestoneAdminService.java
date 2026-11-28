// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.admin;

import java.util.List;
import org.veridger.database.repository.MilestoneArchiveRepository;
import org.veridger.database.repository.ProjectRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.LookupException;
import org.veridger.domain.rule.OwnerAction;
import org.veridger.domain.value.OwnerIdentity;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TextRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MilestoneAdminService {

    private final ProjectRepository projects;
    private final MilestoneArchiveRepository archive;
    private final OwnerAuditWriter audit;

    public MilestoneAdminService(ProjectRepository projects, MilestoneArchiveRepository archive, OwnerAuditWriter audit) {
        this.projects = projects;
        this.archive = archive;
        this.audit = audit;
    }

    public void setArchived(String project, String name, boolean archived, OwnerIdentity actor) {
        ProjectId id = ProjectId.of(project);
        String milestone = TextRule.MILESTONE_NAME.required(name);
        var current = projects.findById(id)
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("project", id.value())));
        if (!projects.pinVersion(id, current.version())) {
            throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION, List.of("project", id.value()));
        }
        if (!archive.hasCriteria(id, milestone)) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of("milestone", milestone));
        }
        if (archive.change(id, milestone, archived)) {
            audit.record(actor, archived ? OwnerAction.MILESTONE_ARCHIVED : OwnerAction.MILESTONE_RESTORED,
                    id.value(), milestone);
        }
    }
}
