package org.pgledger.domain.value;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.pgledger.domain.error.ValidationException;

/**
 * Position after the last row of a page: the sort key (may be absent) and the id, for one kind of list. The
 * encoded form is opaque and carries a short checksum, so any damage is detected; it holds no more than a
 * position, so a hand-made cursor can only move the position of the reader who made it.
 */
public record PageCursor(Kind kind, String sortKey, String id) {

    /** The list a cursor belongs to; a cursor of another list is rejected. */
    public enum Kind {
        DEFECTS, TASKS, CONDITIONS, HISTORY, SEARCH, ATTENTION, PROJECTS, CODES
    }

    private static final String PATH = "cursor";

    public PageCursor {
        if (kind == null || id == null || id.isEmpty() || id.indexOf('\n') >= 0
                || (sortKey != null && sortKey.indexOf('\n') >= 0)) {
            throw ValidationException.invalid(PATH, "is not a valid position");
        }
    }

    public String encode() {
        String payload = kind.name() + "\n" + (sortKey == null ? "" : sortKey) + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + checksum(payload);
    }

    public static PageCursor decode(String token, Kind expected) {
        if (token == null) {
            throw ValidationException.invalid(PATH, "is required");
        }
        int dot = token.lastIndexOf('.');
        if (dot < 1) {
            throw ValidationException.invalid(PATH, "is damaged");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ValidationException.invalid(PATH, "is damaged");
        }
        if (!checksum(payload).equals(token.substring(dot + 1))) {
            throw ValidationException.invalid(PATH, "is damaged");
        }
        int first = payload.indexOf('\n');
        int second = first < 0 ? -1 : payload.indexOf('\n', first + 1);
        if (second < 0 || payload.indexOf('\n', second + 1) >= 0) {
            throw ValidationException.invalid(PATH, "is damaged");
        }
        String[] parts = {payload.substring(0, first), payload.substring(first + 1, second),
            payload.substring(second + 1)};
        Kind kind;
        try {
            kind = Kind.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            throw ValidationException.invalid(PATH, "is damaged");
        }
        if (kind != expected) {
            throw ValidationException.invalid(PATH, "belongs to another list");
        }
        return new PageCursor(kind, parts[1].isEmpty() ? null : parts[1], parts[2]);
    }

    private static String checksum(String payload) {
        return Sha256.ofText(payload).hex().substring(0, 8);
    }
}
