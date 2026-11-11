package org.pgledger.database.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SchemaHistoryRepository.Outcome;
import org.pgledger.database.repository.SchemaHistoryRepository.Row;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * What an application does about the schema at startup: it only looks, it never changes anything. The schema is
 * up to date when every file has a history whose latest outcome is SUCCESS with the digest of the file, and the
 * history knows no version without a file. Anything else stops the application.
 */
public class SchemaVerifier {

    private final DataSource dataSource;
    private final ResourcePatternResolver resolver;
    private final SchemaProperties properties;

    public SchemaVerifier(DataSource dataSource, ResourcePatternResolver resolver, SchemaProperties properties) {
        this.dataSource = dataSource;
        this.resolver = resolver;
        this.properties = properties;
    }

    /** @throws SchemaMigrationException when the schema is not up to date */
    public void verify() {
        List<MigrationFile> files = MigrationFile.load(resolver, properties.location());
        MigrationFile.requireContiguous(files);
        try (Connection connection = dataSource.getConnection()) {
            SchemaHistoryRepository.useSchema(connection, properties.name(), false);
            SchemaHistoryRepository history = new SchemaHistoryRepository(connection);
            if (!history.tableExists()) {
                throw new SchemaMigrationException("the database has no schema history; run the migrate command");
            }
            List<Row> rows = history.rows();
            for (Row row : rows) {
                if (files.stream().noneMatch(f -> f.version() == row.version())) {
                    throw new SchemaMigrationException("V" + row.version() + " (" + row.fileName()
                            + ") was applied but has no schema file");
                }
            }
            for (MigrationFile file : files) {
                verifyVersion(file, rows.stream().filter(r -> r.version() == file.version()).toList());
            }
        } catch (SQLException e) {
            throw new SchemaMigrationException("cannot reach the database to verify the schema", e);
        }
    }

    private static void verifyVersion(MigrationFile file, List<Row> rows) {
        if (rows.isEmpty()) {
            throw new SchemaMigrationException("V" + file.version() + " (" + file.fileName()
                    + ") is pending; run the migrate command");
        }
        Row latest = rows.getLast();
        if (latest.outcome() == Outcome.FAILED) {
            throw new SchemaMigrationException("V" + file.version() + " (" + file.fileName()
                    + ") is recorded as FAILED; repair it and run the migrate command");
        }
        if (!latest.sha256().equals(file.digest())) {
            throw new SchemaMigrationException("digest mismatch for V" + file.version() + " (" + file.fileName()
                    + "): applied " + latest.sha256().hex() + " but the file is " + file.digest().hex());
        }
    }
}
