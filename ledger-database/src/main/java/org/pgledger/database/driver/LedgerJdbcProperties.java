package org.pgledger.database.driver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings ({@code ledger.jdbc.*}). The password is not part of any configuration file: it arrives
 * through the secret store (a configtree property source).
 *
 * @param url      JDBC URL
 * @param username database account
 * @param password database password
 * @param poolSize maximum pool size (default 10)
 * @param driver   the registered driver
 */
@ConfigurationProperties(prefix = "ledger.jdbc")
public record LedgerJdbcProperties(String url, String username, String password, Integer poolSize,
        DriverRegistration driver) {

    public LedgerJdbcProperties {
        if (poolSize == null) {
            poolSize = 10;
        }
        if (driver == null) {
            driver = new DriverRegistration(null, null, null, null);
        }
    }
}
