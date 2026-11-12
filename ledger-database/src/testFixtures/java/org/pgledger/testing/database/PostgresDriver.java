package org.pgledger.testing.database;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The PostgreSQL JDBC driver as the sample driver of the tests that register a driver by its jar path (the loader of the
 * product reads a jar and a class name). The path of the jar is passed in by the build ({@value #JAR_PROPERTY}, resolved by
 * Gradle from a test-only configuration), never guessed from a cache directory.
 */
public final class PostgresDriver {

    /** The system property that carries the path of the driver jar. */
    public static final String JAR_PROPERTY = "ledger.test.postgres-jar";

    /** The driver class of the jar. */
    public static final String CLASS_NAME = "org.postgresql.Driver";

    /** A class of the same jar that is not a {@code java.sql.Driver} (the registration refuses it). */
    public static final String NOT_A_DRIVER_CLASS = "org.postgresql.util.PGobject";

    private PostgresDriver() {
    }

    /**
     * The path of the driver jar.
     *
     * @throws IllegalStateException naming the property when it is not set or the file does not exist
     */
    public static Path jar() {
        String path = System.getProperty(JAR_PROPERTY);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("the test property " + JAR_PROPERTY + " is not set (the root build.gradle.kts "
                    + "passes the path of the PostgreSQL driver jar to the test JVM)");
        }
        Path jar = Path.of(path);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("the jar of the test property " + JAR_PROPERTY + " does not exist: " + jar);
        }
        return jar;
    }

    /** A PostgreSQL URL that nothing listens on (port 1): the driver accepts it, a connection is refused. */
    public static String unreachableUrl(String database) {
        return "jdbc:postgresql://127.0.0.1:1/" + database;
    }
}
