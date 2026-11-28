// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.veridger.application.attachment.AttachmentLinks;
import org.veridger.application.gate.Lookup;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.history.ChangeSet;
import org.veridger.application.history.HistoryKey;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.history.Snapshot;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.write.WriteResult.RecordDeliverableResult;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.DeliverableRepository;
import org.veridger.database.repository.HistoryRepository;
import org.veridger.database.repository.IdAllocator;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TransitionException;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.Deliverable;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.model.Spec;
import org.veridger.domain.model.Task;
import org.veridger.domain.rule.CheckResult;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CanonicalPayload;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.Evidence;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.veridger.domain.value.TextRule;
import org.veridger.domain.value.Tokens;
import org.veridger.domain.value.UtcInstant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Records a deliverable produced for an issue, specification, or task (or amends external check facts on an already
 * recorded deliverable).
 *
 * <p>The target entity row is read and conditionally saved ({@code @Version}) before any counter is allocated so
 * concurrent target transitions (especially verification) decide the race cleanly via {@code VERSION_CONFLICT}.
 * Recording on a {@code VERIFIED} entity is refused ({@code VERIFIED_ENTITY}).
 *
 * <p>The deliverable itself is read only after the target entity is pinned (spec, task) or read (issue): every
 * write to a deliverable of a target is a write to that target too, so a deliverable read at that point is the row the
 * write goes on from, and what a concurrent write changed in between is either seen (the pin waited for it) or ends
 * in {@code VERSION_CONFLICT} when the target is saved. Read before, it would be overwritten with the values it had
 * before the other write ({@code deliverable} has no version column).
 */
@Component
public class RecordDeliverableOperation extends WriteOperation<RecordDeliverableCommand, RecordDeliverableResult> {

    private final WriteGate gate;
    private final Rows rows;
    private final CodeRules codeRules;
    private final DeliverableRepository deliverables;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final HistoryRepository historyRows;
    private final AttachmentLinks attachments;
    private final Clock clock;

