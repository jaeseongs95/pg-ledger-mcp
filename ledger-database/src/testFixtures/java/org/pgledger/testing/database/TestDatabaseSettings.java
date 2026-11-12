package org.pgledger.testing.database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Where the tests find their database (TEST_CASES.md 1.4a, 1.4b): the PostgreSQL of the test PC. The settings are
 * system properties of the test JVM that the root build passes in from Gradle properties; the two password file
 * properties carry the PATH of a one-line file, never a password. A password file is read here and nowhere else:
 * its content is only ever handed to a connection pool, and no message, log line or exception text holds it.
 *
 * @param url              JDBC URL of the test database ({@code ledger.test.jdbc-url})
 * @param user             the owner account of that database ({@code ledger.test.jdbc-user})
 * @param appUser          the least-privilege application account ({@code ledger.test.app-user})
 * @param ownerPasswordFile path of the password file of the owner, or {@code null}: then no password is set and the
 *                         PostgreSQL driver reads the pass file of the user ({@code ledger.test.jdbc-password-file})
 * @param appPasswordFile  the same for the application account ({@code ledger.test.app-password-file})
 */
public record TestDatabaseSettings(String url, String user, String appUser, String ownerPasswordFile,
        String appPasswordFile) {

    public static final String URL_PROPERTY = "ledger.test.jdbc-url";
    public static final String USER_PROPERTY = "ledger.test.jdbc-user";
    public static final String APP_USER_PROPERTY = "ledger.test.app-user";
    public static final String OWNER_PASSWORD_FILE_PROPERTY = "ledger.test.jdbc-password-file";
    public static final String APP_PASSWORD_FILE_PROPERTY = "ledger.test.app-password-file";

    /** The settings of the running test JVM. A missing property is a build error and fails with its name. */
    public static TestDatabaseSettings fromSystemProperties() {
        return from(System::getProperty);
    }

    /** The settings read through {@code properties} (the system properties, or a map of a test). */
    public static TestDatabaseSettings from(UnaryOperator<String> properties) {
        return new TestDatabaseSettings(required(properties, URL_PROPERTY), required(properties, USER_PROPERTY),
                required(properties, APP_USER_PROPERTY), optional(properties, OWNER_PASSWORD_FILE_PROPERTY),
                optional(properties, APP_PASSWORD_FILE_PROPERTY));
    }

    /** The password of the owner account; empty when no password file is set (the driver then uses the pass file of the user). */
    public Optional<String> ownerPassword() {
        return read(OWNER_PASSWORD_FILE_PROPERTY, ownerPasswordFile);
    }

    /** The password of the application account; empty when no password file is set. */
    public Optional<String> appPassword() {
        return read(APP_PASSWORD_FILE_PROPERTY, appPasswordFile);
    }

    /** Never prints a password file path's content; the paths are settings, the passwords are not here at all. */
    @Override
    public String toString() {
        return "TestDatabaseSettings[url=" + url + ", user=" + user + ", appUser=" + appUser + "]";
    }

    /**
     * The first line of the file at {@code path} (UTF-8, without its line break), or empty when {@code path} is null.
     *
     * @throws IllegalStateException naming the property and the path (never the content) when the file is missing,
     *         unreadable or empty
     */
    static Optional<String> read(String property, String path) {
        if (path == null) {
            return Optional.empty();
        }
        String text;
        try {
            text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("the password file of the test property " + property + " does not exist: "
                    + path, e);
        } catch (IOException e) {
            throw new UncheckedIOException("the password file of the test property " + property + " cannot be read: "
                    + path, e);
        }
        int end = text.indexOf('\n');
        String line = end < 0 ? text : text.substring(0, end);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.isEmpty()) {
            throw new IllegalStateException("the password file of the test property " + property + " is empty: "
                    + path);
        }
        return Optional.of(line);
    }

    private static String required(UnaryOperator<String> properties, String name) {
        String value = properties.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the test property " + name + " is not set (the root build.gradle.kts passes "
                    + "it to the test JVM)");
        }
        return value;
    }

    private static String optional(UnaryOperator<String> properties, String name) {
        String value = properties.apply(name);
        return value == null || value.isBlank() ? null : value;
    }
}
