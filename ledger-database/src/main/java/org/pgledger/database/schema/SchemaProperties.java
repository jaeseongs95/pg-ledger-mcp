package org.pgledger.database.schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Schema settings ({@code ledger.schema.*}). A value that does not bind or validate stops the startup.
 *
 * @param location     resource prefix that holds the {@code V{n}__{name}.sql} files
 * @param longTextType the only per-DBMS difference in the DDL: the long text type that replaces the
 *                     {@code ${longtext}} placeholder (for example {@code TEXT} or {@code CLOB})
 * @param requireUtc   fail startup when the JVM zone or the database session zone is not UTC
 * @param name         the schema that holds the tables, or absent for the default schema of the account. The
 *                     migrator creates it when it is missing; every connection of the application uses it
 * @param peerWait     how long a migration whose file failed waits for another migration process to finish the
 *                     same version before it records the failure (a DBMS that commits every DDL statement at once
 *                     shows the other process's half-done file as an error)
 */
@Validated
@ConfigurationProperties(prefix = "ledger.schema")
public record SchemaProperties(
        @DefaultValue(SchemaProperties.DEFAULT_LOCATION) @NotBlank String location,
        @DefaultValue(SchemaProperties.DEFAULT_LONG_TEXT_TYPE) @NotBlank String longTextType,
        @DefaultValue("true") boolean requireUtc,
        @Pattern(regexp = "[a-z][a-z0-9_]{0,29}") String name,
        @DefaultValue(SchemaProperties.DEFAULT_PEER_WAIT) Duration peerWait) {

    /** The defaults of the binding; code that builds the settings by hand passes them on explicitly. */
    public static final String DEFAULT_LOCATION = "classpath:db/";
    public static final String DEFAULT_LONG_TEXT_TYPE = "TEXT";
    /** ISO-8601, which {@link Duration#parse} and the Boot binder both read. */
    public static final String DEFAULT_PEER_WAIT = "PT5S";
}
