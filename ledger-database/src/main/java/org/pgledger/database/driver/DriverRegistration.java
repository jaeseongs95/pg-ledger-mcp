package org.pgledger.database.driver;

import java.time.Instant;

/**
 * The registered JDBC driver ({@code ledger.jdbc.driver.*}, read from the registration file through
 * {@code spring.config.import}): where the jar is, which class is the driver, and the SHA-256 of the jar at the time
 * it was registered. The product ships no driver; the user supplies and registers one.
 *
 * @param jar          absolute path of the driver jar
 * @param className    the {@code java.sql.Driver} class inside the jar
 * @param sha256       SHA-256 of the jar (64 lower-case hex characters)
 * @param registeredAt when it was registered (UTC), informational
 */
public record DriverRegistration(String jar, String className, String sha256, Instant registeredAt) {
}
