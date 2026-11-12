package org.pgledger.mcp.tool;

import java.util.List;
import org.pgledger.application.read.AttentionService;
import org.pgledger.application.read.CatalogService;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.GetService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.SearchService;
import org.pgledger.application.read.StatsService;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.TaskState;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The twelve read tools. No attestation and no grant is needed to read; the services read in read-only
 * transactions. As in {@link WriteTools}, the parameter names are the wire keys and the descriptions are message
 * keys.
 */
@Component
public class ReadTools implements ToolAdapter {

    private final GetService get;
    private final DetailService detail;
    private final HistoryService history;
    private final SearchService search;
    private final ListService lists;
    private final AttentionService attention;
    private final StatsService stats;
    private final CatalogService catalog;

    public ReadTools(GetService get, DetailService detail, HistoryService history, SearchService search,
            ListService lists, AttentionService attention, StatsService stats, CatalogService catalog) {
        this.get = get;
        this.detail = detail;
        this.history = history;
        this.search = search;
        this.lists = lists;
        this.attention = attention;
        this.stats = stats;
        this.catalog = catalog;
    }

    @McpTool(name = "ledger_get", description = "tool.ledger_get", generateOutputSchema = true)
    public ReadOutputs.Got get(@McpToolParam(description = "param.ids", required = true) List<String> ids) {
        return ReadOutputs.got(get.get(ids));
    }

    @McpTool(name = "ledger_defect", description = "tool.ledger_defect", generateOutputSchema = true)
    public ReadOutputs.Defect defect(@McpToolParam(description = "param.defect_id", required = true) String id) {
        return ReadOutputs.defect(detail.defect(id));
    }

    @McpTool(name = "ledger_task", description = "tool.ledger_task", generateOutputSchema = true)
    public ReadOutputs.Task task(@McpToolParam(description = "param.task_id", required = true) String id) {
        return ReadOutputs.task(detail.task(id));
    }

    @McpTool(name = "ledger_history", description = "tool.ledger_history", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.History> history(
            @McpToolParam(description = "param.history_ids", required = false) List<Long> ids,
            @McpToolParam(description = "param.id_from", required = false) Long id_from,
            @McpToolParam(description = "param.id_to", required = false) Long id_to,
            @McpToolParam(description = "param.history_entity", required = false) @Choice(from = HistoryEntity.class) String entity,
            @McpToolParam(description = "param.entity_id", required = false) String entity_id,
            @McpToolParam(description = "param.project", required = false) String project,
            @McpToolParam(description = "param.op", required = false) @Choice(from = HistoryOp.class) String op,
            @McpToolParam(description = "param.history_author_id", required = false) String author_id,
            @McpToolParam(description = "param.at_from", required = false) String at_from,
            @McpToolParam(description = "param.at_to", required = false) String at_to,
            @McpToolParam(description = "param.include_before", required = false) Boolean include_before,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        boolean withBefore = include_before != null && include_before;
        return ReadOutputs.page(history.history(new HistoryService.HistoryQuery(ids, id_from, id_to, entity,
                entity_id, project, op, author_id, at_from, at_to, include_before, max_rows, cursor)),
                row -> ReadOutputs.history(row, withBefore));
    }

    @McpTool(name = "ledger_search", description = "tool.ledger_search", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.Hit> search(
            @McpToolParam(description = "param.project", required = false) String project,
            @McpToolParam(description = "param.search_kinds", required = false) @Choice(from = HistoryEntity.class,
                    only = {"DEFECT", "TASK", "CONDITION"}) List<String> kinds,
            @McpToolParam(description = "param.keywords", required = false) List<String> keywords,
            @McpToolParam(description = "param.match", required = false) @Choice(from = SearchService.Match.class) String match,
            @McpToolParam(description = "param.exclude_status", required = false) List<String> exclude_status,
            @McpToolParam(description = "param.created_from", required = false) String created_from,
            @McpToolParam(description = "param.created_to", required = false) String created_to,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(search.search(new SearchService.SearchQuery(project, kinds, keywords, match,
                exclude_status, created_from, created_to, max_rows, cursor)), ReadOutputs::hit);
    }

    @McpTool(name = "ledger_defects", description = "tool.ledger_defects", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.DefectRow> defects(
            @McpToolParam(description = "param.project", required = true) String project,
            @McpToolParam(description = "param.defect_status", required = false) @Choice(from = DefectStatus.class) String status,
            @McpToolParam(description = "param.severity", required = false) String severity,
            @McpToolParam(description = "param.category", required = false) String category,
            @McpToolParam(description = "param.attention_kind", required = false) String attention,
            @McpToolParam(description = "param.owner", required = false) String owner,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(lists.defects(new ListService.DefectsQuery(project, status, severity, category,
                attention, owner, max_rows, cursor)), ReadOutputs::defectRow);
    }

    @McpTool(name = "ledger_tasks", description = "tool.ledger_tasks", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.TaskRow> tasks(
            @McpToolParam(description = "param.project", required = true) String project,
            @McpToolParam(description = "param.task_state", required = false) @Choice(from = TaskState.class) String state,
            @McpToolParam(description = "param.owner", required = false) String owner,
            @McpToolParam(description = "param.attention_kind", required = false) String attention,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(lists.tasks(new ListService.TasksQuery(project, state, owner, attention, max_rows,
                cursor)), ReadOutputs::taskRow);
    }

    @McpTool(name = "ledger_conditions", description = "tool.ledger_conditions", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.ConditionRow> conditions(
            @McpToolParam(description = "param.project", required = true) String project,
            @McpToolParam(description = "param.condition_state", required = false) @Choice(from = ConditionState.class) String state,
            @McpToolParam(description = "param.release", required = false) String release,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(lists.conditions(new ListService.ConditionsQuery(project, state, release, max_rows,
                cursor)), ReadOutputs::conditionRow);
    }

    @McpTool(name = "ledger_attention", description = "tool.ledger_attention", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.AttentionRow> attention(
            @McpToolParam(description = "param.project", required = false) String project,
            @McpToolParam(description = "param.attention_filter", required = false) @Choice(from = AttentionKind.class) String kind,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(attention.attention(new AttentionService.AttentionQuery(project, kind, max_rows,
                cursor)), ReadOutputs::attentionRow);
    }

    @McpTool(name = "ledger_stats", description = "tool.ledger_stats", generateOutputSchema = true)
    public ReadOutputs.Stats stats(@McpToolParam(description = "param.project", required = false) String project,
            @McpToolParam(description = "param.bucket", required = false) String bucket) {
        return ReadOutputs.stats(stats.stats(new StatsService.StatsQuery(project, bucket)));
    }

    @McpTool(name = "ledger_projects", description = "tool.ledger_projects", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.ProjectRow> projects(
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(catalog.projects(new CatalogService.ProjectsQuery(max_rows, cursor)),
                ReadOutputs::projectRow);
    }

    @McpTool(name = "ledger_codes", description = "tool.ledger_codes", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.CodeRow> codes(
            @McpToolParam(description = "param.code_group", required = false) @Choice(from = CodeGroup.class) String group,
            @McpToolParam(description = "param.include_inactive", required = false) Boolean include_inactive,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(catalog.codes(new CatalogService.CodesQuery(group, include_inactive, max_rows,
                cursor)), ReadOutputs::codeRow);
    }
}
