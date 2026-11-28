// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.veridger.application.gate.Target;
import org.veridger.application.history.ChangeSet;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.write.WriteResult.CorrectResult;
import org.veridger.application.write.WriteResult.LinkResult;
import org.veridger.application.write.WriteResult.RecordCaseResult;
import org.veridger.application.write.WriteResult.RecordDeliverableResult;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.application.write.WriteResult.TransitionResult;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.IdAllocator;
import org.veridger.database.repository.IssueCaseRepository;
import org.veridger.domain.model.IssueCase;
import org.veridger.domain.rule.CodeDirection;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;

/**
 * Per-call state carried explicitly through {@link WriteContext} during a single {@link BatchService#batch}
 * transaction (no {@code ThreadLocal}). Tracks pre-pinned entities and pre-authorized {@code session_grant} targets so
 * batch operations never re-acquire locks out of canonical order, and buffers {@code ISSUE_CASE} and {@code HISTORY}
 * writes until after all operations complete so counter locks always follow
 * {@code DELIVERABLE -> ISSUE_CASE -> HISTORY}.
 */
final class BatchBuffer {

    record DeferredCase(long caseSlot, long historySlot) {
    }

    private record PendingCase(WriteContext ctx, IssueId issueId, String observation, String evidence, String quote,
            Instant observedAt, String attachment, ChangeSet changes, Instant now, int historyIndex) {
    }

    private static final class PendingHistory {
        private final WriteContext ctx;
        private HistoryWriter.Entry entry;

        private PendingHistory(WriteContext ctx, HistoryWriter.Entry entry) {
            this.ctx = ctx;
            this.entry = entry;
        }
    }

    private record PendingReceipt(boolean replayed, OperationKey operationKey, AuthorId author, WriteResult result) {
    }

    private final Set<ProjectId> pinnedProjects;
    private final Set<IssueId> pinnedIssues;
    private final Set<SpecId> pinnedSpecs;
    private final Set<TaskId> pinnedTasks;
    private final Set<CriterionId> pinnedCriteria;
    private final Set<Target> authorizedGrants;
    private final Map<String, CodeDirection> heldRelationKinds;
    private final List<PendingCase> pendingCases = new ArrayList<>();
    private final List<PendingHistory> pendingHistories = new ArrayList<>();
    private final List<PendingReceipt> pendingReceipts = new ArrayList<>();

    BatchBuffer(Set<ProjectId> pinnedProjects, Set<IssueId> pinnedIssues, Set<SpecId> pinnedSpecs,
            Set<TaskId> pinnedTasks, Set<CriterionId> pinnedCriteria, Set<Target> authorizedGrants,
            Map<String, CodeDirection> heldRelationKinds) {
        this.pinnedProjects = new HashSet<>(pinnedProjects);
        this.pinnedIssues = new HashSet<>(pinnedIssues);
        this.pinnedSpecs = new HashSet<>(pinnedSpecs);
        this.pinnedTasks = new HashSet<>(pinnedTasks);
        this.pinnedCriteria = new HashSet<>(pinnedCriteria);
        this.authorizedGrants = Set.copyOf(authorizedGrants);
        this.heldRelationKinds = Map.copyOf(heldRelationKinds);
    }

    /** Whether the code of the relation kind was held in this direction by the planner (Phase 1.6). */
    boolean hasHeldRelationKind(String kind, CodeDirection direction) {
        return direction == heldRelationKinds.get(kind);
    }

    boolean hasPinnedProject(ProjectId id) {
        return pinnedProjects.contains(id);
    }

    boolean hasPinnedIssue(IssueId id) {
        return pinnedIssues.contains(id);
    }

    boolean hasPinnedSpec(SpecId id) {
        return pinnedSpecs.contains(id);
    }

    boolean hasPinnedTask(TaskId id) {
        return pinnedTasks.contains(id);
    }

    boolean hasPinnedCriterion(CriterionId id) {
        return pinnedCriteria.contains(id);
    }

    void markPinnedIssue(IssueId id) {
        pinnedIssues.add(id);
    }

    void markPinnedSpec(SpecId id) {
        pinnedSpecs.add(id);
    }

    void markPinnedTask(TaskId id) {
        pinnedTasks.add(id);
    }

    void markPinnedCriterion(CriterionId id) {
        pinnedCriteria.add(id);
    }

    boolean isAuthorized(Target target) {
        return authorizedGrants.contains(target);
    }

    Optional<AuthorId> latestLinkAuthor(HistoryEntity entity, String entityId) {
        for (int i = pendingHistories.size() - 1; i >= 0; i--) {
            PendingHistory ph = pendingHistories.get(i);
            if (ph.entry != null && ph.entry.entity() == entity && Objects.equals(ph.entry.entityId(), entityId)
                    && ph.entry.op() == HistoryOp.LINK) {
                return Optional.of(ph.ctx.author());
            }
        }
        return Optional.empty();
    }

    long deferHistory(WriteContext ctx, HistoryWriter.Entry entry) {
        int index = pendingHistories.size();
        pendingHistories.add(new PendingHistory(ctx, entry));
        return slot(index);
    }

