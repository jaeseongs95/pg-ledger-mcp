package org.pgledger.application.read;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.HistoryQueryRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.MergeChainRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectCase;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The detail of one defect (what the old defect view showed: fixes, tasks, conditions, the cases of the defect and
 * of every defect merged into it, relations, the last change and the recent changes) and of one task.
 */
@Service
@Transactional(readOnly = true)
public class DetailService {

    /** How many recent history rows a detail shows. */
    public static final int RECENT_CHANGES = 5;

    public record LastChange(Instant at, String author, String login, String assurance, String op, String reason) {
    }

    public record FixView(String commit, String repo, String branch, String ci, String ciConclusion, String by) {
    }

    public record TaskRef(String task, String state, String owner) {
    }

    /** A case of the defect or of a merged one ({@code source} case), or a merged defect's own behavior. */
    public record CaseView(String source, String defect, Long caseId, String observation, String evidence,
            String userQuote, Instant observedAt, String by, Instant at) {
    }

    public record RelationView(String kind, String other) {
    }

    public record ChangeSummary(long id, Instant at, String author, String op, String reason) {
    }

    public record DefectDetail(String id, String projectId, String title, String category, String severity,
            String status, String mergedInto, String behavior, String rootCause, String ownerActorId, long version,
            Instant updatedAt, String createdBy, String fixedBy, Instant fixedAt, LastChange lastChange,
            List<FixView> fixes, List<TaskRef> tasks, List<String> conditions, List<CaseView> cases,
            List<RelationView> relations, List<ChangeSummary> recentChanges) {
    }

    public record TaskDetail(String id, String projectId, String title, String purpose, String successCondition,
            String originRef, String originQuote, String state, String ownerActorId, long version, Instant createdAt,
            Instant updatedAt, String createdBy, String completedBy, List<String> defects, List<String> conditions) {
    }

    private final DefectRepository defects;
    private final TaskRepository tasks;
    private final DefectFixRepository fixes;
    private final DefectCaseRepository cases;
    private final LinkRepository links;
    private final MergeChainRepository mergeChain;
    private final HistoryRepository historyRows;
    private final HistoryQueryRepository historyQueries;

    public DetailService(DefectRepository defects, TaskRepository tasks, DefectFixRepository fixes,
            DefectCaseRepository cases, LinkRepository links, MergeChainRepository mergeChain,
            HistoryRepository historyRows, HistoryQueryRepository historyQueries) {
        this.defects = defects;
        this.tasks = tasks;
        this.fixes = fixes;
        this.cases = cases;
        this.links = links;
        this.mergeChain = mergeChain;
        this.historyRows = historyRows;
        this.historyQueries = historyQueries;
    }

    public DefectDetail defect(String rawId) {
        DefectId id = DefectId.of(rawId);
        Defect d = defects.findById(id).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of("defect", rawId)));
        List<DefectFix> fixRows = fixes.findByDefectIdOrderById(id);
        DefectFix lastFix = fixRows.isEmpty() ? null : fixRows.getLast();
        List<TaskRef> taskRefs = links.tasksOf(id).stream().map(link -> tasks.findById(link.taskId()))
                .flatMap(java.util.Optional::stream).map(t -> new TaskRef(t.id().value(), t.state().name(),
                        t.ownerActorId() == null ? null : t.ownerActorId().value())).toList();
        List<RelationView> relations = links.relationsOf(id).stream().map(r -> new RelationView(r.relation().value(),
                (r.a().equals(id) ? r.b() : r.a()).value())).toList();
        List<Long> newest = historyQueries.newestOfEntity(HistoryEntity.DEFECT, rawId, true, 1);
        LastChange last = newest.isEmpty() ? null : lastChange(historyRows.findById(newest.getFirst()).orElseThrow());
        List<ChangeSummary> recent = historyQueries.newestOfEntity(HistoryEntity.DEFECT, rawId, false,
                RECENT_CHANGES).stream().map(h -> historyRows.findById(h).orElseThrow()).map(DetailService::summary)
                .toList();
        return new DefectDetail(rawId, d.projectId().value(), d.title(), d.category().value(), d.severity().value(),
                d.status().name(), d.mergedInto() == null ? null : d.mergedInto().value(), d.behavior(),
                d.rootCause(), d.ownerActorId() != null ? d.ownerActorId().value()
                        : lastFix == null ? null : lastFix.recordedBy().value(),
                d.version(), d.updatedAt(), d.createdBy().value(), lastFix == null ? null : lastFix.recordedBy()
                        .value(), lastFix == null ? null : lastFix.recordedAt(), last,
                fixRows.stream().map(f -> new FixView(f.commitSha().value(), f.repo(), f.branch(), f.ciRef(),
                        f.ciConclusion() == null ? null : f.ciConclusion().token(), f.recordedBy().value())).toList(),
                taskRefs, links.conditionsOfDefect(id), cases(d), relations, recent);
    }

    /** The cases of the defect and of every defect merged into it, oldest first; merged behaviors are listed as cases. */
    private List<CaseView> cases(Defect root) {
        List<CaseView> out = new ArrayList<>();
        List<DefectId> sources = new ArrayList<>(List.of(root.id()));
        sources.addAll(mergeChain.mergedInto(root.id()));
        for (DefectId source : sources) {
            for (DefectCase c : cases.findByDefectIdOrderById(source)) {
                out.add(new CaseView("case", source.value(), c.id(), c.observation(), c.evidence(), c.userQuote(),
                        c.observedAt(), c.recordedBy().value(), c.recordedAt()));
            }
            if (!source.equals(root.id())) {
                Defect merged = defects.findById(source).orElseThrow();
                out.add(new CaseView("merged_behavior", source.value(), null, merged.behavior(), null, null, null,
                        null, merged.createdAt()));
            }
        }
        out.sort(Comparator.comparing((CaseView c) -> c.observedAt() != null ? c.observedAt() : c.at())
                .thenComparing(c -> c.caseId() == null ? Long.MIN_VALUE : c.caseId())
                .thenComparing(CaseView::defect));
        return out;
    }

    private static LastChange lastChange(HistoryEntry h) {
        return new LastChange(h.at(), h.authorId().value(), h.login(), h.actorAssurance().name(), h.op().name(),
                h.reason());
    }

    private static ChangeSummary summary(HistoryEntry h) {
        return new ChangeSummary(h.id(), h.at(), h.authorId().value(), h.op().name(), h.reason());
    }

    public TaskDetail task(String rawId) {
        TaskId id = TaskId.of(rawId);
        Task t = tasks.findById(id).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of("task", rawId)));
        String completedBy = historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.TASK, rawId).reversed()
                .stream().filter(h -> h.op() == HistoryOp.TRANSITION && "REPORTED_DONE".equals(h.toState())
                        && !TaskState.VERIFIED.name().equals(h.fromState())).findFirst()
                .map(h -> h.authorId().value()).orElse(null);
        return new TaskDetail(rawId, t.projectId().value(), t.title(), t.purpose(), t.successCondition(),
                t.originRef(), t.originQuote(), t.state().name(), t.ownerActorId() == null ? null
                        : t.ownerActorId().value(), t.version(), t.createdAt(), t.updatedAt(), t.createdBy().value(),
                completedBy, links.defectsOfTask(id), links.conditionsOfTask(id));
    }
}
