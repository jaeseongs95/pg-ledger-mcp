package org.pgledger.application.read;

import java.util.List;

/**
 * One page of a list: the items, whether more follow ({@code truncated}) and, when they do, the cursor of the next
 * page. The cursor is opaque, belongs to one list and the filters it was made with, and holds only a position.
 */
public record Page<T>(List<T> items, boolean truncated, String nextCursor) {

    public Page {
        items = List.copyOf(items);
    }
}
