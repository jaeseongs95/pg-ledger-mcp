package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.support.DdlModel;
import org.pgledger.database.support.SqlText;
import org.pgledger.database.support.SqlText.Banned;
import org.pgledger.database.support.SqlText.Group;

/** ANSI-001 .. ANSI-014, REPO-004 and ARC-012 (text part): static checks of every SQL file and SQL string. */
class AnsiRulesTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path REPOSITORY = MAIN_JAVA.resolve("org/pgledger/database/repository");
    private static final Path SCHEMA = Path.of("src", "main", "resources", "db");
    private static final Path TEMPLATES = Path.of("src", "main", "resources", "db-templates");

    /** Reserved words of ANSI SQL, H2 and PostgreSQL that an identifier must not be (see the allow-list below). */
    private static final Set<String> RESERVED = Set.of("condition", "release", "user", "type", "value", "key", "order",
            "group", "table", "column", "select", "from", "where", "check", "default", "primary", "references",
            "unique", "index", "constraint", "all", "any", "as", "case", "end", "in", "is", "not", "null", "or", "and",
            "like", "limit", "offset", "window", "year", "month", "day", "hour", "minute", "second", "row", "rows",
            "true", "false", "to", "on", "using", "when", "with");
    /** SQL-standard words that PostgreSQL and H2 accept as column names and that the catalogue fixes (SCH-018). */
    private static final Set<String> ACCEPTED = Set.of("at", "scope");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern SQL_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> files(Path directory, String glob) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(p -> p.getFileName().toString().matches(glob)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SqlText schemaFile(Path file) {
        return new SqlText(file.getFileName().toString(), read(file).replace("${longtext}", "CLOB"));
    }

    /** All string literals of a Java source file, adjacent literals joined (the way the SQL text is built). */
    private static SqlText javaLiterals(Path file) {
        String merged = Pattern.compile("\"\\s*\\+\\s*\"").matcher(read(file)).replaceAll("");
        String all = SQL_LITERAL.matcher(merged).results().map(m -> m.group(1).replace("\\\\", "\\")
                .replace("\\\"", "\"")).collect(java.util.stream.Collectors.joining("\n"));
        return new SqlText(file.getFileName().toString(), all);
    }

    static Stream<SqlText> schemaFiles() {
        return files(SCHEMA, "V\\d+__.*\\.sql").stream().map(AnsiRulesTest::schemaFile);
    }

    static Stream<SqlText> schemaAndTemplateFiles() {
        return Stream.concat(schemaFiles(), files(TEMPLATES, ".*\\.sql").stream().map(AnsiRulesTest::schemaFile));
    }

    static Stream<SqlText> repositorySources() {
        return files(REPOSITORY, ".*\\.java").stream().map(AnsiRulesTest::javaLiterals);
    }

    /** Everything that holds SQL except the one class that reads the session clock for the UTC guard. */
    static Stream<SqlText> sqlWithoutTheGuard() {
        return Stream.concat(schemaFiles(), repositorySources().filter(s -> !s.name().equals("SessionClockRepository.java")));
    }

    static Stream<SqlText> sqlOfTheApplication() {
        return Stream.concat(schemaFiles(), repositorySources());
    }

    @ParameterizedTest
    @MethodSource("sqlOfTheApplication")
    @DisplayName("ANSI-001/002: no vendor syntax in repository SQL and schema files")
    void noVendorSyntax(SqlText sql) {
        assertThat(sql.findings(Group.VENDOR_SYNTAX)).as(sql.name()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("schemaAndTemplateFiles")
    @DisplayName("ANSI-003/012: schema files hold only tables, indexes, rows and grants")
    void onlyWhitelistedStatements(SqlText sql) {
        assertThat(sql.findings(Group.DDL_OBJECT, Group.SESSION_STATEMENT)).as(sql.name()).isEmpty();
        assertThat(Stream.of(sql.stripped().split(";")).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.split("\\s+")[0].toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet()))
                .isSubsetOf("CREATE", "INSERT", "GRANT");
        assertThat(Stream.of(sql.stripped().split(";")).map(String::strip).filter(s -> s.toUpperCase(Locale.ROOT)
                .startsWith("CREATE")).map(s -> s.split("\\s+")[1].toUpperCase(Locale.ROOT)).toList())
                .allMatch(kind -> kind.equals("TABLE") || kind.equals("INDEX") || kind.equals("UNIQUE"));
    }

    @ParameterizedTest
    @MethodSource("sqlWithoutTheGuard")
    @DisplayName("ANSI-014: no database-side time function or default in SQL (the UTC guard is the one exception)")
    void noDatabaseSideTime(SqlText sql) {
        assertThat(sql.findings(Group.TIME_FUNCTION)).as(sql.name()).isEmpty();
    }

    @Test
    @DisplayName("ANSI-014: the guard reads the session clock with exactly one statement")
    void theGuardIsTheOnlyReaderOfTheSessionClock() {
        assertThat(javaLiterals(REPOSITORY.resolve("SessionClockRepository.java")).raw())
                .contains("SELECT MAX(LOCALTIMESTAMP) FROM schema_history");
        assertThat(repositorySources().filter(s -> s.findings(Group.TIME_FUNCTION).size() > 0).map(SqlText::name))
                .containsExactly("SessionClockRepository.java");
    }

    @ParameterizedTest
    @MethodSource("schemaFiles")
    @DisplayName("ANSI-005: identifiers are lower case, at most 30 characters, unquoted and not reserved")
    void identifiers(SqlText sql) {
        List<String> names = DdlModel.parse(sql.stripped()).identifiers();
        assertThat(sql.raw()).doesNotContain("\"");
        assertThat(names).isNotEmpty().allMatch(n -> IDENTIFIER.matcher(n).matches() && n.length() <= 30);
        assertThat(names.stream().filter(n -> RESERVED.contains(n) && !ACCEPTED.contains(n))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("schemaFiles")
    @DisplayName("ANSI-006: only VARCHAR, CLOB, INTEGER, BIGINT, TIMESTAMP and DECIMAL columns")
    void columnTypes(SqlText sql) {
        assertThat(DdlModel.parse(sql.stripped()).tables().stream().flatMap(t -> t.columns().stream())
                .map(DdlModel.Column::type)).allMatch(type -> type.matches("VARCHAR\\(\\d+\\)|CLOB|INTEGER|BIGINT|"
                        + "TIMESTAMP|DECIMAL(\\(\\d+,\\d+\\))?"));
    }

    @ParameterizedTest
    @MethodSource("schemaFiles")
    @DisplayName("ANSI-007: every column of a key is NOT NULL and an index is a plain list of columns")
    void keys(SqlText sql) {
        DdlModel model = DdlModel.parse(sql.stripped());
        assertThat(model.tables().stream().flatMap(t -> t.constraints().stream()
                .filter(c -> c.kind().equals("PRIMARY KEY") || c.kind().equals("UNIQUE"))
                .flatMap(c -> c.columns().stream().filter(col -> t.columns().stream()
                        .noneMatch(column -> column.name().equals(col) && column.notNull())))))
                .as("nullable columns in keys").isEmpty();
        assertThat(model.indexes().stream().flatMap(i -> i.columns().stream())).allMatch(
                c -> IDENTIFIER.matcher(c).matches());
    }

    @ParameterizedTest
    @MethodSource("schemaFiles")
    @DisplayName("ANSI-008 / REPO-004: CHECK clauses use only lists, comparisons and NULL tests")
    void checks(SqlText sql) {
        Pattern call = Pattern.compile("(?i)\\b(?!AND\\b|OR\\b|NOT\\b|IN\\b|CHECK\\b)\\w+\\s*\\(");
        assertThat(DdlModel.parse(sql.stripped()).tables().stream().flatMap(t -> t.constraints().stream())
                .filter(c -> c.kind().equals("CHECK")).map(DdlModel.Constraint::body))
                .noneMatch(body -> call.matcher(body).find() || body.contains("~"));
    }

    @Test
    @DisplayName("ANSI-013: one set of files, no per-DBMS variant and no placeholder but the long text type")
    void oneTextForEveryDbms() {
        assertThat(files(SCHEMA, ".*").stream().map(p -> p.getFileName().toString()))
                .allMatch(name -> name.matches("V[1-9][0-9]*__[a-z0-9_]+\\.sql"));
        assertThat(schemaFiles().map(s -> s.raw().replace("${longtext}", "")))
                .noneMatch(text -> text.contains("${") || text.toLowerCase(Locale.ROOT).contains("dbms:"));
    }

    @ParameterizedTest
    @MethodSource("repositorySources")
    @DisplayName("ANSI-009: values are bind parameters, never pasted into the SQL text")
    void noValueIsPastedIntoSql(SqlText sql) {
        assertThat(read(REPOSITORY.resolve(sql.name()))).as(sql.name())
                .doesNotContainPattern("'\"\\s*\\+\\s*\\w");
    }

    @ParameterizedTest
    @MethodSource("repositorySources")
    @DisplayName("ARC-011 (text part): no statement updates or deletes history rows or the schema history")
    void noStatementChangesHistory(SqlText sql) {
        assertThat(sql.raw().toUpperCase(Locale.ROOT)).as(sql.name())
                .doesNotContainPattern("(UPDATE|DELETE\\s+FROM|TRUNCATE\\s+TABLE)\\s+(SCHEMA_)?HISTORY\\b");
    }

    @ParameterizedTest
    @MethodSource("sqlLiteralsOutsideTheRepositoryPackage")
    @DisplayName("ARC-012 (text part): SQL strings live only in the repository package")
    void sqlOnlyInTheRepositoryPackage(Path file) {
        assertThat(read(file)).doesNotContainPattern("\"\\s*(SELECT|INSERT INTO|UPDATE|DELETE FROM|CREATE TABLE)\\s");
    }

    static Stream<Path> sqlLiteralsOutsideTheRepositoryPackage() {
        return files(MAIN_JAVA, ".*\\.java").stream().filter(p -> !p.startsWith(REPOSITORY));
    }

    /** A banned construct, a statement that contains it, and the same statement written differently. */
    private record Corpus(Banned banned, String violation) {
        @Override
        public String toString() {
            return banned.name();
        }
    }

    static Stream<Corpus> corpus() {
        return Stream.of(
                new Corpus(Banned.LIMIT, "SELECT 1 FROM t LIMIT 5"),
                new Corpus(Banned.ROWNUM, "SELECT 1 FROM t WHERE ROWNUM < 3"),
                new Corpus(Banned.TOP, "SELECT TOP 3 x FROM t"),
                new Corpus(Banned.ILIKE, "SELECT 1 FROM t WHERE a ILIKE b"),
                new Corpus(Banned.REGEX_OPERATOR, "SELECT 1 FROM t WHERE a ~ b"),
                new Corpus(Banned.CAST_OPERATOR, "SELECT a::text FROM t"),
                new Corpus(Banned.RETURNING, "INSERT INTO t VALUES (1) RETURNING id"),
                new Corpus(Banned.ON_CONFLICT, "INSERT INTO t VALUES (1) ON CONFLICT DO NOTHING"),
                new Corpus(Banned.MERGE, "MERGE INTO t USING s ON t.a = s.a"),
                new Corpus(Banned.ON_DUPLICATE_KEY, "INSERT INTO t VALUES (1) ON DUPLICATE KEY UPDATE a = 1"),
                new Corpus(Banned.FOR_UPDATE_OR_SHARE, "SELECT 1 FROM t FOR UPDATE"),
                new Corpus(Banned.WITH_RECURSIVE, "WITH RECURSIVE r AS (SELECT 1 FROM t) SELECT * FROM r"),
                new Corpus(Banned.JSON_FUNCTION, "SELECT json_agg(a) FROM t"),
                new Corpus(Banned.ARRAY_FUNCTION, "SELECT unnest(a) FROM t"),
                new Corpus(Banned.NOW_FUNCTION, "SELECT now() FROM t"),
                new Corpus(Banned.UUID_FUNCTION, "SELECT gen_random_uuid() FROM t"),
                new Corpus(Banned.ADVISORY_LOCK, "SELECT pg_advisory_lock(1) FROM t"),
                new Corpus(Banned.SEQUENCE_CALL, "SELECT nextval(s) FROM t"),
                new Corpus(Banned.NULLS_NOT_DISTINCT, "CREATE UNIQUE INDEX i ON t (a) NULLS NOT DISTINCT"),
                new Corpus(Banned.ROW_VALUE_COMPARISON, "SELECT 1 FROM t WHERE (a, b) < (1, 2)"),
                new Corpus(Banned.PARTIAL_INDEX, "CREATE INDEX i ON t (a) WHERE a > 1"),
                new Corpus(Banned.TRIGGER, "CREATE TRIGGER x BEFORE UPDATE ON t"),
                new Corpus(Banned.PROCEDURE, "CREATE PROCEDURE p() BEGIN END"),
                new Corpus(Banned.FUNCTION, "CREATE FUNCTION f() RETURNS int"),
                new Corpus(Banned.VIEW, "CREATE VIEW v AS SELECT 1 FROM t"),
                new Corpus(Banned.SEQUENCE, "CREATE SEQUENCE s"),
                new Corpus(Banned.EXTENSION, "CREATE EXTENSION pgcrypto"),
                new Corpus(Banned.ROLE, "CREATE ROLE r"),
                new Corpus(Banned.IDENTITY, "CREATE TABLE t (id BIGINT GENERATED ALWAYS AS IDENTITY)"),
                new Corpus(Banned.DO_BLOCK, "DO $$ BEGIN END $$"),
                new Corpus(Banned.SET_STATEMENT, "SET search_path = x"),
                new Corpus(Banned.ALTER_SESSION, "ALTER SESSION SET x = 1"),
                new Corpus(Banned.PRAGMA, "PRAGMA foreign_keys = ON"),
                new Corpus(Banned.COMMENT_ON, "COMMENT ON TABLE t IS 'x'"),
                new Corpus(Banned.CURRENT_TIME_FUNCTION, "SELECT CURRENT_TIMESTAMP FROM t"));
    }

    @ParameterizedTest
    @MethodSource("corpus")
    @DisplayName("ANSI-010: the checker reports a real use, in any case and over several lines")
    void checkerFindsTheRealUse(Corpus sample) {
        Set<Group> everything = Set.of(Group.values());
        assertThat(new SqlText("x", sample.violation()).findings(everything)).contains(sample.banned());
        assertThat(new SqlText("x", sample.violation().toLowerCase(Locale.ROOT)).findings(everything))
                .contains(sample.banned());
        assertThat(new SqlText("x", sample.violation().replace(" ", "\n  ")).findings(everything))
                .contains(sample.banned());
    }

    @ParameterizedTest
    @MethodSource("corpus")
    @DisplayName("ANSI-010: the checker ignores the same text in a comment or a string literal")
    void checkerIgnoresCommentsAndLiterals(Corpus sample) {
        Set<Group> everything = Set.of(Group.values());
        String quoted = sample.violation().replace("'", "''");
        assertThat(new SqlText("x", "-- " + sample.violation() + "\nSELECT 1 FROM t").findings(everything)
                .contains(sample.banned())).isFalse();
        assertThat(new SqlText("x", "/* " + sample.violation() + " */ SELECT 1 FROM t").findings(everything)
                .contains(sample.banned())).isFalse();
        assertThat(new SqlText("x", "SELECT '" + quoted + "' FROM t").findings(everything)
                .contains(sample.banned())).isFalse();
    }

    @ParameterizedTest
    @MethodSource("cleanStatements")
    @DisplayName("ANSI-010: clean SQL has no finding (no false positive on identifiers that contain a banned word)")
    void cleanSqlHasNoFinding(String sql) {
        assertThat(new SqlText("x", sql).findings(Set.of(Group.values()))).isEmpty();
    }

    static Stream<String> cleanStatements() {
        return Stream.of("SELECT limit_value, stop_value FROM desktop WHERE topic = 'top'",
                "SELECT id FROM defect WHERE created_at < :c ORDER BY id",
                "UPDATE project SET next_defect_no = next_defect_no + 1 WHERE code = :c",
                "SELECT note FROM merge_log WHERE returning_flag = 'RETURNING'");
    }

    @ParameterizedTest
    @EnumSource(Banned.class)
    @DisplayName("ANSI-010: every ban has a sample in the corpus")
    void everyBanIsCovered(Banned banned) {
        assertThat(corpus().map(Corpus::banned)).contains(banned);
    }
}
