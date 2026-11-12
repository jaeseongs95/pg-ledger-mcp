package org.pgledger.database.schema;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SchemaHistoryRepository.Outcome;
import org.pgledger.database.repository.SchemaHistoryRepository.Row;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the ANSI schema files {@code V{n}__*.sql} in order with Spring's {@link ResourceDatabasePopulator} and
 * records every attempt as a row of {@code schema_history} (rows are only appended). It is a service for the
 * operator's {@code migrate} command; the applications only verify (see {@link SchemaVerifier}).
 *
 * <ul>
 * <li>A version is applied once. Its file's SHA-256 is recorded; if the file changes afterwards, the digest
 *     mismatch is recorded as a FAILED attempt and migration stops.
 * <li>A file that fails is recorded as FAILED and migration stops; the next run retries that version and appends
 *     another row. The latest outcome of every version must be SUCCESS before the next version is applied. On a
 *     DBMS with transactional DDL a failed file is rolled back as a whole; on others the part that ran stays and
 *     the operator repairs it before retrying.
 * <li>Gaps, files applied out of order and files that are missing are refused.
 * </ul>
 *
 * <p>What the history says about a file is {@link SchemaHistory}, the same judgement the verifier uses. Failures are an
 * {@link InfrastructureException} with a {@code SCHEMA_*} code.
 */
