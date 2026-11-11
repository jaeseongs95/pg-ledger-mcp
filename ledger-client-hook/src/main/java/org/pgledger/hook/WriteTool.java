package org.pgledger.hook;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * Regex alternation of all operations as ONE capturing group, for the tool name patterns of the dialects: a
     * pattern that is built with it has no other capturing group, so {@link #find} reads the operation from group 1.
     */
    static String alternation() {
        return Arrays.stream(values()).map(WriteTool::operation).collect(Collectors.joining("|", "(", ")"));
    }

    /**
     * The write tool a host tool name stands for, whatever naming the host uses around {@code ledger_<operation>}.
     *
     * @param pattern a dialect pattern built with {@link #alternation()}
     */
    static Optional<WriteTool> find(Pattern pattern, String hostToolName) {
        Matcher matcher = pattern.matcher(hostToolName);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String found = matcher.group(1);
        return Arrays.stream(values()).filter(tool -> tool.operation.equals(found)).findFirst();
    }
}
