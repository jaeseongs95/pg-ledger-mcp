package org.pgledger.database.driver;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Connection settings ({@code ledger.jdbc.*}). The password is not part of any configuration file: it arrives
 * through the secret store (a configtree property source). A value that does not bind or validate stops the startup.
 *
 * @param url      JDBC URL
 * @param username database account
 * @param password database password
 * @param poolSize maximum pool size
 * @param driver   the registered driver
 */
@Validated
@ConfigurationProperties(prefix = "ledger.jdbc")
public record LedgerJdbcProperties(String url, String username, String password,
        @DefaultValue("10") @Min(1) int poolSize, @DefaultValue @Valid DriverRegistration driver) {

    /**
     * Never prints the password, and not the password inside the URL either. A record prints every component by
     * default, and a settings object ends up in logs and failure messages ({@code LegacySourceProperties} does the same).
     */
    @Override
    public String toString() {
        return "LedgerJdbcProperties[url=" + RegisteredDriver.sanitize(url) + ", username=" + username + ", poolSize="
                + poolSize + ", driver=" + driver + "]";
    }
}
