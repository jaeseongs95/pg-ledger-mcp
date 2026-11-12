package org.pgledger.database.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.pgledger.database.driver.LegacySourcePool;
import org.pgledger.database.driver.LegacySourceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read side of the legacy import: the old schema {@code ledger}, on a {@link DataSource} of its own (the connection of
 * the legacy account; in production the same registered driver and DBMS as the new schema, in a test a second embedded
 * database). It only reads. Every connection it uses is read-only ({@link TransactionTemplate#setReadOnly}, which Spring
 * hands to {@code Connection.setReadOnly(true)}), so a DBMS that honours the JDBC hint refuses a write on it, and the whole
 * import reads one snapshot (isolation REPEATABLE READ), so the rows the validation pass saw are the rows the copy pass
 * and the comparison see. The statements are plain ANSI: a select list, a table of the legacy schema and an
 * {@code ORDER BY}; values that are JSON in the legacy schema are read as text, never converted by a function.
 *
 * <p>A component that only {@code LegacySourceConfiguration} makes, from the settings {@code ledger.import.source.*} (the
 * scan of this package leaves it out), and a test hands it a database it has filled. One repository and one transaction
 * manager are made per legacy data source, which is a setting and not a bean, so the transaction manager is made here.
 * Spring's programmatic transaction support
 * ({@link TransactionTemplate} over a {@link DataSourceTransactionManager}) is the standard tool for a transaction on a
 * second {@link DataSource}; it is described in the Spring Framework reference, "Programmatic Transaction Management"
 * (https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html), and the read-only and
 * isolation settings of a transaction definition in "Transaction Definition"
 * (https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html).
 */
@Component
public final class LegacySourceRepository {

    /** The legacy schema name is an identifier, so it cannot be a bind value: it is checked against this form. */
    private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,29}");

    /**
     * The steps of the legacy installer that the import needs: the schema itself and the three later migrations
     * ({@code defect_case}, {@code common_code}, {@code created_by}); a database older than the checkout {@code 9fb62fe}
     * lacks some of them.
     */
    private static final List<String> REQUIRED_STEPS = List.of("schema", "defect_case", "common_code", "created_by");

    private static final String MIGRATION_TABLE = "_migration";

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;
    private final String schema;

    /** The repository of the legacy database of the settings {@code ledger.import.source.*}. */
    @Autowired
    public LegacySourceRepository(LegacySourcePool pool, LegacySourceProperties properties) {
        this(pool.dataSource(), properties.schema());
    }

    /**
     * @param dataSource the connection of the legacy account
     * @param schema     the legacy schema (lower case letters, digits and {@code _}, at most 30)
     * @throws IllegalArgumentException when the schema is not an identifier of that form
     */
    public LegacySourceRepository(DataSource dataSource, String schema) {
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("the legacy schema must be lower case letters, digits and _ (max 30): "
                    + schema);
        }
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.schema = schema;
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** The legacy schema the statements read. */
    public String schema() {
        return schema;
    }

    /**
     * Runs the work in one read-only snapshot of the legacy database: every {@link #scan} inside it sees the same rows. The
     * snapshot ends when the work returns or throws; nothing is ever written to the legacy database.
     */
    public <T> T readSnapshot(Supplier<T> work) {
        return snapshot.execute(status -> work.get());
    }

    /**
     * The steps of the legacy installer that are missing (all of them when the schema has no {@code _migration} table), in
     * the order the installer applies them; empty when the database is complete enough to import.
     */
    public List<String> missingSteps() {
        if (!SchemaHistoryRepository.inspect(dataSource, metaData -> {
            try (ResultSet tables = metaData.getTables(null, SchemaHistoryRepository.fold(metaData, schema),
                    SchemaHistoryRepository.fold(metaData, MIGRATION_TABLE), new String[] {"TABLE"})) {
                return tables.next();
            }
        })) {
            return REQUIRED_STEPS;
        }
        Set<String> present = new HashSet<>(jdbc.sql("SELECT step FROM " + schema + "." + MIGRATION_TABLE)
                .query(String.class).list());
        List<String> missing = new ArrayList<>();
        for (String step : REQUIRED_STEPS) {
            if (!present.contains(step)) {
                missing.add(step);
            }
        }
        return missing;
    }

    /** Calls the consumer with every row of the legacy table, in key order, one row at a time. */
    public void scan(LegacyTable table, Consumer<LegacyRow> consumer) {
        List<LegacyColumn> columns = table.sourceColumns();
        jdbc.sql(table.sourceSelect(schema)).query((RowCallbackHandler) rs -> consumer.accept(
                LegacyRow.ofSource(rs, columns)));
    }
}
