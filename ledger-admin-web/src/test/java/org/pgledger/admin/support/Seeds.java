package org.pgledger.admin.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.springframework.stereotype.Component;

/**
 * Rows for the page tests, made through the real services in a project of its own (so tests do not collide): the
 * author of the scenario holds the defect and task grants, exactly as an installation does.
 */
@Component
public class Seeds {

    public static final String AUTHOR = "claude:admin-test-author";
    public static final String REASON = "a reason that is long enough";

    /** A project and what was made in it. */
    public record Seeded(String project, String code) {

        public String defectId(int number) {
            return code + "-D-" + "0".repeat(4 - Integer.toString(number).length()) + number;
        }

        public String taskId(int number) {
            return code + "-T-" + "0".repeat(4 - Integer.toString(number).length()) + number;
        }
    }

    private final AtomicInteger numbers = new AtomicInteger();
    private final ProjectService projects;
    private final GrantService grants;
    private final SaveService saves;
    private final TransitionService transitions;
    private final RecordCaseService cases;
    private final MutableClock clock;

    public Seeds(ProjectService projects, GrantService grants, SaveService saves, TransitionService transitions,
            RecordCaseService cases,
            MutableClock clock) {
        this.projects = projects;
        this.grants = grants;
        this.saves = saves;
        this.transitions = transitions;
        this.cases = cases;
        this.clock = clock;
    }

    /** Moves the clock on by a second, so rows made one after the other have different times. */
    public void tick() {
        clock.advance(java.time.Duration.ofSeconds(1));
    }

    /** A new project of its own where the author may write defects and tasks. */
    public Seeded project() {
        int number = numbers.incrementAndGet();
        Seeded seeded = new Seeded("adm" + number, "ADM" + number);
        projects.add(new ProjectCommand(seeded.project(), seeded.code(), "Project " + number));
        grants.grant(new GrantCommand(AUTHOR, seeded.project(), List.of("defect", "task"), null, null));
        return seeded;
    }

    public String defect(Seeded seeded, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("category", "ARC");
        fields.put("severity", "P2");
        fields.put("behavior", "behavior of " + title);
        fields.put("root_cause", "cause of " + title);
        return saves.save(AUTHOR, new SaveCommand(null, "defect", null, 0L, fields, REASON)).id();
    }

    public String task(Seeded seeded, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("title", title);
        fields.put("purpose", "purpose of " + title);
        fields.put("success_condition", "success of " + title);
        return saves.save(AUTHOR, new SaveCommand(null, "task", null, 0L, fields, REASON)).id();
    }

    public String condition(Seeded seeded, String suffix, String release, String conditionCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", seeded.project());
        fields.put("release", release);
        fields.put("code", conditionCode);
        fields.put("condition_text", "condition " + conditionCode);
        return saves.save(AUTHOR, new SaveCommand(null, "condition", seeded.code() + "-C-" + suffix, 0L, fields,
                REASON)).id();
    }

    /** Moves an OPEN defect (version 1) to DISPUTED, with the given evidence text in the history row. */
    public void dispute(String defect, String evidence) {
        transitions.transition(AUTHOR, new TransitionCommand(null, "defect", defect, 1L, "DISPUTED", REASON, evidence,
                null, null));
    }

    /** Records an observation of a defect with the given evidence text. */
    public void observe(String defect, String observation, String evidence) {
        cases.recordCase(AUTHOR, new RecordCaseCommand(null, defect, observation, REASON, evidence, null, null));
    }
}
