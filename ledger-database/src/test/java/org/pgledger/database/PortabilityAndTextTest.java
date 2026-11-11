package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.database.repository.DefectQueryRepository;
import org.pgledger.database.repository.DefectQueryRepository.Filter;
import org.pgledger.database.repository.DefectQueryRepository.Order;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.MergeChainRepository;
import org.pgledger.database.repository.StatsRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.Dbms;
import org.pgledger.database.support.DbmsAware;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectCase;
import org.pgledger.domain.model.DefectFields;
import org.pgledger.domain.model.DefectTaskLink;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.Patch;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-031, REPO-032, REPO-033 and REPO-037. */
@DatabaseTest
class PortabilityAndTextTest implements DbmsAware {

    @Parameter
    Dbms dbms;

    private LedgerTestContext ctx;

    @Override
    public Dbms dbms() {
        return dbms;
    }

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
    }

    private static Dbms other(Dbms dbms) {
        return dbms == Dbms.H2 ? Dbms.POSTGRESQL : Dbms.H2;
    }

    @Test
    @DisplayName("REPO-037: every hand-written statement of the repositories is understood by both databases")
    void everyStatementPreparesOnTheOtherDatabaseToo() throws SQLException {
        Seed seed = new Seed(ctx);
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "portable", LedgerTestContext.START);
        Defect second = seed.defect(project, 2, "portable two", LedgerTestContext.START);
        var task = seed.task(project, 1, "portable task");
        IdAllocator ids = ctx.bean(IdAllocator.class);
        ctx.spy().clear();
        ctx.tx(() -> {
            ids.nextDefectNo(project.code());
            ids.nextTaskNo(project.code());
            ids.raiseDefectNo(project.code(), 5);
            ids.next(Counter.HISTORY);
            return null;
        });
        ctx.tx(() -> ctx.bean(LinkRepository.class).add(new DefectTaskLink(defect.id(), task.id(), Seed.ALICE)));
        ctx.tx(() -> ctx.bean(LinkRepository.class).remove(defect.id(), task.id()));
        ctx.tx(() -> ctx.bean(GrantStateRepository.class).markUsed(Seed.ALICE, project.id(), Scope.DEFECT,
                LedgerTestContext.START));
        ctx.tx(() -> ctx.bean(GrantStateRepository.class).revokeLive(Seed.ALICE, project.id(), Scope.DEFECT,
                LedgerTestContext.START));
        DefectQueryRepository queries = ctx.bean(DefectQueryRepository.class);
        queries.page(project.id(), new Filter(null, null, null, LedgerTestContext.START, LedgerTestContext.START),
                MaxRows.DEFAULT, Order.OLDEST_FIRST, new DefectQueryRepository.Position(LedgerTestContext.START,
                        defect.id()));
        queries.search(project.id(), List.of("a", "b"), KeywordMatch.ALL, MaxRows.DEFAULT);
        ctx.bean(StatsRepository.class).defectsByStatus(project.id());
        ctx.bean(StatsRepository.class).defectsBySeverity(project.id());
        ctx.bean(StatsRepository.class).attention(project.id());
        ctx.bean(MergeChainRepository.class).mergedInto(defect.id());
        assertThat(second).isNotNull();
        List<String> statements = ctx.spy().statements().stream().distinct().toList();
        assertThat(statements).hasSizeGreaterThan(10);
        try (LedgerTestContext otherDatabase = LedgerTestContext.create(other(dbms));
                Connection c = otherDatabase.pool().getConnection()) {
            List<String> refused = statements.stream().map(sql -> syntaxErrorOf(c, sql)).filter(e -> e != null)
                    .toList();
            assertThat(refused).as("statements the other database does not understand").isEmpty();
        }
    }

    /** The SQLState of a syntax or feature error (class 42) for a statement, or {@code null} when it is understood. */
    private static String syntaxErrorOf(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.getParameterMetaData();
            return null;
        } catch (SQLException e) {
            return e.getSQLState() != null && e.getSQLState().startsWith("42") ? sql + " -> " + e.getSQLState() : null;
        }
    }

    /** A defect text column: its length and how a defect with that long text is made from a project id. */
    private record DefectText(String column, int characters, BiFunction<String, String, DefectFields> fields) {
        @Override
        public String toString() {
            return column;
        }
    }

    private static DefectFields defectFields(String project, String title, String behavior, String cause) {
        return DefectFields.of(Patch.set(project), Patch.set(title), Patch.set("ARC"), Patch.set("P1"),
                Patch.set(behavior), Patch.set(cause), Patch.absent());
    }

    static Stream<DefectText> longDefectTexts() {
        return Stream.of(
                new DefectText("title", 300, (p, t) -> defectFields(p, t, "b", "c")),
                new DefectText("behavior", 4000, (p, t) -> defectFields(p, "t", t, "c")),
                new DefectText("root_cause", 4000, (p, t) -> defectFields(p, "t", "b", t)));
    }

    @ParameterizedTest
    @MethodSource("longDefectTexts")
    @DisplayName("REPO-031: long Korean defect texts are stored and read back without loss")
    void longDefectTextsAreExact(DefectText text) {
        Project project = new Seed(ctx).project();
        Defect saved = LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(DefectRepository.class).save(
                Defect.open(DefectId.of(project.code(), 1), text.fields().apply(project.id().value(),
                        "한".repeat(text.characters())), LedgerTestContext.START))));
        assertThat(ctx.bean(DefectRepository.class).findById(saved.id())).hasValue(saved);
    }

    /** A case text column: its length and how a case with that long text is made. */
    private record CaseText(String column, int characters, BiFunction<DefectId, String, DefectCase> made) {
        @Override
        public String toString() {
            return column;
        }
    }

    private DefectCase observation(DefectId defect, String observation, String evidence, String quote) {
        return DefectCase.observe(ctx.tx(() -> ctx.bean(IdAllocator.class).next(Counter.DEFECT_CASE)), defect,
                observation, evidence, quote, null, Seed.ALICE, LedgerTestContext.START);
    }

    Stream<CaseText> longCaseTexts() {
        return Stream.of(
                new CaseText("observation", 4000, (d, t) -> observation(d, t, null, null)),
                new CaseText("evidence", 2000, (d, t) -> observation(d, "o", t, null)),
                new CaseText("user_quote", 2000, (d, t) -> observation(d, "o", null, t)));
    }

    @ParameterizedTest
    @MethodSource("longCaseTexts")
    @DisplayName("REPO-031: long Korean case texts are stored and read back without loss")
    void longCaseTextsAreExact(CaseText text) {
        Seed seed = new Seed(ctx);
        Defect defect = seed.defect(seed.project(), 1, "case host", LedgerTestContext.START);
        DefectCase observed = ctx.tx(() -> ctx.bean(DefectCaseRepository.class).insert(text.made().apply(defect.id(),
                "한".repeat(text.characters()))));
        assertThat(ctx.bean(DefectCaseRepository.class).findById(observed.id())).hasValue(observed);
    }

    @Test
    @DisplayName("REPO-032: an id with a lower-case letter never reaches the database")
    void lowerCaseIdsAreRefusedBeforeAnySql() {
        ctx.spy().clear();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DefectId.of("abc-D-0001"))
                .isInstanceOf(org.pgledger.domain.error.ValidationException.class);
        assertThat(ctx.spy().statements()).isEmpty();
    }

    @Test
    @DisplayName("REPO-033: no text column of any table holds an empty string")
    void noColumnHoldsAnEmptyString() throws SQLException {
        Seed seed = new Seed(ctx);
        seed.defect(seed.project(), 1, "scan host", LedgerTestContext.START);
        JdbcClient jdbc = JdbcClient.create(ctx.pool());
        Set<String> textColumns = jdbc.sql("SELECT LOWER(table_name) || '.' || LOWER(column_name) FROM "
                + "information_schema.columns WHERE LOWER(data_type) IN ('character varying', 'text', "
                + "'character large object', 'clob', 'varchar') AND LOWER(table_schema) IN "
                + "(SELECT LOWER(table_schema) FROM information_schema.tables WHERE LOWER(table_name) = 'defect')")
                .query(String.class).set();
        assertThat(textColumns).contains("defect.title", "defect.behavior", "history.reason");
        Set<String> emptyOnes = textColumns.stream().filter(column -> jdbc.sql("SELECT COUNT(*) FROM "
                + column.substring(0, column.indexOf('.')) + " WHERE " + column.substring(column.indexOf('.') + 1)
                + " = ''").query(Integer.class).single() > 0).collect(Collectors.toSet());
        assertThat(emptyOnes).isEmpty();
    }
}