@Component
public class SchemaMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrator.class);
    /**
     * The one place where the DDL differs between DBMSs: the type of a long text ({@code TEXT}, {@code CLOB}, ...) is not
     * the same in ANSI SQL on every DBMS, so the schema files write this placeholder and the migrator replaces it with
     * {@code ledger.schema.long-text-type} before the file runs. It is a plain text replacement of the file, made before
     * the digest of the applied text is judged (the digest is of the file, so it does not depend on the setting).
     */
    private static final String PLACEHOLDER = "${longtext}";
    /**
     * How often the history is looked at while waiting for another migration process (see
     * {@link #appliedByAnotherProcess}). There is no event to wait for: the other process is a separate program, and the
     * only thing it leaves behind that this one can see is its row in {@code schema_history}. So the row is polled, at
     * this interval, until {@code ledger.schema.peer-wait} is over.
     */
    private static final long PEER_POLL_MILLIS = 50;

    private final DataSource dataSource;
    private final ResourcePatternResolver resolver;
    private final SchemaProperties properties;
    private final Clock clock;

    public SchemaMigrator(DataSource dataSource, ResourcePatternResolver resolver, SchemaProperties properties,
            Clock clock) {
        this.dataSource = dataSource;
        this.resolver = resolver;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Applies every pending version and returns the versions applied by this call (empty when up to date).
     *
     * @throws InfrastructureException one of the schema codes ({@code SCHEMA_*}) when the files and the history disagree,
     *         a file failed or the database cannot be reached
     */
    public List<Integer> migrate() {
        List<MigrationFile> files = MigrationFile.load(resolver, properties.location());
        MigrationFile.requireContiguous(files);
        try {
            if (properties.name() != null) {
                SchemaHistoryRepository.createSchemaIfMissing(dataSource, properties.name());
            }
            DataSource scoped = SchemaDataSource.of(dataSource, properties.name());
            // Not beans: the repository and the transaction manager belong to the data source scoped to the schema of
            // this run (ledger.schema.name), which is a setting and not a bean, so they are made here for that one
            // data source. Spring shares a connection per DataSource object, and the populator and the history row
            // must run on the same one.
            SchemaHistoryRepository repository = new SchemaHistoryRepository(scoped);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(scoped));
            ensureTable(repository);
            List<Integer> applied = new ArrayList<>();
            for (MigrationFile file : files) {
                SchemaHistory history = new SchemaHistory(repository.rows());
                history.requireFileForEveryVersion(files);
                if (!settle(repository, history, file) && apply(scoped, transaction, repository, file)) {
                    applied.add(file.version());
                }
            }
            LOG.info("schema migration finished: applied={} total={}", applied, files.size());
            return applied;
        } catch (CannotGetJdbcConnectionException e) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_DATABASE_UNREACHABLE, List.of(), e);
        }
    }

    private static void ensureTable(SchemaHistoryRepository repository) {
        if (repository.tableExists()) {
            return;
        }
        try {
            repository.createTable();
        } catch (DataAccessException e) {
            if (!repository.tableExists()) {
                throw new InfrastructureException(InfrastructureCode.SCHEMA_HISTORY_NOT_CREATED, List.of(), e);
            }
            LOG.info("schema_history was created by another migration process at the same time", e);
        }
    }

    /**
     * Looks at the history of one version. Returns true when it is applied and intact; false when it must be
     * applied (never succeeded, or only failed attempts so far); throws when it must stop.
     */
    private boolean settle(SchemaHistoryRepository repository, SchemaHistory history, MigrationFile file) {
        return switch (history.verdictOf(file)) {
            case PENDING, FAILED_ONLY -> {
                history.requireNothingLaterApplied(file);
                yield false;
            }
            case DIGEST_CHANGED -> {
                if (!history.latestFailureIsOf(file)) {
                    repository.append(new Row(file.version(), file.fileName(), file.digest(),
                            nextTime(history.attemptsOf(file.version())), Outcome.FAILED));
                }
                throw history.digestMismatch(file);
            }
            case LATEST_FAILED -> throw SchemaHistory.recordedFailed(file);
            case INTACT -> true;
        };
    }

    /**
     * Runs one file and its SUCCESS row in one transaction; true when applied by this call. A failure rolls the
     * transaction back; the FAILED row is written afterwards, outside it.
     */
    private boolean apply(DataSource scoped, TransactionTemplate transaction, SchemaHistoryRepository repository,
            MigrationFile file) {
        String sql = file.text().replace(PLACEHOLDER, properties.longTextType());
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(false, false, "UTF-8",
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        List<Row> before = new SchemaHistory(repository.rows()).attemptsOf(file.version());
        try {
            transaction.executeWithoutResult(status -> {
                DatabasePopulatorUtils.execute(populator, scoped);
                repository.append(new Row(file.version(), file.fileName(), file.digest(), nextTime(before),
                        Outcome.SUCCESS));
            });
            LOG.info("applied V{} {}", file.version(), file.fileName());
            return true;
        } catch (DataAccessException | TransactionException e) {
            return failed(repository, file, before, e);
        }
    }

    private boolean failed(SchemaHistoryRepository repository, MigrationFile file, List<Row> before,
            RuntimeException cause) {
        if (appliedByAnotherProcess(repository, file, before)) {
            LOG.info("V{} was applied by another migration process", file.version(), cause);
            return false;
        }
        List<Row> now = new SchemaHistory(repository.rows()).attemptsOf(file.version());
        repository.append(new Row(file.version(), file.fileName(), file.digest(), nextTime(now), Outcome.FAILED));
        throw new InfrastructureException(InfrastructureCode.SCHEMA_MIGRATION_FAILED,
                List.of(String.valueOf(file.version()), file.fileName()), cause);
    }

    /**
     * A file that fails may only have met another migration process that is applying the same version (a DBMS
     * that commits every DDL statement at once shows its half-done file as an error). The history decides: this
     * waits up to {@code peerWait} for a SUCCESS row of the same digest that was not there before. The wait is a
     * poll of the history ({@link #PEER_POLL_MILLIS}) because the other process is another program and gives no event.
     */
    private boolean appliedByAnotherProcess(SchemaHistoryRepository repository, MigrationFile file,
            List<Row> before) {
        long deadline = System.nanoTime() + properties.peerWait().toNanos();
        while (true) {
            List<Row> now = new SchemaHistory(repository.rows()).attemptsOf(file.version());
            if (now.size() > before.size() && now.stream()
                    .anyMatch(r -> r.outcome() == Outcome.SUCCESS && r.sha256().equals(file.digest()))) {
                return true;
            }
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            try {
                Thread.sleep(PEER_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InfrastructureException(InfrastructureCode.SCHEMA_MIGRATION_INTERRUPTED, List.of(), e);
            }
        }
    }

    /** Now (microseconds), but strictly after the previous attempt of the same version, which the key needs. */
    private Instant nextTime(List<Row> rowsOfVersion) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (rowsOfVersion.isEmpty()) {
            return now;
        }
        Instant last = rowsOfVersion.getLast().appliedAt();
        return now.isAfter(last) ? now : last.plus(1, ChronoUnit.MICROS);
    }
}
