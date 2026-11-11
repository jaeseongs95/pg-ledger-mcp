package org.pgledger.database.repository;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.value.Sha256;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The migration bookkeeping on one connection: the schema, the table {@code schema_history} (one row per attempt,
 * never updated or deleted) and its rows. Not a Spring bean: the migrator and the startup guard hand it the
 * connection they work on, so the schema chosen on that connection applies to every statement.
 */
public final class SchemaHistoryRepository {

    private static final Pattern SIMPLE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,29}");

    /** Result of one attempt to apply a version. */
    public enum Outcome {
        SUCCESS, FAILED
    }

    /** One row of schema_history. */
    public record Row(int version, String fileName, Sha256 sha256, Instant appliedAt, Outcome outcome) {
    }

    private static final String TABLE = "schema_history";

    private final Connection connection;
    private final JdbcClient jdbc;

    public SchemaHistoryRepository(Connection connection) {
        this.connection = connection;
        this.jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    /**
     * Makes {@code schema} the schema of the connection (JDBC {@code setSchema}), creating it first when asked
     * and missing. A blank name keeps the default schema of the account.
     */
    public static void useSchema(Connection connection, String schema, boolean create) {
        if (schema == null) {
            return;
        }
        try {
            String folded = fold(connection.getMetaData(), schema);
            if (create && !schemaExists(connection.getMetaData(), folded)) {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + requireSimpleName(schema));
                }
            }
            connection.setSchema(folded);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot use schema " + schema, e);
        }
    }

    /** The name as the DBMS stores an unquoted identifier (JDBC metadata tells the folding). */
    public static String fold(DatabaseMetaData metaData, String name) throws SQLException {
        if (metaData.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ROOT);
        }
        return metaData.storesLowerCaseIdentifiers() ? name.toLowerCase(Locale.ROOT) : name;
    }

    private static boolean schemaExists(DatabaseMetaData metaData, String foldedName) throws SQLException {
        try (ResultSet rs = metaData.getSchemas()) {
            while (rs.next()) {
                if (foldedName.equalsIgnoreCase(rs.getString("TABLE_SCHEM"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String requireSimpleName(String name) {
        if (!SIMPLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("schema name must be lower case letters, digits and _ (max 30): "
                    + name);
        }
        return name;
    }

    public boolean tableExists() {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String schema = connection.getSchema();
            try (ResultSet rs = metaData.getTables(connection.getCatalog(), schema, fold(metaData, TABLE),
                    new String[] {"TABLE"})) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot inspect the database", e);
        }
    }

    public void createTable() {
        jdbc.sql("CREATE TABLE schema_history ("
                + "version INTEGER NOT NULL, "
                + "file_name VARCHAR(200) NOT NULL, "
                + "sha256 VARCHAR(64) NOT NULL, "
                + "applied_at TIMESTAMP NOT NULL, "
                + "outcome VARCHAR(10) NOT NULL, "
                + "CONSTRAINT pk_schema_history PRIMARY KEY (version, applied_at), "
                + "CONSTRAINT ck_schema_history_outcome CHECK (outcome IN ('SUCCESS', 'FAILED')))").update();
    }

    /** All attempts, oldest first per version. */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>(jdbc.sql(
                "SELECT version, file_name, sha256, applied_at, outcome FROM schema_history ORDER BY version, applied_at")
                .query((rs, n) -> new Row(rs.getInt("version"), rs.getString("file_name"),
                        new Sha256(rs.getString("sha256")), UtcTime.read(rs, "applied_at"),
                        Outcome.valueOf(rs.getString("outcome"))))
                .list());
        return List.copyOf(rows);
    }

    public void append(Row row) {
        jdbc.sql("INSERT INTO schema_history (version, file_name, sha256, applied_at, outcome) "
                + "VALUES (:version, :file, :sha, :at, :outcome)")
                .param("version", row.version())
                .param("file", row.fileName())
                .param("sha", row.sha256().hex())
                .param("at", UtcTime.toDb(row.appliedAt()))
                .param("outcome", row.outcome().name())
                .update();
    }
}
