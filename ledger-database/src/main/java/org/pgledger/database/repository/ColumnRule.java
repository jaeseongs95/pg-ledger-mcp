package org.pgledger.database.repository;

/**
 * What the new schema says about one column, read from the JDBC metadata of the database itself (so it cannot drift from the
 * schema files): whether it accepts NULL and how many characters a text column holds ({@code 0} for a column that has no
 * limit the metadata can state, for example a long text column).
 */
public record ColumnRule(boolean nullable, int maxChars) {
}
