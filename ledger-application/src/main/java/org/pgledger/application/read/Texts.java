package org.pgledger.application.read;

/** The one meaning of a blank text in a filter of a read call: the filter is not given. */
final class Texts {

    private Texts() {
    }

    /** The text, or null when it is null or blank (a pure function of its argument). */
    static String nullIfBlank(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
