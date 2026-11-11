package org.pgledger.mcp.support;

import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.tool.WriteOutputs;

/**
 * The rows that most cases start from, made through the tools of the server in the project of a scenario: an OPEN
 * defect, a task, a defect with a recorded fix (SOURCE_FIXED, version 2) and a defect that the reviewer has verified.
 *
 * @param project            the project of the scenario
 * @param defect             an OPEN defect, version 1
 * @param defectHistoryId    the history row that created it
 * @param task               a task, version 1
 * @param fixed              a SOURCE_FIXED defect, version 2
 * @param verified           a VERIFIED defect
 * @param verificationRow    the history row of that verification
 */
public record Seed(String project, String defect, long defectHistoryId, String task, String fixed, String verified,
        long verificationRow) {

    public static final String COMMIT = "a".repeat(40);

    /** Makes the rows with the author and the reviewer of the scenario. */
    public static Seed create(McpSession session, Scenario scenario) {
        String project = scenario.projectId();
        WriteOutputs.Saved defect = session.ok("ledger_save",
                Calls.Save.create("defect", Fields.defect(project, "open defect"), Scenario.AUTHOR),
                WriteOutputs.Saved.class);
        WriteOutputs.Saved task = session.ok("ledger_save",
                Calls.Save.create("task", Fields.task(project, "a task"), Scenario.AUTHOR),
                WriteOutputs.Saved.class);
        String fixed = fixedDefect(session, project, "fixed defect", COMMIT);
        String verified = fixedDefect(session, project, "verified defect", "b".repeat(40));
        WriteOutputs.Transitioned verification = session.ok("ledger_review",
                Calls.Review.of("defect", verified, 2, "VERIFIED", Scenario.REVIEWER),
                WriteOutputs.Transitioned.class);
        return new Seed(project, defect.id(), defect.historyId(), task.id(), fixed, verified,
                verification.historyId());
    }

    private static String fixedDefect(McpSession session, String project, String title, String commit) {
        WriteOutputs.Saved created = session.ok("ledger_save",
                Calls.Save.create("defect", Fields.defect(project, title), Scenario.AUTHOR),
                WriteOutputs.Saved.class);
        session.ok("ledger_record_fix", Calls.RecordFix.of(created.id(), commit, Scenario.AUTHOR),
                WriteOutputs.FixRecorded.class);
        return created.id();
    }
}
