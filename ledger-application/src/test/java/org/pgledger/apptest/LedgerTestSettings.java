package org.pgledger.apptest;

import java.util.UUID;

/**
 * The configuration properties of the test contexts. They are Spring Boot's own: an in-memory H2 database in its
 * default mode (the lock wait is long because the race tests make many writers wait for one row), a pool that
 * holds the threads of those tests, and the time zone of the database session.
 */
public final class LedgerTestSettings {

    private static final String H2_OPTIONS = ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000";

    /** The database of the context of {@code @SpringBootTest}: a name no other context uses. */
    public static final String URL = "spring.datasource.url=jdbc:h2:mem:${random.uuid}" + H2_OPTIONS;
    public static final String POOL = "spring.datasource.hikari.maximum-pool-size=40";
    public static final String UTC_SESSION = "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'";
    public static final String QUIET_BANNER = "spring.main.banner-mode=off";
    public static final String QUIET_LOG = "logging.level.root=WARN";
    public static final String NO_JMX = "spring.jmx.enabled=false";

    private LedgerTestSettings() {
    }

    /** The URL of a new empty database, for the contexts that {@code ApplicationContextRunner} builds. */
    static String newUrl() {
        return "spring.datasource.url=jdbc:h2:mem:t" + UUID.randomUUID().toString().replace("-", "") + H2_OPTIONS;
    }

    /** The time zone of the database sessions of a context. */
    static String session(String zone) {
        return "spring.datasource.hikari.connection-init-sql=SET TIME ZONE '" + zone + "'";
    }
}