    public RecordDeliverableOperation(WriteGate gate, ReceiptService receipts, Rows rows,
            CodeRules codeRules, DeliverableRepository deliverables, IdAllocator ids, HistoryWriter history,
            AttachmentLinks attachments, HistoryRepository historyRows, Clock clock) {
        super(gate, receipts, ReceiptOperation.RECORD_DELIVERABLE, RecordDeliverableResult.class);
        this.gate = gate;
        this.rows = rows;
        this.codeRules = codeRules;
        this.deliverables = deliverables;
        this.ids = ids;
        this.history = history;
        this.historyRows = historyRows;
        this.attachments = attachments;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(RecordDeliverableCommand c) {
        return Lookup.afterActor(() -> {
            DeliverableTarget target = Tokens.parse("target_entity", c.targetEntity(), DeliverableTarget.values());
            ProjectId project = switch (target) {
                case ISSUE -> rows.issue(IssueId.of(c.targetId())).projectId();
                case SPEC -> rows.spec(SpecId.of(c.targetId())).projectId();
                case TASK -> rows.task(TaskId.of(c.targetId())).projectId();
            };
            CodeValue kind = CodeValue.of(TextRule.CODE_NAME.required(c.kind()));
            String reference = TextRule.DELIVERABLE_REF.required(c.deliverableRef());
            String location = TextRule.LOCATION.optional(c.location());
            boolean exists = findDeliverable(target, c.targetId(), kind, reference, location).isPresent();
            return List.of(new Target(project, deliverableScope(exists)));
        });
    }

    @Override
    protected List<Target> targetsAnswered(RecordDeliverableCommand command, RecordDeliverableResult stored,
            List<Target> now) {
        return historyRows.findById(stored.historyId()).map(row -> {
            Scope scope = deliverableScope(row.op() != HistoryOp.CREATE);
            return now.stream().map(target -> new Target(target.project(), scope)).toList();
        }).orElse(now);
    }

    private static Scope deliverableScope(boolean exists) {
        return exists ? Scope.DELIVERABLE_EDIT : Scope.DELIVERABLE_ADD;
    }

    @Override
    protected Prepared<RecordDeliverableResult> prepare(RecordDeliverableCommand c) {
        DeliverableTarget target = Tokens.parse("target_entity", c.targetEntity(), DeliverableTarget.values());
        String targetId = switch (target) {
            case ISSUE -> IssueId.of(c.targetId()).value();
            case SPEC -> SpecId.of(c.targetId()).value();
            case TASK -> TaskId.of(c.targetId()).value();
        };
        CodeValue kind = CodeValue.of(TextRule.CODE_NAME.required(c.kind()));
        String deliverableRef = TextRule.DELIVERABLE_REF.required(c.deliverableRef());
        String location = TextRule.LOCATION.optional(c.location());
        String summary = TextRule.DELIVERABLE_SUMMARY.required(c.summary());
        String checkRef = TextRule.CHECK_REF.optional(c.checkRef());
        CheckResult checkResult = c.checkResult() == null || c.checkResult().isBlank() ? null
                : Tokens.parse("check_result", c.checkResult(), CheckResult.values());
        Instant checkedAt = c.checkedAt() == null || c.checkedAt().isBlank() ? null
                : UtcInstant.parse("checked_at", c.checkedAt()).value();
        if (checkResult != null && checkRef == null) {
            throw ValidationException.invalid("check_ref", ValidationReason.REQUIRED_WHEN_SET, "check_result");
        }
        boolean mark = c.markDone() == null || c.markDone();
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty()
                .text("target_entity", target.token())
                .text("target_id", targetId)
                .text("kind", kind.value())
                .text("deliverable_ref", deliverableRef)
                .text("location", location)
                .text("summary", summary)
                .text("check_ref", checkRef)
                .text("check_result", checkResult == null ? null : checkResult.name())
                .text("checked_at", ChangeSet.text(checkedAt))
                .text("mark", Boolean.toString(mark))
                .text("attachment_id", c.attachmentId());
        return new Prepared<>(payload, ctx -> execute(ctx, target, targetId, kind, deliverableRef, location, summary,
                checkRef, checkResult, checkedAt, mark, c.attachmentId()));
    }

    private RecordDeliverableResult execute(WriteContext ctx, DeliverableTarget target, String targetId,
            CodeValue kind, String deliverableRef, String location, String summary, String checkRef,
            CheckResult checkResult, Instant checkedAt, boolean mark, String attachment) {
        attachments.requireOwnedBy(attachment, ctx.author());
        Instant now = clock.instant();
        AuthorId author = ctx.author();
        Evidence evidence = Evidence.of(checkRef, null);
        Supplier<Known> afterTheTarget = () -> readKnown(target, targetId, kind, deliverableRef, location, checkRef,
                checkResult, checkedAt);

        TargetUpdate targetUpdate = switch (target) {
            case ISSUE -> updateIssue(ctx, IssueId.of(targetId), afterTheTarget, author, mark, now);
            case SPEC -> updateSpec(ctx, SpecId.of(targetId), afterTheTarget, author, mark, now);
            case TASK -> updateTask(ctx, TaskId.of(targetId), afterTheTarget, author, mark, now);
        };

        String delivHistoryId = HistoryKey.deliverableEntityId(target.token(), targetId, kind.value(), deliverableRef);
        DeliverableWrite write = switch (targetUpdate.known()) {
            case Known.Recorded recorded -> amendDeliverable(ctx, recorded.row(), delivHistoryId, checkRef,
                    checkResult, checkedAt, evidence, recorded.amended(), attachment);
            case Known.New unrecorded -> insertDeliverable(ctx, target, targetId, delivHistoryId, kind,
                    deliverableRef, location, summary, checkRef, checkResult, checkedAt, evidence, now, attachment);
        };

        Long transitionHistoryId = null;
        if (targetUpdate.op() == HistoryOp.TRANSITION) {
            ChangeSet changes = ChangeSet.empty().with("deliverable", null, deliverableRef)
                    .withIfChanged("owner_actor_id", targetUpdate.oldOwner(), targetUpdate.newOwner());
            transitionHistoryId = ctx.appendHistory(history, HistoryWriter.Entry.of(target.historyEntity(), targetId,
                    HistoryOp.TRANSITION, changes)
                    .withStates(targetUpdate.fromState(), targetUpdate.toState())
                    .withEvidence(evidence)
                    .withBefore(targetUpdate.before()));
        } else if (targetUpdate.op() == HistoryOp.EDIT) {
            transitionHistoryId = ctx.appendHistory(history, HistoryWriter.Entry.of(target.historyEntity(), targetId,
                    HistoryOp.EDIT, ChangeSet.empty().with("owner_actor_id", targetUpdate.oldOwner(),
                            targetUpdate.newOwner()))
                    .withEvidence(evidence)
                    .withBefore(targetUpdate.before()));
        }

        return new RecordDeliverableResult(WriteStatus.COMMITTED, target.token(), targetId, write.deliverableId(),
                targetUpdate.toState(), targetUpdate.newOwner(), targetUpdate.version(), write.historyId(),
                transitionHistoryId, author.value(), ctx.login());
    }

    /** The deliverable of the request as it is when the target is held, and what an amendment would change in it. */
    private sealed interface Known {

        /** No such deliverable yet: the request records it. */
        record New() implements Known {
        }

        /** The deliverable is recorded: the request amends its check facts, with these changes. */
        record Recorded(Deliverable row, ChangeSet amended) implements Known {
        }

        default boolean exists() {
            return this instanceof Recorded;
        }
    }

    /**
     * Reads the deliverable of the request. Called by the update of the target once it has the row of the target and
     * not before (see the class comment); refuses a kind that is not a code when the deliverable is new, and an
     * amendment that changes nothing.
     */
    private Known readKnown(DeliverableTarget target, String targetId, CodeValue kind, String deliverableRef,
            String location, String checkRef, CheckResult checkResult, Instant checkedAt) {
        return findDeliverable(target, targetId, kind, deliverableRef, location)
                .<Known>map(row -> new Known.Recorded(row, amendedChanges(row, checkRef, checkResult, checkedAt)))
                .orElseGet(() -> {
                    codeRules.checkDeliverableKind(kind);
                    return new Known.New();
                });
    }

    private Optional<Deliverable> findDeliverable(DeliverableTarget target, String targetId, CodeValue kind,
            String reference, String location) {
        return deliverables.findByTargetEntityAndTargetIdAndKindAndDeliverableRefAndLocationKey(target, targetId, kind,
                reference, Deliverable.locationKeyOf(location))
                .or(() -> location == null ? soleDeliverable(target, targetId, kind, reference) : Optional.empty());
    }


    private Optional<Deliverable> soleDeliverable(DeliverableTarget target, String targetId, CodeValue kind,
            String deliverableRef) {
        List<Deliverable> byRef = deliverables.findByTargetEntityAndTargetIdAndKindAndDeliverableRef(target, targetId,
                kind, deliverableRef);
        return byRef.size() == 1 ? Optional.of(byRef.getFirst()) : Optional.empty();
    }

    private record TargetUpdate(HistoryOp op, String fromState, String toState, String oldOwner, String newOwner,
            long version, Snapshot before, Known known) {
    }

    private TargetUpdate updateIssue(WriteContext ctx, IssueId id, Supplier<Known> afterTheTarget, AuthorId author,
            boolean mark, Instant now) {
        ctx.requirePinnedIssue(id);
        Issue row = rows.issueForWrite(id, null);
        Known known = afterTheTarget.get();
        ctx.authorize(gate, List.of(new Target(row.projectId(), deliverableScope(known.exists()))));
        row.requireNotVerified();
        ActorId nextOwner = known.exists() ? row.ownerActorId() : author;
        String oldOwner = row.ownerActorId() == null ? null : row.ownerActorId().value();
        String newOwner = nextOwner == null ? null : nextOwner.value();
        Snapshot before = Snapshot.IssueRow.of(row);
        if (row.status() == IssueStatus.OPEN && mark && !known.exists()) {
            Issue after = row.transitioned(IssueStatus.RESOLVED, null, now).ownedBy(nextOwner, now);
            long version = rows.saveIssue(after).version();
            return new TargetUpdate(HistoryOp.TRANSITION, IssueStatus.OPEN.name(), IssueStatus.RESOLVED.name(),
                    oldOwner, newOwner, version, before, known);
        }
        Issue after = row.ownedBy(nextOwner, now);
        long version = rows.saveIssue(after).version();
        HistoryOp op = Objects.equals(oldOwner, newOwner) ? null : HistoryOp.EDIT;
        return new TargetUpdate(op, row.status().name(), row.status().name(), oldOwner, newOwner, version, before,
                known);
    }

    private TargetUpdate updateSpec(WriteContext ctx, SpecId id, Supplier<Known> afterTheTarget, AuthorId author,
            boolean mark, Instant now) {
        ProjectId pinnedProject = ctx.pinSpec(rows, id);
        Spec row = rows.specForWrite(id, null);
        Known known = afterTheTarget.get();
        ctx.authorize(gate, List.of(new Target(pinnedProject, deliverableScope(known.exists()))));
        row.requireNotVerified();
        ActorId nextOwner = known.exists() ? row.ownerActorId() : author;
        String oldOwner = row.ownerActorId() == null ? null : row.ownerActorId().value();
        String newOwner = nextOwner == null ? null : nextOwner.value();
        Snapshot before = Snapshot.SpecRow.of(row);
        boolean canComplete = row.status() == SpecStatus.DRAFT || row.status() == SpecStatus.PLANNED
                || row.status() == SpecStatus.ACTIVE || row.status() == SpecStatus.BLOCKED;
        if (canComplete && mark && !known.exists()) {
            Spec after = row.transitioned(SpecStatus.COMPLETED, now).ownedBy(nextOwner, now);
            long version = rows.saveSpec(after).version();
            return new TargetUpdate(HistoryOp.TRANSITION, row.status().name(), SpecStatus.COMPLETED.name(), oldOwner,
                    newOwner, version, before, known);
        }
        Spec after = row.ownedBy(nextOwner, now);
        long version = rows.saveSpec(after).version();
        HistoryOp op = Objects.equals(oldOwner, newOwner) ? null : HistoryOp.EDIT;
        return new TargetUpdate(op, row.status().name(), row.status().name(), oldOwner, newOwner, version, before,
                known);
    }

    private TargetUpdate updateTask(WriteContext ctx, TaskId id, Supplier<Known> afterTheTarget, AuthorId author,
            boolean mark, Instant now) {
        ProjectId pinnedProject = ctx.pinTask(rows, id);
        Task row = rows.taskForWrite(id, null);
        Known known = afterTheTarget.get();
        ctx.authorize(gate, List.of(new Target(pinnedProject, deliverableScope(known.exists()))));
        row.requireNotVerified();
        ActorId nextOwner = known.exists() ? row.ownerActorId() : author;
        String oldOwner = row.ownerActorId() == null ? null : row.ownerActorId().value();
        String newOwner = nextOwner == null ? null : nextOwner.value();
        Snapshot before = Snapshot.TaskRow.of(row);
        boolean canComplete = row.state() == TaskState.PLANNED || row.state() == TaskState.ACTIVE
                || row.state() == TaskState.BLOCKED;
        TaskState nextState = canComplete && mark && !known.exists() ? TaskState.COMPLETED : row.state();
        Task after = new Task(row.id(), row.projectId(), row.title(), row.purpose(), row.successCondition(), nextState,
                nextOwner, row.originRef(), row.originQuote(), row.version(), row.createdAt(), now, row.createdBy(),
                row.idKey());
        long version = rows.saveTask(after).version();
        if (nextState != row.state()) {
            return new TargetUpdate(HistoryOp.TRANSITION, row.state().name(), nextState.name(), oldOwner, newOwner,
                    version, before, known);
        }
        HistoryOp op = Objects.equals(oldOwner, newOwner) ? null : HistoryOp.EDIT;
        return new TargetUpdate(op, row.state().name(), row.state().name(), oldOwner, newOwner, version, before,
                known);
    }

    private record DeliverableWrite(long deliverableId, long historyId) {
    }

    private DeliverableWrite insertDeliverable(WriteContext ctx, DeliverableTarget target, String targetId,
            String delivHistoryId, CodeValue kind, String deliverableRef, String location, String summary,
            String checkRef, CheckResult checkResult, Instant checkedAt, Evidence evidence, Instant now,
            String attachment) {
        Deliverable row = Deliverable.record(ids.next(Counter.DELIVERABLE), target, targetId, kind, deliverableRef,
                location, summary, checkRef, checkResult, checkedAt, attachment, ctx.author(), now);
        long deliverableId = inserted(() -> deliverables.insert(row), target.token() + " " + targetId).id();
        ChangeSet changes = ChangeSet.empty()
                .with("target_entity", null, target.token())
                .with("target_id", null, targetId)
                .with("kind", null, kind.value())
                .with("deliverable_ref", null, deliverableRef)
                .with("location", null, location)
                .with("summary", null, summary)
                .with("check_ref", null, checkRef)
                .with("check_result", null, name(checkResult));
        long historyId = ctx.appendHistory(history, HistoryWriter.Entry.of(HistoryEntity.DELIVERABLE, delivHistoryId,
                HistoryOp.CREATE, changes).withEvidence(evidence).withAttachment(attachment));
        return new DeliverableWrite(deliverableId, historyId);
    }

    private static ChangeSet amendedChanges(Deliverable known, String checkRef, CheckResult checkResult,
            Instant checkedAt) {
        if (checkRef == null) {
            throw new TransitionException(TransitionCode.DELIVERABLE_ALREADY_RECORDED, List.of());
        }
        ChangeSet changes = ChangeSet.empty()
                .withIfChanged("check_ref", known.checkRef(), checkRef)
                .withIfChanged("check_result", ChangeSet.text(name(known.checkResult())), name(checkResult))
                .withIfChanged("checked_at", ChangeSet.text(known.checkedAt()), ChangeSet.text(checkedAt));
        if (changes.isEmpty()) {
            throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
        }
        return changes;
    }

    private DeliverableWrite amendDeliverable(WriteContext ctx, Deliverable known, String delivHistoryId,
            String checkRef, CheckResult checkResult, Instant checkedAt, Evidence evidence, ChangeSet changes,
            String attachment) {
        deliverables.update(known.withCheck(checkRef, checkResult, checkedAt));
        long historyId = ctx.appendHistory(history, HistoryWriter.Entry.of(HistoryEntity.DELIVERABLE, delivHistoryId,
                HistoryOp.EDIT, changes).withEvidence(evidence).withBefore(Snapshot.DeliverableRow.of(known))
                .withAttachment(attachment));
        return new DeliverableWrite(known.id(), historyId);
    }

    private static String name(CheckResult checkResult) {
        return checkResult == null ? null : checkResult.name();
    }

    private static <T> T inserted(Supplier<T> insert, String targetLabel) {
        try {
            return insert.get();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("deliverable of " + targetLabel), e);
        }
    }
}
