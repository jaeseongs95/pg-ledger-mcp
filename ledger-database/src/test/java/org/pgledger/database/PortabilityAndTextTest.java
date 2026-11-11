package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

/**
 * REPO-031, REPO-032 and REPO-033. REPO-037 (the statements are understood by a second DBMS) needs a second
 * database and moved to the test server stage with the decision of 2026-11-11 (H2 only), see TEST_CASES.md.
 */
@DatabaseTest
class PortabilityAndTextTest {

    private LedgerTestContext ctx;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
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

    /** The three text columns of a case. */
    private record CaseParts(String observation, String evidence, String quote) {
    }

    /** A case text column: its length and which text column carries the long text. */
    private record CaseText(String column, int characters, Function<String, CaseParts> parts) {
        @Override
        public String toString() {
            return column;
        }
    }

    static Stream<CaseText> longCaseTexts() {
        return Stream.of(
                new CaseText("observation", 4000, t -> new CaseParts(t, null, null)),
                new CaseText("evidence", 2000, t -> new CaseParts("o", t, null)),
                new CaseText("user_quote", 2000, t -> new CaseParts("o", null, t)));
    }

    @ParameterizedTest
    @MethodSource("longCaseTexts")
    @DisplayName("REPO-031: long Korean case texts are stored and read back without loss")
    void longCaseTextsAreExact(CaseText text) {
        Seed seed = new Seed(ctx);
        Defect defect = seed.defect(seed.project(), 1, "case host", LedgerTestContext.START);
        CaseParts parts = text.parts().apply("한".repeat(text.characters()));
        DefectCase observed = ctx.tx(() -> ctx.bean(DefectCaseRepository.class).insert(DefectCase.observe(
                ctx.bean(IdAllocator.class).next(Counter.DEFECT_CASE), defect.id(), parts.observation(),
                parts.evidence(), parts.quote(), null, Seed.ALICE, LedgerTestContext.START)));
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
