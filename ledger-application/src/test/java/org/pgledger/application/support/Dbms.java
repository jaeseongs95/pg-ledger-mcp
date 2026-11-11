package org.pgledger.application.support;

/** The two databases the service tests run on: H2 in default mode and an embedded PostgreSQL. */
public enum Dbms {
    H2("CLOB"), POSTGRESQL("TEXT");

    private final String longText;

    Dbms(String longText) {
        this.longText = longText;
    }

    /** The long text type that replaces the placeholder of the schema files. */
    public String longText() {
        return longText;
    }
}
