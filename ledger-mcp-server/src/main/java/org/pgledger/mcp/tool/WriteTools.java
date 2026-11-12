package org.pgledger.mcp.tool;

import java.util.List;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WhoamiService;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.rule.CodeGroup;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The ten write tools. Each method only turns the flat arguments of the tool into the input of its service and
 * the result into its output record; the gate, the validation and the transaction belong to the services.
 *
 * <p>The parameter names are the wire keys of the tool contract ({@code expected_version}, {@code request_id},
 * {@code _hostAttestation}, ...), which is why they are not camel case. The descriptions are message keys that the
 * tool catalog resolves from the message bundle.
 */
@Component
public class WriteTools implements ToolAdapter {

    private final SaveService save;
    private final TransitionService transition;
    private final RecordFixService recordFix;
    private final RecordCaseService recordCase;
    private final LinkService link;
    private final CorrectService correct;
    private final BatchService batch;
    private final ReviewService review;
    private final WhoamiService whoami;

    public WriteTools(SaveService save, TransitionService transition, RecordFixService recordFix,
            RecordCaseService recordCase, LinkService link, CorrectService correct, BatchService batch,
            ReviewService review, WhoamiService whoami) {
        this.save = save;
        this.transition = transition;
        this.recordFix = recordFix;
        this.recordCase = recordCase;
        this.link = link;
        this.correct = correct;
        this.batch = batch;
        this.review = review;
        this.whoami = whoami;
    }

    @McpTool(name = "ledger_save", description = "tool.ledger_save", generateOutputSchema = true)
    public WriteOutputs.Saved save(
            @McpToolParam(description = "param.entity", required = true)
            @Choice(from = HistoryEntity.class, only = {"DEFECT", "TASK", "CONDITION"}) String entity,
            @McpToolParam(description = "param.fields", required = true) SaveFields fields,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.save_id", required = false) String id,
            @McpToolParam(description = "param.save_expected_version", required = false) Long expected_version,
            @McpToolParam(description = "param.request_id", required = false) String request_id,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        return WriteOutputs.saved(save.save(_hostAttestation,
                new SaveCommand(request_id, entity, id, expected_version, fields.toInput(), reason)));
    }

    @McpTool(name = "ledger_transition", description = "tool.ledger_transition", generateOutputSchema = true)
    public WriteOutputs.Transitioned transition(
            @McpToolParam(description = "param.entity", required = true)
            @Choice(from = HistoryEntity.class, only = {"DEFECT", "TASK", "CONDITION"}) String entity,
            @McpToolParam(description = "param.id", required = true) String id,
            @McpToolParam(description = "param.expected_version", required = true) Long expected_version,
            @McpToolParam(description = "param.transition_to", required = true)
            @Choice(from = {DefectStatus.class, TaskState.class, ConditionState.class},
                    verification = Choice.Verification.EXCLUDED) String to,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.evidence_uri", required = false) String evidence_uri,
            @McpToolParam(description = "param.evidence_sha256", required = false) String evidence_sha256,
            @McpToolParam(description = "param.merged_into", required = false) String merged_into,
            @McpToolParam(description = "param.request_id", required = false) String request_id,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        return WriteOutputs.transitioned(transition.transition(_hostAttestation,
                new TransitionCommand(request_id, entity, id, expected_version, to, reason, evidence_uri,
                        evidence_sha256, merged_into)));
    }

    @McpTool(name = "ledger_record_fix", description = "tool.ledger_record_fix", generateOutputSchema = true)
    public WriteOutputs.FixRecorded recordFix(
            @McpToolParam(description = "param.defect_id", required = true) String defect_id,
            @McpToolParam(description = "param.repo", required = true) String repo,
            @McpToolParam(description = "param.commit_sha", required = true) String commit_sha,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.branch", required = false) String branch,
            @McpToolParam(description = "param.ci_ref", required = false) String ci_ref,
            @McpToolParam(description = "param.ci_conclusion", required = false)
            @Choice(from = CiConclusion.class) String ci_conclusion,
            @McpToolParam(description = "param.ci_completed_at", required = false) String ci_completed_at,
            @McpToolParam(description = "param.mark_source_fixed", required = false) Boolean mark_source_fixed,
            @McpToolParam(description = "param.request_id", required = false) String request_id,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        return WriteOutputs.fixRecorded(recordFix.recordFix(_hostAttestation,
                new RecordFixCommand(request_id, defect_id, repo, commit_sha, reason, branch, ci_ref,
                        ci_conclusion, ci_completed_at, mark_source_fixed)));
    }

