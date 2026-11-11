package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.Dbms;
import org.pgledger.database.support.LedgerDatabaseExtension;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SCH-013: the same schema files give the same logical schema on every database that is tested. */
@ExtendWith(LedgerDatabaseExtension.class)
class SchemaComparisonTest {

    /** A key constraint with its columns in key order: {@code table.name(a, b) PRIMARY KEY}. */
    private record KeyConstraint(String table, String name, String type, List<String> columns) {
    }

    /** What the files must give on every DBMS: tables, columns with their nullability, and the named keys. */
    private record LogicalSchema(Set<String> tables, Set<String> columns, Set<KeyConstraint> keys) {
    }

    private record ColumnRow(String table, String column, String nullable) {
    }

    private record KeyRow(String table, String name, String type, String column, int position) {
    }

    private static LogicalSchema migratedSchema(Databases databases, Dbms dbms) {
        try (HikariDataSource pool = databases.newDataSource(dbms)) {
            new SchemaMigrator(pool, new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(null, dbms.longText(), true, null), Clock.systemUTC()).migrate();
            return read(pool);
        }
    }

    private static LogicalSchema read(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String schema = jdbc.sql("SELECT LOWER(table_schema) FROM information_schema.tables "
                + "WHERE LOWER(table_name) = 'schema_history'").query(String.class).single();
        Set<String> tables = jdbc.sql("SELECT LOWER(table_name) FROM information_schema.tables "
                + "WHERE LOWER(table_schema) = :schema AND table_type = 'BASE TABLE'").param("schema", schema)
                .query(String.class).set();
        Set<String> columns = jdbc.sql("SELECT LOWER(table_name) AS t, LOWER(column_name) AS c, is_nullable AS n "
                + "FROM information_schema.columns WHERE LOWER(table_schema) = :schema").param("schema", schema)
                .query((rs, n) -> new ColumnRow(rs.getString("t"), rs.getString("c"), rs.getString("n")))
                .list().stream().map(c -> c.table() + "." + c.column() + (c.nullable().equals("YES") ? " NULL"
                        : " NOT NULL")).collect(Collectors.toSet());
        List<KeyRow> rows = jdbc.sql("SELECT LOWER(c.table_name) AS t, LOWER(c.constraint_name) AS n, "
                + "c.constraint_type AS y, LOWER(k.column_name) AS col, k.ordinal_position AS pos "
                + "FROM information_schema.table_constraints c JOIN information_schema.key_column_usage k "
                + "ON k.constraint_name = c.constraint_name AND k.table_name = c.table_name "
                + "AND k.table_schema = c.table_schema "
                + "WHERE LOWER(c.table_schema) = :schema AND c.constraint_type IN ('PRIMARY KEY', 'UNIQUE', "
                + "'FOREIGN KEY')").param("schema", schema)
                .query((rs, n) -> new KeyRow(rs.getString("t"), rs.getString("n"), rs.getString("y"),
                        rs.getString("col"), rs.getInt("pos"))).list();
        Set<KeyConstraint> keys = rows.stream().collect(Collectors.groupingBy(r -> r.table() + "/" + r.name() + "/"
                + r.type())).values().stream().map(group -> new KeyConstraint(group.getFirst().table(),
                        group.getFirst().name(), group.getFirst().type(), group.stream().sorted(Comparator
                                .comparingInt(KeyRow::position)).map(KeyRow::column).toList()))
                .collect(Collectors.toSet());
        return new LogicalSchema(tables, columns, keys);
    }

    @Test
    @DisplayName("SCH-013: PostgreSQL and H2 built from the same files have the same tables, columns, keys and nullability")
    void bothDatabasesHaveTheSameLogicalSchema(Databases databases) {
        LogicalSchema h2 = migratedSchema(databases, Dbms.H2);
        LogicalSchema postgres = migratedSchema(databases, Dbms.POSTGRESQL);
        assertThat(h2.tables()).hasSize(16).isEqualTo(postgres.tables());
        assertThat(h2.columns()).hasSizeGreaterThan(100).isEqualTo(postgres.columns());
        assertThat(h2.keys().stream().map(KeyConstraint::type).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("PRIMARY KEY", "UNIQUE", "FOREIGN KEY");
        assertThat(h2.keys()).isEqualTo(postgres.keys());
    }
}
