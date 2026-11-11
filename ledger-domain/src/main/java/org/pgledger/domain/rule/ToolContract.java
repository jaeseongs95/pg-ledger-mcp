package org.pgledger.domain.rule;

import java.util.List;
import java.util.stream.Stream;

/**
 * The names of the tools of the ledger MCP server: the external contract that every client relies on (the ten write tools
 * and the twelve read tools). The list is written once, here. The server tests list the tools of the real server and
 * require exactly these names, and the CLI command {@code mcp-check} requires the same names from a running server, so a
 * tool added to or removed from the server fails a test until this list follows.
 */
public final class ToolContract {

    public static final List<String> WRITE = List.of("ledger_save", "ledger_transition", "ledger_record_fix",
            "ledger_record_case", "ledger_link", "ledger_correct", "ledger_batch", "ledger_review",
            "ledger_review_correct", "ledger_whoami");

    public static final List<String> READ = List.of("ledger_get", "ledger_defect", "ledger_task", "ledger_history",
            "ledger_search", "ledger_defects", "ledger_tasks", "ledger_conditions", "ledger_attention",
            "ledger_stats", "ledger_projects", "ledger_codes");

    private ToolContract() {
    }

    /** Every tool name, write tools first. */
    public static List<String> all() {
        return Stream.concat(WRITE.stream(), READ.stream()).toList();
    }
}
