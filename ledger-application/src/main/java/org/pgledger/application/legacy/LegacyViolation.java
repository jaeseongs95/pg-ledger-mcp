package org.pgledger.application.legacy;

import java.util.List;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;

/**
 * One value of the legacy ledger that cannot be imported as it is: the table of the new schema, the key of the row in the
 * legacy table, the column, and why. The import reports every one of them and writes nothing; it never invents a value
 * to make a row fit.
 *
 * @param table      the table of the new schema the row would go to
 * @param key        the key of the row in the legacy table (the key columns joined by {@code |})
 * @param column     the column of the new table
 * @param reason     why the value is refused
 * @param reasonArgs the arguments of the reason (a limit, a format)
 */
public record LegacyViolation(String table, String key, String column, ValidationReason reason,
        List<String> reasonArgs) {

    public LegacyViolation {
        reasonArgs = List.copyOf(reasonArgs);
    }

    /** {@code table[key].column}, the path the refusal names the value by. */
    public String path() {
        return table + "[" + key + "]." + column;
    }

    ValidationIssue toIssue() {
        return new ValidationIssue(path(), reason, reasonArgs);
    }
}
