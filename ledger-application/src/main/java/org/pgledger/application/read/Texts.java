package org.pgledger.application.read;

/** The one meaning of a blank text in a filter of a read call (the filter is not given), and of a NULL long text (it is empty). */
final class Texts {

    private Texts() {
    }

    /**
     * The text of a field that may be empty: the row holds NULL for it (no column holds {@code ''}), and the tools show
     * {@code ""} as 1.x did (a pure function of its argument).
     */
    static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /** The text, or null when it is null or blank (a pure function of its argument). */
    static String nullIfBlank(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