    @McpTool(name = "ledger_record_case", description = "tool.ledger_record_case", generateOutputSchema = true)
    public WriteOutputs.CaseRecorded recordCase(
            @McpToolParam(description = "param.defect_id", required = true) String defect_id,
            @McpToolParam(description = "param.observation", required = true) String observation,
            @McpToolParam(description = "param.case_reason", required = true) String reason,
            @McpToolParam(description = "param.case_evidence", required = false) String evidence,
            @McpToolParam(description = "param.user_quote", required = false) String user_quote,
            @McpToolParam(description = "param.observed_at", required = false) String observed_at,
            @McpToolParam(description = "param.request_id", required = false) String request_id,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        return WriteOutputs.caseRecorded(recordCase.recordCase(_hostAttestation,
                new RecordCaseCommand(request_id, defect_id, observation, reason, evidence, user_quote,
                        observed_at)));
    }

    @McpTool(name = "ledger_link", description = "tool.ledger_link", generateOutputSchema = true)
    public WriteOutputs.Linked link(
            @McpToolParam(description = "param.kind", required = true)
            @CommonCode(group = CodeGroup.RELATION_KIND,
                    fixed = {"defect_task", "condition_task", "condition_defect"}) String kind,
            @McpToolParam(description = "param.link_a", required = true) String a,
            @McpToolParam(description = "param.link_b", required = true) String b,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.remove", required = false) Boolean remove,
            @McpToolParam(description = "param.request_id", required = false) String request_id,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        return WriteOutputs.linked(link.link(_hostAttestation,
                new LinkCommand(request_id, kind, a, b, reason, remove)));
    }

    @McpTool(name = "ledger_correct", description = "tool.ledger_correct", generateOutputSchema = true)
    public WriteOutputs.Corrected correct(
            @McpToolParam(description = "param.history_id", required = true) Long history_id,
            @McpToolParam(description = "param.correct_reason", required = true) String reason,
            @McpToolParam(description = "param.request_id", required = false) String request_id,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        return WriteOutputs.corrected(correct.correct(_hostAttestation,
                new CorrectCommand(request_id, history_id, reason)));
    }

    @McpTool(name = "ledger_batch", description = "tool.ledger_batch", generateOutputSchema = true)
    public WriteOutputs.Batched batch(
            @McpToolParam(description = "param.ops", required = true) List<BatchOp> ops,
            @McpToolParam(description = "param.batch_request_id", required = false) String request_id,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        List<BatchOpInput> inputs = ops.stream().map(BatchOp::toInput).toList();
        return WriteOutputs.of(batch.batch(_hostAttestation, request_id, inputs));
    }

    @McpTool(name = "ledger_review", description = "tool.ledger_review", generateOutputSchema = true)
    public WriteOutputs.Transitioned review(
            @McpToolParam(description = "param.review_entity", required = true)
            @Choice(from = HistoryEntity.class, only = {"DEFECT", "TASK", "CONDITION"}) String entity,
            @McpToolParam(description = "param.id", required = true) String id,
            @McpToolParam(description = "param.expected_version", required = true) Long expected_version,
            @McpToolParam(description = "param.review_to", required = true)
            @Choice(from = {DefectStatus.class, TaskState.class, ConditionState.class}) String to,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.review_evidence_uri", required = true) String evidence_uri,
            @McpToolParam(description = "param.evidence_sha256", required = false) String evidence_sha256,
            @McpToolParam(description = "param.request_id", required = false) String request_id,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        return WriteOutputs.transitioned(review.review(_hostAttestation,
                new TransitionCommand(request_id, entity, id, expected_version, to, reason, evidence_uri,
                        evidence_sha256, null)));
    }

    @McpTool(name = "ledger_review_correct", description = "tool.ledger_review_correct",
            generateOutputSchema = true)
    public WriteOutputs.Corrected reviewCorrect(
            @McpToolParam(description = "param.history_id", required = true) Long history_id,
            @McpToolParam(description = "param.correct_reason", required = true) String reason,
            @McpToolParam(description = "param.request_id", required = false) String request_id,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        return WriteOutputs.corrected(review.reviewCorrect(_hostAttestation,
                new CorrectCommand(request_id, history_id, reason)));
    }

    @McpTool(name = "ledger_whoami", description = "tool.ledger_whoami", generateOutputSchema = true)
    public WriteOutputs.Whoami whoami(
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation,
            @McpToolParam(description = "param.author_id", required = false) String author_id) {
        return WriteOutputs.of(whoami.whoami(_hostAttestation, Channel.AUTHOR));
    }
}
