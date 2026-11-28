// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.veridger.application.gate.Lookup;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.history.ChangeSet;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.history.Snapshot;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.write.WriteResult.LinkResult;
import org.veridger.database.repository.CommonCodeRepository;
import org.veridger.database.repository.HistoryRepository;
import org.veridger.database.repository.LinkRepository;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TransitionException;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.CodeCatalog;
import org.veridger.domain.model.CriterionLink;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.model.IssueRelation;
import org.veridger.domain.model.IssueSpecLink;
import org.veridger.domain.model.IssueTaskLink;
import org.veridger.domain.model.Link;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.model.SpecTaskLink;
import org.veridger.domain.rule.CodeDirection;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.LinkKind;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CanonicalPayload;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Links or unlinks issues, specifications, tasks, milestone criteria, and issue-to-issue relations.
 * Adding an existing link or removing an absent one fails with {@code NOTHING_CHANGES}; unlinking records the removed
 * row in {@code before_data}.
 */
@Component
public class LinkOperation extends WriteOperation<LinkCommand, LinkResult> {

    private final WriteGate gate;
    private final Rows rows;
    private final CommonCodeRepository codes;
    private final LinkRepository links;
    private final HistoryRepository historyRows;
    private final HistoryWriter history;

    public LinkOperation(WriteGate gate, ReceiptService receipts, Rows rows, CommonCodeRepository codes,
            LinkRepository links, HistoryRepository historyRows, HistoryWriter history) {
        super(gate, receipts, ReceiptOperation.LINK, LinkResult.class);
        this.gate = gate;
        this.rows = rows;
        this.codes = codes;
        this.links = links;
        this.historyRows = historyRows;
        this.history = history;
    }

    private LinkKind knownKind(String token) {
        LinkKind kind = LinkKind.ofToken(token);
        if (kind != LinkKind.RELATION) {
            return kind;
        }
        CodeCatalog catalog = codes.catalog(CodeGroup.RELATION_KIND);
        if (token != null && catalog.entries().stream().anyMatch(e -> Objects.equals(e.code().value(), token))) {
            return kind;
        }
        throw unknownKind(catalog, token);
    }

    private CodeDirection directionOf(String token) {
        CodeCatalog catalog = codes.catalog(CodeGroup.RELATION_KIND);
        return catalog.directionOf(CodeValue.of(token)).orElseThrow(() -> unknownKind(catalog, token));
    }

    private static ValidationException unknownKind(CodeCatalog catalog, String token) {
        List<String> allowed = new ArrayList<>(List.of(
                LinkKind.ISSUE_TASK.token(),
                LinkKind.ISSUE_SPEC.token(),
                LinkKind.SPEC_TASK.token(),
                LinkKind.CRITERION_TASK.token(),
                LinkKind.CRITERION_ISSUE.token(),
                LinkKind.CRITERION_SPEC.token()));
        catalog.validCodes().forEach(code -> allowed.add(code.value()));
        return ValidationException.invalid("kind", ValidationReason.UNKNOWN_VALUE, allowed, String.valueOf(token));
    }

    @Override
    protected Lookup resolve(LinkCommand c) {
        return Lookup.afterActor(() -> switch (knownKind(c.kind())) {
            case ISSUE_TASK -> List.of(
                    new Target(rows.issue(IssueId.of(c.a())).projectId(), Scope.ISSUE_UPDATE),
                    new Target(rows.task(TaskId.of(c.b())).projectId(), Scope.ISSUE_UPDATE));
            case ISSUE_SPEC -> List.of(
                    new Target(rows.issue(IssueId.of(c.a())).projectId(), Scope.SPEC_UPDATE),
                    new Target(rows.spec(SpecId.of(c.b())).projectId(), Scope.SPEC_UPDATE));
            case SPEC_TASK -> List.of(
                    new Target(rows.spec(SpecId.of(c.a())).projectId(), Scope.SPEC_UPDATE),
                    new Target(rows.task(TaskId.of(c.b())).projectId(), Scope.SPEC_UPDATE));
            case CRITERION_TASK -> List.of(
                    new Target(rows.criterion(CriterionId.of(c.a())).projectId(), Scope.CRITERION_UPDATE),
                    new Target(rows.task(TaskId.of(c.b())).projectId(), Scope.CRITERION_UPDATE));
            case CRITERION_ISSUE -> List.of(
                    new Target(rows.criterion(CriterionId.of(c.a())).projectId(), Scope.CRITERION_UPDATE),
                    new Target(rows.issue(IssueId.of(c.b())).projectId(), Scope.CRITERION_UPDATE));
            case CRITERION_SPEC -> List.of(
                    new Target(rows.criterion(CriterionId.of(c.a())).projectId(), Scope.CRITERION_UPDATE),
                    new Target(rows.spec(SpecId.of(c.b())).projectId(), Scope.CRITERION_UPDATE));
            case RELATION -> List.of(
                    new Target(rows.issue(IssueId.of(c.a())).projectId(), Scope.ISSUE_UPDATE),
                    new Target(rows.issue(IssueId.of(c.b())).projectId(), Scope.ISSUE_UPDATE));
        });
    }

