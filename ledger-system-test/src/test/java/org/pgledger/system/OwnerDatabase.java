package org.pgledger.system;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.pgledger.testing.database.TestDatabaseSettings;

/**
 * The owner account of the test database for the checks of the system test: <b>one connection at a time</b>, opened for one
 * call and closed at its end. The shared pool of {@code TestDatabase} (six connections) is not used: the server, the command
 * line tools and this class together stay far below the limit of 10 connections of an account. The password is read through
 * {@link TestDatabaseSettings} and goes to the driver only; when the settings have no password file the driver reads the
 * pass file of the user.
 */
final class OwnerDatabase {

    private final TestDatabaseSettings settings;

    OwnerDatabase(TestDatabaseSettings settings) {
        this.settings = settings;
    }

    /** One result row as column name (lower case) to text; a null column is a null value. */
    List<Map<String, String>> rows(String sql) {
        return withConnection(connection -> {
            List<Map<String, String>> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int column = 1; column <= columns; column++) {
                        row.put(result.getMetaData().getColumnLabel(column).toLowerCase(java.util.Locale.ROOT),
                                result.getString(column));
                    }
                    rows.add(row);
                }
            }
            return rows;
        });
    }

    /** The one value of the first column of a query that returns one row. */
    String value(String sql) {
        List<Map<String, String>> rows = rows(sql);
        if (rows.size() != 1) {
            throw new IllegalStateException("expected one row, got " + rows.size() + " for: " + sql);
        }
        return rows.getFirst().values().iterator().next();
    }

    /** Runs one statement of the DBMS that is not a query (a grant, a drop). */
    void execute(String sql) {
        withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    /** Whether a schema of this name exists. */
    boolean schemaExists(String schema) {
        return withConnection(connection -> {
            try (ResultSet found = connection.getMetaData().getSchemas(null, schema)) {
                while (found.next()) {
                    if (schema.equals(found.getString("TABLE_SCHEM"))) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T withConnection(Work<T> work) {
        Properties properties = new Properties();
        properties.setProperty("user", settings.user());
        settings.ownerPassword().ifPresent(password -> properties.setProperty("password", password));
        // the message of a failure names the URL and the account (a setting), never the password
        try (Connection connection = DriverManager.getConnection(settings.url(), properties)) {
            return work.run(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("the test database refused a check of the system test (account "
                    + settings.user() + ", " + settings.url() + "): " + failure.getMessage(), failure);
        }
    }
}
