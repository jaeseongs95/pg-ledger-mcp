package org.pgledger.application.read;

import java.util.List;
import java.util.function.Function;
import org.pgledger.database.repository.KeysetPage;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.Sha256;
import org.springframework.stereotype.Component;

/**
 * Makes and reads the cursors of the lists. A cursor is the domain's {@link PageCursor} (list kind, sort key, id)
 * whose sort key is prefixed with a short digest of the filters of the call, so a cursor is refused when the
 * filters changed or when it was made for another list.
 */
@Component
public class Cursors {

    /** A position read from a cursor. */
    public record Position(String sortKey, String id) {
    }

    /** The row limit of a call; absent means the default of 50. */
    public MaxRows maxRows(Number raw) {
        return MaxRows.of(raw);
    }

    public String encode(PageCursor.Kind kind, Object filter, String sortKey, String id) {
        return new PageCursor(kind, digest(filter) + ":" + (sortKey == null ? "" : sortKey), id).encode();
    }

    /** The position of {@code cursor}, or null for the first page. */
    public Position decode(String cursor, PageCursor.Kind kind, Object filter) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        PageCursor decoded = PageCursor.decode(cursor, kind);
        String expected = digest(filter) + ":";
        String sortKey = decoded.sortKey();
        if (sortKey == null || !sortKey.startsWith(expected)) {
            throw ValidationException.invalid("cursor", "was made for other filters");
        }
        String rest = sortKey.substring(expected.length());
        return new Position(rest.isEmpty() ? null : rest, decoded.id());
    }

    /** Builds the page from a keyset page: the next cursor is made from the last row when more rows follow. */
    public <T, R> Page<R> page(KeysetPage<T> page, PageCursor.Kind kind, Object filter, Function<T, R> view,
            Function<T, String> sortKey, Function<T, String> id) {
        List<R> items = page.rows().stream().map(view).toList();
        String next = null;
        if (page.truncated() && !page.rows().isEmpty()) {
            T last = page.rows().getLast();
            next = encode(kind, filter, sortKey.apply(last), id.apply(last));
        }
        return new Page<>(items, page.truncated(), next);
    }

    private static String digest(Object filter) {
        return Sha256.ofText(String.valueOf(filter)).hex().substring(0, 8);
    }
}
