package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.jdbc.LedgerJdbcConfiguration;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.OwnerAuditRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.schema.LedgerSchemaConfiguration;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.OwnerAuditEntry;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.RequestId;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;

/**
 * REPO-061 (F10), as a {@code @DataJdbcTest} slice (principle 7.2: a Spring Data JDBC repository is tested with the Data
 * JDBC slice on the embedded H2): the three records whose column names are not the names of their components
 * ({@code at} is stored in {@code occurred_at}, {@code scope} in {@code grant_scope}, because AT and SCOPE are reserved
 * words of SQL:2003) are written and read through their repositories, and the text of the columns is what the schema files
 * say. The schema is built by the real {@code db/V*.sql} files when the context starts.
 */
// Boot's pooled DataSource on an embedded H2 (Replace.NONE): see SearchQueryRepositoryJdbcTest for the reason.
@DataJdbcTest(properties = {"ledger.schema.long-text-type=CLOB",
        "spring.datasource.url=jdbc:h2:mem:w2mappingslice;DB_CLOSE_DELAY=-1"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ReservedWordColumnsDataJdbcTest.Slice.class)
class ReservedWordColumnsDataJdbcTest {

    private static final Instant WHEN = Instant.parse("2026-11-05T10:00:00.123456Z");

    /** The slice: the ledger's Spring Data JDBC configuration, the schema services, and the clock and client they need. */
    @Configuration(proxyBeanMethods = false)
    @Import({LedgerJdbcConfiguration.class, LedgerSchemaConfiguration.class})
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
    private SessionGrantRepository grants;

    @Autowired
    private HistoryRepository history;

    @Autowired
    private OwnerAuditRepository audit;

    @Test
    @DisplayName("REPO-061 a grant keeps its scope in grant_scope, a history row and an audit row their time in occurred_at")
    void theRenamedColumnsAreWrittenAndRead() {
        jdbc.sql("INSERT INTO project (id, code, title, version, created_by) VALUES ('P1', 'P1', 'p', 1, 'user:owner')")
                .update();

        grants.insert(SessionGrant.issue(1L, AuthorId.of("claude:a"), ProjectId.of("P1"), Scope.FIX, AuthorId.OWNER,
                WHEN, null, null));
        history.insert(new HistoryEntry(1L, WHEN, AuthorId.of("claude:a"), "ledger_author", ActorAssurance.DECLARED,
                HistoryEntity.DEFECT, "P1-D-0001", HistoryOp.CREATE, null, null, JsonDocument.EMPTY_OBJECT, null,
                "a reason of enough length", null, null, null, new RequestId(UUID.randomUUID()), null));
        audit.insert(new OwnerAuditEntry(1L, WHEN, "token:owner", OwnerAction.GRANT_ISSUED, "claude:a|P1", null));

        assertThat(jdbc.sql("SELECT grant_scope FROM session_grant WHERE id = 1").query(String.class).single())
                .isEqualTo("FIX");
        assertThat(grants.findById(1L)).get().extracting(SessionGrant::scope).isEqualTo(Scope.FIX);
        assertThat(history.findById(1L)).get().extracting(HistoryEntry::at).isEqualTo(WHEN);
        assertThat(audit.findBySubjectOrderById("claude:a|P1")).extracting(OwnerAuditEntry::at).containsExactly(WHEN);
        assertThat(jdbc.sql("SELECT occurred_at FROM history WHERE id = 1").query(java.time.LocalDateTime.class)
                .single()).hasToString("2026-11-05T10:00:00.123456");
        assertThat(jdbc.sql("SELECT occurred_at FROM owner_audit WHERE id = 1").query(java.time.LocalDateTime.class)
                .single()).hasToString("2026-11-05T10:00:00.123456");
    }
}
