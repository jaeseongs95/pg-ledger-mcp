// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.veridger.application.attachment.AttachmentLinks;
import org.veridger.application.gate.Lookup;
import org.veridger.application.gate.Resolution;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.history.Change;
import org.veridger.application.history.ChangeSet;
import org.veridger.application.history.HistoryKey;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.history.Snapshot;
import org.veridger.application.json.LedgerJson;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.write.WriteResult.CorrectResult;
import org.veridger.database.repository.HistoryRepository;
import org.veridger.database.repository.LinkRepository;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.CriterionLink;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.LockOrder;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.CanonicalPayload;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.veridger.domain.value.Tokens;
import org.springframework.stereotype.Component;

/**
 * Appends a {@code CORRECT} history row pointing at an erroneous history row without altering the original row.
 *
 * <p>Resolves endpoints without throwing before caller authentication (F8), resolves composite link targets against
 * snapshots and active link rows first (F8), and pins mutable-project entities ({@code spec}, {@code task},
 * {@code milestone_criterion}) inside the write transaction to prevent concurrent project-move races (F7).
 */
@Component
public class CorrectOperation extends WriteOperation<CorrectCommand, CorrectResult> {

    private final WriteGate gate;
    private final Rows rows;
    private final HistoryRepository historyRows;
    private final LinkRepository links;
    private final HistoryWriter history;
    private final AttachmentLinks attachments;
    private final LedgerJson json;

    public CorrectOperation(WriteGate gate, ReceiptService receipts, Rows rows, HistoryRepository historyRows,
            LinkRepository links, HistoryWriter history, AttachmentLinks attachments, LedgerJson json) {
        super(gate, receipts, ReceiptOperation.CORRECT, CorrectResult.class);
        this.gate = gate;
        this.rows = rows;
        this.historyRows = historyRows;
        this.links = links;
        this.history = history;
        this.attachments = attachments;
        this.json = json;
    }

    sealed interface EndpointRef {
        record IssueRef(IssueId id, Scope scope) implements EndpointRef {}
        record SpecRef(SpecId id, Scope scope) implements EndpointRef {}
        record TaskRef(TaskId id, Scope scope) implements EndpointRef {}
        record CriterionRef(CriterionId id, Scope scope) implements EndpointRef {}
    }

    private sealed interface Endpoint {
        record IssueEnd(IssueId id, Scope scope) implements Endpoint {}
        record SpecEnd(SpecId id, Scope scope) implements Endpoint {}
        record TaskEnd(TaskId id, Scope scope) implements Endpoint {}
        record CriterionEnd(CriterionId id, Scope scope) implements Endpoint {}
    }

    private record ResolvedPlan(List<Endpoint> endpoints, Resolution resolution) {
    }

    /** Read-only endpoint discovery for {@link BatchLockPlanner} before Phase 1 locks are acquired. */
    List<EndpointRef> referencedEndpoints(Long historyId) {
        if (historyId == null) {
            return List.of();
        }
        return historyRows.findById(historyId).map(row -> inspect(row).endpoints().stream()
                .map(endpoint -> switch (endpoint) {
                    case Endpoint.IssueEnd e -> (EndpointRef) new EndpointRef.IssueRef(e.id(), e.scope());
                    case Endpoint.SpecEnd e -> new EndpointRef.SpecRef(e.id(), e.scope());
                    case Endpoint.TaskEnd e -> new EndpointRef.TaskRef(e.id(), e.scope());
                    case Endpoint.CriterionEnd e -> new EndpointRef.CriterionRef(e.id(), e.scope());
                }).toList()).orElse(List.of());
    }

