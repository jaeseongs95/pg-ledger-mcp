package org.pgledger.application.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.LinkResult;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.CodeCatalog;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.model.DefectRelation;
import org.pgledger.domain.model.DefectTaskLink;
import org.pgledger.domain.model.Link;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Links or unlinks defect and task, two defects (a relation of a RELATION_KIND code), a condition and a task, or a
 * condition and a defect. Adding a link that exists and removing one that does not are NOTHING_CHANGES; the row
 * that an unlink removes is kept in the history as {@code before_data}.
 */
@Component
public class LinkOperation extends WriteOperation<LinkCommand, LinkResult> {

    private final Rows rows;
    private final CommonCodeRepository codes;
    private final DefectRepository defects;
    private final TaskRepository tasks;
    private final LinkRepository links;
    private final HistoryWriter history;

    public LinkOperation(WriteGate gate, ReceiptService receipts, Rows rows, CommonCodeRepository codes,
            DefectRepository defects, TaskRepository tasks, LinkRepository links, HistoryWriter history) {
        super(gate, receipts, ReceiptOperation.LINK, LinkResult.class);
        this.rows = rows;
        this.codes = codes;
        this.defects = defects;
        this.tasks = tasks;
        this.links = links;
        this.history = history;
    }

    /**
     * The kind of a request: structural, or the code of an existing relation kind; anything else is refused. This
     * reads the relation kind codes, so it is called once per request, by {@link #resolve}; {@link #prepare} runs
     * only after that succeeded and takes the kind from the token alone.
     */
    private LinkKind knownKind(String token) {
        LinkKind kind = LinkKind.ofToken(token);
        if (kind != LinkKind.RELATION) {
            return kind;
        }
        CodeCatalog catalog = codes.catalog(CodeGroup.RELATION_KIND);
        if (token != null && catalog.entries().stream().anyMatch(e -> Objects.equals(e.code().value(), token))) {
            return kind;
        }
        List<String> allowed = new ArrayList<>(List.of(LinkKind.DEFECT_TASK.token(),
                LinkKind.CONDITION_TASK.token(), LinkKind.CONDITION_DEFECT.token()));
        catalog.validCodes().forEach(code -> allowed.add(code.value()));
        throw ValidationException.invalid("kind", ValidationReason.UNKNOWN_VALUE, allowed, String.valueOf(token));
    }

    @Override
    protected Lookup resolve(LinkCommand c) {
        return Lookup.afterActor(() -> switch (knownKind(c.kind())) {
            case CONDITION_TASK, CONDITION_DEFECT -> List.of(new Target(
                    rows.condition(ConditionId.of(c.a())).projectId(), Scope.TASK));
            case DEFECT_TASK -> List.of(new Target(rows.defect(DefectId.of(c.a())).projectId(), Scope.DEFECT));
            case RELATION -> List.of(new Target(rows.defect(DefectId.of(c.a())).projectId(), Scope.DEFECT),
                    new Target(rows.defect(DefectId.of(c.b())).projectId(), Scope.DEFECT));
        });
    }

