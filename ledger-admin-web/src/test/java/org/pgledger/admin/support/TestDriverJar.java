package org.pgledger.admin.support;

import java.nio.file.Path;
import org.pgledger.testing.database.TestDatabaseSettings;

/**
 * The JDBC driver that the driver registration tests register: the PostgreSQL driver of the test PC. The jar is the file
 * that the build resolves and passes in as the system property {@value #JAR_PROPERTY}; the registration only asks the
 * driver whether it accepts the URL, it never connects, so no database is touched here.
 */
public final class TestDriverJar {

    /** The system property of the test JVM that holds the path of the PostgreSQL driver jar. */
    public static final String JAR_PROPERTY = "ledger.test.postgres-jar";

    /** The driver class of that jar. */
    public static final String DRIVER_CLASS = "org.postgresql.Driver";

    /** A class that is in that jar and is not a {@code java.sql.Driver}. */
    public static final String NOT_A_DRIVER_CLASS = "org.postgresql.util.PGobject";

    /**
     * The property of the application under test that holds the JDBC URL, for the tests that start in setup mode (a
     * compile-time constant for an annotation; it is the URL of the test database that the tests share).
     */
    public static final String URL_PROPERTY = "ledger.jdbc.url=jdbc:postgresql://127.0.0.1:55432/ledger_test";

    private TestDriverJar() {
    }

    /**
     * The driver jar where the build resolved it (outside every allowed driver directory of the tests).
     *
     * @throws IllegalStateException naming {@value #JAR_PROPERTY} when the build did not pass it in
     */
    public static Path jar() {
        String path = System.getProperty(JAR_PROPERTY);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("the test property " + JAR_PROPERTY + " is not set (the root build.gradle.kts "
                    + "passes the path of the PostgreSQL driver jar to the test JVM)");
        }
        return Path.of(path);
    }

    /** The JDBC URL of the test database, which the PostgreSQL driver accepts. */
    public static String url() {
        return TestDatabaseSettings.fromSystemProperties().url();
    }
}
