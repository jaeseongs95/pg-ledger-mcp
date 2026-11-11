package org.pgledger.mcp.support;

import java.util.List;
import org.pgledger.mcp.tool.ReadOutputs;

/** Reads of the ledger through the read tools, for the cases that check what was (not) written. */
public final class Ledger {

    private Ledger() {
    }

    /** Every history row of the project (the tests keep a project small enough for one page). */
    public static List<ReadOutputs.History> history(McpSession session, String project) {
        ReadOutputs.Items<ReadOutputs.History> page = session.readItems(
                session.call("ledger_history", Calls.History.of(project)), ReadOutputs.History.class);
        return page.items();
    }

    /** The item of {@code ledger_get} for the id, or the ids that are not found. */
    public static ReadOutputs.Got get(McpSession session, String... ids) {
        return session.ok("ledger_get", new Calls.Ids(List.of(ids)), ReadOutputs.Got.class);
    }
}
