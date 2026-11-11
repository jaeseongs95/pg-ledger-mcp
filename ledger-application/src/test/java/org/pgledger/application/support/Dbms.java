package org.pgledger.application.support;

/** The database the service tests run on: the embedded H2 in its default mode (no compatibility mode). */
public enum Dbms {
    H2("CLOB");

    private final String longText;

    Dbms(String longText) {
        this.longText = longText;
    }

    /** The long text type that replaces the placeholder of the schema files. */
    public String longText() {
        return longText;
    }
}
