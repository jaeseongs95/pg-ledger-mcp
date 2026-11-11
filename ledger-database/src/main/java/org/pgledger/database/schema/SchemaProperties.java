package org.pgledger.database.schema;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Schema settings ({@code ledger.schema.*}).
 *
 * @param location     resource prefix that holds the {@code V{n}__{name}.sql} files
 * @param longTextType the only per-DBMS difference in the DDL: the long text type that replaces the
 *                     {@code ${longtext}} placeholder (for example {@code TEXT} or {@code CLOB})
 * @param requireUtc   fail startup when the JVM zone or the database session zone is not UTC (default true)
 * @param name         the schema that holds the tables, or blank for the default schema of the account. The
 *                     migrator creates it when it is missing; every connection of the application uses it
 */
@ConfigurationProperties(prefix = "ledger.schema")
public record SchemaProperties(String location, String longTextType, Boolean requireUtc, String name) {

    public SchemaProperties {
        if (location == null || location.isBlank()) {
            location = "classpath:db/";
        }
        if (longTextType == null || longTextType.isBlank()) {
            longTextType = "TEXT";
        }
        if (requireUtc == null) {
            requireUtc = Boolean.TRUE;
        }
        if (name != null && name.isBlank()) {
            name = null;
        }
    }
}
