package org.pgledger.database.driver;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A driver instance loaded from the registered jar, with the class loader that holds it. The classes come from a
 * private copy of the jar that {@link DriverLoader} made (the registered file is not held open, so it can be deleted or
 * replaced at any time, also on Windows, without changing what runs). Closing the driver closes the class loader and
 * then deletes the private copy. The driver is never registered with {@code java.sql.DriverManager} by this code.
 */
public final class RegisteredDriver implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RegisteredDriver.class);
    private static final Pattern PASSWORD_PARAMETER = Pattern.compile("(?i)((?:password|pwd)=)[^&;]*");
    private static final Pattern USER_INFO = Pattern.compile("(//[^/:@]+:)[^@/]*@");

    private final Driver driver;
    private final URLClassLoader loader;
    private final Path privateCopy;

    RegisteredDriver(Driver driver, URLClassLoader loader, Path privateCopy) {
        this.driver = driver;
        this.loader = loader;
        this.privateCopy = privateCopy;
    }

    public Driver driver() {
        return driver;
    }

    /** Fails with DRIVER_INVALID unless the driver accepts {@code url}; the URL in the message has no password. */
    public void requireAccepts(String url) {
        try {
            if (url != null && driver.acceptsURL(url)) {
                return;
            }
        } catch (SQLException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("the driver cannot read the JDBC URL " + sanitize(url)), e);
        }
        throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                List.of("the driver does not accept the JDBC URL " + sanitize(url)));
    }

    /** The URL without any password. */
    public static String sanitize(String url) {
        if (url == null) {
            return "(none)";
        }
        String withoutParameter = PASSWORD_PARAMETER.matcher(url).replaceAll("$1***");
        return USER_INFO.matcher(withoutParameter).replaceAll("$1***@");
    }

    @Override
    public void close() {
        try {
            loader.close();
        } catch (IOException e) {
            LOG.warn("cannot close the driver class loader", e);
        }
        DriverLoader.deleteCopy(privateCopy);
    }
}
