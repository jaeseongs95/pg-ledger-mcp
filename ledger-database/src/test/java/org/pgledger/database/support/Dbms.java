package org.pgledger.database.support;

/** The two databases the repository tests run on: H2 in default mode and an embedded PostgreSQL. */
public enum Dbms {
    H2("CLOB", false), POSTGRESQL("TEXT", true);

    private final String longText;
    private final boolean transactionalDdl;

    Dbms(String longText, boolean transactionalDdl) {
        this.longText = longText;
        this.transactionalDdl = transactionalDdl;
    }

    /** The long text type that replaces the placeholder of the schema files. */
    public String longText() {
        return longText;
    }

    /** True when a failed schema file is rolled back as a whole (H2 commits every DDL statement at once). */
    public boolean transactionalDdl() {
        return transactionalDdl;
    }
}
