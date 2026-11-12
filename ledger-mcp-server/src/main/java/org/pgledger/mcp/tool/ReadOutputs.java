package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.pgledger.application.read.AttentionService;
import org.pgledger.application.read.CatalogService;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.GetService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.Page;
import org.pgledger.application.read.SearchService;
import org.pgledger.application.read.StatsService;

/**
 * The results of the read tools as they go on the wire: the records of the application re-declared with the wire
 * keys. Lists are {@code items}, {@code truncated} and {@code next_cursor}; every instant is UTC ISO-8601 with
 * {@code Z} (the mapper writes {@code Instant} that way).
 */
public final class ReadOutputs {

    private static final String JSON_NULL = "null";

    private ReadOutputs() {
    }

    /** One page of a list. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Items<T>(@JsonProperty("items") List<T> items, @JsonProperty("truncated") boolean truncated,
            @JsonProperty("next_cursor") String nextCursor) {

        public Items {
            items = List.copyOf(items);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GotItem(@JsonProperty("entity") String entity, @JsonProperty("id") String id,
            @JsonProperty("state") String state, @JsonProperty("version") long version,
            @JsonProperty("project_id") String projectId, @JsonProperty("merged_into") String mergedInto) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Got(@JsonProperty("items") List<GotItem> items, @JsonProperty("not_found") List<String> notFound) {

        public Got {
            items = List.copyOf(items);
            notFound = List.copyOf(notFound);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LastChange(@JsonProperty("at") Instant at, @JsonProperty("author") String author,
            @JsonProperty("login") String login, @JsonProperty("assurance") String assurance,
            @JsonProperty("op") String op, @JsonProperty("reason") String reason) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Fix(@JsonProperty("commit") String commit, @JsonProperty("repo") String repo,
            @JsonProperty("branch") String branch, @JsonProperty("ci") String ci,
            @JsonProperty("ci_conclusion") String ciConclusion, @JsonProperty("by") String by) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TaskRef(@JsonProperty("task") String task, @JsonProperty("state") String state,
            @JsonProperty("owner") String owner) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Case(@JsonProperty("source") String source, @JsonProperty("defect") String defect,
            @JsonProperty("case_id") Long caseId, @JsonProperty("observation") String observation,
            @JsonProperty("evidence") String evidence, @JsonProperty("user_quote") String userQuote,
            @JsonProperty("observed_at") Instant observedAt, @JsonProperty("by") String by,
            @JsonProperty("at") Instant at) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Relation(@JsonProperty("kind") String kind, @JsonProperty("other") String other) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChangeSummary(@JsonProperty("id") long id, @JsonProperty("at") Instant at,
            @JsonProperty("author") String author, @JsonProperty("op") String op,
            @JsonProperty("reason") String reason) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Defect(@JsonProperty("id") String id, @JsonProperty("project_id") String projectId,
            @JsonProperty("title") String title, @JsonProperty("category") String category,
            @JsonProperty("severity") String severity, @JsonProperty("status") String status,
            @JsonProperty("merged_into") String mergedInto, @JsonProperty("behavior") String behavior,
            @JsonProperty("root_cause") String rootCause, @JsonProperty("owner_actor_id") String ownerActorId,
            @JsonProperty("version") long version, @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("created_by") String createdBy, @JsonProperty("fixed_by") String fixedBy,
            @JsonProperty("fixed_at") Instant fixedAt, @JsonProperty("last_change") LastChange lastChange,
            @JsonProperty("fixes") List<Fix> fixes, @JsonProperty("tasks") List<TaskRef> tasks,
            @JsonProperty("conditions") List<String> conditions, @JsonProperty("cases") List<Case> cases,
            @JsonProperty("relations") List<Relation> relations,
            @JsonProperty("recent_changes") List<ChangeSummary> recentChanges) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Task(@JsonProperty("id") String id, @JsonProperty("project_id") String projectId,
            @JsonProperty("title") String title, @JsonProperty("purpose") String purpose,
            @JsonProperty("success_condition") String successCondition,
            @JsonProperty("origin_ref") String originRef, @JsonProperty("origin_quote") String originQuote,
            @JsonProperty("state") String state, @JsonProperty("owner_actor_id") String ownerActorId,
            @JsonProperty("version") long version, @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt, @JsonProperty("created_by") String createdBy,
            @JsonProperty("completed_by") String completedBy, @JsonProperty("defects") List<String> defects,
            @JsonProperty("conditions") List<String> conditions) {
    }

    /**
     * One history row. {@code changes} is the object of {@code [before, after]} pairs of the row; {@code before_data}
     * is the full row before the change, present only when asked for ({@code include_before}): then it is the row,
     * or null when the operation has none.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record History(@JsonProperty("id") long id, @JsonProperty("at") Instant at,
            @JsonProperty("author_id") String authorId, @JsonProperty("vendor") String vendor,
            @JsonProperty("login") String login, @JsonProperty("actor_assurance") String actorAssurance,
            @JsonProperty("entity") String entity, @JsonProperty("entity_id") String entityId,
            @JsonProperty("op") String op, @JsonProperty("from_state") String fromState,
            @JsonProperty("to_state") String toState, @JsonRawValue @JsonProperty("changes") Object changes,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonRawValue @JsonProperty("before_data") Object beforeData,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence_uri") String evidenceUri, @JsonProperty("corrects_id") Long correctsId,
            @JsonProperty("request_id") String requestId, @JsonProperty("legacy_ref") String legacyRef) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Hit(@JsonProperty("kind") String kind, @JsonProperty("id") String id,
            @JsonProperty("project_id") String projectId, @JsonProperty("title") String title,
            @JsonProperty("state") String state, @JsonProperty("created_at") Instant createdAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DefectRow(@JsonProperty("id") String id, @JsonProperty("title") String title,
            @JsonProperty("category") String category, @JsonProperty("severity") String severity,
            @JsonProperty("status") String status, @JsonProperty("merged_into") String mergedInto,
            @JsonProperty("owner") String owner, @JsonProperty("version") long version,
            @JsonProperty("created_at") Instant createdAt, @JsonProperty("updated_at") Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TaskRow(@JsonProperty("id") String id, @JsonProperty("title") String title,
            @JsonProperty("state") String state, @JsonProperty("owner_actor_id") String ownerActorId,
            @JsonProperty("version") long version, @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ConditionRow(@JsonProperty("id") String id, @JsonProperty("release") String release,
            @JsonProperty("goal_label") String goalLabel, @JsonProperty("code") String code,
            @JsonProperty("condition_text") String conditionText, @JsonProperty("state") String state,
            @JsonProperty("version") long version) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttentionRow(@JsonProperty("kind") String kind, @JsonProperty("entity") String entity,
            @JsonProperty("id") String id, @JsonProperty("project_id") String projectId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DayCount(@JsonProperty("day") LocalDate day, @JsonProperty("defects") long defects,
            @JsonProperty("tasks") long tasks) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Stats(@JsonProperty("defects_by_status") Map<String, Long> defectsByStatus,
            @JsonProperty("defects_by_severity") Map<String, Long> defectsBySeverity,
            @JsonProperty("tasks_by_state") Map<String, Long> tasksByState,
            @JsonProperty("conditions_by_state") Map<String, Long> conditionsByState,
            @JsonProperty("defects") long defects, @JsonProperty("tasks") long tasks,
            @JsonProperty("conditions") long conditions,
            @JsonProperty("created_per_day") List<DayCount> createdPerDay) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProjectRow(@JsonProperty("id") String id, @JsonProperty("code") String code,
            @JsonProperty("title") String title) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CodeRow(@JsonProperty("group") String group, @JsonProperty("code") String code,
            @JsonProperty("name_ko") String nameKo, @JsonProperty("name_en") String nameEn,
            @JsonProperty("description_ko") String descriptionKo,
            @JsonProperty("description_en") String descriptionEn, @JsonProperty("sort_order") int sortOrder,
            @JsonProperty("state") String state) {
    }

    public static <S, T> Items<T> page(Page<S> page, Function<S, T> view) {
        return new Items<>(page.items().stream().map(view).toList(), page.truncated(), page.nextCursor());
    }

    public static Got got(GetService.Result result) {
        return new Got(result.items().stream().map(i -> new GotItem(i.entity(), i.id(), i.state(), i.version(),
                i.projectId(), i.mergedInto())).toList(), result.notFound());
    }

    public static Defect defect(DetailService.DefectDetail d) {
        return new Defect(d.id(), d.projectId(), d.title(), d.category(), d.severity(), d.status(), d.mergedInto(),
                d.behavior(), d.rootCause(), d.ownerActorId(), d.version(), d.updatedAt(), d.createdBy(),
                d.fixedBy(), d.fixedAt(),
                d.lastChange() == null ? null : new LastChange(d.lastChange().at(), d.lastChange().author(),
                        d.lastChange().login(), d.lastChange().assurance(), d.lastChange().op(),
                        d.lastChange().reason()),
                d.fixes().stream().map(f -> new Fix(f.commit(), f.repo(), f.branch(), f.ci(), f.ciConclusion(),
                        f.by())).toList(),
                d.tasks().stream().map(t -> new TaskRef(t.task(), t.state(), t.owner())).toList(), d.conditions(),
                d.cases().stream().map(c -> new Case(c.source(), c.defect(), c.caseId(), c.observation(),
                        c.evidence(), c.userQuote(), c.observedAt(), c.by(), c.at())).toList(),
                d.relations().stream().map(r -> new Relation(r.kind(), r.other())).toList(),
                d.recentChanges().stream().map(c -> new ChangeSummary(c.id(), c.at(), c.author(), c.op(),
                        c.reason())).toList());
    }

    public static Task task(DetailService.TaskDetail t) {
        return new Task(t.id(), t.projectId(), t.title(), t.purpose(), t.successCondition(), t.originRef(),
                t.originQuote(), t.state(), t.ownerActorId(), t.version(), t.createdAt(), t.updatedAt(),
                t.createdBy(), t.completedBy(), t.defects(), t.conditions());
    }

    /** @param includeBefore whether the caller asked for {@code before_data}: when not, the key is left out */
    public static History history(HistoryService.HistoryView h, boolean includeBefore) {
        return new History(h.id(), h.at(), h.authorId(), h.vendor(), h.login(), h.actorAssurance(), h.entity(),
                h.entityId(), h.op(), h.fromState(), h.toState(), h.changes().text(),
                beforeData(h, includeBefore), h.reason(), h.evidenceUri(),
                h.correctsId(), h.requestId(), h.legacyRef());
    }

