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
import javax.sql.DataSource;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.value.Sha256;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.DatabaseMetaDataCallback;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

/**
 * The migration bookkeeping on a {@link DataSource}: the table {@code schema_history} (one row per attempt, never
 * updated or deleted) and its rows. Not a Spring bean: the migrator and the startup guard hand it the data source
 * they work on, which already applies the schema chosen for the run (see {@code SchemaDataSource}). Statements run
 * through {@link JdbcClient}, so inside a Spring transaction they share its connection.
 */
public final class SchemaHistoryRepository {

    /** Result of one attempt to apply a version. */
    public enum Outcome {
        SUCCESS, FAILED
    }

    /** One row of schema_history. */
    public record Row(int version, String fileName, Sha256 sha256, Instant appliedAt, Outcome outcome) {
    }

    private static final Pattern SIMPLE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,29}");
    private static final String TABLE = "schema_history";

    private final DataSource dataSource;
    private final JdbcClient jdbc;

    public SchemaHistoryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** The name as the DBMS stores an unquoted identifier (JDBC metadata tells the folding). */
    public static String fold(DatabaseMetaData metaData, String name) throws SQLException {
        if (metaData.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ROOT);
        }
        return metaData.storesLowerCaseIdentifiers() ? name.toLowerCase(Locale.ROOT) : name;
    }

    /** Reads the database metadata through Spring's {@link JdbcUtils}, which gets and releases the connection. */
    public static <T> T inspect(DataSource dataSource, DatabaseMetaDataCallback<T> callback) {
        try {
            return JdbcUtils.extractDatabaseMetaData(dataSource, callback);
        } catch (MetaDataAccessException e) {
            throw new IllegalStateException("cannot inspect the database", e);
        }
    }

    /** Creates the schema when it is missing (the schema name is an identifier, so it cannot be a bind value). */
    public static void createSchemaIfMissing(DataSource dataSource, String schema) {
        if (!SIMPLE_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("schema name must be lower case letters, digits and _ (max 30): "
                    + schema);
        }
        boolean exists = inspect(dataSource, metaData -> {
            String folded = fold(metaData, schema);
            try (ResultSet rs = metaData.getSchemas()) {
                while (rs.next()) {
                    if (folded.equalsIgnoreCase(rs.getString("TABLE_SCHEM"))) {
                        return true;
                    }
                }
            }
            return false;
        });
        if (!exists) {
            JdbcClient.create(dataSource).sql("CREATE SCHEMA " + schema).update();
        }
    }

    public boolean tableExists() {
        return inspect(dataSource, metaData -> {
            Connection connection = metaData.getConnection();
            try (ResultSet rs = metaData.getTables(connection.getCatalog(), connection.getSchema(),
                    fold(metaData, TABLE), new String[] {"TABLE"})) {
                return rs.next();
            }
        });
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
