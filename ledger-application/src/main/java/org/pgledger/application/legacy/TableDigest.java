package org.pgledger.application.legacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.pgledger.database.repository.LegacyColumn;
import org.pgledger.database.repository.LegacyRow;
import org.pgledger.database.repository.LegacyTable;
import org.pgledger.domain.value.Sha256;

/**
 * The count and the digest of the rows of one table, collected row by row. The digest is the SHA-256 over the lines
 * {@code <canonical key> TAB <digest of the canonical row> LF}, ordered by the canonical key of the new table (a plain
 * string comparison, so no collation of any DBMS takes part and the two sides order alike). Only the key and the
 * 64-character digest of a row are kept, not the row.
 */
final class TableDigest {

    private record Entry(String key, String rowDigest) {
    }

    private final LegacyTable table;
    private final List<LegacyColumn> keyColumns;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();

    TableDigest(LegacyTable table) {
        this.table = table;
        this.keyColumns = table.targetColumns().stream().filter(column -> table.targetKey().contains(column.name()))
                .toList();
    }

    /** The canonical key of a row of the new table's shape. */
    String keyOf(LegacyRow row) {
        return CanonicalRow.of(keyColumns, row);
    }

    /** Adds a row of the new table's shape. */
    void add(LegacyRow row) {
        String key = keyOf(row);
        entries.add(new Entry(key, CanonicalRow.digest(table.targetColumns(), row)));
        keys.add(key);
    }

    /** Adds the row only when its key is one of {@code only}; the common codes are compared by the keys of the legacy rows. */
    void addIfKeyed(LegacyRow row, Set<String> only) {
        if (only.contains(keyOf(row))) {
            add(row);
        }
    }

    long count() {
        return entries.size();
    }

    /** The canonical keys of the rows added. */
    Set<String> keys() {
        return keys;
    }

    Sha256 digest() {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(Entry::key));
        StringBuilder lines = new StringBuilder();
        for (Entry entry : ordered) {
            lines.append(entry.key()).append('\t').append(entry.rowDigest()).append('\n');
        }
        return Sha256.ofText(lines.toString());
    }
}
