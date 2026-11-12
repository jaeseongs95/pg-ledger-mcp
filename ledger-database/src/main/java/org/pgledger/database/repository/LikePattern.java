package org.pgledger.database.repository;

/**
 * The one place that turns a keyword into the pattern of an ANSI {@code LIKE ... ESCAPE '\'}: {@code %}, {@code _} and the
 * backslash of the keyword are literal, so a keyword can never act as a wildcard. The keyword search of the defects and the
 * keyword search over all kinds share it.
 */
final class LikePattern {

    private LikePattern() {
    }

    /** The pattern that matches a text that contains {@code keyword} (the bind value of the {@code LIKE}). */
    static String contains(String keyword) {
        return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
