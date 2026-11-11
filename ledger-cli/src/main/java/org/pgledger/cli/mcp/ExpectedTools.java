package org.pgledger.cli.mcp;

import java.util.List;
import java.util.stream.Stream;

/**
 * The tools of the ledger MCP server that {@code mcp-check} expects (the names are part of the external contract: the ten
 * write tools and the twelve read tools). The list is written here once; the test {@code CLI-010} lists the tools of the
 * real server and fails when the two differ, so a tool added to or removed from the server cannot go unnoticed.
 */
public final class ExpectedTools {

    public static final List<String> WRITE = List.of("ledger_save", "ledger_transition", "ledger_record_fix",
            "ledger_record_case", "ledger_link", "ledger_correct", "ledger_batch", "ledger_review",
            "ledger_review_correct", "ledger_whoami");

    public static final List<String> READ = List.of("ledger_get", "ledger_defect", "ledger_task", "ledger_history",
            "ledger_search", "ledger_defects", "ledger_tasks", "ledger_conditions", "ledger_attention",
            "ledger_stats", "ledger_projects", "ledger_codes");

    private ExpectedTools() {
    }

    /** Every expected tool name, write tools first. */
    public static List<String> all() {
        return Stream.concat(WRITE.stream(), READ.stream()).toList();
    }
}
