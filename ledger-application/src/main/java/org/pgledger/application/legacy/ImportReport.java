package org.pgledger.application.legacy;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What a legacy import (or a re-verification) found: the comparison of every table, all of which matched, or the call
 * would have been refused.
 *
 * @param schema the legacy schema that was read
 * @param tables one comparison per table, in the order the tables are copied
 */
public record ImportReport(String schema, List<TableCheck> tables) {

    public ImportReport {
        tables = List.copyOf(tables);
    }

    /** The rows of all tables. */
    public long rows() {
        return tables.stream().mapToLong(TableCheck::targetRows).sum();
    }

    /** The report as one line for the owner's audit trail: the rows of all tables and of each one. */
    public String detail() {
        return "rows=" + rows() + " " + tables.stream()
                .map(check -> check.table().targetName() + "=" + check.targetRows()).collect(Collectors.joining(", "));
    }
}