    /**
     * The text that is written as it is for {@code before_data} ({@code @JsonRawValue}): the JSON the row stores
     * (snake_case keys, written by the ledger mapper), or {@code null} (the JSON null) when it was asked for and the
     * row has none, or a Java null (the key is left out) when it was not asked for.
     */
    private static String beforeData(HistoryService.HistoryView h, boolean includeBefore) {
        if (!includeBefore) {
            return null;
        }
        return h.beforeData() == null ? JSON_NULL : h.beforeData().text();
    }

    public static Hit hit(SearchService.Hit h) {
        return new Hit(h.kind(), h.id(), h.projectId(), h.title(), h.state(), h.createdAt());
    }

    public static DefectRow defectRow(ListService.DefectItem d) {
        return new DefectRow(d.id(), d.title(), d.category(), d.severity(), d.status(), d.mergedInto(), d.owner(),
                d.version(), d.createdAt(), d.updatedAt());
    }

    public static TaskRow taskRow(ListService.TaskItem t) {
        return new TaskRow(t.id(), t.title(), t.state(), t.ownerActorId(), t.version(), t.createdAt(),
                t.updatedAt());
    }

    public static ConditionRow conditionRow(ListService.ConditionItem c) {
        return new ConditionRow(c.id(), c.release(), c.goalLabel(), c.code(), c.conditionText(), c.state(),
                c.version());
    }

    public static AttentionRow attentionRow(AttentionService.Item i) {
        return new AttentionRow(i.kind(), i.entity(), i.id(), i.projectId());
    }

    public static Stats stats(StatsService.Stats s) {
        return new Stats(s.defectsByStatus(), s.defectsBySeverity(), s.tasksByState(), s.conditionsByState(),
                s.defects(), s.tasks(), s.conditions(),
                s.createdPerDay().stream().map(d -> new DayCount(d.day(), d.defects(), d.tasks())).toList());
    }

    public static ProjectRow projectRow(CatalogService.ProjectView p) {
        return new ProjectRow(p.id(), p.code(), p.title());
    }

    public static CodeRow codeRow(CatalogService.CodeView c) {
        return new CodeRow(c.group(), c.code(), c.nameKo(), c.nameEn(), c.descriptionKo(), c.descriptionEn(),
                c.sortOrder(), c.state().token());
    }
}
