package org.pgledger.database.driver;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The connection to the legacy ledger that {@code import-legacy} reads ({@code ledger.import.source.*}). Like the settings
 * of the ledger database it is read with the registered JDBC driver, and the password is not part of any configuration
 * file: it arrives through the secret store (the secret {@code ledger.import.source.password}, a configtree property
 * source). The account should be the legacy read-only login of the old installer ({@code ledger_reader}), and the command
 * never writes to it. A value that does not bind or validate stops the startup.
 *
 * @param url      JDBC URL of the database that holds the legacy schema
 * @param username database account (a read-only one)
 * @param password database password
 * @param schema   the legacy schema, {@code ledger} for every installation of the old installer
 */
@Validated
@ConfigurationProperties(prefix = "ledger.import.source")
public record LegacySourceProperties(@NotBlank String url, String username, String password,
        @DefaultValue("ledger") @Pattern(regexp = "[a-z][a-z0-9_]{0,29}") String schema) {

    /** Never prints the password, and not the password of the URL either. */
    @Override
    public String toString() {
        return "LegacySourceProperties[url=" + RegisteredDriver.sanitize(url) + ", username=" + username + ", schema="
                + schema + "]";
    }
}
