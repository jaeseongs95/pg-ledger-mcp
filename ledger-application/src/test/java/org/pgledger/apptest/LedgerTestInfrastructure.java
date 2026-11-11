package org.pgledger.apptest;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.gate.SignedAttestationVerifier;
import org.pgledger.application.support.LedgerTestFixtures;
import org.pgledger.application.support.RecordingDataSource;
import org.pgledger.application.support.TestClock;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the service tests add to the application under test: the injected UTC clock, the recording of the SQL
 * texts at the data source, and the schema, built by the real {@code db/V*.sql} files and the real migrator before
 * the first test. The database itself is the embedded H2 that Spring Boot configures from {@code spring.datasource.*}
 * (see {@link LedgerTestSettings}); there is no compatibility mode.
 *
 * <p>This class lives outside {@code org.pgledger.application} on purpose: the application scans its own package
 * for components, and a test configuration that it found by itself would be part of every context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LedgerTestInfrastructure {

    /** The long text type of the schema files on H2, the only per-DBMS difference of the DDL. */
    private static final String LONG_TEXT = "CLOB";

    /** The versions that the migration applied to the database of the context. */
    public record SchemaMigrated(List<Integer> versions) {
    }

    @Bean
    static BeanPostProcessor recordingDataSource() {
        return new RecordingDataSource.Installer();
    }

    @Bean
    TestClock clock() {
        return new TestClock(TestClock.START);
    }

    @Bean
    LedgerTestFixtures ledgerTestFixtures() {
        return new LedgerTestFixtures();
    }

    /** The key pairs of the test authors; the hook of the service tests (see {@link SigningAttestationVerifier}). */
    @Bean
    TestKeyRing testKeyRing() {
        return new TestKeyRing();
    }

    /**
     * Signs the plain authors of the service tests and hands the signed attestation to the real verifier. It is the
     * primary {@link AttestationVerifier} of the context; the real one stays a bean of its own type for the tests of
     * the verification.
     */
    @Bean
    @Primary
    SigningAttestationVerifier signingAttestationVerifier(SignedAttestationVerifier real, TestKeyRing keys, Clock clock,
            SessionKeyService sessionKeys, PlatformTransactionManager transactions) {
        return new SigningAttestationVerifier(real, keys, clock, sessionKeys, transactions);
    }

    @Bean
    SchemaMigrated schemaMigrated(DataSource dataSource, Clock clock) {
        return new SchemaMigrated(new SchemaMigrator(dataSource, new PathMatchingResourcePatternResolver(),
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, LONG_TEXT, true, null,
                        Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), clock).migrate());
    }
}
