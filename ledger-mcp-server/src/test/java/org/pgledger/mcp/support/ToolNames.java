package org.pgledger.mcp.support;

import java.util.List;
import java.util.stream.Stream;

/** The tools of the contract (catalogue sections 7 and 8): the names are part of the external interface. */
public final class ToolNames {

    public static final List<String> WRITE = List.of("ledger_save", "ledger_transition", "ledger_record_fix",
            "ledger_record_case", "ledger_link", "ledger_correct", "ledger_batch", "ledger_review",
            "ledger_review_correct", "ledger_whoami");

    public static final List<String> READ = List.of("ledger_get", "ledger_defect", "ledger_task", "ledger_history",
            "ledger_search", "ledger_defects", "ledger_tasks", "ledger_conditions", "ledger_attention",
            "ledger_stats", "ledger_projects", "ledger_codes");

    private ToolNames() {
    }

    public static List<String> all() {
        return Stream.concat(WRITE.stream(), READ.stream()).toList();
    }

    public static Stream<String> write() {
        return WRITE.stream();
    }

    public static Stream<String> read() {
        return READ.stream();
    }
}
