package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.database.schema.GrantApplier.Profile;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * INS-007 .. INS-009: the grant templates read as text (they hold ANSI GRANT statements and nothing else, they cover every
 * table of the schema files, they never give a write on the history) and the account name, the one value that enters the
 * SQL.
 */
class GrantTemplateTest {

    private static final Pattern STATEMENT = Pattern.compile(
            "GRANT ((?:SELECT|INSERT|UPDATE|DELETE)(?:, (?:SELECT|INSERT|UPDATE|DELETE))*) ON ([a-z_]+) TO \\$\\{account\\}");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?m)^CREATE TABLE ([a-z_]+) \\(");

    private static String read(String resource) {
        try {
            return new ClassPathResource(resource, GrantTemplateTest.class.getClassLoader())
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The statements of a template without its comment lines, one string each. */
    private static List<String> statements(Profile profile) {
        List<String> kept = new ArrayList<>();
        for (String line : read(profile.resource()).split("\\R")) {
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

    /** The rights a template gives, per table. */
    private static Map<String, Set<String>> rights(Profile profile) {
        Map<String, Set<String>> rights = new LinkedHashMap<>();
        for (String statement : statements(profile)) {
            Matcher matcher = STATEMENT.matcher(statement);
            assertThat(matcher.matches()).as("%s: %s", profile, statement).isTrue();
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

    @ParameterizedTest
    @EnumSource(Profile.class)
    @DisplayName("INS-007 a template holds only GRANT statements of SELECT, INSERT, UPDATE and DELETE on tables to the placeholder account")
    void ins007_aTemplateHoldsOnlyPlainGrants(Profile profile) {
        assertThat(statements(profile)).isNotEmpty().allSatisfy(statement -> assertThat(STATEMENT.matcher(statement).matches())
                .as(statement).isTrue());
        // ${account} is the only placeholder
        assertThat(read(profile.resource()).replace("${account}", "")).doesNotContain("${");
        assertThat(read(profile.resource()).toUpperCase(java.util.Locale.ROOT)).doesNotContain("PUBLIC", "GRANT OPTION",
                "TRUNCATE", "REFERENCES", "TRIGGER", "ALL PRIVILEGES");
    }

    @Test
    @DisplayName("INS-007 the single template of the application account is gone: the two profiles replace it")
    void ins007_theSingleTemplateIsGone() {
        assertThat(new ClassPathResource("db-templates/dml-grants.sql", GrantTemplateTest.class.getClassLoader()).exists())
                .isFalse();
    }

    @Test
    @DisplayName("INS-008 every table of the schema files is granted by at least one template, no template names another table, and nobody can update or delete the history")
    void ins008_everyTableIsConsciouslyGranted() throws IOException {
        Set<String> granted = new TreeSet<>();
        for (Profile profile : Profile.values()) {
            Map<String, Set<String>> rights = rights(profile);
            granted.addAll(rights.keySet());
            assertThat(rights.getOrDefault("history", Set.of())).as("history of %s", profile)
                    .doesNotContain("UPDATE", "DELETE");
            assertThat(rights.getOrDefault("schema_history", Set.of())).as("schema_history of %s", profile)
                    .containsExactly("SELECT");
        }
        assertThat(granted).containsExactlyInAnyOrderElementsOf(schemaTables());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "a'b", "a b", "a;b", "a-b", "a.b", "1abc", "a\"b", "a\nb", "ünicode",
            "abcdefghijklmnopqrstuvwxyz01234" })
    @DisplayName("INS-009 a text that is not a plain account name is refused before any statement runs")
    void ins009_anInvalidAccountNameIsRefused(String name) {
        assertThat(GrantApplier.isAccountName(name)).isFalse();
        // the data source is never touched: a refusal comes before the template is read
        DataSource untouched = null;
        GrantApplier applier = new GrantApplier(untouched, new SchemaProperties(SchemaProperties.DEFAULT_LOCATION,
                SchemaProperties.DEFAULT_LONG_TEXT_TYPE, true, null, Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)));
        assertThatThrownBy(() -> applier.apply(Profile.MCP, name)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(name.isEmpty() ? "\0" : name);
    }

    @ParameterizedTest
    @ValueSource(strings = { "a", "MCP_ACCOUNT", "ledger_admin", "A1_b2", "abcdefghijklmnopqrstuvwxyz0123" })
    @DisplayName("INS-009 a plain identifier is an account name")
    void ins009_aPlainIdentifierIsAnAccountName(String name) {
        assertThat(GrantApplier.isAccountName(name)).isTrue();
    }

    @Test
    @DisplayName("INS-009 a missing name is not an account name")
    void ins009_nullIsNotAnAccountName() {
        assertThat(GrantApplier.isAccountName(null)).isFalse();
    }
}
