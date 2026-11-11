package org.pgledger.database.schema;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SchemaHistoryRepository.Outcome;
import org.pgledger.database.repository.SchemaHistoryRepository.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.dao.DataAccessException;

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
 */
public class SchemaMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrator.class);
    private static final String PLACEHOLDER = "${longtext}";

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

    /** Applies every pending version and returns the versions applied by this call (empty when up to date). */
    public List<Integer> migrate() {
        List<MigrationFile> files = MigrationFile.load(resolver, properties.location());
        MigrationFile.requireContiguous(files);
        try (Connection connection = dataSource.getConnection()) {
            SchemaHistoryRepository.useSchema(connection, properties.name(), true);
            SchemaHistoryRepository history = new SchemaHistoryRepository(connection);
            ensureTable(history);
            List<Integer> applied = new ArrayList<>();
            for (MigrationFile file : files) {
                List<Row> rows = history.rows();
                requireKnownVersions(rows, files);
                if (!settle(history, file, rows) && apply(connection, history, file)) {
                    applied.add(file.version());
                }
            }
            LOG.info("schema migration finished: applied={} total={}", applied, files.size());
            return applied;
        } catch (SQLException e) {
            throw new SchemaMigrationException("cannot reach the database for the schema migration", e);
        }
    }

    private static void ensureTable(SchemaHistoryRepository history) {
        if (history.tableExists()) {
            return;
        }
        try {
            history.createTable();
        } catch (DataAccessException e) {
            if (!history.tableExists()) {
                throw new SchemaMigrationException("cannot create schema_history", e);
            }
            // another migration process created it at the same time
        }
    }

    private static void requireKnownVersions(List<Row> rows, List<MigrationFile> files) {
        for (Row row : rows) {
            if (files.stream().noneMatch(f -> f.version() == row.version())) {
                throw new SchemaMigrationException("V" + row.version() + " (" + row.fileName()
                        + ") was applied but has no schema file");
            }
        }
    }

    /**
     * Looks at the history of one version. Returns true when it is applied and intact; false when it must be
     * applied (never succeeded, or only failed attempts so far); throws when it must stop.
     */
    private boolean settle(SchemaHistoryRepository history, MigrationFile file, List<Row> allRows) {
        List<Row> rows = allRows.stream().filter(r -> r.version() == file.version()).toList();
        Row lastSuccess = rows.reversed().stream().filter(r -> r.outcome() == Outcome.SUCCESS).findFirst()
                .orElse(null);
        if (lastSuccess == null) {
            requireNothingLaterApplied(allRows, file);
            return false;
        }
        Row latest = rows.getLast();
        if (!lastSuccess.sha256().equals(file.digest())) {
            if (latest.outcome() != Outcome.FAILED || !latest.sha256().equals(file.digest())) {
                history.append(new Row(file.version(), file.fileName(), file.digest(), nextTime(rows),
                        Outcome.FAILED));
            }
            throw new SchemaMigrationException("digest mismatch for V" + file.version() + " (" + file.fileName()
                    + "): applied " + lastSuccess.sha256().hex() + " but the file is " + file.digest().hex());
        }
        if (latest.outcome() == Outcome.FAILED) {
            throw new SchemaMigrationException("V" + file.version() + " (" + file.fileName()
                    + ") was applied but its latest recorded outcome is FAILED; repair the database manually");
        }
        return true;
    }

    private static void requireNothingLaterApplied(List<Row> rows, MigrationFile file) {
        for (Row row : rows) {
            if (row.version() > file.version() && row.outcome() == Outcome.SUCCESS) {
                throw new SchemaMigrationException("V" + file.version() + " (" + file.fileName()
                        + ") is not applied but V" + row.version() + " is: files must not be added out of order");
            }
        }
    }

    /** Runs one file and its history row in one transaction; true when applied by this call. */
    private boolean apply(Connection connection, SchemaHistoryRepository history, MigrationFile file) {
        String sql = file.text().replace(PLACEHOLDER, properties.longTextType());
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(false, false, "UTF-8",
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        List<Row> before = history.rows().stream().filter(r -> r.version() == file.version()).toList();
        try {
            connection.setAutoCommit(false);
            populator.populate(connection);
            history.append(new Row(file.version(), file.fileName(), file.digest(), nextTime(before),
                    Outcome.SUCCESS));
            connection.commit();
            LOG.info("applied V{} {}", file.version(), file.fileName());
            return true;
        } catch (SQLException | DataAccessException e) {
            return failed(connection, history, file, before, e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                LOG.warn("cannot restore auto-commit after V{}", file.version(), e);
            }
        }
    }

    private boolean failed(Connection connection, SchemaHistoryRepository history, MigrationFile file,
            List<Row> before, Exception cause) {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
        List<Row> now = history.rows().stream().filter(r -> r.version() == file.version()).toList();
        boolean appliedMeanwhile = now.size() > before.size() && now.stream()
                .anyMatch(r -> r.outcome() == Outcome.SUCCESS && r.sha256().equals(file.digest()));
        if (appliedMeanwhile) {
            LOG.info("V{} was applied by another migration process", file.version());
            return false;
        }
        history.append(new Row(file.version(), file.fileName(), file.digest(), nextTime(now), Outcome.FAILED));
        throw new SchemaMigrationException("migration V" + file.version() + " (" + file.fileName() + ") failed",
                cause);
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
