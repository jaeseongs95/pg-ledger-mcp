package org.pgledger.database.schema;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Schema settings ({@code ledger.schema.*}).
 *
 * @param location     resource prefix that holds the {@code V{n}__{name}.sql} files
 * @param longTextType the only per-DBMS difference in the DDL: the long text type that replaces the
 *                     {@code ${longtext}} placeholder (for example {@code TEXT} or {@code CLOB})
 * @param requireUtc   fail startup when the JVM zone or the database session zone is not UTC (default true)
 * @param name         the schema that holds the tables, or blank for the default schema of the account. The
 *                     migrator creates it when it is missing; every connection of the application uses it
 * @param peerWait     how long a migration whose file failed waits for another migration process to finish the
 *                     same version before it records the failure (default 5 seconds; a DBMS that commits every DDL
 *                     statement at once shows the other process's half-done file as an error)
 */
@ConfigurationProperties(prefix = "ledger.schema")
public record SchemaProperties(String location, String longTextType, Boolean requireUtc, String name,
        Duration peerWait) {

    private static final Duration DEFAULT_PEER_WAIT = Duration.ofSeconds(5);

    @ConstructorBinding
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
        if (peerWait == null) {
            peerWait = DEFAULT_PEER_WAIT;
        }
    }

    public SchemaProperties(String location, String longTextType, Boolean requireUtc, String name) {
        this(location, longTextType, requireUtc, name, null);
    }
}