    DeferredCase deferCase(WriteContext ctx, IssueId issueId, String observation, String evidence, String quote,
            Instant observedAt, String attachment, ChangeSet changes, Instant now) {
        int historyIndex = pendingHistories.size();
        pendingHistories.add(new PendingHistory(ctx, null));
        int caseIndex = pendingCases.size();
        pendingCases.add(new PendingCase(ctx, issueId, observation, evidence, quote, observedAt, attachment, changes,
                now, historyIndex));
        return new DeferredCase(slot(caseIndex), slot(historyIndex));
    }

    void recordReplayed(WriteResult result) {
        pendingReceipts.add(new PendingReceipt(true, null, null, result));
    }

    void recordPendingReceipt(OperationKey operationKey, AuthorId author, WriteResult result) {
        pendingReceipts.add(new PendingReceipt(false, operationKey, author, result));
    }

    /**
     * Flushes deferred {@code ISSUE_CASE} rows (Tier 8), then deferred {@code HISTORY} rows (Tier 9), resolves final
     * results with real surrogate IDs, and persists all pending per-operation receipts.
     */
    List<WriteResult> flush(IdAllocator ids, IssueCaseRepository cases, HistoryWriter history,
            ReceiptService receipts) {
        long[] caseIds = new long[pendingCases.size()];
        if (!pendingCases.isEmpty()) {
            long firstCaseId = ids.reserveRange(Counter.ISSUE_CASE, pendingCases.size());
            for (int i = 0; i < pendingCases.size(); i++) {
                PendingCase pc = pendingCases.get(i);
                long caseId = firstCaseId + i;
                cases.insert(IssueCase.observe(caseId, pc.issueId(), pc.observation(), pc.evidence(), pc.quote(),
                        pc.observedAt(), pc.ctx().author(), pc.now()));
                caseIds[i] = caseId;
                String entityId = pc.issueId().value() + "|" + caseId;
                pendingHistories.get(pc.historyIndex()).entry = HistoryWriter.Entry
                        .of(HistoryEntity.ISSUE_CASE, entityId, HistoryOp.CREATE, pc.changes())
                        .withAttachment(pc.attachment());
            }
        }
        List<HistoryWriter.BatchItem> historyItems = new ArrayList<>(pendingHistories.size());
        for (PendingHistory ph : pendingHistories) {
            historyItems.add(new HistoryWriter.BatchItem(ph.ctx, ph.entry));
        }
        long[] historyIds = history.appendAll(historyItems);
        List<WriteResult> results = new ArrayList<>(pendingReceipts.size());
        List<ReceiptService.Finished> toFinish = new ArrayList<>(pendingReceipts.size());
        for (PendingReceipt pr : pendingReceipts) {
            if (pr.replayed()) {
                results.add(pr.result());
            } else {
                WriteResult resolved = patch(pr.result(), caseIds, historyIds);
                toFinish.add(new ReceiptService.Finished(pr.operationKey(), pr.author(), resolved));
                results.add(resolved);
            }
        }
        receipts.finishAll(toFinish);
        return results;
    }

    private static long slot(int index) {
        return -(index + 1L);
    }

    private static long resolve(long placeholder, long[] realIds) {
        return placeholder < 0 ? realIds[(int) (-placeholder - 1L)] : placeholder;
    }

    private static Long resolveNullable(Long placeholder, long[] realIds) {
        return placeholder == null ? null : resolve(placeholder, realIds);
    }

    private static WriteResult patch(WriteResult result, long[] caseIds, long[] historyIds) {
        return switch (result) {
            case SaveResult r -> new SaveResult(r.status(), r.entity(), r.id(), r.version(),
                    resolve(r.historyId(), historyIds), r.author(), r.login());
            case TransitionResult r -> new TransitionResult(r.status(), r.entity(), r.id(), r.from(), r.to(),
                    r.version(), resolve(r.historyId(), historyIds), r.author(), r.login(), r.reviewReturn());
            case RecordDeliverableResult r -> new RecordDeliverableResult(r.status(), r.targetEntity(), r.targetId(),
                    r.deliverableId(), r.targetState(), r.ownerActorId(), r.version(),
                    resolve(r.historyId(), historyIds), resolveNullable(r.transitionHistoryId(), historyIds),
                    r.author(), r.login());
            case RecordCaseResult r -> {
                long caseId = resolve(r.caseId(), caseIds);
                yield new RecordCaseResult(r.status(), r.entity(), r.issue() + "|" + caseId, caseId, r.issue(),
                        resolve(r.historyId(), historyIds), r.author(), r.login());
            }
            case LinkResult r -> new LinkResult(r.status(), r.entity(), r.id(), r.removed(),
                    resolve(r.historyId(), historyIds), r.author(), r.login());
            case CorrectResult r -> new CorrectResult(r.status(), r.corrects(),
                    resolve(r.historyId(), historyIds), r.author(), r.login());
            case WriteResult.ProjectVersionResult r -> r;
        };
    }
}