    @Override
    protected Prepared<LinkResult> prepare(LinkCommand c) {
        LinkKind kind = LinkKind.ofToken(c.kind());
        boolean remove = c.remove() != null && c.remove();
        // the two ends as the payload names them: a relation has no direction, so its ends are in the order of the
        // domain's relation (the one place that knows it), whichever way the caller gave them
        List<String> ends = switch (kind) {
            case CONDITION_TASK -> {
                ConditionId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case CONDITION_DEFECT -> {
                ConditionId.of(c.a());
                DefectId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case DEFECT_TASK -> {
                DefectId.of(c.a());
                TaskId.of(c.b());
                yield List.of(c.a(), c.b());
            }
            case RELATION -> {
                DefectRelation relation = new DefectRelation(DefectId.of(c.a()), DefectId.of(c.b()),
                        CodeValue.of(c.kind()), AuthorId.OWNER);
                yield List.of(relation.a().value(), relation.b().value());
            }
        };
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("kind", c.kind())
                .text("a", ends.get(0)).text("b", ends.get(1)).text("remove", Boolean.toString(remove));
        return new Prepared<>(payload, ctx -> execute(ctx, kind, c.kind(), c.a(), c.b(), remove));
    }

    private LinkResult execute(WriteContext ctx, LinkKind kind, String token, String a, String b, boolean remove) {
        AuthorId author = ctx.author();
        Link link = switch (kind) {
            case DEFECT_TASK -> {
                TaskId task = TaskId.of(b);
                requireTask(task);
                yield new DefectTaskLink(DefectId.of(a), task, author);
            }
            case CONDITION_TASK -> {
                TaskId task = TaskId.of(b);
                requireTask(task);
                yield ConditionLink.toTask(ConditionId.of(a), task, author);
            }
            case CONDITION_DEFECT -> {
                DefectId defect = DefectId.of(b);
                if (!defects.existsById(defect)) {
                    throw Rows.notFound(HistoryEntity.DEFECT.token(), b);
                }
                yield ConditionLink.toDefect(ConditionId.of(a), defect, author);
            }
            case RELATION -> {
                DefectRelation relation = new DefectRelation(DefectId.of(a), DefectId.of(b), CodeValue.of(token),
                        author);
                if (!remove) {
                    codes.catalog(CodeGroup.RELATION_KIND).check("defect_relation.kind", relation.relation(), null);
                }
                yield relation;
            }
        };
        HistoryWriter.Entry entry = HistoryWriter.Entry.of(link.historyEntity(), link.historyId(),
                remove ? HistoryOp.UNLINK : HistoryOp.LINK,
                remove ? ChangeSet.empty().with("kind", token, null) : ChangeSet.empty().with("kind", null, token));
        try {
            if (remove) {
                // the row that the unlink removes is kept as before_data; a link that is not there changes nothing
                Snapshot stored = stored(link).orElseThrow(
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
        long historyId = history.append(ctx, entry);
        return new LinkResult(WriteStatus.COMMITTED, link.historyEntity().token(), link.historyId(), remove,
                historyId, author.value(), ctx.login());
    }

    private void requireTask(TaskId task) {
        if (!tasks.existsById(task)) {
            throw Rows.notFound(HistoryEntity.TASK.token(), task.value());
        }
    }

    private boolean addLink(Link link) {
        return switch (link) {
            case DefectTaskLink l -> links.add(l);
            case DefectRelation l -> links.add(l);
            case ConditionLink l -> links.add(l);
        };
    }

    private boolean removeLink(Link link) {
        return switch (link) {
            case DefectTaskLink l -> links.remove(l.defectId(), l.taskId());
            case DefectRelation l -> links.remove(l);
            case ConditionLink l -> links.remove(l);
        };
    }

    /** The stored row of the link, as it is now (its creator is whoever linked first). */
    private Optional<Snapshot> stored(Link link) {
        return switch (link) {
            case DefectTaskLink l -> links.tasksOf(l.defectId()).stream()
                    .filter(x -> Objects.equals(x.taskId(), l.taskId())).findFirst().<Snapshot>map(Snapshot.DefectTaskRow::of);
            case DefectRelation l -> links.relationsOf(l.a()).stream()
                    .filter(x -> Objects.equals(x.a(), l.a()) && Objects.equals(x.b(), l.b())
                            && Objects.equals(x.relation(), l.relation()))
                    .findFirst().<Snapshot>map(Snapshot.DefectRelationRow::of);
            case ConditionLink l -> links.linksOf(l.conditionId()).stream()
                    .filter(x -> Objects.equals(x.linkKey(), l.linkKey())).findFirst().<Snapshot>map(Snapshot.ConditionLinkRow::of);
        };
    }
}
