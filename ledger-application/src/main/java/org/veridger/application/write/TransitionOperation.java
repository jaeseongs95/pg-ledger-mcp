// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.veridger.application.attachment.AttachmentLinks;
import org.veridger.application.gate.Lookup;
import org.veridger.application.gate.Resolution;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.history.ChangeSet;
import org.veridger.application.history.CriterionHistory;
import org.veridger.application.history.HistoryKey;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.history.Snapshot;
import org.veridger.application.history.SpecHistory;
import org.veridger.application.history.TaskHistory;
import org.veridger.application.json.LedgerJson;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.write.WriteResult.TransitionResult;
import org.veridger.database.repository.DeliverableRepository;
import org.veridger.database.repository.HistoryRepository;
import org.veridger.database.repository.IssueCaseRepository;
import org.veridger.database.repository.MergeChainRepository;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.TrustException;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.Criterion;
import org.veridger.domain.model.Deliverable;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.model.Spec;
import org.veridger.domain.model.Task;
import org.veridger.domain.rule.CriterionReports;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.DeliverableHistory;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.LifecycleState;
import org.veridger.domain.rule.MergeTarget;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.rule.SpecReports;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskReports;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.CanonicalPayload;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.EntityId;
import org.veridger.domain.value.Evidence;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SessionKey;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.veridger.domain.value.Tokens;
import org.springframework.stereotype.Component;

/**
 * Transitions an issue, specification, task, or milestone criterion with one {@code TRANSITION} history row.
 * Entering or leaving verification states ({@code VERIFIED}, {@code MET}, {@code NOT_MET}) requires {@code Scope.VERIFY}
 * and the reviewer channel, plus independent-verifier separation from creators, owners, deliverable authors/amenders,
 * case reporters, and completion reporters.
 */
@Component
public class TransitionOperation extends WriteOperation<TransitionCommand, TransitionResult> {

    private static final HistoryEntity[] STATEFUL = {
        HistoryEntity.ISSUE, HistoryEntity.SPEC, HistoryEntity.TASK, HistoryEntity.CRITERION
    };

    private final WriteGate gate;
    private final Rows rows;
    private final DeliverableRepository deliverables;
    private final IssueCaseRepository cases;
    private final MergeChainRepository mergeChain;
    private final HistoryRepository historyRows;
    private final HistoryWriter history;
    private final LedgerJson json;
    private final AttachmentLinks attachments;
    private final Clock clock;

