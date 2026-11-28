// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.veridger.application.admin.ProjectVersionService.ItemRef;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.database.repository.CommonCodeRepository;
import org.veridger.database.repository.EntityLookupRepository;
import org.veridger.database.repository.ProjectRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.model.CodeCatalog;
import org.veridger.domain.model.Project;
import org.veridger.domain.rule.CodeDirection;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.LinkKind;
import org.veridger.domain.rule.LockOrder;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectCode;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.veridger.domain.value.Tokens;
import org.springframework.stereotype.Component;

/**
 * Plans and acquires all entity and {@code session_grant} locks for a multi-operation {@link BatchService#batch}
 * transaction in the exact global canonical lock order:
 * {@code project (id asc) -> issue (id asc) -> spec (id asc) -> task (id asc) -> milestone_criterion (id asc) ->
 * session_grant (project_id asc, grant_scope asc) -> common_code of the relation kinds (code asc)}.
 */
@Component
class BatchLockPlanner {

    private static final HistoryEntity[] STATEFUL = {
        HistoryEntity.ISSUE, HistoryEntity.SPEC, HistoryEntity.TASK, HistoryEntity.CRITERION
    };

    private final Rows rows;
    private final ProjectRepository projects;
    private final CorrectOperation correctOperation;
    private final WriteGate gate;
    private final CommonCodeRepository codes;
    private final EntityLookupRepository lookup;

    BatchLockPlanner(Rows rows, ProjectRepository projects, CorrectOperation correctOperation, WriteGate gate,
            CommonCodeRepository codes, EntityLookupRepository lookup) {
        this.rows = rows;
        this.projects = projects;
        this.correctOperation = correctOperation;
        this.gate = gate;
        this.codes = codes;
        this.lookup = lookup;
    }

    /**
     * Acquires entity pins (Tiers 1..5) and available {@code session_grant} locks (Tier 6) in strict canonical order
     * before any operation in {@code ops} executes.
     */
    BatchBuffer acquireLocks(AuthorId author, List<BatchOpInput> ops) {
        TreeSet<ProjectId> projectIds = new TreeSet<>(LockOrder.PROJECT);
        TreeSet<IssueId> issueIds = new TreeSet<>(Comparator.comparing(IssueId::value));
        TreeSet<SpecId> specIds = new TreeSet<>(Comparator.comparing(SpecId::value));
        Map<SpecId, ProjectId> hierarchySpecs = new LinkedHashMap<>();
        TreeSet<TaskId> taskIds = new TreeSet<>(Comparator.comparing(TaskId::value));
        TreeSet<CriterionId> criterionIds = new TreeSet<>(Comparator.comparing(CriterionId::value));

        // Phase 0: read-only discovery of all existing entities & projects touched by the batch
        for (BatchOpInput op : ops) {
            discoverEntities(op, projectIds, issueIds, specIds, hierarchySpecs, taskIds, criterionIds);
        }

        // Phase 1: acquire all entity locks in strict global order (Tiers 1..5)
        for (ProjectId p : projectIds) {
            rows.pinProject(p);
        }
        Map<String, ProjectId> issueProjects = new HashMap<>();
        for (IssueId i : issueIds) {
            if (rows.pinIssue(i)) {
                rows.findIssue(i).ifPresent(row -> issueProjects.put(row.id().value(), row.projectId()));
            }
        }
        Map<String, ProjectId> specProjects = new HashMap<>();
        for (SpecId s : specIds) {
            ProjectId expected = hierarchySpecs.get(s);
            if (expected != null) {
                if (rows.pinSpecInProject(s, expected)) {
                    specProjects.put(s.value(), expected);
                } else {
                    ProjectId moved = rows.spec(s).projectId();
                    if (projectIds.contains(moved) && rows.pinSpecInProject(s, moved)) {
                        specProjects.put(s.value(), moved);
                    } else {
                        throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                                List.of(HistoryEntity.SPEC.token(), s.value()));
                    }
                }
            } else {
                specProjects.put(s.value(), rows.pinSpec(s));
            }
        }
        Map<String, ProjectId> taskProjects = new HashMap<>();
        for (TaskId t : taskIds) {
            taskProjects.put(t.value(), rows.pinTask(t));
        }
        Map<String, ProjectId> criterionProjects = new HashMap<>();
        for (CriterionId c : criterionIds) {
            criterionProjects.put(c.value(), rows.pinCriterion(c));
        }

