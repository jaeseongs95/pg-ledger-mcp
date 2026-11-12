package org.pgledger.hook;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
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

    /** The canonical tool name of the server, {@code ledger_<operation>}: what the attestation is issued for. */
    public String canonicalName() {
        return "ledger_" + operation;
    }

    /**
     * Regex alternation of all operations as ONE capturing group, for the tool name patterns of the host dialects:
     * the operation is read from that group and checked again with {@link #ofOperation}.
     */
    static String alternation() {
        return Arrays.stream(values()).map(WriteTool::operation).collect(Collectors.joining("|", "(", ")"));
    }

    /** The write tool of the operation name (exactly one of the ten), if it is one. */
    static Optional<WriteTool> ofOperation(String found) {
        return Arrays.stream(values()).filter(tool -> Objects.equals(tool.operation, found)).findFirst();
    }
}