    @Override
    protected Prepared<LinkResult> prepare(LinkCommand c) {
        LinkKind kind = LinkKind.ofToken(c.kind());
        boolean remove = c.remove() != null && c.remove();
        CodeDirection direction = kind == LinkKind.RELATION ? directionOf(c.kind()) : CodeDirection.CANONICAL;
        List<String> ends = switch (kind) {
            case ISSUE_TASK -> {
                IssueId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case ISSUE_SPEC -> {
                IssueId.of(c.a());
                SpecId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case SPEC_TASK -> {
                SpecId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case CRITERION_TASK -> {
                CriterionId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case CRITERION_ISSUE -> {
                CriterionId.of(c.a());
                IssueId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case CRITERION_SPEC -> {
                CriterionId.of(c.a());
                SpecId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case RELATION -> {
                IssueRelation.Pair pair = IssueRelation.Pair.of(IssueId.of(c.a()), IssueId.of(c.b()), direction);
                yield List.of(pair.first().value(), pair.second().value());
            }
        };
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("kind", c.kind())
                .text("a", ends.get(0)).text("b", ends.get(1)).text("remove", Boolean.toString(remove));
        return new Prepared<>(payload, ctx -> execute(ctx, kind, c.kind(), c.a(), c.b(), direction, remove));
    }

    private LinkResult execute(WriteContext ctx, LinkKind kind, String token, String a, String b,
            CodeDirection direction, boolean remove) {
        AuthorId author = ctx.author();
        // Canonical entity pin order: spec -> task -> criterion (issues cannot move projects), then session_grant asc.
        Link link = switch (kind) {
            case ISSUE_TASK -> {
                IssueId issue = IssueId.of(a);
                TaskId task = TaskId.of(b);
                ctx.requirePinnedIssue(issue);
                ProjectId issueProject = rows.issue(issue).projectId();
                ProjectId taskProject = ctx.pinTask(rows, task);
                ctx.authorize(gate, List.of(new Target(issueProject, Scope.ISSUE_UPDATE),
                        new Target(taskProject, Scope.ISSUE_UPDATE)));
                yield new IssueTaskLink(issue, task, author);
            }
            case ISSUE_SPEC -> {
                IssueId issue = IssueId.of(a);
                SpecId spec = SpecId.of(b);
                ctx.requirePinnedIssue(issue);
                ProjectId issueProject = rows.issue(issue).projectId();
                ProjectId specProject = ctx.pinSpec(rows, spec);
                ctx.authorize(gate, List.of(new Target(issueProject, Scope.SPEC_UPDATE),
                        new Target(specProject, Scope.SPEC_UPDATE)));
                yield new IssueSpecLink(issue, spec, author);
            }
            case SPEC_TASK -> {
                SpecId spec = SpecId.of(a);
                TaskId task = TaskId.of(b);
                ProjectId specProject = ctx.pinSpec(rows, spec);
                ProjectId taskProject = ctx.pinTask(rows, task);
                ctx.authorize(gate, List.of(new Target(specProject, Scope.SPEC_UPDATE),
                        new Target(taskProject, Scope.SPEC_UPDATE)));
                yield new SpecTaskLink(spec, task, author);
            }
            case CRITERION_TASK -> {
                CriterionId criterion = CriterionId.of(a);
                TaskId task = TaskId.of(b);
                ProjectId taskProject = ctx.pinTask(rows, task);
                ProjectId criterionProject = ctx.pinCriterion(rows, criterion);
                ctx.authorize(gate, List.of(new Target(taskProject, Scope.CRITERION_UPDATE),
                        new Target(criterionProject, Scope.CRITERION_UPDATE)));
                yield CriterionLink.toTask(criterion, task, author);
            }
            case CRITERION_ISSUE -> {
                CriterionId criterion = CriterionId.of(a);
                IssueId issue = IssueId.of(b);
                ctx.requirePinnedIssue(issue);
                ProjectId issueProject = rows.issue(issue).projectId();
                ProjectId criterionProject = ctx.pinCriterion(rows, criterion);
                ctx.authorize(gate, List.of(new Target(issueProject, Scope.CRITERION_UPDATE),
                        new Target(criterionProject, Scope.CRITERION_UPDATE)));
                yield CriterionLink.toIssue(criterion, issue, author);
            }
            case CRITERION_SPEC -> {
                CriterionId criterion = CriterionId.of(a);
                SpecId spec = SpecId.of(b);
                ProjectId specProject = ctx.pinSpec(rows, spec);
                ProjectId criterionProject = ctx.pinCriterion(rows, criterion);
                ctx.authorize(gate, List.of(new Target(specProject, Scope.CRITERION_UPDATE),
                        new Target(criterionProject, Scope.CRITERION_UPDATE)));
                yield CriterionLink.toSpec(criterion, spec, author);
            }
            case RELATION -> {
                IssueId first = IssueId.of(a);
                IssueId second = IssueId.of(b);
                ctx.requirePinnedIssue(first);
                ctx.requirePinnedIssue(second);
                ctx.authorize(gate, List.of(new Target(rows.issue(first).projectId(), Scope.ISSUE_UPDATE),
                        new Target(rows.issue(second).projectId(), Scope.ISSUE_UPDATE)));
                // the direction that decided the order of the two ends must still be the one of the code until this
                // transaction ends (the owner cannot change it while a relation is being stored)
                ctx.holdRelationDirection(codes, token, direction);
                IssueRelation relation = IssueRelation.between(first, second, CodeValue.of(token), direction, author);
                if (!remove) {
                    codes.catalog(CodeGroup.RELATION_KIND).check("issue_relation.kind", relation.relation(), null);
                }
                yield relation;
            }
        };
        HistoryWriter.Entry entry = HistoryWriter.Entry.of(link.historyEntity(), link.historyId(),
                remove ? HistoryOp.UNLINK : HistoryOp.LINK,
                remove ? ChangeSet.empty().with("kind", token, null) : ChangeSet.empty().with("kind", null, token));
        try {
            if (remove) {
                Snapshot stored = stored(ctx, link).orElseThrow(
                        () -> new TransitionException(TransitionCode.NOTHING_CHANGES, List.of()));
                if (!removeLink(link)) {
                    throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
                }
                entry = entry.withBefore(stored);
            } else if (!addLink(link)) {
                throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
            }
        } catch (DuplicateKeyException e) {
            throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of(), e);
        }
        long historyId = ctx.appendHistory(history, entry);
        return new LinkResult(WriteStatus.COMMITTED, link.historyEntity().token(), link.historyId(), remove,
                historyId, author.value(), ctx.login());
    }

    private boolean addLink(Link link) {
        return switch (link) {
            case IssueTaskLink l -> links.add(l);
            case IssueSpecLink l -> links.add(l);
            case SpecTaskLink l -> links.add(l);
            case IssueRelation l -> links.add(l);
            case CriterionLink l -> links.add(l);
        };
    }

    private boolean removeLink(Link link) {
        return switch (link) {
            case IssueTaskLink l -> links.remove(l.issueId(), l.taskId());
            case IssueSpecLink l -> links.remove(l.issueId(), l.specId());
            case SpecTaskLink l -> links.remove(l.specId(), l.taskId());
            case IssueRelation l -> links.remove(l);
            case CriterionLink l -> links.remove(l);
        };
    }

    private Optional<Snapshot> stored(WriteContext ctx, Link link) {
        return switch (link) {
            case IssueTaskLink l -> links.tasksOf(l.issueId()).stream()
                    .filter(x -> Objects.equals(x.taskId(), l.taskId()))
                    .findFirst().<Snapshot>map(Snapshot.IssueTaskRow::of);
            case IssueSpecLink l -> links.specsOfIssue(l.issueId()).contains(l.specId().value())
                    ? Optional.of(new Snapshot.IssueSpecRow(l.issueId().value(), l.specId().value(),
                            latestCreator(ctx, HistoryEntity.ISSUE_SPEC, l.historyId())))
                    : Optional.empty();
            case SpecTaskLink l -> links.tasksOfSpec(l.specId()).contains(l.taskId().value())
                    ? Optional.of(new Snapshot.SpecTaskRow(l.specId().value(), l.taskId().value(),
                            latestCreator(ctx, HistoryEntity.SPEC_TASK, l.historyId())))
                    : Optional.empty();
            case IssueRelation l -> links.relationsOf(l.a()).stream()
                    .filter(x -> Objects.equals(x.a(), l.a()) && Objects.equals(x.b(), l.b())
                            && Objects.equals(x.relation(), l.relation()))
                    .findFirst().<Snapshot>map(Snapshot.IssueRelationRow::of);
            case CriterionLink l -> links.linksOf(l.criterionId()).stream()
                    .filter(x -> Objects.equals(x.linkKey(), l.linkKey()))
                    .findFirst().<Snapshot>map(Snapshot.CriterionLinkRow::of);
        };
    }

    private String latestCreator(WriteContext ctx, HistoryEntity entity, String historyId) {
        if (ctx.isBatched()) {
            Optional<AuthorId> buffered = ctx.batch().latestLinkAuthor(entity, historyId);
            if (buffered.isPresent()) {
                return buffered.orElseThrow().value();
            }
        }
        List<HistoryEntry> entries = historyRows.findByEntityAndEntityIdOrderById(entity, historyId);
        for (int i = entries.size() - 1; i >= 0; i--) {
            HistoryEntry entry = entries.get(i);
            if (entry.op() == HistoryOp.LINK || entry.op() == HistoryOp.IMPORT) {
                return entry.authorId().value();
            }
        }
        return AuthorId.OWNER.value();
    }
}
