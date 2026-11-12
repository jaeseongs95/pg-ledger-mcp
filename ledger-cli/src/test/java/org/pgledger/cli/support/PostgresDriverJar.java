package org.pgledger.cli.support;

import java.nio.file.Path;

/**
 * The PostgreSQL JDBC driver as a jar file, for the tests of the driver registration ({@code driver-register}): the product
 * ships no driver, the operator registers the jar of the DBMS, and the tests register the driver of the test database. Gradle
 * resolves the jar and passes its path as the system property {@value #PROPERTY} (test only; the driver is on the test class
 * path through the test fixtures of the database module, the registration needs the file).
 */
public final class PostgresDriverJar {

    /** The system property that holds the path of the driver jar. */
    public static final String PROPERTY = "ledger.test.postgres-jar";
    /** The driver class of the PostgreSQL jar. */
    public static final String DRIVER_CLASS = "org.postgresql.Driver";
    /** A class of the jar that is not a {@code java.sql.Driver}. */
    public static final String NOT_A_DRIVER_CLASS = "org.postgresql.util.PGobject";
    /** A class that the jar does not hold. */
    public static final String MISSING_CLASS = "org.postgresql.NoSuchDriver";

    private PostgresDriverJar() {
    }

    /**
     * The path of the driver jar.
     *
     * @throws IllegalStateException naming the property when the build did not pass it
     */
    public static Path path() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the test property " + PROPERTY + " is not set (the root build.gradle.kts "
                    + "passes the path of the PostgreSQL driver jar to the test JVM)");
        }
        return Path.of(value);
    }
}