    public TransitionOperation(WriteGate gate, ReceiptService receipts, Rows rows,
            DeliverableRepository deliverables, IssueCaseRepository cases, MergeChainRepository mergeChain,
            HistoryRepository historyRows, HistoryWriter history, LedgerJson json, AttachmentLinks attachments,
            Clock clock) {
        super(gate, receipts, ReceiptOperation.TRANSITION, TransitionResult.class);
        this.gate = gate;
        this.rows = rows;
        this.deliverables = deliverables;
        this.cases = cases;
        this.mergeChain = mergeChain;
        this.historyRows = historyRows;
        this.history = history;
        this.json = json;
        this.attachments = attachments;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(TransitionCommand c) {
        return Lookup.verifiable(() -> Tokens.find(c.entity(), STATEFUL).map(entity -> switch (entity) {
            case ISSUE -> IssueId.parse(c.id()).map(id -> rows.findIssue(id)
                    .map(row -> issueTarget(row, c))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("issue", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(IssueId.malformed()));
            case SPEC -> SpecId.parse(c.id()).map(id -> rows.findSpec(id)
                    .map(row -> target(entity, row.projectId(), row.status(), c, SpecStatus.values()))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("spec", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(SpecId.malformed()));
            case TASK -> TaskId.parse(c.id()).map(id -> rows.findTask(id)
                    .map(row -> target(entity, row.projectId(), row.state(), c, TaskState.values()))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("task", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(TaskId.malformed()));
            case CRITERION -> CriterionId.parse(c.id()).map(id -> rows.findCriterion(id)
                    .map(row -> target(entity, row.projectId(), row.state(), c, CriterionState.values()))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("criterion", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(CriterionId.malformed()));
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                throw new IllegalStateException("STATEFUL has no such entity");
        }).orElseGet(() -> Resolution.unresolved(Tokens.unknown("entity", c.entity(), STATEFUL))));
    }

    private Resolution issueTarget(Issue row, TransitionCommand c) {
        return Tokens.find(c.to(), IssueStatus.values()).map(to -> {
            Target own = new Target(row.projectId(), Scope.forTransition(HistoryEntity.ISSUE, row.status(), to));
            if (to != IssueStatus.MERGED) {
                return Resolution.of(own);
            }
            return mergeTargetProject(c).map(project -> Resolution.of(own,
                    new Target(project, HistoryEntity.ISSUE.updateScope()))).orElseGet(() -> Resolution.of(own));
        }).orElseGet(() -> Resolution.unresolved(Tokens.unknown("to", c.to(), IssueStatus.values())));
    }

    private Optional<ProjectId> mergeTargetProject(TransitionCommand c) {
        return Optional.ofNullable(c.mergedInto()).filter(text -> !text.isBlank()).flatMap(IssueId::parse)
                .flatMap(rows::findIssue).map(Issue::projectId);
    }

    private static <S extends Enum<S> & LifecycleState> Resolution target(HistoryEntity entity, ProjectId project,
            S from, TransitionCommand c, S[] states) {
        return Tokens.find(c.to(), states)
                .map(to -> Resolution.of(new Target(project, Scope.forTransition(entity, from, to))))
                .orElseGet(() -> Resolution.unresolved(Tokens.unknown("to", c.to(), states)));
    }

    /**
     * The first target of a transition (the one of the entity itself; a merge adds the target issue after it) needs
     * the scope of the state change that was answered, from and to as the stored response has them: the scope of the
     * state the entity has now says nothing about it (an entity that has been verified since would ask for
     * {@code verify} and the reviewer channel for the replay of a transition that was none).
     */
    @Override
    protected List<Target> targetsAnswered(TransitionCommand c, TransitionResult stored, List<Target> now) {
        List<Target> answered = new ArrayList<>(now);
        answered.set(0, new Target(now.getFirst().project(), answeredScope(stored)));
        return answered;
    }

    private static Scope answeredScope(TransitionResult stored) {
        HistoryEntity entity = Tokens.parse("entity", stored.entity(), STATEFUL);
        return switch (entity) {
            case ISSUE -> scopeOf(entity, stored, IssueStatus.values());
            case SPEC -> scopeOf(entity, stored, SpecStatus.values());
            case TASK -> scopeOf(entity, stored, TaskState.values());
            case CRITERION -> scopeOf(entity, stored, CriterionState.values());
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                throw new IllegalStateException("Tokens.parse refused it");
        };
    }

    private static <S extends Enum<S> & LifecycleState> Scope scopeOf(HistoryEntity entity, TransitionResult stored,
            S[] states) {
        return Scope.forTransition(entity, Tokens.parse("from", stored.from(), states),
                Tokens.parse("to", stored.to(), states));
    }

    @Override
    protected Prepared<TransitionResult> prepare(TransitionCommand c) {
        HistoryEntity entity = Tokens.parse("entity", c.entity(), STATEFUL);
        if (c.expectedVersion() == null) {
            throw ValidationException.invalid("expected_version", ValidationReason.REQUIRED);
        }
        EntityId.of(c.id());
        Evidence evidence = Evidence.of(c.evidenceUri(), c.evidenceSha256());
        IssueId mergedInto = c.mergedInto() == null || c.mergedInto().isBlank() ? null
                : IssueId.of(c.mergedInto());
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("entity", entity.token())
                .text("id", c.id()).text("v", Long.toString(c.expectedVersion())).text("to", c.to())
                .text("evidence_uri", evidence.uri())
                .text("evidence_sha256", evidence.sha256() == null ? null : evidence.sha256().hex())
                .text("merged_into", mergedInto == null ? null : mergedInto.value())
                .text("attachment_id", c.attachmentId());
        long version = c.expectedVersion();
        String attachment = c.attachmentId();
        return new Prepared<>(payload, ctx -> {
            attachments.requireOwnedBy(attachment, ctx.author());
            return switch (entity) {
                case ISSUE -> issue(ctx, IssueId.of(c.id()), version,
                        Tokens.parse("to", c.to(), IssueStatus.values()), evidence, mergedInto, attachment);
                case SPEC -> spec(ctx, SpecId.of(c.id()), version,
                        Tokens.parse("to", c.to(), SpecStatus.values()), evidence, attachment);
                case TASK -> task(ctx, TaskId.of(c.id()), version,
                        Tokens.parse("to", c.to(), TaskState.values()), evidence, attachment);
                case CRITERION -> criterion(ctx, CriterionId.of(c.id()), version,
                        Tokens.parse("to", c.to(), CriterionState.values()), evidence, attachment);
                case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                    throw new IllegalStateException("Tokens.parse refused it");
            };
        });
    }

    private Scope requireChannel(WriteContext ctx, HistoryEntity entity, LifecycleState from, LifecycleState to) {
        Scope scope = Scope.forTransition(entity, from, to);
        if (scope == Scope.VERIFY && !ctx.session().channel().mayVerify()) {
            throw new TrustException(TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN, List.of());
        }
        return scope;
    }

    private void recheck(WriteContext ctx, HistoryEntity entity, ProjectId project, LifecycleState from,
            LifecycleState to) {
        Scope scope = requireChannel(ctx, entity, from, to);
        ctx.authorize(gate, List.of(new Target(project, scope)));
    }

    private TransitionResult issue(WriteContext ctx, IssueId id, long expected, IssueStatus to, Evidence evidence,
            IssueId mergedInto, String attachment) {
        Instant now = clock.instant();
        ctx.requirePinnedIssue(id);
        if (to == IssueStatus.MERGED && mergedInto != null) {
            ctx.requirePinnedIssue(mergedInto);
        }
        boolean locked = false;
        boolean sourceReady = true;
        boolean targetReady = true;
        boolean verifyReady = true;
        if (to == IssueStatus.MERGED && mergedInto != null && !Objects.equals(id, mergedInto)) {
            if (id.value().compareTo(mergedInto.value()) <= 0) {
                sourceReady = rows.guardMergeSource(id);
                targetReady = rows.guardMergeTarget(mergedInto);
            } else {
                targetReady = rows.guardMergeTarget(mergedInto);
                sourceReady = rows.guardMergeSource(id);
            }
            locked = sourceReady && targetReady;
        } else if (to == IssueStatus.VERIFIED) {
            verifyReady = rows.guardVerifyCandidate(id);
            locked = verifyReady;
        }
        Issue row = rows.issueForWrite(id, expected);
        Scope scope = requireChannel(ctx, HistoryEntity.ISSUE, row.status(), to);
        List<Target> authTargets = new ArrayList<>(2);
        authTargets.add(new Target(row.projectId(), scope));
        MergeTarget target = null;
        if (to == IssueStatus.MERGED && mergedInto != null) {
            Issue targetRow = rows.issue(mergedInto);
            authTargets.add(new Target(targetRow.projectId(), HistoryEntity.ISSUE.updateScope()));
            target = new MergeTarget(targetRow.id(), targetRow.status());
        }
        if (locked) {
            ctx.authorize(gate, authTargets);
        } else {
            authTargets.forEach(t -> gate.requireLive(ctx.author(), t));
        }
        List<SessionKey> contributors = sourceReady && targetReady && verifyReady
                ? deliverableContributors(DeliverableTarget.ISSUE, id.value())
                : List.of();
        DeliverableHistory delivHistory = new DeliverableHistory(contributors,
                verifyReady ? reporters(row, to) : List.of());
        row.status().requireTransitionTo(to, evidence, delivHistory, ctx.author().sessionKey(), id, target);
        Issue saved = rows.saveIssue(row.transitioned(to, to == IssueStatus.MERGED ? mergedInto : null, now));
        if (!locked) {
            ctx.authorize(gate, authTargets);
        }
        ChangeSet changes = to == IssueStatus.MERGED
                ? ChangeSet.empty().with("merged_into", null, mergedInto.value()) : ChangeSet.empty();
        long historyId = ctx.appendHistory(history, HistoryWriter.Entry.of(HistoryEntity.ISSUE, id.value(),
                HistoryOp.TRANSITION, changes).withStates(row.status().name(), to.name()).withEvidence(evidence)
                .withBefore(Snapshot.IssueRow.of(row)).withAttachment(attachment));
        return result(ctx, HistoryEntity.ISSUE, id.value(), row.status().name(), to.name(), saved.version(),
                historyId);
    }

    /**
     * Collects every session that recorded or amended a deliverable on the target entity (D-0029).
     */
    private List<SessionKey> deliverableContributors(DeliverableTarget target, String targetId) {
        List<Deliverable> rowsOfTarget = deliverables.findByTargetEntityAndTargetIdOrderById(target, targetId);
        List<SessionKey> contributors = new ArrayList<>();
        for (Deliverable d : rowsOfTarget) {
            contributors.add(d.recordedBy().sessionKey());
            String delivHistoryId = HistoryKey.deliverableEntityId(target.token(), targetId, d.kind().value(),
                    d.deliverableRef());
            historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.DELIVERABLE, delivHistoryId)
                    .forEach(entry -> contributors.add(entry.authorId().sessionKey()));
        }
        return contributors;
    }

    /**
     * Collects every session that ever owned the entity across its current row and historical before-snapshots so
     * deliverable ownership changes cannot erase an earlier owner from self-verification checks. Reuses the already
     * loaded {@code entries} to avoid a second full-CLOB {@code history} query.
     */
    private List<SessionKey> ownerContributors(HistoryEntity entity, ActorId currentOwner, List<HistoryEntry> entries) {
        List<SessionKey> owners = new ArrayList<>();
        if (currentOwner != null) {
            owners.add(currentOwner.sessionKey());
        }
        for (HistoryEntry entry : entries) {
            if (entry.beforeData() != null) {
                String beforeOwner = switch (entity) {
                    case ISSUE -> json.read(entry.beforeData(), Snapshot.IssueRow.class).ownerActorId();
                    case SPEC -> json.read(entry.beforeData(), Snapshot.SpecRow.class).ownerActorId();
                    case TASK -> json.read(entry.beforeData(), Snapshot.TaskRow.class).ownerActorId();
                    default -> null;
                };
                if (beforeOwner != null && !beforeOwner.isBlank()) {
                    owners.add(ActorId.ofStored(beforeOwner).sessionKey());
                }
            }
        }
        return owners;
    }

    private List<SessionKey> reporters(Issue row, IssueStatus to) {
        if (to != IssueStatus.VERIFIED) {
            return List.of();
        }
        List<Issue> sources = new ArrayList<>(List.of(row));
        mergeChain.mergedInto(row.id()).forEach(merged -> sources.add(rows.issue(merged)));
        List<SessionKey> reporters = new ArrayList<>();
        for (Issue source : sources) {
            if (!Objects.equals(source.id(), row.id())) {
                reporters.addAll(deliverableContributors(DeliverableTarget.ISSUE, source.id().value()));
            }
            List<HistoryEntry> entries = historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.ISSUE,
                    source.id().value());
            reporters.addAll(ownerContributors(HistoryEntity.ISSUE, source.ownerActorId(), entries));
            for (HistoryEntry entry : entries) {
                if (entry.authorId() == null) {
                    continue;
                }
                if (entry.op() == HistoryOp.EDIT) {
                    reporters.add(entry.authorId().sessionKey());
                } else if (entry.op() == HistoryOp.TRANSITION
                        && Objects.equals(IssueStatus.RESOLVED.name(), entry.toState())) {
                    reporters.add(entry.authorId().sessionKey());
                }
            }
            if (source.createdBy() != null) {
                reporters.add(source.createdBy().sessionKey());
            }
            cases.findByIssueIdOrderById(source.id()).forEach(c -> reporters.add(c.recordedBy().sessionKey()));
        }
        return reporters;
    }

    private TransitionResult spec(WriteContext ctx, SpecId id, long expected, SpecStatus to, Evidence evidence,
            String attachment) {
        Instant now = clock.instant();
        ProjectId pinnedProject = ctx.pinSpec(rows, id);
        Spec row = rows.spec(id);
        recheck(ctx, HistoryEntity.SPEC, pinnedProject, row.status(), to);
        Rows.requireVersion(HistoryEntity.SPEC.token(), id.value(), expected, row.version());
        List<HistoryEntry> entries = historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.SPEC, id.value());
        SpecReports reports = new SpecHistory(entries).reports();
        List<SessionKey> contributors = new ArrayList<>(deliverableContributors(DeliverableTarget.SPEC, id.value()));
        contributors.addAll(ownerContributors(HistoryEntity.SPEC, row.ownerActorId(), entries));
        if (row.createdBy() != null) {
            contributors.add(row.createdBy().sessionKey());
        }
        for (HistoryEntry entry : entries) {
            if (entry.op() == HistoryOp.EDIT && entry.authorId() != null) {
                contributors.add(entry.authorId().sessionKey());
            }
        }
        row.status().requireTransitionTo(to, evidence, ctx.author().sessionKey(), contributors, reports);
        Spec saved = rows.saveSpec(row.transitioned(to, now));
        long historyId = ctx.appendHistory(history, HistoryWriter.Entry.of(HistoryEntity.SPEC, id.value(),
                HistoryOp.TRANSITION, ChangeSet.empty()).withStates(row.status().name(), to.name())
                .withEvidence(evidence).withBefore(Snapshot.SpecRow.of(row)).withAttachment(attachment));
        return result(ctx, HistoryEntity.SPEC, id.value(), row.status().name(), to.name(), saved.version(), historyId);
    }

    private TransitionResult task(WriteContext ctx, TaskId id, long expected, TaskState to, Evidence evidence,
            String attachment) {
        Instant now = clock.instant();
        ProjectId pinnedProject = ctx.pinTask(rows, id);
        Task row = rows.task(id);
        recheck(ctx, HistoryEntity.TASK, pinnedProject, row.state(), to);
        Rows.requireVersion(HistoryEntity.TASK.token(), id.value(), expected, row.version());
        List<HistoryEntry> entries = historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.TASK, id.value());
        TaskReports reports = new TaskHistory(entries).reports();
        List<SessionKey> registrants = new ArrayList<>(deliverableContributors(DeliverableTarget.TASK, id.value()));
        registrants.addAll(ownerContributors(HistoryEntity.TASK, row.ownerActorId(), entries));
        if (row.createdBy() != null) {
            registrants.add(row.createdBy().sessionKey());
        }
        for (HistoryEntry entry : entries) {
            if (entry.op() == HistoryOp.EDIT && entry.authorId() != null) {
                registrants.add(entry.authorId().sessionKey());
            }
        }
        row.state().requireTransitionTo(to, evidence, ctx.author().sessionKey(), registrants, reports);
        Task saved = rows.saveTask(row.transitioned(to, now));
        long historyId = ctx.appendHistory(history, HistoryWriter.Entry.of(HistoryEntity.TASK, id.value(),
                HistoryOp.TRANSITION, ChangeSet.empty()).withStates(row.state().name(), to.name())
                .withEvidence(evidence).withBefore(Snapshot.TaskRow.of(row)).withAttachment(attachment));
        return result(ctx, HistoryEntity.TASK, id.value(), row.state().name(), to.name(), saved.version(),
                historyId);
    }

    private TransitionResult criterion(WriteContext ctx, CriterionId id, long expected, CriterionState to,
            Evidence evidence, String attachment) {
        ProjectId pinnedProject = ctx.pinCriterion(rows, id);
        Criterion row = rows.criterion(id);
        recheck(ctx, HistoryEntity.CRITERION, pinnedProject, row.state(), to);
        Rows.requireVersion(HistoryEntity.CRITERION.token(), id.value(), expected, row.version());
        List<HistoryEntry> entries = historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.CRITERION, id.value());
        CriterionReports reports = new CriterionHistory(entries).reports();
        List<SessionKey> contributors = new ArrayList<>();
        if (row.createdBy() != null) {
            contributors.add(row.createdBy().sessionKey());
        }
        for (HistoryEntry entry : entries) {
            if (entry.op() == HistoryOp.EDIT && entry.authorId() != null) {
                contributors.add(entry.authorId().sessionKey());
            }
        }
        row.state().requireTransitionTo(to, evidence, ctx.author().sessionKey(), contributors, reports);
        Criterion saved = rows.saveCriterion(row.transitioned(to));
        long historyId = ctx.appendHistory(history, HistoryWriter.Entry.of(HistoryEntity.CRITERION, id.value(),
                HistoryOp.TRANSITION, ChangeSet.empty()).withStates(row.state().name(), to.name())
                .withEvidence(evidence).withBefore(Snapshot.CriterionRow.of(row)).withAttachment(attachment));
        return result(ctx, HistoryEntity.CRITERION, id.value(), row.state().name(), to.name(), saved.version(),
                historyId);
    }

    private static TransitionResult result(WriteContext ctx, HistoryEntity entity, String id, String from,
            String to, long version, long historyId) {
        return new TransitionResult(WriteStatus.COMMITTED, entity.token(), id, from, to, version, historyId,
                ctx.author().value(), ctx.login());
    }
}
