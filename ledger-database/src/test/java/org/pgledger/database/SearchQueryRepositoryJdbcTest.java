package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.SearchQueryRepository;
import org.pgledger.database.repository.SearchQueryRepository.Filter;
import org.pgledger.database.repository.SearchQueryRepository.Hit;
import org.pgledger.database.repository.SearchQueryRepository.Kind;
import org.pgledger.database.schema.LedgerSchemaConfiguration;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.ProjectId;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;

/**
 * REPO-060 (F7), as a {@code @JdbcTest} slice (principle 7.2: a repository is tested with the JDBC slice, on the embedded
 * H2 that Boot makes, with no other bean than the repository and the schema): the names of the SQL parameters of the
 * search never come from a value. The schema is built by the real {@code db/V*.sql} files when the context starts; every
 * test runs in a transaction that Spring rolls back.
 */
@JdbcTest(properties = "ledger.schema.long-text-type=CLOB")
@ContextConfiguration(classes = SearchQueryRepositoryJdbcTest.Slice.class)
class SearchQueryRepositoryJdbcTest {

    /** The slice: the schema services, the repository under test, the clock and the client they need. */
    @Configuration(proxyBeanMethods = false)
    @Import({LedgerSchemaConfiguration.class, SearchQueryRepository.class})
    static class Slice {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        /** Builds the schema once, when the context starts (DDL is not part of the rolled back test transaction). */
        @Bean
        SmartInitializingSingleton schemaIsMigrated(SchemaMigrator migrator) {
            return migrator::migrate;
        }
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SearchQueryRepository search;

    private void defect(String id, String status) {
        jdbc.sql("INSERT INTO defect (id, project_id, title, category, severity, status, behavior, root_cause, version, "
                + "created_at, updated_at, created_by) VALUES (:id, 'P1', :id, 'c', 'P2', :status, 'b', 'r', 1, "
                + "TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 10:00:00', 'user:owner')")
                .param("id", id).param("status", status).update();
    }

    private List<String> found(Set<String> excludedStatuses) {
        Filter filter = new Filter(ProjectId.of("P1"), Set.of(Kind.DEFECT), List.of(), KeywordMatch.ANY,
                excludedStatuses, null, null);
        return search.page(filter, MaxRows.DEFAULT, null).rows().stream().map(Hit::id).sorted().toList();
    }

    @Test
    @DisplayName("REPO-060 excluded statuses are bind values named by position: a status that looks like SQL excludes nothing and breaks nothing")
    void anExcludedStatusNeverBecomesPartOfTheStatement() {
        jdbc.sql("INSERT INTO project (id, code, title, version, created_by) VALUES ('P1', 'P1', 'p', 1, 'user:owner')")
                .update();
        defect("P1-D-0001", "OPEN");
        defect("P1-D-0002", "SOURCE_FIXED");
        defect("P1-D-0003", "VERIFIED");

        assertThat(found(Set.of())).containsExactly("P1-D-0001", "P1-D-0002", "P1-D-0003");
        assertThat(found(Set.of("OPEN"))).containsExactly("P1-D-0002", "P1-D-0003");
        assertThat(found(Set.of("OPEN", "SOURCE_FIXED"))).containsExactly("P1-D-0003");
        // a value that is text of SQL, spaces and quotes: it is only a value that no status equals
        assertThat(found(Set.of("OPEN", "X') OR 1 = 1 --", "a b", "x'y"))).containsExactly("P1-D-0002", "P1-D-0003");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM defect").query(Long.class).single()).isEqualTo(3L);
    }
}
