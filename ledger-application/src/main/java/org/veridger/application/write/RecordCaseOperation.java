// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.veridger.application.attachment.AttachmentLinks;
import org.veridger.application.gate.Lookup;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.history.ChangeSet;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.write.WriteResult.RecordCaseResult;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.IdAllocator;
import org.veridger.database.repository.IssueCaseRepository;
import org.veridger.database.repository.IssueRepository;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TransitionException;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.IssueCase;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.CanonicalPayload;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.TextRule;
import org.veridger.domain.value.UtcInstant;
import org.springframework.stereotype.Component;

/**
 * Records one observed occurrence under an issue.
 */
@Component
public class RecordCaseOperation extends WriteOperation<RecordCaseCommand, RecordCaseResult> {

    private final WriteGate gate;
    private final Rows rows;
    private final IssueRepository issues;
    private final IssueCaseRepository cases;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final AttachmentLinks attachments;
    private final Clock clock;

    public RecordCaseOperation(WriteGate gate, ReceiptService receipts, Rows rows, IssueRepository issues,
            IssueCaseRepository cases, IdAllocator ids, HistoryWriter history, AttachmentLinks attachments,
            Clock clock) {
        super(gate, receipts, ReceiptOperation.RECORD_CASE, RecordCaseResult.class);
        this.gate = gate;
        this.rows = rows;
        this.issues = issues;
        this.cases = cases;
        this.ids = ids;
        this.history = history;
        this.attachments = attachments;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(RecordCaseCommand c) {
        return Lookup.afterActor(() -> List.of(new Target(rows.issue(IssueId.of(c.issueId())).projectId(),
                Scope.ISSUE_UPDATE)));
    }

    @Override
    protected Prepared<RecordCaseResult> prepare(RecordCaseCommand c) {
        IssueId issueId = IssueId.of(c.issueId());
        String observation = TextRule.OBSERVATION.required(c.observation());
        String evidence = TextRule.EVIDENCE.optional(c.evidence());
        String quote = TextRule.SOURCE_QUOTE.optional(c.sourceQuote());
        Instant observedAt = c.observedAt() == null || c.observedAt().isBlank() ? null
                : UtcInstant.parse("observed_at", c.observedAt()).value();
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("issue", issueId.value())
                .text("observation", observation).text("evidence", evidence).text("source_quote", quote)
                .text("observed_at", ChangeSet.text(observedAt)).text("attachment_id", c.attachmentId());
        return new Prepared<>(payload, ctx -> execute(ctx, issueId, observation, evidence, quote, observedAt,
                c.attachmentId()));
    }

    private RecordCaseResult execute(WriteContext ctx, IssueId issueId, String observation, String evidence,
            String quote, Instant observedAt, String attachment) {
        attachments.requireOwnedBy(attachment, ctx.author());
        ctx.requirePinnedIssue(issueId);
        if (!issues.nextCaseSeq(issueId)) {
            Issue issue = rows.issue(issueId);
            if (issue.status() == IssueStatus.VERIFIED) {
                throw new TransitionException(TransitionCode.VERIFIED_ENTITY, List.of(issueId.value()));
            }
            throw new TransitionException(TransitionCode.MERGED_ISSUE,
                    List.of(issueId.value(), issue.mergedInto() == null ? "?" : issue.mergedInto().value()));
        }
        ctx.authorize(gate, List.of(new Target(rows.issue(issueId).projectId(), Scope.ISSUE_UPDATE)));
        ChangeSet changes = ChangeSet.empty().with("observation", null, observation).with("evidence", null, evidence)
                .with("source_quote", null, quote).with("observed_at", null, ChangeSet.text(observedAt));
        if (ctx.isBatched()) {
            BatchBuffer.DeferredCase deferred = ctx.batch().deferCase(ctx, issueId, observation, evidence, quote,
                    observedAt, attachment, changes, clock.instant());
            return new RecordCaseResult(WriteStatus.COMMITTED, HistoryEntity.ISSUE_CASE.token(),
                    issueId.value() + "|" + deferred.caseSlot(), deferred.caseSlot(), issueId.value(),
                    deferred.historySlot(), ctx.author().value(), ctx.login());
        }
        long caseId = ids.next(Counter.ISSUE_CASE);
        cases.insert(IssueCase.observe(caseId, issueId, observation, evidence, quote, observedAt, ctx.author(),
                clock.instant()));
        String entityId = issueId.value() + "|" + caseId;
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.ISSUE_CASE, entityId,
                HistoryOp.CREATE, changes).withAttachment(attachment));
        return new RecordCaseResult(WriteStatus.COMMITTED, HistoryEntity.ISSUE_CASE.token(), entityId, caseId,
                issueId.value(), historyId, ctx.author().value(), ctx.login());
    }
}
