package org.pgledger.apptest;

/**
 * The configuration properties of the test contexts that are not about the database: the quiet start of Spring Boot.
 * The database is the PostgreSQL of the test PC (see {@code TestDataSourceConfiguration}); its schema comes from
 * {@code TestSchemaInitializer} and the time zone of its sessions from the property
 * {@code TestDataSourceConfiguration.ZONE_PROPERTY}.
 */
public final class LedgerTestSettings {

    public static final String QUIET_BANNER = "spring.main.banner-mode=off";
    public static final String QUIET_LOG = "logging.level.root=WARN";
    public static final String NO_JMX = "spring.jmx.enabled=false";

    private LedgerTestSettings() {
    }
}
