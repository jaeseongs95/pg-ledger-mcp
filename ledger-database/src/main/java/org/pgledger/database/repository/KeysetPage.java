package org.pgledger.database.repository;

import java.util.List;

/** One page of a keyset list; {@code truncated} is true when more rows follow. */
public record KeysetPage<T>(List<T> rows, boolean truncated) {

    public KeysetPage {
        rows = List.copyOf(rows);
    }

    /** Cuts {@code fetched} (read with one row more than {@code maxRows}) to a page. */
    static <T> KeysetPage<T> of(List<T> fetched, int maxRows) {
        boolean more = fetched.size() > maxRows;
        return new KeysetPage<>(more ? fetched.subList(0, maxRows) : fetched, more);
    }
}