        // Phase 1.5: collect all (project, scope) targets after Phase 1 pins and lock live grants once in canonical order
        Set<Target> candidates = collectGrantTargets(ops, issueProjects, specProjects, taskProjects, criterionProjects);
        Set<Target> authorized = gate.authorizeAvailable(author, candidates);

        // Phase 1.6: the codes of the relation kinds that the batch writes, in the order of their characters
        Map<String, CodeDirection> heldKinds = holdRelationKinds(ops);

        return new BatchBuffer(projectIds, issueIds, specIds, taskIds, criterionIds, authorized, heldKinds);
    }

    /**
     * Holds the code of every relation kind that a link of the batch names (see {@link CommonCodeRepository#holdDirection}),
     * sorted by the characters of the code, so that two batches never hold two codes in two orders. A kind that is not a code
     * of the group is left out: the link refuses it itself.
     */
    private Map<String, CodeDirection> holdRelationKinds(List<BatchOpInput> ops) {
        TreeSet<String> kinds = new TreeSet<>();
        for (BatchOpInput op : ops) {
            if (op instanceof BatchOpInput.Link l && l.kind() != null && CodeValue.FORMAT.matcher(l.kind()).matches()
                    && LinkKind.ofToken(l.kind()) == LinkKind.RELATION) {
                kinds.add(l.kind());
            }
        }
        Map<String, CodeDirection> held = new HashMap<>();
        CodeCatalog catalog = codes.catalog(CodeGroup.RELATION_KIND);
        for (String kind : kinds) {
            Optional<CodeDirection> direction = catalog.directionOf(CodeValue.of(kind));
            if (direction.isPresent()) {
                if (!codes.holdDirection(CodeGroup.RELATION_KIND, CodeValue.of(kind), direction.orElseThrow())) {
                    throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION, List.of("common_code", kind));
                }
                held.put(kind, direction.orElseThrow());
            }
        }
        return held;
    }

    private void discoverEntities(BatchOpInput op, TreeSet<ProjectId> projectIds, TreeSet<IssueId> issueIds,
            TreeSet<SpecId> specIds, Map<SpecId, ProjectId> hierarchySpecs, TreeSet<TaskId> taskIds,
            TreeSet<CriterionId> criterionIds) {
        switch (op) {
            case BatchOpInput.Save s -> discoverSave(s, projectIds, issueIds, specIds, hierarchySpecs, taskIds,
                    criterionIds);
            case BatchOpInput.Transition t -> discoverTransition(t, issueIds, specIds, taskIds, criterionIds);
            case BatchOpInput.RecordDeliverable d -> discoverDeliverable(d, issueIds, specIds, taskIds);
            case BatchOpInput.RecordCase c -> addIssueIfPresent(c.issueId(), issueIds);
            case BatchOpInput.Link l -> discoverLink(l, issueIds, specIds, taskIds, criterionIds);
            case BatchOpInput.Correct c -> discoverCorrect(c, issueIds, specIds, taskIds, criterionIds);
            case BatchOpInput.ProjectVersion v -> projectId(v.projectId()).ifPresent(project -> {
                projectIds.add(project);
                discoverVersionItems(project, v, issueIds, specIds, taskIds, criterionIds);
            });
        }
    }

    private void discoverVersionItems(ProjectId project, BatchOpInput.ProjectVersion change, Set<IssueId> issues,
            Set<SpecId> specs, Set<TaskId> tasks, Set<CriterionId> criteria) {
        Set<ItemRef> selected = ProjectVersionLinks.from(change).items().collect(Collectors.toSet());
        List<String> ids = selected.stream().map(ItemRef::id).toList();
        for (var found : lookup.findAny(ids)) {
            String id = found.row().id();
            if (!Objects.equals(project.value(), found.row().projectId())
                    || !selected.contains(new ItemRef(found.entity(), id))) {
                continue;
            }
            switch (found.entity()) {
                case ISSUE -> issues.add(IssueId.of(id));
                case SPEC -> specs.add(SpecId.of(id));
                case TASK -> tasks.add(TaskId.of(id));
                case CRITERION -> criteria.add(CriterionId.of(id));
                case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE -> {
                }
            }
        }
    }

    private void discoverSave(BatchOpInput.Save s, TreeSet<ProjectId> projectIds, TreeSet<IssueId> issueIds,
            TreeSet<SpecId> specIds, Map<SpecId, ProjectId> hierarchySpecs, TreeSet<TaskId> taskIds,
            TreeSet<CriterionId> criterionIds) {
        Optional<HistoryEntity> parsed = Tokens.find(s.entity(), EntityEditors.SAVEABLE);
        if (parsed.isEmpty()) {
            return;
        }
        HistoryEntity entity = parsed.orElseThrow();
        boolean create = s.expectedVersion() == null || s.expectedVersion() == 0;
        Optional<ProjectId> mentionedProject = mentionedProject(s.fields());
        mentionedProject.filter(projects::existsById).ifPresent(projectIds::add);
        if (create || s.id() == null || s.id().isBlank()) {
            return;
        }
        switch (entity) {
            case ISSUE -> addIssueIfPresent(s.id(), issueIds);
            case SPEC -> SpecId.parse(s.id()).flatMap(rows::findSpec).ifPresent(row -> {
                specIds.add(row.id());
                if (s.fields() != null && (s.fields().projectId().mentioned() || s.fields().parentId().mentioned())) {
                    projectIds.add(row.projectId());
                    hierarchySpecs.putIfAbsent(row.id(), row.projectId());
                }
            });
            case TASK -> addTaskIfPresent(s.id(), taskIds);
            case CRITERION -> addCriterionIfPresent(s.id(), criterionIds);
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE -> {
            }
        }
    }

    private void discoverTransition(BatchOpInput.Transition t, TreeSet<IssueId> issueIds, TreeSet<SpecId> specIds,
            TreeSet<TaskId> taskIds, TreeSet<CriterionId> criterionIds) {
        Optional<HistoryEntity> parsed = Tokens.find(t.entity(), STATEFUL);
        if (parsed.isEmpty()) {
            return;
        }
        switch (parsed.orElseThrow()) {
            case ISSUE -> {
                addIssueIfPresent(t.id(), issueIds);
                addIssueIfPresent(t.mergedInto(), issueIds);
            }
            case SPEC -> addSpecIfPresent(t.id(), specIds);
            case TASK -> addTaskIfPresent(t.id(), taskIds);
            case CRITERION -> addCriterionIfPresent(t.id(), criterionIds);
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE -> {
            }
        }
    }

    private void discoverDeliverable(BatchOpInput.RecordDeliverable d, TreeSet<IssueId> issueIds,
            TreeSet<SpecId> specIds, TreeSet<TaskId> taskIds) {
        Optional<DeliverableTarget> parsed = Tokens.find(d.targetEntity(), DeliverableTarget.values());
        if (parsed.isEmpty()) {
            return;
        }
        switch (parsed.orElseThrow()) {
            case ISSUE -> addIssueIfPresent(d.targetId(), issueIds);
            case SPEC -> addSpecIfPresent(d.targetId(), specIds);
            case TASK -> addTaskIfPresent(d.targetId(), taskIds);
        }
    }

    private void discoverLink(BatchOpInput.Link l, TreeSet<IssueId> issueIds, TreeSet<SpecId> specIds,
            TreeSet<TaskId> taskIds, TreeSet<CriterionId> criterionIds) {
        if (l.kind() == null || l.kind().isBlank()) {
            return;
        }
        switch (LinkKind.ofToken(l.kind())) {
            case ISSUE_TASK -> {
                addIssueIfPresent(l.a(), issueIds);
                addTaskIfPresent(l.b(), taskIds);
            }
            case ISSUE_SPEC -> {
                addIssueIfPresent(l.a(), issueIds);
                addSpecIfPresent(l.b(), specIds);
            }
            case SPEC_TASK -> {
                addSpecIfPresent(l.a(), specIds);
                addTaskIfPresent(l.b(), taskIds);
            }
            case CRITERION_TASK -> {
                addCriterionIfPresent(l.a(), criterionIds);
                addTaskIfPresent(l.b(), taskIds);
            }
            case CRITERION_ISSUE -> {
                addCriterionIfPresent(l.a(), criterionIds);
                addIssueIfPresent(l.b(), issueIds);
            }
            case CRITERION_SPEC -> {
                addCriterionIfPresent(l.a(), criterionIds);
                addSpecIfPresent(l.b(), specIds);
            }
            case RELATION -> {
                addIssueIfPresent(l.a(), issueIds);
                addIssueIfPresent(l.b(), issueIds);
            }
        }
    }

    private void discoverCorrect(BatchOpInput.Correct c, TreeSet<IssueId> issueIds, TreeSet<SpecId> specIds,
            TreeSet<TaskId> taskIds, TreeSet<CriterionId> criterionIds) {
        for (CorrectOperation.EndpointRef ref : correctOperation.referencedEndpoints(c.historyId())) {
            switch (ref) {
                case CorrectOperation.EndpointRef.IssueRef r -> rows.findIssue(r.id())
                        .ifPresent(row -> issueIds.add(row.id()));
                case CorrectOperation.EndpointRef.SpecRef r -> rows.findSpec(r.id())
                        .ifPresent(row -> specIds.add(row.id()));
                case CorrectOperation.EndpointRef.TaskRef r -> rows.findTask(r.id())
                        .ifPresent(row -> taskIds.add(row.id()));
                case CorrectOperation.EndpointRef.CriterionRef r -> rows.findCriterion(r.id())
                        .ifPresent(row -> criterionIds.add(row.id()));
            }
        }
    }

    private Set<Target> collectGrantTargets(List<BatchOpInput> ops, Map<String, ProjectId> issueProjects,
            Map<String, ProjectId> specProjects, Map<String, ProjectId> taskProjects,
            Map<String, ProjectId> criterionProjects) {
        Set<Target> targets = new LinkedHashSet<>();
        for (BatchOpInput op : ops) {
            switch (op) {
                case BatchOpInput.Save s -> collectSaveTargets(s, issueProjects, specProjects, taskProjects,
                        criterionProjects, targets);
                case BatchOpInput.Transition t -> collectTransitionTargets(t, issueProjects, specProjects,
                        taskProjects, criterionProjects, targets);
                case BatchOpInput.RecordDeliverable d -> collectDeliverableTargets(d, issueProjects, specProjects,
                        taskProjects, targets);
                case BatchOpInput.RecordCase c -> resolveIssueProject(c.issueId(), issueProjects)
                        .ifPresent(p -> targets.add(new Target(p, Scope.ISSUE_UPDATE)));
                case BatchOpInput.Link l -> collectLinkTargets(l, issueProjects, specProjects, taskProjects,
                        criterionProjects, targets);
                case BatchOpInput.Correct c -> collectCorrectTargets(c, issueProjects, specProjects, taskProjects,
                        criterionProjects, targets);
                case BatchOpInput.ProjectVersion v -> projectId(v.projectId())
                        .ifPresent(p -> targets.addAll(ProjectVersionLinks.from(v).targets(p, v)));
            }
        }
        return targets;
    }

    private void collectSaveTargets(BatchOpInput.Save s, Map<String, ProjectId> issueProjects,
            Map<String, ProjectId> specProjects, Map<String, ProjectId> taskProjects,
            Map<String, ProjectId> criterionProjects, Set<Target> targets) {
        Optional<HistoryEntity> parsed = Tokens.find(s.entity(), EntityEditors.SAVEABLE);
        if (parsed.isEmpty()) {
            return;
        }
        HistoryEntity entity = parsed.orElseThrow();
        boolean create = s.expectedVersion() == null || s.expectedVersion() == 0;
        Scope scope = create ? entity.createScope() : entity.updateScope();
        Optional<ProjectId> mentionedProject = mentionedProject(s.fields()).filter(projects::existsById);
        if (create) {
            mentionedProject.ifPresent(p -> {
                targets.add(new Target(p, scope));
                if (s.id() != null && !s.id().isBlank()) {
                    switch (entity) {
                        case ISSUE -> issueProjects.put(s.id(), p);
                        case SPEC -> specProjects.put(s.id(), p);
                        case TASK -> taskProjects.put(s.id(), p);
                        case CRITERION -> criterionProjects.put(s.id(), p);
                        case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK,
                                ISSUE_CASE -> {
                        }
                    }
                }
            });
            return;
        }
        if (s.id() == null || s.id().isBlank()) {
            return;
        }
        Optional<ProjectId> current = switch (entity) {
            case ISSUE -> resolveIssueProject(s.id(), issueProjects);
            case SPEC -> resolveSpecProject(s.id(), specProjects);
            case TASK -> resolveTaskProject(s.id(), taskProjects);
            case CRITERION -> resolveCriterionProject(s.id(), criterionProjects);
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                Optional.empty();
        };
        current.ifPresent(p -> targets.add(new Target(p, scope)));
        if (entity != HistoryEntity.ISSUE && mentionedProject.isPresent()) {
            ProjectId dest = mentionedProject.orElseThrow();
            targets.add(new Target(dest, scope));
            switch (entity) {
                case SPEC -> specProjects.put(s.id(), dest);
                case TASK -> taskProjects.put(s.id(), dest);
                case CRITERION -> criterionProjects.put(s.id(), dest);
                case ISSUE, DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK,
                        ISSUE_CASE -> {
                }
            }
        }
    }

    private void collectTransitionTargets(BatchOpInput.Transition t, Map<String, ProjectId> issueProjects,
            Map<String, ProjectId> specProjects, Map<String, ProjectId> taskProjects,
            Map<String, ProjectId> criterionProjects, Set<Target> targets) {
        Optional<HistoryEntity> parsed = Tokens.find(t.entity(), STATEFUL);
        if (parsed.isEmpty()) {
            return;
        }
        HistoryEntity entity = parsed.orElseThrow();
        Optional<ProjectId> current = switch (entity) {
            case ISSUE -> resolveIssueProject(t.id(), issueProjects);
            case SPEC -> resolveSpecProject(t.id(), specProjects);
            case TASK -> resolveTaskProject(t.id(), taskProjects);
            case CRITERION -> resolveCriterionProject(t.id(), criterionProjects);
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                Optional.empty();
        };
        // Batches always run on Channel.AUTHOR (Scope.VERIFY is rejected by VERIFY_NEEDS_REVIEWER_LOGIN)
        current.ifPresent(p -> targets.add(new Target(p, entity.updateScope())));
        if (entity == HistoryEntity.ISSUE && Tokens.find(t.to(), IssueStatus.values()).filter(s -> s == IssueStatus.MERGED)
                .isPresent()) {
            resolveIssueProject(t.mergedInto(), issueProjects)
                    .ifPresent(p -> targets.add(new Target(p, HistoryEntity.ISSUE.updateScope())));
        }
    }

    private void collectDeliverableTargets(BatchOpInput.RecordDeliverable d, Map<String, ProjectId> issueProjects,
            Map<String, ProjectId> specProjects, Map<String, ProjectId> taskProjects, Set<Target> targets) {
        Optional<DeliverableTarget> parsed = Tokens.find(d.targetEntity(), DeliverableTarget.values());
        if (parsed.isEmpty()) {
            return;
        }
        Optional<ProjectId> project = switch (parsed.orElseThrow()) {
            case ISSUE -> resolveIssueProject(d.targetId(), issueProjects);
            case SPEC -> resolveSpecProject(d.targetId(), specProjects);
            case TASK -> resolveTaskProject(d.targetId(), taskProjects);
        };
        project.ifPresent(p -> {
            targets.add(new Target(p, Scope.DELIVERABLE_ADD));
            targets.add(new Target(p, Scope.DELIVERABLE_EDIT));
        });
    }

    private void collectLinkTargets(BatchOpInput.Link l, Map<String, ProjectId> issueProjects,
            Map<String, ProjectId> specProjects, Map<String, ProjectId> taskProjects,
            Map<String, ProjectId> criterionProjects, Set<Target> targets) {
        if (l.kind() == null || l.kind().isBlank()) {
            return;
        }
        switch (LinkKind.ofToken(l.kind())) {
            case ISSUE_TASK -> {
                resolveIssueProject(l.a(), issueProjects).ifPresent(p -> targets.add(new Target(p, Scope.ISSUE_UPDATE)));
                resolveTaskProject(l.b(), taskProjects).ifPresent(p -> targets.add(new Target(p, Scope.ISSUE_UPDATE)));
            }
            case ISSUE_SPEC -> {
                resolveIssueProject(l.a(), issueProjects).ifPresent(p -> targets.add(new Target(p, Scope.SPEC_UPDATE)));
                resolveSpecProject(l.b(), specProjects).ifPresent(p -> targets.add(new Target(p, Scope.SPEC_UPDATE)));
            }
            case SPEC_TASK -> {
                resolveSpecProject(l.a(), specProjects).ifPresent(p -> targets.add(new Target(p, Scope.SPEC_UPDATE)));
                resolveTaskProject(l.b(), taskProjects).ifPresent(p -> targets.add(new Target(p, Scope.SPEC_UPDATE)));
            }
            case CRITERION_TASK -> {
                resolveCriterionProject(l.a(), criterionProjects).ifPresent(p -> targets.add(new Target(p, Scope.CRITERION_UPDATE)));
                resolveTaskProject(l.b(), taskProjects).ifPresent(p -> targets.add(new Target(p, Scope.CRITERION_UPDATE)));
            }
            case CRITERION_ISSUE -> {
                resolveCriterionProject(l.a(), criterionProjects).ifPresent(p -> targets.add(new Target(p, Scope.CRITERION_UPDATE)));
                resolveIssueProject(l.b(), issueProjects).ifPresent(p -> targets.add(new Target(p, Scope.CRITERION_UPDATE)));
            }
            case CRITERION_SPEC -> {
                resolveCriterionProject(l.a(), criterionProjects).ifPresent(p -> targets.add(new Target(p, Scope.CRITERION_UPDATE)));
                resolveSpecProject(l.b(), specProjects).ifPresent(p -> targets.add(new Target(p, Scope.CRITERION_UPDATE)));
            }
            case RELATION -> {
                resolveIssueProject(l.a(), issueProjects).ifPresent(p -> targets.add(new Target(p, Scope.ISSUE_UPDATE)));
                resolveIssueProject(l.b(), issueProjects).ifPresent(p -> targets.add(new Target(p, Scope.ISSUE_UPDATE)));
            }
        }
    }

    private void collectCorrectTargets(BatchOpInput.Correct c, Map<String, ProjectId> issueProjects,
            Map<String, ProjectId> specProjects, Map<String, ProjectId> taskProjects,
            Map<String, ProjectId> criterionProjects, Set<Target> targets) {
        for (CorrectOperation.EndpointRef ref : correctOperation.referencedEndpoints(c.historyId())) {
            switch (ref) {
                case CorrectOperation.EndpointRef.IssueRef r -> resolveIssueProject(r.id().value(), issueProjects)
                        .ifPresent(p -> targets.add(new Target(p, r.scope())));
                case CorrectOperation.EndpointRef.SpecRef r -> resolveSpecProject(r.id().value(), specProjects)
                        .ifPresent(p -> targets.add(new Target(p, r.scope())));
                case CorrectOperation.EndpointRef.TaskRef r -> resolveTaskProject(r.id().value(), taskProjects)
                        .ifPresent(p -> targets.add(new Target(p, r.scope())));
                case CorrectOperation.EndpointRef.CriterionRef r ->
                    resolveCriterionProject(r.id().value(), criterionProjects)
                            .ifPresent(p -> targets.add(new Target(p, r.scope())));
            }
        }
    }

    private Optional<ProjectId> resolveIssueProject(String raw, Map<String, ProjectId> known) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        ProjectId cached = known.get(raw);
        if (cached != null) {
            return Optional.of(cached);
        }
        return IssueId.parse(raw).flatMap(id -> projects.findByCode(id.projectCode()).map(Project::id));
    }

    private Optional<ProjectId> resolveSpecProject(String raw, Map<String, ProjectId> known) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        ProjectId cached = known.get(raw);
        if (cached != null) {
            return Optional.of(cached);
        }
        return projectByPrefix(raw, "-S-");
    }

    private Optional<ProjectId> resolveTaskProject(String raw, Map<String, ProjectId> known) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        ProjectId cached = known.get(raw);
        if (cached != null) {
            return Optional.of(cached);
        }
        return projectByPrefix(raw, "-T-");
    }

    private Optional<ProjectId> resolveCriterionProject(String raw, Map<String, ProjectId> known) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(known.get(raw));
    }

    private Optional<ProjectId> projectByPrefix(String raw, String separator) {
        int idx = raw.indexOf(separator);
        if (idx <= 0) {
            return Optional.empty();
        }
        String prefix = raw.substring(0, idx);
        if (!ProjectCode.FORMAT.matcher(prefix).matches()) {
            return Optional.empty();
        }
        return projects.findByCode(ProjectCode.of(prefix)).map(Project::id);
    }

    private void addIssueIfPresent(String raw, TreeSet<IssueId> ids) {
        if (raw != null && !raw.isBlank()) {
            IssueId.parse(raw).flatMap(rows::findIssue).ifPresent(row -> ids.add(row.id()));
        }
    }

    private void addSpecIfPresent(String raw, TreeSet<SpecId> ids) {
        if (raw != null && !raw.isBlank()) {
            SpecId.parse(raw).flatMap(rows::findSpec).ifPresent(row -> ids.add(row.id()));
        }
    }

    private void addTaskIfPresent(String raw, TreeSet<TaskId> ids) {
        if (raw != null && !raw.isBlank()) {
            TaskId.parse(raw).flatMap(rows::findTask).ifPresent(row -> ids.add(row.id()));
        }
    }

    private void addCriterionIfPresent(String raw, TreeSet<CriterionId> ids) {
        if (raw != null && !raw.isBlank()) {
            CriterionId.parse(raw).flatMap(rows::findCriterion).ifPresent(row -> ids.add(row.id()));
        }
    }

    private static Optional<ProjectId> mentionedProject(FieldsInput fields) {
        if (fields == null) {
            return Optional.empty();
        }
        return projectId(fields.projectId().orNull());
    }

    private static Optional<ProjectId> projectId(String text) {
        if (text != null && !text.isBlank() && ProjectId.FORMAT.matcher(text).matches()) {
            return Optional.of(ProjectId.of(text));
        }
        return Optional.empty();
    }
}
