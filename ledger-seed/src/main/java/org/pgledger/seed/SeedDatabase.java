package org.pgledger.seed;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The one place that opens the H2 file of the bundle. The driver is created here and asked to connect; it is not registered
 * with {@code java.sql.DriverManager}, so no other code of the process can reach it by a URL, and the product never has a
 * ledger database in H2 (the registration of a driver refuses H2, see {@code EmbeddedDatabaseRefusal}).
 */
final class SeedDatabase {

    /** The file ends with this; H2 is given the name without it. */
    private static final String SUFFIX = ".mv.db";
    private static final String SCHEME = "jdbc:h2:file:";

    private SeedDatabase() {
    }

    /**
     * @param file    the bundle file ({@code <name>.mv.db})
     * @param options H2 settings after the name, {@code ;}-separated, or empty
     */
    static Connection open(Path file, String options) throws SQLException {
        String name = file.toAbsolutePath().toString().replace('\\', '/');
        if (!name.endsWith(SUFFIX)) {
            throw new SQLException("the seed bundle file must end with " + SUFFIX + ": " + file);
        }
        String url = SCHEME + name.substring(0, name.length() - SUFFIX.length()) + (options.isEmpty() ? "" : ";" + options);
        Connection connection = new org.h2.Driver().connect(url, new Properties());
        if (connection == null) {
            throw new SQLException("the H2 driver does not accept the seed bundle URL");
        }
        return connection;
    }
}
