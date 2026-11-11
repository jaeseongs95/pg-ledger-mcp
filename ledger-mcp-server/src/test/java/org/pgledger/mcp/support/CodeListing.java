package org.pgledger.mcp.support;

import java.util.Comparator;
import java.util.List;
import org.pgledger.mcp.tool.ReadOutputs.CodeRow;

/**
 * The common codes of a group as the {@code ledger_codes} tool reports them, and the line that the schema of a tool
 * shows for a code: {@code CODE name_ko (name_en): description_ko / description_en}.
 */
public final class CodeListing {

    private CodeListing() {
    }

    /** The active codes of the group in the order of the schema: by sort order, then by code. */
    public static List<CodeRow> active(McpSession session, String group) {
        return session.readItems(session.call("ledger_codes", Calls.Codes.of(group)), CodeRow.class).items().stream()
                .filter(row -> "ACTIVE".equals(row.state()))
                .sorted(Comparator.comparingInt(CodeRow::sortOrder).thenComparing(CodeRow::code)).toList();
    }

    /** The line of a code in the description of a schema. */
    public static String line(CodeRow row) {
        String description = row.descriptionKo() == null || row.descriptionKo().isBlank() ? ""
                : ": " + row.descriptionKo() + " / " + row.descriptionEn();
        return row.code() + " " + row.nameKo() + " (" + row.nameEn() + ")" + description;
    }

    /** The codes of the rows. */
    public static List<String> codes(List<CodeRow> rows) {
        return rows.stream().map(CodeRow::code).toList();
    }
}
