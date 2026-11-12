package org.pgledger.database.schema;

import java.util.List;
import javax.sql.DataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

/**
 * What an application does about the schema at startup: it only looks, it never changes anything. The schema is
 * up to date when every file has a history whose latest outcome is SUCCESS with the digest of the file, and the
 * history knows no version without a file ({@link SchemaHistory} judges it). Anything else stops the application.
 */
@Component
public class SchemaVerifier {

    private final DataSource dataSource;
    private final ResourcePatternResolver resolver;
    private final SchemaProperties properties;

    public SchemaVerifier(DataSource dataSource, ResourcePatternResolver resolver, SchemaProperties properties) {
        this.dataSource = dataSource;
        this.resolver = resolver;
        this.properties = properties;
    }

    /**
     * @throws InfrastructureException one of the schema codes ({@code SCHEMA_*}) when the schema is not up to date
     */
    public void verify() {
        List<MigrationFile> files = MigrationFile.load(resolver, properties.location());
        MigrationFile.requireContiguous(files);
        try {
            SchemaHistoryRepository repository = new SchemaHistoryRepository(
                    SchemaDataSource.of(dataSource, properties.name()));
            if (!repository.tableExists()) {
                throw new InfrastructureException(InfrastructureCode.SCHEMA_HISTORY_MISSING, List.of());
            }
            SchemaHistory history = new SchemaHistory(repository.rows());
            history.requireFileForEveryVersion(files);
            for (MigrationFile file : files) {
                switch (history.verdictOf(file)) {
                    case INTACT -> {
                    }
                    case PENDING -> throw SchemaHistory.pending(file);
                    case FAILED_ONLY, LATEST_FAILED -> throw SchemaHistory.recordedFailed(file);
                    case DIGEST_CHANGED -> throw history.digestMismatch(file);
                }
            }
        } catch (CannotGetJdbcConnectionException e) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_DATABASE_UNREACHABLE, List.of(), e);
        }
    }
}
