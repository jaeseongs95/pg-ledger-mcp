package org.pgledger.application.read;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.pgledger.database.repository.KeysetPage;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
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

    /** The digits of a number id: at most 18, so that every one fits a {@code long}. */
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,18}");

    /** A position read from a cursor. */
    public record Position(String sortKey, String id) {

        /**
         * The id as a number (the position in a list that is ordered by a number id, such as the history). A cursor is
         * checked for damage but not signed, so its id is whatever the caller made it: anything that is not a number
         * of at most 18 digits is a damaged cursor, never a {@code NumberFormatException}.
         *
         * @throws ValidationException VALIDATION_FAILED on {@code cursor} (CURSOR_DAMAGED)
         */
        public long number() {
            if (!NUMBER.matcher(id).matches()) {
                throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
            }
            return Long.parseLong(id);
        }
    }

    /** The row limit of a call; absent means the default of 50. */
    public MaxRows maxRows(Number raw) {
        return MaxRows.of(raw);
    }

    public String encode(PageCursor.Kind kind, Object filter, String sortKey, String id) {
        return new PageCursor(kind, digest(filter) + ":" + (sortKey == null ? "" : sortKey), id).encode();
    }

    /** The position of {@code cursor}, or none for the first page (no cursor). */
    public Optional<Position> decode(String cursor, PageCursor.Kind kind, Object filter) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        PageCursor decoded = PageCursor.decode(cursor, kind);
        String expected = digest(filter) + ":";
        String sortKey = decoded.sortKey();
        if (sortKey == null || !sortKey.startsWith(expected)) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_OTHER_FILTERS);
        }
        String rest = sortKey.substring(expected.length());
        return Optional.of(new Position(rest.isEmpty() ? null : rest, decoded.id()));
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
