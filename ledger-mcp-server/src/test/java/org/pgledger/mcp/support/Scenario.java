package org.pgledger.mcp.support;

import java.util.List;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.springframework.context.ApplicationContext;
import org.pgledger.testing.TestOwners;

/**
 * A project of its own with the actors of the catalogue: an author with the defect, task, fix and verify grants, a
 * reviewer with fix and verify, an author with only the defect grant, and one without any grant. Built through the
 * real services, so every test starts the way an installation does.
 */
public final class Scenario {

    public static final String AUTHOR = "claude:e2e-author";
    public static final String REVIEWER = "codex:e2e-reviewer";
    public static final String DEFECT_ONLY = "codex:defect-only";
    public static final String NOGRANT = "gemini:e2e-nogrant";
    public static final String REASON = "a reason that is long enough";
    public static final String EVIDENCE = "https://evidence.example/1";

    private final ApplicationContext context;
    private final String projectId;
    private final String code;

    public Scenario(ApplicationContext context) {
        this.context = context;
        int number = context.getBean(ProjectNumbers.class).next();
        this.projectId = "p" + number;
        this.code = "E2E" + number;
        context.getBean(ProjectService.class).add(new ProjectCommand(projectId, code, "Project " + number), TestOwners.OWNER);
        grant(AUTHOR, "defect", "task", "fix", "verify");
        grant(REVIEWER, "fix", "verify");
        grant(DEFECT_ONLY, "defect");
    }

    /** Gives the author the scopes on the project of this scenario. */
    public void grant(String author, String... scopes) {
        // a grant needs a live key of the author: the owner registered it first
        context.getBean(KeyRing.class).enroll(author);
        context.getBean(GrantService.class).grant(new GrantCommand(author, projectId, List.of(scopes), null, null), TestOwners.OWNER);
    }

    /**
     * A new author id that holds exactly these scopes on this project and nowhere else (the actors of the constants
     * hold grants on every scenario project, so their whoami lists the projects of the other tests as well).
     */
    public String actorWith(String label, String... scopes) {
        String actor = "claude:" + label + "-" + projectId;
        grant(actor, scopes);
        return actor;
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public String projectId() {
        return projectId;
    }

    /** The project code that prefixes defect and task ids. */
    public String code() {
        return code;
    }

    public String defectId(int number) {
        return code + "-D-" + String.format("%04d", number);
    }

    public String taskId(int number) {
        return code + "-T-" + String.format("%04d", number);
    }
}
