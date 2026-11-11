package org.pgledger.application.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;

/**
 * The factory of fresh, empty in-memory H2 databases for one test run ({@link LedgerAppExtension} keeps one
 * instance in the root store of the run). H2 runs in its default mode; the lock wait is long because the race tests
 * make many writers wait for one row.
 */
public final class Databases {

    /** The URL of a new empty database. */
    public String newUrl(Dbms dbms) {
        return switch (dbms) {
            case H2 -> "jdbc:h2:mem:t" + UUID.randomUUID().toString().replace("-", "")
                    + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000";
        };
    }

    /** A pool on a new, empty database whose sessions run in {@code zone} (a test setup statement). */
    public HikariDataSource newDataSource(Dbms dbms, String zone) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(newUrl(dbms));
        config.setMaximumPoolSize(40);
        config.setConnectionInitSql("SET TIME ZONE '" + zone + "'");
        return new HikariDataSource(config);
    }
}
