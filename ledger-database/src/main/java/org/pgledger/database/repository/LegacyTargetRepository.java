package org.pgledger.database.repository;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.rule.OwnerAction;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The write side of the legacy import, in the new schema: the statements that tell whether the schema already holds
 * ledger data, what its columns accept, that insert a copied row, and that read the rows back for the comparison. Every
 * method runs in the transaction of the caller (the import service), so the whole import is one transaction. The rows are
 * written through Spring's {@link JdbcClient}; the times are bound as UTC ({@link UtcTime}), like every other write of this
 * module. A row is a {@link LegacyRow} in the column order of {@link LegacyTable#targetColumns()}.
 */
@Repository
public class LegacyTargetRepository {

    /**
     * The tables whose rows are ledger data: every copied table except the common codes (a fresh installation seeds some),
     * and the table of the session keys, which only the new schema has. A schema in which none of them has a row, and in which
     * no import has run ({@link #EARLIER_IMPORT}), is one that only {@code migrate} and {@code setup} have prepared.
     */
    private static final List<String> DATA_TABLES = dataTables();

    /** The table of the owner's audit trail, which only the new schema has: it is counted only for the operation IMPORT. */
    private static final String OWNER_AUDIT = "owner_audit";

    /**
     * Where an earlier import shows: the audit row of the operation IMPORT. Every other operation of the owner that makes ledger
     * data leaves its row in a table that is counted anyway ({@code GRANT_ISSUED} and {@code GRANT_REVOKED} a grant,
     * {@code KEY_*} a session key, {@code PROJECT_ADDED} a project), and the ones that make none ({@code CODE_SET} of the seed that
     * {@code setup} adds, {@code DRIVER_REGISTERED}) must not stop the import: the order of the installation is the install
     * (which runs {@code setup}) and then the import.
     */
    private static final String EARLIER_IMPORT = "SELECT COUNT(*) FROM " + OWNER_AUDIT + " WHERE operation = ?";

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    public LegacyTargetRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    private static List<String> dataTables() {
        List<String> names = new ArrayList<>();
        for (LegacyTable table : LegacyTable.values()) {
            if (!table.targetMayHoldOtherRows()) {
                names.add(table.targetName());
            }
        }
        names.add("session_key");
        return List.copyOf(names);
    }

    /**
     * The tables of the new schema that already hold ledger data, in the order of the copy, and {@code owner_audit} last when
     * an earlier import left its row. Empty on a schema that only {@code migrate} and {@code setup} have prepared (the seed codes
     * of {@code common_code} and the audit rows of the operator actions that make no ledger data do not count).
     */
    public List<String> tablesWithData() {
        List<String> found = new ArrayList<>();
        for (String table : DATA_TABLES) {
            Long rows = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
            if (rows != null && rows > 0) {
                found.add(table);
            }
        }
        Long imports = jdbc.sql(EARLIER_IMPORT).param(OwnerAction.IMPORT.name()).query(Long.class).single();
        if (imports != null && imports > 0) {
            found.add(OWNER_AUDIT);
        }
        return found;
    }

    /**
     * What the database says about the columns of the table: whether a column accepts NULL and how long a text column is.
     * Read from the JDBC metadata, so the answer is the schema's own and cannot drift from the schema files.
     */
    public Map<String, ColumnRule> columnRules(LegacyTable table) {
        return SchemaHistoryRepository.inspect(dataSource, metaData -> {
            Map<String, ColumnRule> rules = new HashMap<>();
            Connection connection = metaData.getConnection();
            try (ResultSet columns = metaData.getColumns(connection.getCatalog(), connection.getSchema(),
                    SchemaHistoryRepository.fold(metaData, table.targetName()), null)) {
                while (columns.next()) {
                    boolean text = columns.getInt("DATA_TYPE") == Types.VARCHAR
                            || columns.getInt("DATA_TYPE") == Types.CHAR;
                    rules.put(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                            new ColumnRule(columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                    text ? columns.getInt("COLUMN_SIZE") : 0));
                }
            }
            return Map.copyOf(rules);
        });
    }

    /** Inserts one row of the table (a time value is stored as UTC). */
    public void insert(LegacyTable table, LegacyRow row) {
        bind(jdbc.sql(table.targetInsert()), table.targetColumns(), row).update();
    }

    /**
     * Sets a common code: the row of the same key (a code that a fresh installation seeds) is overwritten with the legacy
     * values, any other code is inserted.
     */
    public void putCommonCode(LegacyRow row) {
        List<LegacyColumn> columns = LegacyTable.COMMON_CODE.targetColumns();
        List<LegacyColumn> values = new ArrayList<>(columns.subList(2, columns.size()));
        List<LegacyColumn> keys = columns.subList(0, 2);
        StringBuilder sql = new StringBuilder("UPDATE common_code SET ");
        for (int i = 0; i < values.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(values.get(i).name()).append(" = ?");
        }
        sql.append(" WHERE ").append(keys.get(0).name()).append(" = ? AND ").append(keys.get(1).name()).append(" = ?");
        List<LegacyColumn> order = new ArrayList<>(values);
        order.addAll(keys);
        if (bind(jdbc.sql(sql.toString()), order, row).update() == 0) {
            insert(LegacyTable.COMMON_CODE, row);
        }
    }

    /** Calls the consumer with every row of the table, one row at a time, in no particular order. */
    public void scan(LegacyTable table, Consumer<LegacyRow> consumer) {
        List<LegacyColumn> columns = table.targetColumns();
        jdbc.sql(table.targetSelect()).query((RowCallbackHandler) rs -> consumer.accept(LegacyRow.ofTarget(rs, columns)));
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, List<LegacyColumn> columns,
            LegacyRow row) {
        for (int i = 0; i < columns.size(); i++) {
            LegacyColumn column = columns.get(i);
            Object value = row.get(column.name());
            if (value == null) {
                statement.param(i + 1, null, column.kind().sqlType());
            } else if (value instanceof Instant instant) {
                statement.param(i + 1, UtcTime.toDb(instant));
            } else {
                statement.param(i + 1, value);
            }
        }
        return statement;
    }
}