    @Override
    protected Lookup resolve(CorrectCommand c) {
        return Lookup.verifiable(() -> {
            if (c.historyId() == null) {
                return Resolution.unresolved(ValidationException.invalid("history_id", ValidationReason.REQUIRED));
            }
            return historyRows.findById(c.historyId()).map(row -> inspect(row).resolution())
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("history", c.historyId().toString())));
        });
    }

    private ResolvedPlan inspect(HistoryEntry row) {
        HistoryEntry origin = originRow(row);
        Scope scope = origin.involvesVerification() ? Scope.VERIFY : origin.entity().updateScope();
        List<Endpoint> endpoints = new ArrayList<>();
        List<Resolution> parts = new ArrayList<>();
        switch (origin.entity()) {
            case ISSUE -> {
                addIssue(origin.entityId(), scope, endpoints, parts);
                mergedInto(origin).ifPresent(merged -> addIssue(merged.value(), Scope.ISSUE_UPDATE, endpoints, parts));
            }
            case SPEC -> addSpec(origin.entityId(), scope, endpoints, parts);
            case TASK -> addTask(origin.entityId(), scope, endpoints, parts);
            case CRITERION -> addCriterion(origin.entityId(), scope, endpoints, parts);
            case ISSUE_CASE -> {
                String owner = new HistoryKey(origin.entity(), origin.entityId()).ownerId();
                addIssue(owner, scope, endpoints, parts);
            }
            case DELIVERABLE -> addDeliverableTarget(origin, scope, endpoints, parts);
            case ISSUE_TASK -> {
                int sep = origin.entityId().indexOf('|');
                if (sep < 0) {
                    parts.add(Resolution.unresolved(IssueId.malformed()));
                } else {
                    addIssue(origin.entityId().substring(0, sep), scope, endpoints, parts);
                    addTask(origin.entityId().substring(sep + 1), scope, endpoints, parts);
                }
            }
            case ISSUE_SPEC -> {
                int sep = origin.entityId().indexOf('|');
                if (sep < 0) {
                    parts.add(Resolution.unresolved(IssueId.malformed()));
                } else {
                    addIssue(origin.entityId().substring(0, sep), scope, endpoints, parts);
                    addSpec(origin.entityId().substring(sep + 1), scope, endpoints, parts);
                }
            }
            case SPEC_TASK -> addSpecTaskTargets(origin, scope, endpoints, parts);
            case ISSUE_RELATION -> {
                String[] ends = origin.entityId().split("\\|", 3);
                if (ends.length < 2) {
                    parts.add(Resolution.unresolved(IssueId.malformed()));
                } else {
                    addIssue(ends[0], scope, endpoints, parts);
                    addIssue(ends[1], scope, endpoints, parts);
                }
            }
            case CRITERION_LINK -> addCriterionLinkTargets(origin, scope, endpoints, parts);
        }
        return new ResolvedPlan(endpoints, combine(parts));
    }

    private void addIssue(String raw, Scope scope, List<Endpoint> endpoints, List<Resolution> parts) {
        Optional<IssueId> parsed = IssueId.parse(raw);
        if (parsed.isEmpty()) {
            parts.add(Resolution.unresolved(IssueId.malformed()));
            return;
        }
        IssueId id = parsed.orElseThrow();
        endpoints.add(new Endpoint.IssueEnd(id, scope));
        parts.add(rows.issueTarget(id, scope));
    }

    private void addSpec(String raw, Scope scope, List<Endpoint> endpoints, List<Resolution> parts) {
        Optional<SpecId> parsed = SpecId.parse(raw);
        if (parsed.isEmpty()) {
            parts.add(Resolution.unresolved(SpecId.malformed()));
            return;
        }
        SpecId id = parsed.orElseThrow();
        endpoints.add(new Endpoint.SpecEnd(id, scope));
        parts.add(rows.specTarget(id, scope));
    }

    private void addTask(String raw, Scope scope, List<Endpoint> endpoints, List<Resolution> parts) {
        Optional<TaskId> parsed = TaskId.parse(raw);
        if (parsed.isEmpty()) {
            parts.add(Resolution.unresolved(TaskId.malformed()));
            return;
        }
        TaskId id = parsed.orElseThrow();
        endpoints.add(new Endpoint.TaskEnd(id, scope));
        parts.add(rows.taskTarget(id, scope));
    }

    private void addCriterion(String raw, Scope scope, List<Endpoint> endpoints, List<Resolution> parts) {
        Optional<CriterionId> parsed = CriterionId.parse(raw);
        if (parsed.isEmpty()) {
            parts.add(Resolution.unresolved(CriterionId.malformed()));
            return;
        }
        CriterionId id = parsed.orElseThrow();
        endpoints.add(new Endpoint.CriterionEnd(id, scope));
        parts.add(rows.criterionTarget(id, scope));
    }

    private void addDeliverableTarget(HistoryEntry origin, Scope scope, List<Endpoint> endpoints,
            List<Resolution> parts) {
        if (origin.beforeData() != null) {
            Snapshot.DeliverableRow snap = json.read(origin.beforeData(), Snapshot.DeliverableRow.class);
            Optional<DeliverableTarget> kind = Tokens.find(snap.targetEntity().toLowerCase(java.util.Locale.ROOT),
                    DeliverableTarget.values());
            if (kind.isPresent()) {
                addByDeliverableTarget(kind.orElseThrow(), snap.targetId(), scope, endpoints, parts);
                return;
            }
        }
        if (origin.changes() != null) {
            ChangeSet parsed = json.read(origin.changes(), ChangeSet.class);
            Change entityChange = parsed.entries().get("target_entity");
            Change idChange = parsed.entries().get("target_id");
            String targetEntity = entityChange == null ? null : entityChange.after();
            String targetId = idChange == null ? null : idChange.after();
            if (targetEntity != null && targetId != null) {
                Optional<DeliverableTarget> kind = Tokens.find(targetEntity, DeliverableTarget.values());
                if (kind.isPresent()) {
                    addByDeliverableTarget(kind.orElseThrow(), targetId, scope, endpoints, parts);
                    return;
                }
            }
        }
        String raw = origin.entityId();
        int at = raw.indexOf('@');
        String left = at < 0 ? raw : raw.substring(0, at);
        int colon = left.indexOf(':');
        if (colon > 0) {
            Optional<DeliverableTarget> kind = Tokens.find(left.substring(0, colon), DeliverableTarget.values());
            if (kind.isPresent()) {
                addByDeliverableTarget(kind.orElseThrow(), left.substring(colon + 1), scope, endpoints, parts);
                return;
            }
        }
        addIssue(left, scope, endpoints, parts);
    }

    private void addByDeliverableTarget(DeliverableTarget target, String targetId, Scope scope,
            List<Endpoint> endpoints, List<Resolution> parts) {
        switch (target) {
            case ISSUE -> addIssue(targetId, scope, endpoints, parts);
            case SPEC -> addSpec(targetId, scope, endpoints, parts);
            case TASK -> addTask(targetId, scope, endpoints, parts);
        }
    }

    private void addSpecTaskTargets(HistoryEntry origin, Scope scope, List<Endpoint> endpoints,
            List<Resolution> parts) {
        if (origin.beforeData() != null) {
            Snapshot.SpecTaskRow snap = json.read(origin.beforeData(), Snapshot.SpecTaskRow.class);
            addSpec(snap.specId(), scope, endpoints, parts);
            addTask(snap.taskId(), scope, endpoints, parts);
            return;
        }
        String entityId = origin.entityId();
        String matchedSpec = null;
        String matchedTask = null;
        String fallbackSpec = null;
        String fallbackTask = null;
        for (int sep = entityId.indexOf('|'); sep >= 0; sep = entityId.indexOf('|', sep + 1)) {
            String sRaw = entityId.substring(0, sep);
            String tRaw = entityId.substring(sep + 1);
            Optional<SpecId> sId = SpecId.parse(sRaw);
            Optional<TaskId> tId = TaskId.parse(tRaw);
            if (sId.isEmpty() || tId.isEmpty()) {
                continue;
            }
            if (fallbackSpec == null) {
                fallbackSpec = sRaw;
                fallbackTask = tRaw;
            }
            if (rows.findSpec(sId.orElseThrow()).isPresent() && rows.findTask(tId.orElseThrow()).isPresent()) {
                if (links.tasksOfSpec(sId.orElseThrow()).contains(tId.orElseThrow().value())) {
                    addSpec(sRaw, scope, endpoints, parts);
                    addTask(tRaw, scope, endpoints, parts);
                    return;
                }
                if (matchedSpec == null) {
                    matchedSpec = sRaw;
                    matchedTask = tRaw;
                }
            }
        }
        if (matchedSpec != null) {
            addSpec(matchedSpec, scope, endpoints, parts);
            addTask(matchedTask, scope, endpoints, parts);
            return;
        }
        if (fallbackSpec == null) {
            parts.add(Resolution.unresolved(SpecId.malformed()));
            return;
        }
        addSpec(fallbackSpec, scope, endpoints, parts);
        addTask(fallbackTask, scope, endpoints, parts);
    }

    private enum CriterionTargetKind {
        TASK, ISSUE, SPEC, ANY
    }

    private CriterionTargetKind criterionTargetKind(HistoryEntry origin) {
        if (origin.changes() == null) {
            return CriterionTargetKind.ANY;
        }
        ChangeSet parsed = json.read(origin.changes(), ChangeSet.class);
        Change kindChange = parsed.entries().get("kind");
        String token = kindChange == null ? null : (kindChange.after() != null ? kindChange.after() : kindChange.before());
        if ("criterion_task".equals(token)) {
            return CriterionTargetKind.TASK;
        }
        if ("criterion_issue".equals(token)) {
            return CriterionTargetKind.ISSUE;
        }
        if ("criterion_spec".equals(token)) {
            return CriterionTargetKind.SPEC;
        }
        return CriterionTargetKind.ANY;
    }

    private void addCriterionLinkTargets(HistoryEntry origin, Scope scope, List<Endpoint> endpoints,
            List<Resolution> parts) {
        if (origin.beforeData() != null) {
            Snapshot.CriterionLinkRow snap = json.read(origin.beforeData(), Snapshot.CriterionLinkRow.class);
            addCriterion(snap.criterionId(), scope, endpoints, parts);
            if (snap.taskId() != null) {
                addTask(snap.taskId(), scope, endpoints, parts);
            } else if (snap.issueId() != null) {
                addIssue(snap.issueId(), scope, endpoints, parts);
            } else if (snap.specId() != null) {
                addSpec(snap.specId(), scope, endpoints, parts);
            }
            return;
        }
        CriterionTargetKind targetKind = criterionTargetKind(origin);
        String entityId = origin.entityId();
        // First pass: check exact active composite link rows in criterion_link
        for (int sep = entityId.indexOf('|'); sep >= 0; sep = entityId.indexOf('|', sep + 1)) {
            String critRaw = entityId.substring(0, sep);
            String targetRaw = entityId.substring(sep + 1);
            Optional<CriterionId> critOpt = CriterionId.parse(critRaw);
            if (critOpt.isEmpty() || rows.findCriterion(critOpt.orElseThrow()).isEmpty()) {
                continue;
            }
            CriterionId crit = critOpt.orElseThrow();
            for (CriterionLink active : links.linksOf(crit)) {
                if (active.taskId() != null && targetKind != CriterionTargetKind.ISSUE
                        && targetKind != CriterionTargetKind.SPEC
                        && Objects.equals(active.taskId().value(), targetRaw)) {
                    addCriterion(critRaw, scope, endpoints, parts);
                    addTask(targetRaw, scope, endpoints, parts);
                    return;
                }
                if (active.issueId() != null && targetKind != CriterionTargetKind.TASK
                        && targetKind != CriterionTargetKind.SPEC
                        && Objects.equals(active.issueId().value(), targetRaw)) {
                    addCriterion(critRaw, scope, endpoints, parts);
                    addIssue(targetRaw, scope, endpoints, parts);
                    return;
                }
                if (active.specId() != null && targetKind != CriterionTargetKind.TASK
                        && targetKind != CriterionTargetKind.ISSUE
                        && Objects.equals(active.specId().value(), targetRaw)) {
                    addCriterion(critRaw, scope, endpoints, parts);
                    addSpec(targetRaw, scope, endpoints, parts);
                    return;
                }
            }
        }
        // Second pass: match existing entities on both sides of |
        String fallbackCrit = null;
        String fallbackTarget = null;
        boolean fallbackCritExists = false;
        for (int sep = entityId.indexOf('|'); sep >= 0; sep = entityId.indexOf('|', sep + 1)) {
            String critRaw = entityId.substring(0, sep);
            String targetRaw = entityId.substring(sep + 1);
            Optional<CriterionId> critOpt = CriterionId.parse(critRaw);
            if (critOpt.isEmpty()) {
                continue;
            }
            CriterionId crit = critOpt.orElseThrow();
            boolean critExists = rows.findCriterion(crit).isPresent();
            if (critExists && resolveCriterionTargetIfPresent(crit, targetRaw, targetKind, scope, endpoints, parts)) {
                return;
            }
            if (critExists && !fallbackCritExists) {
                fallbackCritExists = true;
                fallbackCrit = critRaw;
                fallbackTarget = targetRaw;
            } else if (fallbackCrit == null) {
                fallbackCrit = critRaw;
                fallbackTarget = targetRaw;
            }
        }
        if (fallbackCrit == null) {
            parts.add(Resolution.unresolved(CriterionId.malformed()));
            return;
        }
        addCriterion(fallbackCrit, scope, endpoints, parts);
        addFallbackCriterionTarget(fallbackTarget, targetKind, scope, endpoints, parts);
    }

    private boolean resolveCriterionTargetIfPresent(CriterionId crit, String targetRaw, CriterionTargetKind kind,
            Scope scope, List<Endpoint> endpoints, List<Resolution> parts) {
        if (kind == CriterionTargetKind.ISSUE || kind == CriterionTargetKind.ANY) {
            Optional<IssueId> issue = IssueId.parse(targetRaw).filter(id -> rows.findIssue(id).isPresent());
            if (issue.isPresent()) {
                addCriterion(crit.value(), scope, endpoints, parts);
                addIssue(issue.orElseThrow().value(), scope, endpoints, parts);
                return true;
            }
        }
        if (kind == CriterionTargetKind.SPEC || kind == CriterionTargetKind.ANY) {
            Optional<SpecId> spec = SpecId.parse(targetRaw).filter(id -> rows.findSpec(id).isPresent());
            if (spec.isPresent()) {
                addCriterion(crit.value(), scope, endpoints, parts);
                addSpec(spec.orElseThrow().value(), scope, endpoints, parts);
                return true;
            }
        }
        if (kind == CriterionTargetKind.TASK || kind == CriterionTargetKind.ANY) {
            Optional<TaskId> task = TaskId.parse(targetRaw).filter(id -> rows.findTask(id).isPresent());
            if (task.isPresent()) {
                addCriterion(crit.value(), scope, endpoints, parts);
                addTask(task.orElseThrow().value(), scope, endpoints, parts);
                return true;
            }
        }
        return false;
    }

    private void addFallbackCriterionTarget(String targetRaw, CriterionTargetKind kind, Scope scope,
            List<Endpoint> endpoints, List<Resolution> parts) {
        switch (kind) {
            case ISSUE -> addIssue(targetRaw, scope, endpoints, parts);
            case SPEC -> addSpec(targetRaw, scope, endpoints, parts);
            case TASK -> addTask(targetRaw, scope, endpoints, parts);
            case ANY -> {
                Optional<IssueId> issue = IssueId.parse(targetRaw).filter(id -> rows.findIssue(id).isPresent());
                Optional<SpecId> spec = SpecId.parse(targetRaw).filter(id -> rows.findSpec(id).isPresent());
                Optional<TaskId> task = TaskId.parse(targetRaw).filter(id -> rows.findTask(id).isPresent());
                issue.ifPresent(id -> addIssue(id.value(), scope, endpoints, parts));
                spec.ifPresent(id -> addSpec(id.value(), scope, endpoints, parts));
                task.ifPresent(id -> addTask(id.value(), scope, endpoints, parts));
                if (issue.isEmpty() && spec.isEmpty() && task.isEmpty()) {
                    addTask(targetRaw, scope, endpoints, parts);
                }
            }
        }
    }

    private HistoryEntry originRow(HistoryEntry row) {
        HistoryEntry current = row;
        Set<Long> seen = new HashSet<>();
        while (current.op() == HistoryOp.CORRECT && current.correctsId() != null && seen.add(current.id())) {
            Optional<HistoryEntry> parent = historyRows.findById(current.correctsId());
            if (parent.isEmpty()) {
                return current;
            }
            current = parent.orElseThrow();
        }
        return current;
    }

    private Optional<IssueId> mergedInto(HistoryEntry origin) {
        if (origin.op() != HistoryOp.TRANSITION || !"MERGED".equals(origin.toState()) || origin.changes() == null) {
            return Optional.empty();
        }
        ChangeSet parsed = json.read(origin.changes(), ChangeSet.class);
        return Optional.ofNullable(parsed.entries().get("merged_into")).map(Change::after).filter(v -> !v.isBlank())
                .flatMap(IssueId::parse);
    }

    private static Resolution combine(List<Resolution> parts) {
        List<Target> targets = new ArrayList<>();
        for (Resolution part : parts) {
            switch (part) {
                case Resolution.Unresolved u -> {
                    return u;
                }
                case Resolution.Resolved r -> targets.addAll(r.targets());
            }
        }
        return new Resolution.Resolved(targets);
    }

    @Override
    protected Prepared<CorrectResult> prepare(CorrectCommand c) {
        long id = c.requireHistoryId();
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("history_id", Long.toString(id))
                .text("attachment_id", c.attachmentId());
        return new Prepared<>(payload, ctx -> {
            attachments.requireOwnedBy(c.attachmentId(), ctx.author());
            HistoryEntry row = historyRows.findById(id)
                    .orElseThrow(() -> Rows.notFound("history", Long.toString(id)));
            ResolvedPlan plan = inspect(row);
            if (plan.resolution() instanceof Resolution.Unresolved u) {
                throw u.failure();
            }
            pinAndAuthorize(ctx, plan.endpoints());
            long newId = ctx.appendHistory(history, HistoryWriter.Entry.of(row.entity(), row.entityId(),
                    HistoryOp.CORRECT, ChangeSet.empty()).correcting(id).withAttachment(c.attachmentId()));
            return new CorrectResult(WriteStatus.COMMITTED, id, newId, ctx.author().value(), ctx.login());
        });
    }

    /**
     * Pins mutable-project targets in canonical entity order (issue -> spec -> task -> criterion) first, then authorizes
     * every pinned {@code (project, scope)} once in canonical {@code session_grant} order so concurrent project moves
     * cannot slip past authorization or deadlock on {@code session_grant}.
     */
    private void pinAndAuthorize(WriteContext ctx, List<Endpoint> endpoints) {
        List<Endpoint> ordered = new ArrayList<>(endpoints);
        ordered.sort(Comparator.comparingInt(CorrectOperation::orderRank)
                .thenComparing(CorrectOperation::entityId)
                .thenComparing(CorrectOperation::endpointScope, LockOrder.SCOPE));
        List<Target> targets = new ArrayList<>(ordered.size());
        for (Endpoint endpoint : ordered) {
            switch (endpoint) {
                case Endpoint.IssueEnd e -> {
                    ctx.requirePinnedIssue(e.id());
                    targets.add(new Target(rows.issue(e.id()).projectId(), e.scope()));
                }
                case Endpoint.SpecEnd e -> targets.add(new Target(ctx.pinSpec(rows, e.id()), e.scope()));
                case Endpoint.TaskEnd e -> targets.add(new Target(ctx.pinTask(rows, e.id()), e.scope()));
                case Endpoint.CriterionEnd e -> targets.add(new Target(ctx.pinCriterion(rows, e.id()), e.scope()));
            }
        }
        ctx.authorize(gate, targets);
    }

    private static int orderRank(Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.IssueEnd ignored -> 0;
            case Endpoint.SpecEnd ignored -> 1;
            case Endpoint.TaskEnd ignored -> 2;
            case Endpoint.CriterionEnd ignored -> 3;
        };
    }

    private static String entityId(Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.IssueEnd e -> e.id().value();
            case Endpoint.SpecEnd e -> e.id().value();
            case Endpoint.TaskEnd e -> e.id().value();
            case Endpoint.CriterionEnd e -> e.id().value();
        };
    }

    private static Scope endpointScope(Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.IssueEnd e -> e.scope();
            case Endpoint.SpecEnd e -> e.scope();
            case Endpoint.TaskEnd e -> e.scope();
            case Endpoint.CriterionEnd e -> e.scope();
        };
    }
}
