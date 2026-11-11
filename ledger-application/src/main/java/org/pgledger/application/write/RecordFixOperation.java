package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.RecordFixResult;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.TextRule;
import org.pgledger.domain.value.Tokens;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Records a fix commit (or new CI data of a known commit). The defect row is locked first, so the state check and
 * the fix insert see the same defect (no write skew); an OPEN defect moves to SOURCE_FIXED in the same
 * transaction unless told not to, and recording a new fix commit makes the recording session the owner.
 * Recording a fix on a MERGED defect is allowed, as the old function allowed it.
 */
@Component
public class RecordFixOperation extends WriteOperation<RecordFixCommand, RecordFixResult> {

    private final Rows rows;
    private final CodeRules codeRules;
    private final DefectRepository defects;
    private final DefectFixRepository fixes;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final Clock clock;

    public RecordFixOperation(WriteGate gate, ReceiptService receipts, Rows rows, CodeRules codeRules,
            DefectRepository defects, DefectFixRepository fixes, IdAllocator ids, HistoryWriter history,
            Clock clock) {
        super(gate, receipts, ReceiptOperation.RECORD_FIX, RecordFixResult.class);
        this.rows = rows;
        this.codeRules = codeRules;
        this.defects = defects;
        this.fixes = fixes;
        this.ids = ids;
        this.history = history;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(RecordFixCommand c) {
        return Lookup.afterActor(() -> List.of(new Target(rows.defect(DefectId.of(c.defectId())).projectId(),
                Scope.FIX)));
    }

    @Override
    protected Prepared<RecordFixResult> prepare(RecordFixCommand c) {
        DefectId defectId = DefectId.of(c.defectId());
        String repo = TextRule.REPO.required(c.repo());
        CommitSha commit = CommitSha.of(c.commitSha());
        String branch = TextRule.BRANCH.optional(c.branch());
        String ciRef = TextRule.CI_REF.optional(c.ciRef());
        CiConclusion conclusion = c.ciConclusion() == null || c.ciConclusion().isBlank() ? null
                : Tokens.parse("ci_conclusion", c.ciConclusion(), CiConclusion.values());
        Instant completedAt = c.ciCompletedAt() == null || c.ciCompletedAt().isBlank() ? null
                : UtcInstant.parse("ci_completed_at", c.ciCompletedAt()).value();
        if (conclusion != null && ciRef == null) {
            throw ValidationException.invalid("ci_ref", "is required when ci_conclusion is set");
        }
        boolean mark = c.markSourceFixed() == null || c.markSourceFixed();
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("defect", defectId.value())
                .text("repo", repo).text("commit", commit.value()).text("branch", branch).text("ci_ref", ciRef)
                .text("ci_conclusion", conclusion == null ? null : conclusion.name())
                .text("ci_completed_at", ChangeSet.text(completedAt)).text("mark", Boolean.toString(mark));
        return new Prepared<>(payload, ctx -> execute(ctx, defectId, repo, commit, branch, ciRef, conclusion,
                completedAt, mark));
    }

    private RecordFixResult execute(WriteContext ctx, DefectId defectId, String repo, CommitSha commit,
            String branch, String ciRef, CiConclusion conclusion, Instant completedAt, boolean mark) {
        Instant now = clock.instant();
        Defect defect = rows.lockedDefect(defectId, null);
        String fixEntityId = defectId.value() + "@" + commit.value();
        ActorId author = ctx.author();
        DefectFix existing = fixes.findByDefectIdAndRepoAndCommitSha(defectId, repo, commit).orElse(null);
        long fixId;
        long historyId;
        ActorId nextOwner;
        Evidence evidence = Evidence.of(ciRef, null);
        if (existing == null) {
            DefectFix fix = DefectFix.record(ids.next(Counter.DEFECT_FIX), defectId, repo, commit, branch, ciRef,
                    conclusion, completedAt, author, now);
            fixId = guarded(() -> fixes.insert(fix), defectId.value()).id();
            ChangeSet changes = ChangeSet.empty().with("repo", null, repo).with("branch", null, branch)
                    .with("ci_ref", null, ciRef).with("ci_conclusion", null,
                            conclusion == null ? null : conclusion.name());
            historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT_FIX, fixEntityId,
                    HistoryOp.CREATE, changes).withEvidence(evidence));
            nextOwner = author;
        } else {
            if (ciRef == null) {
                throw new TransitionException(TransitionCode.FIX_ALREADY_RECORDED, List.of());
            }
            ChangeSet changes = ChangeSet.empty()
                    .withIfChanged("ci_ref", existing.ciRef(), ciRef)
                    .withIfChanged("ci_conclusion", ChangeSet.text(name(existing.ciConclusion())),
                            conclusion == null ? null : conclusion.name())
                    .withIfChanged("ci_completed_at", ChangeSet.text(existing.ciCompletedAt()),
                            ChangeSet.text(completedAt));
            if (changes.isEmpty()) {
                throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
            }
            fixes.update(existing.withCi(ciRef, conclusion, completedAt));
            fixId = existing.id();
            historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT_FIX, fixEntityId,
                    HistoryOp.EDIT, changes).withEvidence(evidence).withBefore(Snapshot.FixRow.of(existing)));
            nextOwner = defect.ownerActorId();
        }
        String oldOwner = defect.ownerActorId() == null ? null : defect.ownerActorId().value();
        String newOwner = nextOwner == null ? null : nextOwner.value();
        DefectStatus status = defect.status();
        long version = defect.version();
        Long transitionHistoryId = null;
        if (defect.status() == DefectStatus.OPEN && mark) {
            Defect after = defect.transitioned(DefectStatus.SOURCE_FIXED, null, now).ownedBy(nextOwner, now);
            codeRules.checkDefect(defect, after.category(), after.severity());
            version = guarded(() -> defects.save(after), defectId.value()).version();
            ChangeSet changes = ChangeSet.empty().with("fix", null, commit.value())
                    .withIfChanged("owner_actor_id", oldOwner, newOwner);
            transitionHistoryId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT,
                    defectId.value(), HistoryOp.TRANSITION, changes)
                    .withStates(DefectStatus.OPEN.name(), DefectStatus.SOURCE_FIXED.name()).withEvidence(evidence)
                    .withBefore(Snapshot.DefectRow.of(defect)));
            status = DefectStatus.SOURCE_FIXED;
        } else if (!Objects.equals(oldOwner, newOwner)) {
            Defect after = defect.ownedBy(nextOwner, now);
            codeRules.checkDefect(defect, after.category(), after.severity());
            version = guarded(() -> defects.save(after), defectId.value()).version();
            transitionHistoryId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT,
                    defectId.value(), HistoryOp.EDIT, ChangeSet.empty().with("owner_actor_id", oldOwner, newOwner))
                    .withEvidence(evidence).withBefore(Snapshot.DefectRow.of(defect)));
        }
        return new RecordFixResult(WriteStatus.COMMITTED, defectId.value(), fixId, status.name(), newOwner, version,
                historyId, transitionHistoryId, author.value(), ctx.login());
    }

    private static String name(CiConclusion conclusion) {
        return conclusion == null ? null : conclusion.name();
    }

    private static <T> T guarded(Supplier<T> write, String defect) {
        try {
            return write.get();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("fix of " + defect), e);
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException(ConflictCode.VERSION_CONFLICT, List.of("defect", defect, "?", "?"), e);
        }
    }
}
