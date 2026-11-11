package org.pgledger.application.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.WriteResult.RecordFixResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TaskId;

/**
 * A project of its own with the actors of the catalogue (project {@code e2e}-style fixtures): an author with the
 * defect, task, fix and verify grants, a reviewer with fix and verify, an author with only the defect grant, and
 * one without any grant. Built through the real services, so every scenario starts the way an installation does.
 */
public final class Scenario {

    public static final String AUTHOR = "claude:e2e-author";
    public static final String REVIEWER = "codex:e2e-reviewer";
    public static final String DEFECT_ONLY = "codex:defect-only";
    public static final String NOGRANT = "gemini:e2e-nogrant";
    public static final String REASON = "a reason that is long enough";
    public static final String EVIDENCE = "https://evidence.example/1";

    private final LedgerAppContext ctx;
    private final String projectId;
    private final String code;

    Scenario(LedgerAppContext ctx) {
        this.ctx = ctx;
        int number = ctx.nextProjectNumber();
        this.projectId = "p" + number;
        this.code = "E2E" + number;
        ctx.clock().set(LedgerAppContext.START);
        ProjectService.ProjectCommand project = new ProjectCommand(projectId, code, "Project " + number);
        ctx.bean(ProjectService.class).add(project);
        GrantService grants = ctx.bean(GrantService.class);
        grants.grant(new GrantCommand(AUTHOR, projectId, List.of("defect", "task", "fix", "verify"), null, null));
        grants.grant(new GrantCommand(REVIEWER, projectId, List.of("fix", "verify"), null, null));
        grants.grant(new GrantCommand(DEFECT_ONLY, projectId, List.of("defect"), null, null));
    }

    public LedgerAppContext ctx() {
        return ctx;
    }

    public <T> T bean(Class<T> type) {
        return ctx.bean(type);
    }

    public String projectId() {
        return projectId;
    }

    /** The project code that prefixes defect and task ids. */
    public String code() {
        return code;
    }

    public String defectId(int number) {
        return code + "-D-" + "0".repeat(Math.max(0, 4 - Integer.toString(number).length())) + number;
    }

    public String taskId(int number) {
        return code + "-T-" + "0".repeat(Math.max(0, 4 - Integer.toString(number).length())) + number;
    }

    public String conditionId(String suffix) {
        return code + "-C-" + suffix;
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** A 40 character lower-case hex commit id made of one digit. */
    public static String commit(char digit) {
        return String.valueOf(digit).repeat(40);
    }

    // ----------------------------------------------------------------- field maps

    public Map<String, String> defectFields(String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", projectId);
        fields.put("title", title);
        fields.put("category", "ARC");
        fields.put("severity", "P2");
        fields.put("behavior", "behavior of " + title);
        fields.put("root_cause", "cause of " + title);
        return fields;
    }

    public Map<String, String> taskFields(String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", projectId);
        fields.put("title", title);
        fields.put("purpose", "purpose of " + title);
        fields.put("success_condition", "success of " + title);
        return fields;
    }

    public Map<String, String> conditionFields(String release, String conditionCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", projectId);
        fields.put("release", release);
        fields.put("code", conditionCode);
        fields.put("condition_text", "condition " + conditionCode);
        return fields;
    }

    // ------------------------------------------------------------------ commands

    public SaveCommand create(String entity, String id, Map<String, String> fields) {
        return new SaveCommand(null, entity, id, 0L, fields, REASON);
    }

    public SaveCommand edit(String entity, String id, long version, Map<String, String> fields) {
        return new SaveCommand(null, entity, id, version, fields, REASON);
    }

    public TransitionCommand move(String entity, String id, long version, String to, String evidence) {
        return new TransitionCommand(null, entity, id, version, to, REASON, evidence, null, null);
    }

    public RecordFixCommand fixCommand(String defectId, String commit) {
        return new RecordFixCommand(null, defectId, "repo/e2e", commit, REASON, null, null, null, null, null);
    }

    // --------------------------------------------------------------- shortcuts

    public SaveResult newDefect(String title) {
        return bean(SaveService.class).save(AUTHOR, create("defect", null, defectFields(title)));
    }

    public SaveResult newTask(String title) {
        return bean(SaveService.class).save(AUTHOR, create("task", null, taskFields(title)));
    }

    public SaveResult newCondition(String suffix, String release, String conditionCode) {
        return bean(SaveService.class).save(AUTHOR,
                create("condition", conditionId(suffix), conditionFields(release, conditionCode)));
    }

    /** Records a fix commit as {@code actor}; an OPEN defect becomes SOURCE_FIXED. */
    public RecordFixResult fix(String actor, String defectId, String commit) {
        return bean(RecordFixService.class).recordFix(actor, fixCommand(defectId, commit));
    }

    /** A defect that the author has fixed (SOURCE_FIXED, version 2). */
    public SaveResult sourceFixedDefect(String title) {
        SaveResult defect = newDefect(title);
        fix(AUTHOR, defect.id(), commit('a'));
        return defect;
    }

    /** A defect that the reviewer verified (VERIFIED, version 3). */
    public SaveResult verifiedDefect(String title) {
        SaveResult defect = sourceFixedDefect(title);
        bean(org.pgledger.application.write.ReviewService.class).review(REVIEWER,
                move("defect", defect.id(), 2, "VERIFIED", EVIDENCE));
        return defect;
    }

    /** A new author id with live grants of exactly these scopes on this project. */
    public String actorWith(String... scopes) {
        String actor = "claude:t" + UUID.randomUUID().toString().substring(0, 8);
        grant(actor, projectId, scopes);
        return actor;
    }

    public void grant(String actor, String project, String... scopes) {
        bean(GrantService.class).grant(new GrantCommand(actor, project, List.of(scopes), null, null));
    }

    /** A second project (codes and ids of its own) for cross-project cases. */
    public String otherProject() {
        int number = ctx.nextProjectNumber();
        bean(ProjectService.class).add(new ProjectCommand("p" + number, "E2E" + number, "Project " + number));
        return "p" + number;
    }

    // ------------------------------------------------------------------ reading

    public Defect defect(String id) {
        return bean(DefectRepository.class).findById(DefectId.of(id)).orElseThrow();
    }

    public Task task(String id) {
        return bean(TaskRepository.class).findById(TaskId.of(id)).orElseThrow();
    }

    public List<HistoryEntry> history(HistoryEntity entity, String entityId) {
        return bean(HistoryRepository.class).findByEntityAndEntityIdOrderById(entity, entityId);
    }

    public HistoryEntry historyRow(long id) {
        return bean(HistoryRepository.class).findById(id).orElseThrow();
    }

    public long count(String sql, String... values) {
        var statement = ctx.jdbc().sql(sql);
        for (int i = 0; i < values.length; i++) {
            statement = statement.param(values[i]);
        }
        return statement.query(Long.class).single();
    }
}
