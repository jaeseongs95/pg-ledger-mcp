package org.pgledger.hook;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The ten ledger tools that write and therefore carry the host attestation (the external contract).
 * Read tools (ledger_get, ledger_history, ledger_search, ledger_defects, ...) are deliberately absent.
 */
public enum WriteTool {
    SAVE("save"),
    TRANSITION("transition"),
    RECORD_FIX("record_fix"),
    RECORD_CASE("record_case"),
    LINK("link"),
    CORRECT("correct"),
    BATCH("batch"),
    REVIEW("review"),
    REVIEW_CORRECT("review_correct"),
    WHOAMI("whoami");

    private final String operation;

    WriteTool(String operation) {
        this.operation = operation;
    }

    /** The name after {@code ledger_}. */
    public String operation() {
        return operation;
    }

    /** Regex alternation of all operations, for the tool name patterns of the dialects. */
    static String alternation() {
        return Arrays.stream(values()).map(WriteTool::operation).collect(Collectors.joining("|", "(?:", ")"));
    }
}
