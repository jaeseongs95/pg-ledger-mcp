package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.support.Databases;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * INS-007 .. INS-010: the grant template of the application account read as text (ANSI GRANT statements and nothing else,
 * every table of the schema files, never a write on the history), the account name (the one value that enters the SQL) and
 * the application of the template through {@link GrantApplier} on an H2 account (an H2 {@code CREATE USER} is a test fixture
 * only). That the account runs every path of the applications and cannot change the history is {@code DmlAccountTest}.
 */
class GrantTemplateTest {

    private static final Pattern STATEMENT = Pattern.compile(
            "GRANT ((?:SELECT|INSERT|UPDATE|DELETE)(?:, (?:SELECT|INSERT|UPDATE|DELETE))*) ON ([a-z_]+) TO \\$\\{dml_account\\}");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?m)^CREATE TABLE ([a-z_]+) \\(");

    private static String template() {
        try {
            return new ClassPathResource(GrantApplier.TEMPLATE, GrantTemplateTest.class.getClassLoader())
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The statements of the template without its comment lines, one string each. */
    private static List<String> statements() {
        List<String> kept = new ArrayList<>();
        for (String line : template().split("\\R")) {
            if (!line.strip().startsWith("--")) {
                kept.add(line);
            }
        }
        List<String> statements = new ArrayList<>();
        for (String statement : String.join("\n", kept).split(";")) {
            if (!statement.isBlank()) {
                statements.add(statement.strip());
            }
        }
        return statements;
    }

    /** The rights the template gives, per table. */
    private static Map<String, Set<String>> rights() {
        Map<String, Set<String>> rights = new LinkedHashMap<>();
        for (String statement : statements()) {
            Matcher matcher = STATEMENT.matcher(statement);
            assertThat(matcher.matches()).as(statement).isTrue();
            rights.computeIfAbsent(matcher.group(2), table -> new TreeSet<>())
                    .addAll(Arrays.asList(matcher.group(1).split(", ")));
        }
        return rights;
    }

    /** Every table of the schema files {@code db/V*.sql}. */
    private static Set<String> schemaTables() throws IOException {
        Set<String> tables = new TreeSet<>();
        for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath:db/V*.sql")) {
            Matcher matcher = CREATE_TABLE.matcher(file.getContentAsString(StandardCharsets.UTF_8));
            while (matcher.find()) {
                tables.add(matcher.group(1));
            }
        }
        // the history of the schema runner is created by the runner itself (SchemaMigrator), not by a schema file
        tables.add("schema_history");
        return tables;
    }

    private static SchemaProperties properties() {
        return new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true, null,
                Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT));
    }

    @Test
    @DisplayName("INS-007 the template holds only GRANT statements of SELECT, INSERT, UPDATE and DELETE on tables to the placeholder account")
    void ins007_theTemplateHoldsOnlyPlainGrants() {
        assertThat(statements()).isNotEmpty().allSatisfy(
                statement -> assertThat(STATEMENT.matcher(statement).matches()).as(statement).isTrue());
        // ${dml_account} is the only placeholder
        assertThat(template().replace("${dml_account}", "")).doesNotContain("${");
        assertThat(template().toUpperCase(Locale.ROOT)).doesNotContain("PUBLIC", "GRANT OPTION", "TRUNCATE", "REFERENCES",
                "TRIGGER", "ALL PRIVILEGES");
    }

    @Test
    @DisplayName("INS-008 every table of the schema files is granted by the template, it names no other table, and nobody can update or delete the history")
    void ins008_everyTableIsConsciouslyGranted() throws IOException {
        Map<String, Set<String>> rights = rights();

        assertThat(rights.keySet()).containsExactlyInAnyOrderElementsOf(schemaTables());
        assertThat(rights.get("history")).doesNotContain("UPDATE", "DELETE");
        assertThat(rights.get("schema_history")).containsExactly("SELECT");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "a'b", "a b", "a;b", "a-b", "a.b", "1abc", "a\"b", "a\nb", "ünicode",
            "abcdefghijklmnopqrstuvwxyz01234" })
    @DisplayName("INS-009 a text that is not a plain account name is refused before any statement runs")
    void ins009_anInvalidAccountNameIsRefused(String name) {
        assertThat(GrantApplier.isAccountName(name)).isFalse();
        // the data source is never touched: a refusal comes before the template is read
        DataSource untouched = null;
        GrantApplier applier = new GrantApplier(untouched, properties());
        assertThatThrownBy(() -> applier.apply(name)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(name.isEmpty() ? "\0" : name);
    }

    @ParameterizedTest
    @ValueSource(strings = { "a", "APP_ACCOUNT", "ledger_app", "A1_b2", "abcdefghijklmnopqrstuvwxyz0123" })
    @DisplayName("INS-009 a plain identifier is an account name")
    void ins009_aPlainIdentifierIsAnAccountName(String name) {
        assertThat(GrantApplier.isAccountName(name)).isTrue();
    }

    @Test
    @DisplayName("INS-009 a missing name is not an account name")
    void ins009_nullIsNotAnAccountName() {
        assertThat(GrantApplier.isAccountName(null)).isFalse();
    }

    @Test
    @DisplayName("INS-010 applying the template a second time changes nothing; an account that does not exist is an InfrastructureException SCHEMA_GRANT_FAILED with its cause and no SQL in it")
    void ins010_theGrantsCanBeAppliedAgain() throws SQLException {
        String url = new Databases().newUrl();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        try (HikariDataSource owner = new HikariDataSource(config)) {
            new SchemaMigrator(owner, new PathMatchingResourcePatternResolver(), properties(), Clock.systemUTC()).migrate();
            String account = "APP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            try (Connection c = owner.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE USER " + account + " PASSWORD 'pw-for-the-test'");
            }
            GrantApplier applier = new GrantApplier(owner, properties());

            applier.apply(account);
            applier.apply(account);

            HikariConfig accountConfig = new HikariConfig();
            accountConfig.setJdbcUrl(url.substring(0, url.indexOf(';')));
            accountConfig.setUsername(account);
            accountConfig.setPassword("pw-for-the-test");
            accountConfig.setMaximumPoolSize(1);
            try (HikariDataSource app = new HikariDataSource(accountConfig); Connection c = app.getConnection();
                    Statement s = c.createStatement()) {
                s.execute("SELECT COUNT(*) FROM defect");
            }
            assertThatThrownBy(() -> applier.apply("NO_SUCH_ACCOUNT"))
                    .isInstanceOfSatisfying(InfrastructureException.class, e -> {
                        assertThat(e.code()).isEqualTo(InfrastructureCode.SCHEMA_GRANT_FAILED);
                        assertThat(e.args()).as("no SQL and no account in the failure").isEmpty();
                    }).hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
}
