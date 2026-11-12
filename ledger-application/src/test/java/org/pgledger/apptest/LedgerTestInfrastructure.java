package org.pgledger.apptest;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.SessionKeyRepository;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.gate.SignedAttestationVerifier;
import org.pgledger.application.support.LedgerTestFixtures;
import org.pgledger.application.support.RecordingDataSource;
import org.pgledger.application.support.TestClock;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.testing.database.TestDataSourceConfiguration;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the service tests add to the application under test: the injected UTC clock, the recording of the SQL
 * texts at the data source, and the schema, built by the real {@code db/V*.sql} files and the real migrator before
 * the first test. The database is the PostgreSQL of the test PC: {@link TestDataSourceConfiguration} supplies the
 * {@link DataSource} (the shared pool, every connection in the schema of the context), and the schema is the one that
 * {@code TestSchemaInitializer} named with the product setting {@code ledger.schema.name}; the real migrator creates
 * it and the tables in it.
 *
 * <p>This class lives outside {@code org.pgledger.application} on purpose: the application scans its own package
 * for components, and a test configuration that it found by itself would be part of every context.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestDataSourceConfiguration.class)
public class LedgerTestInfrastructure {

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
            SessionKeyRepository keyRows, IdAllocator ids,
            SessionKeyService sessionKeys, PlatformTransactionManager transactions) {
        return new SigningAttestationVerifier(real, keys, clock, sessionKeys, keyRows, ids, transactions);
    }

    @Bean
    SchemaMigrated schemaMigrated(DataSource dataSource, Clock clock, Environment environment) {
        return new SchemaMigrated(new SchemaMigrator(dataSource, new PathMatchingResourcePatternResolver(),
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, SchemaProperties.DEFAULT_LONG_TEXT_TYPE, true,
                        environment.getRequiredProperty(TestSchemaInitializer.SCHEMA_PROPERTY),
                        Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), clock).migrate());
    }
}
